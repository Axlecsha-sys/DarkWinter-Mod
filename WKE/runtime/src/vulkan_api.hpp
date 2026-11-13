#pragma once
#ifndef NOMINMAX
#define NOMINMAX
#endif
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>
#include <vulkan/vulkan_win32.h>
#include <stdexcept>
#include <string>
#include <bit>

#define DW_VK_GLOBAL(X) X(CreateInstance) X(EnumerateInstanceLayerProperties) X(EnumerateInstanceExtensionProperties)
#define DW_VK_INSTANCE(X) X(DestroyInstance) X(EnumeratePhysicalDevices) X(GetPhysicalDeviceProperties) X(GetPhysicalDeviceFeatures) X(GetPhysicalDeviceQueueFamilyProperties) X(GetPhysicalDeviceMemoryProperties) X(GetPhysicalDeviceFormatProperties) X(GetPhysicalDeviceImageFormatProperties) X(GetPhysicalDeviceSurfaceSupportKHR) X(GetPhysicalDeviceSurfaceCapabilitiesKHR) X(GetPhysicalDeviceSurfaceFormatsKHR) X(GetPhysicalDeviceSurfacePresentModesKHR) X(CreateWin32SurfaceKHR) X(DestroySurfaceKHR) X(CreateDevice) X(EnumerateDeviceExtensionProperties) X(GetDeviceProcAddr)
#define DW_VK_DEVICE(X) X(DestroyDevice) X(GetDeviceQueue) X(DeviceWaitIdle) X(QueueSubmit) X(QueuePresentKHR) X(CreateSwapchainKHR) X(DestroySwapchainKHR) X(GetSwapchainImagesKHR) X(AcquireNextImageKHR) X(CreateCommandPool) X(DestroyCommandPool) X(AllocateCommandBuffers) X(ResetCommandBuffer) X(BeginCommandBuffer) X(EndCommandBuffer) X(CreateFence) X(DestroyFence) X(WaitForFences) X(ResetFences) X(CreateSemaphore) X(DestroySemaphore) X(CreateBuffer) X(DestroyBuffer) X(GetBufferMemoryRequirements) X(AllocateMemory) X(FreeMemory) X(BindBufferMemory) X(MapMemory) X(UnmapMemory) X(CreateImage) X(DestroyImage) X(GetImageMemoryRequirements) X(BindImageMemory) X(CreateImageView) X(DestroyImageView) X(CreateDescriptorSetLayout) X(DestroyDescriptorSetLayout) X(CreateDescriptorPool) X(DestroyDescriptorPool) X(AllocateDescriptorSets) X(UpdateDescriptorSets) X(CreateShaderModule) X(DestroyShaderModule) X(CreatePipelineLayout) X(DestroyPipelineLayout) X(CreateComputePipelines) X(CreateGraphicsPipelines) X(CreatePipelineCache) X(DestroyPipelineCache) X(GetPipelineCacheData) X(CreateRenderPass) X(DestroyRenderPass) X(CreateFramebuffer) X(DestroyFramebuffer) X(CreateSampler) X(DestroySampler) X(DestroyPipeline) X(CmdBindPipeline) X(CmdBindDescriptorSets) X(CmdPushConstants) X(CmdDispatch) X(CmdBeginRenderPass) X(CmdEndRenderPass) X(CmdSetViewport) X(CmdSetScissor) X(CmdDraw) X(CmdDrawIndirect) X(CmdCopyImage) X(CmdCopyBufferToImage) X(CmdClearColorImage) X(CmdClearDepthStencilImage) X(CmdPipelineBarrier) X(CmdCopyBuffer) X(CmdCopyImageToBuffer) X(CmdBlitImage) X(CreateQueryPool) X(DestroyQueryPool) X(CmdResetQueryPool) X(CmdWriteTimestamp) X(GetQueryPoolResults)
namespace dw::client::vk {
inline HMODULE loader=nullptr;
inline PFN_vkGetInstanceProcAddr GetInstanceProcAddr=nullptr;
#define DW_DECLARE(n) inline PFN_vk##n n=nullptr;
DW_VK_GLOBAL(DW_DECLARE)
DW_VK_INSTANCE(DW_DECLARE)
DW_VK_DEVICE(DW_DECLARE)
#undef DW_DECLARE
inline PFN_vkCreateDebugUtilsMessengerEXT CreateDebugUtilsMessengerEXT=nullptr;
inline PFN_vkDestroyDebugUtilsMessengerEXT DestroyDebugUtilsMessengerEXT=nullptr;
inline void check(VkResult r,const char* what){if(r!=VK_SUCCESS)throw std::runtime_error(std::string(what)+": VkResult "+std::to_string(r));}
inline void global(){
    loader=LoadLibraryExW(L"vulkan-1.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);
    if(!loader)throw std::runtime_error("Vulkan driver loader vulkan-1.dll is unavailable");
    GetInstanceProcAddr=std::bit_cast<PFN_vkGetInstanceProcAddr>(GetProcAddress(loader,"vkGetInstanceProcAddr"));
    if(!GetInstanceProcAddr)throw std::runtime_error("vkGetInstanceProcAddr missing");
#define DW_LOAD(n) n=reinterpret_cast<PFN_vk##n>(GetInstanceProcAddr(nullptr,"vk" #n));if(!n)throw std::runtime_error("Missing vk" #n);
    DW_VK_GLOBAL(DW_LOAD)
#undef DW_LOAD
}
inline void instance(VkInstance i){
#define DW_LOAD(n) n=reinterpret_cast<PFN_vk##n>(GetInstanceProcAddr(i,"vk" #n));if(!n)throw std::runtime_error("Missing vk" #n);
    DW_VK_INSTANCE(DW_LOAD)
#undef DW_LOAD
    CreateDebugUtilsMessengerEXT=reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(GetInstanceProcAddr(i,"vkCreateDebugUtilsMessengerEXT"));
    DestroyDebugUtilsMessengerEXT=reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(GetInstanceProcAddr(i,"vkDestroyDebugUtilsMessengerEXT"));
}
inline void device(VkDevice d){
#define DW_LOAD(n) n=reinterpret_cast<PFN_vk##n>(GetDeviceProcAddr(d,"vk" #n));if(!n)throw std::runtime_error("Missing vk" #n);
    DW_VK_DEVICE(DW_LOAD)
#undef DW_LOAD
}
}
