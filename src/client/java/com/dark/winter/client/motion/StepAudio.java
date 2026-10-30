// Plays native block step sounds using the measured alternating-foot gait.
package com.dark.winter.client.motion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/** Same alternating feet and pan as RC1; only the real block's native step event is played. */
public final class StepAudio {
    private StepAudio() { }
    public static void reset() { }
    public static void tick(LocalPlayer p,MotionSettings s) { }

    public static void contact(LocalPlayer p,MotionSettings s,boolean right) {
        // Thin snow can be at foot level with little/no collision height.
        BlockPos feet=BlockPos.containing(p.getX(),p.getY()+0.01,p.getZ());
        BlockState ground=p.level().getBlockState(feet);
        if (!ground.is(Blocks.SNOW)) {
            BlockPos support=BlockPos.containing(p.getX(),p.getY()-0.06,p.getZ());
            ground=p.level().getBlockState(support);
            if (ground.isAir()) ground=p.level().getBlockState(support.below());
        }
        if (ground.isAir()||p.isSilent()||!s.enabled()||!s.footsteps()) return;
        SoundType type=ground.getSoundType();
        double volume=s.stepVolume()*type.getVolume()*(p.isSprinting()?1.18:1.0);
        if (volume<=0.0001) return;
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(
                type.getStepSound().location(),SoundSource.PLAYERS,
                (float)Math.min(volume,0.8),type.getPitch(),
                SoundInstance.createUnseededRandom(),false,0,SoundInstance.Attenuation.NONE,
                right?0.10:-0.10,0,-0.10,true));
        // No procedural sole, snow or cloth layers, even with an old clothing=true config.
    }
}
