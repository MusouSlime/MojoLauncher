package com.severedchains.launcher;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.PopupMenu;
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

import com.severedchains.launcher.utils.GpuDetectUtils;

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
    /**
     * Game bundle manifest published alongside each launcher release.
     * "latest/download" always resolves to the newest non-prerelease release,
     * so a game update is picked up as soon as its release is published.
     * For local testing, put a URL in the file "game-update-url.txt" in the
     * game directory (next to the jar); it overrides this constant.
     */
    private static final String GAME_UPDATE_MANIFEST_URL =
            "https://github.com/MusouSlime/Severed-Chains-Android-Lavitz-Edition/releases/latest/download/game-update.json";

    private static final String FFMPEG_MAVEN_BASE = "https://repo1.maven.org/maven2/org/bytedeco/ffmpeg/8.0.1-1.5.13/";
    private static final String FFMPEG_ANDROID_FILE = "ffmpeg-8.0.1-1.5.13-android-arm64.jar";

    public static final String MAIN_JAR_NAME = "lod-game-snapshot.jar";

    // ------------------------------------------------------------------
    // ABI selection: one APK per architecture (arm64 or x86_64 test build).
    // The native asset set, JRE and ffmpeg jar are picked at runtime from the
    // process ABI so a single code path serves both.
    // ------------------------------------------------------------------
    private static final String FFMPEG_ANDROID_X86_64_FILE = "ffmpeg-8.0.1-1.5.13-android-x86_64.jar";

    /** True when this process is the x86_64 test build. */
    public static boolean isX86_64() {
        String arch = System.getProperty("os.arch");
        if (arch == null) {
            return false;
        }
        arch = arch.toLowerCase(java.util.Locale.US);
        return arch.contains("x86_64") || arch.contains("amd64");
    }

    private static String targetAbiName() {
        return isX86_64() ? "x86_64" : "arm64-v8a";
    }

    private static String jreArchiveName() {
        return isX86_64() ? JRE_X86_64_FILE : JRE_ARM64_FILE;
    }

    private static String localJreAsset() {
        return "jre/" + jreArchiveName();
    }

    private static String ffmpegAndroidFile() {
        return isX86_64() ? FFMPEG_ANDROID_X86_64_FILE : FFMPEG_ANDROID_FILE;
    }

    /** bytedeco stages Android libs under lib/<abi>/ inside the jar. */
    private static String ffmpegLibPrefix() {
        return isX86_64() ? "lib/x86_64/" : "lib/arm64-v8a/";
    }

    private ProgressBar progressBar;
    private TextView statusText;
    private TextView jreStatusText;
    private TextView downloadStatusText;
    private Button launchButton;
    private Button menuButton;
    private TextView gpuModeText;

    // Renderer choice is AUTOMATIC (no manual buttons since 2026-09-17):
    // all GPUs -> "snapdragon" (native system GLES). Legacy Zink is
    // DISABLED (crashes Mali drivers: missing Zink Vulkan features);
    // ANGLE is gone (EGL_BAD_ATTRIBUTE on T820). Mali HARDWARE still gets
    // the DEBUG probe gsh via isMali(). Retired values ("mali", "malivk",
    // "vulkan_zink", "opengles2", "mali"/"malib"/"malic" shader variants)
    // migrate to the auto-selected mode on load.
    public static final String GPU_MODE_SNAPDRAGON = "snapdragon";
    public static final String GPU_MODE_MALI = "mali";
    private static final String PREFS_NAME = "sclauncher_prefs";

    // App internal data folder: used by mods and file operations.
    // Gets resolved to getFilesDir().getParent() + "/games/SCLauncher".
    public static final String APP_DATA_DIR = "games/SCLauncher";
    private static final String PREF_GPU_MODE = "gpu_mode";
    /** Handheld Mode: touch is always mouse; the game screen drops the
     *  virtual-control/exit buttons and single BACK returns to the launcher. */
    public static final String PREF_HANDHELD = "handheld_mode";
    /** Show the launcher's Legend of Dragoon-themed on-screen pad
     *  (TouchGamepadView). Default ON; the in-game mobile-controls mod is
     *  always removed since the launcher pad is the controller now. */
    public static final String PREF_TOUCH_PAD = "launcher_touch_pad";
    /**
     * Mute the in-game GL error popups.
     *
     * <p>The game's KHR_debug callback raises a five-second on-screen toast for
     * every high-severity driver message. On the Adreno driver those are
     * routine ("Namespace collision detected, using slow path", "All
     * allocations busy", a degenerate triangle) and the toast is drawn over
     * the play area, so during a battle it covers the fight.
     *
     * <p>Default ON: the messages are still written to the log in full (with
     * the GL context and a stack trace), so nothing is lost for diagnosis --
     * only the screen-covering popup is suppressed.
     */
    public static final String PREF_MUTE_GL_POPUPS = "mute_gl_popups";
    /** JVM property the launcher passes to the game for the setting above. */
    public static final String PROP_MUTE_GL_POPUPS = "sc.mute.gl.popups";

    /** Diagnostic probe: visualise the raw battle vertex position (inPos) as
     *  colour, untextured. See applyGpuShaderVariant + gpu_variants/probe/. */
    private static final boolean PROBE_BATTLE_POS = false;


    private String mGpuMode = GPU_MODE_SNAPDRAGON;

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

    // Unpacked-data import: the ISO rip (Unpacker) is CPU- and I/O-heavy and
    // crawls on low-end hardware. The user picks a pre-ripped `files/` tree
    // (produced once on a faster device/PC that ran Severed Chains to
    // completion) and it is copied into gameDir/files/. The game then sees a
    // matching `files/version` and skips the rip entirely. isos/ (still needed
    // for the game's quick disc check) and saves/ are untouched.
    private final ActivityResultLauncher<Uri> mPickUnpackedLauncher = registerForActivityResult(
        new ActivityResultContracts.OpenDocumentTree(),
        uri -> {
            if (uri != null) {
                importUnpackedFiles(uri);
            }
        }
    );

    // Mod install: mods live in gameDir/mods/ (the game's mod-loader scans that
    // folder for *.jar). Three entry points, all landing in mods/:
    //  - Import Mod Jar…    : a single .jar, copied to mods/<name>.jar
    //  - Import Mod Zip…    : a zipped mod, unzipped into mods/ in place
    //  - Import Mod Folder… : an unzipped mod's contents, copied into mods/
    //    recursively (the jar plus any folder it ships).
    private final ActivityResultLauncher<String> mImportModJarLauncher = registerForActivityResult(
        new ActivityResultContracts.GetContent(),
        uri -> {
            if (uri != null) {
                importModJar(uri);
            }
        }
    );

    // Mod zips are usually application/zip, but some file managers hand them
    // over as octet-stream, so accept all three spellings and filter by
    // extension in code.
    private final ActivityResultLauncher<String[]> mImportModZipLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    importModZip(uri);
                }
            }
    );

    private final ActivityResultLauncher<Uri> mImportModsFolderLauncher = registerForActivityResult(
        new ActivityResultContracts.OpenDocumentTree(),
        uri -> {
            if (uri != null) {
                importModsFolder(uri);
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
        menuButton = findViewById(R.id.menuButton);
        if (menuButton != null) {
            menuButton.setOnClickListener(v -> showOptionsMenu());
        }
        gpuModeText = findViewById(R.id.gpuModeText);

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

        // If the previous game session died hard (VM abort/SIGKILL), its clean
        // export never ran — pull its logs into Downloads/Reports now.
        SCLogExporter.exportCrashRecoveryIfNeeded(this, gameDir);
        SCGpuInfo.logOnce(this);
        // Renderer evidence in every log: Vulkan hw feature, system
        // libvulkan presence (informational; Zink is disabled).
        try {
            LogFile.log("Vulkan probe: " + SCVulkanProbe.summary(this).replace("\n", " | "));
        } catch (Exception e) {
            LogFile.error("Vulkan probe failed", e);
        }
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

        // Renderer choice is automatic: all GPUs run native GLES (Zink
        // disabled). The saved pref only fast-forwards the first
        // auto-detect; stale values (mali, malivk, vulkan_zink, opengles2,
        // retired shader variants) migrate with a log line.
        mGpuMode = loadGpuMode();
        updateGpuModeUI();

        // Extract bundled game files on first run
        extractBundledGameFiles();

        launchButton.setOnClickListener(v -> launchGame());

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
        File markerV5 = new File(gameDir, ".bundled_extracted_v5");
        if (markerV5.exists()) {
            LogFile.log("Bundled game files already extracted (v5, SC 3.0)");
            extractSclauncherNatives();
            extractGpuVariants();
            applyGpuShaderVariant();
            refreshAll();
            return;
        }
        // v1/v2/v3/v4 -> v5 upgrade: refresh the whole game bundle to the
        // SC 3.0 jar/assets (the old snapshot is missing 3.0 event classes),
        // preserving user data (isos/, saves/, mods/, configs, files/).
        File markerV1 = new File(gameDir, ".bundled_extracted");
        File markerV2 = new File(gameDir, ".bundled_extracted_v2");
        File markerV3 = new File(gameDir, ".bundled_extracted_v3");
        File markerV4 = new File(gameDir, ".bundled_extracted_v4");
        if (markerV1.exists() || markerV2.exists() || markerV3.exists() || markerV4.exists()) {
            LogFile.log("Upgrading game bundle to v5 (SC 3.0), preserving user data");
            preserveAndWipeGameDir();
            markerV1.delete();
            markerV2.delete();
            markerV3.delete();
            markerV4.delete();
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
                    markerV5.createNewFile();
                    LogFile.log("Bundled game files (SC 3.0) extracted to " + gameDir.getAbsolutePath());
                    extractSclauncherNatives();
                    extractGpuVariants();
                    applyGpuShaderVariant();
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

    // ------------------------------------------------------------------
    // Import unpacked data: copy a pre-ripped `files/` tree (the CPU-heavy
    // output of the game's ISO Unpacker, produced once on a faster device/PC
    // that ran Severed Chains to completion) into gameDir/files/. The game
    // then sees a matching `files/version` and skips the rip entirely. The
    // picked folder may be the `files/` folder itself or a parent containing
    // it (e.g. a copied gameDir). isos/ (still needed for the game's quick
    // disc check) and saves/ are untouched. Framework DocumentsContract only,
    // no extra dependency.
    // ------------------------------------------------------------------
    private static class DocEntry {
        String docId;
        String name;
        String mime;
    }

    private void importUnpackedFiles(Uri treeUri) {
        try {
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            LogFile.log("Folder permission not persistable (one-shot copy): " + e.getMessage());
        }
        statusText.setText("Importing unpacked data...");
        progressBar.setVisibility(View.VISIBLE);
        launchButton.setEnabled(false);
        LogFile.log("Importing unpacked data folder: " + treeUri);
        SCActionTrail.record("Unpacked data import started");

        new Thread(() -> {
            try {
                final String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);

                // Accept either the files/ folder itself or a parent (e.g. a
                // copied gameDir) that contains a `files` child.
                DocEntry root = new DocEntry();
                root.docId = treeDocId;
                root.name = "files";
                root.mime = DocumentsContract.Document.MIME_TYPE_DIR;
                for (DocEntry e : listTreeChildren(treeUri, treeDocId)) {
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(e.mime)
                            && "files".equals(e.name)) {
                        root = e;
                        break;
                    }
                }

                final List<DocEntry> rootChildren = listTreeChildren(treeUri, root.docId);
                if (!looksLikeUnpack(rootChildren)) {
                    throw new IOException("Folder doesn't look like unpacked Severed Chains data "
                            + "(need a 'version' file and SECT/ etc.; saw: " + nameList(rootChildren) + ")");
                }

                final File dest = new File(gameDir, "files");
                dest.mkdirs();
                final int[] count = new int[]{0};
                for (DocEntry e : rootChildren) {
                    copyTreeEntry(treeUri, e, new File(dest, e.name), count);
                    final int n = count[0];
                    uiHandler.post(() -> statusText.setText("Importing unpacked data... " + n + " files"));
                }

                final String version = readImportedVersion(dest);
                LogFile.log("Imported " + count[0] + " unpacked files into "
                        + dest.getAbsolutePath() + " (unpack version " + version + ")");
                SCActionTrail.record("Unpacked data import finished (" + count[0] + " files)");
                final int total = count[0];
                uiHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setText("Unpacked data imported (v" + version + ").");
                    Toast.makeText(this, "Imported " + total + " files (unpack v" + version + ")",
                            Toast.LENGTH_LONG).show();
                    refreshAll();
                });
            } catch (Exception e) {
                LogFile.error("Failed to import unpacked data", e);
                uiHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setText("Import failed: " + e.getMessage());
                    Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** True if the entries look like the game's unpacked `files/` tree
     *  (a `version` marker file plus at least one known data directory). */
    private static boolean looksLikeUnpack(List<DocEntry> children) {
        boolean hasVersion = false, hasDir = false;
        for (DocEntry e : children) {
            boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(e.mime);
            if (!isDir && "version".equals(e.name)) hasVersion = true;
            if (isDir && ("SECT".equals(e.name) || "characters".equals(e.name)
                    || "monsters".equals(e.name) || "OVL".equals(e.name)
                    || "SUBMAP".equals(e.name))) {
                hasDir = true;
            }
        }
        return hasVersion && hasDir;
    }

    private static String nameList(List<DocEntry> entries) {
        StringBuilder names = new StringBuilder();
        int shown = 0;
        for (DocEntry e : entries) {
            if (shown > 0) names.append(", ");
            names.append(e.name);
            if (++shown >= 12) {
                names.append(", …");
                break;
            }
        }
        return names.toString();
    }

    /** Reads the game's unpack version marker from the imported files/ tree. */
    private static String readImportedVersion(File filesDir) {
        File version = new File(filesDir, "version");
        try (BufferedReader r = new BufferedReader(new FileReader(version))) {
            String line = r.readLine();
            return line != null && !line.trim().isEmpty() ? line.trim() : "?";
        } catch (Exception e) {
            return "?";
        }
    }

    private List<DocEntry> listTreeChildren(Uri treeUri, String parentDocId) {
        List<DocEntry> out = new ArrayList<>();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        try (Cursor c = getContentResolver().query(childrenUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE},
                null, null, null)) {
            if (c == null) return out;
            int idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (c.moveToNext()) {
                DocEntry e = new DocEntry();
                e.docId = c.getString(idCol);
                e.name = c.getString(nameCol);
                e.mime = c.getString(mimeCol);
                // Never import a hostile name (../ escapes, absolute paths).
                if (e.name == null || e.name.contains("..") || e.name.contains("/") || e.name.contains("\\")) {
                    LogFile.log("Skipping unsafe entry: " + e.name);
                    continue;
                }
                out.add(e);
            }
        }
        return out;
    }

    private void copyTreeEntry(Uri treeUri, DocEntry entry, File dst, int[] count) throws IOException {
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(entry.mime)) {
            dst.mkdirs();
            // Children resolve via the tree + this entry's document id.
            for (DocEntry child : listTreeChildren(treeUri, entry.docId)) {
                copyTreeEntry(treeUri, child, new File(dst, child.name), count);
            }
        } else {
            Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.docId);
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream in = getContentResolver().openInputStream(fileUri);
                 OutputStream fileOut = new FileOutputStream(dst)) {
                if (in == null) throw new IOException("Cannot open " + entry.name);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    fileOut.write(buf, 0, n);
                }
            }
            count[0]++;
        }
    }

    /**
     * Saves live in shared storage where non-root users (and the player) can
     * reach them: <sdcard>/Android/data/<pkg>/files/Saves (USB/file manager,
     * no root). gameDir/saves is a symlink there so the game sees no change.
     * Existing save files are moved, never deleted.
     */    private void setupSavesLink() {
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

    /**
     * Refreshes the bundled game dir (bundle upgrade) while preserving the
     * user's data: isos/ (disc images) and saves/ are copied out/back, and
     * files/ (the game's slow ISO rip) is kept IN PLACE so a launcher-side
     * update never forces a multi-GB re-rip. Only a game-side unpack VERSION
     * bump makes the game rebuild files/ itself.
     */
    private void preserveAndWipeGameDir() {
        // Bundle upgrades must not destroy user state. Keep the unpacked data
        // (files/) in place because copying it through cache would need GBs,
        // and round-trip the small user-owned items through cache.
        final String[] preserve = {
                "isos", "saves", "mods",
                "config.conf", "config.dcnf", "campaign_config.dcnf"
        };
        File tmpRoot = new File(getCacheDir(), "game_upgrade_tmp");
        deleteRecursive(tmpRoot);
        tmpRoot.mkdirs();
        for (final String name : preserve) {
            final File src = new File(gameDir, name);
            if (!src.exists()) continue;
            try {
                copyRecursive(src, new File(tmpRoot, name));
            } catch (Exception e) {
                LogFile.error("Failed to preserve " + name, e);
            }
        }
        File[] children = gameDir.listFiles();
        if (children != null) {
            for (File child : children) {
                if ("files".equals(child.getName())) continue;
                deleteRecursive(child);
            }
        }
        for (final String name : preserve) {
            final File kept = new File(tmpRoot, name);
            if (!kept.exists()) continue;
            try {
                copyRecursive(kept, new File(gameDir, name));
            } catch (Exception e) {
                LogFile.error("Failed to restore " + name, e);
            }
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
        // The marker carries the LAUNCHER's versionCode, not a hand-bumped
        // revision: the natives are re-extracted whenever the launcher build
        // changes, so an in-place APK update can never leave stale natives
        // behind. That used to be the reason the README demanded an uninstall
        // before every update -- but an uninstall wipes the whole app data
        // dir, including the unpacked game assets, so the next launch had to
        // re-rip all 46 files from the discs (144 s on an S20, during which the
        // game sits frozen and ignores input). With this marker, installing
        // over the top is safe and the rip happens only once.
        final String stamp = ".extracted_" + launcherVersionCode();
        File marker = new File(nativesDir, stamp);
        if (marker.exists()) {
            ensureMobileControlsJar();
            return;
        }
        // Any other marker means natives from an older launcher build.
        final File[] oldMarkers = nativesDir.listFiles((d, name) -> name.startsWith(".extracted"));
        if (oldMarkers != null && oldMarkers.length > 0) {
            LogFile.log("Launcher build changed, refreshing natives in libs_natives/");
            deleteRecursive(nativesDir);
        }
        try {
            nativesDir.mkdirs();
            extractAssetsDir("sclauncher_natives", nativesDir);
            marker.createNewFile();
            LogFile.log("SCLauncher natives extracted (" + targetAbiName() + ", build "
                    + launcherVersionCode() + ") to " + nativesDir.getAbsolutePath());
        } catch (Exception e) {
            LogFile.error("Failed to extract SCLauncher natives", e);
        }
        ensureMobileControlsJar();
    }

    /** The launcher's own versionCode; used to stamp the natives marker. */
    private int launcherVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * In-game touch controls (legend.mobile.MobileControlsMod,
     * {@code mobile-controls.jar}): REPLACES the launcher's touch overlay
     * (see SCGameActivity). The mod is discovered by the game's ModManager
     * via Reflections classpath scanning ({@code @Mod} annotation), so it
     * only needs to sit in {@code gameDir/libs/} — which checkGameFiles()
     * already puts on the classpath (no filter hits that name).
     * Fresh installs get it via the bulk assets/game extract; existing
     * installs (bundle marker v4, no re-extract) get it here — runs on
     * every launch. The deployed copy is accepted only when its CONTENT
     * matches the bundled jar (same-length-but-stale jars are replaced).
     */
    private void ensureMobileControlsJar() {
        if (gameDir == null) return;
        // The launcher's Legend of Dragoon-themed pad (TouchGamepadView) is the
        // on-screen controller now, so the in-game MobileControlsMod is disabled
        // by removing its jar: the two control layers must never draw or consume
        // the same touches. Runs on every launch; a fresh bulk extract that
        // re-places the jar is removed again here.
        File dst = new File(new File(gameDir, "libs"), "mobile-controls.jar");
        if (dst.exists() && dst.delete()) {
            LogFile.log("Removed mobile-controls.jar (Dragoon pad is the controller)");
            SCActionTrail.record("In-game controls mod disabled (Dragoon pad)");
        }
    }

    private static byte[] md5(File f) throws IOException {
        java.security.MessageDigest d;
        try {
            d = java.security.MessageDigest.getInstance("MD5");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) d.update(buf, 0, n);
        }
        return d.digest();
    }

    private static byte[] md5(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("MD5").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
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

    // ------------------------------------------------------------------
    // Renderer choice: automatic by GPU vendor (no manual buttons).
    // All GPUs -> "snapdragon" (native system GLES; Zink disabled — it
    // crashes Mali drivers). Mali HARDWARE still gets the DEBUG probe gsh
    // via isMali() in applyGpuShaderVariant. Any stale saved value
    // migrates to the auto-selected mode with a log line.
    // ------------------------------------------------------------------
    private String loadGpuMode() {
        String saved = null;
        try {
            android.content.SharedPreferences p =
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            saved = p.getString(PREF_GPU_MODE, null);
        } catch (Exception ignored) {}
        String selected = GpuDetectUtils.selectBestGpuMode(this, saved);
        if (saved == null) {
            LogFile.log("GPU mode (auto): " + selected);
        } else if (!selected.equals(saved)) {
            LogFile.log("GPU mode (saved " + saved + " -> auto " + selected + ")");
        } else {
            LogFile.log("GPU mode (saved): " + saved);
        }
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putString(PREF_GPU_MODE, selected).apply();
        } catch (Exception ignored) {}
        return selected;
    }

    /** Fresh-install default: pure auto-detect (flavor-independent). */
    private String detectGpuMode() {
        return GpuDetectUtils.selectBestGpuMode(this, null);
    }

    private void updateGpuModeUI() {
        if (gpuModeText != null) {
            String text;
            if (GPU_MODE_MALI.equals(mGpuMode)) {
                text = "GPU: Mali (native GLES + PLAIN probe, auto)";
            } else if (GPU_MODE_SNAPDRAGON.equals(mGpuMode)) {
                // Mali hardware reports snapdragon mode (wrappers removed)
                // but still gets the Mali probe shader — surface the
                // HARDWARE truth here, never the mode string alone.
                text = GpuDetectUtils.isMali(this)
                        ? "GPU: Mali (native GLES + PLAIN probe, auto)"
                        : "GPU: Snapdragon (native GLES, auto)";
            } else {
                text = "GPU: " + mGpuMode;
            }
            gpuModeText.setText(text);
        }
    }

    /** Extracts the stock-shader dir once (repairs debug-overwritten files). */
    private void extractGpuVariants() {
        try {
            File variantsDir = new File(gameDir, "gpu_variants");
            File marker = new File(variantsDir, ".gpu_variant_v13");
            if (marker.exists()
                    && new File(variantsDir, "stock/tmd.gsh").exists()
                    && new File(variantsDir, "stock/tmd.fsh").exists()
                    && new File(variantsDir, "stock/tmd.vsh").exists()
                    && new File(variantsDir, "stock/battle_tmd.vsh").exists()
                    && new File(variantsDir, "stock/battle_tmd.fsh").exists()
                    && new File(variantsDir, "fixed/tmd.gsh").exists()
                    && new File(variantsDir, "plain/tmd.gsh").exists()
                    && new File(variantsDir, "plain/tmd.vsh").exists()
                    && new File(variantsDir, "plain/tmd.fsh").exists()
                    && new File(variantsDir, "plain/battle_tmd.vsh").exists()
                    && new File(variantsDir, "plain/battle_tmd.fsh").exists()) {
                return;
            }
// v11: stock/ tmd.gsh reverted to PRISTINE (the Mali-only tpageOverride
// keep-alive was removed from stock — it shipped on every GPU including
// Adreno). v10 was the PLAIN-varyings probe in plain/. Wipe + re-extract so
// devices holding the old cached stock/ shader heal on next launch.
            // The v8 FIXED shader is backed up at Backups/tmd.fixed.gsh.v1.
            // Stock bytes ship IN the APK (pristine), so a stale dir is
            // simply wiped — never snapshotted from gameDir (which may
            // already hold a debug file from a previous run).
            deleteRecursive(variantsDir);
            variantsDir.mkdirs();
            extractAssetsDir("gpu_variants", variantsDir);
            marker.createNewFile();
            LogFile.log("GPU shader variants extracted to "
                    + variantsDir.getAbsolutePath());
        } catch (Exception e) {
            LogFile.error("Failed to extract GPU shader variants", e);
        }
    }

    /**
     * Applies the per-launch TMD shaders over gameDir/gfx/shaders/.
     * Mali GPUs run the PLAIN probe (scalar varyings + recovered-z cull,
     * real varyings; 5 files: tmd vsh/gsh/fsh + battle vsh/fsh); everything
     * else runs STOCK (same 5 files restored, heals debug-overwritten
     * devices). Keyed off hardware ({@code isMali}), not the mode string.
     * Called on every launch.
     */
    private void applyGpuShaderVariant() {
        if (gameDir == null) return;
        try {
            boolean mali = GpuDetectUtils.isMali(this);
            File variants = new File(gameDir, "gpu_variants");
            File plainDir = new File(variants, "plain");
            // All five plain files must be present or we fall back to the
            // previous behaviour (fixed gsh + stock fsh) / stock.
            boolean plainReady = new File(plainDir, "tmd.vsh").exists()
                    && new File(plainDir, "tmd.gsh").exists()
                    && new File(plainDir, "tmd.fsh").exists()
                    && new File(plainDir, "battle_tmd.vsh").exists()
                    && new File(plainDir, "battle_tmd.fsh").exists();
            // Mali only. The scalar-varyings (plain) TMD shaders FAIL TO LINK on
            // Adreno ("Use is before Def" from the shader compiler) — applying
            // them on Snapdragon/Adreno leaves all uniforms missing and the game
            // crashes with "Failed to create texture, glError: 502". So they must
            // stay hardware-gated.
            boolean plain = mali && plainReady;
            boolean uvProbe = new File(gameDir, "gpu_variants/debug_uvprobe/tmd.gsh").exists();
            String mode;
            if (plain) {
                copyFile(new File(plainDir, "tmd.vsh"),
                        new File(gameDir, "gfx/shaders/tmd.vsh"));
                copyFile(new File(plainDir, "tmd.gsh"),
                        new File(gameDir, "gfx/shaders/tmd.gsh"));
                copyFile(new File(plainDir, "tmd.fsh"),
                        new File(gameDir, "gfx/shaders/tmd.fsh"));
                copyFile(new File(plainDir, "battle_tmd.vsh"),
                        new File(gameDir, "gfx/shaders/battle_tmd.vsh"));
                copyFile(new File(plainDir, "battle_tmd.fsh"),
                        new File(gameDir, "gfx/shaders/battle_tmd.fsh"));
                mode = "PLAIN varyings probe";
            } else if (mali && uvProbe) {
                // Mali-only: the UV/FLAG debug probe must never run on
                // Snapdragon (it tinted non-Mali models blue). Gated on
                // hardware so non-Mali always falls through to stock.
                File gshSrc = new File(gameDir, "gpu_variants/debug_uvprobe/tmd.gsh");
                if (gshSrc.exists()) {
                    copyFile(gshSrc, new File(gameDir, "gfx/shaders/tmd.gsh"));
                }
                mode = "UV/FLAG probe";
            } else if (mali) {
                File gshSrc = new File(gameDir, "gpu_variants/fixed/tmd.gsh");
                if (gshSrc.exists()) {
                    copyFile(gshSrc, new File(gameDir, "gfx/shaders/tmd.gsh"));
                }
                File fshSrc = new File(gameDir, "gpu_variants/stock/tmd.fsh");
                if (fshSrc.exists()) {
                    copyFile(fshSrc, new File(gameDir, "gfx/shaders/tmd.fsh"));
                }
                mode = "WHITEOUT color probe";
            } else {
                // Non-Mali: restore all five stock files (heals probes).
                String[][] pairs = {
                        {"stock/tmd.vsh", "gfx/shaders/tmd.vsh"},
                        {"stock/tmd.gsh", "gfx/shaders/tmd.gsh"},
                        {"stock/tmd.fsh", "gfx/shaders/tmd.fsh"},
                        {"stock/battle_tmd.vsh", "gfx/shaders/battle_tmd.vsh"},
                        {"stock/battle_tmd.fsh", "gfx/shaders/battle_tmd.fsh"},
                };
                for (String[] p : pairs) {
                    File src = new File(variants, p[0]);
                    if (src.exists()) copyFile(src, new File(gameDir, p[1]));
                }
                mode = "stock";
                // PROBE: visualise the raw battle vertex position (inPos) as
                // colour, untextured (see gpu_variants/probe/battle_tmd.*).
                if (PROBE_BATTLE_POS) {
                    File pvsh = new File(variants, "probe/battle_tmd.vsh");
                    File pfsh = new File(variants, "probe/battle_tmd.fsh");
                    if (pvsh.exists() && pfsh.exists()) {
                        copyFile(pvsh, new File(gameDir, "gfx/shaders/battle_tmd.vsh"));
                        copyFile(pfsh, new File(gameDir, "gfx/shaders/battle_tmd.fsh"));
                        mode = "stock + battle position probe";
                    }
                }
            }
            File gshDst = new File(gameDir, "gfx/shaders/tmd.gsh");
            File fshDst = new File(gameDir, "gfx/shaders/tmd.fsh");
            // Hardware truth in the prefix: the mode string is "snapdragon"
            // on every GPU (wrappers removed), which misled testers on Mali
            // hardware into thinking the Mali probe never ran.
            String hwPrefix;
            try {
                hwPrefix = GpuDetectUtils.isMali(this) ? "MALI" : "SNAPDRAGON";
            } catch (Throwable t) {
                hwPrefix = mGpuMode.toUpperCase();
            }
            LogFile.log("GPU shaders applied: " + hwPrefix
                    + " (" + mode + ")"
                    + " tmd.gsh (" + gshDst.length() + "b)"
                    + " tmd.fsh (" + fshDst.length() + "b)");
        } catch (Exception e) {
            LogFile.error("Failed to apply GPU shader variant", e);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    // ------------------------------------------------------------------
    // Options dropdown: every launcher action except Launch lives here,
    // rebuilt fresh on each open so disc ✓ states and the JRE-download
    // entry are always current.
    // ------------------------------------------------------------------
    private void showOptionsMenu() {
        if (menuButton == null) return;
        PopupMenu menu = new PopupMenu(this, menuButton);
        int order = 0;
        menu.getMenu().add(0, 1, order++, "Search Again");
        menu.getMenu().add(0, 2, order++, "Import Unpacked Data (files/)…");
        menu.getMenu().add(0, 3, order++, "Send Log");
        if (foundJreName == null) {
            menu.getMenu().add(0, 4, order++, "Download JRE 25");
        }
        menu.getMenu().add(0, 5, order++, "Import JRE…");
        menu.getMenu().add(0, 6, order++, "Disc Status…");
        menu.getMenu().add(0, 7, order++, "Update Game…");
        for (int i = 0; i < DISC_FILE_PATTERNS.length; i++) {
            menu.getMenu().add(0, 10 + i, order++, discLabel(i));
        }
        menu.getMenu().add(0, 20, order++, "Export Saves");
        menu.getMenu().add(0, 21, order++, "Import Saves");
        menu.getMenu().add(0, 30, order++, "Import Mod Jar…");
        menu.getMenu().add(0, 35, order++, "Import Mod Zip…");
        menu.getMenu().add(0, 34, order++, "Import Mod Folder…");
        menu.getMenu().add(0, 32, order++, "Mod Manager…");
        menu.getMenu().add(0, 31, order++, "App Folder (mods)…");
        menu.getMenu().add(0, 40, order++, loadHandheldMode() ? "Handheld Mode ✓" : "Handheld Mode");
        menu.getMenu().add(0, 41, order++, loadTouchPad() ? "Dragoon Pad ✓" : "Dragoon Pad");
        menu.getMenu().add(0, 42, order++, "Touch Pad Opacity…");
        menu.getMenu().add(0, 43, order++, "Reset Controls");
        menu.getMenu().add(0, 44, order++, loadMuteGlPopups()
                ? "Mute In-Game Error Popups ✓"
                : "Mute In-Game Error Popups");
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                refreshAll();
                return true;
            } else if (id == 2) {
                mPickUnpackedLauncher.launch(null);
                return true;
            } else if (id == 3) {
                sendLastLog();
                return true;
            } else if (id == 4) {
                downloadJre25();
                return true;
            } else if (id == 5) {
                importJreFromStorage();
                return true;
            } else if (id == 6) {
                showDiscStatusDialog();
                return true;
            } else if (id == 7) {
                updateGame();
                return true;
            } else if (id >= 10 && id < 14) {
                pickDiscImage(id - 10);
                return true;
            } else if (id == 20) {
                exportSaves();
                return true;
            } else if (id == 21) {
                pickSavesArchive();
                return true;
            } else if (id == 30) {
                mImportModJarLauncher.launch("*/*");
                return true;
            } else if (id == 35) {
                mImportModZipLauncher.launch(new String[]{
                        "application/zip", "application/x-zip-compressed", "application/octet-stream"});
                return true;
            } else if (id == 34) {
                mImportModsFolderLauncher.launch(null);
                return true;
            } else if (id == 31) {
                showAppFolderDialog();
                return true;
            } else if (id == 32) {
                showModManagerDialog();
                return true;
            } else if (id == 40) {
                setHandheldMode(!loadHandheldMode());
                return true;
            } else if (id == 41) {
                setTouchPad(!loadTouchPad());
                return true;
            } else if (id == 42) {
                chooseTouchPadOpacity();
                return true;
            } else if (id == 43) {
                resetControls();
                return true;
            } else if (id == 44) {
                setMuteGlPopups(!loadMuteGlPopups());
                return true;
            }
            return false;
        });
        menu.show();
        SCActionTrail.record("Options menu opened");
    }

    /** Persisted Handheld Mode setting (read by SCGameActivity at launch). */
    private boolean loadHandheldMode() {
        try {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(PREF_HANDHELD, false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void setHandheldMode(final boolean enabled) {
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(PREF_HANDHELD, enabled).apply();
        } catch (Exception ignored) {}
        String label = enabled
                ? "Handheld Mode ON (touch = mouse, single BACK exits, no exit button)"
                : "Handheld Mode OFF";
        try {
            android.widget.Toast.makeText(this, label, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
        LogFile.log(label);
        SCActionTrail.record(label);
        updateLaunchReady();
    }

    /** Persisted "use the Dragoon pad" setting (read by SCGameActivity).
     *  Defaults ON: the launcher pad is the primary on-screen controller. */
    public boolean loadTouchPad() {
        try {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(PREF_TOUCH_PAD, true);
        } catch (Exception ignored) {
            return true;
        }
    }

    private void setTouchPad(final boolean enabled) {
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(PREF_TOUCH_PAD, enabled).apply();
        } catch (Exception ignored) {}
        // The in-game mobile-controls mod is always removed; this only shows or
        // hides the launcher pad (touch-as-mouse remains for menus).
        ensureMobileControlsJar();
        String label = enabled
                ? "Dragoon Pad ON (Legend of Dragoon themed controls)"
                : "Dragoon Pad OFF (touch-as-mouse for menus)";
        try {
            android.widget.Toast.makeText(this, label, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
        LogFile.log(label);
        SCActionTrail.record(label);
    }

    /**
     * Persisted "mute in-game error popups" setting.
     *
     * <p>Passed to the game as the system property
     * {@link #PROP_MUTE_GL_POPUPS}; the patched
     * {@code GlesApi.notifyGl} helper reads it and skips the on-screen toast.
     * The GL messages themselves are still logged in full.
     */
    private boolean loadMuteGlPopups() {
        try {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(PREF_MUTE_GL_POPUPS, true);
        } catch (Exception ignored) {
            return true;
        }
    }

    private void setMuteGlPopups(final boolean muted) {
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(PREF_MUTE_GL_POPUPS, muted).apply();
        } catch (Exception ignored) {}
        final String label = muted
                ? "In-game error popups MUTED (the messages are still in the log)"
                : "In-game error popups SHOWN";
        try {
            android.widget.Toast.makeText(this, label, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
        LogFile.log(label);
        SCActionTrail.record(label);
    }

    /** Small picker for the launcher touch-pad opacity (persisted, applied live). */
    private void chooseTouchPadOpacity() {
        final float[] values = {1.0f, 0.75f, 0.5f, 0.3f};
        final String[] names = {"100%", "75%", "50%", "30%"};
        final float current = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getFloat("launcher_touch_opacity", 1.0f);
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - current) < 0.01f) checked = i;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Touch Pad Opacity")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit().putFloat("launcher_touch_opacity", values[which]).apply();
                    LogFile.log("Touch pad opacity: " + names[which]);
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** "Disc N ✓" when the image is on disk, region-flagged when it is not US. */
    /**
     * Clears the on-screen pad's saved layout/scale/opacity so the next game
     * launch shows the default Dragoon Pad arrangement. This replaces the old
     * in-game double-tap-to-reset gesture.
     */
    private void resetControls() {
        try {
            getSharedPreferences(
                    com.severedchains.launcher.ui.TouchGamepadView.PREFS, MODE_PRIVATE)
                    .edit().clear().apply();
        } catch (Exception ignored) {}
        try {
            android.widget.Toast.makeText(this, "Controls reset to default",
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
        LogFile.log("Controls reset to default (launcher menu)");
        SCActionTrail.record("Controls reset (launcher)");
    }

    private String discLabel(int index) {
        if (gameDir == null) return "Disc " + (index + 1);
        File f = new File(new File(gameDir, "isos"), DISC_FILE_PATTERNS[index]);
        if (!f.isFile()) {
            return "Disc " + (index + 1);
        }
        SCDiscImage.Info info = SCDiscImage.detect(f);
        if (info.kind == SCDiscImage.Kind.US) {
            return "Disc " + (index + 1) + " ✓"
                    + (info.disc == index + 1 ? "" : " (disc " + info.disc + "?)");
        }
        if (info.kind == SCDiscImage.Kind.OTHER_REGION) {
            return "Disc " + (index + 1) + " ⚠ " + info.region + " (US required)";
        }
        return "Disc " + (index + 1) + " ⚠ not a US disc";
    }

    /**
     * Per-disc status report (region detection via {@link SCDiscImage}) plus
     * free space, so the user learns a wrong-region rip is the problem before
     * the game's unpacker fails with an opaque error. Ported from the setup
     * screen of dracinn/Severed-Chains-Android.
     */
    private void showDiscStatusDialog() {
        if (gameDir == null) return;
        File isosDir = new File(gameDir, "isos");
        StringBuilder sb = new StringBuilder();
        int us = 0;
        for (int i = 0; i < DISC_FILE_PATTERNS.length; i++) {
            File f = new File(isosDir, DISC_FILE_PATTERNS[i]);
            sb.append("Disc ").append(i + 1).append(": ");
            if (!f.isFile()) {
                sb.append("not provided\n");
                continue;
            }
            SCDiscImage.Info info = SCDiscImage.detect(f);
            sb.append(f.getName()).append(" — ");
            if (info.kind == SCDiscImage.Kind.US && info.disc == i + 1) {
                sb.append("US release ✓");
                us++;
            } else if (info.kind == SCDiscImage.Kind.US) {
                sb.append("US release, disc ").append(info.disc)
                        .append(" (in slot ").append(i + 1).append(")");
            } else if (info.kind == SCDiscImage.Kind.OTHER_REGION) {
                sb.append("wrong region (").append(info.region).append(") — US required");
            } else if (info.volumeId != null) {
                sb.append("unrecognized disc (id ").append(info.volumeId).append(")");
            } else {
                sb.append("no PSX disc marker (unreadable/mid-copy/not a disc image)");
            }
            sb.append('\n');
        }
        try {
            android.os.StatFs stat = new android.os.StatFs(isosDir.getAbsolutePath());
            sb.append("\nFree space: ")
                    .append(String.format(java.util.Locale.US, "%.1f GB", stat.getAvailableBytes() / 1e9));
        } catch (Throwable ignored) {
        }
        sb.append("\n\nAll four discs must be the US release (SCUS-94491/94584/94585/94586).");
        SCActionTrail.record("Disc status viewed (" + us + "/4 US)");
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(us == 4 ? "Discs OK (4/4 US)" : "Disc images (" + us + "/4 US)")
                    .setMessage(sb.toString())
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception ignored) {
        }
    }

    /**
     * Copies a user-picked file into the app folder: .jar files go to
     * gameDir/libs/ (game ModManager class-path scans it, so this is how
     * mods get installed); anything else goes to the app folder root.
     * Rejects hostile names (../ escapes, separators).
     */
    /** Copies a single picked mod .jar into gameDir/mods/ (top level, so the
     *  game's mod-loader, which scans mods/*.jar, finds it). */
    private void importModJar(Uri uri) {
        if (gameDir == null) return;
        statusText.setText("Importing mod jar…");
        LogFile.log("Importing mod jar: " + uri);
        SCActionTrail.record("Mod jar import started");
        new Thread(() -> {
            try {
                String name = "mod.jar";
                try (Cursor c = getContentResolver().query(uri,
                        new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        final String q = c.getString(0);
                        if (q != null && !q.isEmpty()) name = q;
                    }
                } catch (Exception ignored) {}
                if (name.contains("/") || name.contains("\\") || name.contains("..")) {
                    throw new IOException("Unsafe file name: " + name);
                }
                if (!name.toLowerCase(java.util.Locale.US).endsWith(".jar")) name += ".jar";

                final File modsDir = new File(gameDir, "mods");
                modsDir.mkdirs();
                final File dst = new File(modsDir, name);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(dst)) {
                    if (in == null) throw new IOException("Cannot open picked file");
                    final byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                final String msg = "Imported " + name + " into mods/ — enable at New Campaign → Mods.";
                LogFile.log(msg + " (" + dst.length() + "b)");
                SCActionTrail.record("Mod jar imported: " + name);
                uiHandler.post(() -> {
                    statusText.setText(msg);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    refreshAll();
                });
            } catch (Exception e) {
                LogFile.error("Mod jar import failed", e);
                uiHandler.post(() -> {
                    statusText.setText("Import failed: " + e.getMessage());
                    Toast.makeText(this, "Import failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** Zip-bomb guard: reject a single entry or a whole archive beyond these. */
    private static final long MOD_ZIP_MAX_ENTRY_BYTES = 128L * 1024 * 1024;
    private static final long MOD_ZIP_MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    /**
     * Unzips a mod archive into {@code gameDir/mods/}.
     *
     * <p>Mod authors ship zips in a few shapes — {@code mymod.jar} at the root,
     * or {@code mymod/mymod.jar} inside a single wrapper folder — and the game's
     * mod-loader only scans {@code mods/*.jar} at the TOP level. So the archive
     * is scanned first for a single common top-level folder, which is stripped
     * when present; that makes the jar land where the loader looks without the
     * user having to unzip by hand.
     *
     * <p>Defensive by design: every entry path is validated (no absolute paths,
     * no {@code ..} escapes, no backslashes) and re-checked against the canonical
     * destination, entry sizes are capped, and the total extracted size is
     * capped, so a hostile or corrupt archive cannot escape mods/ or fill the
     * device. The original zip is never deleted.
     */
    private void importModZip(final Uri uri) {
        if (gameDir == null) return;
        statusText.setText("Unzipping mod…");
        LogFile.log("Unzipping mod: " + uri);
        SCActionTrail.record("Mod zip import started");

        new Thread(() -> {
            try {
                final File modsDir = new File(gameDir, "mods");
                if (!modsDir.isDirectory() && !modsDir.mkdirs()) {
                    throw new IOException("Cannot create mods/");
                }
                final String modsRoot = modsDir.getCanonicalPath();

                // Pass 1: entry names only, to find a single common root folder.
                final List<String> names = new ArrayList<>();
                try (InputStream in = openZip(uri);
                     ZipInputStream zis = new ZipInputStream(in)) {
                    ZipEntry e;
                    while ((e = zis.getNextEntry()) != null) {
                        if (!e.isDirectory()) names.add(e.getName());
                    }
                }
                if (names.isEmpty()) throw new IOException("Archive contains no files");

                final String strip = commonRootFolder(names);
                if (strip != null) {
                    LogFile.log("Mod zip: stripping single top-level folder '" + strip + "'");
                }

                // Pass 2: extract.
                int files = 0;
                int jars = 0;
                int skipped = 0;
                long total = 0;
                try (InputStream in = openZip(uri);
                     ZipInputStream zis = new ZipInputStream(in)) {
                    ZipEntry e;
                    while ((e = zis.getNextEntry()) != null) {
                        if (e.isDirectory()) continue;
                        String name = e.getName().replace('\\', '/');
                        if (strip != null && name.startsWith(strip)) {
                            name = name.substring(strip.length());
                        }
                        name = name.replaceAll("^/+", "");
                        if (name.isEmpty()) continue;

                        if (name.contains("..") || name.contains("//") || name.indexOf(':') >= 0) {
                            LogFile.log("Mod zip: skipped unsafe entry '" + e.getName() + "'");
                            skipped++;
                            continue;
                        }
                        // Nested jars are kept rather than skipped: mods ship helper
                        // data and libraries in subfolders next to the top-level jar.
                        final File dst = new File(modsDir, name);
                        if (!dst.getCanonicalPath().startsWith(modsRoot + File.separator)) {
                            LogFile.log("Mod zip: blocked path escape '" + e.getName() + "'");
                            skipped++;
                            continue;
                        }
                        // The declared entry size is only a hint (it is -1 for streamed
                        // entries), so the caps are enforced against the bytes
                        // actually written while copying.
                        if (e.getSize() > MOD_ZIP_MAX_ENTRY_BYTES) {
                            throw new IOException("Entry too large (" + name + ")");
                        }

                        final File parent = dst.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                            throw new IOException("Cannot create " + parent.getName());
                        }
                        long written = 0;
                        try (OutputStream out = new FileOutputStream(dst)) {
                            final byte[] buf = new byte[65536];
                            int n;
                            while ((n = zis.read(buf)) != -1) {
                                written += n;
                                if (written > MOD_ZIP_MAX_ENTRY_BYTES) {
                                    throw new IOException("Entry too large (" + name + ")");
                                }
                                total += n;
                                if (total > MOD_ZIP_MAX_TOTAL_BYTES) {
                                    throw new IOException("Archive expands past the size limit");
                                }
                                out.write(buf, 0, n);
                            }
                        }
                        files++;
                        if (name.toLowerCase(java.util.Locale.US).endsWith(".jar")
                                && name.indexOf('/') < 0) {
                            jars++;
                        }
                        LogFile.log("Mod zip: " + name + " -> " + dst.getAbsolutePath()
                                + " (" + dst.length() + "b)");
                    }
                }

                final String msg = "Unzipped " + files + " file(s) into mods/"
                        + (jars > 0 ? " (" + jars + " mod jar at top level)" : " — no top-level .jar found")
                        + (skipped > 0 ? " — " + skipped + " entr(ies) SKIPPED, the mod is incomplete" : "")
                        + " — enable at New Campaign → Mods.";
                LogFile.log(msg);
                SCActionTrail.record("Mod zip unzipped: " + files + " files"
                        + (skipped > 0 ? ", " + skipped + " skipped" : ""));
                logModScriptLayout(modsDir);
                uiHandler.post(() -> {
                    statusText.setText(msg);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    refreshAll();
                });
            } catch (Exception e) {
                LogFile.error("Mod zip import failed", e);
                uiHandler.post(() -> {
                    statusText.setText("Unzip failed: " + e.getMessage());
                    Toast.makeText(this, "Unzip failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private InputStream openZip(final Uri uri) throws IOException {
        final InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("Cannot open picked file");
        return in;
    }

    /**
     * Returns {@code "name/"} when every entry sits under one common top-level
     * folder, else null (the archive root is used as-is).
     */
    /**
     * Log the script layout of every installed mod, and warn about entries the
     * game cannot load.
     *
     * <p>The game's mod script loader loads
     * {@code mods/<mod>/scripts/<group>/<id>} as a <b>directory</b>
     * ({@code Loader.loadDirectory}). A missing id surfaces only as a
     * {@code NoSuchFileException} deep inside the game, one line per attempt,
     * with no indication of which mod was at fault — which is exactly how a
     * broken mod (a {@code scripts/battle/4997} that never made it into the
     * zip, or an id shipped as a plain file) shows up in a device report.
     *
     * <p>Logging the layout at import time makes the next report
     * self-diagnosing, and the id check catches the "shipped as a file" case
     * up front instead of thousands of lines into a session.
     */
    private static void logModScriptLayout(final File modsDir) {
        final File[] mods = modsDir.listFiles();
        if (mods == null) return;

        for (final File mod : mods) {
            if (!mod.isDirectory()) continue;

            final File scripts = new File(mod, "scripts");
            final File[] groups = scripts.listFiles();
            if (groups == null) {
                LogFile.log("Mod layout: " + mod.getName() + " has no scripts/ directory");
                continue;
            }

            for (final File group : groups) {
                if (!group.isDirectory()) continue;
                final File[] ids = group.listFiles();
                final int idCount = ids == null ? 0 : ids.length;
                LogFile.log("Mod layout: " + mod.getName() + " scripts/" + group.getName()
                        + " — " + idCount + " id(s)");

                if (ids == null) continue;
                for (final File id : ids) {
                    if (!id.isFile()) continue;
                    // The game loads a script id as a directory; a plain file
                    // here makes every load of that id fail.
                    LogFile.log("Mod layout WARNING: " + mod.getName() + " scripts/" + group.getName() + "/"
                            + id.getName() + " is a file, but the game loads script ids as directories — "
                            + "this id will fail to load");
                }
            }
        }
    }

    private static String commonRootFolder(final List<String> names) {
        String root = null;
        for (final String n : names) {
            final String clean = n.replace('\\', '/');
            final int slash = clean.indexOf('/');
            if (slash <= 0) return null; // an entry at the archive root
            final String top = clean.substring(0, slash + 1);
            if (root == null) {
                root = top;
            } else if (!root.equals(top)) {
                return null;
            }
        }
        return root;
    }

    private void importModsFolder(Uri treeUri) {
        if (gameDir == null) return;
        statusText.setText("Installing mods from folder…");
        LogFile.log("Installing mods from folder: " + treeUri);
        SCActionTrail.record("Mods folder install started");
        new Thread(() -> {
            final int[] counts = {0}; // {files}
            try {
                new File(gameDir, "mods").mkdirs();
                final String rootId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
                final Uri rootChildren = android.provider.DocumentsContract
                        .buildChildDocumentsUriUsingTree(treeUri, rootId);
                copyModTree(treeUri, rootChildren, "", counts);
                final String msg = "Installed " + counts[0] + " file(s) into mods/"
                        + " — enable at New Campaign → Mods.";
                LogFile.log(msg);
                SCActionTrail.record("Mods folder installed: " + counts[0] + " files");
                uiHandler.post(() -> {
                    statusText.setText(msg);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    refreshAll();
                });
            } catch (Exception e) {
                LogFile.error("Mods folder install failed", e);
                uiHandler.post(() -> {
                    statusText.setText("Install failed: " + e.getMessage());
                    Toast.makeText(this, "Install failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** Recursively copies a SAF tree: .jar → gameDir/libs/, other files →
     *  gameDir, both preserving the folder's relative subpaths. */
    private void copyModTree(final Uri treeUri, final Uri childrenUri, final String rel,
                             final int[] counts) throws Exception {
        final android.content.ContentResolver cr = getContentResolver();
        try (Cursor c = cr.query(childrenUri, new String[]{
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE},
                null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                final String docId = c.getString(0);
                final String name = c.getString(1);
                final String mime = c.getString(2);
                if (docId == null || name == null) continue;
                if (name.contains("/") || name.contains("\\") || name.contains("..")) continue;
                final String childRel = rel.isEmpty() ? name : rel + "/" + name;

                if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    final Uri grand = android.provider.DocumentsContract
                            .buildChildDocumentsUriUsingTree(treeUri, docId);
                    copyModTree(treeUri, grand, childRel, counts);
                    continue;
                }

                // Mods live in gameDir/mods/ — the game's mod-loader
                // (ModManager.Access.findMods) scans that folder for *.jar and
                // loads each via its own URLClassLoader. A mod is typically a
                // jar plus a folder, so the whole tree is preserved verbatim.
                final File dstDir = new File(gameDir, "mods");
                final File dst = new File(dstDir, childRel);
                if (!dst.getCanonicalPath().startsWith(dstDir.getCanonicalPath())) continue; // zip-slip
                dst.getParentFile().mkdirs();
                final Uri docUri = android.provider.DocumentsContract
                        .buildDocumentUriUsingTree(treeUri, docId);
                try (InputStream in = cr.openInputStream(docUri);
                     FileOutputStream out = new FileOutputStream(dst)) {
                    if (in == null) continue;
                    final byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                counts[0]++;
                LogFile.log("Installed " + childRel + " -> " + dst.getAbsolutePath()
                        + " (" + dst.length() + "b)");
            }
        }
    }

    /**
     * Shows the user where the app folder is and what lives there, with a
     * shortcut into the mod-file importer. Non-root users can't browse
     * internal storage with a file manager, so this dialog + importer is
     * their access path for mod support.
     */
    private void showAppFolderDialog() {
        if (gameDir == null) return;
        String msg = "App folder:\n" + gameDir.getAbsolutePath()
                + "\n\nMods go in:\nmods/  (a .jar, plus any folder it needs)"
                + "\n\nDisc images go in:\nisos/"
                + "\n\nUse “Import Mod Jar…” for a single .jar, “Import Mod "
                + "Zip…” to unzip a mod archive, or “Import Mod Folder…” for an "
                + "already-unzipped mod — all three land in mods/. Enable mods at "
                + "New Campaign → Mods.";
        new AlertDialog.Builder(this)
                .setTitle("App Folder (mod support)")
                .setMessage(msg)
                .setPositiveButton("Import Mod Zip…",
                        (d, w) -> mImportModZipLauncher.launch(new String[]{
                                "application/zip", "application/x-zip-compressed", "application/octet-stream"}))
                .setNeutralButton("Import Mod Folder…",
                        (d, w) -> mImportModsFolderLauncher.launch(null))
                .setNegativeButton("Close", null)
                .show();
        SCActionTrail.record("App folder dialog opened");
    }

    /**
     * Lists everything in {@code gameDir/mods/}: top-level mod jars plus any
     * data folders they ship with, and lets the user delete an entry. Deleting
     * a jar unloads the mod on the next launch; the game has no runtime unload
     * API. This is a filesystem view because the mod-loader's enabled/known
     * lists live in the binary config.dcnf.
     */
    private void showModManagerDialog() {
        if (gameDir == null) return;
        final File modsDir = new File(gameDir, "mods");
        final File[] entries = modsDir.listFiles();
        if (entries == null || entries.length == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Mod Manager")
                    .setMessage("No mods installed.\n\nMods live in:\n"
                            + modsDir.getAbsolutePath()
                            + "\n\nUse Import Mod Jar/Zip/Folder to add one.")
                    .setPositiveButton("Close", null)
                    .setNeutralButton("Import Mod Jar…",
                            (d, w) -> mImportModJarLauncher.launch("*/*"))
                    .show();
            SCActionTrail.record("Mod manager opened (empty)");
            return;
        }

        Arrays.sort(entries, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        final String[] labels = new String[entries.length];
        for (int i = 0; i < entries.length; i++) {
            final File f = entries[i];
            final String kind;
            final String size;
            if (f.isDirectory()) {
                kind = "[data]";
                size = "data folder";
            } else {
                kind = f.getName().toLowerCase(java.util.Locale.US).endsWith(".jar")
                        ? "[mod] " : "[file]";
                size = android.text.format.Formatter.formatShortFileSize(this, f.length());
            }
            labels[i] = kind + "  " + f.getName() + "  (" + size + ")";
        }

        new AlertDialog.Builder(this)
                .setTitle("Mod Manager (" + entries.length + ")")
                .setItems(labels, (d, which) -> confirmRemoveMod(entries[which]))
                .setPositiveButton("Close", null)
                .setNeutralButton("Import Mod Jar…",
                        (d, w) -> mImportModJarLauncher.launch("*/*"))
                .show();
        SCActionTrail.record("Mod manager opened (" + entries.length + " entries)");
    }

    private void confirmRemoveMod(final File entry) {
        final boolean dir = entry.isDirectory();
        final String what = dir ? "data folder" : "mod";
        new AlertDialog.Builder(this)
                .setTitle("Remove " + what + "?")
                .setMessage(entry.getName() + "\n\n"
                        + (dir
                        ? "This deletes the folder and everything in it."
                        : "The mod will no longer load on the next launch."))
                .setPositiveButton("Remove", (d, w) -> removeModEntry(entry))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void removeModEntry(final File entry) {
        new Thread(() -> {
            final boolean ok = deleteRecursively(entry);
            final String msg = ok
                    ? "Removed " + entry.getName()
                    : "Could not remove " + entry.getName();
            uiHandler.post(() -> {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                statusText.setText(msg);
                LogFile.log(msg);
                SCActionTrail.record(msg);
                refreshAll();
            });
        }, "SCModRemove").start();
    }

    private static boolean deleteRecursively(final File file) {
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children != null) {
                for (final File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return file.delete();
    }

    private void refreshAll() {
        downloadStatusText.setVisibility(View.GONE);
        checkGameFiles();
        checkJreStatus();
        ensureFfmpegAndroid();
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
        File target = new File(libsDir, ffmpegAndroidFile());
        // bytedeco 1.5.13 ships Android libs under lib/<abi>/ inside the jar.
        if (target.exists() && target.length() > 1024 * 1024) {
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(target)) {
                if (zf.getEntry(ffmpegLibPrefix() + "libjniavutil.so") != null) {
                    LogFile.log(ffmpegLibPrefix() + " ffmpeg present, intro video ready");
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
        LogFile.log("Downloading " + ffmpegLibPrefix() + " ffmpeg for intro video...");
        uiHandler.post(() -> {
            launchButton.setEnabled(false);
            if (statusText.getText().toString().startsWith("Ready to launch")) {
                statusText.setText("Downloading video support, one moment...");
            }
        });
        new Thread(() -> {
            try {
                // Atomic: game must never see a partial jar on the classpath.
                File part = new File(libsDir, ffmpegAndroidFile() + ".part");
                part.delete();
                downloadFile(FFMPEG_MAVEN_BASE + ffmpegAndroidFile(), part);
                LogFile.log("ffmpeg download bytes: " + part.length());
                // Validate BEFORE rename: truncated downloads are the norm on
                // mobile data and produce jars that fail later with confusing
                // loader errors instead of a clear message here.
                try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(part)) {
                    if (zf.getEntry(ffmpegLibPrefix() + "libjniavutil.so") == null) {
                        throw new IOException("downloaded jar lacks libjniavutil.so");
                    }
                } catch (java.util.zip.ZipException e) {
                    throw new IOException("downloaded jar is corrupt/truncated", e);
                }
                if (!part.renameTo(new File(libsDir, ffmpegAndroidFile()))) {
                    throw new IOException("rename of ffmpeg download failed");
                }
                LogFile.log("Downloaded " + ffmpegAndroidFile());
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
                if (name.startsWith(ffmpegLibPrefix()) && name.endsWith(".so")) {
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
    /**
     * Restores the pristine game jar from the APK asset whenever the current
     * patch-revision marker (see {@link GameJarPatcher#MARKER}) is absent.
     * The bytecode patches are applied to the deployed jar in place and there
     * is no pristine copy on-device, so seeding each revision from the asset
     * prevents the GlesMesh guard from nesting when a new revision ships and
     * heals any install left partially patched. The patcher writes the marker
     * after a successful patch, so this costs at most one asset copy per patch
     * revision (never on a normal launch).
     */
    private void restorePristineGameJarIfNeeded(File mainJar) {
        // Content check, not existence: a marker written by an older patch
        // revision must trigger the re-seed (that is how a stale jar patched
        // before a patch change gets fixed without a manual file-name bump).
        if (GameJarPatcher.isPatchRevisionCurrent(gameDir)) return;
        try {
            if (mainJar.exists()) {
                try (InputStream in = getAssets().open("game/" + MAIN_JAR_NAME);
                     FileOutputStream out = new FileOutputStream(mainJar)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
                new File(gameDir, ".glesmesh_patch_v1").delete();
                new File(gameDir, ".glesmesh_patch_v2").delete();
                new File(gameDir, ".gamejar_healed_v2").delete();
                // Every stale patch-marker revision, current one included: we only
                // get here when the current marker is absent, and the pristine jar
                // has just been restored, so all of them are stale by definition.
                // Enumerating them by hand used to rot (the list lagged the MARKER
                // bump), so sweep the prefix instead.
                final File[] staleMarkers = gameDir.listFiles((d, name) ->
                        name.startsWith(".gamejar_patch") || name.startsWith(".glesmesh_patch"));
                if (staleMarkers != null) {
                    for (final File stale : staleMarkers) {
                        stale.delete();
                    }
                }
                new File(gameDir, MAIN_JAR_NAME + ".glespatch.tmp").delete();
                LogFile.log("Restored pristine game jar for " + GameJarPatcher.MARKER);
            }
        } catch (Exception e) {
            LogFile.error("Failed to restore pristine game jar", e);
        }
    }

    private void checkGameFiles() {
        File mainJar = new File(gameDir, MAIN_JAR_NAME);
        File libsDir = new File(gameDir, "libs");

        if (!mainJar.exists() || !libsDir.exists()) {
            statusText.setText("Game files not found.\nPlace Severed Chains in:\n" + gameDir.getAbsolutePath());
            launchButton.setEnabled(false);
            return;
        }

        // Seed the pristine jar once per patch revision so the in-place
        // bytecode patches can't nest across revisions, then apply them.
        restorePristineGameJarIfNeeded(mainJar);

        // Runtime bytecode patches:
        //  - GlesMesh.draw: skip <3-vertex triangle draws (Adreno spam). All builds.
        //  - Unpacker: bound the virtual-thread ISO rip to a fixed pool so
        //    Android never exhausts its file descriptors (EMFILE storm).
        //    ALL builds (borrowed from Severed-Chains-Android; was lite-only).
        //  - Config.lowMemoryUnpacker: force the disk-backed unpacker from the
        //    start instead of only after a first OOM. ALL builds.
        //  - IsoReader: exact seek/readFully instead of the lseek-backed
        //    skipBytes/partial-read pair (Android read storm). ALL builds.
        // Idempotent, re-applied after game updates.
        try {
            GameJarPatcher.Result patch = GameJarPatcher.patch(mainJar, gameDir, true, true);
            LogFile.log("GameJarPatcher: " + patch.message);
            if (patch.patched) {
                SCActionTrail.record("Game jar patched (" + patch.message + ")");
            }
        } catch (Throwable t) {
            LogFile.error("GameJarPatcher threw", t);
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
            updateLaunchReady();
        } else {
            jreStatusText.setText("JRE 25: Not installed");
            jreStatusText.setTextColor(0xFFFF8888);
            jreStatusText.setVisibility(View.VISIBLE);
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
        menuButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        // Swap the selected shader variant in before the JVM starts — the
        // game transpiles gfx/shaders/ at load, so this takes effect now.
        applyGpuShaderVariant();
        statusText.setText("Starting game display with " + foundJreName
                + " [" + mGpuMode + "]...");

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
            // Heap is set as a real JVM arg in buildVmArgs(); "-Xmx2G" here
            // would be a program arg the game ignores.
            appArgs.add("-ea");

            LogFile.log("Launching SCGameActivity with runtime: " + foundJreName
                    + " [GPU:" + mGpuMode + "]");
            SCActionTrail.record("Launch game (" + foundJreName + ", "
                    + cpStrings.size() + " classpath entries, GPU:" + mGpuMode + ")");
            Intent intent = new Intent(this, SCGameActivity.class);
            intent.putExtra(SCGameActivity.EXTRA_RUNTIME, foundJreName);
            intent.putStringArrayListExtra(SCGameActivity.EXTRA_VMARGS, new ArrayList<>(vmArgs));
            intent.putStringArrayListExtra(SCGameActivity.EXTRA_CP, cpStrings);
            intent.putExtra(SCGameActivity.EXTRA_MAIN, mainClass);
            intent.putStringArrayListExtra(SCGameActivity.EXTRA_APPARGS, appArgs);
            intent.putExtra(SCGameActivity.EXTRA_DIRDATA, dirData);
            intent.putExtra(SCGameActivity.EXTRA_DIRCACHE, dirCache);
            intent.putExtra(SCGameActivity.EXTRA_NATIVELIBDIR, nativeLibDir);
            intent.putExtra(SCGameActivity.EXTRA_GPU_MODE, mGpuMode);
            startActivity(intent);
        } catch (final Exception e) {
            LogFile.error("Launch failed", e);
            String fullError = getFullErrorReport(e);
            LogFile.error(fullError);
            showErrorDialog(fullError);
        } finally {
            progressBar.setVisibility(View.GONE);
            launchButton.setEnabled(true);
            menuButton.setEnabled(true);
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
                menuButton.setEnabled(true);
                
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
        try {
            sb.append("--- vulkan probe ---\n").append(SCVulkanProbe.summary(this)).append("\n");
        } catch (Exception e) {
            sb.append("--- vulkan probe failed: ").append(e.getMessage()).append(" ---\n");
        }
        File launcher = LogFile.getLogFile();
        sb.append("\n--- launcher log ---\n").append(readHeadTail(launcher, 128 * 1024, 256 * 1024));
        File latest = new File(getFilesDir(), "latestlog.txt");
        // Head AND tail: a run-away native assert used to fill latestlog.txt with
        // megabytes of repeats, so a tail-only report lost the beginning of the
        // storm -- the part that says what actually went wrong first.
        sb.append("\n--- latestlog.txt (last game log: head + tail) ---\n")
                .append(readHeadTail(latest, 256 * 1024, 512 * 1024));
        sb.append("\n--- last actions ---\n").append(SCActionTrail.dump());
        return sb.toString();
    }

    /**
     * First {@code headBytes} plus last {@code tailBytes} of {@code f} (whole
     * file when smaller), with an elision note for whatever is skipped.
     */
    private static String readHeadTail(File f, long headBytes, long tailBytes) {
        if (f == null || !f.exists()) return "(no log file yet)\n";
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            final long len = raf.length();
            if (len <= headBytes + tailBytes) {
                byte[] all = new byte[(int) len];
                raf.readFully(all);
                return new String(all, "UTF-8") + "\n";
            }
            byte[] head = new byte[(int) headBytes];
            raf.seek(0);
            raf.readFully(head);
            byte[] tail = new byte[(int) tailBytes];
            raf.seek(len - tailBytes);
            raf.readFully(tail);
            return new String(head, "UTF-8")
                    + "\n[... elided " + (len - headBytes - tailBytes) + " bytes in the middle ...]\n"
                    + new String(tail, "UTF-8") + "\n";
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
        menuButton.setEnabled(false);
        menuButton.setEnabled(false);
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

                    File jreXz = new File(destDir, jreArchiveName());
                    boolean usedLocalAsset = false;

                    // Try local asset first
                    try {
                        updateDownloadStatus("Extracting bundled JRE 25...");
                        InputStream assetIn = getAssets().open(localJreAsset());
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
                        LogFile.log("Downloading from: " + JRE_BASE_URL + jreArchiveName());
                        downloadFile(JRE_BASE_URL + jreArchiveName(), jreXz);
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
                            new File(destDir, "lib/x86_64/jli/libjli.so"),
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
                            menuButton.setEnabled(true);
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
                            menuButton.setEnabled(true);
                            menuButton.setEnabled(true);
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

        // Heap: JVM ergonomics (25% of RAM) is often too small for the game's
        // in-memory ISO unpacker on low-end devices, which then falls back to
        // the much slower file-backed "low memory" unpacker. Give it a sane,
        // clamped share of physical RAM. (The old "-Xmx2G" was passed after
        // the main class as a program arg, so the JVM never applied it.)
        args.add("-Xmx" + computeMaxHeapMb() + "m");

        args.add("-Djoml.fastmath");
        args.add("-Djoml.sinLookup");
        args.add("-Djoml.useMathFma");

        // Mute the in-game GL error popups (the driver messages are still
        // logged in full). Read by the patched GlesApi.notifyGl helper.
        if (loadMuteGlPopups()) {
            args.add("-D" + PROP_MUTE_GL_POPUPS + "=true");
        }

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
        // All modes: native system GLES (Zink disabled). SDL creates the
        // context from the system EGL, so LWJGL looks it up there too.
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

    /**
     * Max JVM heap in MB: a third of physical RAM, clamped to [768, 2560].
     * Falls back to 1536 when RAM cannot be read (e.g. API oddities).
     */
    private int computeMaxHeapMb() {
        long totalMb = 0;
        boolean lowRam = false;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    getSystemService(ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            if (am != null) {
                am.getMemoryInfo(mi);
                totalMb = mi.totalMem / (1024L * 1024L);
                lowRam = am.isLowRamDevice();
            }
        } catch (Throwable ignored) {
        }
        if (totalMb <= 0) {
            return 1536;
        }
        long heapMb = totalMb / 3;
        if (heapMb < 768) heapMb = 768;
        if (heapMb > 2560) heapMb = 2560;
        // Waydroid/cgroup memory caps do not change /proc/meminfo, so a low-RAM
        // Android profile must cap the in-process JVM heap explicitly.
        //
        // The first-run unpacker materialises individual files in a byte[]
        // (FileBackedFileData.getBytes), so it needs headroom; 768m OOMs during
        // the write phase. Use 1024m only until files/version exists, then the
        // watchdog restart comes back with 768m for the game/title.
        final boolean unpacking = gameDir != null
                && !new File(new File(gameDir, "files"), "version").isFile();
        long cap = 2560;
        if (lowRam || isX86_64()) {
            cap = unpacking ? 1024 : 768;
        }
        if (heapMb > cap) heapMb = cap;
        LogFile.log("Max heap: " + heapMb + "m (physical RAM " + totalMb + "m"
                + (lowRam ? ", low-RAM device" : "")
                + (isX86_64() ? (unpacking ? ", x86 unpack 1024m cap" : ", x86 768m cap") : "")
                + ")");
        return (int) heapMb;
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
        final File isosDir = new File(gameDir, "isos");
        isosDir.mkdirs();
        final File targetFile = new File(isosDir, targetName);

        new Thread(() -> {
            // Atomic import: copy to <name>.part, verify length, then rename.
            // Never leaves the game looking at a half-written disc image.
            final File part = new File(isosDir, targetName + ".part");
            try {
                final long expected = queryFileSize(uri);
                if (expected > 0 && targetFile.isFile() && targetFile.length() == expected) {
                    logDiscImageInfo(targetFile);
                    uiHandler.post(() -> {
                        Toast.makeText(this, targetName + " already imported", Toast.LENGTH_SHORT).show();
                        refreshAll();
                    });
                    return;
                }

                ContentResolver resolver = getContentResolver();
                long copied = 0;
                try (InputStream in = resolver.openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(part)) {
                    if (in == null) throw new IOException("Failed to open input stream");
                    byte[] buf = new byte[262144];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        copied += n;
                    }
                }
                if (expected > 0 && copied < expected) {
                    part.delete();
                    throw new IOException("Import truncated (" + copied + "/" + expected + " bytes)");
                }
                if (targetFile.exists() && !targetFile.delete()) {
                    part.delete();
                    throw new IOException("Could not replace existing " + targetName);
                }
                if (!part.renameTo(targetFile)) {
                    part.delete();
                    throw new IOException("Could not move imported file into place");
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
                part.delete();
                LogFile.error("Failed to copy disc image", e);
                uiHandler.post(() -> Toast.makeText(this, "Copy failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** DISPLAY_NAME/SIZE from a content Uri, or -1 if unknown. */
    private long queryFileSize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * Logs what the transferred disc image is: US disc N, a wrong-region disc,
     * or an unrecognized/unreadable file. Detection uses {@link SCDiscImage}
     * (the game's unpacker remains the final judge).
     */
    private void logDiscImageInfo(File f) {
        long len = f.length();
        SCDiscImage.Info info = SCDiscImage.detect(f);
        if (info.kind == SCDiscImage.Kind.US) {
            LogFile.log("Disc image OK (" + targetSizeMb(len) + "): " + f.getName()
                    + " = US disc " + info.disc + " [" + info.volumeId + "]");
        } else if (info.kind == SCDiscImage.Kind.OTHER_REGION) {
            LogFile.log("Disc image is WRONG REGION (" + info.region + ", " + targetSizeMb(len)
                    + "): " + f.getName() + " — US release required [" + info.volumeId + "]");
        } else {
            LogFile.log("Disc image copied (" + targetSizeMb(len)
                    + ", no recognized US disc marker): " + f.getName()
                    + (info.volumeId != null ? " [id " + info.volumeId + "]" : ""));
        }
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
        menuButton.setEnabled(false);
        menuButton.setEnabled(false);
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
                        new File(destDir, "lib/x86_64/jli/libjli.so"),
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
                    menuButton.setEnabled(true);
                    refreshAll();
                });

            } catch (final Exception e) {
                LogFile.error("JRE import failed", e);
                uiHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    downloadStatusText.setText("Import failed: " + e.getMessage());
                    downloadStatusText.setTextColor(0xFFFF6666);
                    menuButton.setEnabled(true);
                    menuButton.setEnabled(true);
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // Launcher-managed game update: manifest -> checksum -> backup ->
    // jar/patches swap -> force Android re-patch -> relaunch.
    // User data (files/, isos/, saves/, mods/, configs) is never touched.
    // ------------------------------------------------------------------
    private void updateGame() {
        if (gameDir == null) return;
        if (SCGameActivity.isJvmStarted()) {
            new AlertDialog.Builder(this)
                    .setTitle("Update Game")
                    .setMessage("Close the game first, then update from the launcher.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        menuButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        downloadStatusText.setVisibility(View.VISIBLE);
        downloadStatusText.setTextColor(0xFFCCCCCC);
        updateDownloadStatus("Checking for game update...");

        new Thread(() -> {
            File cache = new File(getCacheDir(), "game-update");
            File backup = null;
            try {
                deleteRecursive(cache);
                cache.mkdirs();

                File manifestFile = new File(cache, "game-update.json");
                downloadFile(gameUpdateManifestUrl(), manifestFile);
                final org.json.JSONObject m = new org.json.JSONObject(readUtf8(manifestFile));
                final String version = m.getString("version");
                final String base = m.getString("url");

                File installedVersion = new File(gameDir, "game.version");
                if (installedVersion.isFile() && version.equals(readUtf8(installedVersion))) {
                    uiHandler.post(() -> {
                        progressBar.setVisibility(View.GONE);
                        downloadStatusText.setVisibility(View.GONE);
                        menuButton.setEnabled(true);
                        new AlertDialog.Builder(this)
                                .setTitle("Update Game")
                                .setMessage("Already up to date: " + version)
                                .setPositiveButton("OK", null)
                                .show();
                    });
                    return;
                }

                updateDownloadStatus("Downloading game " + version + "...");
                File newJar = new File(cache, m.getString("jar"));
                downloadFile(base + m.getString("jar"), newJar);
                verifyDownload(newJar, m.getString("jarSha256"), m.optLong("jarSize", -1), "game jar");

                File newPatches = new File(cache, m.getString("patches"));
                downloadFile(base + m.getString("patches"), newPatches);
                verifyDownload(newPatches, m.getString("patchesSha256"), m.optLong("patchesSize", -1), "patches");

                updateDownloadStatus("Backing up current game...");
                backup = new File(gameDir, "update-backup-" + System.currentTimeMillis());
                backup.mkdirs();
                File oldJar = new File(gameDir, MAIN_JAR_NAME);
                copyFile(oldJar, new File(backup, MAIN_JAR_NAME));
                File oldPatches = new File(gameDir, "patches");
                if (oldPatches.isDirectory()) copyRecursive(oldPatches, new File(backup, "patches"));
                File oldMarker = new File(gameDir, GameJarPatcher.MARKER);
                if (oldMarker.isFile()) copyFile(oldMarker, new File(backup, GameJarPatcher.MARKER));

                updateDownloadStatus("Installing " + version + "...");
                copyFile(newJar, oldJar);
                if (oldPatches.exists()) deleteRecursive(oldPatches);
                extractZip(newPatches, gameDir);
                if (oldMarker.exists()) oldMarker.delete();

                updateDownloadStatus("Applying Android patches...");
                GameJarPatcher.Result patch = GameJarPatcher.patch(oldJar, gameDir, true, true);
                if (!patch.patched && !patch.message.contains("already patched")) {
                    throw new IOException("Android patch failed: " + patch.message);
                }
                writeUtf8(installedVersion, version);

                String unpackVersion = readUtf8(new File(gameDir, "files/version"));
                boolean reRip = m.has("unpackVersion")
                        && !String.valueOf(m.getInt("unpackVersion")).equals(unpackVersion);
                LogFile.log("Game update installed: " + version + "; " + patch.message
                        + "; reRip=" + reRip);
                SCActionTrail.record("Game update installed: " + version);

                final boolean needsRip = reRip;
                uiHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    downloadStatusText.setVisibility(View.GONE);
                    menuButton.setEnabled(true);
                    refreshAll();
                    new AlertDialog.Builder(this)
                            .setTitle("Game updated")
                            .setMessage("Installed " + version + ".\n"
                                    + (needsRip ? "The game data must be re-extracted on next launch (ISOs required)." : "Launch the game to test."))
                            .setPositiveButton("OK", null)
                            .show();
                });
            } catch (final Exception e) {
                LogFile.error("Game update failed", e);
                try {
                    if (backup != null) {
                        copyFile(new File(backup, MAIN_JAR_NAME), new File(gameDir, MAIN_JAR_NAME));
                        File bp = new File(backup, "patches");
                        if (bp.isDirectory()) {
                            File cur = new File(gameDir, "patches");
                            if (cur.exists()) deleteRecursive(cur);
                            copyRecursive(bp, cur);
                        }
                        File bm = new File(backup, GameJarPatcher.MARKER);
                        if (bm.isFile()) copyFile(bm, new File(gameDir, GameJarPatcher.MARKER));
                    }
                } catch (Exception ignored) {
                }
                uiHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    downloadStatusText.setText("Update failed: " + e.getMessage());
                    downloadStatusText.setTextColor(0xFFFF6666);
                    menuButton.setEnabled(true);
                });
            } finally {
                deleteRecursive(cache);
            }
        }, "SCGameUpdate").start();
    }

    /** Production manifest unless game-update-url.txt overrides it for testing. */
    private String gameUpdateManifestUrl() {
        final String override = readUtf8(new File(gameDir, "game-update-url.txt"));
        return override.isEmpty() ? GAME_UPDATE_MANIFEST_URL : override;
    }

    private void verifyDownload(final File file, final String sha256, final long size, final String label) throws Exception {
        if (size > 0 && file.length() != size) {
            throw new IOException(label + " size mismatch: " + file.length() + " != " + size);
        }
        final String actual = sha256(file);
        if (sha256 != null && !sha256.isEmpty() && !sha256.equalsIgnoreCase(actual)) {
            throw new IOException(label + " SHA-256 mismatch");
        }
    }

    private static String readUtf8(final File file) {
        try {
            if (!file.isFile()) return "";
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeUtf8(final File file, final String text) throws Exception {
        java.nio.file.Files.write(file.toPath(), (text + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha256(final File file) throws Exception {
        final java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            final byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        final StringBuilder sb = new StringBuilder();
        for (final byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
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
