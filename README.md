# StrataSpent Android

Native Android shell for [StrataSpent](https://strataspent-124617071143.us-east1.run.app), the family group expenditure tracker and receipt analyzer. The app wraps the web client in a hardware-accelerated WebView and bridges native device capabilities (camera capture, gallery picker, push notifications, state preservation) into the existing React/Firebase frontend.

- **Web app:** React 18 + TypeScript + Vite (hosted on Cloud Run)
- **Web backend:** Firebase (Firestore + Auth) + Google GenAI for receipt analysis
- **Android shell:** Kotlin, AndroidX, Material 3, Chrome Custom Tabs, Firebase Messaging

---

## Requirements

| Tool | Version |
| --- | --- |
| Android Studio | Koala (2024.1.1) or newer |
| JDK | 17 (bundled with Android Studio) |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |
| Gradle | 8.7+ |
| Min SDK | 26 (Android 8.0 Oreo) |
| Target / Compile SDK | 35 (Android 15) |

Android Studio's SDK Manager must have **Android 15 (API 35)** platform + build tools installed.

---

## Project layout

```
claude-strata/
├── build.gradle.kts                # Root build script
├── settings.gradle.kts             # Modules + dependency resolution
├── gradle.properties               # AndroidX / Kotlin / JVM flags
└── app/
    ├── build.gradle.kts            # App module config (SDK 35, min 26)
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/strataspent/app/
        │   ├── StrataSpentApplication.kt   # WebView init
        │   ├── MainActivity.kt             # WebView shell + file chooser bridge
        │   └── push/StrataPushService.kt   # FCM notification handler
        └── res/
            ├── drawable/           # Launcher icon layers, splash vector
            ├── layout/             # Activity + bottom-sheet
            ├── mipmap-anydpi-v26/  # Adaptive launcher icon
            ├── values/             # Colors, strings, themes
            └── xml/                # FileProvider paths, backup rules
```

---

## First-time setup

### 1. Clone and open

```powershell
git clone <your-repo-url> strataspent-android
```

Open the `claude-strata` directory in Android Studio. Let Gradle sync — it will download AGP 8.5.2, Kotlin 1.9.24, and dependencies on first run.

### 2. Add Firebase config (optional, required only for push)

Push notifications are wired through `StrataPushService` but the project does not ship a `google-services.json`. To enable FCM:

1. Open the Firebase project that backs StrataSpent.
2. Add an Android app with package name `com.strataspent.app`.
3. Download `google-services.json` and drop it into `app/`.
4. Add the Google services plugin to the build:

   In `build.gradle.kts` (root):
   ```kotlin
   plugins {
       id("com.google.gms.google-services") version "4.4.2" apply false
   }
   ```

   In `app/build.gradle.kts`:
   ```kotlin
   plugins {
       id("com.google.gms.google-services")
   }
   ```

Without this step the app still builds and runs — push will simply be inert.

### 3. Configure release signing (only needed for Play Store builds)

Create `keystore.properties` at the repo root (do **not** commit):

```properties
storeFile=../strataspent-release.jks
storePassword=<store-password>
keyAlias=strataspent
keyPassword=<key-password>
```

Then extend `app/build.gradle.kts`:

```kotlin
import java.util.Properties
import java.io.FileInputStream

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(FileInputStream(f))
}

android {
    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps["storeFile"] as String)
            storePassword = keystoreProps["storePassword"] as String
            keyAlias = keystoreProps["keyAlias"] as String
            keyPassword = keystoreProps["keyPassword"] as String
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

Generate a keystore once with:

```powershell
keytool -genkey -v -keystore strataspent-release.jks `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -alias strataspent
```

---

## Build

All commands assume you're at the repo root.

### Debug APK

```powershell
.\gradlew.bat assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release APK (signed)

```powershell
.\gradlew.bat assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

### Play Store bundle (AAB)

```powershell
.\gradlew.bat bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

### Clean

```powershell
.\gradlew.bat clean
```

---

## Run

### On a connected device or emulator

```powershell
.\gradlew.bat installDebug
adb shell am start -n com.strataspent.app/.MainActivity
```

Or hit the green Run button in Android Studio with the app module selected.

### Remote debugging the WebView

Debug builds enable Chrome DevTools inspection (`WebView.setWebContentsDebuggingEnabled(true)` in `StrataSpentApplication`). With the app running:

1. Open Chrome on your desktop → `chrome://inspect`
2. Pick the StrataSpent WebView under your device.
3. Inspect, profile, and edit the live React app.

---

## How the shell bridges to the web client

| Native capability | Where it lives | What it does |
| --- | --- | --- |
| **Camera + gallery** | `MainActivity.StrataWebChromeClient.onShowFileChooser` | Shows a bottom-sheet ("Take Photo" / "Choose File"). Camera path writes to `cacheDir/captures/` via `FileProvider`. Returns URIs through the WebView's `ValueCallback<Array<Uri>>`. |
| **External link routing** | `MainActivity.StrataWebViewClient.shouldOverrideUrlLoading` | Internal StrataSpent / Firebase hosts stay in-app; everything else launches in a Chrome Custom Tab. |
| **Push notifications** | `push/StrataPushService` | FCM messages render as a system notification; tapping opens `MainActivity` with the optional `deepLink` extra. |
| **State preservation** | `MainActivity.onSaveInstanceState` / `onRestoreInstanceState` | WebView history + form state survive rotation and process death. |
| **Back navigation** | `OnBackPressedCallback` | Traverses WebView history before finishing the activity. |
| **Splash** | `Theme.StrataSpent.Starting` + `installSplashScreen()` | Android 12+ SplashScreen API; held until the WebView paints its first frame. |

---

## Troubleshooting

**Gradle sync fails on `compileSdk 35`** — open SDK Manager and install *Android 15 (API 35)* platform + matching build tools.

**`google-services.json is missing` build error** — you added the Google services plugin without the config file. Either drop in `google-services.json` (see step 2 above) or remove the plugin lines.

**WebView shows a blank white screen** — check `adb logcat -s chromium` for SSL or mixed-content errors. The shell already sets `MIXED_CONTENT_COMPATIBILITY_MODE`, so the most common cause is `usesCleartextTraffic="false"` blocking an `http://` resource — confirm StrataSpent loads cleanly over HTTPS in a desktop browser.

**Camera capture returns a null URI** — the user denied `CAMERA` permission, or no `ACTION_IMAGE_CAPTURE` handler is installed. The WebView's `filePathCallback` is always invoked with `null` in that case so the page doesn't deadlock.

**Themed launcher icon looks wrong on Android 13+** — only the alpha channel of `ic_launcher_monochrome.xml` is honored; check the silhouette is fully opaque white.

---

## Release checklist

- [ ] Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
- [ ] `google-services.json` present (if shipping push).
- [ ] `keystore.properties` populated and `keystore.properties` + `*.jks` excluded from VCS.
- [ ] `assembleRelease` succeeds with R8 / shrinker enabled.
- [ ] Manual smoke test: login, capture a receipt, view analytics, push reminder, back navigation, rotate.
- [ ] Upload AAB to Play Console internal testing track.
