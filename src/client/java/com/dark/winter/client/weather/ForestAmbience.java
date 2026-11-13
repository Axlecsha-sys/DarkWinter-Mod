// Sparse positional forest detail, driven by the existing synchronized weather and sun.
package com.dark.winter.client.weather;

import com.dark.winter.visuals.SolarRuntime;
import com.dark.winter.weather.WeatherPreset;
import com.dark.winter.worldgen.TestBiomeCompatibility;
import com.dark.winter.worldgen.TestSpruceBlocks;
import java.util.ArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** No chunk requests or per-frame scans: at most356 loaded block reads every40 active ticks. */
public final class ForestAmbience {
    private static final RandomSource RANDOM=RandomSource.create();
    private static final SoundEvent BIRD=SoundEvent.createVariableRangeEvent(
            Identifier.fromNamespaceAndPath("darkwinter","ambient.forest_bird"));
    private static final SoundEvent CREAK=SoundEvent.createVariableRangeEvent(
            Identifier.fromNamespaceAndPath("darkwinter","ambient.forest_creak"));
    private static final ArrayList<Vec3> SOURCES=new ArrayList<>(8);
    private static ClientLevel world;
    private static ForestSound birdSound,creakSound;
    private static int probeDelay,birdDelay,creakDelay;
    private static double probeX,probeZ;
    private static boolean enabled;
    private ForestAmbience(){}

    public static void init(){ClientTickEvents.END_CLIENT_TICK.register(ForestAmbience::tick);}

    private static boolean clearWeather(){
        if(!WeatherClient.active()||WeatherClient.targetPreset()!=WeatherPreset.CLEAR)return false;
        var weather=WeatherClient.sample(1);
        return weather.cloudCover()<.15f&&weather.snowfall()<.001f&&weather.windSpeed()<.5f;
    }
    private static boolean daylight(ClientLevel level){
        var sun=SolarRuntime.current(level);
        if(sun!=null)return sun.y()>0;
        double ticks=level.getDefaultClockTime(),local=ticks-Math.floor(ticks/24000)*24000;
        return local<12000;
    }
    private static boolean tree(BlockState state){
        return state.is(TestSpruceBlocks.LOG)||state.is(TestSpruceBlocks.BOUGH)
                ||state.is(Blocks.SPRUCE_LOG)||state.is(Blocks.SPRUCE_WOOD)||state.is(Blocks.SPRUCE_LEAVES);
    }
    private static boolean forestBiome(ClientLevel level,BlockPos pos){
        var biome=level.getBiome(pos);
        return TestBiomeCompatibility.snowTerrain(biome)||biome.is(Biomes.TAIGA)||biome.is(Biomes.SNOWY_TAIGA)
                ||biome.is(Biomes.OLD_GROWTH_PINE_TAIGA)||biome.is(Biomes.OLD_GROWTH_SPRUCE_TAIGA)
                ||biome.is(Biomes.FOREST)||biome.is(Biomes.DARK_FOREST)||biome.is(Biomes.BIRCH_FOREST);
    }
    private static boolean outside(ClientLevel level,BlockPos eye){
        var cursor=new BlockPos.MutableBlockPos();
        for(int above=1;above<=10;above++){
            cursor.set(eye.getX(),eye.getY()+above,eye.getZ());
            var state=level.getBlockState(cursor);
            // Snow resting on an actual trunk/bough is canopy cover, not an indoor roof.
            boolean canopySnow=(state.is(Blocks.SNOW)||state.is(Blocks.SNOW_BLOCK))
                    &&tree(level.getBlockState(cursor.below()));
            if(!tree(state)&&!canopySnow&&!state.getCollisionShape(level,cursor).isEmpty())return false;
        }
        return true;
    }
    private static void probe(Minecraft client){
        SOURCES.clear();var player=client.player;ClientLevel level=client.level;
        probeX=player.getX();probeZ=player.getZ();
        BlockPos eye=BlockPos.containing(player.getEyePosition());
        if(!level.getChunkSource().hasChunk(eye.getX()>>4,eye.getZ()>>4)
                ||!forestBiome(level,eye)||!outside(level,eye))return;
        var cursor=new BlockPos.MutableBlockPos();
        // Trunks and actual bough blocks supply stable world positions, not a sound fixed to the camera.
        for(int column=0;column<24&&SOURCES.size()<8;column++){
            double angle=RANDOM.nextDouble()*Math.PI*2,distance=6+RANDOM.nextDouble()*16;
            int x=(int)Math.floor(probeX+Math.cos(angle)*distance),z=(int)Math.floor(probeZ+Math.sin(angle)*distance);
            if(!level.getChunkSource().hasChunk(x>>4,z>>4))continue;
            for(int height=1;height<=14;height++){
                cursor.set(x,player.blockPosition().getY()+height,z);
                if(tree(level.getBlockState(cursor))){
                    SOURCES.add(new Vec3(x+.5,cursor.getY()+.5,z+.5));break;
                }
            }
        }
    }
    private static void tick(Minecraft client){
        if(client.level!=world){
            if(birdSound!=null)client.getSoundManager().stop(birdSound);
            if(creakSound!=null)client.getSoundManager().stop(creakSound);
            birdSound=null;creakSound=null;world=client.level;SOURCES.clear();
            enabled=false;probeDelay=0;birdDelay=50+RANDOM.nextInt(90);creakDelay=100+RANDOM.nextInt(160);
        }
        if(world==null||client.player==null){enabled=false;return;}
        if(client.isPaused()||!world.tickRateManager().runsNormally())return;
        if(!clearWeather()||client.player.isUnderWater()||!client.player.isAlive()){
            enabled=false;probeDelay=0;return;
        }
        if(--probeDelay<=0||Math.hypot(client.player.getX()-probeX,client.player.getZ()-probeZ)>8){
            probe(client);probeDelay=40;
        }
        if(SOURCES.size()<2){enabled=false;return;}
        if(!enabled){
            birdDelay=50+RANDOM.nextInt(90);creakDelay=100+RANDOM.nextInt(160);enabled=true;
        }
        if(--birdDelay<=0){
            if(daylight(world)&&(birdSound==null||!client.getSoundManager().isActive(birdSound))){
                birdSound=new ForestSound(world,BIRD,true,.55f,source());client.getSoundManager().play(birdSound);
            }
            birdDelay=300+RANDOM.nextInt(500);
        }
        if(--creakDelay<=0){
            if(creakSound==null||!client.getSoundManager().isActive(creakSound)){
                creakSound=new ForestSound(world,CREAK,false,.65f,source());client.getSoundManager().play(creakSound);
            }
            creakDelay=400+RANDOM.nextInt(600);
        }
    }
    private static Vec3 source(){return SOURCES.get(RANDOM.nextInt(SOURCES.size()));}

    private static final class ForestSound extends AbstractTickableSoundInstance {
        private final ClientLevel level;
        private final boolean bird;
        private boolean fading;
        ForestSound(ClientLevel level,SoundEvent event,boolean bird,float gain,Vec3 source){
            super(event,SoundSource.AMBIENT,SoundInstance.createUnseededRandom());
            this.level=level;this.bird=bird;volume=gain;pitch=1;
            x=source.x;y=source.y;z=source.z;
            relative=false;looping=false;delay=0;attenuation=Attenuation.LINEAR;
        }
        @Override public void tick(){
            var client=Minecraft.getInstance();
            if(client.level!=level||client.player==null){stop();return;}
            if(!enabled||!clearWeather()||bird&&!daylight(level))fading=true;
            if(fading){volume=Math.max(0,volume-.06f);if(volume<=0)stop();}
        }
    }
}
