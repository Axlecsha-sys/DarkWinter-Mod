// Own a manually saved biome profile and its reversible editing commands.
#pragma once
#include <QObject>
#include <QJsonObject>
#include <QJsonValue>
#include <QStringList>
#include <QUndoStack>

class Document final : public QObject {
    Q_OBJECT
public:
    explicit Document(QObject *parent = nullptr);
    static QJsonObject defaultProfile();
    static bool validate(const QJsonObject &, QString *error = nullptr);
    QJsonObject profile() const { return profile_; }
    QString filePath() const { return path_; }
    bool dirty() const { return !undo_.isClean(); }
    QUndoStack *undoStack() { return &undo_; }
    bool load(const QString &, QString *error = nullptr);
    bool save(const QString &, QString *error = nullptr);
    void reset();
    void setValue(const QStringList &, const QJsonValue &, const QString &label);
    void appendOperation(const QJsonObject &, const QString &label);
    void replaceOperation(int, const QJsonObject &, const QString &label);
    void removeOperation(int);
signals:
    void changed();
    void dirtyChanged(bool);
private:
    friend class ProfileCommand;
    void assign(const QJsonObject &);
    void change(const QJsonObject &, const QString &);
    QJsonObject profile_;
    QString path_;
    QUndoStack undo_;
};
