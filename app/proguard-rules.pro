# kotlinx.serialization: keep generated serializers of KultrDL's own models.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers @kotlinx.serialization.Serializable class app.kultr.dl.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class app.kultr.dl.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# youtubedl-android runs yt-dlp through its own classes and reads JSON with Jackson.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
# It unpacks Python and ffmpeg with commons-compress 1.12, whose zip reader creates
# its extra-field classes with Class.newInstance() in a static initializer. R8 would
# drop those constructors, and the initializer then fails (ExceptionInInitializerError).
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.apache.commons.io.**

# jaudiotagger looks up tag and field classes reflectively and mentions desktop Java APIs it never uses on Android.
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
-dontwarn java.beans.**

-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# SFTP: JSch creates its ciphers, key exchanges and signatures from class names in its
# config. Its Bouncy Castle ones (X25519, Ed25519, ML-KEM…) pull in the BC classes they use.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.apache.logging.log4j.**
# FTP/FTPS: Commons Net can pick its directory-listing parsers by class name.
-keep class org.apache.commons.net.** { *; }
-dontwarn org.apache.commons.net.**
