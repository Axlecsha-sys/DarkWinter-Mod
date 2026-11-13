// Implements the experimental detached block-light FIFO prefix without owning live world data.
#include "dw_light_prefix.h"
#include <array>
#include <cstddef>
#include <cstring>
#include <limits>
#include <new>
#include <vector>

static_assert(sizeof(dw_light_cells) == 56, "This experimental ABI requires 64-bit pointers.");
static_assert(sizeof(dw_light_queue_entry) == 16 && sizeof(dw_light_write) == 16);
static_assert(sizeof(dw_light_prefix_result) == 16);

namespace {
struct Region { uintptr_t first; uintptr_t end; };
bool region(const void* pointer, size_t bytes, size_t alignment, Region& out) {
    auto first = reinterpret_cast<uintptr_t>(pointer);
    if (bytes == 0) { out = {first, first}; return true; }
    if (pointer == nullptr || first % alignment != 0 ||
        bytes > std::numeric_limits<uintptr_t>::max() - first) return false;
    out = {first, first + bytes};
    return true;
}
bool overlaps(Region a, Region b) {
    return a.first != a.end && b.first != b.end && a.first < b.end && b.first < a.end;
}
int32_t signed_component(uint64_t value, uint32_t bits) {
    const uint32_t sign = uint32_t(1) << (bits - 1);
    const uint32_t masked = static_cast<uint32_t>(value) & ((uint32_t(1) << bits) - 1);
    return masked < sign ? static_cast<int32_t>(masked)
                         : static_cast<int32_t>(static_cast<int64_t>(masked) - (int64_t(1) << bits));
}
int32_t x_of(int64_t p) { return signed_component(static_cast<uint64_t>(p) >> 38, 26); }
int32_t y_of(int64_t p) { return signed_component(static_cast<uint64_t>(p), 12); }
int32_t z_of(int64_t p) { return signed_component(static_cast<uint64_t>(p) >> 12, 26); }
int64_t position(int32_t x, int32_t y, int32_t z) {
    uint64_t bits = ((static_cast<uint64_t>(x) & 0x3ffffffULL) << 38) |
                    ((static_cast<uint64_t>(z) & 0x3ffffffULL) << 12) |
                    (static_cast<uint64_t>(y) & 0xfffULL);
    int64_t result; std::memcpy(&result, &bits, sizeof result); return result;
}
bool cell_index(const dw_light_cells& c, int64_t p, size_t& index) {
    const int64_t x = int64_t(x_of(p)) - c.min_x;
    const int64_t y = int64_t(y_of(p)) - c.min_y;
    const int64_t z = int64_t(z_of(p)) - c.min_z;
    if (x < 0 || y < 0 || z < 0 || x >= c.width || y >= c.height || z >= c.depth) return false;
    index = static_cast<size_t>((y * c.depth + z) * c.width + x);
    return true;
}
bool bounds(const dw_light_cells& c) {
    if (!c.width || !c.height || !c.depth ||
        c.width > DW_LIGHT_MAX_AXIS || c.height > DW_LIGHT_MAX_AXIS || c.depth > DW_LIGHT_MAX_AXIS)
        return false;
    const uint64_t count = uint64_t(c.width) * c.height * c.depth;
    if (count != c.cell_count || count > DW_LIGHT_MAX_CELLS) return false;
    // Include the end points in representable BlockPos coordinates; offsets wrap like BlockPos.
    return c.min_x >= -33554432 && int64_t(c.min_x) + c.width - 1 <= 33554431 &&
           c.min_z >= -33554432 && int64_t(c.min_z) + c.depth - 1 <= 33554431 &&
           c.min_y >= -2048 && int64_t(c.min_y) + c.height - 1 <= 2047;
}
int32_t compute(const dw_light_cells& c, const dw_light_queue_entry* initial, uint32_t initial_count,
                uint32_t max_steps, dw_light_write* writes, uint32_t write_capacity,
                dw_light_queue_entry* remaining, uint32_t queue_capacity,
                dw_light_prefix_result* result) {
    std::vector<uint8_t> levels(c.levels, c.levels + c.cell_count);
    std::vector<dw_light_queue_entry> queue(queue_capacity);
    for (uint32_t i = 0; i < initial_count; ++i) queue[i] = initial[i];
    std::vector<dw_light_write> output;
    output.reserve(write_capacity);
    size_t head = 0, queued = initial_count;
    uint32_t processed = 0;
    int32_t status = DW_LIGHT_DONE;
    constexpr int dx[6] = {0,0,0,0,-1,1};
    constexpr int dy[6] = {-1,1,0,0,0,0};
    constexpr int dz[6] = {0,0,-1,1,0,0};
    while (queued != 0 && processed < max_steps) {
        const auto current = queue[head];
        size_t from_index;
        if (!cell_index(c, current.position, from_index) || !(c.flags[from_index] & DW_LIGHT_KNOWN) ||
            !(c.flags[from_index] & DW_LIGHT_STORING)) {
            status = DW_LIGHT_BOUNDARY; break;
        }
        const uint32_t target = static_cast<uint32_t>(current.entry & 15);
        uint32_t from_level = levels[from_index];
        std::array<dw_light_write, 7> step_writes{};
        std::array<size_t, 7> step_indices{};
        std::array<dw_light_queue_entry, 6> generated{};
        size_t changed = 0, added = 0;
        if ((current.entry & 2048) && from_level < target) {
            step_indices[changed] = from_index;
            step_writes[changed++] = {current.position, target, 0};
            from_level = target;
        }
        if (from_level == target) {
            for (int direction = 0; direction < 6; ++direction) {
                if (!(current.entry & (uint64_t(1) << (direction + 4)))) continue;
                const int64_t to = position(x_of(current.position) + dx[direction],
                    y_of(current.position) + dy[direction], z_of(current.position) + dz[direction]);
                size_t to_index;
                if (!cell_index(c, to, to_index) || !(c.flags[to_index] & DW_LIGHT_KNOWN)) {
                    status = DW_LIGHT_BOUNDARY; break;
                }
                if (!(c.flags[to_index] & DW_LIGHT_STORING)) continue;
                const int old_level = levels[to_index];
                if (static_cast<int>(from_level) - 1 <= old_level) continue;
                const int next_level = static_cast<int>(from_level) - c.opacity[to_index];
                if (next_level <= old_level) continue;
                if (!(c.flags[to_index] & DW_LIGHT_SIMPLE_EMPTY_SHAPE) ||
                    (!(current.entry & 1024) && !(c.flags[from_index] & DW_LIGHT_SIMPLE_EMPTY_SHAPE))) {
                    status = DW_LIGHT_UNSUPPORTED_SHAPE; break;
                }
                step_indices[changed] = to_index;
                step_writes[changed++] = {to, static_cast<uint32_t>(next_level), 0};
                if (next_level > 1) {
                    uint64_t data = uint64_t(next_level) | 1024 | (1008 & ~(uint64_t(1) << ((direction ^ 1) + 4)));
                    generated[added++] = {to, data};
                }
            }
        }
        if (status != DW_LIGHT_DONE) break;
        if (changed > write_capacity - output.size() || queued - 1 + added > queue_capacity) {
            status = DW_LIGHT_CAPACITY; break;
        }
        // Commit this complete entry to private scratch only; caller outputs remain untouched.
        for (size_t i = 0; i < changed; ++i) {
            levels[step_indices[i]] = static_cast<uint8_t>(step_writes[i].level);
            output.push_back(step_writes[i]);
        }
        head = (head + 1) % queue_capacity;
        --queued; ++processed;
        for (size_t i = 0; i < added; ++i) {
            queue[(head + queued) % queue_capacity] = generated[i]; ++queued;
        }
    }
    if (status == DW_LIGHT_DONE && queued != 0) status = DW_LIGHT_STEP_LIMIT;
    // Final output commit cannot allocate or throw. Input and output spans were checked disjoint.
    if (!output.empty()) std::memcpy(writes, output.data(), output.size() * sizeof(dw_light_write));
    for (size_t i = 0; i < queued; ++i) remaining[i] = queue[(head + i) % queue_capacity];
    *result = {processed, static_cast<uint32_t>(output.size()), static_cast<uint32_t>(queued), 0};
    return status;
}
}

extern "C" DW_LIGHT_API int32_t dw_light_prefix_abi(void) { return DW_LIGHT_PREFIX_ABI; }
extern "C" DW_LIGHT_API int32_t dw_light_prefix_cells_size(void) { return sizeof(dw_light_cells); }
extern "C" DW_LIGHT_API int32_t dw_block_light_increase_prefix_v1(
    const dw_light_cells* cells, const dw_light_queue_entry* initial, uint32_t initial_count,
    uint32_t max_steps, dw_light_write* writes, uint32_t write_capacity,
    dw_light_queue_entry* remaining, uint32_t remaining_capacity, dw_light_prefix_result* result) {
    if (!cells || reinterpret_cast<uintptr_t>(cells) % alignof(dw_light_cells) ||
        max_steps > DW_LIGHT_MAX_STEPS || initial_count > DW_LIGHT_MAX_QUEUE ||
        write_capacity > DW_LIGHT_MAX_WRITES || remaining_capacity > DW_LIGHT_MAX_QUEUE ||
        initial_count > remaining_capacity) return DW_LIGHT_INVALID_PACKET;
    const auto c = *cells;
    if (!bounds(c)) return DW_LIGHT_INVALID_PACKET;
    std::array<Region, 8> spans{};
    if (!region(cells,sizeof(*cells),alignof(dw_light_cells),spans[0]) ||
        !region(c.levels,c.cell_count,1,spans[1]) ||
        !region(c.opacity,c.cell_count,1,spans[2]) ||
        !region(c.flags,c.cell_count,1,spans[3]) ||
        !region(initial,size_t(initial_count)*16,8,spans[4]) ||
        !region(writes,size_t(write_capacity)*16,8,spans[5]) ||
        !region(remaining,size_t(remaining_capacity)*16,8,spans[6]) ||
        !region(result,sizeof(*result),4,spans[7])) return DW_LIGHT_INVALID_PACKET;
    for (size_t i = 0; i < spans.size(); ++i)
        for (size_t j = i + 1; j < spans.size(); ++j)
            if (overlaps(spans[i],spans[j])) return DW_LIGHT_INVALID_PACKET;
    for (uint32_t i = 0; i < c.cell_count; ++i) {
        if (c.levels[i] > 15 || c.opacity[i] < 1 || c.opacity[i] > 16 || (c.flags[i] & ~7) ||
            ((c.flags[i] & DW_LIGHT_STORING) && !(c.flags[i] & DW_LIGHT_KNOWN)))
            return DW_LIGHT_INVALID_PACKET;
    }
    for (uint32_t i = 0; i < initial_count; ++i)
        if (initial[i].entry & ~uint64_t(4095)) return DW_LIGHT_INVALID_PACKET;
    try { return compute(c,initial,initial_count,max_steps,writes,write_capacity,
                         remaining,remaining_capacity,result); }
    catch (const std::bad_alloc&) { return DW_LIGHT_ALLOCATION_FAILED; }
    catch (...) { return DW_LIGHT_INVALID_PACKET; }
}
