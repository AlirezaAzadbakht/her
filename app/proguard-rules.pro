-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
-keep class com.her.** { *; }

-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

-keep class com.google.android.gms.auth.api.identity.** { *; }
-keep class com.google.android.gms.common.api.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-dontwarn com.google.android.gms.**

-dontwarn okhttp3.**
