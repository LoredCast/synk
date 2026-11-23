cmake_minimum_required(VERSION 3.18)
project(synk_native C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(SYNK_CORE_SOURCES
    src/sha256.c
    src/scanner.c
    src/diff3.c
    src/planner.c
    src/watcher.c
)

add_library(synk_native SHARED
    ${SYNK_CORE_SOURCES}
    jni/synk_jni.c
)

target_include_directories(synk_native PRIVATE include)

if(ANDROID)
    target_link_libraries(synk_native log)
else()
    find_package(JNI REQUIRED)
    target_include_directories(synk_native PRIVATE ${JNI_INCLUDE_DIRS})
    find_package(Threads REQUIRED)
    target_link_libraries(synk_native Threads::Threads)
endif()

target_compile_options(synk_native PRIVATE -O2 -Wall -Wextra -fvisibility=hidden)
