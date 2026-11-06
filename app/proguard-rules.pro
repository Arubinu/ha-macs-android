-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class fr.pergens.hamacs.**$$serializer { *; }
-keepclassmembers class fr.pergens.hamacs.** { *** Companion; }
-keepclasseswithmembers class fr.pergens.hamacs.** { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-dontwarn org.slf4j.**

# Detections zone: SMB (smbj + mbassador event bus + BouncyCastle) and NFS (nfs-client + Netty 3).
# Kerberos (GSS) and Java EL are never used on Android.
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class com.emc.ecs.nfsclient.** { *; }
-keep class org.jboss.netty.** { *; }
-dontwarn org.jboss.netty.**
