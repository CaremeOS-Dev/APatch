# JNI: libapjni.so binds these methods by exact name in JNI_OnLoad. If R8 strips or
# renames one (it follows only *referenced* code -- suPromptPoll became dead code
# once the app stopped polling), RegisterNatives fails, JNI_OnLoad returns 0 and the
# whole app dies with UnsatisfiedLinkError before onCreate finishes.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.zcg.apatch.Natives { *; }
-keep class com.zcg.apatch.Natives$SuRequest { *; }
-keep class com.zcg.apatch.Natives$Profile { *; }
-keep class com.zcg.apatch.Natives$KPMCtlRes { *; }

-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn java.beans.Introspector
-dontwarn java.beans.VetoableChangeListener
-dontwarn java.beans.VetoableChangeSupport
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.PropertyDescriptor

# Keep ini4j Service Provider Interface
-keep,allowobfuscation,allowoptimization class org.ini4j.spi.** { *; }

# Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
    public static void throw*(...);
}

-repackageclasses
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute SourceFile
