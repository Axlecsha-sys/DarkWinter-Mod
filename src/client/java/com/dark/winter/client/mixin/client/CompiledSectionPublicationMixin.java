// Adds a lightweight publication token to native meshes without retaining task snapshots.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.fixes.performance.SectionMeshPublication;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(CompiledSectionMesh.class)
abstract class CompiledSectionPublicationMixin implements SectionMeshPublication.Mesh {
    @Unique private SectionMeshPublication.Token darkwinter$publicationToken;
    @Override public SectionMeshPublication.Token darkwinter$meshToken(){return darkwinter$publicationToken;}
    @Override public void darkwinter$bindMeshToken(SectionMeshPublication.Token token){darkwinter$publicationToken=token;}
}
