// Projects actual native BLOCK vertices; baked color already includes native tint, AO and face shading.
#version 450
layout(location=0) in vec3 position;
layout(location=1) in vec4 vertexColor;
layout(location=2) in vec2 texCoord;
layout(location=3) in uint packedLight;
layout(binding=1) uniform sampler2D nativeLightmap;
layout(push_constant) uniform ScenePush {
    mat4 mvp;
    vec4 fogColor;
    vec4 viewDepth;
    vec4 options;
    vec4 state;
} scene;
layout(location=0) out vec2 uv;
layout(location=1) out vec4 color;
layout(location=2) out float depth;
layout(location=3) out float height;
void main(){
    gl_Position=scene.mvp*vec4(position,1.0);
    uv=texCoord;
    vec2 light=vec2(float((packedLight&65535u)>>4u),float((packedLight>>16u)>>4u));
    vec3 illumination=textureLod(nativeLightmap,(clamp(light,vec2(0.0),vec2(15.0))+0.5)/16.0,0.0).rgb;
    color=vec4(vertexColor.rgb*illumination,vertexColor.a);
    depth=dot(position,scene.viewDepth.xyz)-scene.viewDepth.w;
    height=position.y;
}
