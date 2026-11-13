# Builds the detached scalar light-prefix experiment; it is never linked into the mod.
cmake_minimum_required(VERSION 3.16)
project(darkwinter_light_prefix_experiment LANGUAGES CXX)
add_library(dw_light_prefix SHARED dw_light_prefix.cpp)
target_compile_features(dw_light_prefix PRIVATE cxx_std_17)
if(MSVC)
    target_compile_options(dw_light_prefix PRIVATE /W4 /O2)
else()
    target_compile_options(dw_light_prefix PRIVATE -Wall -Wextra -Wpedantic -O3)
endif()
set_target_properties(dw_light_prefix PROPERTIES
    OUTPUT_NAME "dw_light_prefix"
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)
