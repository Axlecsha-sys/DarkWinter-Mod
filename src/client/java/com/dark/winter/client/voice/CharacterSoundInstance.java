package com.dark.winter.client.voice;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** Personal character audio: the existing centered first-person mix, or a moving world source. */
public class CharacterSoundInstance extends AbstractTickableSoundInstance {
    private final ClientLevel world;
    private final java.util.UUID owner;
    private final Vec3 firstPersonPosition;
    private final Vec3 contact;

    protected CharacterSoundInstance(Identifier id,SoundSource source,float volume,boolean looping) {
        this(id,source,volume,1,looping,Vec3.ZERO,null);
    }
    private CharacterSoundInstance(Identifier id,SoundSource source,float volume,float pitch,boolean looping,Vec3 firstPosition,Vec3 contact) {
        super(SoundEvent.createVariableRangeEvent(id),source,SoundInstance.createUnseededRandom());
        var client=Minecraft.getInstance();
        this.world=client.level;this.owner=client.player.getUUID();
        this.volume=volume;this.pitch=pitch;this.looping=looping;this.delay=0;
        this.firstPersonPosition=firstPosition;this.contact=contact;
        perspective(client,client.gameRenderer.mainCamera());
    }
    public static CharacterSoundInstance footstep(Identifier event,float volume,float pitch,boolean right) {
        var player=Minecraft.getInstance().player;
        double yaw=Math.toRadians(player.yBodyRot),side=right?.13:-.13;
        Vec3 point=player.position().add(-Math.cos(yaw)*side,.025,-Math.sin(yaw)*side);
        return new CharacterSoundInstance(event,SoundSource.PLAYERS,volume,pitch,false,
                new Vec3(right?.10:-.10,0,-.10),point);
    }
    public final boolean footstep(){return contact!=null;}

    @Override public void tick() {
        var client=Minecraft.getInstance();
        if(client.level!=world||client.player==null||!owner.equals(client.player.getUUID())||!client.player.isAlive())finish();
        else perspective(client,client.gameRenderer.mainCamera());
    }
    public final void finish(){volume=0;stop();}

    /** Capture on the render thread; the audio executor receives immutable coordinates and mode. */
    public final Perspective perspective(Minecraft client,Camera camera) {
        relative=client.options.getCameraType().isFirstPerson();
        attenuation=relative?SoundInstance.Attenuation.NONE:SoundInstance.Attenuation.LINEAR;
        Vec3 position=firstPersonPosition;
        if(!relative&&client.level==world&&client.player!=null&&owner.equals(client.player.getUUID())) {
            float partial=camera.isInitialized()?camera.getCameraEntityPartialTicks(client.getDeltaTracker()):1;
            position=contact!=null?contact:client.player.getEyePosition(partial).add(0,-.08,0)
                    .add(client.player.getViewVector(partial).scale(.10));
        }
        x=position.x;y=position.y;z=position.z;
        float range=sound==null?16:sound.getAttenuationDistance(getVolume());
        var colour=CharacterAcoustics.at(relative?0:position.distanceTo(camera.position()));
        return new Perspective(relative,position,range,colour,footstep());
    }

    public record Perspective(boolean relative,Vec3 position,float range,CharacterAcoustics.Colour colour,boolean footstep) {
        public void apply(Channel channel) {
            channel.setRelative(relative);
            channel.setSelfPosition(position);
            ((CharacterAudioChannel)channel).darkwinter$characterDepth(relative,footstep,range,colour);
        }
    }
}
