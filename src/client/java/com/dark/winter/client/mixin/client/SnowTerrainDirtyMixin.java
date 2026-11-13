// Invalidates dependent snow owners across section boundaries for the two-block crust and terrace halo.
package com.dark.winter.client.mixin.client;

import com.dark.winter.worldgen.TestBiomeCompatibility;
import com.winterkore.engine.surface.client.SnowSurfaceClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class SnowTerrainDirtyMixin {
    @Shadow private @Nullable ClientLevel level;
    @Shadow private void setSectionDirty(int x,int y,int z,boolean playerChanged){throw new AssertionError("Mixin shadow");}

    @Inject(method="setBlocksDirty(IIIIII)V",at=@At("TAIL"),require=1,allow=1)
    private void darkwinter$snowOwners(int x0,int y0,int z0,int x1,int y1,int z1,CallbackInfo ci) {
        SnowSurfaceClient.blockChanged(x0,y0,z0,x1,y1,z1);
        darkwinter$dirtySnowHalo(x0,y0,z0,x1,y1,z1,false);
    }

    @Inject(method="setBlockDirty(Lnet/minecraft/core/BlockPos;Z)V",at=@At("TAIL"),require=1,allow=1)
    private void darkwinter$singleSnowOwner(BlockPos pos,boolean playerChanged,CallbackInfo ci) {
        SnowSurfaceClient.blockChanged(pos.getX(),pos.getY(),pos.getZ(),pos.getX(),pos.getY(),pos.getZ());
        darkwinter$dirtySnowHalo(pos.getX(),pos.getY(),pos.getZ(),pos.getX(),pos.getY(),pos.getZ(),playerChanged);
    }

    private void darkwinter$dirtySnowHalo(int x0,int y0,int z0,int x1,int y1,int z1,boolean playerChanged) {
        if(level==null)return;
        int nx0=SectionPos.blockToSectionCoord(x0-1),nx1=SectionPos.blockToSectionCoord(x1+1);
        int ny0=SectionPos.blockToSectionCoord(y0-1),ny1=SectionPos.blockToSectionCoord(y1+1);
        int nz0=SectionPos.blockToSectionCoord(z0-1),nz1=SectionPos.blockToSectionCoord(z1+1);
        int ex0=SectionPos.blockToSectionCoord(x0-2),ex1=SectionPos.blockToSectionCoord(x1+2);
        int ey0=SectionPos.blockToSectionCoord(y0-2),ey1=SectionPos.blockToSectionCoord(y1+2);
        int ez0=SectionPos.blockToSectionCoord(z0-2),ez1=SectionPos.blockToSectionCoord(z1+2);
        if(ex0==nx0&&ex1==nx1&&ey0==ny0&&ey1==ny1&&ez0==nz0&&ez1==nz1)return;
        BlockPos.MutableBlockPos sample=new BlockPos.MutableBlockPos();
        int sampleY=y0+(y1-y0)/2;
        for(int z=ez0;z<=ez1;z++)for(int x=ex0;x<=ex1;x++) {
            if(!darkwinter$testSection(sample,x,sampleY,z))continue;
            for(int y=ey0;y<=ey1;y++)
                if(x<nx0||x>nx1||y<ny0||y>ny1||z<nz0||z>nz1)setSectionDirty(x,y,z,playerChanged);
        }
    }

    private boolean darkwinter$testSection(BlockPos.MutableBlockPos p,int sectionX,int y,int sectionZ) {
        if(!level.getChunkSource().hasChunk(sectionX,sectionZ))return false;
        int x=SectionPos.sectionToBlockCoord(sectionX),z=SectionPos.sectionToBlockCoord(sectionZ);
        for(int corner=0;corner<5;corner++) {
            int dx=corner==4?8:(corner&1)*15,dz=corner==4?8:(corner>>1)*15;
            p.set(x+dx,y,z+dz);
            if(level.getBiome(p).is(TestBiomeCompatibility.BIOME))return true;
        }
        return false;
    }
}
