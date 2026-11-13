// Immutable validated native scene bytes shared by the IPC reader and Vulkan viewport.
#pragma once
#include <QByteArray>
#include <QJsonObject>
#include <QSize>
#include <QString>
#include <QVector>
#include <array>
#include <cstddef>
#include <functional>
#include <memory>

struct SceneVertex {
    float x,y,z;
    quint8 r,g,b,a;
    float u,v;
    quint32 light,reserved;
};
static_assert(sizeof(SceneVertex)==32);
static_assert(offsetof(SceneVertex,r)==12 && offsetof(SceneVertex,u)==16 && offsetof(SceneVertex,light)==24 && offsetof(SceneVertex,reserved)==28);
struct ScenePart {
    quint32 firstIndex=0,indexCount=0;
    int layer=0;
    std::array<double,6> bounds{};
};
struct SceneLighting {
    quint64 sceneRevision=0,revision=0,resourceEpoch=0;
    QByteArray lightmap;
    QSize lightmapSize;
    QJsonObject environment;
};
struct SceneBundle {
    quint64 sceneRevision=0,revision=0,resourceEpoch=0;
    std::array<double,3> origin{};
    std::array<double,6> bounds{};
    double cutY=320;
    quint32 vertexCount=0,indexCount=0;
    QByteArray vertices,indices;
    QVector<ScenePart> parts;
    QByteArray atlas,lightmap;
    QSize atlasSize,lightmapSize;
    QJsonObject environment;
    quint64 omittedBlockEntities=0;
    QJsonObject sourceManifest; // Already parsed by the background reader; diagnostics never reread IPC on the GUI.
};
struct SceneReadStats {
    quint64 payloadBytesRead=0,shaBytesHashed=0;
    qint64 maxReadNanos=0,maxShaNanos=0;
};
class SceneBundleReader final {
public:
    static constexpr qint64 ManifestLimit=1024*1024;
    static bool safeAssetName(const QString &);
    static std::shared_ptr<const SceneBundle> read(const QString &directory,const QByteArray &manifest,
        quint64 expectedScene,quint64 maxRevision,const std::function<bool()> &cancel={},
        SceneReadStats *stats=nullptr,QString *error=nullptr);
    static std::shared_ptr<const SceneLighting> readLighting(const QString &directory,const QByteArray &manifest,
        quint64 expectedScene,quint64 maxRevision,const std::function<bool()> &cancel={},
        SceneReadStats *stats=nullptr,QString *error=nullptr);
};
