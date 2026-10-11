package dev.huddle.neoforge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;

// morir en un evento no suelta nada: la muerte se cancela antes de las tumbas (Corpse y GraveStone
// escuchan en LOWEST sin recibir cancelados; Tombstone los recibe y solo deja un aviso en el log).
// Se sigue en el evento (ver Lobbies.afterDeath); en el último en pie y el marcado de la recompensa, se sale.
// Con keepInventoryOnDeath en false la muerte sigue su curso, se sale y al reaparecer vuelve a donde se unió.
public final class EventDeaths {
    // solo se usa desde el hilo del servidor; se pierde si el servidor se reinicia antes de reaparecer
    private static final Map<UUID, Participants.Spot> RETURN_ON_RESPAWN = new HashMap<>();

    private EventDeaths() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, EventDeaths::keepEverything);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, EventDeaths::dieForReal);
        NeoForge.EVENT_BUS.addListener(EventDeaths::onRespawn);
    }

    private static void keepEverything(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || Participants.of(player) == null
                || !HuddleConfig.KEEP_INVENTORY_ON_DEATH.get()) {
            return;
        }
        event.setCanceled(true);
        player.setHealth(player.getMaxHealth());
        player.clearFire();
        player.removeAllEffects();
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
        Participants.Spot joinedAt = Participants.joinedAt(player);
        Lobbies.onDeath(player, killer(event));
        Lobbies.AfterDeath after = Lobbies.afterDeath(player);
        if (after != Lobbies.AfterDeath.OUT) {
            if (after == Lobbies.AfterDeath.JOIN_POINT && !returnTo(player, joinedAt)) {
                toRespawnPoint(player);
            }
            player.sendSystemMessage(Component.translatableWithFallback("huddle.event.respawned",
                    "You died, but you are still in the event. You keep all your items."));
            return;
        }
        Lobbies.leave(player.server, player.getUUID());
        // si se queda donde cayó (vacío, lava) vuelve a morir, ya fuera del evento y perdiendo todo
        if (!returnTo(player, joinedAt)) {
            toRespawnPoint(player);
        }
        player.sendSystemMessage(Component.translatableWithFallback("huddle.event.knocked_out",
                "You were knocked out of the event. You keep all your items."));
        Huddle.LOGGER.debug("{} was knocked out of an event without dying", player.getGameProfile().getName());
    }

    // LOWEST y sin recibir cancelados: solo cuenta si de verdad murió
    private static void dieForReal(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || Participants.of(player) == null) {
            return;
        }
        Participants.Spot joinedAt = Participants.joinedAt(player);
        Lobbies.onDeath(player, killer(event));
        Lobbies.leave(player.server, player.getUUID());
        RETURN_ON_RESPAWN.put(player.getUUID(), joinedAt);
    }

    // golpe, flecha, tridente o explosión con dueño: el jugador que la causó
    private static ServerPlayer killer(LivingDeathEvent event) {
        return event.getSource().getEntity() instanceof ServerPlayer p ? p : null;
    }

    // cambia el destino antes de crear al jugador nuevo, en vez de moverlo después de aparecer
    private static void onRespawn(PlayerRespawnPositionEvent event) {
        Participants.Spot joinedAt = RETURN_ON_RESPAWN.remove(event.getEntity().getUUID());
        if (joinedAt == null || event.isFromEndFight() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = joinPoint(player, joinedAt);
        if (level != null) {
            event.setDimensionTransition(new DimensionTransition(level, joinedAt.pos(), Vec3.ZERO, joinedAt.yRot(), joinedAt.xRot(),
                    DimensionTransition.DO_NOTHING));
        }
    }

    private static boolean returnTo(ServerPlayer player, Participants.Spot spot) {
        ServerLevel level = joinPoint(player, spot);
        if (level == null) {
            return false;
        }
        player.teleportTo(level, spot.pos().x, spot.pos().y, spot.pos().z, spot.yRot(), spot.xRot());
        return true;
    }

    // null: no hay que volver al punto de unión o ya no es seguro
    private static ServerLevel joinPoint(ServerPlayer player, Participants.Spot spot) {
        if (spot == null || HuddleConfig.DEATH_DESTINATION.get() != HuddleConfig.DeathDestination.JOIN_POINT) {
            return null;
        }
        ServerLevel level = player.server.getLevel(spot.dimension());
        return level != null && isSafe(player, level, spot.pos()) ? level : null;
    }

    // el lugar pudo cambiar desde que se unió: lava, fuego o bloques donde estaba parado
    static boolean isSafe(ServerPlayer player, ServerLevel level, Vec3 pos) {
        if (pos.y < level.getMinBuildHeight() || pos.y > level.getMaxBuildHeight()) {
            return false;
        }
        BlockPos feet = BlockPos.containing(pos);
        for (BlockPos p : new BlockPos[] {feet, feet.above()}) {
            if (level.getFluidState(p).is(FluidTags.LAVA) || level.getBlockState(p).is(BlockTags.FIRE)) {
                return false;
            }
        }
        return level.noCollision(player, player.getDimensions(Pose.STANDING).makeBoundingBox(pos));
    }

    // cama o ancla (sin gastar carga, como con keepInventory) o el spawn del mundo
    static void toRespawnPoint(ServerPlayer player) {
        DimensionTransition home = player.findRespawnPositionAndUseSpawnBlock(true, DimensionTransition.DO_NOTHING);
        if (player.changeDimension(home) == null) {
            // otro mod canceló el viaje: al menos sale del peligro dentro del mismo mundo
            BlockPos spawn = player.serverLevel().getSharedSpawnPos();
            player.teleportTo(player.serverLevel(), spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, player.getYRot(), player.getXRot());
        }
    }
}
