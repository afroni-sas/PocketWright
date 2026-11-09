# Le moteur Chromium de l'app est chargé par réflexion et par des proxys d'interfaces.
-keep class com.norman.webviewup.** { *; }

# Appelé depuis la bibliothèque native (JNI).
-keep class com.afroni.pocketwright.engine.NodeRuntime { *; }

# Appelées depuis le JavaScript des pages.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Ktor et la configuration Typesafe référencent des classes absentes sur Android.
-dontwarn org.slf4j.**
-dontwarn com.typesafe.config.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn io.netty.**
-dontwarn org.fusesource.**
-dontwarn reactor.blockhound.**
-keep class io.ktor.server.config.** { *; }
-keepnames class io.ktor.** { *; }
