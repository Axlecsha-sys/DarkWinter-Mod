// Computes curved cloud-layer ray intervals above the flat voxel terrain.
#ifndef DARKWINTER_CLOUD_SHELL
#define DARKWINTER_CLOUD_SHELL
// Visual curvature only; terrain remains the existing flat voxel world.
const float DW_CLOUD_RADIUS=120000.0;
const float DW_CLOUD_BOTTOM=300.0;
const float DW_CLOUD_TOP=500.0;
bool dwSphereInterval(vec3 ray,float radius,out vec2 hit) {
    float r=DW_CLOUD_RADIUS+CameraInfo.y;
    float b=r*ray.y;
    float c=(r-radius)*(r+radius);
    float discriminant=b*b-c;
    if(discriminant<0.0) return false;
    float root=sqrt(discriminant);
    float q=-b-(b<0.0?-root:root);
    float other=abs(q)>0.00001?c/q:-b;
    hit=vec2(min(q,other),max(q,other));
    return hit.y>0.0;
}
bool dwCloudInterval(vec3 ray,out vec2 interval) {
    vec2 outerHit,innerHit,groundHit;
    if(!dwSphereInterval(ray,DW_CLOUD_RADIUS+DW_CLOUD_TOP,outerHit)) return false;
    float a=max(outerHit.x,0.0),b=outerHit.y;
    if(dwSphereInterval(ray,DW_CLOUD_RADIUS+DW_CLOUD_BOTTOM,innerHit)) {
        if(innerHit.x<=a && innerHit.y>a) a=innerHit.y;
        else if(innerHit.x>a) b=min(b,innerHit.x);
    }
    if(dwSphereInterval(ray,DW_CLOUD_RADIUS,groundHit) && groundHit.x>0.0)
        b=min(b,groundHit.x);
    interval=vec2(a,b);
    return b>a;
}
float dwCloudHeight(vec3 ray,float distanceBlocks) {
    vec2 xz=ray.xz*distanceBlocks;
    float y=CameraInfo.y+ray.y*distanceBlocks;
    float r=sqrt(dot(xz,xz)+(DW_CLOUD_RADIUS+y)*(DW_CLOUD_RADIUS+y));
    return (dot(xz,xz)+y*(2.0*DW_CLOUD_RADIUS+y))/(r+DW_CLOUD_RADIUS);
}
// Conservative depth gate: a cloud behind nearby terrain cannot tint that terrain.
// Partial traversal uses a homogeneous approximation of the existing integrated cloud opacity.
float dwVisibleCloudAlpha(vec3 ray,float distanceBlocks,float fullAlpha) {
    vec2 span;
    if(fullAlpha<=0.00001 || !dwCloudInterval(ray,span) || distanceBlocks<=span.x) return 0.0;
    float fraction=clamp((distanceBlocks-span.x)/max(span.y-span.x,0.001),0.0,1.0);
    return 1.0-pow(max(1.0-fullAlpha,0.0001),fraction);
}
#endif
