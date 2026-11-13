#include "wke_vulkan.h"
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>
#include <array>
#include <atomic>
#include <cstring>
#include <iostream>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <type_traits>
#include <vector>

namespace {
template<class T> T handle(uint64_t value) {
    if constexpr (std::is_pointer_v<T>) return reinterpret_cast<T>(static_cast<uintptr_t>(value));
    else return static_cast<T>(value);
}
template<class T> uint64_t bits(T value) {
    if constexpr (std::is_pointer_v<T>) return uint64_t(reinterpret_cast<uintptr_t>(value));
    else return uint64_t(value);
}
std::mutex mock_mutex;
std::vector<std::string> calls;
std::atomic<uint64_t> next_pipeline{0x1000};
std::atomic<bool> mock_error{false};
std::atomic<bool> fail_create{false};
std::atomic<bool> missing_function{false};
std::atomic<uint32_t> destroy_device_calls{0};
const void* expected_next = nullptr;
std::array<uint64_t, 6> direct_args{}, indexed_args{}, indirect_args{}, indexed_indirect_args{};
std::array<uint64_t, 5> vertex_args{};
std::array<uint64_t, 3> index_args{};
uint64_t check_count = 0;
void check(bool value, const char* reason) {
    ++check_count;
    if (!value) throw std::runtime_error(reason);
}
void trace(const char* call) {
    std::lock_guard lock(mock_mutex);
    calls.emplace_back(call);
}
bool command_ok(VkCommandBuffer command) {
    bool okay = bits(command) == 0x202;
    if (!okay) mock_error = true;
    return okay;
}
VKAPI_ATTR VkResult VKAPI_CALL create_pipeline(VkDevice device, VkPipelineCache cache,
    uint32_t count, const VkGraphicsPipelineCreateInfo* info,
    const VkAllocationCallbacks* allocation, VkPipeline* pipeline) {
    trace("create");
    if (bits(device) != 0x101 || bits(cache) != 0x303 || count != 1 || !info
        || info->pNext != expected_next || bits(info->layout) != 0x404 || allocation != nullptr)
        mock_error = true;
    *pipeline = handle<VkPipeline>(next_pipeline.fetch_add(1));
    return fail_create.load() ? VK_ERROR_OUT_OF_HOST_MEMORY : VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL destroy_pipeline(VkDevice device, VkPipeline pipeline, const VkAllocationCallbacks* allocation) {
    trace("destroy");
    if (bits(device) != 0x101 || !pipeline || allocation != nullptr) mock_error = true;
}
VKAPI_ATTR void VKAPI_CALL destroy_device(VkDevice, const VkAllocationCallbacks*) { ++destroy_device_calls; }
VKAPI_ATTR void VKAPI_CALL bind_pipeline(VkCommandBuffer command, VkPipelineBindPoint point, VkPipeline pipeline) {
    trace("pipeline"); command_ok(command);
    if (point != VK_PIPELINE_BIND_POINT_GRAPHICS || !pipeline) mock_error = true;
}
VKAPI_ATTR void VKAPI_CALL bind_vertices(VkCommandBuffer command, uint32_t first, uint32_t count,
    const VkBuffer* buffers, const VkDeviceSize* offsets) {
    trace("vertices"); command_ok(command);
    vertex_args = {first, count, bits(buffers[0]), offsets[0], count > 1 ? bits(buffers[1]) : 0};
}
VKAPI_ATTR void VKAPI_CALL bind_indices(VkCommandBuffer command, VkBuffer buffer, VkDeviceSize offset, VkIndexType type) {
    trace("indices"); command_ok(command); index_args = {bits(buffer), offset, uint64_t(type)};
}
VKAPI_ATTR void VKAPI_CALL draw(VkCommandBuffer command, uint32_t vertices, uint32_t instances,
    uint32_t first, uint32_t first_instance) {
    trace("draw"); command_ok(command); direct_args = {vertices, instances, first, first_instance, 0, 0};
}
VKAPI_ATTR void VKAPI_CALL draw_indexed(VkCommandBuffer command, uint32_t indices, uint32_t instances,
    uint32_t first, int32_t vertex, uint32_t first_instance) {
    trace("drawIndexed"); command_ok(command);
    indexed_args = {indices, instances, first, uint64_t(int64_t(vertex)), first_instance, 0};
}
VKAPI_ATTR void VKAPI_CALL draw_indirect(VkCommandBuffer command, VkBuffer buffer,
    VkDeviceSize offset, uint32_t count, uint32_t stride) {
    trace("drawIndirect"); command_ok(command); indirect_args = {bits(buffer), offset, count, stride, 0, 0};
}
VKAPI_ATTR void VKAPI_CALL draw_indexed_indirect(VkCommandBuffer command, VkBuffer buffer,
    VkDeviceSize offset, uint32_t count, uint32_t stride) {
    trace("drawIndexedIndirect"); command_ok(command);
    indexed_indirect_args = {bits(buffer), offset, count, stride, 0, 0};
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL get_proc(VkDevice device, const char* name) {
    if (bits(device) != 0x101) mock_error = true;
#define PROC(text, function) if (std::strcmp(name, text) == 0) return reinterpret_cast<PFN_vkVoidFunction>(function)
    PROC("vkCreateGraphicsPipelines", create_pipeline);
    PROC("vkDestroyPipeline", destroy_pipeline);
    PROC("vkDestroyDevice", destroy_device);
    PROC("vkCmdBindPipeline", bind_pipeline);
    PROC("vkCmdBindVertexBuffers", bind_vertices);
    PROC("vkCmdBindIndexBuffer", bind_indices);
    PROC("vkCmdDraw", draw);
    PROC("vkCmdDrawIndexed", draw_indexed);
    PROC("vkCmdDrawIndirect", draw_indirect);
    if (missing_function && std::strcmp(name, "vkCmdDrawIndexedIndirect") == 0) return nullptr;
    PROC("vkCmdDrawIndexedIndirect", draw_indexed_indirect);
#undef PROC
    return nullptr;
}
void clear_calls() { std::lock_guard lock(mock_mutex); calls.clear(); }
std::array<uint64_t, WKE_VK_STATS_WORDS> stats(uint64_t context) {
    std::array<uint64_t, WKE_VK_STATS_WORDS> output{};
    check(wke_vk_copy_stats(context, output.data(), uint32_t(output.size())) == WKE_VK_OK, "Read native statistics");
    return output;
}
}

int main() {
    try {
        check(sizeof(WkeVkDrawPacket) == 128 && offsetof(WkeVkDrawPacket, operation) == 72
            && offsetof(WkeVkDrawPacket, flags) == 120, "Wire packet layout");
        uint64_t context = 99;
        uint64_t get = uint64_t(reinterpret_cast<uintptr_t>(&get_proc));
        check(wke_vk_attach(2, 0x101, get, &context) == WKE_VK_UNSUPPORTED && context == 0, "Reject ABI mismatch");
        missing_function = true;
        check(wke_vk_attach(1, 0x101, get, &context) == WKE_VK_UNSUPPORTED && context == 0, "Reject missing Vulkan dispatch");
        missing_function = false;
        check(wke_vk_attach(1, 0x101, get, &context) == WKE_VK_OK && context != 0, "Borrow host device");
        check(calls.empty(), "Attach neither creates nor submits Vulkan work");
        uint64_t old_context = context;

        VkVertexInputBindingDescription bindings[2]{{0, 28, VK_VERTEX_INPUT_RATE_VERTEX}, {1, 16, VK_VERTEX_INPUT_RATE_INSTANCE}};
        VkVertexInputAttributeDescription attribute{0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0};
        VkPipelineVertexInputStateCreateInfo vertex{};
        vertex.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        vertex.vertexBindingDescriptionCount = 2; vertex.pVertexBindingDescriptions = bindings;
        vertex.vertexAttributeDescriptionCount = 1; vertex.pVertexAttributeDescriptions = &attribute;
        VkPipelineShaderStageCreateInfo stages[2]{};
        for (auto& stage : stages) {
            stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            stage.module = handle<VkShaderModule>(0x505); stage.pName = "main";
        }
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT; stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        VkPipelineRenderingCreateInfo rendering{};
        rendering.sType = VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO;
        rendering.depthAttachmentFormat = VK_FORMAT_D24_UNORM_S8_UINT;
        VkGraphicsPipelineCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        info.pNext = expected_next = &rendering;
        info.stageCount = 2; info.pStages = stages; info.pVertexInputState = &vertex;
        info.layout = handle<VkPipelineLayout>(0x404);
        uint64_t pipeline = 0;
        check(wke_vk_create_graphics(context, 0x303, &info, &pipeline) == WKE_VK_OK && pipeline, "Native creates owned pipeline");
        check(calls == std::vector<std::string>{"create"} && !mock_error, "Forward original pipeline layout and pNext synchronously");
        clear_calls();
        bindings[1].binding = 3;
        uint64_t rejected_pipeline = 99;
        check(wke_vk_create_graphics(context, 0x303, &info, &rejected_pipeline) == WKE_VK_UNSUPPORTED
            && rejected_pipeline == 0 && calls.empty(), "Unsupported vertex binding makes no Vulkan call");
        bindings[1].binding = 1;
        rendering.depthAttachmentFormat = VK_FORMAT_D32_SFLOAT;
        uint64_t second = 0;
        check(wke_vk_create_graphics(context, 0x303, &info, &second) == WKE_VK_OK && second != pipeline, "Own second depth-format-compatible pipeline");
        check(wke_vk_detach(context) == WKE_VK_BUSY && destroy_device_calls == 0, "Live native pipelines prevent detach");
        clear_calls();

        WkeVkDrawPacket base{};
        base.pipeline = pipeline; base.vertex_buffer0 = 0x606; base.vertex_buffer1 = 0x707;
        base.vertex_offset0 = 128; base.vertex_offset1 = 64; base.vertex_binding_count = 2;
        base.index_buffer = 0x808; base.index_offset = 8; base.index_type = 1;
        base.vertex_count = 6; base.index_count = 12; base.instance_count = 2;
        base.first_vertex = 3; base.first_index = 2; base.vertex_offset = -4; base.first_instance = 9;
        base.indirect_buffer = 0x909; base.indirect_offset = 64; base.indirect_count = 2; base.indirect_stride = 16;

        std::array<WkeVkDrawPacket, 2> invalid{base, base};
        invalid[1].pipeline = 0xdeadcafe;
        check(wke_vk_record_terrain(context, 0x202, invalid.data(), 2) == WKE_VK_BAD_HANDLE && calls.empty(),
            "Whole batch is prevalidated before its first Vulkan command");
        invalid[1] = base; invalid[1].flags = 1;
        check(wke_vk_record_terrain(context, 0x202, invalid.data(), 2) == WKE_VK_BAD_ARGUMENT && calls.empty(), "Reject reserved flags without commands");
        invalid[1] = base; invalid[1].operation = WKE_VK_DRAW_INDEXED; invalid[1].index_offset = 3;
        check(wke_vk_record_terrain(context, 0x202, invalid.data(), 2) == WKE_VK_BAD_ARGUMENT && calls.empty(), "Reject unaligned index offset without commands");
        invalid[1] = base; invalid[1].operation = WKE_VK_DRAW_INDIRECT; invalid[1].indirect_stride = 3;
        check(wke_vk_record_terrain(context, 0x202, invalid.data(), 2) == WKE_VK_BAD_ARGUMENT && calls.empty(), "Reject invalid indirect stride without commands");

        std::array<WkeVkDrawPacket, 4> packets{base, base, base, base};
        for (uint32_t i = 0; i < packets.size(); ++i) packets[i].operation = i;
        packets[1].instance_count = 3; packets[1].first_instance = 5;
        packets[3].pipeline = second; packets[3].indirect_count = 3; packets[3].indirect_stride = 20;
        check(wke_vk_record_terrain(context, 0x202, packets.data(), uint32_t(packets.size())) == WKE_VK_OK, "Record all four real native draw commands");
        check(calls == std::vector<std::string>{
            "pipeline", "vertices", "draw", "pipeline", "vertices", "indices", "drawIndexed",
            "pipeline", "vertices", "drawIndirect", "pipeline", "vertices", "indices", "drawIndexedIndirect"},
            "Native pipeline/buffer/index/draw command ordering");
        check(direct_args == std::array<uint64_t,6>{6,2,3,9,0,0}, "Native nonindexed arguments");
        check(indexed_args == std::array<uint64_t,6>{12,3,2,uint64_t(int64_t(-4)),5,0}, "Native indexed arguments including negative base vertex");
        check(indirect_args == std::array<uint64_t,6>{0x909,64,2,16,0,0}, "Native indirect arguments");
        check(indexed_indirect_args == std::array<uint64_t,6>{0x909,64,3,20,0,0}, "Native indexed-indirect arguments");
        check(vertex_args == std::array<uint64_t,5>{0,2,0x606,128,0x707}
            && index_args == std::array<uint64_t,3>{0x808,8,1}, "Borrowed buffer bindings and offsets");
        check(!mock_error && destroy_device_calls == 0, "No unrelated host state/device ownership");

        auto measured = stats(context);
        check(measured[WKE_VK_STAT_LIVE_PIPELINES] == 2 && measured[WKE_VK_STAT_CREATED_PIPELINES] == 2, "Owned pipeline counters");
        check(measured[WKE_VK_STAT_DRAW_CALLS] == 1 && measured[WKE_VK_STAT_INDEXED_CALLS] == 1
            && measured[WKE_VK_STAT_INDIRECT_CALLS] == 1 && measured[WKE_VK_STAT_INDEXED_INDIRECT_CALLS] == 1,
            "Actual native draw-type counters");
        check(measured[WKE_VK_STAT_DIRECT_VERTICES] == 12 && measured[WKE_VK_STAT_DIRECT_INDICES] == 36
            && measured[WKE_VK_STAT_INDIRECT_DRAW_COUNT] == 5 && measured[WKE_VK_STAT_PIPELINE_BINDS] == 4,
            "Native geometry counters");
        check(measured[WKE_VK_STAT_REJECTED_BATCHES] == 4, "Rejected whole batches counted separately");
        clear_calls();
        fail_create = true;
        rejected_pipeline = 99;
        check(wke_vk_create_graphics(context, 0x303, &info, &rejected_pipeline) == WKE_VK_HOST_ERROR
            && rejected_pipeline == 0 && calls == std::vector<std::string>{"create", "destroy"},
            "Failed pipeline creation cleans up partial Vulkan result");
        fail_create = false;
        check(stats(context)[WKE_VK_STAT_LIVE_PIPELINES] == 2, "Failed creation publishes no handle");
        clear_calls();

        std::atomic<uint32_t> thread_errors{0};
        std::vector<std::thread> workers;
        for (uint32_t worker = 0; worker < 4; ++worker) workers.emplace_back([&] {
            for (uint32_t i = 0; i < 16; ++i) {
                uint64_t temporary = 0;
                if (wke_vk_create_graphics(context, 0x303, &info, &temporary) != WKE_VK_OK
                    || wke_vk_destroy_graphics(context, temporary) != WKE_VK_OK) ++thread_errors;
            }
        });
        for (auto& worker : workers) worker.join();
        check(thread_errors == 0 && stats(context)[WKE_VK_STAT_LIVE_PIPELINES] == 2, "Concurrent creation/destruction preserves ownership");
        check(wke_vk_destroy_graphics(context, pipeline) == WKE_VK_OK, "GPU-safe owner release");
        clear_calls();
        check(wke_vk_destroy_graphics(context, pipeline) == WKE_VK_BAD_HANDLE && calls.empty(), "Double release makes no Vulkan call");
        check(wke_vk_destroy_graphics(context, second) == WKE_VK_OK, "Release final owned pipeline");
        check(wke_vk_detach(context) == WKE_VK_OK && destroy_device_calls == 0, "Detach never destroys borrowed device");
        clear_calls();
        std::array<uint64_t,16> output{};
        check(wke_vk_copy_stats(old_context, output.data(), 16) == WKE_VK_BAD_HANDLE, "Retired context is rejected");
        check(wke_vk_record_terrain(old_context, 0x202, packets.data(), 4) == WKE_VK_BAD_HANDLE && calls.empty(), "Retired context records no commands");
        uint64_t replacement = 0;
        check(wke_vk_attach(1, 0x101, get, &replacement) == WKE_VK_OK && replacement != old_context, "Context handles are not reused (ABA guard)");
        check(wke_vk_detach(replacement) == WKE_VK_OK && !mock_error && destroy_device_calls == 0, "Replacement lifecycle keeps host intact");
        std::cout << "{\"completed\":true,\"checks\":" << check_count
            << ",\"scope\":\"fake Vulkan dispatch ownership and ABI; not GPU validation\"}\n";
        std::cout << "WKE_RENDER_NATIVE_PASS\n";
        return 0;
    } catch (const std::exception& failure) {
        std::cerr << "WKE_EMBEDDED_CHECK_FAILURE " << failure.what() << "\n";
        return 1;
    }
}
