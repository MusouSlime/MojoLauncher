# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep MojoLauncher classes
-keep class net.kdt.pojavlaunch.** { *; }
-keep class git.artdeell.mojo.** { *; }

# Keep JavaRunner native methods
-keepclassmembers class net.kdt.pojavlaunch.utils.jre.JavaRunner {
    native <methods>;
}

# Keep SCLauncher classes
-keep class com.severedchains.launcher.** { *; }

# Keep LWJGL classes
-keep class org.lwjgl.** { *; }

# Keep game classes
-keep class legend.game.** { *; }
-keep class legend.core.** { *; }

# Keep JavaFX shim classes
-keep class javafx.application.** { *; }

# Keep JavaCV/FFmpeg classes
-keep class org.bytedeco.** { *; }