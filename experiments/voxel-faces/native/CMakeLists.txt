cmake_minimum_required(VERSION 3.16)
project(darkwinter_voxel_faces_experiment LANGUAGES CXX)

add_library(dw_voxel_faces SHARED voxel_faces.cpp)
target_compile_features(dw_voxel_faces PRIVATE cxx_std_17)

if(MSVC)
    target_compile_options(dw_voxel_faces PRIVATE /W4 /O2 /fp:strict)
else()
    target_compile_options(dw_voxel_faces PRIVATE -Wall -Wextra -Wpedantic -O3 -fno-fast-math -ffp-contract=off)
endif()

# This binary is strictly experimental; it is not linked or packaged into the mod.
set_target_properties(dw_voxel_faces PROPERTIES
    OUTPUT_NAME "dw_voxel_faces"
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)
