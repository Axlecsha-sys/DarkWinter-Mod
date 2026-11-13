#include "ui.hpp"
#include "darkwinter_visual_provider.hpp"
#include "player.hpp"
#include "audio.hpp"
#include "streaming.hpp"
#include "world_ui_store.hpp"
#include "world_options.hpp"
#include <future>
#include <cwctype>
#include <shellapi.h>
#include <tlhelp32.h>
#include <cctype>
#include <array>
#include <iomanip>
#include <sstream>
#include <thread>

using namespace dw::client;
struct Input {
    std::array<bool,256> keys{};bool pointerDown=false,editing=false,bindingCapture=false,doubleClick=false,selectAll=false,looking=false,raw=false,close=false,capture=false,home=false,click=false,game=false;unsigned fullscreenKey=VK_F11;std::map<UINT,unsigned> modifiers;int mx=0,my=0;std::vector<UINT> presses;
    int dx=0,dy=0,lastX=0,lastY=0;bool mouseValid=false,actorMode=true,jumpBuffered=false,toggleFullscreen=false,mouseChanged=false,fullscreen=false,displayChanged=false;int scroll=0,wheelRemainder=0;std::wstring characters;float speed=6;
    void release(){pointerDown=false;keys.fill(false);jumpBuffered=false;dx=dy=0;mouseValid=false;ClipCursor(nullptr);if(looking){looking=false;ReleaseCapture();ShowCursor(TRUE);}}
};
static unsigned sidedKey(WPARAM key,LPARAM detail){
    if(key==VK_SHIFT){unsigned result=MapVirtualKeyW((unsigned(detail)>>16)&0xff,MAPVK_VSC_TO_VK_EX);return result?result:VK_LSHIFT;}
    if(key==VK_CONTROL)return (detail&(1LL<<24))?VK_RCONTROL:VK_LCONTROL;
    if(key==VK_MENU)return (detail&(1LL<<24))?VK_RMENU:VK_LMENU;
    return unsigned(key);
}
static unsigned genericKey(unsigned key){
    if(key==VK_LSHIFT||key==VK_RSHIFT)return VK_SHIFT;
    if(key==VK_LCONTROL||key==VK_RCONTROL)return VK_CONTROL;
    if(key==VK_LMENU||key==VK_RMENU)return VK_MENU;
    return key;
}
static LRESULT CALLBACK procedure(HWND h,UINT message,WPARAM w,LPARAM l){
    Input* input=reinterpret_cast<Input*>(GetWindowLongPtrW(h,GWLP_USERDATA));
    if(message==WM_NCCREATE){input=static_cast<Input*>(reinterpret_cast<CREATESTRUCTW*>(l)->lpCreateParams);SetWindowLongPtrW(h,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(input));}
    if(!input)return DefWindowProcW(h,message,w,l);
    switch(message){
        case WM_CLOSE:input->close=true;return 0;
        case WM_DPICHANGED:{input->release();if(!input->fullscreen){auto* r=reinterpret_cast<RECT*>(l);SetWindowPos(h,nullptr,r->left,r->top,r->right-r->left,r->bottom-r->top,SWP_NOZORDER|SWP_NOACTIVATE);}else input->displayChanged=true;return 0;}
        case WM_DISPLAYCHANGE:input->release();input->displayChanged=true;return 0;
        case WM_KILLFOCUS:input->release();return 0;
        case WM_KEYDOWN:case WM_SYSKEYDOWN:
            if(!input->bindingCapture&&(sidedKey(w,l)==input->fullscreenKey||(w==VK_RETURN&&message==WM_SYSKEYDOWN&&(l&(1LL<<29))))&&!(l&(1LL<<30))){input->toggleFullscreen=true;return 0;}
            if(w<256){input->keys[genericKey(unsigned(w))]=true;input->keys[sidedKey(w,l)]=true;}if(input->editing&&input->keys[VK_CONTROL]&&w=='A'){input->selectAll=true;return 0;}if(input->editing&&input->keys[VK_CONTROL]&&w=='V'){SendMessageW(h,WM_PASTE,0,0);return 0;}if(w==VK_SPACE&&!(l&(1LL<<30)))input->jumpBuffered=true;
            if(!(l&(1LL<<30))||input->editing){input->presses.push_back(sidedKey(w,l));input->modifiers[sidedKey(w,l)]=(input->keys[VK_SHIFT]?1u:0u)|(input->keys[VK_CONTROL]?2u:0u);}if(w==VK_F12&&!(l&(1LL<<30)))input->capture=true;if(w==VK_HOME)input->home=true;
            if(message==WM_SYSKEYDOWN&&w==VK_F4)return DefWindowProcW(h,message,w,l);
            return 0;
        case WM_KEYUP:case WM_SYSKEYUP:if(w<256){input->keys[sidedKey(w,l)]=false;unsigned base=genericKey(unsigned(w));if(base==VK_SHIFT)input->keys[base]=input->keys[VK_LSHIFT]||input->keys[VK_RSHIFT];else if(base==VK_CONTROL)input->keys[base]=input->keys[VK_LCONTROL]||input->keys[VK_RCONTROL];else if(base==VK_MENU)input->keys[base]=input->keys[VK_LMENU]||input->keys[VK_RMENU];else input->keys[base]=false;}return 0;
        case WM_LBUTTONDBLCLK:input->doubleClick=true;[[fallthrough]];
        case WM_LBUTTONDOWN:input->keys[VK_LBUTTON]=true;if(input->bindingCapture){input->presses.push_back(VK_LBUTTON);return 0;}if(input->game)input->presses.push_back(VK_LBUTTON);input->pointerDown=true;if(!input->game)SetCapture(h);input->click=true;input->mx=short(LOWORD(l));input->my=short(HIWORD(l));return 0;
        case WM_LBUTTONUP:input->keys[VK_LBUTTON]=false;input->pointerDown=false;if(!input->game)ReleaseCapture();return 0;
        case WM_RBUTTONDOWN:
            input->keys[VK_RBUTTON]=true;if(input->bindingCapture){input->presses.push_back(VK_RBUTTON);return 0;}if(input->game)input->presses.push_back(VK_RBUTTON);
            if(!input->game)return 0;
            if(!input->looking){input->looking=true;input->mouseValid=false;SetCapture(h);ShowCursor(FALSE);}return 0;
        case WM_RBUTTONUP:input->keys[VK_RBUTTON]=false;if(!input->actorMode)input->release();return 0;
        case WM_MBUTTONDOWN:input->keys[VK_MBUTTON]=true;if(input->bindingCapture||input->game)input->presses.push_back(VK_MBUTTON);return 0;
        case WM_MBUTTONUP:input->keys[VK_MBUTTON]=false;return 0;
        case WM_XBUTTONDOWN:{unsigned key=GET_XBUTTON_WPARAM(w)==XBUTTON1?VK_XBUTTON1:VK_XBUTTON2;input->keys[key]=true;if(input->bindingCapture||input->game)input->presses.push_back(key);return TRUE;}
        case WM_XBUTTONUP:input->keys[GET_XBUTTON_WPARAM(w)==XBUTTON1?VK_XBUTTON1:VK_XBUTTON2]=false;return TRUE;
        case WM_CAPTURECHANGED:input->pointerDown=false;if(input->looking){input->looking=false;ClipCursor(nullptr);ShowCursor(TRUE);}return 0;
        case WM_PASTE:{if(input->editing&&OpenClipboard(h)){if(auto data=GetClipboardData(CF_UNICODETEXT)){if(auto text=static_cast<const wchar_t*>(GlobalLock(data))){input->characters.append(text,wcsnlen(text,128));GlobalUnlock(data);}}CloseClipboard();}return 0;}
        case WM_CHAR:if(w>=32||w==8)input->characters.push_back(wchar_t(w));return 0;
        case WM_MOUSEWHEEL:input->wheelRemainder+=GET_WHEEL_DELTA_WPARAM(w);input->scroll+=input->wheelRemainder/WHEEL_DELTA;input->wheelRemainder%=WHEEL_DELTA;if(input->game&&!input->actorMode)input->speed=std::clamp(input->speed*std::pow(1.2f,float(GET_WHEEL_DELTA_WPARAM(w))/120),.5f,100.f);return 0;
        case WM_MOUSEMOVE:
            input->mouseChanged=true;input->mx=short(LOWORD(l));input->my=short(HIWORD(l));
            if(input->looking&&!input->raw){int x=short(LOWORD(l)),y=short(HIWORD(l));if(input->mouseValid){input->dx+=x-input->lastX;input->dy+=y-input->lastY;}input->lastX=x;input->lastY=y;input->mouseValid=true;}return 0;
        case WM_INPUT:{
            if(input->looking){RAWINPUT data{};UINT bytes=sizeof(data);if(GetRawInputData(reinterpret_cast<HRAWINPUT>(l),RID_INPUT,&data,&bytes,sizeof(RAWINPUTHEADER))==bytes&&data.header.dwType==RIM_TYPEMOUSE&&!(data.data.mouse.usFlags&MOUSE_MOVE_ABSOLUTE)){input->dx+=data.data.mouse.lLastX;input->dy+=data.data.mouse.lLastY;}}
            return DefWindowProcW(h,message,w,l);
        }
        case WM_GETMINMAXINFO:{auto* p=reinterpret_cast<MINMAXINFO*>(l);p->ptMinTrackSize={400,280};p->ptMaxTrackSize={8000,4500};return 0;}
        default:return DefWindowProcW(h,message,w,l);
    }
}
struct Window {
    Input input;HWND handle=nullptr;bool full=false;WINDOWPLACEMENT placement{sizeof(WINDOWPLACEMENT)};
    Window(uint32_t w,uint32_t h,bool hidden){
        SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);WNDCLASSW wc{};wc.style=CS_DBLCLKS;wc.lpfnWndProc=procedure;wc.hInstance=GetModuleHandleW(nullptr);wc.lpszClassName=L"DarkWinterStandaloneCore";wc.hCursor=LoadCursorW(nullptr,IDC_ARROW);
        if(!RegisterClassW(&wc)&&GetLastError()!=ERROR_CLASS_ALREADY_EXISTS)throw std::runtime_error("Window class registration failed");
        RECT r{0,0,LONG(w),LONG(h)};AdjustWindowRect(&r,WS_OVERLAPPEDWINDOW,FALSE);
        handle=CreateWindowExW(0,wc.lpszClassName,L"DarkWinter",WS_OVERLAPPEDWINDOW,CW_USEDEFAULT,CW_USEDEFAULT,r.right-r.left,r.bottom-r.top,nullptr,nullptr,wc.hInstance,&input);
        if(!handle)throw std::runtime_error("Win32 window creation failed");
        size(w,h); // CreateWindowEx may constrain the initial outer height; restore the requested client size.
        RAWINPUTDEVICE raw{1,2,0,handle};input.raw=RegisterRawInputDevices(&raw,1,sizeof(raw))==TRUE;
        ShowWindow(handle,hidden?SW_HIDE:SW_SHOW);if(!hidden)UpdateWindow(handle);
    }
    void fullscreen(bool enabled){
        if(enabled==full)return;input.release();input.fullscreen=enabled;
        if(enabled){GetWindowPlacement(handle,&placement);MONITORINFO monitor{sizeof(MONITORINFO)};if(!GetMonitorInfoW(MonitorFromWindow(handle,MONITOR_DEFAULTTONEAREST),&monitor))throw std::runtime_error("Monitor unavailable");SetWindowLongPtrW(handle,GWL_STYLE,(GetWindowLongPtrW(handle,GWL_STYLE)&WS_VISIBLE)|WS_POPUP);SetWindowPos(handle,HWND_TOP,monitor.rcMonitor.left,monitor.rcMonitor.top,monitor.rcMonitor.right-monitor.rcMonitor.left,monitor.rcMonitor.bottom-monitor.rcMonitor.top,SWP_FRAMECHANGED|SWP_NOACTIVATE);}
        else{SetWindowLongPtrW(handle,GWL_STYLE,(GetWindowLongPtrW(handle,GWL_STYLE)&WS_VISIBLE)|WS_OVERLAPPEDWINDOW);SetWindowPlacement(handle,&placement);SetWindowPos(handle,nullptr,0,0,0,0,SWP_NOMOVE|SWP_NOSIZE|SWP_NOZORDER|SWP_NOACTIVATE|SWP_FRAMECHANGED);}
        full=enabled;
    }
    void refreshMonitor(){if(!full)return;MONITORINFO m{sizeof(MONITORINFO)};if(GetMonitorInfoW(MonitorFromWindow(handle,MONITOR_DEFAULTTONEAREST),&m))SetWindowPos(handle,HWND_TOP,m.rcMonitor.left,m.rcMonitor.top,m.rcMonitor.right-m.rcMonitor.left,m.rcMonitor.bottom-m.rcMonitor.top,SWP_FRAMECHANGED|SWP_NOACTIVATE);}
    ~Window(){input.release();if(handle)DestroyWindow(handle);}
    void size(uint32_t w,uint32_t h){RECT r{0,0,LONG(w),LONG(h)};AdjustWindowRect(&r,WS_OVERLAPPEDWINDOW,FALSE);SetWindowPos(handle,nullptr,0,0,r.right-r.left,r.bottom-r.top,SWP_NOMOVE|SWP_NOZORDER|SWP_NOACTIVATE);}
};
static void cameraInput(Camera& camera,Input& input,float dt){
    if(input.home){camera={};input.home=false;}
    camera.yaw+=input.dx*.0025f;camera.pitch=std::clamp(camera.pitch-input.dy*.0025f,-1.55f,1.55f);input.dx=input.dy=0;
    camera.yaw+=(float(input.keys[VK_RIGHT])-float(input.keys[VK_LEFT]))*dt;
    camera.pitch=std::clamp(camera.pitch+(float(input.keys[VK_UP])-float(input.keys[VK_DOWN]))*dt,-1.55f,1.55f);
    V3 f{std::sin(camera.yaw)*std::cos(camera.pitch),std::sin(camera.pitch),std::cos(camera.yaw)*std::cos(camera.pitch)},r{std::cos(camera.yaw),0,-std::sin(camera.yaw)};
    V3 move=f*(float(input.keys['W'])-float(input.keys['S']))+r*(float(input.keys['D'])-float(input.keys['A']))+V3{0,float(input.keys[VK_SPACE])-float(input.keys[VK_CONTROL]),0};
    if(dot(move,move)>0)camera.position=camera.position+normalized(move)*(dt*input.speed*(input.keys[VK_SHIFT]?3.f:1.f));
}
static void grabMouse(HWND h,Input& input){
    if(input.looking||GetForegroundWindow()!=h)return;
    input.looking=true;input.mouseValid=false;SetCapture(h);ShowCursor(FALSE);
    RECT r;GetClientRect(h,&r);POINT a{r.left,r.top},b{r.right,r.bottom};ClientToScreen(h,&a);ClientToScreen(h,&b);r={a.x,a.y,b.x,b.y};ClipCursor(&r);
}
static double percentile(std::vector<double> v,double quantile){if(v.empty())return 0;std::sort(v.begin(),v.end());return v[size_t(quantile*double(v.size()-1))];}
static std::string jsonString(const std::string& s){std::string o="\"";for(unsigned char c:s){if(c=='\\'||c=='"'){o+='\\';o+=char(c);}else if(c<32)o+=' ';else o+=char(c);}return o+'"';}
static std::string utf8(std::wstring_view w){if(w.empty())return{};int n=WideCharToMultiByte(CP_UTF8,0,w.data(),int(w.size()),nullptr,0,nullptr,nullptr);std::string s(n,0);WideCharToMultiByte(CP_UTF8,0,w.data(),int(w.size()),s.data(),n,nullptr,nullptr);return s;}
static std::pair<std::string,bool> modules(){
    HANDLE snapshot=CreateToolhelp32Snapshot(TH32CS_SNAPMODULE,GetCurrentProcessId());
    if(snapshot==INVALID_HANDLE_VALUE)throw std::runtime_error("Module snapshot failed");
    MODULEENTRY32W entry{};entry.dwSize=sizeof(entry);std::string json="[";bool first=true,java=false;
    BOOL found=Module32FirstW(snapshot,&entry);
    if(!found){CloseHandle(snapshot);throw std::runtime_error("Module enumeration failed");}
    do{
        auto name=utf8(entry.szModule);std::string lower=name;std::transform(lower.begin(),lower.end(),lower.begin(),[](unsigned char c){return char(std::tolower(c));});
        java|=lower=="jvm.dll"||lower=="java.dll"||lower=="jli.dll";
        if(!first)json+=',';first=false;json+=jsonString(name);
    }while(Module32NextW(snapshot,&entry));
    CloseHandle(snapshot);return{json+"]",java};
}

static Camera initial(const std::filesystem::path& folder){Camera c;std::ifstream f(folder/"camera.txt");if(!(f>>c.position.x>>c.position.y>>c.position.z>>c.yaw>>c.pitch))throw std::runtime_error("Biome camera unavailable");return c;}

static std::wstring wide(const std::string& s){if(s.empty())return{};int n=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,s.data(),int(s.size()),nullptr,0);if(n<=0)return L"Некорректное имя";std::wstring result(n,0);MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,s.data(),int(s.size()),result.data(),n);return result;}
struct LoadedWorld {std::unique_ptr<Scene> scene;std::unique_ptr<CollisionWorld> collision;std::unique_ptr<StreamingWorld> stream;Player player;Needs needs;Camera camera;double seconds=0,daySeconds=0;std::wstring name=L"DarkWinter";std::string id;Settings options;NativeWorldOptions policy;};
int WINAPI WinMain(HINSTANCE,HINSTANCE,LPSTR,int){
 wchar_t module[32768];DWORD length=GetModuleFileNameW(nullptr,module,32768);auto folder=std::filesystem::path(std::wstring(module,length)).parent_path();
 bool hidden=false,verify=false,exercise=false,play=false,forceWindowed=false,lab=false,renderMotion=false;uint32_t limit=0;
 auto epoch=std::to_string(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count());auto report=folder/"reports"/("stage6-"+epoch),saves=folder/"saves";
 try{
  int argc=0;auto argv=CommandLineToArgvW(GetCommandLineW(),&argc);if(!argv)throw std::runtime_error("Arguments unavailable");std::vector<std::wstring> args;for(int i=1;i<argc;i++)args.emplace_back(argv[i]);LocalFree(argv);
  auto settingsFile=folder/"settings.cfg";
  for(size_t i=0;i<args.size();++i)if(args[i]==L"--settings"){if(i+1>=args.size())throw std::runtime_error("Missing settings path");settingsFile=args[++i];}
  Settings settings;settings.load(settingsFile);
  for(size_t i=0;i<args.size();i++){auto value=[&](){if(++i==args.size())throw std::runtime_error("Missing argument");return args[i];};
   if(args[i]==L"--render-motion")renderMotion=true;else if(args[i]==L"--settings"){value();}else if(args[i]==L"--lab")lab=true;else if(args[i]==L"--hidden")hidden=true;else if(args[i]==L"--verify")verify=true;else if(args[i]==L"--play")play=true;else if(args[i]==L"--exercise-ui"||args[i]==L"--exercise-player"||args[i]==L"--exercise-world")exercise=true;
   else if(args[i]==L"--windowed")forceWindowed=true;else if(args[i]==L"--no-vsync")settings.vsync=false;else if(args[i]==L"--daylight-preview")settings.daylightPreview=true;else if(args[i]==L"--world-clock")settings.daylightPreview=false;else if(args[i]==L"--frames")limit=uint32_t(std::stoul(value()));else if(args[i]==L"--report")report=value();else if(args[i]==L"--saves")saves=value();else throw std::runtime_error("Unknown option");
  }
  if(renderMotion&&(!play||!limit))throw std::runtime_error("Render-motion capture requires --play and a frame limit");
   if(exercise&&limit<720)throw std::runtime_error("UI exercise needs at least 720 frames");
  if(exercise||limit)saves=report/"saves";std::filesystem::create_directories(report);std::filesystem::create_directories(saves);if(hidden||forceWindowed)settings.fullscreen=false;
  Window window(settings.width,settings.height,hidden);window.fullscreen(settings.fullscreen);Ui ui(folder);ui.settings=settings;StepAudio audio(folder/"footsteps.dws",!hidden);audio.volume=settings.volume;audio.muted=!settings.sound;
  const NativeRuleCatalog ruleCatalog(folder/"ui-game-rules.tsv");
  std::string worldOptionsResult,worldRulesResult;
  LoadedWorld current;std::unique_ptr<Renderer> renderer;std::vector<double> gpuTimes;bool active=false,actorMode=true,third=settings.third,bodyVisible=false,cancelLoading=false;float targetYaw=0,targetPitch=-.12f;uint64_t saveCount=0,newWorldCount=0,loadRevision=0,loadingFrame=0;
  uint64_t frames=0,jumps=0,steps=0,climbs=0,totalColumns=0;double travelled=0;std::vector<double> frameMs;std::vector<double> physicsMs;physicsMs.reserve(65536);
  bool loadCancelled=false,restored=false,fullscreenVerified=false,windowRestored=false,pauseVerified=false,firstPersonVerified=false;uint64_t pauseTick=0;std::filesystem::path savedFile;std::string savedPlayer;
  auto resizeUi=[&](){RECT r;GetClientRect(window.handle,&r);ui.resizeFor(r.right,r.bottom);};resizeUi();
  auto pumpMessages=[&](){MSG message;while(PeekMessageW(&message,nullptr,0,0,PM_REMOVE)){if(message.message==WM_QUIT)window.input.close=true;TranslateMessage(&message);DispatchMessageW(&message);}if(window.input.displayChanged){window.refreshMonitor();window.input.displayChanged=false;ui.dirty=true;}};
  auto persistSettings=[&](){if(!exercise&&!hidden){Settings saved=ui.settings;std::erase_if(saved.options,[](const auto& row){return worldOptionKey(row.first)||row.first.starts_with("world.");});std::erase_if(saved.textOptions,[](const auto& row){return worldOptionKey(row.first)||row.first.starts_with("world.");});saved.save(settingsFile);}};
  auto doFullscreen=[&](){window.fullscreen(!window.full);ui.settings.fullscreen=window.full;resizeUi();ui.dirty=true;persistSettings();};
  std::function<void()> labDuringLoad,labAfterLoadingFrame;
  auto load=[&](const std::filesystem::path& file,bool restore,bool native,uint64_t seed)->bool{
   ++loadRevision;loadingFrame=0;cancelLoading=false;ui.errorText.clear();ui.selectPage(Ui::LOADING);ui.progress=0;
   ui.loadingKnown=false;ui.loadingCompleted=ui.loadingTotal=0;ui.loadingElapsedMillis=0;
   ui.loadingStage=ui.tr("loading.darkwinter.opening",L"Открытие мира",L"Opening world");
   window.input.release();if(renderer){renderer->drain();gpuTimes.insert(gpuTimes.end(),renderer->gpuMs.begin(),renderer->gpuMs.end());}renderer.reset();
   const auto loadingStarted=std::chrono::steady_clock::now();const float loadingAnimationStart=ui.menuTime;
   float shownProgress=0;uint64_t lastProgressFrameMillis=0;bool hasProgressFrame=false,showingKnownProgress=false;
   auto renderLoading=[&](){
    auto elapsed=std::chrono::steady_clock::now()-loadingStarted;
    ui.loadingElapsedMillis=uint64_t(std::chrono::duration_cast<std::chrono::milliseconds>(elapsed).count());
    // WorldPreloadClient.displayedProgress: measured counts remain authoritative; only the bar is smoothed.
    if(showingKnownProgress!=ui.loadingKnown){showingKnownProgress=ui.loadingKnown;shownProgress=0;hasProgressFrame=false;}
    const float target=ui.loadingKnown&&ui.loadingTotal?float(ui.loadingCompleted)/float(ui.loadingTotal):0.f;
    if(target<shownProgress)shownProgress=std::max(0.f,target);
    const uint64_t dtMillis=ui.loadingElapsedMillis-lastProgressFrameMillis;
    const float step=hasProgressFrame?float(1.-std::exp(-double(std::min(uint64_t(100),dtMillis))/110.)):1.f;
    shownProgress+=(std::clamp(target,0.f,1.f)-shownProgress)*step;
    if(ui.progress!=shownProgress){ui.progress=shownProgress;ui.dirty=true;}
    lastProgressFrameMillis=ui.loadingElapsedMillis;hasProgressFrame=true;
    ui.animate(loadingAnimationStart+std::chrono::duration<float>(elapsed).count());resizeUi();ui.render(nullptr,0);++loadingFrame;
    if(!hidden)ui.present(window.handle);if(labAfterLoadingFrame)labAfterLoadingFrame();
   };
   renderLoading();
   // Publish completed/total together so the render thread never observes a torn pair.
   std::atomic<uint64_t> chunkCounts{0};std::atomic<bool> cancelled{false};std::wstring newName=ui.worldName;std::string newId="world-"+epoch+"-"+std::to_string(++newWorldCount);Settings newOptions=worldDraft(ui.settings);
   auto future=std::async(std::launch::async,[&,file,restore,native,seed,newName,newId,newOptions]()mutable{
    LoadedWorld next;next.options=restore?Settings{}:newNativeWorldOptions(newOptions);bool hasMetadata=false;next.name=newName;next.id=newId;uint64_t expected=0;std::string magic;std::ifstream state;
    if(restore){next.id=file.stem().string();auto info=file;info.replace_extension(".info");std::ifstream meta(info);std::string label,id;uint64_t metadataSeed;if(meta>>std::quoted(label)>>metadataSeed){next.name=wide(label);hasMetadata=true;if(meta>>std::quoted(id)&&!id.empty()&&id.size()<128)next.id=id;}auto p=file;p.replace_extension(".state");state.open(p);if(!(state>>magic>>expected>>next.camera.position.x>>next.camera.position.y>>next.camera.position.z>>next.camera.yaw>>next.camera.pitch>>next.seconds)||(magic!="DWSTATE1"&&magic!="DWSTATE2"&&magic!="DWSTATE3")||!std::isfinite(next.seconds)||next.seconds<0)throw std::runtime_error("Save header invalid");native=magic=="DWSTATE3";next.options.load(worldSidecar(file,".options"));}
    next.policy=nativeWorldOptions(next.options);storeWorldOptions(next.options,next.policy);next.daySeconds=nativeDaySeconds(next.options,next.seconds);
    next.scene=std::make_unique<Scene>(file,folder/"assets.dwa",!native,false);
    if(restore&&expected!=next.scene->logicalHash())throw std::runtime_error("Save state does not match world");
    next.collision=std::make_unique<CollisionWorld>(*next.scene,folder/"collision.dwc");
    if(restore&&magic!="DWSTATE1")next.player.read(state,*next.collision,next.policy.personalGameMode,nativeFlying(next.options,next.policy));
    if(restore&&native){next.needs.read(state);std::string name;if(!(state>>seed>>std::quoted(name))||(name.size()>512||wide(name).size()>128))throw std::runtime_error("Native state metadata invalid");if(!hasMetadata)next.name=wide(name);std::string id;if(state>>std::quoted(id)&&!id.empty()&&id.size()<128)next.id=id;else next.id=file.stem().string();}
    if(cancelled)throw std::runtime_error("Loading cancelled");
    if(native){next.scene->prepareNative();next.stream=std::make_unique<StreamingWorld>(*next.scene,seed);if(restore){auto journal=file;journal.replace_extension(".dwg");next.stream->restore(journal);}V3 at=restore?next.player.feet:V3{.5f,90,.5f};const uint32_t total=next.scene->world[4]*next.scene->world[6];chunkCounts=uint64_t(total)<<32;
     next.stream->bootstrap(at,[&](float){
      const uint32_t completed=uint32_t(std::lround(std::clamp(next.stream->progress,0.f,1.f)*float(total)));
      chunkCounts=(uint64_t(total)<<32)|std::min(completed,total);
     },&cancelled);if(!restore){Generator terrain(*next.scene,seed);V3 spawn=next.collision->spawn({.5f,float(terrain.height(0,0))+2,.5f});next.player.reset(spawn);}}
    else if(!restore||magic=="DWSTATE1"){if(!restore)next.camera=initial(folder);next.player.reset(next.collision->spawn(next.camera.position),next.camera.yaw,next.camera.pitch);}
    next.player.setGameMode(next.policy.personalGameMode,nativeFlying(next.options,next.policy));
    return next;
   });
   bool injectCancel=exercise&&frames==35&&!restore;bool loadingCaptured=false;
   while(future.wait_for(std::chrono::milliseconds(0))!=std::future_status::ready){
    pumpMessages();if(labDuringLoad)labDuringLoad();auto& input=window.input;
    if(injectCancel&&(chunkCounts.load()>>32)!=0){
     auto button=std::find_if(ui.buttons.begin(),ui.buttons.end(),[](const UiButton& b){return b.id==900&&b.enabled;});
     if(button!=ui.buttons.end()){RECT r;GetClientRect(window.handle,&r);int x=(button->x+button->w/2)*r.right/ui.w,y=(button->y+button->h/2)*r.bottom/ui.h;
      SendMessageW(window.handle,WM_LBUTTONDOWN,MK_LBUTTON,MAKELPARAM(x,y));SendMessageW(window.handle,WM_LBUTTONUP,0,MAKELPARAM(x,y));injectCancel=false;}
    }
    // The mod ignores Escape while opening a world; hidden cancellation cannot receive keyboard focus.
    const bool canCancel=std::any_of(ui.buttons.begin(),ui.buttons.end(),[](const UiButton& b){return b.id==900&&b.enabled;});
    if(!canCancel&&ui.focus==900){ui.focus=-1;ui.dirty=true;}
    for(UINT key:input.presses){
     if(key==VK_TAB&&canCancel){ui.focus=900;ui.dirty=true;}
     else if((key==VK_RETURN||key==VK_SPACE)&&canCancel&&ui.focus==900)cancelled=true;
    }
    input.presses.clear();
    if(input.toggleFullscreen){doFullscreen();input.toggleFullscreen=false;}
    RECT r;GetClientRect(window.handle,&r);
    if(input.mouseChanged&&r.right&&r.bottom){int hover=ui.button(input.mx*ui.w/r.right,input.my*ui.h/r.bottom);if(hover!=ui.hover){ui.hover=hover;ui.dirty=true;}input.mouseChanged=false;}
    if(input.click){if(r.right&&r.bottom&&ui.button(input.mx*ui.w/r.right,input.my*ui.h/r.bottom)==900)cancelled=true;input.click=false;}
    if(input.close||cancelLoading)cancelled=true;
    uint64_t counts=chunkCounts.load();uint32_t total=uint32_t(counts>>32),completed=uint32_t(counts);
    if(ui.loadingCompleted!=completed||ui.loadingTotal!=total||ui.loadingElapsedMillis/1000!=uint64_t(std::chrono::duration_cast<std::chrono::seconds>(std::chrono::steady_clock::now()-loadingStarted).count()))ui.dirty=true;
    ui.loadingCompleted=completed;ui.loadingTotal=total;ui.loadingKnown=total!=0;
    auto stage=cancelled?ui.tr("loading.darkwinter.cancelled",L"Подготовка отменена",L"Preparation cancelled"):
     total?ui.tr("loading.darkwinter.chunks",L"Подготовка мира",L"Preparing world"):
     ui.tr("loading.darkwinter.opening",L"Открытие мира",L"Opening world");
    if(stage!=ui.loadingStage){ui.loadingStage=std::move(stage);ui.dirty=true;}
    renderLoading();
    if(exercise&&!loadingCaptured&&ui.loadingKnown){write_png(report/"loading.png",ui.pixels[0],ui.pixels[1],reinterpret_cast<const uint8_t*>(ui.pixels.data()+4));loadingCaptured=true;}
    std::this_thread::sleep_for(std::chrono::milliseconds(8));
   }
   try{
    auto next=future.get();if(cancelled)throw std::runtime_error("Loading cancelled");
    const uint64_t finalCounts=chunkCounts.load();ui.loadingTotal=uint32_t(finalCounts>>32);ui.loadingCompleted=uint32_t(finalCounts);ui.loadingKnown=ui.loadingTotal!=0;
    ui.dirty=true;renderLoading();
    auto nextRenderer=std::make_unique<Renderer>(window.handle,*next.scene,folder/"trace.comp.spv",verify,ui.settings.vsync,true,std::make_unique<DarkWinterVisualProvider>());
    if(current.stream)totalColumns+=current.stream->generatedColumns;current.stream.reset();current.collision.reset();current.scene.reset();current=std::move(next);renderer=std::move(nextRenderer);ui.progress=1;targetYaw=current.player.yaw;targetPitch=current.player.pitch;ui.worldName=current.name;ui.worldDaySeconds=ui.settings.visualDaySeconds(current.daySeconds);worldOptionsResult.clear();worldRulesResult.clear();clearWorldStaging(ui.settings);applyWorldDraft(ui.settings,current.options);ui.selectPage(Ui::GAME);window.input.release();return true;
   }catch(const std::exception& e){
    std::string failure=e.what();bool recoveryFailed=false;
    if(current.scene){try{renderer=std::make_unique<Renderer>(window.handle,*current.scene,folder/"trace.comp.spv",verify,ui.settings.vsync,true,std::make_unique<DarkWinterVisualProvider>());}
     catch(const std::exception& recovery){failure+="; renderer recovery failed: ";failure+=recovery.what();renderer.reset();current.stream.reset();current.collision.reset();current.scene.reset();current=LoadedWorld{};active=false;recoveryFailed=true;}}
    if(cancelled&&!recoveryFailed){loadCancelled=true;active=false;ui.activeWorld=false;ui.scanWorlds(saves);ui.selectPage(Ui::TITLE);window.input.release();}else{ui.errorText=wide(failure);ui.selectPage(Ui::FAULT);}return false;
   }
  };
  if(play&&!load(folder/"world.dwv",false,true,19971008))throw std::runtime_error("Startup load failed");active=play;if(!play){ui.scanWorlds(saves);ui.selectPage(Ui::TITLE);ui.render(nullptr,0);if(!hidden)ui.present(window.handle);}
  auto save=[&](){
   if(!active||!current.scene||!renderer)return std::filesystem::path{};
   try{Settings snapshot=current.options;storeWorldOptions(snapshot,current.policy);putWorldOption(snapshot,"world.daySeconds",current.daySeconds);putWorldOption(snapshot,"world.flying",current.player.isFlying());renderer->updateWorld(current.scene->pump(true));auto file=saves/("world-"+epoch+"-"+std::to_string(++saveCount)+".dwv");current.scene->save(file);
   if(current.stream){auto journal=file;journal.replace_extension(".dwg");current.stream->save(journal);}
   auto state=file;state.replace_extension(".state");auto tmp=state;tmp+=".tmp";
   {std::ofstream f(tmp);f<<std::setprecision(17)<<(current.stream?"DWSTATE3 ":"DWSTATE2 ")<<current.scene->logicalHash()<<' '<<current.camera.position.x<<' '<<current.camera.position.y<<' '<<current.camera.position.z<<' '<<current.camera.yaw<<' '<<current.camera.pitch<<' '<<current.seconds<<'\n';current.player.write(f);if(current.stream){current.needs.write(f);f<<current.stream->seed()<<' '<<std::quoted(utf8(current.name))<<' '<<std::quoted(current.id)<<'\n';}f.close();if(!f)throw std::runtime_error("State write failed");}
   auto info=file;info.replace_extension(".info");{std::ofstream f(info);f<<std::quoted(utf8(current.name))<<' '<<(current.stream?current.stream->seed():0)<<' '<<std::quoted(current.id)<<'\n';f.close();if(!f)throw std::runtime_error("World name write failed");}
   snapshot.save(worldSidecar(file,".options"));std::filesystem::rename(tmp,state);current.options=std::move(snapshot);ui.status=L"Мир сохранён";ui.scanWorlds(saves);ui.dirty=true;return file;}catch(const std::exception& e){ui.status=L"Не удалось сохранить: "+wide(e.what());ui.selectPage(Ui::PAUSE);return std::filesystem::path{};}
  };
  auto applySettings=[&](bool persist=true){
   if(window.full!=ui.settings.fullscreen)window.fullscreen(ui.settings.fullscreen);
   if(renderer)renderer->sync(ui.settings.vsync);
   audio.volume=ui.settings.volume*float(ui.settings.option("sound.players",1));
   audio.muted=!ui.settings.sound;third=ui.settings.third;
   resizeUi();ui.dirty=true;if(persist)persistSettings();
  };
  auto back=[&](bool commit=true){
   if(commit)ui.applyPendingSettings();persistSettings();
   if(!ui.backPage()){
    if(ui.page==Ui::PAUSE||ui.page==Ui::MATERIALS)ui.selectPage(active?Ui::GAME:Ui::TITLE);
    else if(ui.page==Ui::STATS||ui.page==Ui::WORLD_OPTIONS)ui.selectPage(Ui::PAUSE);
    else if(ui.page==Ui::FAULT)ui.selectPage(active?Ui::PAUSE:Ui::TITLE);
    else ui.selectPage(Ui::TITLE);
   }
   window.input.release();
  };
  auto selectedWorld=[&]()->WorldRow*{
   return ui.selectedWorld>=0&&size_t(ui.selectedWorld)<ui.worlds.size()?&ui.worlds[size_t(ui.selectedWorld)]:nullptr;
  };
  auto createDefaults=[&](int mode){
   Settings draft;draft.setOption("create.mode",mode);draft.setOption("create.difficulty",2);
   draft.setOption("create.gameMode",mode==2?2:0);draft.setOption("create.commands",mode==2);
   draft.setOption("create.worldType",mode==0?4:0);draft.setOption("create.structures",1);draft.setOption("create.bonusChest",0);
   draft.setOption("test.template",0);draft.setOption("test.radiusIndex",1);draft.setOption("test.shape",0);
   draft.setTextOption("create.biome","minecraft:snowy_taiga");applyWorldDraft(ui.settings,draft);
   ui.worldName=mode==2?L"DarkWinter — тестовый остров":L"DarkWinter";ui.seedText=mode==0?L"19971008":L"";
  };
  auto openFolder=[&](const std::filesystem::path& path){
   std::filesystem::create_directories(path);
   if(reinterpret_cast<INT_PTR>(ShellExecuteW(window.handle,L"open",path.c_str(),nullptr,nullptr,SW_SHOWNORMAL))<=32)
    throw std::runtime_error("The folder could not be opened");
  };
  auto noticePath=[&](int page)->std::filesystem::path{
   if(page==Ui::ATTRIBUTIONS)return folder/"attribution.html";
   if(page==Ui::LICENSES)return folder/"licenses.html";
   if(page==Ui::GUIDE)return folder/"accessibility.html";
   throw std::runtime_error("No document for this page");
  };
  auto noticeDestination=[&](int page){
   return page==Ui::XBOX_PRIVACY?std::wstring(L"https://aka.ms/MinecraftJavaXboxPrivacyAndSafety"):noticePath(page).wstring();
  };
  auto copyText=[&](const std::wstring& value){
   HGLOBAL data=GlobalAlloc(GMEM_MOVEABLE,(value.size()+1)*sizeof(wchar_t));
   if(!data)throw std::runtime_error("Clipboard allocation failed");
   auto target=static_cast<wchar_t*>(GlobalLock(data));
   if(!target){GlobalFree(data);throw std::runtime_error("Clipboard lock failed");}
   std::copy(value.begin(),value.end(),target);target[value.size()]=0;GlobalUnlock(data);
   if(!OpenClipboard(window.handle)){GlobalFree(data);throw std::runtime_error("Clipboard unavailable");}
   bool copied=EmptyClipboard()&&SetClipboardData(CF_UNICODETEXT,data)!=nullptr;CloseClipboard();
   if(!copied){GlobalFree(data);throw std::runtime_error("Clipboard write failed");}
  };
  auto command=[&](int id){
   try{
    if(id==900){cancelLoading=true;return;}
    if(id>=400&&id<500){ui.focusField(id);return;}
    if(id==700)createDefaults(0);
    if(id==701)createDefaults(2);
    if(id==631||id==632||id==636)ui.textFields[460]=noticeDestination(id==631?Ui::ATTRIBUTIONS:id==632?Ui::LICENSES:Ui::XBOX_PRIVACY);
    if(id==609){ui.textFields[460]=noticePath(Ui::GUIDE).wstring();ui.openPage(Ui::GUIDE);return;}
    if((id==634||id==635)&&(ui.page==Ui::ATTRIBUTIONS||ui.page==Ui::LICENSES||ui.page==Ui::GUIDE||ui.page==Ui::XBOX_PRIVACY)){
     auto target=noticeDestination(ui.page);if(ui.page!=Ui::XBOX_PRIVACY&&!std::filesystem::is_regular_file(std::filesystem::path(target)))throw std::runtime_error("The local document is unavailable");
     if(id==635)copyText(target);
     else if(reinterpret_cast<INT_PTR>(ShellExecuteW(window.handle,L"open",target.c_str(),nullptr,nullptr,SW_SHOWNORMAL))<=32)throw std::runtime_error("The local document could not be opened");
     back();return;
    }
    if(ui.activateNavigation(id)){applySettings();window.input.release();return;}
    if(ui.activateOption(id)){
     applySettings();return;
    }
    if(id==0||id==203||id==302){back();return;}
    if(id==201){createDefaults(0);ui.openPage(Ui::WORLD_MODE);}
    else if(id==233){createDefaults(1);ui.openPage(Ui::CREATE);}
    else if(id==230||id==231||id==232){int difficulty=int(ui.settings.option("create.difficulty",2));ui.settings.setOption("create.difficulty",1+(difficulty-1+(id==231?2:1))%3);ui.dirty=true;}
    else if(id==235||id==236){ui.settings.setOption("create.mode",0);ui.settings.setOption("create.worldType",4);ui.settings.setTextOption("create.biome","minecraft:snowy_taiga");ui.openPage(id==235?Ui::CREATE:Ui::CREATE_WORLD_TAB);}
    else if(id==237){ui.settings.setOption("test.template",(int(ui.settings.option("test.template",0))+1)%4);ui.dirty=true;}
    else if(id==238){ui.settings.setOption("test.radiusIndex",(int(ui.settings.option("test.radiusIndex",1))+1)%3);ui.dirty=true;}
    else if(id==239){ui.settings.setOption("test.shape",(int(ui.settings.option("test.shape",0))+1)%3);ui.dirty=true;}
    else if(id==240){
     ui.settings.setOption("create.mode",2);ui.settings.setOption("create.gameMode",2);ui.settings.setOption("create.commands",1);
     int type=int(ui.settings.option("test.template",0));ui.settings.setOption("create.worldType",type==2?1:0);
     const wchar_t* names[]={L"DarkWinter — тестовый остров",L"DarkWinter — обычный мир",L"DarkWinter — плоский полигон",L"DarkWinter — испытательная площадка"};
     ui.worldName=names[std::clamp(type,0,3)];ui.openPage(Ui::CREATE);
    }
    else if(id>=241&&id<=246){
     const char* key=id==241?"create.gameMode":id==242?"create.difficulty":id==243?"create.commands":id==244?"create.worldType":id==245?"create.structures":"create.bonusChest";
     int fallback=id==242?2:id==245?1:0,count=id==241?3:id==242?4:id==244?5:2;
     int value=(int(ui.settings.option(key,fallback))+1)%count;ui.settings.setOption(key,value);
     if(id==241){if(value==1)ui.settings.setOption("create.difficulty",3);if(value==2)ui.settings.setOption("create.commands",1);}
     ui.dirty=true;
    }
    else if(id==301&&(ui.page==Ui::CREATE||ui.page==Ui::CREATE_WORLD_TAB||ui.page==Ui::CREATE_MORE_TAB)){
     bool experimental=false;for(const auto& [key,value]:ui.settings.options)if(key.rfind("create.experiment.",0)==0&&value!=0)experimental=true;
     if(experimental){ui.status=L"Выбранные эксперименты Minecraft ещё не реализованы в нативной игре.";ui.dirty=true;return;}
     int mode=int(ui.settings.option("create.mode",0)),type=int(ui.settings.option("create.worldType",4));
     auto biome=ui.settings.textOption("create.biome","minecraft:snowy_taiga");
     if(mode!=0||type!=4||biome!="minecraft:snowy_taiga"){
      ui.status=L"В этой сборке доступна генерация заснеженной тайги для истории. Выбранный генератор ещё не перенесён.";
      ui.dirty=true;return;
     }
     if(ui.worldName.find_first_not_of(L" \t\r\n")==std::wstring::npos){ui.status=L"Введите название мира";ui.dirty=true;return;}
     if(load(folder/"world.dwv",false,true,seedFromText(ui.seedText)))active=true;
    }
    else if(id==220||id==221||id==222){
     if(auto row=selectedWorld()){
      if(id==220){Settings draft;draft.load(worldSidecar(row->file,".options"));applyWorldDraft(ui.settings,draft);ui.worldName=row->name;ui.seedText=std::to_wstring(row->seed);ui.openPage(Ui::CREATE);}
      if(id==221){ui.fieldValue(404)=row->name;ui.openPage(Ui::WORLD_EDIT);}
      if(id==222)ui.openPage(Ui::WORLD_DELETE_CONFIRM);
     }
    }
    else if(id==223){if(auto row=selectedWorld()){auto name=ui.fieldValue(404);renameWorld(saves,*row,name);if(current.id==row->id)current.name=name;ui.scanWorlds(saves);back();}}
    else if(id==225){if(auto row=selectedWorld()){auto target=worldBackup(saves,*row);ui.status=L"Резервная копия создана: "+target.filename().wstring();ui.dirty=true;}}
    else if(id==226){if(auto row=selectedWorld())openFolder(row->file.parent_path());}
    else if(id==227){if(auto row=selectedWorld()){auto icon=worldSidecar(row->file,".png");if(std::filesystem::is_regular_file(icon)){auto copy=worldBackup(saves,*row);std::filesystem::rename(icon,copy/"previous-icon.png");}ui.status=L"Иконка сброшена";ui.dirty=true;}}
    else if(id==228){ui.openPage(Ui::WORLD_OPTIMIZE);}
    else if(id==229)openFolder(saves/"backups");
    else if(id==247&&ui.page==Ui::WORLD_DELETE_CONFIRM){if(auto row=selectedWorld()){worldBackup(saves,*row,true);ui.scanWorlds(saves);ui.selectPage(Ui::WORLDS);ui.status=L"Мир удалён";}}
    else if(id==248)openFolder(saves/"datapacks");
    else if(ui.page==Ui::TITLE){
     if(id==10){ui.scanWorlds(saves);ui.openPage(Ui::WORLDS);}
     else if(id==11&&!ui.worlds.empty()){auto row=ui.worlds.front();if(load(row.file,true,row.native,row.seed))active=true;}
     else if(id==90)window.input.close=true;
    }
    else if(ui.page==Ui::PAUSE){
     if(id==1)ui.selectPage(Ui::GAME);
     else if(id==2)save();
     else if(id==4){if(save().empty())return;active=false;ui.selectPage(Ui::TITLE);}
    }
    else if(ui.page==Ui::WORLDS){
     if(id>=1000&&id<1000+int(ui.worlds.size())){ui.selectedWorld=id-1000;ui.dirty=true;}
     else if(id==202){if(auto row=selectedWorld()){auto copy=*row;if(load(copy.file,true,copy.native,copy.seed))active=true;}}
    }
    else if((id==250||id==260)&&ui.page==Ui::WORLD_OPTIONS){
     if(!active||!current.scene)throw std::runtime_error("No active world");
     Settings next=applyNativeWorldOptions(current.options,ui.settings,current.player.isFlying());
     const auto policy=nativeWorldOptions(next);
     current.player.changeGameMode(*current.collision,policy.personalGameMode);
     next.options.at("world.flying")=current.player.isFlying();
     current.options=std::move(next);current.policy=policy;
     clearWorldStaging(ui.settings);applyWorldDraft(ui.settings,current.options);
     worldOptionsResult="applied";ui.status.clear();ui.backToPage(Ui::PAUSE);
    }
    else if(id==740&&ui.page==Ui::WORLD_GAME_RULES&&active){
     Settings next=applyNativeGameRules(current.options,ui.settings,ruleCatalog);
     const auto policy=nativeWorldOptions(next);
     current.options=std::move(next);current.policy=policy;
     for(const auto& [key,value]:current.options.options)if(key.starts_with("rule."))ui.settings.setOption(key,value);
     std::erase_if(ui.settings.options,[](const auto& row){return row.first.starts_with("world.ruleDraft.");});
     worldRulesResult="applied";ui.status.clear();ui.backToPage(Ui::WORLD_OPTIONS);
    }
    else if(ui.page==Ui::MATERIALS){if(id>=5000&&current.scene&&uint32_t(id-5000)<current.scene->materials){ui.selected=uint32_t(id-5000);ui.dirty=true;}}
    else if(id==633)openFolder(folder/"resourcepacks");
    else if(id==622||id==623||id==624){ui.status=L"Сетевые соединения ещё не подключены к самостоятельной C++ игре.";ui.dirty=true;}
    window.input.release();
   }catch(const std::exception& e){if(id==250||id==260)worldOptionsResult="rejected";if(id==740)worldRulesResult="rejected";ui.status=L"Операция не завершена: "+wide(e.what());ui.dirty=true;window.input.release();}
  };
  uint64_t labSequence=0,labCapture=0,labHeldUntil=0;int labHeld=0,lastLabPage=ui.page;std::string labResult="ready",labCaptureFile;auto nextLabState=std::chrono::steady_clock::now();
  auto labPoll=[&](){if(!lab)return;if(ui.page!=lastLabPage){if(labHeld)window.input.keys[size_t(labHeld)]=false;labHeld=0;lastLabPage=ui.page;}uint64_t now=GetTickCount64();if(labHeld){window.input.keys[size_t(labHeld)]=now<labHeldUntil;if(now>=labHeldUntil)labHeld=0;}std::ifstream inbox(report/"inbox.txt");uint64_t pid=0,seq=0;std::string op;if(!(inbox>>pid>>seq>>op)||pid!=GetCurrentProcessId()||seq<=labSequence)return;labSequence=seq;labResult="rejected";int key=0;
   if(op=="click"){int page,id;if(inbox>>page>>id&&page==ui.page){auto b=std::find_if(ui.buttons.begin(),ui.buttons.end(),[&](const UiButton& b){return b.enabled&&b.id==id;});if(b!=ui.buttons.end()){RECT r;GetClientRect(window.handle,&r);int x=(b->x+b->w/2)*r.right/ui.w,y=(b->y+b->h/2)*r.bottom/ui.h;SendMessageW(window.handle,WM_MOUSEMOVE,0,MAKELPARAM(x,y));SendMessageW(window.handle,WM_LBUTTONDOWN,MK_LBUTTON,MAKELPARAM(x,y));SendMessageW(window.handle,WM_LBUTTONUP,0,MAKELPARAM(x,y));labResult="accepted";}}}
   else if(op=="text"&&ui.editing()){std::string value;if(inbox>>std::quoted(value)){auto w=wide(value);if(w.size()<=512){for(wchar_t c:w)SendMessageW(window.handle,WM_CHAR,c,0);labResult="accepted";}}}
   else if(op=="key"&&inbox>>key&&key>=1&&key<256){unsigned modifiers=0;inbox>>modifiers;
     if(modifiers<=3){if(modifiers&1)SendMessageW(window.handle,WM_KEYDOWN,VK_SHIFT,0);if(modifiers&2)SendMessageW(window.handle,WM_KEYDOWN,VK_CONTROL,0);
      SendMessageW(window.handle,WM_KEYDOWN,key,0);SendMessageW(window.handle,WM_KEYUP,key,0);
      if(modifiers&2)SendMessageW(window.handle,WM_KEYUP,VK_CONTROL,0);if(modifiers&1)SendMessageW(window.handle,WM_KEYUP,VK_SHIFT,0);labResult="accepted";}}
    else if(op=="scroll"){int delta,x,y;if(inbox>>delta>>x>>y&&std::abs(delta)<=100&&x>=0&&y>=0&&x<ui.w&&y<ui.h&&ui.page!=Ui::GAME){
     RECT rect;GetClientRect(window.handle,&rect);int px=x*rect.right/ui.w,py=y*rect.bottom/ui.h;
     SendMessageW(window.handle,WM_MOUSEMOVE,0,MAKELPARAM(px,py));SendMessageW(window.handle,WM_MOUSEWHEEL,MAKEWPARAM(0,SHORT(delta*WHEEL_DELTA)),MAKELPARAM(px,py));labResult="accepted";}}
    else if(op=="pointer"){int page,x,y;std::string phase;if(inbox>>page>>x>>y>>phase&&page==ui.page&&x>=0&&y>=0&&x<ui.w&&y<ui.h&&ui.page!=Ui::GAME){
     RECT rect;GetClientRect(window.handle,&rect);int px=x*rect.right/ui.w,py=y*rect.bottom/ui.h;
     if(phase=="move"||phase=="down"||phase=="up"){SendMessageW(window.handle,WM_MOUSEMOVE,window.input.pointerDown?MK_LBUTTON:0,MAKELPARAM(px,py));
      if(phase=="down")SendMessageW(window.handle,WM_LBUTTONDOWN,MK_LBUTTON,MAKELPARAM(px,py));if(phase=="up")SendMessageW(window.handle,WM_LBUTTONUP,0,MAKELPARAM(px,py));labResult="accepted";}}}
   else if(op=="hold"){int ms;if(inbox>>key>>ms&&key>=1&&key<256&&ms>=20&&ms<=5000&&ui.page==Ui::GAME){labHeld=key;labHeldUntil=now+uint64_t(ms);SendMessageW(window.handle,WM_KEYDOWN,key,0);labResult="accepted";}}
   else if(op=="look"){int yaw,pitch;if(inbox>>yaw>>pitch&&std::abs(yaw)<=180&&std::abs(pitch)<=90&&ui.page==Ui::GAME){window.input.dx+=int(yaw*.01745329252/.0025);window.input.dy+=int(pitch*.01745329252/.0025);labResult="accepted";}}
   else if(op=="weather"){int preset;if(active&&inbox>>preset&&preset>=-1&&preset<9){ui.weatherOverride=preset;ui.dirty=true;labResult="accepted";}}
   else if(op=="clock"){double seconds;if(active&&inbox>>seconds&&std::isfinite(seconds)&&seconds>=0&&seconds<=31536000){current.seconds=current.daySeconds=seconds;ui.dirty=true;labResult="accepted";}}
   else if(op=="capture"){window.input.capture=true;labCapture=seq;labResult="accepted";}
   else if(op=="quit"){window.input.close=true;labResult="accepted";}
  };
  auto labState=[&](){
   if(!lab||std::chrono::steady_clock::now()<nextLabState)return;
   nextLabState=std::chrono::steady_clock::now()+std::chrono::milliseconds(100);
   RECT client;GetClientRect(window.handle,&client);auto file=report/"state.json",temp=report/"state.tmp";std::ofstream f(temp);
   f<<std::setprecision(10)<<"{\"pid\":"<<GetCurrentProcessId()<<",\"sequence\":"<<labSequence<<",\"result\":"<<jsonString(labResult)
    <<",\"frame\":"<<frames<<",\"page\":"<<ui.page<<",\"clientWidth\":"<<client.right<<",\"clientHeight\":"<<client.bottom
    <<",\"uiWidth\":"<<ui.w<<",\"uiHeight\":"<<ui.h<<",\"focusedField\":"<<ui.focusedField
    <<",\"focusedValue\":"<<jsonString(ui.editing()&&ui.focusedField!=451?utf8(ui.fieldValue(ui.focusedField)):"")
    <<",\"bindingCapture\":"<<ui.bindingCapture<<",\"language\":"<<jsonString(ui.settings.textOption("language","ru_ru"))
    <<",\"progress\":"<<ui.progress<<",\"loadingKnown\":"<<(ui.loadingKnown?"true":"false")
    <<",\"loadingCompleted\":"<<ui.loadingCompleted<<",\"loadingTotal\":"<<ui.loadingTotal
    <<",\"loadingElapsedMillis\":"<<ui.loadingElapsedMillis<<",\"loadingFrame\":"<<loadingFrame
    <<",\"loadingStage\":"<<jsonString(utf8(ui.loadingStage))
    <<",\"worldActive\":"<<(active?"true":"false")<<",\"errorText\":"<<jsonString(utf8(ui.errorText))
    <<",\"worldOptionsResult\":"<<jsonString(worldOptionsResult)<<",\"worldRulesResult\":"<<jsonString(worldRulesResult)
    <<",\"firstPerson\":"<<((active&&actorMode&&!bodyVisible&&!current.player.noClip())?"true":"false")
    <<",\"rightShoulder\":"<<(ui.settings.rightShoulder?"true":"false")<<",\"world\":"<<jsonString(utf8(current.name))
    <<",\"worldId\":"<<jsonString(current.id)<<",\"worldCount\":"<<ui.worlds.size()<<",\"capture\":"<<jsonString(labCaptureFile)
    <<",\"status\":"<<jsonString(utf8(ui.status))<<",\"x\":"<<current.player.feet.x<<",\"y\":"<<current.player.feet.y
    <<",\"z\":"<<current.player.feet.z<<",\"yaw\":"<<current.player.yaw<<",\"pitch\":"<<current.player.pitch
    <<",\"stamina\":"<<current.player.stamina<<",\"seconds\":"<<current.seconds<<",\"daySeconds\":"<<current.daySeconds
    <<",\"visualDaySeconds\":"<<ui.settings.visualDaySeconds(current.daySeconds)<<",\"daylightPreview\":"<<(ui.settings.daylightPreview?"true":"false")
    <<",\"playerMode\":"<<current.player.mode()<<",\"flying\":"<<(current.player.isFlying()?"true":"false")
    <<",\"mayBuild\":"<<(current.player.mayBuild()?"true":"false")<<",\"noClip\":"<<(current.player.noClip()?"true":"false")
    <<",\"residentSections\":"<<(current.scene?current.scene->sections:0)<<",\"worldSettings\":{";
   bool firstSetting=true;
   auto setting=[&](const std::string& key,double value){if(!firstSetting)f<<',';firstSetting=false;f<<jsonString(key)<<':'<<value;};
   if(current.scene){
    for(const auto& [key,value]:current.options.options)
     if((key.starts_with("world.")&&key!="world.daySeconds"&&key!="world.flying")||key.starts_with("rule."))setting(key,value);
    setting("world.daySeconds",current.daySeconds);setting("world.flying",current.player.isFlying());
    if(!current.options.options.contains("rule.advance_time"))setting("rule.advance_time",current.policy.advanceTime);
    if(!current.options.options.contains("rule.spectators_generate_chunks"))setting("rule.spectators_generate_chunks",current.policy.spectatorsGenerateChunks);
   }
   f<<"},\"buttons\":[";
   bool first=true;for(const auto& b:ui.buttons){
    if(!first)f<<',';first=false;const auto* option=ui.optionFor(b.id);
    f<<"{\"id\":"<<b.id<<",\"enabled\":"<<(b.enabled?"true":"false")<<",\"label\":"<<jsonString(b.id==451?"":utf8(b.text))
     <<",\"x\":"<<b.x<<",\"y\":"<<b.y<<",\"width\":"<<b.w<<",\"height\":"<<b.h<<",\"kind\":"<<b.kind
     <<",\"targetPage\":"<<b.targetPage<<",\"optionKey\":"<<jsonString(b.optionKey)<<",\"amount\":"<<b.amount;
    if(option)f<<",\"value\":"<<ui.settings.option(option->key,option->defaultValue)<<",\"nativeSupported\":"<<(option->nativeSupported?"true":"false");
    else if(b.hasValue)f<<",\"value\":"<<b.value;
    f<<'}';
   }
   f<<"]}\n";f.close();MoveFileExW(temp.c_str(),file.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH);
  };
  labDuringLoad=[&](){labPoll();};
  labAfterLoadingFrame=[&](){
   if(window.input.capture){
    auto image=report/(labCapture?"lab-"+std::to_string(GetCurrentProcessId())+"-"+std::to_string(labCapture)+".png":"loading-"+std::to_string(loadRevision)+"-"+std::to_string(loadingFrame)+".png");
    write_png(image,ui.pixels[0],ui.pixels[1],reinterpret_cast<const uint8_t*>(ui.pixels.data()+4));
    if(labCapture){labCaptureFile=image.filename().string();labCapture=0;}window.input.capture=false;
   }
   labState();
  };
  auto uiClick=[&](int id){ui.render(current.scene.get(),current.seconds);auto it=std::find_if(ui.buttons.begin(),ui.buttons.end(),[&](const UiButton& b){return b.enabled&&b.id==id;});if(it==ui.buttons.end())throw std::runtime_error("Exercise button unavailable: "+std::to_string(id));RECT r;GetClientRect(window.handle,&r);int x=(it->x+it->w/2)*r.right/ui.w,y=(it->y+it->h/2)*r.bottom/ui.h;SendMessageW(window.handle,WM_MOUSEMOVE,0,MAKELPARAM(x,y));SendMessageW(window.handle,WM_LBUTTONDOWN,MK_LBUTTON,MAKELPARAM(x,y));SendMessageW(window.handle,WM_LBUTTONUP,0,MAKELPARAM(x,y));};
  auto start=std::chrono::steady_clock::now(),prior=start;uint64_t oracleTotal=0,oracleEquivalentTotal=0,oracleAmbiguousTotal=0,sourceTextureFramesTotal=0,filteredTextureFramesTotal=0;
  auto uiPointer=[&](int id,float ratio,UINT event){ui.render(current.scene.get(),current.seconds);auto b=std::find_if(ui.buttons.begin(),ui.buttons.end(),[&](const UiButton& b){return b.id==id&&b.enabled;});if(b==ui.buttons.end())throw std::runtime_error("Exercise slider unavailable");RECT r;GetClientRect(window.handle,&r);int x=int(b->x+b->w*ratio)*r.right/ui.w,y=(b->y+b->h/2)*r.bottom/ui.h;SendMessageW(window.handle,WM_MOUSEMOVE,event==WM_LBUTTONUP?0:MK_LBUTTON,MAKELPARAM(x,y));if(event!=WM_MOUSEMOVE)SendMessageW(window.handle,event,event==WM_LBUTTONDOWN?MK_LBUTTON:0,MAKELPARAM(x,y));};
  int activeSlider=-1;
  auto setSlider=[&](int id,float value){if(!ui.setOptionSlider(id,value))return false;applySettings(false);return true;};
  auto keyCode=[&](const std::string& name,unsigned fallback){auto binding=std::find_if(ui.keyBindings.begin(),ui.keyBindings.end(),[&](const UiKeyBinding& b){return b.key==name;});if(binding!=ui.keyBindings.end())fallback=binding->initialKey;return unsigned(std::clamp(ui.settings.option("key."+name,fallback),0.,255.));};
  auto held=[&](const std::string& name,unsigned fallback){unsigned key=keyCode(name,fallback);if(key==VK_LSHIFT||key==VK_RSHIFT)key=VK_SHIFT;if(key==VK_LCONTROL||key==VK_RCONTROL)key=VK_CONTROL;return key!=0&&window.input.keys[key];};
  bool sprintToggled=false;

  for(;;){
   while(!window.input.close&&(!limit||frames<limit)){
   pumpMessages();labPoll();auto frameLoadRevision=loadRevision;auto now=std::chrono::steady_clock::now();float dt=std::clamp(std::chrono::duration<float>(now-prior).count(),0.f,.1f);prior=now;auto& input=window.input;resizeUi();
   if(input.toggleFullscreen){doFullscreen();input.toggleFullscreen=false;}
   if(exercise){
    if(frames==5){SendMessageW(window.handle,WM_KEYDOWN,VK_F11,0);if(!input.toggleFullscreen)throw std::runtime_error("F11 binding missing");input.toggleFullscreen=false;doFullscreen();RECT c;GetClientRect(window.handle,&c);MONITORINFO m{sizeof(MONITORINFO)};GetMonitorInfoW(MonitorFromWindow(window.handle,MONITOR_DEFAULTTONEAREST),&m);fullscreenVerified=window.full&&c.right==m.rcMonitor.right-m.rcMonitor.left&&c.bottom==m.rcMonitor.bottom-m.rcMonitor.top;}
    if(frames==15){SendMessageW(window.handle,WM_SYSKEYDOWN,VK_RETURN,1LL<<29);if(!input.toggleFullscreen)throw std::runtime_error("Alt+Enter binding missing");input.toggleFullscreen=false;doFullscreen();RECT c;GetClientRect(window.handle,&c);windowRestored=!window.full&&c.right==settings.width&&c.bottom==settings.height;}
    if(frames==25)uiClick(10);if(frames==30)uiClick(201);
    if(frames==31)uiClick(700);if(frames==32)uiClick(235);
    if(frames==33){uiClick(400);input.selectAll=true;for(wchar_t c:std::wstring(128,L'Я'))SendMessageW(window.handle,WM_CHAR,c,0);}

    if(frames==35||frames==40)uiClick(301);
    if(frames==36){if(ui.page!=Ui::TITLE)throw std::runtime_error("Loading cancellation did not return to title");uiClick(10);}
    if(frames==37)uiClick(201);if(frames==38)uiClick(700);if(frames==39)uiClick(235);
    if(frames==55)SendMessageW(window.handle,WM_KEYDOWN,'W',0);
    if(frames==75)SendMessageW(window.handle,WM_KEYDOWN,VK_SPACE,0);
    if(frames==76)SendMessageW(window.handle,WM_KEYUP,VK_SPACE,0);
    if(frames==100)SendMessageW(window.handle,WM_KEYDOWN,VK_F5,0);if(frames==105)firstPersonVerified=!third;if(frames==130)SendMessageW(window.handle,WM_KEYDOWN,VK_F5,0);if(frames==135||frames==140)SendMessageW(window.handle,WM_KEYDOWN,'V',0);
    if(frames==145){SendMessageW(window.handle,WM_KEYUP,'W',0);savedFile=save();std::ostringstream p;current.player.write(p);savedPlayer=p.str();}
    if(frames==160){pauseTick=current.player.ticks;ui.selectPage(Ui::PAUSE);current.player.suspend();}
    if(frames==170){pauseVerified=current.player.ticks==pauseTick;uiClick(3);}
    if(frames==174){ui.settings.setOption("guiScale",1);resizeUi();}
    if(frames==175)uiClick(21);
    if(frames==176)uiPointer(106,.2f,WM_LBUTTONDOWN);if(frames==177)uiPointer(106,.8f,WM_MOUSEMOVE);
    if(frames==178){if(ui.settings.brightness<1.2f)throw std::runtime_error("Slider drag failed");uiPointer(106,.8f,WM_LBUTTONUP);}
    if(frames==179){uiPointer(106,.5f,WM_LBUTTONDOWN);uiPointer(106,.5f,WM_LBUTTONUP);}
    if(frames==180)uiClick(0);
    if(frames==181){uiPointer(107,.6f,WM_LBUTTONDOWN);uiPointer(107,.6f,WM_LBUTTONUP);}
    if(frames==182){SendMessageW(window.handle,WM_MOUSEMOVE,0,MAKELPARAM(0,0));SendMessageW(window.handle,WM_KEYDOWN,VK_LEFT,0);}
    if(frames==183){if(ui.settings.fov<76||ui.settings.fov>79)throw std::runtime_error("Slider keyboard adjustment failed");uiPointer(107,.5f,WM_LBUTTONDOWN);uiPointer(107,.5f,WM_LBUTTONUP);}
    if(frames==184)uiClick(21);
    if(frames==186)uiClick(110);if(frames==187){if(ui.settings.shadows)throw std::runtime_error("Shadow toggle failed");uiClick(110);}
    if(frames==188){ui.settings.setOption("clouds",2);uiClick(114);}if(frames==189){if(ui.settings.clouds)throw std::runtime_error("Cloud cycle failed");uiClick(114);}
    if(frames==190)uiClick(24);if(frames==200)uiClick(136);if(frames==205)uiClick(136);
    if(frames==210){ui.settings.scale=1;resizeUi();ui.selectPage(Ui::PAUSE);command(1);}
    if(frames==230){actorMode=false;current.camera.position={8.5f,110,8.5f};current.camera.yaw=0;current.camera.pitch=0;SendMessageW(window.handle,WM_KEYDOWN,'W',0);input.speed=8;}
    if(frames==590)SendMessageW(window.handle,WM_KEYUP,'W',0);
    if(frames==650){SendMessageW(window.handle,WM_KEYDOWN,VK_ESCAPE,0);}
    if(frames==654)uiClick(4);if(frames==658){if(ui.worlds.size()!=1)throw std::runtime_error("Snapshots duplicated in world list");uiClick(10);}if(frames==662)uiClick(202);
    if(frames==610){if(current.stream)totalColumns+=current.stream->generatedColumns;current.stream->generatedColumns=0;if(!load(savedFile,true,true,19971008))throw std::runtime_error("Saved native world reload failed");std::ostringstream p;current.player.write(p);restored=p.str()==savedPlayer;actorMode=true;}
   }
   if(input.close)break;
   RECT client;GetClientRect(window.handle,&client);int mx=client.right?input.mx*ui.w/client.right:0,my=client.bottom?input.my*ui.h/client.bottom:0;int hovered=ui.button(mx,my);if(input.mouseChanged&&hovered!=ui.hover){ui.hover=hovered;ui.dirty=true;}input.mouseChanged=false;
   auto presses=std::move(input.presses);input.presses.clear();
   for(UINT key:presses){
    unsigned modifiers=input.modifiers.contains(key)?input.modifiers[key]:0;
    if(ui.handleKey(key,(modifiers&1)!=0,(modifiers&2)!=0)){applySettings();continue;}
    if(key==VK_ESCAPE){current.player.suspend();if(ui.page==Ui::GAME)ui.openPage(Ui::PAUSE);else if(ui.page==Ui::WORLD_GAME_RULES)command(741);else if(ui.page==Ui::WORLD_DIFFICULTY_LOCK_CONFIRM)command(261);else if(ui.page==Ui::WORLD_RULES_DISCARD_CONFIRM)command(263);else back(false);input.release();sprintToggled=false;}
    else if(key==VK_RETURN){
     if(ui.page==Ui::GAME){}else if(ui.page==Ui::MATERIALS)ui.selectPage(Ui::GAME);
     else if(ui.editing()&&(ui.page==Ui::CREATE||ui.page==Ui::CREATE_WORLD_TAB))command(301);
     else if(ui.focus>=0||ui.hover>=0)command(ui.focus>=0?ui.focus:ui.hover);
     else if(ui.page==Ui::WORLDS)command(ui.worlds.empty()?201:202);
     else if(ui.page==Ui::TITLE)command(10);else if(ui.page==Ui::PAUSE)command(1);
    }
    else if(key==keyCode("inventory",VK_TAB)&&key!=0&&active&&(ui.page==Ui::GAME||ui.page==Ui::MATERIALS)){ui.selectPage(ui.page==Ui::GAME?Ui::MATERIALS:Ui::GAME);input.release();}
    else if(key==VK_F6&&active)save();
    else if(key==keyCode("togglePerspective",VK_F5)&&ui.page==Ui::GAME){third=!third;ui.settings.third=third;persistSettings();}
    else if(key==keyCode("darkwinter.switch_shoulder",'V')&&ui.page==Ui::GAME){ui.settings.rightShoulder=!ui.settings.rightShoulder;persistSettings();}
    else if(key==keyCode("screenshot",VK_F2))input.capture=true;
    else if(key==VK_F8&&ui.page==Ui::GAME){actorMode=!actorMode;input.release();}
    else if((key==VK_LEFT||key==VK_RIGHT)&&ui.page==Ui::MATERIALS){ui.selected=uint32_t(std::clamp(int(ui.selected)+(key==VK_RIGHT?1:-1),1,int(current.scene->materials)-1));ui.dirty=true;}
    if(ui.page==Ui::GAME&&ui.settings.option("toggleSprint",0)!=0){
     unsigned sprint=keyCode("sprint",VK_LSHIFT);
     if(key==sprint||(key==VK_SHIFT&&(sprint==VK_LSHIFT||sprint==VK_RSHIFT)))sprintToggled=!sprintToggled;
    }
   }
   input.modifiers.clear();
   if(input.selectAll)ui.handleKey('A',false,true);
   for(wchar_t c:input.characters)ui.inputText(c);
   input.characters.clear();input.selectAll=false;
   if(input.scroll)ui.scroll(input.scroll,mx,my);input.scroll=0;
   if(input.click&&ui.page!=Ui::GAME){
    int id=ui.button(mx,my);if(id>=0)ui.focus=id;
    auto b=std::find_if(ui.buttons.begin(),ui.buttons.end(),[&](const UiButton& q){return q.id==id&&q.enabled;});
    if(b!=ui.buttons.end()&&b->kind==2){ui.focusField(id,mx);input.editing=true;}
    else if(b!=ui.buttons.end()&&b->amount>=0){
     float value=b->id==690?float(my-b->y)/std::max(1,b->h):float(mx-b->x)/std::max(1,b->w);
     setSlider(id,value);if(input.pointerDown)activeSlider=id;else persistSettings();
    }else if(id>=0){bool playWorld=input.doubleClick&&ui.page==Ui::WORLDS&&id>=1000&&id<2000;command(id);if(playWorld)command(202);}
    else ui.focusedField=-1;
    input.click=false;input.doubleClick=false;
   }
   if(activeSlider>=0){
    if(input.pointerDown){auto b=std::find_if(ui.buttons.begin(),ui.buttons.end(),[&](const UiButton& q){return q.id==activeSlider;});
     if(b!=ui.buttons.end())setSlider(activeSlider,b->id==690?float(my-b->y)/std::max(1,b->h):float(mx-b->x)/std::max(1,b->w));
    }else{activeSlider=-1;persistSettings();}
   }
   input.editing=ui.editing();input.bindingCapture=ui.bindingCapture>=0;input.fullscreenKey=keyCode("fullscreen",VK_F11);
   input.game=ui.page==Ui::GAME;input.actorMode=actorMode;
   if(renderMotion&&ui.page==Ui::GAME){
     actorMode=false;double phase=double(frames)/60.;
     current.camera={current.player.eye()+V3{float(std::sin(phase)*.75),0,float(std::cos(phase)*.25)},float(std::sin(phase)*.3),-.12f};
    }
    if(input.game&&current.scene){double elapsed=exercise?1./60:dt;if(!hidden&&!exercise&&!lab&&GetForegroundWindow()!=window.handle){current.player.suspend();ui.selectPage(Ui::PAUSE);input.release();elapsed=0;}
    if(actorMode){if(!hidden&&!exercise&&!lab&&ui.page==Ui::GAME)grabMouse(window.handle,input);float dx=input.dx*.005f*ui.settings.sensitivity*(ui.settings.option("invertMouseX",0)?-1.f:1.f),dy=-input.dy*.005f*ui.settings.sensitivity*(ui.settings.option("invertMouseY",0)?-1.f:1.f);input.dx=input.dy=0;
     dx+=(float(input.keys[VK_RIGHT])-float(input.keys[VK_LEFT]))*float(elapsed);dy+=(float(input.keys[VK_UP])-float(input.keys[VK_DOWN]))*float(elapsed);
     if(third){if(dx*(targetYaw-current.player.yaw)<0)targetYaw=current.player.yaw;if(dy*(targetPitch-current.player.pitch)<0)targetPitch=current.player.pitch;targetYaw=current.player.yaw+std::clamp(targetYaw-current.player.yaw+dx,-.35f,.35f);targetPitch=std::clamp(targetPitch+dy,-1.5f,1.5f);float b=1-std::exp(-float(elapsed)/.085f);current.player.yaw+=std::clamp((targetYaw-current.player.yaw)*b,-3.665f*float(elapsed),3.665f*float(elapsed));current.player.pitch+=std::clamp((targetPitch-current.player.pitch)*b,-2.618f*float(elapsed),2.618f*float(elapsed));}
     else{current.player.yaw+=dx;current.player.pitch=std::clamp(current.player.pitch+dy,-1.5f,1.5f);targetYaw=current.player.yaw;targetPitch=current.player.pitch;}
     PlayerInput motion{float(held("forward",'W'))-float(held("back",'S')),float(held("right",'D'))-float(held("left",'A')),ui.settings.option("toggleSprint",0)!=0?sprintToggled:held("sprint",VK_LSHIFT),held("jump",VK_SPACE)||(keyCode("jump",VK_SPACE)==VK_SPACE&&input.jumpBuffered),held("sneak",VK_LCONTROL)};auto oldTicks=current.player.ticks,oldSteps=current.player.steps,oldJumps=current.player.jumps,oldClimbs=current.player.autoSteps;double oldDistance=current.player.distance;
     current.player.advance(*current.collision,motion,elapsed);if(current.player.ticks!=oldTicks)input.jumpBuffered=false;travelled+=current.player.distance-oldDistance;steps+=current.player.steps-oldSteps;jumps+=current.player.jumps-oldJumps;climbs+=current.player.autoSteps-oldClimbs;
     if(current.player.steps!=oldSteps){auto feet=current.player.feet;auto ground=current.scene->cell(int(std::floor(feet.x)),int(std::floor(feet.y-.08f)),int(std::floor(feet.z)));audio.step(current.scene->materialName(ground),(steps&1)!=0,current.player.running?1.2f:1.f);}if(physicsMs.size()<65536)physicsMs.push_back(current.player.lastCpuMs);current.camera=current.player.view(*current.collision,third,bodyVisible,ui.settings.rightShoulder?1.f:-1.f);
    }else{cameraInput(current.camera,input,float(elapsed));bodyVisible=false;}
    if(current.stream&&(!current.player.noClip()||current.policy.spectatorsGenerateChunks))current.stream->tick(actorMode?current.player.feet:current.camera.position);current.seconds+=elapsed;if(current.policy.advanceTime)current.daySeconds+=elapsed;
   }else{input.dx=input.dy=0;targetYaw=current.player.yaw;targetPitch=current.player.pitch;}
   if(current.scene&&renderer){renderer->updateWorld(current.scene->pump());renderer->actor(current.player.visualFeet(),current.player.bodyYaw,current.player.phase,current.player.gait,current.player.yaw,current.player.pitch,actorMode&&bodyVisible&&active&&!current.player.noClip(),actorMode&&!bodyVisible&&active&&!current.player.noClip());}
   bool captureClick=input.click&&ui.page==Ui::GAME&&!input.looking&&!hidden;if(captureClick){grabMouse(window.handle,input);input.click=false;}
   auto pressed=[&](unsigned key){return key!=0&&std::find(presses.begin(),presses.end(),key)!=presses.end()&&!(captureClick&&key==VK_LBUTTON);};
   bool place=pressed(keyCode("use",VK_RBUTTON)),attack=pressed(keyCode("attack",VK_LBUTTON));
   if((attack||place)&&ui.page==Ui::GAME&&renderer&&current.scene&&current.player.mayBuild()){auto p=parameters(current.camera,renderer->width(),renderer->height(),false);V3 ray=direction(p,renderer->width()/2,renderer->height()/2);auto hit=current.scene->reference(current.camera.position,ray,UINT32_MAX,8.f);if(hit.material!=UINT32_MAX&&hit.distance<8){V3 point=current.camera.position+ray*hit.distance,origin=current.player.feet+V3{0,Player::eyeHeight,0},delta=point-origin;float range=std::sqrt(dot(delta,delta));bool reachable=!actorMode;
     if(actorMode&&range>0&&range<=(current.player.mode()==1?5.f:4.5f)){auto blocked=current.scene->reference(origin,delta*(1/range),UINT32_MAX,range+.02f);reachable=blocked.material!=UINT32_MAX&&blocked.distance>=range-.02f;}
     if(reachable){int x=int(hit.cell.x),y=int(hit.cell.y),z=int(hit.cell.z);if(place){auto n=hit.normal;if(std::abs(n.x)>=std::abs(n.y)&&std::abs(n.x)>=std::abs(n.z))x+=n.x>0?1:-1;else if(std::abs(n.y)>=std::abs(n.z))y+=n.y>0?1:-1;else z+=n.z>0?1:-1;if(current.player.canPlace(*current.collision,x,y,z,ui.selected))current.scene->edit(x,y,z,ui.selected);}else current.scene->edit(x,y,z,0);}}
    input.click=false;
   }
   if(ui.page==Ui::GAME)input.click=false;
   ui.activeWorld=active;ui.sessionDistance=current.player.distance;ui.sessionSteps=current.player.steps;ui.sessionJumps=current.player.jumps;ui.worldSeed=current.stream?current.stream->seed():0;ui.worldDaySeconds=ui.settings.visualDaySeconds(current.daySeconds);ui.animate(float(std::chrono::duration<double>(now-start).count()));ui.playerState(current.player.stamina,current.player.exhausted,actorMode,third,current.needs);bool dirty=ui.dirty||(ui.minute!=uint64_t(ui.worldDaySeconds*18)/60||ui.weatherTick!=uint64_t(current.seconds));ui.render(current.scene.get(),current.seconds);if(renderer&&dirty)renderer->overlay(ui.pixels);if(renderer){renderer->sampleWorld(ui.page==Ui::GAME);renderer->environment(current.seconds,ui.worldSeed,ui.weatherOverride,ui.worldDaySeconds);renderer->video(ui.settings.brightness,ui.settings.fov,ui.settings.viewDistance,ui.settings.fog,ui.settings.shadows,ui.settings.clouds,ui.settings.ambient);renderer->animate(float(std::chrono::duration<double>(now-start).count()));}
   if(IsIconic(window.handle)){MsgWaitForMultipleObjectsEx(0,nullptr,50,QS_ALLINPUT,MWMO_INPUTAVAILABLE);continue;}
   bool last=limit&&frames+1==limit,shot=(exercise&&(frames==0||frames==10||frames==28||frames==32||frames==120||frames==164||frames==178||frames==202||frames==500||frames==625||frames==668))||(renderMotion&&(frames==60||frames==180||frames==300));
   auto image=report/(labCapture?"lab-"+std::to_string(GetCurrentProcessId())+"-"+std::to_string(labCapture)+".png":"frame-"+std::to_string(frames)+".png");bool oracle=verify&&ui.page==Ui::GAME&&(last||(exercise&&(frames==120||frames==500||frames==625)));
   if(renderer){uint32_t beforeRays=renderer->oracleRays,beforeEquivalent=renderer->oracleEquivalent,beforeAmbiguous=renderer->oracleAmbiguous;uint64_t beforeSource=renderer->sourceTextureOracleFrames,beforeFiltered=renderer->filteredTextureFrames;if(!renderer->draw(current.camera,last||shot||input.capture,image,oracle))continue;oracleTotal+=renderer->oracleRays-beforeRays;oracleEquivalentTotal+=renderer->oracleEquivalent-beforeEquivalent;oracleAmbiguousTotal+=renderer->oracleAmbiguous-beforeAmbiguous;sourceTextureFramesTotal+=renderer->sourceTextureOracleFrames-beforeSource;filteredTextureFramesTotal+=renderer->filteredTextureFrames-beforeFiltered;}else{if(last||shot||input.capture)write_png(image,ui.pixels[0],ui.pixels[1],reinterpret_cast<const uint8_t*>(ui.pixels.data()+4));if(!hidden)ui.present(window.handle);MsgWaitForMultipleObjectsEx(0,nullptr,16,QS_ALLINPUT,MWMO_INPUTAVAILABLE);}
   if(renderer&&renderer->oracleFailures){std::ofstream proof(report/"oracle-errors.json");proof<<"[";for(size_t i=0;i<renderer->oracleDetails.size();i++){if(i)proof<<",";proof<<renderer->oracleDetails[i];}proof<<"]\n";throw std::runtime_error("GPU/CPU scene oracle mismatch");}if(labCapture&&input.capture){labCaptureFile=image.filename().string();labCapture=0;}if(ui.page==Ui::GAME&&loadRevision==frameLoadRevision&&!oracle&&!shot&&frames>55&&frameMs.size()<65536)frameMs.push_back(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-now).count());input.capture=false;++frames;labState();if(!exercise&&!hidden){int cap=ui.page==Ui::GAME?ui.settings.fps:60;if(ui.settings.backgroundLimit&&GetForegroundWindow()!=window.handle)cap=cap?std::min(cap,30):30;if(cap>0){auto deadline=now+std::chrono::microseconds(1000000/cap);std::this_thread::sleep_until(deadline);}}
  }
  if(active&&!exercise&&!limit&&save().empty()){window.input.close=false;window.input.release();ui.selectPage(Ui::PAUSE);continue;}
   break;
   }
   if(renderer){renderer->drain();gpuTimes.insert(gpuTimes.end(),renderer->gpuMs.begin(),renderer->gpuMs.end());}auto loaded=modules();uint64_t generated=totalColumns+(current.stream?current.stream->generatedColumns:0);
  bool ok=!loaded.second&&(!limit||frames==limit)&&(!verify||oracleTotal>300)&&(!exercise||(fullscreenVerified&&windowRestored&&pauseVerified&&firstPersonVerified&&loadCancelled&&restored&&generated>81));
  std::ofstream out(report/"stage6.json");out<<std::setprecision(9)<<"{\"completed\":"<<(ok?"true":"false")<<",\"jvm\":"<<(loaded.second?"true":"false")<<",\"antialiasingSamples\":"<<(renderer?renderer->antialiasingSamples():0)<<",\"validationEnabled\":"<<(renderer&&renderer->validationEnabled?"true":"false")<<",\"validationErrors\":"<<(renderer?renderer->validationErrors.load():0)<<",\"frames\":"<<frames<<",\"fullscreen\":"<<(fullscreenVerified?"true":"false")<<",\"windowRestored\":"<<(windowRestored?"true":"false")<<",\"pause\":"<<(pauseVerified?"true":"false")<<",\"firstPerson\":"<<(firstPersonVerified?"true":"false")<<",\"reload\":"<<(restored?"true":"false")<<",\"generatedColumns\":"<<generated<<",\"residentSections\":"<<(current.scene?current.scene->sections:0)<<",\"journalEdits\":"<<(current.stream?current.stream->editCount():0)<<",\"oracleRays\":"<<oracleTotal<<",\"sourceTextureOracleFrames\":"<<sourceTextureFramesTotal<<",\"filteredTextureFrames\":"<<filteredTextureFramesTotal<<",\"textureOracleMode\":\"point_source_geometry_only\""<<",\"oracleEquivalent\":"<<oracleEquivalentTotal<<",\"oracleAmbiguous\":"<<oracleAmbiguousTotal<<",\"gpuMedianMs\":"<<percentile(gpuTimes,.5)<<",\"physicsMedianMs\":"<<percentile(physicsMs,.5)<<",\"frameP95Ms\":"<<percentile(frameMs,.95)<<",\"frameP99Ms\":"<<percentile(frameMs,.99)<<",\"distance\":"<<travelled<<",\"autoSteps\":"<<climbs<<",\"jumps\":"<<jumps<<",\"steps\":"<<steps<<",\"cancelledLoads\":"<<(loadCancelled?1:0)<<",\"audioReady\":"<<(audio.enabled?"true":"false")<<"}\n";
  return ok?0:2;
 }catch(const std::exception& e){std::error_code ignored;std::filesystem::create_directories(report,ignored);std::ofstream out(report/"error.json");out<<"{\"completed\":false,\"error\":"<<jsonString(e.what())<<"}\n";if(!hidden)MessageBoxA(nullptr,e.what(),"DarkWinter",MB_ICONERROR|MB_OK);return 1;}
}
