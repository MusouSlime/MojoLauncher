package com.severedchains.launcher;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

/**
 * Device-vibration sink for game rumble, ported from the Android port at
 * dracinn/Severed-Chains-Android ({@code AndroidPlatformManager.rumble}).
 *
 * <p>Severed Chains emits rumble through SDL ({@code SdlGamepadDevice.rumble}
 * -> {@code SDL_RumbleGamepad}). On Android the Mojo SDL joystick backend only
 * vibrates physical controllers that expose their own vibrator, so touch-only
 * play (the virtual pad) got no feedback at all. {@code sc_gamepad.c} now gives
 * the virtual SDL joystick a {@code Rumble} callback (SDL3 supports this for
 * virtual joysticks) which calls {@link #vibrate(int, int)} here, so rumbles
 * reach the phone's vibrator regardless of the controller in use.
 *
 * <p>All methods are safe no-ops before {@link #init(Context)} runs or on
 * devices without a vibrator.
 */
public final class SCVibration {
    private static final String TAG = "SCVib";

    /** One-shot length: SDL re-drives a held rumble and zeroes it on expiry,
     *  so a long shot that a later (0,0) cancels matches SDL's own Android
     *  haptic behaviour. */
    private static final long RUMBLE_MS = 5000L;

    private static volatile Vibrator sVibrator;

    private SCVibration() {
    }

    /** Grab the device vibrator. Call once from the game activity (any thread). */
    public static void init(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        try {
            Vibrator vibrator = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                final VibratorManager manager =
                        (VibratorManager) app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (manager != null) {
                    vibrator = manager.getDefaultVibrator();
                }
            }
            if (vibrator == null) {
                vibrator = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            }
            sVibrator = vibrator;
            Log.i(TAG, vibrator == null ? "no device vibrator" : "device vibrator ready");
        } catch (final Throwable t) {
            Log.w(TAG, "vibrator lookup failed", t);
        }
        // Have native cache this class + the vibrate() method id on the app
        // classloader so the (game-thread) Rumble callback never has to
        // FindClass from an attached thread (which would use the boot loader).
        try {
            SCGamepadNative.bindVibration();
        } catch (final Throwable t) {
            Log.w(TAG, "vibration binding failed", t);
        }
    }

    /**
     * Called from native {@code sc_gamepad.c} (the virtual joystick Rumble
     * callback). {@code low}/{@code high} are 0..0xffff.
     */
    public static void vibrate(final int low, final int high) {
        final Vibrator vibrator = sVibrator;
        if (vibrator == null) return;

        final int raw = Math.max(low, high);
        if (raw <= 0) {
            cancel();
            return;
        }

        int amplitude = Math.round(raw / 65535.0f * 255.0f);
        if (amplitude < 1) amplitude = 1;
        if (amplitude > 255) amplitude = 255;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(RUMBLE_MS, amplitude));
            } else {
                //noinspection deprecation
                vibrator.vibrate(RUMBLE_MS);
            }
        } catch (final Throwable ignored) {
        }
    }

    /** Stop any active rumble. */
    public static void cancel() {
        final Vibrator vibrator = sVibrator;
        if (vibrator == null) return;
        try {
            vibrator.cancel();
        } catch (final Throwable ignored) {
        }
    }
}
