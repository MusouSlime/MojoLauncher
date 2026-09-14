package com.severedchains.launcher;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Setup UI: gets everything ready, then hands off to SCGameActivity.
 *
 * <p>Responsibilities, in launch order:
 * <ol>
 *   <li>Extract the bundled game + LWJGL natives from APK assets (once,
 *   stamped with a version marker; upgrades preserve {@code isos/} and
 *   {@code saves/}).</li>
 *   <li>Find or install JRE 25 (bundled asset, network download, or
 *   user-imported archive).</li>
 *   <li>Fetch the Android ffmpeg jar for the intro FMV and pre-extract its
 *   natives for JavaCPP.</li>
 *   <li>Assemble the classpath (game jars + bionic LWJGL stack, glibc
 *   natives/JavaFX/desktop-ffmpeg filtered out) and build the JVM args.</li>
 *   <li>Collect the 4 LoD disc images via the system file picker.</li>
 *   <li>Start SCGameActivity with all of the above as intent extras.</li>
 * </ol>
 */
public class SCLauncherActivity extends AppCompatActivity {
    private static final String TAG = "SCLauncher";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private static final String JRE_BASE_URL = "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre25/";
    private static final String JRE_ARM64_FILE = "jre25-android-arm64.tar.xz";
    private static final String JRE_X86_64_FILE = "jre25-android-x86_64.tar.xz";
    private static final String JRE_RUNTIME_NAME = "Internal-25";
    private static final int JRE_REQUIRED_VERSION = 25;
    private static final String LOCAL_JRE_ASSET = "jre/jre25-android-arm64.tar.xz";

    private static final String FFMPEG_MAVEN_BASE = "https://repo1.maven.org/maven2/org/bytedeco/ffmpeg/8.0.1-1.5.13/";
    private static final String FFMPEG_ANDROID_FILE = "ffmpeg-8.0.1-1.5.13-android-arm64.jar";

    public static final String MAIN_JAR_NAME = "lod-game-snapshot.jar";

    private ProgressBar progressBar;
    private TextView statusText;
    private TextView jreStatusText;
    private TextView downloadStatusText;
    private Button launchButton;
    private Button searchAgainButton;
    private Button sendLogButton;
    private Button downloadJreButton;
    private Button importJreButton;
    private Button exportSavesButton;
    private Button importSavesButton;

    private String dirData;
    private String dirCache;
    private String nativeLibDir;
    private File gameDir;
    private File[] classpath;
    private String foundJreName;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private static final String[] DISC_FILE_PATTERNS = {
            "disc1.bin", "disc2.bin", "disc3.bin", "disc4.bin"};
    private int mDiscTarget = 0;
    private final Button[] mDiscButtons = new Button[4];

    private final ActivityResultLauncher<Intent> mPickFileLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Intent data = result.getData();
                // Explicit per-disc buttons: the file goes exactly where the
                // user pointed it. Multi-select takes the first item.
                Uri uri = data.getData();
                if (uri == null && data.getClipData() != null
                        && data.getClipData().getItemCount() > 0) {
                    uri = data.getClipData().getItemAt(0).getUri();
                }
                if (uri != null && mDiscTarget >= 0
                        && mDiscTarget < DISC_FILE_PATTERNS.length) {
                    copyDiscImage(uri, DISC_FILE_PATTERNS[mDiscTarget]);
                }
            }
        }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sclauncher);

        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        TextView titleText = findViewById(R.id.titleText);
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            titleText.setText("Severed Chains Launcher v" + pi.versionName
                    + " (" + pi.versionCode + ")");
            LogFile.log("App version: " + pi.versionName + " (" + pi.versionCode + ")");
        } catch (Exception e) {
            Log.w(TAG, "version lookup failed", e);
        }
        jreStatusText = findViewById(R.id.jreStatusText);
        downloadStatusText = findViewById(R.id.downloadStatusText);
        launchButton = findViewById(R.id.launchButton);
        searchAgainButton = findViewById(R.id.searchAgainButton);
        sendLogButton = findViewById(R.id.sendLogButton);
        sendLogButton.setOnClickListener(v -> sendLastLog());
        downloadJreButton = findViewById(R.id.downloadJreButton);
        mDiscButtons[0] = findViewById(R.id.pickDisc1Button);
        mDiscButtons[1] = findViewById(R.id.pickDisc2Button);
        mDiscButtons[2] = findViewById(R.id.pickDisc3Button);
        mDiscButtons[3] = findViewById(R.id.pickDisc4Button);
        importJreButton = findViewById(R.id.importJreButton);
        exportSavesButton = findViewById(R.id.exportSavesButton);
        importSavesButton = findViewById(R.id.importSavesButton);

        importJreButton.setOnClickListener(v -> importJreFromStorage());
        exportSavesButton.setOnClickListener(v -> exportSaves());
        importSavesButton.setOnClickListener(v -> pickSavesArchive());
        for (int i = 0; i < 4; i++) {
            final int index = i;
            mDiscButtons[i].setOnClickListener(v -> pickDiscImage(index));
        }

        dirData = getFilesDir().getParent();
        dirCache = getCacheDir().getAbsolutePath();
        nativeLibDir = getApplicationInfo().nativeLibraryDir;

        // Use internal storage for game files (no external storage permission needed)
        gameDir = new File(getFilesDir(), "games/SCLauncher");

        // Saves live in shared storage (symlinked) so non-root users can reach them.
        setupSavesLink();

        // Initialize file logging
        LogFile.init(this);
        LogFile.log("SCLauncherActivity onCreate");
        LogFile.log("dirData=" + dirData + ", dirCache=" + dirCache + ", nativeLibDir=" + nativeLibDir);
        LogFile.log("gameDir=" + gameDir.getAbsolutePath());
        SCGpuInfo.logOnce(this);
        // Low-end preflight (storage/RAM/GLES3): warn early with a plain
        // message instead of failing later with a confusing error.
        try {
            String preflight = SCGpuInfo.preflight(this);
            if (preflight != null) {
                LogFile.log("Preflight warning: " + preflight);
                SCActionTrail.record("Preflight warning shown");
                final String msg = preflight;
                uiHandler.post(() -> Toast.makeText(SCLauncherActivity.this,
                        msg, Toast.LENGTH_LONG).show());
            }
        } catch (Exception e) {
            LogFile.error("Preflight check failed", e);
        }

        // Extract bundled game files on first run
        extractBundledGameFiles();

        launchButton.setOnClickListener(v -> launchGame());
        searchAgainButton.setOnClickListener(v -> refreshAll());
        downloadJreButton.setOnClickListener(v -> downloadJre25());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_MEDIA_IMAGES}, PERMISSION_REQUEST_CODE);
            } else {
                refreshAll();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
            } else {
                refreshAll();
            }
        } else {
            refreshAll();
        }
    }

    // ------------------------------------------------------------------
    // First-run bundle extraction: APK assets -> gameDir, guarded by a
    // version marker so upgrades wipe stale jars but keep isos/ + saves/.
    // ------------------------------------------------------------------
    private void extractBundledGameFiles() {
        File markerV4 = new File(gameDir, ".bundled_extracted_v4");
        if (markerV4.exists()) {
            LogFile.log("Bundled game files already extracted (v4)");
            extractSclauncherNatives();
            refreshAll();
            return;
        }
        // v1/v2/v3 -> v4 upgrade: refresh bundle (e.g. patched AudioThread:
        // OpenAL10 caps check bypassed, mobile OpenAL-Soft never reports it).
        // Preserve user data (isos/, saves/).
        File markerV1 = new File(gameDir, ".bundled_extracted");
        File markerV2 = new File(gameDir, ".bundled_extracted_v2");
        File markerV3 = new File(gameDir, ".bundled_extracted_v3");
        if (markerV1.exists() || markerV2.exists() || markerV3.exists()) {
            LogFile.log("Upgrading game bundle to v4, preserving isos/ + saves/");
            preserveAndWipeGameDir();
            markerV1.delete();
            markerV2.delete();
            markerV3.delete();
        }
        LogFile.log("Extracting bundled game files from assets...");
        statusText.setText("Extracting game files (first run)...");
        progressBar.setVisibility(View.VISIBLE);
        launchButton.setEnabled(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    gameDir.mkdirs();
                    extractAssetsDir("game", gameDir);
                    markerV4.createNewFile();
                    LogFile.log("Bundled game files extracted to " + gameDir.getAbsolutePath());
                    extractSclauncherNatives();
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            progressBar.setVisibility(View.GONE);
                            statusText.setText("Game files ready.");
                            refreshAll();
                        }
                    });
                } catch (Exception e) {
                    LogFile.error("Failed to extract bundled game files", e);
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            progressBar.setVisibility(View.GONE);
                            statusText.setText("Extract failed: " + e.getMessage());
                            Toast.makeText(SCLauncherActivity.this, "Extract failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * Saves live in shared storage where non-root users (and the player) can
     * reach them: <sdcard>/Android/data/<pkg>/files/Saves (USB/file manager,
     * no root). gameDir/saves is a symlink there so the game sees no change.
     * Existing save files are moved, never deleted.
     */
    private void setupSavesLink() {
        try {
            File extRoot = getExternalFilesDir(null);
            if (extRoot == null) {
                LogFile.log("External storage unavailable, saves stay internal");
                return;
            }
            File extSaves = new File(extRoot, "Saves");
            extSaves.mkdirs();
            File link = new File(gameDir, "saves");
            try {
                if (!link.getCanonicalPath().equals(link.getAbsolutePath())) {
                    // Already a link: repoint if stale.
                    if (!link.getCanonicalPath().equals(extSaves.getCanonicalPath())) {
                        link.delete();
                    } else {
                        LogFile.log("Saves reachable at: " + extSaves.getAbsolutePath());
                        return;
                    }
                }
            } catch (Exception e) {
                LogFile.error("Saves link check failed", e);
            }
            if (link.exists()) {
                // Real directory with user data: move contents out first.
                File[] children = link.listFiles();
                if (children != null) {
                    for (File child : children) {
                        copyRecursive(child, new File(extSaves, child.getName()));
                    }
                }
                deleteRecursive(link);
            }
            android.system.Os.symlink(extSaves.getAbsolutePath(), link.getAbsolutePath());
            LogFile.log("Saves reachable at: " + extSaves.getAbsolutePath());
        } catch (Exception e) {
            LogFile.error("Saves link setup failed (saves stay internal)", e);
        }
    }

    private void preserveAndWipeGameDir() {
        File isos = new File(gameDir, "isos");
        File saves = new File(gameDir, "saves");
        File tmpRoot = new File(getCacheDir(), "game_upgrade_tmp");
        deleteRecursive(tmpRoot);
        tmpRoot.mkdirs();
        try {
            if (isos.exists()) copyRecursive(isos, new File(tmpRoot, "isos"));
            if (saves.exists()) copyRecursive(saves, new File(tmpRoot, "saves"));
        } catch (Exception e) {
            LogFile.error("Failed to preserve isos/saves", e);
        }
        File[] children = gameDir.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        try {
            File keptIsos = new File(tmpRoot, "isos");
            File keptSaves = new File(tmpRoot, "saves");
            if (keptIsos.exists()) copyRecursive(keptIsos, isos);
            if (keptSaves.exists()) copyRecursive(keptSaves, saves);
        } catch (Exception e) {
            LogFile.error("Failed to restore isos/saves", e);
        }
        deleteRecursive(tmpRoot);
        // Saves live outside gameDir (symlink); re-establish after wipe.
        setupSavesLink();
    }

    private void copyRecursive(File src, File dst) throws IOException {
        try {
            if (!src.getCanonicalPath().equals(src.getAbsolutePath())) {
                return; // symlink: never follow, caller handles links
            }
        } catch (Exception e) {
            throw new IOException(e);
        }
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursive(child, new File(dst, child.getName()));
                }
            }
        } else {
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // LWJGL bionic stack: java + natives jars extracted once to
    // gameDir/libs_natives and appended to the game classpath.
    // ------------------------------------------------------------------
    private void extractSclauncherNatives() {
        File nativesDir = new File(gameDir, "libs_natives");
        File marker = new File(nativesDir, ".extracted_v6");
        if (marker.exists()) {
            return;
        }
        // v5 -> v6: complete monolithic libopus.so (opus 1.6.1 full API:
        // 24-bit + custom + DRED + projection, opusfile, libopusenc). The v5
        // monolith (opus 1.5.2, custom/DRED off, no opusenc) still died in
        // Opus$Functions.<clinit> (opus_encode24 missing) during unpack.
        for (String old : new String[]{".extracted", ".extracted_v2", ".extracted_v3", ".extracted_v4", ".extracted_v5"}) {
            File oldMarker = new File(nativesDir, old);
            if (oldMarker.exists()) {
                LogFile.log("Upgrading natives to v6, wiping libs_natives/");
                deleteRecursive(nativesDir);
                break;
            }
        }
        try {
            nativesDir.mkdirs();
            extractAssetsDir("sclauncher_natives", nativesDir);
            marker.createNewFile();
            LogFile.log("SCLauncher natives extracted to " + nativesDir.getAbsolutePath());
        } catch (Exception e) {
            LogFile.error("Failed to extract SCLauncher natives", e);
        }
    }

    private void extractAssetsDir(String assetPath, File destDir) throws IOException {
        String[] files = getAssets().list(assetPath);
        if (files == null) return;
        for (String file : files) {
            String subPath = assetPath + "/" + file;
            File destFile = new File(destDir, file);
            String[] subFiles = getAssets().list(subPath);
            if (subFiles != null && subFiles.length > 0) {
                destFile.mkdirs();
                extractAssetsDir(subPath, destFile);
            } else {
                InputStream in = getAssets().open(subPath);
                FileOutputStream out = new FileOutputStream(destFile);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.close();
                in.close();
                LogFile.log("Extracted: " + subPath);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            refreshAll();
        }
    }

    private void refreshAll() {
        downloadJreButton.setVisibility(View.GONE);
        downloadStatusText.setVisibility(View.GONE);
        checkGameFiles();
        checkJreStatus();
        updateDiscButtons();
        ensureFfmpegAndroid();
    }

    /** Shows which of the 4 discs are on disk (user maps them explicitly). */
    private void updateDiscButtons() {
        if (gameDir == null) return;
        File isosDir = new File(gameDir, "isos");
        for (int i = 0; i < 4; i++) {
            if (mDiscButtons[i] == null) continue;
            boolean present = new File(isosDir, DISC_FILE_PATTERNS[i]).exists();
            mDiscButtons[i].setText("Disc " + (i + 1) + (present ? " ✓" : ""));
        }
    }

    // ------------------------------------------------------------------
    // Intro-FMV support: bytedeco needs the android-arm64 ffmpeg build.
    // Downloaded once from Maven (atomic .part + rename, zip-validated),
    // then its .so files are extracted for -Dplatform.library.path.
    // The launch button stays disabled while this runs.
    // ------------------------------------------------------------------
    private volatile boolean mFfmpegDownloadRunning = false;

    /** bytedeco needs the bionic ffmpeg build for the intro FMV; fetch once. */
    private void ensureFfmpegAndroid() {
        if (gameDir == null || mFfmpegDownloadRunning) return;
        File libsDir = new File(gameDir, "libs");
        File target = new File(libsDir, FFMPEG_ANDROID_FILE);
        // bytedeco 1.5.13 ships Android libs under lib/<abi>/ inside the jar.
        if (target.exists() && target.length() > 1024 * 1024) {
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(target)) {
                if (zf.getEntry("lib/arm64-v8a/libjniavutil.so") != null) {
                    LogFile.log("android-arm64 ffmpeg present, intro video ready");
                    extractFfmpegNatives(target);
                    return;
                }
                LogFile.log("ffmpeg android jar missing jniavutil, redownloading");
            } catch (Exception e) {
                LogFile.log("ffmpeg android jar unreadable, redownloading: " + e.getMessage());
            }
            target.delete();
        } else if (target.exists()) {
            LogFile.log("ffmpeg android jar too small (" + target.length() + "b), redownloading");
            target.delete();
        }
        File[] need = libsDir.listFiles((dir, name) ->
                name.startsWith("javacv-") && name.endsWith(".jar"));
        if (need == null || need.length == 0) {
            LogFile.log("javacv not bundled, skipping ffmpeg download");
            return;
        }
        mFfmpegDownloadRunning = true;
        LogFile.log("Downloading android-arm64 ffmpeg for intro video...");
        uiHandler.post(() -> {
            launchButton.setEnabled(false);
            if (statusText.getText().toString().startsWith("Ready to launch")) {
                statusText.setText("Downloading video support, one moment...");
            }
        });
        new Thread(() -> {
            try {
                // Atomic: game must never see a partial jar on the classpath.
                File part = new File(libsDir, FFMPEG_ANDROID_FILE + ".part");
                part.delete();
                downloadFile(FFMPEG_MAVEN_BASE + FFMPEG_ANDROID_FILE, part);
                LogFile.log("ffmpeg download bytes: " + part.length());
                // Validate BEFORE rename: truncated downloads are the norm on
                // mobile data and produce jars that fail later with confusing
                // loader errors instead of a clear message here.
                try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(part)) {
                    if (zf.getEntry("lib/arm64-v8a/libjniavutil.so") == null) {
                        throw new IOException("downloaded jar lacks libjniavutil.so");
                    }
                } catch (java.util.zip.ZipException e) {
                    throw new IOException("downloaded jar is corrupt/truncated", e);
                }
                if (!part.renameTo(new File(libsDir, FFMPEG_ANDROID_FILE))) {
                    throw new IOException("rename of ffmpeg download failed");
                }
                LogFile.log("Downloaded " + FFMPEG_ANDROID_FILE);
                SCActionTrail.record("Video support downloaded (ffmpeg android)");
                uiHandler.post(() -> {
                    mFfmpegDownloadRunning = false;
                    refreshAll();
                });
            } catch (Exception e) {
                LogFile.error("ffmpeg android download failed (intro video may not play)", e);
                mFfmpegDownloadRunning = false;
                uiHandler.post(() -> refreshAll());
            }
        }).start();
    }

    /**
     * JavaCPP's classpath scan is unreliable here, but it always honors
     * `-Dplatform.library.path`. Extract the .so files once so loading is
     * a plain path lookup instead of jar scanning + cache extraction.
     */
    private void extractFfmpegNatives(File jar) {
        File outDir = new File(gameDir, "ffmpeg_natives");
        File done = new File(outDir, ".extracted_" + jar.length());
        if (done.exists() && new File(outDir, "libjniavutil.so").exists()) {
            File[] sos = outDir.listFiles((dir, name) -> name.endsWith(".so"));
            SCLogger.appendToLog("ffmpeg natives ready: " + (sos == null ? 0 : sos.length)
                    + " libs in " + outDir.getAbsolutePath());
            return;
        }
        deleteRecursive(outDir);
        outDir.mkdirs();
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            int count = 0;
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                String name = e.getName();
                if (name.startsWith("lib/arm64-v8a/") && name.endsWith(".so")) {
                    String base = name.substring(name.lastIndexOf('/') + 1);
                    try (InputStream in = zf.getInputStream(e);
                         FileOutputStream out = new FileOutputStream(new File(outDir, base))) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    count++;
                }
            }
            done.createNewFile();
            LogFile.log("Extracted " + count + " ffmpeg natives to " + outDir.getAbsolutePath());
        } catch (Exception e) {
            LogFile.error("Failed to extract ffmpeg natives", e);
        }
    }

    // ------------------------------------------------------------------
    // Classpath assembly: game main jar + libs, minus everything that only
    // runs on desktop Linux (glibc natives, game LWJGL, JavaFX shim target,
    // desktop ffmpeg), plus our bionic LWJGL stack from libs_natives/.
    // ------------------------------------------------------------------
    private void checkGameFiles() {
        File mainJar = new File(gameDir, MAIN_JAR_NAME);
        File libsDir = new File(gameDir, "libs");

        if (!mainJar.exists() || !libsDir.exists()) {
            statusText.setText("Game files not found.\nPlace Severed Chains in:\n" + gameDir.getAbsolutePath());
            launchButton.setEnabled(false);
            return;
        }

        List<File> cpEntries = new ArrayList<>();
        cpEntries.add(mainJar);

        File[] libJars = libsDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".jar");
            }
        });
        if (libJars != null) {
            Arrays.sort(libJars);

            // New (SDL/GLES) game build on LWJGL 3.4.2: we run one consistent
            // Mojo 3.4.3 LWJGL stack (java + bionic natives from libs_natives/),
            // so drop ALL game lwjgl jars. Also drop glibc natives, the JavaFX
            // jars (shim), and desktop bytedeco platform jars (the android-arm64
            // ffmpeg jar is downloaded at runtime, see ensureFfmpegAndroid()).
            for (File jar : libJars) {
                String name = jar.getName();

                if (name.contains("-natives-")) {
                    LogFile.log("Excluding glibc natives: " + name);
                    continue;
                }
                if (name.startsWith("javafx-")) {
                    LogFile.log("Excluding JavaFX: " + name);
                    continue;
                }
                if (name.startsWith("lwjgl-")) {
                    LogFile.log("Excluding game LWJGL jar (Mojo 3.4.3 stack wins): " + name);
                    continue;
                }
                if (name.startsWith("ffmpeg-") && name.contains("-linux-")) {
                    LogFile.log("Excluding desktop ffmpeg (android-arm64 comes at runtime): " + name);
                    continue;
                }

                cpEntries.add(jar);
            }
        }

        // Add our bionic natives + Mojo's modified SDL/OpenAL Java jars
        // from extracted assets (gameDir/libs_natives).
        File nativesDir = new File(gameDir, "libs_natives");
        if (nativesDir.exists()) {
            File[] ourNatives = nativesDir.listFiles((dir, name) -> name.endsWith(".jar"));
            if (ourNatives != null) {
                Arrays.sort(ourNatives);
                for (File jar : ourNatives) {
                    cpEntries.add(jar);
                    LogFile.log("Adding our native jar: " + jar.getName());
                }
            }
        }
        
        classpath = cpEntries.toArray(new File[0]);

        statusText.setText("Game files found (" + classpath.length + " classpath entries).");
        updateLaunchReady();
    }

    private void checkJreStatus() {
        foundJreName = findJre(JRE_REQUIRED_VERSION);
        if (foundJreName != null) {
            jreStatusText.setText("JRE 25: " + foundJreName + " \u2713");
            jreStatusText.setTextColor(0xFF88FF88);
            jreStatusText.setVisibility(View.VISIBLE);
            downloadJreButton.setVisibility(View.GONE);
            updateLaunchReady();
        } else {
            jreStatusText.setText("JRE 25: Not installed");
            jreStatusText.setTextColor(0xFFFF8888);
            jreStatusText.setVisibility(View.VISIBLE);
            downloadJreButton.setVisibility(View.VISIBLE);
            launchButton.setEnabled(false);
        }
    }

    private void updateLaunchReady() {
        if (classpath != null && classpath.length > 0 && foundJreName != null
                && !mFfmpegDownloadRunning) {
            statusText.setText("Ready to launch.");
            launchButton.setEnabled(true);
        } else if (mFfmpegDownloadRunning) {
            launchButton.setEnabled(false);
        }
    }

    // ------------------------------------------------------------------
    // Hand-off: package runtime + vmargs + classpath + main class as intent
    // extras and start SCGameActivity (singleTask: relaunch reuses it, so a
    // second JVM is never created in one process).
    // ------------------------------------------------------------------
    private void launchGame() {
        launchButton.setEnabled(false);
        searchAgainButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText("Starting game display with " + foundJreName + "...");

        try {
            List<String> vmArgs = buildVmArgs(foundJreName);

            ArrayList<String> cpStrings = new ArrayList<String>();
            for (File entry : classpath) {
                if (entry.exists()) {
                    cpStrings.add(entry.getAbsolutePath());
                } else {
                    LogFile.log("Skipped missing: " + entry.getAbsolutePath());
                }
            }

            String mainClass = "legend.game.Main";
            ArrayList<String> appArgs = new ArrayList<String>();
            appArgs.add("-Xmx2G");
            appArgs.add("-ea");

            LogFile.log("Launching SCGameActivity with runtime: " + foundJreName);
            SCActionTrail.record("Launch game (" + foundJreName + ", "
                    + cpStrings.size() + " classpath entries)");
            Intent intent = new Intent(this, SCGameActivity.class);
            intent.putExtra(SCGameActivity.EXTRA_RUNTIME, foundJreName);
            intent.putStringArrayListExtra(SCGameActivity.EXTRA_VMARGS, new ArrayList<>(vmArgs));
            intent.putStringArrayListExtra(SCGameActivity.EXTRA_CP, cpStrings);
            intent.putExtra(SCGameActivity.EXTRA_MAIN, mainClass);
            intent.putStringArrayListExtra(SCGameActivity.EXTRA_APPARGS, appArgs);
            intent.putExtra(SCGameActivity.EXTRA_DIRDATA, dirData);
            intent.putExtra(SCGameActivity.EXTRA_DIRCACHE, dirCache);
            intent.putExtra(SCGameActivity.EXTRA_NATIVELIBDIR, nativeLibDir);
            startActivity(intent);
        } catch (final Exception e) {
            LogFile.error("Launch failed", e);
            String fullError = getFullErrorReport(e);
            LogFile.error(fullError);
            showErrorDialog(fullError);
        } finally {
            progressBar.setVisibility(View.GONE);
            launchButton.setEnabled(true);
            searchAgainButton.setEnabled(true);
            statusText.setText("Game display launched — see game screen + log.");
        }
    }

    private String getFullErrorReport(Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        String stackTrace = sw.toString();

        StringBuilder report = new StringBuilder();
        report.append("=== SCLauncher Error Report ===\n");
        report.append("Time: ").append(new java.util.Date()).append("\n");
        report.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        report.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        report.append(SCGpuInfo.summary()).append("\n");
        report.append("Arch: ").append(System.getProperty("os.arch")).append("\n");
        report.append("Java VM: ").append(System.getProperty("java.vm.name")).append(" ").append(System.getProperty("java.version")).append("\n");
        report.append("Error: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n\n");
        report.append("Stack Trace:\n").append(stackTrace).append("\n");
        
        // Add JVM args if available
        if (classpath != null) {
            report.append("\nClasspath (").append(classpath.length).append(" entries):\n");
            for (File f : classpath) {
                report.append("  ").append(f.getAbsolutePath()).append(" (").append(f.exists() ? "EXISTS" : "MISSING").append(")\n");
            }
        }
        
        // Add JRE info
        if (foundJreName != null) {
            File jreDir = new File(dirData, "runtimes/" + foundJreName);
            report.append("\nJRE Dir: ").append(jreDir.getAbsolutePath()).append(" exists=").append(jreDir.exists()).append("\n");
            if (jreDir.exists()) {
                File[] files = jreDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        report.append("  ").append(f.getName()).append(f.isDirectory() ? "/" : "").append("\n");
                    }
                }
            }
        }
        
        return report.toString();
    }

    private void showErrorDialog(final String errorReport) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                progressBar.setVisibility(View.GONE);
                launchButton.setEnabled(true);
                searchAgainButton.setEnabled(true);
                
                // Show detailed dialog
                new AlertDialog.Builder(SCLauncherActivity.this)
                    .setTitle("JVM Launch Failed")
                    .setMessage("Error: " + (errorReport.contains("\n") ? errorReport.substring(0, errorReport.indexOf("\n")) : errorReport))
                    .setPositiveButton("Copy Full Log", (dialog, which) -> copyLogToShare(errorReport))
                    .setNegativeButton("Dismiss", null)
                    .setNeutralButton("Save to Downloads", (dialog, which) -> saveLogToDownloads(errorReport))
                    .show();
                
                statusText.setText("Launch failed - see dialog for details");
            }
        });
    }

    // ------------------------------------------------------------------
    // Send Log: shares the LAST log the game made (latestlog.txt tail +
    // launcher log tail) via any share target, or saves to Downloads.
    // Available from the main menu even when the game never launched (then
    // it's just the launcher log). Never throws to the UI thread.
    // ------------------------------------------------------------------
    private void sendLastLog() {
        SCActionTrail.record("Send Log opened from main menu");
        new Thread(() -> {
            final String report = buildLastLogReport();
            uiHandler.post(() -> new AlertDialog.Builder(SCLauncherActivity.this)
                    .setTitle("Last game log")
                    .setMessage(report.length() > 2000
                            ? report.substring(0, 2000) + "\n…(full log shared/saved)"
                            : report)
                    .setPositiveButton("Share", (dialog, which) -> copyLogToShare(report))
                    .setNeutralButton("Save to Downloads", (dialog, which) -> saveLogToDownloads(report))
                    .setNegativeButton("Dismiss", null)
                    .show());
        }).start();
    }

    /** Device header + launcher-log tail + last game-log (latestlog.txt) tail. */
    private String buildLastLogReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SCLauncher Last Log ===\n");
        sb.append("Time: ").append(new java.util.Date()).append("\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append(SCGpuInfo.summary()).append("\n");
        File launcher = LogFile.getLogFile();
        sb.append("\n--- launcher log ---\n").append(readTail(launcher, 256 * 1024));
        File latest = new File(getFilesDir(), "latestlog.txt");
        sb.append("\n--- latestlog.txt (last game log) ---\n").append(readTail(latest, 512 * 1024));
        sb.append("\n--- last actions ---\n").append(SCActionTrail.dump());
        return sb.toString();
    }

    /** Last maxBytes of f (whole file when smaller); friendly note when absent. */
    private static String readTail(File f, long maxBytes) {
        if (f == null || !f.exists()) return "(no log file yet)\n";
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - maxBytes);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            int n = 0, r;
            while (n < buf.length && (r = raf.read(buf, n, buf.length - n)) != -1) n += r;
            String prefix = start > 0 ? "[... truncated " + start + " bytes ...]\n" : "";
            return prefix + new String(buf, 0, n, "UTF-8") + "\n";
        } catch (Exception e) {
            return "(could not read " + f.getName() + ": " + e.getMessage() + ")\n";
        }
    }

    private void copyLogToShare(String errorReport) {        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "SCLauncher Error Report");
        shareIntent.putExtra(Intent.EXTRA_TEXT, errorReport);
        Intent chooser = Intent.createChooser(shareIntent, "Share log via...");
        if (shareIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(chooser);
        } else {
            Toast.makeText(this, "No app to share with", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveLogToDownloads(String errorReport) {
        new Thread(() -> {
            try {
                // Try MediaStore approach for Android 10+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStore(errorReport);
                } else {
                    saveLegacy(errorReport);
                }
            } catch (Exception ex) {
                // Fallback to internal storage
                try {
                    File internalLog = new File(getFilesDir(), "sclauncher_error_" + System.currentTimeMillis() + ".txt");
                    FileOutputStream fos = new FileOutputStream(internalLog);
                    fos.write(errorReport.getBytes("UTF-8"));
                    fos.close();
                    uiHandler.post(() -> Toast.makeText(this, "Log saved to app storage: " + internalLog.getName(), Toast.LENGTH_LONG).show());
                } catch (Exception ex2) {
                    LogFile.error("Failed to save log anywhere", ex2);
                    uiHandler.post(() -> Toast.makeText(this, "Failed to save log: " + ex2.getMessage(), Toast.LENGTH_LONG).show());
                }
            }
        }).start();
    }

    private void saveViaMediaStore(String errorReport) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, "sclauncher_error_" + System.currentTimeMillis() + ".txt");
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SCLauncher");
        
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Failed to create MediaStore entry");
        
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("Failed to open output stream");
            out.write(errorReport.getBytes("UTF-8"));
            out.flush();
        }
        
        uiHandler.post(() -> Toast.makeText(this, "Log saved to Downloads/SCLauncher/", Toast.LENGTH_LONG).show());
    }

    private void saveLegacy(String errorReport) throws Exception {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadsDir.exists()) downloadsDir.mkdirs();
        File logFile = new File(downloadsDir, "sclauncher_error_" + System.currentTimeMillis() + ".txt");
        try (FileOutputStream fos = new FileOutputStream(logFile)) {
            fos.write(errorReport.getBytes("UTF-8"));
        }
        uiHandler.post(() -> Toast.makeText(this, "Log saved to Downloads: " + logFile.getName(), Toast.LENGTH_LONG).show());
    }

    // --- JRE 25 Download ---

    // ------------------------------------------------------------------
    // JRE install (network): download the AngelAuraMC android-arm64 tar.xz,
    // extract, verify libjli.so + release file. Runs off the UI thread.
    // ------------------------------------------------------------------
    private void downloadJre25() {
        LogFile.log("Starting JRE 25 installation...");
        SCActionTrail.record("JRE 25 install started (download)");
        downloadJreButton.setEnabled(false);
        searchAgainButton.setEnabled(false);
        launchButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        downloadStatusText.setVisibility(View.VISIBLE);
        downloadStatusText.setText("Installing JRE 25...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File runtimesDir = new File(dirData, "runtimes");
                    runtimesDir.mkdirs();
                    final File destDir = new File(runtimesDir, JRE_RUNTIME_NAME);
                    if (destDir.exists()) {
                        deleteRecursive(destDir);
                    }
                    destDir.mkdirs();

                    File jreXz = new File(destDir, JRE_ARM64_FILE);
                    boolean usedLocalAsset = false;

                    // Try local asset first
                    try {
                        updateDownloadStatus("Extracting bundled JRE 25...");
                        InputStream assetIn = getAssets().open(LOCAL_JRE_ASSET);
                        FileOutputStream assetOut = new FileOutputStream(jreXz);
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = assetIn.read(buf)) != -1) {
                            assetOut.write(buf, 0, n);
                        }
                        assetOut.close();
                        assetIn.close();
                        LogFile.log("Used bundled JRE asset: " + jreXz.length() + " bytes");
                        usedLocalAsset = true;
                    } catch (IOException e) {
                        LogFile.log("Bundled JRE asset not found, will download: " + e.getMessage());
                    }

                    // Fallback to download if local asset not available
                    if (!usedLocalAsset) {
                        updateDownloadStatus("Downloading JRE 25...");
                        LogFile.log("Downloading from: " + JRE_BASE_URL + JRE_ARM64_FILE);
                        downloadFile(JRE_BASE_URL + JRE_ARM64_FILE, jreXz);
                        LogFile.log("Downloaded: " + jreXz.length() + " bytes");
                    }

                    // Extract tar.xz or zip
                    updateDownloadStatus("Extracting JRE 25...");
                    extractJreArchive(jreXz, destDir);
                    jreXz.delete();

                    // Verify libjli.so exists
                    File libjli = new File(destDir, "lib/libjli.so");
                    if (!libjli.exists()) {
                        // Try alternative paths
                        File[] altPaths = {
                            new File(destDir, "lib/aarch64/jli/libjli.so"),
                            new File(destDir, "lib/arm64/jli/libjli.so"),
                            new File(destDir, "lib/jli.so")
                        };
                        boolean found = false;
                        for (File p : altPaths) {
                            if (p.exists()) {
                                libjli = p;
                                found = true;
                                LogFile.log("Found libjli.so at: " + p.getAbsolutePath());
                                break;
                            }
                        }
                        if (!found) {
                            throw new IOException("libjli.so not found in extracted JRE");
                        }
                    }
                    LogFile.log("Verified libjli.so at: " + libjli.getAbsolutePath());

                    // Verify release file
                    File releaseFile = new File(destDir, "release");
                    if (!releaseFile.exists()) {
                        throw new IOException("JRE installation incomplete — release file missing");
                    }
                    LogFile.log("JRE 25 installed to: " + destDir.getAbsolutePath());
                    SCActionTrail.record("JRE 25 installed (download)");

                    updateDownloadStatus("JRE 25 installed successfully!");
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            progressBar.setVisibility(View.GONE);
                            downloadStatusText.setVisibility(View.GONE);
                            searchAgainButton.setEnabled(true);
                            refreshAll();
                        }
                    });

                } catch (final Exception e) {
                    LogFile.error("JRE installation failed", e);
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            progressBar.setVisibility(View.GONE);
                            downloadStatusText.setText("Download failed: " + e.getMessage());
                            downloadStatusText.setTextColor(0xFFFF6666);
                            downloadJreButton.setEnabled(true);
                            searchAgainButton.setEnabled(true);
                        }
                    });
                }
            }
        }).start();
    }

    private void updateDownloadStatus(final String message) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                downloadStatusText.setText(message);
            }
        });
    }

    private void downloadFile(String urlString, File output) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.connect();

            if (conn.getResponseCode() != 200) {
                throw new IOException("HTTP " + conn.getResponseCode() + " for " + urlString);
            }

            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(output);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.close();
            in.close();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void extractJreArchive(File archive, File destDir) throws IOException {
        // Detect archive type by magic bytes: PK\x03\x04 = zip, otherwise tar.xz.
        try (FileInputStream fis = new FileInputStream(archive)) {
            byte[] magic = new byte[4];
            int read = fis.read(magic);
            if (read == 4 && magic[0] == 0x50 && magic[1] == 0x4B && magic[2] == 0x03 && magic[3] == 0x04) {
                LogFile.log("Detected zip archive, extracting with ZipInputStream");
                extractZip(archive, destDir);
                return;
            }
        }
        LogFile.log("Detected tar.xz archive, extracting with tar");
        extractTarXz(archive, destDir);
    }

    private void extractZip(File zipFile, File destDir) throws IOException {
        try (FileInputStream fis = new FileInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(destDir, entry.getName());
                // Protect against zip-slip.
                if (!out.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator)) {
                    throw new IOException("Zip entry outside target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (FileOutputStream outStream = new FileOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) != -1) {
                            outStream.write(buf, 0, n);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void extractTarXz(File xzFile, File destDir) throws IOException {
        // Decompress XZ to a temp .tar file, then extract with tar command
        File tarFile = new File(xzFile.getParent(), xzFile.getName() + ".tmp");
        try {
            FileInputStream fis = new FileInputStream(xzFile);
            XZInputStream xzIn = new XZInputStream(fis);
            FileOutputStream tarOut = new FileOutputStream(tarFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = xzIn.read(buf)) != -1) {
                tarOut.write(buf, 0, n);
            }
            tarOut.close();
            xzIn.close();
            fis.close();

            // Use system tar to extract
            ProcessBuilder pb = new ProcessBuilder(
                    "tar", "xf", tarFile.getAbsolutePath(), "-C", destDir.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            while (reader.readLine() != null) { /* consume output */ }
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                throw new IOException("tar extraction failed with exit code " + exitCode);
            }
        } catch (InterruptedException e) {
            throw new IOException("Extraction interrupted", e);
        } finally {
            tarFile.delete();
        }
    }

    private void deleteRecursive(File file) {
        // Never traverse symlinks (e.g. saves/ -> shared storage): delete the
        // link itself only, or external user data would be wiped with it.
        try {
            if (!file.getCanonicalPath().equals(file.getAbsolutePath())) {
                file.delete();
                return;
            }
        } catch (Exception e) {
            LogFile.error("Symlink check failed", e);
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    // --- JRE Discovery ---

    // ------------------------------------------------------------------
    // JRE discovery: accept only a runtime whose `release` file reports the
    // required major version (nearest-version matching lives in Mojo, not
    // here — the game needs exactly Java 25, class-file v69).
    // ------------------------------------------------------------------
    private String findJre(int requiredVersion) {
        File runtimesDir = new File(dirData, "runtimes");
        if (!runtimesDir.exists()) {
            runtimesDir.mkdirs();
            return null;
        }

        // Check for exact "Internal-25" first
        File internal = new File(runtimesDir, JRE_RUNTIME_NAME);
        if (internal.exists() && new File(internal, "release").exists()
                && parseJavaVersion(new File(internal, "release")) == requiredVersion) {
            return JRE_RUNTIME_NAME;
        }

        // Scan all runtimes for the required version
        File[] runtimeDirs = runtimesDir.listFiles();
        if (runtimeDirs != null) {
            for (File dir : runtimeDirs) {
                File releaseFile = new File(dir, "release");
                if (releaseFile.exists()) {
                    int version = parseJavaVersion(releaseFile);
                    if (version == requiredVersion) {
                        return dir.getName();
                    }
                }
            }
        }

        return null;
    }

    private int parseJavaVersion(File releaseFile) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(releaseFile));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("JAVA_VERSION=\"")) {
                    String version = line.substring(14, line.length() - 1);
                    String[] parts = version.split("\\.");
                    reader.close();
                    if (parts[0].equals("1")) {
                        return Integer.parseInt(parts[1]);
                    } else {
                        return Integer.parseInt(parts[0]);
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse release file: " + releaseFile, e);
        }
        return 0;
    }

    // --- JVM Args ---

    // ------------------------------------------------------------------
    // JVM args: Termux-proven mobile flags (native-access, add-opens),
    // LWJGL pinned to system GLES + bundled SDL/OpenAL, JavaCPP pointed at
    // the pre-extracted ffmpeg natives. Mirrors the desktop launch script
    // minus X11-only bits (DRI_PRIME etc.).
    // ------------------------------------------------------------------
    private List<String> buildVmArgs(String runtimeName) {
        File runtimeHome = new File(dirData, "runtimes/" + runtimeName);
        File nativesDir = new File(dirCache, "natives/SCLauncher");
        File lwjglExtractDir = new File(dirCache, "lwjgl_native/SCLauncher");
        // LWJGL never refreshes extracted natives itself: a corrupt/stale .so
        // from any earlier build would fail every later run identically
        // ("Could not initialize class" with the first cause long gone).
        File extractStamp = new File(lwjglExtractDir, ".extract_v4");
        if (!extractStamp.exists()) {
            LogFile.log("Refreshing LWJGL native extract dir");
            deleteRecursive(lwjglExtractDir);
            lwjglExtractDir.mkdirs();
            try {
                extractStamp.createNewFile();
            } catch (IOException e) {
                LogFile.error("Failed to stamp extract dir", e);
            }
        } else {
            lwjglExtractDir.mkdirs();
        }

        List<String> args = new ArrayList<String>();

        args.add("-Djoml.fastmath");
        args.add("-Djoml.sinLookup");
        args.add("-Djoml.useMathFma");

        // JRE libs must be on java.library.path or System.loadLibrary("jsound")
        // (Spu audio) fails: default path is replaced by the override below.
        String jreLibPath = new File(runtimeHome, "lib").getAbsolutePath()
                + ":" + new File(runtimeHome, "lib/server").getAbsolutePath();
        if (nativesDir.exists()) {
            String nativesPath = nativesDir.getAbsolutePath();
            args.add("-Djava.library.path=" + nativesPath + ":" + nativeLibDir + ":" + jreLibPath);
            args.add("-Djna.boot.library.path=" + nativesPath);
            args.add("-Dorg.lwjgl.librarypath=" + nativesPath);
        } else {
            args.add("-Djava.library.path=" + nativeLibDir + ":" + jreLibPath);
            args.add("-Djna.boot.library.path=" + nativeLibDir);
            args.add("-Dorg.lwjgl.librarypath=" + nativeLibDir);
        }

        args.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + lwjglExtractDir.getAbsolutePath());
        args.add("-Dorg.lwjgl.opengl.libname=libGLESv2.so");
        args.add("-Dorg.lwjgl.opengles.libname=libGLESv2.so");
        args.add("-Dorg.lwjgl.sdl.libname=" + new File(nativeLibDir, "libSDL3.so").getAbsolutePath());
        // Pin OpenAL to our bundled OpenSL build (same as SDL above). Without
        // this LWJGL resolves "openal" via librarypath scan and can pick a
        // system/extracted copy with missing caps (OpenAL10 retry storm).
        args.add("-Dorg.lwjgl.openal.libname=" + new File(nativeLibDir, "libopenal.so").getAbsolutePath());
        args.add("-Dorg.lwjgl.vulkan.libname=libvulkan.so");

        args.add("-Djava.home=" + runtimeHome.getAbsolutePath());
        args.add("-Djava.io.tmpdir=" + dirCache);
        args.add("-Duser.home=" + gameDir.getAbsolutePath());
        args.add("-Duser.language=en");
        args.add("-Duser.country=US");
        args.add("-Dos.name=Linux");
        args.add("-Dos.version=Android-" + Build.VERSION.RELEASE);
        args.add("-Dpojav.path.minecraft=" + new File(gameDir, ".minecraft").getAbsolutePath());
        args.add("-Dpojav.path.private.account=" + new File(dirData, "accounts").getAbsolutePath());
        args.add("-Duser.timezone=" + java.util.TimeZone.getDefault().getID());

        args.add("-Dorg.lwjgl.system.allocator=system");
        args.add("-Dlog4j2.formatMsgNoLookups=true");
        // JavaCPP (bytedeco ffmpeg): plain path lookup, no jar scanning.
        File ffmpegNativesDir = new File(gameDir, "ffmpeg_natives");
        ffmpegNativesDir.mkdirs();
        args.add("-Dplatform.library.path=" + ffmpegNativesDir.getAbsolutePath());
        args.add("-Dfml.earlyprogresswindow=false");
        args.add("-Dloader.disable_forked_guis=true");
        args.add("-Djdk.lang.Process.launchMechanism=FORK");

        // Required on Java 17+ for LWJGL/native access (proven by Termux launch scripts).
        args.add("--enable-native-access=ALL-UNNAMED");
        args.add("--add-opens=java.base/java.util=ALL-UNNAMED");
        args.add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED");

        return args;
    }

    // ------------------------------------------------------------------
    // Disc images (user-supplied, not redistributable): explicit per-disc
    // picker buttons copy into isos/discN.bin; the unpacker is the final
    // judge of region/format (we only log the ISO9660 marker, never block).
    // ------------------------------------------------------------------
    private void pickDiscImage(int index) {
        mDiscTarget = index;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, "Select image for Disc " + (index + 1));
        mPickFileLauncher.launch(intent);
    }

    private void copyDiscImage(Uri uri, String targetName) {
        File isosDir = new File(gameDir, "isos");
        isosDir.mkdirs();
        File targetFile = new File(isosDir, targetName);

        new Thread(() -> {
            try {
                ContentResolver resolver = getContentResolver();
                try (InputStream in = resolver.openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(targetFile)) {
                    if (in == null) throw new IOException("Failed to open input stream");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
                LogFile.log("Copied disc image to: " + targetFile.getAbsolutePath() + " (" + targetFile.length() + " bytes)");
                SCActionTrail.record("Disc image copied: " + targetName
                        + " (" + (targetFile.length() / 1048576) + " MB)");
                logDiscImageInfo(targetFile);
                uiHandler.post(() -> {
                    Toast.makeText(this, "Copied " + targetName, Toast.LENGTH_SHORT).show();
                    refreshAll();
                });
            } catch (Exception e) {
                LogFile.error("Failed to copy disc image", e);
                uiHandler.post(() -> Toast.makeText(this, "Copy failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /**
     * Logs what we can tell about a transferred disc image. Sizes and names
     * vary by region and rip format, so nothing here blocks or warns the user
     * — the game's unpacker is the final judge. Checks the ISO9660 "CD001"
     * marker at sector 16 for both 2048-byte and 2352-byte (raw BIN) sectors.
     */
    private void logDiscImageInfo(File f) {
        long len = f.length();
        byte[] magic = new byte[5];
        for (long sectorSize : new long[]{2048, 2352}) {
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                long off = 16 * sectorSize + 1;
                if (off + 5 <= len) {
                    raf.seek(off);
                    if (raf.read(magic) == 5
                            && magic[0] == 'C' && magic[1] == 'D'
                            && magic[2] == '0' && magic[3] == '0' && magic[4] == '1') {
                        LogFile.log("Disc image has PSX/ISO marker (" + (len / 1048576)
                                + " MB): " + f.getName());
                        return;
                    }
                }
            } catch (Exception e) {
                LogFile.error("Disc check I/O failed", e);
                return;
            }
        }
        LogFile.log("Disc image copied (" + (len / 1048576) + " MB, no ISO marker — "
                + "may be another region/format): " + f.getName());
    }

    private String targetSizeMb(long len) {
        return (len / 1048576) + " MB";
    }

    // ------------------------------------------------------------------
    // Save import/export: saves live in shared storage
    // (Android/data/<pkg>/files/Saves, symlinked from gameDir/saves) but
    // users asked for one-tap backup/restore. Export zips Saves/ to
    // Downloads (MediaStore Q+, legacy pre-Q); import unzips a user-picked
    // archive back into Saves/ (zip-slip protected, same-name files
    // overwritten, nothing deleted).
    // ------------------------------------------------------------------
    private File savesDir() {
        File extRoot = getExternalFilesDir(null);
        File dir = extRoot != null ? new File(extRoot, "Saves") : new File(gameDir, "saves");
        dir.mkdirs();
        return dir;
    }

    private void exportSaves() {
        SCActionTrail.record("Saves export started");
        Toast.makeText(this, "Exporting saves...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File src = savesDir().getCanonicalFile();
                File[] kids = src.listFiles();
                if (kids == null || kids.length == 0) {
                    uiHandler.post(() -> Toast.makeText(this,
                            "No saves to export yet", Toast.LENGTH_LONG).show());
                    return;
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int[] count = {0};
                try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                    for (File kid : kids) {
                        zipRecursive(kid, src, zos, count);
                    }
                }
                String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                        java.util.Locale.US).format(new java.util.Date());
                String name = "saves-" + stamp + ".zip";
                byte[] bytes = baos.toByteArray();
                String where;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    where = saveBytesViaMediaStore(bytes, name, "application/zip",
                            Environment.DIRECTORY_DOWNLOADS + "/SCLauncher");
                } else {
                    where = saveBytesLegacy(bytes, name);
                }
                LogFile.log("Saves exported (" + count[0] + " files): " + where);
                SCActionTrail.record("Saves exported (" + count[0] + " files)");
                uiHandler.post(() -> Toast.makeText(this,
                        "Saves exported (" + count[0] + " files)", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                LogFile.error("Saves export failed", e);
                uiHandler.post(() -> Toast.makeText(this,
                        "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void zipRecursive(File f, File root, ZipOutputStream zos, int[] count) throws IOException {
        String rel = root.toURI().relativize(f.toURI()).getPath();
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File kid : kids) zipRecursive(kid, root, zos, count);
            }
            return;
        }
        zos.putNextEntry(new java.util.zip.ZipEntry(rel));
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
        }
        zos.closeEntry();
        count[0]++;
    }

    private String saveBytesViaMediaStore(byte[] bytes, String displayName,
                                          String mime, String relativePath) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore insert failed");
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("MediaStore open failed");
            out.write(bytes);
            out.flush();
        }
        return "Downloads/SCLauncher/" + displayName;
    }

    private String saveBytesLegacy(byte[] bytes, String displayName) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "SCLauncher");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, displayName);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(bytes);
        }
        return out.getAbsolutePath();
    }

    private void pickSavesArchive() {
        // Saves are single .dsav files; our export is a .zip of Saves/.
        // Accept both: a bare */* filter (many pickers ignore MIME hints)
        // plus explicit hints where honored.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/zip", "application/octet-stream"});
        intent.putExtra(Intent.EXTRA_TITLE, "Select saves backup (.zip) or save (.dsav)");
        mImportSavesLauncher.launch(intent);
    }

    private final ActivityResultLauncher<Intent> mImportSavesLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) importSavesFile(uri);
            }
        }
    );

    private void importSavesFile(Uri uri) {
        SCActionTrail.record("Saves import started");
        Toast.makeText(this, "Importing saves...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File dst = savesDir().getCanonicalFile();
                ContentResolver resolver = getContentResolver();
                // Sniff the content: PK.. = zip backup (our export format),
                // anything else = a single save file (.dsav).
                byte[] magic = new byte[4];
                int count;
                String singleName;
                try (InputStream probe = resolver.openInputStream(uri)) {
                    if (probe == null) throw new IOException("Failed to open input stream");
                    int read = 0, n;
                    while (read < 4 && (n = probe.read(magic, read, 4 - read)) != -1) read += n;
                    if (read < 4) throw new IOException("File is empty");
                }
                boolean isZip = magic[0] == 0x50 && magic[1] == 0x4B
                        && magic[2] == 0x03 && magic[3] == 0x04;
                if (isZip) {
                    count = importSavesZip(resolver, uri, dst);
                    singleName = null;
                } else {
                    singleName = importSingleSave(resolver, uri, dst);
                    count = 1;
                }
                LogFile.log("Saves imported (" + count + " file(s)"
                        + (singleName != null ? ": " + singleName : ", zip, same names overwritten")
                        + ")");
                SCActionTrail.record("Saves imported (" + count + " file(s))");
                uiHandler.post(() -> {
                    Toast.makeText(this, "Saves imported (" + count + " file(s))",
                            Toast.LENGTH_LONG).show();
                    refreshAll();
                });
            } catch (Exception e) {
                LogFile.error("Saves import failed", e);
                uiHandler.post(() -> Toast.makeText(this,
                        "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** Zip backup path (our export format). Returns file count. */
    private int importSavesZip(ContentResolver resolver, Uri uri, File dst) throws IOException {
        String base = dst.getCanonicalPath() + File.separator;
        int count = 0;
        try (InputStream in = resolver.openInputStream(uri);
             ZipInputStream zis = new ZipInputStream(in)) {
            if (in == null) throw new IOException("Failed to open input stream");
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(dst, entry.getName());
                if (!out.getCanonicalPath().startsWith(base)) {
                    throw new IOException("Zip entry outside saves dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (FileOutputStream outStream = new FileOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) != -1) outStream.write(buf, 0, n);
                    }
                    count++;
                }
                zis.closeEntry();
            }
        }
        return count;
    }

    /**
     * Single save-file path (.dsav etc.): copies the document into Saves/
     * under its own display name (same names overwritten). Returns the name.
     */
    private String importSingleSave(ContentResolver resolver, Uri uri, File dst) throws IOException {
        String name = displayNameOf(uri);
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")
                || name.equals(".") || name.equals("..")) {
            name = "save-" + System.currentTimeMillis() + ".dsav";
        }
        File out = new File(dst, name);
        String base = dst.getCanonicalPath() + File.separator;
        if (!out.getCanonicalPath().startsWith(base)) {
            throw new IOException("Unsafe save name: " + name);
        }
        try (InputStream in = resolver.openInputStream(uri);
             FileOutputStream outStream = new FileOutputStream(out)) {
            if (in == null) throw new IOException("Failed to open input stream");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) outStream.write(buf, 0, n);
        }
        if (out.length() == 0) {
            out.delete();
            throw new IOException("File is empty");
        }
        return name;
    }

    private String displayNameOf(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return null;
    }

    private void importJreFromStorage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/x-xz", "application/x-tar", "application/zip"});
        mImportJreLauncher.launch(intent);
    }

    private final ActivityResultLauncher<Intent> mImportJreLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) {
                    importJreFile(uri);
                }
            }
        }
    );

    // ------------------------------------------------------------------
    // JRE install (user file): same verification as the download path.
    // Format is magic-sniffed (zip PK.. vs tar.xz), zip-slip protected.
    // ------------------------------------------------------------------
    private void importJreFile(Uri uri) {
        LogFile.log("Starting JRE 25 import from storage...");
        SCActionTrail.record("JRE 25 install started (import)");
        downloadJreButton.setEnabled(false);
        searchAgainButton.setEnabled(false);
        launchButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        downloadStatusText.setVisibility(View.VISIBLE);
        downloadStatusText.setText("Importing JRE 25...");

        new Thread(() -> {
            try {
                final File runtimesDir = new File(dirData, "runtimes");
                runtimesDir.mkdirs();
                final File destDir = new File(runtimesDir, JRE_RUNTIME_NAME);
                if (destDir.exists()) {
                    deleteRecursive(destDir);
                }
                destDir.mkdirs();

                // Copy the imported file (tar.xz or zip)
                File jreXz = new File(destDir, "jre-import.bin");
                ContentResolver resolver = getContentResolver();
                try (InputStream in = resolver.openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(jreXz)) {
                    if (in == null) throw new IOException("Failed to open input stream");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
                LogFile.log("Imported JRE file: " + jreXz.length() + " bytes");

                // Extract tar.xz or zip (detected by file magic)
                updateDownloadStatus("Extracting JRE 25...");
                extractJreArchive(jreXz, destDir);
                jreXz.delete();

                // Verify libjli.so exists
                File libjli = new File(destDir, "lib/libjli.so");
                if (!libjli.exists()) {
                    File[] altPaths = {
                        new File(destDir, "lib/aarch64/jli/libjli.so"),
                        new File(destDir, "lib/arm64/jli/libjli.so"),
                        new File(destDir, "lib/jli.so")
                    };
                    boolean found = false;
                    for (File p : altPaths) {
                        if (p.exists()) {
                            libjli = p;
                            found = true;
                            LogFile.log("Found libjli.so at: " + p.getAbsolutePath());
                            break;
                        }
                    }
                    if (!found) {
                        throw new IOException("libjli.so not found in extracted JRE");
                    }
                }
                LogFile.log("Verified libjli.so at: " + libjli.getAbsolutePath());

                File releaseFile = new File(destDir, "release");
                if (!releaseFile.exists()) {
                    throw new IOException("JRE installation incomplete — release file missing");
                }
                LogFile.log("JRE 25 installed to: " + destDir.getAbsolutePath());
                SCActionTrail.record("JRE 25 installed (import)");

                updateDownloadStatus("JRE 25 imported successfully!");

                uiHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    downloadStatusText.setVisibility(View.GONE);
                    searchAgainButton.setEnabled(true);
                    refreshAll();
                });

            } catch (final Exception e) {
                LogFile.error("JRE import failed", e);
                uiHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    downloadStatusText.setText("Import failed: " + e.getMessage());
                    downloadStatusText.setTextColor(0xFFFF6666);
                    downloadJreButton.setEnabled(true);
                    searchAgainButton.setEnabled(true);
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // Close: leave the session log in Downloads/Reports (no root/adb needed
    // to read it). Fire-and-forget; the export collapses if one is running.
    // ------------------------------------------------------------------
    @Override
    protected void onDestroy() {
        SCLogExporter.exportAsync(this, "launcher-close");
        super.onDestroy();
    }
}
