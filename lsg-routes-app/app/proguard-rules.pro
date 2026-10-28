# Reglas ProGuard para LSG Routes (builds de release)
# Activadas solo en builds release con isMinifyEnabled = true

# ---- Retrofit + OkHttp ----
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ---- Gson (DTOs necesitan reflexion) ----
-keep class com.google.gson.** { *; }
-keep class cl.usach.diinf.lsg.routes.data.remote.dto.** { *; }
-keepclassmembers class cl.usach.diinf.lsg.routes.data.remote.dto.** { <fields>; }

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { <fields>; }
-dontwarn androidx.room.paging.**

# ---- MapLibre ----
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.**

# ---- Hilt ----
-keepclassmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}

# ---- WorkManager ----
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---- Timber ----
-dontwarn org.jetbrains.annotations.**

# ---- Kotlin Coroutines ----
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ---- Serialization ----
-keepattributes EnclosingMethod
-keepattributes InnerClasses
