# Keep the JS bridge methods reachable from the page (window.VistaksTvHost.*).
-keepclassmembers class com.vistaks.tv.MainActivity$TvHostBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# --- sideload flavor: native player (ADR-0100) -------------------------------------------------
# Rules for NewPipeExtractor (its README) and Rhino / Rhino Engine, the JS engine that runs
# YouTube's signature code (as in NewPipe's own app/proguard-rules.pro). The play flavor has none
# of these classes; the rules are inert there.
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.mozilla.javascript.* { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**
# protobuf-javalite (the extractor's YouTube requests) reads its fields reflectively.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
