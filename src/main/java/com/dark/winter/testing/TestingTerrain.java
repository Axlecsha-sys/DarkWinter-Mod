// Edits natural island terrain using generated baseline and placement provenance.
package com.dark.winter.testing;

import java.io.IOException;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Natural-only island tools. Generated baseline plus provenance, never block type alone. */
final class TestingTerrain {
    private TestingTerrain() {}
    private static TestingIslandGenerator generator(TestingServer.Context c) throws IOException {
        if(c.server.overworld().getChunkSource().getGenerator() instanceof TestingIslandGenerator g)return g;
        throw new IOException("Безопасные природные кисти доступны на островном генераторе; для старых карт используйте выделение объектов");
    }
    static long column(int x,int z){return BlockPos.asLong(x,0,z);}
    static int extent(TestingServer.Context c,TestingIslandGenerator g)throws IOException {
        int extent=(int)Math.ceil(g.radius()*1.45)+4;
        for(long key:c.heights.keySet()){BlockPos p=BlockPos.of(key);extent=Math.max(extent,Math.max(Math.abs(p.getX()),Math.abs(p.getZ())));}
        if(extent>192)throw new IOException("Изменения вне192м обрабатывайте отдельной областью или полной копией");return extent;
    }
    static Set<net.minecraft.world.level.ChunkPos> chunks(int extent) {
        Set<net.minecraft.world.level.ChunkPos> chunks=new LinkedHashSet<>();
        for(int x=Math.floorDiv(-extent,16);x<=Math.floorDiv(extent,16);x++)for(int z=Math.floorDiv(-extent,16);z<=Math.floorDiv(extent,16);z++)chunks.add(new net.minecraft.world.level.ChunkPos(x,z));return Set.copyOf(chunks);
    }
    static void requireLoaded(TestingServer.Context c,Collection<net.minecraft.world.level.ChunkPos> chunks)throws IOException {
        for(var chunk:chunks)if(!c.server.overworld().hasChunk(chunk.x(),chunk.z()))throw new IOException("Сначала подготовьте всю природную область: "+chunk);
    }
    static int height(TestingServer.Context c,TestingIslandGenerator g,int x,int z) {
        int original=g.surfaceHeight(x,z);
        if(!c.server.overworld().hasChunkAt(new BlockPos(x,original,z)))throw new IllegalStateException("Природная высота запрошена вне подготовленной области: "+x+","+z);
        if(!c.heights.containsKey(column(x,z))&&c.server.overworld().getBlockState(new BlockPos(x,original,z)).equals(g.naturalState(x,original,z)))return original;
        for(int y=c.server.overworld().getMaxY()-1;y>=c.server.overworld().getMinY();y--) {
            BlockPos p=new BlockPos(x,y,z);BlockState s=c.server.overworld().getBlockState(p);
            if(s.isAir()||!s.getFluidState().isEmpty()||s.is(Blocks.SNOW))continue;
            String origin=c.edits.origins.get(p.asLong());
            if("terrain".equals(origin)||"surface".equals(origin)||(origin==null&&y<=original&&g.naturalState(x,y,z).equals(s)))return y;
        }return original;
    }
    private static boolean natural(TestingServer.Context c,TestingIslandGenerator g,BlockPos p) {
        if(!c.server.overworld().hasChunkAt(p)||c.server.overworld().isOutsideBuildHeight(p))return false;
        if(TestingServer.naturalProtectedAt(c,p)||c.server.overworld().getBlockEntity(p)!=null)return false;
        String origin=c.edits.origins.get(p.asLong());
        return "terrain".equals(origin)||"surface".equals(origin)||(origin==null&&g.naturalState(p.getX(),p.getY(),p.getZ()).equals(c.server.overworld().getBlockState(p)));
    }
    private static BlockState surface(String biome) {
        return biome.endsWith("desert")?Blocks.SAND.defaultBlockState():Blocks.GRASS_BLOCK.defaultBlockState().setValue(SnowyBlock.SNOWY,biome.endsWith("snowy_taiga"));
    }
    static void brush(TestingServer.Context c,String value) throws IOException {
        String[] v=value.split("\\|");String tool=v[0];BlockPos center=TestingServer.position(v[1]);
        int radius=v.length>2?Integer.parseInt(v[2]):c.settings.radius;
        int power=v.length>3?Integer.parseInt(v[3]):c.settings.strength;
        if(radius<1||radius>24||power<1||power>8)throw new IOException("Кисть: радиус 1–24, сила 1–8");
        TestingIslandGenerator g=generator(c);ServerLevel world=c.server.overworld();
        for(int x=Math.floorDiv(center.getX()-radius-1,16);x<=Math.floorDiv(center.getX()+radius+1,16);x++)for(int z=Math.floorDiv(center.getZ()-radius-1,16);z<=Math.floorDiv(center.getZ()+radius+1,16);z++)if(!world.hasChunk(x,z))throw new IOException("Кисть и её соседние столбцы должны быть загружены");
        Map<BlockPos,TestingEdits.Change> changes=new LinkedHashMap<>();
        int blocked=0,flat=height(c,g,center.getX(),center.getZ());
        for(int x=center.getX()-radius;x<=center.getX()+radius;x++)for(int z=center.getZ()-radius;z<=center.getZ()+radius;z++) {
            if(c.settings.brushShape.equals("circle")&&(x-center.getX())*(x-center.getX())+(z-center.getZ())*(z-center.getZ())>radius*radius)continue;
            if(Math.abs(x)>g.radius()+32||Math.abs(z)>g.radius()+32)continue;
            int h=height(c,g,x,z);BlockPos top=new BlockPos(x,h,z);
            if(!world.hasChunkAt(top)||TestingServer.naturalProtectedAt(c,top)){blocked++;continue;}
            int target=h;
            switch(tool) {
                case "raise" -> target=h+power;
                case "lower","erase" -> target=h-power;
                case "flatten" -> target=h+Math.clamp(flat-h,-power,power);
                case "smooth" -> {
                    int sum=height(c,g,x-1,z)+height(c,g,x+1,z)+height(c,g,x,z-1)+height(c,g,x,z+1);
                    target=h+Math.clamp(Math.round(sum/4f)-h,-power,power);
                }
                case "shore" -> target=h+Math.clamp(TestingIslandGenerator.SEA_LEVEL-1-h,-power,power);
                case "material" -> add(c,g,changes,top,surface(c.settings.biome),"surface");
                case "drain" -> {
                    for(int y=h+1;y<=Math.max(center.getY(),TestingIslandGenerator.SEA_LEVEL);y++){BlockPos p=new BlockPos(x,y,z);if(world.getBlockState(p).is(Blocks.WATER))add(c,g,changes,p,Blocks.AIR.defaultBlockState(),"terrain");}
                }
                case "water" -> {
                    int y=center.getY();if(y<world.getMinY()+1||y>=world.getMaxY()-1)throw new IOException("Уровень воды за пределами мира");
                    for(int yy=Math.min(h+1,y);yy<=y;yy++)add(c,g,changes,new BlockPos(x,yy,z),Blocks.WATER.defaultBlockState(),"terrain");
                }
                default -> throw new IOException("Неизвестная кисть "+tool);
            }
            if(target<world.getMinY()+2||target>world.getMaxY()-3)continue;
            if(target!=h) {
                for(int y=Math.min(h,target);y<=Math.max(h,target)+1;y++) {
                    BlockPos p=new BlockPos(x,y,z);BlockState next;
                    if(y<target)next=Blocks.DIRT.defaultBlockState();
                    else if(y==target)next=surface(c.settings.biome);
                    else if(y==target+1&&c.settings.biome.endsWith("snowy_taiga")&&target>=TestingIslandGenerator.SEA_LEVEL)next=Blocks.SNOW.defaultBlockState();
                    else next=y<TestingIslandGenerator.SEA_LEVEL?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState();
                    add(c,g,changes,p,next,y==target?"surface":"terrain");
                }
                c.heights.put(column(x,z),target);
            }
        }
        if(blocked>0)c.event("protected_skipped",""+blocked);
        c.edits.submit("Кисть "+tool+" (защищённых столбцов "+blocked+")",new ArrayList<>(changes.values()));
    }
    private static void add(TestingServer.Context c,TestingIslandGenerator g,Map<BlockPos,TestingEdits.Change> changes,BlockPos p,BlockState next,String origin) {
        if(!natural(c,g,p))return;
        if(c.server.overworld().getBlockState(p).equals(next))return;
        changes.put(p,c.edits.change(p,next,origin));
    }
    static void biome(TestingServer.Context c,String biome) throws IOException {
        TestingIslandGenerator g=generator(c);String key=biome.contains(":")?biome:"minecraft:"+biome;
        c.server.overworld().registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.parse(key)));
        int extent=extent(c,g);requireLoaded(c,chunks(extent));TestingServer.rebuildSupport(c);
        Map<BlockPos,TestingEdits.Change> changes=new LinkedHashMap<>();int skipped=0;
        for(int x=-extent;x<=extent;x++)for(int z=-extent;z<=extent;z++) {
            int h=height(c,g,x,z);if(h<TestingIslandGenerator.SEA_LEVEL-1)continue;
            BlockPos p=new BlockPos(x,h,z);
            if(!c.server.overworld().hasChunkAt(p))throw new IOException("Загрузите всю область острова перед сменой биома");
            if(TestingServer.naturalProtectedAt(c,p)){skipped++;continue;}
            add(c,g,changes,p,surface(biome),"surface");
            BlockPos above=p.above();BlockState s=c.server.overworld().getBlockState(above);
            if(biome.endsWith("snowy_taiga")&&h>=TestingIslandGenerator.SEA_LEVEL) {
                if(s.isAir())add(c,g,changes,above,Blocks.SNOW.defaultBlockState(),"surface");
            } else if(s.is(Blocks.SNOW))add(c,g,changes,above,Blocks.AIR.defaultBlockState(),"surface");
        }
        c.edits.submitBiome("Биом "+key+" (защищённых столбцов "+skipped+")",new ArrayList<>(changes.values()),c.settings.biome,key);
    }
    static void applyBiome(ServerLevel world,String key)throws IOException {
        if(!(world.getChunkSource().getGenerator() instanceof TestingIslandGenerator g))return;
        String id=key.contains(":")?key:"minecraft:"+key;
        var biome=world.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.parse(id)));
        var context=TestingServer.context(world.getServer());if(context==null)throw new IOException("Нет контекста природной операции");
        Set<net.minecraft.world.level.ChunkPos> footprint=chunks(extent(context,g));requireLoaded(context,footprint);
        List<ChunkAccess> chunks=new ArrayList<>();
        for(var position:footprint) {
            var chunk=world.getChunk(position.x(),position.z());chunk.fillBiomesFromNoise((qx,qy,qz)->biome);chunk.markUnsaved();chunks.add(chunk);
        }
        world.getChunkSource().chunkMap.resendBiomesForChunks(chunks);
        TestingServer.biomeCommitted(world.getServer(),key);
    }
    static void vegetation(TestingServer.Context c,String category) throws IOException {
        if(!Set.of("grass","bushes","trees").contains(category))throw new IOException("Неизвестная категория растений");
        TestingIslandGenerator g=generator(c);int extent=extent(c,g);requireLoaded(c,chunks(extent+4));TestingServer.rebuildSupport(c);
        Map<BlockPos,TestingEdits.Change> changes=new LinkedHashMap<>();int skipped=0;
        if(c.edits.origins.containsValue(category)) {
            Set<BlockPos> remaining=new LinkedHashSet<>();for(var entry:c.edits.origins.entrySet())if(category.equals(entry.getValue()))remaining.add(BlockPos.of(entry.getKey()));
            for(BlockPos p:remaining) {
                if(!c.server.overworld().hasChunkAt(p))throw new IOException("Выгруженный член растительности: "+p);
                BlockState state=c.server.overworld().getBlockState(p);
                boolean known=state.isAir()||(category.equals("trees")?(state.is(net.minecraft.tags.BlockTags.LOGS)||state.is(net.minecraft.tags.BlockTags.LEAVES)):
                        state.is(Blocks.FERN)||state.is(Blocks.SHORT_GRASS)||state.is(Blocks.DEAD_BUSH)||state.is(Blocks.SWEET_BERRY_BUSH)||state.is(Blocks.AZALEA));
                if(!known||state.hasBlockEntity())throw new IOException("Член растительности заменён другим объектом: "+p);
            }
            while(!remaining.isEmpty()) {
                BlockPos first=remaining.iterator().next();Set<BlockPos> plant=new LinkedHashSet<>();Deque<BlockPos> todo=new ArrayDeque<>();todo.add(first);remaining.remove(first);
                while(!todo.isEmpty()){BlockPos p=todo.removeFirst();plant.add(p);if(category.equals("trees"))for(var direction:net.minecraft.core.Direction.values()){BlockPos neighbor=p.relative(direction);if(remaining.remove(neighbor))todo.add(neighbor);}}
                if(plant.stream().anyMatch(p->TestingServer.naturalProtectedAt(c,p))){skipped++;continue;}
                for(BlockPos p:plant) {
                    int h=height(c,g,p.getX(),p.getZ());
                    BlockState cover=p.getY()==h+1&&h>=TestingIslandGenerator.SEA_LEVEL&&c.settings.biome.endsWith("snowy_taiga")
                            ?Blocks.SNOW.defaultBlockState():p.getY()>h&&p.getY()<TestingIslandGenerator.SEA_LEVEL
                            ?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState();
                    changes.put(p,c.edits.change(p,cover,g.naturalState(p.getX(),p.getY(),p.getZ()).equals(cover)?null:"surface"));
                }
            }
        } else {
            Random random=new Random(c.server.overworld().getSeed()^category.hashCode());
            int step=Math.max(category.equals("trees")?7:3,65-c.settings.density);
            for(int x=-extent;x<=extent;x+=step)for(int z=-extent;z<=extent;z+=step) {
                int xx=x+random.nextInt(Math.max(1,step/2)),zz=z+random.nextInt(Math.max(1,step/2));
                if(xx>extent||zz>extent)continue;
                int h=height(c,g,xx,zz);if(h<TestingIslandGenerator.SEA_LEVEL)continue;
                BlockPos base=new BlockPos(xx,h+1,zz);List<Map.Entry<BlockPos,BlockState>> plant=new ArrayList<>();
                String requested=category.equals("trees")?c.settings.treeSpecies:category.equals("bushes")?c.settings.bushSpecies:c.settings.grassSpecies;
                if(requested.equals("auto")) {
                    if(c.settings.biome.endsWith("desert")&&!category.equals("bushes"))continue;
                    requested=category.equals("trees")?(c.settings.biome.endsWith("taiga")?"minecraft:spruce":"minecraft:oak"):
                            category.equals("bushes")?(c.settings.biome.endsWith("desert")?"minecraft:dead_bush":c.settings.biome.endsWith("taiga")?"minecraft:sweet_berry_bush":"minecraft:azalea"):
                            (c.settings.biome.endsWith("taiga")?"minecraft:fern":"minecraft:short_grass");
                }
                if(category.equals("trees")) {
                    Block log=requested.endsWith("birch")?Blocks.BIRCH_LOG:requested.endsWith("oak")?Blocks.OAK_LOG:Blocks.SPRUCE_LOG;
                    Block leaves=requested.endsWith("birch")?Blocks.BIRCH_LEAVES:requested.endsWith("oak")?Blocks.OAK_LEAVES:Blocks.SPRUCE_LEAVES;
                    int height=5+random.nextInt(3);
                    for(int y=0;y<height;y++)plant.add(Map.entry(base.above(y),log.defaultBlockState()));
                    for(int y=2;y<=height;y++) {
                        int r=Math.max(0,(height-y)/2);
                        for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++)if(dx!=0||dz!=0||y==height)
                            plant.add(Map.entry(base.offset(dx,y,dz),leaves.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true)));
                    }
                } else {
                    BlockState state=TestingServer.block(requested);
                    if(state.is(Blocks.SWEET_BERRY_BUSH))state=state.setValue(SweetBerryBushBlock.AGE,3);
                    plant.add(Map.entry(base,state));
                }
                boolean allowed=true;
                for(var e:plant) {
                    BlockPos p=e.getKey();if(!c.server.overworld().hasChunkAt(p))throw new IOException("Соседняя часть дерева не подготовлена: "+p);
                    if(c.server.overworld().isOutsideBuildHeight(p)||Math.abs((long)p.getX())>192||Math.abs((long)p.getZ())>192||TestingServer.naturalProtectedAt(c,p)
                            ||!c.server.overworld().getBlockState(p).canBeReplaced()||changes.containsKey(p)){allowed=false;break;}
                }
                if(!allowed){skipped++;continue;}
                for(var e:plant)changes.put(e.getKey(),c.edits.change(e.getKey(),e.getValue(),category));
            }
        }
        c.edits.submit("Растительность: "+category+" (пропущено целых растений "+skipped+")",new ArrayList<>(changes.values()));
    }
}
