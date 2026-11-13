#define WKE_RENDER_BUILD 1
#include "wke_vulkan.h"
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>
#include "vulkan_world_commands.hpp"
#include <array>
#include <atomic>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <type_traits>
#include <unordered_map>

namespace {
template<class T> T as_handle(uint64_t value) noexcept {
    if constexpr (std::is_pointer_v<T>) return reinterpret_cast<T>(static_cast<uintptr_t>(value));
    else return static_cast<T>(value);
}
template<class T> uint64_t bits(T value) noexcept {
    if constexpr (std::is_pointer_v<T>) return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(value));
    else return static_cast<uint64_t>(value);
}
void add(uint64_t& target, uint64_t value = 1) noexcept {
    target = value > std::numeric_limits<uint64_t>::max() - target
        ? std::numeric_limits<uint64_t>::max() : target + value;
}
struct Dispatch {
    PFN_vkCreateGraphicsPipelines create = nullptr;
    PFN_vkDestroyPipeline destroy = nullptr;
    PFN_vkCmdBindPipeline bind_pipeline = nullptr;
    PFN_vkCmdBindVertexBuffers bind_vertices = nullptr;
    PFN_vkCmdBindIndexBuffer bind_indices = nullptr;
    PFN_vkCmdDraw draw = nullptr;
    PFN_vkCmdDrawIndexed draw_indexed = nullptr;
    PFN_vkCmdDrawIndirect draw_indirect = nullptr;
    PFN_vkCmdDrawIndexedIndirect draw_indexed_indirect = nullptr;
};
struct Pipeline {
    uint32_t vertex_mask = 0;
};
struct Context {
    std::mutex mutex;
    VkDevice device = VK_NULL_HANDLE; // borrowed
    Dispatch vk;
    bool detached = false;
    std::unordered_map<uint64_t, Pipeline> pipelines;
    std::array<uint64_t, WKE_VK_STATS_WORDS> stats{};
    Context() { stats[WKE_VK_STAT_ABI] = WKE_VK_ABI_VERSION; }
};
std::mutex contexts_mutex;
std::unordered_map<uint64_t, std::shared_ptr<Context>> contexts;
uint64_t next_context = 1;

std::shared_ptr<Context> find_context(uint64_t id) {
    std::lock_guard lock(contexts_mutex);
    auto found = contexts.find(id);
    return found == contexts.end() ? nullptr : found->second;
}
template<class Function> int32_t with_context(uint64_t id, Function&& function) noexcept {
    try {
        auto context = find_context(id);
        if (!context) return WKE_VK_BAD_HANDLE;
        std::lock_guard lock(context->mutex);
        if (context->detached) return WKE_VK_BAD_HANDLE;
        return function(*context);
    } catch (...) {
        return WKE_VK_INTERNAL_ERROR;
    }
}
int32_t vertex_mask(const VkGraphicsPipelineCreateInfo& info, uint32_t& mask) noexcept {
    if (!info.pVertexInputState
        || info.pVertexInputState->sType != VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
        return WKE_VK_UNSUPPORTED;
    const auto& vertex = *info.pVertexInputState;
    if (vertex.vertexBindingDescriptionCount > 2 || vertex.vertexAttributeDescriptionCount > 256)
        return WKE_VK_UNSUPPORTED;
    if ((vertex.vertexBindingDescriptionCount && !vertex.pVertexBindingDescriptions)
        || (vertex.vertexAttributeDescriptionCount && !vertex.pVertexAttributeDescriptions))
        return WKE_VK_BAD_ARGUMENT;
    mask = 0;
    for (uint32_t i = 0; i < vertex.vertexBindingDescriptionCount; ++i) {
        uint32_t binding = vertex.pVertexBindingDescriptions[i].binding;
        if (binding >= 2 || (mask & (1u << binding))) return WKE_VK_UNSUPPORTED;
        mask |= 1u << binding;
    }
    for (uint32_t i = 0; i < vertex.vertexAttributeDescriptionCount; ++i) {
        uint32_t binding = vertex.pVertexAttributeDescriptions[i].binding;
        if (binding >= 2 || !(mask & (1u << binding))) return WKE_VK_UNSUPPORTED;
    }
    return WKE_VK_OK;
}
bool indexed(uint32_t operation) noexcept {
    return operation == WKE_VK_DRAW_INDEXED || operation == WKE_VK_DRAW_INDEXED_INDIRECT;
}
bool indirect(uint32_t operation) noexcept {
    return operation == WKE_VK_DRAW_INDIRECT || operation == WKE_VK_DRAW_INDEXED_INDIRECT;
}
bool empty(const WkeVkDrawPacket& packet) noexcept {
    if (indirect(packet.operation)) return packet.indirect_count == 0;
    return packet.instance_count == 0 || (indexed(packet.operation) ? packet.index_count : packet.vertex_count) == 0;
}
int32_t validate(const Context& context, const WkeVkDrawPacket& packet) noexcept {
    if (packet.flags || packet.reserved || packet.operation > WKE_VK_DRAW_INDEXED_INDIRECT
        || packet.vertex_binding_count > 2) return WKE_VK_BAD_ARGUMENT;
    auto pipeline = context.pipelines.find(packet.pipeline);
    if (pipeline == context.pipelines.end()) return WKE_VK_BAD_HANDLE;
    uint32_t supplied = (1u << packet.vertex_binding_count) - 1u;
    if (pipeline->second.vertex_mask & ~supplied) return WKE_VK_BAD_ARGUMENT;
    if ((packet.vertex_binding_count >= 1 && !packet.vertex_buffer0)
        || (packet.vertex_binding_count >= 2 && !packet.vertex_buffer1)) return WKE_VK_BAD_ARGUMENT;
    if (indexed(packet.operation)) {
        if (!packet.index_buffer || packet.index_type > 1) return WKE_VK_BAD_ARGUMENT;
        uint32_t alignment = packet.index_type == 0 ? 2 : 4;
        if (packet.index_offset % alignment) return WKE_VK_BAD_ARGUMENT;
        uint64_t last = (uint64_t(packet.first_index) + packet.index_count) * alignment;
        if (last > std::numeric_limits<uint64_t>::max() - packet.index_offset) return WKE_VK_BAD_ARGUMENT;
    }
    if (indirect(packet.operation)) {
        if (!packet.indirect_buffer || packet.indirect_offset % 4 || packet.indirect_count > 1048576)
            return WKE_VK_BAD_ARGUMENT;
        uint32_t command_bytes = indexed(packet.operation)
            ? uint32_t(sizeof(VkDrawIndexedIndirectCommand)) : uint32_t(sizeof(VkDrawIndirectCommand));
        if (packet.indirect_count > 1
            && (packet.indirect_stride < command_bytes || packet.indirect_stride % 4))
            return WKE_VK_BAD_ARGUMENT;
        uint64_t bytes = packet.indirect_count == 0 ? 0
            : uint64_t(packet.indirect_count - 1) * packet.indirect_stride + command_bytes;
        if (bytes > std::numeric_limits<uint64_t>::max() - packet.indirect_offset) return WKE_VK_BAD_ARGUMENT;
    } else if (!indexed(packet.operation)) {
        if (uint64_t(packet.first_vertex) + packet.vertex_count > std::numeric_limits<uint32_t>::max())
            return WKE_VK_BAD_ARGUMENT;
    }
    return WKE_VK_OK;
}
}

extern "C" WKE_RENDER_API int32_t wke_vk_attach(uint32_t version, uint64_t device,
    uint64_t get_device_proc_addr, uint64_t* out_context) noexcept {
    if (!out_context) return WKE_VK_BAD_ARGUMENT;
    *out_context = 0;
    if (version != WKE_VK_ABI_VERSION) return WKE_VK_UNSUPPORTED;
    if (!device || !get_device_proc_addr) return WKE_VK_BAD_ARGUMENT;
    try {
        auto context = std::make_shared<Context>();
        context->device = as_handle<VkDevice>(device);
        auto get = reinterpret_cast<PFN_vkGetDeviceProcAddr>(static_cast<uintptr_t>(get_device_proc_addr));
#define WKE_LOAD(member, name) context->vk.member = reinterpret_cast<PFN_##name>(get(context->device, #name)); if (!context->vk.member) return WKE_VK_UNSUPPORTED
        WKE_LOAD(create, vkCreateGraphicsPipelines);
        WKE_LOAD(destroy, vkDestroyPipeline);
        WKE_LOAD(bind_pipeline, vkCmdBindPipeline);
        WKE_LOAD(bind_vertices, vkCmdBindVertexBuffers);
        WKE_LOAD(bind_indices, vkCmdBindIndexBuffer);
        WKE_LOAD(draw, vkCmdDraw);
        WKE_LOAD(draw_indexed, vkCmdDrawIndexed);
        WKE_LOAD(draw_indirect, vkCmdDrawIndirect);
        WKE_LOAD(draw_indexed_indirect, vkCmdDrawIndexedIndirect);
#undef WKE_LOAD
        std::lock_guard lock(contexts_mutex);
        if (!next_context) return WKE_VK_INTERNAL_ERROR;
        uint64_t id = next_context++;
        contexts.emplace(id, std::move(context));
        *out_context = id;
        return WKE_VK_OK;
    } catch (...) {
        return WKE_VK_INTERNAL_ERROR;
    }
}

extern "C" WKE_RENDER_API int32_t wke_vk_create_graphics(uint64_t context, uint64_t pipeline_cache,
    const void* create_info, uint64_t* out_pipeline) noexcept {
    if (!out_pipeline) return WKE_VK_BAD_ARGUMENT;
    *out_pipeline = 0;
    if (!create_info) return WKE_VK_BAD_ARGUMENT;
    return with_context(context, [&](Context& owner) -> int32_t {
        const auto& info = *static_cast<const VkGraphicsPipelineCreateInfo*>(create_info);
        if (info.sType != VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO || !info.stageCount
            || info.stageCount > 16 || !info.pStages || !info.layout) return WKE_VK_BAD_ARGUMENT;
        bool vertex_stage = false;
        for (uint32_t i = 0; i < info.stageCount; ++i) {
            const auto& stage = info.pStages[i];
            if (stage.sType != VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO
                || !stage.module || !stage.pName) return WKE_VK_BAD_ARGUMENT;
            vertex_stage |= stage.stage == VK_SHADER_STAGE_VERTEX_BIT;
        }
        if (!vertex_stage) return WKE_VK_UNSUPPORTED;
        uint32_t mask = 0;
        int32_t valid = vertex_mask(info, mask);
        if (valid != WKE_VK_OK) return valid;
        VkPipeline pipeline = VK_NULL_HANDLE;
        VkResult result = owner.vk.create(owner.device, as_handle<VkPipelineCache>(pipeline_cache), 1, &info, nullptr, &pipeline);
        owner.stats[WKE_VK_STAT_LAST_VK_RESULT] = static_cast<uint64_t>(static_cast<int64_t>(result));
        if (result != VK_SUCCESS || pipeline == VK_NULL_HANDLE) {
            if (pipeline != VK_NULL_HANDLE) owner.vk.destroy(owner.device, pipeline, nullptr);
            add(owner.stats[WKE_VK_STAT_HOST_ERRORS]);
            return WKE_VK_HOST_ERROR;
        }
        uint64_t id = bits(pipeline);
        try {
            if (!owner.pipelines.emplace(id, Pipeline{mask}).second) {
                // A valid Vulkan driver cannot return a duplicate live pipeline handle.
                // Keep the existing handle owned and do not destroy it twice.
                add(owner.stats[WKE_VK_STAT_HOST_ERRORS]);
                return WKE_VK_HOST_ERROR;
            }
        } catch (...) {
            owner.vk.destroy(owner.device, pipeline, nullptr);
            throw;
        }
        add(owner.stats[WKE_VK_STAT_CREATED_PIPELINES]);
        *out_pipeline = id;
        return WKE_VK_OK;
    });
}

extern "C" WKE_RENDER_API int32_t wke_vk_destroy_graphics(uint64_t context, uint64_t pipeline) noexcept {
    if (!pipeline) return WKE_VK_BAD_ARGUMENT;
    return with_context(context, [&](Context& owner) -> int32_t {
        auto found = owner.pipelines.find(pipeline);
        if (found == owner.pipelines.end()) return WKE_VK_BAD_HANDLE;
        owner.vk.destroy(owner.device, as_handle<VkPipeline>(pipeline), nullptr);
        owner.pipelines.erase(found);
        add(owner.stats[WKE_VK_STAT_DESTROYED_PIPELINES]);
        return WKE_VK_OK;
    });
}

extern "C" WKE_RENDER_API int32_t wke_vk_record_terrain(uint64_t context, uint64_t command_buffer,
    const WkeVkDrawPacket* packets, uint32_t count) noexcept {
    if (!command_buffer || count > WKE_VK_MAX_PACKETS || (count && !packets))
        return WKE_VK_BAD_ARGUMENT;
    return with_context(context, [&](Context& owner) -> int32_t {
        // Everything that can reject the batch happens before its first vkCmd*.
        // Host guarantees packets stay immutable until this synchronous call returns.
        for (uint32_t i = 0; i < count; ++i) {
            int32_t result = validate(owner, packets[i]);
            if (result != WKE_VK_OK) {
                add(owner.stats[WKE_VK_STAT_REJECTED_BATCHES]);
                return result;
            }
        }
        auto command = as_handle<VkCommandBuffer>(command_buffer);
        for (uint32_t i = 0; i < count; ++i) {
            const auto& packet = packets[i];
            if (empty(packet)) continue;
            wke::vulkan::execute_world_command(owner.vk, command, packet);
            add(owner.stats[WKE_VK_STAT_PIPELINE_BINDS]);
            switch (packet.operation) {
            case WKE_VK_DRAW:
                add(owner.stats[WKE_VK_STAT_DRAW_CALLS]);
                add(owner.stats[WKE_VK_STAT_DIRECT_VERTICES], uint64_t(packet.vertex_count) * packet.instance_count);
                break;
            case WKE_VK_DRAW_INDEXED:
                add(owner.stats[WKE_VK_STAT_INDEXED_CALLS]);
                add(owner.stats[WKE_VK_STAT_DIRECT_INDICES], uint64_t(packet.index_count) * packet.instance_count);
                break;
            case WKE_VK_DRAW_INDIRECT:
                add(owner.stats[WKE_VK_STAT_INDIRECT_CALLS]);
                add(owner.stats[WKE_VK_STAT_INDIRECT_DRAW_COUNT], packet.indirect_count);
                break;
            case WKE_VK_DRAW_INDEXED_INDIRECT:
                add(owner.stats[WKE_VK_STAT_INDEXED_INDIRECT_CALLS]);
                add(owner.stats[WKE_VK_STAT_INDIRECT_DRAW_COUNT], packet.indirect_count);
                break;
            }
        }
        add(owner.stats[WKE_VK_STAT_RECORDED_BATCHES]);
        return WKE_VK_OK;
    });
}

extern "C" WKE_RENDER_API int32_t wke_vk_copy_stats(uint64_t context, uint64_t* out_words,
    uint32_t word_count) noexcept {
    if (!out_words || word_count < WKE_VK_STATS_WORDS) return WKE_VK_BAD_ARGUMENT;
    return with_context(context, [&](Context& owner) -> int32_t {
        owner.stats[WKE_VK_STAT_LIVE_PIPELINES] = owner.pipelines.size();
        std::memcpy(out_words, owner.stats.data(), sizeof(owner.stats));
        return WKE_VK_OK;
    });
}

extern "C" WKE_RENDER_API int32_t wke_vk_detach(uint64_t context) noexcept {
    try {
        std::lock_guard registry_lock(contexts_mutex);
        auto found = contexts.find(context);
        if (found == contexts.end()) return WKE_VK_BAD_HANDLE;
        auto owner = found->second;
        std::lock_guard context_lock(owner->mutex);
        if (!owner->pipelines.empty()) return WKE_VK_BUSY;
        owner->detached = true;
        contexts.erase(found);
        return WKE_VK_OK;
    } catch (...) {
        return WKE_VK_INTERNAL_ERROR;
    }
}
