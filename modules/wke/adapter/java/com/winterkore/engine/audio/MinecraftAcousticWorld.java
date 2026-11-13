// Bounded client-thread collision probes; Minecraft remains the only world representation.
package com.winterkore.engine.audio;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Traverses loaded cells only and tests the actual collision shape, including open doors. */
public final class MinecraftAcousticWorld {
    private static final double[][] ROOM_DIRECTIONS={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private MinecraftAcousticWorld() { }

    public static final class Budget {
        private int remaining;
        private int used;
        public Budget(int cells) { remaining=Math.max(0,cells); }
        public boolean take() { if(remaining==0)return false;remaining--;used++;return true; }
        public int remaining() { return remaining; }
        public int used() { return used; }
    }

    public record Trace(AcousticModel.Path path,double firstDistance,float firstReflection,boolean hit) { }

    public static AcousticModel.Path path(ClientLevel level,Vec3 from,Vec3 to,Budget budget) {
        return trace(level,from,to,budget,false).path();
    }

    public static AcousticModel.Room room(ClientLevel level,Vec3 listener,Budget budget) {
        int hits=0;double distances=0;float reflection=0;
        for(double[] direction:ROOM_DIRECTIONS) {
            Vec3 end=listener.add(direction[0]*AcousticModel.ROOM_PROBE_DISTANCE,
                    direction[1]*AcousticModel.ROOM_PROBE_DISTANCE,direction[2]*AcousticModel.ROOM_PROBE_DISTANCE);
            Trace trace=trace(level,listener,end,budget,true);
            if(!trace.path().complete())return null;
            if(trace.hit()) { hits++;distances+=trace.firstDistance();reflection+=trace.firstReflection(); }
        }
        if(hits<2)return AcousticModel.OUTDOORS;
        float enclosure=(hits-1f)/(ROOM_DIRECTIONS.length-1f);
        return new AcousticModel.Room(enclosure,distances/hits,reflection/hits);
    }

    private static Trace trace(ClientLevel level,Vec3 from,Vec3 to,Budget budget,boolean firstOnly) {
        double dx=to.x-from.x,dy=to.y-from.y,dz=to.z-from.z;
        int x=floor(from.x),y=floor(from.y),z=floor(from.z);
        int endX=floor(to.x),endY=floor(to.y),endZ=floor(to.z);
        int sx=Double.compare(dx,0),sy=Double.compare(dy,0),sz=Double.compare(dz,0);
        double tx=boundary(from.x,x,dx,sx),ty=boundary(from.y,y,dy,sy),tz=boundary(from.z,z,dz,sz);
        double ix=sx==0?Double.POSITIVE_INFINITY:Math.abs(1/dx);
        double iy=sy==0?Double.POSITIVE_INFINITY:Math.abs(1/dy);
        double iz=sz==0?Double.POSITIVE_INFINITY:Math.abs(1/dz);
        float transmission=1,high=1,firstReflection=0;int barriers=0;
        double firstDistance=0;boolean hit=false,complete=false;
        BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
        for(int visited=0;visited<256;visited++) {
            if(!budget.take())break;
            pos.set(x,y,z);
            if(!level.hasChunkAt(pos))break; // Never requests/generates an unloaded chunk.
            boolean last=x==endX&&y==endY&&z==endZ;
            // Emitter positions often lie inside a furnace/block; do not occlude it by itself.
            if(visited>0 && (!last||firstOnly)) {
                BlockState state=level.getBlockState(pos);
                if(!state.isAir()) {
                    var shape=state.getCollisionShape(level,pos);
                    var collision=shape.isEmpty()?null:shape.clip(from,to,pos);
                    if(collision!=null) {
                        AcousticModel.Material material=material(state);
                        transmission*=material.transmission();high*=material.highTransmission();barriers++;
                        if(!hit) {
                            hit=true;firstDistance=from.distanceTo(collision.getLocation());
                            firstReflection=material.reflectivity();
                        }
                        if(firstOnly) { complete=true;break; }
                    }
                }
            }
            if(last) { complete=true;break; }
            double next=Math.min(tx,Math.min(ty,tz));
            if(next>1) { complete=true;break; }
            if(tx<=next+1e-10) {x+=sx;tx+=ix;}
            if(ty<=next+1e-10) {y+=sy;ty+=iy;}
            if(tz<=next+1e-10) {z+=sz;tz+=iz;}
        }
        return new Trace(new AcousticModel.Path(transmission,high,barriers,complete),
                firstDistance,firstReflection,hit);
    }

    private static AcousticModel.Material material(BlockState state) {
        SoundType sound=state.getSoundType();
        if(sound==SoundType.GLASS)return AcousticModel.GLASS;
        if(sound==SoundType.SNOW||sound==SoundType.POWDER_SNOW)return AcousticModel.SNOW;
        if(state.is(BlockTags.LEAVES))return AcousticModel.FOLIAGE;
        if(sound==SoundType.WOOL||sound==SoundType.MOSS||sound==SoundType.SAND
                ||sound==SoundType.GRAVEL||sound==SoundType.GRASS)return AcousticModel.SOFT;
        if(sound==SoundType.WOOD||sound==SoundType.BAMBOO_WOOD||state.is(BlockTags.PLANKS)
                ||state.getBlock() instanceof DoorBlock||state.getBlock() instanceof TrapDoorBlock)
            return sound==SoundType.METAL?AcousticModel.STONE:AcousticModel.WOOD;
        return AcousticModel.STONE;
    }

    private static int floor(double value) { int i=(int)value;return value<i?i-1:i; }
    private static double boundary(double value,int cell,double delta,int step) {
        return step==0?Double.POSITIVE_INFINITY:((step>0?cell+1:cell)-value)/delta;
    }
}
