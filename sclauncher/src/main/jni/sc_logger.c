// ---------------------------------------------------------------------------
// sc_logger: captures ALL stdout/stderr of the in-process JVM into
// latestlog.txt (+ logcat + Java callbacks).
//
// How it works: nativeBegin() creates a pipe and dup2()s it over fds 1 and 2,
// so every printf/fprintf from libjvm, LWJGL, SDL or OpenAL-Soft lands in the
// pipe. A detached logger thread drains the pipe, appends to the file, and
// forwards each chunk to SCLogger.onNativeLog() for the on-screen view.
// nativeAppend() lets Java add its own lines to the same file.
// ---------------------------------------------------------------------------
#include <jni.h>
#include <unistd.h>
#include <pthread.h>
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>
#include <android/log.h>

#define LOG_TAG "SCGame"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---- Capture state (pipe fds, log file, logger thread) ----
static int pipe_fds[2] = {-1, -1};
static pthread_t logger_thread_id;
static jmethodID on_native_log_mid = NULL;
static jobject log_class_ref = NULL;
static int log_fd = -1;
static JavaVM *cached_vm = NULL;

// ---- Pipe drain: file + Java callback + logcat mirror ----
static void write_to_file(const char *buf, ssize_t len) {
    if (log_fd != -1 && len > 0) {
        write(log_fd, buf, (size_t)len);
        fdatasync(log_fd);
    }
}

static void *logger_thread(void *arg) {
    (void)arg;
    JNIEnv *env = NULL;
    if ((*cached_vm)->AttachCurrentThread(cached_vm, &env, NULL) != JNI_OK) {
        LOGE("sc_logger: AttachCurrentThread failed");
        return NULL;
    }
    char buf[4096];
    ssize_t n;
    while ((n = read(pipe_fds[0], buf, sizeof(buf) - 1)) > 0) {
        write_to_file(buf, n);
        if (buf[n - 1] == '\n') n--;
        buf[n] = '\0';
        if (log_class_ref != NULL && on_native_log_mid != NULL) {
            jstring js = (*env)->NewStringUTF(env, buf);
            if (js != NULL) {
                jclass cls = (jclass)log_class_ref;
                (*env)->CallStaticVoidMethod(env, cls, on_native_log_mid, js);
                (*env)->DeleteLocalRef(env, js);
                if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            }
        }
        // Also mirror to logcat so `logcat -s SCGame` always has it.
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", buf);
    }
    (*cached_vm)->DetachCurrentThread(cached_vm);
    return NULL;
}

JNIEXPORT void JNICALL
Java_com_severedchains_launcher_SCLogger_nativeBegin(JNIEnv *env, jclass clazz, jstring logPath) {
    // Re-entry safe: close any previous capture before starting a new one.
    if (log_fd != -1) {
        close(log_fd);
        log_fd = -1;
    }
    if (on_native_log_mid == NULL) {
        on_native_log_mid = (*env)->GetStaticMethodID(env, clazz, "onNativeLog", "(Ljava/lang/String;)V");
    }
    if (log_class_ref == NULL) {
        log_class_ref = (*env)->NewGlobalRef(env, clazz);
    }
    (*env)->GetJavaVM(env, &cached_vm);

    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    if (pipe_fds[0] == -1) {
        if (pipe(pipe_fds) != 0) {
            LOGE("sc_logger: pipe failed: %s", strerror(errno));
            return;
        }
        dup2(pipe_fds[1], STDOUT_FILENO);
        dup2(pipe_fds[1], STDERR_FILENO);
    }

    const char *path = (*env)->GetStringUTFChars(env, logPath, NULL);
    log_fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (log_fd == -1) {
        LOGE("sc_logger: open %s failed: %s", path, strerror(errno));
    }
    (*env)->ReleaseStringUTFChars(env, logPath, path);

    pthread_create(&logger_thread_id, NULL, logger_thread, NULL);
    pthread_detach(logger_thread_id);
}

JNIEXPORT void JNICALL
Java_com_severedchains_launcher_SCLogger_nativeAppend(JNIEnv *env, jclass clazz, jstring text) {
    (void)clazz;
    if (text == NULL) return;
    const char *c = (*env)->GetStringUTFChars(env, text, NULL);
    size_t len = strlen(c);
    write_to_file(c, (ssize_t)len);
    write_to_file("\n", 1);
    (*env)->ReleaseStringUTFChars(env, text, c);
}
