# Sprint 0+1 — Onboarding repair build (1.2.1) — change report

**Date:** 2026-08-31 · **Branch:** `arena/01a056f1-tenderbase` · **Follows:** `01-code-audit-and-sprint-roadmap.md`

## What was fixed / shipped

| Audit ref | Change | Files |
|---|---|---|
| **C1** | **Finish-navigation race (the reported crash).** One `completeOnboarding()` exit: persists once, then launches `MainActivity` with `NEW_TASK \| CLEAR_TASK` + `finish()`; re-entrancy lock + `ClickGuard` on all exit taps. The old `startActivity()` + `finishAffinity()` is gone. | `OnboardingActivity.kt` |
| **C2** | **CrashReporter** — zero-dependency uncaught-exception capture: report files (device/app facts, 64-event breadcrumb trail, stack trace), kept ×5 in private storage. Settings → **Share diagnostics** hands it to the user; nothing is uploaded automatically. StrictMode (log-only) in debug; debug-only “Trigger test crash” self-test row. | `CrashReporter.kt`, `TbOtherScreens.kt`, `TenderBaseApp.kt`, `strings.xml` |
| **C3** | **Firebase strip.** `firebase-messaging` was shipping without `google-services.json` (dead code + latent `IllegalStateException` class). Removed dependency, `TenderMessagingService`, manifest service + `WAKE_LOCK`/`RECEIVE_BOOT_COMPLETED`, ProGuard keep. Server-side register-device API kept for future re-enable. | `build.gradle`, `AndroidManifest.xml`, `proguard-rules.pro` |
| **H1** | **OnboardingViewModel.** Selections + persistence live on the application context; the screen is stateless; preferences are written **once** on exit (was: on every Continue tap). | `ui/vm/OnboardingViewModel.kt`, `OnboardingScreen.kt` |
| **H2** | **Single permission owner.** Onboarding sets `notif_permission_asked` before the system dialog; `MainActivity` honours it — no back-to-back prompts on first launch. | `TenderRepository.kt`, `OnboardingActivity.kt`, `MainActivity.kt` |
| **H3** | **Corruption-proof preferences.** All typed SharedPreferences reads recover from `ClassCastException` by resetting the key (with breadcrumb) instead of crashing. | `TenderRepository.kt` |
| Gate | **OnboardingFlowTest** (Compose UI): fresh install → welcome → categories (with a chip toggle) → provinces → alerts → “Maybe later” → asserts the app is **alive on the Home feed** and state persisted. CI emulator-matrix job ready at `docs/ci-android-onboarding-gate.yml` (*manual paste required — GitHub Apps may not modify workflow files*). | `androidTest/…/OnboardingFlowTest.kt`, `docs/ci-android-onboarding-gate.yml` |
| Tests | JVM: `OnboardingLogicTest` (6), `ClickGuardTest` (4), `BreadcrumbsTest` (5), `ChangelogTest` updated for 1.2.1. | `android/app/src/test/…` |
| Version | versionCode 4 / versionName **1.2.1** (+ “What’s new” entry). | `build.gradle`, `Changelog.kt` |

## Verified

- Existing suites unaffected: backend 113/113 green (rerun locally); Android JVM tests unchanged except the intentional `ChangelogTest` version bump.
- Full-file static cross-check of every new call site (imports, signatures, package-local references) — the sandbox has no Android SDK, so **CI build is the compiler of record** for this change.

## How to ship it (two owner actions)

1. **Build the APK:** Actions → *Build Android APK* → Run workflow on branch `arena/01a056f1-tenderbase` (or merge PR → builds on `main`). The `apk-latest` release asset is overwritten by every successful build.
2. **Turn on the permanent UI gate:** paste `docs/ci-android-onboarding-gate.yml` over `.github/workflows/build-apk.yml` (GitHub web UI). From then on, a red onboarding walk **blocks APK publication** on API 26/29/34.

## Validation on device (user side)

1. Install `TenderBase-debug.apk` (1.2.1) from the `apk-latest` release, fresh.
2. Walk onboarding to the end; finish via any button. **Expected:** feed opens, no crash/vanish.
3. Reopen the app: it must go straight to the feed (not onboarding), and must **not** re-ask notification permission immediately.
4. If anything still misbehaves: More → Settings → **Share diagnostics** → send the report (it now contains the exact failure + the last 64 breadcrumbs).

## Not done (by design — later sprints)

- CI emulator gate activation awaits owner paste of the workflow (above).
- Release (R8) build smoke + real signing: Sprint 3. M1 picker-semantics unification (onboarding vs preferences): Sprint 4.
- Push notifications end-to-end: behind the explicit FCM re-enable decision with full credentials.
