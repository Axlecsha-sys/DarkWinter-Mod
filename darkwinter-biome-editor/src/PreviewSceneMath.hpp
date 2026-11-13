// Builds the retained scene's Vulkan clip transform from the same double-precision work-plane camera.
#pragma once
#include "PreviewViewportMath.hpp"
#include "SceneBundle.hpp"
#include <array>
#include <limits>
namespace PreviewSceneMath {
struct View {
    std::array<float,16> matrix{};
    std::array<double,3> forward{};
    double eyeDot=0,nearPlane=.05,farPlane=2048,distance=128;
};
inline View view(const PreviewMath::Camera &camera,const std::array<double,3> &origin,QSize extent,double sceneDiagonal) {
    View result;const auto b=PreviewMath::basis(camera);
    const double x=camera.x-origin[0],y=camera.y-origin[1],z=camera.z-origin[2];
    result.forward={b.fx,b.fy,b.fz};result.distance=std::max({128.0,camera.span*1.2,sceneDiagonal+64});
    result.farPlane=result.distance+std::max(1024.0,sceneDiagonal*2);
    result.eyeDot=b.fx*x+b.fy*y+b.fz*z-result.distance;
    const double sx=2/(camera.span*std::max(1,extent.width())/std::max(1,extent.height())),sy=2/camera.span;
    const double dz=result.farPlane-result.nearPlane;
    // Column-major. This includes QVulkanWindow's Y inversion and -1..1 to 0..1 depth correction.
    result.matrix={float(b.rx*sx),float(-b.ux*sy),float(b.fx/dz),0,
        0,float(-b.uy*sy),float(b.fy/dz),0,
        float(b.rz*sx),float(-b.uz*sy),float(b.fz/dz),0,
        float(-(b.rx*x+b.rz*z)*sx),float((b.ux*x+b.uy*y+b.uz*z)*sy),
        float((result.distance-result.nearPlane-b.fx*x-b.fy*y-b.fz*z)/dz),1};
    return result;
}
inline std::array<double,4> transform(const std::array<float,16> &m,double x,double y,double z) {
    return {m[0]*x+m[4]*y+m[8]*z+m[12],m[1]*x+m[5]*y+m[9]*z+m[13],
        m[2]*x+m[6]*y+m[10]*z+m[14],m[3]*x+m[7]*y+m[11]*z+m[15]};
}
inline bool visible(const std::array<double,6> &bounds,const View &view) {
    bool outside[6]={true,true,true,true,true,true};
    for(int i=0;i<8;++i){
        const auto p=transform(view.matrix,bounds[(i&1)?3:0],bounds[(i&2)?4:1],bounds[(i&4)?5:2]);
        outside[0]&=p[0]<-p[3]-.0001;outside[1]&=p[0]>p[3]+.0001;
        outside[2]&=p[1]<-p[3]-.0001;outside[3]&=p[1]>p[3]+.0001;
        outside[4]&=p[2]<-.0001;outside[5]&=p[2]>p[3]+.0001;
    }
    return std::none_of(std::begin(outside),std::end(outside),[](bool value){return value;});
}
inline double depth(const std::array<double,6> &bounds,const View &view) {
    return .5*((bounds[0]+bounds[3])*view.forward[0]+(bounds[1]+bounds[4])*view.forward[1]+(bounds[2]+bounds[5])*view.forward[2])-view.eyeDot;
}
inline double diagonal(const QVector<ScenePart> &parts) {
    if(parts.isEmpty())return 128;
    std::array<double,3> low{std::numeric_limits<double>::max(),std::numeric_limits<double>::max(),std::numeric_limits<double>::max()};
    std::array<double,3> high{-low[0],-low[1],-low[2]};
    for(const auto &part:parts)for(int i=0;i<3;++i){low[i]=std::min(low[i],part.bounds[i]);high[i]=std::max(high[i],part.bounds[i+3]);}
    return std::hypot(high[0]-low[0],high[1]-low[1],high[2]-low[2]);
}
}
