package dev.huddle.neoforge;

import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

// eventos que llevan a los jugadores lejos (la carrera a su largada) los devuelven a donde estaban.
// Sin esto, abrir una carrera en una pista lejana y salir sería un teletransporte gratis.
// Los regresos pendientes se guardan con el mundo: quien estaba desconectado o una caída del servidor
// vuelve al entrar.
public final class Homecoming {
    // más lejos que esto de donde la dejó, la montura se fue sola o se la llevaron: vuelve a pie
    static final double MOUNT_REACH = 16;

    private Homecoming() {
    }

    public static void remember(MinecraftServer server, UUID player, Participants.Spot home) {
        HuddleData data = HuddleData.get(server);
        data.returns().put(player, home);
        data.changed();
    }

    public static boolean pending(MinecraftServer server, UUID player) {
        return HuddleData.get(server).returns().containsKey(player);
    }

    // lo devuelve ahora si está conectado y vivo; si no, queda pendiente para cuando entre o reaparezca
    public static void bring(MinecraftServer server, UUID player) {
        ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (p == null || p.isDeadOrDying()) {
            return;
        }
        HuddleData data = HuddleData.get(server);
        Participants.Spot home = data.returns().remove(player);
        if (home == null) {
            return;
        }
        data.changed();
        ServerLevel level = server.getLevel(home.dimension());
        // su montura sigue donde la dejó y libre: vuelve sentado en ella
        Entity mount = level == null || home.mount() == null ? null : level.getEntity(home.mount());
        if (mount != null && mount.isAlive() && mount.getPassengers().isEmpty() && mount.position().distanceTo(home.pos()) < MOUNT_REACH) {
            p.stopRiding();
            p.teleportTo(level, mount.getX(), mount.getY(), mount.getZ(), home.yRot(), home.xRot());
            p.startRiding(mount, true);
            return;
        }
        if (level != null && EventDeaths.isSafe(p, level, home.pos())) {
            move(p, level, home.pos(), home.yRot(), home.xRot(), true);
        } else {
            EventDeaths.toRespawnPoint(p);
        }
    }

    public static void forget(MinecraftServer server, UUID player) {
        if (server != null && HuddleData.get(server).returns().remove(player) != null) {
            HuddleData.get(server).changed();
        }
    }

    public static void onLogin(ServerPlayer p) {
        if (Lobbies.of(p) == null) {
            bring(p.server, p.getUUID());
        }
    }

    // con withMount, su caballo o bote viaja con él si es el único que va arriba y no cambia de dimensión
    static void move(ServerPlayer p, ServerLevel level, Vec3 at, float yRot, float xRot, boolean withMount) {
        Entity mount = p.getVehicle();
        if (mount != null) {
            p.stopRiding();
        }
        p.teleportTo(level, at.x, at.y, at.z, yRot, xRot);
        if (withMount && mount != null && mount.isAlive() && mount.level() == level && mount.getPassengers().isEmpty()) {
            mount.teleportTo(level, at.x, at.y, at.z, java.util.Set.of(), yRot, mount.getXRot());
            p.startRiding(mount, true);
        }
    }

    static Map<UUID, Participants.Spot> all(MinecraftServer server) {
        return Map.copyOf(HuddleData.get(server).returns());
    }
}
