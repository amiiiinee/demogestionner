# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }

# Hilt
-keepclasseswithmembernames class * { @dagger.hilt.* <methods>; }

# ZXing
-keep class com.google.zxing.** { *; }

# Data models (Firestore serialization)
-keep class com.attendance.app.data.model.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Jetpack Compose
-keep class androidx.compose.** { *; }
