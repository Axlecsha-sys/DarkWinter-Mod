// Distances are evaluated from SVG contour coordinates. No texture sampler is used.
struct SvgShape { ivec4 span; vec4 style; vec4 color; };
layout(std140) uniform SvgSegments { vec4 svgSegments[1024]; };
layout(std140) uniform SvgShapes { SvgShape svgShapes[128]; };

float svgSegmentDistance(vec2 p,vec2 a,vec2 b) {
    vec2 delta=b-a;
    float t=clamp(dot(p-a,delta)/max(dot(delta,delta),1e-20),0.0,1.0);
    return length(p-a-t*delta);
}
float svgBoxDistance(vec2 p,vec2 a,vec2 b,float radius) {
    vec2 delta=b-a;float len=length(delta);vec2 along=delta/max(len,1e-12);
    vec2 local=vec2(dot(p-(a+b)*0.5,along),dot(p-(a+b)*0.5,vec2(-along.y,along.x)));
    vec2 d=abs(local)-vec2(len*0.5,radius);
    return length(max(d,0.0))+min(max(d.x,d.y),0.0);
}
float svgDistance(vec2 p,SvgShape shape,float fraction) {
    int first=shape.span.x,count=shape.span.y,mode=shape.span.z;
    vec4 circle=svgSegments[first];
    if(mode==4)return length(p-circle.xy)-circle.z;
    if(mode==5)return abs(length(p-circle.xy)-circle.z)-shape.style.x;
    if(mode==6){
        if(fraction<=0.0)return 1e20;
        vec2 v=p-circle.xy;float span=svgSegments[first+1].x*clamp(fraction,0.0,1.0);
        float angle=mod((atan(v.y,v.x)-circle.w)*sign(span)+6.28318530718,6.28318530718);
        vec2 start=circle.xy+circle.z*vec2(cos(circle.w),sin(circle.w));
        vec2 end=circle.xy+circle.z*vec2(cos(circle.w+span),sin(circle.w+span));
        float centerDistance=angle<=abs(span)?abs(length(v)-circle.z):min(length(p-start),length(p-end));
        return centerDistance-shape.style.x;
    }
    float nearest=1e20;int winding=0;
    for(int index=0;index<1024;index++) {
        if(index>=count)break;
        vec4 segment=svgSegments[first+index];vec2 a=segment.xy,b=segment.zw;
        if(mode==3)nearest=min(nearest,svgBoxDistance(p,a,b,shape.style.x));
        else nearest=min(nearest,svgSegmentDistance(p,a,b));
        if(mode<2) {
            float crossValue=(b.x-a.x)*(p.y-a.y)-(b.y-a.y)*(p.x-a.x);
            if(a.y<=p.y&&b.y>p.y&&crossValue>0.0)winding++;
            if(a.y>p.y&&b.y<=p.y&&crossValue<0.0)winding--;
        }
    }
    if(mode==2)return nearest-shape.style.x;
    if(mode==3)return nearest;
    bool inside=mode==1?(abs(winding)%2==1):winding!=0;
    return inside?-nearest:nearest;
}
vec4 svgPaint(vec2 p,ivec2 range,vec4 tint,vec2 options) {
    if(options.y==5.0){
        int kind=int(floor(options.x));float light=fract(options.x)*2.0;
        vec2 halfSize=vec2(range)*0.5;float radius=min(1.4,min(halfSize.x,halfSize.y));
        vec2 q=abs(p-halfSize)-halfSize+radius;
        float d=length(max(q,0.0))+min(max(q.x,q.y),0.0)-radius;
        float aa=max(fwidth(d)*0.5,0.0001);
        float inside=1.0-smoothstep(-aa,aa,d);
        float inner=1.0-smoothstep(-aa,aa,d+0.62);
        float edge=clamp(inside-inner,0.0,1.0);
        float v=clamp(p.y/max(float(range.y),1.0),0.0,1.0);
        vec3 top=mix(vec3(0.067,0.12,0.16),vec3(0.11,0.19,0.24),light*0.7);
        vec3 bottom=vec3(0.025,0.052,0.078);
        float fillAlpha=0.84,edgeAlpha=0.28+light*0.42;
        if(kind==1){top=mix(top,tint.rgb,0.08);edgeAlpha=0.60+light*0.26;fillAlpha=0.91;}
        if(kind==2){top=vec3(0.027,0.055,0.078);fillAlpha=0.96;edgeAlpha=0.35+light*0.48;}
        if(kind==3){top=vec3(0.055,0.10,0.14);fillAlpha=0.94;edgeAlpha=0.36;}
        if(kind==4){top=bottom=vec3(0.09,0.16,0.20);fillAlpha=0.76;edgeAlpha=0.0;}
        if(kind==5){top=tint.rgb;bottom=tint.rgb*0.86;fillAlpha=0.98;edgeAlpha=0.0;}
        if(kind==6){top=mix(top,tint.rgb,0.10);fillAlpha=0.93;edgeAlpha=0.62+light*0.22;}
        if(kind==7){fillAlpha=0.46;edgeAlpha=0.17;}
        if(kind==8){top=vec3(0.055,0.095,0.13);fillAlpha=0.985;edgeAlpha=0.55;}
        if(kind==9){fillAlpha=0.05+light*0.56;edgeAlpha=0.0;}
        if(kind==10){fillAlpha=0.08+light*0.56;edgeAlpha=0.035+light*0.55;}
        vec3 base=mix(top,bottom,v);
        float alpha=inside*fillAlpha+edge*edgeAlpha*(1.0-fillAlpha);
        float edgeWeight=edge*edgeAlpha/max(alpha,0.0001);
        return vec4(mix(base,tint.rgb,edgeWeight),alpha*tint.a);
    }
    if(options.y==3.0){
        float radius=length((p-vec2(32.0))/32.0);
        return vec4(tint.rgb,tint.a*pow(max(1.0-radius,0.0),2.4));
    }
    if(options.y==4.0){
        vec2 halfSize=vec2(range)*0.5,q=abs(p-halfSize)-halfSize;
        float d=length(max(q,0.0))+min(max(q.x,q.y),0.0);
        return vec4(tint.rgb,tint.a*exp(-d*d/5.5)*0.52);
    }
    // One straight-alpha source-over composition per SVG paint operation.
    vec4 premultiplied=vec4(0.0);
    float pixelWidth=max(length(vec2(dFdx(p.x),dFdy(p.x))),length(vec2(dFdx(p.y),dFdy(p.y))));
    vec2 pixelX=dFdx(p),pixelY=dFdy(p);
    float aa=max(pixelWidth*0.5,1e-5);
    for(int index=0;index<128;index++) {
        if(index>=range.y)break;
        SvgShape shape=svgShapes[range.x+index];float d=svgDistance(p,shape,options.x);
        float coverage=1.0-smoothstep(-aa,aa,d);
        // Thin strokes have two nearby boundaries: a single-edge smoothstep loses coverage.
        // Integrate the numeric distance over the pixel only for these narrow strokes.
        if((shape.span.z==2||shape.span.z==3||shape.span.z==5||shape.span.z==6)&&shape.style.x<1.5*pixelWidth&&abs(d)<pixelWidth){
            coverage=0.0;
            for(int yy=0;yy<4;yy++)for(int xx=0;xx<4;xx++){
                vec2 samplePoint=p+pixelX*((float(xx)+0.5)/4.0-0.5)+pixelY*((float(yy)+0.5)/4.0-0.5);
                coverage+=clamp(0.5-svgDistance(samplePoint,shape,options.x)/max(pixelWidth*0.25,1e-5),0.0,1.0)*0.0625;
            }
        }
        if(options.y==2.0)coverage=exp(-pow(max(d,0.0),2.0)/30.0)*0.40;
        vec4 color=vec4(options.y>=1.0?vec3(1.0):shape.color.rgb,shape.color.a)*tint;
        float alpha=coverage*color.a;
        premultiplied.rgb=color.rgb*alpha+premultiplied.rgb*(1.0-alpha);
        premultiplied.a=alpha+premultiplied.a*(1.0-alpha);
    }
    return premultiplied.a>0.0?vec4(premultiplied.rgb/premultiplied.a,premultiplied.a):vec4(0.0);
}
