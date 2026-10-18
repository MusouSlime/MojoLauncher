package com.severedchains.launcher;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Application entry point. Owns one app-wide job:
 * crash safety net — a global uncaught-exception handler that persists a
 * report (internal {@code crashes/} + {@code Downloads/SCLogs/}) and then
 * chains to the previous handler. Recursion-safe by design.
 *
 * <p>Note: per-launch {@code Downloads/SCLogs/startup_*.log} snapshots were
 * removed (log spam on every cold start). Startup diagnostics still go to
 * the internal rolling log ({@code filesDir/logs/}) and the game log.
 */
public class SCApplication extends Application {
    private static final String TAG = "SCApplication";
    private static final String LOG_DIR_NAME = "SCLogs";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "SCLauncher application starting");

        // Install global uncaught exception handler (keep the previous one
        // so we don't recurse into ourselves).
        final Thread.UncaughtExceptionHandler previousHandler =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                handleCrash(thread, ex, previousHandler);
            }
        });
    }

    // ------------------------------------------------------------------
    // Log persistence: MediaStore on Android 10+ (scoped storage), legacy
    // public-Downloads path below that, internal filesDir/logs as fallback.
    // Used by the crash handler below.
    // ------------------------------------------------------------------
    private void saveLogToDownloads(String filename, String content) {
        try {
            File logDir = null;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MediaStore for Android 10+
                logDir = saveViaMediaStore(filename, content);
            } else {
                // Legacy approach
                logDir = saveLegacy(filename, content);
            }
            
            if (logDir != null) {
                Log.i(TAG, "Log saved to: " + logDir.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save log to downloads", e);
            // Fallback to internal storage
            saveToInternal(filename, content);
        }
    }

    private File saveViaMediaStore(String filename, String content) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + LOG_DIR_NAME);
        
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Failed to create MediaStore entry");
        
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("Failed to open output stream");
            out.write(content.getBytes("UTF-8"));
            out.flush();
        }
        
        // Return a fake file for logging purposes
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), LOG_DIR_NAME + "/" + filename);
    }

    private File saveLegacy(String filename, String content) throws Exception {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File logDir = new File(downloadsDir, LOG_DIR_NAME);
        if (!logDir.exists()) logDir.mkdirs();
        File logFile = new File(logDir, filename);
        try (FileOutputStream fos = new FileOutputStream(logFile)) {
            fos.write(content.getBytes("UTF-8"));
        }
        return logDir;
    }

    private void saveToInternal(String filename, String content) {
        try {
            File logDir = new File(getFilesDir(), "logs");
            if (!logDir.exists()) logDir.mkdirs();
            File logFile = new File(logDir, filename);
            try (FileOutputStream fos = new FileOutputStream(logFile)) {
                fos.write(content.getBytes("UTF-8"));
            }
            Log.i(TAG, "Log saved to internal storage: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save log to internal storage", e);
        }
    }

    // ------------------------------------------------------------------
    // Crash handler: persist the stack trace, then chain to the previous
    // handler so the system crash dialog still appears. Never throws.
    // ------------------------------------------------------------------
    private void handleCrash(Thread thread, Throwable ex, Thread.UncaughtExceptionHandler previousHandler) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        String stackTrace = sw.toString();

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String crashReport = "=== SCLauncher Crash Report ===\n" +
                "Time: " + new Date() + "\n" +
                "Thread: " + thread.getName() + "\n" +
                "Error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage() + "\n\n" +
                stackTrace;

        // Save to internal storage
        try {
            File crashDir = new File(getFilesDir(), "crashes");
            if (!crashDir.exists()) crashDir.mkdirs();
            File crashFile = new File(crashDir, "crash_" + timestamp + ".txt");
            try (FileOutputStream fos = new FileOutputStream(crashFile)) {
                fos.write(crashReport.getBytes("UTF-8"));
            }
            Log.e(TAG, "Crash report saved to: " + crashFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save crash report", e);
        }

        // Also save crash to Downloads/SCLogs
        try {
            String crashFilename = "crash_" + timestamp + ".log";
            saveLogToDownloads(crashFilename, crashReport);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save crash to downloads", e);
        }

        // Log to system log
        Log.e(TAG, "UNCAUGHT EXCEPTION", ex);

        // Call the previous handler to show system crash dialog (may be null).
        if (previousHandler != null) {
            previousHandler.uncaughtException(thread, ex);
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
                String nativeLibDir = ai.nativeLibraryDir;
                System.setProperty("java.library.path", nativeLibDir);
                Log.i(TAG, "Native lib dir: " + nativeLibDir);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Failed to get native lib dir", e);
            }
        }
    }
}
