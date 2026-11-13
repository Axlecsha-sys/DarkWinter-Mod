#pragma once

// Shared Vulkan world-command execution for the standalone and embedded WKE hosts.
// No device, queue, window, command buffer or allocation is created here.
#include <vulkan/vulkan_core.h>
#include <type_traits>

// C API declarations stay unexported in a static host; the DLL defines WKE_RENDER_BUILD.
#include "wke_vulkan.h"

namespace wke::vulkan {

template<class Handle> inline Handle handle_from_bits(uint64_t value) noexcept {
    if constexpr (std::is_pointer_v<Handle>)
        return reinterpret_cast<Handle>(static_cast<uintptr_t>(value));
    else
        return static_cast<Handle>(value);
}

template<class Handle> inline uint64_t handle_bits(Handle value) noexcept {
    if constexpr (std::is_pointer_v<Handle>)
        return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(value));
    else
        return static_cast<uint64_t>(value);
}

// Each host supplies functions for the operations it emits. The embedded host's
// richer dispatch has these same members and can be passed directly.
struct WorldCommandDispatch {
    PFN_vkCmdBindPipeline bind_pipeline = nullptr;
    PFN_vkCmdBindVertexBuffers bind_vertices = nullptr;
    PFN_vkCmdBindIndexBuffer bind_indices = nullptr;
    PFN_vkCmdDraw draw = nullptr;
    PFN_vkCmdDrawIndexed draw_indexed = nullptr;
    PFN_vkCmdDrawIndirect draw_indirect = nullptr;
    PFN_vkCmdDrawIndexedIndirect draw_indexed_indirect = nullptr;
};

// Preconditions: the packet and dispatch have been validated by the host, the
// command buffer is inside a compatible active render pass, and borrowed GPU
// objects remain alive. This function allocates nothing and never submits work.
// bind_pipeline=false is allowed only if this exact graphics pipeline is already
// bound in this command buffer; it avoids redundant binds in a standalone batch.
template<class Dispatch>
inline void execute_world_command(const Dispatch& vk, VkCommandBuffer command,
        const WkeVkDrawPacket& packet, bool bind_pipeline = true) noexcept {
    if (bind_pipeline)
        vk.bind_pipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS,
            handle_from_bits<VkPipeline>(packet.pipeline));
    if (packet.vertex_binding_count) {
        const VkBuffer buffers[] = {
            handle_from_bits<VkBuffer>(packet.vertex_buffer0),
            handle_from_bits<VkBuffer>(packet.vertex_buffer1)
        };
        const VkDeviceSize offsets[] = {packet.vertex_offset0, packet.vertex_offset1};
        vk.bind_vertices(command, 0, packet.vertex_binding_count, buffers, offsets);
    }
    if (packet.operation == WKE_VK_DRAW_INDEXED
            || packet.operation == WKE_VK_DRAW_INDEXED_INDIRECT)
        vk.bind_indices(command, handle_from_bits<VkBuffer>(packet.index_buffer),
            packet.index_offset, static_cast<VkIndexType>(packet.index_type));
    switch (packet.operation) {
    case WKE_VK_DRAW:
        vk.draw(command, packet.vertex_count, packet.instance_count,
            packet.first_vertex, packet.first_instance);
        break;
    case WKE_VK_DRAW_INDEXED:
        vk.draw_indexed(command, packet.index_count, packet.instance_count,
            packet.first_index, packet.vertex_offset, packet.first_instance);
        break;
    case WKE_VK_DRAW_INDIRECT:
        vk.draw_indirect(command, handle_from_bits<VkBuffer>(packet.indirect_buffer),
            packet.indirect_offset, packet.indirect_count, packet.indirect_stride);
        break;
    case WKE_VK_DRAW_INDEXED_INDIRECT:
        vk.draw_indexed_indirect(command, handle_from_bits<VkBuffer>(packet.indirect_buffer),
            packet.indirect_offset, packet.indirect_count, packet.indirect_stride);
        break;
    }
}

} // namespace wke::vulkan
