package com.severedchains.launcher;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Launcher-side rolling log: every line goes to logcat AND to a timestamped
 * file under {@code filesDir/logs/}. Used by the setup UI (JRE install, disc
 * copy, classpath assembly). Game output itself is captured by SCLogger.
 */
public class LogFile {
    private static final String TAG = "LogFile";
    private static PrintWriter writer;
    private static File logFile;
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public static void init(Context context) {
        try {
            File logDir = new File(context.getFilesDir(), "logs");
            if (!logDir.exists()) logDir.mkdirs();

            logFile = new File(logDir, "sclauncher_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".log");
            writer = new PrintWriter(new FileWriter(logFile, true), true);
            log("=== SCLauncher log started ===");
            log("Log file: " + logFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to init log file", e);
        }
    }

    public static void log(String message) {
        String timestamp = TIME_FORMAT.format(new Date());
        String line = timestamp + " " + message;
        Log.i(TAG, line);
        if (writer != null) {
            writer.println(line);
            writer.flush();
        }
    }

    public static void error(String message) {
        String timestamp = TIME_FORMAT.format(new Date());
        String line = timestamp + " ERROR: " + message;
        Log.e(TAG, line);
        if (writer != null) {
            writer.println(line);
            writer.flush();
        }
    }

    public static void error(String message, Throwable t) {
        String timestamp = TIME_FORMAT.format(new Date());
        String line = timestamp + " ERROR: " + message;
        Log.e(TAG, line, t);
        if (writer != null) {
            writer.println(line);
            t.printStackTrace(writer);
            writer.flush();
        }
    }

    public static File getLogFile() {
        return logFile;
    }

    public static void flush() {
        if (writer != null) {
            writer.flush();
        }
    }

    public static void close() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }
}