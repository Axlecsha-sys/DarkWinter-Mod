package com.dark.winter.interaction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Three seconds of server ticks, then one atomic whole-pile removal and exactly three stored sticks. */
public final class DeadwoodHarvest {
    public static final int DURATION_TICKS=60,REWARD_COUNT=3;
    private static final Map<MinecraftServer,Map<UUID,Session>> ACTIVE=new HashMap<>();
    private static final class Session {
        final ServerPlayer player;final ServerLevel level;final DeadwoodTarget target;final Vec3 aim;final int serial;
        int elapsed;
        Session(ServerPlayer player,DeadwoodTarget target,Vec3 aim,int serial){this.player=player;this.level=player.level();this.target=target;this.aim=aim;this.serial=serial;}
    }
    private DeadwoodHarvest() {}
    public static void initialize() {
        DeadwoodSounds.initialize();
        PayloadTypeRegistry.serverboundPlay().register(DeadwoodRequest.TYPE,DeadwoodRequest.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DeadwoodProgress.TYPE,DeadwoodProgress.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(DeadwoodRequest.TYPE,(packet,context)->context.server().execute(()->request(context.player(),packet)));
        ServerTickEvents.END_SERVER_TICK.register(DeadwoodHarvest::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{var sessions=ACTIVE.get(server);if(sessions!=null)sessions.remove(handler.player.getUUID());});
        ServerLifecycleEvents.SERVER_STOPPED.register(ACTIVE::remove);
    }
    private static void request(ServerPlayer player,DeadwoodRequest request) {
        if(request.serial()<=0)return;
        var sessions=ACTIVE.computeIfAbsent(player.level().getServer(),ignored->new HashMap<>());
        Session former=sessions.get(player.getUUID());
        if(!request.start()) {
            if(former!=null&&former.serial==request.serial()){sessions.remove(player.getUUID());send(former,DeadwoodProgress.CANCELLED);}
            return;
        }
        // A repeated start cannot reset the timer or produce concurrent rewards.
        if(former!=null){send(former,DeadwoodProgress.RUNNING);return;}
        DeadwoodTarget target=DeadwoodTarget.at(player.level(),request.target());
        if(target==null||!target.origin().equals(request.target())||!valid(player,target,request.aim(),player.level())){
            reply(player,request.target(),request.serial(),DeadwoodProgress.INVALID,0);return;
        }
        if(!hasSpace(player.getInventory())){reply(player,target.origin(),request.serial(),DeadwoodProgress.NO_SPACE,0);return;}
        Session session=new Session(player,target,request.aim(),request.serial());sessions.put(player.getUUID(),session);send(session,DeadwoodProgress.RUNNING);
    }
    private static boolean valid(ServerPlayer player,DeadwoodTarget target,Vec3 aim,ServerLevel level) {
        if(player.level()!=level||!player.isAlive()||player.isRemoved()||player.isSpectator()||!player.mayBuild()
                ||player.containerMenu!=player.inventoryMenu||!level.hasChunkAt(target.origin())
                ||!level.getWorldBorder().isWithinBounds(target.origin())||!level.mayInteract(player,target.origin())
                ||!player.isWithinBlockInteractionRange(target.origin(),0)||level.getBlockState(target.origin())!=target.state())return false;
        for(BlockPos pos:target.block().positions(target.origin(),target.state().getValue(com.dark.winter.block.FurnitureBlock.FACING)))
            if(!level.hasChunkAt(pos)||!level.mayInteract(player,pos))return false;
        return target.visibleFrom(player,aim);
    }
    private static void tick(MinecraftServer server) {
        var sessions=ACTIVE.get(server);if(sessions==null)return;
        var iterator=sessions.values().iterator();
        while(iterator.hasNext()) {
            Session session=iterator.next();
            if(!valid(session.player,session.target,session.aim,session.level)) {iterator.remove();send(session,DeadwoodProgress.CANCELLED);continue;}
            session.elapsed++;
            if(session.elapsed>=DURATION_TICKS){iterator.remove();finish(session);}
            else {
                if(session.elapsed%5==0)send(session,DeadwoodProgress.RUNNING);
            }
        }
    }
    public static boolean hasSpace(Inventory inventory) {
        ItemStack reward=new ItemStack(Items.STICK,REWARD_COUNT);int space=0;
        for(int i=0;i<Inventory.INVENTORY_SIZE;i++) {
            ItemStack item=inventory.getItem(i);
            if(item.isEmpty())space+=inventory.getMaxStackSize(reward);
            else if(ItemStack.isSameItemSameComponents(item,reward))space+=Math.max(0,inventory.getMaxStackSize(item)-item.getCount());
            if(space>=REWARD_COUNT)return true;
        }
        ItemStack offhand=inventory.getItem(Inventory.SLOT_OFFHAND);
        if(ItemStack.isSameItemSameComponents(offhand,reward))space+=Math.max(0,inventory.getMaxStackSize(offhand)-offhand.getCount());
        return space>=REWARD_COUNT;
    }
    private static void finish(Session session) {
        Inventory inventory=session.player.getInventory();
        if(!hasSpace(inventory)){send(session,DeadwoodProgress.NO_SPACE);return;}
        // Native creative inventory.add discards overflow, so validate capacity first and retain a rollback snapshot.
        var before=new ArrayList<ItemStack>(inventory.getContainerSize());
        for(int i=0;i<inventory.getContainerSize();i++)before.add(inventory.getItem(i).copy());
        ItemStack reward=new ItemStack(Items.STICK,REWARD_COUNT);inventory.add(reward);
        boolean committed=false;
        try {
            if(reward.isEmpty())committed=session.target.block().collectAssembly(session.level,session.target.origin(),session.target.state());
        }finally {
            if(!committed)for(int i=0;i<before.size();i++)inventory.setItem(i,before.get(i));
            inventory.setChanged();session.player.inventoryMenu.broadcastChanges();
        }
        send(session,committed?DeadwoodProgress.COMPLETE:DeadwoodProgress.CANCELLED);
    }
    private static void send(Session s,int phase){reply(s.player,s.target.origin(),s.serial,phase,s.elapsed);}
    private static void reply(ServerPlayer player,BlockPos pos,int serial,int phase,int elapsed) {
        if(ServerPlayNetworking.canSend(player,DeadwoodProgress.TYPE))ServerPlayNetworking.send(player,new DeadwoodProgress(pos,serial,phase,elapsed,DURATION_TICKS));
    }
}
