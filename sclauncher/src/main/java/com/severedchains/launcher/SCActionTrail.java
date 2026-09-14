package com.severedchains.launcher;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Breadcrumb trail of the user's last actions (launches, disc copies, JRE
 * installs, controller changes, exits). Kept as a small in-memory ring so
 * asking "what did the user just do?" never needs disk I/O; the trail is
 * appended to every Reports export (see SCLogExporter) written when the
 * app is closed or returns to the launcher.
 */
public final class SCActionTrail {
    private static final int CAPACITY = 50;
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private static final Deque<String> sTrail = new ArrayDeque<>(CAPACITY + 1);

    private SCActionTrail() {}

    /** Record one user-visible action. Thread-safe, never throws. */
    public static synchronized void record(String action) {
        if (action == null) return;
        try {
            if (sTrail.size() >= CAPACITY) sTrail.removeFirst();
            sTrail.addLast(TIME_FORMAT.format(new Date()) + " " + action);
        } catch (Exception ignored) {}
    }

    /** Oldest-first snapshot for reports. Never null. */
    public static synchronized List<String> snapshot() {
        return new ArrayList<>(sTrail);
    }

    /** Formatted block for embedding in a report file. */
    public static synchronized String dump() {
        if (sTrail.isEmpty()) return "(no actions recorded)\n";
        StringBuilder sb = new StringBuilder();
        for (String a : sTrail) sb.append(a).append('\n');
        return sb.toString();
    }
}
