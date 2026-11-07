-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
-keep class com.her.** { *; }

-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

-dontwarn okhttp3.**
