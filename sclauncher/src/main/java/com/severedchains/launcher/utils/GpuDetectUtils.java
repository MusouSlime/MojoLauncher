package com.severedchains.launcher.utils;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

import android.opengl.GLES20;

/**
 * GPU detection for SCLauncher.
 *
 * <p>Policy (2026-09-17): no manual renderer buttons, native GLES everywhere.
 * Legacy Zink (Mesa 23.0.4) is DISABLED: it hard-crashes on Mali (the
 * Vulkan driver lacks base Zink features — logicOp, fillModeNonSolid,
 * shaderClipDistance — so eglCreateContext fails with EGL_BAD_MATCH;
 * confirmed on RG406H/T820/Mali-G57, and Pojav documents Mali as Zink-
 * unsupported generally). Modern ANGLE was already removed (EGL_BAD_ATTRIBUTE
 * on T820). Both Mali and Snapdragon therefore run native system GLES;
 * the TMD invisibility is investigated via debug shaders, not wrappers.
 */
public final class GpuDetectUtils {
    private static final String TAG = "GpuDetectUtils";

    /** Retired renderer mode (Zink disabled); kept so stale prefs migrate. */
    public static final String MODE_MALI = "mali";
    /** Native GLES renderer for Snapdragon and everything non-Mali. */
    public static final String MODE_SNAPDRAGON = "snapdragon";

    private static Boolean sIsMali = null;
    private static Boolean sHasWorkingVulkan = null;
    private static Boolean sHasLegacyZink = null;
    private static GpuInfoCache sGpuInfoCache = null;

    private static class GpuInfoCache {
        final String renderer;
        final String vendor;
        final int glesMajorVersion;

        GpuInfoCache(String renderer, String vendor, int glesMajorVersion) {
            this.renderer = renderer;
            this.vendor = vendor;
            this.glesMajorVersion = glesMajorVersion;
        }
    }

    private GpuDetectUtils() {}

    /**
     * True on any ARM Mali/Immortalis GPU (not just Unisoc T820).
     * Probes once via a throwaway EGL pbuffer context; never throws.
     */
    public static boolean isMali(Context context) {
        if (sIsMali != null) return sIsMali;
        try {
            GpuInfoCache info = getGpuInfo(context);
            boolean mali = isArmMali(info);
            sIsMali = mali;
            Log.i(TAG, "Mali detection: " + mali
                    + " (renderer=" + info.renderer + ", vendor=" + info.vendor + ")");
        } catch (Throwable t) {
            Log.w(TAG, "GLInfo query failed, assuming not Mali", t);
            sIsMali = false;
        }
        return sIsMali;
    }

    /**
     * Legacy alias kept for callers: the T820/Mali-G57 is Mali, so this
     * is just {@link #isMali}. New code should call {@link #isMali}.
     */
    public static boolean isUnisocT820MaliG57(Context context) {
        return isMali(context);
    }

    /** Get GPU info via EGL pbuffer context (similar to SCGpuInfo.eglProbe). */
    private static GpuInfoCache getGpuInfo(Context context) {
        if (sGpuInfoCache != null) return sGpuInfoCache;

        EGL10 egl = null;
        EGLDisplay dpy = null;
        EGLSurface surf = null;
        EGLContext ctx = null;
        try {
            egl = (EGL10) EGLContext.getEGL();
            dpy = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            if (dpy == null || dpy == EGL10.EGL_NO_DISPLAY) {
                sGpuInfoCache = new GpuInfoCache("(no EGL display)", "(unknown)", 2);
                return sGpuInfoCache;
            }
            int[] ver = new int[2];
            if (!egl.eglInitialize(dpy, ver)) {
                sGpuInfoCache = new GpuInfoCache("(eglInitialize failed)", "(unknown)", 2);
                return sGpuInfoCache;
            }
            int[] cfgAttrs = {
                    EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                    EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16,
                    EGL10.EGL_NONE };
            EGLConfig[] cfgs = new EGLConfig[1];
            int[] num = new int[1];
            if (!egl.eglChooseConfig(dpy, cfgAttrs, cfgs, 1, num) || num[0] == 0) {
                sGpuInfoCache = new GpuInfoCache("(no EGL config)", "(unknown)", 2);
                return sGpuInfoCache;
            }
            int[] pbufAttrs = {
                    EGL10.EGL_WIDTH, 64, EGL10.EGL_HEIGHT, 64,
                    EGL10.EGL_NONE };
            surf = egl.eglCreatePbufferSurface(dpy, cfgs[0], pbufAttrs);
            int[] ctxAttrs = { 0x3098, 2, EGL10.EGL_NONE }; // EGL_CONTEXT_CLIENT_VERSION
            ctx = egl.eglCreateContext(dpy, cfgs[0], EGL10.EGL_NO_CONTEXT, ctxAttrs);
            if (surf == null || ctx == null
                    || surf == EGL10.EGL_NO_SURFACE || ctx == EGL10.EGL_NO_CONTEXT
                    || !egl.eglMakeCurrent(dpy, surf, surf, ctx)) {
                sGpuInfoCache = new GpuInfoCache("(pbuffer/context failed)", "(unknown)", 2);
                return sGpuInfoCache;
            }
            String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
            String vendor = GLES20.glGetString(GLES20.GL_VENDOR);
            String version = GLES20.glGetString(GLES20.GL_VERSION);

            // Parse major GLES version
            int glesMajor = 2;
            if (version != null && version.startsWith("OpenGL ES ")) {
                try {
                    glesMajor = Integer.parseInt(version.substring("OpenGL ES ".length(), version.indexOf('.', "OpenGL ES ".length())));
                } catch (Exception ignored) {}
            }

            sGpuInfoCache = new GpuInfoCache(renderer, vendor, glesMajor);
            return sGpuInfoCache;
        } catch (Throwable t) {
            Log.w(TAG, "GPU info probe failed", t);
            sGpuInfoCache = new GpuInfoCache("(probe failed: " + t.getMessage() + ")", "(unknown)", 2);
            return sGpuInfoCache;
        } finally {
            try {
                if (egl != null && dpy != null) {
                    egl.eglMakeCurrent(dpy, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE,
                            EGL10.EGL_NO_CONTEXT);
                    if (surf != null && surf != EGL10.EGL_NO_SURFACE) egl.eglDestroySurface(dpy, surf);
                    if (ctx != null && ctx != EGL10.EGL_NO_CONTEXT) egl.eglDestroyContext(dpy, ctx);
                    egl.eglTerminate(dpy);
                }
            } catch (Exception ignored) {}
        }
    }

    private static boolean isArmMali(GpuInfoCache info) {
        return info != null && info.vendor != null && info.vendor.equals("ARM")
                && info.renderer != null && (info.renderer.contains("Mali") || info.renderer.contains("Immortalis"));
    }

    /** Check if device has working Vulkan support (needed by Zink). */
    public static boolean hasWorkingVulkan(Context context) {
        if (sHasWorkingVulkan != null) return sHasWorkingVulkan;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            sHasWorkingVulkan = false;
            return false;
        }
        try {
            PackageManager pm = context.getPackageManager();
            sHasWorkingVulkan = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
                    && pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION);
        } catch (Throwable t) {
            Log.w(TAG, "Vulkan feature query failed", t);
            sHasWorkingVulkan = false;
        }
        return sHasWorkingVulkan;
    }

    /**
     * Check if the bundled Legacy Zink libraries are present in our own
     * native library dir ({@code libEGL_legacy.so} + {@code libgallium_dri.so}).
     */
    public static boolean hasLegacyZink(Context context) {
        if (sHasLegacyZink != null) return sHasLegacyZink;
        try {
            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            boolean egl = new java.io.File(nativeLibDir, "libEGL_legacy.so").exists();
            boolean gallium = new java.io.File(nativeLibDir, "libgallium_dri.so").exists();
            sHasLegacyZink = egl && gallium;
            if (!sHasLegacyZink) {
                Log.w(TAG, "Legacy Zink libs missing in " + nativeLibDir
                        + " (legacy=" + egl + ", gallium=" + gallium + ")");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Legacy Zink check failed", t);
            sHasLegacyZink = false;
        }
        return sHasLegacyZink;
    }

    /** Legacy alias: the Mesa-plugin check is gone, this is the bundled Legacy Zink check. */
    public static boolean hasMesaZink(Context context) {
        return hasLegacyZink(context);
    }

    /**
     * Auto-select the renderer. Never returns null.
     *
     * <p>Currently always {@link #MODE_SNAPDRAGON} (native GLES): Legacy Zink
     * is disabled (crashes Mali drivers — missing Vulkan features), so the
     * Mali mode is kept only as a stale-pref migration target. The TMD
     * invisibility probe (DEBUG gsh) runs on native GLES.
     *
     * @param context Application context
     * @param savedMode Previously saved mode (old values like
     *                  {@code mali}/{@code malivk}/{@code vulkan_zink}/
     *                  {@code opengles2} are migrated, not honored blindly);
     *                  null for pure auto-detect.
     * @return {@link #MODE_SNAPDRAGON} always (native system GLES).
     */
    public static String selectBestGpuMode(Context context, String savedMode) {
        if (isMali(context)) {
            if (savedMode != null && !MODE_SNAPDRAGON.equals(savedMode)) {
                Log.i(TAG, "Mali GPU: Zink disabled (driver lacks Zink Vulkan features) — migrating saved mode "
                        + savedMode + " -> " + MODE_SNAPDRAGON + " (native GLES + DEBUG gsh probe)");
            } else {
                Log.i(TAG, "Mali GPU detected - native GLES (Zink disabled, DEBUG gsh probe active)");
            }
            return MODE_SNAPDRAGON;
        }

        // Snapdragon / Adreno / anything non-Mali: native system GLES.
        if (savedMode != null && !MODE_SNAPDRAGON.equals(savedMode)) {
            Log.i(TAG, "Non-Mali GPU: migrating saved mode " + savedMode + " -> " + MODE_SNAPDRAGON);
        }
        return MODE_SNAPDRAGON;
    }

    /** Clear cached detection results (for testing). */
    public static void clearCache() {
        sIsMali = null;
        sHasWorkingVulkan = null;
        sHasLegacyZink = null;
        sGpuInfoCache = null;
    }
}
