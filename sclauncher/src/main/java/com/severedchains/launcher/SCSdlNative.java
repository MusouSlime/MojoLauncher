package com.severedchains.launcher;

/**
 * Bridge to the SDL3 library already loaded in the process. Used to detect
 * when the game has a text field focused so the soft keyboard can be raised
 * automatically (Mojo's SDLActivity.showTextInput is a stub).
 */
public final class SCSdlNative {
    private static volatile boolean sLoaded;

    static {
        try {
            System.loadLibrary("sc_sdlprobe");
            sLoaded = true;
        } catch (Throwable ignored) {
            sLoaded = false;
        }
    }

    private SCSdlNative() {}

    /** True once the native probe loaded. */
    public static boolean isLoaded() {
        return sLoaded;
    }

    /** True while the game has an active SDL text-input (text field focused). */
    public static native boolean isTextInputActive();
}
