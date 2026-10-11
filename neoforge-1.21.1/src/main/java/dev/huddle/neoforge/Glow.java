package dev.huddle.neoforge;

import dev.huddle.neoforge.mixin.EntityAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

// brillo que solo ven algunos jugadores: en el servidor la entidad no brilla, y a cada uno de esos
// jugadores se le reescribe la bandera de brillo en los paquetes de datos que recibe (PlayerPacketMixin).
// Solo se usa desde el hilo del servidor
public final class Glow {
    private static final byte GLOWING = 1 << 6;
    private static final Map<UUID, Set<Integer>> SEEN_BY = new HashMap<>();

    private Glow() {
    }

    public static void show(ServerPlayer viewer, Entity target) {
        if (SEEN_BY.computeIfAbsent(viewer.getUUID(), u -> new HashSet<>()).add(target.getId())) {
            resend(viewer, target);
        }
    }

    public static void hide(ServerPlayer viewer, Entity target) {
        Set<Integer> ids = SEEN_BY.get(viewer.getUUID());
        if (ids != null && ids.remove(target.getId())) {
            if (ids.isEmpty()) {
                SEEN_BY.remove(viewer.getUUID());
            }
            resend(viewer, target);
        }
    }

    public static void forget(UUID viewer) {
        SEEN_BY.remove(viewer);
    }

    static boolean remembers(UUID viewer) {
        return SEEN_BY.containsKey(viewer);
    }

    // las banderas reales de la entidad; el mixin les suma el brillo si corresponde
    private static void resend(ServerPlayer viewer, Entity target) {
        EntityDataAccessor<Byte> flags = EntityAccessor.huddle$sharedFlags();
        viewer.connection.send(new ClientboundSetEntityDataPacket(target.getId(),
                List.of(SynchedEntityData.DataValue.create(flags, target.getEntityData().get(flags)))));
    }

    // el servidor manda el mismo paquete a todos los que ven la entidad: nunca modificarlo, crear otro
    public static Packet<?> forViewer(ServerPlayer viewer, Packet<?> packet) {
        Set<Integer> ids = SEEN_BY.get(viewer.getUUID());
        if (ids == null) {
            return packet;
        }
        if (packet instanceof ClientboundSetEntityDataPacket data) {
            return ids.contains(data.id()) ? withGlow(data) : packet;
        }
        // al entrar en rango, los datos de la entidad llegan dentro de un paquete agrupado
        if (packet instanceof ClientboundBundlePacket bundle) {
            List<Packet<? super ClientGamePacketListener>> out = new ArrayList<>();
            boolean changed = false;
            for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                @SuppressWarnings("unchecked")
                Packet<? super ClientGamePacketListener> now = (Packet<? super ClientGamePacketListener>) forViewer(viewer, sub);
                changed |= now != sub;
                out.add(now);
            }
            return changed ? new ClientboundBundlePacket(out) : packet;
        }
        return packet;
    }

    private static ClientboundSetEntityDataPacket withGlow(ClientboundSetEntityDataPacket data) {
        int flagsId = EntityAccessor.huddle$sharedFlags().id();
        List<SynchedEntityData.DataValue<?>> items = new ArrayList<>(data.packedItems().size());
        boolean changed = false;
        for (SynchedEntityData.DataValue<?> item : data.packedItems()) {
            if (item.id() == flagsId && item.value() instanceof Byte b && (b & GLOWING) == 0) {
                items.add(SynchedEntityData.DataValue.create(EntityAccessor.huddle$sharedFlags(), (byte) (b | GLOWING)));
                changed = true;
            } else {
                items.add(item);
            }
        }
        return changed ? new ClientboundSetEntityDataPacket(data.id(), items) : data;
    }
}
