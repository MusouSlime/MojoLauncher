package com.severedchains.launcher;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.File;

/**
 * Vulkan / Legacy-Zink probe (informational only).
 *
 * <p>Background: 3D models are missing on Mali-G57 (RG406H, Unisoc T820)
 * while the UI renders. Shaders compile/link clean — the geometry-shader
 * stage mis-executes on the native Mali GLES driver. Legacy Zink was tried
 * as a wrapper but is DISABLED: the Mali Vulkan driver lacks base Zink
 * features (logicOp, fillModeNonSolid, shaderClipDistance), so Zink's
 * eglCreateContext fails with EGL_BAD_MATCH and the game crashes. All
 * GPUs therefore run native system GLES; the TMD invisibility is probed
 * with a debug geometry shader instead.
 *
 * <p>All methods are best-effort and never throw.
 */
public final class SCVulkanProbe {
    private static final String TAG = "SCVulkan";

    public static final String ZINK_PLUGIN_ID = "git.mojo.zink";
    public static final String ANGLE_PLUGIN_ID = "git.mojo.angle";

    /** Legacy Zink libs bundled in our APK (arm64-v8a jniLibs). */
    public static final String[] LEGACY_ZINK_LIBS = {
            "libEGL_legacy.so", "libdrm.so", "libgallium_dri.so"};

    private SCVulkanProbe() {}

    /** One-line Vulkan capability summary for logs and Send-Log reports. */
    public static String summary(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            PackageManager pm = ctx.getPackageManager();
            boolean hasVulkan = false;
            int vulkanVersion = 0;
            try {
                hasVulkan = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION);
                // FEATURE_VULKAN_HARDWARE_LEVEL is API 30+; version int encodes major.minor.patch.
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        vulkanVersion = pm.getSystemAvailableFeatures() == null ? 0 : vulkanFeatureVersion(pm);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                sb.append("Vulkan feature query failed: ").append(t.getMessage());
                return sb.toString();
            }
            sb.append("Vulkan hw feature: ").append(hasVulkan);
            if (vulkanVersion != 0) {
                sb.append(" (v").append((vulkanVersion >> 22) & 0x3ff).append('.')
                  .append((vulkanVersion >> 12) & 0x3ff).append('.')
                  .append(vulkanVersion & 0xfff).append(')');
            }
            // libvulkan.so load check: MojoExec.preloadVulkan dlopens it
            // natively; here we just confirm the system lib file exists in a
            // well-known location without loading it into our namespace.
            sb.append("; libvulkan: ").append(findSystemVulkanLib());
            sb.append("\nLegacy Zink (bundled): ")
              .append(bundledLibsLine(ctx, LEGACY_ZINK_LIBS))
              .append(legacyZinkUsableSuffix(ctx));
        } catch (Throwable t) {
            Log.w(TAG, "vulkan summary failed", t);
            sb.append("Vulkan probe failed: ").append(t.getMessage());
        }
        return sb.toString();
    }

    private static int vulkanFeatureVersion(PackageManager pm) {
        try {
            for (android.content.pm.FeatureInfo fi : pm.getSystemAvailableFeatures()) {
                if (PackageManager.FEATURE_VULKAN_HARDWARE_VERSION.equals(fi.name)) {
                    return fi.version;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static String findSystemVulkanLib() {
        String[] candidates = {
                "/system/lib64/libvulkan.so",
                "/vendor/lib64/libvulkan.so",
                "/vendor/lib64/vulkan/libvulkan.so",
        };
        for (String p : candidates) {
            try {
                if (new File(p).exists()) return "present (" + p + ")";
            } catch (Throwable ignored) {}
        }
        return "not found in /system|/vendor lib64 (dlopen may still work via linker)";
    }

    private static String bundledLibsLine(Context ctx, String[] libs) {
        try {
            String ourDir = ctx.getApplicationInfo().nativeLibraryDir;
            StringBuilder sb = new StringBuilder("bundled @ ").append(ourDir).append(" [");
            for (int i = 0; i < libs.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(libs[i]).append(':')
                  .append(new File(ourDir, libs[i]).exists() ? "ok" : "missing");
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return "bundled check failed: " + t.getMessage();
        }
    }

    private static String pluginLibsLine(Context ctx, String pluginId, String[] libs) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(
                    pluginId, PackageManager.GET_SHARED_LIBRARY_FILES);
            if (pi == null || pi.applicationInfo == null) return "installed (no native dir)";
            String dir = pi.applicationInfo.nativeLibraryDir;
            StringBuilder sb = new StringBuilder("installed @ ").append(dir).append(" [");
            for (int i = 0; i < libs.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(libs[i]).append(':')
                  .append(new File(dir, libs[i]).exists() ? "ok" : "missing");
            }
            return sb.append(']').toString();
        } catch (PackageManager.NameNotFoundException e) {
            // Fallback: check our own native library dir (for bundled libs)
            try {
                String ourDir = ctx.getApplicationInfo().nativeLibraryDir;
                StringBuilder sb = new StringBuilder("bundled @ ").append(ourDir).append(" [");
                for (int i = 0; i < libs.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(libs[i]).append(':')
                      .append(new File(ourDir, libs[i]).exists() ? "ok" : "missing");
                }
                return sb.append(']').toString();
            } catch (Throwable t) {
                return "not installed (bundled check failed: " + t.getMessage() + ")";
            }
        } catch (Throwable t) {
            return "query failed: " + t.getMessage();
        }
    }

    /** Absolute path of a present plugin lib, or null. */
    public static String pluginLibPath(Context ctx, String pluginId, String libName) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(
                    pluginId, PackageManager.GET_SHARED_LIBRARY_FILES);
            if (pi != null && pi.applicationInfo != null) {
                File f = new File(pi.applicationInfo.nativeLibraryDir, libName);
                if (f.exists()) return f.getAbsolutePath();
            }
        } catch (Throwable ignored) {}
        // Fallback: check our own native library dir (for bundled libs)
        try {
            String ourDir = ctx.getApplicationInfo().nativeLibraryDir;
            File f = new File(ourDir, libName);
            if (f.exists()) return f.getAbsolutePath();
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * One-line Legacy Zink usability note: {@code libEGL_legacy.so} IS the
     * Zink EGL on ARM (upstream {@code MesaUtils.getPreferredEGL} picks
     * legacy on ARM), backed here by the bundled {@code libgallium_dri.so}.
     */
    private static String legacyZinkUsableSuffix(Context ctx) {
        try {
            if (pluginLibPath(ctx, ZINK_PLUGIN_ID, "libEGL_legacy.so") != null) {
                return " => Legacy Zink usable (bundled EGL present)";
            }
        } catch (Throwable ignored) {}
        return "";
    }
}
