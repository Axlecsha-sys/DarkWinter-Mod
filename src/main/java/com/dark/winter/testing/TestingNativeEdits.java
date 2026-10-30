// Records native editor gestures inside marked-save transaction scopes.
package com.dark.winter.testing;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.LoggerFactory;

/** Exact native EDIT gestures. Nothing is observed outside a marked local owner's synchronous scope. */
public final class TestingNativeEdits {
    private static final ThreadLocal<Gesture> ACTIVE=new ThreadLocal<>();
    private static final int LIMIT=32768;
    private static volatile long recorded,suppressed;
    private TestingNativeEdits(){}
    private static boolean initialized;
    public static void init(){
        if(initialized)return;initialized=true;
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server->{
            var context=TestingServer.context(server);if(context==null)return;
            try{context.edits.stopNative();}
            catch(java.io.IOException failure){
                // Preserve the journal and let vanilla finish saving players/chunks even if metadata storage failed.
                context.edits.nativeSaveFailed(failure);
            }
        });
    }
    public static boolean chunkSaving(ServerLevel level,net.minecraft.world.level.chunk.ChunkAccess chunk){
        var server=level.getServer();var context=TestingServer.context(server);
        if(context==null||level!=server.overworld())return true;
        if(!server.isSameThread())return false;
        try{context.edits.nativeBeforeSave(chunk);return true;}
        catch(Exception failure){context.edits.nativeSaveFailed(failure);return false;}
    }
    public static void chunkQueued(ServerLevel level,net.minecraft.world.level.ChunkPos pos,java.util.concurrent.CompletableFuture<Void> future){
        var server=level.getServer();var context=TestingServer.context(server);
        if(context!=null&&level==server.overworld()&&server.isSameThread())context.edits.nativeQueued(pos,future);
    }

    private static final class Gesture {
        final TestingServer.Context context;final ServerPlayer player;final String label;
        final LinkedHashMap<BlockPos,TestingEdits.Cell> before=new LinkedHashMap<>();
        int depth;boolean finishing,rejected,canSave;String error="";
        com.dark.winter.online.world.IslandAccess.Permission permission;
        Gesture(TestingServer.Context context,ServerPlayer player,String label){this.context=context;this.player=player;this.label=label;permission=label.contains("разрушение")?com.dark.winter.online.world.IslandAccess.Permission.BREAK:label.contains("установка блоков")?com.dark.winter.online.world.IslandAccess.Permission.BUILD:com.dark.winter.online.world.IslandAccess.Permission.INTERACT;}
    }
    public static final class Scope implements AutoCloseable {
        private static final Scope EMPTY=new Scope(null);
        private final Gesture gesture;private boolean closed;
        private Scope(Gesture gesture){this.gesture=gesture;}
        public void abort(){if(gesture!=null){gesture.rejected=true;gesture.error="Нативное действие завершилось ошибкой";}}
        public boolean accepted(){return gesture==null||!gesture.rejected;}
        public void seed(BlockPos pos){if(gesture!=null)seedStructure(gesture,pos);}
        @Override public void close(){
            if(gesture==null||closed)return;closed=true;
            if(--gesture.depth>0)return;
            try{finish(gesture);}finally{ACTIVE.remove();}
        }
    }
    private static TestingServer.Context owner(ServerPlayer player){
        if(player==null)return null;MinecraftServer server=player.level().getServer();
        TestingServer.Context context=TestingServer.context(server);
        if(context==null||!server.isSameThread()||!server.isSingleplayer()
                ||!player.level().dimension().equals(Level.OVERWORLD))return null;
        var host=com.dark.winter.online.server.OnlineGameHost.get(server);
        if(host!=null)return host.can(player,com.dark.winter.online.world.IslandAccess.Permission.VIEW)?context:null;
        return !server.isPublished()&&server.isSingleplayerOwner(player.nameAndId())?context:null;
    }
    public static boolean refused(ServerPlayer player){
        TestingServer.Context context=owner(player);
        return context!=null&&(context.readOnlyPreview||TestingServer.busy(context.server));
    }
    public static Scope begin(ServerPlayer player,String label){
        TestingServer.Context context=owner(player);
        if(context==null||context.readOnlyPreview||!"EDIT".equals(context.settings.mode)||TestingServer.busy(context.server))return Scope.EMPTY;
        Gesture gesture=ACTIVE.get();
        if(gesture==null){gesture=new Gesture(context,player,label);ACTIVE.set(gesture);}
        if(gesture.context!=context||gesture.player!=player||gesture.finishing)return Scope.EMPTY;
        if(label.contains("установка блоков"))gesture.permission=com.dark.winter.online.world.IslandAccess.Permission.BUILD;
        if(label.contains("разрушение"))gesture.permission=com.dark.winter.online.world.IslandAccess.Permission.BREAK;
        try(var actor=TestingActors.enter(context.server,player,Set.of(gesture.permission))){}
        catch(java.io.IOException denied){gesture.rejected=true;gesture.error="Право нативного редактирования отозвано";}
        gesture.depth++;return new Scope(gesture);
    }
    /** Called at Level.setBlock HEAD, before the native block/BE removal and all neighbour callbacks. */
    public static boolean before(Level level,BlockPos pos){
        Gesture gesture=ACTIVE.get();
        if(gesture==null||gesture.finishing||level!=gesture.context.server.overworld())return true;
        if(gesture.rejected)return false;
        if(!level.hasChunkAt(pos)||level.isOutsideBuildHeight(pos)||TestingServer.zoneProtected(gesture.context,pos)){
            gesture.rejected=true;gesture.error="Изменение пересекает незагруженную или защищённую область";return false;
        }
        capture(gesture,pos);return !gesture.rejected;
    }
    private static void seedStructure(Gesture gesture,BlockPos pos){
        var world=gesture.context.server.overworld();if(!world.hasChunkAt(pos)||world.isOutsideBuildHeight(pos))return;
        LinkedHashSet<BlockPos> positions=new LinkedHashSet<>();positions.add(pos);
        var state=world.getBlockState(pos);var entity=world.getBlockEntity(pos);
        if(entity instanceof com.dark.winter.block.entity.WallModuleBlockEntity wall&&state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)){
            BlockPos master=wall.getMasterPos();var shape=wall.getShape();
            if(world.hasChunkAt(master)&&world.getBlockEntity(master) instanceof com.dark.winter.block.entity.WallModuleBlockEntity original)shape=original.getShape();
            positions.addAll(shape.getPositions(master,state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)));
        }
        if(state.getBlock() instanceof com.dark.winter.block.FurnitureBlock furniture)
            positions.addAll(furniture.positions(furniture.origin(pos,state),state.getValue(com.dark.winter.block.FurnitureBlock.FACING)));
        var half=net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF;
        if(state.hasProperty(half))positions.add(state.getValue(half)==net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER?pos.above():pos.below());
        var bed=net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART;
        var facing=net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
        if(state.hasProperty(bed)&&state.hasProperty(facing))positions.add(pos.relative(state.getValue(bed)==net.minecraft.world.level.block.state.properties.BedPart.FOOT?state.getValue(facing):state.getValue(facing).getOpposite()));
        if(state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock&&state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)!=net.minecraft.world.level.block.state.properties.ChestType.SINGLE)
            positions.add(net.minecraft.world.level.block.ChestBlock.getConnectedBlockPos(pos,state));
        for(BlockPos part:positions)if(world.hasChunkAt(part)&&!world.isOutsideBuildHeight(part))capture(gesture,part);
    }
    private static void capture(Gesture gesture,BlockPos pos){
        if(gesture.before.containsKey(pos)||gesture.finishing||gesture.rejected)return;
        if(gesture.before.size()>=LIMIT){gesture.rejected=true;gesture.error="Нативное действие превышает 32768 блоков";return;}
        TestingEdits.Cell cell=gesture.context.edits.read(pos);
        if(cell.entity()!=null&&cell.entity().sizeInBytes()>4L*1024*1024){gesture.rejected=true;gesture.error="Данные контейнера превышают 4 MiB";return;}
        gesture.before.put(pos.immutable(),cell);
    }
    /** ChunkMap.save has not called tryMarkSaved yet, so deferred chunks remain dirty for the committed flush. */
    public static boolean deferSave(MinecraftServer server){
        Gesture gesture=ACTIVE.get();return gesture!=null&&gesture.context.server==server&&!gesture.canSave;
    }
    /** Called by the journal engine only after its provisional WAL has reached disk. */
    public static void allowSave(MinecraftServer server){Gesture gesture=ACTIVE.get();if(gesture!=null&&gesture.context.server==server)gesture.canSave=true;}
    public static boolean suppressDrop(ServerLevel level,Entity entity){
        Gesture gesture=ACTIVE.get();
        if(gesture!=null&&level==gesture.context.server.overworld()&&entity instanceof ItemEntity){suppressed++;return true;}
        return false;
    }
    public static long recordedOperations(){return recorded;}
    public static long suppressedDrops(){return suppressed;}
    private static boolean physicalSame(TestingEdits.Cell a,TestingEdits.Cell b){return a.state().equals(b.state())&&Objects.equals(a.entity(),b.entity());}
    private static void origin(TestingServer.Context context,BlockPos pos,String value){
        if(value==null)context.edits.origins.remove(pos.asLong());else context.edits.origins.put(pos.asLong(),value);
    }
    private static void finish(Gesture gesture){
        gesture.finishing=true;List<TestingEdits.Change> changes=new ArrayList<>();
        try{
            if(gesture.rejected){restore(gesture);return;}
            for(var entry:gesture.before.entrySet()){
                BlockPos pos=entry.getKey();TestingEdits.Cell before=entry.getValue(),actual=gesture.context.edits.read(pos);
                if(physicalSame(before,actual)){origin(gesture.context,pos,before.origin());continue;}
                String afterOrigin=actual.state().isAir()?null:
                        actual.state().getBlock() instanceof com.dark.winter.block.FurnitureBlock?"object":
                        ("building".equals(actual.origin())||"object".equals(actual.origin()))?actual.origin():"building";
                TestingEdits.Cell after=new TestingEdits.Cell(actual.state(),actual.entity(),afterOrigin);
                origin(gesture.context,pos,afterOrigin);changes.add(new TestingEdits.Change(pos,before,after));
            }
            if(changes.isEmpty())return;
            try(var actor=TestingActors.enter(gesture.context.server,gesture.player,Set.of(gesture.permission))){gesture.context.edits.recordApplied(gesture.label,List.copyOf(changes));}
            gesture.context.message="Нативное редактирование записано: "+changes.size()+" блоков";recorded++;
        }catch(Exception failure){
            gesture.rejected=true;gesture.error="Нативное изменение отменено: "+failure.getMessage();
            if(gesture.context.edits.nativeRestoreAllowed())try{restore(gesture);}catch(Exception rollback){failure.addSuppressed(rollback);}
            LoggerFactory.getLogger("DarkWinter/Testing").error("Native edit could not enter durable Undo history",failure);
        }finally{
            if(gesture.rejected){
                gesture.context.message=gesture.error.isBlank()?"Нативное изменение отменено":gesture.error;
                gesture.player.sendOverlayMessage(Component.literal(gesture.context.message));
            }
        }
    }
    private static void restore(Gesture gesture){
        var world=gesture.context.server.overworld();
        for(var entry:gesture.before.entrySet()){
            BlockPos pos=entry.getKey();TestingEdits.Cell cell=entry.getValue();
            world.removeBlockEntity(pos);
            world.setBlock(pos,cell.state(),Block.UPDATE_CLIENTS|Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
            if(cell.entity()!=null){
                var tag=cell.entity().copy();tag.putInt("x",pos.getX());tag.putInt("y",pos.getY());tag.putInt("z",pos.getZ());
                BlockEntity entity=BlockEntity.loadStatic(pos,cell.state(),tag,world.registryAccess());
                if(entity==null)throw new IllegalStateException("Cannot restore native block entity at "+pos);
                world.setBlockEntity(entity);entity.setChanged();
            }
            origin(gesture.context,pos,cell.origin());
        }
    }
}
