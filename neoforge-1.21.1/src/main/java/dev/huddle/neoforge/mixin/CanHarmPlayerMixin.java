package dev.huddle.neoforge.mixin;

import dev.huddle.core.PvpRule;
import dev.huddle.neoforge.Participants;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// todo el PvP vanilla pasa por aquí (golpes, flechas, explosiones con dueño, lobos), y con pvp=false
// se corta antes del evento de daño de NeoForge: por eso no alcanza con escuchar ese evento
@Mixin(ServerPlayer.class)
abstract class CanHarmPlayerMixin {
    @Inject(method = "canHarmPlayer", at = @At("HEAD"), cancellable = true)
    private void huddle$eventPvp(Player other, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        switch (PvpRule.between(Participants.of(self), Participants.of(other), self.server.getTickCount())) {
            case ALLOW -> cir.setReturnValue(true);
            case DENY -> cir.setReturnValue(false);
            case VANILLA -> {
            }
        }
    }
}
