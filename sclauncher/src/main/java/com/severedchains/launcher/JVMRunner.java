package com.severedchains.launcher;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Starts the game's Java VM inside our own process.
 *
 * <p>How it fits together:
 * <ol>
 *   <li>SCLauncherActivity collects the JRE dir, JVM args and classpath.</li>
 *   <li>{@link #startJvm} locates {@code libjvm.so}, sets up {@code
 *   LD_LIBRARY_PATH} / {@code JAVA_HOME}, then calls {@link #nativeLoadJVM}.</li>
 *   <li>{@code jvmrunner.c} dlopens {@code libjvm.so} and calls
 *   {@code JNI_CreateJavaVM} in-process (never execs {@code bin/java}, which
 *   fails on Android), then invokes {@code legend.game.Main.main()}.</li>
 * </ol>
 */
public class JVMRunner {
    private static final String TAG = "JVMRunner";

    // ------------------------------------------------------------------
    // Logging helpers: mirror to logcat and to the launcher log file.
    // ------------------------------------------------------------------

    private static void log(String msg) {
        Log.i(TAG, msg);
        LogFile.log(msg);
    }

    private static void error(String msg) {
        Log.e(TAG, msg);
        LogFile.error(msg);
    }

    private static void error(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        LogFile.error(msg, t);
    }

    public static class VMLoadException extends Exception {
        public VMLoadException(String message, int code, int errno) {
            super(message + " (code=" + code + ", errno=" + errno + ")");
        }
    }

    // ------------------------------------------------------------------
    // Native error codes: must stay in sync with jvmrunner.c.
    // ------------------------------------------------------------------
    public static final int ERR_DLOPEN_FAILED = 1;
    public static final int ERR_DLSYM_FAILED = 2;
    public static final int ERR_CREATE_VM_FAILED = 3;
    public static final int ERR_FIND_CLASS_FAILED = 4;
    public static final int ERR_FIND_METHOD_FAILED = 5;
    public static final int ERR_NEW_OBJECT_ARRAY_FAILED = 6;

    public static class Runtime {
        public final String name;
        public final String arch;
        public final int javaVersion;

        public Runtime(String name, String arch, int javaVersion) {
            this.name = name;
            this.arch = arch;
            this.javaVersion = javaVersion;
        }
    }

    // ------------------------------------------------------------------
    // JRE discovery: probe the known layouts for libjvm.so / libjli.so.
    // Layouts differ per JRE build (plain lib/ vs arch subdirs, server vs
    // client VM), so try each candidate in order and take the first hit.
    // ------------------------------------------------------------------
    private static File findVmPath(File runtimeHomeDir, String arch) {
        String actualArch = System.getProperty("os.arch");
        if (actualArch == null) actualArch = arch;
        // Default to x86_64 for Waydroid
        if (actualArch != null && (actualArch.equals("aarch64") || actualArch.equals("arm64"))) {
            actualArch = "aarch64";
        } else {
            actualArch = "x86_64";
        }

        File[] candidates = {
            new File(runtimeHomeDir, "lib/" + actualArch + "/server/libjvm.so"),
            new File(runtimeHomeDir, "lib/" + actualArch + "/client/libjvm.so"),
            new File(runtimeHomeDir, "lib/" + arch + "/server/libjvm.so"),
            new File(runtimeHomeDir, "lib/" + arch + "/client/libjvm.so"),
            new File(runtimeHomeDir, "lib/server/libjvm.so"),
            new File(runtimeHomeDir, "lib/client/libjvm.so"),
        };
        for (File f : candidates) {
            if (f.exists()) return f;
        }
        return null;
    }

    private static File findJliPath(File runtimeHomeDir) {
        // Try standard locations for libjli.so
        File[] candidates = {
            new File(runtimeHomeDir, "lib/libjli.so"),
            new File(runtimeHomeDir, "lib/jli/libjli.so"),
            new File(runtimeHomeDir, "lib/aarch64/jli/libjli.so"),
            new File(runtimeHomeDir, "lib/arm64/jli/libjli.so"),
            new File(runtimeHomeDir, "lib/x86_64/jli/libjli.so"),
            new File(runtimeHomeDir, "lib/jli.so"),
        };
        for (File f : candidates) {
            if (f.exists()) return f;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Process environment for the VM: LD_LIBRARY_PATH (JRE libs first, then
    // our bundled natives, then system dirs) plus JAVA_HOME/HOME/TMPDIR.
    // ------------------------------------------------------------------
    private static void relocateLdLibPath(File jreHome, File vmPath, String nativeLibDir, List<String> extraDirs) {
        // vmPath is .../lib/server/libjvm.so; derive <jre>/lib and <jre>/lib/server.
        File libsDir = new File(jreHome, "lib");
        File serverDir = (vmPath != null && vmPath.getParentFile() != null)
                ? vmPath.getParentFile()
                : new File(libsDir, "server");

        // Build comprehensive library path like Amethyst's JREUtils.relocateLibPath.
        StringBuilder libPathBuilder = new StringBuilder()
                // JRE lib directories
                .append(libsDir.getAbsolutePath()).append(":")
                .append(serverDir.getAbsolutePath()).append(":")
                .append(new File(libsDir, "client").getAbsolutePath()).append(":")
                .append(new File(libsDir, "jli").getAbsolutePath()).append(":")
                // App native libs
                .append(nativeLibDir).append(":")
                // System libs
                .append("/system/lib64").append(":")
                .append("/system/lib64/vulkan").append(":")
                .append("/vendor/lib64").append(":")
                .append("/vendor/lib64/vulkan").append(":")
                .append("/vendor/lib64/hw").append(":")
                .append("/vendor/lib64/egl").append(":");

        if (extraDirs != null) {
            for (String path : extraDirs) {
                libPathBuilder.append(path).append(':');
            }
        }

        String ldLibPath = libPathBuilder.toString();
        try {
            Os.setenv("LD_LIBRARY_PATH", ldLibPath, true);
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }
        System.setProperty("java.library.path", ldLibPath);
        log("LD_LIBRARY_PATH: " + ldLibPath);
    }

    private static void setImmutableEnvVars(File jreHome, String dirData, String dirCache) {
        try {
            Os.setenv("JAVA_HOME", jreHome.getAbsolutePath(), true);
            Os.setenv("HOME", dirData, true);
            Os.setenv("TMPDIR", dirCache, true);
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // Main entry point: validate context, assemble the final arg list
    // (caller args + CPU count + classpath), set up the environment, then
    // hand off to native code. Throws VMLoadException with the real
    // dlerror()/JNI cause instead of dying silently.
    // ------------------------------------------------------------------
    public static void startJvm(Runtime runtime, List<String> vmArgs, List<String> classpathEntries,
                                String mainClass, List<String> applicationArgs) throws VMLoadException {
        // Resolve paths from the calling context
        // dirData = <app>/files/.. = getFilesDir().getParent()
        // These are set as system properties by SCLauncherActivity before calling this
        String dirData = System.getProperty("sc.dir.data");
        String dirCache = System.getProperty("sc.dir.cache");
        String nativeLibDir = System.getProperty("sc.native.lib.dir");

        if (dirData == null || dirCache == null || nativeLibDir == null) {
            throw new VMLoadException("Missing sc.dir.data / sc.dir.cache / sc.native.lib.dir system properties", 0, -1);
        }

        File runtimeHomeDir = new File(dirData, "runtimes/" + runtime.name);
        if (!runtimeHomeDir.exists()) {
            throw new VMLoadException("Runtime not found: " + runtimeHomeDir.getAbsolutePath(), 0, -1);
        }

        log("JRE directory contents:");
        listDir(runtimeHomeDir, 0);

        // Primary launch path is libjvm.so via in-process JNI_CreateJavaVM
        // (avoids exec'ing bin/java, which fails when system libs mismatch).
        File vmPath = findVmPath(runtimeHomeDir, runtime.arch);
        if (vmPath == null) {
            throw new VMLoadException("Unable to find libjvm.so in " + runtimeHomeDir.getAbsolutePath(), 0, -1);
        }

        log("Found libjvm.so at: " + vmPath.getAbsolutePath());
        log("libjvm.so exists: " + vmPath.exists() + ", length: " + vmPath.length());

        // Log libjli.so presence for diagnostics (preloaded by native code).
        File libjli = findJliPath(runtimeHomeDir);
        if (libjli != null) {
            log("Found libjli.so at: " + libjli.getAbsolutePath());
        } else {
            log("WARNING: libjli.so not found; continuing with libjvm.so only");
        }

        List<String> runtimeArgs = new ArrayList<String>();
        runtimeArgs.addAll(vmArgs);

        runtimeArgs.add("-XX:ActiveProcessorCount=" + java.lang.Runtime.getRuntime().availableProcessors());

        StringBuilder classpathBuilder = new StringBuilder().append("-Djava.class.path=");
        boolean first = true;
        for (String entry : classpathEntries) {
            if (first) first = false;
            else classpathBuilder.append(':');
            classpathBuilder.append(entry);
        }
        runtimeArgs.add(classpathBuilder.toString());

        setImmutableEnvVars(runtimeHomeDir, dirData, dirCache);
        relocateLdLibPath(runtimeHomeDir, vmPath, nativeLibDir, null);

        String[] javaArgs = runtimeArgs.toArray(new String[0]);
        String[] appArgs = applicationArgs.toArray(new String[0]);

        log("Starting JVM via JNI_CreateJavaVM: " + vmPath.getAbsolutePath());
        log("Runtime home: " + runtimeHomeDir.getAbsolutePath());
        log("Classpath entries: " + classpathEntries.size());
        log("Main class: " + mainClass);
        log("Dir data: " + dirData);
        log("Dir cache: " + dirCache);
        log("Native lib dir: " + nativeLibDir);
        for (int i = 0; i < javaArgs.length; i++) {
            log("JVM arg " + i + ": " + javaArgs[i]);
        }

        int nativeResult = nativeLoadJVM(vmPath.getAbsolutePath(), javaArgs, mainClass, appArgs, false);
        if (nativeResult != 0) {
            String msg;
            switch (nativeResult) {
                case ERR_DLOPEN_FAILED: msg = "dlopen failed"; break;
                case ERR_DLSYM_FAILED: msg = "dlsym failed"; break;
                case ERR_CREATE_VM_FAILED: msg = "JNI_CreateJavaVM failed"; break;
                case ERR_FIND_CLASS_FAILED: msg = "FindClass failed"; break;
                case ERR_FIND_METHOD_FAILED: msg = "GetStaticMethodID failed"; break;
                case ERR_NEW_OBJECT_ARRAY_FAILED: msg = "NewObjectArray failed"; break;
                default: msg = "Unknown native error " + nativeResult;
            }
            throw new VMLoadException(msg, nativeResult, -1);
        }
    }

    public static native int nativeLoadJVM(String vmPath, String[] javaArgs, String mainClass,
                                               String[] appArgs, boolean hasJavaAgents) throws VMLoadException;

    // ------------------------------------------------------------------
    // Touch-key queue (legacy path): the overlay enqueues Android keycodes
    // here; the game side drains them. The live path is SDL key injection
    // (see TouchGamepadView) — this queue is kept as a harmless fallback.
    // ------------------------------------------------------------------
    private static final java.util.concurrent.ConcurrentLinkedQueue<KeyEvent> sKeyEventQueue = 
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    
    public static void queueKeyEvent(int keyCode, boolean down) {
        sKeyEventQueue.offer(new KeyEvent(keyCode, down));
        log("Queued key event: " + keyCode + " down=" + down);
    }
    
    public static KeyEvent pollKeyEvent() {
        return sKeyEventQueue.poll();
    }
    
    public static class KeyEvent {
        public final int keyCode;
        public final boolean down;
        public final long timestamp;
        
        public KeyEvent(int keyCode, boolean down) {
            this.keyCode = keyCode;
            this.down = down;
            this.timestamp = System.currentTimeMillis();
        }
    }

    static {
        System.loadLibrary("jvmrunner");
    }

    private static void listDir(File dir, int depth) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String indent = "";
                for (int i = 0; i < depth; i++) indent += "  ";
                log(indent + (f.isDirectory() ? "[D] " : "[F] ") + f.getName() + " (" + f.length() + " bytes)");
                if (f.isDirectory() && depth < 3) {
                    listDir(f, depth + 1);
                }
            }
        }
    }
}
