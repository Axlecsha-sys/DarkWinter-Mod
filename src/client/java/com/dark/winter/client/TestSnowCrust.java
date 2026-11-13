// Adds globally shared, bounded crust relief to existing flat snow quads without changing topology or world data.
package com.dark.winter.client;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Four shared world-corner heights, stored as NW/NE/SE/SW two-bit values in one byte. */
public final class TestSnowCrust {
    public static final float STEP=com.winterkore.engine.surface.geometry.SnowCrustRelief.STEP,
            MAX_RISE=com.winterkore.engine.surface.geometry.SnowCrustRelief.MAX_RISE;
    private static final float EPS=1e-6f;
    private static final ThreadLocal<BlockPos.MutableBlockPos> CURSOR=ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private TestSnowCrust(){}

    /** Caller supplies the existing exact-test-biome resolver; no world or resolver is retained. */
    public static int forCap(BlockAndTintGetter level,BlockPos coverPos,int layers,ColorResolver exactBiome) {
        if(layers<1||layers>7||exactBiome==null)return 0;
        int x=coverPos.getX(),y=coverPos.getY(),z=coverPos.getZ();
        BlockPos.MutableBlockPos cursor=CURSOR.get();long columns=0;
        for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++)
            if(eligible(level,cursor,x+dx,y,z+dz,layers,exactBiome))columns|=1L<<((dz+2)*5+dx+2);
        return pattern(x,z,columns);
    }
    private static boolean eligible(BlockAndTintGetter level,BlockPos.MutableBlockPos p,int x,int y,int z,int layers,ColorResolver exactBiome) {
        BlockState cover=level.getBlockState(p.set(x,y,z));if(!cover.getFluidState().isEmpty())return false;
        boolean plant=TestGroundSnowModels.groundPlant(cover);
        int actual=cover.is(Blocks.SNOW)?cover.getValue(SnowLayerBlock.LAYERS):cover.is(Blocks.SNOW_BLOCK)?8:
                plant?TestGroundSnowModels.overlayLayers(level,p):0;
        if(actual!=layers)return false;
        BlockState support=level.getBlockState(p.set(x,y-1,z));
        if(!naturalSoil(support)||!support.getFluidState().isEmpty())return false;
        if((support.is(Blocks.GRASS_BLOCK)||support.is(Blocks.PODZOL))&&!support.getValue(SnowyBlock.SNOWY))return false;
        // This is the same cap-contact veto as the terrain model; plant upper parts are intentional.
        if(!plant&&!level.getBlockState(p.set(x,y+1,z)).isAir())return false;
        return SnowBiomePresentation.matchesTerrainTint(level.getBlockTint(p.set(x,y,z),exactBiome));
    }
    private static boolean naturalSoil(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.DIRT)||state.is(Blocks.COARSE_DIRT)
                ||state.is(Blocks.PODZOL)||state.is(Blocks.ROOTED_DIRT);
    }
    static int pattern(long worldX,long worldZ,long columns) {
        return com.winterkore.engine.surface.geometry.SnowCrustRelief.pattern(worldX,worldZ,columns);
    }
    static float offset(int code,float x,float z) {
        return com.winterkore.engine.surface.geometry.SnowCrustRelief.offset(code,x,z);
    }

    /** Apply only to an existing flat white cap and its upper side vertices, before native lighting. */
    public static void apply(MutableQuadView quad,int code,float top) {
        if((code&~255)!=0)throw new IllegalArgumentException("Snow crust code");
        if(code==0||!Float.isFinite(top)||top<=0||top>7f/8f)return;
        boolean topFace=true;
        for(int v=0;v<4;v++) {
            float x=quad.x(v),y=quad.y(v),z=quad.z(v);
            if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)||x<-EPS||x>1+EPS||z<-EPS||z>1+EPS)return;
            topFace&=Math.abs(y-top)<=EPS;
        }
        for(int v=0;v<4;v++)if(Math.abs(quad.y(v)-top)<=EPS)
            quad.pos(v,quad.x(v),quad.y(v)+offset(code,Math.clamp(quad.x(v),0,1),Math.clamp(quad.z(v),0,1)),quad.z(v));
        if(!topFace)return;
        // A nonplanar quad has two different face normals. Assigning the first one
        // to all vertices creates a diagonal lighting cut; share rounded vertex normals instead.
        int layers=Math.round(top*8);
        for(int v=0;v<4;v++){
            var normal=com.winterkore.engine.surface.geometry.SnowTerrainGeometry.smoothSample(
                    layers,0,code,Math.clamp(quad.x(v),0,1),Math.clamp(quad.z(v),0,1));
            quad.normal(v,(float)normal.normalX(),(float)normal.normalY(),(float)normal.normalZ());
        }
    }
}
