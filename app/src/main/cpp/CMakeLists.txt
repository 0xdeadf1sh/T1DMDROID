# Vulkan capability-probe JNI shim for the Hardware panel (issue 20 — STEP 5).
# arm64-v8a only; links the NDK Vulkan loader (libvulkan.so, present since API 24).
cmake_minimum_required(VERSION 3.22.1)
project(t1dmvk LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(t1dmvk SHARED vulkan_probe.cpp)

# libvulkan is the ICD loader shipped in the NDK sysroot; find it there.
find_library(vulkan-lib vulkan)
target_link_libraries(t1dmvk ${vulkan-lib})
