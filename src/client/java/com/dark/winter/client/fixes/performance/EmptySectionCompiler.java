// Skips native meshing only when the immutable central snapshot is known to contain air.
package com.dark.winter.client.fixes.performance;

import com.dark.winter.client.mixin.client.SectionCopySnapshotAccess;
import com.dark.winter.client.mixin.client.SectionRegionSnapshotAccess;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;

public final class EmptySectionCompiler {
    private EmptySectionCompiler() {}

    public static SectionCompiler.@Nullable Results tryCompile(SectionPos position, RenderSectionRegion region) {
        SectionRegionSnapshotAccess snapshot = (SectionRegionSnapshotAccess) region;
        if (position.x() != snapshot.darkwinter$minSectionX() + 1
                || position.y() != snapshot.darkwinter$minSectionY() + 1
                || position.z() != snapshot.darkwinter$minSectionZ() + 1) {
            return null;
        }
        SectionCopySnapshotAccess center = (SectionCopySnapshotAccess) snapshot.darkwinter$sections()[13];
        if (center.darkwinter$debug() || center.darkwinter$section() != null) {
            return null;
        }
        SectionCompiler.Results result = new SectionCompiler.Results();
        result.visibilitySet.setAll(true);
        return result;
    }
}
