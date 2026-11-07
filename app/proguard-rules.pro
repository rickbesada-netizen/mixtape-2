# NewPipeExtractor (uses reflection + Rhino JS engine for signature deciphering)
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-keep class com.grack.nanojson.** { *; }
-dontwarn com.grack.nanojson.**

# jsoup / protobuf / misc optional deps pulled in by the extractor
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-dontwarn org.slf4j.**

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# App models used by Room
-keep class app.mixtape.data.db.** { *; }

# The extractor's optional dependencies reference desktop-JVM classes that don't exist on
# Android; don't fail the release build over them.
-ignorewarnings

# Chromecast options provider is named in the manifest only.
-keep class app.mixtape.player.CastOptionsProvider { *; }
