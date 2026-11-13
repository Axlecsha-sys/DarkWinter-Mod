package com.dark.winter.client.voice;

import com.dark.winter.client.SprintStamina;
import com.dark.winter.client.SurvivalNeeds;
import com.dark.winter.client.TestingAccess;
import com.dark.winter.client.TestingDrone;
import com.dark.winter.client.loading.WorldPreloadClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** Local physical breathing following the character in third person; no server/gameplay authority. */
public final class BreathingClient {
    private static final Identifier RUN=Identifier.fromNamespaceAndPath("darkwinter","protagonist.breath.running");
    private static final Identifier JUMP=Identifier.fromNamespaceAndPath("darkwinter","protagonist.breath.jump");
    private static final BreathingEnvelope envelope=new BreathingEnvelope();
    private static ClientLevel world;
    private static java.util.UUID player;
    private static Vec3 previous;
    private static RunningBreath loop;
    private static EffortBreath jump;
    private static int jumpCooldown,jumpDuck,retryTicks;
    private static boolean jumped;
    private static float duck=1;
    private BreathingClient() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(BreathingClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->reset(client));
    }
    private static boolean eligible(Minecraft client) {
        var p=client.player;
        return p!=null&&client.level!=null&&p.isAlive()&&!p.isSpectator()&&!p.isSleeping()
                &&!p.getAbilities().flying&&!p.isFallFlying()&&!p.isPassenger()
                &&!p.isUnderWater()&&!p.isInLava()&&!p.isSwimming()
                &&!TestingDrone.active()&&!WorldPreloadClient.active();
    }
    private static boolean pushToTalk(Minecraft client) {
        for(var key:client.options.keyMappings)
            if(key.getName().equals("key.darkwinter.voice_ptt")&&key.isDown())return true;
        return false;
    }
    private static void tick(Minecraft client) {
        if(!eligible(client)){reset(client);return;}
        var p=client.player;
        if(world!=client.level||!p.getUUID().equals(player)) {
            reset(client);world=client.level;player=p.getUUID();previous=p.position();
        }
        Vec3 position=p.position();
        double travel=previous==null?0:Math.hypot(position.x-previous.x,position.z-previous.z);
        previous=position;
        if(client.isPaused()||client.gui.screen()!=null) {
            stopSounds(client);jumped=false;return;
        }
        if(jumpCooldown>0)jumpCooldown--;
        if(jumpDuck>0)jumpDuck--;
        if(retryTicks>0)retryTicks--;
        // Actual displacement rejects a held sprint key against a wall and teleport-sized moves.
        boolean running=p.isSprinting()&&travel>.075&&travel<.75;
        float stamina=TestingAccess.enabled("stamina")?SprintStamina.getStamina()/Math.max(1,SprintStamina.getMaxStamina()):1;
        float fatigue=TestingAccess.enabled("stamina")?SurvivalNeeds.getFatigue()/100:0;
        envelope.tick(running,stamina,fatigue,jumped);jumped=false;
        float targetDuck=pushToTalk(client)?0:ProtagonistVoiceClient.isSpeaking()?.10f:jumpDuck>0?.18f:1;
        duck+=Math.clamp(targetDuck-duck,-.30f,.06f);
        if(loop!=null&&!client.getSoundManager().isActive(loop))loop=null;
        if(loop!=null&&envelope.gain()<=.001f) {
            loop.finish();client.getSoundManager().stop(loop);loop=null;
        }
        if(loop==null&&envelope.gain()>.02f&&retryTicks==0) {
            retryTicks=20;
            if(client.getSoundManager().getSoundEvent(RUN)!=null) {
                var candidate=new RunningBreath(world);
                if(client.getSoundManager().play(candidate)!=SoundEngine.PlayResult.NOT_STARTED)loop=candidate;
            }
        }
    }
    /** Called only after the native ground jump completed, so cancelled jumps stay silent. */
    public static void onJump() {
        Minecraft client=Minecraft.getInstance();
        if(!eligible(client)||client.isPaused()||client.gui.screen()!=null||jumpCooldown>0)return;
        jumped=true;jumpCooldown=24;
        if(pushToTalk(client)||ProtagonistVoiceClient.isSpeaking())return;
        if(client.getSoundManager().getSoundEvent(JUMP)==null)return;
        if(jump!=null){jump.finish();client.getSoundManager().stop(jump);}
        jump=new EffortBreath(client.level);
        client.getSoundManager().play(jump);jumpDuck=18;
    }
    private static void stopSounds(Minecraft client) {
        if(loop!=null){loop.finish();client.getSoundManager().stop(loop);}
        if(jump!=null){jump.finish();client.getSoundManager().stop(jump);}
        loop=null;jump=null;
    }
    private static void reset(Minecraft client) {
        stopSounds(client);envelope.reset();world=null;player=null;previous=null;
        jumpCooldown=jumpDuck=retryTicks=0;jumped=false;duck=1;
    }
    private static final class RunningBreath extends CharacterSoundInstance {
        private final ClientLevel level;
        RunningBreath(ClientLevel level) {
            super(RUN,SoundSource.PLAYERS,envelope.gain()*duck,true);
            this.level=level;
        }
        @Override public boolean canStartSilent(){return true;}
        @Override public void tick() {
            super.tick();if(isStopped())return;
            var client=Minecraft.getInstance();
            if(client.level!=level||!eligible(client)||client.gui.screen()!=null){stop();return;}
            volume=envelope.gain()*duck;
        }
    }
    private static final class EffortBreath extends CharacterSoundInstance {
        private final ClientLevel level;
        EffortBreath(ClientLevel level) {
            super(JUMP,SoundSource.PLAYERS,.70f,false);
            this.level=level;
        }
        @Override public void tick() {
            super.tick();if(isStopped())return;
            var client=Minecraft.getInstance();
            if(client.level!=level||!eligible(client)||client.gui.screen()!=null){stop();return;}
            float target=pushToTalk(client)?0:ProtagonistVoiceClient.isSpeaking()?.07f:.70f;
            volume+=Math.clamp(target-volume,-.25f,.08f);
        }
    }
}
