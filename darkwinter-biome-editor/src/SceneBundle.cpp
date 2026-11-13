// Read and validate immutable scene assets on the caller's bounded I/O executor.
#include "SceneBundle.hpp"
#include <QCryptographicHash>
#include <QDir>
#include <QElapsedTimer>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QRegularExpression>
#include <QtEndian>
#include <algorithm>
#include <bit>
#include <cmath>
#include <limits>
#include <utility>
#ifdef Q_OS_WIN
#include <windows.h>
#endif

namespace {
constexpr quint64 JsonIntegerLimit=9007199254740991ULL;
constexpr quint64 MeshLimit=512ULL*1024*1024,AtlasLimit=256ULL*1024*1024,TotalLimit=768ULL*1024*1024;
bool fail(QString *out,const QString &message){if(out)*out=message;return false;}
bool canceled(const std::function<bool()> &cancel){return cancel&&cancel();}
bool integer(const QJsonValue &value,quint64 minimum,quint64 maximum,quint64 &out){
    const double n=value.toDouble(-1);
    if(!value.isDouble()||!std::isfinite(n)||n<double(minimum)||n>double(maximum)||std::floor(n)!=n)return false;
    out=quint64(n);return true;
}
template<size_t N> bool numbers(const QJsonValue &value,std::array<double,N> &out,bool integral=false){
    if(!value.isArray()||value.toArray().size()!=qsizetype(N))return false;
    const auto array=value.toArray();
    for(size_t i=0;i<N;++i){const auto v=array[qsizetype(i)];if(!v.isDouble())return false;
        const double n=v.toDouble();if(!std::isfinite(n)||std::abs(n)>30000000||(integral&&std::floor(n)!=n))return false;out[i]=n;}
    return true;
}
bool bounds(const QJsonValue &value,std::array<double,6> &out){
    return numbers(value,out)&&out[0]<=out[3]&&out[1]<=out[4]&&out[2]<=out[5];
}
bool environment(const QJsonValue &value,QJsonObject &out){
    if(!value.isObject())return false;
    out=value.toObject();quint64 time=0;
    return integer(out.value("time"),0,24000,time)&&out.value("effects").isBool()&&
        QStringList{"clear","rain","snow","thunder"}.contains(out.value("weather").toString());
}
bool identity(const QJsonObject &o,quint64 expectedScene,quint64 maxRevision,
              quint64 &scene,quint64 &revision,quint64 &resources,QString *error){
    if(o.value("schema").toInt()!=1||!integer(o.value("sceneRevision"),1,JsonIntegerLimit,scene)||scene!=expectedScene||
       !integer(o.value("revision"),1,JsonIntegerLimit,revision)||revision>maxRevision||
       !integer(o.value("resourceEpoch"),1,JsonIntegerLimit,resources))
        return fail(error,QStringLiteral("Сцена относится к другому поколению или содержит неверные идентификаторы."));
    return true;
}
QJsonObject parse(const QByteArray &manifest,QString *error){
    if(manifest.isEmpty()||manifest.size()>SceneBundleReader::ManifestLimit){fail(error,QStringLiteral("Манифест сцены пуст или превышает 1 МиБ."));return {};}
    QJsonParseError parseError;const auto doc=QJsonDocument::fromJson(manifest,&parseError);
    if(parseError.error!=QJsonParseError::NoError||!doc.isObject()){fail(error,QStringLiteral("Повреждён JSON манифеста сцены."));return {};}
    return doc.object();
}
struct Asset {QString file;quint64 bytes=0;QByteArray sha;};
bool asset(const QJsonValue &value,quint64 expected,quint64 cap,Asset &out,QString *error){
    if(!value.isObject())return fail(error,QStringLiteral("Отсутствует описание файла сцены."));
    const auto o=value.toObject();out.file=o.value("file").toString();out.sha=o.value("sha256").toString().toLatin1();
    if(!SceneBundleReader::safeAssetName(out.file)||!integer(o.value("bytes"),0,cap,out.bytes)||out.bytes!=expected||
       !QRegularExpression("^[0-9a-f]{64}$").match(QString::fromLatin1(out.sha)).hasMatch())
        return fail(error,QStringLiteral("Недопустимое имя, размер или SHA-256 файла сцены: ")+out.file);
    return true;
}
bool image(const QJsonValue &value,int maxDimension,quint64 cap,Asset &out,QSize &size,QString *error){
    const auto o=value.toObject();quint64 width=0,height=0;
    if(!integer(o.value("width"),1,quint64(maxDimension),width)||!integer(o.value("height"),1,quint64(maxDimension),height)||
       o.value("format").toString()!="RGBA8")
        return fail(error,QStringLiteral("Недопустимые размеры или формат RGBA8 текстуры сцены."));
    size=QSize(int(width),int(height));return asset(value,width*height*4,cap,out,error);
}
bool load(const QString &directory,const Asset &asset,QByteArray &bytes,const std::function<bool()> &cancel,SceneReadStats *stats,QString *error){
    if(canceled(cancel))return fail(error,QStringLiteral("Чтение сцены отменено новым поколением."));
    const auto path=QDir(directory).filePath(asset.file);const QFileInfo info(path);
    if(info.isSymLink()||!info.isFile()||quint64(info.size())!=asset.bytes)
        return fail(error,QStringLiteral("Файл сцены отсутствует, является ссылкой или имеет неверный размер: ")+asset.file);
    QElapsedTimer readTime;readTime.start();bytes.resize(qsizetype(asset.bytes));
#ifdef Q_OS_WIN
    const HANDLE handle=CreateFileW(reinterpret_cast<LPCWSTR>(path.utf16()),GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,
        nullptr,OPEN_EXISTING,FILE_FLAG_SEQUENTIAL_SCAN|FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
    if(handle==INVALID_HANDLE_VALUE)return fail(error,QStringLiteral("Не удалось открыть файл сцены: ")+asset.file);
    struct Close{HANDLE h;~Close(){CloseHandle(h);}} close{handle};
    LARGE_INTEGER fileSize{};BY_HANDLE_FILE_INFORMATION details{};
    if(GetFileType(handle)!=FILE_TYPE_DISK||!GetFileSizeEx(handle,&fileSize)||fileSize.QuadPart<0||quint64(fileSize.QuadPart)!=asset.bytes||
       !GetFileInformationByHandle(handle,&details)||(details.dwFileAttributes&FILE_ATTRIBUTE_REPARSE_POINT))
        return fail(error,QStringLiteral("Недопустимый открытый файл сцены: ")+asset.file);
    for(qsizetype offset=0;offset<bytes.size();){
        if(canceled(cancel))return fail(error,QStringLiteral("Чтение сцены отменено новым поколением."));
        DWORD received=0;const DWORD count=DWORD(std::min<qsizetype>(1024*1024,bytes.size()-offset));
        if(!ReadFile(handle,bytes.data()+offset,count,&received,nullptr)||!received)return fail(error,QStringLiteral("Неполный файл сцены: ")+asset.file);
        offset+=received;if(stats)stats->payloadBytesRead+=received;
    }
#else
    QFile file(path);if(!file.open(QIODevice::ReadOnly))return fail(error,QStringLiteral("Не удалось открыть файл сцены: ")+asset.file);
    for(qsizetype offset=0;offset<bytes.size();){
        if(canceled(cancel))return fail(error,QStringLiteral("Чтение сцены отменено новым поколением."));
        const auto count=file.read(bytes.data()+offset,std::min<qsizetype>(1024*1024,bytes.size()-offset));
        if(count<=0)return fail(error,QStringLiteral("Неполный файл сцены: ")+asset.file);
        offset+=count;if(stats)stats->payloadBytesRead+=quint64(count);
    }
#endif
    if(stats)stats->maxReadNanos=std::max(stats->maxReadNanos,readTime.nsecsElapsed());
    QElapsedTimer shaTime;shaTime.start();QCryptographicHash hash(QCryptographicHash::Sha256);
    for(qsizetype offset=0;offset<bytes.size();offset+=1024*1024){
        if(canceled(cancel))return fail(error,QStringLiteral("Проверка сцены отменена новым поколением."));
        const auto count=std::min<qsizetype>(1024*1024,bytes.size()-offset);hash.addData(QByteArrayView(bytes.constData()+offset,count));
        if(stats)stats->shaBytesHashed+=quint64(count);
    }
    if(stats)stats->maxShaNanos=std::max(stats->maxShaNanos,shaTime.nsecsElapsed());
    return hash.result().toHex()==asset.sha||fail(error,QStringLiteral("SHA-256 не совпадает для файла сцены: ")+asset.file);
}
quint32 word(const char *bytes){return qFromLittleEndian<quint32>(reinterpret_cast<const uchar*>(bytes));}
}
bool SceneBundleReader::safeAssetName(const QString &name){
    return !name.contains("..")&&QRegularExpression("^[A-Za-z0-9][A-Za-z0-9_.-]{0,159}$").match(name).hasMatch();
}
std::shared_ptr<const SceneBundle> SceneBundleReader::read(const QString &directory,const QByteArray &manifest,
    quint64 expectedScene,quint64 maxRevision,const std::function<bool()> &cancel,SceneReadStats *stats,QString *error){
    if(error)error->clear();const auto o=parse(manifest,error);if(o.isEmpty()||canceled(cancel))return {};
    auto out=std::make_shared<SceneBundle>();out->sourceManifest=o;quint64 vertices=0,indices=0;
    if(!identity(o,expectedScene,maxRevision,out->sceneRevision,out->revision,out->resourceEpoch,error))return {};
    if(o.value("kind").toString()!="native-terrain-mesh"||!numbers(o.value("origin"),out->origin,true)||!bounds(o.value("bounds"),out->bounds)||
       !o.value("cutY").isDouble()||!std::isfinite(o.value("cutY").toDouble())||o.value("cutY").toDouble()<-64||o.value("cutY").toDouble()>320||
       !integer(o.value("vertexCount"),0,MeshLimit/32,vertices)||!integer(o.value("indexCount"),0,MeshLimit/4,indices)||
       vertices*32+indices*4>MeshLimit||indices%3||!environment(o.value("environment"),out->environment)||
       !integer(o.value("omittedBlockEntities"),0,JsonIntegerLimit,out->omittedBlockEntities)){
        fail(error,QStringLiteral("Недопустимые параметры сцены или геометрия превышает 512 МиБ."));return {};
    }
    out->vertexCount=quint32(vertices);out->indexCount=quint32(indices);out->cutY=o.value("cutY").toDouble();
    Asset vertexAsset,indexAsset,atlasAsset,lightAsset;
    if(!asset(o.value("vertices"),vertices*32,MeshLimit,vertexAsset,error)||!asset(o.value("indices"),indices*4,MeshLimit,indexAsset,error)||
       !image(o.value("atlas"),8192,AtlasLimit,atlasAsset,out->atlasSize,error)||!image(o.value("lightmap"),16,1024,lightAsset,out->lightmapSize,error))return {};
    if(out->lightmapSize!=QSize(16,16)||vertexAsset.bytes+indexAsset.bytes+atlasAsset.bytes+lightAsset.bytes>TotalLimit){
        fail(error,QStringLiteral("Сцена превышает 768 МиБ или lightmap имеет размер, отличный от 16 × 16."));return {};
    }
    if(!o.value("parts").isArray()||o.value("parts").toArray().size()>16384){fail(error,QStringLiteral("Слишком много частей сцены (предел 16384)."));return {};}
    QVector<std::pair<quint64,quint64>> ranges;
    for(const auto &value:o.value("parts").toArray()){
        const auto p=value.toObject();ScenePart part;quint64 first=0,count=0,layer=0;
        if(!value.isObject()||!integer(p.value("firstIndex"),0,indices,first)||!integer(p.value("indexCount"),1,indices,count)||
           first+count>indices||count%3||!integer(p.value("layer"),0,2,layer)||!bounds(p.value("bounds"),part.bounds)){
            fail(error,QStringLiteral("Неверный диапазон индексов, слой или границы части сцены."));return {};
        }
        part.firstIndex=quint32(first);part.indexCount=quint32(count);part.layer=int(layer);
        out->parts.append(part);ranges.append({first,first+count});
    }
    std::sort(ranges.begin(),ranges.end());quint64 end=0;
    for(const auto &range:ranges){if(range.first!=end){fail(error,QStringLiteral("Части сцены перекрываются или оставляют индексы без части."));return {};}end=range.second;}
    if(end!=indices){fail(error,QStringLiteral("Части сцены не покрывают весь буфер индексов."));return {};}
    if(!load(directory,vertexAsset,out->vertices,cancel,stats,error)||!load(directory,indexAsset,out->indices,cancel,stats,error)||
       !load(directory,atlasAsset,out->atlas,cancel,stats,error)||!load(directory,lightAsset,out->lightmap,cancel,stats,error))return {};
    for(quint64 i=0;i<vertices;++i){
        if((i&32767)==0&&canceled(cancel))return {};
        const char *v=out->vertices.constData()+qsizetype(i*32);
        for(int offset:{0,4,8,16,20})if(!std::isfinite(std::bit_cast<float>(word(v+offset)))){
            fail(error,QStringLiteral("Геометрия содержит нечисловую координату или UV."));return {};
        }
        const auto light=word(v+24);
        if((light&65535)>240||(light>>16)>240||word(v+28)!=0){fail(error,QStringLiteral("Геометрия содержит неверный packed light или reserved."));return {};}
    }
    for(quint64 i=0;i<indices;++i){
        if((i&65535)==0&&canceled(cancel))return {};
        if(word(out->indices.constData()+qsizetype(i*4))>=vertices){fail(error,QStringLiteral("Индекс геометрии выходит за буфер вершин."));return {};}
    }
    if(canceled(cancel))return {};return out;
}
std::shared_ptr<const SceneLighting> SceneBundleReader::readLighting(const QString &directory,const QByteArray &manifest,
    quint64 expectedScene,quint64 maxRevision,const std::function<bool()> &cancel,SceneReadStats *stats,QString *error){
    if(error)error->clear();const auto o=parse(manifest,error);if(o.isEmpty()||canceled(cancel))return {};
    auto out=std::make_shared<SceneLighting>();Asset lightAsset;
    if(!identity(o,expectedScene,maxRevision,out->sceneRevision,out->revision,out->resourceEpoch,error))return {};
    if(!environment(o.value("environment"),out->environment)){fail(error,QStringLiteral("Недопустимое окружение обновления освещения."));return {};}
    if(!image(o.value("lightmap"),16,1024,lightAsset,out->lightmapSize,error))return {};
    if(out->lightmapSize!=QSize(16,16)){fail(error,QStringLiteral("Lightmap должна иметь размер 16 × 16."));return {};}
    if(!load(directory,lightAsset,out->lightmap,cancel,stats,error)||canceled(cancel))return {};return out;
}
