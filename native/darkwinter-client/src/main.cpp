#include "darkwinter_visual_provider.hpp"
#include <shellapi.h>
#include <tlhelp32.h>
#include <cctype>
#include <array>
#include <iomanip>
#include <sstream>
#include <thread>

using namespace dw::client;
struct Input {
    std::array<bool,256> keys{};bool looking=false,raw=false,close=false,capture=false,home=false;
    int dx=0,dy=0,lastX=0,lastY=0;bool mouseValid=false;float speed=6;
    void release(){keys.fill(false);dx=dy=0;mouseValid=false;if(looking){looking=false;ReleaseCapture();ShowCursor(TRUE);}}
};
static LRESULT CALLBACK procedure(HWND h,UINT message,WPARAM w,LPARAM l){
    Input* input=reinterpret_cast<Input*>(GetWindowLongPtrW(h,GWLP_USERDATA));
    if(message==WM_NCCREATE){input=static_cast<Input*>(reinterpret_cast<CREATESTRUCTW*>(l)->lpCreateParams);SetWindowLongPtrW(h,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(input));}
    if(!input)return DefWindowProcW(h,message,w,l);
    switch(message){
        case WM_CLOSE:input->close=true;return 0;
        case WM_KILLFOCUS:input->release();return 0;
        case WM_KEYDOWN:case WM_SYSKEYDOWN:
            if(w<256)input->keys[w]=true;
            if(w==VK_ESCAPE)input->close=true;if(w==VK_F12&&!(l&(1LL<<30)))input->capture=true;if(w==VK_HOME)input->home=true;
            if(message==WM_SYSKEYDOWN&&w==VK_F4)return DefWindowProcW(h,message,w,l);
            return 0;
        case WM_KEYUP:case WM_SYSKEYUP:if(w<256)input->keys[w]=false;return 0;
        case WM_RBUTTONDOWN:
            if(!input->looking){input->looking=true;input->mouseValid=false;SetCapture(h);ShowCursor(FALSE);}return 0;
        case WM_RBUTTONUP:input->release();return 0;
        case WM_CAPTURECHANGED:if(input->looking){input->looking=false;ShowCursor(TRUE);}return 0;
        case WM_MOUSEWHEEL:input->speed=std::clamp(input->speed*std::pow(1.2f,float(GET_WHEEL_DELTA_WPARAM(w))/120),.5f,100.f);return 0;
        case WM_MOUSEMOVE:
            if(input->looking&&!input->raw){int x=short(LOWORD(l)),y=short(HIWORD(l));if(input->mouseValid){input->dx+=x-input->lastX;input->dy+=y-input->lastY;}input->lastX=x;input->lastY=y;input->mouseValid=true;}return 0;
        case WM_INPUT:{
            if(input->looking){RAWINPUT data{};UINT bytes=sizeof(data);if(GetRawInputData(reinterpret_cast<HRAWINPUT>(l),RID_INPUT,&data,&bytes,sizeof(RAWINPUTHEADER))==bytes&&data.header.dwType==RIM_TYPEMOUSE&&!(data.data.mouse.usFlags&MOUSE_MOVE_ABSOLUTE)){input->dx+=data.data.mouse.lLastX;input->dy+=data.data.mouse.lLastY;}}
            return DefWindowProcW(h,message,w,l);
        }
        case WM_GETMINMAXINFO:{auto* p=reinterpret_cast<MINMAXINFO*>(l);p->ptMinTrackSize={400,280};p->ptMaxTrackSize={3800,2100};return 0;}
        default:return DefWindowProcW(h,message,w,l);
    }
}
struct Window {
    Input input;HWND handle=nullptr;
    Window(uint32_t w,uint32_t h,bool hidden){
        SetProcessDPIAware();WNDCLASSW wc{};wc.lpfnWndProc=procedure;wc.hInstance=GetModuleHandleW(nullptr);wc.lpszClassName=L"DarkWinterStandaloneCore";wc.hCursor=LoadCursorW(nullptr,IDC_ARROW);
        if(!RegisterClassW(&wc)&&GetLastError()!=ERROR_CLASS_ALREADY_EXISTS)throw std::runtime_error("Window class registration failed");
        RECT r{0,0,LONG(w),LONG(h)};AdjustWindowRect(&r,WS_OVERLAPPEDWINDOW,FALSE);
        handle=CreateWindowExW(0,wc.lpszClassName,L"DarkWinter Core | Vulkan | WASD, Space/Ctrl, RMB look, Shift, Home, F12",WS_OVERLAPPEDWINDOW,CW_USEDEFAULT,CW_USEDEFAULT,r.right-r.left,r.bottom-r.top,nullptr,nullptr,wc.hInstance,&input);
        if(!handle)throw std::runtime_error("Win32 window creation failed");
        RAWINPUTDEVICE raw{1,2,0,handle};input.raw=RegisterRawInputDevices(&raw,1,sizeof(raw))==TRUE;
        ShowWindow(handle,hidden?SW_HIDE:SW_SHOW);if(!hidden)UpdateWindow(handle);
    }
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
int WINAPI WinMain(HINSTANCE,HINSTANCE,LPSTR,int){
    wchar_t module[32768];DWORD length=GetModuleFileNameW(nullptr,module,32768);auto folder=std::filesystem::path(std::wstring(module,length)).parent_path();
    bool hidden=false,verify=false,exercise=false,vsync=true,validation=true;uint32_t frameLimit=0,width=1280,height=720;
    auto world=folder/L"world.dwv",assets=folder/L"assets.dwa",shader=folder/L"trace.comp.spv";
    auto stamp=std::to_string(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count());
    auto report=folder/L"reports"/std::filesystem::path("viewer-"+stamp);
    Camera camera;std::string failure;int code=0;
    try{
        int argc=0;LPWSTR* argv=CommandLineToArgvW(GetCommandLineW(),&argc);if(!argv)throw std::runtime_error("Command-line decoding failed");
        std::vector<std::wstring> args;for(int i=1;i<argc;++i)args.emplace_back(argv[i]);LocalFree(argv);
        auto number=[](const std::wstring& text){size_t end=0;auto v=std::stoul(text,&end);if(end!=text.size()||v>1000000)throw std::runtime_error("Invalid integer option");return uint32_t(v);};
        for(size_t i=0;i<args.size();++i){
            auto value=[&]()->std::wstring{if(i+1>=args.size())throw std::runtime_error("Missing option value");return args[++i];};
            if(args[i]==L"--hidden")hidden=true;else if(args[i]==L"--verify")verify=true;else if(args[i]==L"--exercise-window")exercise=true;else if(args[i]==L"--no-vsync")vsync=false;else if(args[i]==L"--no-validation")validation=false;
            else if(args[i]==L"--frames")frameLimit=number(value());else if(args[i]==L"--width")width=number(value());else if(args[i]==L"--height")height=number(value());
            else if(args[i]==L"--world")world=value();else if(args[i]==L"--assets")assets=value();else if(args[i]==L"--shader")shader=value();else if(args[i]==L"--report")report=value();
            else throw std::runtime_error("Unknown option "+utf8(args[i]));
        }
        if(width<400||width>3840||height<280||height>2160)throw std::runtime_error("Resolution must be between 400x280 and 3840x2160");
        if(exercise&&frameLimit<160)throw std::runtime_error("Window exercise needs at least 160 frames");
        std::filesystem::create_directories(report);
        Scene scene(world,assets);Window window(width,height,hidden);
        Renderer renderer(window.handle,scene,shader,verify,vsync,validation,std::make_unique<DarkWinterVisualProvider>());
        std::vector<double> frameMs;frameMs.reserve(65536);
        auto start=std::chrono::steady_clock::now(),prior=start,titleTime=start;uint32_t frames=0,minimizeTests=0;bool inputMoved=false;
        while(!window.input.close&&(!frameLimit||frames<frameLimit)){
            MSG message;while(PeekMessageW(&message,nullptr,0,0,PM_REMOVE)){if(message.message==WM_QUIT)window.input.close=true;TranslateMessage(&message);DispatchMessageW(&message);}
            if(window.input.close)break;
            if(exercise){
                if(frames==5){SendMessageW(window.handle,WM_KEYDOWN,'W',0);SendMessageW(window.handle,WM_KEYDOWN,VK_RIGHT,0);}
                if(frames==20){SendMessageW(window.handle,WM_KEYUP,'W',0);SendMessageW(window.handle,WM_KEYUP,VK_RIGHT,0);inputMoved=std::abs(camera.position.z+7)>.1f&&std::abs(camera.yaw)>.1f;}
                if(frames==40)window.size(1024,640);
                if(frames==80)window.size(width,height);
                if(frames==100){ShowWindow(window.handle,SW_MINIMIZE);Sleep(20);ShowWindow(window.handle,hidden?SW_SHOWNOACTIVATE:SW_RESTORE);if(hidden)ShowWindow(window.handle,SW_HIDE);++minimizeTests;}
                if(frames==110)camera={{9.f,83.15f,3.5f},.15f,-.2f};
            }
            auto now=std::chrono::steady_clock::now();float dt=std::clamp(std::chrono::duration<float>(now-prior).count(),0.f,.1f);prior=now;
            cameraInput(camera,window.input,exercise?1.f/60:dt);
            if(IsIconic(window.handle)){MsgWaitForMultipleObjectsEx(0,nullptr,50,QS_ALLINPUT,MWMO_INPUTAVAILABLE);continue;}
            bool last=frameLimit&&frames+1==frameLimit;bool checkpoint=verify&&exercise&&(frames==35||frames==120);
            bool capture=window.input.capture||last||checkpoint;
            auto image=report/(checkpoint?(frames==35?"initial.png":"moved.png"):last?"final.png":("capture-"+std::to_string(GetTickCount64())+".png"));
            auto before=std::chrono::steady_clock::now();
            if(!renderer.draw(camera,capture,image,verify&&(last||checkpoint)))continue;
            double elapsed=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-before).count();
            if(frames>=30&&!capture){if(frameMs.size()<65536)frameMs.push_back(elapsed);else frameMs[frames%frameMs.size()]=elapsed;}
            ++frames;window.input.capture=false;
            if(now-titleTime>std::chrono::milliseconds(500)){
                std::wostringstream title;title<<L"DarkWinter Core | Vulkan | "<<renderer.width()<<L"x"<<renderer.height()<<L" | GPU "<<std::fixed<<std::setprecision(2)<<(renderer.gpuMs.empty()?0:renderer.gpuMs.back())<<L" ms | WASD + Space/Ctrl | RMB look | F12";
                SetWindowTextW(window.handle,title.str().c_str());titleTime=now;
            }
        }
        window.input.release();renderer.drain();
        auto loaded=modules();std::string materialPixels="[";for(size_t i=0;i<renderer.materialPixels.size();++i){if(i)materialPixels+=',';materialPixels+=std::to_string(renderer.materialPixels[i]);}materialPixels+=']';
        bool completed=!loaded.second&&(!frameLimit||frames==frameLimit)&&(!verify||(renderer.oracleRays>300&&renderer.oracleFailures==0))&&(!exercise||(inputMoved&&renderer.resizeCount>=2&&minimizeTests==1))&&renderer.validationErrors.load()==0;
        if(!completed)code=2;
        std::ofstream out(report/"viewer.json");out<<std::setprecision(8)
            <<"{\"completed\":"<<(completed?"true":"false")<<",\"jvm\":false,\"renderer\":\"Vulkan compute voxel traversal + shared shape BVH\",\"device\":"<<jsonString(renderer.deviceName)
            <<",\"loadedModules\":"<<loaded.first<<",\"frames\":"<<frames<<",\"width\":"<<renderer.width()<<",\"height\":"<<renderer.height()<<",\"vsync\":"<<(vsync?"true":"false")
            <<",\"sections\":"<<scene.sections<<",\"materials\":"<<scene.materials<<",\"renderInstances\":"<<scene.instances.size()<<",\"nativeWorkers\":"<<scene.workers<<",\"peakWorkers\":"<<scene.peak_workers
            <<",\"worldFnv64\":"<<jsonString(std::to_string(scene.world_hash))<<",\"worldGpuBytes\":"<<scene.world.size()*4<<",\"assetGpuBytes\":"<<scene.assets.size()*4
            <<",\"materialPixels\":"<<materialPixels<<",\"oracleRays\":"<<renderer.oracleRays<<",\"sourceTextureOracleFrames\":"<<renderer.sourceTextureOracleFrames<<",\"filteredTextureFrames\":"<<renderer.filteredTextureFrames<<",\"textureOracleMode\":\"point_source_geometry_only\""<<",\"oracleEquivalent\":"<<renderer.oracleEquivalent<<",\"oracleAmbiguous\":"<<renderer.oracleAmbiguous<<",\"oracleFailures\":"<<renderer.oracleFailures<<",\"windowResizes\":"<<renderer.resizeCount<<",\"minimizeRestoreTests\":"<<minimizeTests<<",\"keyboardMovedCamera\":"<<(inputMoved?"true":"false")
            <<",\"validationEnabled\":"<<(renderer.validationEnabled?"true":"false")<<",\"validationErrors\":"<<renderer.validationErrors.load()
            <<",\"gpuTraceMedianMs\":"<<percentile(renderer.gpuMs,.5)<<",\"gpuTraceP95Ms\":"<<percentile(renderer.gpuMs,.95)<<",\"gpuSamples\":"<<renderer.gpuMs.size()
            <<",\"cpuDrawMedianMs\":"<<percentile(frameMs,.5)<<",\"cpuDrawP95Ms\":"<<percentile(frameMs,.95)<<",\"cpuSamples\":"<<frameMs.size()
            <<",\"camera\":["<<camera.position.x<<","<<camera.position.y<<","<<camera.position.z<<","<<camera.yaw<<","<<camera.pitch<<"]"
            <<",\"scope\":\"Small imported scene; GPU timestamps cover trace dispatch, CPU draw includes frame wait/present; no comparison with Minecraft FPS; viewer has no gameplay simulation\"}\n";
        if(!out)throw std::runtime_error("Report write failed");
    }catch(const std::exception& e){failure=e.what();code=1;
        std::error_code ignored;std::filesystem::create_directories(report,ignored);
        std::ofstream out(report/"error.json");out<<"{\"completed\":false,\"error\":"<<jsonString(failure)<<"}\n";
        if(!hidden)MessageBoxA(nullptr,failure.c_str(),"DarkWinter Core",MB_ICONERROR|MB_OK);
    }
    return code;
}
