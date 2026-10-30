// Builds the live laboratory sidebar and dispatches edits through server authority.
package com.dark.winter.client;

import com.dark.winter.client.lab.LabUiProvider;
import com.dark.winter.client.online.OnlineTestingView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A live, focusable sidebar. Every world mutation goes through server authority;
 * its widget bounds and the Lab semantic state describe the same controls.
 */
public final class TestingEditorScreen extends Screen implements LabUiProvider {
    private enum Tab {
        SYSTEMS("Системы"),ISLAND("Остров"),OBJECTS("Объекты"),SCENE("Сцена"),HISTORY("Версии"),
        TEST("Испытание"),CAMERA("Камера"),SCENARIOS("Сценарии"),DIAGNOSTICS("Данные");
        final String label;Tab(String label){this.label=label;}
    }
    private record Caption(String text,int y,int color) {}
    private record LibraryIcon(Identifier texture,int y) {}
    private record ModuleButton(Button button,String module) {}
    private final Screen parent;
    private final Map<String,String> values=new HashMap<>();
    private final List<Caption> captions=new ArrayList<>();
    private final List<LibraryIcon> libraryIcons=new ArrayList<>();
    private final List<ModuleButton> moduleButtons=new ArrayList<>();
    private final List<AbstractWidget> contentWidgets=new ArrayList<>();
    private final Map<Button,String> actionGates=new HashMap<>();
    private Tab tab=Tab.SYSTEMS;
    private String detail="";
    private int panelLeft,panelWidth,panelBottom,contentTop,contentBottom,scroll,contentHeight,cursor;
    private int catalogPage,catalogKind,scenePage,libraryPage,exportPage;
    private long lastLibraryRevision=-1,lastExportRevision=-1,lastReportsRevision=-1,lastPrefabTransferRevision=-1,lastOnlineRevision=-1;
    private boolean lastDiagnosticEnabled;
    private record SceneRow(com.dark.winter.testing.TestingScene.ObjectView view,int depth) {}
    private String message="";
    private int dragAxis=-1,dragSteps;
    private double dragX,dragY;
    private boolean dragging;
    private String pending="";
    private boolean sceneWatching;
    private String lastTransferReviewFingerprint="";
    private String lastSceneSelection="",lastSceneGeometry="",lastReviewFingerprint="",lastTimelineFingerprint="";
    public TestingEditorScreen(Screen parent) {
        super(Component.literal("DarkWinter | Лаборатория"));this.parent=parent;
    }
    public static void openTransferReview(net.minecraft.client.Minecraft client){
        TestingDrone.stop();var screen=new TestingEditorScreen(null);screen.tab=Tab.HISTORY;client.gui.setScreen(screen);
    }
    public static void clearWorldHistory(){TestingClient.reset();}
    @Override protected void init() {
        if(!TestingClient.active()){onClose();return;}
        if(TestingClient.selected()==null && minecraft.hitResult instanceof BlockHitResult hit && hit.getType()==HitResult.Type.BLOCK)
            TestingClient.select(hit.getBlockPos(),hit.getBlockPos().relative(hit.getDirection()));
        panelWidth=Math.min(328,Math.max(224,width/3));
        panelWidth=Math.min(panelWidth,width-16);
        panelLeft=width-panelWidth-8;
        panelBottom=height-34; // Keep the native nine-slot hotbar below every editor surface.
        contentTop=108;contentBottom=Math.max(contentTop+20,panelBottom-62);
        captions.clear();libraryIcons.clear();moduleButtons.clear();contentWidgets.clear();actionGates.clear();
        int navWidth=(panelWidth-16)/3;
        for(Tab t:Tab.values()){
            int x=16+(t.ordinal()%3)*navWidth,y=40+(t.ordinal()/3)*20;
            addRenderableWidget(Button.builder(Component.literal(t.label),b->{tab=t;detail="";scroll=0;pending="";if(t==Tab.HISTORY&&!OnlineTestingView.active())TestingSessions.action(minecraft,"refresh","");rebuildWidgets();})
                    .bounds(x,y,navWidth-3,18).build());
        }
        addRenderableWidget(Button.builder(Component.literal("В мир"),b->onClose()).bounds(16,panelBottom-25,(panelWidth-22)/2,18).build());
        addRenderableWidget(Button.builder(Component.literal("Обновить"),b->rebuildWidgets()).bounds(20+(panelWidth-22)/2,panelBottom-25,(panelWidth-22)/2,18).build());
        if(tab==Tab.SCENE&&!sceneWatching){lastSceneSelection=TestingClient.value("scene_selected");sceneWatching=true;}
        if(tab!=Tab.SCENE)sceneWatching=false;
        cursor=contentTop-scroll;
        switch(tab){
            case SYSTEMS->systems();case ISLAND->island();case OBJECTS->objects();case SCENE->scene();case HISTORY->history();
            case TEST->test();case CAMERA->camera();case SCENARIOS->scenarios();case DIAGNOSTICS->diagnostics();
        }
        // Build every native control in one local coordinate system, then anchor the whole sidebar.
        for(var child:children())if(child instanceof AbstractWidget widget)widget.setX(widget.getX()+panelLeft-8);
        contentHeight=Math.max(0,cursor-(contentTop-scroll));
        for(AbstractWidget w:contentWidgets){
            w.visible=w.getY()>=contentTop && w.getBottom()<=contentBottom;
            if(w instanceof Button button&&OnlineTestingView.active()&&actionGates.containsKey(button))button.active&=OnlineTestingView.allowed(actionGates.get(button));
            if(localPreview()&&tab!=Tab.HISTORY&&tab!=Tab.CAMERA&&tab!=Tab.DIAGNOSTICS&&tab!=Tab.SYSTEMS)w.active=false;
        }
    }
    private boolean localPreview(){return !OnlineTestingView.active()&&TestingSessions.isPreview();}
    private List<String> scenarioNames(){return OnlineTestingView.active()?OnlineTestingView.scenarioNames():com.dark.winter.testing.TestingScenarios.names(minecraft.getSingleplayerServer());}
    private List<String> spawnerNames(){return OnlineTestingView.active()?OnlineTestingView.spawnerNames():com.dark.winter.testing.TestingScenarios.spawnerNames(minecraft.getSingleplayerServer());}
    private List<String> spawnableTypes(){return OnlineTestingView.active()?OnlineTestingView.spawnableTypes():com.dark.winter.testing.TestingScenarios.spawnableTypes();}
    private String scenarioStatus(){return OnlineTestingView.active()?OnlineTestingView.scenarioStatus():com.dark.winter.testing.TestingScenarios.status(minecraft.getSingleplayerServer());}
    private void startScenario(String name){if(OnlineTestingView.active())action("scenario_run",name);else TestingScenarioTimeline.start(minecraft,name);}
    private Button onlineRow(String label,String command,java.util.function.Supplier<String> argument){
        Button button=row(label,()->action(command,argument.get()));button.active=OnlineTestingView.allowed(command);
        if(!button.active)button.setTooltip(Tooltip.create(Component.literal(OnlineTestingView.reason(command))));return button;
    }
    private void caption(String text){caption(text,HudStyle.MUTED);}
    private void caption(String text,int color){captions.add(new Caption(text,cursor,color));cursor+=14;}
    private void captionWrapped(String text){captionWrapped(text,HudStyle.MUTED);}
    private void captionWrapped(String text,int color){
        int available=Math.max(1,panelWidth-20);
        for(String paragraph:text.split("\\R",-1)){
            if(paragraph.isEmpty()){caption("",color);continue;}
            String rest=paragraph;
            while(!rest.isEmpty()){
                String line=font.plainSubstrByWidth(rest,available);
                // Native substring selection uses fractional advances; Font.width rounds its rendered width up.
                while(!line.isEmpty()&&(font.width(line)>available||HudStyle.width(Component.literal(line))*.69f>available))
                    line=line.substring(0,line.offsetByCodePoints(line.length(),-1));
                if(line.isEmpty())line=rest.substring(0,rest.offsetByCodePoints(0,1));
                caption(line,color);rest=rest.substring(line.length());
            }
        }
    }
    private Button row(String label,Runnable action){
        Button button=Button.builder(Component.literal(label),b->action.run()).bounds(16,cursor,panelWidth-16,20).build();
        contentWidgets.add(addRenderableWidget(button));cursor+=24;return button;
    }
    private void pair(String a,Runnable aa,String b,Runnable bb){gatedPair(a,"",aa,b,"",bb);}
    private Button gatedRow(String label,String command,Runnable callback){return gate(row(label,callback),command);}
    private Button gate(Button button,String command){
        if(!command.isEmpty())actionGates.put(button,command);
        if(OnlineTestingView.active()&&!command.isEmpty()&&!OnlineTestingView.allowed(command)){
            button.active=false;button.setTooltip(Tooltip.create(Component.literal(OnlineTestingView.reason(command))));
        }return button;
    }
    private void gatedPair(String a,String actionA,Runnable aa,String b,String actionB,Runnable bb){
        int w=(panelWidth-20)/2;
        Button ba=Button.builder(Component.literal(a),v->aa.run()).bounds(16,cursor,w,20).build();
        Button bbutton=Button.builder(Component.literal(b),v->bb.run()).bounds(20+w,cursor,w,20).build();
        contentWidgets.add(addRenderableWidget(gate(ba,actionA)));contentWidgets.add(addRenderableWidget(gate(bbutton,actionB)));cursor+=24;
    }
    private EditBox field(String key,String title,String initial){
        caption(title,HudStyle.INK);
        EditBox box=new EditBox(font,16,cursor,panelWidth-16,20,Component.literal(title));
        box.setMaxLength(key.equals("scenario_events")?131072:key.equals("scene_children")?8192:key.startsWith("library_desc_")?2048:512);box.setValue(values.computeIfAbsent(key,k->initial));
        box.setResponder(v->values.put(key,v));
        contentWidgets.add(addRenderableWidget(box));cursor+=25;return box;
    }
    private String value(String key,String fallback){String v=TestingClient.value(key);return v.isBlank()?fallback:v;}
    private void parameter(String key,String title,String fallback){
        caption("Сейчас: "+value(key,fallback));
        field(key,title,value(key,fallback));
        gatedRow("Применить: "+title,"parameter",()->{TestingClient.action("parameter",key+"="+values.get(key));message="Параметр отправлен на проверку";});
    }
    private void action(String action,String value){if(OnlineTestingView.active()&&!OnlineTestingView.allowed(action)){message=OnlineTestingView.reason(action);return;}if(localPreview()&&!java.util.Set.of("teleport","toggle","all_on","all_off","group_on","group_off","parameter","setting","profile_load","diagnostics","report").contains(action)){message="Геометрия и определения предпросмотра доступны только для чтения";return;}TestingClient.action(action,value);message=action.equals("teleport")&&TestingDrone.active()?TestingCameraState.status():action.equals("camera")?TestingDrone.status():"Запрос: "+action;}
    private void confirm(String key,String label,Runnable action){
        if(pending.equals(key)){pending="";action.run();message=label;}else{pending=key;message="Нажмите ещё раз: "+label;}
    }
    private void systems(){
        if(!detail.isEmpty()){systemDetail();return;}
        gatedPair("Включить всё","all_on",()->action("all_on",""),"Выключить всё","all_off",()->action("all_off",""));
        if(localPreview())caption("Параметры меняются только в копии предпросмотра.");
        else gatedRow("Сбросить системы • мир сохраняется","reset",()->action("reset",""));
        caption("Быстрый переключатель / подробные настройки");
        for(String module:TestingClient.MODULES){
            int toggleWidth=43;
            var toggle=Button.builder(Component.literal(TestingClient.enabled(module)?"ВКЛ":"ВЫКЛ"),b->action("toggle",module))
                    .bounds(16,cursor,toggleWidth,20).build();
            toggle.setTooltip(Tooltip.create(Component.literal("Клавиша: "+TestingClient.shortcut(module)+". Назначение: Настройки → Управление.")));
            contentWidgets.add(addRenderableWidget(toggle));moduleButtons.add(new ModuleButton(toggle,module));
            if(OnlineTestingView.active()){toggle.active=OnlineTestingView.allowed("toggle");if(!toggle.active)toggle.setTooltip(Tooltip.create(Component.literal(OnlineTestingView.reason("toggle"))));}
            var settings=Button.builder(Component.literal(TestingClient.label(module)),b->{detail=module;scroll=0;rebuildWidgets();})
                    .bounds(20+toggleWidth,cursor,panelWidth-20-toggleWidth,20).build();
            contentWidgets.add(addRenderableWidget(settings));cursor+=24;
        }
        caption("Группы независимых систем");
        group("Визуал",List.of("visuals","atmosphere"));
        group("Окружение",List.of("weather","wind","precipitation"));
        group("Персонаж",List.of("stamina","motion","camera"));
        group("Выживание",List.of("survival","hud"));
        field("profile","Имя профиля","Чистый полигон");
        if(localPreview())gatedRow("Применить профиль к предпросмотру","profile_load",()->action("profile_load",values.get("profile")));
        else gatedPair("Сохранить профиль","profile_save",()->action("profile_save",values.get("profile")),"Применить профиль","profile_load",()->action("profile_load",values.get("profile")));
        caption(OnlineTestingView.active()?"Профили и изменения применяет ведущий острова.":"Профили общие; состояние модулей хранится в мире.");
        caption("Растительность управляется на вкладке «Остров».");
    }
    private void group(String name,List<String> modules){
        gatedPair(name+" +","toggle",()->{for(String m:modules)if(!TestingClient.enabled(m))TestingClient.action("toggle",m);},name+" −","toggle",()->{for(String m:modules)if(TestingClient.enabled(m))TestingClient.action("toggle",m);});
    }
    private void systemDetail(){
        row("‹ Все системы",()->{detail="";scroll=0;rebuildWidgets();});
        caption(TestingClient.label(detail),HudStyle.INK);
        gatedRow("Переключить: "+(TestingClient.enabled(detail)?"ВКЛ":"ВЫКЛ"),"toggle",()->action("toggle",detail));
        caption("Горячая клавиша: "+TestingClient.shortcut(detail));
        caption("Назначается в обычном меню управления.");
        switch(detail){
            case "weather"->{
                field("weather","Состояние погоды",value("weather","clear"));
                for(String weather:List.of("clear","partly_cloudy","overcast","light_snow","snow","blizzard","severe_blizzard","ground_snow"))
                    gatedRow(weather,"parameter",()->{values.put("weather",weather);action("parameter","weather="+weather);});
                caption("Осадки и ветер имеют отдельные переключатели.");
            }
            case "wind"->{parameter("windSpeed","Скорость ветра, м/с","0");parameter("windDirection","Направление ветра, 0–360°","270");}
            case "precipitation"->{
                caption("Использует выбранный профиль погоды.");
                caption("Профиль задаёт снег; модуль погоды меняет небо отдельно.");
                row("Перейти к погоде",()->{detail="weather";scroll=0;rebuildWidgets();});
            }
            case "atmosphere"->{parameter("hazeDensity","Плотность дымки, 0–0.03","0.005");parameter("temperature","Температура воздуха, °C","-5");}
            case "visuals"->{
                caption("Включает текущий рендерер DarkWinter.");
                caption("Базовый рендер Minecraft и Pipeline Guard активны.");
                parameter("filmStrength","Сила плёночных эффектов, 0–1","1");
                parameter("shadowStrength","Интенсивность теней, 0–0.7","0.5");
            }
            case "stamina"->{
                parameter("staminaCapacity","Максимальная выносливость, 1–1000","150");
                parameter("staminaSprintDrain","Расход при беге, ед./с","8");
                parameter("staminaWalkRegen","Восстановление на ходу, ед./с","12");
                parameter("staminaIdleRegen","Восстановление в покое, ед./с","18");
                parameter("staminaJumpCost","Стоимость обычного прыжка","10");
                parameter("staminaSprintJumpCost","Стоимость прыжка в беге","18");
                caption("Отключение модуля снимает ограничение выносливости.");
            }
            case "survival"->{caption("Управляет реализованным голодом.");caption("Отопление, жажда и холод не заявлены как механики.");}
            case "motion"->{parameter("motionWalkSpeed","Обычная ходьба: множитель, 0.55–1","0.82");caption("Использует текущий контроллер движения DarkWinter.");}
            case "camera"->{
                parameter("motionCameraWeight","Вес камеры персонажа, 0–1.6","1");
                parameter("motionTurnWeight","Инерция поворота, 0–1.6","1");
                caption("Параметры сохраняются только в этом тестовом мире.");
                row("Свободная камера и точки обзора",()->{tab=Tab.CAMERA;detail="";scroll=0;rebuildWidgets();});
            }
            default->caption("Независимый переключатель существующего модуля.");
        }
    }
    private void island(){
        if(TestingClient.value("mode").startsWith("TEST_")){caption("Редактор острова недоступен в испытании.");return;}
        caption("Биом и покрытие • рельеф сохраняется",HudStyle.INK);
        gatedPair("Снежная тайга","biome",()->action("biome","snowy_taiga"),"Зелёная тайга","biome",()->action("biome","taiga"));
        gatedPair("Луг / трава","biome",()->action("biome","plains"),"Пустыня","biome",()->action("biome","desert"));
        caption("Строения и авторские участки защищены.");
        caption("Растительность • независимые категории",HudStyle.INK);
        for(String type:List.of("grass","bushes","trees")){
            String label=switch(type){case "grass"->"Трава";case "bushes"->"Кусты";default->"Деревья";};
            gatedRow(label+" • включить / убрать автоматическую","vegetation",()->action("vegetation",type));
        }
        parameter("density","Плотность растительности, 1–64","8");
        species("grassSpecies","Вид травы",List.of("auto","minecraft:short_grass","minecraft:fern","minecraft:dead_bush"));
        species("bushSpecies","Вид кустов",List.of("auto","minecraft:sweet_berry_bush","minecraft:azalea","minecraft:dead_bush"));
        species("treeSpecies","Порода деревьев",List.of("auto","minecraft:spruce","minecraft:oak","minecraft:birch"));
        caption("Ручные растения не удаляются автоматически.");
        caption("Кисти • предпросмотр виден в мире",HudStyle.INK);
        parameter("radius","Радиус кисти, блоки","6");
        parameter("strength","Сила кисти","1");
        gatedPair("Круглая кисть","parameter",()->action("parameter","brushShape=circle"),"Квадратная кисть","parameter",()->action("parameter","brushShape=square"));
        field("brush","Инструмент кисти",value("brush","raise"));
        for(String tool:List.of("raise","lower","smooth","flatten","material","erase","water","drain","shore")){
            String label=switch(tool){case "raise"->"Поднять";case "lower"->"Опустить";case "smooth"->"Сгладить";
                case "flatten"->"Выровнять";case "material"->"Нанести грунт";case "erase"->"Убрать слой";case "water"->"Вода";case "drain"->"Убрать воду";default->"Берег";};
            gatedRow("Кисть: "+label,"parameter",()->{values.put("brush",tool);action("parameter","brush="+tool);});
        }
        gatedRow("Применить кисть к выбранной цели","brush",this::applyBrush).active=TestingClient.selected()!=null;
        caption("Клик по миру выбирает центр, рамка показывает охват.");
        caption("Красные клетки защищены и сохраняются кистью.");
        caption("Ручные зоны отмечены красной рамкой.");
        gatedPair("Undo","undo",()->action("undo",""),"Redo","redo",()->action("redo",""));
        field("zone","Название защищённой зоны","Постройка");
        pair("Природа: "+Boolean.parseBoolean(values.getOrDefault("zone_natural","true")),()->{
            values.put("zone_natural",Boolean.toString(!Boolean.parseBoolean(values.getOrDefault("zone_natural","true"))));rebuildWidgets();
        },"Объекты: "+Boolean.parseBoolean(values.getOrDefault("zone_objects","true")),()->{
            values.put("zone_objects",Boolean.toString(!Boolean.parseBoolean(values.getOrDefault("zone_objects","true"))));rebuildWidgets();
        });
        caption("Природа: рельеф, покрытие и растительность.");
        caption("Объекты: постройки, предметы и NPC.");
        gatedPair("Защитить A–B","protect",()->zoneAction("protect"),"Снять защиту","unprotect",()->confirm("unprotect","Снять защиту",()->action("unprotect",values.get("zone"))));
        gatedRow("Применить правила к существующей зоне","zone_rules",()->zoneAction("zone_rules"));
        if(!Boolean.parseBoolean(values.getOrDefault("zone_natural","true"))&&!Boolean.parseBoolean(values.getOrDefault("zone_objects","true")))
            caption("Оба выключены: ручная защита зоны не действует.");
        caption("Опоры авторских объектов защищены автоматически.");
        for(var zone:TestingClient.zones())
            row(zone.name()+" • природа "+zone.natural()+" • объекты "+zone.objects(),()->{
                values.put("zone",zone.name());values.put("zone_natural",Boolean.toString(zone.natural()));values.put("zone_objects",Boolean.toString(zone.objects()));rebuildWidgets();
            });

        gatedRow("Точка появления здесь","spawn",()->action("spawn",""));
        caption("Размер, форма и холмистость острова",HudStyle.INK);
        var generator=TestingClient.generator();
        if(generator.island()){
            field("island_radius","Радиус острова, 32–128",Integer.toString(generator.radius()));
            field("island_shape","Форма: ROUND / OVAL / CRESCENT",generator.shape());
            field("island_hills","Холмистость, 0–2",Double.toString(generator.hills()));
            gatedRow("Изменить форму • резервная версия","resize",()->confirm("resize","Создать версию и перестроить остров",
                    ()->action("resize",values.get("island_radius")+"|"+values.get("island_shape")+"|"+values.get("island_hills"))));
            caption("Мир сохранится и откроется после контрольной точки.");
            caption("Постройки, опоры и защищённые зоны сохраняются.");
        }else caption("Параметры формы доступны островному генератору.");
    }
    private void zoneAction(String operation){
        String value=values.get("zone")+"|"+values.getOrDefault("zone_natural","true")+"|"+values.getOrDefault("zone_objects","true");
        if(!Boolean.parseBoolean(values.getOrDefault("zone_natural","true"))||!Boolean.parseBoolean(values.getOrDefault("zone_objects","true")))
            confirm(operation,"Применить правила с частично отключённой ручной защитой",()->action(operation,value));
        else action(operation,value);
    }
    private void species(String key,String label,List<String> choices){
        String current=value(key,"auto");
        gatedRow(label+": "+current,"parameter",()->{
            int index=choices.indexOf(value(key,"auto"));
            action("parameter",key+"="+choices.get((index+1)%choices.size()));values.remove(key);
        });
    }
    private void applyBrush(){
        BlockPos p=TestingClient.selected();
        if(p!=null)action("brush",values.getOrDefault("brush",value("brush","raise"))+"|"+TestingClient.coords(p)+"|"+
                values.getOrDefault("radius",value("radius","6"))+"|"+values.getOrDefault("strength",value("strength","1")));
    }
    private void objects(){
        if(TestingClient.value("mode").startsWith("TEST_")){caption("Редактор объектов недоступен в испытании.");return;}
        row("Именованные объекты / дерево сцены",()->{tab=Tab.SCENE;scroll=0;rebuildWidgets();});
        caption("Выделение и инспектор",HudStyle.INK);
        field("target","Точные координаты цели x,y,z",TestingClient.selected()==null?"0,80,0":TestingClient.coords(TestingClient.selected()));
        row("Выбрать координаты",()->{
            try{BlockPos p=parse(values.get("target"));TestingClient.select(p,p.above());message="Выбрана цель "+p.toShortString();}
            catch(IllegalArgumentException e){message="Координаты: три целых числа x,y,z";}
        });
        pair("Угол A",()->TestingClient.mark(false),"Угол B",()->TestingClient.mark(true));
        caption("A: "+(TestingClient.first()==null?"—":TestingClient.first().toShortString()));
        caption("B: "+(TestingClient.second()==null?"—":TestingClient.second().toShortString()));
        field("delta","Смещение группы dx,dy,dz","1,0,0");
        gatedPair("Переместить","move",()->transform("move"),"Копировать","copy",()->transform("copy"));
        field("destination","Переместить цель в x,y,z",TestingClient.selected()==null?"0,80,0":TestingClient.coords(TestingClient.selected()));
        gatedRow("Применить точное положение","move",()->{
            try{BlockPos p=TestingClient.selected(),to=parse(values.get("destination"));if(p!=null)TestingClient.transform("move",to.subtract(p));}
            catch(IllegalArgumentException e){message="Неверные координаты назначения";}
        });
        gatedPair("Повернуть 90°","rotate",()->{if(TestingClient.selected()!=null)action("rotate",TestingClient.coords(TestingClient.selected()));},"Повернуть группу","group_rotate",()->action("group_rotate",""));
        gatedRow("Удалить цель","remove",()->confirm("remove","Удалить выбранный объект",()->{
            if(TestingClient.selected()!=null)action("remove",TestingClient.coords(TestingClient.selected()));
        }));
        gatedPair("Undo","undo",()->action("undo",""),"Redo","redo",()->action("redo",""));
        caption("В мире: клик — захват; оси X/Y/Z — перетаскивание.");
        caption("Круг у центра — поворот группы на 90°.");
        caption("Сетка блоков 1 м; масштаб блоков неизменен.");
        caption("Каталог зарегистрированного контента",HudStyle.INK);
        field("catalog_search","Поиск ID / названия","");
        pair("Найти",()->{catalogPage=0;rebuildWidgets();},"Категория: "+category(),()->{catalogKind=(catalogKind+1)%4;catalogPage=0;rebuildWidgets();});
        List<String> catalog=catalog();
        int perPage=6,pages=Math.max(1,(catalog.size()+perPage-1)/perPage);
        catalogPage=Math.clamp(catalogPage,0,pages-1);
        caption("Страница "+(catalogPage+1)+" / "+pages+" • "+catalog.size()+" записей");
        for(int i=catalogPage*perPage;i<Math.min(catalog.size(),(catalogPage+1)*perPage);i++){
            String id=catalog.get(i);
            row(id,()->{values.put("block",id);message="Каталог: "+id;}).setTooltip(Tooltip.create(Component.literal(id)));
        }
        pair("‹ Назад",()->{catalogPage=Math.max(0,catalogPage-1);rebuildWidgets();},"Далее ›",()->{catalogPage=Math.min(pages-1,catalogPage+1);rebuildWidgets();});
        field("block","Блок / предмет по ID","minecraft:spruce_planks");
        gatedPair("Поставить у цели","place",()->{if(TestingClient.adjacent()!=null)action("place",values.get("block")+"|"+TestingClient.coords(TestingClient.adjacent()));},"В инвентарь","give",()->action("give",values.get("block")));
        caption("Prefabs",HudStyle.INK);
        if(OnlineTestingView.active()){onlinePrefabs();return;}
        caption(TestingPrefabs.status());
        field("prefab_name","Имя Prefab","Постройка");
        field("prefab_turns","Поворот Prefab, четверти круга 0–3","0");
        pair("Сохранить A–B",()->TestingPrefabs.exportSelection(minecraft,values.get("prefab_name")),"Вставить Prefab",()->{
            try {TestingPrefabs.paste(minecraft,TestingClient.adjacent(),values.get("prefab_id"),Integer.parseInt(values.get("prefab_turns")));}
            catch(NumberFormatException error){message="Поворот: целое число 0–3";}
        });
        for(String entry:TestingPrefabs.list())row(entry,()->{String id=entry.split("\\|",2)[0].strip();values.put("prefab_id",id);TestingPrefabs.select(id);message="Выбран Prefab "+entry;});
        caption("Prefab сохраняет блоки и данные контейнеров.");
        caption("Копия NPC: новый UUID; AI-память и движение сбрасываются.");
        TestingPrefabTransferClient.refresh(minecraft);
        row("Экспортировать выбранный Prefab",()->TestingPrefabTransferClient.export(minecraft,values.get("prefab_id"))).active=!TestingPrefabTransferClient.busy();
        field("prefab_package","Файл .dwprefab из archive/imports","prefab.dwprefab");
        row("Импортировать независимый Prefab",()->TestingPrefabTransferClient.importPackage(minecraft,values.get("prefab_package"),id->{
            if(minecraft.gui.screen()!=this)return;values.put("prefab_id",id);TestingPrefabs.select(id);rebuildWidgets();
        })).active=!TestingPrefabTransferClient.busy();
        for(String file:TestingPrefabTransferClient.imports())row(file,()->{values.put("prefab_package",file);rebuildWidgets();});
        caption(TestingPrefabTransferClient.status());
        caption("Пакет проверяется до записи; геометрия — при вставке.");

        row("Устройства, триггеры и NPC",()->{tab=Tab.SCENARIOS;scroll=0;rebuildWidgets();});
    }
    private String category(){return switch(catalogKind){case 0->"DarkWinter";case 1->"Все блоки";case 2->"Природа";default->"Все предметы";};}
    private List<String> catalog(){
        String filter=values.getOrDefault("catalog_search","").toLowerCase(Locale.ROOT);
        if(catalogKind==3)return BuiltInRegistries.ITEM.keySet().stream().filter(id->filter.isBlank()||id.toString().contains(filter)||Component.translatable(BuiltInRegistries.ITEM.getValue(id).getDescriptionId()).getString().toLowerCase(Locale.ROOT).contains(filter))
                .map(Identifier::toString).sorted().toList();
        return BuiltInRegistries.BLOCK.keySet().stream().filter(id->{
            String s=id.toString();
            if(catalogKind==0 && !id.getNamespace().startsWith("dark"))return false;
            if(catalogKind==2 && !(s.contains("grass")||s.contains("leaves")||s.contains("log")||s.contains("snow")||s.contains("sand")||s.contains("stone")||s.contains("flower")||s.contains("bush")))return false;
            return filter.isBlank()||s.contains(filter)||BuiltInRegistries.BLOCK.getValue(id).getName().getString().toLowerCase(Locale.ROOT).contains(filter);
        }).map(Identifier::toString).sorted().toList();
    }
    private void transform(String kind){
        try{TestingClient.transform(kind,parse(values.get("delta")));}
        catch(IllegalArgumentException e){message="Смещение: три целых числа dx,dy,dz";}
    }
    private String sceneId(){
        if("true".equals(values.get("scene_new")))return "";
        String id=values.getOrDefault("scene_id","");
        if(id.isBlank())id=TestingClient.value("scene_selected");
        for(var view:TestingClient.scene())if(view.id().equals(id))return id;
        return "";
    }
    public boolean previewScene(){return tab==Tab.SCENE;}
    public String selectedSceneId(){return previewScene()?sceneId():"";}
    public BlockPos gizmoPosition(){
        if(!previewScene())return TestingClient.selected();
        String id=sceneId();
        for(var node:TestingClient.scene())if(node.id().equals(id))return node.min();
        return null;
    }
    private void scene(){
        if(TestingClient.value("mode").startsWith("TEST_")){caption("Авторская сцена доступна после завершения теста.");return;}
        if(!"true".equals(values.get("scene_details"))){sceneBrowser();return;}
        row("‹ Вернуться к дереву сцены",()->{values.put("scene_details","false");rebuildWidgets();});
        var nodes=TestingClient.scene();
        caption("Инспектор именованного объекта",HudStyle.INK);
        field("scene_name","Имя объекта / группы","Новый объект");
        gatedRow("Сохранить объект из выделения A–B","scene_capture",()->action("scene_capture",values.get("scene_name")));
        String id=sceneId();
        var selected=nodes.stream().filter(n->n.id().equals(id)).findFirst().orElse(null);
        if(selected!=null){
            caption("ID: "+id);
            caption(selected.kind()+" • "+selected.blockCount()+" блоков / "+selected.npcCount()+" NPC");
            caption(selected.loaded()?"Данные объектов загружены":"Часть объектов не загружена",selected.loaded()?HudStyle.STABLE:HudStyle.AMBER);
            caption("От "+selected.min().toShortString()+" до "+selected.max().toShortString());
            gatedPair("Выбрать в мире","scene_select",()->{action("scene_select",id);TestingClient.select(selected.min(),selected.min().above());},"Показать объект","scene_focus",()->{
                        if(TestingDrone.active()){
                            double x=(selected.min().getX()+selected.max().getX()+1)/2d,z=(selected.min().getZ()+selected.max().getZ()+1)/2d;
                            action("teleport",x+","+(selected.max().getY()+5)+","+(z+6)+",180,30");
                        }else action("scene_focus",id);
                    });
            gatedRow("Переименовать выбранный","scene_rename",()->action("scene_rename",id+"|"+values.get("scene_name")));
            field("scene_delta","Смещение именованного объекта dx,dy,dz","1,0,0");
            gatedPair("Переместить объект","scene_move",()->action("scene_move",id+"|"+values.get("scene_delta")),"Копировать объект","scene_copy",()->action("scene_copy",id+"|"+values.get("scene_delta")));
            field("scene_to","Точное положение минимума x,y,z",TestingClient.coords(selected.min()));
            gatedRow("Применить точное положение","scene_move",()->{
                try{BlockPos delta=parse(values.get("scene_to")).subtract(selected.min());action("scene_move",id+"|"+TestingClient.coords(delta));}
                catch(IllegalArgumentException error){message="Нужны целые координаты x,y,z";}
            });
            gatedPair("Повернуть объект 90°","scene_rotate",()->action("scene_rotate",id),"Удалить объект","scene_remove",()->confirm("scene_remove","Удалить объект с возможностью Undo",()->action("scene_remove",id)));
            row("Добавить ID к новой группе",()->{
                String old=values.getOrDefault("scene_children","");
                var ids=new java.util.LinkedHashSet<String>(List.of(old.split(",")));ids.remove("");ids.add(id);
                values.put("scene_children",String.join(",",ids));rebuildWidgets();
            });
            field("scene_parent","ID родителя (пусто = корень)","");
            gatedRow("Изменить родителя","scene_reparent",()->action("scene_reparent",id+"|"+values.get("scene_parent")));
            gatedRow("Разгруппировать выбранный","scene_ungroup",()->action("scene_ungroup",id));
            if(selected.kind().equals("group")){
                field("scene_prefab_name","Имя Prefab выбранной группы",selected.name());
                if(OnlineTestingView.active())onlineRow("Сохранить точный состав группы как Prefab","prefab_scene_save",()->id+"|"+values.get("scene_prefab_name"));
                else row("Сохранить точный состав группы как Prefab",()->TestingPrefabs.exportSceneGroup(minecraft,id,values.get("scene_prefab_name")));
                caption("Сохраняются члены группы и её иерархия.");
                caption("Посторонние блоки внутри габаритов не включаются.");
                caption(OnlineTestingView.active()?OnlineTestingView.reason("prefab_scene_save"):TestingPrefabs.status());
            }
            if(selected.npcCount()>0)caption("Копия NPC: новый UUID; AI-память и движение сбрасываются.");

        }else caption("Выберите строку дерева для инспектора.");
        caption("Создание родительской группы",HudStyle.INK);
        field("scene_children","ID дочерних объектов через запятую","");
        gatedRow("Создать группу с выбранными детьми","scene_group",()->action("scene_group",values.get("scene_name")+"|"+values.get("scene_children")));
        gatedPair("Undo","undo",()->action("undo",""),"Redo","redo",()->action("redo",""));
        caption("Иерархия и состав сохраняются вместе с геометрией.");
        caption("Указанные ID проверяются на сервере.");
    }
    private void sceneBrowser(){
        var nodes=TestingClient.scene();
        int fieldWidth=panelWidth-116;
        EditBox search=new EditBox(font,16,cursor,fieldWidth,20,Component.literal("Поиск объекта по имени или ID"));
        search.setMaxLength(256);search.setValue(values.getOrDefault("scene_filter",""));search.setHint(Component.literal("Имя / ID объекта"));
        search.setResponder(v->values.put("scene_filter",v));contentWidgets.add(addRenderableWidget(search));
        var find=Button.builder(Component.literal("Найти"),b->{scenePage=0;rebuildWidgets();}).bounds(20+fieldWidth,cursor,44,20).build();
        var create=Button.builder(Component.literal("Новый"),b->{values.put("scene_details","true");values.put("scene_new","true");
            values.remove("scene_id");values.put("scene_name","Новый объект");scroll=0;rebuildWidgets();}).bounds(68+fieldWidth,cursor,48,20).build();
        contentWidgets.add(addRenderableWidget(find));contentWidgets.add(addRenderableWidget(create));cursor+=24;
        Map<String,List<com.dark.winter.testing.TestingScene.ObjectView>> children=new HashMap<>();
        java.util.Set<String> ids=nodes.stream().map(com.dark.winter.testing.TestingScene.ObjectView::id).collect(java.util.stream.Collectors.toSet());
        for(var view:nodes){String parent=view.parentId();if(parent==null||!ids.contains(parent))parent="";children.computeIfAbsent(parent,k->new ArrayList<>()).add(view);}
        List<SceneRow> ordered=new ArrayList<>();java.util.Set<String> shown=new java.util.HashSet<>();
        for(var root:children.getOrDefault("",List.of()))sceneRows(root,children,shown,0,ordered);
        String filter=values.getOrDefault("scene_filter","").strip().toLowerCase(Locale.ROOT);
        List<SceneRow> filtered=ordered.stream().filter(row->filter.isEmpty()||row.view().name().toLowerCase(Locale.ROOT).contains(filter)||row.view().id().contains(filter)).toList();
        int perPage=24,pages=Math.max(1,(filtered.size()+perPage-1)/perPage);scenePage=Math.clamp(scenePage,0,pages-1);
        caption(filtered.size()+" объектов • страница "+(scenePage+1)+" / "+pages);
        String activeId=sceneId();
        for(int i=scenePage*perPage;i<Math.min(filtered.size(),(scenePage+1)*perPage);i++)sceneTree(filtered.get(i).view(),filtered.get(i).depth(),activeId);
        addRenderableWidget(Button.builder(Component.literal("‹"),b->{scenePage=Math.max(0,scenePage-1);rebuildWidgets();})
                .bounds(panelWidth-43,panelBottom-59,20,17).build()).active=scenePage>0;
        addRenderableWidget(Button.builder(Component.literal("›"),b->{scenePage=Math.min(pages-1,scenePage+1);rebuildWidgets();})
                .bounds(panelWidth-20,panelBottom-59,20,17).build()).active=scenePage<pages-1;
        if(nodes.isEmpty())caption("Кнопка «Новый» сохраняет выделение A–B.");
        caption("Строка дерева открывает инспектор и 3D-оси.");
    }
    private void sceneRows(com.dark.winter.testing.TestingScene.ObjectView view,
            Map<String,List<com.dark.winter.testing.TestingScene.ObjectView>> children,java.util.Set<String> shown,int depth,List<SceneRow> rows){
        if(!shown.add(view.id())||depth>32)return;
        rows.add(new SceneRow(view,depth));
        for(var child:children.getOrDefault(view.id(),List.of()))sceneRows(child,children,shown,depth+1,rows);
    }
    private void sceneTree(com.dark.winter.testing.TestingScene.ObjectView view,int depth,String activeId){
        String label=(view.kind().equals("group")?"▣ ":"◆ ")+view.name()+" ["+view.blockCount()+" / "+view.npcCount()+"]";
        var button=new TreeRowButton(16,cursor,panelWidth-16,20,Component.literal(label),Math.min(depth,12),view.id().equals(activeId),
                b->{values.put("scene_details","true");values.remove("scene_new");values.put("scene_id",view.id());values.put("scene_name",view.name());values.remove("scene_to");scroll=0;
                    action("scene_select",view.id());TestingClient.select(view.min(),view.min().above());rebuildWidgets();});
        button.setTooltip(Tooltip.create(Component.literal(view.id()+" • "+view.kind()+" • "+(view.loaded()?"загружено":"требует загрузки"))));
        contentWidgets.add(addRenderableWidget(button));cursor+=24;
    }
    private static final class TreeRowButton extends Button {
        private final int depth;private final boolean selected;
        TreeRowButton(int x,int y,int w,int h,Component label,int depth,boolean selected,OnPress action){
            super(x,y,w,h,label,action,DEFAULT_NARRATION);this.depth=depth;this.selected=selected;
        }
        @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt){
            MenuStyle.button(g,this,alpha,isHoveredOrFocused()||selected?1:0);
            int indent=7+depth*9;
            if(depth>0)g.fill(getX()+indent-5,getY()+4,getX()+indent-4,getBottom()-4,HudStyle.LINE);
            HudStyle.fit(g,getMessage(),getX()+indent,getY()+6,getWidth()-indent-5,.70f,selected?HudStyle.STABLE:HudStyle.INK);
        }
    }
    private void onlineOperation(){
        var operation=OnlineTestingView.operation();
        if(!operation.status().isBlank())captionWrapped(operation.status());
        if(!OnlineTestingView.status().isBlank())captionWrapped(OnlineTestingView.status());
    }
    private void onlinePages(String command,int page,int total,int size){
        captionWrapped("Страница "+(page+1)+" / "+Math.max(1,(total+size-1)/size)+" • записей: "+total);
        gatedPair("‹ Назад",command,()->action(command,Integer.toString(Math.max(0,page-1))),
                "Далее ›",command,()->action(command,Integer.toString(page+1)));
        // Bounds are host-owned; never request a negative or nonexistent page.
        if(contentWidgets.size()>=2){
            AbstractWidget previous=contentWidgets.get(contentWidgets.size()-2),next=contentWidgets.get(contentWidgets.size()-1);
            previous.active&=page>0;next.active&=page<4095&&(long)(page+1)*size<total;
        }
    }
    private void onlinePrefabs(){
        var prefabs=OnlineTestingView.prefabs();
        captionWrapped("Библиотека Prefab ведущего",HudStyle.INK);captionWrapped(prefabs.status());
        field("prefab_name","Имя Prefab","Постройка");
        onlineRow("Сохранить A–B как Prefab","prefab_save",()->values.get("prefab_name"));
        onlineRow("Обновить Prefab ведущего","prefab_list",()->"");
        for(var prefab:prefabs.entries()){
            onlineRow((prefab.id().equals(prefabs.selectedId())?"Выбран: ":"")+prefab.name(),"prefab_select",prefab::id);
        }
        onlinePages("prefab_page",prefabs.page(),prefabs.total(),24);
        if(!prefabs.selectedId().isEmpty())captionWrapped("Выбранный Prefab: "+prefabs.selectedId());
        field("prefab_turns","Поворот: четверти круга 0–3","0");
        gatedRow("Вставить выбранный Prefab","prefab_paste",()->{
            BlockPos target=TestingClient.adjacent();if(target==null||prefabs.selectedId().isEmpty()){message="Выберите Prefab и место вставки";return;}
            try{int turns=Integer.parseInt(values.get("prefab_turns"));if(turns<0||turns>3)throw new NumberFormatException();
                action("prefab_paste",prefabs.selectedId()+"|"+TestingClient.coords(target)+"|"+turns);
            }catch(NumberFormatException invalid){message="Поворот: целое число 0–3";}
        }).active&=!prefabs.selectedId().isEmpty();
        onlineRow("Экспортировать выбранный Prefab у ведущего","prefab_export",prefabs::selectedId).active&=!prefabs.selectedId().isEmpty();
        field("prefab_package","Файл .dwprefab, подготовленный у ведущего","prefab.dwprefab");
        onlineRow("Импортировать независимый Prefab","prefab_import",()->values.get("prefab_package"));
        captionWrapped("Файлы читает и сохраняет ведущий. Копия NPC получает новый UUID; AI-память и движение сбрасываются.");
        row("Устройства, триггеры и NPC",()->{tab=Tab.SCENARIOS;scroll=0;rebuildWidgets();});
        onlineOperation();
    }
    private void onlineHistory(){
        var history=OnlineTestingView.history();var review=history.transferReview();
        captionWrapped("Версии острова ведущего",HudStyle.INK);
        if(review.active()){
            captionWrapped("Предварительный обзор переноса",HudStyle.INK);
            captionWrapped("Остров заморожен; перенос ещё не применён.");
            onlineRow("Подтвердить показанный перенос","transfer_apply",()->"").active&=review.ready()&&review.canApply()&&!history.busy();
            onlineRow("Отменить перенос","transfer_cancel",()->"");
            for(String line:review.lines())captionWrapped(line);
            onlineOperation();return;
        }
        if(history.preview()){
            captionWrapped("Визуальный предпросмотр • только чтение");
            onlineRow("Вернуться к авторскому редактору","preview_exit",()->"");
            onlineOperation();return;
        }
        captionWrapped(history.storageStatus());captionWrapped(history.status());
        field("version","Имя версии / ветки / шаблона","Версия 1");
        onlineRow("Создать контрольную точку","checkpoint",()->values.get("version"));
        onlineRow("Обновить версии","history_refresh",()->"");
        captionWrapped("Выберите сохранённую версию из списка.");
        for(var version:history.versions()){
            onlineRow((version.id().equals(history.selectedId())?"Выбрана: ":"")+version.name()+" • "+version.bytes()+" B","history_select",version::id);
        }
        onlinePages("history_page",history.page(),history.total(),12);
        if(history.selectedId().isBlank())captionWrapped("Версия пока не выбрана.");
        else captionWrapped("Выбранная версия: "+history.selectedId());
        for(var version:history.versions())if(version.id().equals(history.selectedId())){
            captionWrapped("Категория: "+libraryCategory(version.category()));
            if(!version.description().isBlank())captionWrapped(version.description());
            captionWrapped(version.protectedVersion()?"Версия защищена от удаления.":"Версия не защищена от удаления.");
            String description="online_desc_"+version.id(),category="online_category_"+version.id();
            field(description,"Описание: \\n означает перенос строки",version.description().replace("\n","\\n")).setMaxLength(2048);
            values.putIfAbsent(category,version.category());
            row("Категория: "+libraryCategory(values.get(category)),()->{
                List<String> categories=List.of("general","terrain","survival","construction","scenarios","showcase");
                values.put(category,categories.get((categories.indexOf(values.get(category))+1)%categories.size()));rebuildWidgets();
            });
            onlineRow("Сохранить описание и категорию","history_metadata",()->{
                var payload=new com.google.gson.JsonObject();payload.addProperty("id",version.id());
                payload.addProperty("description",values.get(description).replace("\\n","\n"));payload.addProperty("category",values.get(category));return payload.toString();
            });
            if(version.hasThumbnail())captionWrapped("У версии есть значок сохранения. Для обзора сцены откройте визуальный предпросмотр.");
        }
        boolean selected=!history.selectedId().isBlank();
        onlineRow("Ветка выбранной версии","branch",()->values.get("version")).active&=selected;
        onlineRow("Открыть визуальный предпросмотр","open_preview",()->"").active&=selected;
        onlineRow("Различия / подтвердить полное восстановление","restore",()->"").active&=selected;
        for(String[] category:new String[][]{
                {"restore_settings","Восстановить только настройки"},{"restore_buildings","Восстановить только постройки"},
                {"restore_objects","Восстановить только объекты / NPC"},{"restore_terrain","Восстановить рельеф / воду"},
                {"restore_surface","Восстановить покрытие / биом"},{"restore_vegetation","Восстановить растительность"},
                {"restore_scenarios","Восстановить сценарии / устройства"}}){
            onlineRow(category[1],category[0],()->"").active&=selected;
        }
        onlineRow("Экспортировать остров у ведущего","export",()->values.get("version"));
        onlineRow("Защитить выбранную версию","protect_version",()->"").active&=selected;
        onlineRow("Удалить выбранную: два подтверждения","delete_version",()->"").active&=selected;
        if(OnlineTestingView.recoveryBlocked())gatedRow("Аварийная независимая копия","recover_copy",()->confirm("recover_copy",
                "Открыть отдельную аварийную копию выбранной версии",()->action("recover_copy",""))).active&=selected;
        captionWrapped("Библиотека островов ведущего",HudStyle.INK);
        field("island_file","Файл .dwisland, подготовленный у ведущего","island.dwisland");
        onlineRow("Открыть независимую чистую копию","import",()->values.get("island_file"));
        field("island_categories","Переносимые категории CSV","terrain,surface,grass,bushes,trees,building,object");
        for(String[] category:new String[][]{{"terrain","Рельеф и вода"},{"surface","Покрытие и биом"},{"grass","Трава"},{"bushes","Кусты"},
                {"trees","Деревья"},{"building","Постройки"},{"object","Объекты / NPC"},{"scenarios","Сценарии / устройства"}})
            row(categoryCaption(category[0],category[1]),()->toggleCategory(category[0]));
        row(categoryCaption("settings","Параметры и включённые системы"),()->confirm("template_settings",
                "Явно переносить параметры и включённые системы",()->toggleCategory("settings")));
        gatedRow("Проверить и перенести выбранные категории","template_apply",()->confirm("template_apply",
                "Подготовить обзор переноса с резервной версией",()->action("template_apply",values.get("island_file")+"|"+values.get("island_categories"))));
        captionWrapped("Сначала ведущий проверит архив и покажет различия. Перенос требует отдельного подтверждения.");
        captionWrapped("Рельеф с прочими категориями: два этапа Undo. Параметры систем переносятся только явным выбором.");
        onlineLibrary();onlineOperation();
    }
    private void onlineLibrary(){
        var library=OnlineTestingView.library();
        onlineRow("Обновить шаблоны ведущего","library_refresh",()->"");
        captionWrapped(library.status());
        field("online_export_search","Поиск на текущей странице","");
        row("Показать совпадения",this::rebuildWidgets);
        String query=values.getOrDefault("online_export_search","").strip().toLowerCase(Locale.ROOT);
        for(var entry:library.exports()){
            if(!(entry.name()+" "+entry.description()+" "+entry.filename()).toLowerCase(Locale.ROOT).contains(query))continue;
            Button select=row((entry.valid()?"":"Отклонён: ")+entry.name()+" • "+libraryCategory(entry.category()),()->{
                values.put("island_file",entry.filename());message="Выбран шаблон "+entry.name();rebuildWidgets();
            });select.active=entry.valid();
            if(!entry.valid())captionWrapped(entry.error());
            if(entry.filename().equals(values.get("island_file"))&&entry.valid()){
                captionWrapped(entry.filename());captionWrapped(entry.description());
                captionWrapped("Размер: "+entry.bytes()+" B • "+entry.created());
            }
        }
        onlinePages("library_page",library.page(),library.total(),12);
        captionWrapped("Показаны файлы ведущего; папки гостевого клиента не используются.");
    }
    private void onlineTest(){
        var test=OnlineTestingView.test();
        captionWrapped("Общее испытание острова",HudStyle.INK);
        captionWrapped("Авторская сцена защищается отдельной копией. Во время перехода участники переподключаются.");
        onlineRow("Начать испытание","start",()->"");
        onlineRow(test.paused()?"Продолжить испытание":"Пауза / обзор параметров","pause",()->"");
        onlineRow("Просмотреть изменения","review",()->"");
        for(String line:test.reviewLines())captionWrapped(line);
        gatedRow(test.reviewReady()?"Завершить и вернуться к авторской сцене":"Подготовить завершение и обзор","finish",()->{
            if(!OnlineTestingView.test().reviewReady()){action("review","");return;}
            confirm("online_finish","Вернуться к авторской сцене с выбранными категориями",()->action("finish",""));
        });
        onlineRow("Оставить объекты: "+test.keepObjects(),"keep_objects",()->"");
        onlineRow("Оставить постройки: "+test.keepBuildings(),"keep_buildings",()->"");
        onlineRow("Оставить настройки: "+test.keepSettings(),"keep_settings",()->"");
        captionWrapped("Журнал сохраняется после отката. Изменения перед завершением показываются в обзоре.");
        onlineOperation();
        row("Параметры систем во время испытания",()->{tab=Tab.SYSTEMS;scroll=0;rebuildWidgets();});
    }
    private void history(){
        if(OnlineTestingView.active()){onlineHistory();return;}
        caption("Версии острова и библиотека",HudStyle.INK);
        if(TestingSessions.transferReviewActive()){
            captionWrapped("Предварительный обзор выбранных категорий",HudStyle.INK);
            captionWrapped("Авторский остров заморожен; перенос ещё не применён.");
            row("Подтвердить показанный перенос",()->{TestingSessions.action(minecraft,"transfer_apply","");rebuildWidgets();})
                    .active=TestingSessions.transferReviewCanApply()&&!TestingSessions.busy();
            row("Отменить перенос и вернуться к редактору",()->{TestingSessions.action(minecraft,"transfer_cancel","");rebuildWidgets();});
            for(String line:TestingSessions.transferReviewLines())captionWrapped(line);
            captionWrapped(TestingSessions.status());return;
        }
        if(localPreview()){
            caption("Визуальный предпросмотр • только чтение");
            caption("Геометрия и определения защищены от изменений.");
            caption("Модули настраиваются только в копии предпросмотра.");
            row("Вернуться к авторскому редактору",()->TestingSessions.action(minecraft,"preview_exit",""));
            caption(TestingSessions.status());return;
        }
        caption(TestingSessions.historyStorageStatus());
        field("version","Имя версии / ветки / шаблона","Версия 1");
        row("Контрольная точка",()->session("checkpoint"));
        row("Ветка выбранной версии",()->session("branch")).active=TestingLibraryClient.entries().stream().anyMatch(entry->entry.id().equals(TestingSessions.selectedVersion()));
        caption("Ветка создаётся из выбранной сохранённой версии.");
        row("Открыть визуальный предпросмотр версии",()->session("open_preview"));
        row("Восстановить: список различий / подтвердить",()->session("restore"));
        pair("Только настройки",()->session("restore_settings"),"Только постройки",()->session("restore_buildings"));
        row("Восстановить только объекты",()->session("restore_objects"));
        pair("Только рельеф / вода",()->session("restore_terrain"),"Только покрытие",()->session("restore_surface"));
        row("Восстановить только растительность",()->session("restore_vegetation"));
        row("Восстановить сценарии / устройства",()->session("restore_scenarios"));
        row("Экспортировать остров",()->session("export"));
        boolean blocked=com.dark.winter.testing.TestingServer.recoveryBlocked(minecraft.getSingleplayerServer());
        if(blocked)caption("Восстановление журнала остановлено. Исходный мир сохраняется.");
        row("Аварийная независимая копия из выбранной версии",()->confirm("recover_copy","Открыть выбранную сохранённую версию отдельной аварийной копией",()->session("recover_copy"))).active=blocked;

        pair("Защитить версию",()->session("protect_version"),"Удалить: 2 нажатия",()->session("delete_version"));
        caption("Библиотека островов • .dwisland",HudStyle.INK);
        field("island_file","Имя файла из archive/exports","island.dwisland");
        row("Импортировать как независимый чистый остров",()->{
            TestingDrone.stop();TestingSessions.action(minecraft,"import",values.get("island_file"));message=TestingSessions.status();
        });
        field("island_categories","Переносимые категории CSV","terrain,surface,grass,bushes,trees,building,object");
        pair(categoryCaption("terrain","Рельеф и вода"),()->toggleCategory("terrain"),
                categoryCaption("surface","Покрытие / биом"),()->toggleCategory("surface"));
        pair(categoryCaption("grass","Трава"),()->toggleCategory("grass"),
                categoryCaption("bushes","Кусты"),()->toggleCategory("bushes"));
        pair(categoryCaption("trees","Деревья"),()->toggleCategory("trees"),
                categoryCaption("building","Постройки"),()->toggleCategory("building"));
        row(categoryCaption("object","Размещённые объекты / NPC"),()->toggleCategory("object"));
        row(categoryCaption("scenarios","Сценарии / триггеры / устройства"),()->toggleCategory("scenarios"));
        row(categoryCaption("settings","Параметры и переключатели систем"),()->confirm("template_settings","Явно применить параметры и включённые системы из шаблона",()->toggleCategory("settings")));
        row("Проверить файл и перенести выбранное",()->confirm("template_apply","Перенести выбранные категории с резервной версией",()->{
            TestingDrone.stop();TestingSessions.action(minecraft,"template_apply",values.get("island_file")+"|"+values.get("island_categories"));message=TestingSessions.status();
        }));
        caption("Сначала проверяется весь архив, затем создаётся копия.");
        caption("Рельеф и прочие категории: 2 этапа Undo.");
        caption("Параметры систем переносятся только явным выбором.");
        pair("Только рельеф / вода",()->template("template_terrain"),"Только покрытие",()->template("template_surface"));
        pair("Вся растительность",()->template("template_vegetation"),"Только постройки",()->template("template_buildings"));
        row("Только объекты / поддерживаемые NPC",()->template("template_objects"));
        caption("Доступные экспортированные острова");
        exportLibrary();
        caption("Доступные версии",HudStyle.INK);
        libraryMetadata();
        caption("Состояние: "+TestingSessions.status());
        caption("Восстановление выполняется после остановки мира.");
    }
    private void exportLibrary(){
        TestingExportLibraryClient.ensure(minecraft);
        row("Обновить общую библиотеку шаблонов",()->{TestingExportLibraryClient.refresh(minecraft);rebuildWidgets();});
        caption(TestingExportLibraryClient.status());
        field("export_search","Шаблоны: поиск по имени, описанию или файлу","");
        String filter=values.getOrDefault("export_filter","all");
        pair("Найти шаблоны",()->{exportPage=0;rebuildWidgets();},"Категория: "+libraryCategory(filter),()->{
            var categories=new ArrayList<String>();categories.add("all");categories.addAll(com.dark.winter.testing.TestingArchive.libraryCategories());
            values.put("export_filter",categories.get((categories.indexOf(filter)+1)%categories.size()));exportPage=0;rebuildWidgets();
        });
        String query=values.getOrDefault("export_search","").strip().toLowerCase(Locale.ROOT);
        var matches=TestingExportLibraryClient.entries().stream().filter(entry->filter.equals("all")||filter.equals(entry.category()))
                .filter(entry->(entry.name()+" "+entry.description()+" "+entry.filename()).toLowerCase(Locale.ROOT).contains(query)).toList();
        int pages=Math.max(1,(matches.size()+5)/6);exportPage=Math.clamp(exportPage,0,pages-1);
        caption("Шаблонов: "+matches.size()+" • "+(exportPage+1)+" / "+pages);
        for(var entry:matches.subList(exportPage*6,Math.min(matches.size(),exportPage*6+6))){
            Button button=row((entry.valid()?"":"Отклонён: ")+entry.name()+" • "+libraryCategory(entry.category()),()->{
                values.put("island_file",entry.filename());message="Выбран шаблон "+entry.name();rebuildWidgets();
            });
            button.active=entry.valid();button.setTooltip(Tooltip.create(Component.literal(entry.valid()?entry.filename()+"\\n"+entry.description():entry.error())));
            if(entry.filename().equals(values.get("island_file"))&&entry.valid()){
                caption("Выбран файл: "+entry.filename());
                for(String line:entry.description().split("\\n",-1))if(!line.isBlank())caption(line);
                row("Открыть независимую копию выбранного шаблона",()->{TestingDrone.stop();TestingSessions.action(minecraft,"import",entry.filename());});
            }
        }
        pair("‹ Шаблоны",()->{exportPage=Math.max(0,exportPage-1);rebuildWidgets();},"Шаблоны ›",()->{exportPage=Math.min(pages-1,exportPage+1);rebuildWidgets();});
        caption("Общая библиотека доступна из разных тестовых миров.");
        caption("Полная проверка файлов выполняется перед открытием.");
    }
    private void libraryMetadata(){
        TestingLibraryClient.ensure(minecraft);
        row("Обновить версии и метаданные",()->{TestingSessions.action(minecraft,"refresh","");TestingLibraryClient.refresh(minecraft);rebuildWidgets();});
        caption(TestingLibraryClient.status());
        field("library_search","Поиск: имя, описание или UUID","");
        String filter=values.getOrDefault("library_filter","all");
        pair("Найти",()->{libraryPage=0;rebuildWidgets();},"Категория: "+libraryCategory(filter),()->{
            var categories=new ArrayList<String>();categories.add("all");categories.addAll(com.dark.winter.testing.TestingArchive.libraryCategories());
            values.put("library_filter",categories.get((categories.indexOf(filter)+1)%categories.size()));libraryPage=0;rebuildWidgets();
        });
        String query=values.getOrDefault("library_search","").strip().toLowerCase(Locale.ROOT);
        var matches=TestingLibraryClient.entries().stream().filter(entry->filter.equals("all")||filter.equals(entry.category()))
                .filter(entry->(entry.name()+" "+entry.description()+" "+entry.id()).toLowerCase(Locale.ROOT).contains(query)).toList();
        int pages=Math.max(1,(matches.size()+5)/6);libraryPage=Math.clamp(libraryPage,0,pages-1);
        caption("Совпадений: "+matches.size()+" • "+(libraryPage+1)+" / "+pages);
        for(var entry:matches.subList(libraryPage*6,Math.min(matches.size(),libraryPage*6+6))){
            row(entry.name()+" • "+libraryCategory(entry.category())+" • "+entry.bytes()+" B",()->{
                TestingSessions.action(minecraft,"select",entry.id());message="Выбрана версия "+entry.name();rebuildWidgets();
            }).setTooltip(Tooltip.create(Component.literal(entry.id()+"\n"+entry.description())));
        }
        pair("‹ Версии",()->{libraryPage=Math.max(0,libraryPage-1);rebuildWidgets();},"Версии ›",()->{libraryPage=Math.min(pages-1,libraryPage+1);rebuildWidgets();});
        String selected=TestingSessions.selectedVersion();
        for(var entry:TestingLibraryClient.entries())if(entry.id().equals(selected)){
            caption("Выбрана: "+entry.name(),HudStyle.INK);
            caption("Категория: "+libraryCategory(entry.category()));
            if(entry.description().isBlank())caption("Описание отсутствует.");
            else for(String line:entry.description().split("\n",-1))caption(line);
            String descKey="library_desc_"+entry.id(),categoryKey="library_category_"+entry.id();
            field(descKey,"Описание: \\n означает перенос строки",entry.description().replace("\n","\\n")).setMaxLength(2048);
            String editingCategory=values.getOrDefault(categoryKey,entry.category());
            row("Изменить категорию: "+libraryCategory(editingCategory),()->{
                var categories=com.dark.winter.testing.TestingArchive.libraryCategories();
                values.put(categoryKey,categories.get((categories.indexOf(editingCategory)+1)%categories.size()));rebuildWidgets();
            });
            row("Сохранить описание / категорию",()->TestingLibraryClient.update(minecraft,entry.id(),values.get(descKey).replace("\\n","\n"),
                    values.getOrDefault(categoryKey,entry.category()))).active=!TestingLibraryClient.busy();
            Identifier thumbnail=TestingLibraryClient.thumbnail(minecraft,entry);
            if(thumbnail!=null){libraryIcons.add(new LibraryIcon(thumbnail,cursor));cursor+=68;}
            else caption(TestingLibraryClient.thumbnailStatus(entry));
            caption("Миниатюра взята из значка выбранного сохранения.");
            break;
        }
    }
    private String libraryCategory(String value){return switch(value){
        case "all"->"Все";case "general"->"Общее";case "terrain"->"Рельеф";case "survival"->"Выживание";
        case "construction"->"Постройки";case "scenarios"->"Сценарии";case "showcase"->"Обзор";default->value;
    };}
    private String categoryCaption(String category,String label){
        return (java.util.Arrays.stream(values.getOrDefault("island_categories","").split(",")).map(String::strip).anyMatch(category::equals)?"[x] ":"[ ] ")+label;
    }
    private void toggleCategory(String category){
        var categories=new java.util.LinkedHashSet<String>();
        for(String value:values.getOrDefault("island_categories","").split(","))if(!value.isBlank())categories.add(value.strip());
        if(!categories.remove(category))categories.add(category);
        values.put("island_categories",String.join(",",categories));rebuildWidgets();
    }
    private void template(String action){
        confirm(action,"Перенести категорию с резервной версией",()->{
            TestingDrone.stop();TestingSessions.action(minecraft,action,values.getOrDefault("island_file",""));message=TestingSessions.status();
        });
    }
    private List<String> exportedIslands(){
        var path=net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("darkwinter-testing/archive/exports");
        if(!java.nio.file.Files.isDirectory(path))return List.of();
        try(var files=java.nio.file.Files.list(path)){
            return files.filter(p->java.nio.file.Files.isRegularFile(p)&&p.getFileName().toString().endsWith(".dwisland"))
                    .map(p->p.getFileName().toString()).sorted().limit(64).toList();
        }catch(java.io.IOException error){message="Библиотека недоступна: "+error.getMessage();return List.of();}
    }
    private void session(String command){TestingSessions.action(minecraft,command,values.getOrDefault("version","Версия 1"));message=TestingSessions.status();}
    private void test(){
        if(OnlineTestingView.active()){onlineTest();return;}
        captionWrapped("Полноценное испытание",HudStyle.INK);
        captionWrapped("Авторский мир защищается отдельной копией.");
        row("Начать тест",()->{TestingDrone.stop();TestingClient.collapse(true);TestingSessions.action(minecraft,"start","");});
        row(TestingSessions.isTestPaused()?"Продолжить испытание":"Пауза / обзор параметров",()->{TestingSessions.action(minecraft,"pause","");rebuildWidgets();});
        captionWrapped("Выберите категории сохранения, затем завершите тест.");
        row("Просмотреть изменения",()->{TestingSessions.action(minecraft,"review","");message=TestingSessions.status();});
        for(String line:TestingSessions.reviewLines())captionWrapped(line);
        row(TestingSessions.reviewReady()?"Завершить: вернуться в авторский мир":"Подготовить завершение и обзор",()->{
            if(!TestingSessions.reviewReady()){TestingSessions.action(minecraft,"review","");message="Сначала просмотрите фактические изменения";return;}
            confirm("finish","Вернуться в исходный авторский мир с выбранными категориями",()->TestingSessions.action(minecraft,"finish",""));
        });
        row("Оставить объекты: "+TestingSessions.keepObjects(),()->{TestingSessions.action(minecraft,"keep_objects","");rebuildWidgets();});
        row("Оставить постройки: "+TestingSessions.keepBuildings(),()->{TestingSessions.action(minecraft,"keep_buildings","");rebuildWidgets();});
        row("Оставить настройки: "+TestingSessions.keepSettings(),()->{TestingSessions.action(minecraft,"keep_settings","");rebuildWidgets();});
        captionWrapped("Состояние: "+TestingSessions.status());
        captionWrapped("Журнал сохраняется и после отката.");
        captionWrapped("Разрушительные инструменты недоступны в тесте.");
        row("Настройки систем во время теста",()->{tab=Tab.SYSTEMS;scroll=0;rebuildWidgets();});
    }
    private void camera(){
        caption("Камера и обзор",HudStyle.INK);
        gatedPair("EDIT / CHARACTER","mode",()->{if(!TestingClient.value("mode").startsWith("TEST_"))action("mode","EDIT".equals(value("mode","EDIT"))?"CHARACTER":"EDIT");},TestingCameraState.drone()?"Вернуть персонажа":"Свободная камера-дрон","camera",()->action("camera",TestingDrone.active()?"character":"drone"));
        caption("Самостоятельная камера; тело остаётся на месте.");
        caption("WASD • Space вверх • Shift вниз • Ctrl быстрее");
        caption("Проходит препятствия в пределах загруженных чанков.");
        caption(TestingDrone.status());
        parameter("flySpeed","Скорость полёта, 0.01–0.5","0.05");
        gatedRow("Перейти к выделенному объекту","teleport",()->{BlockPos p=TestingClient.selected();if(p!=null)action("teleport",(p.getX()+.5)+","+(p.getY()+3)+","+(p.getZ()+.5)+",0,45");});
        field("camera_position","Координаты камеры x,y,z,yaw,pitch","0,90,0,0,30");
        gatedRow("Применить положение камеры","teleport",()->action("teleport",values.get("camera_position")));
        if(OnlineTestingView.active()){captionWrapped(OnlineTestingView.reason("camera_bookmarks"));return;}
        field("view","Имя точки обзора","Обзор 1");
        pair("Сохранить точку",()->TestingCameraState.save(values.get("view")),"Перейти к точке",()->TestingCameraState.restore(values.get("view")));
        row("Удалить точку",()->confirm("delete_view","Удалить точку обзора",()->TestingCameraState.delete(values.get("view"))));
        for(String name:TestingCameraState.names())row(name,()->{values.put("view",name);TestingCameraState.restore(name);});
        caption(TestingCameraState.status());
    }
    private void loadScenario(String name){
        java.util.function.Consumer<com.google.gson.JsonObject> receiver=document->{
            if(minecraft.gui.screen()!=this)return;
            values.put("scenario",document.get("name").getAsString());
            values.put("scenario_events",document.getAsJsonArray("events").toString());
            var replay=document.has("replay")&&!document.get("replay").isJsonNull()?document.getAsJsonObject("replay"):null;
            values.put("scenario_baseline",replay==null?"":replay.get("baselineVersionId").getAsString());
            values.put("scenario_rollback",replay==null?"review":replay.get("rollbackPolicy").getAsString());
            var measurements=document.has("measurements")&&!document.get("measurements").isJsonNull()?document.getAsJsonObject("measurements"):null;
            values.put("scenario_measure_interval",measurements==null?"20":measurements.get("intervalTicks").getAsString());
            var metrics=new ArrayList<String>();if(measurements!=null)for(var metric:measurements.getAsJsonArray("metrics"))metrics.add(metric.getAsString());
            values.put("scenario_metrics",String.join(",",metrics));
            var profile=document.getAsJsonObject("profile");int active=0;
            for(var module:profile.getAsJsonObject("modules").entrySet())if(module.getValue().getAsBoolean())active++;
            values.put("scenario_profile_summary","Сохранённый профиль: "+active+" модулей • "+profile.get("weather").getAsString()+" • "+profile.get("temperature").getAsString()+" °C");
            pending="";message="События и параметры загружены: "+name;rebuildWidgets();
        };
        if(OnlineTestingView.active())OnlineTestingView.requestDocument(name,receiver);
        else TestingScenarioTimeline.loadDocument(minecraft,name,receiver);
    }
    private void scenarios(){
        recordingControls();
        caption("Сценарии и реальные устройства",HudStyle.INK);
        caption(scenarioStatus());
        field("scenario","Имя сценария","Проверка погоды");
        gatedRow("Загрузить выбранный сценарий","scenario_document",()->loadScenario(values.get("scenario")));
        caption(values.getOrDefault("scenario_profile_summary","Новый сценарий сохранит текущий профиль."));
        caption(TestingClient.status());
        field("scenario_events","События: JSON или условие:действие:значение; ...","0:toggle:weather;20:parameter:weather=blizzard;200:report:weather").setMaxLength(131072);
        caption("Таймер: 20:toggle:weather");
        caption("Вход: enter@Gate:spawn:Wolves");
        caption("Здоровье: health<=10:finish:low-health");
        caption("Действия: toggle / parameter / spawn / report / finish");
        caption("Каждое событие срабатывает один раз за запуск.");
        caption("Лимиты: 256 событий, 72000 тиков на запуск.");
        gatedRow("Сохранить события и текущий профиль","scenario_save",()->{
            String name=values.get("scenario");Runnable save=()->action("scenario_save",name+"|"+values.get("scenario_events"));
            if(scenarioNames().contains(name))
                confirm("scenario_overwrite:"+name,"Перезаписать события и исходный профиль выбранного сценария",save);
            else save.run();
        });
        gatedPair("Запустить сценарий","scenario_run",()->startScenario(values.get("scenario")),"Остановить","scenario_stop",()->action("scenario_stop",""));
        caption("Версия мира и воспроизведение",HudStyle.INK);
        field("scenario_baseline","Начальная версия UUID (пусто: текущий мир)","");
        row("Использовать выбранную в истории версию",()->{values.put("scenario_baseline",OnlineTestingView.active()?OnlineTestingView.history().selectedId():TestingSessions.selectedVersion());rebuildWidgets();});
        field("scenario_rollback","Завершение: review / discard","review");
        field("scenario_measure_interval","Интервал измерений, 20–1200 тиков","20");
        field("scenario_metrics","Измерения CSV: health,food,heap,loaded_chunks","");
        gatedRow("Сохранить версию / политику / измерения","scenario_metadata",()->action("scenario_metadata",values.get("scenario")+"|"+values.get("scenario_baseline")+"|"+
                values.get("scenario_rollback")+"|"+values.get("scenario_measure_interval")+"|"+values.get("scenario_metrics")));
        caption("Измерения собираются только при включённой диагностике.");
        field("scenario_checkpoint","Имя точки воспроизведения","События 1");
        if(OnlineTestingView.active()){
            onlineRow("Сохранить мир и точное состояние событий","scenario_checkpoint",()->values.get("scenario")+"|"+values.get("scenario_checkpoint"));
            onlineRow("Обновить сохранённые точки","scenario_timeline",()->values.get("scenario"));
            var timeline=OnlineTestingView.timeline();
            if(timeline.name().equals(values.get("scenario")))for(var checkpoint:timeline.checkpoints()){
                captionWrapped(checkpoint.label()+" • событие "+checkpoint.eventIndex()+" • тик "+checkpoint.elapsedTicks());
                gatedRow("Перейти к сохранённой точке","scenario_seek",()->confirm("scenario_seek:"+checkpoint.versionId(),
                        "Открыть сохранённый мир и точное состояние событий",
                        ()->action("scenario_seek",values.get("scenario")+"|"+checkpoint.versionId()+"|"+checkpoint.eventIndex()+"|"+checkpoint.elapsedTicks())));
            }
            onlineOperation();
        }else{
            row("Сохранить мир и точное состояние событий",()->TestingScenarioTimeline.checkpoint(minecraft,values.get("scenario"),values.get("scenario_checkpoint")));
            for(String checkpoint:TestingScenarioTimeline.checkpoints(minecraft,values.get("scenario")))
                row("Перейти: "+checkpoint,()->confirm("scenario_seek","Открыть отдельную копию сохранённой версии и восстановить события",
                        ()->TestingScenarioTimeline.seek(minecraft,values.get("scenario"),checkpoint)));
            caption(TestingScenarioTimeline.status());
        }
        caption("Переходы не воспроизводят физические действия игрока.");
        gatedRow("Отчёт сценария","report",()->action("report",values.get("scenario")));
        for(String name:scenarioNames())
            row(name,()->loadScenario(name));
        caption("Запуск возвращает сохранённый профиль систем.");
        caption("Без начальной версии используется текущая сцена.");
        caption("finish в испытании: пауза и TEST_REVIEW.");
        caption("Полный откат — кнопка завершения испытания.");
        caption("Зоны входа • A–B из редактора объектов",HudStyle.INK);
        field("trigger_name","Название зоны","Gate");
        gatedPair("Создать из A–B","trigger_add",()->{
            if(TestingClient.first()==null||TestingClient.second()==null){message="Сначала выделите углы A и B";return;}
            action("trigger_add",values.get("trigger_name")+"|"+TestingClient.coords(TestingClient.first())+"|"+TestingClient.coords(TestingClient.second()));
        },"Удалить зону","trigger_remove",()->confirm("trigger_remove","Удалить зону",()->action("trigger_remove",values.get("trigger_name"))));
        for(var zone:TestingClient.triggers())
            row(zone.name()+" ["+zone.x0()+","+zone.y0()+","+zone.z0()+"]",()->{values.put("trigger_name",zone.name());message="Выбрана зона "+zone.name();});
        caption(OnlineTestingView.active()?"Условия входа проверяет сервер острова.":"Вход считается по позиции владельца в Overworld.");
        caption("Сущности / NPC • настоящие Mob",HudStyle.INK);
        field("device_name","Название спавнера","Wolves");
        field("entity_type","Тип зарегистрированной сущности","minecraft:wolf");
        field("entity_position","Позиция x,y,z",minecraft.player==null?"0,90,0":
                String.format(Locale.ROOT,"%.1f,%.1f,%.1f",minecraft.player.getX()+3,minecraft.player.getY()+2,minecraft.player.getZ()));
        field("entity_count","Количество, 1–16","1");
        field("entity_ai","Отключить AI: true / false","true");
        field("entity_invulnerable","Неуязвимость: true / false","false");
        gatedRow("Сохранить устройство спавна","spawner_add",()->action("spawner_add",values.get("device_name")+"|"+values.get("entity_type")+"|"+values.get("entity_position")+"|"+
                values.get("entity_count")+"|"+values.get("entity_ai")+"|"+values.get("entity_invulnerable")));
        gatedPair("Создать сущности","spawner_activate",()->action("spawner_activate",values.get("device_name")),"Очистить созданных","spawner_clear",()->confirm("spawner_clear","Удалить только созданных этим устройством",()->action("spawner_clear",values.get("device_name"))));
        gatedRow("Удалить определение спавнера","spawner_remove",()->confirm("spawner_remove","Удалить определение",()->action("spawner_remove",values.get("device_name"))));
        for(String name:spawnerNames())
            row("Устройство: "+name,()->{values.put("device_name",name);message="Выбрано устройство "+name;});
        caption("Поддерживаемые типы:");
        for(String type:spawnableTypes())row(type,()->{values.put("entity_type",type);message="Тип: "+type;rebuildWidgets();});
        caption("NPC: vanilla villager с AI / без AI, без сюжетных диалогов.");
        caption("Созданных сущностей отслеживают UUID устройства.");
        caption("Снимки мира включают их настоящее состояние.");
        caption("Экспорт / импорт сценариев",HudStyle.INK);
        gatedRow("Экспортировать выбранный сценарий","scenario_export",()->action("scenario_export",values.get("scenario")));
        field("scenario_import_file","JSON в darkwinter-testing/scenarios","Проверка погоды.scenario.json");
        gatedRow("Импортировать под указанным новым именем","scenario_import",()->action("scenario_import",values.get("scenario_import_file")+"|"+values.get("scenario")));
        caption("Импорт проверяет весь файл до применения.");
        caption("Определения устройств встроены в сценарий.");
        caption("Отопление и фиктивные показатели не имитируются.");
    }
    private void recordingControls(){
        caption("Запись параметров и событий",HudStyle.INK);
        field("record_name","Имя записи / воспроизведения","Запись 1");
        gatedPair("Начать запись","scenario_record_start",()->action("scenario_record_start",values.get("record_name")),"Остановить запись","scenario_record_stop",()->action("scenario_record_stop",values.get("record_name")));
        gatedRow("Воспроизвести запись","scenario_run",()->startScenario(values.get("record_name")));
        caption("Пишутся принятые переключатели, параметры,");
        caption("отчёты и активации зарегистрированных спавнеров.");
        caption("Результат — сценарий с исходным профилем.");
        caption("Физическое движение игрока не записывается.");
        caption("Повторяемую сцену задаёт версия / копия теста.");
        caption("Внешний журнал остаётся после отката теста.");
    }
    private void diagnostics(){
        recordingControls();
        caption("Фактические измерения клиента",HudStyle.INK);
        gatedRow(TestingClient.diagnostics()?"Отключить измерения / график":"Включить измерения / график","parameter",()->{TestingClient.diagnostics(!TestingClient.diagnostics());rebuildWidgets();});
        caption("FPS: "+minecraft.getFps());
        caption("Время кадра: реальные интервалы HUD extraction.");
        caption("Открытая панель входит в измеряемую нагрузку.");
        var runtime=Runtime.getRuntime();
        caption("Память JVM: "+(runtime.totalMemory()-runtime.freeMemory())/1048576+" MiB");
        caption("CPU: нагрузка процесса, если доступна в JVM.");
        caption("GPU: метрика времени недоступна.");
        field("report_name","Название измерения","Измерение");
        gatedPair("Скриншот","",TestingClient::screenshot,"Сохранить отчёт","report",()->{TestingClient.saveClientReport(values.get("report_name"));action("report","manual");message=TestingClient.reportStatus();});
        caption(TestingClient.reportStatus());
        caption(TestingClient.screenshotStatus());
        caption(TestingReports.status());
        row("Обновить все отчёты",()->{TestingReports.refresh(minecraft,true);rebuildWidgets();});
        field("report_a","Отчёт A, имя файла","");
        field("report_b","Отчёт B, имя файла","");
        row("Сравнить A и B",()->{values.put("compare","true");rebuildWidgets();});
        if("true".equals(values.get("compare")))for(String line:TestingClient.compareReports(values.get("report_a"),values.get("report_b")))caption(line);
        for(String report:TestingClient.reports())pair("A: "+report,()->{values.put("report_a",report);rebuildWidgets();},"B: "+report,()->{values.put("report_b",report);rebuildWidgets();});
        caption("Клиентские и сценарные измерения используют общий флаг мира.");
        caption("Снимки сохраняет штатный Screenshot Minecraft.");
        caption("Тяжёлый сбор отключается вместе с диагностикой.");
        caption("Журнал настоящего клиента",HudStyle.INK);
        caption(TestingClient.errorScope());
        gatedRow("Собирать WARN/ERROR (диагностика вкл)","parameter",()->{TestingClient.diagnostics(true);rebuildWidgets();});
        field("error_detail","Выбранная запись WARN/ERROR","").setEditable(false);
        int errorIndex=0;
        for(String error:TestingClient.recentErrors()){
            final String line=error;
            row((++errorIndex)+". "+error,()->{values.put("error_detail",line);rebuildWidgets();}).setTooltip(Tooltip.create(Component.literal(error)));
        }
        caption("Сбор ограничен 64 KiB и идёт раз в 2 секунды.");
        caption("При выключении диагностики чтение прекращается.");
        caption("Выбранный объект",HudStyle.INK);
        BlockPos p=TestingClient.selected();
        if(p==null)caption("Цель не выбрана.");
        else{
            caption(p.toShortString());
            caption(minecraft.level.getBlockState(p).toString());
            var be=minecraft.level.getBlockEntity(p);
            caption("Block entity: "+(be==null?"нет":be.getClass().getSimpleName()));
        }
    }
    @Override public void tick(){
        if(OnlineTestingView.active()){
            var onlineReview=OnlineTestingView.history().transferReview();
            String onlineFingerprint=onlineReview.active()+"|"+onlineReview.ready()+"|"+String.join("|",onlineReview.lines());
            if(onlineReview.active()&&!onlineFingerprint.equals(lastTransferReviewFingerprint)){
                lastTransferReviewFingerprint=onlineFingerprint;tab=Tab.HISTORY;scroll=0;rebuildWidgets();return;
            }
            if(!onlineReview.active())lastTransferReviewFingerprint="";
            String selected=TestingClient.value("scene_selected");
            if(!selected.equals(lastSceneSelection)){
                lastSceneSelection=selected;values.remove("scene_new");values.put("scene_id",selected);values.remove("scene_to");
                for(var node:TestingClient.scene())if(node.id().equals(selected))values.put("scene_name",node.name());
            }
            if(lastOnlineRevision!=OnlineTestingView.revision()&&!(getFocused() instanceof EditBox)){
                lastOnlineRevision=OnlineTestingView.revision();rebuildWidgets();return;
            }
            for(var entry:moduleButtons){entry.button.setMessage(Component.literal(TestingClient.enabled(entry.module)?"ВКЛ":"ВЫКЛ"));entry.button.active=OnlineTestingView.allowed("toggle");}
            return;
        }
        if(TestingSessions.transferReviewActive()){
            String fingerprint=TestingSessions.transferReviewReady()+"|"+TestingSessions.busy()+"|"+String.join("|",TestingSessions.transferReviewLines());
            if(tab!=Tab.HISTORY||!fingerprint.equals(lastTransferReviewFingerprint)){
                tab=Tab.HISTORY;lastTransferReviewFingerprint=fingerprint;scroll=0;rebuildWidgets();
            }
            return;
        }
        if(!lastTransferReviewFingerprint.isEmpty()){lastTransferReviewFingerprint="";rebuildWidgets();return;}
        if(tab==Tab.OBJECTS&&TestingPrefabTransferClient.revision()!=lastPrefabTransferRevision&&!(getFocused() instanceof EditBox)){
            lastPrefabTransferRevision=TestingPrefabTransferClient.revision();rebuildWidgets();return;
        }
        if(tab==Tab.DIAGNOSTICS&&TestingReports.revision()!=lastReportsRevision&&!(getFocused() instanceof EditBox)){
            lastReportsRevision=TestingReports.revision();rebuildWidgets();return;
        }
        if(tab==Tab.DIAGNOSTICS&&TestingClient.diagnostics()!=lastDiagnosticEnabled&&!(getFocused() instanceof EditBox)){
            lastDiagnosticEnabled=TestingClient.diagnostics();rebuildWidgets();return;
        }
        if(tab==Tab.HISTORY&&!localPreview()){
            TestingExportLibraryClient.ensure(minecraft);
            if(TestingExportLibraryClient.revision()!=lastExportRevision&&!(getFocused() instanceof EditBox)){
                lastExportRevision=TestingExportLibraryClient.revision();rebuildWidgets();return;
            }
            TestingLibraryClient.ensure(minecraft);
            long revision=TestingLibraryClient.revision();
            if(revision!=lastLibraryRevision&&!(getFocused() instanceof EditBox)){lastLibraryRevision=revision;rebuildWidgets();return;}
        }
        if(tab==Tab.SCENARIOS){
            String timeline=String.join("|",TestingScenarioTimeline.checkpoints(minecraft,values.get("scenario")));
            if(!timeline.equals(lastTimelineFingerprint)){lastTimelineFingerprint=timeline;rebuildWidgets();return;}
        }
        if(tab==Tab.TEST){
            String review=TestingSessions.reviewReady()+"|"+String.join("|",TestingSessions.reviewLines());
            if(!review.equals(lastReviewFingerprint)){lastReviewFingerprint=review;rebuildWidgets();return;}
        }
        if(tab==Tab.SCENE){
            String selected=TestingClient.value("scene_selected");
            if(!selected.equals(lastSceneSelection)){
                lastSceneSelection=selected;values.remove("scene_new");values.put("scene_id",selected);values.remove("scene_to");
                values.put("scene_details",Boolean.toString(!selected.isBlank()));
                for(var node:TestingClient.scene())
                    if(node.id().equals(selected))values.put("scene_name",node.name());
                scroll=0;rebuildWidgets();return;
            }
            if("true".equals(values.get("scene_details"))&&!(getFocused() instanceof EditBox)){
                String current=sceneId();
                for(var node:TestingClient.scene())if(node.id().equals(current)){
                    String geometry=node.id()+"|"+node.min()+"|"+node.max()+"|"+node.name()+"|"+node.blockCount()+"|"+node.npcCount()+"|"+node.loaded();
                    if(!geometry.equals(lastSceneGeometry)){lastSceneGeometry=geometry;values.remove("scene_to");rebuildWidgets();return;}
                }
            }
        }
        for(var entry:moduleButtons)entry.button.setMessage(Component.literal(TestingClient.enabled(entry.module)?"ВКЛ":"ВЫКЛ"));
    }
    public int panelLeft(){return panelLeft;}
    public int panelWidth(){return panelWidth;}
    public boolean previewBrush(){return tab==Tab.ISLAND;}
    public boolean previewDevices(){return tab==Tab.SCENARIOS;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){if(minecraft!=null)minecraft.gui.setScreen(parent);}
    @Override public boolean keyPressed(KeyEvent event){
        if(event.isEscape() || DarkWinterClient.isTestingEditorKey(event)){onClose();return true;}
        if(!(getFocused() instanceof EditBox) && event.hasControlDown()){
            if(event.key()==com.mojang.blaze3d.platform.InputConstants.KEY_Z){action("undo","");return true;}
            if(event.key()==com.mojang.blaze3d.platform.InputConstants.KEY_Y){action("redo","");return true;}
        }
        return super.keyPressed(event);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(x>=panelLeft && x<=panelLeft+panelWidth && y>=contentTop && y<contentBottom){
            int max=Math.max(0,contentHeight-(contentBottom-contentTop));
            int next=Math.clamp(scroll-(int)Math.round(vertical*28),0,max);
            if(next!=scroll){scroll=next;rebuildWidgets();}
            return true;
        }
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){
        if(event.x()>=0 && event.x()<panelLeft-4 && event.y()>=0 && event.y()<height && TestingClient.active()){
            if(event.button()==1){
                int handle=handleAt(event.x(),event.y());
                if(handle>=0){dragAxis=handle;dragX=event.x();dragY=event.y();dragSteps=0;dragging=true;return true;}
                if(handle==-2){if(tab==Tab.SCENE&&!sceneId().isBlank())action("scene_rotate",sceneId());else if(tab!=Tab.SCENE)action("group_rotate","");return true;}
                var hit=pick(event.x(),event.y());
                if(tab==Tab.SCENE){
                    var mob=pickMob(event.x(),event.y(),hit);
                    if(mob!=null){values.remove("scene_id");TestingClient.select(mob.blockPosition(),mob.blockPosition().above());
                        action("scene_pick_entity",mob.getUUID().toString());message="Выбран NPC: "+mob.getName().getString();return true;}
                }
                if(hit!=null){TestingClient.select(hit.getBlockPos(),hit.getBlockPos().relative(hit.getDirection()));if(tab==Tab.SCENE){values.remove("scene_id");action("scene_pick",TestingClient.coords(hit.getBlockPos()));}message="Выделено: "+hit.getBlockPos().toShortString();return true;}
            }
        }
        return super.mouseClicked(event,doubleClick);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){
        if(dragging && dragAxis>=0 && gizmoPosition()!=null){
            double[] center=project(Vec3.atCenterOf(gizmoPosition()));
            double[] endpoint=project(Vec3.atCenterOf(gizmoPosition()).add(axis(dragAxis).scale(3)));
            if(center!=null && endpoint!=null){
                double vx=endpoint[0]-center[0],vy=endpoint[1]-center[1],length=Math.hypot(vx,vy);
                if(length>1)dragSteps=(int)Math.round(((event.x()-dragX)*vx+(event.y()-dragY)*vy)/(length*16));
            }
            return true;
        }
        return super.mouseDragged(event,dx,dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event){
        if(dragging){
            if(dragSteps!=0){BlockPos delta=new BlockPos(dragAxis==0?dragSteps:0,dragAxis==1?dragSteps:0,dragAxis==2?dragSteps:0);if(tab==Tab.SCENE){if(!sceneId().isBlank())action("scene_move",sceneId()+"|"+TestingClient.coords(delta));}else TestingClient.transform("move",delta);}
            dragging=false;dragAxis=-1;dragSteps=0;return true;
        }
        return super.mouseReleased(event);
    }
    private static Vec3 axis(int i){return i==0?Vec3.X_AXIS:i==1?Vec3.Y_AXIS:Vec3.Z_AXIS;}
    private int handleAt(double x,double y){
        if(gizmoPosition()==null)return -1;
        Vec3 origin=Vec3.atCenterOf(gizmoPosition());
        for(int i=0;i<3;i++){
            double[] p=project(origin.add(axis(i).scale(3)));
            if(viewportPoint(p,10) && Math.hypot(x-p[0],y-p[1])<10)return i;
        }
        double[] p=project(origin);
        return p!=null && viewportPoint(new double[]{p[0]+18,p[1]-18},10) && Math.hypot(x-p[0]-18,y-p[1]+18)<9?-2:-1;
    }
    private boolean viewportPoint(double[] p,int margin){
        return p!=null&&p[0]>=margin&&p[0]<panelLeft-4-margin&&p[1]>=margin&&p[1]<height-margin;
    }
    private double[] project(Vec3 world){
        var camera=minecraft.gameRenderer.mainCamera();
        Vec3 rel=world.subtract(camera.position()),forward=new Vec3(camera.forwardVector()),up=new Vec3(camera.upVector()),left=new Vec3(camera.leftVector());
        double depth=rel.dot(forward);
        if(depth<=.1)return null;
        double focal=height/(2*Math.tan(Math.toRadians(camera.getFov())/2));
        return new double[]{width/2.0-rel.dot(left)*focal/depth,height/2.0-rel.dot(up)*focal/depth};
    }
    private net.minecraft.world.entity.Mob pickMob(double x,double y,BlockHitResult block){
        var camera=minecraft.gameRenderer.mainCamera();
        double tan=Math.tan(Math.toRadians(camera.getFov())/2),aspect=(double)width/height;
        Vec3 direction=new Vec3(camera.forwardVector()).add(new Vec3(camera.leftVector()).scale(-(x*2/width-1)*tan*aspect))
                .add(new Vec3(camera.upVector()).scale(-(y*2/height-1)*tan)).normalize();
        Vec3 from=camera.position(),to=from.add(direction.scale(128));
        double nearest=block==null?128*128:from.distanceToSqr(block.getLocation());net.minecraft.world.entity.Mob selected=null;
        for(var mob:minecraft.level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,new net.minecraft.world.phys.AABB(from,to).inflate(1))){
            var collision=mob.getBoundingBox().inflate(.1).clip(from,to);
            if(collision.isPresent()&&collision.get().distanceToSqr(from)<nearest){nearest=collision.get().distanceToSqr(from);selected=mob;}
        }
        return selected;
    }
    private BlockHitResult pick(double x,double y){
        var camera=minecraft.gameRenderer.mainCamera();
        double tan=Math.tan(Math.toRadians(camera.getFov())/2),aspect=(double)width/height;
        Vec3 direction=new Vec3(camera.forwardVector()).add(new Vec3(camera.leftVector()).scale(-(x*2/width-1)*tan*aspect))
                .add(new Vec3(camera.upVector()).scale(-(y*2/height-1)*tan)).normalize();
        Vec3 from=camera.position();
        var hit=minecraft.level.clip(new ClipContext(from,from.add(direction.scale(128)),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,minecraft.player));
        return hit.getType()==HitResult.Type.BLOCK?hit:null;
    }
    private static BlockPos parse(String raw){
        try{
            String[] p=raw.split(",");if(p.length!=3)throw new IllegalArgumentException();
            return new BlockPos(Integer.parseInt(p[0].strip()),Integer.parseInt(p[1].strip()),Integer.parseInt(p[2].strip()));
        }catch(RuntimeException e){throw new IllegalArgumentException("Invalid coordinates");}
    }
    @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float dt){
        // World stays visible and simulation continues behind the independent sidebar.
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
        HudStyle.panel(g,panelLeft,6,panelWidth,panelBottom-6);
        HudStyle.text(g,"DARKWINTER / ЛАБОРАТОРИЯ",panelLeft+8,13,.82f,HudStyle.INK);
        String world=TestingClient.worldName();
        HudStyle.fit(g,Component.literal(world+" • "+TestingClient.value("mode")),panelLeft+8,27,panelWidth-16,.63f,HudStyle.MUTED);
        g.enableScissor(panelLeft+4,contentTop,panelLeft+panelWidth-3,contentBottom);
        for(Caption c:captions)if(c.y>=contentTop-14 && c.y<contentBottom)
            HudStyle.fit(g,Component.literal(c.text),panelLeft+8,c.y,panelWidth-20,.69f,c.color);
        g.disableScissor();
        String state=tab==Tab.SCENARIOS?scenarioStatus():tab==Tab.HISTORY||tab==Tab.TEST?(OnlineTestingView.active()?OnlineTestingView.status():TestingSessions.status()):TestingClient.active()?TestingClient.status():"";
        HudStyle.fit(g,Component.literal(state),panelLeft+8,panelBottom-53,tab==Tab.SCENE&&!"true".equals(values.get("scene_details"))?panelWidth-72:panelWidth-16,.62f,HudStyle.AMBER);
        HudStyle.fit(g,Component.literal(message.isEmpty()?"Прокрутка • Esc: мир • Ctrl Z/Y":message),panelLeft+8,panelBottom-40,panelWidth-16,.60f,HudStyle.MUTED);
        if(contentHeight>contentBottom-contentTop){
            int max=Math.max(1,contentHeight-(contentBottom-contentTop));
            int track=contentBottom-contentTop,bar=Math.max(14,track*track/contentHeight),top=contentTop+(track-bar)*scroll/max;
            g.fill(panelLeft+panelWidth-3,top,panelLeft+panelWidth-1,top+bar,HudStyle.STABLE);
        }
        if(!libraryIcons.isEmpty()){
            g.enableScissor(panelLeft+8,contentTop,panelLeft+72,contentBottom);
            for(LibraryIcon icon:libraryIcons)if(icon.y()+64>contentTop&&icon.y()<contentBottom)
                g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,icon.texture(),panelLeft+8,icon.y(),0,0,64,64,64,64);
            g.disableScissor();
        }
        renderHandles(g);
        super.extractRenderState(g,mx,my,dt);
    }
    private void renderHandles(GuiGraphicsExtractor g){
        if(gizmoPosition()==null || !"EDIT".equals(TestingClient.value("mode")))return;
        Vec3 origin=Vec3.atCenterOf(gizmoPosition());
        String[] axes={"X","Y","Z"};int[] colors={0xFFE26464,0xFF7ACC91,0xFF6AA4E5};
        for(int i=0;i<3;i++){
            double[] p=project(origin.add(axis(i).scale(3)));
            if(!viewportPoint(p,12))continue;
            int x=(int)p[0],y=(int)p[1];g.fill(x-6,y-6,x+7,y+7,colors[i]);HudStyle.text(g,axes[i],x-3,y-4,.7f,0xFF101920);
        }
        double[] p=project(origin);
        if(p!=null && viewportPoint(new double[]{p[0]+18,p[1]-18},12)){
            HudStyle.text(g,"↻",(float)p[0]+13,(float)p[1]-23,1f,HudStyle.AMBER);
            if(dragging)HudStyle.text(g,"Δ "+dragSteps,(float)p[0]+12,(float)p[1]+10,.8f,HudStyle.INK);
        }
    }
    @Override public List<Region> darkwinter$labRegions(){
        List<Region> regions=new ArrayList<>();
        if(gizmoPosition()!=null){
            Vec3 origin=Vec3.atCenterOf(gizmoPosition());
            for(int i=0;i<3;i++){
                double[] p=project(origin.add(axis(i).scale(3)));
                if(viewportPoint(p,12))regions.add(new Region("testing.gizmo."+i,"Ось "+("XYZ".charAt(i)),
                        "Перетащить выделение по оси с привязкой 1 блок; применяется одной транзакцией при отпускании.",
                        (int)p[0]-8,(int)p[1]-8,16,16,dragAxis==i));
            }
        }
        return regions;
    }
    @Override public Map<String,String> darkwinter$labState(){
        return Map.of("tab",tab.name(),"detail",detail,"mode",TestingClient.value("mode"),
                "selection",TestingClient.selected()==null?"":TestingClient.coords(TestingClient.selected()),
                "session",OnlineTestingView.active()?OnlineTestingView.status():TestingSessions.status(),"message",message,"pending_confirmation",pending,
                "scene_id",tab==Tab.SCENE?sceneId():"","scene_details",values.getOrDefault("scene_details","false"));
    }
}
