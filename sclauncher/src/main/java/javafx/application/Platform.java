package javafx.application;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

// JavaFX stub: the game calls setImplicitExit()/runLater()/exit(). There is
// no UI toolkit here, so runLater() just runs on a background thread and
// exit() is a no-op (the game calls it from its window-close handler while
// it is already shutting down; the launcher owns process exit).
public final class Platform {
    private static final Executor sExecutor = Executors.newSingleThreadExecutor();
    private static boolean sImplicitExit = true;

    private Platform() {}

    public static void setImplicitExit(boolean implicitExit) {
        sImplicitExit = implicitExit;
    }

    public static boolean isImplicitExit() {
        return sImplicitExit;
    }

    public static void runLater(Runnable runnable) {
        if (runnable == null) {
            throw new NullPointerException("Runnable cannot be null");
        }
        sExecutor.execute(runnable);
    }

    public static boolean isFxApplicationThread() {
        return false;
    }

    public static void exit() {
        // No toolkit to tear down; the game is already exiting (called from
        // Scus94491BpeSegment's renderer onClose after stopSound/AUDIO stop).
    }
}