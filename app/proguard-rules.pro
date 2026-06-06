# ─── Project data classes (read/written reflectively by Firestore) ─────────
-keep class com.strataspent.app.data.model.** { *; }
-keepclassmembers class com.strataspent.app.data.model.** { *; }

# ─── Firebase Firestore — keep getters/setters for POJOs ──────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.firebase.firestore.** { *; }
-keepclassmembers class com.google.firebase.firestore.** { *; }

# ─── Google AI (Gemini) SDK ───────────────────────────────────────────────
# Generative AI client ships its own consumer rules, but be defensive.
-keep class com.google.ai.client.generativeai.** { *; }
-keep class com.google.ai.client.generativeai.type.** { *; }

# ─── MediaPipe / Google AI Edge (on-device Gemma via tasks-genai) ─────────
# Loads native libs + JNI/proto classes reflectively; keep them intact.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.tasks.genai.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# ─── WorkManager workers (instantiated reflectively) ──────────────────────
-keep class * extends androidx.work.ListenableWorker
-keep class * extends androidx.work.CoroutineWorker
-keep class com.strataspent.app.data.OcrProcessingWorker { *; }

# ─── Firebase Auth + Messaging — consumer rules handle most, keep our hooks ─
-keep class com.strataspent.app.push.StrataPushService { *; }

# ─── Compose — opt out of stripping the runtime intrinsics R8 sometimes ───
# misclassifies. Generally Compose ships clean consumer rules.
-dontwarn org.jetbrains.compose.**

# ─── Kotlinx Coroutines — handled by consumer rules but make explicit ─────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ─── Don't strip BuildConfig (we read GEMINI_API_KEY + VERSION_NAME at runtime) ─
-keep class com.strataspent.app.BuildConfig { *; }

# ─── Standard Android — keep crash-friendly line numbers ──────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
