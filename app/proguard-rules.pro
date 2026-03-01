# Preserve JNI entry points used by libacidcam.
-keepclasseswithmembernames class com.lostsidedead.acidcam.AcidCam_Filter {
    native <methods>;
}

# CameraX metadata is loaded reflectively.
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# OpenCV Java wrappers and JNI access.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
