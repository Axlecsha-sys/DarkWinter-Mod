cmake_minimum_required(VERSION 3.20)
project(WinterKoreEngine VERSION 0.1.0 LANGUAGES CXX)
# Independent native build: no Minecraft, Fabric, Java, Gradle or game assets.
enable_testing()
add_subdirectory(core)
add_subdirectory(kernels/voxel-faces)
add_library(WKE::Core ALIAS dw_core)
add_library(WKE::Voxel ALIAS dw_voxel_faces)
set_target_properties(dw_core PROPERTIES OUTPUT_NAME wke_core)
set_target_properties(dw_voxel_faces PROPERTIES OUTPUT_NAME wke_voxel)

# Borrowed-device GPU backend is independent of Minecraft and the standalone host.
option(WKE_BUILD_EMBEDDED_RENDERER "Build the optional borrowed-device Vulkan renderer" OFF)
if(WKE_BUILD_EMBEDDED_RENDERER)
    add_subdirectory(runtime/embedded)
endif()

# The core/kernels remain buildable without graphics SDK headers or game assets.
option(WKE_BUILD_WINDOWS_RUNTIME "Build the optional Windows Vulkan runtime" OFF)
if(WKE_BUILD_WINDOWS_RUNTIME)
    add_subdirectory(runtime)
endif()
