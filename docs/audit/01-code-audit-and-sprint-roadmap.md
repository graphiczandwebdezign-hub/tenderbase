# TenderBase — Full Code Audit & Repair Roadmap

**Date:** 2026-08-31
**Scope:** Entire repository — Android app (10,188 LOC Kotlin/Compose), FastAPI backend (5,634 LOC Python), CI/CD, Docker/Render ops, build & release pipeline.
**Trigger:** Debug APK crashes on the onboarding screen when tapping Continue.
**Method:** 100% file-by-file read of the Android tree, backend read + full test-suite run, CI run-history cross-check, live API probe, crash-path trace analysis.

---

## 0. Executive summary

- **The code is in much better shape than a "crashes on first screen" report suggests.** Layering is clean (pure-Kotlin logic isolated and unit-tested), all network/DB work is correctly off the main thread, JSON parsing is defensive everywhere, error surfaces are humanized, and the backend's 113 tests pass green.
- **Statically, the onboarding *Continue* handler itself is sound** — the full call chain was traced (see §3). The defect is almost certainly (a) the onboarding **finish** navigation — which users typically also call "continue" — and/or (b) a device-specific runtime failure we cannot yet see, because:
- **The app has zero crash telemetry and zero UI tests.** A fatal crash in any Compose screen ships straight through a green CI, because CI only gates on JVM unit tests. That — not the crash itself — is the systemic defect to fix first.
- Three independently certain defects were found and are fix-on-sight: the **finish-navigation race** (§4, C1), **unconfigured Firebase Messaging** (§4, C3), and an **unproven R8/minified release path** (§4, H4).
- The repair plan (§6) is safety-first: **Sprint 0 adds eyes, Sprint 1 fixes what's certain, Sprint 2 locks the gate, Sprints 3–4 handle release/ops/UX debt.**

---

## 1. Build provenance — *which code your APK actually runs*

This turned out to be the first trap. The links you shared point at tag `apk-latest`, and the **tag is stale**:

| Item | Value |
|---|---|
| Git tag `apk-latest` points at | `c951101` — old View-based skeleton, **no onboarding code at all**, versionCode 1 ("1.0") |
| Release asset `TenderBase-debug.apk` | re-uploaded **2026-08-31 07:50 UTC** by CI run [`33369830183`](.) |
| CI run built commit | `a7d571b` — `origin/main` HEAD = *exactly the code audited here* (versionCode 3, "1.2") |

`softprops/action-gh-release` re-attaches the newest build to the same release/tag, so the APK is fresh while the tag and the source zip are ancient. **All analysis below targets the real, current code.** The confusing tag setup is itself a finding (M2).

---

## 2. Repository map

```
tenderbase/
├── android/                 # Native Android app (Kotlin, Jetpack Compose, Room, WorkManager)
│   ├── app/src/main/java/com/tenderbase/app/
│   │   ├── OnboardingActivity.kt            # launcher; hosts the crash site
│   │   ├── ui/screens/OnboardingScreen.kt   # 4-page pager flow
│   │   ├── ui/screens/…                     # TbFeed, TbMainScreens, TbOtherScreens, DetailScreen
│   │   ├── ui/vm/TbViewModels.kt            # 7 state machines, all coroutine-safe
│   │   ├── ui/components/…                  # design system (cards, chips, buttons, sheets)
│   │   ├── ApiClient.kt                     # HttpURLConnection + Dispatchers.IO, no networking lib
│   │   ├── TenderRepository.kt              # Room + SharedPreferences facade
│   │   └── …                                # utils: DateUtils, DeadlineStatus, Filters, …
│   └── app/src/test/…                       # 16 pure-JVM test classes (all green in CI)
├── app/                     # FastAPI backend (SQLAlchemy 2, Alembic, APScheduler, slowapi)
├── migrations/              # 5 Alembic migrations
├── tests/                   # 113 backend tests — ALL PASS (run during this audit)
├── .github/workflows/build-apk.yml   # CI: assembleDebug gated on testDebugUnitTest → release apk-latest
├── Dockerfile, docker-compose.yml, render.yaml
└── docs/
```

---

## 3. Crash forensics — onboarding Continue, traced end-to-end

### 3.1 What the button actually does

```
OnboardingScreen (4 pages: welcome → categories → provinces → alerts)
Continue (pages 0,1):  page 0 → repo.setSelectedCategories(...)  +  pager.animateScrollToPage(+1)
Continue (page 2):     saves categories + provinces              +  pager.animateScrollToPage(3)
Final page (3):        "Enable deadline alerts" → permission dialog → finishOnboarding()
                       "Maybe later" / "Skip"                     → finishOnboarding()
finishOnboarding():    setOnboarded(true) → startActivity(MainActivity) → finishAffinity()
```

### 3.2 Everything on the path, verified

| Element checked | Result |
|---|---|
| `repo.setSelectedCategories/Provinces` (SharedPreferences write) | Safe; set is copied before write |
| `pagerState.animateScrollToPage(n)` bounds (pageCount = 4, targets 1..3) | Safe; double-tap safe (MutatorMutex cancels & restarts) |
| Destination pages 1/2 (`ObPicker` → `TbChipFlow`/`FlowRow` → M3 `FilterChip`) | Sound; 13 chips composition OK |
| Page 3 (`ObNotifications`) | Sound |
| All 17 onboarding string resources | Present, well-formed, escapes correct |
| Icons (`Search`, `Star`, `CalendarMonth`, `Notifications`) | `material-icons-extended` dependency present |
| `TenderTaxonomy.displayName` | Total function, no throw paths |
| `rememberSaveable` restoration types (`ArrayList<String>`) | All Serializable → save/restore OK |
| Theme/dimens tokens used | All defined |
| Main-thread I/O in click path | None (prefs `.apply()` is async; Room instance creation is lazy) |
| Library versions (Kotlin 1.9.24 ↔ compiler 1.5.14 ↔ BOM 2024.09.03) | Consistent; CI build green incl. unit-test gate |
| minSdk 24 API-surface hazards (`java.time`, desugaring) | None found anywhere |

**Verdict:** the Continue handler *as written* should not crash on a healthy device. That leaves three realistic failure modes, ranked by probability:

1. **The user's "continue" is the *final* onboarding action** (Skip / Maybe later / Enable alerts). `finishOnboarding()` calls `startActivity(MainActivity)` **and then `finishAffinity()`** on the same task — a documented race that can tear down the just-launched `MainActivity`; the app vanishes to the launcher, indistinguishable from a crash. **This is a certain defect regardless** (C1 below).
2. **A device-specific runtime failure** (OEM ROM quirk, Play-Services-less device hitting the dormant Firebase graph, memory pressure during the pager animation) — *invisible without a stack trace*, because the app has no crash reporting (C2).
3. A genuine Compose/library-internal issue on the user's exact API level — same requirement: **get the trace first** (Sprint 0 exists precisely for this).

---

## 4. Findings ledger

### 🔴 Critical

| # | Finding | Location | Fix |
|---|---|---|---|
| **C1** | **Onboarding finish navigation race.** `startActivity()` + `finishAffinity()` can finish the just-started `MainActivity` (same task affinity). App appears to crash/close right after the final onboarding tap on affected devices/timings. | `OnboardingActivity.kt:52-56` | Launch `MainActivity` with `NEW_TASK \| CLEAR_TASK`, then plain `finish()`. Centralize in one `completeOnboarding()` helper used by all three exit paths. |
| **C2** | **No crash telemetry, no on-device tests.** No Crashlytics (google-services absent), no uncaught-exception handler, zero instrumented/Compose tests. CI gate = JVM unit tests only → any UI crash ships green. This is *why* the onboarding crash reached users and why we can't see its trace. | repo-wide | Sprint 0: lightweight file-based crash reporter + settings "Share diagnostics"; emulator UI-test job on CI. |
| **C3** | **Firebase Messaging shipped unconfigured.** `firebase-messaging` (BOM 33.1.2) + `TenderMessagingService` in the manifest, but no `com.google.gms.google-services` plugin and no `google-services.json`; backend `FCM_ENABLED=false`. Push is dead code today and a live crash class (`IllegalStateException: Default FirebaseApp is not initialized`) for any future caller, worst on no-Play-Services devices. | `build.gradle`, manifest, `TenderMessagingService.kt` | Decide in Sprint 0: **strip** (recommended short-term) or **complete** (plugin + json + server creds). |

### 🟠 High

| # | Finding | Location |
|---|---|---|
| **H1** | Onboarding keeps selection state in `rememberSaveable` inside the composable and builds `TenderRepository(context)` (Activity context, opens the Room singleton) *during composition*. Fragile across process death/recreation; untestable off-device. | `OnboardingScreen.kt:74-82` |
| **H2** | Notification permission is requested from **two owners**: onboarding page 3 *and* `MainActivity.maybeRequestNotificationPermission()` (its own `notif_permission_asked` flag). Possible immediate double system prompt on first launch of the feed; OEM behaviour varies. | `OnboardingActivity.kt:44-50`, `MainActivity.kt:149-163` |
| **H3** | SharedPreferences typed reads aren't upgrade-safe. All debug builds share one app id (`com.tenderbase.app.debug`); any build (past/future) that writes a different type under the same key crashes every reader with `ClassCastException`. | `TenderRepository.kt` (`getStringSet`/`getLong`/`getString` call sites) |
| **H4** | Minified release build is **never built or run anywhere**: `minifyEnabled true` + `shrinkResources true`, CI only ever runs `assembleDebug`. R8/Compose/Room issues would be discovered by end users. Also: release is signed with the *debug* key (acknowledged in a comment — must change before Play upload). | `app/build.gradle`, CI workflow |

### 🟡 Medium

| # | Finding | Location |
|---|---|---|
| **M1** | Same picker, two semantics: onboarding — empty ≡ "all selected" and *Select all* sets the full list; Preferences — tapping a chip when empty selects *everything except* that chip. Confusing state machine drift between two screens editing the same preference. | `OnboardingScreen.kt` vs `TbOtherScreens.kt:530-560` |
| **M2** | **Stale `apk-latest` tag** (§1): release asset continuously overwritten from `main` while the tag/source-zip stay pinned to the May skeleton. Anyone auditing or side-loading from the zip gets code that doesn't match the binary. | `.github/workflows/build-apk.yml` |
| **M3** | Free-tier Render cold starts (~50 s): first `fetchTenders` shows a long skeleton on fresh open. `readTimeout=70s` accommodates it, but first-run UX is fragile. | `ApiClient.kt`, `render.yaml` |
| **M4** | Read-only API key shipped in the client (deliberate, documented). Needs a rotation runbook + per-key rate-limit watch. Backend already rate-limits per key/IP — good. | `ApiClient.kt:20`, backend `slowapi` |
| **M5** | Toolchain aging: Kotlin 1.9.24 + compiler 1.5.14 + Compose 1.7.x; `material-icons-extended` full pack bloats the APK (21.9 MB debug). | `android/build.gradle` |

### 🟢 Low / notes

- **L1** `MainActivity.tabState` is an activity-field `MutableState` — works, but move to `rememberSaveable`/SavedStateHandle for recreation correctness.
- **L2** `DetailScreen` `!!` usages (5 total in repo) are all currently guarded — convert to `?.let` for future-proofing.
- **L3** `notif_permission_asked` is written *before* the user answers → deny-once means never-ask-again. Acceptable; document.
- **L4** WorkManager 12 h pre-cache + KEEP policy is fine; feed already refetches on cold start.
- **M2'** Compose `FilterChip`/`flow` usage verified against BOM-pinned M3 1.3.0 — no API misuse anywhere in the UI layer.

### ✅ What the audit found *healthy* (keep doing this)

- **Backend (🔒 + ✅):** 113/113 tests green; API-key auth with hashed storage, admin endpoints behind `X-Admin-Secret`, per-key/IP rate limiting, security headers, per-request correlation IDs, Alembic-managed schema migrations (5), ORM-only data access (no raw SQL), non-root Docker image, environment-only secrets, live API confirmed `healthy` (HTTP 200, version 1.0.0).
- **Android architecture:** pure-Kotlin, unit-tested logic split from UI (`Filters`, `DateUtils`, `DeadlineStatus`, `Dashboard`, `SearchQueue`, `RecentSearches`, `Changelog`, `BidPack` — 16 test classes); all network/DB on `Dispatchers.IO`/Room executors; humanized error taxonomy (`UserErrorKind`) with retry surfaces and offline cache; defensive JSON throughout (`opt*` only, zero force-unwraps); 48 dp touch targets, content descriptions, light+dark design tokens.
- **CI:** debug APK gated on unit tests; workflow_run history shows the failing-test gate actually working (two red runs blocked earlier today).

---

## 5. Risk picture

```
Likely user-facing crash cluster (confidence in parentheses):
 ├─ C1 finish-navigation race ............... HIGH — certain defect, matches symptom if
 │                                             "continue" = last onboarding step
 ├─ device-specific runtime failure ......... UNPROVABLE today (C2) — Sprint 0 exists to see it
 └─ C3 dormant Firebase ..................... MEDIUM — latent crash class, wrong-device dependent

Release pipeline risk: minified builds untested (HIGH), debug-key signing (must change pre-Play)
Ops risk: cold-start UX (MED), mutable release tag (MED), FCM not delivering alerts (MED, by design — off)
```

---

## 6. Repair roadmap — sprints

> Ground rule adopted for all sprints: **no fix ships without a reproducing test or an instrumentation payload that would have caught it.** That's how the current crash got out; we close the door behind us.

---

### 🏁 Sprint 0 — *See the crash* (observability & reproduction)

**Goal:** turn "it crashes" into a stack trace, permanently, for every future crash.

- [ ] **0.1** Dependency-free `CrashReporter`: `Thread.setDefaultUncaughtExceptionHandler` → writes trace + device/API/version + breadcrumb ring buffer to app storage; user-shareable from Settings → *"Share diagnostics"*. (No Crashlytics — it needs google-services we don't have. C2)
- [ ] **0.2** Onboarding breadcrumbs: `page_shown(n)`, `continue_tapped(n)`, `finish_tapped(action)` → attached to every crash report.
- [ ] **0.3** Firebase decision (**recommend: strip** — remove `firebase-messaging`, `TenderMessagingService`, manifest service until alerts are actually built). If kept: guard every Firebase call behind `FirebaseApp.getApps().isNotEmpty()`. (C3)
- [ ] **0.4** StrictMode in debug builds; `CrashReporter` self-test ("throw test crash" row in debug settings).
- [ ] **0.5** CI emulator job (`android-emulator-runner`, API 26/29/34) with a Compose test: *launch → Continue×3 → Enable/Maybe-later → feed visible*. This is the repro harness — run it before any Sprint 1 fix, so we know whether C1 was the whole story. (C2)
- [ ] **0.6** Diagnostics build (`1.2-diag`) published; collect user logcat/device data (see §7).

**Exit criteria:** stack trace of the onboarding crash captured (emulator harness or user diagnostics build) *or* conclusive non-repro across the API matrix with instrumentation live.

---

### 🛠 Sprint 1 — *Fix what's certain* (stability)

- [ ] **1.1** **C1:** `finishOnboarding()` → `Intent(NEW_TASK | CLEAR_TASK)` + `finish()`; single `completeOnboarding()` path for Skip / Maybe-later / Enable; double-tap guards on all onboarding buttons (single-shot click helper).
- [ ] **1.2** **H1:** `OnboardingViewModel` — selection state, one save-on-exit write, repository on `applicationContext` only; screen becomes stateless. Unit tests for the VM (JVM).
- [ ] **1.3** **H2:** single permission owner — onboarding sets `notif_permission_asked`; `MainActivity` respects it.
- [ ] **1.4** **H3:** safe typed SharedPreferences getters (reset-on-corrupt `ClassCastException` → log + default), shared by repository and viewmodels.
- [ ] **1.5** Pager Continue hardening: clamp targets, `LaunchedEffect`-driven page advance instead of fire-and-forget coroutines.
- [ ] **1.6** **L1** tab state → `rememberSaveable`; **L2** guarded-`!!` cleanup.

**Exit criteria:** all C1/H1–H3 fixes landed with unit tests; emulator onboarding test green on API 26/29/34; no regressions in existing suites.

---

### 🧪 Sprint 2 — *Lock the gate* (test & quality)

- [ ] Compose UI tests for every golden path: onboarding (all three exits), feed + pull-to-refresh + pagination, filter sheet apply, save/unsave, detail workspace (checklist, note), deadlines, downloads.
- [ ] Make the emulator job a **required status check** for anything that publishes an APK (C2, permanently).
- [ ] **H4:** CI `assembleRelease` + install-and-launch smoke on the emulator (proves R8 config); keep-release-artifact for manual QA.
- [ ] Client/server contract test: pin `SearchFilters.toSavedSearchPayload` keys against the backend schema (drift detector).
- [ ] LeakCanary (debug only); coverage report surfaced in CI summary.

**Exit criteria:** red emulator test blocks APK publication; release build boots on-device; leak canary silent on golden paths.

---

### 🚀 Sprint 3 — *Release & ops hardening*

- [ ] **M2:** versioned tags per build (`v1.2.1+buildN`), release notes generated from `Changelog.kt`; keep `apk-latest` only as a clearly-labeled "nightly".
- [ ] Real signing config from CI secrets (drop debug-key signing); versionCode strategy documented.
- [ ] **M3:** Render cold-start mitigation (external uptime pinger every 10 min, or paid instance); document the scheduler-sleep trade-off (already noted in `render.yaml`) and wire the cron→`POST /admin/sync` fallback.
- [ ] **M4:** API-key rotation runbook; dashboard watch on per-key 429s.
- [ ] Push notifications end-to-end **only if Sprint 0 chose "complete FCM"**: google-services.json + server service-account + `FCM_ENABLED=true` + device-registration flow tests.
- [ ] targetSdk 35 review (edge-to-edge already implemented ✓), `material-icons-extended` → core icons (APK diet, M5), Kotlin 2.x migration plan (M5).

**Exit criteria:** properly signed, versioned release artifact from CI; documented runbooks; APK ≤ 12 MB release.

---

### ✨ Sprint 4 — *Consistency & polish* (audit nits)

- [ ] **M1:** one picker component, one semantics contract (empty ≡ follow-all), used by onboarding *and* preferences.
- [ ] Notification-permission UX copy pass ("why we ask" before the system dialog).
- [ ] i18n sweep (hardcoded `"Sort:"`, emoji "★" in snackbar, AM/PM formatting on 24 h locales).
- [ ] a11y pass: TalkBack order in the pager, minimum contrast re-check of urgency tokens (already AA-tuned), touch-target audit of icon buttons.
- [ ] Docs refresh: build/CI/release onboarding for new contributors (fold in `SETUP_GRADLE_WRAPPER.md` history).

---

## 7. Inputs needed from the user (Sprint 0 kickoff)

To aim the repro harness precisely, please confirm:

1. **Which exact button** when it crashed — the middle-pages *Continue*, or the last screen's *Enable deadline alerts / Maybe later / Skip*?
2. **Device model + Android version** (Settings → About phone).
3. **Fresh install, or installed over an older TenderBase build?**
4. Can you run `adb logcat` during the crash — or shall the Sprint 0 diagnostics build (shake-to-share crash file) be your path? Both work; the diagnostics build needs zero tooling.

---

*Audit performed 2026-08-31 on branch `arena/01a056f1-tenderbase` (base `a7d571b`). Next review: end of Sprint 1.*
