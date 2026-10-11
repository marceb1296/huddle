package dev.huddle.neoforge.mixin;

import dev.huddle.neoforge.Glow;
import dev.huddle.neoforge.Lang;
import dev.huddle.neoforge.Zones;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// todo paquete hacia un jugador pasa por aquí, también los agrupados con que llega una entidad al entrar en rango:
// brillo y zona por jugador y textos en su idioma
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class PlayerPacketMixin {
    @ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), argsOnly = true)
    private Packet<?> huddle$forViewer(Packet<?> packet) {
        if ((Object) this instanceof ServerGamePacketListenerImpl game) {
            return Lang.forViewer(game.player, Glow.forViewer(game.player, Zones.forViewer(game.player, packet)));
        }
        return packet;
    }
}
