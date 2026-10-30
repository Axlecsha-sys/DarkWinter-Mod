// Applies the shared client and server policy for assisted terrain steps.
package com.dark.winter.movement;

import com.dark.winter.block.WallSegmentBlock;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Common client/server policy. Does not teleport, jump, modify attributes or request chunks. */
public final class TerrainStepAssist {
    private static final double LIP_CLEARANCE=1e-4;
    private static final TagKey<Block> TERRAIN=TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("dark-winter","step_assist_terrain"));
    private static final TagKey<Block> FORBIDDEN=TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("dark-winter","step_assist_forbidden"));
    private TerrainStepAssist() { }

    public static float height(Entity entity,Vec3 requested,float ordinary) {
        if (!(entity instanceof Player p) || !eligible(p) || !p.onGround()
                || ordinary>=TerrainStepGeometry.MAX_RISE || ordinary<0.1f
                || !forward(p,requested)) return ordinary;
        AABB body=p.getBoundingBox();
        // onGround can still describe the preceding move at an edge or after a
        // cancelled climb. A new climb always needs real support under the feet.
        if (p.level().noCollision(p,body.move(0,-0.02,0))) return ordinary;
        return probe(p,requested,ordinary,body,body);
    }

    public static boolean eligible(Player p) {
        return com.dark.winter.testing.TestingServer.enabledFor(p.level(),"motion")
                && p.isAlive() && !p.isCrouching() && !p.isSpectator()
                && !p.getAbilities().flying && !p.isPassenger() && !p.isInWater() && !p.isInLava()
                && !p.isFallFlying() && !p.onClimbable() && !p.isSleeping() && !p.isVisuallySwimming();
    }

    public static boolean forward(Player p,Vec3 requested) {
        if (!Double.isFinite(requested.x+requested.y+requested.z) || requested.y>0.05) return false;
        double distance=Math.hypot(requested.x,requested.z);
        if (distance<0.001 || distance>0.70) return false;
        double yaw=Math.toRadians(p.getYRot());
        return (requested.x*(-Math.sin(yaw))+requested.z*Math.cos(yaw))/distance>=0.35;
    }

    public static boolean stillValid(Player p,Vec3 requested,double startY,double targetY,float ordinary) {
        AABB current=p.getBoundingBox();
        AABB base=current.move(0,startY-current.minY,0);
        // Keep the original rise/terrain policy, but check the remaining clearance from
        // the real body. Lowering the clearance box back into a lip already crossed
        // would incorrectly cancel diagonal climbs across uneven terrain.
        float rise=probe(p,requested,ordinary,base,current);
        return rise>ordinary && Math.abs(startY+rise-targetY)<0.005;
    }

    private static float probe(Player p,Vec3 requested,float ordinary,AABB body,AABB clearanceBody) {
        Level level=p.level();
        AABB sweep=body.expandTowards(requested.x,0,requested.z);
        var pieces=new ArrayList<TerrainStepGeometry.Piece>(12);
        CollisionContext context=CollisionContext.of(p);
        int x0=(int)Math.floor(sweep.minX),x1=(int)Math.floor(sweep.maxX);
        int z0=(int)Math.floor(sweep.minZ),z1=(int)Math.floor(sweep.maxZ);
        int y0=(int)Math.floor(body.minY-1e-5),y1=(int)Math.floor(body.minY+TerrainStepGeometry.MAX_RISE);
        // A guard against unexpected scaled entities or abnormally large collision regions.
        if ((long)(x1-x0+1)*(z1-z0+1)*(y1-y0+1)>64) return ordinary;
        for (int x=x0;x<=x1;x++) for (int z=z0;z<=z1;z++) for (int y=y0;y<=y1;y++) {
            BlockPos pos=new BlockPos(x,y,z);
            if (!level.hasChunkAt(pos)) return ordinary;
            BlockState state=level.getBlockState(pos);
            if (state.isAir()) continue;
            var shapes=state.getCollisionShape(level,pos,context);
            if (shapes.isEmpty()) continue;
            boolean terrain=terrain(level,pos,state);
            for (AABB a:shapes.toAabbs()) {
                pieces.add(new TerrainStepGeometry.Piece(new TerrainStepGeometry.Box(
                        a.minX+x,a.minY+y,a.minZ+z,a.maxX+x,a.maxY+y,a.maxZ+z),terrain));
            }
        }
        var shape=new TerrainStepGeometry.Box(body.minX,body.minY,body.minZ,body.maxX,body.maxY,body.maxZ);
        double rise=TerrainStepGeometry.rise(shape,requested.x,requested.z,ordinary,pieces);
        if (rise<=ordinary) return ordinary;
        // Free headroom for lifting the entire body and then moving horizontally on the lip.
        // Collision still runs normally afterwards, including entities and world borders.
        float step=(float)(rise+LIP_CLEARANCE);
        double lift=Math.max(0,body.minY+step-clearanceBody.minY);
        if (!level.noCollision(p,clearanceBody.expandTowards(0,lift,0))
                || !level.noCollision(p,clearanceBody.move(0,lift,0).expandTowards(requested.x,0,requested.z))) return ordinary;
        // MAX_RISE limits terrain geometry, not this tiny collision clearance. Keep
        // the same clearance for a full block and for the maximum snow-covered lip.
        return step;
    }

    private static boolean terrain(Level level,BlockPos pos,BlockState state) {
        // Hard deny for our thin wall modules, even if a datapack mistakenly whitelists them.
        if (state.getBlock() instanceof WallSegmentBlock || state.is(FORBIDDEN) || !state.is(TERRAIN)) return false;
        if (state.is(Blocks.SNOW)) {
            BlockPos below=pos.below();
            if (!level.hasChunkAt(below)) return false;
            BlockState support=level.getBlockState(below);
            // A snow cap on a fence, leaf, log, roof or Wall Segment is not terrain.
            return !(support.getBlock() instanceof WallSegmentBlock)
                    && !support.is(FORBIDDEN) && support.is(TERRAIN) && !support.is(Blocks.SNOW);
        }
        return true;
    }
}
