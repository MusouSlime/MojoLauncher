// ---------------------------------------------------------------------------
// sc_gamepad: virtual XInput-style controller via SDL3's virtual-joystick
// API, resolved at runtime from the SAME libSDL3.so instance the game uses
// (dlsym, no link-time dependency). TouchGamepadView and SCPhysicalGamepad
// drive it from Java (SCGamepadNative); the game sees a real controller.
// Attach is lazy (after the game's SDL_Init) — eager SDL_OpenJoystick trips
// a native assertion and SIGABRTs via CheckJNI.
// ---------------------------------------------------------------------------
#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <android/log.h>

#include <SDL3/SDL_joystick.h>
#include <SDL3/SDL_gamepad.h>

#define LOG_TAG "SCPad"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Resolved at runtime from the already-loaded libSDL3.so (same instance the
// game uses, so the virtual joystick is visible to the game). dlsym avoids a
// link-time dependency on libSDL3.
typedef int (*AttachFn)(const SDL_VirtualJoystickDesc*);
typedef bool (*DetachFn)(int);
typedef bool (*SetButtonFn)(void*, int, bool);
typedef bool (*SetAxisFn)(void*, int, Sint16);

static void* sdl_handle = NULL;
static AttachFn pAttach = NULL;
static DetachFn pDetach = NULL;
static SetButtonFn pSetButton = NULL;
static SetAxisFn pSetAxis = NULL;

// SDL joystick objects are opaque; the virtual-button setters take the
// joystick pointer. We resolve the open joystick from the instance id.
typedef void* (*OpenFn)(int);
static OpenFn pOpen = NULL;
// instance id -> joystick cache (single pad, simple static)
static void* sJoystick = NULL;
static int sInstance = -1;

static bool resolve(void) {
    if (pAttach) return true;
    sdl_handle = dlopen("libSDL3.so", RTLD_NOLOAD | RTLD_GLOBAL);
    if (!sdl_handle) {
        LOGE("libSDL3.so not loaded yet: %s", dlerror());
        return false;
    }
    pAttach = (AttachFn)dlsym(sdl_handle, "SDL_AttachVirtualJoystick");
    pDetach = (DetachFn)dlsym(sdl_handle, "SDL_DetachVirtualJoystick");
    pSetButton = (SetButtonFn)dlsym(sdl_handle, "SDL_SetJoystickVirtualButton");
    pSetAxis = (SetAxisFn)dlsym(sdl_handle, "SDL_SetJoystickVirtualAxis");
    pOpen = (OpenFn)dlsym(sdl_handle, "SDL_OpenJoystick");
    if (!pAttach || !pDetach || !pSetButton || !pSetAxis || !pOpen) {
        LOGE("missing SDL virtual-joystick symbols");
        pAttach = NULL;
        return false;
    }
    return true;
}

JNIEXPORT jint JNICALL
Java_com_severedchains_launcher_SCGamepadNative_nativeAttach(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    if (sInstance >= 0) return sInstance;
    if (!resolve()) return -1;

    SDL_VirtualJoystickDesc desc;
    memset(&desc, 0, sizeof(desc));
    desc.version = sizeof(desc);
    desc.type = SDL_JOYSTICK_TYPE_GAMEPAD;
    desc.naxes = 6;     // sticks + triggers
    desc.nbuttons = 15; // SOUTH..DRIGHT
    desc.vendor_id = 0x045E;  // present as a standard XInput-style pad
    desc.product_id = 0x028E;
    desc.button_mask = 0x7FFF; // buttons 0..14 valid
    desc.axis_mask = 0x3F;     // axes 0..5 valid
    desc.name = "SCLauncher Virtual Pad";

    int inst = pAttach(&desc);
    if (inst < 0) {
        LOGE("SDL_AttachVirtualJoystick failed");
        return -1;
    }
    sJoystick = pOpen(inst);
    if (!sJoystick) {
        LOGE("SDL_OpenJoystick(%d) failed", inst);
        pDetach(inst);
        return -1;
    }
    sInstance = inst;
    LOGI("virtual gamepad attached, instance=%d", inst);
    return inst;
}

JNIEXPORT void JNICALL
Java_com_severedchains_launcher_SCGamepadNative_nativeSetButton(
        JNIEnv* env, jclass clazz, jint instance, jint button, jboolean down) {
    (void)env; (void)clazz;
    if (!resolve() || !sJoystick || instance != sInstance) return;
    pSetButton(sJoystick, button, down ? true : false);
}

JNIEXPORT void JNICALL
Java_com_severedchains_launcher_SCGamepadNative_nativeSetAxis(
        JNIEnv* env, jclass clazz, jint instance, jint axis, jint value) {
    (void)env; (void)clazz;
    if (!resolve() || !sJoystick || instance != sInstance) return;
    if (value < -32768) value = -32768;
    if (value > 32767) value = 32767;
    pSetAxis(sJoystick, axis, (Sint16)value);
}

JNIEXPORT void JNICALL
Java_com_severedchains_launcher_SCGamepadNative_nativeDetach(
        JNIEnv* env, jclass clazz, jint instance) {
    (void)env; (void)clazz;
    if (!resolve() || instance != sInstance) return;
    pDetach(instance);
    sJoystick = NULL;
    sInstance = -1;
    LOGI("virtual gamepad detached");
}
