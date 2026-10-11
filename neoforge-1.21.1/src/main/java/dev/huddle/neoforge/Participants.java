package dev.huddle.neoforge;

import dev.huddle.core.EventTag;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

// solo se usa desde el hilo del servidor
public final class Participants {
    // dónde estaba el jugador al unirse: ahí vuelve si muere en el evento. Si iba montado, pos es la de su montura
    // (el asiento queda bajo el suelo) y mount la montura, para volver a sentarlo en ella
    public record Spot(ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot, UUID mount) {
        public Spot(ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot) {
            this(dimension, pos, yRot, xRot, null);
        }
    }

    private record Entry(EventTag event, Spot joinedAt) {
    }

    private static final Map<UUID, Entry> BY_PLAYER = new HashMap<>();

    private Participants() {
    }

    public static EventTag of(Player player) {
        Entry e = BY_PLAYER.get(player.getUUID());
        return e == null ? null : e.event();
    }

    public static Spot joinedAt(Player player) {
        Entry e = BY_PLAYER.get(player.getUUID());
        return e == null ? null : e.joinedAt();
    }

    public static void join(Player player, EventTag event) {
        Entity v = player.getVehicle();
        UUID mount = v == null || LentMounts.isLent(v) ? null : v.getUUID();
        BY_PLAYER.put(player.getUUID(), new Entry(event, new Spot(player.level().dimension(), v == null ? player.position() : v.position(),
                player.getYRot(), player.getXRot(), mount)));
    }

    public static void leave(Player player) {
        leave(player.getUUID());
    }

    public static void leave(UUID player) {
        BY_PLAYER.remove(player);
    }
}
