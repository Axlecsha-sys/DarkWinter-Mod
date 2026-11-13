// Validate bounded local IPC receipts and reject frames from superseded scenes.
#include "WorkerClient.hpp"
#include "Document.hpp"
#include <QDir>
#include <QCoreApplication>
#include <QCryptographicHash>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QRegularExpression>
#include <QSaveFile>
#include <QUuid>
#include <cmath>
#include <limits>
#include <algorithm>
#include <QThread>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <functional>
#include <mutex>
#include <optional>
#include <thread>
#ifdef Q_OS_WIN
#include <windows.h>
#endif

namespace {
constexpr quint64 JsonIntegerLimit=9007199254740991ULL;
bool fail(QString *out,const QString &message) { if(out) *out=message; return false; }
bool revision(const QJsonValue &v,quint64 *out) {
    if(!v.isDouble() || !std::isfinite(v.toDouble()) || v.toDouble()<1 ||
       v.toDouble()>double(JsonIntegerLimit) || std::floor(v.toDouble())!=v.toDouble()) return false;
    *out=quint64(v.toDouble()); return true;
}
bool within(const QString &path,const QString &directory) {
    const auto clean=QDir::cleanPath(QFileInfo(path).absoluteFilePath());
    const auto root=QDir::cleanPath(QFileInfo(directory).absoluteFilePath())+QStringLiteral("/");
    return clean.startsWith(root,Qt::CaseInsensitive);
}
QByteArray boundedRead(const QString &path,qint64 limit,const std::function<bool()> &cancel={},quint64 *readBytes=nullptr) {
    if(cancel&&cancel())return {};
    const QFileInfo info(path);
    if(info.isSymLink() || !info.isFile() || info.size()<0 || info.size()>limit) return {};
#ifdef Q_OS_WIN
    // Sharing deletion lets atomic publishers replace a file while this handle pins its complete version.
    const HANDLE native=CreateFileW(reinterpret_cast<LPCWSTR>(path.utf16()),GENERIC_READ,
        FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,FILE_FLAG_SEQUENTIAL_SCAN|FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
    if(native==INVALID_HANDLE_VALUE)return {};
    struct Close {HANDLE handle;~Close(){CloseHandle(handle);}} close{native};
    LARGE_INTEGER size{};BY_HANDLE_FILE_INFORMATION details{};
    if(GetFileType(native)!=FILE_TYPE_DISK||!GetFileSizeEx(native,&size)||size.QuadPart<0||size.QuadPart>limit||
       !GetFileInformationByHandle(native,&details)||(details.dwFileAttributes&FILE_ATTRIBUTE_REPARSE_POINT))return {};
    QByteArray bytes(qsizetype(size.QuadPart),Qt::Uninitialized);qsizetype offset=0;
    while(offset<bytes.size()){
        if(cancel&&cancel())return {};
        DWORD received=0;const DWORD requested=DWORD(std::min<qsizetype>(1024*1024,bytes.size()-offset));
        if(!ReadFile(native,bytes.data()+offset,requested,&received,nullptr)||!received)return {};
        offset+=received;if(readBytes)*readBytes+=received;
    }
    return bytes;
#else
    QFile file(path);if(!file.open(QIODevice::ReadOnly))return {};
    QByteArray bytes;bytes.reserve(qsizetype(info.size()));
    while(!file.atEnd()){
        if(cancel&&cancel())return {};
        const auto part=file.read(std::min<qint64>(1024*1024,limit+1-bytes.size()));
        if(part.isEmpty()&&file.error()!=QFileDevice::NoError)return {};
        bytes.append(part);if(readBytes)*readBytes+=quint64(part.size());if(bytes.size()>limit)return {};
    }
    return bytes;
#endif
}
QJsonObject object(const QByteArray &data) {
    QJsonParseError error;
    const auto document=QJsonDocument::fromJson(data,&error);
    return error.error==QJsonParseError::NoError && document.isObject()?document.object():QJsonObject{};
}
bool stringArray(const QJsonValue &value,QStringList *out,int cap=2048) {
    if(!value.isArray() || value.toArray().size()>cap) return false;
    for(const auto &v:value.toArray()) {
        if(!v.isString() || v.toString().size()>32768 || v.toString().contains(QChar::Null)) return false;
        out->append(v.toString());
    }
    return true;
}

struct IoRequest {
    QString ipc;
    quint64 token=0,epoch=0,request=0,scene=0;
    QByteArray lastState,lastFrame;
    QJsonObject camera;
    QByteArray lastScene,lastLighting;
    bool sceneLoaded=false,diagnosticFrames=false;
};
struct IoMeasurements {
    quint64 jobs=0,coalescedPolls=0,frameReads=0,validatedFrames=0,rejectedFrames=0;
    quint64 supersededJobs=0,metadataChanged=0,payloadBytesRead=0,shaBytesHashed=0,ownerThreadIoViolations=0;
    quint64 sceneReads=0,validatedScenes=0,rejectedScenes=0,lightingReads=0,sceneCacheHits=0,lightingCacheHits=0;
    int maxPendingJobs=0,maxCompletedFrames=0;
    qint64 maxIoNanos=0,maxPayloadReadNanos=0,maxShaNanos=0;
};
struct IoResult {
    IoRequest job;
    QByteArray stateBytes,frameBytes,rgba;
    QJsonObject state,frame;
    QSize size;
    QByteArray sceneBytes,lightingBytes;
    std::shared_ptr<const SceneBundle> scene;
    std::shared_ptr<const SceneLighting> lighting;
    QString diagnostic;
    IoMeasurements measurements;
    std::chrono::steady_clock::time_point completedAt;
};
IoResult readReceipt(const IoRequest &job,const std::function<bool()> &cancel) {
    IoResult result;result.job=job;result.measurements.jobs=1;QElapsedTimer total;total.start();
    const auto directory=QDir(job.ipc);
    const auto stateBytes=boundedRead(directory.filePath("state.json"),16384,cancel);
    if(!stateBytes.isEmpty()&&stateBytes!=job.lastState){
        const auto state=object(stateBytes);quint64 stateRequest=0,stateScene=0;
        const auto status=state.value("status").toString();
        if(state.value("schema").toInt()==1&&revision(state.value("revision"),&stateRequest)&&stateRequest<=job.request&&
           revision(state.value("sceneRevision"),&stateScene)&&stateScene==job.scene&&
           QStringList{"starting","generating","ready","failed","stopped"}.contains(status)&&
           state.value("progress").isDouble()&&std::isfinite(state.value("progress").toDouble())&&
           state.value("progress").toDouble()>=0&&state.value("progress").toDouble()<=1){
            result.stateBytes=stateBytes;result.state=state;
        }
    }
    const auto addStats=[&](const SceneReadStats &stats){
        result.measurements.payloadBytesRead+=stats.payloadBytesRead;result.measurements.shaBytesHashed+=stats.shaBytesHashed;
        result.measurements.maxPayloadReadNanos=std::max(result.measurements.maxPayloadReadNanos,stats.maxReadNanos);
        result.measurements.maxShaNanos=std::max(result.measurements.maxShaNanos,stats.maxShaNanos);
    };
    if(!job.sceneLoaded&&!cancel()){
        const auto scenePath=directory.filePath("scene.json");
        const qint64 size=QFileInfo(scenePath).size();
        const auto bytes=size>SceneBundleReader::ManifestLimit?QByteArray("oversized-scene:")+QByteArray::number(size):
            boundedRead(scenePath,SceneBundleReader::ManifestLimit,cancel);
        if(!bytes.isEmpty()&&bytes!=job.lastScene){
            const auto manifest=object(bytes);quint64 scene=0,publishedRevision=0;
            if(size>SceneBundleReader::ManifestLimit||manifest.isEmpty()){
                result.sceneBytes=bytes;++result.measurements.rejectedScenes;
                result.diagnostic=size>SceneBundleReader::ManifestLimit?QStringLiteral("Манифест сцены превышает предел 1 МиБ."):QStringLiteral("Повреждён JSON манифеста сцены.");
            }
            if(revision(manifest.value("sceneRevision"),&scene)&&scene==job.scene&&
               revision(manifest.value("revision"),&publishedRevision)&&publishedRevision<=job.request){
                result.sceneBytes=bytes;++result.measurements.sceneReads;SceneReadStats stats;
                result.scene=SceneBundleReader::read(job.ipc,bytes,job.scene,job.request,cancel,&stats,&result.diagnostic);addStats(stats);
                if(result.scene)++result.measurements.validatedScenes;else if(!cancel())++result.measurements.rejectedScenes;
            }
        }
    }
    if(!cancel()){
        const auto bytes=boundedRead(directory.filePath("lighting.json"),SceneBundleReader::ManifestLimit,cancel);
        if(!bytes.isEmpty()&&bytes!=job.lastLighting){
            const auto manifest=object(bytes);quint64 scene=0,publishedRevision=0;
            if(revision(manifest.value("sceneRevision"),&scene)&&scene==job.scene&&
               revision(manifest.value("revision"),&publishedRevision)&&publishedRevision<=job.request){
                result.lightingBytes=bytes;++result.measurements.lightingReads;SceneReadStats stats;QString error;
                result.lighting=SceneBundleReader::readLighting(job.ipc,bytes,job.scene,job.request,cancel,&stats,&error);addStats(stats);
                if(!result.lighting&&!error.isEmpty())result.diagnostic=error;
            }
        }
    }
    const auto framePath=directory.filePath("frame.json");
    const auto frameBytes=job.diagnosticFrames?boundedRead(framePath,16384,cancel):QByteArray{};
    if(!cancel()&&!frameBytes.isEmpty()&&frameBytes!=job.lastFrame){
        const auto frame=object(frameBytes);
        if(WorkerClient::validateFrame(frame,job.request,job.scene)){
            const int width=frame.value("width").toInt(),height=frame.value("height").toInt();
            const qint64 expected=qint64(width)*height*4;
            const auto imagePath=directory.filePath(frame.value("file").toString());
            if(within(imagePath,job.ipc)&&!QFileInfo(imagePath).isSymLink()){
                ++result.measurements.frameReads;QElapsedTimer readTime;readTime.start();
                auto rgba=boundedRead(imagePath,expected,cancel,&result.measurements.payloadBytesRead);
                result.measurements.maxPayloadReadNanos=readTime.nsecsElapsed();
                bool valid=rgba.size()==expected&&!cancel();
                if(valid&&frame.contains("sha256")){
                    QElapsedTimer shaTime;shaTime.start();QCryptographicHash hash(QCryptographicHash::Sha256);
                    for(qsizetype offset=0;offset<rgba.size()&&!cancel();offset+=1024*1024){
                        const auto count=std::min<qsizetype>(1024*1024,rgba.size()-offset);
                        hash.addData(QByteArrayView(rgba.constData()+offset,count));result.measurements.shaBytesHashed+=quint64(count);
                    }
                    valid=!cancel()&&hash.result().toHex()==frame.value("sha256").toString().toLatin1().toLower();
                    result.measurements.maxShaNanos=shaTime.nsecsElapsed();
                }
                if(valid){
                    // A ring slot may be atomically replaced while it is being read. Pin the payload, verify its hash,
                    // and recheck the published request, scene and pose before delivering it.
                    const auto publishedBytes=boundedRead(framePath,16384,cancel);
                    if(publishedBytes!=frameBytes){
                        ++result.measurements.metadataChanged;
                        const auto published=object(publishedBytes);quint64 oldSequence=0,newSequence=0;
                        // A verified immutable payload remains usable when animation has published a newer frame
                        // of exactly the same pose. Requiring stable animated metadata would starve slow reads.
                        valid=frame.contains("sha256")&&published.contains("sha256")&&frame.value("camera").isObject()&&WorkerClient::validateFrame(published,job.request,job.scene)&&
                            revision(frame.value("sequence"),&oldSequence)&&revision(published.value("sequence"),&newSequence)&&newSequence>oldSequence&&
                            published.value("camera")==frame.value("camera")&&published.value("width")==frame.value("width")&&
                            published.value("height")==frame.value("height")&&published.value("format")==frame.value("format");
                    }
                }
                if(valid&&!cancel()){
                    result.rgba=std::move(rgba);result.frameBytes=frameBytes;result.frame=frame;result.size=QSize(width,height);
                    ++result.measurements.validatedFrames;
                }else ++result.measurements.rejectedFrames;
            }
        }else ++result.measurements.rejectedFrames;
    }
    if(cancel()){++result.measurements.supersededJobs;result.stateBytes.clear();result.rgba.clear();result.frameBytes.clear();result.sceneBytes.clear();result.lightingBytes.clear();result.scene.reset();result.lighting.reset();result.diagnostic.clear();}
    result.measurements.maxIoNanos=total.nsecsElapsed();result.completedAt=std::chrono::steady_clock::now();return result;
}
void accumulate(IoMeasurements &target,const IoMeasurements &source){
    target.jobs+=source.jobs;target.frameReads+=source.frameReads;target.validatedFrames+=source.validatedFrames;
    target.sceneCacheHits+=source.sceneCacheHits;target.lightingCacheHits+=source.lightingCacheHits;
    target.sceneReads+=source.sceneReads;target.validatedScenes+=source.validatedScenes;target.rejectedScenes+=source.rejectedScenes;target.lightingReads+=source.lightingReads;
    target.rejectedFrames+=source.rejectedFrames;target.supersededJobs+=source.supersededJobs;
    target.metadataChanged+=source.metadataChanged;target.payloadBytesRead+=source.payloadBytesRead;target.shaBytesHashed+=source.shaBytesHashed;
    target.ownerThreadIoViolations+=source.ownerThreadIoViolations;
    target.maxIoNanos=std::max(target.maxIoNanos,source.maxIoNanos);
    target.maxPayloadReadNanos=std::max(target.maxPayloadReadNanos,source.maxPayloadReadNanos);
    target.maxShaNanos=std::max(target.maxShaNanos,source.maxShaNanos);
}
}
struct WorkerClient::IoState {
    std::mutex mutex;
    std::condition_variable wake;
    std::atomic<bool> stopping{false};
    std::atomic<quint64> token{0};
    std::optional<IoRequest> pending;
    std::optional<IoResult> completed;
    IoMeasurements measurements;
    bool active=false;
    std::thread thread;
};
WorkerClient::WorkerClient(QObject *parent):QObject(parent),io_(std::make_unique<IoState>()) {
    const auto ownerThread=thread();
    io_->thread=std::thread([this,ownerThread]{
        QByteArray validatedMetadata,validatedSceneMetadata,validatedLightingMetadata;
        quint64 validatedToken=0,receiptToken=0;
        bool sceneValidated=false;
        for(;;){
            IoRequest job;
            {
                std::unique_lock lock(io_->mutex);
                io_->wake.wait(lock,[this]{return io_->stopping.load()||(io_->pending.has_value()&&!io_->completed.has_value());});
                if(io_->stopping.load())return;
                job=std::move(*io_->pending);io_->pending.reset();io_->active=true;
            }
            if(validatedToken==job.token&&!validatedMetadata.isEmpty())job.lastFrame=validatedMetadata;
            if(receiptToken!=job.token){
                receiptToken=job.token;validatedSceneMetadata.clear();validatedLightingMetadata.clear();sceneValidated=false;
            }
            const bool sceneCacheHit=sceneValidated&&!job.sceneLoaded;
            const bool lightingCacheHit=!validatedLightingMetadata.isEmpty()&&job.lastLighting!=validatedLightingMetadata;
            if(sceneValidated){job.sceneLoaded=true;job.lastScene=validatedSceneMetadata;}
            if(!validatedLightingMetadata.isEmpty())job.lastLighting=validatedLightingMetadata;
            const auto canceled=[this,token=job.token]{return io_->stopping.load()||io_->token.load()!=token;};
            auto result=readReceipt(job,canceled);
            if(sceneCacheHit)++result.measurements.sceneCacheHits;if(lightingCacheHit)++result.measurements.lightingCacheHits;
            if(QThread::currentThread()==ownerThread)++result.measurements.ownerThreadIoViolations;
            const bool hasReceipt=!result.stateBytes.isEmpty()||!result.frameBytes.isEmpty()||!result.sceneBytes.isEmpty()||!result.lightingBytes.isEmpty();
            {
                std::lock_guard lock(io_->mutex);io_->active=false;accumulate(io_->measurements,result.measurements);
                if(io_->stopping.load())return;
                if(canceled()||!hasReceipt)continue;
                if(!result.frameBytes.isEmpty()){validatedMetadata=result.frameBytes;validatedToken=job.token;}
                // Populate this executor-local cache BEFORE waking a queued owner callback.
                // A pending poll may still contain its pre-delivery GUI metadata snapshot.
                // Only successful validation enters the cache; future/corrupt receipts remain retryable.
                if(result.scene){validatedSceneMetadata=result.sceneBytes;sceneValidated=true;}
                if(result.lighting)validatedLightingMetadata=result.lightingBytes;
                io_->completed=std::move(result);
                if(!io_->completed->rgba.isEmpty())io_->measurements.maxCompletedFrames=1;
            }
            // One completion notification carries no pixel data. Further reads wait until the owner consumes
            // this single result, while timer ticks replace only the latest pending request.
            QMetaObject::invokeMethod(this,&WorkerClient::drainIo,Qt::QueuedConnection);
        }
    });
    timer_.setInterval(50);timer_.setTimerType(Qt::PreciseTimer);publisher_.setSingleShot(true);
    connect(&publisher_,&QTimer::timeout,this,[this]{publishRequest();});
    connect(&timer_,&QTimer::timeout,this,&WorkerClient::poll);
    connect(&process_,&QProcess::readyReadStandardError,this,[this]{appendLog(process_.readAllStandardError());});
    connect(&process_,&QProcess::readyReadStandardOutput,this,[this]{appendLog(process_.readAllStandardOutput());});
    connect(&process_,&QProcess::started,this,[this]{timer_.start(); emit processChanged(true);});
    connect(&process_,&QProcess::errorOccurred,this,[this](QProcess::ProcessError) {emit diagnostic(process_.errorString());});
    connect(&process_,&QProcess::finished,this,[this](int exitCode,QProcess::ExitStatus) {
        poll(); timer_.stop();publisher_.stop();pendingRequest_.clear();emit processChanged(false);
        if(!stopping_) emit diagnostic(QStringLiteral("Процесс генерации завершён, код %1.").arg(exitCode));
    });
}
WorkerClient::~WorkerClient() {
    closing_=true;timer_.stop();shutdownIo();
    stop();
    QElapsedTimer deadline;deadline.start();
    while(running()&&deadline.elapsed()<2000){if(!pendingRequest_.isEmpty())publishRequest();process_.waitForFinished(25);}
    if(running()){forcedStop_=true;process_.terminate();if(!process_.waitForFinished(1000)){process_.kill();process_.waitForFinished(1000);}}
}
bool WorkerClient::running()const {return process_.state()!=QProcess::NotRunning;}
void WorkerClient::appendLog(const QByteArray &bytes) {
    log_.append(bytes.right(65536)); if(log_.size()>65536) log_.remove(0,log_.size()-65536);
}
bool WorkerClient::start(const QString &projectRoot,QString *error) {
    if(running()) return fail(error,QStringLiteral("Процесс генерации уже запущен."));
    const auto root=QFileInfo(projectRoot).canonicalFilePath();
    if(root.isEmpty() || !QFileInfo(root).isDir()) return fail(error,QStringLiteral("Каталог DarkWinter не найден."));
    const QString manifestPath=QDir(root).filePath("build/biome-editor/worker-launch.json");
    const auto manifest=object(boundedRead(manifestPath,1024*1024));
    if(manifest.isEmpty()) return fail(error,QStringLiteral("Подготовьте точный backend DarkWinter: отсутствует build/biome-editor/worker-launch.json."));
    if(manifest.value("schema").toInt()!=1) return fail(error,QStringLiteral("Неподдерживаемая версия конфигурации backend."));
    const QFileInfo java(manifest.value("java").toString());
    if(!java.isAbsolute() || !java.isFile() || java.isSymLink() || java.suffix().compare("exe",Qt::CaseInsensitive)!=0)
        return fail(error,QStringLiteral("В конфигурации backend не указан существующий Java.exe."));
    QStringList vmArgs,classpath,args;
    if(!stringArray(manifest.value("vmArgs"),&vmArgs) || !stringArray(manifest.value("classpath"),&classpath) ||
       !stringArray(manifest.value("args"),&args)) return fail(error,QStringLiteral("Повреждены параметры запуска Java."));
    for(const auto &entry:classpath) if(!QFileInfo(entry).isAbsolute() || !QFileInfo::exists(entry))
        return fail(error,QStringLiteral("Classpath backend содержит отсутствующий путь: ")+entry);
    const QString mainClass=manifest.value("mainClass").toString();
    if(!QRegularExpression("^[A-Za-z_$][A-Za-z0-9_.$]*$").match(mainClass).hasMatch() || classpath.isEmpty())
        return fail(error,QStringLiteral("Не указан основной класс backend."));
    const QString work=manifest.value("workDirectory").toString();
    if(!QFileInfo(work).isAbsolute() || !QFileInfo(work).isDir() || (!within(work,root) && QDir::cleanPath(work)!=root))
        return fail(error,QStringLiteral("Рабочий каталог backend должен находиться в DarkWinter."));
    const auto ipcBase=QDir::cleanPath(manifest.value("ipcDirectory").toString());
    if(!QFileInfo(ipcBase).isAbsolute() || !within(ipcBase,QDir(root).filePath("build/biome-editor")))
        return fail(error,QStringLiteral("Каталог IPC должен находиться в build/biome-editor."));
    if(!QDir().mkpath(ipcBase)) return fail(error,QStringLiteral("Не удалось создать каталог IPC."));
    const auto canonicalBase=QFileInfo(ipcBase).canonicalFilePath();
    if(!within(canonicalBase,QDir(root).filePath("build/biome-editor")))
        return fail(error,QStringLiteral("Ссылка IPC выходит за пределы каталога backend."));
    ipc_=QDir(canonicalBase).filePath("session-"+QUuid::createUuid().toString(QUuid::WithoutBraces));
    if(!QDir().mkpath(ipc_)) return fail(error,QStringLiteral("Не удалось создать сеанс IPC."));
    publisher_.stop();pendingRequest_.clear();publicationFailure_.invalidate();publicationAttempts_=0;publicationWarned_=false;forcedStop_=false;
    io_->token.fetch_add(1);lastSequence_=0;
    revision_=0;sceneRevision_=0;generationAccepted_=false;environmentQueued_=false;loadedScene_=0;resourceEpoch_=0;lastLightingRevision_=0;expectedEnvironmentRevision_=0;lastDiagnostic_.clear();pendingLighting_.reset();lastScene_.clear();lastLighting_.clear();lastFrame_.clear();lastState_.clear();log_.clear();stopping_=false;
    for(const auto &v:vmArgs) if(v.startsWith("-Ddarkwinter.biomeEditor.ipc=")||v.startsWith("-Ddarkwinter.biomeEditor.parentPid="))
        return fail(error,QStringLiteral("IPC и идентификатор родителя задаются редактором и не должны дублироваться в vmArgs."));
    vmArgs.append("-Ddarkwinter.biomeEditor.ipc="+ipc_);
    vmArgs.append("-Ddarkwinter.biomeEditor.parentPid="+QString::number(QCoreApplication::applicationPid()));
    vmArgs.append("-cp");vmArgs.append(classpath.join(QDir::listSeparator()));vmArgs.append(mainClass);vmArgs.append(args);
    process_.setProgram(java.absoluteFilePath());process_.setArguments(vmArgs);process_.setWorkingDirectory(work);
    ++processEpoch_;process_.start();if(error)error->clear();return true;
}
bool WorkerClient::send(const QString &kind) {
    if(!running() || ipc_.isEmpty() || revision_>=JsonIntegerLimit) return false;
    const quint64 nextRevision=revision_+1;
    const quint64 nextScene=kind=="generate"?nextRevision:sceneRevision_;
    QJsonObject request{{"schema",1},{"revision",double(nextRevision)},{"kind",kind},
                        {"sceneRevision",double(nextScene)},{"camera",camera_},{"environment",environment_}};
    if(kind=="generate")request.insert("profile",profile_);
    pendingRequest_=QJsonDocument(request).toJson(QJsonDocument::Compact);
    revision_=nextRevision;sceneRevision_=nextScene;
    if(kind=="generate"||kind=="stop")io_->token.fetch_add(1);
    if(kind=="generate"||kind=="environment")expectedEnvironmentRevision_=nextRevision;
    if(kind=="generate"){generationAccepted_=false;loadedScene_=0;resourceEpoch_=0;lastLightingRevision_=0;pendingLighting_.reset();lastScene_.clear();lastLighting_.clear();}
    publishRequest();return true;
}
bool WorkerClient::publishRequest() {
    if(pendingRequest_.isEmpty()||!running()){publisher_.stop();return false;}
    QSaveFile file(QDir(ipc_).filePath("request.json"));
    if(file.open(QIODevice::WriteOnly)&&file.write(pendingRequest_)==pendingRequest_.size()&&file.commit()){
        pendingRequest_.clear();publisher_.stop();publicationAttempts_=0;publicationFailure_.invalidate();publicationWarned_=false;return true;
    }
    if(!publicationFailure_.isValid())publicationFailure_.start();
    publicationAttempts_=std::min(publicationAttempts_+1,8);
    publisher_.start(25*publicationAttempts_);
    if(publicationFailure_.elapsed()>5000&&!publicationWarned_){
        publicationWarned_=true;emit diagnostic(QStringLiteral("Не удалось передать запрос генератору; повторная попытка продолжается: ")+file.errorString());
    }
    return false;
}
bool WorkerClient::generate(const QJsonObject &profile) {
    QString error;if(!Document::validate(profile,&error)){emit diagnostic(error);return false;}
    profile_=profile;camera_=profile.value("camera").toObject();environment_=profile.value("environment").toObject();environmentQueued_=false;return send("generate");
}
bool WorkerClient::camera(const QJsonObject &camera) {if(!sceneRevision_)return false;camera_=camera;profile_["camera"]=camera;return send(generationAccepted_?"camera":"generate");}
bool WorkerClient::environment(const QJsonObject &environment) {
    if(!sceneRevision_)return false;environment_=environment;profile_["environment"]=environment;
    if(!generationAccepted_){environmentQueued_=true;return true;}return send("environment");
}
void WorkerClient::stop() {
    if(!running())return;stopping_=true;send("stop");
    const quint64 epoch=processEpoch_;
    QTimer::singleShot(15000,&process_,[this,epoch]{if(epoch==processEpoch_&&stopping_&&running()){forcedStop_=true;process_.terminate();}});
}
bool WorkerClient::safeFrameName(const QString &name) {
    return !name.contains("..") && QRegularExpression("^[A-Za-z0-9][A-Za-z0-9_.-]{0,120}\\.rgba$").match(name).hasMatch();
}
bool WorkerClient::validateFrame(const QJsonObject &frame,quint64 request,quint64 scene,QString *error) {
    quint64 gotRequest=0,gotScene=0;
    if(frame.value("schema").toInt()!=1 || !revision(frame.value("revision"),&gotRequest) ||
       !revision(frame.value("sceneRevision"),&gotScene) || gotRequest!=request || gotScene!=scene)
        return fail(error,QStringLiteral("Кадр относится к предыдущему запросу или сцене."));
    for(const auto &key:{"width","height"}) {
        const auto v=frame.value(key);
        if(!v.isDouble() || !std::isfinite(v.toDouble()) || std::floor(v.toDouble())!=v.toDouble() || v.toDouble()<1 || v.toDouble()>4096)
            return fail(error,QStringLiteral("Недопустимый размер кадра."));
    }
    if(frame.value("format").toString()!="RGBA8" || !safeFrameName(frame.value("file").toString()))
        return fail(error,QStringLiteral("Недопустимый формат или путь кадра."));
    quint64 sequence=0;if(frame.contains("sequence")&&!revision(frame.value("sequence"),&sequence))return fail(error,QStringLiteral("Некорректный номер кадра."));
    if(frame.contains("sha256") && (!frame.value("sha256").isString() || !QRegularExpression("^[0-9a-fA-F]{64}$").match(frame.value("sha256").toString()).hasMatch()))
        return fail(error,QStringLiteral("Некорректная контрольная сумма кадра."));
    if(frame.contains("camera")) {
        if(!frame.value("camera").isObject())return fail(error,QStringLiteral("Некорректный снимок камеры."));
        const auto camera=frame.value("camera").toObject();
        for(auto it=camera.begin();it!=camera.end();++it)if(!QStringList{"targetX","targetY","targetZ","yaw","pitch","span","cutY"}.contains(it.key()) || !it.value().isDouble() || !std::isfinite(it.value().toDouble()))return fail(error,QStringLiteral("Некорректный снимок камеры."));
        for(const auto &required:{"targetX","targetY","targetZ","yaw","pitch","span"})if(!camera.contains(required))return fail(error,QStringLiteral("Неполный снимок камеры."));
        if(std::abs(camera.value("targetX").toDouble())>30000000 || std::abs(camera.value("targetZ").toDouble())>30000000 || camera.value("targetY").toDouble()<-512 || camera.value("targetY").toDouble()>1024 ||
           std::abs(camera.value("yaw").toDouble())>360000 || std::abs(camera.value("pitch").toDouble())>90 || camera.value("span").toDouble()<4 || camera.value("span").toDouble()>4096 ||
           (camera.contains("cutY")&&(camera.value("cutY").toDouble()<-64||camera.value("cutY").toDouble()>320)))return fail(error,QStringLiteral("Снимок камеры вне допустимого диапазона."));
    }
    if(error)error->clear();return true;
}
bool WorkerClient::validatePayload(const QJsonObject &frame,const QByteArray &rgba) {
    return !frame.contains("sha256") || QCryptographicHash::hash(rgba,QCryptographicHash::Sha256).toHex()==frame.value("sha256").toString().toLatin1().toLower();
}
void WorkerClient::poll() {
    if(closing_||ipc_.isEmpty())return;
    ++polls_;IoRequest job{ipc_,io_->token.load(),processEpoch_,revision_,sceneRevision_,lastState_,lastFrame_,camera_,lastScene_,lastLighting_,loadedScene_==sceneRevision_,diagnosticFramesEnabled_};
    {
        std::lock_guard lock(io_->mutex);if(io_->pending)++io_->measurements.coalescedPolls;io_->pending=std::move(job);
    }
    io_->wake.notify_one();
}
void WorkerClient::drainIo() {
    if(closing_)return;
    QElapsedTimer dispatch;dispatch.start();std::optional<IoResult> result;
    {
        std::lock_guard lock(io_->mutex);result=std::move(io_->completed);io_->completed.reset();
    }
    io_->wake.notify_one();if(!result)return;
    maxResultWaitNanos_=std::max(maxResultWaitNanos_,qint64(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-result->completedAt).count()));
    const auto current=[&]{return result->job.token==io_->token.load()&&result->job.epoch==processEpoch_&&result->job.scene==sceneRevision_;};
    if(!current()){++discardedResults_;maxGuiDispatchNanos_=std::max(maxGuiDispatchNanos_,dispatch.nsecsElapsed());return;}
    if(!result->stateBytes.isEmpty()&&result->stateBytes!=lastState_){
        const auto status=result->state.value("status").toString();
        if(status=="generating"||status=="ready")generationAccepted_=true;
        lastState_=result->stateBytes;emit stateChanged(result->state);
        if(current()&&generationAccepted_&&environmentQueued_){environmentQueued_=false;send("environment");}
    }
    if(current()&&result->scene){
        lastScene_=std::move(result->sceneBytes);loadedScene_=sceneRevision_;resourceEpoch_=result->scene->resourceEpoch;
        lastLightingRevision_=std::max(lastLightingRevision_,result->scene->revision);
        if(pendingLighting_&&pendingLighting_->revision<=lastLightingRevision_)pendingLighting_.reset();
        lastDiagnostic_.clear();++acceptedScenes_;emit sceneReady(std::move(result->scene));
    }
    if(current()&&result->lighting){
        lastLighting_=std::move(result->lightingBytes);
        if(result->lighting->revision>lastLightingRevision_&&result->lighting->revision>=expectedEnvironmentRevision_&&
           result->lighting->environment==environment_&&(!pendingLighting_||result->lighting->revision>pendingLighting_->revision))
            pendingLighting_=std::move(result->lighting);
    }
    if(pendingLighting_&&(pendingLighting_->revision<expectedEnvironmentRevision_||pendingLighting_->revision<=lastLightingRevision_||
       pendingLighting_->environment!=environment_))pendingLighting_.reset();
    if(current()&&pendingLighting_&&loadedScene_==sceneRevision_&&pendingLighting_->resourceEpoch==resourceEpoch_){
        lastLightingRevision_=pendingLighting_->revision;++acceptedLighting_;emit lightingReady(std::move(pendingLighting_));
    }
    if(current()&&!result->diagnostic.isEmpty()&&result->diagnostic!=lastDiagnostic_){lastDiagnostic_=result->diagnostic;emit diagnostic(lastDiagnostic_);}
    if(current()&&result->job.request==revision_&&!result->frameBytes.isEmpty()&&result->frameBytes!=lastFrame_){
        quint64 sequence=0;const bool numbered=revision(result->frame.value("sequence"),&sequence);
        if(!numbered||sequence>lastSequence_){
            if(numbered)lastSequence_=sequence;
            frameCamera_=result->frame.contains("camera")?result->frame.value("camera").toObject():result->job.camera;
            lastFrame_=std::move(result->frameBytes);++acceptedFrames_;
            emit frameReady(std::move(result->rgba),result->size,sceneRevision_);
        }else ++discardedResults_;
    }
    maxGuiDispatchNanos_=std::max(maxGuiDispatchNanos_,dispatch.nsecsElapsed());
}
void WorkerClient::shutdownIo(){
    if(!io_)return;io_->stopping.store(true);io_->token.fetch_add(1);io_->wake.notify_one();
    if(io_->thread.joinable())io_->thread.join();
    std::lock_guard lock(io_->mutex);io_->pending.reset();io_->completed.reset();
}
QJsonObject WorkerClient::ioDiagnostics()const{
    std::lock_guard lock(io_->mutex);const auto &s=io_->measurements;
    return {{"scope","Scene validation, asset I/O and SHA on one bounded background thread; retained scene delivered by shared pointer"},
        {"sceneReads",double(s.sceneReads)},{"validatedScenes",double(s.validatedScenes)},{"rejectedScenes",double(s.rejectedScenes)},
        {"acceptedScenes",double(acceptedScenes_)},{"lightingReads",double(s.lightingReads)},{"acceptedLighting",double(acceptedLighting_)},
        {"diagnosticFramesEnabled",diagnosticFramesEnabled_},{"pollIntervalMs",timer_.interval()},
        {"sceneCacheHits",double(s.sceneCacheHits)},{"lightingCacheHits",double(s.lightingCacheHits)},
        {"polls",double(polls_)},{"jobs",double(s.jobs)},{"coalescedPolls",double(s.coalescedPolls)},
        {"frameReads",double(s.frameReads)},{"validatedFrames",double(s.validatedFrames)},{"acceptedFrames",double(acceptedFrames_)},
        {"rejectedFrames",double(s.rejectedFrames)},{"supersededJobs",double(s.supersededJobs)},{"discardedResults",double(discardedResults_)},
        {"metadataChanged",double(s.metadataChanged)},{"payloadBytesRead",double(s.payloadBytesRead)},{"shaBytesHashed",double(s.shaBytesHashed)},
        {"ownerThreadIoViolations",double(s.ownerThreadIoViolations)},{"maxIoMs",double(s.maxIoNanos)/1e6},
        {"maxPayloadReadMs",double(s.maxPayloadReadNanos)/1e6},{"maxShaMs",double(s.maxShaNanos)/1e6},
        {"maxGuiDispatchMs",double(maxGuiDispatchNanos_)/1e6},{"maxDeliveryWaitMs",double(maxResultWaitNanos_)/1e6},
        {"activeReads",io_->active?1:0},{"pendingJobs",io_->pending?1:0},{"completedResults",io_->completed?1:0},{"completedFrames",io_->completed&&!io_->completed->rgba.isEmpty()?1:0},
        {"maxPendingJobs",1},{"maxCompletedFrames",1}};
}
