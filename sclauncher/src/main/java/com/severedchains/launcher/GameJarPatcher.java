package com.severedchains.launcher;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Runtime patcher for the deployed Severed Chains game jar. Several
 * idempotent, version-independent bytecode patches are applied in one pass:
 *
 * <ol>
 *   <li><b>GlesMesh.draw guard</b> — the Adreno driver reports a HIGH-severity
 *   debug message ("unable to generate a triangle primitive because there are
 *   less than 3 vertices") whenever a mesh with fewer than three vertices is
 *   drawn as a triangle primitive. An early return is injected into
 *   {@code legend.core.renderer.opengles.GlesMesh.draw()V} and
 *   {@code draw(II)V} when the mode is a triangle primitive
 *   (GL_TRIANGLES / GL_TRIANGLE_STRIP / GL_TRIANGLES_ADJACENCY) and the vertex
 *   count is a positive value below 3. Line primitives (valid with two
 *   vertices) are untouched, and {@code count == 0} must NEVER be skipped — it
 *   is the "draw everything" sentinel (it delegates to {@code draw()}), the
 *   normal path for models, UI and the FMV quad.
 *
 *   <p>The same pass also clamps every triangle draw count down to a whole
 *   number of primitives, which is what actually silences the driver: the
 *   message is raised by the <i>trailing partial primitive</i> of a count that
 *   is not a multiple of 3 (or 6 for {@code GL_TRIANGLES_ADJACENCY}), not by
 *   the whole count being small. The clamp is rendering-neutral, because the
 *   driver discards those trailing vertices anyway.</li>
 *
 *   <li><b>AudioThread reinit loop</b> — the game's audio thread treated
 *   LWJGL's {@code ALCapabilities.OpenAL10} flag as "this device cannot do
 *   OpenAL 1.0", tore the device and context down, and the two-second device
 *   poll then recreated them, forever. The flag is false whenever the OpenAL
 *   build does not export LWJGL's own {@code al*Direct} entry points, which
 *   the stock OpenAL Soft builds do not, while every entry point the game
 *   actually uses is present and working. The patch redirects that branch to
 *   the success path and logs one explanatory line.</li>
 *
 *   <li><b>GL debug filter</b> — the game passed the GLES2 hint constant
 *   {@code GL_DONT_CARE} (0x1100) to {@code glDebugMessageControl}, where
 *   KHR_debug's 0xFFFF belongs; every one of its filter calls was therefore
 *   answered with INVALID_ENUM and changed nothing. The patch uses the right
 *   constant and additionally mutes the driver's PERFORMANCE/OTHER messages
 *   and the no-ID sentinel (the Adreno "Namespace collision detected, using
 *   slow path" hint).</li>
 *
 *   <p><b>r25 ordering fix:</b> r24 inserted the PERFORMANCE mute after the
 *   game's <i>first</i> filter call. The game then calls
 *   {@code glDebugMessageControl(..., GL_DEBUG_SEVERITY_HIGH, true)}, and
 *   KHR_debug's last matching rule wins, so the HIGH enable re-enabled the
 *   very messages the mute had just disabled. The device logs still showed
 *   ~97,000 suppressed lines from the namespace-collision message and the
 *   r24 release did not stop the synchronous callback overhead. The mutes are
 *   now emitted after the final filter call (immediately before
 *   {@code glDebugMessageCallback}), and the callback itself drops messages
 *   whose driver ID is the 0x7FFFFFFF no-ID sentinel as a belt-and-braces
 *   guard in case a driver ignores the type/ID filter.</p>
 *
 *   <li><b>Unpacker I/O bound (LITE BUILD ONLY)</b> —
 *   {@code legend.game.unpacker.Unpacker} runs its transform and write phases
 *   on {@code Executors.newVirtualThreadPerTaskExecutor()}. File I/O unmounts
 *   virtual threads, so the write loop can have thousands of streams open at
 *   once and Android processes exhaust their file-descriptor budget
 *   ("Too many open files" / EMFILE) with a storm of near-identical
 *   exceptions. When {@code boundUnpacker} is set (lite APK), both executor
 *   creations are rewritten to a bounded
 *   {@code Executors.newFixedThreadPool(}{@value #UNPACK_THREADS}{@code )},
 *   capping concurrent unpack I/O at a few descriptors. The main APK leaves
 *   the game's executors untouched.</li>
 *
 *   <li><b>Low-memory unpacker forced (LITE BUILD ONLY)</b> — the game only
 *   switches to its disk-backed unpacker after a first OutOfMemoryError, which
 *   is too late on very low-RAM devices (the process aborts at the write
 *   phase). When {@code forceLowMemUnpacker} is set (lite APK),
 *   {@code legend.core.Config.lowMemoryUnpacker()} is rewritten to always
 *   return true, so the rip uses file-backed reads from the start and needs
 *   far less heap.</li>
 * </ol>
 *
 * <p>The jar is patched in place before the JVM starts. A sidecar marker keyed
 * by the jar's md5 makes it idempotent and re-applies automatically after a
 * game update replaces the jar. The unpacker rewrite is naturally idempotent
 * (the replaced call no longer exists); the launcher restores the pristine jar
 * from the APK asset whenever the revision marker is absent so the GlesMesh
 * injection can never nest across revisions.
 */
public final class GameJarPatcher {
    private static final String TAG = "GameJarPatcher";

    /**
     * Sidecar marker filename. Its <b>content</b> is
     * {@code "<patched jar md5> <revision>"} (see {@link #PATCH_REVISION}), so a
     * patch change only has to change {@link #PATCH_REVISION} -- the file name
     * is bumped only to force a one-time re-seed of installs created by an
     * older launcher.
     *
     * <p>History: this used to be a bare md5 with the revision in the file
     * name, so a device that had already been patched kept its old jar forever
     * unless the name was bumped by hand. The v1.0.34 scissor rewrite
     * (368 -&gt; 320) shipped without a bump, so testers kept running the
     * v1.0.33 jar: the wide-screen path then scaled every glyph's clip
     * rectangle by 1.15 and clipped the dialogue text out of its box in
     * "Extended" mode (Forced 4:3 was unaffected because that path never reads
     * {@code expectedWidth}).
     */
    public static final String MARKER = ".gamejar_patch_v23";

    /**
     * Patch-revision stamp stored in the marker. <b>Change this whenever the
     * patch behaviour changes</b> (and include any new behavioural input in
     * {@link #fingerprint()} so it cannot be forgotten again). It is logged on
     * every launch, which makes a stale patch visible in a device report.
     */
    public static final String PATCH_REVISION = "r25";

    /** Rewritten {@code expectedWidth} for the retail widescreen branch. */
    private static final int EXPECTED_WIDTH_FROM = 368;
    private static final int EXPECTED_WIDTH_TO = 320;

    /**
     * TEMPORARY diagnostic: print the live widescreen state from
     * {@code RenderBatch.updateProjections()} to stderr (which the launcher
     * captures into latestlog.txt, and therefore Send Log). The game logs
     * nothing about the resolved projection, and {@code config.dcnf} is binary,
     * so this is the only way to see from a device report which widescreen
     * branch ran, with what {@code expectedWidth}/{@code widescreenOrthoOffsetX}
     * and which ortho matrix. Called rarely (resize / widescreen option change /
     * resizeRenderBuffers), so it cannot spam. Remove once the widescreen text
     * bug is closed.
     */
    private static final boolean ENABLE_WIDESCREEN_DIAG = true;

    /**
     * EXPERIMENT: stop the retail textbox glyphs from carrying a per-character
     * clip rectangle.
     *
     * <p>{@code Text} is the <b>only</b> class in the whole jar that calls
     * {@code QueuedModelStandard.scissor(int,int,int,int)} (four call sites,
     * verified), so a glyph's clip rect is the only per-model scissor in the
     * game -- and "the dialogue box draws with no text in it" is precisely the
     * symptom of that rect not covering the glyph. The rewrite drops the four
     * computed arg values and passes 0,0,0,0 instead; {@code GlesApi.scissor}
     * treats an empty model scissor as "no model clip" and falls back to the
     * model's world scissor (the full frame). Straight-line, no new branch
     * targets, net stack unchanged, so COMPUTE_MAXS stays valid.
     *
     * <p>Cost if it is kept: glyphs are no longer clipped at the textbox edge,
     * which can look slightly wrong for scrolling multi-line text. Nothing else
     * in the game loses clipping.
     */
    private static final boolean ENABLE_GLYPH_SCISSOR_BYPASS = true;

    /**
     * UI-on-top render-order patch. Off: it did not fix the missing death
     * effect and reordering the passes made some 3D geometry draw out of order.
     * Kept (see {@link #transformRenderEngine}) so a reworked variant can be
     * toggled here without touching the jar plumbing.
     */
    private static final boolean ENABLE_RENDERENGINE_PATCH = false;

    /**
     * EXPERIMENT: make {@code GenericSpriteEffect24} sprites (monster-death
     * body parts / "blood", Dragoon feathers, moonlight stars) render opaque
     * instead of translucent. They are queued as {@code B_PLUS_F} additive
     * translucent ortho sprites; if the port's additive-translucency path is
     * what makes the death effect invisible, dropping the translucency flag
     * makes them show up in the opaque pass. Toggle off once diagnosed.
     */
    private static final boolean ENABLE_EFFECT_OPAQUE_PATCH = false;

    /**
     * Depth-buffer texture format: change the game's {@code TextureDataType.UINT}
     * depth texture back to {@code FLOAT} to match the desktop build.
     *
     * <p>The PS1 depth emulation writes a custom per-primitive depth
     * ({@code tmd.fsh} {@code gl_FragDepth}, {@code tmd.gsh} {@code gl_Position.z
     * = avgDepth}). The Android jar was patched UINT (RESUME: "GLES rejects
     * float depth"); the desktop jar uses FLOAT, and it's the only other
     * difference between the two jars. A UINT (integer) depth buffer quantizes
     * the written depth differently, which breaks the fine per-primitive
     * ordering — reported as "ordering is the big issue in the battle scenes".
     * Matching desktop restores the float depth path.
     *
     * <p>Key detail: in GLES an <b>unsized</b> {@code GL_DEPTH_COMPONENT} cannot
     * take {@code GL_FLOAT} (that combination is invalid → glError 502, which is
     * what the first attempt hit). The GLES-valid float depth is the <b>sized</b>
     * format {@code GL_DEPTH_COMPONENT32F}. So this patch changes two things
     * together: {@code RenderEngine} UINT→FLOAT, and {@code GlesTexture}'s
     * internal format for {@code DEPTH_COMPONENT} → {@code GL_DEPTH_COMPONENT32F}
     * ({@link #transformGlesTextureDepth}, guarded by the same flag).
     */
    private static final boolean ENABLE_DEPTH_FLOAT_PATCH = true;

    /**
     * Force {@code usePs1Depth} true for every TMD / battle-TMD model, so all
     * models use the PS1 per-primitive average-depth ordering (the geometry
     * shader writes {@code gl_Position.z = avgDepth}) instead of modern
     * per-vertex depth. The flag is per-model, set by scripts; forcing it is the
     * only remaining launcher-side lever for the battle "out of order" /
     * see-through model issue.
     */
    private static final boolean ENABLE_FORCE_PS1DEPTH = true;

    /**
     * The Adreno battle workarounds below force the game's legacy 368-wide
     * projection. Mesa/Waydroid does not need them, and they override the
     * user's EXPANDED widescreen mode (nativeW=368 + black bar on a 16:9
     * display), so they are disabled for the x86_64 Waydroid test build.
     */
    private static final boolean IS_X86_64 =
            "x86_64".equals(System.getProperty("os.arch"))
            || "amd64".equals(System.getProperty("os.arch"));

    /**
     * Force {@code RenderBatch.setRenderMode(...)} to always use
     * {@code RenderMode.LEGACY}. Superseded by
     * {@link #ENABLE_DECOUPLE_BATTLE}; kept disabled.
     */
    private static final boolean ENABLE_FORCE_LEGACY = false;

    /**
     * FIX: decouple the 3D projection from the 2D overlay for the battle.
     *
     * <p>On this Adreno GLES driver the PERSPECTIVE-mode 3D projection
     * ({@code perspectiveProjection}, the −Y-negated perspective matrix)
     * shatters battle geometry, while the game's LEGACY projection path is
     * fine. But simply forcing {@code renderMode == LEGACY} also switches the
     * 2D overlay/HUD to the legacy ortho, which broke the battle
     * HUD/portrait/subtitles/transition on the tested build.
     *
     * <p>v1 (shipped as 1.0.32-decouple) kept {@code renderMode == PERSPECTIVE}
     * and hand-wrote the LEGACY ortho over the 3D projection. That fixed the
     * models but pinned the 3D view to 4:3 and, because it bypassed the game's
     * own code, ignored the widescreen setting entirely.
     *
     * <p><b>v2</b> keeps {@code renderMode == PERSPECTIVE} (so the game-logic
     * sites that branch on it — {@code SBtld} battle HUD placement,
     * {@code ScreenDistortionEffectData08} — are untouched) but makes the whole
     * <i>projection pipeline</i> take the stock LEGACY path, so the game's own
     * widescreen code runs unmodified:
     * <ol>
     *   <li>{@code RenderBatch.updateProjections}: the
     *       {@code renderMode == PERSPECTIVE} test is forced false, so the
     *       game's own LEGACY branch builds BOTH matrices. That branch is
     *       option-aware: {@code submap_widescreen_mode == EXPANDED} ("extended",
     *       true widescreen) widens the 3D ortho to the screen aspect and sets
     *       {@code expectedWidth = 368}; the other modes keep 4:3. It also sets
     *       the 2D ortho and {@code widescreenOrthoOffsetX} consistently. No
     *       hand-written matrix, so no option can be bypassed.</li>
     *   <li>{@code RenderEngine.setProjectionMode}: {@code highQualityProjection}
     *       (renderMode == PERSPECTIVE) is forced false, so {@code projectionMode}
     *       is 1 (PS1/H-division) like the LEGACY path, giving the correct
     *       pseudo-perspective.</li>
     *   <li>{@link #ENABLE_DECOUPLE_WIDESCREEN}: the remaining
     *       {@code renderMode == LEGACY} gates on the widescreen option are
     *       forced true, so FORCED_4_3/STRETCHED/EXPANDED all take effect.</li>
     * </ol>
     */
    private static final boolean ENABLE_DECOUPLE_BATTLE = !IS_X86_64;

    /**
     * Companion to {@link #ENABLE_DECOUPLE_BATTLE}: the game's widescreen
     * plumbing asks {@code renderMode == LEGACY} in a few more places, and all
     * of them are dead while the field still reads PERSPECTIVE:
     *
     * <ul>
     *   <li>{@code RenderEngine.pre()} — the FORCED_4_3 pillarbox
     *       ({@code api.viewport(offset, 0, expectedWidth, expectedHeight)});</li>
     *   <li>{@code GlesApi.initBatch} — the {@code widescreen} flag that picks
     *       the scissor maths, which must agree with the matrices
     *       {@code updateProjections} just built;</li>
     *   <li>{@code GlesApi.scissor} — the FORCED_4_3 scissor branch;</li>
     *   <li>{@code ScissorStack.setRescale} — same flag, for submap
     *       scissors.</li>
     * </ul>
     *
     * <p>Each is forced to read as LEGACY, i.e. "resolve the widescreen option
     * the way the legacy path does" — which is exactly what
     * {@code updateProjections} now does. Without this the option changes the
     * 3D matrix but not the viewport/scissor, which is the "stuck in stretched"
     * / mismatched-framing bug.
     */
    private static final boolean ENABLE_DECOUPLE_WIDESCREEN = !IS_X86_64;

    /**
     * FIX: make the modern (PERSPECTIVE) screen-space conversion return
     * coordinates in the same 0-based space the 2D ortho projection uses.
     *
     * <p>{@code Transformations.toScreenspace} has two branches. The LEGACY one
     * uses the PS1 GTE and returns 0-based PS1 screen coordinates (0..320 x
     * 0..240). The modern one multiplies the NDC by half the extent:
     * {@code out.x = ndc.x / 2 * scale} and {@code out.y = -ndc.y / 2 * height}
     * — i.e. it returns <i>centred</i> coordinates (-w/2..w/2), while the 2D
     * ortho projection is 0-based (0..w). Every consumer that positions a 2D
     * element from that result is therefore off by half the screen: the battle
     * target arrow (the "enemy select" cursor) lands off-screen for enemies on
     * the left, and the floating damage numbers pile up against the left edge
     * (the game's own {@code clampX}/{@code clampY} expect 0..320 / 0..240,
     * which is the giveaway).
     *
     * <p>The patch remaps NDC [-1,1] to [0,1] in both axes (adding 0.5 to each
     * NDC term) and subtracts the widescreen ortho offset from x, because
     * {@code RenderBatch.queueOrthoModel} adds that offset to every 2D model —
     * the UI needs it (it is laid out in 0..320 space), but an element placed
     * from a world coordinate is already in screen space and must not get it
     * twice.
     *
     * <p>Only the modern branch is touched: the LEGACY branch returns early
     * and is already correct (it is what the title screen and submap use).
     */
    private static final boolean ENABLE_SCREENSPACE_FIX = true;

    private static final String GLESMESH_ENTRY = "legend/core/renderer/opengles/GlesMesh.class";
    private static final String GLESMESH_OWNER = "legend/core/renderer/opengles/GlesMesh";
    private static final String FIELD_MODE = "mode";
    private static final String FIELD_COUNT = "count";
    private static final int GL_TRIANGLES = 4;
    private static final int GL_TRIANGLE_STRIP = 5;
    /**
     * GL_TRIANGLES_ADJACENCY is <b>0x0008</b> (8), not 0x000C. The old value 12
     * was the desktop-GL GL_QUADS constant and never matched anything, so the
     * guard silently never applied to adjacency meshes -- which is exactly
     * what the TMD models use ({@code TmdObjLoader} builds them with
     * {@code VertexOrder.TRIANGLES_ADJACENCY}).
     */
    private static final int GL_TRIANGLES_ADJACENCY = 8;
    /** Vertices per GL_TRIANGLES primitive. */
    private static final int GL_TRIANGLES_VERTS = 3;
    /** Vertices per GL_TRIANGLES_ADJACENCY primitive (3 + 3 adjacency). */
    private static final int GL_TRIANGLES_ADJACENCY_VERTS = 6;
    /** Minimum vertices for a GL_TRIANGLE_STRIP to produce a primitive. */
    private static final int GL_TRIANGLE_STRIP_MIN = 3;
    /** Name of the injected helper that clamps a draw count to whole primitives. */
    private static final String CLAMP_COUNT = "clampCount";

    private static final String AUDIOTHREAD_ENTRY = "legend/core/audio/AudioThread.class";
    private static final String AUDIOTHREAD_OWNER = "legend/core/audio/AudioThread";
    private static final String LOGGER_FIELD = "LOGGER";
    private static final String LOGGER_DESC = "Lorg/apache/logging/log4j/Logger;";
    private static final String ALCAPS_OWNER = "org/lwjgl/openal/ALCapabilities";
    private static final String ALCAPS_OPENAL10 = "OpenAL10";

    private static final String GLES32_OWNER = "org/lwjgl/opengles/GLES32";
    private static final String GLESAPI_OWNER = "legend/core/renderer/opengles/GlesApi";
    private static final String GAMEOVERLAY_OWNER = "legend/game/ui/GameOverlay";
    private static final String NOTIFY_DESC = "(ILlegend/core/lang/TextComponent;)V";
    private static final String GET_OBJECT_NAME = "getObjectName";
    private static final String GET_OBJECT_NAME_DESC = "(II)Ljava/lang/String;";
    /** Name of the injected helper that replaces the on-screen GL error popup. */
    private static final String NOTIFY_GL = "notifyGl";
    /**
     * System property the launcher sets when the player wants the in-game GL
     * error popups muted. Read by the patched {@link #NOTIFY_GL} helper.
     */
    private static final String MUTE_POPUPS_PROPERTY = "sc.mute.gl.popups";
    /**
     * KHR_debug's "don't care" filter is 0xFFFF. The game passes
     * {@code GLES20.GL_DONT_CARE}, which is the <b>GLES2 hint</b> constant
     * 0x1100 -- not a valid source, type or severity for
     * {@code glDebugMessageControl}. The driver answers INVALID_ENUM and
     * changes nothing, so all four of the game's filter calls were no-ops and
     * the driver kept its default state (everything except NOTIFICATION).
     */
    private static final int GLES_DONT_CARE_KHR = 0xFFFF;
    /**
     * {@code GLES20.GL_DONT_CARE} (0x1100) -- the GLES2 <i>hint</i>
     * constant, which the game passes to {@code glDebugMessageControl} where
     * KHR_debug's 0xFFFF belongs.
     */
    private static final int GLES2_DONT_CARE_HINT = 0x1100;
    /** GL_DEBUG_TYPE_PERFORMANCE -- Adreno's "Namespace collision" hint. */
    private static final int GL_DEBUG_TYPE_PERFORMANCE = 0x8250;
    /** GL_DEBUG_TYPE_OTHER -- Adreno also tags the hint as [Other]. */
    private static final int GL_DEBUG_TYPE_OTHER = 0x8251;
    /**
     * The ID Adreno reports for messages that carry no KHR_debug ID
     * ({@code Integer.MAX_VALUE}). The namespace-collision hint uses it.
     */
    private static final int ADRENO_NO_MESSAGE_ID = 0x7FFFFFFF;
    /** JVM type code for {@code int[]} (NEWARRAY operand). */
    private static final int T_INT = 10;

    private static final String UNPACKER_ENTRY = "legend/game/unpacker/Unpacker.class";
    private static final String EXECUTORS = "java/util/concurrent/Executors";
    private static final String EXECUTOR_SERVICE = "Ljava/util/concurrent/ExecutorService;";
    /** Max concurrent unpack jobs (each holds at most a few file descriptors). */
    private static final int UNPACK_THREADS = 16;

    private static final String CONFIG_ENTRY = "legend/core/Config.class";
    private static final String CONFIG_METHOD = "lowMemoryUnpacker";
    private static final String CONFIG_DESCRIPTOR = "()Z";

    private static final String ISOREADER_ENTRY = "legend/game/unpacker/IsoReader.class";
    private static final String ISOREADER_OWNER = "legend/game/unpacker/IsoReader";
    private static final String RAF_OWNER = "java/io/RandomAccessFile";

    private static final String GENERICSPRITE_ENTRY = "legend/game/combat/effects/GenericSpriteEffect24.class";
    private static final String RENDERENGINE_ENTRY = "legend/core/renderer/RenderEngine.class";
    private static final String RENDERENGINE_OWNER = "legend/core/renderer/RenderEngine";
    private static final String RENDERBATCH_OWNER = "legend/core/renderer/RenderBatch";
    private static final String RENDERBATCH_DESC = "Llegend/core/renderer/RenderBatch;";
    private static final String RENDERAPI_OWNER = "legend/core/renderer/RenderApi";
    private static final String PROJECTIONMODE_OWNER = "legend/core/renderer/ProjectionMode";
    private static final String QUEUEPOOL_DESC = "Llegend/core/QueuePool;";
    private static final String TEXTUREDATATYPE_OWNER = "legend/core/renderer/TextureDataType";
    /** The {@code resizeRenderBuffers} lambda that builds the depth texture. */
    private static final String DEPTH_LAMBDA = "lambda$resizeRenderBuffers$1";
    private static final String RENDERBATCH_ENTRY = "legend/core/renderer/RenderBatch.class";
    private static final String RENDERMODE_OWNER = "legend/game/EngineState$RenderMode";
    private static final String GLESTEXTURE_ENTRY = "legend/core/renderer/opengles/GlesTexture.class";
    private static final String GLESAPI_ENTRY = "legend/core/renderer/opengles/GlesApi.class";
    private static final String SCISSORSTACK_ENTRY = "legend/core/renderer/ScissorStack.class";
    private static final String SCISSORSTACK_OWNER = "legend/core/renderer/ScissorStack";
    private static final String TRANSFORMATIONS_ENTRY = "legend/core/Transformations.class";
    private static final String TRANSFORMATIONS_OWNER = "legend/core/Transformations";
    private static final String TEXT_ENTRY = "legend/game/Text.class";
    private static final String UPDATER_ENTRY = "legend/core/Updater.class";
    /**
     * The stock SC updater downloads the desktop Linux ARM64 build and would
     * replace the Android-patched jar/assets. Keep the in-game check inert;
     * launcher/APK updates are handled separately.
     */
    private static final boolean ENABLE_UPDATER_DISABLE = true;
    private static final String GAMEENGINE_OWNER = "legend/core/GameEngine";
    private static final String QUEUEDMODEL_DESC = "Llegend/core/renderer/QueuedModel;";
    private static final String RENDERMODE_DESC = "Llegend/game/EngineState$RenderMode;";
    private static final String SHADEROPTS_TMD_ENTRY = "legend/core/renderer/ShaderOptionsTmd.class";
    private static final String SHADEROPTS_BTMD_ENTRY = "legend/core/renderer/ShaderOptionsBattleTmd.class";
    private static final String SHADERUNIFORMINT_OWNER = "legend/core/renderer/ShaderUniformInt";
    /** GL_DEPTH_COMPONENT (unsized). */
    private static final int GL_DEPTH_COMPONENT = 0x1902;
    /** GL_DEPTH_COMPONENT32F (sized; the GLES-valid float depth). */
    private static final int GL_DEPTH_COMPONENT32F = 0x8CAC;

    private static final int PATCHED_GLESMESH = 1;
    private static final int PATCHED_UNPACKER = 2;
    private static final int PATCHED_CONFIG = 4;
    private static final int PATCHED_ISOREADER = 8;
    private static final int PATCHED_RENDERENGINE = 16;
    private static final int PATCHED_GENERICSPRITE = 32;
    private static final int PATCHED_GLESTEXTURE = 64;
    private static final int PATCHED_PS1DEPTH = 128;
    private static final int PATCHED_RENDERBATCH = 256;
    private static final int PATCHED_GLESAPI = 512;
    private static final int PATCHED_SCISSORSTACK = 1024;
    private static final int PATCHED_TRANSFORMATIONS = 2048;
    private static final int PATCHED_TEXT_SCISSOR = 4096;
    private static final int PATCHED_UPDATER = 8192;
    private static final int PATCHED_AUDIOTHREAD = 16384;
    private static final int PATCHED_GLESAPI_DEBUG = 32768;

    private GameJarPatcher() {}

    /** Result of a patch attempt, for logging. */
    public static final class Result {
        public final boolean patched;
        public final String message;
        Result(final boolean patched, final String message) {
            this.patched = patched;
            this.message = message;
        }
    }

    /**
     * Every jar entry that {@link #transformJar} rewrites, with the transform
     * that owns it.
     *
     * <p>{@link #transformJar} dispatches on the entry name with an
     * {@code if / else if} chain, so <b>two entries with the same name means
     * one of the transforms is silently dropped</b> — no error, no log line,
     * just a patch that stopped being applied. That is not hypothetical: in
     * r23 a second branch for {@code GlesApi.class} (the KHR_debug filter)
     * shadowed the branch that rewrote the widescreen gates, and the battle
     * HUD portraits went back to being wrong with nothing in the log to say
     * why.
     *
     * <p>This table is the single source of truth for the dispatch, and the
     * check below fails loudly if two owners ever claim the same file again.
     */
    private static final String[][] PATCH_TARGETS = {
            { GLESMESH_ENTRY, "glesmesh" },
            { AUDIOTHREAD_ENTRY, "audiothread" },
            { GLESAPI_ENTRY, "glesapi" },
            { UNPACKER_ENTRY, "unpacker" },
            { CONFIG_ENTRY, "config" },
            { ISOREADER_ENTRY, "isoreader" },
            { RENDERENGINE_ENTRY, "renderengine" },
            { GENERICSPRITE_ENTRY, "genericsprite" },
            { GLESTEXTURE_ENTRY, "glestexture" },
            { SHADEROPTS_TMD_ENTRY, "shaderopts-tmd" },
            { SHADEROPTS_BTMD_ENTRY, "shaderopts-btmd" },
            { RENDERBATCH_ENTRY, "renderbatch" },
            { SCISSORSTACK_ENTRY, "scissorstack" },
            { TRANSFORMATIONS_ENTRY, "transformations" },
            { TEXT_ENTRY, "text" },
            { UPDATER_ENTRY, "updater" },
    };

    static {
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (final String[] target : PATCH_TARGETS) {
            if (!seen.add(target[0])) {
                throw new IllegalStateException("GameJarPatcher: two patch targets claim the same jar entry: "
                        + target[0] + " (" + target[1] + ") -- merge them into one branch, or the second "
                        + "transform is silently dropped by the else-if chain");
            }
        }
    }

    /**
     * TEMPORARY diagnostic helpers (see {@link #ENABLE_WIDESCREEN_DIAG}).
     *
     * <p>Each emits a straight-line {@code System.err} print with no branch
     * targets, so the surrounding method's StackMapTable stays valid under
     * {@code COMPUTE_MAXS}. They are written to the delegate visitor on purpose:
     * routing them through {@link LegacyRenderMode} would rewrite the
     * {@code renderMode} read they are meant to observe.
     */
    private static void diagStr(final MethodVisitor mv, final String s) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
        mv.visitLdcInsn(s);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false);
    }

    private static void diagIntField(final MethodVisitor mv, final String owner, final String field) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, field, "I");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
    }

    private static void diagFloatField(final MethodVisitor mv, final String owner, final String field) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, field, "F");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(F)V", false);
    }

    private static void diagObjectField(final MethodVisitor mv, final String owner, final String field,
                                        final String desc) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, field, desc);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/Object;)V", false);
    }

    /** Prints {@code this.engine.<getter>()} as an int (RenderEngine getters). */
    private static void diagEngineInt(final MethodVisitor mv, final String getter) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, RENDERBATCH_OWNER, "engine", "L" + RENDERENGINE_OWNER + ";");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RENDERENGINE_OWNER, getter, "()I", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
    }

    /**
     * True when the marker was written by <b>this</b> patch revision, i.e. the
     * deployed jar is known to have been patched by the current code. Used by
     * the launcher to decide whether to re-seed the pristine jar. Content-only
     * (no jar hash) so it stays cheap on every launch; the patcher still
     * verifies the jar's own md5.
     */
    public static boolean isPatchRevisionCurrent(final File markerDir) {
        final File marker = new File(markerDir, MARKER);
        if (!marker.isFile()) {
            return false;
        }
        try {
            return readText(marker).trim().endsWith(" " + PATCH_REVISION + "/" + fingerprint());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Behavioural fingerprint of the enabled patches: every flag and constant
     * that changes what lands in the jar. Stored in the marker, so toggling any
     * of them invalidates an existing patch automatically instead of relying on
     * someone remembering to bump {@link #PATCH_REVISION} (which is how the
     * v1.0.34 scissor rewrite was lost for a day).
     */
    private static String fingerprint() {
        // Deterministic: Integer/Boolean hashCodes are value-based (no identity
        // hashes), so the stamp is stable across processes and JVM versions.
        final int h = java.util.Arrays.hashCode(new Object[] {
                MARKER, PATCH_REVISION,
                ENABLE_RENDERENGINE_PATCH, ENABLE_EFFECT_OPAQUE_PATCH,
                ENABLE_DEPTH_FLOAT_PATCH, ENABLE_FORCE_PS1DEPTH,
                ENABLE_FORCE_LEGACY, ENABLE_DECOUPLE_BATTLE,
                ENABLE_DECOUPLE_WIDESCREEN, ENABLE_SCREENSPACE_FIX,
                ENABLE_WIDESCREEN_DIAG, ENABLE_GLYPH_SCISSOR_BYPASS,
                EXPECTED_WIDTH_FROM, EXPECTED_WIDTH_TO,
                GL_TRIANGLES, GL_TRIANGLES_ADJACENCY, UNPACK_THREADS,
                GL_TRIANGLE_STRIP, GL_TRIANGLES_VERTS,
                GL_TRIANGLES_ADJACENCY_VERTS, GL_TRIANGLE_STRIP_MIN,
                GLES_DONT_CARE_KHR, GLES2_DONT_CARE_HINT,
                GL_DEBUG_TYPE_PERFORMANCE, GL_DEBUG_TYPE_OTHER,
                ADRENO_NO_MESSAGE_ID,
                // The GL-popup mute is a patch *shape* change (a new synthetic
                // method plus a redirect), not just a constant tweak, so it
                // belongs in the stamp as well as in PATCH_REVISION.
                NOTIFY_GL, NOTIFY_DESC, GAMEOVERLAY_OWNER, MUTE_POPUPS_PROPERTY,
        });
        return Integer.toHexString(h);
    }

    /**
     * {@link #ENABLE_GLYPH_SCISSOR_BYPASS}: rewrite the four
     * {@code QueuedModelStandard.scissor(IIII)} call sites in {@code Text} to
     * pass {@code 0,0,0,0}. The receiver is left on the stack, the four
     * computed ints are dropped and four zeros pushed in their place, so the
     * stack shape, max stack and (branch-free) StackMapTable are unchanged.
     */
    private static byte[] transformTextGlyphScissors(final byte[] input) {
        final ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(final int opcode, final String owner, final String methodName,
                                                final String methodDesc, final boolean itf) {
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && "scissor".equals(methodName)
                                && ("(IIII)" + QUEUEDMODEL_DESC).equals(methodDesc)) {
                            for (int i = 0; i < 4; i++) {
                                super.visitInsn(Opcodes.POP);
                            }
                            for (int i = 0; i < 4; i++) {
                                super.visitInsn(Opcodes.ICONST_0);
                            }
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDesc, itf);
                    }
                };
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /**
     * Patches {@code jar} once per content revision. Safe to call on every
     * launch: if the marker matches the jar's md5 it returns immediately.
     *
     * @param boundUnpacker lite build only: also bound the Unpacker's
     *                      virtual-thread ISO-rip concurrency (EMFILE fix).
     * @param forceLowMemUnpacker lite build only: also force the game's
     *                      disk-backed low-memory unpacker from the start
     *                      (fewer file handles / much less heap).
     */
    public static Result patch(final File jar, final File markerDir,
                               final boolean boundUnpacker, final boolean forceLowMemUnpacker) {
        if (jar == null || !jar.isFile()) {
            return new Result(false, "game jar not found");
        }

        final File marker = new File(markerDir, MARKER);
        final String rev = PATCH_REVISION + "/" + fingerprint();
        String md5;
        try {
            md5 = md5(jar);
        } catch (Exception e) {
            return new Result(false, "md5 failed: " + e.getMessage());
        }

        if (marker.isFile()) {
            try {
                // Content is "<md5> <revision>". Both must match, so a jar
                // patched by different patch code is always re-patched (the old
                // format stored the bare md5, so any pre-r21 marker re-patches).
                final String prev = readText(marker).trim();
                if (prev.equals(md5 + " " + rev)) {
                    return new Result(false, "already patched (" + rev + ")");
                }
            } catch (Exception ignored) {}
        }

        final File tmp = new File(jar.getParentFile(), jar.getName() + ".glespatch.tmp");
        try {
            final int patched = transformJar(jar, tmp, boundUnpacker, forceLowMemUnpacker);
            if (patched == 0) {
                tmp.delete();
                return new Result(false, "patch targets not present in jar");
            }

            final String patchedMd5 = md5(tmp);
            if (!jar.delete()) {
                tmp.delete();
                return new Result(false, "could not replace jar (delete failed)");
            }
            if (!tmp.renameTo(jar)) {
                return new Result(false, "could not replace jar (rename failed)");
            }

            markerDir.mkdirs();
            writeText(marker, patchedMd5 + " " + rev);

            final StringBuilder msg = new StringBuilder();
            if ((patched & PATCHED_GLESMESH) != 0) {
                msg.append("GlesMesh.draw guard");
            }
            if ((patched & PATCHED_AUDIOTHREAD) != 0) {
                if (msg.length() > 0) msg.append(" + ");
                msg.append("AudioThread reinit loop stopped");
            }
            if ((patched & PATCHED_GLESAPI_DEBUG) != 0) {
                if (msg.length() > 0) msg.append(" + ");
                msg.append("GL debug filter fixed (KHR don't-care)");
            }
            if ((patched & PATCHED_UNPACKER) != 0) {
                if (msg.length() > 0) msg.append(" + ");
                msg.append("Unpacker I/O bounded to ").append(UNPACK_THREADS);
            }
            if ((patched & PATCHED_CONFIG) != 0) {
                if (msg.length() > 0) msg.append(" + ");
                msg.append("Config.lowMemoryUnpacker forced true");
            }
            if ((patched & PATCHED_ISOREADER) != 0) {
                if (msg.length() > 0) msg.append(" + ");
                msg.append("IsoReader readFully/seek");
            }
            if ((patched & PATCHED_RENDERENGINE) != 0) {
                if (msg.length() > 0) msg.append(" + ");
                msg.append(ENABLE_DEPTH_FLOAT_PATCH
                        ? "depth texture FLOAT (desktop parity)"
                        : "UI depth isolate (2D over 3D)");
                if (ENABLE_DECOUPLE_BATTLE) {
                    msg.append(" + battle projection decoupled");
                }
            }
            if ((patched & PATCHED_GLESTEXTURE) != 0) {
                if (msg.length() > 0) msg.append(" + ");
                msg.append("depth internal format DEPTH_COMPONENT32F");
            }
            if ((patched & PATCHED_PS1DEPTH) != 0) {
                if (msg.length() > 0) msg.append(" + ");
                msg.append("PS1 depth forced (all TMD)");
            }
            if ((patched & PATCHED_RENDERBATCH) != 0) {
                if (msg.length() > 0) msg.append(" + ");
                msg.append(ENABLE_DECOUPLE_BATTLE
                        ? "projections = legacy path (widescreen option live)"
                        : "projection forced LEGACY (probe)");
            }
            if ((patched & PATCHED_GLESAPI) != 0 || (patched & PATCHED_SCISSORSTACK) != 0) {
                if (msg.length() > 0) msg.append(" + ");
                msg.append("widescreen gates = legacy (viewport/scissor)");
            }
            if ((patched & PATCHED_TRANSFORMATIONS) != 0) {
                if (msg.length() > 0) msg.append(" + ");
                msg.append("screen space = 0-based (target arrow)");
            }
            if ((patched & PATCHED_TEXT_SCISSOR) != 0) {
                if (msg.length() > 0) msg.append(" + ");
                msg.append("glyph scissors off (test)");
            }
            if ((patched & PATCHED_UPDATER) != 0) {
                if (msg.length() > 0) msg.append(" + ");
                msg.append("in-game updater disabled");
            }
            if ((patched & PATCHED_GENERICSPRITE) != 0) {
                if (msg.length() > 0) msg.append(" + ");
                msg.append("effect sprites opaque (test)");
            }
            return new Result(true, msg.toString());
        } catch (Throwable t) {
            tmp.delete();
            return new Result(false, "patch failed: " + t);
        }
    }

    /**
     * Updater: replace {@code check(Consumer)} with a straight
     * {@code onComplete.accept(null); return;}, so the launcher-deployed game
     * never offers to install the desktop build over itself.
     */
    private static byte[] transformUpdater(final byte[] input) {
        final ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"check".equals(name) || !"(Ljava/util/function/Consumer;)V".equals(descriptor)) {
                    return delegate;
                }

                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitCode() {
                        delegate.visitCode();
                        delegate.visitVarInsn(Opcodes.ALOAD, 1);
                        delegate.visitInsn(Opcodes.ACONST_NULL);
                        delegate.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                                "java/util/function/Consumer", "accept",
                                "(Ljava/lang/Object;)V", true);
                        delegate.visitInsn(Opcodes.RETURN);
                        delegate.visitMaxs(2, 2);
                        delegate.visitEnd();
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private static int transformJar(final File in, final File out,
                                    final boolean boundUnpacker, final boolean forceLowMemUnpacker) throws Exception {
        int patched = 0;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(in));
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            ZipEntry entry;
            final byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                final ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                zos.putNextEntry(copy);

                if (GLESMESH_ENTRY.equals(entry.getName())) {
                    patched |= PATCHED_GLESMESH;
                    zos.write(transformGlesMesh(readAll(zis)));
                } else if (AUDIOTHREAD_ENTRY.equals(entry.getName())) {
                    patched |= PATCHED_AUDIOTHREAD;
                    zos.write(transformAudioThread(readAll(zis)));
                } else if (GLESAPI_ENTRY.equals(entry.getName())) {
                    // GlesApi needs BOTH transforms, so they cannot be
                    // separate branches of the else-if chain: two entries
                    // with the same name means one of the transforms is
                    // silently dropped. That is exactly what happened in r23
                    // -- the KHR_debug-filter branch shadowed the branch
                    // that rewrote the widescreen gates, and the battle HUD
                    // portraits went back to being wrong with nothing in the
                    // log to say why. See PATCH_TARGETS for the guard.
                    byte[] glesApi = readAll(zis);
                    if (ENABLE_DECOUPLE_WIDESCREEN) {
                        patched |= PATCHED_GLESAPI;
                        glesApi = transformWidescreenGates(glesApi, false);
                    }
                    patched |= PATCHED_GLESAPI_DEBUG;
                    glesApi = transformGlesApiDebug(glesApi);
                    zos.write(glesApi);
                } else if (boundUnpacker && UNPACKER_ENTRY.equals(entry.getName())) {
                    patched |= PATCHED_UNPACKER;
                    zos.write(transformUnpacker(readAll(zis)));
                } else if (forceLowMemUnpacker && CONFIG_ENTRY.equals(entry.getName())) {
                    patched |= PATCHED_CONFIG;
                    zos.write(transformConfigForceLowMem(readAll(zis)));
                } else if (ISOREADER_ENTRY.equals(entry.getName())) {
                    patched |= PATCHED_ISOREADER;
                    zos.write(transformIsoReader(readAll(zis)));
                } else if ((ENABLE_RENDERENGINE_PATCH || ENABLE_DEPTH_FLOAT_PATCH || ENABLE_DECOUPLE_BATTLE)
                        && RENDERENGINE_ENTRY.equals(entry.getName())) {
                    patched |= PATCHED_RENDERENGINE;
                    zos.write(transformRenderEngine(readAll(zis)));
                } else if (ENABLE_EFFECT_OPAQUE_PATCH && GENERICSPRITE_ENTRY.equals(entry.getName())) {
                    patched |= PATCHED_GENERICSPRITE;
                    zos.write(transformGenericSprite(readAll(zis)));
                } else if (ENABLE_DEPTH_FLOAT_PATCH && GLESTEXTURE_ENTRY.equals(entry.getName())) {
                    patched |= PATCHED_GLESTEXTURE;
                    zos.write(transformGlesTextureDepth(readAll(zis)));
                } else if (ENABLE_FORCE_PS1DEPTH
                        && (SHADEROPTS_TMD_ENTRY.equals(entry.getName())
                            || SHADEROPTS_BTMD_ENTRY.equals(entry.getName()))) {
                    patched |= PATCHED_PS1DEPTH;
                    zos.write(transformForcePs1Depth(readAll(zis)));
                } else if ((ENABLE_FORCE_LEGACY || ENABLE_DECOUPLE_BATTLE)
                        && RENDERBATCH_ENTRY.equals(entry.getName())) {
                    patched |= PATCHED_RENDERBATCH;
                    zos.write(transformRenderBatch(readAll(zis)));
                } else if (ENABLE_DECOUPLE_WIDESCREEN && SCISSORSTACK_ENTRY.equals(entry.getName())) {
                    patched |= PATCHED_SCISSORSTACK;
                    zos.write(transformWidescreenGates(readAll(zis), true));
                } else if (ENABLE_SCREENSPACE_FIX && TRANSFORMATIONS_ENTRY.equals(entry.getName())) {
                    patched |= PATCHED_TRANSFORMATIONS;
                    zos.write(transformTransformations(readAll(zis)));
                } else if (ENABLE_GLYPH_SCISSOR_BYPASS && TEXT_ENTRY.equals(entry.getName())) {
                    patched |= PATCHED_TEXT_SCISSOR;
                    zos.write(transformTextGlyphScissors(readAll(zis)));
                } else if (ENABLE_UPDATER_DISABLE && UPDATER_ENTRY.equals(entry.getName())) {
                    patched |= PATCHED_UPDATER;
                    zos.write(transformUpdater(readAll(zis)));
                } else {
                    int n;
                    while ((n = zis.read(buf)) != -1) {
                        zos.write(buf, 0, n);
                    }
                }

                zos.closeEntry();
                zis.closeEntry();
            }
        }
        return patched;
    }

    /**
     * GlesMesh: two related fixes for the Adreno driver's
     * "unable to generate a triangle primitive because there are less than 3
     * vertices" complaint.
     *
     * <ol>
     *   <li><b>Clamp the draw count to whole primitives.</b> The driver
     *   validates every primitive it is handed. A triangle list whose vertex
     *   count is not a multiple of 3 (or a multiple of 6 for
     *   {@code GL_TRIANGLES_ADJACENCY}) ends in a partial primitive with fewer
     *   than three vertices, and the driver reports that as a high-severity
     *   debug message. The trailing vertices are discarded by the driver
     *   anyway, so rounding the count <i>down</i> to the nearest whole
     *   primitive is rendering-neutral and silences the driver.
     *
     *   <p>The clamp is injected at the {@code glDrawArrays} call site as
     *   {@code clampCount(count, mode)}: the count is already on the operand
     *   stack there, so pushing {@code this.mode} on top of it hands the
     *   helper exactly the two values it needs without touching any local.
     *
     *   <li><b>Skip degenerate draws outright.</b> A count below the primitive
     *   size clamps to zero, which is already a legal no-op draw, so the
     *   early return at method entry is kept only for the
     *   {@code GL_TRIANGLE_STRIP} case, where a count below 3 cannot be
     *   rounded down to a whole primitive.
     * </ol>
     */
    private static byte[] transformGlesMesh(final byte[] input) {
        final ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                // The guard only merges the initial frame (no reference-type
                // merges in draw()), so Object is always safe here.
                return "java/lang/Object";
            }
        };

        final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"draw".equals(name)) {
                    return mv;
                }

                final boolean full = "()V".equals(descriptor);
                final boolean ranged = "(II)V".equals(descriptor);
                if (!full && !ranged) {
                    return mv;
                }

                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        final Label triangle = new Label();
                        final Label body = new Label();

                        modeCheck(this, triangle, GL_TRIANGLES);
                        modeCheck(this, triangle, GL_TRIANGLE_STRIP);
                        modeCheck(this, triangle, GL_TRIANGLES_ADJACENCY);
                        super.visitJumpInsn(Opcodes.GOTO, body);

                        super.visitLabel(triangle);
                        // count == 0 is the "draw everything" sentinel
                        // (GlesMesh.draw(int,int) delegates to draw()); it must
                        // fall through to the real body. Only a positive count
                        // below 3 is the degenerate draw we want to skip.
                        loadCount(this, full);
                        super.visitJumpInsn(Opcodes.IFEQ, body);
                        loadCount(this, full);
                        super.visitInsn(Opcodes.ICONST_3);
                        super.visitJumpInsn(Opcodes.IF_ICMPGE, body);
                        super.visitInsn(Opcodes.RETURN);
                        super.visitLabel(body);
                    }

                    @Override
                    public void visitMethodInsn(final int opcode, final String owner, final String methodName,
                                                final String methodDescriptor, final boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengles/GLES20".equals(owner)
                                && "glDrawArrays".equals(methodName)
                                && "(III)V".equals(methodDescriptor)) {
                            // stack: [mode, first, count] -> push this.mode so
                            // the helper sees (count, mode) and returns the
                            // clamped count, leaving [mode, first, clamped].
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, GLESMESH_OWNER, FIELD_MODE, "I");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GLESMESH_OWNER,
                                    CLAMP_COUNT, "(II)I", false);
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, itf);
                    }
                };
            }

            @Override
            public void visitEnd() {
                emitClampCount(writer);
                super.visitEnd();
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /**
     * Emit {@code static int clampCount(int count, int mode)} into
     * {@code GlesMesh}. Rounds a triangle draw count down to a whole number
     * of primitives:
     * <ul>
     *   <li>{@code GL_TRIANGLES} -- multiple of 3;</li>
     *   <li>{@code GL_TRIANGLES_ADJACENCY} -- multiple of 6;</li>
     *   <li>{@code GL_TRIANGLE_STRIP} -- at least 3 vertices, otherwise 0.</li>
     * </ul>
     * Any other primitive type is passed through unchanged.
     */
    private static void emitClampCount(final ClassWriter writer) {
        final MethodVisitor mv = writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                CLAMP_COUNT, "(II)I", null, null);
        mv.visitCode();

        // count - count % 3  (GL_TRIANGLES)
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitIntInsn(Opcodes.BIPUSH, GL_TRIANGLES);
        final Label notTriangles = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, notTriangles);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.DUP);
        mv.visitIntInsn(Opcodes.BIPUSH, GL_TRIANGLES_VERTS);
        mv.visitInsn(Opcodes.IREM);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitInsn(Opcodes.IRETURN);

        // count - count % 6  (GL_TRIANGLES_ADJACENCY)
        mv.visitLabel(notTriangles);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitIntInsn(Opcodes.BIPUSH, GL_TRIANGLES_ADJACENCY);
        final Label notAdjacency = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, notAdjacency);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.DUP);
        mv.visitIntInsn(Opcodes.BIPUSH, GL_TRIANGLES_ADJACENCY_VERTS);
        mv.visitInsn(Opcodes.IREM);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitInsn(Opcodes.IRETURN);

        // count < 3 ? 0 : count  (GL_TRIANGLE_STRIP)
        mv.visitLabel(notAdjacency);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitIntInsn(Opcodes.BIPUSH, GL_TRIANGLE_STRIP);
        final Label notStrip = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, notStrip);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitIntInsn(Opcodes.BIPUSH, GL_TRIANGLE_STRIP_MIN);
        final Label stripOk = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, stripOk);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(stripOk);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.IRETURN);

        // any other primitive type: unchanged
        mv.visitLabel(notStrip);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    /**
     * AudioThread: stop the "Device does not support OpenAL10" path from
     * putting the audio thread into a permanent retry loop.
     *
     * <p>What happens on this device: the bundled {@code libopenal.so} is a
     * stock OpenAL Soft build, while LWJGL expects its own fork of OpenAL
     * Soft, which additionally exports the {@code al*Direct} entry points.
     * LWJGL resolves <b>every</b> AL entry point through {@code alGetProcAddress}
     * and its {@code ALCapabilities.check_AL10()} insists on all 109 of them,
     * so the missing {@code alGetProcAddressDirect} alone flips
     * {@code alCapabilities.OpenAL10} to false. The launcher's own
     * {@code SCAudioProbe} shows it exactly:
     * {@code alGetProcAddress sweep: 108/109 resolved, missing=alGetProcAddressDirect}.
     *
     * <p>The game then treats that flag as "this device cannot do OpenAL 1.0":
     * it logs a warning, tears the device and context down and sets
     * {@code paused = true}. The device-poll scheduler wakes every two
     * seconds, sees {@code paused}, and re-runs the whole initialisation --
     * forever. The result on device is a fresh OpenAL context every two
     * seconds, the music sequence restarted on every cycle, and ~25% of the
     * entire session log being audio-retry spam (which is also what pushed
     * 9.5 MB of the launcher log out of the session reports).
     *
     * <p>The game only ever calls the plain AL 1.0 entry points
     * ({@code alBufferData}, {@code alGenSources}, {@code alSourcePlay}, ...)
     * and never a {@code *Direct} variant, and all of those resolve fine, so
     * the flag is a false negative. The patch therefore redirects the
     * "OpenAL10 is false" branch to the success path -- {@code paused = false},
     * {@code notify()}, return -- and logs one line explaining why, so the
     * device report still shows that the fallback was taken.
     */
    private static byte[] transformAudioThread(final byte[] input) {
        final ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                return "java/lang/Object";
            }
        };

        final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"initInternal".equals(name) || !"()V".equals(descriptor)) {
                    return mv;
                }

                return new MethodVisitor(Opcodes.ASM9, mv) {
                    private boolean sawOpenAL10;

                    @Override
                    public void visitFieldInsn(final int opcode, final String owner, final String field,
                                               final String fieldDesc) {
                        super.visitFieldInsn(opcode, owner, field, fieldDesc);
                        if (opcode == Opcodes.GETFIELD
                                && ALCAPS_OWNER.equals(owner)
                                && ALCAPS_OPENAL10.equals(field)
                                && "Z".equals(fieldDesc)) {
                            sawOpenAL10 = true;
                        }
                    }

                    @Override
                    public void visitJumpInsn(final int opcode, final Label label) {
                        if (!sawOpenAL10 || opcode != Opcodes.IFEQ) {
                            super.visitJumpInsn(opcode, label);
                            return;
                        }
                        sawOpenAL10 = false;

                        // Original:  IFEQ <teardown-and-pause>
                        // Patched:    IFEQ <logAndContinue>
                        //             GOTO  <success>
                        //   logAndContinue:
                        //             LOGGER.warn("...")
                        //   success:                       <- the game's own
                        //             paused = false;           success path
                        //             notify();
                        //             return;
                        final Label logAndContinue = new Label();
                        final Label success = new Label();

                        super.visitJumpInsn(Opcodes.IFEQ, logAndContinue);
                        super.visitJumpInsn(Opcodes.GOTO, success);

                        super.visitLabel(logAndContinue);
                        super.visitFieldInsn(Opcodes.GETSTATIC, AUDIOTHREAD_OWNER,
                                LOGGER_FIELD, LOGGER_DESC);
                        super.visitLdcInsn("OpenAL10 capability flag not set by the OpenAL backend "
                                + "(alGetProcAddressDirect is missing), continuing with the AL entry "
                                + "points that are available instead of retrying forever.");
                        super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/apache/logging/log4j/Logger",
                                "warn", "(Ljava/lang/String;)V", true);

                        super.visitLabel(success);
                    }
                };
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /**
     * GlesApi: repair the KHR_debug message filter and mute the Adreno
     * driver's "Namespace collision detected, using slow path" hint.
     *
     * <p>Two problems in one place:
     *
     * <ol>
     *   <li>The game passes {@code GLES20.GL_DONT_CARE} (0x1100, the GLES2
     *   <i>hint</i> constant) as the source and type filter of
     *   {@code glDebugMessageControl}. KHR_debug's "don't care" is 0xFFFF.
     *   0x1100 is neither a valid source/type/severity nor "don't care", so
     *   the driver answers {@code INVALID_ENUM} and changes nothing: all four
     *   of the game's filter calls were no-ops and the driver kept its
     *   default state ("everything except NOTIFICATION"). This patch uses
     *   0xFFFF, so the game gets the HIGH-only filter it asked for.
     *
     *   <li>Adreno's "Namespace collision detected, using slow path"
     *   ({@code [Other/Performance] ID 2147483647}) is a known benign
     *   driver performance hint -- it is not an error and does not affect
     *   correctness, but it fires in bursts during heavy mesh allocation and
     *   floods the log (65 + 52 occurrences in two device sessions) and pops
     *   an on-screen notification. It is muted with one extra
     *   {@code glDebugMessageControl(GL_DONT_CARE, GL_DEBUG_TYPE_PERFORMANCE,
     *   GL_DONT_CARE, ..., false)} call.
     * </ol>
     */
    private static byte[] transformGlesApiDebug(final byte[] input) {
        // Pass 1: which methods contain the debug-control calls, and which one
        // is the debug callback itself?
        //
        // The source and type filters are pushed as constants *before* the
        // glDebugMessageControl call, so the streaming API cannot tell them
        // apart from the severity filter at push time -- but in the shipped
        // jar every `sipush 4352` in those methods is a source or type filter
        // (the severities are all > 32767 and are pushed with ldc), so
        // scoping the rewrite to the methods that own the calls is enough.
        //
        // The debug callback is the KHR_debug lambda, which compiles to a
        // synthetic method. It is the only caller of the private
        // GlesApi.getObjectName(II) helper (it resolves the shader/texture/
        // VAO/VBO names for the error dump), so that call is what identifies
        // it -- far more robust than guessing the lambda index.
        final java.util.Set<String> debugMethods = new java.util.HashSet<>();
        final java.util.Set<String> callbackMethods = new java.util.HashSet<>();
        new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(final int opcode, final String owner, final String methodName,
                                                final String methodDescriptor, final boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC && GLES32_OWNER.equals(owner)) {
                            if ("glDebugMessageControl".equals(methodName)
                                    || "glDebugMessageCallback".equals(methodName)) {
                                debugMethods.add(name + descriptor);
                            }
                        }
                        if ((opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESPECIAL)
                                && GLESAPI_OWNER.equals(owner)
                                && GET_OBJECT_NAME.equals(methodName)
                                && GET_OBJECT_NAME_DESC.equals(methodDescriptor)) {
                            callbackMethods.add(name + descriptor);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        final ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                return "java/lang/Object";
            }
        };

        final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                final boolean isDebugSetup = debugMethods.contains(name + descriptor);
                final boolean isCallback = callbackMethods.contains(name + descriptor);
                if (!isDebugSetup && !isCallback) {
                    return mv;
                }

                return new MethodVisitor(Opcodes.ASM9, mv) {
                    private final int idLocal = (access & Opcodes.ACC_STATIC) == 0 ? 3 : 2;

                    @Override
                    public void visitCode() {
                        super.visitCode();
                        if (isCallback) {
                            // Belt-and-braces for drivers that ignore the
                            // type/ID filter below: the Adreno performance
                            // hint carries the 0x7FFFFFFF "no ID" sentinel.
                            // Dropping it at the callback's first instruction
                            // avoids the six glGetObjectLabel queries, the
                            // eight log lines, the Throwable and the popup,
                            // all of which run synchronously inside the GL
                            // call that raised the message.
                            final Label notNoId = new Label();
                            super.visitVarInsn(Opcodes.ILOAD, this.idLocal);
                            super.visitLdcInsn(Integer.valueOf(ADRENO_NO_MESSAGE_ID));
                            super.visitJumpInsn(Opcodes.IF_ICMPNE, notNoId);
                            super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(notNoId);
                        }
                    }

                    @Override
                    public void visitIntInsn(final int opcode, final int operand) {
                        if (opcode == Opcodes.SIPUSH && operand == GLES2_DONT_CARE_HINT) {
                            // GLES20.GL_DONT_CARE (0x1100) -> KHR_debug's
                            // GL_DONT_CARE (0xFFFF). 0x1100 is invalid for
                            // glDebugMessageControl, so the driver's reply was
                            // INVALID_ENUM and the filter never took effect.
                            super.visitLdcInsn(Integer.valueOf(GLES_DONT_CARE_KHR));
                            return;
                        }
                        super.visitIntInsn(opcode, operand);
                    }

                    @Override
                    public void visitMethodInsn(final int opcode, final String owner, final String methodName,
                                                final String methodDescriptor, final boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && GLES32_OWNER.equals(owner)
                                && "glDebugMessageCallback".equals(methodName)
                                && "(Lorg/lwjgl/opengles/GLDebugMessageCallbackI;J)V".equals(methodDescriptor)) {
                            // Apply the mutes *after* the game's last filter
                            // call. KHR_debug's last matching rule wins, and
                            // the game enables every HIGH-severity message
                            // right before installing the callback -- which
                            // is what silently undid the r24 mute (it was
                            // inserted after the first filter instead).
                            emitGlDebugMutes(mv);
                        }

                        if (isCallback
                                && opcode == Opcodes.INVOKESTATIC
                                && GAMEOVERLAY_OWNER.equals(owner)
                                && "addNotification".equals(methodName)
                                && NOTIFY_DESC.equals(methodDescriptor)) {
                            // Route the on-screen GL error popup through
                            // notifyGl(), which honours the launcher's
                            // "mute in-game error popups" setting. The
                            // errors are still logged in full; only the
                            // screen-covering popup is suppressed.
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GLESAPI_OWNER,
                                    NOTIFY_GL, NOTIFY_DESC, false);
                            return;
                        }

                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, itf);
                    }
                };
            }

            @Override
            public void visitEnd() {
                emitNotifyGl(writer);
                super.visitEnd();
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /**
     * Emit the KHR_debug filters that silence Adreno's benign performance
     * hints. Must run after the game's final {@code glDebugMessageControl}
     * call: KHR_debug applies the <b>last</b> matching rule, and the game's
     * "enable every HIGH severity message" call comes last.
     *
     * <p>Three filters are emitted: PERFORMANCE and OTHER by type (Adreno has
     * tagged the namespace-collision hint as both at different driver
     * versions), and the 0x7FFFFFFF no-ID sentinel by ID. The ID filter is
     * also mirrored inside the callback, in case a driver ignores it.
     */
    private static void emitGlDebugMutes(final MethodVisitor mv) {
        emitTypeMute(mv, GL_DEBUG_TYPE_PERFORMANCE);
        emitTypeMute(mv, GL_DEBUG_TYPE_OTHER);

        mv.visitLdcInsn(Integer.valueOf(GLES_DONT_CARE_KHR));
        mv.visitLdcInsn(Integer.valueOf(GLES_DONT_CARE_KHR));
        mv.visitLdcInsn(Integer.valueOf(GLES_DONT_CARE_KHR));
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitIntInsn(Opcodes.NEWARRAY, T_INT);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLdcInsn(Integer.valueOf(ADRENO_NO_MESSAGE_ID));
        mv.visitInsn(Opcodes.IASTORE);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, GLES32_OWNER,
                "glDebugMessageControl", "(III[IZ)V", false);
    }

    /** {@code glDebugMessageControl(DONT_CARE, type, DONT_CARE, {}, false)}. */
    private static void emitTypeMute(final MethodVisitor mv, final int type) {
        mv.visitLdcInsn(Integer.valueOf(GLES_DONT_CARE_KHR));
        mv.visitLdcInsn(Integer.valueOf(type));
        mv.visitLdcInsn(Integer.valueOf(GLES_DONT_CARE_KHR));
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitIntInsn(Opcodes.NEWARRAY, T_INT);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, GLES32_OWNER,
                "glDebugMessageControl", "(III[IZ)V", false);
    }

    /**
     * Emit {@code private static void notifyGl(int, TextComponent)} into
     * {@code GlesApi}.
     *
     * <p>The game's KHR_debug callback raises a five-second on-screen toast for
     * every high-severity driver message. On the Adreno driver those are
     * routine ("Namespace collision detected, using slow path", "All
     * allocations busy. Blocking on oldest busy.", a degenerate triangle), and
     * the toast is drawn over the whole play area -- during a battle it
     * covers the fight.
     *
     * <p>This helper replaces that call. The message is still written to the
     * log (with the full GL context and a stack trace), so nothing is lost
     * for diagnosis; only the on-screen popup is suppressed, and only when the
     * player asked for it.
     */
    private static void emitNotifyGl(final ClassWriter writer) {
        final MethodVisitor mv = writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                NOTIFY_GL, NOTIFY_DESC, null, null);
        mv.visitCode();

        mv.visitLdcInsn(MUTE_POPUPS_PROPERTY);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean",
                "getBoolean", "(Ljava/lang/String;)Z", false);
        final Label muted = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, muted);

        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, GAMEOVERLAY_OWNER,
                "addNotification", NOTIFY_DESC, false);

        mv.visitLabel(muted);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    /**
     * Unpacker: rewrite every {@code Executors.newVirtualThreadPerTaskExecutor()}
     * to a bounded {@code Executors.newFixedThreadPool(UNPACK_THREADS)}. Only a
     * single call instruction is replaced by two (push int + call), and no
     * branches/targets are added, so the existing StackMapTable frames remain
     * valid — COMPUTE_MAXS alone is correct and avoids the reference-merge
     * risks of COMPUTE_FRAMES on this large method.
     */
    private static byte[] transformUnpacker(final byte[] input) {
        final ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(final int opcode, final String owner, final String methodName,
                                                final String methodDescriptor, final boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && EXECUTORS.equals(owner)
                                && "newVirtualThreadPerTaskExecutor".equals(methodName)) {
                            super.visitIntInsn(Opcodes.BIPUSH, UNPACK_THREADS);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, EXECUTORS,
                                    "newFixedThreadPool", "(I)" + EXECUTOR_SERVICE, false);
                        } else {
                            super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                        }
                    }
                };
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /**
     * Config: replace {@code lowMemoryUnpacker()} with {@code return true}.
     *
     * <p>The original body (which reads the {@code low_memory_unpacker}
     * property) is dropped entirely; the delegate is only asked for the method
     * header/annotations and a final {@code visitEnd}. COMPUTE_MAXS recomputes
     * the trivial max stack for {@code iconst_1; ireturn}.
     */
    private static byte[] transformConfigForceLowMem(final byte[] input) {
        final ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!CONFIG_METHOD.equals(name) || !CONFIG_DESCRIPTOR.equals(descriptor)) {
                    return mv;
                }

                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitInsn(Opcodes.ICONST_1);
                        super.visitInsn(Opcodes.IRETURN);
                    }

                    // Swallow the original body so nothing is emitted after the
                    // injected return. Metadata/annotations still forward.
                    @Override public void visitInsn(final int opcode) {}
                    @Override public void visitIntInsn(final int opcode, final int operand) {}
                    @Override public void visitVarInsn(final int opcode, final int varIndex) {}
                    @Override public void visitTypeInsn(final int opcode, final String type) {}
                    @Override public void visitFieldInsn(final int opcode, final String owner, final String fieldName, final String fieldDescriptor) {}
                    @Override public void visitMethodInsn(final int opcode, final String owner, final String methodName, final String methodDescriptor, final boolean isInterface) {}
                    @Override public void visitInvokeDynamicInsn(final String name, final String descriptor, final org.objectweb.asm.Handle bootstrapMethod, final Object... bootstrapArgs) {}
                    @Override public void visitJumpInsn(final int opcode, final Label label) {}
                    @Override public void visitLabel(final Label label) {}
                    @Override public void visitLdcInsn(final Object value) {}
                    @Override public void visitIincInsn(final int varIndex, final int increment) {}
                    @Override public void visitTableSwitchInsn(final int min, final int max, final Label dflt, final Label... labels) {}
                    @Override public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {}
                    @Override public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {}
                    @Override public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {}
                    @Override public void visitLocalVariable(final String name, final String descriptor, final String signature, final Label start, final Label end, final int index) {}
                    @Override public void visitLineNumber(final int line, final Label start) {}
                    @Override public void visitFrame(final int type, final int numLocal, final Object[] local, final int numStack, final Object[] stack) {}
                };
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /**
     * IsoReader: Android's {@code RandomAccessFile.skipBytes} is lseek-backed
     * and can report a short/false count, and {@code read(byte[])} permits a
     * partial read — upstream treats both as exact. Rewrite the three
     * sequential-cursor helpers to {@code seek}/{@code readFully}, which are
     * exact equivalents here and avoid the ISO-rip read storm on Android flash.
     * (Ported from Severed-Chains-Android's staged {@code IsoReader} edits.)
     *
     * <p>Straight-line bodies only, so {@code COMPUTE_MAXS} suffices and no
     * StackMapTable frames are needed.
     */
    private static byte[] transformIsoReader(final byte[] input) {
        final ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                if ("advance".equals(name) && "(I)V".equals(descriptor)) {
                    return new BodyReplacer(mv, m -> {
                        // this.file.seek(this.file.getFilePointer() + amount)
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitFieldInsn(Opcodes.GETFIELD, ISOREADER_OWNER, "file", "Ljava/io/RandomAccessFile;");
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitFieldInsn(Opcodes.GETFIELD, ISOREADER_OWNER, "file", "Ljava/io/RandomAccessFile;");
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RAF_OWNER, "getFilePointer", "()J", false);
                        m.visitVarInsn(Opcodes.ILOAD, 1);
                        m.visitInsn(Opcodes.I2L);
                        m.visitInsn(Opcodes.LADD);
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RAF_OWNER, "seek", "(J)V", false);
                        m.visitInsn(Opcodes.RETURN);
                    });
                }

                if ("read".equals(name) && "([B)V".equals(descriptor)) {
                    return new BodyReplacer(mv, m -> {
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitFieldInsn(Opcodes.GETFIELD, ISOREADER_OWNER, "file", "Ljava/io/RandomAccessFile;");
                        m.visitVarInsn(Opcodes.ALOAD, 1);
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RAF_OWNER, "readFully", "([B)V", false);
                        m.visitInsn(Opcodes.RETURN);
                    });
                }

                if ("read".equals(name) && "([BII)V".equals(descriptor)) {
                    return new BodyReplacer(mv, m -> {
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitFieldInsn(Opcodes.GETFIELD, ISOREADER_OWNER, "file", "Ljava/io/RandomAccessFile;");
                        m.visitVarInsn(Opcodes.ALOAD, 1);
                        m.visitVarInsn(Opcodes.ILOAD, 2);
                        m.visitVarInsn(Opcodes.ILOAD, 3);
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RAF_OWNER, "readFully", "([BII)V", false);
                        m.visitInsn(Opcodes.RETURN);
                    });
                }

                return mv;
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /**
     * GenericSpriteEffect24: neuter the {@code (flags_00 & 0x40000000) != 0}
     * translucency check so the sprite is not queued as an additive translucent
     * model (see {@link #ENABLE_EFFECT_OPAQUE_PATCH}). Implemented as a
     * single-constant swap (mask {@code 0x40000000} → {@code 0}); the field is
     * only read for this check, so the sprite then renders in the opaque ortho
     * pass. Straight-line method, so {@code COMPUTE_MAXS} suffices.
     */
    private static byte[] transformGenericSprite(final byte[] input) {
        final ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitLdcInsn(final Object value) {
                        if (value instanceof Integer && ((Integer)value).intValue() == 0x4000_0000) {
                            super.visitLdcInsn(0);
                        } else {
                            super.visitLdcInsn(value);
                        }
                    }
                };
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /**
     * GlesTexture: map the internal format {@code DEPTH_COMPONENT} to
     * {@code GL_DEPTH_COMPONENT32F} in the texture constructor, so the depth
     * texture created by {@code RenderEngine} (with {@code dataType FLOAT}, see
     * {@link #ENABLE_DEPTH_FLOAT_PATCH}) is the valid sized float format rather
     * than the GLES-illegal unsized {@code GL_DEPTH_COMPONENT} + {@code FLOAT}.
     *
     * <p>The internal-format switch body pushes the value via {@code SIPUSH
     * 6402} for the {@code DEPTH_COMPONENT} case; it is replaced by an
     * {@code LDC} of {@code 0x8CAC} ({@code 6402} only ever appears there in the
     * constructor). Straight-line, {@code COMPUTE_MAXS} only.
     */
    private static byte[] transformGlesTextureDepth(final byte[] input) {
        final ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"<init>".equals(name)) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitIntInsn(final int opcode, final int operand) {
                        if (opcode == Opcodes.SIPUSH && operand == GL_DEPTH_COMPONENT) {
                            super.visitLdcInsn(GL_DEPTH_COMPONENT32F);
                        } else {
                            super.visitIntInsn(opcode, operand);
                        }
                    }
                };
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /**
     * ShaderOptionsTmd / ShaderOptionsBattleTmd: replace
     * {@code usePs1Depth(boolean)} with a body that always sets the uniform to
     * 1 (see {@link #ENABLE_FORCE_PS1DEPTH}). Straight-line, COMPUTE_MAXS only.
     */
    private static byte[] transformForcePs1Depth(final byte[] input) {
        final ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            private String owner;

            @Override
            public void visit(final int version, final int access, final String name, final String signature,
                              final String superName, final String[] interfaces) {
                this.owner = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"usePs1Depth".equals(name) || !descriptor.startsWith("(Z)L")) {
                    return mv;
                }
                final String o = this.owner;
                return new BodyReplacer(mv, m -> {
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitFieldInsn(Opcodes.GETFIELD, o, "usePs1Depth", "L" + SHADERUNIFORMINT_OWNER + ";");
                    m.visitInsn(Opcodes.ICONST_1);
                    m.visitMethodInsn(Opcodes.INVOKEINTERFACE, SHADERUNIFORMINT_OWNER, "set", "(I)V", true);
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitInsn(Opcodes.ARETURN);
                });
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /**
     * RenderBatch patches: (a) {@link #ENABLE_FORCE_LEGACY} — force
     * {@code setRenderMode} to LEGACY; (b) {@link #ENABLE_DECOUPLE_BATTLE} —
     * force {@code updateProjections()}'s {@code renderMode == PERSPECTIVE}
     * test to fail, so the game's own LEGACY branch builds both projections.
     *
     * <p>That branch is the whole point: it is option-aware
     * ({@code submap_widescreen_mode == EXPANDED} → true widescreen,
     * {@code expectedWidth = 368}, {@code widescreenOrthoOffsetX} set) and it
     * keeps the 2D ortho and the 3D ortho consistent with each other. The v1
     * patch instead overwrote the 3D matrix with a hardcoded 4:3 ortho, which
     * rendered correct models but ignored the widescreen setting entirely.
     *
     * <p>The rewrite is a two-instruction, structure-preserving swap: drop the
     * {@code renderMode} reference the {@code GETFIELD} just pushed and push
     * {@code RenderMode.LEGACY} instead, so the following
     * {@code IF_ACMPNE} against {@code PERSPECTIVE} always takes the legacy
     * branch. The (now unreachable) PERSPECTIVE code is left in place, so the
     * class stays verifiable.
     */
    private static byte[] transformRenderBatch(final byte[] input) {
        final ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                if (ENABLE_FORCE_LEGACY && "setRenderMode".equals(name)
                        && ("(L" + RENDERMODE_OWNER + ";)V").equals(descriptor)) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitVarInsn(final int opcode, final int varIndex) {
                            if (opcode == Opcodes.ALOAD && varIndex == 1) {
                                super.visitFieldInsn(Opcodes.GETSTATIC, RENDERMODE_OWNER, "LEGACY",
                                        "L" + RENDERMODE_OWNER + ";");
                            } else {
                                super.visitVarInsn(opcode, varIndex);
                            }
                        }
                    };
                }

                if (ENABLE_DECOUPLE_BATTLE && "updateProjections".equals(name) && "()V".equals(descriptor)) {
                    return new LegacyRenderMode(mv) {
                        /** Saw the widescreen branch's expectedWidth push. */
                        private boolean pendingExpanded;

                        @Override
                        public void visitCode() {
                            super.visitCode();
                            if (ENABLE_WIDESCREEN_DIAG) {
                                // Straight-line only (no new branch targets), so
                                // COMPUTE_MAXS stays valid. Emitted through the
                                // delegate so LegacyRenderMode can't rewrite the
                                // renderMode read here.
                                diagStr(mv, "SCWIDE updateProjections: mode=");
                                diagObjectField(mv, RENDERBATCH_OWNER, "renderMode", RENDERMODE_DESC);
                                diagStr(mv, "SCWIDE nativeW=");
                                diagIntField(mv, RENDERBATCH_OWNER, "nativeWidth");
                                diagStr(mv, "SCWIDE nativeH=");
                                diagIntField(mv, RENDERBATCH_OWNER, "nativeHeight");
                                diagStr(mv, "SCWIDE renderW=");
                                diagEngineInt(mv, "getRenderWidth");
                                diagStr(mv, "SCWIDE renderH=");
                                diagEngineInt(mv, "getRenderHeight");
                            }
                        }

                        @Override
                        public void visitIntInsn(final int opcode, final int operand) {
                            // The legacy branch sets expectedWidth = 368 for the
                            // retail "expanded submap" mode. In this port that
                            // value is only ever consumed by the scissor maths
                            // (GlesApi.scissor / ScissorStack.setRescale), which
                            // multiplies by expectedWidth/nativeWidth — so 368
                            // scales every scissor rectangle by 1.15 and clips
                            // the battle HUD portrait (and the HP/MP/SP labels)
                            // out of its own box, and clips every glyph's
                            // per-character scissor in the field, which is why
                            // Extended showed a text box with no text. The 2D
                            // ortho projection built right above is 0..w (no such
                            // factor), so the scissor must not have it either:
                            // keep the PS1 width at 320.
                            if (opcode == Opcodes.SIPUSH && operand == EXPECTED_WIDTH_FROM) {
                                this.pendingExpanded = true;
                                super.visitIntInsn(opcode, EXPECTED_WIDTH_TO);
                            } else {
                                super.visitIntInsn(opcode, operand);
                            }
                        }

                        @Override
                        public void visitFieldInsn(final int opcode, final String owner, final String fname,
                                                   final String fdesc) {
                            super.visitFieldInsn(opcode, owner, fname, fdesc);
                            if (this.pendingExpanded && opcode == Opcodes.PUTFIELD
                                    && RENDERBATCH_OWNER.equals(owner) && "expectedWidth".equals(fname)) {
                                this.pendingExpanded = false;
                                if (ENABLE_WIDESCREEN_DIAG) {
                                    diagStr(mv, "SCWIDE EXPANDED expectedWidth=");
                                    diagIntField(mv, RENDERBATCH_OWNER, "expectedWidth");
                                    diagStr(mv, "SCWIDE EXPANDED wsoOffset=");
                                    diagFloatField(mv, RENDERBATCH_OWNER, "widescreenOrthoOffsetX");
                                    diagStr(mv, "SCWIDE EXPANDED ortho=");
                                    diagObjectField(mv, RENDERBATCH_OWNER, "orthographicProjection",
                                            "Lorg/joml/Matrix4f;");
                                }
                            }
                        }
                    };
                }

                return mv;
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /**
     * Rewrites every {@code renderMode} read in the visited methods so it
     * yields {@link EngineState.RenderMode#LEGACY} instead — i.e. "resolve the
     * widescreen option the way the legacy path does", matching what
     * {@code RenderBatch.updateProjections} now builds.
     *
     * <p>Handles both spellings the game uses: the field
     * ({@code batch.renderMode}, seen on the stack as the receiver) and the
     * getter ({@code batch.getRenderMode()}). In each case the original
     * instruction's pushed value is popped and replaced with the constant, so
     * the surrounding stack shape and branch structure are unchanged.
     */
    private static class LegacyRenderMode extends MethodVisitor {
        LegacyRenderMode(final MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String desc) {
            if (opcode == Opcodes.GETFIELD && RENDERBATCH_OWNER.equals(owner) && "renderMode".equals(name)) {
                super.visitInsn(Opcodes.POP); // drop the batch reference
                super.visitFieldInsn(Opcodes.GETSTATIC, RENDERMODE_OWNER, "LEGACY", RENDERMODE_DESC);
            } else {
                super.visitFieldInsn(opcode, owner, name, desc);
            }
        }

        @Override
        public void visitMethodInsn(final int opcode, final String owner, final String name,
                                    final String desc, final boolean itf) {
            if (opcode == Opcodes.INVOKEVIRTUAL && RENDERBATCH_OWNER.equals(owner)
                    && "getRenderMode".equals(name) && ("()" + RENDERMODE_DESC).equals(desc)) {
                super.visitInsn(Opcodes.POP); // drop the batch reference
                super.visitFieldInsn(Opcodes.GETSTATIC, RENDERMODE_OWNER, "LEGACY", RENDERMODE_DESC);
            } else {
                super.visitMethodInsn(opcode, owner, name, desc, itf);
            }
        }
    }

    /**
     * {@link #ENABLE_SCREENSPACE_FIX}: remap the modern branch of
     * {@code Transformations.toScreenspace} from centred NDC coordinates to the
     * 0-based space the 2D ortho projection uses.
     *
     * <p>The method computes (modern branch):
     * <pre>
     *   out.x = (vec.x / vec.w / 2) * (nativeWidth * renderAspect / nativeAspectRatio)
     *   out.y = (-vec.y / vec.w / 2) * nativeHeight
     * </pre>
     * which yields -w/2..w/2 and -h/2..h/2. The 2D ortho projection is 0-based
     * (0..w, 0..h), so every consumer is off by half the screen.
     *
     * <p>The rewrite is purely arithmetic and shape-preserving:
     * <ul>
     *   <li>after each {@code fdiv} by 2, add 0.5 — turning the NDC [-1,1] into
     *       [0,1] before the extent multiply;</li>
     *   <li>after the x multiply, subtract
     *       {@code RENDERER.getWidescreenOrthoOffsetX()} — every 2D model gets
     *       that offset in {@code RenderBatch.queueOrthoModel}, so an element
     *       placed from a world coordinate (the battle target arrow, the
     *       floating damage numbers, the lens flare) would otherwise get it
     *       twice.</li>
     * </ul>
     * The LEGACY branch returns early and is left untouched: the GTE already
     * returns 0-based PS1 coordinates, which is what that branch's consumers
     * (title screen, submap) expect.
     */
    private static byte[] transformTransformations(final byte[] input) {
        final ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if("toScreenspace".equals(name)
                        && ("(Lorg/joml/Vector3f;Llegend/core/gte/MV;Lorg/joml/Vector2f;)V").equals(descriptor)) {
                    return new ScreenSpaceFixer(mv);
                }
                return mv;
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /**
     * Rewrites the arithmetic of {@code Transformations.toScreenspace}'s modern
     * branch: NDC [-1,1] becomes [0,1] on both axes, and the x result has the
     * widescreen ortho offset removed.
     */
    private static final class ScreenSpaceFixer extends MethodVisitor {
        /** The previous instruction was {@code fconst_2}, so the next fdiv is a "÷2". */
        private boolean afterFconst2;
        /** The previous instruction was an fdiv (identifies the x extent multiply). */
        private boolean afterFdiv;

        ScreenSpaceFixer(final MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitInsn(final int opcode) {
            super.visitInsn(opcode);
            if(opcode == Opcodes.FCONST_2) {
                this.afterFconst2 = true;
                return;
            }
            if(opcode == Opcodes.FDIV) {
                if(this.afterFconst2) {
                    // (ndc / 2) -> (ndc / 2 + 0.5): NDC [-1,1] -> [0,1]
                    super.visitLdcInsn(0.5f);
                    super.visitInsn(Opcodes.FADD);
                }
                this.afterFconst2 = false;
                this.afterFdiv = true;
                return;
            }
            if(opcode == Opcodes.FMUL) {
                if(this.afterFdiv) {
                    // The x extent multiply: it is the only fmul directly
                    // after an fdiv (the y one follows an i2f). Subtract the
                    // widescreen ortho offset, which queueOrthoModel adds to
                    // every 2D model afterwards.
                    super.visitInsn(Opcodes.DUP);
                    super.visitFieldInsn(Opcodes.GETSTATIC, GAMEENGINE_OWNER, "RENDERER",
                            "L" + RENDERENGINE_OWNER + ";");
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RENDERENGINE_OWNER,
                            "getWidescreenOrthoOffsetX", "()F", false);
                    super.visitInsn(Opcodes.FSUB);
                    super.visitInsn(Opcodes.SWAP);
                    super.visitInsn(Opcodes.POP);
                }
                this.afterFdiv = false;
                return;
            }
            this.afterFdiv = false;
        }
    }

    /**
     * {@link #ENABLE_DECOUPLE_WIDESCREEN}: force the remaining
     * {@code renderMode == LEGACY} widescreen gates to read as LEGACY, so the
     * in-game graphics option actually takes effect.
     *
     * <p>Targets, all projection/scissor plumbing that must agree with the
     * matrices {@code updateProjections} built:
     * <ul>
     *   <li>{@code GlesApi.initBatch(RenderBatch)} — the {@code widescreen}
     *       flag that selects the scissor maths;</li>
     *   <li>{@code GlesApi.scissor(QueuedModel, FloatBuffer, ShaderUniformBuffer)}
     *       — the FORCED_4_3 scissor branch;</li>
     *   <li>{@code ScissorStack.setRescale(int,int,int,int)} — the same flag for
     *       submap scissors.</li>
     * </ul>
     *
     * <p>The desktop {@code GlApi} is not patched: it never runs on Android.
     */
    private static byte[] transformWidescreenGates(final byte[] input, final boolean scissorStack) {
        final ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (scissorStack) {
                    if ("setRescale".equals(name) && ("(IIII)L" + SCISSORSTACK_OWNER + ";").equals(descriptor)) {
                        return new LegacyRenderMode(mv);
                    }
                    return mv;
                }
                if ("initBatch".equals(name) && ("(" + RENDERBATCH_DESC + ")V").equals(descriptor)) {
                    return new LegacyRenderMode(mv);
                }
                if ("scissor".equals(name) && ("(" + QUEUEDMODEL_DESC + "Ljava/nio/FloatBuffer;"
                        + "Llegend/core/renderer/ShaderUniformBuffer;)V").equals(descriptor)) {
                    return new LegacyRenderMode(mv);
                }
                return mv;
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /**
     * RenderEngine: rewrite {@code renderBatch(RenderBatch)} so the 2D overlay
     * is depth-isolated from the 3D scene (HUD/dialogue/effects on top).
     *
     * <p>Stock order is 3D-opaque, 2D-opaque, 3D-translucent, 2D-translucent,
     * all into one shared depth buffer with depth testing on. On this GLES path
     * the 2D depth does not land in front, so models occlude the text boxes and
     * depth-hide 2D effects. Rewritten order:
     * <ol>
     *   <li>3D opaque, then 3D translucent (unchanged — correct depth among
     *       models),</li>
     *   <li>{@code clear(false, true, false)} — erase depth, keep colour,</li>
     *   <li>2D opaque, then 2D translucent (pool already z-sorted).</li>
     * </ol>
     * Unlike the v7 attempt (which cleared depth while a 3D pass still ran
     * afterwards and let it overdraw the scene — models went missing), no 3D
     * pass runs after the clear, so model rendering is unaffected.
     */
    private static byte[] transformRenderEngine(final byte[] input) {
        final ClassReader reader = new ClassReader(input);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                if (ENABLE_DEPTH_FLOAT_PATCH && DEPTH_LAMBDA.equals(name)) {
                    return new DepthFormatFloater(mv);
                }

                if (ENABLE_RENDERENGINE_PATCH && "renderBatch".equals(name)
                        && ("(" + RENDERBATCH_DESC + ")V").equals(descriptor)) {
                    return new RenderBatchRewriter(mv);
                }

                // pre() owns the FORCED_4_3 pillarbox: it narrows the viewport to
                // the 4:3 aspect instead of filling the screen. Gated on
                // renderMode == LEGACY, so it never fired while decouple kept the
                // field on PERSPECTIVE. `pre()` reads renderMode exactly once.
                if (ENABLE_DECOUPLE_WIDESCREEN && "pre".equals(name) && "()V".equals(descriptor)) {
                    return new LegacyRenderMode(mv);
                }

                if (ENABLE_DECOUPLE_BATTLE && "setProjectionMode".equals(name)
                        && ("(" + RENDERBATCH_DESC + "L" + PROJECTIONMODE_OWNER + ";)V").equals(descriptor)) {
                    // Force `highQualityProjection = batch.renderMode == PERSPECTIVE`
                    // to false, by flipping the ICONST_1 that follows the
                    // comparison's IF_ACMPNE to ICONST_0 (so both branches yield
                    // false). Structure-preserving — no frame changes.
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        private boolean afterCmp;

                        @Override
                        public void visitJumpInsn(final int opcode, final Label label) {
                            super.visitJumpInsn(opcode, label);
                            this.afterCmp = opcode == Opcodes.IF_ACMPNE;
                        }

                        @Override
                        public void visitInsn(final int opcode) {
                            if (this.afterCmp && opcode == Opcodes.ICONST_1) {
                                this.afterCmp = false;
                                super.visitInsn(Opcodes.ICONST_0);
                                return;
                            }
                            this.afterCmp = false;
                            super.visitInsn(opcode);
                        }
                    };
                }

                return mv;
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /** Rewrites {@code TextureDataType.UINT} → {@code FLOAT} in the depth-texture lambda. */
    private static final class DepthFormatFloater extends MethodVisitor {
        DepthFormatFloater(final MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            if (opcode == Opcodes.GETSTATIC && TEXTUREDATATYPE_OWNER.equals(owner) && "UINT".equals(name)) {
                super.visitFieldInsn(opcode, owner, "FLOAT", descriptor);
            } else {
                super.visitFieldInsn(opcode, owner, name, descriptor);
            }
        }
    }

    /** Replaces {@code renderBatch}'s body with the reordered passes. */
    private static final class RenderBatchRewriter extends MethodVisitor {
        private boolean emitted;

        RenderBatchRewriter(final MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (!this.emitted) {
                this.emitted = true;
                emitBatchBody(this.mv);
            }
        }

        // Swallow the original body; only metadata/maxs/end are forwarded.
        @Override public void visitInsn(final int opcode) {}
        @Override public void visitIntInsn(final int opcode, final int operand) {}
        @Override public void visitVarInsn(final int opcode, final int varIndex) {}
        @Override public void visitTypeInsn(final int opcode, final String type) {}
        @Override public void visitFieldInsn(final int opcode, final String owner, final String name, final String desc) {}
        @Override public void visitMethodInsn(final int opcode, final String owner, final String name, final String desc, final boolean itf) {}
        @Override public void visitInvokeDynamicInsn(final String name, final String desc, final org.objectweb.asm.Handle bsm, final Object... args) {}
        @Override public void visitJumpInsn(final int opcode, final Label label) {}
        @Override public void visitLabel(final Label label) {}
        @Override public void visitLdcInsn(final Object value) {}
        @Override public void visitIincInsn(final int varIndex, final int increment) {}
        @Override public void visitTableSwitchInsn(final int min, final int max, final Label dflt, final Label... labels) {}
        @Override public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {}
        @Override public void visitMultiANewArrayInsn(final String desc, final int dims) {}
        @Override public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {}
        @Override public void visitLocalVariable(final String name, final String desc, final String sig, final Label start, final Label end, final int index) {}
        @Override public void visitLineNumber(final int line, final Label start) {}
        @Override public void visitFrame(final int type, final int numLocal, final Object[] local, final int numStack, final Object[] stack) {}
    }

    private static void emitBatchBody(final MethodVisitor m) {
        // if(batch.needsSorting) { sortPerspectivePool(modelPool); sortOrthoPool(orthoPool); batch.needsSorting = false; }
        final Label skip = new Label();
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitFieldInsn(Opcodes.GETFIELD, RENDERBATCH_OWNER, "needsSorting", "Z");
        m.visitJumpInsn(Opcodes.IFEQ, skip);
        emitSort(m, "modelPool", "sortPerspectivePool");
        emitSort(m, "orthoPool", "sortOrthoPool");
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitInsn(Opcodes.ICONST_0);
        m.visitFieldInsn(Opcodes.PUTFIELD, RENDERBATCH_OWNER, "needsSorting", "Z");
        m.visitLabel(skip);
        m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

        // api.initBatch(batch); api.clear(false, true, false);
        emitApi(m);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, RENDERAPI_OWNER, "initBatch", "(" + RENDERBATCH_DESC + ")V", true);
        emitDepthClear(m);

        // 3D opaque, then 3D translucent (wireframe honours wireframeMode).
        emitWireframe(m, true);
        emitProjection(m, "_3D");
        emitRenderPool(m, "modelPool", true);
        emitWireframe(m, true);
        emitProjection(m, "_3D");
        emitRenderPoolTranslucent(m, "modelPool");

        // Erase depth (keep colour) so the overlay is never occluded. No 3D pass
        // runs after this, so model rendering is untouched.
        emitDepthClear(m);

        // 2D overlay: opaque, then translucent (pool is z-sorted).
        emitWireframe(m, false);
        emitProjection(m, "_2D");
        emitRenderPool(m, "orthoPool", false);
        emitWireframe(m, false);
        emitProjection(m, "_2D");
        emitRenderPoolTranslucent(m, "orthoPool");

        m.visitInsn(Opcodes.RETURN);
    }

    private static void emitApi(final MethodVisitor m) {
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, RENDERENGINE_OWNER, "api", "L" + RENDERAPI_OWNER + ";");
    }

    private static void emitDepthClear(final MethodVisitor m) {
        emitApi(m);
        m.visitInsn(Opcodes.ICONST_0);
        m.visitInsn(Opcodes.ICONST_1);
        m.visitInsn(Opcodes.ICONST_0);
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, RENDERAPI_OWNER, "clear", "(ZZZ)V", true);
    }

    private static void emitWireframe(final MethodVisitor m, final boolean mode) {
        emitApi(m);
        if (mode) {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitFieldInsn(Opcodes.GETFIELD, RENDERENGINE_OWNER, "wireframeMode", "Z");
        } else {
            m.visitInsn(Opcodes.ICONST_0);
        }
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, RENDERAPI_OWNER, "wireframe", "(Z)V", true);
    }

    private static void emitProjection(final MethodVisitor m, final String field) {
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitFieldInsn(Opcodes.GETSTATIC, PROJECTIONMODE_OWNER, field, "L" + PROJECTIONMODE_OWNER + ";");
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RENDERENGINE_OWNER, "setProjectionMode",
                "(" + RENDERBATCH_DESC + "L" + PROJECTIONMODE_OWNER + ";)V", false);
    }

    private static void emitSort(final MethodVisitor m, final String poolField, final String method) {
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitFieldInsn(Opcodes.GETFIELD, RENDERBATCH_OWNER, poolField, QUEUEPOOL_DESC);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RENDERENGINE_OWNER, method, "(" + QUEUEPOOL_DESC + ")V", false);
    }

    private static void emitRenderPool(final MethodVisitor m, final String poolField, final boolean backFaceCulling) {
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitFieldInsn(Opcodes.GETFIELD, RENDERBATCH_OWNER, poolField, QUEUEPOOL_DESC);
        m.visitInsn(backFaceCulling ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RENDERENGINE_OWNER, "renderPool", "(" + QUEUEPOOL_DESC + "Z)V", false);
    }

    private static void emitRenderPoolTranslucent(final MethodVisitor m, final String poolField) {
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitFieldInsn(Opcodes.GETFIELD, RENDERBATCH_OWNER, poolField, QUEUEPOOL_DESC);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RENDERENGINE_OWNER, "renderPoolTranslucent", "(" + QUEUEPOOL_DESC + ")V", false);
    }

    /** Emits a replacement body, then swallows the original instructions. */
    private interface Body {
        void emit(MethodVisitor mv);
    }

    private static final class BodyReplacer extends MethodVisitor {
        private final Body body;
        private boolean emitted;

        BodyReplacer(final MethodVisitor mv, final Body body) {
            super(Opcodes.ASM9, mv);
            this.body = body;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (!this.emitted) {
                this.emitted = true;
                this.body.emit(this.mv);
            }
        }

        @Override public void visitInsn(final int opcode) {}
        @Override public void visitIntInsn(final int opcode, final int operand) {}
        @Override public void visitVarInsn(final int opcode, final int varIndex) {}
        @Override public void visitTypeInsn(final int opcode, final String type) {}
        @Override public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {}
        @Override public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor, final boolean isInterface) {}
        @Override public void visitInvokeDynamicInsn(final String name, final String descriptor, final org.objectweb.asm.Handle bootstrapMethod, final Object... bootstrapArgs) {}
        @Override public void visitJumpInsn(final int opcode, final Label label) {}
        @Override public void visitLabel(final Label label) {}
        @Override public void visitLdcInsn(final Object value) {}
        @Override public void visitIincInsn(final int varIndex, final int increment) {}
        @Override public void visitTableSwitchInsn(final int min, final int max, final Label dflt, final Label... labels) {}
        @Override public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {}
        @Override public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {}
        @Override public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {}
        @Override public void visitLocalVariable(final String name, final String descriptor, final String signature, final Label start, final Label end, final int index) {}
        @Override public void visitLineNumber(final int line, final Label start) {}
        // The original method's StackMapTable frames must be dropped along with
        // its instructions: their anchor labels are swallowed above, so the
        // frames would be re-emitted at the wrong (shorter) offsets and the
        // verifier rejects the class with "StackMapTable error: bad offset".
        // The replacement bodies are straight-line, so no frames are needed.
        @Override public void visitFrame(final int type, final int numLocal, final Object[] local, final int numStack, final Object[] stack) {}
    }
    private static void loadCount(final MethodVisitor mv, final boolean full) {
        if (full) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, GLESMESH_OWNER, FIELD_COUNT, "I");
        } else {
            mv.visitVarInsn(Opcodes.ILOAD, 2);
        }
    }

    private static void modeCheck(final MethodVisitor mv, final Label triangle, final int mode) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, GLESMESH_OWNER, FIELD_MODE, "I");
        if (mode <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + mode);
        } else {
            mv.visitIntInsn(Opcodes.BIPUSH, mode);
        }
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, triangle);
    }

    private static byte[] readAll(final InputStream in) throws Exception {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(16384);
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String md5(final File file) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("MD5");
        try (FileInputStream in = new FileInputStream(file)) {
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        final StringBuilder sb = new StringBuilder();
        for (final byte b : digest.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String readText(final File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            final byte[] data = readAll(in);
            return new String(data, "UTF-8");
        }
    }

    private static void writeText(final File file, final String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes("UTF-8"));
        }
    }
}
