// Launch and communicate with the isolated exact Minecraft generation worker.
#pragma once
#include <QObject>
#include "SceneBundle.hpp"
#include <QJsonObject>
#include <QProcess>
#include <QSize>
#include <QTimer>
#include <QElapsedTimer>
#include <memory>

class WorkerClient final : public QObject {
    Q_OBJECT
public:
    explicit WorkerClient(QObject *parent=nullptr);
    ~WorkerClient() override;
    bool start(const QString &projectRoot,QString *error=nullptr);
    bool running() const;
    qint64 processId() const { return process_.processId(); }
    bool cleanExit() const {return !running() && process_.exitStatus()==QProcess::NormalExit && process_.exitCode()==0 && !forcedStop_;}
    QString ipcDirectory() const { return ipc_; }
    quint64 sceneRevision() const { return sceneRevision_; }
    quint64 latestRevision() const { return revision_; }
    QJsonObject frameCamera() const { return frameCamera_; }
    QJsonObject ioDiagnostics() const;
    QString logTail() const { return QString::fromUtf8(log_); }
    bool generate(const QJsonObject &profile);
    bool camera(const QJsonObject &camera);
    bool environment(const QJsonObject &environment);
    void stop();
    void setDiagnosticFrames(bool enabled) { diagnosticFramesEnabled_=enabled; timer_.setInterval(enabled?16:50); }
    bool diagnosticFramesEnabled() const { return diagnosticFramesEnabled_; }
    static bool validateFrame(const QJsonObject &,quint64 request,quint64 scene,QString *error=nullptr);
    static bool safeFrameName(const QString &);
    static bool validatePayload(const QJsonObject &,const QByteArray &);
signals:
    void sceneReady(std::shared_ptr<const SceneBundle> scene);
    void lightingReady(std::shared_ptr<const SceneLighting> lighting);
    void frameReady(QByteArray rgba,QSize size,quint64 sceneRevision);
    void stateChanged(QJsonObject state);
    void diagnostic(QString);
    void processChanged(bool running);
private:
    bool send(const QString &kind);
    void poll();
    void drainIo();
    void shutdownIo();
    struct IoState;
    std::unique_ptr<IoState> io_;
    bool publishRequest();
    void appendLog(const QByteArray &);
    QProcess process_;
    QTimer timer_,publisher_;
    QElapsedTimer publicationFailure_;
    QByteArray pendingRequest_;
    int publicationAttempts_=0;
    bool publicationWarned_=false,forcedStop_=false;
    QString ipc_,lastDiagnostic_;
    QByteArray log_,lastState_,lastFrame_,lastScene_,lastLighting_;
    QJsonObject profile_,camera_,environment_,frameCamera_;
    std::shared_ptr<const SceneLighting> pendingLighting_;
    quint64 revision_=0,sceneRevision_=0,processEpoch_=0,lastSequence_=0;
    quint64 polls_=0,acceptedFrames_=0,discardedResults_=0,loadedScene_=0,resourceEpoch_=0,lastLightingRevision_=0,expectedEnvironmentRevision_=0,acceptedScenes_=0,acceptedLighting_=0;
    qint64 maxGuiDispatchNanos_=0,maxResultWaitNanos_=0;
    bool closing_=false;
    bool stopping_=false,generationAccepted_=false,environmentQueued_=false,diagnosticFramesEnabled_=false;
};
