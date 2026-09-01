# Keepix ProGuard Rules

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room: keep all entities, DAOs, RoomDatabase
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# Coil
-dontwarn coil.**

# Compose
-dontwarn androidx.compose.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# WorkManager
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker
-keepclassmembers class * {
    @androidx.work.Worker <methods>;
}

# Keepix: Room entities package (BinItemEntity, KeptItemEntity, etc.)
-keep class com.sese.keepix.db.**Entity { *; }

# Keepix: SharedPreferences wrapper
-keep class com.sese.keepix.data.KeepixPreferences { *; }

# OWASP: Strip debug/verbose/info/warn logging from release
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
    public static int w(...);
}