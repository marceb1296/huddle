package dev.huddle.neoforge;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

// como makeMockServerPlayerInLevel, pero sin forzar isCreative: los mods de tumbas ignoran a los creativos
final class TestPlayers {
    private static final Map<UUID, EmbeddedChannel> CHANNELS = new HashMap<>();

    private TestPlayers() {
    }

    static ServerPlayer join(GameTestHelper helper, String name) {
        return join(helper, name, UUID.randomUUID());
    }

    // con un UUID dado: el mismo jugador que vuelve a entrar
    static ServerPlayer join(GameTestHelper helper, String name, UUID id) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(id, name), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        acceptModChannels(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
        p.setGameMode(GameType.SURVIVAL);
        CHANNELS.put(p.getUUID(), channel);
        try {
            // recién conectado no recibe daño durante 60 ticks, y el jugador de prueba no avanza ticks
            Field f = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
            f.setAccessible(true);
            f.setInt(p, 0);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    // como un cliente con los mismos mods: sin esto, los mods que mandan paquetes propios (Corpse, Tombstone)
    // fallan con "Payload ... may not be sent to the client"
    @SuppressWarnings("unchecked")
    private static void acceptModChannels(Connection connection) {
        try {
            Field f = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
            f.setAccessible(true);
            Map<ConnectionProtocol, Map<ResourceLocation, ?>> registrations = (Map<ConnectionProtocol, Map<ResourceLocation, ?>>) f.get(null);
            ChannelAttributes.getOrCreateAdHocChannels(connection).addAll(registrations.getOrDefault(ConnectionProtocol.PLAY, Map.of()).keySet());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // los paquetes que el jugador recibió desde la última llamada (sin codificar: el canal no tiene encoder)
    static List<Packet<?>> received(ServerPlayer p) {
        EmbeddedChannel channel = CHANNELS.get(p.getUUID());
        List<Packet<?>> out = new ArrayList<>();
        for (Object o; (o = channel.readOutbound()) != null; ) {
            if (o instanceof Packet<?> packet) {
                out.add(packet);
            }
        }
        return out;
    }

    // títulos (T:) y subtítulos (S:) en el orden en que llegaron
    static List<String> titles(List<Packet<?>> packets) {
        List<String> out = new ArrayList<>();
        for (Packet<?> packet : packets) {
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket t) {
                out.add("T:" + t.text().getString());
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket t) {
                out.add("S:" + t.text().getString());
            }
        }
        return out;
    }

    // el título grande no salta de línea: con la interfaz en escala 4 a 1080p entran unos 20 caracteres
    static boolean shortTitles(List<String> titles) {
        return titles.stream().filter(t -> t.startsWith("T:")).allMatch(t -> t.length() - 2 <= 20);
    }

    static void leave(GameTestHelper helper, ServerPlayer p) {
        Participants.leave(p);
        helper.getLevel().getServer().getPlayerList().remove(p);
        CHANNELS.remove(p.getUUID());
    }
}
