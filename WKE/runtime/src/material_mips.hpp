#pragma once
// Shared CPU material filtering for embedded and standalone WKE hosts.
// Canonical pixels are little-endian RGBA8 words: 0xAABBGGRR.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <span>
#include <stdexcept>
#include <vector>

namespace wke::materials {
inline constexpr uint32_t cutout = 1u;
inline constexpr uint32_t color_padding = 2u;
inline constexpr uint64_t maximum_words = 32ull * 1024 * 1024;

inline uint64_t mip_words(uint32_t width, uint32_t height, uint32_t levels) noexcept {
    if (!width || !height || !levels || levels > 16 || width > 16384 || height > 16384) return 0;
    uint64_t total = 0;
    for (uint32_t level = 0; level < levels; ++level) {
        const uint64_t count = uint64_t(width) * height;
        if (total > maximum_words - std::min(count, maximum_words) || count > maximum_words) return 0;
        total += count;
        if (level + 1 < levels && width == 1 && height == 1) return 0;
        width = std::max(1u, width >> 1);
        height = std::max(1u, height >> 1);
    }
    return total;
}

inline float linear_channel(uint32_t channel) noexcept {
    const float value = float(channel) / 255.f;
    return value <= .04045f ? value / 12.92f : std::pow((value + .055f) / 1.055f, 2.4f);
}
inline uint32_t encode_channel(float value) noexcept {
    value = std::clamp(value, 0.f, 1.f);
    const float encoded = value <= .0031308f ? value * 12.92f : 1.055f * std::pow(value, 1.f / 2.4f) - .055f;
    return uint32_t(std::clamp(std::lround(encoded * 255.f), 0l, 255l));
}
inline uint32_t scaled_alpha(uint32_t pixel, float scale) noexcept {
    return uint32_t(std::clamp(std::lround(float(pixel >> 24) * scale), 0l, 255l));
}

// Measure the same bilinear alpha field that a linear GPU sampler tests.
// Tiny terminal mips use texel centres, avoiding division by zero on 1xN images.
inline double coverage(std::span<const uint32_t> pixels, uint32_t width, uint32_t height,
                       float reference, float scale = 1.f) noexcept {
    const float threshold = reference * 255.f;
    uint64_t hits = 0, samples = 0;
    if (width < 2 || height < 2) {
        for (uint32_t pixel : pixels) hits += float(scaled_alpha(pixel, scale)) >= threshold;
        return pixels.empty() ? 0. : double(hits) / double(pixels.size());
    }
    constexpr uint32_t subdivisions = 4;
    for (uint32_t y = 0; y + 1 < height; ++y) for (uint32_t x = 0; x + 1 < width; ++x) {
        const float a = float(scaled_alpha(pixels[size_t(y) * width + x], scale));
        const float b = float(scaled_alpha(pixels[size_t(y) * width + x + 1], scale));
        const float c = float(scaled_alpha(pixels[size_t(y + 1) * width + x], scale));
        const float d = float(scaled_alpha(pixels[size_t(y + 1) * width + x + 1], scale));
        for (uint32_t sy = 0; sy < subdivisions; ++sy) {
            const float fy = (float(sy) + .5f) / float(subdivisions);
            const float left = a + (c - a) * fy, right = b + (d - b) * fy;
            for (uint32_t sx = 0; sx < subdivisions; ++sx) {
                const float fx = (float(sx) + .5f) / float(subdivisions);
                hits += left + (right - left) * fx >= threshold;
            }
        }
        samples += subdivisions * subdivisions;
    }
    return samples ? double(hits) / double(samples) : 0.;
}

inline void fit_coverage(std::span<uint32_t> pixels, uint32_t width, uint32_t height,
                         float reference, double desired) noexcept {
    if (desired <= 0. || desired >= 1.) return;
    float low = 0.f, high = 16.f, best = 1.f;
    double error = std::abs(coverage(pixels, width, height, reference) - desired);
    auto consider = [&](float scale) {
        const double current = coverage(pixels, width, height, reference, scale);
        const double candidate = std::abs(current - desired);
        if (candidate < error || (candidate == error && std::abs(scale - 1.f) < std::abs(best - 1.f))) {
            best = scale;
            error = candidate;
        }
        return current;
    };
    consider(low);
    consider(high);
    for (uint32_t step = 0; step < 16; ++step) {
        const float scale = (low + high) * .5f;
        if (consider(scale) < desired) low = scale; else high = scale;
    }
    for (uint32_t& pixel : pixels) pixel = (pixel & 0x00ffffffu) | (scaled_alpha(pixel, best) << 24);
}

inline void pad_transparent_rgb(std::span<uint32_t> pixels, uint32_t width, uint32_t height, int radius) {
    // Never alter alpha or a nonzero-alpha texel's authored colour.
    const std::vector<uint32_t> source(pixels.begin(), pixels.end());
    for (uint32_t y = 0; y < height; ++y) for (uint32_t x = 0; x < width; ++x) {
        const size_t at = size_t(y) * width + x;
        if (source[at] >> 24) continue;
        int best_distance = std::numeric_limits<int>::max();
        uint32_t best = 0;
        for (int dy = -radius; dy <= radius; ++dy) for (int dx = -radius; dx <= radius; ++dx) {
            const int xx = int(x) + dx, yy = int(y) + dy;
            if (xx < 0 || yy < 0 || xx >= int(width) || yy >= int(height)) continue;
            const uint32_t candidate = source[size_t(yy) * width + uint32_t(xx)];
            if (!(candidate >> 24)) continue;
            const int distance = dx * dx + dy * dy;
            if (distance < best_distance || (distance == best_distance && (candidate >> 24) > (best >> 24))) {
                best_distance = distance;
                best = candidate;
            }
        }
        if (best_distance != std::numeric_limits<int>::max()) pixels[at] = best & 0x00ffffffu;
    }
}

// Output contains level0, level1, ... as tightly packed rows. The source may alias
// output's level0 exactly; no adjusted mip is ever fed into generation of another.
inline void build(std::span<const uint32_t> source, uint32_t width, uint32_t height, uint32_t levels,
                  float reference, uint32_t flags, std::span<uint32_t> output, std::span<double> coverages = {}) {
    const uint64_t words = mip_words(width, height, levels);
    if (!words || source.size() != uint64_t(width) * height || output.size() < words ||
        !std::isfinite(reference) || reference <= 0.f || reference >= 1.f || (flags & ~3u) ||
        (!coverages.empty() && coverages.size() < levels))
        throw std::invalid_argument("WKE material mip request");
    if (source.data() != output.data()) std::copy(source.begin(), source.end(), output.begin());
    std::array<size_t, 16> offsets{};
    std::array<uint32_t, 16> widths{}, heights{};
    widths[0] = width;
    heights[0] = height;
    static const std::array<float, 256> linear = [] {
        std::array<float, 256> values{};
        for (uint32_t i = 0; i < 256; ++i) values[i] = linear_channel(i);
        return values;
    }();
    for (uint32_t level = 1; level < levels; ++level) {
        const uint32_t previous_width = widths[level - 1], previous_height = heights[level - 1];
        widths[level] = std::max(1u, previous_width >> 1);
        heights[level] = std::max(1u, previous_height >> 1);
        offsets[level] = offsets[level - 1] + size_t(previous_width) * previous_height;
        for (uint32_t y = 0; y < heights[level]; ++y) for (uint32_t x = 0; x < widths[level]; ++x) {
            float red = 0.f, green = 0.f, blue = 0.f, alpha = 0.f, weight = 0.f, area = 0.f;
            const float x0 = float(x) * float(previous_width) / float(widths[level]);
            const float x1 = float(x + 1) * float(previous_width) / float(widths[level]);
            const float y0 = float(y) * float(previous_height) / float(heights[level]);
            const float y1 = float(y + 1) * float(previous_height) / float(heights[level]);
            for (uint32_t yy = uint32_t(y0); yy < std::min(uint32_t(std::ceil(y1)), previous_height); ++yy)
                for (uint32_t xx = uint32_t(x0); xx < std::min(uint32_t(std::ceil(x1)), previous_width); ++xx) {
                    const float sample_area = std::max(0.f, std::min(x1, float(xx + 1)) - std::max(x0, float(xx))) *
                        std::max(0.f, std::min(y1, float(yy + 1)) - std::max(y0, float(yy)));
                    const uint32_t pixel = output[offsets[level - 1] + size_t(yy) * previous_width + xx];
                    const float opacity = float(pixel >> 24) / 255.f;
                    const float premultiplied_weight = opacity * sample_area;
                    red += linear[pixel & 255u] * premultiplied_weight;
                    green += linear[(pixel >> 8) & 255u] * premultiplied_weight;
                    blue += linear[(pixel >> 16) & 255u] * premultiplied_weight;
                    alpha += premultiplied_weight;
                    weight += premultiplied_weight;
                    area += sample_area;
                }
            const float inverse = weight > 0.f ? 1.f / weight : 0.f;
            const uint32_t opacity = uint32_t(std::clamp(std::lround(alpha * 255.f / area), 0l, 255l));
            output[offsets[level] + size_t(y) * widths[level] + x] =
                encode_channel(red * inverse) | (encode_channel(green * inverse) << 8) |
                (encode_channel(blue * inverse) << 16) | (opacity << 24);
        }
    }
    const double desired = (flags & cutout) ? coverage(output.first(source.size()), width, height, reference) : 0.;
    for (uint32_t level = 0; level < levels; ++level) {
        const size_t count = size_t(widths[level]) * heights[level];
        auto image = output.subspan(offsets[level], count);
        if ((flags & cutout) && level != 0) fit_coverage(image, widths[level], heights[level], reference, desired);
        if (flags & color_padding) pad_transparent_rgb(image, widths[level], heights[level], level == 0 ? 2 : 1);
        if (!coverages.empty()) coverages[level] = coverage(image, widths[level], heights[level], reference);
    }
}
}
