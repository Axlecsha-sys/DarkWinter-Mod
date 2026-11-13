// Arrange biome authoring tools around the exact Vulkan preview.
#pragma once
#include "Document.hpp"
#include "WorkerClient.hpp"
#include <QMainWindow>
#include <QMap>
#include <QJsonObject>
#include <QElapsedTimer>
#include <memory>
class QAction;
class QVulkanInstance;
class PreviewViewport;
class QCloseEvent;
class QDoubleSpinBox;
class QCheckBox;
class QLineEdit;
class QListWidget;
class QLabel;
class QProgressBar;
class QPushButton;
class QTimer;

class EditorWindow final : public QMainWindow {
    Q_OBJECT
public:
    explicit EditorWindow(QString projectRoot,QWidget *parent=nullptr);
    ~EditorWindow() override;
    void runUiSmoke(const QString &outputDirectory,const QString &imagePath=QString{});
    void runWorkerSmoke(const QString &outputDirectory,int seconds=120);
    void runSceneSmoke(const QString &outputDirectory);
    QJsonObject interactionDiagnostics() const;
protected:
    void closeEvent(QCloseEvent *) override;
private:
    void buildUi();
    QWidget *tools();
    QWidget *properties();
    void sync(const QJsonObject &previous=QJsonObject{});
    void commitCamera();
    void queueCamera(const QJsonObject &);
    void sendQueuedCamera();
    void clearCameraRequests();
    void documentChanged();
    void generate();
    void toggleWorker();
    bool maybeSave();
    bool save(bool choosePath=false);
    void open();
    void exportProfile();
    void exportDatapack();
    void receiveStroke(QJsonObject);
    void setTool(const QString &);
    void updateBrush();
    void setPathValue(const QStringList &,const QJsonValue &,const QString &);
    void recenter();
    Document document_;
    WorkerClient worker_;
    std::unique_ptr<QVulkanInstance> vulkan_;
    PreviewViewport *viewport_=nullptr;
    QString projectRoot_,tool_="raise",exportDirectory_;
    QMap<QString,QWidget*> fields_;
    QListWidget *layers_=nullptr;
    QLabel *message_=nullptr,*previewMessage_=nullptr,*rootLabel_=nullptr,*pointerLabel_=nullptr;
    QProgressBar *progress_=nullptr;
    QPushButton *workerButton_=nullptr,*datapackButton_=nullptr;
    QDoubleSpinBox *radius_=nullptr,*strength_=nullptr,*falloff_=nullptr,*workY_=nullptr,*height_=nullptr,*water_=nullptr,*width_=nullptr,*depth_=nullptr;
    QLineEdit *block_=nullptr;
    QCheckBox *cut_=nullptr;
    QDoubleSpinBox *cutY_=nullptr;
    QTimer *rebuild_=nullptr,*cameraTimer_=nullptr,*cameraRequestTimer_=nullptr;
    QAction *undoAction_=nullptr,*redoAction_=nullptr;
    QElapsedTimer lastCameraSent_,latestNavigation_;
    quint64 navigationInputs_=0,navigationCommits_=0,cameraRequests_=0,cameraTimeouts_=0;
    qint64 lastCameraAckMs_=0,lastNavigationFrameMs_=0;
    QJsonObject previousProfile_,pendingCamera_,queuedCamera_;
    bool syncing_=false,sceneReady_=false,currentFrame_=false;
    bool cameraEdited_=false,cameraQueued_=false,cameraInFlight_=false;
    quint64 visibleScene_=0;
    std::shared_ptr<const SceneBundle> displayedScene_,pendingScene_;
    bool sceneLoading_=false;
};
