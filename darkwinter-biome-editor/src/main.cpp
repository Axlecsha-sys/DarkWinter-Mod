// Start the native C++ biome editor with an explicit DarkWinter project root.
#include "EditorWindow.hpp"
#include <QApplication>
#include <QCommandLineParser>
#include <QLocale>
#include <QTranslator>
#include <QLibraryInfo>
#include <QDir>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSaveFile>

int main(int argc,char **argv) {
    QApplication app(argc,argv);
    QCoreApplication::setApplicationName("DarkWinter Biome Editor");
    QCoreApplication::setOrganizationName("DarkWinter");
    QCoreApplication::setApplicationVersion("0.1.0");
    app.setStyle("Fusion");
    QTranslator qtTranslation;
    if(qtTranslation.load(QLocale::system(),"qtbase","_",QLibraryInfo::path(QLibraryInfo::TranslationsPath)))app.installTranslator(&qtTranslation);
    QCommandLineParser parser;parser.setApplicationDescription(QStringLiteral("Точный редактор биомов DarkWinter"));parser.addHelpOption();parser.addVersionOption();
    QCommandLineOption root({"r","darkwinter-root"},QStringLiteral("Каталог проекта DarkWinter"),"path","F:/DarkWinter");parser.addOption(root);
    QCommandLineOption smoke("ui-smoke",QStringLiteral("Проверить только собственное окно и записать диагностические кадры"),"directory");parser.addOption(smoke);
    QCommandLineOption sceneSmoke("scene-smoke",QStringLiteral("Проверить retained Vulkan на синтетической калибровке без генератора"),"directory");parser.addOption(sceneSmoke);
    QCommandLineOption image("smoke-image",QStringLiteral("Статический диагностический кадр для проверки Vulkan"),"path");parser.addOption(image);
    QCommandLineOption workerSmoke("worker-smoke",QStringLiteral("Проверить настоящий генератор и четыре режима окружения"),"project-root");parser.addOption(workerSmoke);
    QCommandLineOption soakSeconds("worker-smoke-seconds",QStringLiteral("Длительность навигации: 120 секунд по умолчанию; короткий запуск не подтверждает полный soak"),"seconds","120");parser.addOption(soakSeconds);
    parser.addPositionalArgument("output-directory",QStringLiteral("Каталог результатов --worker-smoke"),"[output-directory]");parser.process(app);
    if(parser.isSet(workerSmoke)&&parser.positionalArguments().size()!=1)parser.showHelp(2);
    bool durationValid=false;const int seconds=parser.value(soakSeconds).toInt(&durationValid);
    if(!durationValid||seconds<1||seconds>3600)parser.showHelp(2);
    int result=1;
    {
        EditorWindow window(parser.isSet(workerSmoke)?parser.value(workerSmoke):parser.value(root));window.show();
        if(parser.isSet(sceneSmoke))window.runSceneSmoke(parser.value(sceneSmoke));
        else if(parser.isSet(smoke))window.runUiSmoke(parser.value(smoke),parser.value(image));
        else if(parser.isSet(workerSmoke))window.runWorkerSmoke(parser.positionalArguments().first(),seconds);
        result=app.exec();
    }
    // This receipt is written only after the owned window, Vulkan viewport and worker I/O thread were destroyed.
    if(parser.isSet(workerSmoke)||parser.isSet(sceneSmoke)){
        const QJsonObject shutdown{{"schema",1},{"eventLoopReturned",true},{"editorWindowDestroyed",true},
            {"workerClientAndIoThreadDestroyed",true},{"exitCode",result},
            {"scope","Qt event loop returned and owned editor destructors completed; external process exit remains observable by the caller."}};
        const auto shutdownDirectory=parser.isSet(sceneSmoke)?parser.value(sceneSmoke):parser.positionalArguments().first();
        QSaveFile file(QDir(shutdownDirectory).filePath("qt-shutdown.json"));
        const auto bytes=QJsonDocument(shutdown).toJson(QJsonDocument::Indented);
        if(!file.open(QIODevice::WriteOnly)||file.write(bytes)!=bytes.size()||!file.commit())result=2;
    }
    return result;
}
