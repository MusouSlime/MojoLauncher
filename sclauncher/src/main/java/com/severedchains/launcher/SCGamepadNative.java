package com.severedchains.launcher;

import android.util.Log;

/**
 * Virtual XInput-style gamepad via SDL3's virtual-joystick API (same
 * libSDL3.so instance the game uses, so it appears as a real controller).
 * TouchGamepadView feeds button/axis states here; see sc_gamepad.c.
 */
public class SCGamepadNative {
    private static final String TAG = "SCPad";
    private static int sInstance = -1;
    private static boolean sTriedLoad = false;
    private static boolean sLoaded = false;

    public static boolean ensureLoaded() {
        if (!sTriedLoad) {
            sTriedLoad = true;
            try {
                System.loadLibrary("sc_gamepad");
                sLoaded = true;
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "libsc_gamepad.so missing", e);
            }
        }
        return sLoaded;
    }

    /** Attach once; returns instance id or -1. Safe to call repeatedly. */
    public static int attach() {
        if (!ensureLoaded()) return -1;
        if (sInstance >= 0) return sInstance;
        try {
            sInstance = nativeAttach();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "attach failed", e);
            sInstance = -1;
        }
        return sInstance;
    }

    /** Attach on first input so SDL is already initialized by the game. */
    private static void ensureAttached() {
        if (sInstance < 0) {
            int id = attach();
            Log.i(TAG, id >= 0 ? "lazy-attached virtual pad=" + id : "lazy attach failed");
        }
    }

    public static void setButton(int button, boolean down) {
        ensureAttached();
        if (sInstance < 0) return;
        try {
            nativeSetButton(sInstance, button, down);
        } catch (UnsatisfiedLinkError ignored) {}
    }

    public static void setAxis(int axis, int value) {
        ensureAttached();
        if (sInstance < 0) return;
        try {
            nativeSetAxis(sInstance, axis, value);
        } catch (UnsatisfiedLinkError ignored) {}
    }

    /**
     * Ask native to cache the {@link SCVibration} class + {@code vibrate(int,int)}
     * method id on the app classloader, so the virtual pad's Rumble callback
     * (invoked on the game thread) can call it without FindClass.
     * Safe no-op if the library is missing.
     */
    public static void bindVibration() {
        if (!ensureLoaded()) return;
        try {
            nativeBindVibration();
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    /** Detach the virtual pad so a physical controller takes player 1. */
    public static void detach() {
        if (!sLoaded || sInstance < 0) return;
        try {
            nativeDetach(sInstance);
        } catch (UnsatisfiedLinkError ignored) {}
        sInstance = -1;
        Log.i(TAG, "virtual pad detached for physical controller");
    }

    private static native int nativeAttach();

    private static native void nativeBindVibration();

    private static native void nativeSetButton(int instance, int button, boolean down);

    private static native void nativeSetAxis(int instance, int axis, int value);

    private static native void nativeDetach(int instance);
}
