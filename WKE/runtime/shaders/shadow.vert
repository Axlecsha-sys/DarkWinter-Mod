#version 450
layout(set=0,binding=1,std430) readonly buffer Assets {uint a[];};
layout(set=0,binding=5,std430) readonly buffer Avatar {uint v[];};
layout(set=0,binding=6,std430) readonly buffer Instances {uvec4 instances[];};
layout(set=0,binding=7,std430) readonly buffer TriangleRefs {uint triangleRefs[];};
layout(set=0,binding=8,std140) uniform SceneFrame {
    mat4 inverseViewProjection;
    mat4 shadowFromRelative;
    vec4 Sun;vec4 Air;vec4 Shadow;vec4 Climate;vec4 WindTime;vec4 Flags;
    vec4 CameraInfo;vec4 Options;vec4 Look;vec4 Analog;vec4 FogLayer;vec4 FogStyle;
    vec4 FogRange;vec4 WeatherShape;vec4 WeatherTint;vec4 CloudDrift;vec4 Film;vec4 FilmTiming;
} scene;
layout(push_constant) uniform View {
    vec4 origin;vec4 forward;vec4 right;vec4 up;uvec4 settings;
    vec4 actor;vec4 pose;vec4 avatar;
} view;
layout(location=0) out vec2 uv;
layout(location=1) out vec3 localPoint;
layout(location=2) flat out vec3 surfaceNormal;
layout(location=3) out vec3 relativePoint;
layout(location=4) flat out uvec2 triangleShape;
layout(location=5) flat out vec3 instanceOrigin;

vec3 av(uint at){return uintBitsToFloat(uvec3(a[at],a[at+1u],a[at+2u]));}
vec2 au(uint at){return uintBitsToFloat(uvec2(a[at],a[at+1u]));}
vec3 vv(uint at){return uintBitsToFloat(uvec3(v[at],v[at+1u],v[at+2u]));}
vec2 vu(uint at){return uintBitsToFloat(uvec2(v[at],v[at+1u]));}
vec3 ry(vec3 p,float angle){float c=cos(angle),s=sin(angle);return vec3(c*p.x+s*p.z,p.y,-s*p.x+c*p.z);}
vec3 rx(vec3 p,float angle){float c=cos(angle),s=sin(angle);return vec3(p.x,c*p.y-s*p.z,s*p.y+c*p.z);}

void worldVertex(uint corner) {
    uvec4 instance=instances[uint(gl_InstanceIndex)];
    uint shape=instance.w,t=a[3]+triangleRefs[uint(gl_VertexIndex)/3u]*24u;
    vec3 p=av(t);vec2 tex=au(t+12u);
    if(corner==1u){p+=av(t+4u);tex+=au(t+14u);}
    if(corner==2u){p+=av(t+8u);tex+=au(t+16u);}
    instanceOrigin=uintBitsToFloat(instance.xyz);
    localPoint=p;uv=tex;surfaceNormal=av(t+20u);triangleShape=uvec2(t,shape);
    relativePoint=(instanceOrigin-view.origin.xyz)+p;
}
void actorVertex(uint corner) {
    // firstInstance is the bone number, firstVertex is bone.triangleStart*3.
    uint bone=uint(gl_InstanceIndex),d=v[1]+bone*12u;
    uint t=v[3]+(uint(gl_VertexIndex)/3u)*24u;
    vec3 p=vv(t);vec2 tex=vu(t+12u);
    if(corner==1u){p+=vv(t+4u);tex+=vu(t+14u);}
    if(corner==2u){p+=vv(t+8u);tex+=vu(t+16u);}
    float angle=0,headYaw=0;
    if(bone==1u){angle=clamp(view.pose.w,-1.2,1.2);headYaw=clamp(view.pose.z,-.8,.8);}
    else if(bone>=2u&&bone<6u) {
        float sign=(bone==2u||bone==5u)?1:-1;
        angle=sin(view.pose.x)*view.pose.y*.58*sign;
    }
    vec3 pivot=vv(d);
    if(bone>=4u&&bone<6u) {
        uint root=v[2]+v[d+4u]*12u;
        vec3 lo=vv(root),hi=vv(root+4u);
        float s=sin(angle),c=cos(angle),minY=lo.y*c-(s>0?hi.z:lo.z)*s;
        float legSign=bone==5u?1:-1;
        float lift=pow(max(sin(view.pose.x)*legSign,0),2)*.10*view.pose.y;
        pivot.y+=lift-(pivot.y+minY);
    }
    vec3 posed=pivot+ry(rx(p,angle),headYaw);
    vec3 normal=ry(rx(vv(t+20u),angle),headYaw);
    if(view.settings.w==2u) {
        float bob=sin(view.pose.x*2)*.010*view.pose.y;
        relativePoint=view.right.xyz*posed.x+view.up.xyz*(posed.y+bob)-view.forward.xyz*posed.z;
        surfaceNormal=view.right.xyz*normal.x+view.up.xyz*normal.y-view.forward.xyz*normal.z;
    } else {
        float body=view.actor.w+3.14159265359;
        relativePoint=(view.actor.xyz-view.origin.xyz)+ry(posed,body);
        surfaceNormal=ry(normal,body);
    }
    localPoint=p;uv=tex;triangleShape=uvec2(t,bone);instanceOrigin=view.actor.xyz;
}
void main() {
    uint corner=uint(gl_VertexIndex)%3u;
    if(view.settings.w==0u)worldVertex(corner);else actorVertex(corner);
    // The same alpha-tested triangles and actor pose cast the shadow.
    // Light matrix takes a camera-relative point; projection/bias belong to caller.
    gl_Position=scene.shadowFromRelative*vec4(relativePoint,1);
}
