package com.severedchains.launcher;

import android.os.Build;
import android.util.Log;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * Device/GPU fingerprint for graphics triage.
 *
 * <p>Background: 3D models are missing on Unisoc Tiger T820 (Mali-G57) while
 * the UI still renders. The game's GLES path transpiles every shader
 * GLSL -&gt; SPIR-V (shaderc) -&gt; GLES 3.2 (spirv-cross), and model shaders
 * take the geometry-shader/translvl path that Mali-Valhall drivers are
 * historically fragile with. A launcher cannot recompile the game, so this
 * helper captures the evidence needed to confirm it: SoC + GPU renderer
 * string in every log/report, so a T820 "Send Log" report shows whether the
 * game logged "Shader compile error" next to a Mali-G57 renderer.
 */
public final class SCGpuInfo {
    private static final String TAG = "SCGpu";
    private static volatile String sCached;
    private static volatile String sRamLine;
    private static volatile boolean sLogged;

    private SCGpuInfo() {}

    /** Log the fingerprint once per process (launcher startup). Never throws. */
    public static void logOnce(android.content.Context ctx) {
        if (sLogged) return;
        sLogged = true;
        try {
            sRamLine = ramLine(ctx);
            LogFile.log("GPU fingerprint: " + summary().replace("\n", " | "));
        } catch (Exception e) {
            Log.e(TAG, "fingerprint failed", e);
        }
    }

    /** Backwards-compatible entry point (no RAM line). */
    public static void logOnce() {
        logOnce(null);
    }

    /** Multi-line "SoC / GPU" block for reports and error dialogs. Never throws. */
    public static String summary() {
        String cached = sCached;
        if (cached != null) return cached;
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("Hardware: ").append(Build.HARDWARE)
              .append(", Board: ").append(Build.BOARD)
              .append(", SoC: ").append(socModel());
            sb.append("\nABIs: ").append(android.text.TextUtils.join(",", Build.SUPPORTED_ABIS));
        } catch (Exception e) {
            sb.append("Build fields unreadable: ").append(e.getMessage());
        }
        sb.append("\n").append(eglProbe());
        if (sRamLine != null) sb.append("\n").append(sRamLine);
        String note = t820Note();
        if (note != null) sb.append("\n").append(note);
        cached = sb.toString();
        sCached = cached;
        return cached;
    }

    private static String socModel() {
        try {
            if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != null) {
                return Build.SOC_MODEL + " (" + Build.SOC_MANUFACTURER + ")";
            }
        } catch (Exception ignored) {}
        return "(unknown, API " + Build.VERSION.SDK_INT + ")";
    }

    /**
     * Renderer/vendor/version via a throwaway EGL pbuffer context. Runs on
     * the caller thread (fast, no surface needed); everything is torn down
     * in finally so a driver failure can never leak or crash the app.
     */
    private static String eglProbe() {
        EGL10 egl = null;
        EGLDisplay dpy = null;
        EGLSurface surf = null;
        EGLContext ctx = null;
        try {
            egl = (EGL10) EGLContext.getEGL();
            dpy = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            if (dpy == null || dpy == EGL10.EGL_NO_DISPLAY) return "GPU: (no EGL display)";
            int[] ver = new int[2];
            if (!egl.eglInitialize(dpy, ver)) return "GPU: (eglInitialize failed)";
            int[] cfgAttrs = {
                    EGL10.EGL_RENDERABLE_TYPE, 4 /* EGL_OPENGL_ES2_BIT */,
                    EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16,
                    EGL10.EGL_NONE };
            EGLConfig[] cfgs = new EGLConfig[1];
            int[] num = new int[1];
            if (!egl.eglChooseConfig(dpy, cfgAttrs, cfgs, 1, num) || num[0] == 0) {
                return "GPU: (no EGL config)";
            }
            int[] pbufAttrs = {
                    EGL10.EGL_WIDTH, 64, EGL10.EGL_HEIGHT, 64,
                    EGL10.EGL_NONE };
            surf = egl.eglCreatePbufferSurface(dpy, cfgs[0], pbufAttrs);
            int[] ctxAttrs = { 0x3098 /* EGL_CONTEXT_CLIENT_VERSION */, 2, EGL10.EGL_NONE };
            ctx = egl.eglCreateContext(dpy, cfgs[0], EGL10.EGL_NO_CONTEXT, ctxAttrs);
            if (surf == null || ctx == null
                    || surf == EGL10.EGL_NO_SURFACE || ctx == EGL10.EGL_NO_CONTEXT
                    || !egl.eglMakeCurrent(dpy, surf, surf, ctx)) {
                return "GPU: (pbuffer/context failed)";
            }
            String renderer = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER);
            String vendor = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VENDOR);
            String version = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VERSION);
            String sl = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_SHADING_LANGUAGE_VERSION);
            return "GPU renderer: " + renderer + "\nGPU vendor: " + vendor
                    + "\nGLES version: " + version + "\nGLSL version: " + sl;
        } catch (Throwable t) {
            return "GPU: (probe failed: " + t.getMessage() + ")";
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

    private static String ramLine(android.content.Context ctx) {
        try {
            if (ctx == null) return null;
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (am == null) return null;
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return "RAM total: " + (mi.totalMem / (1024 * 1024)) + " MB"
                    + (mi.lowMemory ? " (system reports low memory)" : "");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Low-end preflight (budget tablets etc.): returns a human warning or
     * null when the device looks capable. Checks free internal storage
     * (~500 MB needed for first run: game + JRE + video), total RAM, and
     * whether a GLES3 context can even be created (the game needs GLES 3.2
     * + geometry shaders; ES2-only GPUs cannot render it). Never throws.
     */
    public static String preflight(android.content.Context ctx) {
        try {
            StringBuilder warn = new StringBuilder();
            java.io.File dir = ctx.getFilesDir();
            long freeMb = dir.getUsableSpace() / (1024 * 1024);
            if (freeMb < 500) {
                warn.append("Low storage: ").append(freeMb)
                    .append(" MB free — first run needs ~500 MB (game + JRE + video). ");
            }
            try {
                android.app.ActivityManager am = (android.app.ActivityManager)
                        ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long totalMb = mi.totalMem / (1024 * 1024);
                if (totalMb < 3072) {
                    warn.append("Only ").append(totalMb)
                        .append(" MB RAM — expect slow loads; keep other apps closed. ");
                }
            } catch (Exception ignored) {}
            String gles3 = tryGles3Version();
            if (gles3 == null) {
                warn.append("No GLES3 context on this GPU — the game needs GLES 3.2 "
                        + "with geometry shaders and will not render 3D. ");
            } else {
                LogFile.log("GLES3 probe: " + gles3);
            }
            String out = warn.toString().trim();
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Best-effort GLES3 pbuffer context; returns GL_VERSION or null. */
    private static String tryGles3Version() {
        EGL10 egl = null;
        EGLDisplay dpy = null;
        EGLSurface surf = null;
        EGLContext ctx = null;
        try {
            egl = (EGL10) EGLContext.getEGL();
            dpy = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            if (dpy == null || dpy == EGL10.EGL_NO_DISPLAY) return null;
            int[] ver = new int[2];
            if (!egl.eglInitialize(dpy, ver)) return null;
            final int EGL_OPENGL_ES3_BIT = 0x40;
            int[] cfgAttrs = {
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                    EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_NONE };
            EGLConfig[] cfgs = new EGLConfig[1];
            int[] num = new int[1];
            if (!egl.eglChooseConfig(dpy, cfgAttrs, cfgs, 1, num) || num[0] == 0) return null;
            int[] pbufAttrs = { EGL10.EGL_WIDTH, 16, EGL10.EGL_HEIGHT, 16, EGL10.EGL_NONE };
            surf = egl.eglCreatePbufferSurface(dpy, cfgs[0], pbufAttrs);
            int[] ctxAttrs = { 0x3098 /* EGL_CONTEXT_CLIENT_VERSION */, 3, EGL10.EGL_NONE };
            ctx = egl.eglCreateContext(dpy, cfgs[0], EGL10.EGL_NO_CONTEXT, ctxAttrs);
            if (surf == null || ctx == null
                    || surf == EGL10.EGL_NO_SURFACE || ctx == EGL10.EGL_NO_CONTEXT
                    || !egl.eglMakeCurrent(dpy, surf, surf, ctx)) {
                return null;
            }
            return android.opengl.GLES30.glGetString(android.opengl.GLES30.GL_VERSION);
        } catch (Throwable t) {
            return null;
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

    /**
     * Triage hint appended when the device looks like the affected
     * Unisoc T820 / Mali-G57 family. Points at the next evidence to collect
     * (game "Shader compile error" lines in latestlog.txt via Send Log).
     */
    private static String t820Note() {
        try {
            String hay = (Build.HARDWARE + " " + Build.BOARD + " " + Build.MODEL + " "
                    + Build.PRODUCT + " " + socModel()).toLowerCase(java.util.Locale.US);
            if (hay.contains("t820") || hay.contains("t820_")
                    || (hay.contains("unisoc") && hay.contains("g57"))
                    || hay.contains("mali-g57")) {
                return "NOTE: Unisoc T820 / Mali-G57 family — known missing-3D-models "
                        + "report. Suspect: geometry-shader model path or SPIR-V "
                        + "transpile on this driver. Check latestlog.txt for "
                        + "'Shader compile error' / shaderc failures.";
            }
        } catch (Exception ignored) {}
        return null;
    }
}
