# protobuf-lite reads message fields reflectively by name (GeneratedMessageLite's
# RawMessageInfo lists them as strings), so their names must survive obfuscation.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
# Lets R8 drop protobuf's non-Android code paths.
-assumevalues class com.google.protobuf.Android { static boolean ASSUME_ANDROID return true; }

# BouncyCastleProvider registers its algorithms as class-name strings and loads them with
# Class.forName, so R8 cannot see that they are used.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
# Only the LDAP CertStore and CRL fetching use JNDI, which Android lacks; neither is reached.
-dontwarn javax.naming.**

# JNI_OnLoad binds the native methods by class and method name (RegisterNatives) and fails the
# whole library if one is missing, so these must keep their names and survive even when unused.
-keep class se.lublin.humla.audio.native.*Native { native <methods>; }
