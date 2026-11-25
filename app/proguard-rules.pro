# Keep readable stack traces in release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room entities/DAOs and TypeConverters.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class com.lanfetch.app.data.Converters { *; }

# WorkManager workers instantiated via reflection.
-keep class com.lanfetch.app.sync.SyncWorker { <init>(...); }
-keep class com.lanfetch.app.sync.SyncAllWorker { <init>(...); }

# Optional platform/third-party references in OkHttp & Jsoup.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.jsoup.nodes.**
