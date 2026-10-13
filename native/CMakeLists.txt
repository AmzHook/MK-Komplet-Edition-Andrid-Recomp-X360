cmake_minimum_required(VERSION 3.25)
project(mk_komplete_android LANGUAGES C CXX ASM)

# Experimental runtime integration. Successful compilation does not establish
# that MK boots or that its graphics and guest functions work on a device.
if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Use the Android NDK toolchain with ANDROID_ABI=arm64-v8a")
endif()
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build configuration" FORCE)
endif()
if(NOT CMAKE_BUILD_TYPE STREQUAL "Release")
    message(FATAL_ERROR "This experimental Android runtime requires CMAKE_BUILD_TYPE=Release")
endif()

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_compile_options(-fno-strict-aliasing -ffp-model=strict -fno-char8_t)

get_filename_component(MK_REPOSITORY_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)
set(SDK_ROOT "/workspace/.tools/mk-native/rexglue-sdk" CACHE PATH
    "Android-capable ReXGlue SDK source (pinned Asura SDK fork)")
set(MK_GENERATED_DIR
    "${MK_REPOSITORY_ROOT}/out/native/mk-komplete/generated/default" CACHE PATH
    "MK sources generated on the host by ReXGlue")
set(MK_NATIVE_OUTPUT_DIR "${CMAKE_BINARY_DIR}/lib" CACHE PATH
    "Directory containing the shared libraries to package in the APK")
set(MK_SDK_BUILD_DIR "/workspace/.tools/mk-native/build-android-arm64" CACHE PATH
    "Completed Android runtime build, providing generated SDK headers")
option(MK_REUSE_SDK_RUNTIME "Reuse verified Android runtime binaries" ON)

foreach(required CMakeLists.txt include/rex/rex_app.h)
    if(NOT EXISTS "${SDK_ROOT}/${required}")
        message(FATAL_ERROR "Missing SDK file: ${SDK_ROOT}/${required}")
    endif()
endforeach()
foreach(required sources.cmake mk_komplete_init.h mk_komplete_pch.h)
    if(NOT EXISTS "${MK_GENERATED_DIR}/${required}")
        message(FATAL_ERROR
            "Missing MK codegen output: ${MK_GENERATED_DIR}/${required}. Run host codegen first.")
    endif()
endforeach()

set(REXGLUE_ENABLE_TRACY OFF CACHE BOOL "" FORCE)
set(REXGLUE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(REXGLUE_USE_VULKAN ON CACHE BOOL "" FORCE)
set(REXGLUE_USE_D3D12 OFF CACHE BOOL "" FORCE)
if(MK_REUSE_SDK_RUNTIME)
    foreach(library librexruntime.so librexgpu-xenos.so)
        if(NOT EXISTS "${SDK_ROOT}/out/linux-arm64/${library}")
            message(FATAL_ERROR "Missing Android SDK runtime: ${library}. Run tools/build_runtime.sh first.")
        endif()
    endforeach()
    add_library(rexruntime SHARED IMPORTED GLOBAL)
    set_target_properties(rexruntime PROPERTIES
        IMPORTED_LOCATION "${SDK_ROOT}/out/linux-arm64/librexruntime.so")
    add_library(rex::runtime ALIAS rexruntime)
    target_include_directories(rexruntime INTERFACE
        "${SDK_ROOT}/include" "${MK_SDK_BUILD_DIR}/include"
        "${SDK_ROOT}/thirdparty/simde" "${SDK_ROOT}/thirdparty/fmt/include"
        "${SDK_ROOT}/thirdparty/spdlog/include" "${SDK_ROOT}/thirdparty/tomlplusplus/include"
        "${SDK_ROOT}/thirdparty/sdl3/include" "${SDK_ROOT}/thirdparty/vulkan-headers/include"
        "${SDK_ROOT}/thirdparty/vulkan-memory-allocator/include"
        "${SDK_ROOT}/thirdparty/spirv-tools/include" "${SDK_ROOT}/thirdparty/imgui")
    target_compile_definitions(rexruntime INTERFACE
        REX_HAS_VULKAN=1 SPDLOG_COMPILED_LIB SPDLOG_FMT_EXTERNAL)
    add_library(mk_sdk_gpu SHARED IMPORTED GLOBAL)
    set_target_properties(mk_sdk_gpu PROPERTIES
        IMPORTED_LOCATION "${SDK_ROOT}/out/linux-arm64/librexgpu-xenos.so")
    add_library(rex::gpu-xenos ALIAS mk_sdk_gpu)
    set(REXGLUE_SHARE_DIR "${SDK_ROOT}/src/ui")
    include("${SDK_ROOT}/cmake/rexglue_helpers.cmake")
else()
    add_subdirectory("${SDK_ROOT}" rexglue-sdk)
endif()

# Consume completed host codegen without invoking an ARM64 rexglue executable
# during the cross-compilation. Keep generated SDK boilerplate unchanged.
include("${MK_GENERATED_DIR}/sources.cmake")
if(NOT GENERATED_SOURCES)
    message(FATAL_ERROR "MK sources.cmake does not list any generated sources")
endif()
find_package(Python3 3.11 REQUIRED COMPONENTS Interpreter)
set(MK_BUILD_SOURCES "")
foreach(source IN LISTS GENERATED_SOURCES)
    if(source MATCHES "_register\\.cpp$")
        # Bound the startup registration functions while preserving every
        # statement and its order. Game bodies and codegen output stay intact.
        set(partitioned "${CMAKE_BINARY_DIR}/mk_komplete_register_partitioned.cpp")
        execute_process(COMMAND "${Python3_EXECUTABLE}"
            "${MK_REPOSITORY_ROOT}/tools/partition_registration.py" "${source}" "${partitioned}"
            COMMAND_ERROR_IS_FATAL ANY)
        set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
            "${source}" "${MK_REPOSITORY_ROOT}/tools/partition_registration.py")
        set(source "${partitioned}")
        set_source_files_properties("${partitioned}" PROPERTIES
            COMPILE_OPTIONS "-O0;-g0" SKIP_PRECOMPILE_HEADERS ON)
    endif()
    list(APPEND MK_BUILD_SOURCES "${source}")
endforeach()

add_library(mk_recomp OBJECT ${MK_BUILD_SOURCES})
target_include_directories(mk_recomp PRIVATE
    "${MK_GENERATED_DIR}"
    "${MK_REPOSITORY_ROOT}/out/native/mk-komplete"
    "${MK_REPOSITORY_ROOT}/out/native/mk-komplete/src")
target_precompile_headers(mk_recomp PRIVATE "${MK_GENERATED_DIR}/mk_komplete_pch.h")
target_compile_options(mk_recomp PRIVATE -g0 -Wno-unused-variable)
target_link_libraries(mk_recomp PRIVATE rex::runtime)
rexglue_apply_target_settings(mk_recomp)

add_library(mk_komplete SHARED mk_app.cpp)
target_include_directories(mk_komplete PRIVATE "${MK_GENERATED_DIR}")
target_link_libraries(mk_komplete PRIVATE
    mk_recomp rex::runtime log)
rexglue_configure_target(mk_komplete GPU_PLUGINS xenos)
target_link_options(mk_komplete PRIVATE -Wl,--export-dynamic -Wl,-z,max-page-size=16384)
set_target_properties(mk_komplete PROPERTIES
    CXX_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN OFF)

# SDL is linked statically. The runtime and Xenos GPU plugin must also be
# packaged; the plugin is loaded through dlopen rather than linked into MK.
set_target_properties(mk_komplete PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${MK_NATIVE_OUTPUT_DIR}")
add_custom_command(TARGET mk_komplete POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        $<TARGET_FILE:rex::runtime> "$<TARGET_FILE_DIR:mk_komplete>"
    VERBATIM)
