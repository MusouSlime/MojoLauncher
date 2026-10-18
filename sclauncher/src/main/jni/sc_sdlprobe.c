#include <jni.h>
#include <dlfcn.h>

/*
 * Tiny probe over the SDL3 library the game already has loaded. The game
 * requests the on-screen keyboard through SDL_StartTextInput; Mojo's Java
 * SDLActivity.showTextInput is a stub, so nothing surfaces. Polling
 * SDL_TextInputActive() from the launcher lets us raise the soft keyboard
 * exactly while the game has a text field focused.
 *
 * No SDL headers needed: the symbol is resolved at runtime with dlsym.
 */

typedef int (*sdl_text_input_active_fn)(void);

static sdl_text_input_active_fn resolve_text_active(void) {
    static sdl_text_input_active_fn fn = NULL;
    static int tried = 0;
    if (!tried) {
        tried = 1;
        void* handle = dlopen("libSDL3.so", RTLD_NOW | RTLD_NOLOAD);
        if (!handle) {
            handle = dlopen("libSDL3.so", RTLD_NOW);
        }
        if (handle) {
            fn = (sdl_text_input_active_fn)dlsym(handle, "SDL_TextInputActive");
            if (!fn) {
                /* SDL2 name, in case an older SDL is present. */
                fn = (sdl_text_input_active_fn)dlsym(handle, "SDL_IsTextInputActive");
            }
        }
    }
    return fn;
}

JNIEXPORT jboolean JNICALL
Java_com_severedchains_launcher_SCSdlNative_isTextInputActive(JNIEnv* env, jclass cls) {
    (void)env;
    (void)cls;
    sdl_text_input_active_fn fn = resolve_text_active();
    if (!fn) {
        return JNI_FALSE;
    }
    return fn() ? JNI_TRUE : JNI_FALSE;
}
