#include "material_mips.h"
#include "../src/material_mips.hpp"
#include <new>
#include <limits>

uint32_t wke_material_api_version(void) { return 1; }
uint64_t wke_material_mip_words(uint32_t width, uint32_t height, uint32_t levels) {
    return wke::materials::mip_words(width, height, levels);
}
int32_t wke_material_build_mips(const uint32_t* source, uint64_t source_words,
    uint32_t width, uint32_t height, uint32_t levels, float alpha_reference, uint32_t flags,
    uint32_t* output, uint64_t output_words, double* coverage, uint64_t coverage_count) {
    const uint64_t required = wke::materials::mip_words(width, height, levels);
    if (!required || !source || !output || source_words != uint64_t(width) * height ||
        output_words < required || source_words > wke::materials::maximum_words ||
        output_words > wke::materials::maximum_words ||
        (coverage && (coverage_count < levels || coverage_count > 16)) || (!coverage && coverage_count != 0))
        return 1;
    try {
        wke::materials::build({source, size_t(source_words)}, width, height, levels, alpha_reference, flags,
            {output, size_t(output_words)}, coverage ? std::span<double>(coverage, size_t(coverage_count)) : std::span<double>{});
        return 0;
    } catch (const std::invalid_argument&) {
        return 1;
    } catch (const std::bad_alloc&) {
        return 2;
    } catch (...) {
        return 3;
    }
}
