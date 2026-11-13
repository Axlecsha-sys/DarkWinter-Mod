#pragma once
#include <stdint.h>
#include <stddef.h>

#ifndef WKE_RENDER_API
#if defined(WKE_RENDER_BUILD) && defined(_WIN32)
#define WKE_RENDER_API __declspec(dllexport)
#elif defined(WKE_RENDER_BUILD)
#define WKE_RENDER_API __attribute__((visibility("default")))
#else
#define WKE_RENDER_API
#endif
#endif
#ifdef __cplusplus
#define WKE_RENDER_NOEXCEPT noexcept
extern "C" {
#else
#define WKE_RENDER_NOEXCEPT
#endif

enum {
    WKE_VK_ABI_VERSION = 1,
    WKE_VK_OK = 0,
    WKE_VK_BAD_ARGUMENT = -1,
    WKE_VK_BAD_HANDLE = -2,
    WKE_VK_UNSUPPORTED = -3,
    WKE_VK_HOST_ERROR = -4,
    WKE_VK_INTERNAL_ERROR = -5,
    WKE_VK_BUSY = -6,
    WKE_VK_DRAW = 0,
    WKE_VK_DRAW_INDEXED = 1,
    WKE_VK_DRAW_INDIRECT = 2,
    WKE_VK_DRAW_INDEXED_INDIRECT = 3,
    WKE_VK_PACKET_BYTES = 128,
    WKE_VK_STATS_WORDS = 16,
    WKE_VK_MAX_PACKETS = 65536
};

/* All offsets are byte offsets. Only the fields used by the selected operation matter.
 * Binding count 0..2 supplies consecutive vertex bindings starting at binding 0.
 * index_type is Vulkan VkIndexType: UINT16=0 or UINT32=1.
 * Native borrows buffers, descriptors, push constants, viewport, scissor and the
 * active compatible render pass from the host; it does not submit or present.
 */
typedef struct WkeVkDrawPacket {
    uint64_t pipeline;           /*   0: must be created by this WKE context */
    uint64_t vertex_buffer0;     /*   8 */
    uint64_t vertex_offset0;     /*  16 */
    uint64_t vertex_buffer1;     /*  24 */
    uint64_t vertex_offset1;     /*  32 */
    uint64_t index_buffer;       /*  40 */
    uint64_t index_offset;       /*  48 */
    uint64_t indirect_buffer;    /*  56 */
    uint64_t indirect_offset;    /*  64 */
    uint32_t operation;          /*  72: WKE_VK_DRAW* */
    uint32_t vertex_binding_count; /*76 */
    uint32_t index_type;         /*  80 */
    uint32_t vertex_count;       /*  84 */
    uint32_t index_count;        /*  88 */
    uint32_t instance_count;     /*  92 */
    uint32_t first_vertex;       /*  96 */
    uint32_t first_index;        /* 100 */
    int32_t vertex_offset;       /* 104: indexed base vertex, not a byte offset */
    uint32_t first_instance;     /* 108 */
    uint32_t indirect_count;     /* 112 */
    uint32_t indirect_stride;    /* 116 */
    uint32_t flags;              /* 120: must be zero in ABI 1 */
    uint32_t reserved;           /* 124: must be zero */
} WkeVkDrawPacket;

enum WkeVkStat {
    WKE_VK_STAT_ABI = 0,
    WKE_VK_STAT_LIVE_PIPELINES = 1,
    WKE_VK_STAT_CREATED_PIPELINES = 2,
    WKE_VK_STAT_DESTROYED_PIPELINES = 3,
    WKE_VK_STAT_RECORDED_BATCHES = 4,
    WKE_VK_STAT_DRAW_CALLS = 5,
    WKE_VK_STAT_INDEXED_CALLS = 6,
    WKE_VK_STAT_INDIRECT_CALLS = 7,
    WKE_VK_STAT_INDEXED_INDIRECT_CALLS = 8,
    WKE_VK_STAT_DIRECT_VERTICES = 9,
    WKE_VK_STAT_DIRECT_INDICES = 10,
    WKE_VK_STAT_INDIRECT_DRAW_COUNT = 11,
    WKE_VK_STAT_REJECTED_BATCHES = 12,
    WKE_VK_STAT_HOST_ERRORS = 13,
    WKE_VK_STAT_LAST_VK_RESULT = 14, /* signed VkResult encoded as int64_t */
    WKE_VK_STAT_PIPELINE_BINDS = 15
};

/* Pointer/function/dispatchable handle arguments are 64-bit Win64 addresses.
 * Attach borrows an existing VkDevice and vkGetDeviceProcAddr function address.
 * No instance, device, queue, window, swapchain or host memory becomes WKE-owned.
 */
WKE_RENDER_API int32_t wke_vk_attach(uint32_t version, uint64_t device,
    uint64_t get_device_proc_addr, uint64_t* out_context) WKE_RENDER_NOEXCEPT;

/* create_info points to a VkGraphicsPipelineCreateInfo valid for this synchronous
 * call. Shader modules come from the host material frontend, including WKE terrain
 * stages and intentionally retained custom shadow/extension materials.
 * All state, pNext chains, layout and compatible render pass are passed unchanged.
 * WKE owns the returned VkPipeline; no create-info pointers are retained.
 */
WKE_RENDER_API int32_t wke_vk_create_graphics(uint64_t context, uint64_t pipeline_cache,
    const void* create_info, uint64_t* out_pipeline) WKE_RENDER_NOEXCEPT;

/* Host calls only after its usual deferred GPU-safe pipeline retirement. */
WKE_RENDER_API int32_t wke_vk_destroy_graphics(uint64_t context,
    uint64_t pipeline) WKE_RENDER_NOEXCEPT;

/* Prevalidates the ENTIRE batch before recording any Vulkan command. Nonzero
 * return means no command was recorded and the original host path can be used.
 * Host must satisfy Vulkan command-buffer state and device-feature/limit rules.
 * Pointers are borrowed for this call only. Recording never queues/submits work.
 */
WKE_RENDER_API int32_t wke_vk_record_terrain(uint64_t context, uint64_t command_buffer,
    const WkeVkDrawPacket* packets, uint32_t count) WKE_RENDER_NOEXCEPT;

WKE_RENDER_API int32_t wke_vk_copy_stats(uint64_t context, uint64_t* out_words,
    uint32_t word_count) WKE_RENDER_NOEXCEPT;

/* Requires zero live WKE pipelines and host GPU work already drained. */
WKE_RENDER_API int32_t wke_vk_detach(uint64_t context) WKE_RENDER_NOEXCEPT;

#ifdef __cplusplus
}
static_assert(sizeof(void*) == 8, "WKE embedded ABI requires a 64-bit host");
static_assert(sizeof(WkeVkDrawPacket) == WKE_VK_PACKET_BYTES);
static_assert(offsetof(WkeVkDrawPacket, pipeline) == 0);
static_assert(offsetof(WkeVkDrawPacket, operation) == 72);
static_assert(offsetof(WkeVkDrawPacket, vertex_offset) == 104);
static_assert(offsetof(WkeVkDrawPacket, flags) == 120);
#endif
