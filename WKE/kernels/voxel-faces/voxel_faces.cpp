// Independent experimental kernel. No Minecraft/Fabric types or dependencies.
#include <cstdint>
#include <cstring>
#include <limits>

static_assert(sizeof(float) == 4 && std::numeric_limits<float>::is_iec559,
              "The mesh ABI requires IEEE-754 binary32");

namespace {
float mesh_float(std::uint32_t bits) noexcept {
    float value;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}
std::uint32_t mesh_bits(float value) noexcept {
    std::uint32_t bits;
    std::memcpy(&bits, &value, sizeof(bits));
    return bits;
}
void mesh_put(std::uint8_t* output, std::uint32_t word) noexcept {
    // Explicit little-endian bytes; output need not be aligned.
    output[0] = static_cast<std::uint8_t>(word);
    output[1] = static_cast<std::uint8_t>(word >> 8);
    output[2] = static_cast<std::uint8_t>(word >> 16);
    output[3] = static_cast<std::uint8_t>(word >> 24);
}
}

#if defined(_WIN32)
#  define DW_EXPORT __declspec(dllexport)
#else
#  define DW_EXPORT __attribute__((visibility("default")))
#endif

extern "C" {

DW_EXPORT int dw_voxel_api_version() noexcept {
    return 1;
}

// Layout: halo[((y * 18 + z) * 18 + x)], with a one-cell border.
// Output: masks[((y * 16 + z) * 16 + x)] for the inner 16^3 cells.
// -X/+X/-Y/+Y/-Z/+Z => 1/2/4/8/16/32.
// Returns face count or -1 for invalid pointer/length.
DW_EXPORT int dw_visible_faces(const std::uint8_t* halo, int halo_length,
                               std::uint8_t* masks, int masks_length) noexcept {
    constexpr int edge = 16;
    constexpr int halo_edge = edge + 2;
    constexpr int halo_plane = halo_edge * halo_edge;
    constexpr int halo_volume = halo_edge * halo_edge * halo_edge;
    constexpr int volume = edge * edge * edge;

    if (halo == nullptr || masks == nullptr ||
        halo_length != halo_volume || masks_length != volume) {
        return -1;
    }

    int face_count = 0;
    for (int y = 0; y < edge; ++y) {
        for (int z = 0; z < edge; ++z) {
            const int in_row = (y + 1) * halo_plane + (z + 1) * halo_edge + 1;
            const int out_row = (y * edge + z) * edge;
            for (int x = 0; x < edge; ++x) {
                const int p = in_row + x;
                std::uint8_t mask = 0;
                if (halo[p] != 0) {
                    if (halo[p - 1] == 0)          { mask |= 1;  ++face_count; }
                    if (halo[p + 1] == 0)          { mask |= 2;  ++face_count; }
                    if (halo[p - halo_plane] == 0) { mask |= 4;  ++face_count; }
                    if (halo[p + halo_plane] == 0) { mask |= 8;  ++face_count; }
                    if (halo[p - halo_edge] == 0)  { mask |= 16; ++face_count; }
                    if (halo[p + halo_edge] == 0)  { mask |= 32; ++face_count; }
                }
                masks[out_row + x] = mask;
            }
        }
    }
    return face_count;
}

// Separate ABI: the original visible-face kernel remains version 1.
DW_EXPORT int dw_block_mesh_api_version() noexcept { return 1; }

// 20 uint32 words/template: 4 * (x,y,z,u,v), all raw binary32 bits.
// 12 words/instance: templateId, offsetX/Y/Z, ARGB[4], finalLight[4].
// Output: four 28-byte Minecraft BLOCK vertices, preserving quad and vertex order.
// All indices are checked BEFORE the first output write. No allocations, callbacks,
// retained pointers, GPU calls, ambient occlusion or world-state access.
DW_EXPORT int dw_pack_block_mesh(const std::uint32_t* templates, int template_words,
                                 const std::uint32_t* records, int record_words,
                                 std::uint8_t* output, int output_bytes) noexcept {
    constexpr int template_stride = 20;
    constexpr int record_stride = 12;
    constexpr int max_entries = 1024;
    if (template_words < 0 || template_words > max_entries * template_stride ||
        record_words < 0 || record_words > max_entries * record_stride ||
        template_words % template_stride != 0 || record_words % record_stride != 0 ||
        output_bytes < 0) return -1;
    const int quads = record_words / record_stride;
    if (quads == 0) return 0;
    const int template_count = template_words / template_stride;
    if (!templates || !records || !output || template_count == 0 || output_bytes < quads * 112) return -1;
    for (int q = 0; q < quads; ++q) {
        if (records[q * record_stride] >= static_cast<std::uint32_t>(template_count)) return -2;
    }
    std::uint8_t* dst = output;
    for (int q = 0; q < quads; ++q) {
        const std::uint32_t* r = records + q * record_stride;
        const std::uint32_t* t = templates + r[0] * template_stride;
        const float ox = mesh_float(r[1]);
        const float oy = mesh_float(r[2]);
        const float oz = mesh_float(r[3]);
        for (int v = 0; v < 4; ++v, dst += 28) {
            const std::uint32_t* p = t + v * 5;
            mesh_put(dst,      mesh_bits(mesh_float(p[0]) + ox));
            mesh_put(dst + 4,  mesh_bits(mesh_float(p[1]) + oy));
            mesh_put(dst + 8,  mesh_bits(mesh_float(p[2]) + oz));
            const std::uint32_t argb = r[4 + v];
            mesh_put(dst + 12, (argb & 0xff00ff00u) | ((argb >> 16) & 255u) | ((argb & 255u) << 16));
            mesh_put(dst + 16, p[3]);
            mesh_put(dst + 20, p[4]);
            mesh_put(dst + 24, r[8 + v]);
        }
    }
    return quads * 112;
}

// Fabric Indigo's already-lit 8-word vertices. Drop unused normals and convert ARGB;
// positions and UVs are copied bit-for-bit, with no second floating-point transform.
DW_EXPORT int dw_pack_indigo_mesh(const std::uint32_t* vertices, int input_words,
                                  std::uint8_t* output, int output_bytes) noexcept {
    if (input_words < 0 || input_words > 1024 * 32 || input_words % 32 != 0 || output_bytes < 0) return -1;
    const int quads = input_words / 32;
    if (quads == 0) return 0;
    if (!vertices || !output || output_bytes < quads * 112) return -1;
    for (int v = 0; v < quads * 4; ++v) {
        const std::uint32_t* s = vertices + v * 8;
        std::uint8_t* d = output + v * 28;
        mesh_put(d, s[0]); mesh_put(d + 4, s[1]); mesh_put(d + 8, s[2]);
        const std::uint32_t c = s[3];
        mesh_put(d + 12, (c & 0xff00ff00u) | ((c >> 16) & 255u) | ((c & 255u) << 16));
        mesh_put(d + 16, s[4]); mesh_put(d + 20, s[5]); mesh_put(d + 24, s[6]);
    }
    return quads * 112;
}

} // extern "C"
