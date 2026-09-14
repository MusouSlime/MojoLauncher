cmake_minimum_required(VERSION 3.18.1)
project("jvmrunner" LANGUAGES C)

# Four small JNI bridges (each its own .so, loaded on demand from Java):
#   jvmrunner     - in-process JNI_CreateJavaVM + game Main() invocation
#   sc_logger     - stdout/stderr pipe capture -> latestlog.txt
#   sc_gamepad    - virtual SDL3 controller (touch + physical mirroring)
#   sc_audio_probe- OpenAL backend diagnostics (dlopen + device probe)
add_library(jvmrunner SHARED jvmrunner.c)

add_library(sc_logger SHARED sc_logger.c)

add_library(sc_gamepad SHARED sc_gamepad.c)

add_library(sc_audio_probe SHARED sc_audio_probe.c)

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