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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Launcher-side rolling log: every line goes to logcat AND to a timestamped
 * file under {@code filesDir/logs/}. Used by the setup UI (JRE install, disc
 * copy, classpath assembly). Game output itself is captured by SCLogger.
 *
 * <p><b>Repeat collapsing.</b> The game log is mirrored here line for line,
 * and some of it is extremely repetitive: a single misbehaving subsystem can
 * emit the same five lines every two seconds for the whole session. Two
 * device sessions lost 9.5 MB and 9.9 MB to exactly that, which pushed the
 * interesting part of the session (the launch, the options, the renderer
 * selection, the mod load) out of the tail-capped session report. Lines that
 * differ only in their timestamps and in pointer values therefore share one
 * "shape"; the first occurrence is written, further identical shapes are
 * counted, and a summary line is emitted every
 * {@link #REPEAT_SUMMARY_EVERY} repeats so the count is never lost.
 */
public class LogFile {
    private static final String TAG = "LogFile";
    private static PrintWriter writer;
    private static File logFile;
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    /** How many suppressed repeats before a summary line is written. */
    private static final int REPEAT_SUMMARY_EVERY = 100;
    /** Upper bound on tracked line shapes (oldest are forgotten first). */
    private static final int MAX_TRACKED_SHAPES = 4096;

    /** Timestamps, at any position: {@code 12:34:56.789}. */
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{1,2}:\\d{2}:\\d{2}\\.\\d{3}");
    /** Pointer values: {@code 0x7bad37eb00}. */
    private static final Pattern POINTER = Pattern.compile("0x[0-9a-fA-F]+");

    /** shape -> {count, first line}. */
    private static final Map<String, int[]> repeatCounts = new LinkedHashMap<>();
    private static int totalSuppressed;

    public static void init(Context context) {
        try {
            File logDir = new File(context.getFilesDir(), "logs");
            if (!logDir.exists()) logDir.mkdirs();

            logFile = new File(logDir, "sclauncher_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".log");
            writer = new PrintWriter(new FileWriter(logFile, true), true);
            resetRepeats();
            log("=== SCLauncher log started ===");
            log("Log file: " + logFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to init log file", e);
        }
    }

    public static void log(String message) {
        final String timestamp = TIME_FORMAT.format(new Date());
        final String line = timestamp + " " + message;
        Log.i(TAG, line);
        write(line, message);
    }

    public static void error(String message) {
        final String timestamp = TIME_FORMAT.format(new Date());
        final String line = timestamp + " ERROR: " + message;
        Log.e(TAG, line);
        write(line, "ERROR: " + message);
    }

    public static void error(String message, Throwable t) {
        final String timestamp = TIME_FORMAT.format(new Date());
        final String line = timestamp + " ERROR: " + message;
        Log.e(TAG, line, t);
        write(line, "ERROR: " + message);
        if (writer != null) {
            t.printStackTrace(writer);
            writer.flush();
        }
    }

    /**
     * Write a line, collapsing repeats.
     *
     * <p>The "shape" of a line is the message with every timestamp and every
     * pointer value removed, so
     * {@code "04:33:22.856 [game] ... Created audio context 0x7bad37eb00"} and
     * the same line with a different time and a different address share one
     * shape. Numbers that carry meaning (script indices, counts, ...) are kept,
     * so distinct messages never collapse into each other.
     */
    private static void write(final String line, final String message) {
        if (writer == null) {
            return;
        }

        final String shape = shape(message);

        final int[] entry = repeatCounts.get(shape);
        if (entry != null) {
            entry[0]++;
            totalSuppressed++;
            if (entry[0] % REPEAT_SUMMARY_EVERY == 0) {
                writer.println(line + " [repeat #" + entry[0] + " of this line, further copies suppressed]");
            }
            writer.flush();
            return;
        }

        if (repeatCounts.size() >= MAX_TRACKED_SHAPES) {
            // Forget the oldest shape so a long session cannot grow the map
            // without bound. The count for the forgotten shape is lost, but
            // the line was written at least once.
            final String oldest = repeatCounts.keySet().iterator().next();
            final int[] dropped = repeatCounts.remove(oldest);
            totalSuppressed -= dropped[0];
        }
        repeatCounts.put(shape, new int[] { 0 });
        writer.println(line);
        writer.flush();
    }

    /** The message with timestamps and pointer values removed. */
    private static String shape(final String message) {
        if (message == null) {
            return "";
        }
        return POINTER.matcher(TIMESTAMP.matcher(message).replaceAll("")).replaceAll("#").trim();
    }

    private static void resetRepeats() {
        repeatCounts.clear();
        totalSuppressed = 0;
    }

    public static File getLogFile() {
        return logFile;
    }

    public static void flush() {
        if (writer != null) {
            writer.flush();
        }
    }

    /**
     * Write the current repeat tally to the log file.
     *
     * <p>Called before the session report is built (and on close), so the
     * report always states how many lines were suppressed rather than leaving
     * the reader to guess from the {@code [repeat #N]} markers.
     */
    public static void writeTally() {
        if (writer == null) {
            return;
        }
        if (totalSuppressed > 0) {
            writer.println("=== " + totalSuppressed + " repeated line(s) suppressed (see [repeat #N] markers) ===");
            writer.flush();
        }
    }

    /**
     * Flush and append the final repeat tally, so the session report still
     * shows how often the repeated lines occurred even though the copies
     * themselves were not written.
     */
    public static void close() {
        if (writer != null) {
            writeTally();
            writer.flush();
            writer.close();
            writer = null;
        }
    }
}
