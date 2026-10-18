cmake_minimum_required(VERSION 3.18.1)
project("jvmrunner" LANGUAGES C)

# Four small JNI bridges (each its own .so, loaded on demand from Java):
#   jvmrunner     - in-process JNI_CreateJavaVM + game Main() invocation
#   sc_logger     - stdout/stderr pipe capture -> latestlog.txt
#   sc_gamepad    - virtual SDL3 controller (touch + physical mirroring)
#   sc_audio_probe- OpenAL backend diagnostics (dlopen + device probe)
#   sc_sdlprobe   - queries SDL_TextInputActive() (auto soft keyboard)
#   sc_sdlassert  - installs an SDL assertion handler that logs + ignores,
#                   so a failing assert can never loop the messagebox forever
add_library(jvmrunner SHARED jvmrunner.c)

add_library(sc_logger SHARED sc_logger.c)

add_library(sc_gamepad SHARED sc_gamepad.c)

add_library(sc_audio_probe SHARED sc_audio_probe.c)

add_library(sc_sdlprobe SHARED sc_sdlprobe.c)

add_library(sc_sdlassert SHARED sc_sdlassert.c)

# NOTE: absolute SDL include path is a local-machine path (this dev box).
# sc_gamepad only needs it at compile time; runtime resolution is via dlsym.
target_include_directories(sc_gamepad PRIVATE
    /var/home/user/Projects/SCLauncher/MojoLauncher/sdl/include
)

target_link_libraries(sc_gamepad
    android
    log
    dl
)

target_link_libraries(sc_audio_probe
    android
    log
    dl
)

target_link_libraries(sc_sdlprobe
    log
    dl
)

target_link_libraries(sc_sdlassert
    log
    dl
)

target_link_libraries(sc_logger
    android
    log
)

target_link_libraries(jvmrunner
    android
    log
    dl
)

target_compile_options(jvmrunner PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
)

# Lite-only ISO-rip mitigation: lift RLIMIT_NOFILE before the JVM starts.
# Off by default (main APK); the Gradle '-Plite' build passes
# -DSC_RAISE_FD_LIMIT=ON for the side-by-side lite APK.
if(SC_RAISE_FD_LIMIT)
    target_compile_definitions(jvmrunner PRIVATE SC_RAISE_FD_LIMIT=1)
endif()