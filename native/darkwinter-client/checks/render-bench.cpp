// Isolated native renderer exercise. No game simulation, input synthesis or save writes.
#include "../src/renderer.hpp"
#include <iostream>
#include <iomanip>
#include <sstream>
using namespace dw::client;
static double percentile(std::vector<double> v,double q){if(v.empty())return 0;std::sort(v.begin(),v.end());return v[size_t(q*(v.size()-1))];}
static LRESULT CALLBACK procedure(HWND w,UINT m,WPARAM a,LPARAM b){return DefWindowProcW(w,m,a,b);}
struct Surface {
 HWND window=nullptr;
 Surface(){
  WNDCLASSW wc{};wc.hInstance=GetModuleHandleW(nullptr);wc.lpfnWndProc=procedure;wc.lpszClassName=L"DarkWinterRenderStudy";
  if(!RegisterClassW(&wc)&&GetLastError()!=ERROR_CLASS_ALREADY_EXISTS)throw std::runtime_error("Study window class");
  RECT r{0,0,1280,720};AdjustWindowRect(&r,WS_OVERLAPPEDWINDOW,FALSE);
  window=CreateWindowExW(0,wc.lpszClassName,L"DarkWinter renderer check",WS_OVERLAPPEDWINDOW,0,0,r.right-r.left,r.bottom-r.top,nullptr,nullptr,wc.hInstance,nullptr);
  if(!window)throw std::runtime_error("Study surface");ShowWindow(window,SW_HIDE);
 }
 ~Surface(){if(window)DestroyWindow(window);}
};
int main(int argc,char** argv){
 try{
  if(argc!=7)throw std::runtime_error("dist shader report camera frames verify required");
  std::filesystem::path dist=argv[1],shader=argv[2],report=argv[3];std::string name=argv[4];
  uint32_t count=uint32_t(std::stoul(argv[5]));bool verify=std::string(argv[6])=="1",record=std::string(argv[6])!="0";
  if(count<60||count>900)throw std::runtime_error("Study frame budget");
  Camera camera;
  if(name=="ground")camera={{.5f,81.f,16.5f},0,-.07f};
  else if(name=="overview")camera={{16.5f,120.f,-24.5f},0,-.48f};
  else if(name=="negative")camera={{-24.5f,96.f,-20.5f},.8f,-.2f};
  else if(name=="road")camera={};
  else throw std::runtime_error("Unknown study camera");
  std::filesystem::create_directories(report);Scene scene(dist/"world.dwv",dist/"assets.dwa");Surface surface;
  Renderer renderer(surface.window,scene,shader,record,false,true);
  std::vector<double> cpu;uint32_t frame=0;
  while(frame<count){
   MSG message;while(PeekMessageW(&message,nullptr,0,0,PM_REMOVE)){TranslateMessage(&message);DispatchMessageW(&message);}
   bool last=frame+1==count;auto begin=std::chrono::steady_clock::now();
   if(!renderer.draw(camera,last,report/"final.png",verify&&last))continue;
   double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-begin).count();
   if(frame>=30&&!last)cpu.push_back(ms);++frame;
  }
  renderer.drain();bool ok=renderer.validationErrors.load()==0&&(!verify||(renderer.oracleRays>300&&renderer.oracleFailures==0));
  std::ofstream out(report/"render.json");out<<std::setprecision(10)<<"{\"completed\":"<<(ok?"true":"false")<<",\"camera\":\""<<name<<"\",\"frames\":"<<frame
   <<",\"device\":"<<std::quoted(renderer.deviceName)<<",\"width\":"<<renderer.width()<<",\"height\":"<<renderer.height()<<",\"gpuSamples\":"<<renderer.gpuMs.size()
   <<",\"gpuMedianMs\":"<<percentile(renderer.gpuMs,.5)<<",\"gpuP95Ms\":"<<percentile(renderer.gpuMs,.95)
   <<",\"cpuDrawMedianMs\":"<<percentile(cpu,.5)<<",\"cpuDrawP95Ms\":"<<percentile(cpu,.95)
   <<",\"oracleRays\":"<<renderer.oracleRays<<",\"sourceTextureOracleFrames\":"<<renderer.sourceTextureOracleFrames<<",\"filteredTextureFrames\":"<<renderer.filteredTextureFrames<<",\"textureOracleMode\":\"point_source_geometry_only\""<<",\"oracleEquivalent\":"<<renderer.oracleEquivalent<<",\"oracleAmbiguous\":"<<renderer.oracleAmbiguous<<",\"oracleFailures\":"<<renderer.oracleFailures
   <<",\"validationEnabled\":"<<(renderer.validationEnabled?"true":"false")<<",\"worldFnv\":\""<<scene.world_hash<<"\",\"assetBytes\":"<<scene.assets.size()*4<<"}\n";
  if(!out)throw std::runtime_error("Study receipt");std::cout<<"RENDER_STUDY "<<name<<" median="<<percentile(renderer.gpuMs,.5)<<" ms pass="<<ok<<"\n";return ok?0:2;
 }catch(const std::exception& e){std::cerr<<e.what()<<"\n";return 1;}
}
