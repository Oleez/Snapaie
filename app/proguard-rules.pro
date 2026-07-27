# snapaie
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-keepnames class kotlinx.serialization.json.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.snapaie.android.data.model.**$$serializer { *; }
-keepclassmembers class com.snapaie.android.data.model.** {
    *** Companion;
}
-keep class com.snapaie.android.data.model.** { *; }

# Serializable payloads parsed out of local model output (recall/vocab engines).
-keep,includedescriptorclasses class com.snapaie.android.domain.recall.**$$serializer { *; }
-keepclassmembers class com.snapaie.android.domain.recall.** {
    *** Companion;
}
-keep class com.snapaie.android.domain.recall.RapidCard { *; }
-keep class com.snapaie.android.domain.recall.Flashcard { *; }
-keep class com.snapaie.android.domain.recall.FeynmanScore { *; }
-keep,includedescriptorclasses class com.snapaie.android.domain.vocab.**$$serializer { *; }
-keepclassmembers class com.snapaie.android.domain.vocab.** {
    *** Companion;
}

-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

-dontwarn com.google.ai.edge.**

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep public class com.android.vending.billing.**
