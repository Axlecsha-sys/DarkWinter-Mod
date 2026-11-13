// Displays exact Minecraft preview frames with Vulkan and translates editor camera/brush input.
#pragma once
#include <QByteArray>
#include <QJsonObject>
#include <QPointF>
#include <QRectF>
#include <QSize>
#include <QString>
#include <QVulkanWindow>
#include <memory>

struct SceneBundle;
struct SceneLighting;
class PreviewRenderer;
class PreviewViewport final : public QVulkanWindow {
    Q_OBJECT
public:
    explicit PreviewViewport(QWindow *parent=nullptr);
    ~PreviewViewport() override;
    void resetScene();
    void setScene(std::shared_ptr<const SceneBundle> scene);
    void setLighting(std::shared_ptr<const SceneLighting> lighting);
    void setEnvironment(QJsonObject environment);
    void setReferenceView(bool enabled);
    void setFrame(QByteArray rgba,QSize size,quint64 sceneRevision);
    void setFrameSnapshot(QByteArray rgba,QSize size,quint64 sceneRevision,QJsonObject capturedCamera);
    void setCamera(QJsonObject camera);
    QJsonObject camera() const;
    QJsonObject renderDiagnostics() const;
    void setBrush(QString kind,double radius,double workY);
    QVulkanWindowRenderer *createRenderer() override;
signals:
    void sceneAccepted(quint64 sceneRevision);
    void sceneDisplayed(quint64 sceneRevision);
    void localFrameRecorded(quint64 serial,quint64 sceneRevision,QJsonObject camera);
    void cameraChanged(QJsonObject camera);
    void brushStroke(QJsonObject operation);
    void diagnostic(QString message);
    void pointerWorldChanged(QJsonObject position);
protected:
    bool event(QEvent *event) override;
    void mousePressEvent(QMouseEvent *event) override;
    void mouseMoveEvent(QMouseEvent *event) override;
    void mouseReleaseEvent(QMouseEvent *event) override;
    void wheelEvent(QWheelEvent *event) override;
private:
    struct Private;
    std::unique_ptr<Private> d;
    friend class PreviewRenderer;
    void navigate(const QJsonObject &camera);
    void scheduleUpdate();
    QRectF viewRect() const;
    void updatePointer(QPointF position);
};
