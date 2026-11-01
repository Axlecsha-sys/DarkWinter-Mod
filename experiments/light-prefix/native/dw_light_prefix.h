// Computes an exact bounded block-light increase prefix over detached experimental inputs.
#ifndef DARKWINTER_LIGHT_PREFIX_H
#define DARKWINTER_LIGHT_PREFIX_H
#include <stdint.h>
#if defined(_WIN32)
#define DW_LIGHT_API __declspec(dllexport)
#else
#define DW_LIGHT_API __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif
enum {
    DW_LIGHT_PREFIX_ABI = 1, DW_LIGHT_MAX_AXIS = 48,
    DW_LIGHT_MAX_CELLS = 48 * 48 * 48, DW_LIGHT_MAX_STEPS = 4096,
    DW_LIGHT_MAX_QUEUE = 65536, DW_LIGHT_MAX_WRITES = 7 * DW_LIGHT_MAX_STEPS
};
enum { DW_LIGHT_KNOWN = 1, DW_LIGHT_STORING = 2, DW_LIGHT_SIMPLE_EMPTY_SHAPE = 4 };
enum {
    DW_LIGHT_DONE = 0, DW_LIGHT_BOUNDARY = 1, DW_LIGHT_CAPACITY = 2,
    DW_LIGHT_UNSUPPORTED_SHAPE = 3, DW_LIGHT_STEP_LIMIT = 4,
    DW_LIGHT_INVALID_PACKET = -1, DW_LIGHT_ALLOCATION_FAILED = -2
};
typedef struct dw_light_queue_entry { int64_t position; uint64_t entry; } dw_light_queue_entry;
typedef struct dw_light_write { int64_t position; uint32_t level; uint32_t reserved; } dw_light_write;
typedef struct dw_light_cells {
    const uint8_t* levels;
    const uint8_t* opacity;
    const uint8_t* flags;
    uint32_t cell_count;
    int32_t min_x, min_y, min_z;
    uint32_t width, height, depth;
} dw_light_cells;
typedef struct dw_light_prefix_result {
    uint32_t processed_entries, write_count, remaining_count, reserved;
} dw_light_prefix_result;
DW_LIGHT_API int32_t dw_light_prefix_abi(void);
DW_LIGHT_API int32_t dw_light_prefix_cells_size(void);
// The caller must supply valid accessible buffers of the declared sizes.
// Native preflight additionally rejects bounds, alignment and overlapping regions.
// All inputs are immutable; negative results leave every output byte unchanged.
// Nonnegative results commit only whole queue entries and return the complete remaining FIFO.
// This experimental function neither reads Minecraft objects nor publishes world light.
DW_LIGHT_API int32_t dw_block_light_increase_prefix_v1(
    const dw_light_cells* cells,
    const dw_light_queue_entry* initial, uint32_t initial_count, uint32_t max_steps,
    dw_light_write* writes, uint32_t write_capacity,
    dw_light_queue_entry* remaining, uint32_t remaining_capacity,
    dw_light_prefix_result* result);
#ifdef __cplusplus
}
#endif
#endif
