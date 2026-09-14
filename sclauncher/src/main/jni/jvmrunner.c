// ---------------------------------------------------------------------------
// jvmrunner: creates the game's JVM inside our process and calls Main.
//
// Pipeline (mirrors the log lines you see at launch):
//   1. preload_jvm_libs()  - dlopen JRE libs in Amethyst order (jli first)
//   2. dlopen libjvm.so    - RTLD_GLOBAL so later lookups resolve
//   3. exit/abort hooks    - HotSpot "exit"/"abort" options rerouted to Java
//   4. chdir + ffmpeg      - game-relative paths + bytedeco .so pre-dlopen
//                            (dlopen here, NEVER ART System.load: CheckJNI
//                            aborts on these foreign .so files)
//   5. JNI_CreateJavaVM    - exactly once per process (EEXIST otherwise)
//   6. FindClass + main()  - legend.game.Main.main(appArgs)
// ---------------------------------------------------------------------------
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <pthread.h>
#include <stdbool.h>
#include <linux/futex.h>
#include <sys/syscall.h>

#define LOG_TAG "JVMRunner"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Error codes matching Java side
#define ERR_DLOPEN_FAILED 1
#define ERR_DLSYM_FAILED 2
#define ERR_CREATE_VM_FAILED 3
#define ERR_FIND_CLASS_FAILED 4
#define ERR_FIND_METHOD_FAILED 5
#define ERR_NEW_OBJECT_ARRAY_FAILED 6

// Use JNI 1.8 for the invocation API: supported by every JDK >= 8,
// including the bundled Amethyst OpenJDK 17 runtime.
// (The Android NDK's jni.h only defines up to 1.6, so define 1.8 here.)
#ifndef JNI_VERSION_1_8
#define JNI_VERSION_1_8 0x00010008
#endif
#define JVM_JNI_VERSION JNI_VERSION_1_8

// ---- VM exit/abort routing (mirrors Mojo's jre_launcher + abort_wait) ----
// HotSpot honors JavaVMOptions named "exit"/"abort" whose extraInfo are hooks
// called INSTEAD of ::exit/abort. Without these, a game System.exit() or fatal
// error kills our process silently (back to launcher, no tombstone).
static uint32_t sc_futex_word = 1;
static int sc_exit_code = 0;
static JavaVM* sc_cached_vm = NULL;
static jclass sc_game_activity_class = NULL;
static jmethodID sc_on_game_exit_mid = NULL;

_Noreturn static void sc_report_exit(void) {
    JNIEnv* env = NULL;
    jint r = (*sc_cached_vm)->GetEnv(sc_cached_vm, (void**)&env, JNI_VERSION_1_6);
    if (r == JNI_EDETACHED) r = (*sc_cached_vm)->AttachCurrentThread(sc_cached_vm, &env, NULL);
    if (r == JNI_OK && sc_game_activity_class != NULL && sc_on_game_exit_mid != NULL) {
        (*env)->CallStaticVoidMethod(env, sc_game_activity_class, sc_on_game_exit_mid, sc_exit_code);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    } else {
        LOGE("Game exited with code %d (no Java callback)", sc_exit_code);
    }
    // Park forever: the Java side finishes the game screen gracefully.
    while (true) { syscall(SYS_futex, &sc_futex_word, FUTEX_WAIT, 0, NULL); }
}

static void* sc_exit_wait(void* unused) {
    (void)unused;
    syscall(SYS_futex, &sc_futex_word, FUTEX_WAIT, 1, NULL);
    sc_report_exit();
    return NULL; // unreachable
}

_Noreturn static void sc_vm_exit(int code) {
    LOGI("Game requested exit(%d)", code);
    sc_exit_code = code;
    sc_futex_word = 0;
    syscall(SYS_futex, &sc_futex_word, FUTEX_WAKE, INT32_MAX, NULL, NULL, 0);
    while (true) {}
}

_Noreturn static void sc_vm_abort(void) {
    LOGE("VM abort() called");
    sc_exit_code = -1;
    sc_futex_word = 0;
    syscall(SYS_futex, &sc_futex_word, FUTEX_WAKE, INT32_MAX, NULL, NULL, 0);
    while (true) {}
}

static void sc_setup_exit_wait(JNIEnv* env) {
    (*env)->GetJavaVM(env, &sc_cached_vm);
    jclass local = (*env)->FindClass(env, "com/severedchains/launcher/SCGameActivity");
    if (local != NULL) {
        sc_game_activity_class = (*env)->NewGlobalRef(env, local);
        sc_on_game_exit_mid = (*env)->GetStaticMethodID(env, sc_game_activity_class,
                "onGameExit", "(I)V");
    }
    if (sc_on_game_exit_mid == NULL) {
        LOGE("onGameExit callback not found; exit codes will only be logged");
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
    pthread_t t;
    pthread_create(&t, NULL, sc_exit_wait, NULL);
    pthread_detach(t);
}

static void throwVmLoadException(JNIEnv* env, const char* msg) {
    jclass exClass = (*env)->FindClass(env, "com/severedchains/launcher/JVMRunner$VMLoadException");
    if (exClass != NULL) {
        (*env)->ThrowNew(env, exClass, msg);
    }
}

static void preload_one(const char* dir, const char* lib) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/%s", dir, lib);
    void* handle = dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
    if (handle) {
        LOGI("Preloaded: %s", lib);
    } else {
        LOGI("Could not preload %s: %s", lib, dlerror());
    }
}

// Preload order mirrors Amethyst's JREUtils.initJavaRuntime(): jli first,
// then the core runtime libs, so later dlopen/dlsym calls resolve.
static void preload_jvm_libs(const char* jre_lib_dir, const char* server_dir) {
    preload_one(jre_lib_dir, "libjli.so");
    preload_one(server_dir, "libjvm.so");
    preload_one(jre_lib_dir, "libverify.so");
    preload_one(jre_lib_dir, "libjava.so");
    preload_one(jre_lib_dir, "libjsig.so");
    preload_one(jre_lib_dir, "libnet.so");
    preload_one(jre_lib_dir, "libnio.so");
    preload_one(jre_lib_dir, "libawt.so");
    preload_one(jre_lib_dir, "libawt_headless.so");
    preload_one(jre_lib_dir, "libfreetype.so");
    preload_one(jre_lib_dir, "libfontmanager.so");
    preload_one(server_dir, "libjsig.so");
}

JNIEXPORT jint JNICALL
Java_com_severedchains_launcher_JVMRunner_nativeLoadJVM(
        JNIEnv* env, jclass clazz,
        jstring vmPath, jobjectArray javaArgs,
        jstring mainClass, jobjectArray appArgs,
        jboolean hasJavaAgents) {

    const char* vm_path = (*env)->GetStringUTFChars(env, vmPath, NULL);
    const char* main_class = (*env)->GetStringUTFChars(env, mainClass, NULL);

    LOGI("Loading JVM via JNI_CreateJavaVM from: %s", vm_path);

    // Derive <jre>/lib and <jre>/lib/server from .../lib/server/libjvm.so
    char server_dir[1024];
    char jre_lib_dir[1024];
    strncpy(server_dir, vm_path, sizeof(server_dir) - 1);
    server_dir[sizeof(server_dir) - 1] = '\0';
    char* slash = strrchr(server_dir, '/');
    if (slash) *slash = '\0';              // -> .../lib/server
    strncpy(jre_lib_dir, server_dir, sizeof(jre_lib_dir) - 1);
    jre_lib_dir[sizeof(jre_lib_dir) - 1] = '\0';
    slash = strrchr(jre_lib_dir, '/');
    if (slash) *slash = '\0';              // -> .../lib
    LOGI("JRE lib dir: %s", jre_lib_dir);
    LOGI("JVM server dir: %s", server_dir);

    LOGI("Preloading JVM libraries (Amethyst order)...");
    preload_jvm_libs(jre_lib_dir, server_dir);

    void* handle = dlopen(vm_path, RTLD_LAZY | RTLD_GLOBAL);
    if (!handle) {
        char msg[1024];
        snprintf(msg, sizeof(msg), "dlopen libjvm.so failed: %s (path=%s)", dlerror(), vm_path);
        LOGE("%s", msg);
        throwVmLoadException(env, msg);
        (*env)->ReleaseStringUTFChars(env, vmPath, vm_path);
        (*env)->ReleaseStringUTFChars(env, mainClass, main_class);
        return ERR_DLOPEN_FAILED;
    }

    LOGI("libjvm.so loaded successfully");

    typedef jint (JNICALL *JNI_CreateJavaVM_t)(JavaVM**, void**, void*);
    JNI_CreateJavaVM_t JNI_CreateJavaVM = (JNI_CreateJavaVM_t)dlsym(handle, "JNI_CreateJavaVM");
    if (!JNI_CreateJavaVM) {
        char msg[1024];
        snprintf(msg, sizeof(msg), "dlsym JNI_CreateJavaVM failed: %s (path=%s)", dlerror(), vm_path);
        LOGE("%s", msg);
        throwVmLoadException(env, msg);
        dlclose(handle);
        (*env)->ReleaseStringUTFChars(env, vmPath, vm_path);
        (*env)->ReleaseStringUTFChars(env, mainClass, main_class);
        return ERR_DLSYM_FAILED;
    }

    LOGI("Found JNI_CreateJavaVM function");

    int argc = (*env)->GetArrayLength(env, javaArgs);
    char** argv = malloc((argc + 1) * sizeof(char*));
    argv[0] = "java";

    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring)(*env)->GetObjectArrayElement(env, javaArgs, i);
        argv[i + 1] = (char*)(*env)->GetStringUTFChars(env, arg, NULL);
        LOGI("JVM arg %d: %s", i, argv[i + 1]);
        if (strncmp(argv[i + 1], "-Djava.class.path=", 18) == 0) {
            LOGI("CLASSPATH: %s", argv[i + 1] + 18);
        }
    }

    JavaVMInitArgs vm_args;
    vm_args.version = JVM_JNI_VERSION;
    // +2 slots for the "exit"/"abort" hooks (see above).
    vm_args.nOptions = argc + 2;
    vm_args.options = malloc((argc + 2) * sizeof(JavaVMOption));
    vm_args.ignoreUnrecognized = JNI_TRUE;

    for (int i = 0; i < argc; i++) {
        vm_args.options[i].optionString = argv[i + 1];
        vm_args.options[i].extraInfo = NULL;
    }
    vm_args.options[argc].optionString = "exit";
    vm_args.options[argc].extraInfo = sc_vm_exit;
    vm_args.options[argc + 1].optionString = "abort";
    vm_args.options[argc + 1].extraInfo = sc_vm_abort;
    sc_setup_exit_wait(env);

    // The game resolves relative paths (saves/, logs/) against the process
    // working directory, which is "/" on Android (read-only). chdir to the
    // game dir (-Duser.home) so those resolve into app storage.
    char gamedir_buf[1024] = {0};
    for (int i = 0; i < argc; i++) {
        const char* prefix = "-Duser.home=";
        if (strncmp(argv[i + 1], prefix, strlen(prefix)) == 0) {
            const char* gamedir = argv[i + 1] + strlen(prefix);
            strncpy(gamedir_buf, gamedir, sizeof(gamedir_buf) - 1);
            if (chdir(gamedir) == 0) {
                LOGI("Working directory: %s", gamedir);
            } else {
                LOGE("chdir(%s) failed: %s", gamedir, strerror(errno));
            }
            break;
        }
    }

    // bytedeco ffmpeg: pre-dlopen the closure (leaves first, RTLD_GLOBAL) so
    // the JVM-side dlopen resolves. Done here with dlopen — NOT via ART
    // System.load, which aborts (CheckJNI) on these foreign .so files.
    // Missing dir/files = video simply unavailable; never fatal to boot.
    if (gamedir_buf[0]) {
        static const char* ff_libs[] = {
            "libavutil.so", "libswresample.so", "libswscale.so",
            "libavcodec.so", "libavformat.so", "libavdevice.so", "libavfilter.so",
            "libjniavutil.so", "libjniswresample.so", "libjniswscale.so",
            "libjniavcodec.so", "libjniavformat.so", "libjniavdevice.so", "libjniavfilter.so",
            NULL
        };
        for (int i = 0; ff_libs[i] != NULL; i++) {
            char path[2048];
            snprintf(path, sizeof(path), "%s/ffmpeg_natives/%s", gamedir_buf, ff_libs[i]);
            void* h = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
            if (h) {
                LOGI("Preloaded: %s", ff_libs[i]);
            } else {
                LOGI("ffmpeg preload skipped %s: %s", ff_libs[i], dlerror());
            }
        }
    }

    JavaVM* jvm;
    void* penv;
    LOGI("Calling JNI_CreateJavaVM (JNI 1.8, in-process, no bin/java exec)...");
    jint res = JNI_CreateJavaVM(&jvm, &penv, &vm_args);

    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring)(*env)->GetObjectArrayElement(env, javaArgs, i);
        (*env)->ReleaseStringUTFChars(env, arg, argv[i + 1]);
    }
    free(argv);
    free(vm_args.options);

    if (res != JNI_OK) {
        const char* name = "unknown JNI error";
        switch (res) {
            case JNI_EVERSION: name = "JNI_EVERSION: VM version not supported"; break;
            case JNI_ENOMEM: name = "JNI_ENOMEM: Not enough memory"; break;
            case JNI_EINVAL: name = "JNI_EINVAL: Invalid arguments"; break;
            case JNI_EEXIST: name = "JNI_EEXIST: VM already created"; break;
            default: break;
        }
        char msg[512];
        snprintf(msg, sizeof(msg), "JNI_CreateJavaVM failed: %s (code=%d)", name, res);
        LOGE("%s", msg);
        const char* dl_err = dlerror();
        if (dl_err) LOGE("dlerror: %s", dl_err);
        throwVmLoadException(env, msg);
        (*env)->ReleaseStringUTFChars(env, vmPath, vm_path);
        (*env)->ReleaseStringUTFChars(env, mainClass, main_class);
        return ERR_CREATE_VM_FAILED;
    }

    LOGI("JVM created successfully (JNI 1.8)");

    // ---- Locate and invoke legend.game.Main.main(String[]) ----
    JNIEnv* jni_env;
    (*jvm)->GetEnv(jvm, (void**)&jni_env, JVM_JNI_VERSION);

    char* class_name = strdup(main_class);
    for (char* p = class_name; *p; p++) {
        if (*p == '.') *p = '/';
    }

    LOGI("Finding main class: %s", class_name);
    jclass main_cls = (*jni_env)->FindClass(jni_env, class_name);
    free(class_name);

    if (!main_cls) {
        char msg[512];
        snprintf(msg, sizeof(msg), "FindClass failed for main class: %s", main_class);
        LOGE("%s", msg);
        if ((*jni_env)->ExceptionCheck(jni_env)) (*jni_env)->ExceptionDescribe(jni_env);
        (*jvm)->DestroyJavaVM(jvm);
        throwVmLoadException(env, msg);
        (*env)->ReleaseStringUTFChars(env, vmPath, vm_path);
        (*env)->ReleaseStringUTFChars(env, mainClass, main_class);
        return ERR_FIND_CLASS_FAILED;
    }

    LOGI("Finding main method...");
    jmethodID main_method = (*jni_env)->GetStaticMethodID(jni_env, main_cls, "main", "([Ljava/lang/String;)V");
    if (!main_method) {
        LOGE("Failed to find main method");
        if ((*jni_env)->ExceptionCheck(jni_env)) (*jni_env)->ExceptionDescribe(jni_env);
        (*jvm)->DestroyJavaVM(jvm);
        throwVmLoadException(env, "GetStaticMethodID failed for main([Ljava/lang/String;)V");
        (*env)->ReleaseStringUTFChars(env, vmPath, vm_path);
        (*env)->ReleaseStringUTFChars(env, mainClass, main_class);
        return ERR_FIND_METHOD_FAILED;
    }

    int app_argc = (*env)->GetArrayLength(env, appArgs);
    jobjectArray app_args_array = (*jni_env)->NewObjectArray(jni_env, app_argc, (*jni_env)->FindClass(jni_env, "java/lang/String"), NULL);
    if (!app_args_array) {
        LOGE("Failed to create app args array");
        (*jvm)->DestroyJavaVM(jvm);
        throwVmLoadException(env, "NewObjectArray failed for app args");
        (*env)->ReleaseStringUTFChars(env, vmPath, vm_path);
        (*env)->ReleaseStringUTFChars(env, mainClass, main_class);
        return ERR_NEW_OBJECT_ARRAY_FAILED;
    }

    for (int i = 0; i < app_argc; i++) {
        jstring arg = (jstring)(*env)->GetObjectArrayElement(env, appArgs, i);
        const char* arg_str = (*env)->GetStringUTFChars(env, arg, NULL);
        jstring jarg = (*jni_env)->NewStringUTF(jni_env, arg_str);
        (*jni_env)->SetObjectArrayElement(jni_env, app_args_array, i, jarg);
        (*env)->ReleaseStringUTFChars(env, arg, arg_str);
    }

    LOGI("Calling main method...");
    (*jni_env)->CallStaticVoidMethod(jni_env, main_cls, main_method, app_args_array);

    if ((*jni_env)->ExceptionCheck(jni_env)) {
        (*jni_env)->ExceptionDescribe(jni_env);
    }

    LOGI("Main method returned, destroying JVM");
    (*jvm)->DestroyJavaVM(jvm);

    (*env)->ReleaseStringUTFChars(env, vmPath, vm_path);
    (*env)->ReleaseStringUTFChars(env, mainClass, main_class);

    return 0;  // Success
}
