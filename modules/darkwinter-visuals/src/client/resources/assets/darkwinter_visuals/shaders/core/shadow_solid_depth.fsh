// Records solid terrain depth without evaluating texture color, lighting or fog.
#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) out vec4 fragColor;

void main() {
    // The pipeline's color write mask is zero; keep the existing RGBA8 attachment contract.
    fragColor = vec4(0.0);
}
