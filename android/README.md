# TenderBase — Android App

A native Android app for browsing live South African government tenders from the
TenderBase API. Built with Kotlin + Material 3.

## Features

- **Tender list** — Material cards showing title, organisation, province,
  category chips, and a colour-coded "Closes in N days" (red when urgent).
- **Search** — search box (title / description / organisation).
- **Category filter** — horizontally scrollable filter chips loaded live from
  the API, single-select with an "All" reset.
- **Pull-to-refresh** and graceful **loading / empty / error** states (the
  error screen explains the free-tier server may take ~1 min to wake).
- **Detail screen** — full description, type, status, closing date, category
  chips, tappable **document links** (open in browser), and an **Open on
  eTenders** button.
- **Adaptive launcher icon** (API 26+) plus PNG icons for older devices.

Talks to the live API at `https://tenderbase-api.onrender.com` (JSON endpoints
`/api/v1/tenders`, `/api/v1/tenders/{id}`, `/api/v1/categories`). Change
`ApiClient.BASE_URL` to point at another deployment.

---

## Getting the installable APK

### Option 1 — Cloud build (recommended, no local tools) ⭐

This produces a downloadable, signed APK using GitHub's build servers — no
Android Studio, no SDK on your machine.

1. In your repo on GitHub: **Add file → Create new file**.
2. Name it exactly: `.github/workflows/build-apk.yml`
3. Paste the entire contents of [`ci-build-apk.yml`](ci-build-apk.yml) (in this
   folder) and **commit**.
   *(GitHub blocks automation bots from adding workflow files, so this one step
   is manual — everything after is automatic.)*
4. Go to the **Actions** tab → **Build Android APK** → **Run workflow**.
5. When it finishes (~3–5 min), download the APK two ways:
   - **Artifacts** on the run page → `TenderBase-debug-apk`, or
   - the auto-created **Release** tagged `apk-latest` →
     `TenderBase-debug.apk` (a stable link you can open on your phone).

### Option 2 — Android Studio (local)

1. Install [Android Studio](https://developer.android.com/studio) (bundles JDK,
   SDK, Gradle).
2. **File → Open** → select this `android/` folder → let Gradle sync finish.
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. Output: `app/build/outputs/apk/debug/app-debug.apk` (already debug-signed).
5. Or press **Run ▶** with a phone connected to build + install + launch.

---

## Install on your phone

1. Download `TenderBase-debug.apk` (from the Release or Artifact) to your phone.
2. Tap it. If prompted, allow **Install unknown apps** for your browser / file
   manager.
3. Open **TenderBase** from your app drawer.

Debug APKs are signed with Android's standard debug key, so they install on any
device without extra signing setup. (For Play Store distribution you'd later
create a release build with your own keystore.)

---

## Project layout

```
android/
├─ app/src/main/
│  ├─ java/com/tenderbase/app/
│  │  ├─ MainActivity.kt      list + search + filters
│  │  ├─ DetailActivity.kt    single-tender detail
│  │  ├─ TenderAdapter.kt     RecyclerView card adapter
│  │  ├─ ApiClient.kt         HttpURLConnection API client
│  │  ├─ Tender.kt            model + JSON parsing
│  │  └─ DateUtils.kt         "closes in N days" helpers
│  └─ res/                    layouts, Material theme, icons
├─ ci-build-apk.yml           → copy to .github/workflows/build-apk.yml
└─ build.gradle, settings.gradle, gradlew(.bat)
```

> **Gradle wrapper JAR:** `gradle/wrapper/gradle-wrapper.jar` (binary) is not
> committed. The cloud workflow and Android Studio both regenerate/provide it
> automatically. For a purely manual CLI build, run `gradle wrapper` once first.

---

## Next steps (foundation for the notification app)

- Firebase Cloud Messaging against the existing `/api/v1/notifications/*`
  endpoints (device registration + preferences already exist server-side).
- Saved/bookmarked tenders and closing-soon reminders.
- Infinite scroll / pagination (API already returns `pagination`).
