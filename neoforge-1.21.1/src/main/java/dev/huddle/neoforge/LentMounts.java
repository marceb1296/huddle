package dev.huddle.neoforge;

import dev.huddle.core.Route;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

// bote o caballo que el evento presta en la largada, igual para todos. No se puede dañar, solo lo usa quien lo recibió,
// no suelta nada y desaparece al salir de la carrera; si quedó uno por una caída del servidor se borra al cargarse.
// Solo se usa desde el hilo del servidor
final class LentMounts {
    static final String TAG = "huddle_lent";
    static final double HORSE_SPEED = 0.3;
    static final double HORSE_JUMP = 0.7;

    // montura -> jugador
    private static final Map<UUID, UUID> RIDER = new HashMap<>();
    // jugador -> montura
    private static final Map<UUID, UUID> MOUNT = new HashMap<>();

    private LentMounts() {
    }

    static boolean lends(Route.Mode mode) {
        return mode == Route.Mode.BOAT || mode == Route.Mode.HORSE;
    }

    // lo deja en `at` montado en su montura prestada; la crea la primera vez. La propia queda donde estaba
    static void seat(ServerPlayer p, ServerLevel level, Route.Mode mode, Vec3 at, float yaw) {
        Entity mount = of(level, p.getUUID());
        p.stopRiding();
        p.teleportTo(level, at.x, at.y, at.z, yaw, 0);
        if (mount == null) {
            mount = create(level, mode, p);
            if (mount == null) {
                return;
            }
            mount.moveTo(at.x, at.y, at.z, yaw, 0);
            // antes de agregarla: sin registro, la protección contra sobrantes no la deja entrar
            RIDER.put(mount.getUUID(), p.getUUID());
            MOUNT.put(p.getUUID(), mount.getUUID());
            if (!level.addFreshEntity(mount)) {
                RIDER.remove(mount.getUUID());
                MOUNT.remove(p.getUUID());
                return;
            }
        } else {
            mount.teleportTo(level, at.x, at.y, at.z, java.util.Set.of(), yaw, 0);
        }
        p.startRiding(mount, true);
    }

    private static Entity create(ServerLevel level, Route.Mode mode, ServerPlayer p) {
        Entity e;
        if (mode == Route.Mode.BOAT) {
            e = EntityType.BOAT.create(level);
        } else {
            Horse h = EntityType.HORSE.create(level);
            if (h != null) {
                h.setTamed(true);
                h.setOwnerUUID(p.getUUID());
                h.equipSaddle(new ItemStack(Items.SADDLE), null);
                h.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(HORSE_SPEED);
                h.getAttribute(Attributes.JUMP_STRENGTH).setBaseValue(HORSE_JUMP);
                h.setHealth(h.getMaxHealth());
                h.setPersistenceRequired();
            }
            e = h;
        }
        if (e != null) {
            e.addTag(TAG);
            e.setInvulnerable(true);
        }
        return e;
    }

    static Entity of(ServerLevel level, UUID player) {
        UUID id = MOUNT.get(player);
        return id == null ? null : level.getEntity(id);
    }

    // al salir de la carrera por cualquier motivo; con el jugador desconectado la montura viaja en sus datos
    // y se borra al volver, porque ya no figura
    static void release(MinecraftServer server, UUID player) {
        UUID id = MOUNT.remove(player);
        if (id == null) {
            return;
        }
        RIDER.remove(id);
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null) {
                e.ejectPassengers();
                e.discard();
            }
        }
    }

    static boolean isLent(Entity e) {
        return e.getTags().contains(TAG);
    }

    static boolean isLeftover(Entity e) {
        return isLent(e) && !RIDER.containsKey(e.getUUID());
    }

    // solo quien la recibió la monta; agachado no (abriría el inventario del caballo)
    static boolean mayUse(ServerPlayer p, Entity e) {
        return p.getUUID().equals(RIDER.get(e.getUUID())) && !p.isShiftKeyDown();
    }

    static void clear() {
        RIDER.clear();
        MOUNT.clear();
    }
}
