// Exposes per-format Vulkan pipeline selection without changing the Minecraft 26.3 pipeline API.
package com.dark.winter.client.fixes.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;

public interface DepthFormatPipelineAccess {
    void darkwinter$captureDepthPipelineInfo(BackendRenderPipeline.CreateInfo info);
    long darkwinter$pipelineForDepthFormat(GpuFormat format);
}
