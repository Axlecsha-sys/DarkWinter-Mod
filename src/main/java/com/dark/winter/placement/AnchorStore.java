package com.dark.winter.placement;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
/** Native dimension saved data; ordinary block/entity data remains in the native chunk files. */
public final class AnchorStore extends SavedData {
    public static final Codec<AnchorStore> CODEC=Anchor.CODEC.listOf().fieldOf("anchors").codec().xmap(AnchorStore::new,AnchorStore::entries);
    public static final SavedDataType<AnchorStore> TYPE=new SavedDataType<>(
        Identifier.fromNamespaceAndPath("darkwinter","surface_anchors"),AnchorStore::new,CODEC,DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    private final LinkedHashMap<Long,Anchor> anchors=new LinkedHashMap<>();
    public AnchorStore(){}
    private AnchorStore(List<Anchor> entries){for(Anchor a:entries)if(anchors.put(a.owner().asLong(),a)!=null)throw new IllegalArgumentException("Duplicate surface anchor");}
    public static AnchorStore get(ServerLevel level){return level.getDataStorage().computeIfAbsent(TYPE);}
    public List<Anchor> entries(){return List.copyOf(anchors.values());}
    void put(Anchor anchor){anchors.put(anchor.owner().asLong(),anchor);setDirty();}
    void remove(BlockPos owner){if(anchors.remove(owner.asLong())!=null)setDirty();}
}
