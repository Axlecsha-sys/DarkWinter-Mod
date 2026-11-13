package com.dark.winter.client.text;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Preserve TrueType coverage and filter at the final GUI scale, including nested inventory transforms. */
public final class SmoothUiText {
    public static final int ATLAS_GUTTER=4;
    public static final RenderPipeline PIPELINE=RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_TEXT_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("darkwinter","pipeline/ui_text"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("darkwinter","core/ui_text"))
            .build());
    private SmoothUiText() {}
    public static void initialize() { }
}
