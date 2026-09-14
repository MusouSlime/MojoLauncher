package com.severedchains.launcher;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Copies the current session log to {@code Downloads/Reports/} when the user
 * leaves the app, so logs are reachable without root or adb.
 *
 * <p>Source priority: the launcher log file (it already mirrors every
 * [game] line SCLogger emits) → latestlog.txt tail → header-only note.
 * Content is tail-capped (~1.5 MB) so closing the app never stalls on I/O.
 *
 * <p>Two flavors: {@link #exportAsync} (fire-and-forget for
 * {@code onDestroy}) and {@link #exportBlocking} (bounded wait for paths
 * that end in {@code killProcess}, where {@code onDestroy} never runs).
 * Concurrent exports are collapsed into one.
 */
public final class SCLogExporter {
    private static final String TAG = "SCReports";
    private static final String REPORTS_DIR = "Reports";
    private static final long MAX_BYTES = 1536L * 1024L;

    private static final AtomicBoolean sExporting = new AtomicBoolean(false);

    private SCLogExporter() {}

    /** Fire-and-forget export (activity {@code onDestroy}). Never throws. */
    public static void exportAsync(Context ctx, String reason) {
        final Context app = ctx != null ? ctx.getApplicationContext() : null;
        if (app == null) return;
        new Thread(() -> exportNow(app, reason), "SCLogExport").start();
    }

    /**
     * Export with a bounded wait (exit paths that call {@code killProcess}).
     * Runs the I/O on a worker and blocks the caller at most
     * {@code timeoutMs}. Never throws.
     */
    public static void exportBlocking(Context ctx, String reason, long timeoutMs) {
        final Context app = ctx != null ? ctx.getApplicationContext() : null;
        if (app == null) return;
        final CountDownLatch done = new CountDownLatch(1);
        new Thread(() -> {
            try {
                exportNow(app, reason);
            } finally {
                done.countDown();
            }
        }, "SCLogExport").start();
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Internals.
    // ------------------------------------------------------------------

    private static void exportNow(Context ctx, String reason) {
        if (!sExporting.compareAndSet(false, true)) return;
        try {
            String safeReason = reason == null ? "close"
                    : reason.replaceAll("[^A-Za-z0-9_-]", "_");
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String filename = "report_" + stamp + "_" + safeReason + ".log";
            String body = buildReport(ctx, safeReason);
            String where = writeReport(ctx, filename, body);
            Log.i(TAG, "Report saved: " + where);
            LogFile.log("Report saved: " + where);
        } catch (Exception e) {
            Log.e(TAG, "Report export failed", e);
        } finally {
            sExporting.set(false);
        }
    }

    private static String buildReport(Context ctx, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SCLauncher Session Report ===\n");
        sb.append("Time: ").append(new Date()).append("\n");
        sb.append("Reason: ").append(reason).append("\n");
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            sb.append("App: ").append(ctx.getPackageName())
              .append(" v").append(pi.versionName)
              .append(" (").append(pi.versionCode).append(")\n");
        } catch (Exception ignored) {}
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
          .append(", Android ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        try {
            sb.append(SCGpuInfo.summary()).append("\n");
        } catch (Exception ignored) {}
        sb.append("\n");

        File primary = LogFile.getLogFile();
        if (primary != null && primary.exists()) {
            sb.append("--- launcher log: ").append(primary.getName()).append(" ---\n");
            sb.append(readTail(primary));
            return withTrail(sb);
        }
        File latest = SCLogger.getLatestLogFile();
        if (latest != null && latest.exists()) {
            sb.append("--- latestlog.txt (launcher log unavailable) ---\n");
            sb.append(readTail(latest));
            return withTrail(sb);
        }
        sb.append("(no log files yet this session)\n");
        return withTrail(sb);
    }

    /** Append the recent user-action trail (oldest first). */
    private static String withTrail(StringBuilder sb) {
        sb.append("\n--- last actions (oldest first, last ").append(SCActionTrail.snapshot().size()).append(") ---\n");
        sb.append(SCActionTrail.dump());
        return sb.toString();
    }

    /** Last MAX_BYTES of a file (whole file when smaller), UTF-8 safe-ish. */
    private static String readTail(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - MAX_BYTES);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            int n = 0, r;
            while (n < buf.length && (r = raf.read(buf, n, buf.length - n)) != -1) n += r;
            String prefix = start > 0 ? "[... truncated " + start + " bytes ...]\n" : "";
            return prefix + new String(buf, 0, n, "UTF-8");
        } catch (Exception e) {
            return "(could not read " + f.getName() + ": " + e.getMessage() + ")\n";
        }
    }

    private static String writeReport(Context ctx, String filename, String body) throws Exception {
        byte[] bytes = body.getBytes("UTF-8");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + REPORTS_DIR);
            Uri uri = ctx.getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("MediaStore insert failed");
            try (OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("MediaStore open failed");
                out.write(bytes);
                out.flush();
            }
            return "Downloads/" + REPORTS_DIR + "/" + filename;
        }
        // Pre-Q: direct file write.
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), REPORTS_DIR);
        if (!dir.exists()) dir.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(new File(dir, filename))) {
            fos.write(bytes);
        }
        return new File(dir, filename).getAbsolutePath();
    }
}
