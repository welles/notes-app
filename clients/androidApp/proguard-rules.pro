# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.welles.notes.** {
    *** Companion;
}
-keepclasseswithmembers class dev.welles.notes.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Ktor / OkHttp optional dependencies
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
