// Native NPC snapshots for editor transactions, not a replacement NPC simulation.
package com.dark.winter.testing;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** Native NPC snapshots for editor transactions, not a replacement NPC simulation. */
public final class TestingEntities {
    public static final int LIMIT = 32;
    private static final int MAX_NBT_BYTES = 1024 * 1024;
    public static final Set<String> TYPES = Set.of("minecraft:villager", "minecraft:wandering_trader", "minecraft:armor_stand");
    public record Change(UUID id, CompoundTag before, CompoundTag after) {
        public Change {
            Objects.requireNonNull(id, "entity id");
            before = before == null ? null : before.copy();
            after = after == null ? null : after.copy();
        }
    }
    private static net.minecraft.server.level.TicketType recoveryTicket;
    private record Recovery(java.util.Map<net.minecraft.world.level.ChunkPos,java.util.concurrent.CompletableFuture<?>> chunks, long started) {}
    private static final java.util.Map<ServerLevel,Recovery> RECOVERY = new java.util.WeakHashMap<>();
    private TestingEntities() {}
    public static void init() {
        if (recoveryTicket != null) return;
        recoveryTicket = net.minecraft.core.Registry.register(BuiltInRegistries.TICKET_TYPE,
                Identifier.fromNamespaceAndPath("darkwinter", "testing_entity_recovery"),
                new net.minecraft.server.level.TicketType(net.minecraft.server.level.TicketType.NO_TIMEOUT,
                        net.minecraft.server.level.TicketType.FLAG_LOADING));
    }
    /** Request native chunk/entity IO without blocking the server thread. Call again from tick. */
    public static boolean prepareRecovery(ServerLevel world,java.util.List<Change> changes) throws IOException {
        return prepareRecovery(world,changes,java.util.List.of());
    }
    /** Keep every verified block/biome/NPC chunk loaded until the complete WAL succeeds or fails. */
    public static boolean prepareRecovery(ServerLevel world,java.util.List<Change> changes,
            java.util.Collection<net.minecraft.world.level.ChunkPos> extraChunks) throws IOException {
        if(changes.size()>LIMIT)throw new IOException("Too many entity changes");
        var required=new java.util.LinkedHashSet<net.minecraft.world.level.ChunkPos>(extraChunks);
        for(var chunk:required)if(Math.abs((long)chunk.x())>1874999||Math.abs((long)chunk.z())>1874999)
            throw new IOException("Recovery chunk coordinate is out of bounds");
        for(Change change:changes)for(CompoundTag tag:new CompoundTag[]{change.before(),change.after()})if(tag!=null) {
            validatePayload(tag,change.id());Vec3 point=position(tag);
            if(Math.abs(point.x)>29999984||Math.abs(point.z)>29999984||world.isOutsideBuildHeight(net.minecraft.core.BlockPos.containing(point)))
                throw new IOException("NPC recovery coordinate is out of bounds");
            required.add(net.minecraft.world.level.ChunkPos.containing(net.minecraft.core.BlockPos.containing(point)));
        }
        if(required.size()>4096)throw new IOException("Recovery exceeds 4096 total block, biome and NPC chunks");
        Recovery recovery=RECOVERY.get(world);
        if(required.isEmpty()&&recovery==null)return true;
        if(recoveryTicket==null)throw new IOException("Entity recovery ticket was not initialized");
        if(recovery==null) {
            recovery=new Recovery(new java.util.LinkedHashMap<>(),System.nanoTime());RECOVERY.put(world,recovery);
        }
        try {
            for(var chunk:required)if(!recovery.chunks().containsKey(chunk))
                recovery.chunks().put(chunk,world.getChunkSource().addTicketAndLoadWithRadius(recoveryTicket,chunk,0));
        }catch(Exception error){finishRecovery(world);throw new IOException("Cannot request recovery chunks",error);}
        if(System.nanoTime()-recovery.started()>60_000_000_000L) {
            finishRecovery(world);throw new IOException("Recovery IO timed out; checkpoint recovery is available");
        }
        for(var entry:recovery.chunks().entrySet()) {
            if(entry.getValue().isCompletedExceptionally()){finishRecovery(world);throw new IOException("Recovery chunk IO failed");}
            if(!entry.getValue().isDone()||!world.areEntitiesLoaded(entry.getKey().pack()))return false;
        }
        return true;
    }
    public static void finishRecovery(ServerLevel world) {
        Recovery recovery = RECOVERY.remove(world);
        if (recovery != null) for (var chunk : recovery.chunks().keySet())
            world.getChunkSource().removeTicketWithRadius(recoveryTicket, chunk, 0);
    }
    /** Load and compare native snapshots without collision checks against blocks that the same WAL will restore. */
    public static void preflightRecovery(ServerLevel world,Change change) throws IOException {
        if(change.before()==null&&change.after()==null)throw new IOException("Empty NPC recovery");
        if(change.before()!=null)decode(world,change.before(),change.id());
        if(change.after()!=null)decode(world,change.after(),change.id());
        CompoundTag current=snapshot(world,change.id());
        if(!equivalent(world,current,change.before())&&!equivalent(world,current,change.after()))
            throw new IOException("NPC recovery conflict before any writes: "+change.id());
    }
    /** Called only after prepareRecovery returned true for the complete operation. */
    public static void recover(ServerLevel world, Change change, boolean forward) throws IOException {
        CompoundTag desired = forward ? change.after() : change.before();
        CompoundTag alternate = forward ? change.before() : change.after();
        CompoundTag current = snapshot(world, change.id());
        if (equivalent(world,current,desired)) return;
        if (!equivalent(world,current,alternate)) throw new IOException("NPC recovery conflict: " + change.id());
        apply(world,change,forward);
    }
    private static boolean equivalent(ServerLevel world,CompoundTag first,CompoundTag second) throws IOException {
        if (first == null || second == null) return first == second;
        if (first.equals(second)) return true;
        return capture(decode(world,first,uuid(first))).equals(capture(decode(world,second,uuid(second))));
    }
    public static net.minecraft.world.phys.AABB bounds(ServerLevel world, CompoundTag tag) throws IOException {
        return decode(world,tag,uuid(tag)).getBoundingBox();
    }
    public static boolean supported(Entity entity) {
        return entity != null && TYPES.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
    }
    public static CompoundTag capture(Entity entity) throws IOException {
        if (!supported(entity) || entity.isPassenger() || entity.isVehicle()
                || entity instanceof Leashable leash && leash.isLeashed())
            throw new IOException("NPC Prefab поддерживает свободных жителей, торговцев и стойки без пассажиров/поводков");
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        if (!entity.save(output)) throw new IOException("Сущность не может быть сохранена");
        CompoundTag tag = output.buildResult();
        // 26.3 Villager.readAdditionalSaveData always finalizes an explicit VillagerData.
        // Canonicalize this loader lifecycle bit in the snapshot, without mutating the live entity.
        if("minecraft:villager".equals(tag.getStringOr("id",""))&&tag.contains("VillagerData"))
            tag.putBoolean("VillagerDataFinalized",true);
        validatePayload(tag, entity.getUUID());
        return tag;
    }
    public static CompoundTag snapshot(ServerLevel world, UUID id) throws IOException {
        Entity entity = world.getEntityInAnyDimension(id);
        if (entity == null) return null;
        if (entity.level() != world) throw new IOException("NPC перешёл в другое измерение; используйте полную контрольную точку");
        return capture(entity);
    }
    public static UUID uuid(CompoundTag tag) throws IOException {
        try {
            if (tag == null || !tag.contains("UUID")) throw new IOException("В NBT нет UUID");
            return UUIDUtil.CODEC.parse(NbtOps.INSTANCE, tag.get("UUID")).getOrThrow();
        } catch (RuntimeException error) { throw new IOException("Недопустимый UUID сущности", error); }
    }
    public static Vec3 position(CompoundTag tag) throws IOException {
        try {
            Vec3 pos = Vec3.CODEC.parse(NbtOps.INSTANCE, tag.get("Pos")).getOrThrow();
            if (!Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z))
                throw new IOException("Нечисловая позиция NPC");
            return pos;
        } catch (RuntimeException error) { throw new IOException("Недопустимая позиция сущности", error); }
    }
    private static void validatePayload(CompoundTag tag, UUID expected) throws IOException {
        if (tag == null || tag.sizeInBytes() > MAX_NBT_BYTES || !TYPES.contains(tag.getStringOr("id", ""))
                || !expected.equals(uuid(tag))) throw new IOException("Тип, UUID или размер NPC не поддерживается");
        if (tag.contains("Passengers") || tag.contains("RootVehicle") || tag.contains("Owner")
                || tag.contains("Leash") || tag.contains("leash") || tag.contains("LeashData") || tag.contains("sleeping_pos"))
            throw new IOException("Внешние связи NPC требуют отдельного полного архива");
        position(tag);
        ListTag rotation = tag.getListOrEmpty("Rotation");
        if (rotation.size() != 2 || !Float.isFinite(rotation.getFloatOr(0, Float.NaN))
                || !Float.isFinite(rotation.getFloatOr(1, Float.NaN))) throw new IOException("Недопустимый угол NPC");
    }
    private static Entity decode(ServerLevel world, CompoundTag tag, UUID id) throws IOException {
        validatePayload(tag, id);
        if("minecraft:villager".equals(tag.getStringOr("id",""))&&tag.contains("VillagerData")) {
            tag=tag.copy();tag.putBoolean("VillagerDataFinalized",true);
        }
        Identifier identifier = Identifier.parse(tag.getStringOr("id", ""));
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(identifier);
        if (type == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(identifier)) throw new IOException("Неизвестный тип NPC");
        try {
            Entity entity = EntityType.loadEntityRecursive(type, tag.copy(), world, EntitySpawnReason.LOAD, EntityProcessor.NOP);
            if (entity == null || !supported(entity) || !id.equals(entity.getUUID()) || entity.isPassenger() || entity.isVehicle())
                throw new IOException("Не удалось декодировать NPC");
            Vec3 pos = entity.position();
            if (!Double.isFinite(pos.x + pos.y + pos.z) || Math.abs(pos.x) > 29999984 || Math.abs(pos.z) > 29999984
                    || world.isOutsideBuildHeight(entity.blockPosition())
                    || !world.getWorldBorder().isWithinBounds(entity.getBoundingBox())
                    || !world.hasChunkAt(entity.blockPosition()))
                throw new IOException("NPC вне разрешённой или загруженной области");
            CompoundTag roundTrip=capture(entity);
            var originalJson=CompoundTag.CODEC.encodeStart(JsonOps.INSTANCE,tag).getOrThrow().getAsJsonObject();
            var restoredJson=CompoundTag.CODEC.encodeStart(JsonOps.INSTANCE,roundTrip).getOrThrow().getAsJsonObject();
            if(!tag.equals(roundTrip)) {
                Set<String> keys=new java.util.TreeSet<>(originalJson.keySet());keys.addAll(restoredJson.keySet());
                java.util.List<String> differences=new java.util.ArrayList<>();
                for(String key:keys)if(!java.util.Objects.equals(originalJson.get(key),restoredJson.get(key))) {
                    String value=key+": "+originalJson.get(key)+" -> "+restoredJson.get(key);
                    differences.add(value.substring(0,Math.min(value.length(),400)));
                }
                throw new IOException("Native NPC payload contains unsupported or lossy values: "+String.join("; ",differences));
            }
            return entity;
        } catch (RuntimeException error) { throw new IOException("Ошибка декодирования NPC", error); }
    }
    /**
     * Creation requires a completely unused UUID. Replacing an existing NPC requires
     * its exact captured native payload. Removal may undo a created NPC after AI
     * movement, but its UUID/type and absence of external links must still match.
     */
    public static void validate(ServerLevel world, Change change) throws IOException {
        if (change.before() == null && change.after() == null) throw new IOException("Пустая операция NPC");
        if (change.before() != null) validatePayload(change.before(), change.id());
        if (change.after() != null) decode(world, change.after(), change.id());
        CompoundTag current = snapshot(world, change.id());
        if (change.before() == null) {
            if (current != null) throw new IOException("UUID NPC уже занят");
        } else {
            if (current == null) throw new IOException("NPC отсутствует или выгружен; загрузите его область");
            if (!current.getStringOr("id", "").equals(change.before().getStringOr("id", "")))
                throw new IOException("Тип существующего NPC изменился");
            if (change.after() != null && !equivalent(world,current,change.before()))
                throw new IOException("NPC изменился после выделения; повторите операцию");
        }
        if (change.after() != null) {
            Entity candidate = decode(world, change.after(), change.id());
            Entity old = world.getEntityInAnyDimension(change.id());
            for (var shape : world.getBlockCollisions(candidate,candidate.getBoundingBox()))
                if (!shape.isEmpty()) throw new IOException("NPC пересекает твёрдые блоки");
            for (Entity entity : world.getEntities(candidate, candidate.getBoundingBox()))
                if (entity != old && entity.isAlive()) throw new IOException("Место NPC занято другой сущностью");
        }
    }
    public static void apply(ServerLevel world, Change change, boolean forward) throws IOException {
        CompoundTag desired = forward ? change.after() : change.before();
        Entity existing = world.getEntityInAnyDimension(change.id());
        if (existing != null && existing.level() != world) throw new IOException("NPC находится в другом измерении");
        Entity replacement = desired == null ? null : decode(world, desired, change.id());
        CompoundTag previous = existing == null ? null : capture(existing);
        if (existing != null && desired != null && equivalent(world,previous,desired)) return;
        if (existing != null) existing.discard();
        if (replacement != null && !world.addFreshEntity(replacement)) {
            if (previous != null) {
                Entity restored = decode(world, previous, change.id());
                if (!world.addFreshEntity(restored)) throw new IOException("NPC restore failed; full checkpoint recovery required");
            }
            throw new IOException("Сервер отклонил добавление NPC; прежняя сущность восстановлена");
        }
    }
    public static Change reverse(Change change) { return new Change(change.id(), change.after(), change.before()); }
    public static JsonObject encode(Change change) {
        JsonObject value=new JsonObject();value.addProperty("id",change.id().toString());
        try {
            if(change.before()!=null)value.addProperty("beforeNbt",TestingNbt.encode(change.before()));
            if(change.after()!=null)value.addProperty("afterNbt",TestingNbt.encode(change.after()));
        } catch(IOException error){throw new IllegalArgumentException("Cannot encode native NPC snapshot",error);}
        return value;
    }
    public static Change decode(JsonObject value) throws IOException {
        try {
            Change change=new Change(UUID.fromString(value.get("id").getAsString()),
                    value.has("beforeNbt")?TestingNbt.decode(value.get("beforeNbt").getAsString()):
                            value.has("before")?CompoundTag.CODEC.parse(JsonOps.INSTANCE,value.get("before")).getOrThrow():null,
                    value.has("afterNbt")?TestingNbt.decode(value.get("afterNbt").getAsString()):
                            value.has("after")?CompoundTag.CODEC.parse(JsonOps.INSTANCE,value.get("after")).getOrThrow():null);
            if(change.before()!=null)validatePayload(change.before(),change.id());
            if(change.after()!=null)validatePayload(change.after(),change.id());
            if(change.before()==null&&change.after()==null)throw new IOException("Пустая операция NPC");
            return change;
        } catch(RuntimeException error){throw new IOException("Повреждён журнал NPC",error);}
    }
    /**
     * Portable copies retain profession, offers, inventory, health and other native
     * parameters. Cached AI destinations are reset so they cannot reference another
     * island. Existing entities and their NBT are never modified by this operation.
     */
    public static CompoundTag copyAt(CompoundTag source, Vec3 destination, float yawRotation, UUID newId) throws IOException {
        validatePayload(source, uuid(source));
        CompoundTag tag = source.copy();
        tag.put("UUID", UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, newId).getOrThrow());
        tag.put("Pos", Vec3.CODEC.encodeStart(NbtOps.INSTANCE, destination).getOrThrow());
        boolean independentCopy=!uuid(source).equals(newId);
        if(independentCopy) {
            if (tag.contains("Brain")) { CompoundTag brain=new CompoundTag();brain.put("memories",new CompoundTag());tag.put("Brain",brain); }
            tag.remove("WanderTarget");
        }
        ListTag rotation = tag.getListOrEmpty("Rotation").copy();
        rotation.set(0, net.minecraft.nbt.FloatTag.valueOf(net.minecraft.util.Mth.wrapDegrees(rotation.getFloatOr(0, 0) + yawRotation)));
        tag.put("Rotation", rotation);
        if(independentCopy)tag.put("Motion", Vec3.CODEC.encodeStart(NbtOps.INSTANCE, Vec3.ZERO).getOrThrow());
        validatePayload(tag, newId);
        return tag;
    }
}
