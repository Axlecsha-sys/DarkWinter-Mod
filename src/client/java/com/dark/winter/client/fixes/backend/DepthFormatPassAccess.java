// Carries the actual depth attachment format from a Vulkan render-pass descriptor to pipeline binding.
package com.dark.winter.client.fixes.backend;

import com.mojang.renderpearl.api.GpuFormat;
import org.jspecify.annotations.Nullable;

public interface DepthFormatPassAccess {
    void darkwinter$setDepthAttachmentFormat(@Nullable GpuFormat format);
}
