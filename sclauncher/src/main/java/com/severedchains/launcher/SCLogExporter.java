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
    // Crash recovery.
    //
    // The clean-exit exports above never run when the process dies hard
    // (native VM abort / SIGKILL). To keep a retrievable log anyway, the game
    // screen writes a small marker when a session starts and deletes it on a
    // clean exit. If the next launcher start still finds the marker, the
    // previous session died abruptly, so its logs are exported to
    // Downloads/Reports as a crash-recovery report.
    // ------------------------------------------------------------------

    /** Marker written when a game session starts, cleared on a clean exit. */
    public static final String SESSION_MARKER = ".game_session_active";

    /** Called when the game screen starts: remembers which log a hard kill should keep. */
    public static void beginGameSession(Context ctx) {
        if (ctx == null) return;
        try {
            File marker = new File(ctx.getFilesDir(), SESSION_MARKER);
            File log = LogFile.getLogFile();
            File latest = new File(ctx.getFilesDir(), "latestlog.txt");
            String body = (log != null ? log.getAbsolutePath() : "") + "\n"
                    + latest.getAbsolutePath() + "\n"
                    + System.currentTimeMillis() + "\n";
            try (FileOutputStream fos = new FileOutputStream(marker, false)) {
                fos.write(body.getBytes("UTF-8"));
                fos.flush();
            }
        } catch (Exception e) {
            Log.w(TAG, "beginGameSession failed", e);
        }
    }

    /** Called on a clean game exit so the marker can't trigger a false recovery. */
    public static void endGameSession(Context ctx) {
        if (ctx == null) return;
        try {
            new File(ctx.getFilesDir(), SESSION_MARKER).delete();
        } catch (Exception ignored) {}
    }

    /**
     * If a previous game session left its marker behind (hard kill / VM abort),
     * export that session's launcher log, latestlog and the game's own
     * debug.log / CRASHLOG to Downloads/Reports, then clear the marker.
     * Fire-and-forget; safe to call on every launcher start.
     */
    public static void exportCrashRecoveryIfNeeded(Context ctx, File gameDir) {
        final Context app = ctx != null ? ctx.getApplicationContext() : null;
        if (app == null) return;
        final File marker = new File(app.getFilesDir(), SESSION_MARKER);
        if (!marker.exists()) return;
        new Thread(() -> {
            try {
                final String[] lines = readAllText(marker).split("\n");
                final String prevLogPath = lines.length > 0 ? lines[0].trim() : "";
                final File prevLog = prevLogPath.isEmpty() ? null : new File(prevLogPath);

                final StringBuilder sb = new StringBuilder();
                appendHeader(app, sb, "previous game session did not exit cleanly (crash recovery)");
                appendSection(sb, "previous session log", prevLog);
                appendSection(sb, "latestlog.txt", new File(app.getFilesDir(), "latestlog.txt"));
                if (gameDir != null) {
                    appendSection(sb, "game debug.log", new File(gameDir, "debug.log"));
                    appendSection(sb, "game crash log", newestFile(gameDir, "CRASHLOG-", ".log"));
                }
                withTrail(sb);

                final String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                final String filename = "report_" + stamp + "_crash-recovery.log";
                final String where = writeReport(app, filename, sb.toString());
                LogFile.log("Crash-recovery report saved: " + where);
            } catch (Exception e) {
                Log.e(TAG, "Crash-recovery export failed", e);
            } finally {
                marker.delete();
            }
        }, "SCLogRecovery").start();
    }

    // ------------------------------------------------------------------
    // Internals.
    // ------------------------------------------------------------------

    private static void appendHeader(Context ctx, StringBuilder sb, String reason) {
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
    }

    private static void appendSection(StringBuilder sb, String label, File f) {
        if (f == null || !f.exists()) return;
        sb.append("--- ").append(label).append(": ").append(f.getName()).append(" ---\n");
        sb.append(readTail(f)).append("\n");
    }

    private static File newestFile(File dir, String prefix, String suffix) {
        File best = null;
        File[] files = dir != null ? dir.listFiles() : null;
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (n.startsWith(prefix) && n.endsWith(suffix)
                        && (best == null || f.lastModified() > best.lastModified())) {
                    best = f;
                }
            }
        }
        return best;
    }

    private static String readAllText(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] buf = new byte[(int) Math.min(raf.length(), 4096)];
            raf.readFully(buf);
            return new String(buf, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static void exportNow(Context ctx, String reason) {
        if (!sExporting.compareAndSet(false, true)) return;
        try {
            // Make sure the repeat tally is in the file before its tail is
            // read, otherwise the report would not say how many lines the
            // log collapsing suppressed.
            LogFile.writeTally();
            LogFile.flush();

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
