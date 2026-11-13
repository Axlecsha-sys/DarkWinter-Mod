// Validate, atomically save and undo complete exact-generation profile edits.
#include "Document.hpp"
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QRegularExpression>
#include <QSaveFile>
#include <QUndoCommand>
#include <QSet>
#include <cmath>

namespace {
constexpr qint64 MaxDocumentBytes = 8 * 1024 * 1024;
bool fail(QString *out, const QString &text) { if (out) *out = text; return false; }
bool finite(const QJsonValue &v, double low, double high) {
    return v.isDouble() && std::isfinite(v.toDouble()) && v.toDouble() >= low && v.toDouble() <= high;
}
bool integer(const QJsonValue &v, double low, double high) {
    return finite(v, low, high) && std::floor(v.toDouble()) == v.toDouble();
}
QJsonObject nested(QJsonObject object, const QStringList &path, qsizetype index, const QJsonValue &value) {
    if (index + 1 == path.size()) object.insert(path[index], value);
    else object.insert(path[index], nested(object.value(path[index]).toObject(), path, index + 1, value));
    return object;
}
bool keys(const QJsonObject &object,const QStringList &allowed) { for(auto it=object.begin();it!=object.end();++it)if(!allowed.contains(it.key()))return false;return true; }
const QStringList Kinds = {"raise","lower","flatten","plains","smooth","mountain","plateau","river","lake","cave","tunnel","set_block","paint","vegetation"};
}
class ProfileCommand final : public QUndoCommand {
public:
    ProfileCommand(Document *document, QJsonObject next, const QString &label)
        : QUndoCommand(label), document_(document), before_(document->profile_), after_(std::move(next)) {}
    void undo() override { document_->assign(before_); }
    void redo() override { document_->assign(after_); }
    int id() const override {return text()==QStringLiteral("Изменён вид")?1:-1;}
    bool mergeWith(const QUndoCommand *command) override {
        const auto *next=dynamic_cast<const ProfileCommand*>(command);
        if(id()!=1||!next||next->id()!=1||next->document_!=document_)return false;
        after_=next->after_;return true;
    }
private:
    Document *document_;
    QJsonObject before_, after_;
};
Document::Document(QObject *parent) : QObject(parent), profile_(defaultProfile()), undo_(this) {
    undo_.setUndoLimit(256);
    connect(&undo_, &QUndoStack::cleanChanged, this, [this](bool clean) { emit dirtyChanged(!clean); });
}
QJsonObject Document::defaultProfile() {
    return {{"schema",1},{"name",QStringLiteral("Новый биом")},{"id","custom_biome"},{"seed","19021"},
        {"biome","darkwinter:darkwinter_test"},{"chunkX",0},{"chunkZ",0},{"chunks",5},
        {"terrain",QJsonObject{{"baseHeight",76},{"broadAmplitude",8},{"detailAmplitude",2},{"noiseScale",1.5}}},
        {"operations",QJsonArray{}},
        {"vegetation",QJsonObject{{"trees",true},{"grass",true},{"bushes",true},{"decor",true},{"density",1}}},
        {"environment",QJsonObject{{"time",6000},{"weather","clear"},{"effects",false}}},
        {"camera",QJsonObject{{"targetX",40},{"targetY",76},{"targetZ",40},{"yaw",45},{"pitch",54.73561},{"span",112}}}};
}
bool Document::validate(const QJsonObject &p, QString *error) {
    if (!keys(p,{"schema","name","id","seed","biome","chunkX","chunkZ","chunks","terrain","operations","vegetation","environment","camera"}))return fail(error,QStringLiteral("Проект содержит неизвестные поля."));
    if (!integer(p.value("schema"),1,1)) return fail(error,QStringLiteral("Неподдерживаемая версия проекта."));
    if (!p.value("name").isString() || p.value("name").toString().trimmed().isEmpty() || p.value("name").toString().size()>160 || p.value("name").toString().contains(QRegularExpression("[\\x00-\\x1f]")))
        return fail(error,QStringLiteral("Название должно содержать от 1 до 160 символов."));
    const QRegularExpression id("^[a-z0-9][a-z0-9_.-]{0,79}$"), resource("^[a-z0-9_.-]+:[a-z0-9_./-]+$"), seed("^-?[0-9]{1,19}$");
    if (!id.match(p.value("id").toString()).hasMatch()) return fail(error,QStringLiteral("ID: строчные латинские буквы, цифры, _, - или точка."));
    bool seedOk=false;
    const QString seedText=p.value("seed").toString();
    seedText.toLongLong(&seedOk);
    if (!p.value("seed").isString() || !seed.match(seedText).hasMatch() || !seedOk)
        return fail(error,QStringLiteral("Seed должен быть целым 64-битным числом в текстовом формате."));
    if (!p.value("biome").isString() || p.value("biome").toString().size()>256 || p.value("biome").toString().contains("..") || !resource.match(p.value("biome").toString()).hasMatch()) return fail(error,QStringLiteral("Биом должен иметь вид namespace:id."));
    if (!integer(p.value("chunks"),3,15) || !integer(p.value("chunkX"),-1875000,1875000) || !integer(p.value("chunkZ"),-1875000,1875000))
        return fail(error,QStringLiteral("Размер предпросмотра: 3–15 чанков; координаты должны лежать в границах мира."));
    for(const auto &axis:{"chunkX","chunkZ"})if(p.value(axis).toDouble()*16 < -30000000 || (p.value(axis).toDouble()+p.value("chunks").toDouble())*16-1>30000000)return fail(error,QStringLiteral("Область предпросмотра выходит за границу мира."));
    const auto terrain=p.value("terrain").toObject(), vegetation=p.value("vegetation").toObject();
    if(!keys(terrain,{"baseHeight","broadAmplitude","detailAmplitude","noiseScale"}) || !keys(vegetation,{"trees","grass","bushes","decor","density"}))return fail(error,QStringLiteral("Неизвестные параметры рельефа или растительности."));
    if (!finite(terrain.value("baseHeight"),-64,319) || !finite(terrain.value("broadAmplitude"),0,256) ||
        !finite(terrain.value("detailAmplitude"),0,64) || !finite(terrain.value("noiseScale"),0.05,64))
        return fail(error,QStringLiteral("Недопустимые параметры рельефа."));
    for (const auto &key: {"trees","grass","bushes","decor"})
        if (!vegetation.value(key).isBool()) return fail(error,QStringLiteral("Недопустимые правила растительности."));
    if (!finite(vegetation.value("density"),0,8)) return fail(error,QStringLiteral("Плотность растительности: 0–8."));
    const auto environment=p.value("environment").toObject(), camera=p.value("camera").toObject();
    if(!keys(environment,{"time","weather","effects"}) || !keys(camera,{"targetX","targetY","targetZ","yaw","pitch","span","cutY"}))return fail(error,QStringLiteral("Неизвестные параметры окружения или камеры."));
    if(camera.contains("cutY")&&!finite(camera.value("cutY"),-64,320))return fail(error,QStringLiteral("Высота разреза: от −64 до 320."));
    if (!integer(environment.value("time"),0,23999) || !QStringList{"clear","rain","snow","thunder"}.contains(environment.value("weather").toString()) ||
        !environment.value("effects").isBool()) return fail(error,QStringLiteral("Недопустимые параметры окружения."));
    for (const auto &key: {"targetX","targetZ"}) if (!finite(camera.value(key),-30000000,30000000)) return fail(error,QStringLiteral("Камера находится вне мира."));
    if (!finite(camera.value("targetY"),-512,1024) || !finite(camera.value("yaw"),-360000,360000) ||
        !finite(camera.value("pitch"),-90,90) || !finite(camera.value("span"),4,4096)) return fail(error,QStringLiteral("Недопустимые параметры камеры."));
    if (!p.value("operations").isArray() || p.value("operations").toArray().size()>128) return fail(error,QStringLiteral("Допустимо до 128 операций точной генерации."));
    qsizetype points=0,pathSpans=0;
    for (const auto &v:p.value("operations").toArray()) {
        if (!v.isObject()) return fail(error,QStringLiteral("Повреждён слой операций."));
        const auto op=v.toObject();
        if(!keys(op,{"kind","enabled","x","y","z","radius","strength","height","width","depth","waterLevel","falloff","points","block"}))return fail(error,QStringLiteral("Слой содержит неизвестные поля."));
        if (!Kinds.contains(op.value("kind").toString()) || (op.contains("enabled") && !op.value("enabled").isBool()))
            return fail(error,QStringLiteral("Неизвестный инструмент или состояние слоя."));
        for (const auto &key: {"x","y","z","height","waterLevel"}) if (op.contains(key) && !finite(op.value(key),-30000000,30000000))
            return fail(error,QStringLiteral("Координаты операции должны быть конечными."));
        for (const auto &key: {"radius","width"}) if (op.contains(key) && !finite(op.value(key),0.001,1024))
            return fail(error,QStringLiteral("Размер или сила операции вне допустимого диапазона."));
        for (const auto &key: {"strength","depth"}) if(op.contains(key)&&!finite(op.value(key),0,1024))return fail(error,QStringLiteral("Сила или глубина операции: 0–1024."));
        if (op.contains("falloff") && !finite(op.value("falloff"),0,1)) return fail(error,QStringLiteral("Плавность кисти: 0–1."));
        if (op.contains("block") && (!op.value("block").isString() || op.value("block").toString().size()>256 || op.value("block").toString().contains("..") || !resource.match(op.value("block").toString()).hasMatch())) return fail(error,QStringLiteral("Неверный идентификатор блока."));
        const auto kind=op.value("kind").toString();const bool path=kind=="river"||kind=="tunnel";
        if(!path&&(std::abs(op.value("x").toDouble(0))+op.value("radius").toDouble(8)>30000000 || std::abs(op.value("z").toDouble(0))+op.value("radius").toDouble(8)>30000000))return fail(error,QStringLiteral("Кисть выходит за границу мира."));
        if(path && (!op.value("points").isArray()||op.value("points").toArray().size()<2))return fail(error,QStringLiteral("Река и туннель требуют минимум две точки."));
        if(path){pathSpans+=op.value("points").toArray().size()-1;if(pathSpans>64)return fail(error,QStringLiteral("Суммарно доступно до 64 сегментов рек и туннелей."));}
        if (op.contains("points")) {
            if (!op.value("points").isArray() || op.value("points").toArray().size()>32) return fail(error,QStringLiteral("Повреждён маршрут кисти."));
            QJsonArray previousPoint;
            for (const auto &point:op.value("points").toArray()) {
                const auto a=point.toArray();
                if (a.size()!=2 || !finite(a[0],-30000000,30000000) || !finite(a[1],-30000000,30000000) || ++points>256)
                    return fail(error,QStringLiteral("Маршрут кисти некорректен: до 32 точек в линии и 256 точек в проекте."));
                const double halfWidth=op.value("width").toDouble(5)*0.5;
                if(std::abs(a[0].toDouble())+halfWidth>30000000 || std::abs(a[1].toDouble())+halfWidth>30000000 || (!previousPoint.isEmpty()&&std::hypot(a[0].toDouble()-previousPoint[0].toDouble(),a[1].toDouble()-previousPoint[1].toDouble())>32768))return fail(error,QStringLiteral("Маршрут выходит за границу мира или имеет слишком длинный сегмент."));
                previousPoint=a;
            }
        }
    }
    if (QJsonDocument(p).toJson(QJsonDocument::Compact).size()>MaxDocumentBytes) return fail(error,QStringLiteral("Проект превышает 8 МиБ."));
    if (error) error->clear();
    return true;
}
void Document::assign(const QJsonObject &value) { profile_=value; emit changed(); }
void Document::change(const QJsonObject &value, const QString &label) {
    if (value==profile_ || !validate(value)) return;
    undo_.push(new ProfileCommand(this,value,label));
}
void Document::setValue(const QStringList &path,const QJsonValue &value,const QString &label) {
    if (!path.isEmpty()) change(nested(profile_,path,0,value),label);
}
void Document::appendOperation(const QJsonObject &operation,const QString &label) {
    auto next=profile_; auto operations=next.value("operations").toArray(); operations.append(operation); next.insert("operations",operations); change(next,label);
}
void Document::replaceOperation(int index,const QJsonObject &operation,const QString &label) {
    auto next=profile_; auto operations=next.value("operations").toArray();
    if(index<0 || index>=operations.size()) return;
    operations.replace(index,operation); next.insert("operations",operations); change(next,label);
}
void Document::removeOperation(int index) {
    auto next=profile_; auto operations=next.value("operations").toArray();
    if(index<0 || index>=operations.size()) return;
    operations.removeAt(index); next.insert("operations",operations); change(next,QStringLiteral("Удалён слой"));
}
void Document::reset() { profile_=defaultProfile(); path_.clear(); undo_.clear(); undo_.setClean(); emit changed(); emit dirtyChanged(false); }
bool Document::load(const QString &path,QString *error) {
    QFile file(path);
    if(!file.open(QIODevice::ReadOnly)) return fail(error,file.errorString());
    if(file.size()>MaxDocumentBytes) return fail(error,QStringLiteral("Проект превышает 8 МиБ."));
    QJsonParseError parseError;
    const auto json=QJsonDocument::fromJson(file.readAll(),&parseError);
    if(parseError.error!=QJsonParseError::NoError || !json.isObject()) return fail(error,QStringLiteral("Неверный JSON: ")+parseError.errorString());
    if(!validate(json.object(),error)) return false;
    profile_=json.object(); path_=path; undo_.clear(); undo_.setClean(); emit changed(); emit dirtyChanged(false); return true;
}
bool Document::save(const QString &path,QString *error) {
    if(!validate(profile_,error)) return false;
    QSaveFile file(path);
    if(!file.open(QIODevice::WriteOnly)) return fail(error,file.errorString());
    const auto bytes=QJsonDocument(profile_).toJson(QJsonDocument::Indented);
    if(file.write(bytes)!=bytes.size() || !file.commit()) return fail(error,file.errorString());
    path_=path; undo_.setClean(); emit dirtyChanged(false); if(error) error->clear(); return true;
}
