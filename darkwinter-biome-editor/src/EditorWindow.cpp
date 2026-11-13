// Provide adaptive Qt authoring panels, real profile operations and explicit backend status.
#include "EditorWindow.hpp"
#include "PreviewViewport.hpp"
#include <QAction>
#include <QButtonGroup>
#include <QCheckBox>
#include <QCloseEvent>
#include <QComboBox>
#include <QDir>
#include <QDirIterator>
#include <QDoubleSpinBox>
#include <QFileDialog>
#include <QFileInfo>
#include <QFormLayout>
#include <QGroupBox>
#include <QHBoxLayout>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLabel>
#include <QLineEdit>
#include <QListWidget>
#include <QMenuBar>
#include <QMessageBox>
#include <QProgressBar>
#include <QPushButton>
#include <QSaveFile>
#include <QScrollArea>
#include <QSignalBlocker>
#include <QSpinBox>
#include <QSplitter>
#include <QStatusBar>
#include <QTabWidget>
#include <QTimer>
#include <QToolBar>
#include <QToolButton>
#include <QVulkanInstance>
#include <QVBoxLayout>
#include <algorithm>

namespace {
const QList<QPair<QString,QString>> ToolNames={
    {"raise",QStringLiteral("Поднять")},{"lower",QStringLiteral("Опустить")},{"flatten",QStringLiteral("Выровнять")},
    {"smooth",QStringLiteral("Сгладить")},{"mountain",QStringLiteral("Гора")},{"plateau",QStringLiteral("Плато")},
    {"river",QStringLiteral("Река")},{"lake",QStringLiteral("Водоём")},{"cave",QStringLiteral("Пещера")},
    {"tunnel",QStringLiteral("Туннель")},{"set_block",QStringLiteral("Блок")},{"paint",QStringLiteral("Покрытие")},
    {"vegetation",QStringLiteral("Растительность")}};
QString toolName(const QString &kind) {if(kind=="plains")return QStringLiteral("Равнина");for(const auto &entry:ToolNames)if(entry.first==kind)return entry.second;return kind;}
QJsonValue pathValue(QJsonObject object,const QStringList &path) {
    for(qsizetype i=0;i<path.size();++i){const auto value=object.value(path[i]);if(i+1==path.size())return value;object=value.toObject();}
    return {};
}
QDoubleSpinBox *number(QWidget *parent,double low,double high,double value,double step=1) {
    auto *spin=new QDoubleSpinBox(parent);spin->setRange(low,high);spin->setDecimals(step<1?2:0);spin->setSingleStep(step);spin->setValue(value);spin->setKeyboardTracking(false);return spin;
}
QString key(const QStringList &path){return path.join('/');}
bool under(const QString &path,const QString &parent) {
    return QDir::cleanPath(path).startsWith(QDir::cleanPath(parent)+QStringLiteral("/"),Qt::CaseInsensitive);
}
}
EditorWindow::EditorWindow(QString root,QWidget *parent):QMainWindow(parent),document_(this),worker_(this),projectRoot_(std::move(root)) {
    setWindowTitle(QStringLiteral("DarkWinter • Редактор биомов"));resize(1440,900);setMinimumSize(800,480);
    setStyleSheet(
        "QMainWindow,QWidget{background:#142330;color:#dce8ed;} QMenuBar,QMenu,QToolBar{background:#1b3040;}"
        "QGroupBox{border:1px solid #385363;border-radius:5px;margin-top:12px;padding:12px 8px 8px;}"
        "QGroupBox::title{subcontrol-origin:margin;left:9px;color:#9ec4d4;}"
        "QLineEdit,QSpinBox,QDoubleSpinBox,QComboBox,QListWidget{background:#0e1c27;border:1px solid #365260;border-radius:4px;padding:5px;selection-background-color:#386879;}"
        "QPushButton,QToolButton{background:#223b4a;border:1px solid #46616f;border-radius:4px;padding:7px;}"
        "QPushButton:hover,QToolButton:hover{background:#305467;} QToolButton:checked{background:#3c7184;border-color:#8ec6d8;}"
        "QPushButton:disabled,QToolButton:disabled{color:#6e8490;border-color:#2d414e;}"
        "QScrollArea{border:0;} QTabBar::tab{background:#203744;padding:7px;}QTabBar::tab:selected{background:#386274;}"
        "QProgressBar{border:1px solid #3a5260;border-radius:3px;text-align:center;}QProgressBar::chunk{background:#497c8c;}"
        "QSplitter::handle{background:#253e4c;} QLabel#previewNote{background:#1c3342;border:1px solid #365365;padding:8px;}");
    rebuild_=new QTimer(this);rebuild_->setSingleShot(true);rebuild_->setInterval(650);
    connect(rebuild_,&QTimer::timeout,this,&EditorWindow::generate);
    cameraTimer_=new QTimer(this);cameraTimer_->setSingleShot(true);cameraTimer_->setInterval(16);cameraTimer_->setTimerType(Qt::PreciseTimer);
    connect(cameraTimer_,&QTimer::timeout,this,&EditorWindow::commitCamera);
    cameraRequestTimer_=new QTimer(this);cameraRequestTimer_->setSingleShot(true);cameraRequestTimer_->setTimerType(Qt::PreciseTimer);
    connect(cameraRequestTimer_,&QTimer::timeout,this,&EditorWindow::sendQueuedCamera);
    buildUi();
    previousProfile_=document_.profile();
    connect(&document_,&Document::changed,this,&EditorWindow::documentChanged);
    connect(&document_,&Document::dirtyChanged,this,[this]{setWindowModified(document_.dirty()||cameraEdited_);});
    connect(&worker_,&WorkerClient::diagnostic,this,[this](const QString &text){message_->setText(text);previewMessage_->setText(text);});
    connect(&worker_,&WorkerClient::processChanged,this,[this](bool running){
        clearCameraRequests();sceneLoading_=running;
        // New workers restart server scene/resource counters; reset the CPU and GPU cache epoch once.
        if(running){pendingScene_.reset();displayedScene_.reset();visibleScene_=0;if(viewport_)viewport_->resetScene();}
        // A stopped worker may retain its last mesh until a new owned session starts.
        sceneReady_=bool(displayedScene_);currentFrame_=sceneReady_;
        updateBrush();datapackButton_->setEnabled(false);exportDirectory_.clear();
        workerButton_->setText(running?QStringLiteral("Остановить генератор"):QStringLiteral("Запустить генератор"));
        if(!running)rebuild_->stop();
    });
    connect(&worker_,&WorkerClient::sceneReady,this,[this](std::shared_ptr<const SceneBundle> scene){
        if(!scene||!viewport_)return;
        pendingScene_=std::move(scene);viewport_->setScene(pendingScene_);
        viewport_->setEnvironment(document_.profile().value("environment").toObject());
        previewMessage_->setText(QStringLiteral("Подготовлена сцена %1 • %2 вершин • %3 треугольников").arg(pendingScene_->sceneRevision).arg(pendingScene_->vertexCount).arg(pendingScene_->indexCount/3));
    });
    connect(&worker_,&WorkerClient::lightingReady,this,[this](std::shared_ptr<const SceneLighting> lighting){
        if(viewport_){viewport_->setLighting(std::move(lighting));viewport_->setEnvironment(document_.profile().value("environment").toObject());}
    });
    if(viewport_)connect(viewport_,&PreviewViewport::sceneDisplayed,this,[this](quint64 scene){
        if(!pendingScene_||pendingScene_->sceneRevision!=scene)return;
        displayedScene_=std::move(pendingScene_);visibleScene_=scene;sceneReady_=true;currentFrame_=true;
        sceneLoading_=scene!=worker_.sceneRevision();updateBrush();
        const auto omitted=displayedScene_->omittedBlockEntities;
        previewMessage_->setText(QStringLiteral("Сцена %1 • локальная 3D-камера • %2 треугольников%3").arg(scene).arg(displayedScene_->indexCount/3)
            .arg(omitted?QStringLiteral(" • спецобъекты без геометрии: %1").arg(omitted):QString{}));
    });
    connect(&worker_,&WorkerClient::frameReady,this,[this](QByteArray rgba,QSize size,quint64 scene){
        if(!worker_.running())return;
        const auto capturedCamera=worker_.frameCamera();
        if(viewport_)viewport_->setFrameSnapshot(std::move(rgba),size,scene,capturedCamera);
        visibleScene_=scene;
        currentFrame_=viewport_&&!cameraEdited_&&capturedCamera==viewport_->camera();
        if(cameraInFlight_&&lastCameraSent_.isValid())lastCameraAckMs_=lastCameraSent_.elapsed();
        cameraInFlight_=false;cameraRequestTimer_->stop();
        if(currentFrame_&&latestNavigation_.isValid())lastNavigationFrameMs_=latestNavigation_.elapsed();
        updateBrush();
        const auto text=QStringLiteral("Точная сцена %1 • %2 × %3").arg(scene).arg(size.width()).arg(size.height());
        if(previewMessage_->text()!=text)previewMessage_->setText(text);
        if(cameraQueued_)sendQueuedCamera();
    });
    connect(&worker_,&WorkerClient::stateChanged,this,[this](const QJsonObject &state){
        progress_->setValue(int(state.value("progress").toDouble()*100));
        const auto status=state.value("status").toString();
        message_->setText(state.value(status=="failed"?"error":"message").toString());
        if(status=="generating"){
            sceneLoading_=true;updateBrush();
            previewMessage_->setText(displayedScene_?QStringLiteral("Сцена %1 остаётся доступной. Обновление: ").arg(visibleScene_)+state.value("message").toString():state.value("message").toString());
            datapackButton_->setEnabled(false);
        }
        if(status=="failed"||status=="stopped")clearCameraRequests();
        if(status=="ready" && !rebuild_->isActive()){
            sceneLoading_=visibleScene_!=worker_.sceneRevision();sceneReady_=bool(displayedScene_)||currentFrame_;updateBrush();
            const auto path=state.value("exportDirectory").toString();
            const auto canonical=QFileInfo(path).canonicalFilePath();
            exportDirectory_=!canonical.isEmpty()&&under(canonical,QDir(worker_.ipcDirectory()).filePath("export"))?canonical:QString{};
            datapackButton_->setEnabled(!exportDirectory_.isEmpty());
        }
    });
    sync();
}
EditorWindow::~EditorWindow(){
    rebuild_->stop();cameraTimer_->stop();cameraRequestTimer_->stop();
    disconnect(&worker_,nullptr,this,nullptr);disconnect(&document_,nullptr,this,nullptr);
    if(viewport_)disconnect(viewport_,nullptr,this,nullptr);
    delete takeCentralWidget();viewport_=nullptr;vulkan_.reset();
}
void EditorWindow::buildUi() {
    auto *file=menuBar()->addMenu(QStringLiteral("Файл"));
    auto *fresh=file->addAction(QStringLiteral("Новый проект"));fresh->setShortcut(QKeySequence::New);
    connect(fresh,&QAction::triggered,this,[this]{if(maybeSave())document_.reset();});
    auto *openAction=file->addAction(QStringLiteral("Открыть…"));openAction->setShortcut(QKeySequence::Open);connect(openAction,&QAction::triggered,this,&EditorWindow::open);
    auto *saveAction=file->addAction(QStringLiteral("Сохранить"));saveAction->setShortcut(QKeySequence::Save);connect(saveAction,&QAction::triggered,this,[this]{save();});
    auto *saveAs=file->addAction(QStringLiteral("Сохранить как…"));saveAs->setShortcut(QKeySequence::SaveAs);connect(saveAs,&QAction::triggered,this,[this]{save(true);});
    file->addSeparator();connect(file->addAction(QStringLiteral("Экспорт профиля JSON…")),&QAction::triggered,this,&EditorWindow::exportProfile);
    file->addSeparator();connect(file->addAction(QStringLiteral("Закрыть")),&QAction::triggered,this,&QWidget::close);
    auto *edit=menuBar()->addMenu(QStringLiteral("Правка"));
    undoAction_=edit->addAction(QStringLiteral("Отменить"));undoAction_->setShortcut(QKeySequence::Undo);undoAction_->setEnabled(false);
    redoAction_=edit->addAction(QStringLiteral("Повторить"));redoAction_->setShortcut(QKeySequence::Redo);redoAction_->setEnabled(false);
    connect(undoAction_,&QAction::triggered,this,[this]{commitCamera();document_.undoStack()->undo();});
    connect(redoAction_,&QAction::triggered,this,[this]{commitCamera();document_.undoStack()->redo();});
    connect(document_.undoStack(),&QUndoStack::canUndoChanged,this,[this](bool enabled){undoAction_->setEnabled(enabled||cameraEdited_);});
    connect(document_.undoStack(),&QUndoStack::canRedoChanged,redoAction_,&QAction::setEnabled);
    auto *bar=addToolBar(QStringLiteral("Проект"));bar->setMovable(false);
    workerButton_=new QPushButton(QStringLiteral("Запустить генератор"),this);connect(workerButton_,&QPushButton::clicked,this,&EditorWindow::toggleWorker);bar->addWidget(workerButton_);
    auto *refresh=new QPushButton(QStringLiteral("Перестроить"),this);connect(refresh,&QPushButton::clicked,this,&EditorWindow::generate);bar->addWidget(refresh);
    auto *center=new QPushButton(QStringLiteral("Центрировать"),this);connect(center,&QPushButton::clicked,this,&EditorWindow::recenter);bar->addWidget(center);
    bar->addSeparator();
    datapackButton_=new QPushButton(QStringLiteral("Экспорт datapack…"),this);datapackButton_->setEnabled(false);connect(datapackButton_,&QPushButton::clicked,this,&EditorWindow::exportDatapack);bar->addWidget(datapackButton_);
    auto *connectButton=new QPushButton(QStringLiteral("Подключить к DarkWinter"),this);connectButton->setEnabled(false);
    connectButton->setToolTip(QStringLiteral("Подключение к игре пока недоступно. Используйте экспорт datapack."));bar->addWidget(connectButton);
    auto *rootBar=addToolBar(QStringLiteral("Каталог"));rootBar->setMovable(false);addToolBarBreak();
    rootLabel_=new QLabel(QDir::toNativeSeparators(projectRoot_),this);rootLabel_->setTextInteractionFlags(Qt::TextSelectableByMouse);rootBar->addWidget(rootLabel_);
    auto *chooseRoot=new QPushButton(QStringLiteral("Каталог DarkWinter…"),this);rootBar->addWidget(chooseRoot);
    connect(chooseRoot,&QPushButton::clicked,this,[this]{
        if(worker_.running()){QMessageBox::information(this,QStringLiteral("Каталог"),QStringLiteral("Сначала остановите генератор."));return;}
        const auto path=QFileDialog::getExistingDirectory(this,QStringLiteral("Каталог DarkWinter"),projectRoot_);
        if(!path.isEmpty()){projectRoot_=path;rootLabel_->setText(QDir::toNativeSeparators(path));}
    });
    auto *splitter=new QSplitter(Qt::Horizontal,this);splitter->setChildrenCollapsible(false);
    auto *left=tools();left->setMinimumWidth(185);left->setMaximumWidth(285);splitter->addWidget(left);
    auto *centerWidget=new QWidget(splitter);auto *layout=new QVBoxLayout(centerWidget);layout->setContentsMargins(0,0,0,0);
    previewMessage_=new QLabel(QStringLiteral("Запустите генератор, чтобы увидеть биом."),centerWidget);
    previewMessage_->setObjectName("previewNote");previewMessage_->setWordWrap(true);layout->addWidget(previewMessage_);
    vulkan_=std::make_unique<QVulkanInstance>();vulkan_->setApiVersion(QVersionNumber(1,2,0));
    if(vulkan_->create()){
        viewport_=new PreviewViewport();viewport_->setVulkanInstance(vulkan_.get());
        auto *container=QWidget::createWindowContainer(viewport_,centerWidget);container->setMinimumSize(320,240);layout->addWidget(container,1);
        connect(viewport_,&PreviewViewport::cameraChanged,this,[this](QJsonObject camera){
            pendingCamera_=std::move(camera);cameraEdited_=true;++navigationInputs_;latestNavigation_.start();
            currentFrame_=bool(displayedScene_);setWindowModified(true);undoAction_->setEnabled(true);redoAction_->setEnabled(false);updateBrush();
            if(!cameraTimer_->isActive())cameraTimer_->start();
        });
        connect(viewport_,&PreviewViewport::brushStroke,this,&EditorWindow::receiveStroke);
        connect(viewport_,&PreviewViewport::pointerWorldChanged,this,[this](QJsonObject point){
            if(!pointerLabel_)return;
            const auto text=point.value("valid").toBool()?QStringLiteral("X %1   Y %2   Z %3").arg(point.value("x").toDouble(),0,'f',1).arg(point.value("y").toDouble(),0,'f',1).arg(point.value("z").toDouble(),0,'f',1):QStringLiteral("Вне рабочей плоскости");
            if(pointerLabel_->text()!=text)pointerLabel_->setText(text);
        });
        connect(viewport_,&PreviewViewport::diagnostic,this,[this](const QString &text){previewMessage_->setText(text);});
    }else{
        auto *error=new QLabel(QStringLiteral("Vulkan недоступен. Проверьте драйвер видеокарты. Другой графический backend не включается."),centerWidget);
        error->setWordWrap(true);error->setAlignment(Qt::AlignCenter);layout->addWidget(error,1);
    }
    splitter->addWidget(centerWidget);
    auto *right=properties();right->setMinimumWidth(265);right->setMaximumWidth(370);splitter->addWidget(right);
    splitter->setStretchFactor(1,1);splitter->setSizes({220,880,300});setCentralWidget(splitter);
    message_=new QLabel(QStringLiteral("Проект не изменён"),this);message_->setMinimumWidth(0);message_->setSizePolicy(QSizePolicy::Ignored,QSizePolicy::Preferred);statusBar()->addWidget(message_,1);
    pointerLabel_=new QLabel(this);statusBar()->addPermanentWidget(pointerLabel_);
    progress_=new QProgressBar(this);progress_->setRange(0,100);progress_->setFixedWidth(145);statusBar()->addPermanentWidget(progress_);
}
QWidget *EditorWindow::tools() {
    auto *scroll=new QScrollArea(this);scroll->setWidgetResizable(true);auto *panel=new QWidget(scroll);auto *layout=new QVBoxLayout(panel);
    auto *title=new QLabel(QStringLiteral("ИНСТРУМЕНТЫ"),panel);layout->addWidget(title);
    auto *group=new QButtonGroup(this);group->setExclusive(true);
    for(const auto &entry:ToolNames){
        auto *button=new QToolButton(panel);button->setText(entry.second);button->setToolButtonStyle(Qt::ToolButtonTextOnly);button->setCheckable(true);button->setSizePolicy(QSizePolicy::Expanding,QSizePolicy::Fixed);
        button->setObjectName("tool_"+entry.first);button->setChecked(entry.first==tool_);group->addButton(button);layout->addWidget(button);
        connect(button,&QToolButton::clicked,this,[this,kind=entry.first]{setTool(kind);});
    }
    auto *brush=new QGroupBox(QStringLiteral("Кисть"),panel);auto *form=new QFormLayout(brush);form->setFieldGrowthPolicy(QFormLayout::AllNonFixedFieldsGrow);form->setRowWrapPolicy(QFormLayout::WrapLongRows);
    radius_=number(brush,0.5,128,8,0.5);strength_=number(brush,0.05,16,1,0.05);falloff_=number(brush,0,1,0.65,0.05);
    workY_=number(brush,-64,319,76);height_=number(brush,-64,319,80);width_=number(brush,1,128,5);depth_=number(brush,0.5,128,4,0.5);water_=number(brush,-64,319,63);
    block_=new QLineEdit("minecraft:stone",brush);
    form->addRow(QStringLiteral("Радиус"),radius_);form->addRow(QStringLiteral("Сила"),strength_);form->addRow(QStringLiteral("Плавность"),falloff_);
    form->addRow(QStringLiteral("Рабочая Y"),workY_);form->addRow(QStringLiteral("Высота"),height_);form->addRow(QStringLiteral("Ширина"),width_);form->addRow(QStringLiteral("Глубина"),depth_);form->addRow(QStringLiteral("Уровень воды"),water_);form->addRow(QStringLiteral("Блок"),block_);
    layout->addWidget(brush);
    for(auto *spin:{radius_,workY_})connect(spin,qOverload<double>(&QDoubleSpinBox::valueChanged),this,[this]{updateBrush();});
    auto *hint=new QLabel(QStringLiteral("ЛКМ — кисть\nСредняя кнопка — панорама\nПКМ — вращение\nКолесо — масштаб\nРека и туннель — проведите линию"),panel);hint->setWordWrap(true);layout->addWidget(hint);layout->addStretch();scroll->setWidget(panel);return scroll;
}
QWidget *EditorWindow::properties() {
    auto *outer=new QWidget(this);auto *outerLayout=new QVBoxLayout(outer);outerLayout->setContentsMargins(4,0,4,0);
    auto *tabs=new QTabWidget(outer);
    auto createTab=[&](const QString &name){auto *scroll=new QScrollArea(tabs);scroll->setWidgetResizable(true);auto *widget=new QWidget(scroll);scroll->setWidget(widget);auto *form=new QFormLayout(widget);form->setFieldGrowthPolicy(QFormLayout::AllNonFixedFieldsGrow);form->setRowWrapPolicy(QFormLayout::WrapLongRows);tabs->addTab(scroll,name);return form;};
    auto line=[&](QFormLayout *form,const QString &label,QStringList path){
        auto *field=new QLineEdit(outer);fields_[key(path)]=field;form->addRow(label,field);
        connect(field,&QLineEdit::editingFinished,this,[this,field,path]{if(!syncing_){setPathValue(path,field->text(),QStringLiteral("Изменены свойства биома"));}});
    };
    auto spin=[&](QFormLayout *form,const QString &label,QStringList path,double low,double high,double step){
        auto *field=number(outer,low,high,low,step);fields_[key(path)]=field;form->addRow(label,field);
        connect(field,qOverload<double>(&QDoubleSpinBox::valueChanged),this,[this,field,path](double value){
            if(syncing_)return;
            const bool region=path.size()==1 && QStringList{"chunks","chunkX","chunkZ"}.contains(path[0]);
            if(region)document_.undoStack()->beginMacro(QStringLiteral("Изменена область предпросмотра"));
            setPathValue(path,value,QStringLiteral("Изменены параметры генерации"));
            if(region){recenter();document_.undoStack()->endMacro();}
        });
    };
    auto check=[&](QFormLayout *form,const QString &label,QStringList path){
        auto *field=new QCheckBox(outer);fields_[key(path)]=field;form->addRow(label,field);
        connect(field,&QCheckBox::toggled,this,[this,path](bool value){if(!syncing_)setPathValue(path,value,QStringLiteral("Изменены правила сцены"));});
    };
    auto *terrain=createTab(QStringLiteral("Биом"));
    line(terrain,QStringLiteral("Название"),{"name"});line(terrain,QStringLiteral("ID"),{"id"});line(terrain,QStringLiteral("Seed"),{"seed"});line(terrain,QStringLiteral("Базовый биом"),{"biome"});
    spin(terrain,QStringLiteral("Чанков по стороне"),{"chunks"},3,15,1);spin(terrain,QStringLiteral("Чанк X"),{"chunkX"},-1875000,1875000,1);spin(terrain,QStringLiteral("Чанк Z"),{"chunkZ"},-1875000,1875000,1);
    spin(terrain,QStringLiteral("Базовая высота"),{"terrain","baseHeight"},-64,319,1);spin(terrain,QStringLiteral("Крупный рельеф"),{"terrain","broadAmplitude"},0,256,0.5);spin(terrain,QStringLiteral("Мелкий рельеф"),{"terrain","detailAmplitude"},0,64,0.25);spin(terrain,QStringLiteral("Масштаб шума"),{"terrain","noiseScale"},0.05,64,0.05);
    auto *plants=createTab(QStringLiteral("Растительность"));
    check(plants,QStringLiteral("Деревья"),{"vegetation","trees"});check(plants,QStringLiteral("Травы"),{"vegetation","grass"});check(plants,QStringLiteral("Кусты"),{"vegetation","bushes"});check(plants,QStringLiteral("Лесной декор"),{"vegetation","decor"});spin(plants,QStringLiteral("Плотность"),{"vegetation","density"},0,8,0.1);
    auto *environment=createTab(QStringLiteral("Окружение"));
    auto *time=new QComboBox(outer);time->addItem(QStringLiteral("День"),6000);time->addItem(QStringLiteral("Ночь"),18000);fields_["environment/time"]=time;environment->addRow(QStringLiteral("Время"),time);
    connect(time,qOverload<int>(&QComboBox::currentIndexChanged),this,[this,time](int){if(!syncing_)setPathValue({"environment","time"},time->currentData().toInt(),QStringLiteral("Изменено время суток"));});
    auto *weather=new QComboBox(outer);weather->addItem(QStringLiteral("Ясно"),"clear");weather->addItem(QStringLiteral("Дождь"),"rain");weather->addItem(QStringLiteral("Снег"),"snow");weather->addItem(QStringLiteral("Гроза"),"thunder");fields_["environment/weather"]=weather;environment->addRow(QStringLiteral("Погода"),weather);
    connect(weather,qOverload<int>(&QComboBox::currentIndexChanged),this,[this,weather](int){if(!syncing_)setPathValue({"environment","weather"},weather->currentData().toString(),QStringLiteral("Изменена погода"));});
    check(environment,QStringLiteral("Шейдеры, погода, туман"),{"environment","effects"});
    auto *effectsHint=new QLabel(QStringLiteral("Шейдеры, постобработка и туман DarkWinter."),outer);effectsHint->setWordWrap(true);environment->addRow(effectsHint);
    cut_=new QCheckBox(outer);cutY_=number(outer,-64,320,76);cutY_->setEnabled(false);environment->addRow(QStringLiteral("Разрез по высоте"),cut_);environment->addRow(QStringLiteral("Скрыть выше Y"),cutY_);
    connect(cut_,&QCheckBox::toggled,this,[this](bool enabled){if(!syncing_){commitCamera();document_.setValue({"camera","cutY"},enabled?QJsonValue(cutY_->value()):QJsonValue(QJsonValue::Undefined),QStringLiteral("Изменён разрез предпросмотра"));}});
    connect(cutY_,qOverload<double>(&QDoubleSpinBox::valueChanged),this,[this](double value){if(!syncing_&&cut_->isChecked()){commitCamera();document_.setValue({"camera","cutY"},value,QStringLiteral("Изменена высота разреза"));}});
    auto *cutHint=new QLabel(QStringLiteral("Разрез скрывает верхние блоки только в предпросмотре. Генерация и экспорт сохраняют полный мир."),outer);cutHint->setWordWrap(true);environment->addRow(cutHint);
    tabs->setObjectName("propertyTabs");
    outerLayout->addWidget(tabs,3);
    auto *layersGroup=new QGroupBox(QStringLiteral("Слои операций"),outer);auto *layerLayout=new QVBoxLayout(layersGroup);layers_=new QListWidget(layersGroup);layers_->setMinimumHeight(125);layerLayout->addWidget(layers_);
    connect(layers_,&QListWidget::itemChanged,this,[this](QListWidgetItem *item){if(syncing_)return;const int row=layers_->row(item);auto ops=document_.profile().value("operations").toArray();if(row<0||row>=ops.size())return;auto op=ops[row].toObject();op["enabled"]=item->checkState()==Qt::Checked;document_.replaceOperation(row,op,QStringLiteral("Изменена видимость слоя"));});
    auto *remove=new QPushButton(QStringLiteral("Удалить слой"),layersGroup);auto *layerButtons=new QHBoxLayout();layerButtons->addWidget(remove);auto *up=new QPushButton(QStringLiteral("↑"),layersGroup);auto *down=new QPushButton(QStringLiteral("↓"),layersGroup);layerButtons->addWidget(up);layerButtons->addWidget(down);layerLayout->addLayout(layerButtons);
    auto moveLayer=[this](int delta){const int row=layers_->currentRow();const auto ops=document_.profile().value("operations").toArray();const int other=row+delta;if(row<0||other<0||other>=ops.size())return;document_.undoStack()->beginMacro(QStringLiteral("Изменён порядок слоёв"));document_.replaceOperation(row,ops[other].toObject(),QStringLiteral("Перемещён слой"));document_.replaceOperation(other,ops[row].toObject(),QStringLiteral("Перемещён слой"));document_.undoStack()->endMacro();layers_->setCurrentRow(other);};
    connect(up,&QPushButton::clicked,this,[moveLayer]{moveLayer(-1);});connect(down,&QPushButton::clicked,this,[moveLayer]{moveLayer(1);});
    connect(remove,&QPushButton::clicked,this,[this]{document_.removeOperation(layers_->currentRow());});outerLayout->addWidget(layersGroup,2);return outer;
}
void EditorWindow::setPathValue(const QStringList &path,const QJsonValue &value,const QString &label) {
    commitCamera();
    auto candidate=document_.profile();
    if(path.size()==1)candidate[path[0]]=value;else{auto child=candidate.value(path[0]).toObject();child[path[1]]=value;candidate[path[0]]=child;}
    QString error;if(!Document::validate(candidate,&error)){message_->setText(error);sync();return;}document_.setValue(path,value,label);
}
void EditorWindow::sync(const QJsonObject &previous) {
    syncing_=true;const auto profile=document_.profile();const bool full=previous.isEmpty();
    for(auto it=fields_.begin();it!=fields_.end();++it){
        const auto path=it.key().split('/');const auto value=pathValue(profile,path);
        if(!full&&value==pathValue(previous,path))continue;
        QSignalBlocker blocker(it.value());
        if(auto *line=qobject_cast<QLineEdit*>(it.value()))line->setText(value.toString());
        else if(auto *spin=qobject_cast<QDoubleSpinBox*>(it.value()))spin->setValue(value.toDouble());
        else if(auto *check=qobject_cast<QCheckBox*>(it.value()))check->setChecked(value.toBool());
        else if(auto *combo=qobject_cast<QComboBox*>(it.value())){int index=combo->findData(value.toVariant());if(index<0 && it.key()=="environment/time")index=value.toInt()>=13000?1:0;combo->setCurrentIndex(index);}
    }
    const auto camera=profile.value("camera").toObject(),oldCamera=previous.value("camera").toObject();
    if(full||camera.value("cutY")!=oldCamera.value("cutY")){
        QSignalBlocker blockCut(cut_),blockCutY(cutY_);cut_->setChecked(camera.contains("cutY"));cutY_->setEnabled(cut_->isChecked());if(camera.contains("cutY"))cutY_->setValue(camera.value("cutY").toDouble());
    }
    if(full||profile.value("operations")!=previous.value("operations")){
        QSignalBlocker blockLayers(layers_);const int selected=layers_->currentRow();layers_->clear();int index=0;
        for(const auto &value:profile.value("operations").toArray()){const auto op=value.toObject();auto *item=new QListWidgetItem(QStringLiteral("%1. %2").arg(++index).arg(toolName(op.value("kind").toString())),layers_);item->setFlags(item->flags()|Qt::ItemIsUserCheckable);item->setCheckState(op.value("enabled").toBool(true)?Qt::Checked:Qt::Unchecked);}
        if(selected>=0 && selected<layers_->count())layers_->setCurrentRow(selected);
    }
    if(viewport_&&!cameraEdited_&&(full||camera!=oldCamera))viewport_->setCamera(camera);
    updateBrush();
    if(full||profile.value("name")!=previous.value("name"))setWindowTitle(profile.value("name").toString()+QStringLiteral("[*] — DarkWinter • Редактор биомов"));
    setWindowModified(document_.dirty()||cameraEdited_);syncing_=false;
}
void EditorWindow::commitCamera() {
    cameraTimer_->stop();if(!cameraEdited_)return;
    cameraEdited_=false;const auto camera=pendingCamera_;pendingCamera_=QJsonObject{};++navigationCommits_;
    document_.setValue({"camera"},camera,QStringLiteral("Изменён вид"));
    undoAction_->setEnabled(document_.undoStack()->canUndo());redoAction_->setEnabled(document_.undoStack()->canRedo());setWindowModified(document_.dirty());updateBrush();
}
void EditorWindow::clearCameraRequests() {
    cameraRequestTimer_->stop();cameraQueued_=false;cameraInFlight_=false;queuedCamera_=QJsonObject{};lastCameraSent_.invalidate();
}
void EditorWindow::queueCamera(const QJsonObject &camera) {
    // Compatibility entry point: interactive navigation never enters worker IPC.
    if(viewport_)viewport_->setCamera(camera);
}
void EditorWindow::sendQueuedCamera() {clearCameraRequests();}
QJsonObject EditorWindow::interactionDiagnostics()const {
    return {{"localCamera",true},{"retainedScene",bool(displayedScene_)},{"displayedSceneRevision",double(visibleScene_)},
        {"requestedSceneRevision",double(worker_.sceneRevision())},{"sceneUpdatePending",sceneLoading_||rebuild_->isActive()},
        {"navigationInputs",double(navigationInputs_)},{"navigationCommits",double(navigationCommits_)},
        {"cameraRequests",double(cameraRequests_)},{"cameraTimeouts",double(cameraTimeouts_)},
        {"cameraDocumentPending",cameraEdited_},{"cameraRequestQueued",cameraQueued_},{"cameraRequestInFlight",cameraInFlight_},
        {"lastCameraAckMs",double(lastCameraAckMs_)},{"lastNavigationFrameMs",double(lastNavigationFrameMs_)},
        {"frameMatchesActiveCamera",bool(displayedScene_)||currentFrame_},{"cameraIntervalMs",16},{"cameraTimeoutMs",0}};
}
void EditorWindow::documentChanged() {
    const auto current=document_.profile(),previous=previousProfile_;auto previousScene=previous,nextScene=current;
    auto previousGeometryCamera=previous.value("camera").toObject(),nextGeometryCamera=current.value("camera").toObject();
    for(const auto &key:{"targetX","targetY","targetZ","yaw","pitch","span"}){
        previousGeometryCamera.remove(key);nextGeometryCamera.remove(key);
    }
    previousScene["camera"]=previousGeometryCamera;nextScene["camera"]=nextGeometryCamera;
    previousScene.remove("environment");nextScene.remove("environment");
    const bool changedCut=previousGeometryCamera.value("cutY")!=nextGeometryCamera.value("cutY");
    const bool changedCamera=previous.value("camera")!=current.value("camera");
    if(cameraEdited_&&changedCamera){cameraTimer_->stop();cameraEdited_=false;pendingCamera_=QJsonObject{};}
    if(previousScene!=nextScene){
        clearCameraRequests();sceneLoading_=true;sceneReady_=bool(displayedScene_);currentFrame_=sceneReady_;
        exportDirectory_.clear();datapackButton_->setEnabled(false);if(worker_.running())rebuild_->start();updateBrush();
        if(changedCut)previewMessage_->setText(displayedScene_?
            QStringLiteral("Разрез пересчитывается. До обновления показана предыдущая сцена %1.").arg(visibleScene_):
            QStringLiteral("Разрез будет создан при генерации сцены."));
    }
    else {
        if(previous.value("environment")!=current.value("environment")){
            const auto environment=current.value("environment").toObject();
            if(viewport_)viewport_->setEnvironment(environment);
            if(worker_.running())worker_.environment(environment);
        }
        // Camera commits only persist Undo/Redo; viewport navigation already applied the view.
        currentFrame_=bool(displayedScene_)||currentFrame_;updateBrush();
    }
    previousProfile_=current;sync(previous);
}
void EditorWindow::setTool(const QString &tool){tool_=tool;updateBrush();}
void EditorWindow::updateBrush(){if(viewport_&&radius_&&workY_)viewport_->setBrush((bool(displayedScene_)||(sceneReady_&&currentFrame_))?tool_:QString{},radius_->value(),workY_->value());}
void EditorWindow::receiveStroke(QJsonObject stroke) {
    if((!displayedScene_&&(!sceneReady_||!currentFrame_))||!viewport_){message_->setText(QStringLiteral("Сначала создайте точный предпросмотр."));return;}
    if(stroke.contains("sceneRevision") && quint64(stroke.value("sceneRevision").toDouble())!=visibleScene_){message_->setText(QStringLiteral("Показанная сцена сменилась. Повторите мазок в новом виде."));return;}
    stroke.remove("sceneRevision");
    stroke["kind"]=tool_;stroke["enabled"]=true;stroke["radius"]=radius_->value();stroke["strength"]=strength_->value();stroke["falloff"]=falloff_->value();
    stroke["height"]=height_->value();stroke["waterLevel"]=water_->value();stroke["width"]=width_->value();stroke["depth"]=depth_->value();stroke["block"]=block_->text().trimmed();
    if(!stroke.contains("y"))stroke["y"]=workY_->value();
    auto candidate=document_.profile();auto ops=candidate.value("operations").toArray();ops.append(stroke);candidate["operations"]=ops;
    QString error;if(!Document::validate(candidate,&error)){message_->setText(error);return;}
    document_.appendOperation(stroke,QStringLiteral("Добавлена операция: ")+toolName(tool_));
}
void EditorWindow::recenter() {
    commitCamera();
    const auto p=document_.profile();auto camera=p.value("camera").toObject();
    camera["targetX"]=p.value("chunkX").toInt()*16+p.value("chunks").toInt()*8;camera["targetZ"]=p.value("chunkZ").toInt()*16+p.value("chunks").toInt()*8;
    camera["targetY"]=p.value("terrain").toObject().value("baseHeight");camera["span"]=p.value("chunks").toInt()*22.4;
    document_.setValue({"camera"},camera,QStringLiteral("Камера центрирована"));if(viewport_)viewport_->setCamera(camera);
}
void EditorWindow::generate() {
    commitCamera();clearCameraRequests();
    rebuild_->stop();if(!worker_.running()){message_->setText(QStringLiteral("Запустите точный генератор DarkWinter."));return;}
    sceneLoading_=true;sceneReady_=bool(displayedScene_);currentFrame_=sceneReady_;updateBrush();exportDirectory_.clear();datapackButton_->setEnabled(false);
    if(worker_.generate(document_.profile())){progress_->setValue(0);previewMessage_->setText(displayedScene_?QStringLiteral("Обновляется биом. Показанная сцена %1 доступна для камеры и кисти…").arg(visibleScene_):QStringLiteral("Создаётся предпросмотр биома…"));}
}
void EditorWindow::toggleWorker() {
    if(worker_.running()){worker_.stop();return;}
    QString error;if(!worker_.start(projectRoot_,&error)){QMessageBox::warning(this,QStringLiteral("Точный генератор"),error);return;}generate();
}
bool EditorWindow::save(bool choosePath) {
    commitCamera();
    auto path=document_.filePath();if(choosePath||path.isEmpty())path=QFileDialog::getSaveFileName(this,QStringLiteral("Сохранить проект биома"),path,QStringLiteral("Профиль биома (*.json)"));
    if(path.isEmpty())return false;if(!path.endsWith(".json",Qt::CaseInsensitive))path+=".json";
    QString error;if(!document_.save(path,&error)){QMessageBox::warning(this,QStringLiteral("Сохранение"),error);return false;}message_->setText(QStringLiteral("Проект сохранён: ")+QDir::toNativeSeparators(path));return true;
}
bool EditorWindow::maybeSave() {
    commitCamera();
    if(!document_.dirty())return true;
    const auto answer=QMessageBox::question(this,QStringLiteral("Несохранённые изменения"),QStringLiteral("Сохранить изменения проекта?"),QMessageBox::Save|QMessageBox::Discard|QMessageBox::Cancel,QMessageBox::Save);
    if(answer==QMessageBox::Cancel)return false;return answer==QMessageBox::Discard||save();
}
void EditorWindow::open() {
    if(!maybeSave())return;const auto path=QFileDialog::getOpenFileName(this,QStringLiteral("Открыть проект биома"),{},QStringLiteral("Профиль биома (*.json)"));if(path.isEmpty())return;
    QString error;if(!document_.load(path,&error))QMessageBox::warning(this,QStringLiteral("Проект"),error);
}
void EditorWindow::exportProfile() {
    commitCamera();
    const auto path=QFileDialog::getSaveFileName(this,QStringLiteral("Экспорт профиля"),document_.profile().value("id").toString()+".json",QStringLiteral("Профиль биома (*.json)"));if(path.isEmpty())return;
    QSaveFile file(path);const auto bytes=QJsonDocument(document_.profile()).toJson(QJsonDocument::Indented);
    if(!file.open(QIODevice::WriteOnly)||file.write(bytes)!=bytes.size()||!file.commit())QMessageBox::warning(this,QStringLiteral("Экспорт"),file.errorString());
    else message_->setText(QStringLiteral("Профиль экспортирован. Состояние проекта не изменено."));
}
void EditorWindow::exportDatapack() {
    if(exportDirectory_.isEmpty() || !under(QFileInfo(exportDirectory_).canonicalFilePath(),QDir(worker_.ipcDirectory()).filePath("export")))return;
    const auto parent=QFileDialog::getExistingDirectory(this,QStringLiteral("Каталог экспорта datapack"));if(parent.isEmpty())return;
    const QString target=QDir(parent).filePath(document_.profile().value("id").toString());
    if(QFileInfo::exists(target)){QMessageBox::warning(this,QStringLiteral("Экспорт"),QStringLiteral("Папка с таким ID уже существует. Выберите другой каталог или ID."));return;}
    QStringList sources;QDirIterator it(exportDirectory_,QDir::Files|QDir::Dirs|QDir::NoDotAndDotDot,QDirIterator::Subdirectories);qint64 total=0;
    while(it.hasNext()){
        const auto path=it.next();const auto info=it.fileInfo();
        if(info.isSymLink()||!under(info.canonicalFilePath(),exportDirectory_)){QMessageBox::warning(this,QStringLiteral("Экспорт"),QStringLiteral("Недопустимая ссылка в datapack."));return;}
        if(info.isFile()){sources.append(path);total+=info.size();if(sources.size()>20000||total>256*1024*1024){QMessageBox::warning(this,QStringLiteral("Экспорт"),QStringLiteral("Datapack превышает допустимый размер."));return;}}
    }
    if(sources.isEmpty()){QMessageBox::warning(this,QStringLiteral("Экспорт"),QStringLiteral("Backend ещё не подготовил datapack."));return;}
    for(const auto &source:sources){
        const auto destination=QDir(target).filePath(QDir(exportDirectory_).relativeFilePath(source));QDir().mkpath(QFileInfo(destination).absolutePath());QFile input(source);QSaveFile output(destination);
        if(!input.open(QIODevice::ReadOnly)||!output.open(QIODevice::WriteOnly)){QMessageBox::warning(this,QStringLiteral("Экспорт"),QStringLiteral("Не удалось записать datapack. Экспорт не завершён."));return;}
        while(!input.atEnd()){const auto chunk=input.read(1024*1024);if(chunk.isEmpty()||output.write(chunk)!=chunk.size()){QMessageBox::warning(this,QStringLiteral("Экспорт"),QStringLiteral("Ошибка записи datapack."));return;}}
        if(!output.commit()){QMessageBox::warning(this,QStringLiteral("Экспорт"),output.errorString());return;}
    }
    message_->setText(QStringLiteral("Datapack экспортирован: ")+QDir::toNativeSeparators(target));
}
void EditorWindow::closeEvent(QCloseEvent *event){if(!maybeSave()){event->ignore();return;}rebuild_->stop();cameraTimer_->stop();clearCameraRequests();worker_.stop();event->accept();}
