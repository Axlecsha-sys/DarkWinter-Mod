package com.dark.winter.client.interaction;

import com.dark.winter.block.DiningChairBlock;
import com.dark.winter.client.DebugOverlayVisibility;
import com.dark.winter.client.HudStyle;
import com.dark.winter.client.SurvivalNeedsHud;
import com.dark.winter.client.TestingAccess;
import com.dark.winter.client.vector.InventoryGlyph;
import com.dark.winter.client.vector.MenuSurface;
import com.dark.winter.client.vector.SvgHud;
import com.dark.winter.interaction.DeadwoodProgress;
import com.dark.winter.interaction.DiningChairInteraction;
import com.dark.winter.interaction.DeadwoodRequest;
import com.dark.winter.interaction.DeadwoodSounds;
import com.dark.winter.interaction.DeadwoodTarget;
import com.dark.winter.interaction.PanelDoorInteraction;
import com.dark.winter.interaction.HousePassage;
import com.dark.winter.interaction.CurtainInteraction;
import com.dark.winter.interaction.FenceGateInteraction;
import com.dark.winter.interaction.StorageFurnitureInteraction;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Interaction presentation only: the server controls time, pile removal and the inventory reward. */
public final class DeadwoodClient {
    private static final KeyMapping.Category CATEGORY=KeyMapping.Category.register(Identifier.fromNamespaceAndPath("darkwinter","interaction"));
    private static KeyMapping gather;
    private static ClientLevel level;
    private static UUID player;
    private static int counter,serial,elapsed,duration;
    private static long receivedTick,requestedAt,hoverSince;
    private static BlockPos origin,hoverOrigin;
    private static String hoverPrompt;
    private static boolean running,releaseRequired;
    private static Component notice=Component.empty();
    private static long noticeUntil;
    private static long pickupAt;
    private static GatheringSound sound;
    private DeadwoodClient() {}
    public static void initialize() {
        PanelDoorRenderer.initialize();
        HousePassageClient.initialize();
        CurtainRenderer.initialize();
        FenceGateRenderer.initialize();
        StorageFurnitureRenderer.initialize();
        DiningChairRenderer.initialize();
        gather=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.darkwinter.gather_deadwood",InputConstants.Type.KEYBOARD,InputConstants.KEY_E,CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(DeadwoodClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->clear(false));
        ClientPlayNetworking.registerGlobalReceiver(DeadwoodProgress.TYPE,(packet,context)->context.client().execute(()->receive(packet)));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("darkwinter","deadwood_interaction"),
                (graphics,ticks)->render(graphics,ticks.getGameTimeDeltaPartialTick(false)));
    }
    private static void tick(Minecraft client) {
        UUID current=client.player==null?null:client.player.getUUID();
        if(level!=client.level||!java.util.Objects.equals(player,current)){
            clear(false);level=client.level;player=current;noticeUntil=0;pickupAt=0;hoverOrigin=null;
            releaseRequired=gather.isDown();
        }
        boolean held=gather.isDown();
        boolean pressed=gather.consumeClick();
        while(gather.consumeClick()){} // Harvest still follows the held key.
        if(!held)releaseRequired=false;
        boolean available=client.player!=null&&client.level!=null&&client.player.isAlive()&&!client.player.isSpectator()
                &&client.gui.screen()==null&&!client.isPaused();
        if(!available){clear(true);hoverOrigin=null;releaseRequired=held;return;}
        if(HousePassageClient.active()){hoverOrigin=null;releaseRequired=held;return;}
        DeadwoodTarget target=target(client);
        if(serial!=0&&(!held||target==null||!target.origin().equals(origin)||(!running&&System.nanoTime()-requestedAt>5_000_000_000L))) {
            clear(true);
        }
        BlockHitResult hit=client.hitResult instanceof BlockHitResult blockHit && blockHit.getType()==HitResult.Type.BLOCK ? blockHit : null;
        boolean furniture=serial==0&&target==null&&hit!=null&&client.player.mayBuild()&&!client.player.isUsingItem();
        PanelDoorInteraction.Target door=furniture&&ClientPlayNetworking.canSend(PanelDoorInteraction.Use.TYPE)
                ? PanelDoorInteraction.reachable(client.player,hit.getBlockPos(),hit.getLocation()) : null;
        Vec3 doorAim=door==null?null:hit.getLocation();
        boolean passageAllowed=HousePassageClient.animationEnabled(client.player)
                &&!client.options.keyShift.isDown()&&ClientPlayNetworking.canSend(PanelDoorInteraction.Use.TYPE);
        if(serial==0&&target==null&&door==null&&passageAllowed&&client.player.mayBuild()&&!client.player.isUsingItem()
                &&client.hitResult!=null&&client.hitResult.getType()!=HitResult.Type.ENTITY) {
            var camera=client.gameRenderer.mainCamera();
            Vec3 from=camera.position();
            var vector=camera.forwardVector();
            Vec3 to=hit==null?from.add(new Vec3(vector.x(),vector.y(),vector.z()).scale(client.player.blockInteractionRange()+4))
                    :hit.getLocation();
            var aperture=camera.isInitialized()?HousePassage.openAim(client.player,from,to):null;
            if(aperture!=null){door=aperture.target();doorAim=aperture.point();}
        }
        CurtainInteraction.Target curtain=furniture&&door==null&&ClientPlayNetworking.canSend(CurtainInteraction.Use.TYPE)
                ? CurtainInteraction.reachable(client.player,hit.getBlockPos(),hit.getLocation()) : null;
        FenceGateInteraction.Target gate=furniture&&door==null&&curtain==null&&ClientPlayNetworking.canSend(FenceGateInteraction.Use.TYPE)
                ? FenceGateInteraction.reachable(client.player,hit.getBlockPos(),hit.getLocation()) : null;
        StorageFurnitureInteraction.Target storage=furniture&&door==null&&curtain==null&&gate==null
                &&ClientPlayNetworking.canSend(StorageFurnitureInteraction.Use.TYPE)
                ? StorageFurnitureInteraction.reachable(client.player,hit.getBlockPos(),hit.getLocation()) : null;
        var chair=furniture&&door==null&&curtain==null&&gate==null&&storage==null
                &&ClientPlayNetworking.canSend(DiningChairInteraction.Use.TYPE)
                ? DiningChairInteraction.reachable(client.player,hit.getBlockPos(),hit.getLocation()) : null;
        if(held&&!releaseRequired&&serial==0&&target!=null) {
            // One request per hold: denial or completion cannot retrigger while E remains down.
            releaseRequired=true;
            counter=counter==Integer.MAX_VALUE?1:counter+1;serial=counter;origin=target.origin();
            elapsed=0;duration=60;running=false;requestedAt=System.nanoTime();noticeUntil=0;
            ClientPlayNetworking.send(new DeadwoodRequest(origin,client.hitResult.getLocation(),serial,true));
        }else if((held||pressed)&&!releaseRequired&&door!=null) {
            releaseRequired=true;
            ClientPlayNetworking.send(new PanelDoorInteraction.Use(door.origin(),doorAim,door.open(),HousePassageClient.animationEnabled(client.player)));
        }else if((held||pressed)&&!releaseRequired&&curtain!=null) {
            releaseRequired=true;
            ClientPlayNetworking.send(new CurtainInteraction.Use(hit.getBlockPos(),hit.getLocation(),curtain.closed()));
        }else if((held||pressed)&&!releaseRequired&&gate!=null) {
            releaseRequired=true;
            ClientPlayNetworking.send(new FenceGateInteraction.Use(hit.getBlockPos(),hit.getLocation(),gate.open()));
        }else if((held||pressed)&&!releaseRequired&&storage!=null) {
            releaseRequired=true;
            ClientPlayNetworking.send(new StorageFurnitureInteraction.Use(hit.getBlockPos(),hit.getLocation(),storage.open()));
        }else if((held||pressed)&&!releaseRequired&&chair!=null) {
            releaseRequired=true;
            ClientPlayNetworking.send(new DiningChairInteraction.Use(hit.getBlockPos(),hit.getLocation(),chair.state().getValue(DiningChairBlock.TUCKED)));
        }
        BlockPos aimed=serial!=0?null:target!=null?target.origin():door!=null?door.origin()
                :curtain!=null?curtain.origin():gate!=null?gate.origin():storage!=null?storage.origin():chair!=null?hit.getBlockPos():null;
        if(!java.util.Objects.equals(aimed,hoverOrigin)) {
            hoverOrigin=aimed;
            hoverSince=System.nanoTime();
        }
        hoverPrompt=target!=null?"interaction.darkwinter.deadwood.prompt":door!=null
                ?doorPrompt(client,door,passageAllowed):curtain!=null
                ?(curtain.closed()?"interaction.darkwinter.curtain.open":"interaction.darkwinter.curtain.close"):gate!=null
                ?(gate.open()?"interaction.darkwinter.gate.close":"interaction.darkwinter.gate.open"):storage!=null
                ?(storage.open()?"interaction.darkwinter.storage.close":"interaction.darkwinter.storage.open"):chair!=null
                ?(chair.pull()?"interaction.darkwinter.chair.pull":"interaction.darkwinter.chair.push"):null;
    }
    private static String doorPrompt(Minecraft client,PanelDoorInteraction.Target door,boolean passageAllowed) {
        HousePassage passage=passageAllowed?HousePassage.plan(client.player,door):null;
        if(passage!=null)return passage.leaving()?"interaction.darkwinter.door.leave":"interaction.darkwinter.door.enter";
        return door.open()?"interaction.darkwinter.door.close":"interaction.darkwinter.door.open";
    }
    private static DeadwoodTarget target(Minecraft client) {
        if(client.player==null||client.level==null||client.player.isUsingItem()||!client.player.mayBuild()
                ||!ClientPlayNetworking.canSend(DeadwoodRequest.TYPE))return null;
        DeadwoodTarget target=DeadwoodTarget.hit(client.level,client.hitResult);
        return target!=null&&client.player.isWithinBlockInteractionRange(target.origin(),0)
                &&target.visibleFrom(client.player,client.hitResult.getLocation())?target:null;
    }
    private static void receive(DeadwoodProgress packet) {
        Minecraft client=Minecraft.getInstance();
        if(serial==0||packet.serial()!=serial||!packet.target().equals(origin)||level!=client.level||client.player==null)return;
        if(packet.phase()==DeadwoodProgress.RUNNING) {
            elapsed=Math.clamp(packet.elapsed(),0,Math.max(1,packet.duration()));duration=Math.clamp(packet.duration(),1,1200);
            receivedTick=client.level.getGameTime();
            if(!running){running=true;sound=new GatheringSound(client.level,origin,serial);client.getSoundManager().play(sound);}
        }else {
            clear(false);
            if(packet.phase()==DeadwoodProgress.COMPLETE)pickupAt=System.nanoTime();
            else if(packet.phase()==DeadwoodProgress.NO_SPACE)message("no_space");
        }
    }
    private static void clear(boolean tellServer) {
        if(tellServer&&serial!=0&&origin!=null&&Minecraft.getInstance().level==level&&ClientPlayNetworking.canSend(DeadwoodRequest.TYPE))
            ClientPlayNetworking.send(new DeadwoodRequest(origin,Vec3.ZERO,serial,false));
        serial=0;origin=null;running=false;
        if(sound!=null){Minecraft.getInstance().getSoundManager().stop(sound);sound=null;}
    }
    private static void message(String key) {notice=Component.translatable("interaction.darkwinter.deadwood."+key);noticeUntil=System.nanoTime()+1_700_000_000L;}
    private static float progress(float partial) {
        if(!running||level==null)return 0;
        float since=Math.clamp(level.getGameTime()-receivedTick+partial,0,5);
        return Math.clamp((elapsed+since)/Math.max(1,duration),0,.995f);
    }
    private static void render(GuiGraphicsExtractor g,float partial) {
        Minecraft client=Minecraft.getInstance();
        if(client.player==null||client.level==null||client.gui.screen()!=null||client.gui.hud.isHidden()
                ||DebugOverlayVisibility.expandedDebugOwnsHud()||client.player.isSpectator())return;
        int cx=g.guiWidth()/2,cy=g.guiHeight()/2+35;
        renderPickup(g);
        if(serial!=0) {
            SvgHud.arc(g,cx-15,cy-15,30,1,0x60476170,false);
            SvgHud.arc(g,cx-15,cy-15,30,progress(partial),HudStyle.STABLE,false);
            HudStyle.centered(g,Component.translatable("interaction.darkwinter.deadwood.working"),cx,cy+21,.8f,HudStyle.INK);
        }else if(System.nanoTime()<noticeUntil) {
            label(g,notice,cx,cy,HudStyle.INK);
        }else if(hoverOrigin!=null&&hoverPrompt!=null) {
            // A short steady aim reveals a quiet prompt, without a panel or a large keycap.
            float fade=Math.clamp((System.nanoTime()-hoverSince-250_000_000L)/150_000_000f,0,1);
            HudStyle.centered(g,Component.translatable(hoverPrompt,gatherKeyLabel()),
                    cx,cy-12,.74f,HudStyle.alpha(0xB3C0D0D6,fade));
        }
    }
    private static Component gatherKeyLabel() {
        return gather.saveString().equals("key.keyboard.e")?Component.literal("E"):gather.getTranslatedKeyMessage();
    }
    private static void renderPickup(GuiGraphicsExtractor g) {
        if(pickupAt==0||!TestingAccess.enabled("hud"))return;
        long age=System.nanoTime()-pickupAt;
        if(age>=2_200_000_000L){pickupAt=0;return;}
        float opacity=Math.min(Math.clamp(age/120_000_000f,0,1),Math.clamp((2_200_000_000L-age)/500_000_000f,0,1));
        int x=SurvivalNeedsHud.panelX(g.guiWidth())+2;
        int y=SurvivalNeedsHud.panelY(g.guiWidth(),g.guiHeight(),false)-24;
        // One quiet, texture-free SVG stick and a count, anchored to the existing reserve dials.
        SvgHud.inventoryIcon(g,InventoryGlyph.STICK,x+1,y+1,19,HudStyle.alpha(0xA0000910,opacity));
        SvgHud.inventoryIcon(g,InventoryGlyph.STICK,x,y,19,HudStyle.alpha(HudStyle.INK,.88f*opacity));
        HudStyle.text(g,"×3",x+24,y+5,.9f,HudStyle.alpha(HudStyle.INK,.88f*opacity));
    }
    private static void label(GuiGraphicsExtractor g,Component text,int cx,int y,int color) {
        int w=Math.round(HudStyle.width(text)*.9f)+20;
        SvgHud.surface(g,cx-w/2,y-5,w,23,MenuSurface.TOOLTIP,0,0xB06C8999);
        HudStyle.centered(g,HudStyle.strong(text),cx,y,.9f,color);
    }
    private static final class GatheringSound extends AbstractTickableSoundInstance {
        private final ClientLevel world;private final int request;
        GatheringSound(ClientLevel world,BlockPos pos,int request) {
            super(DeadwoodSounds.COLLECT,SoundSource.PLAYERS,SoundInstance.createUnseededRandom());
            this.world=world;this.request=request;
            x=pos.getX()+.5;y=pos.getY()+.25;z=pos.getZ()+.5;volume=.72f;pitch=1f;looping=false;relative=false;
        }
        @Override public void tick() {
            if(Minecraft.getInstance().level!=world||serial!=request||!running)stop();
        }
    }
}
