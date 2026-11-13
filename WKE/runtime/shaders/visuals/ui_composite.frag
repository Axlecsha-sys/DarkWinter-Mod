#version 450
// Existing native UI pixels are composited after the source film and the first-person hand.
layout(set=0,binding=0) uniform sampler2D MainSampler;
layout(std430,set=0,binding=11) readonly buffer Overlay { uint ui[]; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
 ivec2 size=textureSize(MainSampler,0);
 uvec2 xy=uvec2(clamp(ivec2(gl_FragCoord.xy),ivec2(0),size-1));
 vec4 overlay=vec4(0);
 if(ui[0]!=0u&&ui[1]!=0u){
  uvec2 p=xy*uvec2(ui[0],ui[1])/uvec2(size);
  uint v=ui[4u+p.x+ui[0]*p.y];
  overlay=vec4(v&255u,(v>>8)&255u,(v>>16)&255u,v>>24)/255.;
 }
 if(ui[2]!=0u&&overlay.a>.5){
  float time=uintBitsToFloat(ui[3]);vec2 p=vec2(xy)/vec2(size);
  float mist=exp(-pow((p.y-.72-.04*sin(time*.03+p.x*4))*7,2))*(.5+.5*sin(p.x*5+time*.04));
  overlay.rgb=mix(overlay.rgb,vec3(.62,.72,.76),mist*.045);
  overlay.rgb*=.996+.004*sin(float(xy.y)*3.14159+time*.2);
 }
 vec3 source=texelFetch(MainSampler,ivec2(xy),0).rgb;
 fragColor=vec4(mix(source,overlay.rgb,overlay.a),1);
}
