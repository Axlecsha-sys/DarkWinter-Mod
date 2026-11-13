package com.dark.winter.placement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
/** One native block assembly, with an authoritative physical contact and common displacement. */
public record Anchor(BlockPos owner,BlockPos support,Direction face,Vec3 offset,Vec3 point,List<BlockPos> members,double yaw){
    private static final Codec<Vec3> VECTOR=RecordCodecBuilder.create(i->i.group(
        Codec.DOUBLE.fieldOf("x").forGetter(Vec3::x),Codec.DOUBLE.fieldOf("y").forGetter(Vec3::y),
        Codec.DOUBLE.fieldOf("z").forGetter(Vec3::z)).apply(i,Vec3::new));
    public static final Codec<Anchor> CODEC=RecordCodecBuilder.create(i->i.group(
        BlockPos.CODEC.fieldOf("owner").forGetter(Anchor::owner),BlockPos.CODEC.fieldOf("support").forGetter(Anchor::support),
        Direction.CODEC.fieldOf("face").forGetter(Anchor::face),VECTOR.fieldOf("offset").forGetter(Anchor::offset),
        VECTOR.fieldOf("point").forGetter(Anchor::point),BlockPos.CODEC.listOf().fieldOf("members").forGetter(Anchor::members),
        Codec.DOUBLE.optionalFieldOf("yaw", 0.0).forGetter(Anchor::yaw)).apply(i,Anchor::new));
    public Anchor(BlockPos owner,BlockPos support,Direction face,Vec3 offset,Vec3 point,List<BlockPos> members) {
        this(owner,support,face,offset,point,members,0);
    }
    public Anchor {
        owner=owner.immutable();support=support.immutable();members=List.copyOf(members);
        if(members.isEmpty()||members.size()>16||!members.contains(owner)||members.stream().distinct().count()!=members.size()
                ||!Double.isFinite(offset.x+offset.y+offset.z+point.x+point.y+point.z+yaw) || Math.abs(yaw)>Math.PI
                ||Math.abs(offset.x)>2||Math.abs(offset.y)>2||Math.abs(offset.z)>2)
            throw new IllegalArgumentException("Invalid physical surface anchor");
    }
}
