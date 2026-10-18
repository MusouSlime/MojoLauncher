package com.severedchains.launcher;

import android.content.Context;
import android.util.Log;

import com.severedchains.launcher.utils.GpuDetectUtils;

import git.artdeell.mojoexec.MojoExec;

/**
 * Renderer selection: automatic, no manual buttons.
 *
 * <p>Native system GLES on ALL GPUs. Legacy Zink (Mesa 23.0.4
 * {@code libEGL_legacy.so} over Vulkan) is DISABLED: it hard-crashes on
 * Mali — the Vulkan driver lacks base Zink features (logicOp,
 * fillModeNonSolid, shaderClipDistance), so SDL's
 * {@code eglCreateContext} fails with {@code EGL_BAD_MATCH} and the game
 * throws "does not support OpenGL 3.3 or OpenGLES 3.2" (RG406H/T820/
 * Mali-G57 log 2026-09-16; Pojav documents Mali as Zink-unsupported
 * generally). ANGLE was already removed (EGL_BAD_ATTRIBUTE on T820).
 * The TMD invisibility is investigated via debug shaders on native GLES,
 * not via wrappers. The {@code mali} mode string is retained only so
 * stale saved prefs migrate with a log line — it behaves identically
 * to {@code snapdragon}.
 */
public final class SCRenderer {
    private static final String TAG = "SCRenderer";

    private SCRenderer() {}

    /**
     * Set up the EGL renderer for the given GPU mode. Must be called before
     * {@code SDL.initialize()} on the game activity thread.
     *
     * @return human-readable report line, always non-null, for SCLogger.
     */
    public static String apply(Context ctx, String gpuMode) {
        // Auto-heal: the effective mode always wins over a stale saved pref.
        String auto = GpuDetectUtils.selectBestGpuMode(ctx, gpuMode);
        if (!auto.equals(gpuMode)) {
            Log.w(TAG, "GPU mode " + gpuMode + " overridden -> " + auto);
            gpuMode = auto;
        }

        // Hardware truth first (mode strings are legacy: every GPU runs
        // native GLES now, so "snapdragon" as a mode does NOT mean the
        // device has a Snapdragon SoC — e.g. the RG406H is Mali-G57).
        String hwName;
        try {
            hwName = GpuDetectUtils.isMali(ctx)
                    ? "Mali (native system GLES)"
                    : getRendererName(gpuMode);
        } catch (Throwable t) {
            hwName = getRendererName(gpuMode);
        }
        StringBuilder report = new StringBuilder("Renderer: " + hwName
                + " (GPU mode " + gpuMode + ")");
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            report.append(" [APK ").append(pi.versionName).append('/').append(pi.versionCode).append(']');
        } catch (Throwable ignored) {}

        try {
            try {
                setenv("MESA_GLSL_CACHE_DIR", ctx.getCacheDir().getAbsolutePath(), report);
            } catch (Throwable t) {
                report.append("; cache-dir failed: ").append(t.getMessage());
            }
            try {
                MojoExec.setUseTurnip(false); // Mali, not Adreno — never Turnip.
                report.append("; turnip=off");
            } catch (Throwable t) {
                report.append("; setUseTurnip failed: ").append(t.getMessage());
            }

            if (GpuDetectUtils.MODE_MALI.equals(gpuMode)) {
                // Zink disabled (crashes Mali drivers): behave as native GLES.
                report.append("; mali mode retired -> native GLES (Zink disabled)");
                Log.w(TAG, "mali mode requested but Zink is disabled; using native GLES");
                return report.toString();
            }

            // Native GLES (snapdragon and any fallback).
            report.append("; native GLES (system EGL/GLES)");
        } catch (Throwable t) {
            Log.e(TAG, "renderer setup failed", t);
            report.append("; FATAL: ").append(t);
        }
        return report.toString();
    }

    private static String getRendererName(String gpuMode) {
        if (GpuDetectUtils.MODE_MALI.equals(gpuMode)) {
            return "Mali (native system GLES — Zink disabled)";
        }
        if (GpuDetectUtils.MODE_SNAPDRAGON.equals(gpuMode)) {
            return "Snapdragon (native system GLES)";
        }
        return gpuMode;
    }

    private static void setenv(String k, String v, StringBuilder report) {
        try {
            android.system.Os.setenv(k, v, true);
            report.append("; ").append(k).append('=').append(v);
        } catch (Throwable t) {
            report.append("; env ").append(k).append(" failed: ").append(t.getMessage());
            Log.w(TAG, "setenv " + k + " failed", t);
        }
    }
}
