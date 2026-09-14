package com.severedchains.launcher;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.appcompat.app.AppCompatActivity;

import com.severedchains.launcher.ui.TouchGamepadView;

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
    private TouchGamepadView gamepadView;
    private android.widget.EditText keyboardInput;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private android.hardware.input.InputManager mInputManager;

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
        // killProcess() skips onDestroy, so export here with a bounded wait —
        // otherwise the session log would never reach Downloads/Reports.
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
        enableImmersive();

        surfaceView = findViewById(R.id.gameSurface);
        gamepadView = findViewById(R.id.gameGamepad);
        keyboardInput = findViewById(R.id.gameKeyboardInput);
        android.widget.Button keyboardButton = findViewById(R.id.keyboardButton);
        if (keyboardButton != null) {
            keyboardButton.setOnClickListener(v -> showGameKeyboard());
        }
        setupKeyboardForwarding();

        // Virtual pad is always present until a real BT/USB controller shows
        // up; then it hides and the virtual SDL joystick detaches (player 1
        // goes to hardware). Disconnecting brings the overlay back.
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
        SCLogger.appendToLog("GPU mode: " + gpuMode
                + ("malib".equals(gpuMode) ? " (cull-off gsh + flag-debug fsh)"
                        : "mali".equals(gpuMode) ? " (cull-off tmd.gsh)"
                        : " (stock shaders)"));
        SCActionTrail.record("Game screen opened [GPU:" + gpuMode + "]");
        // Game output stays in backend files (latestlog.txt); no on-screen log.
        SCLogger.begin(getFilesDir());

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
            // OpenAL-Soft: Oboe/AAudio alone reports no OpenAL10 caps on this
            // device (infinite reinit loop, silence). Prefer classic OpenSL ES
            // like Mojo's PREF_ALSOFT_FORCE_OPENSL, but keep oboe as fallback
            // instead of a single forced driver with no second chance.
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
            float rate = getWindowManager().getDefaultDisplay().getRefreshRate();
            try {
                MojoExec.setDisplayParams(w, h, rate);
            } catch (UnsatisfiedLinkError ignored) {}
            SDLActivity.nativeSetScreenResolution(w, h, w, h, 1.0f, rate);
            SDLActivity.onNativeResize();
            SDLActivity.onNativeSurfaceChanged();
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
        try {
            float rate = getWindowManager().getDefaultDisplay().getRefreshRate();
            try {
                MojoExec.setDisplayParams(width, height, rate);
            } catch (UnsatisfiedLinkError ignored) {}
            SDLActivity.nativeSetScreenResolution(width, height, width, height, 1.0f, rate);
            SDLActivity.onNativeResize();
        } catch (Throwable t) {
            SCLogger.appendToLog("resize failed: " + t.getMessage());
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        enableImmersive();
        try {
            SDLActivity.nativeVisibilityChanged(true);
        } catch (Throwable ignored) {}
        SCLogger.appendToLog("activity resumed");
        syncGamepadVisibility();
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
        hideGameKeyboard();
        try {
            if (mInputManager != null) mInputManager.unregisterInputDeviceListener(mDeviceListener);
        } catch (Exception ignored) {}
        // Normal close (back-out, system finish): drop the session log where
        // the user can reach it. Fire-and-forget; the process stays alive.
        SCLogExporter.exportAsync(this, "game-close");
        super.onDestroy();
    }

    /**
     * A physical controller only hides the touch overlay (it is mirrored into
     * the virtual SDL pad by SCPhysicalGamepad, which is the proven game
     * channel). The virtual pad stays attached so physical input keeps
     * working; the overlay itself follows connection state.
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
        return super.dispatchKeyEvent(e);
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
        long now = android.os.SystemClock.uptimeMillis();
        if (now - mLastBackPress < BACK_EXIT_WINDOW_MS) {
            SCLogger.appendToLog("double-back: exiting to launcher");
            SCActionTrail.record("Back double-press: exit to launcher");
            super.onBackPressed();
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

    /** Show the virtual overlay iff no physical controller is attached.
     *  The virtual SDL pad itself stays attached: physical input is mirrored
     *  into it (SCPhysicalGamepad), so detaching would only kill input. */
    private static boolean sLastPhysical = false;

    private void syncGamepadVisibility() {
        boolean physical = hasPhysicalGamepad();
        if (physical != sLastPhysical) {
            sLastPhysical = physical;
            SCActionTrail.record(physical
                    ? "Physical controller connected (touch pad hidden)"
                    : "Physical controller removed (touch pad shown)");
        }
        if (gamepadView != null) {
            gamepadView.setVisibility(physical ? View.GONE : View.VISIBLE);
            if (!physical) gamepadView.bringToFront();
        }
        SCLogger.appendToLog(physical
                ? "physical controller attached, virtual pad hidden"
                : "no physical controller, virtual pad shown");
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
    // The ⌨ button focuses a hidden EditText; every committed char is
    // forwarded as SDL key down/up (SDL keycodes match ASCII for text).
    // ------------------------------------------------------------------
    private boolean mKbGuard = false;
    private String mKbBefore = "";

    private void setupKeyboardForwarding() {
        if (keyboardInput == null) return;
        keyboardInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                if (!mKbGuard) mKbBefore = s.toString();
            }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (mKbGuard) return;
                String cur = s.toString();
                String before = mKbBefore;
                mKbBefore = cur;
                int common = 0;
                int max = Math.min(before.length(), cur.length());
                while (common < max && before.charAt(common) == cur.charAt(common)) common++;
                for (int i = common; i < before.length(); i++) sendSdlKey(8); // backspace
                String added = cur.substring(common);
                if (!added.isEmpty()) sendSdlText(added);
                if (!cur.isEmpty()) {
                    mKbGuard = true;
                    s.clear();
                    mKbBefore = "";
                    mKbGuard = false;
                }
            }
        });
        keyboardInput.setOnKeyListener((v, keyCode, event) -> {
            // Backspace on an empty field produces no text change — catch it.
            if (keyCode == android.view.KeyEvent.KEYCODE_DEL
                    && event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                sendSdlKey(8);
                return true;
            }
            return false;
        });
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

    private void showGameKeyboard() {
        if (keyboardInput == null) return;
        SCActionTrail.record("Virtual keyboard opened");
        try {
            keyboardInput.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            boolean shown = imm != null && imm.showSoftInput(keyboardInput,
                    InputMethodManager.SHOW_IMPLICIT);
            if (!shown && imm != null) imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        } catch (Exception e) {
            Log.w(TAG, "show keyboard failed", e);
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

    /** Printable ASCII goes 1:1 to SDL keycodes; capitals wrap in LSHIFT. */
    private void sendSdlText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                sendSdlKey(13);
            } else if (c >= 'A' && c <= 'Z') {
                sendSdlKeyDown(0x400000E1); // hold LSHIFT for the capital
                sendSdlKey(Character.toLowerCase(c));
                sendSdlKeyUp(0x400000E1);
            } else if (c >= 32 && c < 127) {
                sendSdlKey(c);
            }
        }
    }

    private void sendSdlKey(int sdlKey) {
        try {
            SDLActivity.onNativeKeyDown(sdlKey);
            SDLActivity.onNativeKeyUp(sdlKey);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            Log.w(TAG, "SDL native not ready, key dropped: " + sdlKey);
        }
    }

    private void sendSdlKeyUp(int sdlKey) {
        try {
            SDLActivity.onNativeKeyUp(sdlKey);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError ignored) {}
    }

    private void sendSdlKeyDown(int sdlKey) {
        try {
            SDLActivity.onNativeKeyDown(sdlKey);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError ignored) {}
    }

    /**
     * Called by SDL3 native code (message boxes, assertion dialogs). Upstream
     * shows a modal; we log + auto-dismiss with the first button so a native
     * assert can never SIGABRT the app via CheckJNI again. Must stay public
     * with this exact signature.
     */
    public int messageboxShowMessageBox(
            final int flags,
            final String title,
            final String message,
            final int[] buttonFlags,
            final int[] buttonIds,
            final String[] buttonTexts,
            final int[] colors) {
        String line = "SDL messagebox [" + title + "]: " + message;
        LogFile.error(line);
        SCLogger.appendToLog(line);
        return (buttonIds != null && buttonIds.length > 0) ? buttonIds[0] : -1;
    }
}
