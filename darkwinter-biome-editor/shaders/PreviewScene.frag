// Samples the real stitched block atlas and native lightmap without inventing replacement block models.
#version 450
layout(binding=0) uniform sampler2D nativeAtlas;
layout(push_constant) uniform ScenePush {
    mat4 mvp;
    vec4 fogColor;
    vec4 viewDepth;
    vec4 options;
    vec4 state;
} scene;
layout(location=0) in vec2 uv;
layout(location=1) in vec4 color;
layout(location=2) in float depth;
layout(location=3) in float height;
layout(location=0) out vec4 outColor;
void main(){
    if(height>scene.options.z)discard;
    vec4 pixel=texture(nativeAtlas,uv)*color;
    if(scene.options.w>0.5&&scene.options.w<1.5&&pixel.a<0.1)discard;
    if(scene.options.w>1.5&&pixel.a<0.0039)discard;
    float fog=scene.state.y*clamp((depth-scene.options.x)/max(0.001,scene.options.y-scene.options.x),0.0,1.0);
    vec3 result=mix(pixel.rgb,scene.fogColor.rgb,fog*fog);
    if(scene.state.x>0.5)result=mix(result/12.92,pow((result+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),result));
    outColor=vec4(result,scene.options.w>1.5?pixel.a:1.0);
}
