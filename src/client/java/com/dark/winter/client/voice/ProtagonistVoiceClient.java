package com.dark.winter.client.voice;

import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.client.DebugOverlayVisibility;
import com.dark.winter.client.HudStyle;
import com.dark.winter.client.SprintStamina;
import com.dark.winter.client.SurvivalNeeds;
import com.dark.winter.client.SurvivalNeedsHud;
import com.dark.winter.client.TestingAccess;
import com.dark.winter.client.TestingDrone;
import com.dark.winter.client.loading.WorldPreloadClient;
import com.dark.winter.client.weather.ClimateDisplay;
import com.dark.winter.client.weather.WeatherClient;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;

/** Personal voice following the third-person character and quiet subtitles; no server authority. */
public final class ProtagonistVoiceClient {
    private static final Logger LOGGER=LogUtils.getLogger();
    private static final Random RANDOM=new Random();
    private static DialogueDirector director=new DialogueDirector(RANDOM);
    private static ClientLevel world;
    private static java.util.UUID player;
    private static long activeTicks,lastThreat=-1000,humanTraceUntil,keepsakeUntil;
    private static String heldItem="";
    private static float previousHealth=20;
    private static int stationarySeconds;
    private static VoiceLine current,pendingDebug;
    private static CharacterSoundInstance sound;
    private static long beganAt,captionUntil;
    private ProtagonistVoiceClient() {}

    public static void initialize() {
        VoiceCommands.register();
        ClientTickEvents.END_CLIENT_TICK.register(ProtagonistVoiceClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->reset(client));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("darkwinter","protagonist_subtitles"),
                (graphics,ticks)->render(graphics));
    }
    private static void reset(Minecraft client) {
        stop(client);pendingDebug=null;world=null;player=null;activeTicks=0;lastThreat=-1000;
        humanTraceUntil=keepsakeUntil=0;heldItem="";stationarySeconds=0;
        director=new DialogueDirector(RANDOM);
    }
    private static void tick(Minecraft client) {
        if(client.level==null||client.player==null){if(world!=null)reset(client);return;}
        if(world!=client.level||!client.player.getUUID().equals(player)) {
            reset(client);world=client.level;player=client.player.getUUID();previousHealth=client.player.getHealth();
        }
        var p=client.player;
        if(!p.isAlive()||p.isSpectator()||p.isSleeping()||TestingDrone.active()||WorldPreloadClient.active()) {
            stop(client);pendingDebug=null;return;
        }
        if(current!=null&&System.nanoTime()>=captionUntil)stop(client);
        if(client.isPaused()||client.gui.screen()!=null) {
            if(current!=null&&!(client.gui.screen() instanceof ChatScreen)){stop(client);director.interrupt(activeTicks/20);}
            return;
        }
        activeTicks++;
        if(pendingDebug!=null) {
            VoiceLine line=pendingDebug;pendingDebug=null;play(client,line,true);return;
        }
        if(p.getHealth()<previousHealth||p.hurtTime>0||client.options.keyAttack.isDown())lastThreat=activeTicks;
        previousHealth=p.getHealth();
        if(activeTicks%20!=0)return;
        DialogueDirector.Frame frame=sample(client);
        VoiceLine chosen=director.choose(frame,activeTicks/20);
        if(chosen==null||current!=null||activeTicks-lastThreat<160||p.isUnderWater()||p.isInLava()||p.isUsingItem())return;
        // Voice-chat transmission takes precedence over the character's inner monologue.
        for(var key:client.options.keyMappings)if(key.getName().equals("key.darkwinter.voice_ptt")&&key.isDown())return;
        play(client,chosen,false);
    }
    private static DialogueDirector.Frame sample(Minecraft client) {
        var p=client.player;var level=client.level;
        boolean weather=WeatherClient.active();var climate=weather?WeatherClient.climate(0):null;
        weather=climate!=null;
        float exposure=weather?ClimateDisplay.exposure():1;
        float heat=SurvivalNeeds.getTemperature(),hunger=SurvivalNeeds.getHunger(),thirst=SurvivalNeeds.getThirst();
        float stamina=SprintStamina.getStamina()/Math.max(1,SprintStamina.getMaxStamina());
        float fatigue=SurvivalNeeds.getFatigue();
        boolean hungerEnabled=TestingAccess.enabled("hunger"),staminaEnabled=TestingAccess.enabled("stamina");
        if(!hungerEnabled)hunger=0;
        if(!staminaEnabled){stamina=1;fatigue=0;}
        boolean calm=hunger<55&&thirst>40&&stamina>.45f&&fatigue<65&&heat>35
                &&p.getHealth()>p.getMaxHealth()*.6f&&!p.isSprinting()&&activeTicks-lastThreat>=160
                &&(!weather||climate.feelsLikeC(exposure)>-25&&climate.windMetersPerSecond()*exposure<6);
        stationarySeconds=p.getDeltaMovement().horizontalDistanceSqr()<.0004?stationarySeconds+1:0;
        boolean outdoors=level.canSeeSky(p.blockPosition().above())||weather&&exposure>.55f;
        if(client.hitResult instanceof BlockHitResult hit&&hit.getType()==HitResult.Type.BLOCK&&level.hasChunkAt(hit.getBlockPos())) {
            var state=level.getBlockState(hit.getBlockPos());
            if(state.getBlock() instanceof FurnitureBlock furniture) {
                String group=furniture.definition().group(),id=furniture.definition().id();
                if(group.equals("residential")||group.equals("hospital"))humanTraceUntil=activeTicks+20*20;
                if(keepsake(id))keepsakeUntil=activeTicks+20*20;
            }else if(state.is(BlockTags.BEDS)||state.is(Blocks.CHEST)||state.is(Blocks.BARREL))humanTraceUntil=activeTicks+20*20;
        }
        String selected=BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString();
        if(!selected.equals(heldItem)) {
            heldItem=selected;
            if(keepsake(selected)||selected.equals("minecraft:compass")||selected.equals("minecraft:clock")
                    ||selected.equals("minecraft:written_book"))keepsakeUntil=activeTicks+25*20;
        }
        boolean vista=calm&&outdoors&&stationarySeconds>=4&&Math.abs(p.getXRot())<=18&&clearView(client);
        boolean nearHeat=(heat<50||weather&&climate.feelsLikeC(exposure)<-12)&&nearHeat(client);
        return new DialogueDirector.Frame(heat,hunger,thirst,stamina,fatigue,
                staminaEnabled&&SprintStamina.captureState().exhausted(),weather,
                weather?climate.feelsLikeC(exposure):0,exposure,weather?climate.windMetersPerSecond():0,
                nearHeat,outdoors,calm,humanTraceUntil>activeTicks,keepsakeUntil>activeTicks,vista);
    }
    private static boolean keepsake(String id) {
        return id.contains("newspaper")||id.contains("matchbox")||id.contains("mug")||id.contains("photograph");
    }
    private static boolean clearView(Minecraft client) {
        var p=client.player;var eye=p.getEyePosition();var direction=p.getLookAngle();
        for(int distance=8;distance<=24;distance+=8)
            if(!client.level.hasChunkAt(BlockPos.containing(eye.add(direction.scale(distance)))))return false;
        return client.level.clip(new ClipContext(eye,eye.add(direction.scale(24)),ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,p)).getType()==HitResult.Type.MISS;
    }
    private static boolean nearHeat(Minecraft client) {
        BlockPos origin=client.player.blockPosition();
        for(BlockPos pos:BlockPos.betweenClosed(origin.offset(-2,-1,-2),origin.offset(2,2,2))) {
            if(!client.level.hasChunkAt(pos))continue;
            var state=client.level.getBlockState(pos);
            if(state.is(Blocks.FIRE)||state.is(Blocks.SOUL_FIRE))return true;
            if((state.is(BlockTags.CAMPFIRES)||state.getBlock() instanceof AbstractFurnaceBlock)
                    &&state.hasProperty(BlockStateProperties.LIT)&&state.getValue(BlockStateProperties.LIT))return true;
        }
        return false;
    }
    public static void requestDebug(VoiceLine line) {
        var client=Minecraft.getInstance();
        if(client.level==null||client.player==null||!client.player.isAlive())throw new IllegalStateException("Войдите в мир живым персонажем.");
        if(client.getSoundManager().getSoundEvent(soundId(line))==null)throw new IllegalStateException("Аудиоресурс реплики не загружен: "+line.id);
        pendingDebug=line;
    }
    public static VoiceLine randomLine() {
        VoiceLine[] lines=VoiceLine.values();VoiceLine candidate;
        do{candidate=lines[RANDOM.nextInt(lines.length)];}while(candidate==current||candidate==director.previous());
        return candidate;
    }
    public static void stopDebug() {pendingDebug=null;stop(Minecraft.getInstance());director.interrupt(activeTicks/20);}
    public static String status() {return current==null?"Сейчас персонаж молчит.":"Текущая реплика: "+current.id;}
    public static boolean isSpeaking() {return current!=null;}
    private static Identifier soundId(VoiceLine line){return Identifier.fromNamespaceAndPath("darkwinter",line.soundPath());}
    private static void play(Minecraft client,VoiceLine line,boolean debug) {
        if(client.getSoundManager().getSoundEvent(soundId(line))==null)return;
        stop(client);
        sound=new CharacterSoundInstance(soundId(line),SoundSource.VOICE,.85f,false);
        client.getSoundManager().play(sound);current=line;beganAt=System.nanoTime();
        String caption=Component.translatable(line.subtitleKey()).getString();
        int readingMillis=Math.clamp(caption.codePointCount(0,caption.length())*65+650,2300,6500);
        captionUntil=beganAt+Math.max(line.durationMillis+450,readingMillis)*1_000_000L;
        director.spoken(line,activeTicks/20);
        LOGGER.info("DarkWinter protagonist: {} ({})",line.id,debug?"debug":"context");
    }
    private static void stop(Minecraft client) {
        if(sound!=null){sound.finish();client.getSoundManager().stop(sound);}
        sound=null;current=null;captionUntil=0;
    }
    private static void render(GuiGraphicsExtractor g) {
        var client=Minecraft.getInstance();
        if(current==null||client.player==null||client.level!=world||client.gui.screen()!=null||client.gui.hud.isHidden()
                ||DebugOverlayVisibility.expandedDebugOwnsHud()||client.isPaused())return;
        long now=System.nanoTime();
        float opacity=Math.min(Math.clamp((now-beganAt)/160_000_000f,0,1),Math.clamp((captionUntil-now)/450_000_000f,0,1));
        if(opacity<=0)return;
        float scale=.9f;
        int side=SurvivalNeedsHud.panelX(g.guiWidth())+SurvivalNeedsHud.panelWidth(g.guiWidth())+18;
        int width=Math.min(520,g.guiWidth()-40),bottom=g.guiHeight()-56;
        if(g.guiWidth()>=720)width=Math.min(width,g.guiWidth()-2*side);
        else bottom=Math.min(bottom,SurvivalNeedsHud.panelY(g.guiWidth(),g.guiHeight(),false)-14);
        width=Math.max(100,width);
        List<FormattedCharSequence> lines=client.font.split(HudStyle.font(Component.translatable(current.subtitleKey())),(int)(width/scale));
        // The supplied translations fit two lines at ordinary GUI scales. Keep
        // wrapping for small windows rather than truncating spoken words.
        int y=bottom-Math.round(lines.size()*12*scale);
        for(FormattedCharSequence text:lines) {
            float x=(g.guiWidth()-client.font.width(text)*scale)/2;
            g.pose().pushMatrix();g.pose().translate(x,y);g.pose().scale(scale,scale);
            g.text(client.font,text,0,0,HudStyle.alpha(0xE3E1E8E8,opacity),true);
            g.pose().popMatrix();y+=Math.round(12*scale);
        }
    }
}
