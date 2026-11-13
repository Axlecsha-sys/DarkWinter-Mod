# Optional Windows-owned Vulkan runtime. This directory is entered only when
# WKE_BUILD_WINDOWS_RUNTIME is enabled by the parent project.
if(NOT WIN32)
    message(FATAL_ERROR "WKE Windows runtime requires a Windows target toolchain.")
endif()

set(WKE_VULKAN_INCLUDE_DIR "" CACHE PATH
    "Vulkan-Headers include directory containing vulkan/vulkan_core.h")
set(WKE_VMA_INCLUDE_DIR "" CACHE PATH
    "Vulkan Memory Allocator directory containing vk_mem_alloc.h")
foreach(header vulkan/vulkan_core.h vulkan/vulkan_win32.h)
    if(NOT EXISTS "${WKE_VULKAN_INCLUDE_DIR}/${header}")
        message(FATAL_ERROR
            "Set WKE_VULKAN_INCLUDE_DIR to a complete Vulkan-Headers include tree; missing ${header}.")
    endif()
endforeach()
if(NOT EXISTS "${WKE_VMA_INCLUDE_DIR}/vk_mem_alloc.h")
    message(FATAL_ERROR
        "Set WKE_VMA_INCLUDE_DIR to the directory containing vk_mem_alloc.h.")
endif()

find_package(Threads REQUIRED)

# Scene uses the C++ snapshot/job implementation, not only the public C ABI.
# Keep this static implementation separate from the core's public shared library.
add_library(wke_runtime_core_impl STATIC
    ../core/src/core.cpp
    ../core/src/format.cpp)
target_compile_features(wke_runtime_core_impl PUBLIC cxx_std_20)
target_include_directories(wke_runtime_core_impl PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}/../core/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/../core/src")
target_link_libraries(wke_runtime_core_impl PUBLIC Threads::Threads)

add_library(wke_runtime STATIC
    src/renderer.cpp
    src/scene.cpp
    src/vma.cpp)
add_library(WKE::Runtime ALIAS wke_runtime)
target_compile_features(wke_runtime PUBLIC cxx_std_20)
target_include_directories(wke_runtime PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${CMAKE_CURRENT_SOURCE_DIR}/../core/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/../core/src")
# Public headers expose Vulkan/VMA types, so consumers need these same headers
# and VMA dispatch definitions. No Vulkan loader import library is required:
# vulkan_api.hpp loads the system Vulkan loader explicitly.
target_include_directories(wke_runtime SYSTEM PUBLIC
    "${WKE_VULKAN_INCLUDE_DIR}"
    "${WKE_VMA_INCLUDE_DIR}")
target_compile_definitions(wke_runtime PUBLIC
    UNICODE _UNICODE NOMINMAX
    VMA_VULKAN_VERSION=1001000
    VMA_STATIC_VULKAN_FUNCTIONS=0
    VMA_DYNAMIC_VULKAN_FUNCTIONS=1)
target_link_libraries(wke_runtime PRIVATE
    wke_runtime_core_impl Threads::Threads user32 shell32 gdi32)

foreach(target wke_runtime_core_impl wke_runtime)
    set_target_properties(${target} PROPERTIES POSITION_INDEPENDENT_CODE ON)
    if(MSVC)
        target_compile_options(${target} PRIVATE /W4 /permissive- /fp:strict)
    else()
        target_compile_options(${target} PRIVATE
            -Wall -Wextra -Wpedantic -fno-fast-math -ffp-contract=off)
    endif()
endforeach()
