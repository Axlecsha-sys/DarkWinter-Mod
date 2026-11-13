// Binds explicitly opted-in snow-terrain biomes once per client level without retaining a world.
package com.dark.winter.client;
import com.dark.winter.worldgen.TestBiomeCompatibility;
import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.ColorResolver;
import net.fabricmc.fabric.api.client.rendering.v1.ColorResolverRegistry;
public final class SnowBiomePresentation {
    private static volatile Set<Biome> snow=Set.of();
    private static boolean initialized,resolverRegistered;
    // Pure white becomes ARGB -1, which is BlockTintCache's uncached sentinel.
    // This marker is a membership predicate only; it never colors rendered vertices.
    public static final int MEMBERSHIP_COLOR = Boolean.parseBoolean(
            System.getProperty("darkwinter.render.snowMaskCache","true")) ? 0xFEFEFE : 0xFFFFFF;
    public static final ColorResolver TERRAIN_MEMBERSHIP =
            (biome,x,z)->contains(biome)?MEMBERSHIP_COLOR:0;
    public static synchronized void registerTerrainResolver(){
        if(resolverRegistered)return;
        ColorResolverRegistry.register(TERRAIN_MEMBERSHIP);resolverRegistered=true;
    }
    public static boolean matchesTerrainTint(int color){return (color&0xFFFFFF)==MEMBERSHIP_COLOR;}
    private SnowBiomePresentation(){}
    public static synchronized void init(){
        if(initialized)return;initialized=true;
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client,level)->{
            Set<Biome> selected=Collections.newSetFromMap(new IdentityHashMap<>());
            level.registryAccess().lookupOrThrow(Registries.BIOME).listElements().forEach(h->{if(TestBiomeCompatibility.snowTerrain(h))selected.add(h.value());});
            snow=Collections.unmodifiableSet(selected);level.clearTintCaches();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->snow=Set.of());
    }
    public static boolean contains(Biome biome){return snow.contains(biome);}
}
