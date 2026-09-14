package com.severedchains.launcher.shim;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

// Alias stub: same role as javafx.application.Platform (see that file).
public final class JavaFXPlatform {
    private static final Executor sExecutor = Executors.newSingleThreadExecutor();
    private static boolean sImplicitExit = true;

    private JavaFXPlatform() {}

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
}