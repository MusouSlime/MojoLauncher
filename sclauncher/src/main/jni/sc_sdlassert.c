// ---------------------------------------------------------------------------
// sc_sdlassert: install a process-wide SDL3 assertion handler.
//
// Why this exists
// ---------------
// Mojo's libSDL3.so is built with SDL assertions live (we see SDL_assert()
// firing in release builds), and SDL's stock assert path is modal: it pops a
// messagebox with Retry / Break / Abort / Ignore / Always Ignore and returns
// whatever the dialog reports. Our Java messagebox override auto-dismisses
// with the *first* button, which for the assertion dialog is
// "Retry" == SDL_ASSERTION_RETRY (0).
//
// SDL's assert macro is
//
//     do {
//         while (!(condition)) {
//             state = SDL_ReportAssertion(...);
//             if (state == SDL_ASSERTION_RETRY) continue;  // go again
//             break;
//         }
//     } while (0);
//
// so a single failing assert re-runs forever: each iteration re-enters the
// Java messagebox, prints an "Assertion failure ... triggered N times" line
// and allocates. A device report from the S20 showed exactly that --
// "Assertion failure at SDL_UnlockMutex (SDL_sysmutex.c:151), triggered 25932
// times" and a 5.5 MB game log, with the app wedged in the loop.
//
// The handler below takes the decision away from the dialog entirely: it logs
// the assert once, dumps a native backtrace so the *caller* can be identified
// offline, and returns SDL_ASSERTION_ALWAYS_IGNORE -- SDL then marks that
// assertion site `always_ignore` and never reports it again, which is exactly
// what a user pressing "Always Ignore" (or a desktop release build) does.
//
// Symbols are resolved from the already-loaded libSDL3.so with dlsym, like
// sc_gamepad.c, so there is no link-time dependency and no second SDL copy.
// ---------------------------------------------------------------------------
#include <jni.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <unwind.h>
#include <android/log.h>

#define LOG_TAG "SCSdlAssert"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// SDL_AssertState values (SDL3: RETRY=0, BREAK=1, ABORT=2, IGNORE=3, ALWAYS_IGNORE=4).
#define SC_SDL_ASSERTION_ALWAYS_IGNORE 4

// Mirrors SDL_AssertData (SDL3). Kept in sync with SDL_assert.h; the struct is
// passed to us by the SDL we resolve at runtime.
typedef struct SC_SDL_AssertData {
    bool always_ignore;
    unsigned int trigger_count;
    const char *condition;
    const char *filename;
    int linenum;
    const char *function;
    const struct SC_SDL_AssertData *next;
} SC_SDL_AssertData;

typedef int (*SC_AssertionHandler)(const SC_SDL_AssertData *data, void *userdata);
typedef void (*SetAssertionHandlerFn)(SC_AssertionHandler handler, void *userdata);
typedef bool (*SetHintFn)(const char *name, const char *value);

static SetAssertionHandlerFn pSetAssertionHandler = NULL;
static SetHintFn pSetHint = NULL;
static bool sInstalled = false;

// ---------------------------------------------------------------------------
// Backtrace: _Unwind_Backtrace comes from the LLVM unwinder the NDK links into
// every binary (clang's <unwind.h>), so it works on every supported API level
// -- bionic's backtrace() only exists from API 33.
// ---------------------------------------------------------------------------
#define SC_MAX_FRAMES 24

static _Unwind_Reason_Code bt_cb(struct _Unwind_Context *ctx, void *arg) {
    int *depth = (int *)arg;
    const uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc != 0) {
        LOGW("    #%02d  pc %p", *depth, (void *)pc);
        ++(*depth);
    }
    return (*depth >= SC_MAX_FRAMES) ? _URC_END_OF_STACK : _URC_NO_REASON;
}

// ---------------------------------------------------------------------------
// The handler itself. Never returns RETRY: that is what turned one assert into
// an infinite loop. Also never shows UI -- SDL's own dialog machinery is what
// re-enters the app on another thread.
// ---------------------------------------------------------------------------
static int sc_assert_handler(const SC_SDL_AssertData *data, void *userdata) {
    (void)userdata;
    if (data == NULL) {
        return SC_SDL_ASSERTION_ALWAYS_IGNORE;
    }

    LOGW("SDL assertion (ignored from now on): '%s' in %s (%s:%d), triggered %u time(s)",
         data->condition ? data->condition : "?",
         data->function ? data->function : "?",
         data->filename ? data->filename : "?", data->linenum,
         data->trigger_count);
    LOGW("  backtrace (resolve with the matching unstripped libSDL3.so):");

    int depth = 0;
    _Unwind_Backtrace(bt_cb, &depth);
    LOGW("  end backtrace (%d frame(s))", depth);

    // SDL sets data->always_ignore after this, so the site is silent from now
    // on. The app keeps running the way a desktop build would.
    return SC_SDL_ASSERTION_ALWAYS_IGNORE;
}

static void *open_sdl(void) {
    // Same instance the game uses; RTLD_NOLOAD first so we never load a second
    // copy, but fall back to loading it (harmless, Mojo's SDL has no
    // JNI_OnLoad) if we get here before the game did.
    void *handle = dlopen("libSDL3.so", RTLD_NOLOAD | RTLD_GLOBAL);
    if (handle == NULL) {
        handle = dlopen("libSDL3.so", RTLD_NOW | RTLD_GLOBAL);
    }
    return handle;
}

JNIEXPORT jboolean JNICALL
Java_com_severedchains_launcher_SCSdlNative_installAssertHandlerNative(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    if (sInstalled) {
        return JNI_TRUE;
    }

    void *handle = open_sdl();
    if (handle == NULL) {
        LOGW("libSDL3.so not loadable yet, assertion handler not installed");
        return JNI_FALSE;
    }

    if (pSetAssertionHandler == NULL) {
        pSetAssertionHandler = (SetAssertionHandlerFn)dlsym(handle, "SDL_SetAssertionHandler");
    }
    if (pSetHint == NULL) {
        pSetHint = (SetHintFn)dlsym(handle, "SDL_SetHint");
    }

    // Belt and braces: if anything later reinstates SDL's default handler, the
    // hint still makes it answer "always ignore" instead of showing a dialog
    // (and looping forever on Retry).
    if (pSetHint != NULL) {
        pSetHint("SDL_ASSERT", "always_ignore");
    }

    if (pSetAssertionHandler == NULL) {
        LOGW("SDL_SetAssertionHandler missing, only the SDL_ASSERT hint applies");
        return JNI_FALSE;
    }

    pSetAssertionHandler(sc_assert_handler, NULL);
    sInstalled = true;
    LOGI("SDL assertion handler installed (asserts are logged + ignored, never modal)");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_severedchains_launcher_SCSdlNative_isAssertHandlerInstalledNative(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return sInstalled ? JNI_TRUE : JNI_FALSE;
}
