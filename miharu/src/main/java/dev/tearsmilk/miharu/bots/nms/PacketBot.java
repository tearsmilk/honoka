package dev.tearsmilk.miharu.bots.nms;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Фейк-игрок только из пакетов: на сервере сущности нет, клиенты видят обычного игрока.
 * Удары по нему приходят как PlayerUseUnknownEntityEvent (Paper).
 * Это единственное место плагина, которое трогает NMS (Mojang-маппинги Paper 1.21.11).
 */
public final class PacketBot {

    private static final EntityDataAccessor<Byte> SHARED_FLAGS = accessor(Entity.class, "DATA_SHARED_FLAGS_ID");
    private static final EntityDataAccessor<Pose> POSE = accessor(Entity.class, "DATA_POSE");
    private static final EntityDataAccessor<Byte> LIVING_FLAGS = accessor(LivingEntity.class, "DATA_LIVING_ENTITY_FLAGS");

    private final int entityId = Entity.nextEntityId();
    private final UUID uuid = UUID.randomUUID();
    private final String name;
    private final GameProfile profile;
    private final boolean listed;
    private final Set<Player> viewers = new HashSet<>();
    private final Map<org.bukkit.inventory.EquipmentSlot, ItemStack> equipment = new EnumMap<>(org.bukkit.inventory.EquipmentSlot.class);

    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private boolean onGround = true;
    private boolean sprinting;
    private boolean sneaking;
    private boolean blocking;
    private byte lastFlags = -1;
    private byte lastLiving = -1;

    public PacketBot(String name, @Nullable String skinValue, @Nullable String skinSignature, boolean listed) {
        this.name = name.length() > 16 ? name.substring(0, 16) : name;
        this.listed = listed;
        if (skinValue != null && !skinValue.isEmpty()) {
            this.profile = new GameProfile(uuid, this.name, new PropertyMap(ImmutableMultimap.of("textures",
                    new Property("textures", skinValue, skinSignature == null || skinSignature.isEmpty() ? null : skinSignature))));
        } else {
            this.profile = new GameProfile(uuid, this.name);
        }
    }

    public int id() {
        return entityId;
    }

    public String name() {
        return name;
    }

    public Set<Player> viewers() {
        return viewers;
    }

    public void position(double x, double y, double z, float yaw, float pitch, boolean onGround) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
    }

    public void sprinting(boolean v) {
        sprinting = v;
    }

    public void sneaking(boolean v) {
        sneaking = v;
    }

    public void blocking(boolean v) {
        blocking = v;
    }

    public void equip(org.bukkit.inventory.EquipmentSlot slot, @Nullable ItemStack item) {
        if (item == null || item.getType().isAir()) {
            equipment.remove(slot);
        } else {
            equipment.put(slot, item.clone());
        }
        broadcast(equipmentPacket());
    }

    // --- показ ---

    public void show(Player viewer) {
        if (!viewers.add(viewer)) {
            return;
        }
        List<Packet<? super ClientGamePacketListener>> list = new ArrayList<>();
        ClientboundPlayerInfoUpdatePacket.Entry entry = new ClientboundPlayerInfoUpdatePacket.Entry(
                uuid, profile, listed, 0, GameType.SURVIVAL, null, true, 0, null);
        list.add(new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY), entry));
        list.add(new ClientboundAddEntityPacket(entityId, uuid, x, y, z, pitch, yaw, EntityType.PLAYER, 0, Vec3.ZERO, yaw));
        list.add(new ClientboundSetEntityDataPacket(entityId, List.of(
                SynchedEntityData.DataValue.create(Avatar.DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7F),
                SynchedEntityData.DataValue.create(LivingEntity.DATA_HEALTH_ID, 20f))));
        list.add(headPacket());
        if (!equipment.isEmpty()) {
            list.add(equipmentPacket());
        }
        send(viewer, new ClientboundBundlePacket(list));
        lastFlags = -1;
        lastLiving = -1;
    }

    public void hide(Player viewer) {
        if (!viewers.remove(viewer)) {
            return;
        }
        if (viewer.isOnline()) {
            send(viewer, new ClientboundRemoveEntitiesPacket(entityId));
            send(viewer, new ClientboundPlayerInfoRemovePacket(List.of(uuid)));
        }
    }

    public void hideAll() {
        for (Player p : List.copyOf(viewers)) {
            hide(p);
        }
    }

    // --- каждый тик ---

    public void sync() {
        if (viewers.isEmpty()) {
            return;
        }
        List<Packet<? super ClientGamePacketListener>> list = new ArrayList<>(3);
        list.add(new ClientboundEntityPositionSyncPacket(entityId,
                new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yaw, pitch), onGround));
        list.add(headPacket());
        byte flags = (byte) ((sneaking ? 0x02 : 0) | (sprinting ? 0x08 : 0));
        byte living = (byte) (blocking ? 0x01 : 0);
        if (flags != lastFlags || living != lastLiving) {
            list.add(new ClientboundSetEntityDataPacket(entityId, List.of(
                    SynchedEntityData.DataValue.create(SHARED_FLAGS, flags),
                    SynchedEntityData.DataValue.create(LIVING_FLAGS, living),
                    SynchedEntityData.DataValue.create(POSE, sneaking ? Pose.CROUCHING : Pose.STANDING))));
            lastFlags = flags;
            lastLiving = living;
        }
        broadcast(new ClientboundBundlePacket(list));
    }

    /** Взмах рукой. */
    public void swing() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(entityId);
        buf.writeByte(ClientboundAnimatePacket.SWING_MAIN_HAND);
        broadcast(ClientboundAnimatePacket.STREAM_CODEC.decode(buf));
    }

    /** Покраснение + наклон от удара. yaw — направление, откуда пришёл удар (относительно бота). */
    public void hurt(float yaw) {
        broadcast(new ClientboundHurtAnimationPacket(entityId, yaw));
    }

    /** Анимация крита (частицы у клиента). */
    public void critEffect() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(entityId);
        buf.writeByte(ClientboundAnimatePacket.CRITICAL_HIT);
        broadcast(ClientboundAnimatePacket.STREAM_CODEC.decode(buf));
    }

    /** Здоровье 0 — клиент сам проигрывает анимацию падения. */
    public void health(float hp) {
        broadcast(new ClientboundSetEntityDataPacket(entityId, List.of(SynchedEntityData.DataValue.create(LivingEntity.DATA_HEALTH_ID, hp))));
    }

    private ClientboundRotateHeadPacket headPacket() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(entityId);
        buf.writeByte((byte) Math.floor(yaw * 256.0F / 360.0F));
        return ClientboundRotateHeadPacket.STREAM_CODEC.decode(buf);
    }

    private ClientboundSetEquipmentPacket equipmentPacket() {
        List<Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> slots = new ArrayList<>();
        for (org.bukkit.inventory.EquipmentSlot slot : org.bukkit.inventory.EquipmentSlot.values()) {
            EquipmentSlot nms = switch (slot) {
                case HAND -> EquipmentSlot.MAINHAND;
                case OFF_HAND -> EquipmentSlot.OFFHAND;
                case HEAD -> EquipmentSlot.HEAD;
                case CHEST -> EquipmentSlot.CHEST;
                case LEGS -> EquipmentSlot.LEGS;
                case FEET -> EquipmentSlot.FEET;
                default -> null;
            };
            if (nms == null) {
                continue;
            }
            ItemStack item = equipment.get(slot);
            slots.add(Pair.of(nms, item == null ? net.minecraft.world.item.ItemStack.EMPTY : CraftItemStack.asNMSCopy(item)));
        }
        return new ClientboundSetEquipmentPacket(entityId, slots);
    }

    private void broadcast(Packet<?> packet) {
        for (Player p : viewers) {
            send(p, packet);
        }
    }

    private static void send(Player p, Packet<?> packet) {
        if (p.isOnline()) {
            ((CraftPlayer) p).getHandle().connection.send(packet);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> EntityDataAccessor<T> accessor(Class<?> owner, String field) {
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            return (EntityDataAccessor<T>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("NMS не совпадает с Paper 1.21.11: нет поля " + owner.getSimpleName() + "." + field, e);
        }
    }
}
