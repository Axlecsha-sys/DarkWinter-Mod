package com.dark.winter.placement;

import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.FurnitureCatalog;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;

/** Authoritative placement metadata and an immutable client/worker geometry mirror. */
public final class AnchorRuntime {
    private record Snapshot(Map<Long,Anchor> members,Map<Long,Vec3> offsets,Map<Long,Double> rotations,Map<Long,List<BlockPos>> physical){}
    private static final Snapshot EMPTY=new Snapshot(Map.of(),Map.of(),Map.of(),Map.of());
    private record RotatedShape(VoxelShape source, Vec3 pivot, double yaw) {}
    private static final Map<RotatedShape,VoxelShape> ROTATED_SHAPES = Collections.synchronizedMap(
            new LinkedHashMap<>(128, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<RotatedShape,VoxelShape> entry) {
                    return size() > 512;
                }
            });
    private static final Map<ServerLevel,State> SERVERS=new ConcurrentHashMap<>();
    private static volatile BlockGetter clientLevel;
    private static volatile Snapshot client=EMPTY;
    private static final ThreadLocal<Integer> RAW=ThreadLocal.withInitial(()->0);
    private static final Map<Long,Vec3> NO_OFFSETS=Map.of();
    private static long sequence;
    private static boolean initialized;
    private static long pendingRevision=Long.MIN_VALUE;
    private static List<Anchor>[] pending;
    private static final class State{
        final AnchorStore store;
        Snapshot snapshot;
        int cursor;
        final Map<UUID,Long> sentAt=new HashMap<>();
        final Map<Long,Removed> removed=new LinkedHashMap<>();
        State(ServerLevel level){store=AnchorStore.get(level);snapshot=build(level,store.entries());}
    }
    private record Removed(Vec3 offset,long until){}
    private AnchorRuntime(){}
    public static void init(){
        if(initialized)return;initialized=true;
        SurfaceGeometry.initialize();
        PayloadTypeRegistry.clientboundPlay().register(AnchorPayload.TYPE,AnchorPayload.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->send((ServerLevel)handler.player.level(),handler.player));
        ServerTickEvents.END_SERVER_TICK.register(server->{
            for(ServerLevel level:server.getAllLevels()){
                State s=state(level);long now=level.getGameTime();
                if(now%5==0)validate(level,s);
                for(ServerPlayer player:level.players()){
                    long cell=BlockPos.asLong(player.blockPosition().getX()>>4,0,player.blockPosition().getZ()>>4);
                    if(!Objects.equals(s.sentAt.get(player.getUUID()),cell)){send(level,player);s.sentAt.put(player.getUUID(),cell);}
                }
                if(now%20==0)s.removed.entrySet().removeIf(e->e.getValue().until<now);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server->SERVERS.keySet().removeIf(level->level.getServer()==server));
    }
    private static State state(ServerLevel level){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Surface anchors require the world owner thread");
        return SERVERS.computeIfAbsent(level,State::new);
    }
    private static Snapshot snapshot(BlockGetter level){
        if(level==clientLevel)return client;
        if(level instanceof ServerLevel server&&server.getServer().isSameThread())return state(server).snapshot;
        return EMPTY;
    }
    public static Map<Long,Vec3> offsets(BlockGetter level){
        if(level instanceof AnchorLevelView view)return view.darkwinter$anchorOffsets();
        return snapshot(level).offsets;
    }
    public static Vec3 offset(BlockGetter level,BlockPos pos){return offsets(level).getOrDefault(pos.asLong(),Vec3.ZERO);}
    public static Map<Long,Double> rotations(BlockGetter level) {
        return level instanceof AnchorLevelView view ? view.darkwinter$anchorRotations() : snapshot(level).rotations;
    }
    public static double rotation(BlockGetter level, BlockPos pos) {
        var rotations = rotations(level);
        return rotations.isEmpty() ? 0 : rotations.getOrDefault(pos.asLong(), 0.0);
    }
    public static Vec3 logicalPoint(BlockGetter level, BlockPos member, Vec3 point) {
        Vec3 delta = offset(level, member);
        Anchor anchor = anchor(level, member);
        if (anchor == null || anchor.yaw() == 0) return point.subtract(delta);
        Vec3 pivot = Vec3.atLowerCornerOf(anchor.owner()).add(delta);
        return FurnitureTransform.point(point, pivot, -anchor.yaw()).subtract(delta);
    }
    public static Anchor anchor(BlockGetter level,BlockPos pos){return snapshot(level).members.get(pos.asLong());}
    public static boolean anchored(BlockGetter level,BlockPos pos){return !offset(level,pos).equals(Vec3.ZERO)||anchor(level,pos)!=null;}
    public static List<BlockPos> logicalOwners(BlockGetter level,BlockPos physicalCell){
        return snapshot(level).physical.getOrDefault(physicalCell.asLong(),List.of());
    }
    public static boolean raw(){return RAW.get()!=0;}
    public static <T>T raw(Supplier<T> action){int old=RAW.get();RAW.set(old+1);try{return action.get();}finally{if(old==0)RAW.remove();else RAW.set(old);}}
    public static VoxelShape rawShape(BlockGetter level,BlockPos pos,BlockState state,boolean collision){
        return raw(()->collision?state.getCollisionShape(level,pos,CollisionContext.empty()):state.getShape(level,pos,CollisionContext.empty()));
    }
    public static VoxelShape transform(BlockGetter level,BlockPos pos,BlockState state,VoxelShape original){
        if(raw())return original;
        Vec3 delta=SurfacePlacement.pendingOffset(level,pos,state,original);
        boolean pending = delta != null;
        if(!pending)delta=offset(level,pos);
        double yaw = pending ? SurfacePlacement.pendingRotation(level, pos) : rotation(level, pos);
        if (yaw != 0 && state.getBlock() instanceof FurnitureBlock furniture) {
            Vec3 pivot = Vec3.atLowerCornerOf(furniture.origin(pos, state).subtract(pos));
            original = ROTATED_SHAPES.computeIfAbsent(new RotatedShape(original, pivot, yaw),
                    key -> FurnitureTransform.shape(key.source(), key.pivot(), key.yaw()));
        }
        return delta.equals(Vec3.ZERO)?original:original.move(delta);
    }
    public static boolean hasTransform(BlockGetter level,BlockPos pos){
        return !raw()&&(SurfacePlacement.pendingAt(level,pos)||offsets(level).containsKey(pos.asLong()));
    }
    private static Snapshot build(Level level,List<Anchor> anchors){
        var members=new HashMap<Long,Anchor>();var offsets=new HashMap<Long,Vec3>();var rotations=new HashMap<Long,Double>();var cells=new HashMap<Long,LinkedHashSet<BlockPos>>();
        for(Anchor a:anchors)for(BlockPos p:a.members()){
            members.put(p.asLong(),a);offsets.put(p.asLong(),a.offset());
            if (a.yaw() != 0) rotations.put(p.asLong(), a.yaw());
            AABB bounds=new AABB(p).move(a.offset()).inflate(.04);
            // Anchor metadata can arrive before the chunk. Reserve the possible tap extension until the block is known.
            BlockState blockState = level.hasChunkAt(p) ? level.getBlockState(p) : null;
            int height = blockState == null || blockState.isAir() ? FurnitureCatalog.partHeightLimit("kitchen_sink")
                    : blockState.getBlock() instanceof FurnitureBlock furniture
                    ? FurnitureCatalog.partHeightLimit(furniture.definition().id()) : 16;
            if (height > 16) bounds = bounds.expandTowards(0, (height - 16) / 16.0, 0);
            if (blockState != null && blockState.getBlock() instanceof FurnitureBlock furniture
                    && (FurnitureCatalog.partWidthLimit(furniture.definition().id(), blockState.getValue(FurnitureBlock.PART)) > 16
                    || furniture.definition().id().equals("interior_wide_dresser"))) {
                AABB extension = SurfaceGeometry.bounds(level, p, blockState).move(Vec3.atLowerCornerOf(p).add(a.offset()));
                bounds = bounds.minmax(extension.inflate(.04));
            } else if (blockState == null || blockState.isAir()) {
                // Before the chunk arrives the two-member horizontal anchor may be the extended worktable.
                if (a.members().size() == 2 && a.members().getLast().getY() == a.owner().getY()) {
                    double margin = -FurnitureCatalog.partMinimum("interior_wide_dresser", 0, 2) / 16;
                    bounds = bounds.inflate(margin, 0, margin);
                    if (!p.equals(a.owner())) {
                        double extra = (FurnitureCatalog.partWidthLimit("interior_kitchen_worktable", 1) - 16) / 16.0;
                        bounds = bounds.expandTowards(extra * (p.getX() - a.owner().getX()), 0,
                                extra * (p.getZ() - a.owner().getZ()));
                    }
                }
            }
            bounds = FurnitureTransform.bounds(bounds, Vec3.atLowerCornerOf(a.owner()).add(a.offset()), a.yaw());
            for(int y=(int)Math.floor(bounds.minY);y<=(int)Math.floor(bounds.maxY);y++)
                for(int z=(int)Math.floor(bounds.minZ);z<=(int)Math.floor(bounds.maxZ);z++)
                    for(int x=(int)Math.floor(bounds.minX);x<=(int)Math.floor(bounds.maxX);x++)
                        cells.computeIfAbsent(BlockPos.asLong(x,y,z),ignored->new LinkedHashSet<>()).add(p);
        }
        var physical=new HashMap<Long,List<BlockPos>>();cells.forEach((key,value)->physical.put(key,List.copyOf(value)));
        return new Snapshot(Map.copyOf(members),Map.copyOf(offsets),Map.copyOf(rotations),Map.copyOf(physical));
    }
    public static void put(ServerLevel level,Anchor anchor){
        State s=state(level);s.store.put(anchor);s.snapshot=build(level,s.store.entries());
        com.winterkore.engine.surface.host.SnowSurfaceRuntime.anchorChanged(level,anchor.owner(),anchor.members(),anchor.offset());sendAll(level);
    }
    private static void remove(ServerLevel level,Anchor anchor){
        State s=state(level);s.store.remove(anchor.owner());
        for(BlockPos member:anchor.members())s.removed.put(member.asLong(),new Removed(anchor.offset(),level.getGameTime()+20));
        s.snapshot=build(level,s.store.entries());
        com.winterkore.engine.surface.host.SnowSurfaceRuntime.anchorChanged(level,anchor.owner(),anchor.members(),anchor.offset());
    }
    public static Vec3 dropOffset(Level level,BlockPos pos){
        if(!(level instanceof ServerLevel server))return offset(level,pos);
        State s=state(server);Anchor a=s.snapshot.members.get(pos.asLong());
        if(a!=null)return a.offset();Removed old=s.removed.get(pos.asLong());
        return old!=null&&old.until>=level.getGameTime()?old.offset:Vec3.ZERO;
    }
    public static void blockChanged(Level level,BlockPos pos,BlockState before,BlockState after){
        if(!(level instanceof ServerLevel server)||!server.getServer().isSameThread()||SurfacePlacement.mutating())return;
        State s=SERVERS.get(server);if(s==null)return;
        Anchor own=s.snapshot.members.get(pos.asLong());
        if(own!=null&&before.getBlock()!=after.getBlock()){remove(server,own);sendAll(server);}
        // External support is revalidated against the actual surface, never against its old bounding box.
        if(!s.store.entries().isEmpty()&&before!=after)validate(server,s);
    }
    private static void validate(ServerLevel level,State state){
        List<Anchor> anchors=state.store.entries();if(anchors.isEmpty())return;
        boolean changed=false;
        for(int n=0;n<Math.min(64,anchors.size());n++){
            Anchor a=anchors.get(Math.floorMod(state.cursor++,anchors.size()));
            if(!level.hasChunkAt(a.owner())||!level.hasChunkAt(a.support()))continue;
            if(level.getBlockState(a.owner()).isAir()){remove(level,a);changed=true;continue;}
            BlockState rootState = level.getBlockState(a.owner());
            if(!SurfaceGeometry.supports(level,a.support(),a.face(),a.point())
                    || InteriorPlacement.rug(rootState.getBlock()) && !(a.yaw() == 0
                    ? InteriorPlacement.supportedRug(level, a.members(), a.offset())
                    : InteriorPlacement.supportedRug(level, a.owner(), (FurnitureBlock) rootState.getBlock(), rootState,
                            Vec3.atLowerCornerOf(a.owner()).add(a.offset()), a.yaw(), true))){
                // Destroy before forgetting the transform so ordinary native loot/BE contents retain their location.
                SurfacePlacement.mutate(()->{
                    BlockState root=level.getBlockState(a.owner());
                    if(root.getBlock() instanceof com.dark.winter.block.FurnitureBlock furniture){
                        var stack=furniture.stackForState(root);
                        if(furniture.collectAssembly(level,a.owner(),root))net.minecraft.world.level.block.Block.popResource(level,a.owner(),stack);
                    }else for(BlockPos p:a.members())if(level.hasChunkAt(p)&&!level.getBlockState(p).isAir())level.destroyBlock(p,true);
                    return null;
                });
                remove(level,a);changed=true;
            }
        }
        if(changed)sendAll(level);
    }
    public static boolean support(BlockGetter view,BlockPos pos,Direction face){
        if(SurfacePlacement.pendingSupport(view,pos,face))return true;
        Anchor a=snapshot(view).members.get(pos.relative(face).asLong());
        return a!=null&&a.face()==face;
    }
    public static void resend(ServerLevel level,ServerPlayer player){send(level,player);}
    public static void predict(BlockGetter level,Anchor anchor){
        if(level!=clientLevel||!(level instanceof Level world))return;
        var byOwner=new LinkedHashMap<Long,Anchor>();for(Anchor old:client.members.values())byOwner.put(old.owner().asLong(),old);
        byOwner.put(anchor.owner().asLong(),anchor);client=build(world,List.copyOf(byOwner.values()));
    }
    private static void sendAll(ServerLevel level){for(ServerPlayer player:level.players())send(level,player);}
    private static void send(ServerLevel level,ServerPlayer player){
        List<Anchor> nearby=state(level).store.entries().stream().filter(a->
            Math.abs((a.owner().getX()>>4)-(player.blockPosition().getX()>>4))<=24
            &&Math.abs((a.owner().getZ()>>4)-(player.blockPosition().getZ()>>4))<=24).toList();
        if(nearby.size()>65536)throw new IllegalStateException("Nearby surface anchors exceed bounded synchronization budget");
        int pages=Math.max(1,(nearby.size()+255)/256);long revision=++sequence;
        for(int page=0;page<pages;page++)ServerPlayNetworking.send(player,new AnchorPayload(level.dimension().toString(),revision,page,pages,
            nearby.subList(page*256,Math.min(nearby.size(),(page+1)*256))));
    }
    @SuppressWarnings("unchecked")
    public static Set<BlockPos> publishClient(BlockGetter level,AnchorPayload payload){
        if(!(level instanceof Level world)||!world.dimension().toString().equals(payload.dimension()))return Set.of();
        if(clientLevel!=level){clearClient();clientLevel=level;}
        if(payload.revision()<pendingRevision)return Set.of();
        if(payload.revision()!=pendingRevision){pendingRevision=payload.revision();pending=(List<Anchor>[])new List<?>[payload.pages()];}
        if(pending.length!=payload.pages())throw new IllegalArgumentException("Mixed anchor snapshot");
        pending[payload.page()]=payload.anchors();for(var page:pending)if(page==null)return Set.of();
        var all=new ArrayList<Anchor>();for(var page:pending)all.addAll(page);
        Snapshot next=build(world,all),old=client;var dirty=new HashSet<BlockPos>();
        var positions=new HashSet<Long>(old.offsets.keySet());positions.addAll(next.offsets.keySet());
        for(long pos:positions)if(!Objects.equals(old.members.get(pos),next.members.get(pos)))dirty.add(BlockPos.of(pos));
        old.physical.forEach((pos,owners)->{if(!Objects.equals(owners,next.physical.get(pos)))dirty.add(BlockPos.of(pos));});
        next.physical.forEach((pos,owners)->{if(!Objects.equals(owners,old.physical.get(pos)))dirty.add(BlockPos.of(pos));});
        client=next;return Set.copyOf(dirty);
    }
    public static void clearClient(){clientLevel=null;client=EMPTY;pending=null;pendingRevision=Long.MIN_VALUE;}
    private static Set<BlockPos> candidates(BlockGetter level,AABB box){
        Snapshot s=snapshot(level);if(s.members.isEmpty())return Set.of();
        long volume=(long)(Math.floor(box.maxX)-Math.floor(box.minX)+1)*(long)(Math.floor(box.maxY)-Math.floor(box.minY)+1)*(long)(Math.floor(box.maxZ)-Math.floor(box.minZ)+1);
        var result=new LinkedHashSet<BlockPos>();
        if(volume>4096){for(long pos:s.members.keySet())result.add(BlockPos.of(pos));return result;}
        for(int y=(int)Math.floor(box.minY);y<=(int)Math.floor(box.maxY);y++)for(int z=(int)Math.floor(box.minZ);z<=(int)Math.floor(box.maxZ);z++)for(int x=(int)Math.floor(box.minX);x<=(int)Math.floor(box.maxX);x++)
            result.addAll(s.physical.getOrDefault(BlockPos.asLong(x,y,z),List.of()));
        return result;
    }
    public static List<VoxelShape> collisionShapes(BlockGetter level,AABB box,CollisionContext context){
        Set<BlockPos> owners = candidates(level, box);
        var gates = com.dark.winter.block.FenceGateGeometry.collisions(level, box);
        if (owners.isEmpty()) return gates;
        VoxelShape query = Shapes.create(box);
        var result = new ArrayList<>(gates);
        for (BlockPos owner : owners) {
            if (level instanceof Level world && !world.hasChunkAt(owner)) continue;
            VoxelShape shape = level.getBlockState(owner).getCollisionShape(level, owner, context).move(owner);
            // Like vanilla BlockCollisions, return only actual intersections.
            // Movement validation treats every returned shape as a hit in the new position.
            if (Shapes.joinIsNotEmpty(shape, query, BooleanOp.AND)) result.add(shape);
        }
        return result;
    }
    public static BlockHitResult ray(BlockGetter level,ClipContext context,BlockHitResult nativeHit){
        Vec3 from=context.getFrom(),to=context.getTo();double best=nativeHit.getType()==HitResult.Type.BLOCK?from.distanceToSqr(nativeHit.getLocation()):Double.POSITIVE_INFINITY;
        BlockHitResult chosen=nativeHit;
        for(BlockPos owner:candidates(level,new AABB(from,to).inflate(.001))){
            if(level instanceof Level world&&!world.hasChunkAt(owner))continue;
            if(context.getBlockShape(level.getBlockState(owner),level,owner).isEmpty())continue;
            SurfaceGeometry.Hit hit=SurfaceGeometry.hit(level,owner,from,to);
            if(hit!=null&&hit.distanceSquared()<best){best=hit.distanceSquared();chosen=new BlockHitResult(hit.point(),hit.face(),owner,false);}
        }
        return com.dark.winter.block.FenceGateGeometry.ray(level, from, to, chosen);
    }
}
