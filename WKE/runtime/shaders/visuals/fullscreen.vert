#version 450
// Native Vulkan screenquad adapter. UV (0,0) is the top-left render-target pixel.
// All source fragment functions retain their original texture-coordinate convention.
layout(location=0) out vec2 texCoord;
void main() {
    vec2 uv=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
    texCoord=uv;
    gl_Position=vec4(uv*2.0-1.0,0.0,1.0);
}
