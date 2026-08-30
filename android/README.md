# TenderBase — Android App (basic)

A deliberately minimal Android app that **launches TenderBase**: it opens the
live API browser (Swagger UI at `https://tenderbase-api.onrender.com/docs`)
inside a WebView so you can browse tenders and try endpoints from your phone.

This is the starter foundation. It uses **only the Android framework** (no
AndroidX / third-party libraries), so the build is fast and dependency-free —
chosen specifically to make producing a **signed debug APK** painless.

- Package: `com.tenderbase.app` (debug builds install as `com.tenderbase.app.debug`)
- `minSdk 24` (Android 7.0+), `targetSdk 34`
- One screen (`MainActivity`) + a WebView, progress bar, and offline/retry view

---

## Build a signed debug APK

You need **[Android Studio](https://developer.android.com/studio)** (it bundles
a JDK, the Android SDK, and Gradle — no separate installs). Any recent version
(Koala / 2024.1+) works.

### Option A — Android Studio UI (easiest)

1. **File → Open** and select this `android/` folder.
2. Let it finish "Gradle sync" (first time it downloads the SDK + Gradle; needs
   internet — this is normal).
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. When it finishes, click **locate** in the notification. The file is:
   ```
   android/app/build/outputs/apk/debug/app-debug.apk
   ```
   Debug APKs are **automatically signed** with Android Studio's debug keystore,
   so they install directly — no manual signing needed.

### Option B — Command line

From the `android/` folder:

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

> **Note on the Gradle wrapper JAR:** `gradle/wrapper/gradle-wrapper.jar` is a
> binary and is not committed here. Android Studio **regenerates it
> automatically** on first open/sync, so Option A just works. If you use the
> command line first, run `gradle wrapper` once (with a system Gradle), or open
> the project in Android Studio once to generate it.

---

## Install on your phone

**Easiest:** with the phone plugged in and Android Studio open, press the green
**Run ▶** button — it builds, installs, and launches in one step.

**Manual APK install:**

1. Copy `app-debug.apk` to your phone.
2. Enable **Settings → Security → Install unknown apps** for your file manager.
3. Tap the APK to install. Open **TenderBase** from your app drawer.

**Via adb** (Android Studio ships it under `platform-tools`):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Configuration

To point the app at a different backend, edit `TENDERBASE_URL` in
[`app/src/main/java/com/tenderbase/app/MainActivity.java`](app/src/main/java/com/tenderbase/app/MainActivity.java).
It defaults to the live Render service. You can also point it at
`.../` (the landing page) or `.../admin` instead of `/docs`.

> The free-tier Render service sleeps when idle and can take ~1 minute to wake
> on the first request. If the page fails to load, the app shows a **Retry**
> button — give it a few seconds and retry.

---

## What this is / isn't

**Is:** a clean, installable starting point that proves the pipeline end to end
(phone → live API).

**Isn't (yet):** a native tenders UI or push notifications. The natural next
steps are to replace the WebView with native screens that call the JSON
endpoints (`/api/v1/tenders`, `/search`, `/latest`, …) and to wire Firebase
Cloud Messaging against the existing `/api/v1/notifications/*` endpoints.
