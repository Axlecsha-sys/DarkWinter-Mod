// Capture an explicitly requested owned-window UI/Vulkan diagnostic without launching Minecraft.
#include "EditorWindow.hpp"
#include "PreviewViewport.hpp"
#include <QAbstractButton>
#include <QAbstractItemModel>
#include <QAction>
#include <QAbstractSpinBox>
#include <QCheckBox>
#include <QComboBox>
#include <QCoreApplication>
#include <QDir>
#include <QDoubleSpinBox>
#include <QElapsedTimer>
#include <QFileInfo>
#include <QFile>
#include <QFontMetrics>
#include <QImage>
#include <QImageReader>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLabel>
#include <QLineEdit>
#include <QListWidget>
#include <QSaveFile>
#include <QScreen>
#include <QTabBar>
#include <QTabWidget>
#include <QTimer>
#include <QWheelEvent>
#include <QToolButton>
#include <QVulkanFunctions>
#include <QVulkanInstance>
#include <memory>
#include <thread>
#include <functional>
#include <cmath>
#include <algorithm>

namespace {
bool interactive(QWidget *widget) {
    return qobject_cast<QAbstractButton*>(widget)||qobject_cast<QAbstractSpinBox*>(widget)||
           qobject_cast<QComboBox*>(widget)||qobject_cast<QLineEdit*>(widget)||
           qobject_cast<QListWidget*>(widget)||qobject_cast<QTabBar*>(widget)||qobject_cast<QLabel*>(widget);
}
QRect visibleRect(QWidget *widget) {
    const auto local=widget->visibleRegion().boundingRect();
    return QRect(widget->mapToGlobal(local.topLeft()),local.size());
}
QJsonArray overlaps(QWidget *window) {
    QList<QWidget*> controls;
    for(auto *widget:window->findChildren<QWidget*>()) {
        if(!widget->isVisible()||widget->visibleRegion().isEmpty()||!interactive(widget))continue;
        bool nested=false;for(auto *parent=widget->parentWidget();parent&&parent!=window;parent=parent->parentWidget())if(interactive(parent)){nested=true;break;}
        if(!nested)controls.append(widget);
    }
    QJsonArray errors;
    for(qsizetype i=0;i<controls.size();++i)for(qsizetype j=i+1;j<controls.size();++j){
        const auto intersection=visibleRect(controls[i]).intersected(visibleRect(controls[j]));
        if(intersection.width()>2&&intersection.height()>2)
            errors.append(QJsonObject{{"first",QString::fromLatin1(controls[i]->metaObject()->className())+":"+controls[i]->objectName()},
                                      {"second",QString::fromLatin1(controls[j]->metaObject()->className())+":"+controls[j]->objectName()},
                                      {"width",intersection.width()},{"height",intersection.height()}});
    }
    return errors;
}
struct Smoke {
    QString output,imagePath;
    QImage source;
    QByteArray rgba;
    QJsonObject report;
    QJsonArray captures,failures;
    QJsonObject baseline,afterBurst,idle,restart;
    QElapsedTimer restartWait;
    int phase=0,burst=0,layerMutations=0;
    bool fontStable=true;
};
}
void EditorWindow::runUiSmoke(const QString &output,const QString &imagePath) {
    auto state=std::make_shared<Smoke>();state->output=QFileInfo(output).absoluteFilePath();state->imagePath=imagePath;
    if(output.isEmpty()||!QDir().mkpath(state->output)){QCoreApplication::exit(2);return;}
    state->report={{"schema",1},{"scope","Owned C++ Qt UI and Vulkan diagnostic only; no Minecraft worker launched"},
                   {"imageSource",imagePath.isEmpty()?"synthetic calibration, diagnostic only":imagePath},
                   {"freshWorkerSceneVerified",false},{"generationVerified",false},{"validationLayerClaim",false}};
    const auto sizePixels=[this](QSize pixels){const qreal ratio=screen()->devicePixelRatio();resize(qRound(pixels.width()/ratio),qRound(pixels.height()/ratio));move(screen()->availableGeometry().topLeft()+QPoint(5,5));};
    setWindowFlag(Qt::WindowStaysOnTopHint,true);sizePixels({1280,720});
    QTimer::singleShot(0,this,[this]{hide();showNormal();raise();});
    auto *timer=new QTimer(this);timer->setInterval(1400);
    auto capture=[this,state](const QString &name){
        const auto origin=mapToGlobal(QPoint(0,0));
        const auto image=screen()->grabWindow(0,origin.x(),origin.y(),width(),height()).toImage();
        const auto outputPath=QDir(state->output).filePath(name+".png");
        const auto errors=overlaps(this);
        if(image.isNull()||!image.save(outputPath))state->failures.append("Native window capture failed: "+name);
        if(!errors.isEmpty())state->failures.append("Visible control overlap: "+name);
        state->captures.append(QJsonObject{{"file",outputPath},{"logicalWidth",width()},{"logicalHeight",height()},
                                          {"pixelWidth",image.width()},{"pixelHeight",image.height()},{"overlaps",errors},
                                          {"fontHeight",QFontMetrics(font()).height()},{"captureMethod","Composited desktop crop limited to the exposed owned app client rectangle"}});
    };
    connect(timer,&QTimer::timeout,this,[this,state,timer,capture,sizePixels]{
        if(state->phase==0){
            capture("waiting-1280x720");
            if(!viewport_||!viewport_->isValid()){state->failures.append("Vulkan viewport was not initialized");state->phase=7;return;}
            VkPhysicalDeviceProperties properties{};
            vulkan_->functions()->vkGetPhysicalDeviceProperties(viewport_->physicalDevice(),&properties);
            state->report["vulkanDevice"]=QString::fromUtf8(properties.deviceName);
            state->report["apiVersion"]=double(properties.apiVersion);
            sizePixels({1920,1080});state->phase=1;return;
        }
        if(state->phase==1){
            capture("waiting-1920x1080");
            if(!state->imagePath.isEmpty()){
                QImageReader reader(state->imagePath);const auto size=reader.size();
                if(size.width()>0&&size.height()>0&&size.width()<=4096&&size.height()<=4096)state->source=reader.read();
            }else{
                state->source=QImage(320,180,QImage::Format_RGBA8888);
                for(int y=0;y<state->source.height();++y)for(int x=0;x<state->source.width();++x)
                    state->source.setPixelColor(x,y,QColor(x*255/319,y*255/179,85));
            }
            state->source=state->source.convertToFormat(QImage::Format_RGBA8888);
            if(state->source.isNull()){state->failures.append("Diagnostic input image failed");state->phase=7;return;}
            state->rgba=QByteArray(reinterpret_cast<const char*>(state->source.constBits()),state->source.sizeInBytes());
            viewport_->setFrame(state->rgba,state->source.size(),1);
            previewMessage_->setText(QStringLiteral("ДИАГНОСТИКА VULKAN • статический контрольный кадр, не результат текущего генератора"));
            state->phase=2;return;
        }
        if(state->phase==2){
            capture("diagnostic-1920x1080");
            state->baseline=viewport_->renderDiagnostics();
            const auto cameraBefore=viewport_->camera();
            document_.appendOperation(QJsonObject{{"kind","raise"},{"x",40},{"z",40},{"radius",8},{"strength",1}},QStringLiteral("Проверка слоя"));
            connect(layers_->model(),&QAbstractItemModel::rowsInserted,this,[state]{++state->layerMutations;});
            connect(layers_->model(),&QAbstractItemModel::rowsRemoved,this,[state]{++state->layerMutations;});
            connect(layers_->model(),&QAbstractItemModel::modelReset,this,[state]{++state->layerMutations;});
            const auto wheel=[this]{
                const QPointF local(viewport_->width()*.5,viewport_->height()*.5);
                QWheelEvent event(local,viewport_->mapToGlobal(local.toPoint()),QPoint{},QPoint(0,120),Qt::NoButton,Qt::NoModifier,Qt::ScrollUpdate,false);
                QCoreApplication::sendEvent(viewport_,&event);
            };
            for(int i=0;i<12;++i)wheel();
            const auto latestCamera=viewport_->camera();
            state->report["navigationWasDeferred"]=cameraEdited_;
            undoAction_->trigger();
            if(document_.profile().value("camera").toObject()!=cameraBefore)state->failures.append("Undo did not include the pending navigation burst");
            redoAction_->trigger();
            if(document_.profile().value("camera").toObject()!=latestCamera)state->failures.append("Redo did not restore the latest navigation pose");
            QString saveError;
            const auto profilePath=QDir(state->output).filePath("interaction-profile.json");
            if(!document_.save(profilePath,&saveError))state->failures.append("Diagnostic profile setup failed: "+saveError);
            wheel();const auto cameraAtSave=viewport_->camera();
            if(!save())state->failures.append("Manual save failed to flush the pending camera");
            QFile savedProfile(profilePath);
            if(!savedProfile.open(QIODevice::ReadOnly)||QJsonDocument::fromJson(savedProfile.readAll()).object().value("camera").toObject()!=cameraAtSave)
                state->failures.append("Saved profile omitted the last uncommitted navigation pose");
            document_.setValue({"environment","time"},18000,QStringLiteral("Проверка окружения"));
            document_.setValue({"environment","time"},6000,QStringLiteral("Проверка окружения"));
            state->report["cameraEnvironmentLayerMutations"]=state->layerMutations;
            if(state->layerMutations)state->failures.append("Camera/environment changes rebuilt the operations list");
            state->report["interaction"]=interactionDiagnostics();
            auto *tool=findChild<QToolButton*>("tool_river");
            const int before=tool?QFontMetrics(tool->font()).height():-1;
            if(tool)tool->click();else state->failures.append("River tool missing");
            if(auto *tabs=findChild<QTabWidget*>("propertyTabs"))tabs->setCurrentIndex(2);
            cut_->setChecked(true);cutY_->setValue(75);
            const int after=tool?QFontMetrics(tool->font()).height():-2;
            state->fontStable=before==after;
            if(!state->fontStable)state->failures.append("Tool selection changed font metrics");
            sizePixels({1280,720});state->phase=3;return;
        }
        if(state->phase==3){
            capture("diagnostic-cut-controls-1280x720");
            state->report["optionalCutawayStored"]=document_.profile().value("camera").toObject().value("cutY").toInt()==75;
            if(!state->report.value("optionalCutawayStored").toBool())state->failures.append("Cutaway was not stored in the real document");
            cut_->setChecked(false);
            viewport_->setFrame(state->rgba,state->source.size(),800);
            const auto beforeReset=viewport_->renderDiagnostics(),capturedCamera=viewport_->camera();
            std::thread queuedOldFrame([this,state,capturedCamera]{viewport_->setFrameSnapshot(state->rgba,state->source.size(),900,capturedCamera);});
            queuedOldFrame.join();
            viewport_->resetScene();
            const auto reset=viewport_->renderDiagnostics();
            if(reset.value("displayValid").toBool()||reset.value("acceptedSceneRevision").toDouble()!=0)state->failures.append("Restart retained the preceding displayed/accepted scene");
            viewport_->setFrameSnapshot(state->rgba,state->source.size(),1,capturedCamera);
            state->restart=viewport_->renderDiagnostics();
            state->report["restartBefore"]=beforeReset;state->report["restartCleared"]=reset;
            if(state->restart.value("acceptedSerial").toDouble()<=beforeReset.value("acceptedSerial").toDouble())state->failures.append("Restart reset the monotonic GPU upload serial");
            state->restartWait.start();timer->setInterval(45);state->phase=4;return;
        }
        if(state->phase==4){
            if(state->burst==0){
                const auto restarted=viewport_->renderDiagnostics();
                if(restarted.value("acceptedSceneRevision").toDouble()==1&&restarted.value("lastSceneRevision").toDouble()!=1&&state->restartWait.elapsed()<1500)return;
                state->report["restartAfterQueuedCallbacks"]=restarted;
                if(restarted.value("acceptedSceneRevision").toDouble()!=1||restarted.value("lastSceneRevision").toDouble()!=1)
                    state->failures.append("A preceding-epoch queued frame replaced the restarted scene");
            }
            viewport_->setFrame(state->rgba,state->source.size(),quint64(++state->burst+1));
            if(state->burst>=30){timer->setInterval(1200);state->phase=5;}return;
        }
        if(state->phase==5){
            state->afterBurst=viewport_->renderDiagnostics();
            const auto camera=viewport_->camera();
            for(int i=0;i<200;++i){viewport_->setCamera(camera);viewport_->setBrush(QString{},radius_->value(),workY_->value());}
            const auto afterNoops=viewport_->renderDiagnostics();
            state->report["sameStateRedrawRequests"]=afterNoops.value("redrawRequests").toDouble()-state->afterBurst.value("redrawRequests").toDouble();
            if(state->report.value("sameStateRedrawRequests").toDouble()!=0)state->failures.append("Repeated unchanged camera/brush state scheduled Vulkan redraws");
            state->phase=6;return;
        }
        if(state->phase==6){
            state->idle=viewport_->renderDiagnostics();
            state->report["initialResources"]=state->baseline;state->report["afterThirtySameSizeFrames"]=state->afterBurst;state->report["idleResources"]=state->idle;
            if(state->afterBurst.value("imageAllocations")!=state->baseline.value("imageAllocations"))state->failures.append("Same-size frame delivery reallocated the sampled image");
            if(state->afterBurst.value("stagingAllocations")!=state->baseline.value("stagingAllocations"))state->failures.append("Same-size delivery reallocated staging buffers");
            if(state->idle.value("frames").toDouble()!=state->afterBurst.value("frames").toDouble())state->failures.append("Viewport continued rendering while idle");
            if(state->afterBurst.value("uploads").toDouble()<state->baseline.value("uploads").toDouble()+10)state->failures.append("Diagnostic frame upload coverage was insufficient");
            if(viewport_->supportsGrab()){
                const QImage gpu=viewport_->grab().convertToFormat(QImage::Format_RGBA8888);
                if(gpu.isNull()||!gpu.save(QDir(state->output).filePath("native-vulkan-readback.png")))state->failures.append("Vulkan readback failed");
                state->report["vulkanReadbackAvailable"]=!gpu.isNull();
                state->report["vulkanReadbackSize"]=QJsonArray{gpu.width(),gpu.height()};
                if(!gpu.isNull()) {
                    const double scale=std::min(double(gpu.width())/state->source.width(),double(gpu.height())/state->source.height());
                    const double imageWidth=state->source.width()*scale,imageHeight=state->source.height()*scale;
                    const double offsetX=(gpu.width()-imageWidth)*0.5,offsetY=(gpu.height()-imageHeight)*0.5;
                    int maxError=0;
                    for(double u:{0.125,0.375,0.625,0.875})for(double v:{0.125,0.375,0.625,0.875}) {
                        const int px=int(offsetX+u*imageWidth),py=int(offsetY+v*imageHeight);
                        const double sx=((px+0.5-offsetX)/imageWidth)*state->source.width()-0.5,sy=((py+0.5-offsetY)/imageHeight)*state->source.height()-0.5;
                        const int x0=std::clamp(int(std::floor(sx)),0,state->source.width()-1),y0=std::clamp(int(std::floor(sy)),0,state->source.height()-1);
                        const int x1=std::min(x0+1,state->source.width()-1),y1=std::min(y0+1,state->source.height()-1);
                        const double fx=sx-std::floor(sx),fy=sy-std::floor(sy);
                        const QColor p00=state->source.pixelColor(x0,y0),p10=state->source.pixelColor(x1,y0),p01=state->source.pixelColor(x0,y1),p11=state->source.pixelColor(x1,y1),actual=gpu.pixelColor(px,py);
                        for(int channel=0;channel<3;++channel) {
                            const auto component=[channel](const QColor &color){return channel==0?color.red():channel==1?color.green():color.blue();};
                            const int expected=qRound(component(p00)*(1-fx)*(1-fy)+component(p10)*fx*(1-fy)+component(p01)*(1-fx)*fy+component(p11)*fx*fy);
                            maxError=std::max(maxError,std::abs(component(actual)-expected));
                        }
                    }
                    state->report["readbackBilinearReferenceMaxChannelError"]=maxError;
                    if(maxError>3)state->failures.append("Vulkan readback differs from the exact bilinear source reference");
                }
            }else state->report["vulkanReadbackAvailable"]=false;
            state->phase=7;return;
        }
        timer->stop();state->report["captures"]=state->captures;state->report["failures"]=state->failures;
        state->report["fontMetricsStable"]=state->fontStable;state->report["status"]=state->failures.isEmpty()?"PASS":"FAIL";
        QSaveFile file(QDir(state->output).filePath("ui-smoke.json"));const auto bytes=QJsonDocument(state->report).toJson(QJsonDocument::Indented);
        const bool saved=file.open(QIODevice::WriteOnly)&&file.write(bytes)==bytes.size()&&file.commit();
        document_.reset();close();QCoreApplication::exit(saved&&state->failures.isEmpty()?0:1);
    });
    timer->start();
}
