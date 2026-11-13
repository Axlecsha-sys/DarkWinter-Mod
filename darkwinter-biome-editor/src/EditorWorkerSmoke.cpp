// Measure actual retained-scene editor interaction; PNGs are Qt/Vulkan frames, not native postprocessing.
#include "EditorWindow.hpp"
#include "PreviewViewport.hpp"
#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDir>
#include <QDoubleSpinBox>
#include <QElapsedTimer>
#include <QEvent>
#include <QFile>
#include <QFileInfo>
#include <QImage>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLabel>
#include <QMouseEvent>
#include <QSaveFile>
#include <QScreen>
#include <QTimer>
#include <QVector>
#include <QWheelEvent>
#include <algorithm>
#include <cmath>
#include <deque>
#include <memory>
#ifdef Q_OS_WIN
#include <windows.h>
#include <psapi.h>
#endif

namespace {
class SmokeInputGuard final : public QObject {
public:
    explicit SmokeInputGuard(QObject *parent):QObject(parent){}
    bool injecting=false;
    bool eventFilter(QObject *,QEvent *event) override {
        if(injecting&&!event->spontaneous())return false;
        switch(event->type()){
        case QEvent::MouseButtonPress:case QEvent::MouseButtonRelease:case QEvent::MouseButtonDblClick:case QEvent::MouseMove:
        case QEvent::Wheel:case QEvent::KeyPress:case QEvent::KeyRelease:case QEvent::Shortcut:case QEvent::ShortcutOverride:
        case QEvent::TouchBegin:case QEvent::TouchUpdate:case QEvent::TouchEnd:case QEvent::TouchCancel:
        case QEvent::NativeGesture:case QEvent::Gesture:return true;
        default:return false;
        }
    }
};
enum class Phase {AwaitScene,Modes,Soak,DrainSoak,Regenerating,Stopping};
struct InputSample {QJsonObject camera;qint64 nanos=0;};
struct WorkerSmoke {
    QString output,previousExport;
    int seconds=120,mode=0,baseOperations=0;
    Phase phase=Phase::AwaitScene;
    QElapsedTimer wall,stage,soak,stop;
    QJsonObject state,report,initialCamera,requestedEnvironment,expectedProfile,soakIo,soakViewport;
    QJsonArray modes,failures,memorySamples,protocolScenes;
    QJsonArray expectedOperations;
    QVector<double> latencyMs,heartbeatMs;
    std::deque<InputSample> pending;
    std::shared_ptr<const SceneBundle> latestScene;
    quint64 frames=0,lastSerial=0,scene=0,initialScene=0,inputCount=0,orbitInputs=0,panInputs=0,zoomInputs=0,coalescedInputs=0;
    quint64 modeAnchor=0,lightingRevision=0,modeRevision=0,heartbeatCount=0,regenerationScene=0,firstWaveScene=0,lateScene=0;
    QJsonObject lateStrokeObservedState;
    qint64 lastHeartbeat=0,lastFrameNanos=0,lastCheckpointMs=0,lastMemoryMs=-1000;
    double maxHeartbeatMs=0,actualSoakMs=0;
    quint64 guiPeak=0,javaPeak=0,guiPlateauBase=0,javaPlateauBase=0,guiPlateauPeak=0,javaPlateauPeak=0,gpuPeak=0;
    bool memoryAvailable=false,modeStarted=false,undoVerified=false,redoVerified=false,strokesWhilePending=false,lateStrokeSent=false;
};
void failure(const std::shared_ptr<WorkerSmoke> &t,const QString &message){
    if(!t->failures.contains(message))t->failures.append(message);
}
QJsonObject distribution(QVector<double> values){
    if(values.isEmpty())return {{"count",0}};
    const auto samples=values;std::sort(values.begin(),values.end());const qsizetype n=values.size();
    const auto percentile=[&](double p){return values[std::min(n-1,qsizetype(std::ceil(n*p)-1))];};
    QJsonArray first;for(qsizetype i=0;i<std::min<qsizetype>(64,samples.size());++i)first.append(samples[i]);
    return {{"count",double(n)},{"medianMs",percentile(.5)},{"p95Ms",percentile(.95)},{"p99Ms",percentile(.99)},
        {"maxMs",values.back()},{"firstSamplesMs",first}};
}
QJsonObject boundedObject(const QString &path,QByteArray *raw=nullptr,qint64 limit=8*1024*1024){
    const QFileInfo info(path);if(!info.isFile()||info.isSymLink()||info.size()<0||info.size()>limit)return {};
    QFile file(path);if(!file.open(QIODevice::ReadOnly))return {};const auto bytes=file.read(limit+1);
    if(bytes.size()>limit)return {};QJsonParseError error;const auto doc=QJsonDocument::fromJson(bytes,&error);
    if(error.error!=QJsonParseError::NoError||!doc.isObject())return {};if(raw)*raw=bytes;return doc.object();
}
bool saveObject(const QString &path,const QJsonObject &object){
    QSaveFile file(path);const auto bytes=QJsonDocument(object).toJson(QJsonDocument::Indented);
    return file.open(QIODevice::WriteOnly)&&file.write(bytes)==bytes.size()&&file.commit();
}
quint64 workingSet(qint64 pid){
#ifdef Q_OS_WIN
    using ReadMemory=BOOL(WINAPI *)(HANDLE,PPROCESS_MEMORY_COUNTERS,DWORD);
    const auto read=reinterpret_cast<ReadMemory>(GetProcAddress(GetModuleHandleW(L"kernel32.dll"),"K32GetProcessMemoryInfo"));
    if(!read||pid<=0)return 0;
    const HANDLE process=pid==QCoreApplication::applicationPid()?GetCurrentProcess():OpenProcess(PROCESS_QUERY_INFORMATION|PROCESS_VM_READ,FALSE,DWORD(pid));
    if(!process)return 0;PROCESS_MEMORY_COUNTERS info{};info.cb=sizeof(info);const bool ok=read(process,&info,sizeof(info));
    if(process!=GetCurrentProcess())CloseHandle(process);return ok?quint64(info.WorkingSetSize):0;
#else
    Q_UNUSED(pid);return 0;
#endif
}
bool receipt(const std::shared_ptr<WorkerSmoke> &t,const WorkerClient &worker){
    t->report["modes"]=t->modes;t->report["failures"]=t->failures;t->report["state"]=t->state;
    t->report["sceneRevision"]=double(t->scene);t->report["localRecordedFrames"]=double(t->frames);
    t->report["ipcDirectory"]=worker.ipcDirectory();t->report["logTail"]=worker.logTail();
    t->report["elapsedMs"]=double(t->wall.elapsed());t->report["workerIo"]=worker.ioDiagnostics();
    t->report["protocolScenes"]=t->protocolScenes;
    return saveObject(QDir(t->output).filePath("worker-smoke.json"),t->report);
}
}
void EditorWindow::runWorkerSmoke(const QString &output,int seconds){
    auto t=std::make_shared<WorkerSmoke>();t->seconds=std::clamp(seconds,1,3600);t->output=QFileInfo(output).absoluteFilePath();
    if(output.isEmpty()||!QDir().mkpath(t->output)){QTimer::singleShot(0,[]{QCoreApplication::exit(2);});return;}
    t->report={{"schema",2},{"status","RUNNING"},{"projectRoot",projectRoot_},{"requestedSoakSeconds",t->seconds},
        {"scope","Actual native generated/compiled terrain retained in the owned C++ Vulkan viewport"},
        {"imageScope","C++ material preview using native geometry/atlas/lightmap; these frames do not prove the native weather/postprocessing graph."},
        {"latencyMethod","Owned Qt input dispatch to matching local mesh command recording and QVulkanWindow::frameReady; excludes GPU completion, presentation and scanout."},
        {"syntheticGeometryUsed",false},{"nativePostprocessingVerified",false},{"measurementExcludesModeCapturesAndPostSoakProfileReceiptChecks",true},{"full120SecondSoak",false},{"performanceImprovementClaim",false},
        {"thresholds",QJsonObject{{"inputToRecordP95Ms",100},{"inputToRecordMaxMs",1000},{"heartbeatP95Ms",50},{"heartbeatMaxMs",1000},
            {"minimumRecordedInputRatio",.8},{"maximumProcessGrowthBytesAfterWarmup",double(256ULL*1024*1024)},{"ioPending",1},{"ioCompleted",1}}}};
    if(!viewport_){failure(t,"Vulkan viewport is unavailable");t->phase=Phase::Stopping;}
    worker_.setDiagnosticFrames(false);
    auto *guard=new SmokeInputGuard(this);QCoreApplication::instance()->installEventFilter(guard);
    t->wall.start();t->stage.start();t->initialCamera=document_.profile().value("camera").toObject();
    setWindowFlag(Qt::WindowStaysOnTopHint,true);
    if(screen()){const auto available=screen()->availableGeometry();resize(std::min(1600,available.width()),std::min(1000,available.height()));move(available.topLeft());}
    QTimer::singleShot(0,this,[this]{hide();showNormal();raise();});
    auto *heartbeat=new QTimer(this);heartbeat->setInterval(16);heartbeat->setTimerType(Qt::PreciseTimer);
    auto *inputs=new QTimer(this);inputs->setInterval(33);inputs->setTimerType(Qt::PreciseTimer);
    auto *control=new QTimer(this);control->setInterval(50);
    const auto drag=[this,guard](Qt::MouseButton button,QPointF delta,QPointF start=QPointF{}){
        if(!viewport_||!viewport_->isValid())return false;
        if(start.isNull())start=QPointF(viewport_->width()*.5,viewport_->height()*.5);
        const auto global=viewport_->mapToGlobal(start.toPoint());const QPointF end=start+delta;
        QMouseEvent press(QEvent::MouseButtonPress,start,global,button,button,Qt::NoModifier);
        QMouseEvent move(QEvent::MouseMove,end,global+delta,Qt::NoButton,button,Qt::NoModifier);
        QMouseEvent release(QEvent::MouseButtonRelease,end,global+delta,button,Qt::NoButton,Qt::NoModifier);
        guard->injecting=true;QCoreApplication::sendEvent(viewport_,&press);QCoreApplication::sendEvent(viewport_,&move);QCoreApplication::sendEvent(viewport_,&release);guard->injecting=false;
        return press.isAccepted();
    };
    const auto wheel=[this,guard](int angle){
        if(!viewport_||!viewport_->isValid())return false;
        const QPointF local(viewport_->width()*.5,viewport_->height()*.5),global=viewport_->mapToGlobal(local.toPoint());
        QWheelEvent event(local,global,QPoint{},QPoint{0,angle},Qt::NoButton,Qt::NoModifier,Qt::NoScrollPhase,false);
        guard->injecting=true;QCoreApplication::sendEvent(viewport_,&event);guard->injecting=false;return event.isAccepted();
    };
    connect(&worker_,&WorkerClient::stateChanged,this,[t](QJsonObject state){t->state=std::move(state);});
    connect(&worker_,&WorkerClient::sceneReady,this,[this,t](std::shared_ptr<const SceneBundle> scene){
        t->latestScene=std::move(scene);
        if(!t->latestScene)return;
        const auto manifest=t->latestScene->sourceManifest;
        const auto &s=*t->latestScene;
        const bool exact=manifest.value("sceneRevision").toDouble()==double(s.sceneRevision)&&manifest.value("resourceEpoch").toDouble()==double(s.resourceEpoch)&&
            manifest.value("vertexCount").toDouble()==s.vertexCount&&manifest.value("indexCount").toDouble()==s.indexCount&&
            manifest.value("vertices").toObject().value("bytes").toDouble()==s.vertices.size()&&manifest.value("indices").toObject().value("bytes").toDouble()==s.indices.size()&&
            manifest.value("atlas").toObject().value("bytes").toDouble()==s.atlas.size()&&manifest.value("lightmap").toObject().value("bytes").toDouble()==s.lightmap.size();
        if(!exact)failure(t,"Accepted scene bytes/counts do not match the actual published protocol manifest");
        const auto manifestFile=QDir(t->output).filePath(QString("scene-%1-manifest.json").arg(s.sceneRevision));
        if(!saveObject(manifestFile,manifest))failure(t,"Could not save actual scene manifest");
        t->protocolScenes.append(QJsonObject{{"sceneRevision",double(s.sceneRevision)},{"resourceEpoch",double(s.resourceEpoch)},
            {"vertices",double(s.vertexCount)},{"indices",double(s.indexCount)},{"vertexBytes",double(s.vertices.size())},{"indexBytes",double(s.indices.size())},
            {"atlasBytes",double(s.atlas.size())},{"lightmapBytes",double(s.lightmap.size())},{"manifestMatchesAcceptedBundle",exact},
            {"shaValidationScope","All four mandatory asset SHA-256 values and ABI/index checks were validated once by WorkerClient's background SceneBundleReader before this signal."},
            {"manifest",manifestFile},{"omittedBlockEntities",double(s.omittedBlockEntities)}});
    });
    connect(&worker_,&WorkerClient::lightingReady,this,[t](std::shared_ptr<const SceneLighting> lighting){
        if(lighting&&lighting->environment==t->requestedEnvironment)t->lightingRevision=lighting->revision;
    });
    if(viewport_){
        connect(viewport_,&PreviewViewport::sceneDisplayed,this,[this,t](quint64 scene){
            t->scene=scene;if(!t->initialScene){t->initialScene=scene;t->report["firstNativeMeshRecordedMs"]=double(t->wall.elapsed());}
            if(t->phase==Phase::Regenerating&&scene==worker_.sceneRevision())t->regenerationScene=scene;
        });
        connect(viewport_,&PreviewViewport::localFrameRecorded,this,[t](quint64 serial,quint64 scene,QJsonObject camera){
            if(serial<=t->lastSerial)failure(t,"Local frame serial did not increase monotonically");
            t->lastSerial=serial;++t->frames;t->lastFrameNanos=t->wall.nsecsElapsed();
            if((t->phase==Phase::Soak||t->phase==Phase::DrainSoak)&&scene!=t->initialScene)failure(t,"Local camera navigation changed geometry scene identity");
            if(t->phase!=Phase::Soak&&t->phase!=Phase::DrainSoak)return;
            for(size_t i=t->pending.size();i>0;--i)if(t->pending[i-1].camera==camera){
                t->latencyMs.append((t->wall.nsecsElapsed()-t->pending[i-1].nanos)/1e6);
                t->coalescedInputs+=i-1;t->pending.erase(t->pending.begin(),t->pending.begin()+std::ptrdiff_t(i));break;
            }
        });
    }
    connect(heartbeat,&QTimer::timeout,this,[t]{
        if(t->phase!=Phase::Soak&&t->phase!=Phase::DrainSoak)return;
        const auto now=t->wall.nsecsElapsed();
        if(t->lastHeartbeat){const double ms=(now-t->lastHeartbeat)/1e6;t->heartbeatMs.append(ms);t->maxHeartbeatMs=std::max(t->maxHeartbeatMs,ms);}
        t->lastHeartbeat=now;++t->heartbeatCount;
    });
    connect(inputs,&QTimer::timeout,this,[this,t,drag,wheel]{
        if(t->phase!=Phase::Soak||!viewport_)return;
        const auto before=viewport_->camera();const auto start=t->wall.nsecsElapsed();bool accepted=false;
        const auto slot=t->inputCount%6;
        if(slot<3){accepted=drag(Qt::RightButton,QPointF(3,.15*std::sin(double(t->inputCount)*.1)));++t->orbitInputs;}
        else if(slot<5){const double sign=slot==3?1:-1;accepted=drag(Qt::MiddleButton,QPointF(sign*2,sign));++t->panInputs;}
        else {accepted=wheel((t->zoomInputs%2)?-120:120);++t->zoomInputs;}
        if(!accepted||viewport_->camera()==before){failure(t,"Owned orbit/pan/zoom input did not immediately update the local camera");return;}
        ++t->inputCount;t->pending.push_back({viewport_->camera(),start});
        if(t->pending.size()>256){failure(t,"Local input-to-frame measurement queue exceeded 256");t->pending.pop_front();}
    });
    const auto beginMode=[this,t](int mode){
        t->mode=mode;t->requestedEnvironment={{"time",mode>=2?18000:6000},{"weather","clear"},{"effects",mode==1||mode==3}};
        const auto previous=document_.profile().value("environment").toObject();
        document_.setValue({"environment"},t->requestedEnvironment,QStringLiteral("Окружение проверки retained mesh"));
        if(previous==t->requestedEnvironment)worker_.environment(t->requestedEnvironment);
        t->modeRevision=worker_.latestRevision();t->lightingRevision=0;t->modeAnchor=t->frames;t->stage.restart();t->modeStarted=true;
    };
    const auto capture=[this,t](const QString &name){
        // Capture the composited child before an offscreen grab, then restore normal presentation.
        const auto uiFile=QDir(t->output).filePath(name+"-ui.png");QImage ui;
        if(screen()){const auto p=mapToGlobal(QPoint{});ui=screen()->grabWindow(0,p.x(),p.y(),width(),height()).toImage();}
        if(ui.isNull()||!ui.save(uiFile))failure(t,"Owned Qt window capture failed: "+name);
        const auto viewFile=QDir(t->output).filePath(name+"-material-preview.png");
        QImage view;if(viewport_&&viewport_->supportsGrab()){view=viewport_->grab();viewport_->requestUpdate();}
        if(view.isNull()||!view.save(viewFile)){failure(t,"Actual Qt Vulkan viewport capture failed: "+name);return QJsonObject{};}
        return QJsonObject{{"viewport",viewFile},{"ui",uiFile},{"pixelSha256",QString::fromLatin1(QCryptographicHash::hash(
            QByteArray(reinterpret_cast<const char*>(view.constBits()),view.sizeInBytes()),QCryptographicHash::Sha256).toHex())},
            {"width",view.width()},{"height",view.height()},{"scope","Actual C++ Vulkan material preview; native postprocessing/weather graph is not verified."}};
    };
    connect(control,&QTimer::timeout,this,[this,t,control,inputs,heartbeat,guard,drag,beginMode,capture]{
        const auto status=t->state.value("status").toString();
        if(t->phase==Phase::Stopping){
            if(!t->stop.isValid()){inputs->stop();heartbeat->stop();worker_.stop();t->stop.start();receipt(t,worker_);return;}
            if(worker_.running()&&t->stop.elapsed()<20000)return;
            const bool exited=!worker_.running(),clean=exited&&worker_.cleanExit();
            t->report["ownedJavaExited"]=exited;t->report["ownedJavaExitedNormally"]=clean;
            if(!clean)failure(t,"Owned Java worker did not exit normally within 20 seconds");
            const auto backend=boundedObject(QDir(worker_.ipcDirectory()).filePath("worker-performance.json"));
            t->report["backendPerformance"]=backend;
            if(!backend.contains("cameraRequests")||backend.value("cameraRequests").toDouble(-1)!=0)failure(t,"Native backend cameraRequests is missing or nonzero");
            const auto io=worker_.ioDiagnostics();
            if(io.value("ownerThreadIoViolations").toInt(-1)!=0||io.value("rejectedScenes").toInt()!=0)failure(t,"Scene reader reported an owner-thread I/O violation or rejected a native scene");
            t->report["interaction"]=interactionDiagnostics();
            if(interactionDiagnostics().value("cameraRequests").toDouble(-1)!=0)failure(t,"Editor sent a camera RPC");
            t->report["viewport"]=viewport_?viewport_->renderDiagnostics():QJsonObject{};
            t->report["status"]=t->failures.isEmpty()?(t->seconds>=120?"PASS":"SHORT_DIAGNOSTIC_COMPLETE"):"FAIL";
            t->report["qtShutdownReceipt"]="qt-shutdown.json (written by main after event loop and owned destructors complete)";
            if(!receipt(t,worker_))failure(t,"Could not save final worker-smoke report");
            control->stop();QCoreApplication::instance()->removeEventFilter(guard);document_.reset();close();QCoreApplication::exit(t->failures.isEmpty()?0:1);return;
        }
        if(status=="failed")failure(t,t->state.value("error").toString());
        if(t->wall.elapsed()>qint64(t->seconds)*1000+420000)failure(t,"Retained usability run exceeded its bounded overall deadline");
        if(!worker_.running()&&t->wall.elapsed()>3000)failure(t,"Native worker exited before the retained usability run completed");
        if(!t->failures.isEmpty()){t->phase=Phase::Stopping;return;}
        if(t->wall.elapsed()-t->lastCheckpointMs>=5000){t->lastCheckpointMs=t->wall.elapsed();receipt(t,worker_);}
        if(t->phase==Phase::AwaitScene){
            if(t->stage.elapsed()>180000){failure(t,"Initial native retained scene was not recorded within 180 seconds");return;}
            if(status!="ready"||!displayedScene_||visibleScene_!=worker_.sceneRevision()||!t->initialScene)return;
            t->initialCamera=viewport_->camera();t->phase=Phase::Modes;beginMode(0);return;
        }
        if(t->phase==Phase::Modes){
            if(t->stage.elapsed()>30000){failure(t,"Environment mode did not deliver native lighting and local frames within 30 seconds");return;}
            // Static retained views render on demand. Exercise each of the three frame slots locally.
            if(t->lightingRevision>=t->modeRevision&&t->frames<t->modeAnchor+3)viewport_->requestUpdate();
            if(t->lightingRevision<t->modeRevision||t->frames<t->modeAnchor+3||t->stage.elapsed()<1200)return;
            if(viewport_->camera()!=t->initialCamera||worker_.sceneRevision()!=t->initialScene){failure(t,"Environment-only update changed the fixed camera or geometry");return;}
            const QString names[]={"day-off","day-on","night-off","night-on"};const auto name=names[t->mode];
            const auto image=capture(name);
            t->modes.append(QJsonObject{{"name",name},{"environment",t->requestedEnvironment},{"sceneRevision",double(t->scene)},
                {"lightingRevision",double(t->lightingRevision)},{"camera",viewport_->camera()},{"images",image},{"viewport",viewport_->renderDiagnostics()}});
            if(t->mode<3){beginMode(t->mode+1);return;}
            commitCamera();t->soakIo=worker_.ioDiagnostics();t->soakViewport=viewport_->renderDiagnostics();
            t->phase=Phase::Soak;t->soak.start();t->stage.restart();t->lastHeartbeat=0;heartbeat->start();inputs->start();return;
        }
        if(t->phase==Phase::Soak||t->phase==Phase::DrainSoak){
            const auto io=worker_.ioDiagnostics(),gpu=viewport_->renderDiagnostics();
            if(io.value("pendingJobs").toInt()>1||io.value("activeReads").toInt()>1||io.value("completedResults").toInt()>1||io.value("completedFrames").toInt()>1||
               io.value("maxPendingJobs").toInt()!=1||io.value("maxCompletedFrames").toInt()!=1)failure(t,"Worker I/O queue bounds were exceeded");
            if(io.value("sceneReads")!=t->soakIo.value("sceneReads")||io.value("shaBytesHashed")!=t->soakIo.value("shaBytesHashed"))
                failure(t,"Local navigation reread or rehashed native geometry/texture assets");
            for(const QString &field:QStringList{"localSceneFrames","sceneGpuAllocatedBytes","sceneUploads","sceneBufferAllocations","sceneRetainedGenerations","sceneUploadQueued","framesInFlight"})
                if(!gpu.contains(field))failure(t,"Viewport retained diagnostic is missing: "+field);
            if(gpu.value("sceneUploads")!=t->soakViewport.value("sceneUploads")||gpu.value("sceneBufferAllocations")!=t->soakViewport.value("sceneBufferAllocations")||
               gpu.value("sceneRetainedGenerations").toInt()>3||gpu.value("sceneUploadQueued").toBool()||
               gpu.value("framesInFlight").toInt()<1||gpu.value("framesInFlight").toInt()>3)failure(t,"Local navigation reuploaded geometry or exceeded Vulkan frame-slot bounds");
            const auto gpuBytes=quint64(gpu.value("sceneGpuAllocatedBytes").toDouble());t->gpuPeak=std::max(t->gpuPeak,gpuBytes);
            if(displayedScene_){
                const auto &s=*displayedScene_;const auto owned=quint64(s.vertices.size()+s.indices.size()+s.atlas.size()+s.lightmap.size());
                if(gpuBytes>owned*4+64ULL*1024*1024)failure(t,"Owned Vulkan scene allocation exceeded the measured four-slot asset budget");
            }
            if(t->soak.elapsed()-t->lastMemoryMs>=1000){
                t->lastMemoryMs=t->soak.elapsed();const auto gui=workingSet(QCoreApplication::applicationPid()),java=workingSet(worker_.processId());
                t->memoryAvailable=gui&&java;t->guiPeak=std::max(t->guiPeak,gui);t->javaPeak=std::max(t->javaPeak,java);
                if(t->soak.elapsed()>=10000){
                    if(!t->guiPlateauBase){t->guiPlateauBase=gui;t->javaPlateauBase=java;}
                    t->guiPlateauPeak=std::max(t->guiPlateauPeak,gui);t->javaPlateauPeak=std::max(t->javaPlateauPeak,java);
                }
                t->memorySamples.append(QJsonObject{{"soakMs",double(t->soak.elapsed())},{"qtWorkingSetBytes",double(gui)},{"javaWorkingSetBytes",double(java)},{"ownedGpuBytes",double(gpuBytes)}});
            }
            if(t->phase==Phase::Soak&&t->soak.elapsed()>=qint64(t->seconds)*1000){
                inputs->stop();t->actualSoakMs=t->soak.elapsed();t->phase=Phase::DrainSoak;t->stage.restart();return;
            }
            if(t->phase==Phase::Soak){
                if(t->wall.nsecsElapsed()-t->lastFrameNanos>2000000000LL)failure(t,"Local mesh recorded no frame for two seconds during sustained input");
                return;
            }
            if(!t->pending.empty()&&t->stage.elapsed()<1500)return;
            heartbeat->stop();commitCamera();
            if(!t->pending.empty())failure(t,"The final sustained navigation pose was never recorded");
            const auto latency=distribution(t->latencyMs),heartbeats=distribution(t->heartbeatMs);
            if(t->latencyMs.size()<double(t->inputCount)*.8||t->orbitInputs<1||t->panInputs<1||t->zoomInputs<1)failure(t,"Too few input poses reached actual local command recording");
            if(latency.value("p95Ms").toDouble(1e9)>100||latency.value("maxMs").toDouble(1e9)>1000)failure(t,"Input-to-local-record latency exceeded the declared usability threshold");
            if(heartbeats.value("p95Ms").toDouble(1e9)>50||t->maxHeartbeatMs>1000)failure(t,"GUI heartbeat exceeded the declared usability threshold");
            if(!t->memoryAvailable)failure(t,"Actual Qt/Java process working-set samples are unavailable");
            const quint64 growthCap=256ULL*1024*1024;
            if(t->guiPlateauBase&&t->guiPlateauPeak>t->guiPlateauBase+growthCap)failure(t,"Qt working set grew by more than 256 MiB after warmup");
            if(t->javaPlateauBase&&t->javaPlateauPeak>t->javaPlateauBase+growthCap)failure(t,"Java working set grew by more than 256 MiB after warmup");
            t->report["full120SecondSoak"]=t->actualSoakMs>=120000;
            t->report["navigationSoak"]=QJsonObject{{"durationMs",t->actualSoakMs},{"inputs",double(t->inputCount)},{"orbit",double(t->orbitInputs)},
                {"pan",double(t->panInputs)},{"zoom",double(t->zoomInputs)},{"supersededBeforeRecord",double(t->coalescedInputs)},
                {"inputToRecord",latency},{"heartbeat",heartbeats},{"heartbeatObserved",double(t->heartbeatCount)},{"viewport",gpu},{"io",io}};
            t->report["memory"]=QJsonObject{{"scope","Actual process working sets; owned GPU allocations are renderer counters, not driver-wide VRAM."},
                {"qtPeakBytes",double(t->guiPeak)},{"javaPeakBytes",double(t->javaPeak)},{"ownedGpuPeakBytes",double(t->gpuPeak)},
                {"qtWarmupBaseBytes",double(t->guiPlateauBase)},{"javaWarmupBaseBytes",double(t->javaPlateauBase)},
                {"qtAfterWarmupPeakBytes",double(t->guiPlateauPeak)},{"javaAfterWarmupPeakBytes",double(t->javaPlateauPeak)},{"samples",t->memorySamples}};
            t->report["afterNavigationImages"]=capture("after-navigation");
            t->previousExport=exportDirectory_;t->baseOperations=document_.profile().value("operations").toArray().size();
            recenter();commitCamera();setTool("flatten");radius_->setValue(4);height_->setValue(workY_->value()+2);
            sceneLoading_=true;rebuild_->start();updateBrush();const auto pendingRevision=worker_.sceneRevision();bool pending=true;
            for(int i=0;i<5;++i){
                pending=pending&&(sceneLoading_||rebuild_->isActive())&&bool(displayedScene_);
                const QPointF start(viewport_->width()*(.40+.04*i),viewport_->height()*.5);
                if(!drag(Qt::LeftButton,QPointF(3,1),start))failure(t,"An actual brush mouse press was rejected while generation was pending");
            }
            commitCamera();const auto appended=document_.profile().value("operations").toArray();
            t->strokesWhilePending=pending&&appended.size()==t->baseOperations+5&&worker_.sceneRevision()==pendingRevision;
            if(!t->strokesWhilePending)failure(t,"Five brush strokes did not accumulate on the displayed mesh while regeneration was pending");
            document_.undoStack()->undo();t->undoVerified=document_.profile().value("operations").toArray().size()==t->baseOperations+4;
            document_.undoStack()->redo();t->redoVerified=document_.profile().value("operations").toArray()==appended;
            if(!t->undoVerified||!t->redoVerified)failure(t,"Brush Undo/Redo did not restore the exact operation array");
            t->expectedOperations=appended;t->expectedProfile=document_.profile();
            generate();t->firstWaveScene=worker_.sceneRevision();t->phase=Phase::Regenerating;t->stage.restart();return;
        }
        if(t->phase==Phase::Regenerating){
            if(t->stage.elapsed()>180000){failure(t,"Batched brush changes did not produce a recorded native scene within 180 seconds");return;}
            if(!t->lateStrokeSent){
                const auto acknowledgedScene=quint64(t->state.value("sceneRevision").toDouble());
                const double progress=t->state.value("progress").toDouble();
                if(status=="generating"&&acknowledgedScene==t->firstWaveScene&&progress>=.05&&progress<=.9){
                    // This second wave is intentionally published AFTER native chunk generation is busy.
                    // It covers the worker's deferred-latest-generation path, not just the debounce window.
                    t->lateStrokeObservedState=t->state;
                    const int before=document_.profile().value("operations").toArray().size();
                    const QPointF start(viewport_->width()*.55,viewport_->height()*.52);
                    if(!drag(Qt::LeftButton,QPointF(4,-1),start)||document_.profile().value("operations").toArray().size()!=before+1)
                        failure(t,"Late brush stroke was rejected during actual native chunk generation");
                    t->lateStrokeSent=true;t->expectedOperations=document_.profile().value("operations").toArray();t->expectedProfile=document_.profile();
                    if(t->expectedOperations.size()!=t->baseOperations+6)failure(t,"Late stroke did not produce the exact six-operation batch");
                    generate();t->lateScene=worker_.sceneRevision();t->regenerationScene=0;
                    t->report["lateBrushRequest"]=QJsonObject{{"observedNativeBusyState",t->lateStrokeObservedState},
                        {"firstWaveSceneRevision",double(t->firstWaveScene)},{"latestSceneRevision",double(t->lateScene)},
                        {"operationCount",t->expectedOperations.size()}};
                    receipt(t,worker_);return;
                }
                if(t->stage.elapsed()>90000)failure(t,"First brush generation never exposed a busy native chunk phase for the late second wave");
                return;
            }
            if(status!="ready"||rebuild_->isActive()||!displayedScene_||visibleScene_!=worker_.sceneRevision()||
               !t->regenerationScene||exportDirectory_.isEmpty()||exportDirectory_==t->previousExport)return;
            const auto manifestPath=QDir(exportDirectory_).filePath("darkwinter_editor_manifest.json"),profilePath=QDir(exportDirectory_).filePath("darkwinter_editor_profile.json");
            QByteArray profileBytes;const auto profile=boundedObject(profilePath,&profileBytes),manifest=boundedObject(manifestPath);
            const auto profileHash=QString::fromLatin1(QCryptographicHash::hash(profileBytes,QCryptographicHash::Sha256).toHex());
            auto expected=t->expectedProfile,actual=profile;expected.remove("camera");actual.remove("camera");expected.remove("environment");actual.remove("environment");
            const bool verified=!profile.isEmpty()&&expected==actual&&profile.value("operations").toArray()==t->expectedOperations&&
                manifest.value("complete").toBool()&&!manifest.value("existingChunksModified").toBool(true)&&
                manifest.value("profileSourceFileSha256").toString()==profileHash&&manifest.value("files").toObject().value("darkwinter_editor_profile.json").toString()==profileHash;
            if(!verified)failure(t,"Native regenerated profile/manifest hash does not match the exact batched operations");
            t->report["brushBatch"]=QJsonObject{{"strokes",6},{"firstWaveStrokes",5},{"lateWaveStrokes",1},{"acceptedWhileGenerationPending",t->strokesWhilePending},
                {"lateStrokeSentWhileNativeBusy",t->lateStrokeSent},{"lateStrokeObservedState",t->lateStrokeObservedState},{"undoVerified",t->undoVerified},{"redoVerified",t->redoVerified},
                {"regenerationMs",double(t->stage.elapsed())},{"recordedSceneRevision",double(visibleScene_)},{"operationCount",t->expectedOperations.size()},
                {"nativeProfileHashVerified",verified},{"profileFileSha256",profileHash},{"manifest",manifestPath},{"profile",profilePath},
                {"images",capture("regenerated")} };
            t->report["generationVerified"]=verified&&!t->protocolScenes.isEmpty();t->phase=Phase::Stopping;receipt(t,worker_);return;
        }
    });
    QString error;if(t->phase!=Phase::Stopping){
        if(!worker_.start(projectRoot_,&error)){failure(t,error);t->phase=Phase::Stopping;}
        else generate();
    }
    receipt(t,worker_);control->start();
}
