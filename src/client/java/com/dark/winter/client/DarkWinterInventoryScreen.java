package com.dark.winter.client;

import com.dark.winter.client.inventory.ClothingGuiIcons;
import com.dark.winter.client.inventory.InventoryActions;
import com.dark.winter.client.inventory.InventoryCatalog;
import com.dark.winter.client.inventory.InventoryCatalog.Category;
import com.dark.winter.client.inventory.InventoryCatalog.Order;
import com.dark.winter.client.inventory.InventoryItemDetails;
import com.dark.winter.client.inventory.InventoryLayout;
import com.dark.winter.client.inventory.InventoryLayout.Rect;
import com.dark.winter.client.inventory.InventoryPortrait;
import com.dark.winter.client.inventory.InventoryUseAction;
import com.dark.winter.client.inventory.ProvisionGuiPreview;
import com.dark.winter.client.lab.LabUiProvider;
import com.dark.winter.client.mixin.client.DarkWinterSlotAccessor;
import com.dark.winter.client.vector.InventoryGlyph;
import com.dark.winter.client.vector.MenuSurface;
import com.dark.winter.client.vector.SvgHud;
import com.dark.winter.client.weather.ClimateDisplay;
import com.dark.winter.client.weather.WeatherClient;
import com.dark.winter.equipment.EquipmentAccess;
import com.dark.winter.equipment.GearItem;
import com.dark.winter.equipment.GearSlot;
import com.dark.winter.equipment.ItemMass;
import com.dark.winter.registry.ModDataComponents;
import com.dark.winter.visuals.VisualTime;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.sdl.SDLMouse;

/** Approved three-page presentation over the original, server-authoritative player menu. */
public final class DarkWinterInventoryScreen extends AbstractContainerScreen<InventoryMenu> implements LabUiProvider {
    public enum Page {STATUS,CLOTHES,BACKPACK}
    private enum Kind {TAB,CATEGORY,GEAR,ITEM,CONDITION,ACTION,QUIET,SORT,PAGE}
    private record Control(String id,Kind kind,Rect bounds,Component label,InventoryGlyph glyph,boolean selected,Button button,int slot) {}
    private final int[] originalX,originalY;
    private final List<Control> controls=new ArrayList<>();
    private Page page=Page.STATUS;
    private Category category=Category.ALL;
    private Order order=Order.STORAGE;
    private GearSlot selectedGear=GearSlot.HAT,replacement;
    private int condition,selectedSlot=-1,bagPage;
    private boolean inspect;
    private List<Integer> visibleSlots=List.of();
    private InventoryLayout layout;
    private long contents;
    private Component notice=Component.empty();
    private long noticeUntil;

    public DarkWinterInventoryScreen(Player player) {
        super(player.inventoryMenu,player.getInventory(),Component.translatable("inventory.darkwinter.v2.title"),1920,1080);
        originalX=new int[menu.slots.size()];originalY=new int[menu.slots.size()];
        for(int i=0;i<menu.slots.size();i++){var slot=menu.slots.get(i);originalX[i]=slot.x;originalY[i]=slot.y;}
    }
    public static Component label(String key,Object... args){return Component.translatable("inventory.darkwinter.v2."+key,args);}
    private static Component literal(String value){return Component.literal(value);}
    private static InventoryGlyph gearGlyph(GearSlot slot){return switch(slot){
        case HAT->InventoryGlyph.CAP;case JACKET,TSHIRT,SWEATER->InventoryGlyph.SHIRT;case PANTS->InventoryGlyph.PANTS;
        case FOOTWEAR->InventoryGlyph.BOOTS;case GLOVES->InventoryGlyph.GLOVES;case SOCKS->InventoryGlyph.SOCKS;case BACKPACK->InventoryGlyph.PACK;};}
    private ItemStack worn(GearSlot slot){int id=GearSlot.MENU_START+slot.ordinal();return id<menu.slots.size()?menu.slots.get(id).getItem():ItemStack.EMPTY;}
    private ItemStack selection(){return selectedSlot>=9&&selectedSlot<menu.slots.size()?menu.slots.get(selectedSlot).getItem():ItemStack.EMPTY;}
    private static Component gearName(GearSlot slot){return Component.translatable("equipment.darkwinter."+slot.id());}

    @Override protected void init() {
        // CreativeModeInventoryScreen installs its own picker menu in its constructor.
        // This screen uses the normal player menu on both client and server.
        if(minecraft.player!=null)minecraft.player.containerMenu=menu;
        super.init();leftPos=0;topPos=0;layout=InventoryLayout.fit(width,height);
        // No native crafting/armor coordinates are exposed. Real slot identities and storage stay untouched.
        for(var slot:menu.slots){var access=(DarkWinterSlotAccessor)slot;access.darkwinter$setX(-10000);access.darkwinter$setY(-10000);}
        rebuildControls();
    }
    @Override protected void containerTick() {
        long next=InventoryCatalog.fingerprint(menu);
        if(next!=contents)rebuildControls();
    }
    private void page(Page next){page=next;inspect=false;rebuildControls();}
    private void message(String key){notice=label(key);noticeUntil=System.nanoTime()+2_500_000_000L;}
    private void control(String id,Kind kind,Rect box,Component title,InventoryGlyph glyph,boolean selected,boolean active,int slot,Runnable action) {
        var widget=new Button(layout.x(box.x()),layout.y(box.y()),layout.size(box.w()),layout.size(box.h()),title,b->action.run(),narration->narration.get()){
            @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt) { }
        };
        widget.active=active;widget.setTooltip(Tooltip.create(HudStyle.font(title)));addRenderableWidget(widget);
        controls.add(new Control(id,kind,box,title,glyph,selected,widget,slot));
    }
    private void action(String id,Component title,int x,int y,int w,boolean active,boolean primary,Runnable run){
        control(id,primary?Kind.ACTION:Kind.QUIET,new Rect(x,y,w,49),title,null,false,active,-1,run);
    }
    private void rebuildControls() {
        clearWidgets();controls.clear();if(layout==null||minecraft.player==null)return;
        for(Page value:Page.values()){int i=value.ordinal();control("page-"+value.name().toLowerCase(),Kind.TAB,InventoryLayout.tab(i),label("page."+value.name().toLowerCase()),
                i==0?InventoryGlyph.PERSON:i==1?InventoryGlyph.SHIRT:InventoryGlyph.PACK,page==value,true,-1,()->page(value));}
        if(minecraft.player.hasInfiniteMaterials())action("creative",label("creative"),1360,52,165,true,false,this::openCreative);
        boolean emptyCursor=menu.getCarried().isEmpty();
        if(page==Page.STATUS){
            for(int i=0;i<4;i++){int index=i;control("condition-"+i,Kind.CONDITION,new Rect(971,267+i*90,310,78),conditionTitle(i),conditionGlyph(i),condition==i,true,-1,()->{condition=index;rebuildControls();});}
        }else if(page==Page.CLOTHES){
            addGear(GearSlot.HAT,68,237,131,112);addGear(GearSlot.TSHIRT,68,398,131,112);addGear(GearSlot.SWEATER,211,398,131,112);
            addGear(GearSlot.JACKET,68,522,131,112);addGear(GearSlot.GLOVES,68,684,131,112);addGear(GearSlot.BACKPACK,981,237,116,121);
            addGear(GearSlot.PANTS,981,408,116,121);addGear(GearSlot.SOCKS,981,578,116,121);addGear(GearSlot.FOOTWEAR,1109,578,116,121);
            boolean has=!worn(selectedGear).isEmpty();
            action("take-off",label("take_off"),1310,867,171,has&&emptyCursor&&InventoryCatalog.firstEmpty(menu)>=0,false,()->{if(!InventoryActions.remove(menu,selectedGear))message("no_space");rebuildControls();});
            boolean spare=!InventoryCatalog.slots(menu,Category.CLOTHES,Order.STORAGE,selectedGear).isEmpty();
            action("replace",label("replace"),1492,867,171,spare&&emptyCursor,true,()->{replacement=selectedGear;category=Category.CLOTHES;bagPage=0;selectedSlot=-1;page(Page.BACKPACK);});
            action("inspect-gear",label("inspect"),1674,867,171,has,false,()->inspect=!inspect);
        }else{
            visibleSlots=InventoryCatalog.slots(menu,category,order,replacement);int pages=Math.max(1,(visibleSlots.size()+19)/20);bagPage=Math.clamp(bagPage,0,pages-1);
            if(!visibleSlots.contains(selectedSlot))selectedSlot=visibleSlots.isEmpty()?-1:visibleSlots.getFirst();
            for(Category value:Category.values())control("category-"+value.id,Kind.CATEGORY,InventoryLayout.category(value.ordinal()),label("category."+value.id),value.glyph,category==value,true,-1,()->{
                category=value;replacement=null;selectedSlot=-1;bagPage=0;rebuildControls();});
            int firstEmpty=InventoryCatalog.firstEmpty(menu);
            for(int i=0;i<20;i++){
                int k=bagPage*20+i,id=k<visibleSlots.size()?visibleSlots.get(k):-1;
                if(id>=0){ItemStack stack=menu.slots.get(id).getItem();control("item-"+id,Kind.ITEM,InventoryLayout.item(i),stack.getHoverName(),null,selectedSlot==id,true,id,()->{
                    if(!menu.getCarried().isEmpty())InventoryActions.click(menu,id,0,ContainerInput.PICKUP);else selectedSlot=id;inspect=false;rebuildControls();});}
                else if(!emptyCursor&&firstEmpty>=0){int target=firstEmpty;control("empty-"+i,Kind.ITEM,InventoryLayout.item(i),label("place_cursor"),null,false,true,target,()->{InventoryActions.click(menu,target,0,ContainerInput.PICKUP);rebuildControls();});break;}
            }
            for(Order value:Order.values()){int i=value.ordinal();control("sort-"+value.name().toLowerCase(),Kind.SORT,new Rect(685+i*115,914,107,39),label("sort."+value.name().toLowerCase()),null,order==value,true,-1,()->{order=value;bagPage=0;rebuildControls();});}
            if(pages>1){control("prev-page",Kind.PAGE,new Rect(1132,982,40,35),literal("‹"),null,false,bagPage>0,-1,()->{bagPage--;rebuildControls();});control("next-page",Kind.PAGE,new Rect(1221,982,40,35),literal("›"),null,false,bagPage<pages-1,-1,()->{bagPage++;rebuildControls();});}
            ItemStack selected=selection();boolean has=!selected.isEmpty();
            action("use-item",primaryLabel(selected),1313,962,169,has&&emptyCursor,true,this::primary);
            action("inspect-item",label("inspect"),1493,962,169,has,false,()->inspect=!inspect);
            action("drop-item",label("drop"),1673,962,170,has&&emptyCursor,false,()->{InventoryActions.drop(menu,selectedSlot,false);inspect=false;rebuildControls();});
            if(replacement!=null)control("cancel-replacement",Kind.QUIET,new Rect(570,975,400,35),label("cancel_replacement"),null,false,true,-1,()->{replacement=null;rebuildControls();});
        }
        contents=InventoryCatalog.fingerprint(menu);
    }
    private void addGear(GearSlot slot,int x,int y,int w,int h){
        ItemStack stack=worn(slot);control("gear-"+slot.id(),Kind.GEAR,new Rect(x,y,w,h),stack.isEmpty()?gearName(slot):stack.getHoverName(),gearGlyph(slot),selectedGear==slot,true,GearSlot.MENU_START+slot.ordinal(),()->{selectedGear=slot;inspect=false;rebuildControls();});
    }
    private void openCreative(){
        if(!minecraft.player.hasInfiniteMaterials())return;DarkWinterInventoryHooks.allowCreativeScreen();
        minecraft.gui.setScreen(new CreativeModeInventoryScreen(minecraft.player,minecraft.player.connection.enabledFeatures(),minecraft.options.operatorItemsTab().get()));
    }
    private Component primaryLabel(ItemStack stack){
        if(stack.getItem() instanceof GearItem)return label("equip");
        if(InventoryUseAction.consumable(stack))return label(stack.get(DataComponents.CONSUMABLE).animation().name().equals("DRINK")?"drink":"eat");
        return label("take_hand");
    }
    private void primary(){
        var stack=selection();if(stack.isEmpty())return;
        if(stack.getItem() instanceof GearItem item){selectedGear=item.slot();if(InventoryActions.equip(menu,selectedSlot)){replacement=null;page(Page.CLOTHES);}else message("busy_cursor");}
        else {boolean consume=InventoryUseAction.consumable(stack);if(InventoryActions.hand(menu,selectedSlot)){onClose();if(consume)InventoryUseAction.begin();}}
    }
    @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float dt){
        MenuStyle.landscape(g,width,height);g.fill(0,0,width,height,0xAD03090E);
    }
    @Override public void extractContents(GuiGraphicsExtractor g,int mx,int my,float dt){
        // Widgets only maintain native hover/focus/narration. Their approved appearance is painted below.
        for(var c:controls)c.button.extractRenderState(g,mx,my,dt);
        InventoryPortrait.extract(g,layout,page==Page.BACKPACK);g.nextStratum();
        g.pose().pushMatrix();g.pose().translate(layout.left(),layout.top());g.pose().scale(layout.scale(),layout.scale());
        common(g);if(page==Page.STATUS)status(g);else if(page==Page.CLOTHES)clothes(g);else backpack(g);
        for(var c:controls)paint(g,c);
        if(System.nanoTime()<noticeUntil){surface(g,590,962,740,49,MenuSurface.TOOLTIP,0);center(g,notice,960,975,23,HudStyle.INK);}
        g.pose().popMatrix();hoveredSlot=null;
    }
    @Override protected void extractTooltip(GuiGraphicsExtractor g,int mx,int my){
        for(var c:controls)if(c.button.isHovered()&&c.slot>=0&&c.slot<menu.slots.size()&&menu.slots.get(c.slot).hasItem()){
            g.setTooltipForNextFrame(minecraft.font,menu.slots.get(c.slot).getItem(),mx,my);return;
        }
    }
    private void common(GuiGraphicsExtractor g){
        t(g,label("page."+page.name().toLowerCase()),68,48,38,HudStyle.INK);
        t(g,label(page==Page.STATUS?"subtitle.status":page==Page.CLOTHES?"subtitle.clothes":"subtitle.backpack"),70,99,15,HudStyle.MUTED);
        line(g,64,133,1856,0x426A8E9D);
        if(minecraft.level!=null){var date=VisualTime.snapshot(minecraft.level,0);right(g,literal(date.timeText()),1850,48,27,HudStyle.INK);right(g,DateTimeHud.calendarText(date),1850,85,16,HudStyle.MUTED);}
        glyph(g,InventoryGlyph.CLOUD,1668,60,29,HudStyle.MUTED);
        t(g,label("footer"),70,1035,16,HudStyle.MUTED);
        right(g,label("north"),1850,1035,16,HudStyle.MUTED);
    }
    private static void t(GuiGraphicsExtractor g,Component text,float x,float y,float size,int color){HudStyle.text(g,HudStyle.strong(text),x,y,size/11f,color);}
    private static void fit(GuiGraphicsExtractor g,Component text,float x,float y,int w,float size,int color){HudStyle.fit(g,HudStyle.strong(text),x,y,w,size/11f,color);}
    private static void right(GuiGraphicsExtractor g,Component text,float x,float y,float size,int color){t(g,text,x-HudStyle.width(text)*size/11f,y,size,color);}
    private static void center(GuiGraphicsExtractor g,Component text,int x,int y,float size,int color){HudStyle.centered(g,HudStyle.strong(text),x,y,size/11f,color);}
    private static void glyph(GuiGraphicsExtractor g,InventoryGlyph glyph,float x,float y,float size,int color){SvgHud.inventoryIcon(g,glyph,x,y,size,color);}
    private static void line(GuiGraphicsExtractor g,int x,int y,int right,int color){SvgHud.surface(g,x,y,right-x,1,MenuSurface.TRACK,0,color);}
    private static void surface(GuiGraphicsExtractor g,int x,int y,int w,int h,MenuSurface material,float light){SvgHud.surface(g,x,y,w,h,material,light,0xA089A8B8);}
    private static void item(GuiGraphicsExtractor g,ItemStack stack,int x,int y,int w,int h,boolean zoom){
        if(stack.isEmpty())return;
        if(ClothingGuiIcons.extract(g,stack,x,y,w,h,zoom))return;
        if(!zoom && ProvisionGuiPreview.supports(stack)){
            var foodCell=InventoryLayout.item(0);
            if(w==foodCell.w()-16 && h==foodCell.h()-38){
                // Existing ITEM insets are 8/7 and the old text reservation is 38. Use the actual free
                // rectangle for food, preserving the bottom line only when it has condition/mass text.
                boolean hasBottomText=InventoryCatalog.condition(stack)>=0 || stack.has(ModDataComponents.MASS_GRAMS);
                float freeHeight=foodCell.h()-10-(hasBottomText?21:0);
                if(ProvisionGuiPreview.extractInRectangle(g,stack,x-3,y-2,foodCell.w()-10,freeHeight))return;
            }
        }
        float size=Math.min(w,h)*(zoom?1.03f:.82f);
        if(ProvisionGuiPreview.extract(g,stack,x+(w-size)/2f,y+(h-size)/2f,size))return;
        g.pose().pushMatrix();g.pose().translate(x+(w-size)/2f,y+(h-size)/2f);g.pose().scale(size/16f,size/16f);g.item(stack,0,0);g.pose().popMatrix();
    }
    private void paint(GuiGraphicsExtractor g,Control c){
        Rect b=c.bounds;boolean focus=c.button.isHoveredOrFocused(),active=c.button.active;float light=c.selected?.65f:focus?.4f:0;
        int ink=active?HudStyle.INK:HudStyle.MUTED;
        switch(c.kind){
            case TAB->{surface(g,b.x(),b.y(),b.w(),b.h(),MenuSurface.SIDEBAR,light);if(c.selected)line(g,b.x(),b.y()+b.h()-1,b.x()+b.w(),HudStyle.STABLE);glyph(g,c.glyph,b.x()+31,b.y()+19,28,c.selected?HudStyle.INK:HudStyle.MUTED);fit(g,c.label,b.x()+72,b.y()+24,b.w()-81,23,c.selected?HudStyle.INK:HudStyle.MUTED);}
            case CATEGORY->{surface(g,b.x(),b.y(),b.w(),b.h(),MenuSurface.BUTTON,light);glyph(g,c.glyph,b.x()+16,b.y()+20,30,c.selected?HudStyle.INK:HudStyle.MUTED);}
            case CONDITION->{surface(g,b.x(),b.y(),b.w(),b.h(),MenuSurface.SIDEBAR,light);glyph(g,c.glyph,b.x()+17,b.y()+25,28,c.selected?HudStyle.STABLE:HudStyle.MUTED);fit(g,c.label,b.x()+64,b.y()+20,b.w()-75,24,ink);}
            case GEAR->{surface(g,b.x(),b.y(),b.w(),b.h(),c.selected?MenuSurface.SELECTED:MenuSurface.FIELD,light);ItemStack stack=menu.slots.get(c.slot).getItem();
                if(stack.isEmpty())glyph(g,c.glyph,b.x()+b.w()/2f-20,b.y()+24,40,0x606D8B9C);else{item(g,stack,b.x()+9,b.y()+6,b.w()-18,b.h()-27,false);glyph(g,InventoryGlyph.CHECK,b.x()+8,b.y()+8,14,HudStyle.STABLE);}
                fit(g,c.label,b.x()+6,b.y()+b.h()-20,b.w()-12,15,HudStyle.MUTED);}
            case ITEM->{surface(g,b.x(),b.y(),b.w(),b.h(),c.selected?MenuSurface.SELECTED:MenuSurface.FIELD,light);ItemStack stack=menu.slots.get(c.slot).getItem();item(g,stack,b.x()+8,b.y()+7,b.w()-16,b.h()-38,false);
                if(stack.getCount()>1)right(g,literal("×"+stack.getCount()),b.x()+b.w()-8,b.y()+8,17,HudStyle.INK);
                float condition=InventoryCatalog.condition(stack);if(condition>=0)t(g,literal(Math.round(condition)+"%"),b.x()+8,b.y()+b.h()-23,16,condition<35?HudStyle.AMBER:HudStyle.MUTED);
                if(stack.has(ModDataComponents.MASS_GRAMS))right(g,literal(ItemMass.kilograms(ItemMass.grams(stack))),b.x()+b.w()-8,b.y()+b.h()-23,16,HudStyle.MUTED);}
            case SORT->{fit(g,c.label,b.x()+5,b.y()+10,b.w()-10,18,c.selected?HudStyle.INK:HudStyle.MUTED);if(c.selected)line(g,b.x()+6,b.y()+b.h()-2,b.x()+b.w()-6,HudStyle.MUTED);}
            default->{surface(g,b.x(),b.y(),b.w(),b.h(),!active?MenuSurface.DISABLED:c.kind==Kind.ACTION?MenuSurface.PRIMARY:MenuSurface.BUTTON,light);fit(g,c.label,b.x()+10,b.y()+15,b.w()-20,22,ink);}
        }
        if(c.button.isFocused()&&active)line(g,b.x()+3,b.y()+b.h()+2,b.x()+b.w()-3,HudStyle.STABLE);
    }
    private static String pct(float value){return Math.round(Math.clamp(value,0,100))+"%";}
    private static Component degree(float value){return literal(String.format(java.util.Locale.ROOT,"%+.1f°C",value).replace('.',','));}
    private void row(GuiGraphicsExtractor g,InventoryGlyph icon,Component name,Component value,int x,int y,int w){glyph(g,icon,x,y,23,HudStyle.MUTED);fit(g,name,x+36,y+2,w-155,21,HudStyle.MUTED);right(g,value,x+w,y,24,HudStyle.INK);}
    private Component conditionTitle(int index){return switch(index){
        case 0->label(SurvivalNeeds.getTemperature()<40?"cold":"warm");case 1->label("health");case 2->label("nutrition");default->label("equipment");};}
    private static InventoryGlyph conditionGlyph(int index){return switch(index){case 0->InventoryGlyph.THERMOMETER;case 1->InventoryGlyph.MEDICAL;case 2->InventoryGlyph.FOOD;default->InventoryGlyph.SHIRT;};}
    private void climate(GuiGraphicsExtractor g,int x,int y,int w){
        surface(g,x,y,w,200,MenuSurface.PANEL,0);t(g,label("environment"),x+22,y+21,15,HudStyle.MUTED);
        t(g,label("air"),x+22,y+61,19,HudStyle.MUTED);t(g,label("wind_chill"),x+22,y+102,19,HudStyle.MUTED);t(g,label("feels"),x+22,y+153,19,HudStyle.MUTED);
        if(WeatherClient.active()) {var climate=WeatherClient.climate(0);right(g,degree(climate.airTemperatureC()),x+w-22,y+57,24,HudStyle.INK);right(g,degree(climate.feelsLikeC(1)),x+w-22,y+98,24,HudStyle.INK);right(g,degree(climate.feelsLikeC(ClimateDisplay.exposure())),x+w-22,y+149,24,HudStyle.STABLE);}
        else for(int dy:new int[]{57,98,149})right(g,literal("—"),x+w-22,y+dy,24,HudStyle.MUTED);
        line(g,x+22,y+135,x+w-22,0x3D6B8C9E);
    }
    private void health(GuiGraphicsExtractor g,boolean pack){
        int x=pack?86:442,y=pack?904:1004,w=pack?329:460;glyph(g,InventoryGlyph.HEART,x,y+6,23,HudStyle.INK);
        surface(g,x+35,y+14,w-85,5,MenuSurface.TRACK,0);SvgHud.surface(g,x+35,y+14,Math.max(1,Math.round((w-85)*minecraft.player.getHealth()/Math.max(1,minecraft.player.getMaxHealth()))),5,MenuSurface.THUMB,0,HudStyle.STABLE);
        right(g,literal(pct(100* minecraft.player.getHealth()/Math.max(1,minecraft.player.getMaxHealth()))),x+w,y+3,18,HudStyle.MUTED);
    }
    private void meter(GuiGraphicsExtractor g,int index,InventoryGlyph icon,String title,String desc,float value){
        int y=246+index*128;t(g,label(title),70,y+42,25,HudStyle.INK);t(g,label(desc),70,y+77,17,HudStyle.MUTED);
        SvgHud.arc(g,249,y+18,84,1,0x40445E6B,false);SvgHud.arc(g,249,y+18,84,Math.clamp(value/100,0,1),HudStyle.STABLE,false);
        glyph(g,icon,274,y+43,34,HudStyle.INK);right(g,literal(pct(value)),326,y+91,15,HudStyle.STABLE);
    }
    private void status(GuiGraphicsExtractor g){
        t(g,label("body"),70,218,16,HudStyle.MUTED);
        meter(g,0,InventoryGlyph.THERMOMETER,SurvivalNeeds.getTemperature()<40?"cold":"warm","heat_reserve",SurvivalNeeds.getTemperature());
        meter(g,1,InventoryGlyph.REST,"rested","rest_reserve",100-SurvivalNeeds.getFatigue());meter(g,2,InventoryGlyph.DROP,"hydrated","water_reserve",100-SurvivalNeeds.getThirst());
        meter(g,3,InventoryGlyph.FOOD,"nutrition","food_reserve",100-SurvivalNeeds.getHunger());
        t(g,label("stamina"),71,749,16,HudStyle.MUTED);right(g,literal(pct(100*SprintStamina.getStamina()/Math.max(1,SprintStamina.getMaxStamina()))),356,746,21,HudStyle.INK);
        climate(g,66,786,296);health(g,false);t(g,label("conditions"),988,224,16,HudStyle.MUTED);
        t(g,conditionTitle(condition),1355,222,35,HudStyle.INK);t(g,label("condition.heading."+condition),1355,278,16,HudStyle.MUTED);
        HudStyle.paragraph(g,label("condition.description."+condition),1355,331,492,23/11f,HudStyle.MUTED,4);line(g,1355,509,1847,0x42476574);
        if(condition==0){row(g,InventoryGlyph.THERMOMETER,label("heat_reserve"),literal(pct(SurvivalNeeds.getTemperature())),1355,550,492);row(g,InventoryGlyph.WIND,label("wind_exposure"),literal(pct(ClimateDisplay.exposure()*100)),1355,609,492);row(g,InventoryGlyph.CONDITION,label("condition"),averageCondition(),1355,668,492);row(g,InventoryGlyph.WEIGHT,label("worn_mass"),massText(ItemMass.grams(EquipmentAccess.of(minecraft.player))),1355,727,492);}
        else if(condition==1){row(g,InventoryGlyph.HEART,label("health"),literal(pct(minecraft.player.getHealth()*100/Math.max(1,minecraft.player.getMaxHealth()))),1355,550,492);row(g,InventoryGlyph.SPRINT,label("stamina"),literal(pct(SprintStamina.getStamina()*100/Math.max(1,SprintStamina.getMaxStamina()))),1355,609,492);row(g,InventoryGlyph.MEDICAL,label("active_effects"),literal(Integer.toString(minecraft.player.getActiveEffects().size())),1355,668,492);}
        else if(condition==2){row(g,InventoryGlyph.FOOD,label("food_reserve"),literal(pct(100-SurvivalNeeds.getHunger())),1355,550,492);row(g,InventoryGlyph.DROP,label("water_reserve"),literal(pct(100-SurvivalNeeds.getThirst())),1355,609,492);row(g,InventoryGlyph.REST,label("rest_reserve"),literal(pct(100-SurvivalNeeds.getFatigue())),1355,668,492);}
        else{row(g,InventoryGlyph.SHIRT,label("worn_items"),literal(Integer.toString(wornCount())),1355,550,492);row(g,InventoryGlyph.CONDITION,label("condition"),averageCondition(),1355,609,492);row(g,InventoryGlyph.WEIGHT,label("worn_mass"),massText(ItemMass.grams(EquipmentAccess.of(minecraft.player))),1355,668,492);}
    }
    private int wornCount(){int count=0;for(GearSlot slot:GearSlot.values())if(!worn(slot).isEmpty())count++;return count;}
    private Component averageCondition(){float value=0;int count=0;for(GearSlot slot:GearSlot.values()){float c=InventoryCatalog.condition(worn(slot));if(!worn(slot).isEmpty()&&c>=0){value+=c;count++;}}return count==0?literal("—"):literal(pct(value/count));}
    private static Component massText(long grams){return label("mass_value",ItemMass.kilograms(grams));}
    private void clothes(GuiGraphicsExtractor g){
        t(g,label("head"),68,207,16,HudStyle.MUTED);t(g,label("torso"),68,372,16,HudStyle.MUTED);t(g,label("hands"),68,656,16,HudStyle.MUTED);
        t(g,label("accessories"),981,207,16,HudStyle.MUTED);t(g,label("legs"),981,379,16,HudStyle.MUTED);t(g,label("feet"),981,548,16,HudStyle.MUTED);
        surface(g,66,844,296,168,MenuSurface.PANEL,0);t(g,label("summary"),86,863,16,HudStyle.MUTED);
        summary(g,label("condition"),averageCondition(),895);summary(g,label("worn_mass"),massText(ItemMass.grams(EquipmentAccess.of(minecraft.player))),929);
        summary(g,label("worn_items"),literal(Integer.toString(wornCount())),963);
        ItemStack stack=worn(selectedGear);fit(g,stack.isEmpty()?gearName(selectedGear):stack.getHoverName(),1310,215,535,34,HudStyle.INK);
        t(g,gearName(selectedGear),1310,266,17,HudStyle.MUTED);
        HudStyle.paragraph(g,stack.isEmpty()?label("empty_gear_description"):InventoryItemDetails.description(stack),1310,301,535,20/11f,HudStyle.MUTED,4);
        if(stack.isEmpty())glyph(g,gearGlyph(selectedGear),1480,422,190,0x566D8B9C);else item(g,stack,1364,403,428,230,inspect);
        t(g,label(stack.isEmpty()?"not_worn":"worn"),1310,650,17,HudStyle.MUTED);right(g,label("layer."+selectedGear.id()),1845,650,17,HudStyle.MUTED);
        detailStats(g,stack,1310,850,535);List<Integer> options=InventoryCatalog.slots(menu,Category.CLOTHES,Order.STORAGE,selectedGear);
        line(g,1310,934,1845,0x42476574);t(g,label("replacement"),1310,957,17,HudStyle.MUTED);
        fit(g,options.isEmpty()?label("no_replacement"):menu.slots.get(options.getFirst()).getItem().getHoverName(),1450,957,395,17,HudStyle.INK);
        center(g,label("clothes_footer"),680,1003,16,HudStyle.MUTED);
    }
    private void summary(GuiGraphicsExtractor g,Component name,Component value,int y){fit(g,name,86,y,160,19,HudStyle.MUTED);right(g,value,342,y,22,HudStyle.INK);}
    /** Bottom-anchored, labelled cells keep every property clear of the action buttons. */
    private int detailStats(GuiGraphicsExtractor g,ItemStack stack,int x,int bottom,int w){
        if(stack.isEmpty()){
            int y=bottom-71;surface(g,x,y,w,71,MenuSurface.PANEL,0);
            center(g,label("empty_slot"),x+w/2,y+27,22,HudStyle.MUTED);return y;
        }
        var clothing=InventoryItemDetails.clothing(stack);
        float condition=InventoryCatalog.condition(stack);
        boolean hasCondition=condition>=0,hasMass=stack.has(ModDataComponents.MASS_GRAMS);
        boolean hasWater=stack.has(ModDataComponents.WATER_RESISTANCE);
        boolean hasInsulation=!(stack.getItem() instanceof GearItem)&&stack.getOrDefault(ModDataComponents.INSULATION,0)>0;
        int count=clothing!=null?6:(hasCondition?1:0)+(hasMass?1:0)+(hasWater?1:0)+(hasInsulation?1:0);
        if(count==0)return bottom;
        int height=((count+1)/2)*53+18,y=bottom-height;
        surface(g,x,y,w,height,MenuSurface.PANEL,0);
        if(clothing!=null){
            detailStat(g,InventoryGlyph.THERMOMETER,"warmth",warmthText(clothing.warmthCelsius()),0,x,y,w);
            detailStat(g,InventoryGlyph.WIND,"wind",literal(pct(clothing.windResistance())),1,x,y,w);
            detailStat(g,InventoryGlyph.DROP,"water",literal(pct(clothing.waterResistance())),2,x,y,w);
            detailStat(g,InventoryGlyph.SPRINT,"mobility",literal(pct(clothing.mobilityPenalty())),3,x,y,w);
            detailStat(g,InventoryGlyph.CONDITION,"condition",hasCondition?literal(pct(condition)):literal("—"),4,x,y,w);
            detailStat(g,InventoryGlyph.WEIGHT,"mass",hasMass?massText(ItemMass.grams(stack)):literal("—"),5,x,y,w);
        }else{
            int cell=0;
            if(hasCondition)detailStat(g,InventoryGlyph.CONDITION,"condition",literal(pct(condition)),cell++,x,y,w);
            if(hasMass)detailStat(g,InventoryGlyph.WEIGHT,"mass",massText(ItemMass.grams(stack)),cell++,x,y,w);
            if(hasWater)detailStat(g,InventoryGlyph.DROP,"water",literal(pct(stack.get(ModDataComponents.WATER_RESISTANCE))),cell++,x,y,w);
            if(hasInsulation)detailStat(g,InventoryGlyph.THERMOMETER,"insulation",literal(Integer.toString(stack.get(ModDataComponents.INSULATION))),cell,x,y,w);
        }
        return y;
    }
    private static Component warmthText(float value){
        String number=value==(int)value?Integer.toString((int)value):Float.toString(value);
        if(Minecraft.getInstance().options.languageCode.startsWith("ru"))number=number.replace('.',',');
        return literal((value>=0?"+":"")+number+" °C");
    }
    private static void detailStat(GuiGraphicsExtractor g,InventoryGlyph icon,String name,Component value,int cell,int x,int y,int w){
        int cellWidth=(w-52)/2,cx=x+18+(cell%2)*(cellWidth+16),cy=y+12+(cell/2)*53;
        glyph(g,icon,cx,cy+15,23,HudStyle.MUTED);
        fit(g,label("stat."+name),cx+36,cy+1,cellWidth-36,16,HudStyle.MUTED);
        fit(g,value,cx+36,cy+23,cellWidth-36,22,HudStyle.INK);
    }
    private void backpack(GuiGraphicsExtractor g){
        int occupied=0;for(int i=9;i<=44;i++)if(menu.slots.get(i).hasItem())occupied++;
        health(g,true);surface(g,66,933,368,96,MenuSurface.PANEL,0);t(g,label("known_mass"),86,951,18,HudStyle.MUTED);right(g,massText(ItemMass.carriedGrams(minecraft.player)),414,949,24,HudStyle.INK);
        surface(g,86,985,328,5,MenuSurface.TRACK,0);SvgHud.surface(g,86,985,Math.max(1,328*occupied/36),5,MenuSurface.THUMB,0,HudStyle.STABLE);
        t(g,label("occupied",occupied,36),86,1001,16,HudStyle.MUTED);right(g,massText(ItemMass.grams(EquipmentAccess.of(minecraft.player))),414,1001,16,HudStyle.MUTED);
        fit(g,replacement==null?label("category."+category.id):label("replacement_for",gearName(replacement)),570,211,420,26,HudStyle.INK);
        long mass=0;for(int id:visibleSlots)mass+=ItemMass.grams(menu.slots.get(id).getItem());right(g,label("category_total",visibleSlots.size(),ItemMass.kilograms(mass)),1218,220,18,HudStyle.MUTED);
        int filled=Math.min(20,Math.max(0,visibleSlots.size()-bagPage*20));
        for(int i=filled;i<20;i++){Rect b=InventoryLayout.item(i);surface(g,b.x(),b.y(),b.w(),b.h(),MenuSurface.FIELD,0);}
        if(visibleSlots.isEmpty())center(g,label("empty_category"),895,540,25,HudStyle.MUTED);
        t(g,label("sorting"),570,926,17,HudStyle.MUTED);int pages=Math.max(1,(visibleSlots.size()+19)/20);if(pages>1)center(g,literal((bagPage+1)+" / "+pages),1196,989,19,HudStyle.MUTED);
        ItemStack stack=selection();fit(g,stack.isEmpty()?label("choose_item"):stack.getHoverName(),1313,211,530,34,HudStyle.INK);
        t(g,label("category."+category.id),1313,266,17,HudStyle.MUTED);
        if(!stack.isEmpty()){
            HudStyle.paragraph(g,InventoryItemDetails.description(stack),1313,301,530,20/11f,HudStyle.MUTED,4);
            int statsTop=detailStats(g,stack,1313,948,530),countY=statsTop-36;
            item(g,stack,1340,403,478,countY-427,inspect);
            center(g,label("count",stack.getCount()),1578,countY,21,HudStyle.INK);
        }
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){
        for(var c:controls)if(c.button.active&&c.slot>=9&&c.button.isMouseOver(event.x(),event.y())){
            if(event.button()==SDLMouse.SDL_BUTTON_RIGHT){if(c.kind==Kind.ITEM){selectedSlot=c.slot;inspect=false;rebuildControls();return true;}}
            if(event.button()==SDLMouse.SDL_BUTTON_LEFT&&event.hasShiftDown()&&menu.getCarried().isEmpty()){
                InventoryActions.click(menu,c.slot,0,ContainerInput.QUICK_MOVE);rebuildControls();return true;
            }
            if(event.button()==SDLMouse.SDL_BUTTON_LEFT&&doubleClick&&c.kind==Kind.ITEM&&menu.getCarried().isEmpty()){
                selectedSlot=c.slot;primary();return true;
            }
        }
        return super.mouseClicked(event,doubleClick);
    }
    @Override public boolean mouseScrolled(double x,double y,double sx,double sy){
        if(page==Page.BACKPACK&&visibleSlots.size()>20&&sy!=0){bagPage+=sy<0?1:-1;rebuildControls();return true;}return false;
    }
    @Override public boolean keyPressed(KeyEvent event){
        if(minecraft.options.keyInventory.matches(event)){onClose();return true;}
        int key=event.key();
        if(!event.hasControlDown()&&!event.hasShiftDown()&&key>=InputConstants.KEY_1&&key<=InputConstants.KEY_3){page(Page.values()[key-InputConstants.KEY_1]);return true;}
        if(page==Page.BACKPACK&&selectedSlot>=9&&menu.getCarried().isEmpty()){
            if(minecraft.options.keyDrop.matches(event)){InventoryActions.drop(menu,selectedSlot,event.hasControlDown());rebuildControls();return true;}
            if(event.hasShiftDown())for(int i=0;i<9;i++)if(minecraft.options.keyHotbarSlots[i].matches(event)){
                InventoryActions.click(menu,selectedSlot,i,ContainerInput.SWAP);rebuildControls();return true;
            }
        }
        return super.keyPressed(event);
    }
    @Override protected boolean hasClickedOutside(double mx,double my,int x,int y){return mx<0||my<0||mx>=width||my>=height;}
    @Override public void removed(){
        for(int i=0;i<menu.slots.size()&&i<originalX.length;i++){var access=(DarkWinterSlotAccessor)menu.slots.get(i);access.darkwinter$setX(originalX[i]);access.darkwinter$setY(originalY[i]);}
        super.removed();
    }
    @Override public Map<String,String> darkwinter$labState(){return Map.of("page",page.name(),"category",category.name(),"selectedSlot",Integer.toString(selectedSlot),"gear",selectedGear.name(),"bagPage",Integer.toString(bagPage),"fixedPortrait","true");}
    @Override public List<Region> darkwinter$labRegions(){return controls.stream().filter(c->c.button.active).map(c->new Region(c.id,c.label.getString(),"click",c.button.getX(),c.button.getY(),c.button.getWidth(),c.button.getHeight(),c.selected)).toList();}
}
