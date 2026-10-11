package dev.huddle.neoforge;

import dev.huddle.neoforge.mixin.EntityAccessor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// el brillo de un objetivo lo ve solo quien debe: los demás reciben las banderas reales, sin brillo
@GameTestHolder(Huddle.MODID)
@PrefixGameTestTemplate(false)
public final class GlowTest {
    private static final byte ON_FIRE = 1;
    private static final byte GLOWING = 1 << 6;

    private static ClientboundSetEntityDataPacket flags(Entity e, byte value) {
        return new ClientboundSetEntityDataPacket(e.getId(), List.of(SynchedEntityData.DataValue.create(EntityAccessor.huddle$sharedFlags(), value)));
    }

    // banderas de la entidad en lo recibido, abriendo los paquetes agrupados; null si no llegó ninguna
    private static Byte flagsSeen(List<Packet<?>> packets, Entity e) {
        Byte seen = null;
        List<Packet<?>> all = new ArrayList<>();
        for (Packet<?> p : packets) {
            if (p instanceof ClientboundBundlePacket bundle) {
                bundle.subPackets().forEach(all::add);
            } else {
                all.add(p);
            }
        }
        for (Packet<?> p : all) {
            if (p instanceof ClientboundSetEntityDataPacket data && data.id() == e.getId()) {
                for (SynchedEntityData.DataValue<?> item : data.packedItems()) {
                    if (item.id() == EntityAccessor.huddle$sharedFlags().id()) {
                        seen = (Byte) item.value();
                    }
                }
            }
        }
        return seen;
    }

    private static void expect(StringBuilder wrong, String what, Byte seen, Byte expected) {
        if (seen == null ? expected != null : !seen.equals(expected)) {
            wrong.append(' ').append(what).append(": got ").append(seen).append(", expected ").append(expected);
        }
    }

    @GameTest(template = "arena")
    public static void onlyTheViewerSeesTheGlow(GameTestHelper helper) {
        ServerPlayer viewer = TestPlayers.join(helper, "viewer");
        ServerPlayer outsider = TestPlayers.join(helper, "outsider");
        Entity target = helper.spawn(EntityType.PIG, 1, 1, 1);
        Entity other = helper.spawn(EntityType.PIG, 5, 1, 5);
        StringBuilder wrong = new StringBuilder();
        try {
            TestPlayers.received(viewer);
            TestPlayers.received(outsider);
            Glow.show(viewer, target);
            expect(wrong, "viewer on show", flagsSeen(TestPlayers.received(viewer), target), GLOWING);
            expect(wrong, "outsider on show", flagsSeen(TestPlayers.received(outsider), target), null);
            if (target.isCurrentlyGlowing()) wrong.append(" glows on the server");

            // el mismo paquete para todos, como lo manda el servidor al cambiar una bandera
            ClientboundSetEntityDataPacket fire = flags(target, ON_FIRE);
            viewer.connection.send(fire);
            outsider.connection.send(fire);
            expect(wrong, "viewer on fire", flagsSeen(TestPlayers.received(viewer), target), (byte) (ON_FIRE | GLOWING));
            expect(wrong, "outsider on fire", flagsSeen(TestPlayers.received(outsider), target), ON_FIRE);
            expect(wrong, "shared packet changed", flagsSeen(List.of(fire), target), ON_FIRE);

            // al entrar en rango los datos llegan agrupados
            ClientboundBundlePacket pairing = new ClientboundBundlePacket(List.of(flags(target, (byte) 0)));
            viewer.connection.send(pairing);
            outsider.connection.send(pairing);
            expect(wrong, "viewer pairing", flagsSeen(TestPlayers.received(viewer), target), GLOWING);
            expect(wrong, "outsider pairing", flagsSeen(TestPlayers.received(outsider), target), (byte) 0);

            viewer.connection.send(flags(other, (byte) 0));
            expect(wrong, "other entity", flagsSeen(TestPlayers.received(viewer), other), (byte) 0);

            Glow.hide(viewer, target);
            expect(wrong, "viewer on hide", flagsSeen(TestPlayers.received(viewer), target), (byte) 0);
            viewer.connection.send(fire);
            expect(wrong, "viewer after hide", flagsSeen(TestPlayers.received(viewer), target), ON_FIRE);
        } finally {
            TestPlayers.leave(helper, viewer);
            TestPlayers.leave(helper, outsider);
        }
        if (!wrong.isEmpty()) throw new GameTestAssertException(wrong.toString().trim());
        helper.succeed();
    }

    // al salir del servidor se olvida lo que veía: si no, el mapa crece con cada jugador que pasó por un evento
    @GameTest(template = "arena")
    public static void glowIsForgottenOnLogout(GameTestHelper helper) {
        ServerPlayer viewer = TestPlayers.join(helper, "leaver");
        Entity target = helper.spawn(EntityType.PIG, 1, 1, 1);
        Glow.show(viewer, target);
        TestPlayers.leave(helper, viewer);
        if (Glow.remembers(viewer.getUUID())) throw new GameTestAssertException("glow kept after logout");
        helper.succeed();
    }
}
