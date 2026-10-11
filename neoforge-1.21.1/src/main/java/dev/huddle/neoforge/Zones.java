package dev.huddle.neoforge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDelayPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDistancePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;

// la zona de un evento como borde del mundo que ve solo cada participante: pared y tinte rojo del propio juego.
// El borde real no se toca; mientras el jugador tiene zona, los paquetes del borde real hacia él se cambian por la zona.
// Solo se usa desde el hilo del servidor
public final class Zones {
    private record Zone(ResourceKey<Level> level, WorldBorder border) {
    }

    private static final Map<UUID, Zone> ZONES = new HashMap<>();
    private static boolean sending;

    private Zones() {
    }

    // halfFrom a halfTo en millis (0: sin achicarse)
    static void show(ServerPlayer p, double x, double z, double halfFrom, double halfTo, long millis) {
        WorldBorder b = new WorldBorder();
        b.setCenter(x, z);
        if (millis > 0 && halfFrom != halfTo) {
            b.lerpSizeBetween(halfFrom * 2, halfTo * 2, millis);
        } else {
            b.setSize(halfTo * 2);
        }
        b.setWarningBlocks(4);
        b.setWarningTime(0);
        ZONES.put(p.getUUID(), new Zone(p.level().dimension(), b));
        send(p, new ClientboundInitializeBorderPacket(b));
    }

    static void hide(ServerPlayer p) {
        if (ZONES.remove(p.getUUID()) != null) {
            send(p, new ClientboundInitializeBorderPacket(p.level().getWorldBorder()));
        }
    }

    static void forget(UUID player) {
        ZONES.remove(player);
    }

    static boolean has(UUID player) {
        return ZONES.containsKey(player);
    }

    private static void send(ServerPlayer p, Packet<?> packet) {
        sending = true;
        try {
            p.connection.send(packet);
        } finally {
            sending = false;
        }
    }

    // el borde real (al cambiar el admin, al reaparecer) no pisa la zona mientras está en ese mundo
    public static Packet<?> forViewer(ServerPlayer viewer, Packet<?> packet) {
        if (sending || !(packet instanceof ClientboundInitializeBorderPacket || packet instanceof ClientboundSetBorderCenterPacket
                || packet instanceof ClientboundSetBorderLerpSizePacket || packet instanceof ClientboundSetBorderSizePacket
                || packet instanceof ClientboundSetBorderWarningDelayPacket || packet instanceof ClientboundSetBorderWarningDistancePacket)) {
            return packet;
        }
        Zone z = ZONES.get(viewer.getUUID());
        if (z == null || viewer.level().dimension() != z.level()) {
            return packet;
        }
        return new ClientboundInitializeBorderPacket(z.border());
    }
}
