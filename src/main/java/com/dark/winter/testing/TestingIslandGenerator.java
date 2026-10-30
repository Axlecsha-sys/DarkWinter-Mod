// Generates clean laboratory island terrain without decoration, structures, or generation mobs.
package com.dark.winter.testing;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * New-world-only laboratory terrain. No biome decoration, trees, structures or
 * generation mobs run here; the actual biome holder remains minecraft:snowy_taiga.
 * The exact baseline is exposed to the editor so roofs never become "terrain".
 */
public final class TestingIslandGenerator extends ChunkGenerator {
    public static final int DEFAULT_RADIUS = 96;
    public static final int SEA_LEVEL = 63;
    public static final int MIN_Y = -64;
    public static final int DEPTH = 384;
    public static final String[] SHAPES = {"ROUND", "OVAL", "CRESCENT"};
    public static final MapCodec<TestingIslandGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Biome.CODEC.fieldOf("biome").forGetter(g -> g.biome),
            Configuration.CODEC.forGetter(TestingIslandGenerator::configuration),
            Codec.LONG.optionalFieldOf("terrain_seed", 19971008L).forGetter(TestingIslandGenerator::terrainSeed)
    ).apply(i, TestingIslandGenerator::new));
    private final Holder<Biome> biome;
    public record Configuration(int radius, String shape, double hills) {
        static final MapCodec<Configuration> CODEC=RecordCodecBuilder.mapCodec(i->i.group(
                Codec.intRange(32,256).optionalFieldOf("radius",DEFAULT_RADIUS).forGetter(Configuration::radius),
                Codec.STRING.optionalFieldOf("shape","ROUND").forGetter(Configuration::shape),
                Codec.doubleRange(0,3).optionalFieldOf("hills",1.0).forGetter(Configuration::hills)
        ).apply(i,Configuration::new));
        public Configuration {
            if (radius < 32 || radius > 256 || !List.of(SHAPES).contains(shape) || !Double.isFinite(hills) || hills < 0 || hills > 3)
                throw new IllegalArgumentException("Invalid island radius/shape/hills");
        }
    }
    private final java.util.concurrent.atomic.AtomicReference<Configuration> configuration;
    private final long terrainSeed;

    private TestingIslandGenerator(Holder<Biome> biome,Configuration config,long seed) {
        this(biome,config.radius(),config.shape(),seed,config.hills());
    }
    public TestingIslandGenerator(Holder<Biome> biome, int radius, String shape, long terrainSeed) {
        this(biome,radius,shape,terrainSeed,1);
    }
    public TestingIslandGenerator(Holder<Biome> biome, int radius, String shape, long terrainSeed, double hills) {
        super(new FixedBiomeSource(biome), ignored -> BiomeGenerationSettings.EMPTY);
        this.biome = biome;
        this.configuration = new java.util.concurrent.atomic.AtomicReference<>(new Configuration(radius,shape,hills));
        this.terrainSeed = terrainSeed;
    }

    public static void init() {
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR,
                Identifier.fromNamespaceAndPath("darkwinter", "testing_island"), CODEC);
    }
    public int radius() { return configuration.get().radius(); }
    public String shape() { return configuration.get().shape(); }
    public double hills() { return configuration.get().hills(); }
    public Configuration configuration() { return configuration.get(); }
    void install(Configuration next) { configuration.set(next); }
    public long terrainSeed() { return terrainSeed; }
    /** Preview target geometry; caller commits only through the protected transaction engine. */
    public TestingIslandGenerator reconfigured(int nextRadius, String nextShape) {
        return reconfigured(nextRadius,nextShape,hills());
    }
    public TestingIslandGenerator reconfigured(int nextRadius, String nextShape, double nextHills) {
        return new TestingIslandGenerator(biome,nextRadius,nextShape,terrainSeed,nextHills);
    }

    public TestingIslandGenerator reseeded(long seed) {
        Configuration c=configuration.get();return new TestingIslandGenerator(biome,c.radius(),c.shape(),seed,c.hills());
    }

    /** Y of the last solid natural block, excluding the snow layer and water. */
    public int surfaceHeight(int x, int z) { return surfaceHeight(configuration.get(),x,z); }
    int surfaceHeight(Configuration config,int x,int z) {
        int radius=config.radius();String shape=config.shape();
        double xx = shape.equals("OVAL") ? x / 1.28 : x;
        double zz = shape.equals("OVAL") ? z * 1.16 : z;
        double angle = Math.atan2(zz, xx);
        double phase = (terrainSeed & 65535L) * .000137;
        double edge = radius * (1 + .055 * Math.sin(angle * 3 + phase) + .035 * Math.cos(angle * 7 - phase));
        double distance = Math.hypot(xx, zz) / edge;
        double core = Math.max(0, 1 - distance * distance);
        double hills = (3.8 * Math.sin(x * .045 + phase) * Math.cos(z * .039 - phase)
                + 2.2 * Math.sin((x + z) * .083)) * core * config.hills();
        double height = SEA_LEVEL - 15 + 34 * Math.pow(core, .72) + hills;
        if (shape.equals("CRESCENT")) {
            double bay = Math.hypot(x - radius * .58, z) / (radius * .58);
            height -= 26 * Math.pow(Math.max(0, 1 - bay * bay), 1.2);
        }
        // A stable dry spawn clearing independent of incidental ridges.
        if (x * (long)x + z * (long)z < 144) height = Math.max(height, SEA_LEVEL + 8);
        return (int)Math.floor(height);
    }

    public int naturalHeight(int x, int z) { return surfaceHeight(x, z); }

    /** Exact generated baseline, used only with authored/protected provenance. */
    public BlockState naturalState(int x, int y, int z) {
        return material(surfaceHeight(configuration.get(),x,z),y);
    }
    BlockState naturalState(Configuration config,int x,int y,int z) { return material(surfaceHeight(config,x,z),y); }
    private static BlockState material(int top,int y) {
        if (y < MIN_Y || y >= MIN_Y + DEPTH) return Blocks.AIR.defaultBlockState();
        if (y == MIN_Y) return Blocks.BEDROCK.defaultBlockState();
        if (y > top) {
            if (top >= SEA_LEVEL && y == top + 1) return Blocks.SNOW.defaultBlockState();
            return y < SEA_LEVEL ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        if (y == top) return top >= SEA_LEVEL
                ? Blocks.GRASS_BLOCK.defaultBlockState().setValue(SnowyBlock.SNOWY, true)
                : Blocks.SAND.defaultBlockState();
        if (y > top - 4) return top >= SEA_LEVEL ? Blocks.DIRT.defaultBlockState() : Blocks.SAND.defaultBlockState();
        return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    @Override protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }
    @Override public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structures, RandomState randomState, long seed) {
        return ChunkGeneratorStructureState.createForFlat(randomState, seed, getOrigin(randomState), biomeSource, Stream.empty());
    }
    @Override public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {}
    @Override public void spawnOriginalMobs(WorldGenRegion region) {}

    @Override public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState,
            StructureManager structures, BiomeManager biomeManager, WorldGenRegion carverRegion, Set<Holder<Biome>> biomes) {
        Configuration config=configuration.get();
        var pos = new BlockPos.MutableBlockPos();
        var oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        var worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int bottom = Math.max(MIN_Y, chunk.getMinY());
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int worldX = chunk.getPos().getMinBlockX() + x;
            int worldZ = chunk.getPos().getMinBlockZ() + z;
            int surface=surfaceHeight(config,worldX,worldZ);
            int top = Math.min(chunk.getMaxY(), Math.max(SEA_LEVEL - 1, surface + 1));
            for (int y = bottom; y <= top; y++) {
                BlockState state = material(surface,y);
                if (state.isAir()) continue;
                chunk.setBlockState(pos.set(worldX, y, worldZ), state);
                oceanFloor.update(x, y, z, state);
                worldSurface.update(x, y, z, state);
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    @Override public int getSpawnHeight(LevelHeightAccessor height) { return surfaceHeight(0, 0) + 2; }
    @Override public int getGenDepth() { return DEPTH; }
    @Override public int getSeaLevel() { return SEA_LEVEL; }
    @Override public int getMinY() { return MIN_Y; }
    @Override public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor height, RandomState randomState) {
        int surface=surfaceHeight(configuration.get(),x,z);
        for (int y = Math.min(height.getMaxY(), Math.max(SEA_LEVEL, surface + 2)); y >= height.getMinY(); y--)
            if (type.isOpaque().test(material(surface,y))) return y + 1;
        return height.getMinY();
    }
    @Override public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState randomState) {
        int surface=surfaceHeight(configuration.get(),x,z);
        BlockState[] states = new BlockState[height.getHeight()];
        for (int i = 0; i < states.length; i++) states[i] = material(surface,height.getMinY() + i);
        return new NoiseColumn(height.getMinY(), states);
    }
    @Override public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feet, SamplerContext context) {
        result.add("DarkWinter laboratory island: radius=" + radius() + ", shape=" + shape() + ", hills=" + hills() + ", baseline=" + surfaceHeight(feet.getX(), feet.getZ()));
    }
}
