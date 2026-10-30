// Presents Vulkan frames through a capability-checked Windows DXGI flip swapchain.
package com.dark.winter.client.vulkan;

import com.mojang.renderpearl.api.device.GpuSurface.Configuration;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.device.GpuSurface.PresentMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanUtils;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DxgiVulkanSurface implements GpuSurfaceBackend {
    public static final FeatureSet FEATURES = new FeatureSet("DarkWinter DXGI presentation",
            Set.of("VK_KHR_external_memory_win32", "VK_KHR_external_semaphore_win32"), Set.of());
    private static final Logger LOG = LoggerFactory.getLogger("DarkWinter/DXGI");
    private static final int D3D12_RESOURCE_HANDLE = 64;
    private static final int D3D12_FENCE_HANDLE = 8;
    private static final long TRANSFER_STAGE = 0x1000L;
    private static final long ALL_COMMANDS_STAGE = 0x10000L;
    private final VulkanDevice device;
    private final long hwnd;
    private WindowsDxgi windows;
    private WindowsDxgi.Com swapchain;
    private WindowsDxgi.Com commandList;
    private final List<WindowsDxgi.Com> backBuffers = new ArrayList<>();
    private final List<WindowsDxgi.Com> sharedTextures = new ArrayList<>();
    private final List<WindowsDxgi.Com> allocators = new ArrayList<>();
    private final List<Long> textureHandles = new ArrayList<>();
    private final List<Long> images = new ArrayList<>();
    private final List<Long> memories = new ArrayList<>();
    private long semaphore, frameWaitHandle, fenceValue;
    private int width, height;
    private PresentMode presentMode = PresentMode.FIFO;
    private boolean closed;

    private DxgiVulkanSurface(VulkanDevice device, long window) {
        this.device = device;
        hwnd = SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(window), "SDL.window.win32.hwnd", 0L);
        if (hwnd == 0) throw new IllegalStateException("SDL did not expose a Windows HWND");
    }

    public static boolean platformSupported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.startsWith("windows") && (arch.equals("amd64") || arch.equals("x86_64"))
                && !"1".equals(System.getenv("ENABLE_VULKAN_RENDERDOC_CAPTURE"))
                && !"1".equals(System.getenv("RENDERPEARL_DISABLE_DXGI_PRESENT"));
    }

    public static DxgiVulkanSurface tryCreate(VulkanDevice device, long window) {
        if (!platformSupported() || !device.vkDevice().getCapabilities().VK_KHR_external_memory_win32
                || !device.vkDevice().getCapabilities().VK_KHR_external_semaphore_win32) return null;
        DxgiVulkanSurface surface = null;
        try {
            if (!supportsImports(device)) return null;
            surface = new DxgiVulkanSurface(device, window);
            surface.initialize();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var pixelWidth = stack.callocInt(1);
                var pixelHeight = stack.callocInt(1);
                if (!SDLVideo.SDL_GetWindowSizeInPixels(window, pixelWidth, pixelHeight)
                        || pixelWidth.get(0) <= 0 || pixelHeight.get(0) <= 0)
                    throw new IllegalStateException("SDL window has no usable pixel extent for DXGI");
                // Preflight the actual swapchain and shared imports while native fallback is still possible.
                // The frontend's first normal configure call still chooses the user's presentation mode.
                surface.configure(new Configuration(pixelWidth.get(0), pixelHeight.get(0), PresentMode.FIFO));
            }
            LOG.info("Vulkan uses DXGI presentation; configured VSync and frame limits remain unchanged");
            return surface;
        } catch (SurfaceException | RuntimeException | LinkageError failure) {
            if (surface != null) {
                try { surface.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            LOG.warn("DXGI presentation is unavailable; keeping the original Vulkan surface", failure);
            return null;
        }
    }

    private static boolean supportsImports(VulkanDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDevice physical = device.vkDevice().getPhysicalDevice();
            var imageInfo = VkPhysicalDeviceImageFormatInfo2.calloc(stack).sType$Default()
                    .format(VK12.VK_FORMAT_R8G8B8A8_UNORM).type(VK12.VK_IMAGE_TYPE_2D)
                    .tiling(VK12.VK_IMAGE_TILING_OPTIMAL).usage(VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            var externalInfo = VkPhysicalDeviceExternalImageFormatInfo.calloc(stack).sType$Default().handleType(D3D12_RESOURCE_HANDLE);
            imageInfo.pNext(externalInfo);
            var imageProperties = VkImageFormatProperties2.calloc(stack).sType$Default();
            var externalProperties = VkExternalImageFormatProperties.calloc(stack).sType$Default();
            imageProperties.pNext(externalProperties);
            if (VK12.vkGetPhysicalDeviceImageFormatProperties2(physical, imageInfo, imageProperties) != VK12.VK_SUCCESS
                    || (externalProperties.externalMemoryProperties().externalMemoryFeatures() & 4) == 0) return false;
            var semaphoreInfo = VkPhysicalDeviceExternalSemaphoreInfo.calloc(stack).sType$Default().handleType(D3D12_FENCE_HANDLE);
            var semaphoreProperties = VkExternalSemaphoreProperties.calloc(stack).sType$Default();
            VK12.vkGetPhysicalDeviceExternalSemaphoreProperties(physical, semaphoreInfo, semaphoreProperties);
            return (semaphoreProperties.externalSemaphoreFeatures() & 2) != 0;
        }
    }

    private void initialize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var id = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
            var properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(id);
            VK12.vkGetPhysicalDeviceProperties2(device.vkDevice().getPhysicalDevice(), properties);
            if (!id.deviceLUIDValid()) throw new IllegalStateException("Vulkan did not expose a D3D12 adapter LUID");
            long luid = id.deviceLUID().order(ByteOrder.nativeOrder()).getLong(0);
            windows = new WindowsDxgi(luid, id.deviceNodeMask());
            var type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                    .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0);
            var create = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type);
            LongBuffer out = stack.callocLong(1);
            check(VK12.vkCreateSemaphore(device.vkDevice(), create, null, out), "create DXGI shared timeline semaphore");
            semaphore = out.get(0);
            var importInfo = VkImportSemaphoreWin32HandleInfoKHR.calloc(stack).sType$Default()
                    .semaphore(semaphore).handleType(D3D12_FENCE_HANDLE).handle(windows.sharedFence);
            long importFunction = device.vkDevice().getCapabilities().vkImportSemaphoreWin32HandleKHR;
            if (importFunction == 0 || windows.sharedFence == 0 || semaphore == 0)
                throw new IllegalStateException("Cannot import a null D3D12 fence or unavailable Vulkan entry point");
            // LWJGL 3.4.3 wrongly requires both HANDLE and name; Vulkan requires a null name for HANDLE import.
            check(org.lwjgl.system.JNI.callPPI(device.vkDevice().address(), importInfo.address(), importFunction),
                    "import D3D12 presentation fence");
        }
    }

    @Override public Collection<PresentMode> supportedPresentModes() {
        return windows.allowTearing ? Set.of(PresentMode.IMMEDIATE, PresentMode.MAILBOX, PresentMode.FIFO)
                : Set.of(PresentMode.MAILBOX, PresentMode.FIFO);
    }

    @Override public void configure(Configuration config) throws SurfaceException {
        if (config.width() <= 0 || config.height() <= 0)
            throw new SurfaceException("DXGI swapchain extent must be positive: " + config.width() + " x " + config.height());
        if (closed) throw new SurfaceException("DXGI surface is closed");
        if (!supportedPresentModes().contains(config.presentMode())) throw new IllegalArgumentException("Unsupported DXGI present mode");
        presentMode = config.presentMode();
        if (swapchain != null && width == config.width() && height == config.height()) return;
        destroySwapchain();
        width = config.width(); height = config.height();
        try {
            swapchain = windows.createSwapchain(hwnd, width, height);
            frameWaitHandle = swapchain.handle(33);
            if (frameWaitHandle == 0) throw new IllegalStateException("DXGI did not expose its frame-latency waitable handle");
            for (int i = 0; i < WindowsDxgi.SWAPCHAIN_BUFFERS; i++) {
                backBuffers.add(swapchain.create(9, WindowsDxgi.RESOURCE_ID, i));
                WindowsDxgi.Com texture = windows.createSharedTexture(width, height);
                sharedTextures.add(texture);
                textureHandles.add(windows.share(texture));
                allocators.add(windows.createAllocator());
                importTexture(textureHandles.get(i));
            }
            // Each back buffer owns its shared texture, so Vulkan cannot overwrite a D3D12 copy still in flight.
            commandList = windows.createCommandList(allocators.getFirst());
        } catch (RuntimeException | Error failure) {
            try { destroySwapchain(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void importTexture(long handle) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var external = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default().handleTypes(D3D12_RESOURCE_HANDLE);
            var create = VkImageCreateInfo.calloc(stack).sType$Default().pNext(external)
                    .imageType(VK12.VK_IMAGE_TYPE_2D).format(VK12.VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(1).arrayLayers(1).samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK12.VK_IMAGE_TILING_OPTIMAL).usage(VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
            create.extent().set(width, height, 1);
            LongBuffer out = stack.callocLong(1);
            check(VK12.vkCreateImage(device.vkDevice(), create, null, out), "create DXGI import image");
            long image = out.get(0);
            images.add(image);
            var handleProperties = VkMemoryWin32HandlePropertiesKHR.calloc(stack).sType$Default();
            check(KHRExternalMemoryWin32.vkGetMemoryWin32HandlePropertiesKHR(device.vkDevice(), D3D12_RESOURCE_HANDLE, handle, handleProperties),
                    "query shared image memory types");
            var requirements = VkMemoryRequirements.calloc(stack);
            VK12.vkGetImageMemoryRequirements(device.vkDevice(), image, requirements);
            int compatibleTypes = handleProperties.memoryTypeBits() & requirements.memoryTypeBits();
            if (compatibleTypes == 0) throw new IllegalStateException("D3D12 and Vulkan have no shared image memory type");
            var dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(image);
            var externalMemory = VkImportMemoryWin32HandleInfoKHR.calloc(stack).sType$Default()
                    .handleType(D3D12_RESOURCE_HANDLE).handle(handle).pNext(dedicated.address());
            var allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default().pNext(externalMemory)
                    .allocationSize(requirements.size()).memoryTypeIndex(Integer.numberOfTrailingZeros(compatibleTypes));
            out.put(0, 0L);
            check(VK12.vkAllocateMemory(device.vkDevice(), allocation, null, out), "import shared D3D12 texture memory");
            long memory = out.get(0);
            memories.add(memory);
            check(VK12.vkBindImageMemory(device.vkDevice(), image, memory, 0), "bind shared D3D12 texture memory");
        }
    }

    @Override public boolean isSuboptimal() { return false; }

    @Override public void acquireNextTexture() throws SurfaceException {
        if (swapchain == null || closed || frameWaitHandle == 0)
            throw new SurfaceException("DXGI swapchain is not configured");
        int result = WindowsDxgi.waitFrame(frameWaitHandle);
        if (result == 258) throw new SurfaceException("DXGI frame-latency wait timed out");
        if (result != 0) throw new SurfaceException("DXGI frame-latency wait failed: 0x" + Integer.toHexString(result));
    }

    @Override public void blitFromTexture(CommandEncoderBackend encoder, GpuTextureView view) {
        if (swapchain == null || commandList == null) throw new IllegalStateException("DXGI swapchain is not configured");
        int index = swapchain.integer(36);
        if (index < 0 || index >= images.size()) throw new IllegalStateException("Invalid DXGI back-buffer index");
        var commands = (VulkanCommandEncoder)encoder;
        VkCommandBuffer buffer = commands.allocateAndBeginTransientCommandBuffer();
        long target = images.get(index);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var before = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(0).srcAccessMask(0).dstStageMask(TRANSFER_STAGE).dstAccessMask(0x1000L)
                    .oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-2).dstQueueFamilyIndex(device.graphicsQueue().queueFamilyIndex()).image(target);
            range(before.subresourceRange());
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(buffer, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(before));

            int copyWidth = Math.min(width, view.getWidth(0)), copyHeight = Math.min(height, view.getHeight(0));
            var sourceOffsets = VkOffset3D.calloc(2, stack);
            sourceOffsets.get(0).set(0, 0, 0); sourceOffsets.get(1).set(copyWidth, copyHeight, 1);
            var destinationOffsets = VkOffset3D.calloc(2, stack);
            destinationOffsets.get(0).set(0, copyHeight, 0); destinationOffsets.get(1).set(copyWidth, 0, 1);
            var blit = VkImageBlit.calloc(1, stack).srcOffsets(sourceOffsets).dstOffsets(destinationOffsets);
            layers(blit.srcSubresource()).mipLevel(view.baseMipLevel());
            layers(blit.dstSubresource()).mipLevel(0);
            VK12.vkCmdBlitImage(buffer, ((VulkanGpuTexture)view.texture()).vkImage(), VK12.VK_IMAGE_LAYOUT_GENERAL,
                    target, VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK12.VK_FILTER_NEAREST);

            var after = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(TRANSFER_STAGE).srcAccessMask(0x1000L).dstStageMask(ALL_COMMANDS_STAGE).dstAccessMask(0)
                    .oldLayout(VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(device.graphicsQueue().queueFamilyIndex()).dstQueueFamilyIndex(-2).image(target);
            range(after.subresourceRange());
            var sourceMemory = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(TRANSFER_STAGE).srcAccessMask(0x800L).dstStageMask(ALL_COMMANDS_STAGE).dstAccessMask(0x18000L);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(buffer, VkDependencyInfo.calloc(stack).sType$Default()
                    .pImageMemoryBarriers(after).pMemoryBarriers(sourceMemory));
        }
        check(VK12.vkEndCommandBuffer(buffer), "end DXGI blit command buffer");
        commands.execute(buffer);
        commands.signalSemaphore(semaphore, ++fenceValue, TRANSFER_STAGE);
        windows.waitForVulkan(fenceValue);
        windows.copy(commandList, allocators.get(index), backBuffers.get(index), sharedTextures.get(index));
    }

    private static void range(VkImageSubresourceRange range) {
        range.aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
    }

    private static VkImageSubresourceLayers layers(VkImageSubresourceLayers layers) {
        return layers.aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).baseArrayLayer(0).layerCount(1);
    }

    @Override public void present() {
        if (swapchain == null) throw new IllegalStateException("DXGI swapchain is not configured");
        int result = switch (presentMode) {
            case IMMEDIATE -> swapchain.integer(8, 0, 512);
            case MAILBOX -> swapchain.integer(8, 1, 4);
            case FIFO -> swapchain.integer(8, 1, 0);
            case FIFO_RELAXED -> throw new IllegalStateException("Adaptive FIFO is not supported by DXGI presentation");
        };
        WindowsDxgi.hresult(result);
    }

    private void destroySwapchain() {
        if (swapchain != null) {
            check(VK12.vkDeviceWaitIdle(device.vkDevice()), "wait for Vulkan before DXGI resize");
            windows.finishQueue(++fenceValue);
        }
        for (long image : images) VK12.vkDestroyImage(device.vkDevice(), image, null);
        for (long memory : memories) VK12.vkFreeMemory(device.vkDevice(), memory, null);
        images.clear(); memories.clear();
        RuntimeException failure = null;
        for (long handle : textureHandles) {
            try { WindowsDxgi.closeHandle(handle); } catch (RuntimeException error) { failure = append(failure, error); }
        }
        textureHandles.clear();
        if (commandList != null) {
            try { commandList.close(); } catch (RuntimeException error) { failure = append(failure, error); }
            commandList = null;
        }
        for (List<WindowsDxgi.Com> objects : List.of(allocators, sharedTextures, backBuffers)) {
            for (WindowsDxgi.Com object : objects)
                try { object.close(); } catch (RuntimeException error) { failure = append(failure, error); }
            objects.clear();
        }
        if (frameWaitHandle != 0) {
            try { WindowsDxgi.closeHandle(frameWaitHandle); } catch (RuntimeException error) { failure = append(failure, error); }
            frameWaitHandle = 0;
        }
        if (swapchain != null) {
            try { swapchain.close(); } catch (RuntimeException error) { failure = append(failure, error); }
            swapchain = null;
        }
        if (failure != null) throw failure;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        try { destroySwapchain(); } catch (RuntimeException error) { failure = error; }
        if (semaphore != 0) {
            VK12.vkDestroySemaphore(device.vkDevice(), semaphore, null);
            semaphore = 0;
        }
        if (windows != null) {
            try { windows.close(); } catch (RuntimeException error) { failure = append(failure, error); }
            windows = null;
        }
        if (failure != null) throw failure;
    }

    private static RuntimeException append(RuntimeException previous, RuntimeException error) {
        if (previous == null) return error;
        previous.addSuppressed(error); return previous;
    }

    private void check(int result, String operation) { VulkanUtils.crashIfFailure(device, result, operation); }
}
