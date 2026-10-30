// Builds Vulkan dynamic-rendering pipeline variants for the actual depth attachment format.
package com.dark.winter.client.fixes.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanUtils;
import it.unimi.dsi.fastutil.longs.LongList;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputDivisorStateCreateInfoEXT;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDivisorDescriptionEXT;

public final class VulkanDepthPipelineVariants implements AutoCloseable {
    private record ShaderStage(int stage, String entryPoint) {}

    private final VulkanRenderPipeline owner;
    private final String name;
    private final List<ShaderStage> stages;
    private final long[] shaderModules;
    private final List<BackendRenderPipeline.CreateInfo.VertexBuffer> vertexBuffers;
    private final List<BackendRenderPipeline.CreateInfo.AttribBinding> attributes;
    private final @Nullable DepthStencilState depthState;
    private final PolygonMode polygonMode;
    private final boolean cull;
    private final List<@Nullable ColorTargetState> colorTargets;
    private final PrimitiveTopology topology;
    private final DepthFormatVariantCache variants;

    public VulkanDepthPipelineVariants(VulkanRenderPipeline owner, BackendRenderPipeline.CreateInfo info, LongList modules) {
        this.owner = owner;
        this.name = info.name();
        this.stages = info.shaders().stream().map(shader -> new ShaderStage(VulkanConst.toVk(shader.module().type()), shader.entryPoint())).toList();
        this.shaderModules = modules.toLongArray();
        if (this.shaderModules.length != this.stages.size()) throw new IllegalStateException("Shader module count changed during pipeline compilation");
        this.vertexBuffers = List.copyOf(info.vertexBuffers());
        this.attributes = List.copyOf(info.attribBindings());
        this.depthState = info.depthStencilState();
        this.polygonMode = info.polygonMode();
        this.cull = info.cull();
        this.colorTargets = Collections.unmodifiableList(new ArrayList<>(info.colorTargetStates()));
        this.topology = info.primitiveTopology();
        this.variants = new DepthFormatVariantCache(owner.withDepthPipeline(), this::compile,
            handle -> VK12.vkDestroyPipeline(owner.device().vkDevice(), handle, null));
    }

    public long get(GpuFormat depthFormat) {
        if (owner.isClosed()) throw new IllegalStateException("Cannot bind a closed Vulkan pipeline");
        return variants.get(depthFormat);
    }

    @Override
    public void close() {
        // The mixin calls this at vanilla's deferred GPU-safe destruction point, before its shader modules and layout are destroyed.
        variants.close();
    }

    private long compile(GpuFormat depthFormat) {
        VulkanDevice device = owner.device();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(stages.size(), stack);
            for (int i = 0; i < stages.size(); i++) {
                ShaderStage stage = stages.get(i);
                shaderStages.get(i).sType$Default().stage(stage.stage()).module(shaderModules[i]).pName(stack.UTF8(stage.entryPoint()));
            }

            VkVertexInputBindingDescription.Buffer bindings = VkVertexInputBindingDescription.calloc(vertexBuffers.size(), stack);
            VkVertexInputBindingDivisorDescriptionEXT.Buffer divisors = VkVertexInputBindingDivisorDescriptionEXT.calloc(vertexBuffers.size(), stack);
            for (BackendRenderPipeline.CreateInfo.VertexBuffer source : vertexBuffers) {
                bindings.put(VkVertexInputBindingDescription.calloc(stack)
                    .binding(source.bufferSlot()).stride(source.stride()).inputRate(source.stepRate() > 0 ? 1 : 0));
                if (source.stepRate() > 0) divisors.put(VkVertexInputBindingDivisorDescriptionEXT.calloc(stack)
                    .binding(source.bufferSlot()).divisor(source.stepRate()));
            }
            bindings.flip();
            divisors.flip();
            VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(attributes.size(), stack);
            for (BackendRenderPipeline.CreateInfo.AttribBinding source : attributes) {
                attributeDescriptions.put(VkVertexInputAttributeDescription.calloc(stack)
                    .location(source.location()).binding(source.bufferSlot()).offset(source.offset()).format(VulkanConst.toVk(source.format())));
            }
            attributeDescriptions.flip();
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                .pVertexAttributeDescriptions(attributeDescriptions).pVertexBindingDescriptions(bindings);
            if (divisors.hasRemaining()) vertexInput.pNext(VkPipelineVertexInputDivisorStateCreateInfoEXT.calloc(stack)
                .sType$Default().pVertexBindingDivisors(divisors));

            VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType$Default().topology(VulkanConst.toVk(topology));
            VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType$Default().polygonMode(VulkanConst.toVk(polygonMode)).cullMode(cull ? 2 : 0).frontFace(1).lineWidth(1.0F);
            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default();
            if (depthState != null) {
                rasterization.depthBiasEnable(depthState.depthBiasConstant() != 0.0F || depthState.depthBiasScaleFactor() != 0.0F)
                    .depthBiasConstantFactor(depthState.depthBiasConstant()).depthBiasSlopeFactor(depthState.depthBiasScaleFactor());
                depth.depthTestEnable(true).depthWriteEnable(depthState.writeDepth()).depthCompareOp(VulkanConst.toVk(depthState.depthTest()));
            }

            VkPipelineColorBlendAttachmentState.Buffer blendAttachments = VkPipelineColorBlendAttachmentState.calloc(colorTargets.size(), stack);
            IntBuffer colorFormats = stack.mallocInt(colorTargets.size());
            for (int i = 0; i < colorTargets.size(); i++) {
                ColorTargetState target = colorTargets.get(i);
                VkPipelineColorBlendAttachmentState blend = blendAttachments.get(i);
                blend.colorWriteMask(target == null ? 0 : VulkanConst.toVk(target));
                colorFormats.put(i, target == null ? VK12.VK_FORMAT_UNDEFINED : VulkanConst.toVk(target.format()));
                if (target != null && target.blendFunction().isPresent()) configureBlend(blend, target.blendFunction().get());
            }
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blendAttachments);
            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().scissorCount(1).viewportCount(1);
            VkPipelineMultisampleStateCreateInfo samples = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType$Default().rasterizationSamples(1).sampleShadingEnable(false);
            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default().pDynamicStates(stack.ints(1, 0));
            VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack).sType$Default()
                .pColorAttachmentFormats(colorFormats).depthAttachmentFormat(VulkanConst.toVk(depthFormat))
                .stencilAttachmentFormat(VK12.VK_FORMAT_UNDEFINED);
            VkGraphicsPipelineCreateInfo.Buffer createInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                .sType$Default().flags(0).pStages(shaderStages).pVertexInputState(vertexInput).pInputAssemblyState(assembly)
                .pRasterizationState(rasterization).pDepthStencilState(depth).pColorBlendState(colorBlend)
                .pViewportState(viewport).pMultisampleState(samples).pDynamicState(dynamic).layout(owner.pipelineLayout()).pNext(rendering);
            LongBuffer handle = stack.callocLong(1);
            boolean retained = false;
            try {
                VulkanUtils.crashIfFailure(device,
                    VK12.vkCreateGraphicsPipelines(device.vkDevice(), 0L, createInfo, null, handle),
                    "Can't compile depth-format variant " + depthFormat + " for " + name);
                if (handle.get(0) == 0L) throw new IllegalStateException("Vulkan returned a null depth-format pipeline for " + name);
                device.instance().debug().setObjectName(device.vkDevice(), 19, handle.get(0), () -> "Pipeline " + name + " / " + depthFormat);
                retained = true;
                return handle.get(0);
            } finally {
                if (!retained && handle.get(0) != 0L) VK12.vkDestroyPipeline(device.vkDevice(), handle.get(0), null);
            }
        }
    }

    private static void configureBlend(VkPipelineColorBlendAttachmentState target, BlendFunction blend) {
        target.blendEnable(true)
            .colorBlendOp(VulkanConst.toVk(blend.color().op())).alphaBlendOp(VulkanConst.toVk(blend.alpha().op()))
            .dstAlphaBlendFactor(VulkanConst.toVk(blend.alpha().destFactor())).dstColorBlendFactor(VulkanConst.toVk(blend.color().destFactor()))
            .srcAlphaBlendFactor(VulkanConst.toVk(blend.alpha().sourceFactor())).srcColorBlendFactor(VulkanConst.toVk(blend.color().sourceFactor()));
    }
}
