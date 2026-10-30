// Provides native controls for saved connections, name-based joining, and Testing island access.
package com.dark.winter.client.online;

import com.dark.winter.client.HudStyle;
import com.dark.winter.client.MenuStyle;
import com.dark.winter.client.TestingWorlds;
import com.dark.winter.online.signaling.IslandJoinFailure;
import com.dark.winter.client.lab.LabUiProvider;
import com.google.gson.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import static com.dark.winter.client.online.OnlineUiBridge.*;

/** Native connection manager. Public rows are coordinator facts; clicks never fabricate reachability or admission. */
public final class FriendsScreen extends Screen implements LabUiProvider {
    private enum Page { LIST,CREATE,JOIN,DETAILS,ISLAND,ISLAND_JOIN }
    private record Line(String text,int y,int color){}
    private record Gate(Button button,String action,BooleanSupplier condition,String reason){}
    private record Indicator(AbstractWidget widget,boolean online){}
    public record PreviewRect(int x,int y,int width,int height){}
    /** Compatibility with the installed in-world avatar renderer; this screen exposes no profile editor. */
    public interface ProfilePreviewRenderer {
        boolean available(JsonObject profile);
        void render(GuiGraphicsExtractor graphics,JsonObject profile,PreviewRect rectangle,int mouseX,int mouseY,float partialTick);
        default String unavailableReason(){return "Предпросмотр персонажа пока недоступен.";}
    }
    private static volatile ProfilePreviewRenderer previewRenderer;
    public static void installProfilePreview(ProfilePreviewRenderer renderer){previewRenderer=renderer;}

    private final Screen parent;
    private final List<Line> lines=new ArrayList<>();
    private final List<AbstractWidget> content=new ArrayList<>();
    private final List<Gate> gates=new ArrayList<>();
    private final List<Indicator> indicators=new ArrayList<>();
    private final Map<String,String> draft=new HashMap<>();
    private JsonObject view=new JsonObject();
    private Page page=Page.LIST;
    private String selected="",fingerprint="",feedback="",pendingAction="",focusRequest="";
    private PasswordBox password;
    private InvitationCodeBox invitationCode;
    private AbstractWidget pendingFocus;
    private int panelLeft,panelWidth,panelBottom,contentTop,contentBottom,cursor,scroll,contentHeight,refreshTicks;

    public FriendsScreen(Screen parent){super(Component.literal("Соединения DarkWinter"));this.parent=parent;}
    public static void open(Screen parent){
        Minecraft client=Minecraft.getInstance();
        if(client.level==null||TestingWorlds.isTesting()||flag(object(read(),"session"),"active",false))
            client.gui.setScreen(new FriendsScreen(parent));
    }
    public static boolean openQuick(Minecraft client){
        if(client.level==null||!(TestingWorlds.isTesting()||flag(object(read(),"session"),"active",false)))return false;
        open(client.gui.screen());return true;
    }
    public int panelLeft(){return panelLeft;}
    public int panelWidth(){return panelWidth;}
    public int panelBottom(){return panelBottom;}
    public PreviewRect previewRectangle(){return null;}

    @Override protected void init(){
        view=read();fingerprint=viewFingerprint(view);
        panelWidth=minecraft.level==null?Math.min(560,width-24):Math.min(380,Math.max(224,width/2));
        panelWidth=Math.min(panelWidth,width-16);panelLeft=width-panelWidth-8;
        panelBottom=height-(minecraft.level==null?8:34);contentTop=76;contentBottom=Math.max(contentTop+24,panelBottom-48);
        lines.clear();content.clear();gates.clear();indicators.clear();
        if((page==Page.DETAILS||page==Page.ISLAND||page==Page.ISLAND_JOIN)&&selectedRow().isEmpty())page=Page.LIST;
        int half=(panelWidth-20)/2;
        if(page==Page.LIST){
            fixed("Создать",panelLeft+8,48,half,()->show(Page.CREATE));
            fixed("Присоединиться",panelLeft+12+half,48,half,()->show(Page.JOIN));
        }else if(page==Page.CREATE||page==Page.JOIN){
            fixed("‹ Соединения",panelLeft+8,48,panelWidth-16,()->show(Page.LIST));
        }else if(page==Page.DETAILS){
            fixed("‹ Соединения",panelLeft+8,48,half,()->show(Page.LIST));
            Button island=fixed("Остров",panelLeft+12+half,48,half,()->show(Page.ISLAND));
            island.active=flag(selectedRow(),"active",false);
            if(!island.active)island.setTooltip(Tooltip.create(Component.literal("Сначала подключитесь к этому соединению.")));
        }else{
            fixed("‹ Соединение",panelLeft+8,48,half,()->show(Page.DETAILS));
            fixed("Участники",panelLeft+12+half,48,half,()->show(Page.DETAILS));
        }
        fixed(minecraft.level==null?"Назад":"В мир",panelLeft+8,panelBottom-23,half,this::onClose);
        fixed("Обновить",panelLeft+12+half,panelBottom-23,half,this::rebuildWidgets);
        cursor=contentTop-scroll;
        switch(page){
            case LIST->list();case CREATE->create();case JOIN->join();case DETAILS->details();case ISLAND->island();case ISLAND_JOIN->islandJoin();
        }
        contentHeight=cursor-(contentTop-scroll);
        int maximum=Math.max(0,contentHeight-(contentBottom-contentTop)),target=Math.min(scroll,maximum);
        if(!focusRequest.isBlank()){
            for(AbstractWidget widget:content)if(widget instanceof EditBox&&widget.getMessage().getString().equals(focusRequest)){
                target=Math.max(0,Math.min(maximum,scroll+widget.getY()-contentTop-12));pendingFocus=widget;break;
            }
            focusRequest="";
        }
        if(target!=scroll){
            int offset=scroll-target;scroll=target;
            for(int i=0;i<lines.size();i++){Line line=lines.get(i);lines.set(i,new Line(line.text(),line.y()+offset,line.color()));}
            for(AbstractWidget widget:content)widget.setY(widget.getY()+offset);
        }
        for(AbstractWidget widget:content)widget.visible=widget.getY()>=contentTop&&widget.getBottom()<=contentBottom;
        refreshGates();
    }
    private Button fixed(String label,int x,int y,int w,Runnable action){
        return addRenderableWidget(Button.builder(Component.literal(label),unused->action.run()).bounds(x,y,w,18).build());
    }
    private Button row(String label,Runnable action){
        Button button=fixed(label,panelLeft+8,cursor,panelWidth-16,action);content.add(button);cursor+=22;return button;
    }
    private Button command(String title,String action,Supplier<JsonObject> args){return command(title,action,args,()->true,"");}
    private Button command(String title,String action,Supplier<JsonObject> args,BooleanSupplier condition,String why){
        Button button=row(title,()->{
            if(!condition.getAsBoolean()){feedback=why;return;}
            try{
                if(submit(action,args.get())){pendingAction=action;feedback="Выполняется действие…";}
                else feedback=actionReason(action);
            }catch(IllegalArgumentException invalid){feedback="Проверьте заполненные поля.";}
        });
        gates.add(new Gate(button,action,condition,why));return button;
    }
    private String actionReason(String action){
        String known=string(object(view,"capabilityReasons"),action,string(object(view,"reasons"),action,""));
        if(!known.isBlank())return publicText(known);
        if(flag(view,"busy",false))return "Дождитесь завершения текущего действия.";
        return action.startsWith("connection_")?"Действие пока недоступно.":OnlineUiBridge.reason(view,action);
    }
    private void refreshGates(){
        for(Gate gate:gates){
            boolean valid=gate.condition().getAsBoolean();gate.button().active=valid&&allowed(view,gate.action());
            String reason=valid?actionReason(gate.action()):gate.reason();
            gate.button().setTooltip(gate.button().active?null:Tooltip.create(Component.literal(reason.isBlank()?"Действие пока недоступно.":reason)));
        }
    }
    private void text(String text){text(text,HudStyle.MUTED);}
    private void text(String value,int color){
        if(value==null)return;
        int budget=Math.max(1,panelWidth-20);
        for(String paragraph:publicText(value).split("\\R",-1)){
            if(paragraph.isEmpty()){cursor+=12;continue;}
            String remaining=paragraph;
            while(!remaining.isEmpty()){
                String part=font.plainSubstrByWidth(remaining,budget);
                while(!part.isEmpty()&&(font.width(part)>budget||HudStyle.width(Component.literal(part))*.70f>budget))
                    part=part.substring(0,part.offsetByCodePoints(part.length(),-1));
                if(part.isEmpty())part=remaining.substring(0,remaining.offsetByCodePoints(0,1));
                lines.add(new Line(part,cursor,color));cursor+=12;remaining=remaining.substring(part.length());
            }
        }
    }
    private void heading(String title){cursor+=4;text(title,HudStyle.INK);}
    private EditBox field(String key,String label,int limit){
        text(label,HudStyle.INK);EditBox input=new EditBox(font,panelLeft+8,cursor,panelWidth-16,18,Component.literal(label));
        input.setMaxLength(limit);input.setValue(draft.getOrDefault(key,""));input.setResponder(value->draft.put(key,value));
        content.add(addRenderableWidget(input));cursor+=19;return input;
    }
    private void privateField(EditBox input,String label){
        text(label,HudStyle.INK);input.setPosition(panelLeft+8,cursor);input.setWidth(panelWidth-16);input.setHeight(18);
        content.add(addRenderableWidget(input));cursor+=19;
    }
    private static JsonObject args(String key,String value){JsonObject result=new JsonObject();result.addProperty(key,value);return result;}
    private static List<JsonObject> records(JsonArray array){
        List<JsonObject> result=new ArrayList<>();for(JsonElement item:array)if(item.isJsonObject())result.add(item.getAsJsonObject());return result;
    }
    private List<JsonObject> connections(){return records(array(view,"connections"));}
    private JsonObject selectedRow(){
        for(JsonObject row:connections())if(selected.equals(string(row,"id","")))return row;return new JsonObject();
    }
    private static String title(JsonObject row){return string(row,"name","Соединение");}
    private void show(Page next){
        eraseSecrets();page=next;scroll=0;feedback="";
        focusRequest=next==Page.CREATE?"Имя соединения":next==Page.JOIN?"Имя соединения":next==Page.ISLAND_JOIN?"Код острова (скрыт)":"";
        rebuildWidgets();
    }
    private void choose(String id){selected=id;show(Page.DETAILS);}
    private void list(){
        approvals();
        if(flag(object(view,"privacy"),"relayOnly",false)){
            text(actionReason("connection_create"),HudStyle.INK);
            command("Включить автоматическое подключение","privacy_update",()->{
                JsonObject result=args("scope","profile");result.addProperty("key","relayOnly");result.addProperty("value",false);return result;
            });
        }
        List<JsonObject> rows=connections();
        if(rows.isEmpty()){text("Сохранённых соединений пока нет.");text("Создайте соединение или узнайте у другого участника его имя и пароль.");return;}
        for(JsonObject connection:rows){
            String id=string(connection,"id","");if(id.isBlank())continue;
            boolean reachable=flag(connection,"reachable",false);
            String label=title(connection)+(flag(connection,"active",false)?" • подключено":"");
            Button entry=row(label,()->choose(id));entry.setTooltip(Tooltip.create(Component.literal(string(connection,"status",reachable?"Доступно":"Не в сети"))));
            indicators.add(new Indicator(entry,reachable));
        }
    }
    private void create(){
        field("connection_name","Имя соединения",96);
        if(password==null)password=new PasswordBox(font);privateField(password,"Пароль");
        Button create=command("Создать соединение","connection_create",()->{
            JsonObject result=args("name",draft.getOrDefault("connection_name","").strip());result.addProperty("password",password.take());return result;
        },()->validName("connection_name"),"Введите имя: от 1 до 48 символов.");
        create.setHeight(18);cursor-=3;
    }
    private boolean validName(String key){String value=draft.getOrDefault(key,"").strip();return !value.isEmpty()&&value.codePointCount(0,value.length())<=48;}
    private void join(){
        field("join_name","Имя соединения",96);
        if(password==null)password=new PasswordBox(font);privateField(password,"Пароль (если требуется)");
        Button join=command("Присоединиться к соединению","connection_join",()->{
            JsonObject result=args("name",draft.getOrDefault("join_name","").strip());result.addProperty("password",password.take());return result;
        },()->validName("join_name"),"Введите имя: от 1 до 48 символов.");
        join.setHeight(18);cursor-=3;
    }
    private void details(){
        approvals();
        JsonObject connection=selectedRow();boolean active=flag(connection,"active",false),reachable=flag(connection,"reachable",false);
        if(active)copy("Скопировать имя",()->string(selectedRow(),"name",""));
        else command("Подключиться","connection_open",()->args("id",selected));
        heading("Участники");
        List<JsonObject> members=records(array(connection,"members"));
        if(members.isEmpty())text(reachable?"Список участников ещё не получен.":"Участники недоступны.");
        for(JsonObject member:members){
            String name=string(member,"name","Участник")+(flag(member,"self",false)?" (вы)":"");
            text(name,reachable&&flag(member,"online",false)?0xFF78D99C:HudStyle.MUTED);
        }
        if(active)command("Отключиться от соединения","connection_close",JsonObject::new);
        else command("Забыть соединение","connection_forget",()->args("id",selected));
    }
    private void island(){
        approvals();
        JsonObject connection=selectedRow();
        if(!flag(connection,"active",false)){text("Сначала подключитесь к соединению.");return;}
        JsonObject session=object(view,"session");
        if(!flag(session,"active",false)){
            String sessionStatus=safeSessionStatus(session),phase=safeSessionPhase(session);
            if(!sessionStatus.isBlank()&&(!phase.equals("offline")||!Set.of("Совместный сеанс не открыт.","Совместный сеанс закрыт.").contains(sessionStatus)))
                text(sessionStatus,HudStyle.INK);
            command("Открыть остров для игры","online_host",()->{
                JsonObject result=new JsonObject();result.addProperty("limit",8);result.addProperty("privacy","invited");result.addProperty("trustedLead","");return result;
            });
            Button enter=row("Ввести код острова",()->show(Page.ISLAND_JOIN));
            gates.add(new Gate(enter,"connect_invite",()->minecraft.level==null,"Сначала сохраните и закройте текущий мир."));
            text("Откройте свой тестовый остров для игры или введите приглашение от ведущего.");
        }else{
            text(string(session,"status","Сеанс острова открыт."));
            command("Создать приглашение на остров","invite_create",()->{
                JsonObject result=args("recipient","");result.addProperty("role","tester");result.addProperty("ttlMinutes",30);
                result.addProperty("maxUses",1);result.addProperty("oneUse",true);result.addProperty("approval",true);return result;
            });
            copy("Скопировать выданное приглашение",this::islandCode);
            text("Приглашение даёт роль тестировщика. Ведущий подтверждает вход.");
            command("Завершить сеанс острова","online_close",JsonObject::new);
        }
    }
    private String islandCode(){
        String island=string(object(view,"session"),"islandId","");if(island.isBlank())return "";
        String code="";long newest=Long.MIN_VALUE;
        for(JsonObject invitation:records(array(view,"invites"))){
            if(!string(invitation,"direction","").equals("outgoing")||!flag(invitation,"actionable",false)
                    ||!island.equals(string(invitation,"islandId",""))||!string(invitation,"recipient","").isBlank())continue;
            String candidate=string(invitation,"code","");if(candidate.isBlank())continue;
            long created;try{created=invitation.get("createdAt").getAsLong();}catch(RuntimeException invalid){continue;}
            if(created>newest){code=candidate;newest=created;}
        }
        return code;
    }
    private void islandJoin(){
        String sessionStatus=safeSessionStatus(object(view,"session"));
        if(!sessionStatus.isBlank()&&!Set.of("Совместный сеанс не открыт.","Совместный сеанс закрыт.").contains(sessionStatus))
            text(sessionStatus,HudStyle.INK);
        if(invitationCode==null)invitationCode=new InvitationCodeBox(font);privateField(invitationCode,"Код острова (скрыт)");
        command("Войти на остров","connect_invite",()->args("code",invitationCode.take().strip()),
                ()->invitationCode!=null&&invitationCode.hasSecret()&&minecraft.level==null,"Вставьте приглашение и закройте текущий мир.");
    }
    private void copy(String label,Supplier<String> source){
        row(label,()->{view=read();String actual=source.get();if(!actual.isBlank()){minecraft.keyboardHandler.setClipboard(actual);feedback="Скопировано.";}})
                .active=!source.get().isBlank();
    }
    private void approvals(){
        List<JsonObject> requests=records(array(view,"joinRequests"));if(requests.isEmpty())return;
        heading("Запросы на вход");
        for(JsonObject request:requests){
            String attempt=string(request,"id","");text(string(request,"nickname","Участник"),HudStyle.INK);
            Button allow=command("Разрешить вход","join_approve",()->args("id",attempt),()->!attempt.isBlank(),"Запрос уже завершён.");
            Button reject=command("Отклонить вход","join_reject",()->args("id",attempt),()->!attempt.isBlank(),"Запрос уже завершён.");
            int half=(panelWidth-20)/2;allow.setWidth(half);reject.setWidth(half);reject.setX(panelLeft+12+half);reject.setY(allow.getY());cursor-=22;
        }
    }
    private static String viewFingerprint(JsonObject snapshot){
        JsonObject result=new JsonObject();JsonArray connections=new JsonArray();
        for(JsonObject original:records(array(snapshot,"connections"))){
            JsonObject row=new JsonObject();
            for(String key:List.of("id","name","active","hosting","reachable","passwordRequired","status","members"))if(original.has(key))row.add(key,original.get(key).deepCopy());
            connections.add(row);
        }
        result.add("connections",connections);result.addProperty("activeConnectionId",string(snapshot,"activeConnectionId",""));
        result.addProperty("relayOnly",flag(object(snapshot,"privacy"),"relayOnly",false));
        JsonObject caps=new JsonObject(),reasons=new JsonObject();
        for(String action:List.of("connection_create","connection_join","connection_open","connection_close","connection_forget","privacy_update","online_host","online_close","invite_create","connect_invite","join_approve","join_reject")){
            caps.addProperty(action,flag(object(snapshot,"capabilities"),action,false));
            reasons.addProperty(action,string(object(snapshot,"capabilityReasons"),action,string(object(snapshot,"reasons"),action,"")));
        }
        result.add("capabilities",caps);result.add("reasons",reasons);result.addProperty("busy",flag(snapshot,"busy",false));
        JsonObject session=new JsonObject();for(String key:List.of("active","phase","status","openingStage"))if(object(snapshot,"session").has(key))session.add(key,object(snapshot,"session").get(key).deepCopy());
        result.add("session",session);result.add("joinRequests",array(snapshot,"joinRequests").deepCopy());
        JsonArray invitations=new JsonArray();for(JsonObject row:records(array(snapshot,"invites")))if(string(row,"direction","").equals("outgoing")){
            JsonObject entry=new JsonObject();for(String key:List.of("id","direction","state","islandId","recipient","actionable","createdAt"))if(row.has(key))entry.add(key,row.get(key).deepCopy());
            entry.addProperty("hasCode",!string(row,"code","").isBlank());invitations.add(entry);
        }result.add("invitations",invitations);return result.toString();
    }
    @Override public void tick(){
        if(pendingFocus!=null){
            AbstractWidget widget=pendingFocus;pendingFocus=null;
            if(children().contains(widget)&&widget.visible){setFocused(widget);widget.setFocused(true);}
        }
        if(++refreshTicks%5!=0)return;
        JsonObject next=read();String nextFingerprint=viewFingerprint(next);
        String active=string(next,"activeConnectionId",""),before=string(view,"activeConnectionId","");
        boolean activeChanged=!active.equals(before);
        if(!pendingAction.isBlank()&&!flag(next,"busy",false)){
            feedback=publicText(string(next,"status","Действие завершено."));pendingAction="";
        }
        view=next;refreshGates();
        if(activeChanged&&!active.isBlank()){selected=active;eraseSecrets();page=Page.DETAILS;scroll=0;}
        if(!nextFingerprint.equals(fingerprint)&&(!(getFocused() instanceof EditBox)||activeChanged)){
            fingerprint=nextFingerprint;rebuildWidgets();
        }
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(x>=panelLeft&&x<=panelLeft+panelWidth&&y>=contentTop&&y<contentBottom){
            int next=Math.clamp(scroll-(int)Math.round(vertical*24),0,Math.max(0,contentHeight-(contentBottom-contentTop)));
            if(next!=scroll){scroll=next;rebuildWidgets();}return true;
        }return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public boolean keyPressed(KeyEvent event){if(event.isEscape()){if(page==Page.LIST)onClose();else show(Page.LIST);return true;}return super.keyPressed(event);}
    @Override public boolean isPauseScreen(){return false;}
    private void eraseSecrets(){
        if(password!=null){password.setValue("");password=null;}
        if(invitationCode!=null){invitationCode.setValue("");invitationCode=null;}
        pendingFocus=null;focusRequest="";
    }
    @Override public void onClose(){eraseSecrets();minecraft.gui.setScreen(parent);}
    @Override public void removed(){eraseSecrets();}
    @Override public void extractBackground(GuiGraphicsExtractor graphics,int mx,int my,float delta){}
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mx,int my,float delta){
        if(minecraft.level==null)MenuStyle.landscape(graphics,width,height);
        HudStyle.panel(graphics,panelLeft,6,panelWidth,panelBottom-6);
        String heading=switch(page){case LIST->"СОЕДИНЕНИЯ";case CREATE->"СОЗДАТЬ СОЕДИНЕНИЕ";case JOIN->"ПРИСОЕДИНИТЬСЯ";case DETAILS->title(selectedRow());case ISLAND->"ОСТРОВ";case ISLAND_JOIN->"ВХОД НА ОСТРОВ";};
        HudStyle.fit(graphics,Component.literal(heading),panelLeft+8,13,panelWidth-16,.84f,HudStyle.INK);
        String status=flag(view,"busy",false)?"Выполняется действие…":records(array(view,"joinRequests")).isEmpty()
                ?page==Page.LIST?"Сохранённых соединений: "+connections().size():string(selectedRow(),"status",""): "Запросов на вход: "+array(view,"joinRequests").size();
        HudStyle.fit(graphics,Component.literal(publicText(status)),panelLeft+8,33,panelWidth-16,.64f,HudStyle.MUTED);
        graphics.enableScissor(panelLeft+4,contentTop,panelLeft+panelWidth-3,contentBottom);
        for(Line line:lines)if(line.y()>=contentTop&&line.y()+12<=contentBottom)HudStyle.text(graphics,line.text(),panelLeft+8,line.y(),.70f,line.color());
        graphics.disableScissor();
        HudStyle.fit(graphics,Component.literal(publicText(feedback)),panelLeft+8,panelBottom-40,panelWidth-16,.60f,HudStyle.MUTED);
        if(contentHeight>contentBottom-contentTop){
            int track=contentBottom-contentTop,bar=Math.max(10,track*track/contentHeight),maximum=Math.max(1,contentHeight-track);
            int top=contentTop+(track-bar)*scroll/maximum;graphics.fill(panelLeft+panelWidth-3,top,panelLeft+panelWidth-1,top+bar,HudStyle.STABLE);
        }
        super.extractRenderState(graphics,mx,my,delta);
        for(Indicator indicator:indicators)if(indicator.widget().visible){
            AbstractWidget widget=indicator.widget();int y=widget.getY()+widget.getHeight()/2;
            graphics.fill(widget.getX()+5,y-2,widget.getX()+10,y+3,indicator.online()?0xFF78D99C:0xFF78828D);
        }
    }
    private static String safeSessionPhase(JsonObject session){
        String phase=string(session,"phase","");
        return Set.of("offline","preparing","hosting","reopening","joining","failed","disconnected","native_login","connected","reconnecting",
                "requesting","waiting","waiting_approval","connecting","checking").contains(phase)?phase:"unknown";
    }
    private static String safeOpeningStage(JsonObject session){
        String stage=string(session,"openingStage","");
        return Set.of("none","network_settings","compatibility","local_policy","save_stop","bootstrap","reopen","install").contains(stage)?stage:"";
    }
    private static String safeSessionStatus(JsonObject session){
        String status=string(session,"status","");if(status.isBlank())return "";
        if(IslandJoinFailure.isPublicMessage(status))return status;
        if(Set.of("Совместный сеанс не открыт.","Совместный сеанс закрыт.",
                "Сохранение острова и подтверждение ведущего служебным узлом.",
                "Получение сетевых настроек общего острова.","Проверка совместимости сборки и игровых данных.",
                "Ожидание встроенного сервера и сохранение настроек доступа.","Сохранение, остановка острова и создание начального снимка.",
                "Подтверждение снимка и полномочий ведущего служебным узлом.","Повторное открытие сохранённого острова.",
                "Подключение подтверждённого ведущего к открытому острову.",
                "Не удалось подтвердить открытие общего острова; сохранения сохранены.",
                "Остров открыт. Личность, права и полномочия ведущего подтверждены.",
                "Проверка приглашения и ожидание ведущего.",
                "Подключение не подтверждено. Проверьте доступность ведущего и срок приглашения.",
                "Игровой канал закрыт. Повторный вход требует новой проверки.",
                "Защищённый канал готов. Minecraft проверяет допуск до загрузки игрока.",
                "Служебный узел не подтвердил подключение к острову.",
                "Личность подтверждена. Игровой мир подключён.",
                "Связь потеряна. Восстановление проверенного острова; команды повторно не отправляются.",
                "Время восстановления истекло. Можно подключиться заново.",
                "Ведущий пока недоступен. Повтор ограничен временем; можно отменить.",
                "Подготовленная копия сохранена; её открытие отменено.",
                "Совместный сеанс закрыт. Для одиночного редактирования сохраните и заново откройте остров.").contains(status)
                ||status.matches("Повторная проверка личности и сохранённого допуска\\. Попытка \\d{1,3}"))
            return publicText(status);
        return "Состояние острова уточняется.";
    }
    @Override public Map<String,String> darkwinter$labState(){
        JsonObject connection=selectedRow(),session=object(view,"session");String router=string(object(view,"room"),"routerState","");
        if(!List.of("running","failed","closed","unknown").contains(router))router="";
        Map<String,String> state=new LinkedHashMap<>();
        state.put("page",page.name());state.put("side","right");state.put("busy",Boolean.toString(flag(view,"busy",false)));
        state.put("connection_active",Boolean.toString(flag(connection,"active",false)));
        state.put("connection_reachable",Boolean.toString(flag(connection,"reachable",false)));
        state.put("member_count",Integer.toString(array(connection,"members").size()));
        state.put("session_active",Boolean.toString(flag(session,"active",false)));
        state.put("session_phase",safeSessionPhase(session));state.put("opening_stage",safeOpeningStage(session));
        state.put("session_status",safeSessionStatus(session));
        state.put("pending_joins",Integer.toString(array(view,"joinRequests").size()));
        state.put("room_router_state",router);state.put("status",publicText(feedback));
        return Collections.unmodifiableMap(state);
    }
    private abstract static class MaskedBox extends EditBox {
        private final String narration;
        MaskedBox(net.minecraft.client.gui.Font font,String label,int max){
            super(font,0,0,1,18,Component.literal(label));narration=label+". Содержимое скрыто.";setMaxLength(max);
            addFormatter((text,start)->FormattedCharSequence.forward("*".repeat(text.length()),Style.EMPTY));
        }
        @Override public String getValue(){return "*".repeat(super.getValue().length());}
        @Override public String getHighlighted(){return "";}
        @Override protected MutableComponent createNarrationMessage(){return Component.literal(narration);}
        boolean hasSecret(){return !super.getValue().isBlank();}
        String take(){String value=super.getValue();setValue("");return value;}
    }
    private static final class PasswordBox extends MaskedBox {PasswordBox(net.minecraft.client.gui.Font font){super(font,"Пароль соединения (скрыт)",128);}}
    private static final class InvitationCodeBox extends MaskedBox {InvitationCodeBox(net.minecraft.client.gui.Font font){super(font,"Код острова (скрыт)",16384);}}
}
