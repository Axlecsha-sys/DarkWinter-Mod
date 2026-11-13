#include "vulkan_api.hpp"
// Compile the allocator once. Shared ABI options come from the native build flags.
#define VMA_IMPLEMENTATION
#include <vk_mem_alloc.h>
