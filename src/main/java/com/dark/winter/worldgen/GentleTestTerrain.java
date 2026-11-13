// Creates a low-relief profile only for explicitly selected fresh single-biome test worlds.
package com.dark.winter.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** New-world construction only. This class has no world-loading or block-editing entry point. */
public final class GentleTestTerrain {
    private static final ResourceKey<NormalNoise> BROAD = noise("test_gentle_broad_v2");
    private static final ResourceKey<NormalNoise> DETAIL = noise("test_gentle_detail_v2");

    private GentleTestTerrain() {}

    /** Custom/multi-biome generators, islands, flat worlds and other biomes retain exact identity. */
    public static boolean eligible(ChunkGenerator selected) {
        return selected instanceof NoiseBasedChunkGenerator noise
                && noise.generatorSettings().is(NoiseGeneratorSettings.OVERWORLD)
                && selected.getBiomeSource() instanceof FixedBiomeSource fixed
                && fixed.getNoiseBiome(0, 0, 0).is(TestBiomeCompatibility.BIOME);
    }

    /** Called only while the explicit Create World action prepares its selected dimensions. */
    public static ChunkGenerator forExplicitNewWorld(HolderLookup.Provider registries, ChunkGenerator selected) {
        if (!eligible(selected)) return selected;
        var source = (NoiseBasedChunkGenerator) selected;
        NoiseGeneratorSettings base = source.generatorSettings().value();
        var noises = registries.lookupOrThrow(Registries.NOISE);

        // Registry-backed noise holders keep native per-key seed derivation after inline settings reload.
        // yScale=0 produces one global X/Z field; no local chunk coordinates or chunk RNG enter it.
        DensityFunction broad = DensityFunctions.noise(noises.getOrThrow(BROAD), 1.5, 0.0).clamp(-1, 1);
        DensityFunction detail = DensityFunctions.noise(noises.getOrThrow(DETAIL), 1.0, 0.0).clamp(-1, 1);
        DensityFunction surface = DensityFunctions.cache(broad.mul(8f).add(detail.mul(2f)).add(base.seaLevel() + 13f));
        int minY = base.noiseSettings().minY();
        int maxY = minY + base.noiseSettings().height() - 1;
        DensityFunction y = DensityFunctions.yClampedGradient(minY, maxY, minY, maxY);
        DensityFunction lowRelief = surface.sub(y).mul(.125f).add(DensityFunctions.beardifier());
        // Preserve existing deep density/cave field. Only the upper terrain transitions to low relief.
        DensityFunction finalDensity = DensityFunctions.lerp(
                DensityFunctions.yClampedGradient(32, 56, 0, 1),
                base.noiseRouter().finalDensity(), lowRelief);
        NoiseRouter old = base.noiseRouter();
        NoiseRouter router = new NoiseRouter(old.temperature(), old.vegetation(), old.continents(), old.erosion(),
                old.depth(), old.ridges(), surface, finalDensity);

        // Native aquifer surface prediction must follow the same height field as the new upper terrain.
        // All fluid/noise parameters remain the original ones; only their preliminary surface changes.
        var aquifers = base.aquifers().map(a -> new Aquifer.Config(a.barrierNoise(), a.fluidLevelFloodednessNoise(),
                a.fluidLevelSpreadNoise(), a.lavaNoise(), a.exclusion(), surface));
        var debug = new NoiseGeneratorSettings.DebugFunctions(base.debugFunctions().functions().stream().map(entry ->
                switch (entry.label()) {
                    case "N" -> new NoiseGeneratorSettings.DebugFunctionEntry(entry.label(), finalDensity);
                    case "PS" -> new NoiseGeneratorSettings.DebugFunctionEntry(entry.label(), surface);
                    default -> entry;
                }).toList());
        NoiseGeneratorSettings settings = new NoiseGeneratorSettings(base.noiseSettings(), base.defaultBlock(),
                base.defaultFluid(), router, base.materialRule(), base.spawnTarget(), base.seaLevel(),
                base.disableMobGeneration(), aquifers, base.useLegacyRandomSource(), debug);
        // Native NoiseBasedChunkGenerator owns terrain, getBaseHeight and getBaseColumn with this one router.
        // Holder.direct is supported by actual 26.3 NoiseGeneratorSettings.CODEC; settings persist inline.
        return new NoiseBasedChunkGenerator(selected.getBiomeSource(), Holder.direct(settings));
    }

    private static ResourceKey<NormalNoise> noise(String name) {
        return ResourceKey.create(Registries.NOISE, Identifier.fromNamespaceAndPath("darkwinter", name));
    }
}
