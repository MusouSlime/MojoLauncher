package com.severedchains.launcher;

import android.util.Log;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Full game-output capture.
 * - Native dup2(pipe) captures ALL stdout/stderr from the in-process JVM
 *   (System.out/err, LWJGL, SDL, libGL) into latestlog.txt + logcat.
 * - Java logcat thread captures tags jrelog/JVMRunner/LIBGL/SDL/Game.
 * - Live listeners feed the on-screen log view.
 */
public class SCLogger {
    private static final String TAG = "SCGame";
    private static File latestLogFile;
    private static final CopyOnWriteArrayList<LogListener> listeners = new CopyOnWriteArrayList<>();
    private static volatile boolean logcatRunning = false;

    public interface LogListener {
        void onLog(String line);
    }

    public static void addListener(LogListener l) {
        listeners.add(l);
    }

    public static void removeListener(LogListener l) {
        listeners.remove(l);
    }

    private static void emit(String line) {
        LogFile.log("[game] " + line);
        for (LogListener l : listeners) {
            try {
                l.onLog(line);
            } catch (Exception ignored) {}
        }
    }

    /** Called from native logger thread for each stdout/stderr chunk. */
    @SuppressWarnings("unused")
    public static void onNativeLog(String line) {
        if (line == null) return;
        // Split multi-line chunks so the UI stays readable.
        for (String part : line.split("\n")) {
            if (!part.isEmpty()) emit(part);
        }
    }

    public static File getLatestLogFile() {
        return latestLogFile;
    }

    /**
     * Start capture. Must be called on the main thread BEFORE startJvm.
     * @param filesDir app files dir (logs go to filesDir/logs + filesDir/latestlog.txt)
     */
    public static void begin(File filesDir) {
        try {
            File logDir = new File(filesDir, "logs");
            if (!logDir.exists()) logDir.mkdirs();
            latestLogFile = new File(filesDir, "latestlog.txt");
            if (!latestLogFile.exists()) latestLogFile.createNewFile();
            Log.i(TAG, "SCLogger.begin -> " + latestLogFile.getAbsolutePath());
            try {
                nativeBegin(latestLogFile.getAbsolutePath());
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "sc_logger native missing, file-only logging", e);
                LogFile.error("sc_logger native missing", e);
            }
            startLogcatThread();
        } catch (Exception e) {
            Log.e(TAG, "SCLogger.begin failed", e);
            LogFile.error("SCLogger.begin failed", e);
        }
    }

    public static void appendToLog(String text) {
        if (text == null) return;
        emit(text);
        try {
            nativeAppend(text);
        } catch (UnsatisfiedLinkError ignored) {}
    }

    private static void startLogcatThread() {
        if (logcatRunning) return;
        logcatRunning = true;
        new Thread(() -> {
            int fails = 0;
            while (fails <= 10) {
                try {
                    new ProcessBuilder().command("logcat", "-c").redirectErrorStream(true).start().waitFor();
                    Process p = new ProcessBuilder().command(
                            "logcat", "-v", "brief", "-s",
                            "jrelog", "JVMRunner", "LIBGL", "SDL")
                            .redirectErrorStream(true).start();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = p.getInputStream().read(buf)) != -1) {
                        String s = new String(buf, 0, n);
                        for (String line : s.split("\n")) {
                            if (!line.trim().isEmpty()) emit(line);
                        }
                    }
                    int code = p.waitFor();
                    Log.e(TAG, "logcat exited " + code);
                    fails++;
                } catch (Exception e) {
                    Log.e(TAG, "logcat thread failed", e);
                    fails++;
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {}
                }
            }
            emit("ERROR: logcat capture stopped after 10 retries");
        }, "SCLogger-logcat").start();
    }

    private static native void nativeBegin(String logPath);

    private static native void nativeAppend(String text);

    static {
        try {
            System.loadLibrary("sc_logger");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "libsc_logger.so not found yet", e);
        }
    }
}
