// Exercises retained Vulkan presentation with explicit synthetic calibration; no world generation is implied.
#include "EditorWindow.hpp"
#include "PreviewViewport.hpp"
#include "PreviewViewportMath.hpp"
#include "SceneBundle.hpp"
#include <QCoreApplication>
#include <QColor>
#include <QCryptographicHash>
#include <QDir>
#include <QElapsedTimer>
#include <QFileInfo>
#include <QImage>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLabel>
#include <QMouseEvent>
#include <QSaveFile>
#include <QScreen>
#include <QTimer>
#include <QWheelEvent>
#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstring>
#include <memory>
#include <thread>

namespace {
using Palette=std::array<QColor,4>;
Palette calibrationPalette(int version) {
    const Palette base{QColor(240,32,48),QColor(24,220,72),QColor(32,80,240),QColor(232,180,24)};
    Palette result=base;
    for(auto &color:result)color=QColor((color.red()+version*43)%256,(color.green()+version*79)%256,(color.blue()+version*113)%256);
    return result;
}
QJsonObject calibrationCamera() {
    return {{"targetX",0},{"targetY",0},{"targetZ",0},{"yaw",0},{"pitch",90},{"span",4}};
}
std::shared_ptr<const SceneBundle> calibrationScene(int version) {
    auto scene=std::make_shared<SceneBundle>();scene->sceneRevision=scene->revision=scene->resourceEpoch=1;
    scene->origin={0,0,0};scene->bounds={-1,0,-1,1,0,1};scene->cutY=320;
    constexpr quint32 light=240u|(240u<<16);
    // UV zero is the top-left texel. Minecraft yaw zero has screen-right pointing toward -X.
    const std::array<SceneVertex,4> vertices{{
        {1,0,1,255,255,255,255,0,0,light,0},{-1,0,1,255,255,255,255,1,0,light,0},
        {-1,0,-1,255,255,255,255,1,1,light,0},{1,0,-1,255,255,255,255,0,1,light,0}}};
    const std::array<quint32,6> indices{0,1,2,0,2,3};
    scene->vertexCount=quint32(vertices.size());scene->indexCount=quint32(indices.size());
    scene->vertices=QByteArray(reinterpret_cast<const char*>(vertices.data()),sizeof(vertices));
    scene->indices=QByteArray(reinterpret_cast<const char*>(indices.data()),sizeof(indices));
    scene->parts.append(ScenePart{0,6,0,{-1,0,-1,1,0,1}});
    scene->atlasSize={2,2};scene->atlas.resize(16);const auto colors=calibrationPalette(version);
    for(int i=0;i<4;++i){scene->atlas[4*i]=char(colors[i].red());scene->atlas[4*i+1]=char(colors[i].green());scene->atlas[4*i+2]=char(colors[i].blue());scene->atlas[4*i+3]=std::bit_cast<char>(quint8{255});}
    scene->lightmapSize={16,16};scene->lightmap=QByteArray(16*16*4,std::bit_cast<char>(quint8{255}));
    scene->environment={{"time",6000},{"weather","clear"},{"effects",false}};
    scene->sourceManifest={{"syntheticCalibration",true},{"generationVerified",false},{"paletteVersion",version}};
    return scene;
}
QJsonArray rgba(const QColor &color){return {color.red(),color.green(),color.blue(),color.alpha()};}
int channelError(const QColor &a,const QColor &b) {
    return std::max({std::abs(a.red()-b.red()),std::abs(a.green()-b.green()),std::abs(a.blue()-b.blue())});
}
constexpr std::array<std::array<double,2>,4> sampleWorld{{{.5,.5},{-.5,.5},{.5,-.5},{-.5,-.5}}};
QPointF projected(int sample,QSize size) {
    return PreviewMath::project(QPointF(sampleWorld[sample][0],sampleWorld[sample][1]),0,
        QRectF(0,0,size.width(),size.height()),*PreviewMath::camera(calibrationCamera()));
}
bool calibrationPose(const QJsonObject &value) {
    const auto a=PreviewMath::camera(value),b=PreviewMath::camera(calibrationCamera());
    return a&&b&&PreviewMath::same(*a,*b);
}
struct SceneSmoke {
    QString output;
    QJsonObject report,initial,navBefore,navAfter,idleBefore,capture;
    QJsonArray failures,captures,diagnostics;
    QElapsedTimer total,phaseTime,captureTime;
    quint64 recorded=0,serial=0,captureRecord=0,startRecord=0,previousSerial=0;
    double previousUploads=0,maxGpuBytes=0,maxGenerations=0;
    QJsonObject recordedCamera;
    int phase=0,version=0,navigation=0;
    bool capturePending=false,injecting=false,finished=false;
};
class CalibrationInputGuard final:public QObject {
public:
    CalibrationInputGuard(std::shared_ptr<SceneSmoke> state,QObject *parent):QObject(parent),state_(std::move(state)){}
    bool eventFilter(QObject *,QEvent *event) override {
        switch(event->type()){
            case QEvent::MouseButtonPress:case QEvent::MouseButtonRelease:case QEvent::MouseMove:case QEvent::Wheel:
                return !state_->injecting;
            default:return false;
        }
    }
private:std::shared_ptr<SceneSmoke> state_;
};
}

void EditorWindow::runSceneSmoke(const QString &outputDirectory) {
    auto state=std::make_shared<SceneSmoke>();state->output=QFileInfo(outputDirectory).absoluteFilePath();
    if(outputDirectory.isEmpty()||!QDir().mkpath(state->output)){QCoreApplication::exit(2);return;}
    state->report={{"schema",1},{"scope","Retained Vulkan synthetic calibration and owned-window presentation only"},
        {"syntheticCalibration",true},{"generationVerified",false},{"freshWorkerSceneVerified",false},
        {"minecraftWorkerStarted",false},{"validationLayerClaim",false},{"resetCount",5},
        {"pixelComparison","Four native ABI32 vertices, actual 2x2 RGBA atlas and full-white 16x16 native lightmap"},
        {"captureMethod","Vulkan readback plus composited desktop crop limited to the exposed owned app client rectangle"}};
    state->total.start();state->phaseTime.start();
    const auto sizePixels=[this](QSize pixels){
        const qreal ratio=screen()->devicePixelRatio();
        resize(qRound(pixels.width()/ratio),qRound(pixels.height()/ratio));
        move(screen()->availableGeometry().topLeft()+QPoint(5,5));
    };
    setWindowFlag(Qt::WindowStaysOnTopHint,true);sizePixels({1280,720});
    QTimer::singleShot(0,this,[this]{hide();showNormal();raise();activateWindow();});
    previewMessage_->setText(QStringLiteral("ДИАГНОСТИКА VULKAN • синтетическая калибровка, не генерация мира"));
    if(viewport_){
        viewport_->installEventFilter(new CalibrationInputGuard(state,viewport_));
        connect(viewport_,&PreviewViewport::diagnostic,this,[state](const QString &value){state->diagnostics.append(value);});
        connect(viewport_,&PreviewViewport::localFrameRecorded,this,[state](quint64 serial,quint64 scene,QJsonObject camera){
            if(scene==1){++state->recorded;state->serial=serial;state->recordedCamera=std::move(camera);}
        });
    }
    auto *timer=new QTimer(this);timer->setTimerType(Qt::PreciseTimer);timer->setInterval(16);
    const auto finish=[this,state,timer]{
        if(state->finished)return;state->finished=true;timer->stop();
        commitCamera();state->report["elapsedMs"]=double(state->total.elapsed());
        state->report["captures"]=state->captures;state->report["failures"]=state->failures;state->report["diagnostics"]=state->diagnostics;
        state->report["maxObservedSceneGpuBytes"]=state->maxGpuBytes;state->report["maxObservedRetainedGenerations"]=state->maxGenerations;
        state->report["finalResources"]=viewport_?viewport_->renderDiagnostics():QJsonObject{};
        state->report["interaction"]=interactionDiagnostics();state->report["workerIo"]=worker_.ioDiagnostics();
        if(worker_.running())state->failures.append("Calibration unexpectedly started a Minecraft worker");
        if(!state->diagnostics.isEmpty())state->failures.append("Vulkan viewport emitted diagnostic errors");
        state->report["failures"]=state->failures;state->report["status"]=state->failures.isEmpty()?"PASS":"FAIL";
        QSaveFile file(QDir(state->output).filePath("scene-smoke.json"));const auto bytes=QJsonDocument(state->report).toJson(QJsonDocument::Indented);
        const bool saved=file.open(QIODevice::WriteOnly)&&file.write(bytes)==bytes.size()&&file.commit();
        document_.reset();close();QCoreApplication::exit(saved&&state->failures.isEmpty()?0:1);
    };
    const auto beginCapture=[this,state](const QString &name){
        if(!viewport_->supportsGrab()){state->failures.append("Vulkan readback is unavailable");return false;}
        const auto image=viewport_->grab().convertToFormat(QImage::Format_RGBA8888);
        if(image.isNull()||!image.save(QDir(state->output).filePath(name+"-vulkan.png"))){state->failures.append("Vulkan calibration capture failed");return false;}
        const auto colors=calibrationPalette(state->version);int maxError=0;QJsonArray samples;
        for(int i=0;i<4;++i){
            const auto point=projected(i,image.size());const auto actual=image.pixelColor(qRound(point.x()),qRound(point.y()));
            maxError=std::max(maxError,channelError(actual,colors[i]));
            samples.append(QJsonObject{{"expected",rgba(colors[i])},{"actual",rgba(actual)},{"x",point.x()},{"y",point.y()}});
        }
        state->capture={{"name",name},{"paletteVersion",state->version},{"sceneRevision",1},{"resourceEpoch",1},
            {"atlasWidth",2},{"atlasHeight",2},{"gpuSamples",samples},{"gpuMaxChannelError",maxError},
            {"gpuImageWidth",image.width()},{"gpuImageHeight",image.height()},
            {"gpuPixelSha256",QString::fromLatin1(QCryptographicHash::hash(QByteArray(reinterpret_cast<const char*>(image.constBits()),image.sizeInBytes()),QCryptographicHash::Sha256).toHex())}};
        if(maxError>3)state->failures.append("Actual Vulkan pixels differ from RGBA/channel/orientation calibration: "+name);
        // A grab records its own readback frame. Present fresh frames before judging the desktop compositor.
        state->captureRecord=state->recorded;state->captureTime.start();state->capturePending=true;viewport_->requestUpdate();return true;
    };
    const auto finishDesktopCapture=[this,state]{
        if(state->recorded<state->captureRecord+2){viewport_->requestUpdate();return false;}
        if(state->captureTime.elapsed()<100)return false;
        const auto origin=mapToGlobal(QPoint(0,0));const auto image=screen()->grabWindow(0,origin.x(),origin.y(),width(),height()).toImage();
        const auto name=state->capture.value("name").toString();
        if(image.isNull()||!image.save(QDir(state->output).filePath(name+"-ui.png"))){state->failures.append("Owned desktop calibration capture failed");return true;}
        const auto colors=calibrationPalette(state->version);QJsonArray samples;int maxError=0,white=0;
        for(int i=0;i<4;++i){
            const auto local=projected(i,viewport_->size());const auto global=viewport_->mapToGlobal(local.toPoint());
            const int x=qRound((global.x()-origin.x())*double(image.width())/width()),y=qRound((global.y()-origin.y())*double(image.height())/height());
            if(x<0||y<0||x>=image.width()||y>=image.height()){state->failures.append("Owned viewport desktop sample was outside the capture");continue;}
            const auto actual=image.pixelColor(x,y);maxError=std::max(maxError,channelError(actual,colors[i]));
            if(actual.red()>=245&&actual.green()>=245&&actual.blue()>=245)++white;
            samples.append(QJsonObject{{"expected",rgba(colors[i])},{"actual",rgba(actual)},{"x",x},{"y",y}});
        }
        state->capture["desktopSamples"]=samples;state->capture["desktopMaxChannelError"]=maxError;
        state->capture["desktopUniformWhite"]=white==4;state->capture["postReadbackRecordedFrames"]=double(state->recorded-state->captureRecord);
        state->capture["postReadbackWaitMs"]=double(state->captureTime.elapsed());state->capture["resources"]=viewport_->renderDiagnostics();
        state->capture["windowLogicalSize"]=QJsonArray{width(),height()};state->capture["desktopPixelSize"]=QJsonArray{image.width(),image.height()};
        if(white==4)state->failures.append("The composited Vulkan child is uniformly white: "+name);
        if(maxError>12)state->failures.append("Composited Vulkan child does not show the uploaded calibration: "+name);
        state->captures.append(state->capture);state->capturePending=false;return true;
    };
    const auto reset=[this,state,sizePixels]{
        ++state->version;state->previousSerial=state->serial;
        state->previousUploads=viewport_->renderDiagnostics().value("sceneUploads").toDouble();
        const auto camera=calibrationCamera();
        // Queue a preceding-session reference callback while the GUI cannot consume it.
        std::thread oldCallback([this,camera]{
            viewport_->setFrameSnapshot(QByteArray::fromHex("ff00ffff00ffffff00ff00ffffff00ff"),QSize(2,2),999,camera);
        });oldCallback.join();
        viewport_->resetScene();viewport_->setCamera(camera);viewport_->setEnvironment({{"time",6000},{"effects",false}});
        viewport_->setScene(calibrationScene(state->version));viewport_->setBrush(QString{},1,0);
        if(state->version==2)sizePixels({1920,1080});
        if(state->version==4)sizePixels({1280,720});
        state->startRecord=state->recorded;state->phaseTime.restart();state->phase=6;
    };
    connect(timer,&QTimer::timeout,this,[this,state,timer,finish,beginCapture,finishDesktopCapture,reset]{
        Q_UNUSED(timer);
        if(state->total.elapsed()>60000){state->failures.append("Retained Vulkan calibration exceeded its bounded timeout");finish();return;}
        if(!viewport_){state->failures.append("Vulkan viewport is unavailable");finish();return;}
        const auto stats=viewport_->renderDiagnostics();
        state->maxGpuBytes=std::max(state->maxGpuBytes,stats.value("sceneGpuAllocatedBytes").toDouble());
        state->maxGenerations=std::max(state->maxGenerations,stats.value("sceneRetainedGenerations").toDouble());
        if(state->maxGenerations>3){state->failures.append("Retained scene generations exceeded three in-flight frame slots");finish();return;}
        if(state->phase==0){
            if(!viewport_->isValid()||!viewport_->isExposed())return;
            viewport_->resetScene();viewport_->setCamera(calibrationCamera());viewport_->setEnvironment({{"time",6000},{"effects",false}});
            viewport_->setBrush(QString{},1,0);viewport_->setScene(calibrationScene(0));
            state->phaseTime.restart();state->phase=1;return;
        }
        if(state->phase==1){
            if(state->recorded<3){viewport_->requestUpdate();return;}
            state->initial=stats;state->report["initialResources"]=stats;
            if(!beginCapture("calibration-initial")){finish();return;}state->phase=2;return;
        }
        if(state->phase==2){
            if(!finishDesktopCapture())return;
            state->navBefore=viewport_->renderDiagnostics();state->startRecord=state->recorded;state->phase=3;return;
        }
        if(state->phase==3){
            state->injecting=true;const QPointF local(viewport_->width()*.5,viewport_->height()*.5);
            if(state->navigation%3==2){
                QWheelEvent event(local,viewport_->mapToGlobal(local.toPoint()),QPoint{},QPoint(0,state->navigation%2?120:-120),
                    Qt::NoButton,Qt::NoModifier,Qt::ScrollUpdate,false);QCoreApplication::sendEvent(viewport_,&event);
            }else{
                const auto button=state->navigation%3?Qt::MiddleButton:Qt::RightButton;
                const auto send=[this,button](QEvent::Type type,QPointF point,Qt::MouseButton changed,Qt::MouseButtons held){
                    QMouseEvent event(type,point,viewport_->mapToGlobal(point.toPoint()),changed,held,Qt::NoModifier);QCoreApplication::sendEvent(viewport_,&event);
                };
                send(QEvent::MouseButtonPress,local,button,button);
                send(QEvent::MouseMove,local+QPointF(3,2),Qt::NoButton,button);
                send(QEvent::MouseButtonRelease,local+QPointF(3,2),button,Qt::NoButton);
            }
            state->injecting=false;
            if(++state->navigation>=120){state->phaseTime.restart();state->phase=4;}return;
        }
        if(state->phase==4){
            if(state->phaseTime.elapsed()<300)return;commitCamera();
            state->navAfter=viewport_->renderDiagnostics();state->report["navigationBefore"]=state->navBefore;state->report["navigationAfter"]=state->navAfter;
            state->report["localNavigationInputs"]=state->navigation;state->report["navigationRecordedFrames"]=double(state->recorded-state->startRecord);
            for(const auto &key:{"sceneUploads","sceneBufferAllocations","sceneImageAllocations"})
                if(state->navBefore.value(key)!=state->navAfter.value(key))state->failures.append(QString("Local navigation allocated or reuploaded scene resources: ")+key);
            if(state->recorded<=state->startRecord)state->failures.append("Local navigation did not record actual Vulkan scene frames");
            if(interactionDiagnostics().value("cameraRequests").toDouble()!=0)state->failures.append("Local navigation issued worker camera RPC");
            viewport_->setCamera(calibrationCamera());viewport_->setBrush(QString{},1,0);state->startRecord=state->recorded;state->phase=5;return;
        }
        if(state->phase==5){
            if(state->recorded<=state->startRecord||!calibrationPose(state->recordedCamera))return;reset();return;
        }
        if(state->phase==6){
            if(stats.value("acceptedSceneRevision").toDouble()!=0){state->failures.append("A preceding-epoch queued RGBA callback survived reset");finish();return;}
            if(stats.value("sceneUploads").toDouble()<state->previousUploads+1||state->recorded<state->startRecord+3){viewport_->requestUpdate();return;}
            if(state->serial<=state->previousSerial)state->failures.append("GPU frame serial stopped being monotonic across reset");
            if(stats.value("sceneUploads").toDouble()!=state->previousUploads+1)state->failures.append("Reset performed duplicate retained-scene uploads");
            if(!beginCapture(QString("calibration-reset-%1").arg(state->version))){finish();return;}state->phase=7;return;
        }
        if(state->phase==7){
            if(!finishDesktopCapture())return;
            if(state->version<5){reset();return;}state->phaseTime.restart();state->phase=8;return;
        }
        if(state->phase==8){
            if(state->phaseTime.elapsed()<250)return;
            state->idleBefore=viewport_->renderDiagnostics();state->report["idleBefore"]=state->idleBefore;
            state->phaseTime.restart();state->phase=9;return;
        }
        if(state->phase==9){
            if(state->phaseTime.elapsed()<1000)return;
            state->report["idleAfter"]=stats;state->report["idleObservedMs"]=double(state->phaseTime.elapsed());
            for(const auto &key:{"frames","localSceneFrames","sceneUploads","sceneBufferAllocations","sceneImageAllocations"})
                if(state->idleBefore.value(key)!=stats.value(key))state->failures.append(QString("Idle viewport continued work: ")+key);
            state->report["queuedPreviousEpochRgbaDiscarded"]=true;finish();
        }
    });
    timer->start();
}
