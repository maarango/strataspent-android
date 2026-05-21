You are an expert mobile developer specializing in Kotlin, Jetpack Compose, and Android SDK development. Your task is to build a highly optimized, production-ready Android companion application for "StrataSpent" (a family group expenditure tracker and receipt analyzer) by wrapping the web client or porting its core features based on the existing repository.

Here is the context of our existing repository:

https://github.com/maarango/StrataSpent

* Tech Stack: React 18, TypeScript, Tailwind CSS, Vite.
* Backend \& DB: Firebase Client SDK (Firestore Database, Firebase Authentication).
* Key Features: Shared group expenditures, automated split ratios, dynamic visual analytics, cash-flow timelines, automatic receipt analyzing via Google GenAI, and push reminders.
* Live Web Deployment URL: https://strataspent-124617071143.us-east1.run.app

We want to construct a native Android Shell utilizing a robust Chrome Custom Tab or an advanced hardware-accelerated System WebView implementation. This wrapper must seamlessly handle native device bridges (specifically camera feeds, gallery image selection for receipt scanning, state preservation during screen orientation changes, and push updates).

Please generate the complete codebase, file by file, with the following criteria:

\---

### 1\. Build and Project Configuration files

Provide the precise code configuration files for the project:

* 'app/build.gradle.kts': Target SDK 35 (Android 15), compile SDK 35, min SDK 26 (Android 8.0 Oreo), enabling full hardware-acceleration and Google Play Store compliance. Include dependencies for AndroidX Core, Custom Tabs, Cohesive WebKit, and Play Services.
* 'settings.gradle.kts': Kotlin DSL-compliant dependency resolution.

### 2\. Android Manifest Configuration ('AndroidManifest.xml')

Configure the file to secure critical device capabilities:

* Enable: INTERNET, CAMERA, READ\_EXTERNAL\_STORAGE, and WRITE\_EXTERNAL\_STORAGE.
* Implement the 'FileProvider' path XML metadata to safely capture camera photos (without exposing raw file URI schemes) and pass them as binary streams to the receipt-analyzing module.
* Set 'android:windowSoftInputMode="adjustResize"' to ensure the keyboard does not clip the entry fields.

### 3\. Native File Picker and Camera Input Bridge

To allow users to upload or snap receipts directly from the Android app, create a custom 'WebChromeClient' inside 'MainActivity.kt'. It must override the 'onShowFileChooser' lifecycle method to:

* Prompt the user with a native bottom-sheet chooser: "Take Photo (Camera)" or "Choose File (Photo Library)".
* Create a temporary '.jpeg' file using 'FileProvider' for camera snaps.
* Deliver the final selected URI file path gracefully back to the WebView's file path callback ('ValueCallback<Array<Uri>>').

### 4\. MainActivity.kt (Kotlin Launcher)

Implement the core Controller activity with:

* Strict viewport configurations: Enable 'javaScriptEnabled', 'domStorageEnabled', 'databaseEnabled', 'mixedContentMode = WebSettings.MIXED\_CONTENT\_COMPATIBILITY\_MODE', and 'allowFileAccess'.
* A custom 'WebViewClient' that intercepts navigation events (keeping all 'StrataSpent' URLs inside the app while forcing external sharing/help links to launch in the native device browser).
* WebView State Preservation: Save and restore the browser state ('onSaveInstanceState' and 'onRestoreInstanceState') during system recreation or screen orientation rotations.
* Native Back Navigation Handler: Block the app from abruptly exiting if the internal browser stack can traverse back.

### 5\. Add a Dynamic Splash Screen

Generate a custom vector launch theme ('themes.xml') leveraging the Android 12+ Splash Screen API to display a sleek, dark-slate background with an indigo wallet icon while the interface loads.

Please output the complete, non-truncated Kotlin and XML files step-by-step so I can dropped them directly into my Android Studio directory structure.

