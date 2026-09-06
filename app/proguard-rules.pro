# Serein keeps its model local; pdfbox-android is the one reflection-touching
# dependency (its resource loader and font handling reach into java.awt stand-ins
# that don't exist on Android, so keep the library intact and silence those).
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn javax.imageio.**
-dontwarn javax.print.**
-dontwarn java.awt.**
