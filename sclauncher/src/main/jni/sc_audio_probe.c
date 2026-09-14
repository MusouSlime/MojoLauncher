// Probes libopenal.so directly: dlopen + alcOpenDevice + alcCreateContext +
// alcMakeContextCurrent + alGetString(AL_VERSION) per backend candidate.
// No link-time dependency on OpenAL (all dlsym), so a broken audio backend
// can never break the probe itself.
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>

typedef void *(*alcOpenDevice_t)(const char *);
typedef int (*alcCloseDevice_t)(void *);
typedef void *(*alcCreateContext_t)(void *, const int *);
typedef int (*alcMakeContextCurrent_t)(void *);
typedef void (*alcDestroyContext_t)(void *);
typedef const char *(*alcGetString_t)(void *, int);
typedef int (*alcGetError_t)(void *);
typedef const char *(*alGetString_t)(int);

#define ALC_DEFAULT_ALL_DEVICES_SPECIFIER 0x1012
#define AL_VERSION 0xB002

static void probe_proc_addresses(void *h, alGetString_t pAlStr, char *report, size_t size, size_t *off) {
    typedef void *(*alGetProcAddress_t)(const char *);
    alGetProcAddress_t pAddr = (alGetProcAddress_t)dlsym(h, "alGetProcAddress");
    if (!pAddr) {
        *off += snprintf(report + *off, size - *off, "audioprobe: alGetProcAddress MISSING\n");
        return;
    }
    static const char *names[] = {
    "alGetError",
    "alGetErrorDirect",
    "alEnable",
    "alEnableDirect",
    "alDisable",
    "alDisableDirect",
    "alIsEnabled",
    "alIsEnabledDirect",
    "alGetBoolean",
    "alGetBooleanDirect",
    "alGetInteger",
    "alGetIntegerDirect",
    "alGetFloat",
    "alGetFloatDirect",
    "alGetDouble",
    "alGetDoubleDirect",
    "alGetBooleanv",
    "alGetBooleanvDirect",
    "alGetIntegerv",
    "alGetIntegervDirect",
    "alGetFloatv",
    "alGetFloatvDirect",
    "alGetDoublev",
    "alGetDoublevDirect",
    "alGetString",
    "alGetStringDirect",
    "alDistanceModel",
    "alDistanceModelDirect",
    "alDopplerFactor",
    "alDopplerFactorDirect",
    "alDopplerVelocity",
    "alListenerf",
    "alListenerfDirect",
    "alListeneri",
    "alListeneriDirect",
    "alListener3f",
    "alListener3fDirect",
    "alListenerfv",
    "alListenerfvDirect",
    "alGetListenerf",
    "alGetListenerfDirect",
    "alGetListeneri",
    "alGetListeneriDirect",
    "alGetListener3f",
    "alGetListener3fDirect",
    "alGetListenerfv",
    "alGetListenerfvDirect",
    "alGenSources",
    "alGenSourcesDirect",
    "alDeleteSources",
    "alDeleteSourcesDirect",
    "alIsSource",
    "alIsSourceDirect",
    "alSourcef",
    "alSourcefDirect",
    "alSource3f",
    "alSource3fDirect",
    "alSourcefv",
    "alSourcefvDirect",
    "alSourcei",
    "alSourceiDirect",
    "alGetSourcef",
    "alGetSourcefDirect",
    "alGetSource3f",
    "alGetSource3fDirect",
    "alGetSourcefv",
    "alGetSourcefvDirect",
    "alGetSourcei",
    "alGetSourceiDirect",
    "alGetSourceiv",
    "alGetSourceivDirect",
    "alSourceQueueBuffers",
    "alSourceQueueBuffersDirect",
    "alSourceUnqueueBuffers",
    "alSourceUnqueueBuffersDirect",
    "alSourcePlay",
    "alSourcePlayDirect",
    "alSourcePause",
    "alSourcePauseDirect",
    "alSourceStop",
    "alSourceStopDirect",
    "alSourceRewind",
    "alSourceRewindDirect",
    "alSourcePlayv",
    "alSourcePlayvDirect",
    "alSourcePausev",
    "alSourcePausevDirect",
    "alSourceStopv",
    "alSourceStopvDirect",
    "alSourceRewindv",
    "alSourceRewindvDirect",
    "alGenBuffers",
    "alGenBuffersDirect",
    "alDeleteBuffers",
    "alDeleteBuffersDirect",
    "alIsBuffer",
    "alIsBufferDirect",
    "alGetBufferf",
    "alGetBufferfDirect",
    "alGetBufferi",
    "alGetBufferiDirect",
    "alBufferData",
    "alBufferDataDirect",
    "alGetEnumValue",
    "alGetEnumValueDirect",
    "alGetProcAddress",
    "alGetProcAddressDirect",
    "alIsExtensionPresent",
    "alIsExtensionPresentDirect"
    };
    int total = (int)(sizeof(names) / sizeof(names[0]));
    int missing = 0;
    char missingBuf[2048];
    missingBuf[0] = '\0';
    size_t moff = 0;
    for (int i = 0; i < total; i++) {
        /* A context must be current for proc lookup; caller guarantees it. */
        void *addr = pAddr(names[i]);
        if (!addr) {
            missing++;
            moff += snprintf(missingBuf + moff, sizeof(missingBuf) - moff, "%s%s", moff ? "," : "", names[i]);
            if (moff > sizeof(missingBuf) - 64) break;
        }
    }
    *off += snprintf(report + *off, size - *off,
                     "audioprobe: alGetProcAddress sweep: %d/%d resolved, missing=%s\n",
                     total - missing, total, missing ? missingBuf : "(none)");
    (void)pAlStr;
}

JNIEXPORT jstring JNICALL
Java_com_severedchains_launcher_SCAudioProbe_probe(
        JNIEnv *env, jclass clazz, jstring libPath) {
    (void)clazz;
    static char report[4096];
    report[0] = '\0';
    size_t off = 0;

    const char *path = (*env)->GetStringUTFChars(env, libPath, NULL);

    void *h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        snprintf(report, sizeof(report), "audioprobe: dlopen FAILED: %s",
                 dlerror() ? dlerror() : "?");
        (*env)->ReleaseStringUTFChars(env, libPath, path);
        return (*env)->NewStringUTF(env, report);
    }
    off += snprintf(report + off, sizeof(report) - off,
                    "audioprobe: dlopen ok: %s\n", path);

    alcOpenDevice_t pOpen = (alcOpenDevice_t)dlsym(h, "alcOpenDevice");
    alcCloseDevice_t pClose = (alcCloseDevice_t)dlsym(h, "alcCloseDevice");
    alcCreateContext_t pCreate = (alcCreateContext_t)dlsym(h, "alcCreateContext");
    alcMakeContextCurrent_t pMake = (alcMakeContextCurrent_t)dlsym(h, "alcMakeContextCurrent");
    alcDestroyContext_t pDestroy = (alcDestroyContext_t)dlsym(h, "alcDestroyContext");
    alcGetString_t pAlcStr = (alcGetString_t)dlsym(h, "alcGetString");
    alcGetError_t pAlcErr = (alcGetError_t)dlsym(h, "alcGetError");
    alGetString_t pAlStr = (alGetString_t)dlsym(h, "alGetString");

    if (!pOpen || !pClose || !pCreate || !pMake || !pDestroy || !pAlcStr || !pAlcErr || !pAlStr) {
        off += snprintf(report + off, sizeof(report) - off,
                        "audioprobe: dlsym FAILED (missing alc/al symbols)\n");
        dlclose(h);
        (*env)->ReleaseStringUTFChars(env, libPath, path);
        return (*env)->NewStringUTF(env, report);
    }

    const char *def = pAlcStr(NULL, ALC_DEFAULT_ALL_DEVICES_SPECIFIER);
    off += snprintf(report + off, sizeof(report) - off,
                    "audioprobe: default device: %s\n", def ? def : "(null)");

    static const char *cands[] = { NULL, "OpenSL", "Oboe", NULL };
    static const char *names[] = { "(default)", "OpenSL", "Oboe" };
    for (int i = 0; i < 3; i++) {
        void *dev = pOpen(cands[i]);
        if (!dev) {
            off += snprintf(report + off, sizeof(report) - off,
                            "audioprobe: [%s] alcOpenDevice FAILED err=%d\n",
                            names[i], 0);
            continue;
        }
        const int attrs[] = { 0 };
        void *ctx = pCreate(dev, attrs);
        if (!ctx) {
            int e = pAlcErr(dev);
            off += snprintf(report + off, sizeof(report) - off,
                            "audioprobe: [%s] open ok, alcCreateContext FAILED err=%d\n",
                            names[i], e);
            pClose(dev);
            continue;
        }
        int mc = pMake(ctx);
        const char *ver = pAlStr(AL_VERSION);
        int e = pAlcErr(dev);
        off += snprintf(report + off, sizeof(report) - off,
                        "audioprobe: [%s] open ok, create ok, makecurrent=%d, version=%s, err=%d\n",
                        names[i], mc, ver ? ver : "(null)", e);
        probe_proc_addresses(h, pAlStr, report, sizeof(report), &off);
        pMake(NULL);
        pDestroy(ctx);
        pClose(dev);
        if (off > sizeof(report) - 256) break;
    }

    dlclose(h);
    (*env)->ReleaseStringUTFChars(env, libPath, path);
    return (*env)->NewStringUTF(env, report);
}
