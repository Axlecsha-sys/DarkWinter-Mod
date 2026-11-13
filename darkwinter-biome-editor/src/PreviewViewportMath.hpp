// Defines the editor's orthographic camera and exact work-plane coordinate transforms.
#pragma once
#include <QJsonObject>
#include <QPointF>
#include <QRectF>
#include <QSizeF>
#include <QSize>
#include <QVector>
#include <algorithm>
#include <cmath>
#include <optional>

namespace PreviewMath {
constexpr double Pi=3.14159265358979323846;
inline bool validFrame(QSize size,qsizetype bytes,quint64 revision) {
    if(size.width()<1||size.height()<1||size.width()>4096||size.height()>4096||revision==0||revision>9007199254740991ULL)return false;
    return qint64(size.width())*qint64(size.height())*4==bytes;
}
struct Camera {
    double x=40,y=76,z=40,yaw=45,pitch=54.73561,span=112;
    QJsonObject json() const {return {{"targetX",x},{"targetY",y},{"targetZ",z},{"yaw",yaw},{"pitch",pitch},{"span",span}};}
};
inline bool same(const Camera &a,const Camera &b) {
    return a.x==b.x&&a.y==b.y&&a.z==b.z&&a.yaw==b.yaw&&a.pitch==b.pitch&&a.span==b.span;
}
inline std::optional<Camera> camera(const QJsonObject &json,Camera value={}) {
    const char *keys[]={"targetX","targetY","targetZ","yaw","pitch","span"};
    double *fields[]={&value.x,&value.y,&value.z,&value.yaw,&value.pitch,&value.span};
    for(int i=0;i<6;i++)if(json.contains(keys[i])) {
        auto item=json.value(keys[i]);if(!item.isDouble()||!std::isfinite(item.toDouble()))return {};
        *fields[i]=item.toDouble();
    }
    value.x=std::clamp(value.x,-30000000.0,30000000.0);value.z=std::clamp(value.z,-30000000.0,30000000.0);
    value.y=std::clamp(value.y,-2048.0,4096.0);
    value.yaw=std::remainder(value.yaw,360.0);value.pitch=std::clamp(value.pitch,15.0,90.0);value.span=std::clamp(value.span,4.0,4096.0);
    return value;
}
inline QRectF fit(QSizeF viewport,QSizeF image) {
    if(viewport.width()<=0||viewport.height()<=0||image.width()<=0||image.height()<=0)return {};
    double scale=std::min(viewport.width()/image.width(),viewport.height()/image.height());
    QSizeF size(image.width()*scale,image.height()*scale);
    return {(viewport.width()-size.width())*.5,(viewport.height()-size.height())*.5,size.width(),size.height()};
}
inline QVector<QPointF> resample(const QVector<QPointF> &points,int limit=32) {
    if(points.isEmpty())return {};
    if(limit<2)return {points.last()};
    if(points.size()<=limit)return points;
    QVector<double> distance(points.size(),0);
    for(qsizetype i=1;i<points.size();i++)distance[i]=distance[i-1]+std::hypot(points[i].x()-points[i-1].x(),points[i].y()-points[i-1].y());
    double total=distance.last();if(total<=1e-9)return {points.first()};
    QVector<QPointF> result;result.reserve(limit);qsizetype segment=1;
    for(int i=0;i<limit;i++) {
        double target=total*i/(limit-1);
        while(segment+1<points.size()&&distance[segment]<target)++segment;
        double length=distance[segment]-distance[segment-1];
        double t=length>1e-12?(target-distance[segment-1])/length:0;
        result.push_back(points[segment-1]+(points[segment]-points[segment-1])*t);
    }
    result.first()=points.first();result.last()=points.last();return result;
}
struct Basis {double rx,rz,ux,uy,uz,fx,fy,fz;};
inline Basis basis(const Camera &c) {
    double yaw=c.yaw*Pi/180,pitch=c.pitch*Pi/180,sy=std::sin(yaw),cy=std::cos(yaw),sp=std::sin(pitch),cp=std::cos(pitch);
    // Minecraft yaw zero faces +Z; screen right then points toward -X.
    return {-cy,-sy,-sy*sp,cp,cy*sp,-sy*cp,-sp,cy*cp};
}
inline std::optional<QPointF> unproject(QPointF point,QRectF rect,const Camera &c,double workY) {
    if(rect.isEmpty()||!rect.contains(point)||!std::isfinite(workY))return {};
    auto b=basis(c);double dx=((point.x()-rect.x())/rect.width()-.5)*c.span*rect.width()/rect.height();
    double dy=(.5-(point.y()-rect.y())/rect.height())*c.span;
    if(std::abs(b.fy)<1e-8)return {};
    double t=(workY-c.y-b.uy*dy)/b.fy;
    QPointF result(c.x+b.rx*dx+b.ux*dy+b.fx*t,c.z+b.rz*dx+b.uz*dy+b.fz*t);
    return std::isfinite(result.x())&&std::isfinite(result.y())?std::optional<QPointF>(result):std::nullopt;
}
inline QPointF project(QPointF world,double workY,QRectF rect,const Camera &c) {
    auto b=basis(c);double x=world.x()-c.x,y=workY-c.y,z=world.y()-c.z;
    double u=.5+(x*b.rx+z*b.rz)/(c.span*rect.width()/rect.height()),v=.5-(x*b.ux+y*b.uy+z*b.uz)/c.span;
    return {rect.x()+u*rect.width(),rect.y()+v*rect.height()};
}
}
