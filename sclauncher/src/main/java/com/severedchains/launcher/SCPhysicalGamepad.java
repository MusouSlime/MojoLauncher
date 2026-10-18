package com.severedchains.launcher;

import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.severedchains.launcher.ui.TouchGamepadView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Routes physical BT/USB controller input into the game through the SAME
 * channel the touch overlay uses (virtual SDL pad via SCGamepadNative +
 * SDL key events). This is the only input path proven to reach the game
 * (see latestlog "Gamepad SCLauncher Virtual Pad connected"); the raw
 * Android KeyEvents would otherwise fall through to system navigation
 * (B button = BACK = back to launcher).
 *
 * <p>All methods are no-ops until the JVM/game is up (SDL not initialized
 * before that; eager SDL_OpenJoystick trips a native assertion).
 *
 * <p>SDL gamepad indices match TouchGamepadView / sc_gamepad.c (XInput-style,
 * 15 buttons + 6 axes).
 */
public class SCPhysicalGamepad {
    private static final String TAG = "SCPad";

    // Android keycode -> SDL virtual-pad button (see TouchGamepadView).
    private static final Map<Integer, Integer> BUTTONS = new HashMap<>();
    // Android keycode -> SDL virtual-pad axis (digital triggers).
    private static final Map<Integer, Integer> AXIS_KEYS = new HashMap<>();
    static {
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_A, 0); // SOUTH
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_B, 1); // EAST
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_X, 2); // WEST
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_Y, 3); // NORTH
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_SELECT, 4);
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_MODE, 5); // guide
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_START, 6);
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_THUMBL, 7);
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_THUMBR, 8);
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_L1, 9);
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_R1, 10);
        BUTTONS.put(KeyEvent.KEYCODE_DPAD_UP, 11);
        BUTTONS.put(KeyEvent.KEYCODE_DPAD_DOWN, 12);
        BUTTONS.put(KeyEvent.KEYCODE_DPAD_LEFT, 13);
        BUTTONS.put(KeyEvent.KEYCODE_DPAD_RIGHT, 14);
        // B button on some pads/firmwares arrives as BACK: game cancel key.
        BUTTONS.put(KeyEvent.KEYCODE_BACK, 1); // EAST
        AXIS_KEYS.put(KeyEvent.KEYCODE_BUTTON_L2, 4);
        AXIS_KEYS.put(KeyEvent.KEYCODE_BUTTON_R2, 5);
    }

    // MotionEvent axis -> SDL virtual-pad axis.
    private static final int[][] AXES = {
            {MotionEvent.AXIS_X, 0},
            {MotionEvent.AXIS_Y, 1},
            {MotionEvent.AXIS_Z, 2},
            {MotionEvent.AXIS_RZ, 3},
            {MotionEvent.AXIS_LTRIGGER, 4},
            {MotionEvent.AXIS_RTRIGGER, 5},
            {MotionEvent.AXIS_BRAKE, 4},
            {MotionEvent.AXIS_GAS, 5},
    };

    private static final float STICK_DEADZONE = 0.08f;
    private static final float TRIGGER_DEADZONE = 0.02f;
    private static final float HAT_THRESHOLD = 0.5f;

    private static final Set<Integer> sPressedButtons = new HashSet<>();
    private static final Set<Integer> sPressedAxisKeys = new HashSet<>();
    // Hat-as-dpad state: index 0..3 = up/down/left/right (pad buttons 11..14).
    private static final boolean[] sHat = new boolean[4];

    private static boolean isGamepadSource(int src) {
        return (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** @return true if the event was consumed (forwarded to the game). */
    public static boolean handleKeyEvent(KeyEvent e) {
        if (!SCGameActivity.isJvmStarted()) {
            return false;
        }
        int kc = e.getKeyCode();
        int action = e.getAction();

        // B-as-BACK from any source while the game runs = game input, never
        // system navigation.
        boolean isBack = kc == KeyEvent.KEYCODE_BACK;
        if (!isBack && !isGamepadSource(e.getSource())) {
            return false;
        }
        // Never steal volume/media/call keys.
        switch (kc) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_CALL:
            case KeyEvent.KEYCODE_CAMERA:
                return false;
            default:
                break;
        }

        Integer btn = BUTTONS.get(kc);
        Integer axisKey = AXIS_KEYS.get(kc);
        // The gamepad is the primary path; its keyboard mirror is only a
        // fallback for when the virtual pad could not attach, otherwise the game
        // receives each controller press twice (gamepad + key) and menus move
        // two steps per press.
        final boolean mirrorToKeys = !SCGamepadNative.isAttached();
        if (action == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                if (btn != null && sPressedButtons.add(kc)) {
                    SCGamepadNative.setButton(btn, true);
                }
                if (axisKey != null && sPressedAxisKeys.add(kc)) {
                    SCGamepadNative.setAxis(axisKey, 32767);
                }
                if (mirrorToKeys) {
                    sendSdlKey(kc, true);
                }
            }
            return true;
        } else if (action == KeyEvent.ACTION_UP) {
            if (btn != null && sPressedButtons.remove(kc)) {
                SCGamepadNative.setButton(btn, false);
            }
            if (axisKey != null && sPressedAxisKeys.remove(kc)) {
                SCGamepadNative.setAxis(axisKey, 0);
            }
            // Hat released with the key cluster on some pads: clear hat state.
            if (kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN
                    || kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
                clearHat();
            }
            if (mirrorToKeys) {
                sendSdlKey(kc, false);
            }
            return true;
        } else if (action == KeyEvent.ACTION_MULTIPLE) {
            return true;
        }
        return false;
    }

    /** @return true if the event was consumed (forwarded to the game). */
    public static boolean handleMotionEvent(MotionEvent e) {
        if (!SCGameActivity.isJvmStarted()
                || !isGamepadSource(e.getSource())) {
            return false;
        }
        boolean consumed = false;
        for (int[] mapping : AXES) {
            int androidAxis = mapping[0];
            int padAxis = mapping[1];
            try {
                float v = e.getAxisValue(androidAxis);
                float dead = (padAxis >= 4) ? TRIGGER_DEADZONE : STICK_DEADZONE;
                int out = Math.abs(v) < dead ? 0 : Math.round(v * 32767f);
                if (out < -32768) out = -32768;
                if (out > 32767) out = 32767;
                SCGamepadNative.setAxis(padAxis, out);
                consumed = true;
            } catch (IllegalArgumentException ignored) {
                // Device has no such axis.
            }
        }
        // Hat switch doubles as d-pad on many controllers.
        try {
            float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            setHat(0, 11, hy < -HAT_THRESHOLD);
            setHat(1, 12, hy > HAT_THRESHOLD);
            setHat(2, 13, hx < -HAT_THRESHOLD);
            setHat(3, 14, hx > HAT_THRESHOLD);
            consumed = true;
        } catch (IllegalArgumentException ignored) {
        }
        return consumed;
    }

    private static void setHat(int slot, int padButton, boolean down) {
        if (sHat[slot] == down) return;
        sHat[slot] = down;
        SCGamepadNative.setButton(padButton, down);
    }

    private static void clearHat() {
        for (int i = 0; i < sHat.length; i++) {
            if (sHat[i]) {
                sHat[i] = false;
                SCGamepadNative.setButton(11 + i, false);
            }
        }
    }

    private static void sendSdlKey(int androidKey, boolean down) {
        // SDLActivity.onNativeKeyDown/Up take an ANDROID keycode and do the
        // SDL key mapping themselves.
        try {
            if (down) git.mojo.sdl.SDLActivity.onNativeKeyDown(androidKey);
            else git.mojo.sdl.SDLActivity.onNativeKeyUp(androidKey);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError ex) {
            Log.w(TAG, "SDL native not ready, key dropped: " + androidKey);
        }
    }
}
