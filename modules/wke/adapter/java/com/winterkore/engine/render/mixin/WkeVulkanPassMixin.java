// Executes world draw commands through WKE; descriptors, matrices, dynamic state and submission remain host-owned.
package com.winterkore.engine.render.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.winterkore.engine.render.WkeRendererRuntime;
import com.winterkore.engine.render.WkeVulkanBridge;
import com.winterkore.engine.render.WkeVulkanNative;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.IntBuffer;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static java.lang.foreign.ValueLayout.*;

@Mixin(value = VulkanRenderPass.class, remap = false)
public abstract class WkeVulkanPassMixin {
    @Shadow @Final private VulkanDevice device;
    @Shadow @Final private VkCommandBuffer commandBuffer;
    @Shadow private void pushDescriptors() { throw new AssertionError(); }
    @Unique private long wke$pipeline, wke$vertex0, wke$vertexOffset0, wke$vertex1, wke$vertexOffset1, wke$index;
    @Unique private int wke$indexType;
    @Unique private boolean wke$unsupportedVertexBinding;
    @Unique private static final ThreadLocal<MemorySegment> wke$singlePacket =
        ThreadLocal.withInitial(() -> Arena.ofAuto().allocate(WkeVulkanNative.PACKET_BYTES, 8));

    // This sees the actual pipeline after the existing actual-depth-format mixin selected its variant.
    @WrapOperation(method = "setPipeline", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/VK12;vkCmdBindPipeline(Lorg/lwjgl/vulkan/VkCommandBuffer;IJ)V"), require = 1)
    private void wke$captureBoundPipeline(VkCommandBuffer commands, int bindPoint, long pipeline, Operation<Void> original) {
        original.call(commands, bindPoint, pipeline);
        wke$pipeline = pipeline;
    }

    @Inject(method = "setVertexBuffer", at = @At("HEAD"), require = 1)
    private void wke$captureVertex(int slot, GpuBufferSlice slice, CallbackInfo ci) {
        if (slice == null) return; // The host also leaves the existing binding unchanged.
        long buffer = ((VulkanGpuBuffer)slice.buffer()).vkBuffer();
        if (slot == 0) { wke$vertex0 = buffer; wke$vertexOffset0 = slice.offset(); }
        else if (slot == 1) { wke$vertex1 = buffer; wke$vertexOffset1 = slice.offset(); }
        else wke$unsupportedVertexBinding = true;
    }

    @Inject(method = "setIndexBuffer", at = @At("HEAD"), require = 1)
    private void wke$captureIndex(GpuBuffer buffer, IndexType type, CallbackInfo ci) {
        wke$index = ((VulkanGpuBuffer)buffer).vkBuffer();
        wke$indexType = type == IndexType.SHORT ? 0 : 1;
    }

    @Unique private boolean wke$eligible() {
        if (!WkeRendererRuntime.nativeWorldActive() && !WkeRendererRuntime.nativeTerrainActive()) return false;
        if (wke$unsupportedVertexBinding || !WkeVulkanBridge.owns(device, wke$pipeline)) {
            WkeVulkanBridge.hostDraw();
            return false;
        }
        return true;
    }
    @Unique private MemorySegment wke$basePacket(MemorySegment packet, int operation) {
        packet.fill((byte)0);
        packet.set(JAVA_LONG, 0, wke$pipeline);
        packet.set(JAVA_LONG, 8, wke$vertex0);
        packet.set(JAVA_LONG, 16, wke$vertexOffset0);
        packet.set(JAVA_LONG, 24, wke$vertex1);
        packet.set(JAVA_LONG, 32, wke$vertexOffset1);
        packet.set(JAVA_LONG, 40, wke$index);
        packet.set(JAVA_INT, 72, operation);
        packet.set(JAVA_INT, 76, wke$vertex1 != 0 ? 2 : wke$vertex0 != 0 ? 1 : 0);
        packet.set(JAVA_INT, 80, wke$indexType);
        return packet;
    }
    @Unique private boolean wke$record(MemorySegment packets, int count) {
        // Identical layout and pipeline are used by both descriptor updates and native execution.
        pushDescriptors();
        int result = WkeVulkanBridge.recordTerrain(device, wke$pipeline, commandBuffer.address(), packets, count);
        if (result == 0) return true;
        WkeVulkanBridge.hostDraw();
        return false;
    }

    @Inject(method = "drawIndexed", at = @At("HEAD"), cancellable = true, require = 1)
    private void wke$drawIndexed(int indices, int instances, int firstIndex, int vertexOffset, int firstInstance, CallbackInfo ci) {
        if (!wke$eligible()) return;
        MemorySegment packet = wke$basePacket(wke$singlePacket.get(), 1);
        packet.set(JAVA_INT, 88, indices);
        packet.set(JAVA_INT, 92, instances);
        packet.set(JAVA_INT, 100, firstIndex);
        packet.set(JAVA_INT, 104, vertexOffset);
        packet.set(JAVA_INT, 108, firstInstance);
        if (wke$record(packet, 1)) ci.cancel();
    }

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true, require = 1)
    private void wke$draw(int vertices, int instances, int firstVertex, int firstInstance, CallbackInfo ci) {
        if (!wke$eligible()) return;
        MemorySegment packet = wke$basePacket(wke$singlePacket.get(), 0);
        packet.set(JAVA_INT, 84, vertices);
        packet.set(JAVA_INT, 92, instances);
        packet.set(JAVA_INT, 96, firstVertex);
        packet.set(JAVA_INT, 108, firstInstance);
        if (wke$record(packet, 1)) ci.cancel();
    }

    @Inject(method = "drawIndexedIndirect", at = @At("HEAD"), cancellable = true, require = 1)
    private void wke$drawIndexedIndirect(GpuBufferSlice commands, int count, CallbackInfo ci) {
        if (!wke$eligible()) return;
        MemorySegment packet = wke$basePacket(wke$singlePacket.get(), 3);
        packet.set(JAVA_LONG, 56, ((VulkanGpuBuffer)commands.buffer()).vkBuffer());
        packet.set(JAVA_LONG, 64, commands.offset());
        packet.set(JAVA_INT, 112, count);
        packet.set(JAVA_INT, 116, 20);
        if (wke$record(packet, 1)) ci.cancel();
    }

    @Inject(method = "drawIndirect", at = @At("HEAD"), cancellable = true, require = 1)
    private void wke$drawIndirect(GpuBufferSlice commands, int count, CallbackInfo ci) {
        if (!wke$eligible()) return;
        MemorySegment packet = wke$basePacket(wke$singlePacket.get(), 2);
        packet.set(JAVA_LONG, 56, ((VulkanGpuBuffer)commands.buffer()).vkBuffer());
        packet.set(JAVA_LONG, 64, commands.offset());
        packet.set(JAVA_INT, 112, count);
        packet.set(JAVA_INT, 116, 16);
        if (wke$record(packet, 1)) ci.cancel();
    }

    @Inject(method = "multiDrawIndexed(Ljava/nio/IntBuffer;III)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void wke$multiDrawIndexed(IntBuffer parameters, int instances, int firstInstance, int count, CallbackInfo ci) {
        if (!wke$eligible()) return;
        if (count <= 0 || count > 65536 || parameters.remaining() < (long)count * 3) {
            WkeVulkanBridge.hostDraw(); return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment packets = arena.allocate((long)count * WkeVulkanNative.PACKET_BYTES, 8);
            int base = parameters.position();
            for (int i = 0; i < count; i++) {
                MemorySegment packet = wke$basePacket(packets.asSlice((long)i * WkeVulkanNative.PACKET_BYTES, WkeVulkanNative.PACKET_BYTES), 1);
                packet.set(JAVA_INT, 88, parameters.get(base + i * 3 + 1));
                packet.set(JAVA_INT, 92, instances);
                packet.set(JAVA_INT, 100, parameters.get(base + i * 3));
                packet.set(JAVA_INT, 104, parameters.get(base + i * 3 + 2));
                packet.set(JAVA_INT, 108, firstInstance);
            }
            if (wke$record(packets, count)) ci.cancel();
        }
    }

    @Inject(method = "multiDraw(Ljava/nio/IntBuffer;III)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void wke$multiDraw(IntBuffer parameters, int instances, int firstInstance, int count, CallbackInfo ci) {
        if (!wke$eligible()) return;
        if (count <= 0 || count > 65536 || parameters.remaining() < (long)count * 2) {
            WkeVulkanBridge.hostDraw(); return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment packets = arena.allocate((long)count * WkeVulkanNative.PACKET_BYTES, 8);
            int base = parameters.position();
            for (int i = 0; i < count; i++) {
                MemorySegment packet = wke$basePacket(packets.asSlice((long)i * WkeVulkanNative.PACKET_BYTES, WkeVulkanNative.PACKET_BYTES), 0);
                packet.set(JAVA_INT, 84, parameters.get(base + i * 2 + 1));
                packet.set(JAVA_INT, 92, instances);
                packet.set(JAVA_INT, 96, parameters.get(base + i * 2));
                packet.set(JAVA_INT, 108, firstInstance);
            }
            if (wke$record(packets, count)) ci.cancel();
        }
    }
}
