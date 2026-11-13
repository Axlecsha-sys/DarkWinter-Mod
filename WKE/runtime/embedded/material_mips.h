#pragma once
#include <stdint.h>
#ifdef _WIN32
#define WKE_MATERIAL_EXPORT __declspec(dllexport)
#else
#define WKE_MATERIAL_EXPORT __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif
// Independent CPU ABI1; these functions never create or access a graphics device.
// Pixels: tightly packed little-endian RGBA8, 0xAABBGGRR. levels includes level0.
// flags: bit0 preserve cutout coverage, bit1 pad RGB in alpha-zero texels.
WKE_MATERIAL_EXPORT uint32_t wke_material_api_version(void);
WKE_MATERIAL_EXPORT uint64_t wke_material_mip_words(uint32_t width, uint32_t height, uint32_t levels);
WKE_MATERIAL_EXPORT int32_t wke_material_build_mips(
    const uint32_t* source, uint64_t source_words, uint32_t width, uint32_t height,
    uint32_t levels, float alpha_reference, uint32_t flags,
    uint32_t* output, uint64_t output_words, double* coverage, uint64_t coverage_count);
#ifdef __cplusplus
}
#endif
