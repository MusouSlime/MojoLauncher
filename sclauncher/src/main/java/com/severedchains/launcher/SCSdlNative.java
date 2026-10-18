package com.severedchains.launcher;

/**
 * Bridge to the SDL3 library already loaded in the process. Used to detect
 * when the game has a text field focused so the soft keyboard can be raised
 * automatically (Mojo's SDLActivity.showTextInput is a stub), and to install
 * the launcher's SDL assertion handler (see jni/sc_sdlassert.c) so a failing
 * SDL assert is logged and ignored instead of looping SDL's modal Retry
 * dialog forever.
 */
public final class SCSdlNative {
    private static volatile boolean sLoaded;
    private static volatile boolean sAssertLoaded;

    static {
        try {
            System.loadLibrary("sc_sdlprobe");
            sLoaded = true;
        } catch (Throwable ignored) {
            sLoaded = false;
        }
        try {
            System.loadLibrary("sc_sdlassert");
            sAssertLoaded = true;
        } catch (Throwable ignored) {
            sAssertLoaded = false;
        }
    }

    private SCSdlNative() {}

    /** True once the native probe loaded. */
    public static boolean isLoaded() {
        return sLoaded;
    }

    /** True while the game has an active SDL text-input (text field focused). */
    public static native boolean isTextInputActive();

    /**
     * Installs the launcher's SDL assertion handler (log + backtrace + always
     * ignore). Idempotent and safe to call before the game starts; returns true
     * once the handler is in place. Never throws.
     */
    public static boolean installAssertHandler() {
        if (!sAssertLoaded) return false;
        try {
            return installAssertHandlerNative();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True once the assertion handler is installed. */
    public static boolean isAssertHandlerInstalled() {
        if (!sAssertLoaded) return false;
        try {
            return isAssertHandlerInstalledNative();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static native boolean installAssertHandlerNative();

    private static native boolean isAssertHandlerInstalledNative();
}
