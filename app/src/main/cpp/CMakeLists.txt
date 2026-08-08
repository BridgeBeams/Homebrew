cmake_minimum_required(VERSION 3.22.1)
project(waide)

include(FetchContent)

# Pinned llama.cpp build tag. If you ever want a newer engine, change this one
# line to a newer tag from https://github.com/ggml-org/llama.cpp/tags
# and push. If a future tag ever breaks the build, come back to this one.
set(LLAMA_TAG "b9453")

set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        ${LLAMA_TAG}
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

add_library(waide SHARED waide-jni.cpp)

target_link_libraries(waide
    llama
    android
    log
)

target_compile_options(waide PRIVATE -O3 -fexceptions)
