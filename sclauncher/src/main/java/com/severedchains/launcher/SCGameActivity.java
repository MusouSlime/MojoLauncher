package com.severedchains.launcher;

import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import java.io.File;
import java.util.ArrayList;

import git.artdeell.mojoexec.MojoExec;
import git.mojo.sdl.SDL;
import git.mojo.sdl.SDLActivity;

/**
 * Full game display + full error surface.
 * - SurfaceView -> SDLActivity native surface (same path as Mojo SDLBackend).
 * - SCLogger captures ALL JVM stdout/stderr to latestlog.txt + on-screen log.
 * - JVM starts ONLY after surface-ready; resize/destroy forwarded.
 * - Every failure shows full error + log paths, never a silent spinner.
 *
 * <p>Sections below: process restart, lifecycle, surface callbacks, one-shot
 * JVM start, physical-controller input, BACK handling, SDL messagebox hook.
 */
public class SCGameActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String TAG = "SCGame";

    public static final String EXTRA_RUNTIME = "runtime";
    public static final String EXTRA_VMARGS = "vmargs";
    public static final String EXTRA_CP = "classpath";
    public static final String EXTRA_MAIN = "main";
    public static final String EXTRA_APPARGS = "appargs";
    public static final String EXTRA_DIRDATA = "dirdata";
    public static final String EXTRA_DIRCACHE = "dircache";
    public static final String EXTRA_NATIVELIBDIR = "nativelibdir";
    public static final String EXTRA_GPU_MODE = "gpumode";

    private SurfaceView surfaceView;
    /** Optional launcher touch overlay (PREF_TOUCH_PAD); null when the in-game
     *  mobile-controls mod is used instead. */
    private com.severedchains.launcher.ui.TouchGamepadView mTouchPad;
    private android.widget.EditText keyboardInput;
    private android.widget.ImageButton keyboardButton;
    private android.widget.ImageButton exitButton;
    private android.widget.ImageButton eyeButton;
    /** True when the on-screen controls are hidden via the eye toggle. */
    private boolean mOverlayHidden = false;
    /** True while the exit-to-launcher confirmation is on screen. */
    private boolean mExitConfirmShowing;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private android.hardware.input.InputManager mInputManager;

    // Handheld Mode (launcher pref): the game screen drops the exit button
    // and a single BACK returns to the launcher immediately.
    private static final String PREFS_PAD = "sclauncher_prefs";
    private boolean mHandheldMode = false;
    /** On-screen keyboard button + auto-raise (launcher Options → Layout). */
    private boolean mKeyboardEnabled = true;

    // Touch is always forwarded to SDL as a mouse (motion + left button),
    // which is what the in-game mobile-controls mod's window hooks expect.
    // There is no launcher virtual pad any more: the modded controller jar
    // draws the on-screen controls and consumes these mouse events.
    private boolean mMouseDown = false;

    // Auto soft keyboard: Mojo's SDLActivity.showTextInput is a stub, so the
    // game can never summon the IME itself. Poll SDL_TextInputActive() and
    // raise the keyboard while the game has a text field focused.
    private static final long KEYBOARD_POLL_MS = 300;
    private boolean mAutoKeyboardActive = false;
    private final Handler keyboardHandler = new Handler(Looper.getMainLooper());
    private final Runnable mKeyboardPoll = new Runnable() {
        @Override
        public void run() {
            try {
                final boolean active = mKeyboardEnabled
                        && SCSdlNative.isLoaded() && SCSdlNative.isTextInputActive();
                if (active != mAutoKeyboardActive) {
                    mAutoKeyboardActive = active;
                    if (active) {
                        SCLogger.appendToLog("text input active -> showing soft keyboard");
                        SCActionTrail.record("Text input active (auto keyboard)");
                        showGameKeyboard();
                    } else {
                        SCLogger.appendToLog("text input inactive -> hiding soft keyboard");
                        hideGameKeyboard();
                    }
                }
            } catch (Throwable ignored) {}
            keyboardHandler.postDelayed(this, KEYBOARD_POLL_MS);
        }
    };

    private void loadHandheldPref() {
        try {
            mHandheldMode = getSharedPreferences(PREFS_PAD, MODE_PRIVATE)
                    .getBoolean(SCLauncherActivity.PREF_HANDHELD, false);
        } catch (Exception ignored) {
            mHandheldMode = false;
        }
    }

    private void loadKeyboardPref() {
        try {
            mKeyboardEnabled = getSharedPreferences(PREFS_PAD, MODE_PRIVATE)
                    .getBoolean(SCLauncherActivity.PREF_KEYBOARD_ENABLED, true);
        } catch (Exception ignored) {
            mKeyboardEnabled = true;
        }
    }

    private boolean loadTouchPadPref() {
        // Default ON: the launcher pad is now the primary on-screen controller
        // (replaces the in-game mobile-controls mod). Users can still hide it
        // from the launcher menu to use pure touch-as-mouse.
        try {
            return getSharedPreferences(PREFS_PAD, MODE_PRIVATE)
                    .getBoolean(SCLauncherActivity.PREF_TOUCH_PAD, true);
        } catch (Exception ignored) {
            return true;
        }
    }

    /**
     * Forwards the primary finger to SDL as an absolute mouse. Coordinates
     * are SurfaceView pixels, which equal the SDL window (surface size) that
     * the game/mod divides by Window.getWidth()/getHeight() in toOrtho().
     * Mirrors Mojo's SDLBackend.sendMousePosition/sendMouseEvent exactly.
     */
    private void forwardTouchAsMouse(MotionEvent e) {
        if (!isJvmStarted()) return;
        int action = e.getActionMasked();
        float x = e.getX();
        float y = e.getY();
        try {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, x, y, false);
                    SDLActivity.onNativeMouseButton(MotionEvent.BUTTON_PRIMARY, 1, x, y, false);
                    mMouseDown = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, x, y, false);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    SDLActivity.onNativeMouseButton(MotionEvent.BUTTON_PRIMARY, 0, x, y, false);
                    mMouseDown = false;
                    break;
                default:
                    break; // secondary pointers ignored (primary drives the mouse)
            }
        } catch (UnsatisfiedLinkError err) {
            SCLogger.appendToLog("mouse forward failed: " + err.getMessage());
        }
    }

    private boolean jvmStarted = false;
    /** Process-wide: HotSpot allows exactly one JVM per process. A second
     *  SCGameActivity instance (relaunch without process death) must re-attach
     *  the surface, never call JNI_CreateJavaVM again (JNI_EEXIST). */
    private static volatile boolean sJvmStarted = false;

    /** True once the game JVM is up (SDL initialized, input may be sent). */
    public static boolean isJvmStarted() {
        return sJvmStarted;
    }
    private boolean surfaceReady = false;
    private int mLastSurfaceW;
    private int mLastSurfaceH;
    private static SCGameActivity sInstance;
    /** Renderer mode passed from the launcher (snapdragon or mali, auto-selected).
     * Read in onCreate, consumed by setGL4ESEnv. */
    private String mGpuMode = "snapdragon";

    /** Called from native exit/abort hooks when the game ends the VM. */
    public static void onGameExit(int code) {
        SCGameActivity a = sInstance;
        if (a == null) return;
        SCLogger.appendToLog(code == 0
                ? "Game exited normally (code 0) — restarting launcher for a fresh process."
                : "Game exited with code " + code + " — see latestlog.txt, restarting launcher.");
        SCActionTrail.record("Game exited (code " + code + ")");
        a.ui.post(() -> a.restartFresh());
    }

    /**
     * HotSpot allows exactly one JVM per process (re-creation after return or
     * destroy fails with JNI_EEXIST). Like Mojo (restartLauncherActivity +
     * fullyExit), always relaunch into a fresh process after the game ends.
     */
    private void restartFresh() {
        try {
            android.content.Intent i = new android.content.Intent(
                    getApplicationContext(), SCLauncherActivity.class);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
            getApplicationContext().startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "restart failed", e);
        }
        LogFile.flush();
        // Clean exit: drop the crash-recovery marker so the next launch doesn't
        // treat this as a hard kill. Then export with a bounded wait —
        // killProcess() skips onDestroy, so this is the only chance.
        SCLogExporter.endGameSession(getApplicationContext());
        SCLogExporter.exportBlocking(getApplicationContext(), "game-exit", 4000);
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;
        setContentView(R.layout.activity_scgame);

        // Never sleep/dim/lock mid-game (screen timeout + lockscreen stay off
        // while this activity is visible; cleared in onDestroy).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Full-bleed first: the SurfaceView must span the true physical
        // display (under bars/cutout) or the game gets a narrower surface
        // than the screen and the widescreen image is pillarboxed.
        applyFullBleed();
        enableImmersive();
        // Let the IME overlay the game instead of resizing the surface; the
        // hidden SDLTextInputView is on-screen so showSoftInput still works.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED);

        surfaceView = findViewById(R.id.gameSurface);
        keyboardInput = findViewById(R.id.gameKeyboardInput);
        keyboardButton = findViewById(R.id.keyboardButton);
        if (keyboardButton != null) {
            keyboardButton.setOnClickListener(v -> showGameKeyboard());
        }
        exitButton = findViewById(R.id.exitButton);
        if (exitButton != null) {
            exitButton.setOnClickListener(v -> confirmExitToLauncher());
        }
        eyeButton = findViewById(R.id.eyeButton);
        if (eyeButton != null) {
            eyeButton.setOnClickListener(v -> toggleOverlayVisibility());
        }
        setupKeyboardForwarding();

        // The Legend of Dragoon-themed launcher pad (TouchGamepadView) replaces
        // the in-game mobile-controls mod. It is layered above the surface,
        // consumes the touches it owns, and lets everything else fall through
        // to the finger-as-mouse path (menus/UI taps).
        if (surfaceView != null) {
            surfaceView.setOnTouchListener((v, e) -> { forwardTouchAsMouse(e); return true; });
        }
        if (loadTouchPadPref()) {
            try {
                mTouchPad = new com.severedchains.launcher.ui.TouchGamepadView(this);
                final float opacity = getSharedPreferences(PREFS_PAD, MODE_PRIVATE)
                        .getFloat("launcher_touch_opacity", 1.0f);
                mTouchPad.setOpacity(opacity);
                final android.view.ViewGroup root = (android.view.ViewGroup) surfaceView.getParent();
                root.addView(mTouchPad, new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
                SCLogger.appendToLog("Dragoon pad enabled (Legend of Dragoon theme)");
            } catch (Throwable t) {
                mTouchPad = null;
                SCLogger.appendToLog("launcher touch pad setup failed: " + t);
            }
        }

        // Handheld Mode: drop the exit button and let a single BACK return to
        // the launcher. The IME auto-raise poll runs in ALL modes: it watches
        // SDL_TextInputActive() (the game calls SDL_StartTextInput when a text
        // field is focused) and shows/hides the keyboard accordingly.
        loadHandheldPref();
        loadKeyboardPref();
        if (exitButton != null) {
            exitButton.setVisibility(mHandheldMode ? View.GONE : View.VISIBLE);
        }
        if (keyboardButton != null) {
            keyboardButton.setVisibility(mKeyboardEnabled ? View.VISIBLE : View.GONE);
        }
        SCLogger.appendToLog("input mode -> MOUSE" + (mHandheldMode ? " (handheld)" : ""));
        keyboardHandler.post(mKeyboardPoll);

        syncGamepadVisibility();
        mInputManager = (android.hardware.input.InputManager)
                getSystemService(android.content.Context.INPUT_SERVICE);
        if (mInputManager != null) {
            mInputManager.registerInputDeviceListener(mDeviceListener, ui);
        }

        LogFile.log("SCGameActivity onCreate");
        String gpuMode = getIntent() != null
                ? getIntent().getStringExtra(EXTRA_GPU_MODE) : null;
        if (gpuMode == null) gpuMode = "snapdragon";
        mGpuMode = gpuMode;
        boolean maliHw = false;
        try {
            maliHw = com.severedchains.launcher.utils.GpuDetectUtils.isMali(this);
        } catch (Throwable ignored) {}
        SCLogger.appendToLog("GPU mode: " + gpuMode
                 + (maliHw ? " (Mali hardware, native GLES, PLAIN probe tmd shaders)"
                         : " (stock shaders, native GLES)"));
        SCActionTrail.record("Game screen opened [GPU:" + gpuMode
                + (maliHw ? "+MaliHW" : "") + "]");
        // Game output stays in backend files (latestlog.txt); no on-screen log.
        SCLogger.begin(getFilesDir());
        // Mark the session so a hard kill (VM abort/SIGKILL) still leaves a log
        // the next launcher start can export as crash-recovery.
        SCLogExporter.beginGameSession(getApplicationContext());

        // Load bundled C++ runtime first: JRE 25's libjvm.so DT_NEEDs it and the
        // classloader namespace only resolves libs in our nativeLibraryDir.
        try {
            System.loadLibrary("c++_shared");
        } catch (UnsatisfiedLinkError e) {
            SCLogger.appendToLog("libc++_shared load failed: " + e.getMessage());
        }
        try {
            System.loadLibrary("mojoexec");
            SCLogger.appendToLog("libmojoexec loaded");
        } catch (UnsatisfiedLinkError e) {
            SCLogger.appendToLog("libmojoexec load failed: " + e.getMessage());
        }

        setGL4ESEnv();
        initSDL();

        // Game rumble -> device vibrator (virtual-pad Rumble callback in
        // sc_gamepad.c calls SCVibration.vibrate).
        SCVibration.init(this);

        // Native OpenAL backend probe (which of OpenSL/Oboe actually opens,
        // outside LWJGL). Result lands in latestlog.txt for diagnosis.
        final String openalPath = nativeLibDirOf(getIntent()) + "/libopenal.so";
        new Thread(() -> {
            try {
                if (!SCAudioProbe.isLoaded()) {
                    SCLogger.appendToLog("audioprobe: native lib missing, skipped");
                    return;
                }
                String report = SCAudioProbe.probe(openalPath);
                SCLogger.appendToLog(report != null ? report : "audioprobe: null report");
            } catch (Throwable t) {
                SCLogger.appendToLog("audioprobe failed: " + t);
            }
        }, "SCAudio-Probe").start();

        surfaceView.getHolder().addCallback(this);
    }

    /**
     * Full-screen play: hide status + gesture/nav bars (sticky immersive, so
     * an edge swipe reveals them transiently and they auto-hide again).
     * Re-applied on resume/focus because the system clears it on dialogs,
     * IME show/hide, and every surface change.
     */
    private void enableImmersive() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(android.view.WindowInsets.Type.statusBars()
                            | android.view.WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                @SuppressWarnings("deprecation")
                int flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
                getWindow().getDecorView().setSystemUiVisibility(flags);
            }
        } catch (Exception e) {
            Log.w(TAG, "immersive failed", e);
        }
    }

    /**
     * Draw edge-to-edge and through the camera cutout. Without this the
     * window is inset by the display cutout / bars, so the SurfaceView (and
     * therefore the game's SDL window) is narrower than the physical screen
     * and the widescreen image sits between black bands. Idempotent.
     */
    private void applyFullBleed() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            }
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Exception e) {
            Log.w(TAG, "full-bleed mode failed", e);
        }
    }

    /**
     * Physical display size in the current orientation (includes the cutout
     * and any bar areas the app draws under). {@code getRealMetrics} is
     * deprecated on API 31+ but is the only call that returns the true
     * physical size across our minSdk-21 range.
     */
    @SuppressWarnings("deprecation")
    private int[] realDisplaySize() {
        try {
            DisplayMetrics m = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(m);
            return new int[]{m.widthPixels, m.heightPixels};
        } catch (Exception e) {
            return null;
        }
    }

    /** Reports surface vs physical-display size so a pillarbox is visible in
     *  every log (surface < display == the widescreen bug). */
    private void logSurfaceVsDisplay(int surfaceW, int surfaceH) {
        int[] real = realDisplaySize();
        if (real == null) {
            SCLogger.appendToLog("display size: unknown (real metrics unavailable)");
            return;
        }
        int rw = real[0];
        int rh = real[1];
        // Normalize to the surface orientation before comparing.
        if ((surfaceW > surfaceH) != (rw > rh)) {
            int t = rw;
            rw = rh;
            rh = t;
        }
        SCLogger.appendToLog("display: surface " + surfaceW + "x" + surfaceH
                + ", physical " + rw + "x" + rh
                + (surfaceW < rw ? " (PILLARBOX: " + (rw - surfaceW) + "px unused)" : " (full)"));
    }

    /** User-facing way back to the launcher from the game screen (the double
     *  BACK gesture is undiscoverable on touch-only devices). Restarting in a
     *  fresh process is required: HotSpot allows one JVM per process. */
    private void confirmExitToLauncher() {
        if (mExitConfirmShowing) return;
        mExitConfirmShowing = true;
        SCActionTrail.record("Exit-to-launcher button pressed");
        try {
            new AlertDialog.Builder(this)
                    .setTitle("Return to launcher?")
                    .setMessage("The game will close. Save progress first — "
                            + "saves are kept in the Saves/ folder.")
                    .setPositiveButton("Return", (d, w) -> {
                        mExitConfirmShowing = false;
                        SCLogger.appendToLog("exit button: returning to launcher");
                        restartFresh();
                    })
                    .setNegativeButton("Cancel", (d, w) -> mExitConfirmShowing = false)
                    .setOnCancelListener(d -> mExitConfirmShowing = false)
                    .show();
        } catch (Exception e) {
            mExitConfirmShowing = false;
            Log.w(TAG, "exit dialog failed", e);
            restartFresh();
        }
    }

    private String nativeLibDirOf(android.content.Intent intent) {        String dir = intent != null ? intent.getStringExtra(EXTRA_NATIVELIBDIR) : null;
        return dir != null ? dir : getApplicationInfo().nativeLibraryDir;
    }

    private void setGL4ESEnv() {
        try {
            android.system.Os.setenv("LIBGL_MIPMAP", "3", true);
            android.system.Os.setenv("LIBGL_NOERROR", "1", true);
            android.system.Os.setenv("LIBGL_NOINTOVLHACK", "1", true);
            android.system.Os.setenv("LIBGL_NORMALIZE", "1", true);
            android.system.Os.setenv("MESA_GLSL_CACHE_DIR", getCacheDir().getAbsolutePath(), true);
            // OpenAL-Soft backend order. Oboe (AAudio) first, OpenSL ES second.
            // The Oboe backend is the one that actually opens on modern
            // Android (13 on the Odin2), and it is also the backend whose
            // device is reported as "Oboe Default"; OpenSL ES is only a
            // fallback for devices where AAudio refuses to start.
            //
            // NOTE: the backend has nothing to do with LWJGL's
            // ALCapabilities.OpenAL10 flag, which is false on this device
            // because the bundled libopenal.so does not export LWJGL's own
            // al*Direct entry points (see the launcher's own audioprobe
            // output and GameJarPatcher's AudioThread patch).
            android.system.Os.setenv("ALSOFT_DRIVERS", "oboe,opensl", true);
            // OpenAL-Soft diagnostics (backend open/realize failures) go to
            // stderr, which SCLogger already captures to latestlog.txt.
            // Level 2 = errors + warnings, no per-frame trace spam.
            android.system.Os.setenv("ALSOFT_LOGLEVEL", "2", true);
            String nativeLibDir = getIntent().getStringExtra(EXTRA_NATIVELIBDIR);
            if (nativeLibDir == null) nativeLibDir = getApplicationInfo().nativeLibraryDir;
            try {
                MojoExec.setNativeLibraryDir(nativeLibDir);
                SCLogger.appendToLog("MojoExec nativeLibraryDir=" + nativeLibDir);
            } catch (UnsatisfiedLinkError e) {
                SCLogger.appendToLog("MojoExec native missing: " + e.getMessage());
            }
            // Renderer is native GLES on all GPUs (Zink/ANGLE wrappers
            // removed — both crash Mali drivers). No prepareEgl call; SDL
            // creates the context from the system EGL before init.
            try {
                String rendererReport = SCRenderer.apply(this, mGpuMode);
                SCLogger.appendToLog(rendererReport);
            } catch (Throwable t) {
                SCLogger.appendToLog("Renderer setup threw: " + t);
            }
        } catch (Exception e) {
            Log.e(TAG, "GL env failed", e);
            SCLogger.appendToLog("GL env failed: " + e);
        }
    }

    private void initSDL() {
        try {
            SDL.initialize();
            SDL.setContext(this);
            SDL.setupJNI();
            // Mojo's SDLBackend sets these; SDLActivity dereferences them
            // unconditionally (setSystemCursor/setCustomCursor/setRelativeMouseEnabled).
            // No-op: touch-first bring-up, no cursor view yet.
            SDLActivity.setGrabListener(enabled -> {});
            SDLActivity.setCursorCallback(cursor -> {});
            SCLogger.appendToLog("SDL initialized");
            // SDL's stock assertion path is modal and its macro LOOPS on the
            // value the dialog reports (SDL_ASSERTION_RETRY). Mojo's SDL has
            // assertions live, so install a handler that logs + ignores.
            if (SCSdlNative.installAssertHandler()) {
                SCLogger.appendToLog("SDL assertion handler installed (asserts logged, never modal)");
            } else {
                SCLogger.appendToLog("SDL assertion handler NOT installed (will rely on the messagebox fallback)");
            }
            // NOTE: virtual gamepad attaches lazily on first button press
            // (SCGamepadNative), i.e. after the game ran SDL_Init — attaching
            // earlier trips an SDL assertion inside SDL_OpenJoystick.
        } catch (Throwable t) {
            Log.e(TAG, "SDL init failed", t);
            SCLogger.appendToLog("SDL init FAILED: " + t);
            showFatal("SDL init failed: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Surface callbacks: attach the native surface on create (then start the
    // JVM once), forward genuine resizes, tear down on destroy. Transient
    // inset/nav-bar passes are filtered so the game doesn't jump mid-frame.
    // ------------------------------------------------------------------
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Surface s = holder.getSurface();
        SCLogger.appendToLog("surfaceCreated valid=" + (s != null && s.isValid()));
        try {
            if (SDLActivity.getNativeSurface() != null) SDLActivity.onNativeSurfaceDestroyed();
            SDLActivity.setNativeSurface(s);
            SDLActivity.onNativeSurfaceCreated();
            int w = surfaceView.getWidth() > 0 ? surfaceView.getWidth() : 1280;
            int h = surfaceView.getHeight() > 0 ? surfaceView.getHeight() : 720;
            mLastSurfaceW = w;
            mLastSurfaceH = h;
            logSurfaceVsDisplay(w, h);
            float rate = getWindowManager().getDefaultDisplay().getRefreshRate();
            pushSurfaceSize(w, h, rate);
            SDLActivity.onNativeSurfaceChanged();
            // surfaceCreated can fire before the full-bleed/immersive relayout
            // settles, leaving the SurfaceView measured with bar/cutout insets.
            // Re-forward the settled size once layout completes.
            surfaceView.postDelayed(this::forwardCurrentSurfaceSize, 300);
        } catch (Throwable t) {
            SCLogger.appendToLog("surface attach FAILED: " + Log.getStackTraceString(t));
            showFatal("Surface attach failed: " + t.getMessage());
            return;
        }
        surfaceReady = true;
        startJvmOnce();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Forward only genuine size changes: Android re-fires surfaceChanged
        // for transient inset/nav-bar passes, and each forward makes the game
        // re-center its window mid-frame (visible jump).
        if (width == mLastSurfaceW && height == mLastSurfaceH) return;
        mLastSurfaceW = width;
        mLastSurfaceH = height;
        SCLogger.appendToLog("surfaceChanged " + width + "x" + height);
        logSurfaceVsDisplay(width, height);
        try {
            float rate = getWindowManager().getDefaultDisplay().getRefreshRate();
            pushSurfaceSize(width, height, rate);
        } catch (Throwable t) {
            SCLogger.appendToLog("resize failed: " + t.getMessage());
        }
    }

    /**
     * Forwards a surface size to SDL. The surface is the window size; the
     * PHYSICAL display size is the "device" size (SDL uses it for the desktop
     * display mode + orientation). Passing the surface for both made SDL
     * believe the display was only as large as the (possibly inset) surface.
     */
    private void pushSurfaceSize(int w, int h, float rate) {
        int dw = w;
        int dh = h;
        int[] real = realDisplaySize();
        if (real != null) {
            dw = real[0];
            dh = real[1];
            // Keep the device size in the same orientation as the surface.
            if ((w > h) != (dw > dh)) {
                int t = dw;
                dw = dh;
                dh = t;
            }
        }
        try {
            MojoExec.setDisplayParams(w, h, rate);
        } catch (UnsatisfiedLinkError ignored) {}
        SDLActivity.nativeSetScreenResolution(w, h, dw, dh, 1.0f, rate);
        SDLActivity.onNativeResize();
    }

    /** Re-forwards the settled SurfaceView size (used after a full-bleed /
     *  immersive relayout that Android did not deliver as surfaceChanged). */
    private void forwardCurrentSurfaceSize() {
        if (surfaceView == null) return;
        int w = surfaceView.getWidth();
        int h = surfaceView.getHeight();
        if (w <= 0 || h <= 0) return;
        if (w == mLastSurfaceW && h == mLastSurfaceH) return;
        mLastSurfaceW = w;
        mLastSurfaceH = h;
        SCLogger.appendToLog("surface re-measured " + w + "x" + h);
        logSurfaceVsDisplay(w, h);
        try {
            float rate = getWindowManager().getDefaultDisplay().getRefreshRate();
            pushSurfaceSize(w, h, rate);
        } catch (Throwable t) {
            SCLogger.appendToLog("re-measure resize failed: " + t.getMessage());
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        SCLogger.appendToLog("surfaceDestroyed");
        surfaceReady = false;
        try {
            SDLActivity.onNativeSurfaceDestroyed();
        } catch (Throwable ignored) {}
    }

    // Mojo's SDLBackend forwards these so the game pauses rendering instead of
    // drawing into a dead/backgrounded surface (which can kill the SDL/EGL state
    // before the game ever shows a frame).
    @Override
    protected void onPause() {
        super.onPause();
        try {
            SDLActivity.nativeVisibilityChanged(false);
        } catch (Throwable ignored) {}
        SCLogger.appendToLog("activity paused");
        // A pause is not a crash. Clear the hard-kill marker so that pressing
        // Home / Recents, or the OS killing the backgrounded app, doesn't turn
        // into a false "previous session did not exit cleanly" report.
        SCLogExporter.endGameSession(getApplicationContext());
        SCLogger.appendToLog("session marker cleared on pause (not a crash)");
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Belt-and-braces: some OEMs can stop without delivering onPause to the
        // same process state. A stopped game is not a foreground crash.
        SCLogExporter.endGameSession(getApplicationContext());
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyFullBleed();
        enableImmersive();
        try {
            SDLActivity.nativeVisibilityChanged(true);
        } catch (Throwable ignored) {}
        SCLogger.appendToLog("activity resumed");
        // We are foreground again: re-arm the marker so a genuine VM abort /
        // SIGKILL while playing is still exported as crash recovery.
        SCLogExporter.beginGameSession(getApplicationContext());
        SCLogger.appendToLog("session marker re-armed on resume");
        syncGamepadVisibility();
        // Bars/cutout may reappear around a resume and change the usable
        // surface; re-forward once the relayout settles.
        if (surfaceView != null) surfaceView.postDelayed(this::forwardCurrentSurfaceSize, 300);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enableImmersive();
        try {
            SDLActivity.nativeFocusChanged(hasFocus);
        } catch (Throwable ignored) {}
        SCLogger.appendToLog(hasFocus ? "window focused" : "window focus lost");
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        SCLogger.appendToLog("onNewIntent: reusing existing game activity (JVM stays)");
    }

    // ------------------------------------------------------------------
    // One-shot JVM start (background thread). Guarded process-wide: HotSpot
    // allows exactly one JVM per process, so a second activity instance only
    // re-attaches the surface (JNI_EEXIST is a re-attach, not an error).
    // ------------------------------------------------------------------
    private void startJvmOnce() {
        if (jvmStarted || sJvmStarted) {
            SCLogger.appendToLog("JVM already started, surface re-attached");
            return;
        }
        jvmStarted = true;
        sJvmStarted = true;
        new Thread(() -> {
            try {
                String runtimeName = getIntent().getStringExtra(EXTRA_RUNTIME);
                ArrayList<String> vmArgs = getIntent().getStringArrayListExtra(EXTRA_VMARGS);
                ArrayList<String> cp = getIntent().getStringArrayListExtra(EXTRA_CP);
                String mainClass = getIntent().getStringExtra(EXTRA_MAIN);
                ArrayList<String> appArgs = getIntent().getStringArrayListExtra(EXTRA_APPARGS);
                String dirData = getIntent().getStringExtra(EXTRA_DIRDATA);
                String dirCache = getIntent().getStringExtra(EXTRA_DIRCACHE);
                String nativeLibDir = getIntent().getStringExtra(EXTRA_NATIVELIBDIR);

                if (runtimeName == null || vmArgs == null || cp == null || mainClass == null) {
                    throw new IllegalStateException("Missing launch extras (runtime/vmargs/classpath/main)");
                }

                System.setProperty("sc.dir.data", dirData);
                System.setProperty("sc.dir.cache", dirCache);
                System.setProperty("sc.native.lib.dir", nativeLibDir);
                try {
                    git.artdeell.mojoexec.MojoExec.setNativeLibraryDir(nativeLibDir);
                } catch (UnsatisfiedLinkError ignored) {}

                SCLogger.appendToLog("Starting JVM: " + mainClass + " cp=" + cp.size());
                SCActionTrail.record("JVM starting: " + mainClass);
                for (String a : vmArgs) {
                    if (a.contains("platform.library.path") || a.contains("java.library.path")) {
                        SCLogger.appendToLog("VMARG " + a);
                    }
                }
                // NOTE: no ART System.load preload here — ART aborts (CheckJNI) on
                // foreign .so files (tombstone: ClassNotFound Loader in nativeLoad).
                // Native preload happens inside jvmrunner.c via dlopen instead.

                // First run: the ISO rip happens inside this JVM and keeps its
                // file tree/threads resident. On a 2 GB/low-RAM device that
                // residue plus the title/game is what makes the end of the rip
                // look like a crash. Once the game writes files/version, restart
                // this process so the second launch starts with only the game.
                final java.io.File versionFile = new java.io.File(
                        dirData, "files/games/SCLauncher/files/version");
                final java.io.File patchCache = new java.io.File(
                        dirData, "files/games/SCLauncher/files/patches/cache/scripts.csv");
                boolean lowRam = false;
                try {
                    android.app.ActivityManager am = (android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE);
                    lowRam = am != null && am.isLowRamDevice();
                } catch(Throwable ignored) {
                }
                final boolean firstRunWatch = !versionFile.isFile()
                        && (lowRam || "x86_64".equals(System.getProperty("os.arch")));
                if(firstRunWatch) {
                    new Thread(() -> {
                        try {
                            for(int i = 0; i < 1200; i++) {
                                // The unpacker creates the marker empty and writes
                                // its content last; restarting on existence alone
                                // kills the app mid-rip (the "crash at the end").
                                if(versionFile.isFile() && versionFile.length() > 0
                                        && patchCache.isFile()) {
                                    Thread.sleep(2000);
                                    if(versionFile.length() > 0 && patchCache.isFile()) {
                                        SCLogger.appendToLog(
                                                "Unpack + script-patch cache complete — restarting app to release unpacker memory");
                                        ui.post(() -> restartFresh());
                                        return;
                                    }
                                }
                                Thread.sleep(500);
                            }
                        } catch(InterruptedException ignored) {
                        }
                    }, "SCGame-UnpackWatch").start();
                }

                JVMRunner.Runtime rt = new JVMRunner.Runtime(runtimeName, System.getProperty("os.arch"), 25);
                JVMRunner.startJvm(rt, vmArgs, cp, mainClass, appArgs == null ? new ArrayList<>() : appArgs);

                SCLogger.appendToLog("JVM main() returned — restarting launcher for a fresh process. Full log: "
                        + (SCLogger.getLatestLogFile() != null ? SCLogger.getLatestLogFile().getAbsolutePath() : "?")
                        + " + " + (LogFile.getLogFile() != null ? LogFile.getLogFile().getAbsolutePath() : "?"));
                ui.post(() -> restartFresh());
            } catch (Throwable e) {
                String msg = String.valueOf(e.getMessage());
                // Race guard: second JNI_CreateJavaVM in one process -> EEXIST.
                // The first VM is still running the game; this is a re-attach,
                // not a fatal error. Never show the error dialog for it.
                if (msg.contains("EEXIST") || msg.contains("already created")) {
                    Log.i(TAG, "Second JVM start ignored (VM already running)", e);
                    SCLogger.appendToLog("JVM already running in this process, surface re-attached");
                    return;
                }
                // Real failure: allow a later retry.
                jvmStarted = false;
                sJvmStarted = false;
                Log.e(TAG, "Game launch failed", e);
                SCLogger.appendToLog("LAUNCH FAILED: " + e);
                SCLogger.appendToLog(Log.getStackTraceString(e));
                ui.post(() -> showFatal(fullReport(e)));
            }
        }, "SCGame-JVM").start();
    }

    private String fullReport(Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append("Game failed: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n\n");
        sb.append(Log.getStackTraceString(e)).append("\n\n");
        File latest = SCLogger.getLatestLogFile();
        File launcher = LogFile.getLogFile();
        sb.append("latestlog.txt: ").append(latest != null ? latest.getAbsolutePath() : "?").append("\n");
        sb.append("launcher log: ").append(launcher != null ? launcher.getAbsolutePath() : "?").append("\n");
        sb.append("logcat tags: jrelog,JVMRunner,LIBGL,SDL,SCGame,SCLauncher\n");
        return sb.toString();
    }

    private void showFatal(String report) {
        try {
            new AlertDialog.Builder(this)
                    .setTitle("SC Game Error (full log kept)")
                    .setMessage(report.length() > 2000 ? report.substring(0, 2000) + "\n…(full in latestlog.txt)" : report)
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        if (sInstance == this) sInstance = null;
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Exception ignored) {}
        keyboardHandler.removeCallbacks(mKeyboardPoll);
        hideGameKeyboard();
        try {
            if (mInputManager != null) mInputManager.unregisterInputDeviceListener(mDeviceListener);
        } catch (Exception ignored) {}
        // Normal close (back-out, system finish): drop the session log where
        // the user can reach it. Fire-and-forget; the process stays alive.
        SCLogExporter.endGameSession(getApplicationContext());
        SCLogExporter.exportAsync(this, "game-close");
        super.onDestroy();
    }

    /**
     * Physical controllers are mirrored into the virtual SDL pad by
     * SCPhysicalGamepad. Nothing to hide any more (no launcher overlay), so
     * this only refreshes the informational state.
     */
    private void onPhysicalGamepadSeen() {
        syncGamepadVisibility();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        // Physical gamepad (incl. B-as-BACK) goes to the game, never to
        // system navigation. Consumed events never reach the BACK handler.
        if (SCPhysicalGamepad.handleKeyEvent(e)) {
            onPhysicalGamepadSeen();
            return true;
        }
        // Physical hardware keyboards type into the game (SDL text-input events
        // for printable chars + SDL key events for navigation). Without this,
        // keys fall through to the system and never reach the game.
        if (handlePhysicalKeyboard(e)) {
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /**
     * Routes a physical (BT/USB) keyboard to the game: printable characters via
     * SDL text input, Delete/Return/arrows/digits via SDL key events. Volume,
     * media and camera keys are left to the system. Consumes only once the JVM
     * is up.
     */
    private boolean handlePhysicalKeyboard(android.view.KeyEvent e) {
        if (!isJvmStarted()) return false;
        if ((e.getSource() & android.view.InputDevice.SOURCE_KEYBOARD)
                != android.view.InputDevice.SOURCE_KEYBOARD) {
            return false;
        }
        int kc = e.getKeyCode();
        switch (kc) {
            case android.view.KeyEvent.KEYCODE_VOLUME_UP:
            case android.view.KeyEvent.KEYCODE_VOLUME_DOWN:
            case android.view.KeyEvent.KEYCODE_VOLUME_MUTE:
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY:
            case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE:
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case android.view.KeyEvent.KEYCODE_MEDIA_NEXT:
            case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case android.view.KeyEvent.KEYCODE_MEDIA_STOP:
            case android.view.KeyEvent.KEYCODE_CALL:
            case android.view.KeyEvent.KEYCODE_CAMERA:
                return false;
            default:
                break;
        }
        int action = e.getAction();
        if (action == android.view.KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() > 0) return true; // ignore auto-repeat
            if (kc == android.view.KeyEvent.KEYCODE_DEL) {
                sendSdlBackspace();
                return true;
            }
            if (kc == android.view.KeyEvent.KEYCODE_ENTER) {
                sendSdlKey(android.view.KeyEvent.KEYCODE_ENTER);
                return true;
            }
            int unicode = e.getUnicodeChar();
            if (unicode >= 32) {
                commitSdlText(new String(Character.toChars(unicode)));
                return true;
            }
            sendSdlKeyDown(kc);
            return true;
        }
        if (action == android.view.KeyEvent.ACTION_UP) {
            if (kc == android.view.KeyEvent.KEYCODE_DEL
                    || kc == android.view.KeyEvent.KEYCODE_ENTER) {
                return true;
            }
            if (e.getUnicodeChar() >= 32) return true;
            sendSdlKeyUp(kc);
            return true;
        }
        return action == android.view.KeyEvent.ACTION_MULTIPLE;
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        // Sticks/triggers/hat mirror into the virtual SDL pad.
        if (SCPhysicalGamepad.handleMotionEvent(e)) {
            onPhysicalGamepadSeen();
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private long mLastBackPress;
    private static final long BACK_EXIT_WINDOW_MS = 1500;

    /**
     * BACK is game input (button B) while the game runs — dispatchKeyEvent
     * already consumed it, so this only fires for non-gamepad BACK (gesture
     * nav). Single press is swallowed; double press exits to the launcher.
     * HOME/recents always work; the error dialog has its own OK button.
     */
    @Override
    public void onBackPressed() {
        if (!isJvmStarted()) {
            super.onBackPressed();
            return;
        }
        // Handheld Mode: a single BACK returns to the launcher (the exit
        // button is hidden in this mode, so BACK is the way out).
        if (mHandheldMode) {
            SCLogger.appendToLog("back: exiting to launcher (handheld mode)");
            SCActionTrail.record("Back: exit to launcher (handheld)");
            restartFresh();
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - mLastBackPress < BACK_EXIT_WINDOW_MS) {
            SCLogger.appendToLog("double-back: exiting to launcher");
            SCActionTrail.record("Back double-press: exit to launcher");
            // Restart into a fresh process (same as the Exit button): simply
            // finishing the activity would leave the game JVM running and a
            // relaunch would hit JNI_EEXIST.
            restartFresh();
            return;
        }
        mLastBackPress = now;
        SCActionTrail.record("Back pressed (sent to game)");
        SCLogger.appendToLog("back swallowed (game input); double-press to exit");
    }

    /** True if any BT/USB controller is currently attached. */
    private static boolean hasPhysicalGamepad() {
        try {
            for (int id : android.view.InputDevice.getDeviceIds()) {
                android.view.InputDevice dev = android.view.InputDevice.getDevice(id);
                if (dev == null) continue;
                int src = dev.getSources();
                if ((src & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                        || (src & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** The launcher pad (TouchGamepadView) IS the on-screen controller again
     *  (Legend of Dragoon theme, see SCGameActivity.onCreate); it replaced the
     *  in-game mobile-controls mod. Touches the pad does not own still fall
     *  through to the finger-as-mouse path. This keeps the edge buttons above
     *  the game surface and reports physical-controller changes. Physical input
     *  is mirrored into the virtual SDL pad (SCPhysicalGamepad) as before. */
    private static boolean sLastPhysical = false;

    /** Eye toggle: hide/show the on-screen controls (handheld convenience). */
    private void toggleOverlayVisibility() {
        mOverlayHidden = !mOverlayHidden;
        if (mTouchPad != null) {
            mTouchPad.setVisibility(mOverlayHidden ? View.GONE : View.VISIBLE);
        }
        if (eyeButton != null) {
            eyeButton.setImageResource(mOverlayHidden ? R.drawable.ic_eye_off : R.drawable.ic_eye);
        }
        SCLogger.appendToLog(mOverlayHidden ? "controls hidden (eye)" : "controls shown (eye)");
        SCActionTrail.record(mOverlayHidden ? "Controls hidden (eye)" : "Controls shown (eye)");
    }

    private void syncGamepadVisibility() {
        if (keyboardButton != null) keyboardButton.bringToFront();
        if (exitButton != null && !mHandheldMode) exitButton.bringToFront();
        if (eyeButton != null) eyeButton.bringToFront();

        boolean physical = hasPhysicalGamepad();
        if (mTouchPad != null && physical) {
            mTouchPad.hideForPhysical();
        }
        if (physical != sLastPhysical) {
            sLastPhysical = physical;
            SCLogger.appendToLog(physical
                    ? "physical controller connected"
                    : "physical controller removed");
            SCActionTrail.record(physical
                    ? "Physical controller connected"
                    : "Physical controller removed");
        }
    }

    private final android.hardware.input.InputManager.InputDeviceListener mDeviceListener =
            new android.hardware.input.InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int deviceId) { syncGamepadVisibility(); }
                @Override public void onInputDeviceRemoved(int deviceId) { syncGamepadVisibility(); }
                @Override public void onInputDeviceChanged(int deviceId) { syncGamepadVisibility(); }
            };

    // ------------------------------------------------------------------
    // Virtual keyboard (name entry etc.): Mojo's SDL showTextInput is a stub
    // (returns false), so the game can never summon the soft keyboard itself.
    // The KB button (or SDL_TextInputActive polling) focuses the hidden
    // SDLTextInputView, whose InputConnection forwards committed text to the
    // game as SDL text-input events. Return is handled in the InputConnection;
    // the Done action here just hides the IME after also sending Return.
    // ------------------------------------------------------------------
    private void setupKeyboardForwarding() {
        if (keyboardInput == null) return;
        keyboardInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                sendSdlKey(13); // return
                hideGameKeyboard();
                return true;
            }
            return false;
        });
        keyboardInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) enableImmersive();
        });
    }

    /** How many delayed retries a keyboard-show request makes while the
     *  immersive transition / focus change settles. */
    private static final int KEYBOARD_SHOW_RETRIES = 8;

    private void showGameKeyboard() {
        if (!mKeyboardEnabled) {
            SCLogger.appendToLog("kb: disabled in launcher options");
            return;
        }
        if (keyboardInput == null) {
            SCLogger.appendToLog("kb: hidden input view missing");
            return;
        }
        SCActionTrail.record("Virtual keyboard opened");
        try {
            keyboardInput.setFocusable(true);
            keyboardInput.setFocusableInTouchMode(true);
            // Re-focus even if a pad/surface touch stole focus. requestFocus()
            // is a no-op when the view already has focus, so always call it
            // before restarting the input connection.
            keyboardInput.requestFocus();
            keyboardInput.setSelection(keyboardInput.getText().length());
            final InputMethodManager imm = (InputMethodManager)
                    getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm == null) {
                SCLogger.appendToLog("kb: no InputMethodManager");
                return;
            }
            // The first request is usually ignored while the window is still
            // settling into immersive mode or while focus is moving to the
            // hidden view, so retry on the next frames until the IME accepts.
            keyboardInput.postDelayed(() -> attemptShowKeyboard(imm, 0), 50);
        } catch (Exception e) {
            Log.w(TAG, "show keyboard failed", e);
        }
    }

    private void attemptShowKeyboard(InputMethodManager imm, int attempt) {
        if (keyboardInput == null || isFinishing()
                || !keyboardInput.isAttachedToWindow()) {
            return;
        }
        try {
            if (!keyboardInput.hasFocus()) {
                keyboardInput.setFocusable(true);
                keyboardInput.setFocusableInTouchMode(true);
                keyboardInput.requestFocus();
            }
            keyboardInput.setSelection(keyboardInput.getText().length());
            imm.restartInput(keyboardInput);
            boolean shown = imm.showSoftInput(keyboardInput,
                    InputMethodManager.SHOW_IMPLICIT);
            if (!shown) {
                shown = imm.showSoftInput(keyboardInput, 0);
            }
            SCLogger.appendToLog("kb: showSoftInput attempt " + attempt
                    + " -> " + shown);
            if (shown) return;
            if (attempt < KEYBOARD_SHOW_RETRIES) {
                keyboardInput.postDelayed(
                        () -> attemptShowKeyboard(imm, attempt + 1), 100);
                return;
            }
            // Last resort: force the IME up. toggleSoftInput would hide an
            // already-visible keyboard, so only use it when it is not up.
            if (!imm.isActive(keyboardInput)) {
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
                SCLogger.appendToLog("kb: toggled SHOW_FORCED");
            }
        } catch (Throwable t) {
            Log.w(TAG, "show keyboard failed", t);
        }
    }

    private void hideGameKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null && keyboardInput != null) {
                imm.hideSoftInputFromWindow(keyboardInput.getWindowToken(), 0);
            }
        } catch (Exception ignored) {}
    }

    /** Forwards a hardware-keyboard character to the game as SDL text input
     *  (the same path the on-screen IME uses via SDLTextInputView). */
    private void commitSdlText(String text) {
        if (text.isEmpty() || !isJvmStarted()) return;
        try {
            git.mojo.sdl.SDLInputConnection.nativeCommitText(text, 1);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            Log.w(TAG, "SDL native not ready, text dropped: " + text);
        }
    }

    private void sendSdlBackspace() {
        if (!isJvmStarted()) return;
        try {
            git.mojo.sdl.SDLInputConnection.nativeGenerateScancodeForUnichar('\b');
        } catch (UnsatisfiedLinkError | NoClassDefFoundError ignored) {}
    }

    /**
     * Sends a key to the game.
     *
     * <p>NOTE: {@code SDLActivity.onNativeKeyDown/Up} take an <b>Android</b>
     * keycode — that is what SDL's own Android layer passes
     * ({@code onNativeKeyDown(event.getKeyCode())}) and the native side maps it
     * to an SDL key/scancode itself. Passing a pre-converted SDL keycode made
     * the game receive nonsense keycodes and drop every one of them ("Unknown
     * key" in the game log, 203 of them in one session).
     */
    private void sendSdlKey(int androidKey) {
        try {
            SDLActivity.onNativeKeyDown(androidKey);
            SDLActivity.onNativeKeyUp(androidKey);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            Log.w(TAG, "SDL native not ready, key dropped: " + androidKey);
        }
    }

    private void sendSdlKeyUp(int androidKey) {
        try {
            SDLActivity.onNativeKeyUp(androidKey);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError ignored) {}
    }

    private void sendSdlKeyDown(int androidKey) {
        try {
            SDLActivity.onNativeKeyDown(androidKey);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError ignored) {}
    }

    /**
     * Called by SDL3 native code (message boxes, assertion dialogs). Upstream
     * shows a modal; we log + auto-dismiss with the first button so a native
     * assert can never SIGABRT the app via CheckJNI again. Must stay public
     * with this exact signature.
     */
    // SDL_AssertState.SDL_ASSERTION_ALWAYS_IGNORE
    private static final int SDL_ASSERTION_ALWAYS_IGNORE = 4;

    // Native messageboxes are deduplicated + rate-limited: a failing SDL assert
    // used to reach this method ~2000 times a second and wrote a line each time.
    private static final java.util.HashMap<String, Integer> sMessageBoxSeen = new java.util.HashMap<>();
    private static final int MESSAGEBOX_LOG_FIRST = 3;
    private static final int MESSAGEBOX_LOG_EVERY = 500;
    private static final int MESSAGEBOX_MAX_KEYS = 64;

    public int messageboxShowMessageBox(
            final int flags,
            final String title,
            final String message,
            final int[] buttonFlags,
            final int[] buttonIds,
            final String[] buttonTexts,
            final int[] colors) {
        final String line = "SDL messagebox [" + title + "]: " + message;

        // Never answer an assertion with "Retry". SDL's assert macro is
        //     while (!(condition)) { state = SDL_ReportAssertion(...);
        //                            if (state == RETRY) continue; ... }
        // and the assertion dialog's FIRST button is Retry (= 0), which is what
        // the old "return the first button" auto-dismiss returned. One failing
        // assert then re-ran forever, re-entering here and writing a log line
        // every iteration (a 1.0.28+ device report showed 25,932 of them). We
        // answer "Always Ignore" instead, so SDL drops that assert site for
        // good -- what a desktop user pressing "Always Ignore" gets.
        final int button = chooseMessageBoxButton(title, message, buttonIds, buttonTexts);

        synchronized (sMessageBoxSeen) {
            final Integer seenBoxed = sMessageBoxSeen.get(line);
            final int seen = seenBoxed == null ? 0 : seenBoxed;
            if (sMessageBoxSeen.size() < MESSAGEBOX_MAX_KEYS || seenBoxed != null) {
                sMessageBoxSeen.put(line, seen + 1);
            }
            // Log the first few, then sample: keeps the shape of a storm visible
            // without filling the log file or hammering the disk.
            if (seen < MESSAGEBOX_LOG_FIRST || (seen % MESSAGEBOX_LOG_EVERY) == 0) {
                LogFile.error(line + (seen > 0 ? "  (repeat #" + (seen + 1) + ")" : ""));
                SCLogger.appendToLog(line + (seen > 0 ? "  (repeat #" + (seen + 1) + ")" : ""));
            }
        }
        return button;
    }

    /**
     * Buttons for the SDL assertion dialog are Retry / Break / Abort / Ignore /
     * Always Ignore, in that order, with SDL_AssertState values as their ids.
     * Prefer the "Always Ignore" button by name; fall back to its constant when
     * the dialog looks like an assertion. Ordinary messageboxes keep the old
     * behaviour (first button).
     */
    private static int chooseMessageBoxButton(
            final String title, final String message, final int[] buttonIds, final String[] buttonTexts) {
        if (buttonTexts != null) {
            for (int i = 0; i < buttonTexts.length; i++) {
                final String text = buttonTexts[i];
                if (text != null && text.trim().equalsIgnoreCase("Always Ignore")) {
                    return (buttonIds != null && i < buttonIds.length) ? buttonIds[i] : SDL_ASSERTION_ALWAYS_IGNORE;
                }
            }
        }
        final boolean assertion =
                (title != null && title.toLowerCase(java.util.Locale.ROOT).contains("assert"))
                        || (message != null && message.contains("Assertion failure"));
        if (assertion) {
            return SDL_ASSERTION_ALWAYS_IGNORE;
        }
        return (buttonIds != null && buttonIds.length > 0) ? buttonIds[0] : -1;
    }
}
