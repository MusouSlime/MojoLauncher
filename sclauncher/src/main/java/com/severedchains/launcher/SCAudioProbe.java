package com.severedchains.launcher;

/**
 * Probes the bundled libopenal.so directly (dlopen + alcOpenDevice +
 * alcCreateContext + alcMakeContextCurrent + alGetString) for each backend,
 * WITHOUT LWJGL. Tells us whether silence is a broken backend (OpenSL vs
 * Oboe) or an LWJGL binding issue: if the probe reports a version string,
 * the library is fine and the game-side AL.createCapabilities path is at
 * fault; if it fails, the backend is broken and ALSOFT_DRIVERS must change.
 */
public class SCAudioProbe {
    private static boolean sLoaded = false;

    static {
        try {
            System.loadLibrary("sc_audio_probe");
            sLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            android.util.Log.e("SCAudio", "libsc_audio_probe.so missing", e);
        }
    }

    public static boolean isLoaded() {
        return sLoaded;
    }

    /**
     * @param libPath absolute path to libopenal.so
     * @return multi-line report, or an error line if the probe lib is missing.
     */
    public static native String probe(String libPath);
}
