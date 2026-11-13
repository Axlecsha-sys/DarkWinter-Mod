#include "ui.hpp"
#include "environment.hpp"
#if __has_include("ui_world_pages.hpp")
#include "ui_world_pages.hpp"
#endif
#include <cwctype>
#include <cstring>
#include <sstream>
#include <iomanip>
namespace dw::client {
static uint32_t color(uint32_t rgb,uint32_t a=255){return ((rgb>>16)&255)|(rgb&0xff00)|((rgb&255)<<16)|(a<<24);}
Ui::Ui(const std::filesystem::path& folder):fontPath(folder/L"ui_regular.ttf"){
 auto b=read_file(folder/L"ui.dwu",64*1024*1024);if(b.size()<24||std::memcmp(b.data(),"DWUIART\0",8))throw std::runtime_error("UI header");
 auto u=[&](size_t at){uint32_t v;std::memcpy(&v,b.data()+at,4);return v;};uint64_t sum;std::memcpy(&sum,b.data()+16,8);
 if(u(8)!=1||(u(12)<35||u(12)>64)||checksum(b.data()+24,b.size()-24)!=sum)throw std::runtime_error("UI integrity");
 size_t at=24;for(uint32_t i=0;i<u(12);++i){if(at+8>b.size())throw std::runtime_error("UI dimensions");Art a{u(at),u(at+4),{}};at+=8;
 if(!a.w||!a.h||a.w>4096||a.h>4096||at+uint64_t(a.w)*a.h*4>b.size())throw std::runtime_error("UI art bounds");a.pixels.resize(size_t(a.w)*a.h);std::memcpy(a.pixels.data(),b.data()+at,a.pixels.size()*4);at+=a.pixels.size()*4;art.push_back(std::move(a));}
 if(at!=b.size())throw std::runtime_error("UI trailing data");
 if(!AddFontResourceExW(fontPath.c_str(),FR_PRIVATE,nullptr))throw std::runtime_error("Source UI font unavailable");
 symbolFontPath=folder/L"ui_symbols.ttf";if(std::filesystem::is_regular_file(symbolFontPath))symbolFontLoaded=AddFontResourceExW(symbolFontPath.c_str(),FR_PRIVATE,nullptr)!=0;
 dc=CreateCompatibleDC(nullptr);canvas(854,480);readCatalogues(folder);
}
Ui::~Ui(){for(auto& [size,f]:fonts)DeleteObject(f);if(dc&&oldBitmap)SelectObject(dc,oldBitmap);if(bitmap)DeleteObject(bitmap);if(dc)DeleteDC(dc);RemoveFontResourceExW(fontPath.c_str(),FR_PRIVATE,nullptr);if(symbolFontLoaded)RemoveFontResourceExW(symbolFontPath.c_str(),FR_PRIVATE,nullptr);}
void Ui::pixel(int x,int y,uint32_t c){
 if(x<clip[0]||y<clip[1]||x>=clip[2]||y>=clip[3]||x<0||y<0||x>=rasterW||y>=rasterH)return;uint32_t& dst=pixels[4+x+rasterW*y];uint32_t a=c>>24,da=dst>>24;if(!a)return;if(a==255){dst=c;return;}if(da==255){uint32_t out=0xff000000u;for(int s=0;s<24;s+=8)out|=((((c>>s)&255)*a+((dst>>s)&255)*(255-a)+127)/255)<<s;dst=out;return;}uint32_t oa=a+(da*(255-a)+127)/255;if(!oa)return;
 uint32_t value=oa<<24;for(int s=0;s<24;s+=8){uint32_t n=(((c>>s)&255)*a*255+((dst>>s)&255)*da*(255-a)+oa*127)/(oa*255);value|=std::min(n,255u)<<s;}dst=value;
}
void Ui::rect(int x,int y,int width,int height,uint32_t c){int x0=std::max(0,int(std::round(x*pixelX))),x1=std::min(rasterW,int(std::round((x+width)*pixelX))),y0=std::max(0,int(std::round(y*pixelY))),y1=std::min(rasterH,int(std::round((y+height)*pixelY)));for(int j=y0;j<y1;j++)for(int i=x0;i<x1;i++)pixel(i,j,c);}
void Ui::picture(int id,int x,int y,int width,int height,bool cover,uint32_t tint){
 if(id==2){picture(9,x,y,width,height,cover,color(0xdce8eb,173));if(tint==0xffffffffu)tint=color(0xdce8eb);}
 if(id<0||id>=int(art.size())||width<=0||height<=0)return;const auto& a=art[size_t(id)];double sw=a.w,sh=a.h,sx=0,sy=0;
 if(cover){double aspect=double(width)/height;if(sw/sh>aspect){sw=sh*aspect;sx=(a.w-sw)*.5;}else{sh=sw/aspect;sy=(a.h-sh)*.5;}}
 int x0=int(std::round(x*pixelX)),y0=int(std::round(y*pixelY)),pw=std::max(1,int(std::round((x+width)*pixelX))-x0),ph=std::max(1,int(std::round((y+height)*pixelY))-y0);
 auto key=std::tuple{id,pw,ph,cover,tint};auto cached=scaledArt.find(key);if(cached==scaledArt.end()){if(scaledArt.size()>=64)scaledArt.clear();Art resampled{uint32_t(pw),uint32_t(ph),std::vector<uint32_t>(size_t(pw)*ph)};
 for(int j=0;j<ph;j++)for(int i=0;i<pw;i++){double u=std::clamp(sx+(i+.5)*sw/pw-.5,0.,double(a.w-1)),v=std::clamp(sy+(j+.5)*sh/ph-.5,0.,double(a.h-1));int ix=int(u),iy=int(v),nx=std::min(ix+1,int(a.w-1)),ny=std::min(iy+1,int(a.h-1));float fx=float(u-ix),fy=float(v-iy);uint32_t c=0;for(int k=0;k<32;k+=8){float top=std::lerp(float((a.pixels[ix+size_t(iy)*a.w]>>k)&255),float((a.pixels[nx+size_t(iy)*a.w]>>k)&255),fx),bottom=std::lerp(float((a.pixels[ix+size_t(ny)*a.w]>>k)&255),float((a.pixels[nx+size_t(ny)*a.w]>>k)&255),fx);uint32_t n=uint32_t(std::round(std::lerp(top,bottom,fy)));if(tint!=0xffffffffu)n=n*((tint>>k)&255)/255;c|=n<<k;}resampled.pixels[i+size_t(j)*pw]=c;}cached=scaledArt.emplace(key,std::move(resampled)).first;}
 for(int j=std::max(0,-y0);j<ph&&y0+j<rasterH;j++)for(int i=std::max(0,-x0);i<pw&&x0+i<rasterW;i++)pixel(x0+i,y0+j,cached->second.pixels[i+size_t(j)*pw]);
}
void Ui::icon(int index,int x,int y,int size){if(art.size()>32){picture(22+index,x,y,size,size,false,color(0xdce8eb,214));return;}const auto& a=art.at(3);int tile=int(a.h);for(int j=0;j<size;j++)for(int i=0;i<size;i++)rect(x+i,y+j,1,1,a.pixels[size_t(index*tile+i*tile/size)+size_t(j*tile/size)*a.w]);}
void Ui::rotated(int id,int cx,int cy,int size,float angle,uint32_t tint){if(id<0||id>=int(art.size()))return;const auto& a=art[id];int px=int(std::round(cx*pixelX)),py=int(std::round(cy*pixelY)),half=int(std::ceil(size*std::max(pixelX,pixelY)*.72f));float c=std::cos(angle),s=std::sin(angle);for(int j=-half;j<=half;j++)for(int i=-half;i<=half;i++){float dx=i/pixelX,dy=j/pixelY,u=.5f+(c*dx+s*dy)/size,v=.5f+(-s*dx+c*dy)/size;if(u<0||v<0||u>=1||v>=1)continue;uint32_t src=a.pixels[std::min(uint32_t(u*a.w),a.w-1)+size_t(std::min(uint32_t(v*a.h),a.h-1))*a.w],alpha=(src>>24)*(tint>>24)/255;pixel(px+i,py+j,(tint&0xffffff)|(alpha<<24));}}
// Align source GUI coordinates to capital ink, not the GDI line-box padding.
HFONT Ui::font(int size){auto it=fonts.find(size);if(it!=fonts.end())return it->second;HFONT f=CreateFontW(-size,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_TT_PRECIS,CLIP_DEFAULT_PRECIS,ANTIALIASED_QUALITY,DEFAULT_PITCH,L"Roboto Condensed");auto previous=SelectObject(dc,f);TEXTMETRICW metrics{};GLYPHMETRICS glyph{};MAT2 transform{{0,1},{0,0},{0,0},{0,1}};int top=0;if(GetTextMetricsW(dc,&metrics)&&GetGlyphOutlineW(dc,L'H',GGO_METRICS,&glyph,0,nullptr,&transform)!=GDI_ERROR)top=std::max(0,int(metrics.tmAscent-glyph.gmptGlyphOrigin.y));SelectObject(dc,previous);fontTop.emplace(size,top);fonts.emplace(size,f);return f;}
int Ui::measure(const std::wstring& value,float size){auto prior=SelectObject(dc,font(std::max(1,int(std::round(size*pixelY)))));SIZE s{};GetTextExtentPoint32W(dc,value.c_str(),int(value.size()),&s);SelectObject(dc,prior);return int(std::ceil(s.cx/pixelX));}
void Ui::text(float x,float y,float size,const std::wstring& value,uint32_t c){if(value.empty())return;int px=int(std::round(x*pixelX)),py=int(std::round(y*pixelY)),ps=std::max(1,int(std::round(size*pixelY)));auto prior=SelectObject(dc,font(ps));py-=fontTop.at(ps);SIZE extent{};GetTextExtentPoint32W(dc,value.c_str(),int(value.size()),&extent);
 int right=std::clamp(px+int(extent.cx)+2,0,rasterW),top=std::clamp(py,0,rasterH),bottom=std::clamp(py+ps*2,0,rasterH),left=std::clamp(px,0,rasterW);for(int j=top;j<bottom;j++)if(left<right)std::fill(mask+j*rasterW+left,mask+j*rasterW+right,0u);
 SetTextColor(dc,RGB(255,255,255));SetBkMode(dc,TRANSPARENT);RECT box{px,py,right,bottom};DrawTextW(dc,value.c_str(),int(value.size()),&box,DT_LEFT|DT_TOP|DT_SINGLELINE|DT_NOPREFIX);GdiFlush();SelectObject(dc,prior);
 for(int j=top;j<bottom;j++)for(int i=left;i<right;i++){uint32_t alpha=mask[i+rasterW*j]&255;if(alpha)pixel(i,j,(c&0xffffff)|((alpha*(c>>24)/255)<<24));}
}
void Ui::canvas(int width,int height,int actualW,int actualH){width=std::clamp(width,320,4096);height=std::clamp(height,180,4096);actualW=actualW?std::clamp(actualW,640,3840):width;actualH=actualH?std::clamp(actualH,360,2160):height;if(bitmap&&w==width&&h==height&&rasterW==actualW&&rasterH==actualH)return;
 if(bitmap){SelectObject(dc,oldBitmap);DeleteObject(bitmap);bitmap=nullptr;}w=width;h=height;rasterW=actualW;rasterH=actualH;pixelX=float(rasterW)/w;pixelY=float(rasterH)/h;BITMAPINFO info{};info.bmiHeader.biSize=sizeof(BITMAPINFOHEADER);info.bmiHeader.biWidth=rasterW;info.bmiHeader.biHeight=-rasterH;info.bmiHeader.biPlanes=1;info.bmiHeader.biBitCount=32;info.bmiHeader.biCompression=BI_RGB;
 bitmap=CreateDIBSection(dc,&info,DIB_RGB_COLORS,reinterpret_cast<void**>(&mask),nullptr,0);if(!bitmap)throw std::runtime_error("GUI canvas allocation");auto previous=SelectObject(dc,bitmap);if(!oldBitmap)oldBitmap=static_cast<HBITMAP>(previous);pixels.resize(4+size_t(rasterW)*rasterH);scaledArt.clear();clipStack.clear();clip={0,0,rasterW,rasterH};dirty=true;
}
void Ui::resizeFor(int width,int height){
 if(width<=0||height<=0)return;int requested=std::clamp(int(settings.option("guiScale",3)),0,16),scale=1;
 while((requested==0||scale<requested)&&width/(scale+1)>=320&&height/(scale+1)>=240)++scale;
 canvas((width+scale-1)/scale,(height+scale-1)/scale,width,height);
}
void Ui::gradient(int x,int y,int width,int height,uint32_t top,uint32_t bottom){int x0=std::max(0,int(std::round(x*pixelX))),x1=std::min(rasterW,int(std::round((x+width)*pixelX))),y0=int(std::round(y*pixelY)),y1=int(std::round((y+height)*pixelY));for(int j=std::max(0,y0);j<std::min(rasterH,y1);j++){float f=y1>y0+1?float(j-y0)/float(y1-y0-1):0;uint32_t c=0;for(int k=0;k<32;k+=8)c|=uint32_t(std::lerp(float((top>>k)&255),float((bottom>>k)&255),f))<<k;for(int i=x0;i<x1;i++)pixel(i,j,c);}}
void Ui::centered(int y,float size,const std::wstring& value,uint32_t c){text(std::max(0,(w-measure(value,size))/2),y,size,value,c);}
void Ui::surface(int x,int y,int width,int height,int kind,float light,uint32_t tint){float unit=1,radius=std::min(1.4f*unit,height*.5f),stroke=.62f*unit;int x0=int(std::round(x*pixelX)),x1=int(std::round((x+width)*pixelX)),y0=int(std::round(y*pixelY)),y1=int(std::round((y+height)*pixelY));float fa=.84f,ea=.28f+light*.42f;V3 top{.067f+.043f*light*.7f,.12f+.07f*light*.7f,.16f+.08f*light*.7f},bottom{.025f,.052f,.078f},accent{float(tint&255)/255,float((tint>>8)&255)/255,float((tint>>16)&255)/255};
 if(kind==1){top=top*.92f+accent*.08f;ea=.60f+light*.26f;fa=.91f;}if(kind==2){top={.027f,.055f,.078f};fa=.96f;ea=.35f+light*.48f;}if(kind==4){top=bottom={.09f,.16f,.20f};fa=.76f;ea=0;}if(kind==5){top=accent;bottom=accent*.86f;fa=.98f;ea=0;}if(kind==6){top=top*.90f+accent*.10f;fa=.93f;ea=.62f+light*.22f;}if(kind==8){top={.055f,.095f,.13f};fa=.985f;ea=.55f;}if(kind==3){top={.055f,.10f,.14f};fa=.94f;ea=.36f;}if(kind==7){fa=.46f;ea=.17f;}if(kind==9){fa=.05f+light*.56f;ea=0;}if(kind==10){fa=.08f+light*.56f;ea=.035f+light*.55f;}
 auto smooth=[](float a,float b,float d){float t=std::clamp((d-a)/(b-a),0.f,1.f);return t*t*(3-2*t);};float aa=.5f/std::min(pixelX,pixelY);
 for(int j=std::max(0,y0);j<std::min(rasterH,y1);j++)for(int i=std::max(0,x0);i<std::min(rasterW,x1);i++){float px=(i+.5f-x0)/pixelX,py=(j+.5f-y0)/pixelY,qx=std::abs(px-width*.5f)-width*.5f+radius,qy=std::abs(py-height*.5f)-height*.5f+radius,d=std::hypot(std::max(qx,0.f),std::max(qy,0.f))+std::min(std::max(qx,qy),0.f)-radius,inside=1-smooth(-aa,aa,d),inner=1-smooth(-aa,aa,d+stroke),edge=std::clamp(inside-inner,0.f,1.f),alpha=inside*fa+edge*ea*(1-fa),ew=edge*ea/std::max(alpha,.0001f);V3 base=top*(1-py/height)+bottom*(py/height),rgb=base*(1-ew)+accent*ew;pixel(i,j,color((uint32_t(std::clamp(rgb.x,0.f,1.f)*255)<<16)|(uint32_t(std::clamp(rgb.y,0.f,1.f)*255)<<8)|uint32_t(std::clamp(rgb.z,0.f,1.f)*255),uint32_t(std::clamp(alpha,0.f,1.f)*(tint>>24))));}
}
void Ui::ring(int cx,int cy,int size,float fraction,uint32_t tint){float scale=std::min(pixelX,pixelY),radius=size*scale*27/64.f,stroke=size*scale*2.65f/128,rr=size*scale*.70f;float start=2.35619449f,span=4.71238898f;int x=int(std::round(cx*pixelX)),y=int(std::round(cy*pixelY));
 for(int j=-int(rr);j<=int(rr);j++)for(int i=-int(rr);i<=int(rr);i++){float len=std::hypot(float(i),float(j)),rad=rr>0?len/rr:1;if(rad<1)pixel(x+i,y+j,color(0x02080d,uint32_t(158*std::pow(1-rad,2.4f))));float angle=std::fmod(std::atan2(float(j),float(i))-start+12.5663706f,6.2831853f);auto distance=[&](float f){float end=start+span*f;return angle<=span*f?std::abs(len-radius)-stroke:std::min(std::hypot(i-radius*std::cos(start),j-radius*std::sin(start)),std::hypot(i-radius*std::cos(end),j-radius*std::sin(end)))-stroke;};float d=distance(1),coverage=std::clamp(.5f-d,0.f,1.f);if(coverage>0)pixel(x+i,y+j,color(0x4b697a,uint32_t(130*coverage)));if(fraction>0){d=distance(std::clamp(fraction,0.f,1.f));float glow=std::exp(-std::pow(std::max(d,0.f),2)/(30*scale*scale))*.116f;pixel(x+i,y+j,(tint&0xffffff)|(uint32_t((tint>>24)*glow)<<24));coverage=std::clamp(.5f-d,0.f,1.f);if(coverage>0)pixel(x+i,y+j,(tint&0xffffff)|(uint32_t((tint>>24)*coverage*.86f)<<24));}}
}
void Ui::analog() {
 // MenuStyle.analogScreen -> HudStyle.analog: GUI-space grain, scanlines, then the optical band.
 if(!settings.animated||settings.motion==2||w<=2||h<=2)return;
 const bool full=settings.motion==0;
 const float strength=std::clamp(settings.fxStrength,0.f,1.5f);
 const float effectOpacity=std::clamp(strength,0.f,1.f);
 const uint32_t grainAlpha=uint32_t(std::lround(255*std::clamp(.045f*strength,0.f,1.f)));
 if(art.size()>16&&grainAlpha){
  const auto& grain=art[16];
  // Only phase modulo 128 affects the source UVs; reduce before integer multiplication.
  const int frame=full?int(std::fmod(std::max(0.,double(menuTime))*12.,128.)):0;
  const int u=(frame*31)%128,v=(frame*17)%128;
  const uint32_t tint=color(0xc7d4dc,grainAlpha);
  // Tint the repeating source tile once, instead of four channel multiplies per screen pixel.
  std::array<uint32_t,128*128> tile;
  for(int gy=0;gy<128;gy++)for(int gx=0;gx<128;gx++){
   const uint32_t src=grain.pixels[size_t((u+gx)%int(grain.w))+size_t((v+gy)%int(grain.h))*grain.w];uint32_t c=0;
   for(int channel=0;channel<32;channel+=8)c|=(((src>>channel)&255)*((tint>>channel)&255)/255)<<channel;
   tile[size_t(gy)*128+gx]=c;
  }
  const int x0=int(std::lround(pixelX)),x1=int(std::lround((w-1)*pixelX));
  const int y0=int(std::lround(pixelY)),y1=int(std::lround((h-1)*pixelY));
  for(int y=y0;y<y1;y++){
   const int gy=std::max(0,int((y+.5f)/pixelY-1))%128;
   for(int x=x0;x<x1;x++){
    const int gx=std::max(0,int((x+.5f)/pixelX-1))%128;
    pixel(x,y,tile[size_t(gy)*128+gx]);
   }
  }
 }
 // HudStyle.alpha clamps the opacity argument, so strength above 100% only intensifies grain.
 const uint32_t scanAlpha=uint32_t(std::lround(21*effectOpacity));
 if(scanAlpha)for(int y=2;y<h-1;y+=3)rect(1,y,w-2,1,color(0x00050b,scanAlpha));
 const double phase=std::fmod(double(menuTime)+4.7,17.);
 if(full&&phase>=0&&phase<.30){
  const int py=1+std::min(h-4,int(phase/.30*std::max(1,h-4)));
  const uint32_t bandAlpha=uint32_t(std::lround(68*effectOpacity));
  if(bandAlpha)gradient(1,py,w-2,std::min(h-1,py+2)-py,color(0x7fa7bb,bandAlpha),color(0x7fa7bb,0));
 }
}
void Ui::backdrop(int id){
 const bool menuLandscape=id==0||id==4,forest=id==4,title=page==TITLE&&id==0,full=settings.animated&&settings.motion==0;
#if __has_include("ui_world_pages.hpp")
 if(menuLandscape)UiWorldPages::cover(*this,id,0,0,w,h,forest?.46f:.5f+(full?.04f*std::sin(menuTime*.035f):0.f),.5f,1,forest?color(0xa5b4c1):0xffffffffu);else picture(id,0,0,w,h,true);
#else
 picture(id,0,0,w,h,true,forest?color(0xa5b4c1):0xffffffffu);
#endif
 if(title){int left=std::clamp(int(std::round(w*.075f)),20,60),row=std::clamp(int(std::round(w*.32f)),140,214),veil=std::min(w,left+row+76);for(int band=0;band<20;band++){float f=1-band/20.f;rect(band*veil/20,0,(band+1)*veil/20-band*veil/20,h,color(0x030a12,uint32_t(std::round(102*f*f))));}}
 if(settings.animated&&settings.menuMist)for(int bank=0;bank<3;bank++){int mw=int(std::round(w*(.74f+bank*.12f))),mh=std::max(36,int(std::round(h*(.12f+bank*.025f)))),mx=int(std::round(w*.52f-mw*.5f+std::sin(menuTime*(.019f+bank*.007f)+bank*2.1f)*w*.13f)),my=int(std::round(h*(.50f+bank*.15f)+std::sin(menuTime*.032f+bank)*h*.018f))-mh/2;picture(8,mx,my,mw,mh,false,color(0xc8d7df,uint32_t(std::round(255*.13f*settings.fxStrength))));}
 if(menuLandscape&&!title)rect(0,0,w,h,forest?color(0x030a13,138):color(0x040d16,89));
 gradient(0,0,w,h/3,color(0x030910,69),color(0x030910,0));gradient(0,h*3/4,w,h/4,color(0x030910,0),color(0x030910,148));analog();rect(3,3,w-6,1,color(0x537384,80));rect(3,h-4,w-6,1,color(0x537384,64));
}
int Ui::button(int x,int y)const{for(const auto& b:buttons)if(b.enabled&&x>=b.x&&x<b.x+b.w&&y>=b.y&&y<b.y+b.h)return b.id;return -1;}
void Ui::playerState(float energy,bool tired,bool actor,bool shoulder,const Needs& state){float q=std::round(energy*2)/2;if(q!=stamina||tired!=exhausted||actor!=walking||shoulder!=third||std::memcmp(&needs,&state,sizeof(Needs)))dirty=true;stamina=q;exhausted=tired;walking=actor;third=shoulder;needs=state;}

void Ui::scanWorlds(const std::filesystem::path& directory){
 worlds.clear();std::map<std::string,size_t> latest;
 if(std::filesystem::exists(directory))for(auto& e:std::filesystem::directory_iterator(directory))if(e.is_regular_file()&&e.path().extension()==".state"){
  auto world=e.path();world.replace_extension(".dwv");if(!std::filesystem::is_regular_file(world))continue;WorldRow row{world,world.stem().wstring(),false,0,world.stem().string(),e.last_write_time()};
  std::ifstream state(e.path());std::string magic;state>>magic;if(magic!="DWSTATE1"&&magic!="DWSTATE2"&&magic!="DWSTATE3")continue;row.native=magic=="DWSTATE3";
  auto info=e.path();info.replace_extension(".info");std::ifstream meta(info);std::string name,id;if(meta>>std::quoted(name)>>row.seed){if(meta>>std::quoted(id)&&!id.empty()&&id.size()<128)row.id=id;int n=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,name.data(),int(name.size()),nullptr,0);if(n>0&&n<=128){row.name.resize(n);MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,name.data(),int(name.size()),row.name.data(),n);}}
  auto found=latest.find(row.id);if(found==latest.end()){latest.emplace(row.id,worlds.size());worlds.push_back(std::move(row));}else if(row.modified>worlds[found->second].modified)worlds[found->second]=std::move(row);
 }
 std::sort(worlds.begin(),worlds.end(),[](const WorldRow& a,const WorldRow& b){return a.modified>b.modified;});selectedWorld=std::clamp(selectedWorld,0,std::max(0,int(worlds.size())-1));worldScroll=std::clamp(worldScroll,0,std::max(0,int(worlds.size())-1));dirty=true;
}
namespace {
std::wstring uiWide(const std::string& value) {
 if(value.empty())return {};
 int n=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,value.data(),int(value.size()),nullptr,0);
 if(!n)return {};
 std::wstring out(size_t(n),L'\0');MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,value.data(),int(value.size()),out.data(),n);return out;
}
std::wstring lowerUnicode(const std::wstring& value) {
 if(value.empty())return {};std::wstring result(value.size(),L'\0');int n=LCMapStringEx(LOCALE_NAME_INVARIANT,LCMAP_LOWERCASE,value.data(),int(value.size()),result.data(),int(result.size()),nullptr,nullptr,0);return n>0?result:value;
}
std::wstring uiFormat(const std::wstring& format,const std::vector<std::wstring>& values) {
 std::wstring result;size_t next=0;
 for(size_t at=0;at<format.size();){if(format[at]!=L'%'){result+=format[at++];continue;}size_t start=at++;if(at<format.size()&&format[at]==L'%'){result+=L'%';++at;continue;}size_t index=0,digits=at;while(at<format.size()&&format[at]>=L'0'&&format[at]<=L'9'){index=index*10+size_t(format[at++]-L'0');}bool positional=at<format.size()&&format[at]==L'$'&&at>digits;if(positional){++at;index=index?index-1:values.size();}else{at=digits;index=next++;}while(at<format.size()&&(format[at]==L'.'||(format[at]>=L'0'&&format[at]<=L'9')))++at;if(at<format.size()&&(format[at]==L's'||format[at]==L'd'||format[at]==L'f')){++at;if(index<values.size())result+=values[index];else result+=format.substr(start,at-start);}else{result+=L'%';at=start+1;}}
 return result;
}
void appendCodepoint(std::string& out,unsigned cp) {
 if(cp<=0x7f)out+=char(cp);
 else if(cp<=0x7ff){out+=char(0xc0|(cp>>6));out+=char(0x80|(cp&63));}
 else if(cp<=0xffff){out+=char(0xe0|(cp>>12));out+=char(0x80|((cp>>6)&63));out+=char(0x80|(cp&63));}
 else if(cp<=0x10ffff){out+=char(0xf0|(cp>>18));out+=char(0x80|((cp>>12)&63));out+=char(0x80|((cp>>6)&63));out+=char(0x80|(cp&63));}
}
bool catalogueFields(const std::string& line,std::array<std::string,3>& fields) {
 size_t p=0;
 auto hex=[&](unsigned& value){if(p+4>line.size())return false;value=0;for(int i=0;i<4;i++){unsigned char c=line[p++];unsigned d=c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:c>='A'&&c<='F'?c-'A'+10:16;if(d==16)return false;value=value*16+d;}return true;};
 for(auto& field:fields){while(p<line.size()&&(line[p]=='\t'||line[p]==' '))++p;if(p==line.size()||line[p++]!='"')return false;bool closed=false;
  while(p<line.size()){unsigned char c=line[p++];if(c=='"'){closed=true;break;}if(c<32)return false;
   if(c!='\\')field+=char(c);else{if(p==line.size())return false;char e=line[p++];switch(e){case '"':case '\\':case '/':field+=e;break;case 'n':field+='\n';break;case 'r':field+='\r';break;case 't':field+='\t';break;case 'b':field+='\b';break;case 'f':field+='\f';break;case 'u':{unsigned cp;if(!hex(cp))return false;if(cp>=0xd800&&cp<=0xdbff){if(p+2>line.size()||line[p++]!='\\'||line[p++]!='u')return false;unsigned low;if(!hex(low)||low<0xdc00||low>0xdfff)return false;cp=0x10000+(cp-0xd800)*1024+low-0xdc00;}else if(cp>=0xdc00&&cp<=0xdfff)return false;appendCodepoint(field,cp);break;}default:return false;}}
   if(field.size()>65536)return false;
  }
  if(!closed)return false;
 }
 while(p<line.size()&&(line[p]=='\t'||line[p]==' '||line[p]=='\r'))++p;return p==line.size();
}
std::wstring compactNumber(double value,int places=2) {
 std::wostringstream out;out<<std::fixed<<std::setprecision(places)<<value;auto s=out.str();if(s.find(L'.')!=std::wstring::npos){while(s.size()>1&&s.back()==L'0')s.pop_back();if(!s.empty()&&s.back()==L'.')s.pop_back();}return s;
}
std::wstring keyName(const Ui& ui,unsigned key,const UiKeyBinding& binding) {
 const char* special=nullptr;
 switch(key){case 0:special="key.keyboard.unknown";break;case VK_XBUTTON1:special="key.mouse.4";break;case VK_XBUTTON2:special="key.mouse.5";break;case VK_LBUTTON:special="key.mouse.left";break;case VK_RBUTTON:special="key.mouse.right";break;case VK_MBUTTON:special="key.mouse.middle";break;case VK_LSHIFT:case VK_SHIFT:special="key.keyboard.left.shift";break;case VK_RSHIFT:special="key.keyboard.right.shift";break;case VK_LCONTROL:case VK_CONTROL:special="key.keyboard.left.control";break;case VK_RCONTROL:special="key.keyboard.right.control";break;case VK_LMENU:case VK_MENU:special="key.keyboard.left.alt";break;case VK_RMENU:special="key.keyboard.right.alt";break;case VK_SPACE:special="key.keyboard.space";break;case VK_TAB:special="key.keyboard.tab";break;case VK_RETURN:special=binding.inputName=="key.keyboard.keypad.enter"?"key.keyboard.keypad.enter":"key.keyboard.enter";break;case VK_BACK:special="key.keyboard.backspace";break;case VK_DELETE:special="key.keyboard.delete";break;case VK_HOME:special="key.keyboard.home";break;case VK_END:special="key.keyboard.end";break;case VK_PRIOR:special="key.keyboard.page.up";break;case VK_NEXT:special="key.keyboard.page.down";break;}
 if(special){std::wstring fallback=key==0?L"Не назначено":key==VK_XBUTTON1?L"Кнопка мыши 4":key==VK_XBUTTON2?L"Кнопка мыши 5":key==VK_SPACE?L"Пробел":key==VK_LBUTTON?L"ЛКМ":key==VK_RBUTTON?L"ПКМ":key==VK_MBUTTON?L"СКМ":uiWide(special);return ui.tr(special,fallback);}
 if(key>=VK_F1&&key<=VK_F24)return L"F"+std::to_wstring(key-VK_F1+1);
 if(key>=VK_NUMPAD0&&key<=VK_NUMPAD9)return ui.tr("key.keyboard.keypad."+std::to_string(key-VK_NUMPAD0),L"Num "+std::to_wstring(key-VK_NUMPAD0));
 if(key>='A'&&key<='Z'){UINT cp=MapVirtualKeyExW(key,MAPVK_VK_TO_CHAR,GetKeyboardLayout(0));if(cp&&!(cp&0x80000000u))return std::wstring(1,wchar_t(cp));}
 wchar_t text[128]{};LONG scan=LONG(MapVirtualKeyW(key,MAPVK_VK_TO_VSC)<<16);if(key==VK_LEFT||key==VK_RIGHT||key==VK_UP||key==VK_DOWN||key==VK_INSERT||key==VK_DELETE)scan|=1<<24;
 if(GetKeyNameTextW(scan,text,128)>0)return text;return std::to_wstring(key);
}
bool copySelection(const std::wstring& text) {
 if(text.empty()||!OpenClipboard(nullptr))return false;HGLOBAL data=GlobalAlloc(GMEM_MOVEABLE,(text.size()+1)*sizeof(wchar_t));bool copied=false;
 if(data){void* memory=GlobalLock(data);if(memory){memcpy(memory,text.c_str(),(text.size()+1)*sizeof(wchar_t));GlobalUnlock(data);if(EmptyClipboard()&&SetClipboardData(CF_UNICODETEXT,data)){copied=true;data=nullptr;}}if(data)GlobalFree(data);}CloseClipboard();return copied;
}
size_t previousCodepoint(const std::wstring& text,size_t index){if(!index)return 0;--index;if(index&&text[index]>=0xdc00&&text[index]<=0xdfff&&text[index-1]>=0xd800&&text[index-1]<=0xdbff)--index;return index;}
size_t nextCodepoint(const std::wstring& text,size_t index){if(index>=text.size())return text.size();if(text[index]>=0xd800&&text[index]<=0xdbff&&index+1<text.size()&&text[index+1]>=0xdc00&&text[index+1]<=0xdfff)++index;return index+1;}

}
void Ui::readCatalogues(const std::filesystem::path& folder) {
 languages={{"en_us",L"English",L"US"},{"ru_ru",L"Русский",L"Россия"}};
 const auto languageFile=folder/L"ui-languages.tsv";
 if(std::filesystem::is_regular_file(languageFile)){
  std::ifstream in(languageFile);std::string line;std::vector<UiLanguage> parsed;
  while(std::getline(in,line)){if(line.size()>196608||parsed.size()>=512)throw std::runtime_error("UI language catalogue bounds");std::array<std::string,3> f;if(!catalogueFields(line,f)||f[0].empty()||f[0].size()>32)throw std::runtime_error("UI language catalogue entry");parsed.push_back({f[0],uiWide(f[1]),uiWide(f[2])});}
  if(!parsed.empty())languages=std::move(parsed);
 }
 std::sort(languages.begin(),languages.end(),[](const auto& a,const auto& b){return a.code<b.code;});
 const auto textFile=folder/L"ui-text.tsv";
 if(std::filesystem::is_regular_file(textFile)){
  if(std::filesystem::file_size(textFile)>64*1024*1024)throw std::runtime_error("UI text catalogue size");
  std::ifstream in(textFile);std::string line;size_t rows=0;
  while(std::getline(in,line)){if(line.size()>196608||++rows>500000)throw std::runtime_error("UI text catalogue bounds");std::array<std::string,3> f;if(!catalogueFields(line,f)||f[0].size()>32||f[1].size()>512)throw std::runtime_error("UI text catalogue entry");translations[f[0]][f[1]]=uiWide(f[2]);}
 }
 // Registered bindings captured from Minecraft 26.3; defaults from Options and mod registrations.
 keyBindings={
  {"advancements",L"Достижения",L"Разное",76,76,"key.category.minecraft.misc",1,0,"key.keyboard.l"},
  {"attack",L"Атаковать/Разрушить",L"Игровой процесс",1,1,"key.category.minecraft.gameplay",3,0,"key.mouse.left"},
  {"back",L"Назад",L"Движение",83,83,"key.category.minecraft.movement",0,0,"key.keyboard.s"},
  {"chat",L"Открыть чат",L"Сетевая игра",84,84,"key.category.minecraft.multiplayer",2,0,"key.keyboard.t"},
  {"command",L"Ввод команды",L"Сетевая игра",191,191,"key.category.minecraft.multiplayer",2,0,"key.keyboard.slash"},
  {"darkwinter.friends",L"Открыть соединения DarkWinter",L"key.category.darkwinter.online",13,13,"key.category.darkwinter.online",12,0,"key.keyboard.keypad.enter"},
  {"darkwinter.gather_deadwood",L"Добыть валежник",L"Взаимодействие DarkWinter",69,69,"key.category.darkwinter.interaction",10,0,"key.keyboard.e"},
  {"darkwinter.switch_shoulder",L"key.darkwinter.switch_shoulder",L"key.category.darkwinter.camera",86,86,"key.category.darkwinter.camera",9,0,"key.keyboard.v"},
  {"darkwinter.testing_atmosphere",L"Лаборатория: атмосфера и туман",L"DarkWinter — лаборатория",102,102,"key.category.darkwinter.testing",11,0,"key.keyboard.keypad.6"},
  {"darkwinter.testing_camera",L"Лаборатория: камера персонажа",L"DarkWinter — лаборатория",96,96,"key.category.darkwinter.testing",11,0,"key.keyboard.keypad.0"},
  {"darkwinter.testing_character",L"Редактор / персонаж",L"DarkWinter — лаборатория",120,120,"key.category.darkwinter.testing",11,0,"key.keyboard.f9"},
  {"darkwinter.testing_diagnostics",L"Диагностика лаборатории",L"DarkWinter — лаборатория",121,121,"key.category.darkwinter.testing",11,0,"key.keyboard.f10"},
  {"darkwinter.testing_editor",L"Редактор тестирования",L"Строительство DarkWinter",119,119,"key.category.darkwinter.building",8,0,"key.keyboard.f8"},
  {"darkwinter.testing_hud",L"Лаборатория: игровой HUD",L"DarkWinter — лаборатория",97,97,"key.category.darkwinter.testing",11,0,"key.keyboard.keypad.1"},
  {"darkwinter.testing_motion",L"Лаборатория: движение",L"DarkWinter — лаборатория",105,105,"key.category.darkwinter.testing",11,0,"key.keyboard.keypad.9"},
  {"darkwinter.testing_panel",L"Свернуть панель лаборатории",L"DarkWinter — лаборатория",118,118,"key.category.darkwinter.testing",11,0,"key.keyboard.f7"},
  {"darkwinter.testing_precipitation",L"Лаборатория: снег и позёмка",L"DarkWinter — лаборатория",100,100,"key.category.darkwinter.testing",11,0,"key.keyboard.keypad.4"},
  {"darkwinter.testing_redo",L"Редактор: повторить",L"DarkWinter — лаборатория",0,0,"key.category.darkwinter.testing",11,0,"key.keyboard.unknown"},
  {"darkwinter.testing_rotate",L"Редактор: повернуть объект",L"DarkWinter — лаборатория",0,0,"key.category.darkwinter.testing",11,0,"key.keyboard.unknown"},
  {"darkwinter.testing_select",L"Выделить объект / угол A",L"DarkWinter — лаборатория",0,0,"key.category.darkwinter.testing",11,0,"key.keyboard.unknown"},
  {"darkwinter.testing_select_second",L"Выделить угол B",L"DarkWinter — лаборатория",0,0,"key.category.darkwinter.testing",11,0,"key.keyboard.unknown"},
  {"darkwinter.testing_stamina",L"Лаборатория: выносливость",L"DarkWinter — лаборатория",103,103,"key.category.darkwinter.testing",11,0,"key.keyboard.keypad.7"},
  {"darkwinter.testing_survival",L"Лаборатория: голод и выживание",L"DarkWinter — лаборатория",104,104,"key.category.darkwinter.testing",11,0,"key.keyboard.keypad.8"},
  {"darkwinter.testing_undo",L"Редактор: отменить",L"DarkWinter — лаборатория",0,0,"key.category.darkwinter.testing",11,0,"key.keyboard.unknown"},
  {"darkwinter.testing_visuals",L"Лаборатория: шейдеры и постэффекты",L"DarkWinter — лаборатория",101,101,"key.category.darkwinter.testing",11,0,"key.keyboard.keypad.5"},
  {"darkwinter.testing_weather",L"Лаборатория: погода",L"DarkWinter — лаборатория",98,98,"key.category.darkwinter.testing",11,0,"key.keyboard.keypad.2"},
  {"darkwinter.testing_wind",L"Лаборатория: ветер",L"DarkWinter — лаборатория",99,99,"key.category.darkwinter.testing",11,0,"key.keyboard.keypad.3"},
  {"darkwinter.voice_ptt",L"Голос: удерживать для передачи",L"DarkWinter: голос",78,78,"key.category.darkwinter.voice",13,0,"key.keyboard.n"},
  {"darkwinter.wall_menu",L"Меню строительства",L"Строительство DarkWinter",85,85,"key.category.darkwinter.building",8,0,"key.keyboard.u"},
  {"debug.clearChat",L"Очистить чат",L"Отладка",68,68,"key.category.minecraft.debug",7,0,"key.keyboard.d"},
  {"debug.copyLocation",L"Копировать координаты",L"Отладка",67,67,"key.category.minecraft.debug",7,0,"key.keyboard.c"},
  {"debug.copyRecreateCommand",L"Копировать данные",L"Отладка",73,73,"key.category.minecraft.debug",7,0,"key.keyboard.i"},
  {"debug.crash",L"Отладочный сбой",L"Отладка",67,67,"key.category.minecraft.debug",7,0,"key.keyboard.c"},
  {"debug.debugOptions",L"Параметры отладки",L"Отладка",117,117,"key.category.minecraft.debug",7,0,"key.keyboard.f6"},
  {"debug.dumpDynamicTextures",L"Сохранить динамические текстуры",L"Отладка",83,83,"key.category.minecraft.debug",7,0,"key.keyboard.s"},
  {"debug.dumpVersion",L"Вывести информацию о версии",L"Отладка",86,86,"key.category.minecraft.debug",7,0,"key.keyboard.v"},
  {"debug.focusPause",L"Переключить паузу при сворачивании",L"Отладка",80,80,"key.category.minecraft.debug",7,0,"key.keyboard.p"},
  {"debug.fpsCharts",L"Графики FPS",L"Отладка",50,50,"key.category.minecraft.debug",7,2,"key.keyboard.2"},
  {"debug.improvedTransparency",L"Улучшенная прозрачность",L"Отладка",88,88,"key.category.minecraft.debug",7,0,"key.keyboard.x"},
  {"debug.lightmapTexture",L"Карта освещения",L"Отладка",52,52,"key.category.minecraft.debug",7,4,"key.keyboard.4"},
  {"debug.modifier",L"Клавиша-модификатор отладки",L"Отладка",114,114,"key.category.minecraft.debug",7,-1,"key.keyboard.f3"},
  {"debug.networkCharts",L"Сетевые графики",L"Отладка",51,51,"key.category.minecraft.debug",7,3,"key.keyboard.3"},
  {"debug.overlay",L"Переключить экран отладки",L"Отладка",114,114,"key.category.minecraft.debug",7,-2,"key.keyboard.f3"},
  {"debug.profiling",L"Начать/остановить профилирование",L"Отладка",76,76,"key.category.minecraft.debug",7,0,"key.keyboard.l"},
  {"debug.profilingChart",L"Диаграмма профилирования",L"Отладка",49,49,"key.category.minecraft.debug",7,1,"key.keyboard.1"},
  {"debug.reloadChunk",L"Перезагрузить чанки",L"Отладка",65,65,"key.category.minecraft.debug",7,0,"key.keyboard.a"},
  {"debug.reloadResourcePacks",L"Перезагрузить наборы ресурсов",L"Отладка",84,84,"key.category.minecraft.debug",7,0,"key.keyboard.t"},
  {"debug.showAdvancedTooltips",L"Показывать расширенные подсказки",L"Отладка",72,72,"key.category.minecraft.debug",7,0,"key.keyboard.h"},
  {"debug.showChunkBorders",L"Отображать границы чанков",L"Отладка",71,71,"key.category.minecraft.debug",7,0,"key.keyboard.g"},
  {"debug.showHitboxes",L"Отображать хитбоксы",L"Отладка",66,66,"key.category.minecraft.debug",7,0,"key.keyboard.b"},
  {"debug.spectate",L"Переключить режим наблюдателя",L"Отладка",78,78,"key.category.minecraft.debug",7,0,"key.keyboard.n"},
  {"debug.switchGameMode",L"Переключатель режима игры",L"Отладка",115,115,"key.category.minecraft.debug",7,0,"key.keyboard.f4"},
  {"drop",L"Выбросить предмет",L"Инвентарь",81,81,"key.category.minecraft.inventory",4,0,"key.keyboard.q"},
  {"forward",L"Вперёд",L"Движение",87,87,"key.category.minecraft.movement",0,0,"key.keyboard.w"},
  {"friends",L"Друзья",L"Сетевая игра",79,79,"key.category.minecraft.multiplayer",2,0,"key.keyboard.o"},
  {"fullscreen",L"Полноэкранный режим",L"Разное",122,122,"key.category.minecraft.misc",1,0,"key.keyboard.f11"},
  {"hotbar.1",L"Слот 1",L"Инвентарь",49,49,"key.category.minecraft.inventory",4,0,"key.keyboard.1"},
  {"hotbar.2",L"Слот 2",L"Инвентарь",50,50,"key.category.minecraft.inventory",4,0,"key.keyboard.2"},
  {"hotbar.3",L"Слот 3",L"Инвентарь",51,51,"key.category.minecraft.inventory",4,0,"key.keyboard.3"},
  {"hotbar.4",L"Слот 4",L"Инвентарь",52,52,"key.category.minecraft.inventory",4,0,"key.keyboard.4"},
  {"hotbar.5",L"Слот 5",L"Инвентарь",53,53,"key.category.minecraft.inventory",4,0,"key.keyboard.5"},
  {"hotbar.6",L"Слот 6",L"Инвентарь",54,54,"key.category.minecraft.inventory",4,0,"key.keyboard.6"},
  {"hotbar.7",L"Слот 7",L"Инвентарь",55,55,"key.category.minecraft.inventory",4,0,"key.keyboard.7"},
  {"hotbar.8",L"Слот 8",L"Инвентарь",56,56,"key.category.minecraft.inventory",4,0,"key.keyboard.8"},
  {"hotbar.9",L"Слот 9",L"Инвентарь",57,57,"key.category.minecraft.inventory",4,0,"key.keyboard.9"},
  {"inventory",L"Открыть/закрыть инвентарь",L"Инвентарь",69,9,"key.category.minecraft.inventory",4,0,"key.keyboard.tab"},
  {"jump",L"Прыжок",L"Движение",32,32,"key.category.minecraft.movement",0,0,"key.keyboard.space"},
  {"left",L"Влево",L"Движение",65,65,"key.category.minecraft.movement",0,0,"key.keyboard.a"},
  {"loadToolbarActivator",L"Загрузить инструменты",L"Творческий режим",88,88,"key.category.minecraft.creative",5,0,"key.keyboard.x"},
  {"pickItem",L"Выбор блока",L"Игровой процесс",4,4,"key.category.minecraft.gameplay",3,0,"key.mouse.middle"},
  {"playerlist",L"Список игроков",L"Сетевая игра",9,0,"key.category.minecraft.multiplayer",2,0,"key.keyboard.unknown"},
  {"quickActions",L"Быстрые действия",L"Разное",71,71,"key.category.minecraft.misc",1,0,"key.keyboard.g"},
  {"right",L"Вправо",L"Движение",68,68,"key.category.minecraft.movement",0,0,"key.keyboard.d"},
  {"saveToolbarActivator",L"Сохранить инструменты",L"Творческий режим",67,67,"key.category.minecraft.creative",5,0,"key.keyboard.c"},
  {"screenshot",L"Сделать снимок экрана",L"Разное",113,113,"key.category.minecraft.misc",1,0,"key.keyboard.f2"},
  {"smoothCamera",L"Кинематографическая камера",L"Разное",0,0,"key.category.minecraft.misc",1,0,"key.keyboard.unknown"},
  {"sneak",L"Красться",L"Движение",162,162,"key.category.minecraft.movement",0,0,"key.keyboard.left.control"},
  {"socialInteractions",L"Экран социальных взаимодействий",L"Сетевая игра",80,80,"key.category.minecraft.multiplayer",2,0,"key.keyboard.p"},
  {"spectatorHotbar",L"Выбрать на панели быстрого доступа",L"Наблюдатель",4,4,"key.category.minecraft.spectator",6,0,"key.mouse.middle"},
  {"spectatorOutlines",L"Подсветка игроков",L"Наблюдатель",0,0,"key.category.minecraft.spectator",6,0,"key.keyboard.unknown"},
  {"sprint",L"Бег",L"Движение",160,160,"key.category.minecraft.movement",0,0,"key.keyboard.left.shift"},
  {"swapOffhand",L"Заменить предмет во второй руке",L"Инвентарь",70,70,"key.category.minecraft.inventory",4,0,"key.keyboard.f"},
  {"toggleGui",L"Переключение интерфейса",L"Разное",112,112,"key.category.minecraft.misc",1,0,"key.keyboard.f1"},
  {"togglePerspective",L"Переключить перспективу",L"Разное",116,116,"key.category.minecraft.misc",1,0,"key.keyboard.f5"},
  {"toggleSpectatorShaderEffects",L"Переключение шейдеров наблюдения",L"Разное",115,115,"key.category.minecraft.misc",1,0,"key.keyboard.f4"},
  {"use",L"Использовать/Установить",L"Игровой процесс",2,2,"key.category.minecraft.gameplay",3,0,"key.mouse.right"}
 };
 selectedLanguage=settings.textOption("language","ru_ru");
}
std::wstring Ui::tr(const std::string& key,const std::wstring& ru,const std::wstring& en) const {
 const auto language=settings.textOption("language","ru_ru");
 auto find=[&](const std::string& locale)->const std::wstring*{auto table=translations.find(locale);if(table==translations.end())return nullptr;auto it=table->second.find(key);return it==table->second.end()?nullptr:&it->second;};
 if(!key.empty()){if(auto value=find(language))return *value;if(auto value=find("en_us"))return *value;}
 return language.rfind("ru",0)==0||en.empty()?ru:en;
}
std::wstring Ui::fitted(const std::wstring& value,float size,int width) {
 if(width<=0)return {};if(measure(value,size)<=width)return value;
 const std::wstring ellipsis=L"…";int ew=measure(ellipsis,size);if(ew>width)return {};
 size_t lo=0,hi=value.size();while(lo<hi){size_t mid=(lo+hi+1)/2;if(measure(value.substr(0,mid),size)+ew<=width)lo=mid;else hi=mid-1;}
 if(lo&&lo<value.size()&&value[lo-1]>=0xd800&&value[lo-1]<=0xdbff)--lo;return value.substr(0,lo)+ellipsis;
}
void Ui::setClip(int x,int y,int width,int height) {
 clipStack.push_back(clip);int a=int(std::round(x*pixelX)),b=int(std::round(y*pixelY)),c=int(std::round((x+std::max(0,width))*pixelX)),d=int(std::round((y+std::max(0,height))*pixelY));
 clip={std::max(clip[0],a),std::max(clip[1],b),std::min(clip[2],c),std::min(clip[3],d)};
}
void Ui::resetClip(){if(clipStack.empty())clip={0,0,rasterW,rasterH};else{clip=clipStack.back();clipStack.pop_back();}}
void Ui::setScrollRegion(int x,int y,int width,int height,int contentHeight) {
 scrollX=x;scrollY=y;scrollWidth=std::max(0,width);scrollHeight=std::max(0,height);scrollMaximum=std::max(0,contentHeight-height);
 optionScroll=std::clamp(optionScroll,0,scrollMaximum);scrollPositions[page]=optionScroll;
}
bool Ui::scroll(int steps,int x,int y) {
 if(!steps||!scrollWidth||!scrollHeight)return false;
 if(x>=0&&y>=0&&(x<scrollX||x>=scrollX+scrollWidth||y<scrollY||y>=scrollY+scrollHeight))return false;
 int previous=optionScroll;optionScroll=std::clamp(optionScroll-steps*scrollStep,0,scrollMaximum);
 scrollPositions[page]=optionScroll;worldScroll=optionScroll/26;if(previous!=optionScroll)dirty=true;return true;
}
void Ui::scrollBar(int overrideX) {
 if(scrollMaximum<=0||scrollHeight<=0)return;
 int x=overrideX>=0?overrideX:scrollX+scrollWidth-7,y=scrollY,width=6,height=scrollHeight;
 surface(x+2,y,width-4,height,4,0,color(0x93aebb,176));
 int thumb=std::clamp(int(double(height)*height/(height+scrollMaximum)),12,height),ty=y+int(double(height-thumb)*optionScroll/scrollMaximum);
 surface(x+1,ty,width-2,thumb,5,0,color(0x93aebb));
 UiButton b{690,x,y,width,height,L"",true,float(optionScroll)/scrollMaximum};b.kind=1;b.optionKey="ui.scroll";buttons.push_back(std::move(b));
}
void Ui::selectPage(int p) {
 if(page==p){dirty=true;return;}
 if(page==CONNECTION_CREATE||page==CONNECTION_JOIN){auto& secret=textFields[451];std::fill(secret.begin(),secret.end(),L'\0');secret.clear();}
 scrollPositions[page]=optionScroll;page=p;hover=-1;focus=-1;focusedField=-1;fieldSelected=false;bindingCapture=-1;
 optionScroll=0;scrollWidth=scrollHeight=scrollMaximum=0;scrollStep=25;dirty=true;
 if(p==TITLE||p==GAME)history.clear();
 if(p==CREATE){status.clear();focusField(400);}
 if(p==LANGUAGE){selectedLanguage=settings.textOption("language","ru_ru");textFields[402].clear();}
 if(p==CONNECTION_CREATE||p==CONNECTION_JOIN){status.clear();focusField(450);}
}
void Ui::openPage(int p,bool remember) {if(p==page)return;if(remember)history.push_back(page);selectPage(p);}
bool Ui::backPage() {
 if(history.empty())return false;int target=history.back();history.pop_back();auto language=selectedLanguage;auto search=textFields[402];selectPage(target);if(target==LANGUAGE){selectedLanguage=language;textFields[402]=search;}
 auto scroll=scrollPositions.find(target);if(scroll!=scrollPositions.end())optionScroll=scroll->second;dirty=true;return true;
}
bool Ui::backToPage(int target) {
 auto parent=std::find(history.rbegin(),history.rend(),target);
 if(parent!=history.rend())history.resize(size_t(history.rend()-parent-1));
 selectPage(target);auto previous=scrollPositions.find(target);if(previous!=scrollPositions.end())optionScroll=previous->second;dirty=true;return true;
}
void Ui::applyPendingSettings(){if(page==LANGUAGE&&!selectedLanguage.empty()){settings.setTextOption("language",selectedLanguage);dirty=true;}}
bool Ui::activateNavigation(int id) {
 if(id>=740&&id<=748)return false; // These world dialogs distinguish Apply from Cancel.
 auto it=std::find_if(buttons.begin(),buttons.end(),[&](const UiButton& b){return b.id==id&&b.enabled&&b.targetPage>=0;});
 if(it==buttons.end())return false;int target=it->targetPage;
 if(id==620||id==621||(id>=710&&id<=712)||(id>=724&&id<=726)){selectPage(target);return true;}
 if(id==625||id==727||id==757||(id>=702&&id<=704)||(id>=717&&id<=719)||id==751||id==752)return backToPage(target);
 if(id>=21&&id<=27){
  applyPendingSettings();if(page==FONT_OPTIONS&&!selectedLanguage.empty())settings.setTextOption("language",selectedLanguage);
  if(page==TITLE||page==SETTINGS)openPage(target);
  else {auto hub=std::find(history.rbegin(),history.rend(),SETTINGS);if(hub!=history.rend())history.resize(size_t(history.rend()-hub));else history.push_back(SETTINGS);selectPage(target);}
 }
 else openPage(target);return true;
}
const UiOption* Ui::optionFor(int id) const {const auto& options=uiOptionCatalogue();auto it=std::find_if(options.begin(),options.end(),[&](const UiOption& o){return o.id==id;});return it==options.end()?nullptr:&*it;}
float Ui::optionAmount(const UiOption& o) const {
 double value=settings.option(o.key,o.defaultValue);
 if(o.format=="fps"&&value==0)return 1;
 if(o.format=="logarithmic")return float(std::clamp((std::log10(std::max(value,o.min))-std::log10(o.min))/(std::log10(o.max)-std::log10(o.min)),0.,1.));
 return float(std::clamp((value-o.min)/std::max(1e-12,o.max-o.min),0.,1.));
}
bool Ui::setOptionSlider(int id,float amount) {
 double t=std::clamp(double(amount),0.,1.);
 if(id==690){optionScroll=int(std::round(scrollMaximum*t));scrollPositions[page]=optionScroll;dirty=true;return true;}
 const auto* o=optionFor(id);if(!o||o->kind!=UiOptionKind::Slider)return false;
 double value=o->format=="logarithmic"?std::pow(10.,std::lerp(std::log10(o->min),std::log10(o->max),t)):std::lerp(o->min,o->max,t);
 if(o->step>0)value=o->min+std::round((value-o->min)/o->step)*o->step;
 value=std::clamp(value,o->min,o->max);if(o->format=="fps"&&t>.985)value=0;
 settings.setOption(o->key,value);dirty=true;return true;
}
bool Ui::activateOption(int id) {
#if __has_include("ui_world_pages.hpp")
 if(UiWorldPages::activate(*this,id))return true;
#endif
 if(id==637){settings.setOption("friendsList",1);settings.setOption("allowFriendRequests",1);return backToPage(ONLINE);}if(id==638)return backToPage(ONLINE);
 if(id==20142&&settings.option("friendsList",0)==0){openPage(FRIENDS_CONFIRM);return true;}
 if(id==691)return scroll(3);if(id==692)return scroll(-3);
 if(id>=10000&&id<10000+int(languages.size())){selectedLanguage=languages[size_t(id-10000)].code;dirty=true;return true;}
 if(id>=12000&&id<12000+int(keyBindings.size())){bindingCapture=id-12000;focus=id;dirty=true;return true;}
 if(id>=13000&&id<13000+int(keyBindings.size())){const auto& b=keyBindings[size_t(id-13000)];settings.setOption("key."+b.key,b.defaultKey);dirty=true;return true;}
 if(id==680){for(const auto& b:keyBindings)settings.setOption("key."+b.key,b.defaultKey);dirty=true;return true;}
 const auto* o=optionFor(id);if(!o)return false;
 if(o->kind==UiOptionKind::Slider)return false;
 if(o->key=="soundDevice"&&!audioDevices.empty()){int index=int(settings.option(o->key,0));settings.setOption(o->key,(index+1)%int(audioDevices.size()));}
 else if(o->kind==UiOptionKind::Toggle)settings.setOption(o->key,settings.option(o->key,o->defaultValue)==0?1:0);
 else if(!o->values.empty()){double value=settings.option(o->key,o->defaultValue);auto it=std::min_element(o->values.begin(),o->values.end(),[&](double a,double b){return std::abs(a-value)<std::abs(b-value);});size_t index=it==o->values.end()?0:(size_t(it-o->values.begin())+1)%o->values.size();settings.setOption(o->key,o->values[index]);}
 if(o->key=="motion")settings.setOption("animated",settings.option("motion",0)!=2);
 dirty=true;return true;
}
std::wstring Ui::optionLabel(const UiOption& o) const {
 double value=settings.option(o.key,o.defaultValue);std::wstring title=tr(o.labelKey,o.label,o.english),display;
 bool ru=settings.textOption("language","ru_ru").rfind("ru",0)==0;
 if(o.kind==UiOptionKind::Toggle)display=value==0?tr("options.off",L"Выкл",L"OFF"):tr("options.on",L"Вкл",L"ON");
 else if(o.kind==UiOptionKind::Cycle){
  if(o.key=="soundDevice"&&!audioDevices.empty())display=audioDevices[size_t(std::clamp(int(value),0,int(audioDevices.size())-1))];
  else {size_t index=0;for(size_t i=1;i<o.values.size();i++)if(std::abs(o.values[i]-value)<std::abs(o.values[index]-value))index=i;display=index<o.labels.size()?o.labels[index]:compactNumber(value);
   static const std::map<std::string,std::vector<std::string>> valueKeys={
    {"preferredGraphicsBackend",{"options.graphicsApi.default","options.graphicsApi.opengl","options.graphicsApi.vulkan"}},
    {"transparencyQuality",{"options.darkwinter.transparency.balanced","options.darkwinter.transparency.precise"}},
    {"sharePresence",{"options.sharePresence.none","options.sharePresence.limited","options.sharePresence.all"}},
    {"clouds",{"options.off","options.clouds.fast","options.clouds.fancy"}},
    {"particles",{"options.particles.all","options.particles.decreased","options.particles.minimal"}},
    {"textureFiltering",{"options.textureFiltering.none","options.textureFiltering.rgss","options.textureFiltering.anisotropic"}},
    {"prioritizeChunkUpdates",{"options.prioritizeChunkUpdates.none","options.prioritizeChunkUpdates.byPlayer","options.prioritizeChunkUpdates.nearby"}},
    {"backgroundLimit",{"options.inactivityFpsLimit.minimized","options.inactivityFpsLimit.afk"}},
    {"musicFrequency",{"options.music_frequency.default","options.music_frequency.frequent","options.music_frequency.constant"}},
    {"musicToast",{"options.musicToast.never","options.musicToast.pauseMenu","options.musicToast.pauseMenuAndToast"}},
    {"narrator",{"options.narrator.off","options.narrator.all","options.narrator.chat","options.narrator.system"}},
    {"attackIndicator",{"options.off","options.attack.crosshair","options.attack.hotbar"}},
    {"chatVisibility",{"options.chat.visibility.full","options.chat.visibility.system","options.chat.visibility.hidden"}},
    {"mainHand",{"options.mainHand.left","options.mainHand.right"}},
    {"backgroundForChatOnly",{"options.accessibility.text_background.everywhere","options.accessibility.text_background.chat"}}
   };auto translated=valueKeys.find(o.key);if(translated!=valueKeys.end()&&index<translated->second.size())display=tr(translated->second[index],display);
  }
  if(display==L"Выкл")display=tr("options.off",L"Выкл",L"OFF");else if(display==L"Вкл")display=tr("options.on",L"Вкл",L"ON");
  if(o.key=="motion")return tr(value==0?"menu.darkwinter.motion.full":value==1?"menu.darkwinter.motion.reduced":"menu.darkwinter.motion.off",L"Эффекты: "+display,value==0?L"Analog effects: full":value==1?L"Analog effects: reduced":L"Analog effects: off");
  if(o.key=="pauseArt")return tr(value==0?"menu.darkwinter.pause.world":"menu.darkwinter.pause.shelter",L"Фон паузы: "+display,value==0?L"Pause background: current scene":L"Pause background: shelter artwork");
  if(o.key.rfind("toggle",0)==0)display=value==0?tr("options.key.hold",L"Удерживание",L"Hold"):tr("options.key.toggle",L"Переключение",L"Toggle");
  if(o.key=="guiScale"&&value==0)display=tr("options.guiScale.auto",L"Авто",L"Auto");
 } else if(o.format=="fov")display=value==70?tr("options.fov.min",L"Нормально",L"Normal"):value==110?L"Quake Pro":compactNumber(value,0);
 else if(o.format=="fps")display=value==0?tr("options.framerateLimit.max",L"Максимум",L"Unlimited"):compactNumber(value,0);
 else if(o.format=="brightness")display=value==1?tr("options.gamma.default",L"По умолчанию",L"Default"):value<=.5?tr("options.gamma.min",L"Темно",L"Moody"):value>=1.5?tr("options.gamma.max",L"Ярко",L"Bright"):std::to_wstring(int(std::round((value-.5)*100)))+L"%";
 else if(o.format=="resolution")display=value<0?tr("options.fullscreen.current",L"Текущее",L"Current"):std::to_wstring(settings.width)+L" × "+std::to_wstring(settings.height);
 else if(o.format=="preset"){const wchar_t* names[]={L"Быстро",L"Детально",L"Великолепно",L"Пользовательский"};const char* key[]={"options.graphics.fast","options.graphics.fancy","options.graphics.fabulous","options.graphics.custom"};int index=std::clamp(int(value),0,3);display=tr(key[index],names[index]);}
 else if(o.format=="biome"){int n=int(value)*2+1;display=value==0?tr("options.off",L"Выкл",L"OFF"):std::to_wstring(n)+L"×"+std::to_wstring(n)+(value==7?(ru?L" (максимум)":L" (Maximum)"):L"");}
 else if(o.format=="percent"||o.format=="volume")display=value==0&&(o.format=="volume"||o.key=="chatScale")?tr("options.off",L"Выкл",L"OFF"):std::to_wstring(int(std::round(value*100)))+L"%";
 else if(o.format=="chatOpacity")display=std::to_wstring(int(std::round((value*.9+.1)*100)))+L"%";
 else if(o.format=="chatWidth"||o.format=="chatHeight")display=std::to_wstring(int(std::floor(o.format=="chatWidth"?value*280+40:value*160+20)))+L"px";
 else if(o.format=="sensitivity")display=value<=0?tr("options.sensitivity.min",L"Минимум",L"*yawn*"):value>=1?tr("options.sensitivity.max",L"Максимум",L"HYPERSPEED!!!"):std::to_wstring(int(std::round(value*200)))+L"%";
 else if(o.format=="blockChunks"||o.format=="chunks")display=uiFormat(tr("options.chunks",L"%s чанков",L"%s chunks"),{std::to_wstring(int(std::round(o.format=="blockChunks"?value/16:value)))});
 else if(o.format=="blocks")display=std::to_wstring(int(std::round(value)))+(ru?L" блоков":L" blocks");
 else if(o.format=="seconds"){if(o.key=="chatDelay"&&value==0)return tr("options.chat.delay_none",L"Задержка чата: выкл.",L"Chat Delay: None");display=compactNumber(value)+(ru?L" сек.":L" s");}
 else if(o.format=="multiplier"){std::wostringstream out;out<<std::fixed<<std::setprecision(1)<<value;display=out.str()+L"x";}
 else if(o.format=="power2")display=std::to_wstring(1<<std::clamp(int(value),0,8))+L"x";
 else if(o.format=="logarithmic"){std::wostringstream out;out<<std::fixed<<std::setprecision(2)<<value;display=out.str();}
 else if(o.format=="offInteger"&&value==0)display=tr("options.off",L"Выкл",L"OFF");
 else display=compactNumber(value,o.step>=1?0:2);
 if(o.key=="menuMist")return tr(value==0?"menu.darkwinter.fx.mist.off":"menu.darkwinter.fx.mist.on",value==0?L"Дымка в меню: выкл.":L"Дымка в меню: вкл.",value==0?L"Menu mist: off":L"Menu mist: on");
 if(o.key=="chatDelay"){std::wostringstream out;out<<std::fixed<<std::setprecision(1)<<value;return uiFormat(tr("options.chat.delay",L"Задержка чата: %s сек.",L"Chat Delay: %s second(s)"),{out.str()});}
 if(o.key=="chunkSectionFadeInTime")return value==0?tr("options.chunkFade.none",L"Проявление чанков: выкл.",L"Chunk Fade: None"):uiFormat(tr("options.chunkFade.seconds",L"Проявление чанков: %s сек.",L"Chunk Fade: %s second(s)"),{compactNumber(value)});
 if(title.find(L'%')!=std::wstring::npos){if(title.find(L"%%")!=std::wstring::npos&&!display.empty()&&display.back()==L'%')display.pop_back();return uiFormat(title,{display});}
 return uiFormat(tr("options.generic_value",L"%s: %s",L"%s: %s"),{title,display});
}
std::wstring& Ui::fieldValue(int id){if(id==400)return worldName;if(id==401)return seedText;return textFields[id];}
bool Ui::focusField(int id,int pointerX) {
 if(id<400||id>=500)return false;focusedField=id;field=id==401?1:0;auto& value=fieldValue(id);caret=value.size();anchor=caret;fieldSelected=false;
 if(pointerX>=0){auto b=std::find_if(buttons.begin(),buttons.end(),[&](const auto& item){return item.id==id;});if(b!=buttons.end()){int local=std::max(0,pointerX-b->x-5+fieldOffsets[id]);const auto display=id==451?std::wstring(value.size(),L'*'):value;caret=0;while(caret<value.size()&&measure(display.substr(0,caret+1),11)<=local)++caret;if(caret<value.size()&&value[caret]>=0xdc00&&value[caret]<=0xdfff&&caret)--caret;anchor=caret;}}
 focus=id;dirty=true;return true;
}
bool Ui::inputText(wchar_t c) {
 if(!editing())return false;auto& value=fieldValue(focusedField);caret=std::min(caret,value.size());anchor=std::min(anchor,value.size());
 if(c<32&&c!=8)return false;size_t lo=std::min(caret,anchor),hi=std::max(caret,anchor);
 if(c==8){if(lo!=hi)value.erase(lo,hi-lo);else if(lo){size_t n=lo-1;if(n&&value[n]>=0xdc00&&value[n]<=0xdfff&&value[n-1]>=0xd800&&value[n-1]<=0xdbff)--n;value.erase(n,lo-n);lo=n;}}
 else {if(lo!=hi)value.erase(lo,hi-lo);size_t limit=focusedField==408?1230:focusedField==409?32:focusedField>=410&&focusedField<=449?20:focusedField==450?96:focusedField==400||focusedField==401||focusedField==451?128:1024;if(value.size()<limit)value.insert(value.begin()+std::ptrdiff_t(lo++),c);}
 caret=anchor=std::min(lo,value.size());fieldSelected=false;if(focusedField==402||focusedField==403)optionScroll=0;dirty=true;
#if __has_include("ui_world_pages.hpp")
 UiWorldPages::ruleFieldEdited(*this,focusedField);
#endif
 return true;
}
bool Ui::handleKey(unsigned key,bool shift,bool ctrl) {
 if(bindingCapture>=0){if(size_t(bindingCapture)<keyBindings.size())settings.setOption("key."+keyBindings[size_t(bindingCapture)].key,key==VK_ESCAPE?0:key);bindingCapture=-1;dirty=true;return true;}
 if(key==VK_ESCAPE&&(page==CONNECTION_CREATE||page==CONNECTION_JOIN)){selectPage(CONNECTIONS);return true;}
 if(page==LANGUAGE&&key==VK_RETURN){applyPendingSettings();if(!backPage())selectPage(SETTINGS);return true;}
 if(editing()){
  auto& value=fieldValue(focusedField);caret=std::min(caret,value.size());anchor=std::min(anchor,value.size());
  if(ctrl&&key=='A'){anchor=0;caret=value.size();fieldSelected=true;dirty=true;return true;}
  if(ctrl&&(key=='C'||key=='X')){if(focusedField==451)return true;size_t lo=std::min(caret,anchor),hi=std::max(caret,anchor);if(lo!=hi&&copySelection(value.substr(lo,hi-lo))&&key=='X'){value.erase(lo,hi-lo);caret=anchor=lo;if(focusedField==402||focusedField==403)optionScroll=0;dirty=true;
#if __has_include("ui_world_pages.hpp")
 UiWorldPages::ruleFieldEdited(*this,focusedField);
#endif
 }return true;}
  if(key==VK_LEFT||key==VK_RIGHT||key==VK_HOME||key==VK_END){if(key==VK_HOME)caret=0;else if(key==VK_END)caret=value.size();else if(!shift&&caret!=anchor)caret=key==VK_LEFT?std::min(caret,anchor):std::max(caret,anchor);else if(key==VK_LEFT){caret=previousCodepoint(value,caret);if(ctrl){while(caret&&std::iswspace(value[caret]))caret=previousCodepoint(value,caret);while(caret&&!std::iswspace(value[previousCodepoint(value,caret)]))caret=previousCodepoint(value,caret);}}else {caret=nextCodepoint(value,caret);if(ctrl){while(caret<value.size()&&!std::iswspace(value[caret]))caret=nextCodepoint(value,caret);while(caret<value.size()&&std::iswspace(value[caret]))caret=nextCodepoint(value,caret);}}if(!shift)anchor=caret;fieldSelected=anchor!=caret;dirty=true;return true;}
  if(key==VK_DELETE||(ctrl&&key==VK_BACK)){size_t lo=std::min(caret,anchor),hi=std::max(caret,anchor);if(lo==hi){if(key==VK_DELETE)hi=nextCodepoint(value,hi);else{lo=previousCodepoint(value,lo);while(lo&&std::iswspace(value[lo]))lo=previousCodepoint(value,lo);while(lo&&!std::iswspace(value[previousCodepoint(value,lo)]))lo=previousCodepoint(value,lo);}}value.erase(lo,hi-lo);caret=anchor=lo;if(focusedField==402||focusedField==403)optionScroll=0;dirty=true;
#if __has_include("ui_world_pages.hpp")
 UiWorldPages::ruleFieldEdited(*this,focusedField);
#endif
 return true;}
 }
 if(page==GAME||page==MATERIALS)return false;
 if(key==VK_PRIOR)return scroll(8);if(key==VK_NEXT)return scroll(-8);
 if(key==VK_TAB||key==VK_UP||key==VK_DOWN){std::vector<const UiButton*> candidates;for(const auto& b:buttons)if(b.enabled&&b.id!=690)candidates.push_back(&b);if(candidates.empty())return true;auto it=std::find_if(candidates.begin(),candidates.end(),[&](auto* b){return b->id==focus;});int next=it==candidates.end()?0:int(it-candidates.begin())+((key==VK_UP||(key==VK_TAB&&shift))?-1:1);const auto* chosen=candidates[size_t((next+int(candidates.size()))%int(candidates.size()))];focus=chosen->id;if(chosen->kind==2)focusField(focus);else focusedField=-1;dirty=true;return true;}
 if((key==VK_LEFT||key==VK_RIGHT)&&!editing()){if(auto* option=optionFor(focus)){if(option->kind==UiOptionKind::Slider)return setOptionSlider(focus,optionAmount(*option)+(key==VK_RIGHT?.01f:-.01f));if(key==VK_RIGHT)return activateOption(focus);if(!option->values.empty()){double v=settings.option(option->key,option->defaultValue);size_t index=0;for(size_t i=0;i<option->values.size();i++)if(std::abs(option->values[i]-v)<std::abs(option->values[index]-v))index=i;settings.setOption(option->key,option->values[(index+option->values.size()-1)%option->values.size()]);dirty=true;return true;}}}
 return false;
}
void Ui::animate(float time) {
 float dt=std::clamp(time-priorAnimation,0.f,.1f);priorAnimation=time;menuTime=time;
 if(hover!=lastHover){lastHover=hover;hoverSince=time;dirty=true;}
 bool animated=settings.animated&&settings.motion==0;
 for(const auto& b:buttons){float target=b.enabled&&(b.id==hover||b.id==focus)?1.f:0.f;float& light=lights[b.id];float previous=light;light=animated?std::lerp(light,target,1-std::exp(-dt/(target>light?.17f:.22f))):target;if(std::abs(previous-light)>1.f/1024)dirty=true;}
 auto frame=uint64_t(std::max(0.f,time)*(settings.motion==0?20:10));if(page!=GAME&&settings.animated&&animationFrame!=frame){animationFrame=frame;dirty=true;}
 if(editing()||(hover>=0&&time-hoverSince<.85f))dirty=true;
}
void Ui::wrapped(int x,int y,int maxWidth,float size,const std::wstring& value,uint32_t tint,int maxLines) {
 std::wstring current,word;int line=0;auto emit=[&](){text(x,y+int(line*(size+3)),size,current,tint);current.clear();++line;};
 for(size_t i=0;i<=value.size()&&line<maxLines;i++){wchar_t c=i==value.size()?L'\n':value[i];if(c==L' '||c==L'\n'||c==L'\r'){if(!word.empty()){std::wstring next=current.empty()?word:current+L" "+word;if(!current.empty()&&measure(next,size)>maxWidth){emit();if(line>=maxLines)return;current=word;}else current=std::move(next);word.clear();}if(c==L'\n')emit();}else word+=c;}
}
void Ui::tooltip() {
 if(hover<0||menuTime-hoverSince<.65f||editing())return;
 auto it=std::find_if(buttons.begin(),buttons.end(),[&](const auto& b){return b.id==hover;});if(it==buttons.end()||it->tooltip.empty())return;
 int width=std::min(280,w-16),lines=std::clamp((measure(it->tooltip,10)+width-17)/(width-16),1,8),height=lines*13+12;
 int x=std::clamp(it->x+12,8,std::max(8,w-width-8)),y=it->y+it->h+8;if(y+height>h-8)y=std::max(8,it->y-height-8);
 surface(x,y,width,height,8,0,color(0xa9dae7));wrapped(x+7,y+6,width-14,10,it->tooltip,color(0xdce8eb),8);
}
void Ui::registerButton(int id,int x,int y,int width,int height,const std::wstring& value,bool enabled,int targetPage) {
 int x0=std::max(x,int(std::ceil(clip[0]/pixelX))),y0=std::max(y,int(std::ceil(clip[1]/pixelY)));
 int x1=std::min(x+width,int(std::floor(clip[2]/pixelX))),y1=std::min(y+height,int(std::floor(clip[3]/pixelY)));
 if(x1<=x0||y1<=y0)return;
 UiButton b{id,x0,y0,x1-x0,y1-y0,value,enabled};b.targetPage=targetPage;buttons.push_back(std::move(b));
}
void Ui::add(int id,int x,int y,int width,int height,const std::wstring& value,bool enabled) {
 size_t previous=buttons.size();registerButton(id,x,y,width,height,value,enabled);if(buttons.size()==previous)return;
 float light=lights.contains(id)?lights[id]:(hover==id||focus==id?1.f:0.f);if(!enabled)light=0;
 bool row=(page==TITLE||page==PAUSE),primary=id==10||id==1||id==201||id==202||id==235||id==301;
 uint32_t muted=color(0x93aebb),bright=color(0xdce8eb),ink=0xff000000u;float fraction=primary?.94f:.72f+light*.28f;
 for(int k=0;k<24;k+=8)ink|=uint32_t(std::round(std::lerp(float((muted>>k)&255),float((bright>>k)&255),fraction)))<<k;
 if(!enabled)ink=(ink&0xffffff)|(122u<<24);
 if(row){surface(x,y,width,height,!enabled?7:primary?1:10,light,color(0xa9dae7));int glyph=(id==10||id==1)?10:id==41?11:id==3||id==20?12:id==7||id==30?13:id==4||id==90?14:15;
  picture(glyph,x+7,y+(height-14)/2,14,14,false,ink);auto caption=fitted(value,11,width-38);text(x+28,y+(height-9)/2.f,11,caption,ink);if(caption!=value)buttons.back().tooltip=value;
  if(focus==id)rect(x+2,y+6,1,std::max(0,height-12),color(0xa9dae7));return;
 }
 surface(x,y,width,height,!enabled?7:primary?1:0,primary?.38f+.62f*light:light,color(id==222||id==224?0xd05a60:0xa9dae7));
 auto display=(id==201||id==301)&&value.rfind(L"+ ",0)!=0?L"+ "+value:value;auto caption=fitted(display,11,width-12);text(x+(width-measure(caption,11))/2.f,y+(height-9)/2.f,11,caption,enabled?color(0xdce8eb):color(0x93aebb));if(caption!=display)buttons.back().tooltip=value;
 if(focus==id&&enabled)rect(x+3,y+5,1,std::max(0,height-10),color(0xa9dae7));
}
void Ui::addPage(int id,int x,int y,int width,int height,const std::wstring& value,int targetPage,bool enabled) {
 size_t previous=buttons.size();add(id,x,y,width,height,value,enabled);if(buttons.size()!=previous)buttons.back().targetPage=targetPage;
}
void Ui::addOption(int id,int x,int y,int width,int height) {
 const auto* o=optionFor(id);if(!o)return;size_t previous=buttons.size();auto label=optionLabel(*o);bool enabled=true;
 if(o->key=="rotateWithMinecart")enabled=settings.option("create.experiment.minecart_improvements",0)!=0;
 if(o->key=="allowFriendRequests"||o->key=="inGameNotification"||o->key=="sharePresence")enabled=settings.option("friendsList",0)!=0;
 add(id,x,y,width,height,label,enabled);if(buttons.size()==previous)return;
 auto& b=buttons.back();b.optionKey=o->key;if(o->key=="friendsList"&&settings.option("friendsList",0)==0)b.targetPage=FRIENDS_CONFIRM;
 std::wstring help=o->tooltip;if(help.empty()&&!o->labelKey.empty())help=tr(o->labelKey+".tooltip",L"");if(!help.empty())b.tooltip=help;
 if(o->kind!=UiOptionKind::Slider)return;
 b.kind=1;b.amount=optionAmount(*o);float a=b.amount;int track=std::max(1,width-8);rect(x+4,y+height-4,track,1,color(0x93aebb,100));rect(x+4,y+height-4,int(std::round(track*a)),1,color(0xa9dae7,179));
 int thumbX=x+int(std::round((width-8)*a));surface(thumbX+1,y+height-5,6,3,5,0,color(0x93aebb));
}
void Ui::editField(int id,int x,int y,int width,int height,const std::wstring& placeholder) {
 size_t previous=buttons.size();auto& stored=fieldValue(id);const std::wstring value=id==451?std::wstring(stored.size(),L'*'):stored;registerButton(id,x,y,width,height,id==451?tr("menu.darkwinter.connections.password_hidden",L"Пароль соединения (скрыт)",L"Connection password (hidden)"):value,true);if(previous==buttons.size())return;buttons.back().kind=2;
 float light=focusedField==id?1.f:lights.contains(id)?lights[id]:0.f;surface(x,y,width,height,2,light,color(0xa9dae7));
 setClip(x+4,y+2,width-8,height-4);int offset=fieldOffsets[id];size_t c=std::min(caret,value.size()),a=std::min(anchor,value.size());
 if(focusedField==id){int cursor=measure(value.substr(0,c),11);if(cursor-offset>width-12)offset=cursor-(width-12);if(cursor<offset)offset=cursor;}
 offset=std::clamp(offset,0,std::max(0,measure(value,11)-(width-12)));fieldOffsets[id]=offset;
 float ty=y+(height-9)/2.f;int tx=x+5-offset;
 if(focusedField==id&&a!=c){int lo=measure(value.substr(0,std::min(a,c)),11),hi=measure(value.substr(0,std::max(a,c)),11);rect(tx+lo,int(ty)-1,std::max(1,hi-lo),12,color(0x5b8ca3,160));}
 if(value.empty()&&focusedField!=id)text(tx,ty,11,fitted(placeholder,11,width-10),color(0x93aebb,150));else text(tx,ty,11,value,color(0xdce8eb));
 if(focusedField==id&&int(menuTime*2)%2==0)rect(tx+measure(value.substr(0,c),11),int(ty)-1,1,12,color(0xdce8eb));resetClip();
}
int Ui::settingsSection(int p)const {
 if(p==VIDEO)return 0;if(p==AUDIO)return 1;if(p==CONTROLS||p==MOUSE||p==KEYBINDS)return 2;if(p==INTERFACE)return 3;if(p==ACCESSIBILITY)return 4;if(p==LANGUAGE||p==FONT_OPTIONS)return 5;return -1;
}
int Ui::settingsFrame(const std::wstring& title) {
 if(activeWorld)gradient(0,0,w,h,color(0x0b1723,39),color(0x040d16,105));else{backdrop(0);if(page==INTERFACE)rect(0,0,w,h,color(0x040c15,74));}
 int left=std::max(12,std::min(34,w/22)),nav=std::min(178,std::max(102,(w-70)/3)),right=left+nav+20,content=std::max(90,w-right-left),step=std::max(12,std::min(25,(h-94)/8));
 text(left,18,15.62f,tr("options.title",L"Настройки",L"Options"),color(0xdce8eb));rect(left,39,w-left*2,1,color(0x537384,144));
 text(left,46,8.03f,tr("menu.darkwinter.categories",L"РАЗДЕЛЫ",L"CATEGORIES"),color(0x93aebb));
 static constexpr int targets[]={VIDEO,AUDIO,CONTROLS,INTERFACE,ACCESSIBILITY,LANGUAGE},ids[]={21,23,22,24,26,27},glyphs[]={17,18,19,13,20,21};
 static const char* keys[]={"video","sound","controls","interface","accessibility","language"};
 static const wchar_t* ru[]={L"Графика",L"Звук",L"Управление",L"Интерфейс",L"Доступность",L"Язык"};
 static const wchar_t* en[]={L"Graphics",L"Audio",L"Controls",L"Interface",L"Accessibility",L"Language"};
 int section=settingsSection(page);
 for(int i=0;i<6;i++){int y=58+i*step,bh=std::max(16,step-3);auto label=tr(std::string("menu.darkwinter.nav.")+keys[i],ru[i],en[i]);size_t before=buttons.size();registerButton(ids[i],left,y,nav,bh,label,true,targets[i]);if(before==buttons.size())continue;
  buttons.back().tooltip=label;float light=lights.contains(ids[i])?lights[ids[i]]:0;if(i==section)light=std::max(light,.82f);surface(left,y,nav,bh,9,light,color(0xa9dae7));
  if(light>0)rect(left,y+4,1,bh-8,color(0xa9dae7,uint32_t(std::round(255*light))));uint32_t ink=i==section||hover==ids[i]||focus==ids[i]?color(0xdce8eb):color(0x93aebb);
  picture(glyphs[i],left+7,y+(bh-13)/2,13,13,false,ink);text(left+27,y+(bh-9)/2+1,11,fitted(label,11,nav-35),color(0xdce8eb));
 }
 surface(right-4,48,content+8,std::max(60,h-90),3,0,color(0x7897a7));rect(right-2,48,16,1,color(0xc2d9e1,166));int glyph=section>=0?glyphs[section]:12;
 picture(glyph,right+6,52,12,12,false,color(0x93aebb));text(right+24,54,8.25f,fitted(title,8.25f,content-30),color(0x93aebb));return right+6;
}
void Ui::settingsFooter(const std::wstring& label) {
 int left=std::max(12,std::min(34,w/22)),nav=std::min(178,std::max(102,(w-70)/3)),x=left+nav+26,cw=std::max(84,w-(left+nav+20)-left-12);
 add(0,page==SETTINGS?w-130:x+cw-110,h-28,110,20,label.empty()?tr("gui.done",L"Готово",L"Done"):label);
}
void Ui::renderSettings() {
 auto title=[&]()->std::wstring{switch(page){case SETTINGS:return tr("options.title",L"Настройки",L"Options");case VIDEO:return tr("options.videoTitle",L"Настройки графики",L"Video Settings");case AUDIO:return tr("options.sounds.title",L"Настройки музыки и звуков",L"Music & Sound Options");case CONTROLS:return tr("controls.title",L"Настройки управления",L"Controls");case MOUSE:return tr("options.mouse_settings.title",L"Настройки мыши",L"Mouse Settings");case INTERFACE:return tr("menu.darkwinter.interface",L"Интерфейс",L"Interface");case ACCESSIBILITY:return tr("options.accessibility.title",L"Настройки доступности",L"Accessibility Settings");case FONT_OPTIONS:return tr("options.font.title",L"Настройки шрифта",L"Font Settings");case SKIN:return tr("options.skinCustomisation.title",L"Настройки внешнего вида",L"Skin Customization");case CHAT:return tr("options.chat.title",L"Настройки чата",L"Chat Settings");case ONLINE:return tr("options.online.title",L"Настройки онлайн-игры",L"Online Options");default:return tr("options.title",L"Настройки",L"Options");}};
 int cx=settingsFrame(title()),cw=std::max(84,w-cx-std::max(12,std::min(34,w/22))-6),x=cx+6,ow=std::max(72,cw-12),viewH=std::max(40,h-120);
 if(page==SETTINGS){
  struct Link{int id,target;const char* key;const wchar_t* ru;const wchar_t* en;bool enabled;};
  Link links[]={{602,ONLINE,"options.online",L"Онлайн...",L"Online...",true},{603,SKIN,"options.skinCustomisation",L"Внешний вид...",L"Skin Customization...",true},{604,CHAT,"options.chat",L"Настройки чата...",L"Chat Settings...",true},{605,RESOURCES,"options.resourcepack",L"Наборы ресурсов...",L"Resource Packs...",true},{606,TELEMETRY,"options.telemetry",L"Собираемые данные...",L"Telemetry Data...",false},{607,CREDITS,"options.credits_and_attribution",L"Титры и атрибуции...",L"Credits & Attribution...",true}};
  setScrollRegion(cx,70,cw,std::max(20,h-118),7*26);setClip(cx,70,cw,std::max(20,h-118));int y=70-optionScroll;addOption(107,cx,y,cw,21);y+=26;
  for(const auto& row:links){addPage(row.id,cx,y,cw,21,tr(row.key,row.ru,row.en),row.target,row.enabled);y+=26;}resetClip();
  add(691,cx+cw-42,50,20,18,L"↑",optionScroll>0);add(692,cx+cw-20,50,20,18,L"↓",optionScroll<scrollMaximum);settingsFooter();return;
 }
 if(page==INTERFACE){int step=h<260?26:30;for(int i=0;i<4;i++){int ids[]={133,135,136,134};addOption(ids[i],cx,72+i*step,cw-16,22);}if(h>=300)wrapped(cx+8,72+step*3+38,cw-16,8.8f,tr("menu.darkwinter.fx.note",L"Эти параметры меняют оформление меню и интерфейса. Игровые настройки сохраняются отдельно.",L"These settings change the presentation of menus and the interface."),color(0x93aebb),3);settingsFooter();return;}
 struct Row{int a=0,b=0;std::wstring heading;int height=25;int targetA=-1,targetB=-1;std::wstring labelA,labelB;};std::vector<Row> rows;
 auto option=[&](int a,int b=0){Row r;r.a=a;r.b=b;r.height=ow<300&&b?44:25;rows.push_back(std::move(r));};
 auto heading=[&](std::wstring label,bool first=false){Row r;r.heading=std::move(label);r.height=first?19:37;rows.push_back(std::move(r));};
 auto link=[&](int a,int targetA,std::wstring labelA,int b=0,int targetB=-1,std::wstring labelB=L""){Row r;r.a=a;r.b=b;r.targetA=targetA;r.targetB=targetB;r.labelA=std::move(labelA);r.labelB=std::move(labelB);r.height=ow<300&&b?44:25;rows.push_back(std::move(r));};
 if(page==VIDEO){heading(tr("options.video.display.header",L"Экран",L"Display"),true);option(20000);option(105,102);option(109,104);option(101,20001);option(106,150);heading(tr("options.video.quality.header",L"Качество и производительность",L"Quality and Performance"));option(20002);option(20003,108);option(20004,20005);option(115,114);option(20006,20007);option(110,20008);option(20009,20010);option(20011,20012);option(20013,20014);option(20015);option(20016);heading(tr("options.video.preferences.header",L"Параметры",L"Preferences"));option(20017,20018);option(20019,20020);}
 else if(page==AUDIO){option(122);for(int id=20030;id<=20038;id+=2)option(id,id+1);option(20040);option(20041,20042);option(20043,20044);}
 else if(page==CONTROLS){link(600,MOUSE,tr("options.mouse_settings",L"Настройки мыши...",L"Mouse Settings..."),601,KEYBINDS,tr("controls.keybinds",L"Назначение клавиш...",L"Key Binds..."));option(20050,20051);option(20052,20053);option(20054,20055);option(20056,20057);}
 else if(page==MOUSE){option(111,20060);option(20061,20062);option(20063,20064);}
 else if(page==ACCESSIBILITY){Row first;first.a=20070;first.b=608;first.targetB=CONTROLS;first.labelB=tr("options.controls",L"Управление...",L"Controls...");first.height=ow<300?44:25;rows.push_back(std::move(first));int ids[]={20041,20071,20009,20072,20073,20074,20075,20076,20077,20078,20079,20080,20081,20082,20083,20084,20085,20086,20087,20088,20089,20090,20091};for(size_t i=0;i<std::size(ids);i+=2)option(ids[i],i+1<std::size(ids)?ids[i+1]:0);}
 else if(page==FONT_OPTIONS)option(20100,20101);
 else if(page==SKIN){option(20110,20111);option(20112,20113);option(20114,20115);option(20116,20117);}
 else if(page==CHAT){option(20120,20121);option(20122,20123);option(20074,20072);option(20124,20075);option(20076,20125);option(20126,20127);option(20070,20128);option(20131,20129);option(20130,20132);}
 else if(page==ONLINE){heading(tr("options.online.friends.header",L"Список друзей",L"Friends List"),true);option(20142,20143);option(20144,20145);link(636,XBOX_PRIVACY,tr("options.online.xboxSettings",L"Настройки Xbox...",L"Xbox Settings..."));heading(tr("options.online.servers.header",L"Серверы",L"Servers"));option(20140);heading(tr("options.online.realms.header",L"Realms",L"Realms"));option(20141);}
 int total=4;for(const auto& row:rows)total+=row.height;setScrollRegion(cx-4,72,cw+8,viewH,total);setClip(cx-4,72,cw+8,viewH);int y=74-optionScroll;
 auto draw=[&](int id,int target,const std::wstring& label,int px,int py,int pw){if(!id)return;if(target>=0)addPage(id,px,py,pw,20,label,target);else addOption(id,px,py,pw,20);};
 for(const auto& row:rows){if(!row.heading.empty()){int ty=y+(row.height==19?2:20);text(x+(ow-measure(row.heading,11))/2.f,ty,11,row.heading,color(0xdce8eb));}else if(row.b&&ow>=300){int half=(ow-8)/2;draw(row.a,row.targetA,row.labelA,x,y+2,half);draw(row.b,row.targetB,row.labelB,x+half+8,y+2,half);}else{draw(row.a,row.targetA,row.labelA,x,y+2,ow);if(row.b)draw(row.b,row.targetB,row.labelB,x,y+24,ow);}y+=row.height;}
 resetClip();scrollBar();
 if(page==ACCESSIBILITY){int bw=std::min(170,std::max(40,(cw-8)/2)),bx=cx+cw-(2*bw+8);add(609,bx,h-28,bw,20,tr("options.accessibility.link",L"Руководство по доступности",L"Accessibility Guide"));add(0,bx+bw+8,h-28,bw,20,tr("gui.done",L"Готово",L"Done"));}
 else settingsFooter();
}
void Ui::renderLanguages() {
 int x=settingsFrame(tr("options.language.title",L"Язык",L"Language")),left=std::max(12,std::min(34,w/22)),cw=std::max(84,w-x-left-6);
 // Same source font is used for the search, row names, codes, caret and selection.
 editField(402,x+20,72,cw-20,22,tr("gui.language.search",L"Поиск...",L"Search..."));
 int cx=x+7,cy=83;for(int dy=-4;dy<=4;dy++)for(int dx=-4;dx<=4;dx++){float d=std::hypot(float(dx),float(dy));if(d>2.8f&&d<4.1f)rect(cx+dx,cy+dy,1,1,color(0x93aebb));}for(int i=0;i<4;i++)rect(cx+3+i,cy+3+i,1,1,color(0x93aebb));
 text(x+5,100,7.15f,tr("menu.darkwinter.language.available",L"ДОСТУПНЫЕ ЯЗЫКИ",L"AVAILABLE LANGUAGES"),color(0x93aebb));
 std::wstring filter=lowerUnicode(fieldValue(402));std::vector<int> filtered;
 for(size_t i=0;i<languages.size();i++){auto text=lowerUnicode(languages[i].name+L" "+languages[i].region);if(filter.empty()||text.find(filter)!=std::wstring::npos)filtered.push_back(int(i));}
 auto count=std::to_wstring(filtered.size());text(x+cw-5-measure(count,7.15f),100,7.15f,count,color(0x93aebb));
 int height=std::max(30,((h-177)/26)*26+4);scrollStep=26;setScrollRegion(x,111,cw,height,int(filtered.size())*26+4);optionScroll=optionScroll/26*26;
 rect(x,111,cw,height,color(0x040c14,38));rect(x,111,cw,1,color(0x93aebb,59));rect(x,111+height-1,cw,1,color(0x93aebb,59));setClip(x,111,cw,height);
 int rowX=x+2,rowW=cw-14;
 for(size_t n=0;n<filtered.size();n++){int i=filtered[n],y=113+int(n)*26-optionScroll;if(y+26<111||y>111+height)continue;const auto& language=languages[size_t(i)];bool selected=selectedLanguage==language.code;float light=hover==10000+i||focus==10000+i?1.f:0.f;
  auto label=language.name+L" ("+language.region+L")";size_t before=buttons.size();registerButton(10000+i,rowX,y,rowW,26,label);if(before==buttons.size())continue;buttons.back().kind=3;buttons.back().tooltip=label;
  if(selected||light>0)surface(rowX,y,rowW,26,selected?6:9,light,color(0xa9dae7));
  if(selected){for(int d=0;d<3;d++)rect(rowX+7+d,y+12+d,2,1,color(0xa9dae7));for(int d=0;d<6;d++)rect(rowX+10+d,y+14-d,2,1,color(0xa9dae7));}else rect(rowX+11,y+12,2,2,color(0x6b8998,71));
  text(rowX+26,y+4,10.23f,fitted(label,10.23f,rowW-32),selected?color(0xdce8eb):color(0xbed0d8));text(rowX+26,y+16,6.6f,uiWide(language.code),color(0x93aebb));
 }
 if(filtered.empty())text(x+8,121,11,tr("menu.darkwinter.language.empty",L"Языки не найдены",L"No languages found"),color(0x93aebb));resetClip();scrollBar();
 wrapped(x+4,h-55,cw-8,8.25f,tr("options.languageAccuracyWarning",L"(Переводы могут быть неточными)",L"(Language translations may not be 100% accurate)"),color(0x93aebb),1);
 int bw=std::min(160,std::max(40,(cw-8)/2)),bx=x+cw-(bw*2+8);addPage(610,bx,h-28,bw,20,tr("options.font",L"Настройки шрифта...",L"Font Settings..."),FONT_OPTIONS);add(0,bx+bw+8,h-28,bw,20,tr("gui.done",L"Готово",L"Done"));
}
void Ui::renderBindings() {
 int cx=settingsFrame(tr("controls.keybinds.title",L"Назначение клавиш",L"Key Binds")),left=std::max(12,std::min(34,w/22)),cw=std::max(84,w-cx-left-6),ow=cw-12;bool compact=ow<250;
 int listX=cx-4,listW=cw+8,rowW=std::min(340,listW-32),x=listX+(listW-rowW)/2,scrollbarX=x+rowW+8;
 std::vector<size_t> order;std::vector<std::wstring> names;for(size_t i=0;i<keyBindings.size();i++){order.push_back(i);names.push_back(tr("key."+keyBindings[i].key,keyBindings[i].name));}
 std::stable_sort(order.begin(),order.end(),[&](size_t a,size_t b){const auto& ka=keyBindings[a];const auto& kb=keyBindings[b];if(ka.categoryOrder!=kb.categoryOrder)return ka.categoryOrder<kb.categoryOrder;if(ka.order!=kb.order)return ka.order<kb.order;return names[a]<names[b];});
 int total=4,category=-1;for(size_t i:order){const auto& b=keyBindings[i];if(category!=b.categoryOrder){total+=20;category=b.categoryOrder;}total+=compact?38:20;}
 setScrollRegion(listX,72,listW,std::max(40,h-120),total);setClip(listX,72,listW,std::max(40,h-120));int y=74-optionScroll;category=-1;
 for(size_t i:order){const auto& binding=keyBindings[i];if(category!=binding.categoryOrder){category=binding.categoryOrder;const auto caption=tr(binding.categoryKey,binding.category);text(cx+(cw-measure(caption,11))/2.f,y+1,11,caption,color(0xdce8eb));y+=20;}
  unsigned key=unsigned(settings.option("key."+binding.key,binding.initialKey));int resetW=50,buttonW=75,resetX=scrollbarX-resetW-10,buttonX=resetX-5-buttonW,by=y;
  if(compact){resetW=std::min(50,std::max(1,rowW-9));buttonW=std::max(1,rowW-resetW-8);buttonX=x+2;resetX=buttonX+buttonW+8;by=y+16;text(x+2,y+2,11,fitted(names[i],11,rowW),color(0xdce8eb));}
  else text(x+2,y+6,11,fitted(names[i],11,std::max(20,buttonX-x-8)),color(0xdce8eb));
  std::wstring collisions;for(size_t j=0;key&&j<keyBindings.size();j++)if(j!=i){const auto& other=keyBindings[j];unsigned otherKey=unsigned(settings.option("key."+other.key,other.initialKey));if(key==otherKey&&(key!=binding.defaultKey||otherKey!=other.defaultKey)){if(!collisions.empty())collisions+=L", ";collisions+=names[j];}}
  auto caption=keyName(*this,key,binding);if(bindingCapture==int(i))caption=L"> "+caption+L" <";
  size_t before=buttons.size();add(12000+int(i),buttonX,by,buttonW,18,caption);if(buttons.size()!=before){buttons.back().optionKey="key."+binding.key;if(!collisions.empty()){auto help=tr("controls.keybinds.duplicateKeybinds",L"Назначено также: %s",L"Also assigned to: %s");auto at=help.find(L"%s");if(at!=std::wstring::npos)help.replace(at,2,collisions);buttons.back().tooltip=help;rect(buttonX-6,by+1,3,18,color(0xffff00));}}
  add(13000+int(i),resetX,by,resetW,18,tr("controls.reset",L"Сброс",L"Reset"),key!=binding.defaultKey);y+=compact?38:20;
 }
 resetClip();scrollBar(scrollbarX);bool modified=false;for(const auto& b:keyBindings)modified|=unsigned(settings.option("key."+b.key,b.initialKey))!=b.defaultKey;
 int bw=std::min(170,std::max(40,(cw-8)/2)),bx=cx+cw-(bw*2+8);add(680,bx,h-28,bw,20,tr("controls.resetAll",L"Сбросить всё",L"Reset Keys"),modified);add(0,bx+bw+8,h-28,bw,20,tr("gui.done",L"Готово",L"Done"));
}
void Ui::renderConnections() {
 if(!activeWorld)backdrop(0);int panelWidth=activeWorld?std::min(380,std::max(224,w/2)):std::min(560,w-24);panelWidth=std::min(panelWidth,w-16);int panelLeft=w-panelWidth-8,panelBottom=h-(activeWorld?34:8),x=panelLeft+8,cw=panelWidth-16,half=(panelWidth-20)/2;
 surface(panelLeft,6,panelWidth,panelBottom-6,3,0,color(0x7897a7));rect(panelLeft+2,6,std::min(16,panelWidth-4),1,color(0xc2d9e1,166));
 auto t=[&](const char* key,const wchar_t* ru,const wchar_t* en){return tr(std::string("menu.darkwinter.connections.")+key,ru,en);};
 auto heading=page==CONNECTIONS?t("heading_list",L"СОЕДИНЕНИЯ",L"CONNECTIONS"):page==CONNECTION_CREATE?t("heading_create",L"СОЗДАТЬ СОЕДИНЕНИЕ",L"CREATE CONNECTION"):t("heading_join",L"ПРИСОЕДИНИТЬСЯ",L"JOIN CONNECTION");
 text(x,13,9.24f,fitted(heading,9.24f,cw),color(0xdce8eb));
 if(page==CONNECTIONS){text(x,33,7.04f,t("saved_count",L"Сохранённых соединений: ",L"Saved connections: ")+L"0",color(0x93aebb));addPage(620,x,48,half,18,t("create",L"Создать",L"Create"),CONNECTION_CREATE);addPage(621,x+half+4,48,half,18,t("join",L"Присоединиться",L"Join"),CONNECTION_JOIN);wrapped(x,76,cw,7.7f,t("no_connections",L"Сохранённых соединений пока нет.",L"No saved connections yet."),color(0x93aebb),2);wrapped(x,88,cw,7.7f,t("get_credentials",L"Создайте соединение или узнайте у другого участника его имя и пароль.",L"Create a connection or ask another participant for its name and password."),color(0x93aebb),3);}
 else {addPage(625,x,48,cw,18,t("back_connections",L"‹ Соединения",L"‹ Connections"),CONNECTIONS);text(x,76,7.7f,t("name",L"Имя соединения",L"Connection name"),color(0xdce8eb));editField(450,x,88,cw,18);text(x,107,7.7f,page==CONNECTION_CREATE?t("password",L"Пароль",L"Password"):t("optional_password",L"Пароль (если требуется)",L"Password (if required)"),color(0xdce8eb));editField(451,x,119,cw,18);auto name=fieldValue(450);auto first=name.find_first_not_of(L" \t\r\n"),last=name.find_last_not_of(L" \t\r\n");bool valid=first!=std::wstring::npos&&last-first+1<=48;int id=page==CONNECTION_CREATE?622:623;add(id,x,138,cw,18,page==CONNECTION_CREATE?t("create_connection",L"Создать соединение",L"Create connection"):t("join_connection",L"Присоединиться к соединению",L"Join connection"),valid);if(!valid)buttons.back().tooltip=t("valid_name",L"Введите имя: от 1 до 48 символов.",L"Enter a name from 1 to 48 characters.");}
 if(!status.empty())text(x,panelBottom-40,6.6f,fitted(status,6.6f,cw),color(0x93aebb));add(0,x,panelBottom-23,half,18,activeWorld?t("return_world",L"Вернуться в мир",L"Return to world"):t("back",L"Назад",L"Back"));add(624,x+half+4,panelBottom-23,half,18,t("refresh",L"Обновить",L"Refresh"));
}
void Ui::renderSimplePage() {
 if(page==FRIENDS_CONFIRM){backdrop(0);auto title=tr("options.friendsList.confirm.title",L"Список друзей",L"Friends List"),link=tr("options.friendsList.confirm.message.link",L"учётной записью Microsoft",L"Microsoft account");auto message=uiFormat(tr("options.friendsList.confirm.message",L"Используйте список друзей для отправки и получения запросов в друзья.\n\nНастройте безопасность в сети с помощью %s.",L"Use your Friends List to send and receive friend requests.\n\nManage online safety settings with your %s."),{link});
  int maxWidth=std::max(140,w-180);std::vector<std::wstring> lines;std::wistringstream paragraphs(message);std::wstring paragraph;while(std::getline(paragraphs,paragraph)){if(paragraph.empty()){lines.emplace_back();continue;}std::wistringstream words(paragraph);std::wstring word,line;while(words>>word){auto candidate=line.empty()?word:line+L" "+word;if(!line.empty()&&measure(candidate,11)>maxWidth){lines.push_back(line);line=word;}else line=std::move(candidate);}if(!line.empty())lines.push_back(line);}if(lines.size()>15)lines.resize(15);int bodyWidth=0;for(const auto& line:lines)bodyWidth=std::max(bodyWidth,measure(line,11));int width=std::max(304,bodyWidth+20),height=71+int(lines.size())*9,x=(w-width)/2,y=(h-height)/2;
  surface(x-18,y-18,width+37,height+37,3,0,color(0x7897a7));text((w-measure(title,11))/2.f,y,11,title,color(0xdce8eb));rect((w-measure(title,11))/2,y+10,measure(title,11),1,color(0xdce8eb));
  for(size_t i=0;i<lines.size();i++){const auto& line=lines[i];int tx=(w-measure(line,11))/2,ty=y+22+int(i)*9;auto at=line.find(link);if(at==std::wstring::npos)text(tx,ty,11,line,color(0xdce8eb));else{auto before=line.substr(0,at),after=line.substr(at+link.size());int lx=tx+measure(before,11),lw=measure(link,11);text(tx,ty,11,before,color(0xdce8eb));text(lx,ty,11,link,color(0x5555ff));rect(lx,ty+10,lw,1,color(0x5555ff));text(lx+lw,ty,11,after,color(0xdce8eb));registerButton(636,lx,ty,lw,11,link,true,XBOX_PRIVACY);}}
  int by=y+height-20;add(637,w/2-152,by,150,20,tr("options.friendsList.confirm.turnOn",L"Включить",L"Turn ON"));add(638,w/2+2,by,150,20,tr("options.friendsList.confirm.turnOff",L"Выключить",L"Turn OFF"));return;
 }
 if(page==ATTRIBUTIONS||page==LICENSES||page==GUIDE||page==XBOX_PRIVACY){backdrop(0);int titleY=h/2-35;centered(titleY,11,tr("chat.link.confirmTrusted",L"Открыть или скопировать ссылку в буфер обмена?",L"Open this link or copy it to the clipboard?"),color(0xdce8eb));auto destination=textFields[460];if(destination.empty())destination=page==XBOX_PRIVACY?L"https://aka.ms/MinecraftJavaXboxPrivacyAndSafety":page==ATTRIBUTIONS?L"attribution.html":page==LICENSES?L"licenses.html":L"accessibility.html";int width=std::max(60,w-40),y=titleY+17;auto shown=fitted(destination,11,width);text((w-measure(shown,11))/2.f,y,11,shown,color(0xa9dae7));int bx=w/2-154,by=h/2+15;add(634,bx,by,100,20,tr("chat.link.open",L"Открыть",L"Open"));add(635,bx+104,by,100,20,tr("chat.copy",L"Копировать",L"Copy"));add(0,bx+208,by,100,20,tr("gui.cancel",L"Отмена",L"Cancel"));return;}
 if(page==CREDITS){backdrop(0);centered(12,11,tr("credits_and_attribution.screen.title",L"Титры и атрибуции",L"Credits and Attribution"),color(0xdce8eb));int x=w/2-105;addPage(630,x,63,210,20,tr("credits_and_attribution.button.credits",L"Титры",L"Credits"),CREDITS_SCROLL);addPage(631,x,91,210,20,tr("credits_and_attribution.button.attribution",L"Атрибуции",L"Attribution"),ATTRIBUTIONS);addPage(632,x,119,210,20,tr("credits_and_attribution.button.licenses",L"Лицензии",L"Licenses"),LICENSES);add(0,w/2-100,h-26,200,20,tr("gui.done",L"Готово",L"Done"));return;}
 if(page==RESOURCES){backdrop(0);centered(4,11,tr("resourcePack.title",L"Выбор наборов ресурсов",L"Select Resource Packs"),color(0xdce8eb));centered(17,11,tr("pack.dropInfo",L"Перетащите файлы в это окно, чтобы добавить наборы",L"Drag and drop files into this window to add packs"),color(0x93aebb));editField(453,w/2-101,30,200,15,tr("gui.recipebook.search_hint",L"Поиск...",L"Search..."));int left=w/2-215,right=w/2+15,width=200,height=std::max(40,h-82);for(int x:{left,right}){rect(x,49,width,height,color(0x040c14,38));rect(x,49,width,1,color(0x93aebb,59));rect(x,48+height,width,1,color(0x93aebb,59));}text(left+2,51,11,tr("pack.available.title",L"Доступные",L"Available"),color(0xdce8eb));text(right+2,51,11,tr("pack.selected.title",L"Выбранные",L"Selected"),color(0xdce8eb));
  auto filter=lowerUnicode(fieldValue(453));if(filter.empty()||std::wstring(L"darkwinter").find(filter)!=std::wstring::npos){picture(2,right+5,78,32,4,false,color(0xdce8eb));text(right+39,66,11,L"DarkWinter",color(0xdce8eb));wrapped(right+39,79,153,9,tr("menu.darkwinter.resources.owned",L"Ресурсы DarkWinter",L"DarkWinter resources"),color(0x93aebb),2);}add(633,w/2-154,h-26,150,20,tr("pack.openFolder",L"Папка с наборами",L"Open Pack Folder"));add(0,w/2+4,h-26,150,20,tr("gui.done",L"Готово",L"Done"));return;
 }
 if(page==CREDITS_SCROLL){backdrop(0);rect(0,0,w,h,color(0x040d16,180));int content=std::max(500,h+280);setScrollRegion(0,0,w,h,content);scrollStep=18;int top=h-80-optionScroll;picture(2,w/2-160,top,320,40);text(w/2-160,top+80,11,L"DARKWINTER",color(0xdce8eb));wrapped(w/2-160,top+110,320,11,tr("menu.darkwinter.credits.project",L"Северная Канада · 1997\nОдиночное зимнее выживание и исследование",L"Northern Canada · 1997\nSingle-player winter survival and exploration"),color(0x93aebb),8);wrapped(w/2-160,top+190,320,11,L"Roboto Condensed — Google / Christian Robertson\nSIL Open Font License 1.1\n\nVulkan Headers — Khronos Group\nApache License 2.0",color(0x93aebb),12);return;}
 int x=settingsFrame(page==TELEMETRY?tr("options.telemetry",L"Собираемые данные",L"Telemetry Data"):L"DarkWinter"),left=std::max(12,std::min(34,w/22)),cw=std::max(84,w-x-left-6);
 if(page==TELEMETRY)wrapped(x+8,82,cw-16,11,tr("menu.darkwinter.telemetry.unavailable",L"Сбор данных отключён.",L"Data collection is disabled."),color(0x93aebb));else{picture(2,x+8,82,std::min(320,cw-16),std::min(320,cw-16)/8);wrapped(x+8,142,cw-16,11,tr("menu.darkwinter.credits.project",L"Северная Канада · 1997\nОдиночное зимнее выживание и исследование",L"Northern Canada · 1997\nSingle-player winter survival and exploration"),color(0x93aebb));}settingsFooter();
}
void Ui::render(const Scene* scene,double seconds) {
 const double daySeconds=worldDaySeconds<0?seconds:worldDaySeconds;
 uint64_t now=uint64_t(std::max(0.,daySeconds)*18)/60;if(page==GAME&&(now!=minute||weatherTick!=uint64_t(seconds)))dirty=true;if(!dirty)return;
 minute=now;weatherTick=uint64_t(std::max(0.,seconds));dirty=false;buttons.clear();clipStack.clear();clip={0,0,rasterW,rasterH};scrollWidth=scrollHeight=scrollMaximum=0;
 // Pause already contains the source menu effects; do not tint its text again in the world shader.
 std::fill(pixels.begin(),pixels.end(),0);pixels[0]=uint32_t(rasterW);pixels[1]=uint32_t(rasterH);pixels[2]=settings.animated&&page!=GAME&&page!=PAUSE?1:0;
 const auto ink=color(0xdce8eb),muted=color(0x93aebb),line=color(0x537384,144);float unit=1;
 int left=std::clamp(int(std::round(w*.075)),20,60),rowW=std::clamp(int(std::round(w*.32)),140,214),step=h<280?25:31;
 #if __has_include("ui_world_pages.hpp")
 if(UiWorldPages::render(*this,scene,seconds)){tooltip();return;}
 #endif
 if(page==TITLE) {
  backdrop(0);
  int y=std::max(65,std::min(int(std::round(h*.345f)),h-5*step-42)),mark=std::min(w-left-22,int(std::round(w*.465f))),markY=std::max(8,std::min(std::max(14,int(std::round(h*.125f))),y-mark/8-22));
  picture(2,left,markY,mark,mark/8);text(left,markY+mark/8+8,8.14f,tr("menu.darkwinter.location_year",L"СЕВЕРНАЯ КАНАДА / 1997",L"NORTHERN CANADA / 1997"),muted);
  add(10,left,y,rowW,24,tr("menu.darkwinter.play",L"Играть",L"Play"));
  addPage(41,left,y+step,rowW,24,tr("menu.darkwinter.connections.short",L"Соединения",L"Connections"),CONNECTIONS);
  addPage(20,left,y+step*2,rowW,24,tr("menu.options",L"Настройки...",L"Options..."),SETTINGS);
  addPage(30,left,y+step*3,rowW,24,tr("menu.darkwinter.interface",L"Интерфейс",L"Interface"),INTERFACE);
  add(90,left,y+step*4,rowW,24,tr("menu.quit",L"Выйти из игры",L"Quit Game"));
  int utilityY=y+step*4+24+13;
  for(int i=0;i<2;i++){int x=left+i*24,id=i?26:27;auto label=i?tr("options.accessibility",L"Специальные возможности...",L"Accessibility Settings..."):tr("options.language",L"Язык...",L"Language...");registerButton(id,x,utilityY,20,20,label,true,i?ACCESSIBILITY:LANGUAGE);buttons.back().tooltip=label;surface(x,utilityY,20,20,0,lights.contains(id)?lights[id]:0,color(0xa9dae7));picture(i?20:21,x+2,utilityY+2,16,16,false,ink);}
  if(w>=580){const wchar_t* words[]={L"СЕВЕР",L"ВЫЖИВАНИЕ",L"ОДИНОЧЕСТВО",L"1997"};for(int i=0;i<4;i++)text(w-80,24+i*10,6.27f,words[i],color(0x93aebb,150));}
  text(left,h-13,8.8f,L"DARKWINTER",muted);
  auto credits=tr("menu.darkwinter.credits.short",L"Титры и атрибуции",L"Credits & Attribution");registerButton(607,w-194,h-13,134,9,credits,true,CREDITS);text(w-194,h-13,8.8f,fitted(credits,8.8f,134),muted);
 } else if(page==PAUSE) {
  if(settings.pauseArt){
#if __has_include("ui_world_pages.hpp")
   UiWorldPages::cover(*this,1,0,0,w,h,.65f,.5f,1,0xffffffffu);
#else
   picture(1,0,0,w,h,true);
#endif
   gradient(0,0,w,h,color(0x081420,37),color(0x02070d,54));
  }else gradient(0,0,w,h,color(0x091420,66),color(0x030912,121));
  for(int i=0;i<20;i++){int l=std::max(0,left-18)+i*(rowW+75)/20,r=std::max(0,left-18)+(i+1)*(rowW+75)/20;float f=1-i/20.f;rect(l,0,r-l,h,color(0x030a13,uint32_t(std::round(203*f*f))));}
  int y=std::max(52,std::min(int(std::round(h*.225f)),h-184));
  rect(left-8,y-8,1,std::max(0,std::min(h-16,y+174)-(y-8)),color(0x537384,112));
  int logoW=std::min(rowW-12,218);picture(2,left+12,std::max(16,int(std::round(h*.085f))),logoW,logoW/8);
  analog();rect(3,3,w-6,1,color(0x537384,80));rect(3,h-4,w-6,1,color(0x537384,64));
  add(1,left,y,rowW,24,tr("menu.returnToGame",L"Вернуться к игре",L"Back to Game"));
  addPage(3,left,y+27,rowW,24,tr("menu.options",L"Настройки...",L"Options..."),SETTINGS);
  addPage(7,left,y+54,rowW,24,tr("menu.darkwinter.interface",L"Интерфейс",L"Interface"),INTERFACE);
  addPage(5,left,y+81,rowW,24,tr("gui.stats",L"Статистика",L"Statistics"),STATS);
  addPage(6,left,y+108,rowW,24,tr("options.worldOptions.button",L"Настройки мира...",L"World Settings..."),WORLD_OPTIONS);
  add(4,left,y+135,rowW,24,tr("menu.returnToMenu",L"Сохранить и выйти",L"Save and Quit to Title"));
 } else if(page==GAME) {

  if(!settings.hud)return;
  NativeClimate climate=nativeClimate(worldSeed,seconds,weatherOverride,daySeconds);int hx=int(15*unit),hy=int(15*unit),hw=int(176*unit),hh=int(76*unit);gradient(hx,hy,hw,hh,color(0x12202b,100),color(0x07111b,144));rect(hx,hy,hw,std::max(1,int(unit)),color(0x5b7687,86));rect(hx,hy,std::max(1,int(unit)),hh,color(0x698493,119));rect(hx+hw-1,hy,1,hh,color(0x537384,64));rect(hx,hy+hh-1,hw,1,color(0x537384,69));
  uint64_t mins=360+now;std::wostringstream clock,date;clock<<std::setfill(L'0')<<std::setw(2)<<(mins/60)%24<<L":"<<std::setw(2)<<mins%60;auto calendar=std::chrono::year_month_day{std::chrono::sys_days{std::chrono::year{1997}/10/8}+std::chrono::days{mins/1440}};date<<std::setfill(L'0')<<std::setw(2)<<unsigned(calendar.day())<<L"."<<std::setw(2)<<unsigned(calendar.month())<<L"."<<int(calendar.year());text(hx+int(7*unit),hy+int(5*unit),int(12.87f*unit),clock.str(),ink);text(hx+int(62*unit),hy+int(8*unit),int(7.92f*unit),date.str(),muted);rect(hx+int(7*unit),hy+int(21*unit),hw-int(14*unit),1,color(0x5b7687,85));
  const wchar_t* weather[]={L"Пасмурно",L"Ясно",L"Небольшая облачность",L"Ветрено",L"Слабый снег",L"Снег без ветра",L"Метель",L"Сильная метель",L"Позёмки"};auto decimal=[](float value){std::wostringstream out;out<<std::fixed<<std::setprecision(1)<<value;return out.str();};int tx=hx+int(7*unit);text(tx,hy+int(26*unit),int(8.91f*unit),weather[climate.preset],ink);text(tx,hy+int(41*unit),int(8.47f*unit),L"Воздух "+decimal(climate.temperature)+L"°C",color(0xd0dee4));text(tx,hy+int(51*unit),int(8.03f*unit),L"Ощущается "+decimal(climate.chill())+L"°C",muted);
  int cx=hx+hw-int(22*unit),cy=hy+int(45*unit),compass=int(32*unit);picture(32,cx-compass/2,cy-compass/2,compass,compass,false,color(0xaccedd,199));text(cx-int(2*unit),cy-int(21*unit),int(6.6f*unit),L"С",muted);if(climate.wind>=.3f)rotated(28,cx,cy,int(18*unit),climate.bearing*.01745329252f,color(0x93aebb,230));else rect(cx-1,cy-1,3,3,muted);
  rect(tx,hy+int(63*unit),hw-int(14*unit),1,color(0x537384,80));const wchar_t* directions[]={L"С",L"СВ",L"В",L"ЮВ",L"Ю",L"ЮЗ",L"З",L"СЗ"};std::wstring wind=climate.wind<.3f?L"Штиль":L"Ветер: "+std::wstring(directions[int(std::floor((climate.bearing+22.5f)/45))%8])+L", "+decimal(climate.wind)+L" м/с";text(tx,hy+int(68*unit),int(8.36f*unit),wind,muted);
  if(walking){float values[]={needs.health/100,stamina/150,needs.satiety/100,needs.temperature/100,needs.thirst/100,1-needs.fatigue/100};int x=int(15*unit),y=h-int(69*unit),width=int(std::clamp(w/unit*.43f,108.f,192.f)*unit),bx=x+int(18*unit),len=width-int(20*unit);float step=len/4.f;int size=int(std::clamp(std::round(std::min(step/unit-2,30.f)),24.f,31.f)*unit);
   for(int i=1;i<=5;i++){int cx=int(std::round(bx+(i-1)*step)),cy=y+int(18*unit);float v=values[i];uint32_t tint=color(v<=(i==1?.15f:.2f)?0xd05a60:v<=.35f?0xd09a59:(i==1||i==4)?0xb9deec:i==3?0xd8e8eb:0xcfdcd9);ring(cx,cy,size,v,tint);int is=int(std::min(18.f,std::max(10.f,size/unit-5))*unit);picture(22+i,cx-is/2,cy-is/2,is,is,false,(tint&0xffffff)|(214u<<24));}
   int heart=int(12*unit),outer=y+int(41*unit),inner=y+int(42*unit);icon(0,x,outer+int(4*unit)-heart/2,heart);rect(bx-int(2*unit),outer,len+int(4*unit),int(8*unit),color(0x3f5c6c,130));rect(bx-int(unit),inner,len+int(2*unit),int(6*unit),color(0x020913,204));int fill=int(len*values[0]);gradient(bx,inner+int(unit),fill,int(2*unit),color(0xd9616b),color(0xd34d58));gradient(bx,inner+int(3*unit),fill,int(2*unit),color(0xd34d58),color(0x481f2d));for(int dx=int(2*unit);dx<fill-int(3*unit);dx+=std::max(1,int(5*unit)))for(int row=0;row<4;row++)rect(bx+dx+int(row*unit),inner+int((4-row)*unit),std::max(1,int(unit)),std::max(1,int(unit)),color(0x080d17,116));
  }
  rect(w/2-1,h/2-9,2,18,color(0xdce8eb,185));rect(w/2-9,h/2-1,18,2,color(0xdce8eb,185));
  if(settings.hints){rect(w/2-310,h-46,620,28,color(0x12202b,150));text(w/2-296,h-40,15,L"WASD · Shift · Space · F5: камера · V: плечо · F6: сохранить · Esc: пауза",muted);}
 } else if(page==SETTINGS||page==VIDEO||page==AUDIO||page==CONTROLS||page==INTERFACE||page==MOUSE||page==ACCESSIBILITY||page==FONT_OPTIONS||page==SKIN||page==CHAT||page==ONLINE)renderSettings();
 else if(page==LANGUAGE)renderLanguages();
 else if(page==KEYBINDS)renderBindings();
 else if(page==CONNECTIONS||page==CONNECTION_CREATE||page==CONNECTION_JOIN)renderConnections();
 else if(page==CREDITS||page==ATTRIBUTIONS||page==LICENSES||page==RESOURCES||page==TELEMETRY||page==CREDITS_SCROLL||page==GUIDE||page==XBOX_PRIVACY||page==FRIENDS_CONFIRM||page==ABOUT)renderSimplePage();
 else if(page==LOADING){
  // NorthernLoadingArt uses its own layer order; a generic menu backdrop changes its tint.
  const std::array<int,4> viewport{w,h,rasterW,rasterH};
  if(loadingCanvas!=viewport){loadingCanvas=viewport;loadingAurora={};loadingReadingShadow={};}
#if __has_include("ui_world_pages.hpp")
  UiWorldPages::cover(*this,6,0,0,w,h,.54f,.5f,1,0xffffffffu);
#else
  picture(6,0,0,w,h,true);
#endif
  gradient(0,0,w,h*2/3,color(0x020815,82),color(0x020815,8));
  if(art.size()>7){
   // Cache the untinted crop: changing aurora opacity must not resample a full-screen image.
   if(loadingAurora.pixels.empty()){
    const auto& source=art[7];const double ratio=double(w)/h;
    const int sw=std::max(1,int(std::lround(std::min(double(source.w),source.h*ratio))));
    const int sh=std::max(1,int(std::lround(std::min(double(source.h),source.w/ratio))));
    const int sx=int(std::lround((source.w-sw)*.54f)),sy=int(std::lround((source.h-sh)*.5f));
    loadingAurora.w=uint32_t(rasterW);loadingAurora.h=uint32_t(rasterH);loadingAurora.pixels.resize(size_t(rasterW)*rasterH);
    for(int y=0;y<rasterH;y++)for(int x=0;x<rasterW;x++){
     const double u=std::clamp(sx+(x+.5)*sw/rasterW-.5,0.,double(source.w-1)),v=std::clamp(sy+(y+.5)*sh/rasterH-.5,0.,double(source.h-1));
     const int ix=int(u),iy=int(v),nx=std::min(ix+1,int(source.w-1)),ny=std::min(iy+1,int(source.h-1));const float fx=float(u-ix),fy=float(v-iy);uint32_t c=0;
     for(int channel=0;channel<32;channel+=8){
      const float top=std::lerp(float((source.pixels[ix+size_t(iy)*source.w]>>channel)&255),float((source.pixels[nx+size_t(iy)*source.w]>>channel)&255),fx);
      const float bottom=std::lerp(float((source.pixels[ix+size_t(ny)*source.w]>>channel)&255),float((source.pixels[nx+size_t(ny)*source.w]>>channel)&255),fx);
      c|=uint32_t(std::lround(std::lerp(top,bottom,fy)))<<channel;
     }
     loadingAurora.pixels[x+size_t(y)*rasterW]=c;
    }
   }
   const uint32_t opacity=uint32_t(std::lround(255*(settings.animated?.90f+.06f*std::sin(menuTime*.17f):.94f)));
   for(int y=0;y<rasterH;y++)for(int x=0;x<rasterW;x++){const uint32_t c=loadingAurora.pixels[x+size_t(y)*rasterW];pixel(x,y,(c&0xffffff)|(((c>>24)*opacity/255)<<24));}
  }
  gradient(0,0,w,h/3,color(0x020a14,57),color(0x020a14,8));
  gradient(0,h*2/3,w,h-h*2/3,color(0x030912,16),color(0x030912,193));
  if(settings.animated&&settings.menuMist)for(int bank=0;bank<3;bank++){
   const int mw=int(std::lround(w*(.74f+bank*.12f))),mh=std::max(36,int(std::lround(h*(.12f+bank*.025f))));
   const int mx=int(std::lround(w*.52f-mw*.5f+std::sin(menuTime*(.019f+bank*.007f)+bank*2.1f)*w*.13f));
   const int my=int(std::lround(h*(.50f+bank*.15f)+std::sin(menuTime*.032f+bank)*h*.018f))-mh/2;
   picture(8,mx,my,mw,mh,false,color(0xc8d7df,uint32_t(std::lround(std::clamp(255*.17f*settings.fxStrength,0.f,255.f)))));
  }
  analog();
  rect(3,3,w-6,1,color(0x537384,80));rect(3,h-4,w-6,1,color(0x537384,64));
  const int barWidth=std::min(330,w-48),left=(w-barWidth)/2;
  const int stageY=std::max(46,std::min(h-98,int(std::lround(h*.57f)))),barY=stageY+20;
  // UiArt.shadow / svg_distance.glsl: a feathered radial reading area, never a solid panel.
  if(loadingReadingShadow.pixels.empty()){
   const int radius=std::max(42,barWidth*2/3),cx=w/2;
   loadingShadowX=std::max(0,int(std::floor((cx-radius)*pixelX)));loadingShadowY=std::max(0,int(std::floor((barY-radius)*pixelY)));
   const int right=std::min(rasterW,int(std::ceil((cx+radius)*pixelX))),bottom=std::min(rasterH,int(std::ceil((barY+radius)*pixelY)));
   loadingReadingShadow.w=uint32_t(std::max(0,right-loadingShadowX));loadingReadingShadow.h=uint32_t(std::max(0,bottom-loadingShadowY));loadingReadingShadow.pixels.resize(size_t(loadingReadingShadow.w)*loadingReadingShadow.h);
   for(uint32_t y=0;y<loadingReadingShadow.h;y++)for(uint32_t x=0;x<loadingReadingShadow.w;x++){
    const float dx=((loadingShadowX+x+.5f)/pixelX-cx)/radius,dy=((loadingShadowY+y+.5f)/pixelY-barY)/radius;
    const uint32_t alpha=uint32_t(std::lround(161*std::pow(std::max(1-std::hypot(dx,dy),0.f),2.4f)));
    loadingReadingShadow.pixels[x+size_t(y)*loadingReadingShadow.w]=color(0x02080d,alpha);
   }
  }
  for(uint32_t y=0;y<loadingReadingShadow.h;y++)for(uint32_t x=0;x<loadingReadingShadow.w;x++)pixel(loadingShadowX+int(x),loadingShadowY+int(y),loadingReadingShadow.pixels[x+size_t(y)*loadingReadingShadow.w]);
  auto fitCenter=[&](int y,float size,const std::wstring& value,uint32_t tint){auto caption=fitted(value,size,w-48);text(w/2.f-measure(caption,size)/2.f,y,size,caption,tint);};
  fitCenter(stageY,9.9f,loadingStage,ink);
  rect(left-1,barY-1,barWidth+2,5,line);rect(left,barY,barWidth,3,color(0x07111b,221));
  if(loadingKnown){
   const int complete=int(std::lround(barWidth*std::clamp(progress,0.f,1.f)));
   if(complete>0){
    const int x0=std::max(0,int(std::floor((left-6)*pixelX))),x1=std::min(rasterW,int(std::ceil((left+complete+6)*pixelX)));
    const int y0=std::max(0,int(std::floor((barY-6)*pixelY))),y1=std::min(rasterH,int(std::ceil((barY+9)*pixelY)));
    for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++){
     const float qx=std::abs((x+.5f)/pixelX-left-complete*.5f)-complete*.5f,qy=std::abs((y+.5f)/pixelY-barY-1.5f)-1.5f;
     const float d=std::hypot(std::max(qx,0.f),std::max(qy,0.f))+std::min(std::max(qx,qy),0.f);
     pixel(x,y,color(0xa9dae7,uint32_t(std::lround(230*std::exp(-d*d/5.5f)*.52f))));
    }
    gradient(left,barY,complete,3,ink,color(0x668ea4));rect(left+complete-1,barY-1,1,5,ink);
   }
   const uint64_t elapsedSeconds=loadingElapsedMillis/1000;std::wostringstream clock;clock<<elapsedSeconds/60<<L":"<<std::setfill(L'0')<<std::setw(2)<<elapsedSeconds%60;
   const auto elapsed=clock.str();const int elapsedWidth=int(std::ceil(measure(elapsed,11)*.83f));
   const auto count=uiFormat(tr("loading.darkwinter.count",L"%s / %s чанков",L"%s / %s chunks"),{std::to_wstring(loadingCompleted),std::to_wstring(loadingTotal)});
   text(left,barY+12,9.13f,fitted(count,9.13f,barWidth-elapsedWidth-8),muted);text(left+barWidth-elapsedWidth,barY+12,9.13f,elapsed,muted);
  }else fitCenter(barY+12,8.8f,tr("loading.darkwinter.opening_hint",L"Подготовка места появления",L"Preparing your arrival"),muted);
  if(h>230){const int markWidth=std::min(134,w/3),markX=w-markWidth-24,markY=h<330?24:h-74;picture(2,markX,markY,markWidth,std::max(1,markWidth/8),false,ink);text(markX,markY+25,7.92f,fitted(tr("loading.darkwinter.north",L"СЕВЕР ЖДЁТ",L"THE NORTH AWAITS"),7.92f,markWidth),muted);}
  // The source mod exposes cancellation only once world preparation has begun.
  if(loadingKnown){
   const int bx=w/2-68,by=std::max(0,h-28);const auto label=tr("loading.darkwinter.menu",L"В главное меню",L"Back to main menu");
   registerButton(900,bx,by,136,20,label);const bool highlighted=hover==900||focus==900;
   rect(bx,by,136,20,highlighted?color(0x405e70,128):color(0x192e3d,48));rect(bx,by+19,136,1,highlighted?color(0x91b9cd):color(0x3d596a));
   const auto caption=fitted(label,11,124);text(bx+68-measure(caption,11)/2.f,by+6,11,caption,highlighted?color(0xe2edf2):color(0x9ab3c2));
  }
 } else if(page==FAULT){backdrop(1);rect(0,0,w,h,color(0x030a13,170));text(left,50,17,tr("menu.darkwinter.world.error",L"Не удалось открыть мир",L"Unable to load world"),ink);wrapped(left,96,w-left*2,11,errorText,ink,12);add(0,w-140,h-28,120,20,tr("gui.back",L"Назад",L"Back"));}
 else if(page==MATERIALS&&scene){rect(0,0,w,h,color(0x07121b,190));text(left,40,17,L"МАТЕРИАЛЫ",ink);uint32_t first=selected>4?selected-4:0;if(first+10>scene->materials)first=scene->materials>10?scene->materials-10:0;int count=std::min(10,std::max(1,(h-140)/28));for(int i=0;i<count&&first+uint32_t(i)<scene->materials;i++){uint32_t id=first+i;auto name=scene->materialName(id);std::wstring label(name.begin(),name.end());add(5000+int(id),left,90+i*28,w-left*2,26,label);if(id==selected)rect(left,90+i*28,2,26,color(0xa9dae7));}text(left,h-30,11,L"← / → — выбрать · Enter — вернуться",muted);}
 else {backdrop(4);text(left,50,17,L"DARKWINTER",ink);add(0,w-140,h-28,120,20,tr("gui.back",L"Назад",L"Back"));}
 tooltip();
}
void Ui::present(HWND window)const{
 RECT client;GetClientRect(window,&client);if(client.right<=0||client.bottom<=0)return;std::vector<uint32_t> bgra(size_t(rasterW)*rasterH);for(size_t i=0;i<bgra.size();i++){uint32_t c=pixels[4+i];bgra[i]=(c&0xff00ff00)|((c&255)<<16)|((c>>16)&255);}BITMAPINFO info{};info.bmiHeader.biSize=sizeof(BITMAPINFOHEADER);info.bmiHeader.biWidth=rasterW;info.bmiHeader.biHeight=-rasterH;info.bmiHeader.biPlanes=1;info.bmiHeader.biBitCount=32;info.bmiHeader.biCompression=BI_RGB;HDC target=GetDC(window);StretchDIBits(target,0,0,client.right,client.bottom,0,0,rasterW,rasterH,bgra.data(),&info,DIB_RGB_COLORS,SRCCOPY);ReleaseDC(window,target);
}
}

