package dev.huddle.neoforge;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;

@Mod(Huddle.MODID)
public final class Huddle {
    public static final String MODID = "huddle";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Huddle(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, HuddleConfig.SPEC);
        EventDeaths.register();
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, event -> {
            Glow.forget(event.getEntity().getUUID());
            Zones.forget(event.getEntity().getUUID());
            if (event.getEntity() instanceof ServerPlayer p) {
                Lobbies.onLogout(p);
                Tracks.onLogout(p);
                TrackGen.onLogout(p);
            }
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer p) {
                Lobbies.onLogin(p);
                // kit que quedó de un evento que terminó sin él (caída del servidor o desconexión larga)
                if (!Lobbies.inLastMan(p.getUUID()) && Kits.strip(p) > 0) {
                    LOGGER.info("Huddle took back a leftover event kit from {}", p.getGameProfile().getName());
                }
                Homecoming.onLogin(p);
                BuyIns.deliver(p);
            }
        });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
            Lobbies.tick(event.getServer());
            Tracks.tick(event.getServer());
            TrackGen.tick(event.getServer());
        });
        NeoForge.EVENT_BUS.addListener(EntityTeleportEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer p) {
                Tracks.onTeleport(p);
                // el descalificado ya volvió a casa: si el teletransporte sigue, lo deja otra vez en la pista
                if (Lobbies.onTeleport(p)) {
                    event.setCanceled(true);
                }
            }
        });
        // muerte de verdad (keepInventoryOnDeath en false): ya reaparece en su punto de unión o su cama
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent.class, event -> {
            Homecoming.forget(event.getEntity().getServer(), event.getEntity().getUUID());
            if (event.getEntity() instanceof ServerPlayer p) {
                BuyIns.deliver(p);
            }
        });
        // con un modo de combate votado, el golpe que no lo cumple no llega (ni el empuje)
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer victim && CombatModes.blocks(victim, event.getSource())) {
                event.setCanceled(true);
            }
        });
        // daño que de verdad llegó: el marcado de una recompensa recuerda quién le pegó último
        NeoForge.EVENT_BUS.addListener(LivingDamageEvent.Post.class, event -> {
            if (event.getEntity() instanceof ServerPlayer victim) {
                Lobbies.onPlayerHurt(victim, event.getSource().getEntity() instanceof ServerPlayer attacker && attacker != victim ? attacker : null);
            }
        });
        // el cofre del tesoro: clic derecho o izquierdo sobre su caja
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.EntityInteract.class, event -> {
            if (event.getEntity() instanceof ServerPlayer p && event.getHand() == InteractionHand.MAIN_HAND
                    && Lobbies.onTreasureClick(p, event.getTarget())) {
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener(AttackEntityEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer p && Lobbies.onTreasureClick(p, event.getTarget())) {
                event.setCanceled(true);
            }
        });
        // marcadores que quedaron en el mundo por una caída del servidor; un kit nunca llega al suelo
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, event -> {
            if (Displays.isLeftover(event.getEntity()) || LentMounts.isLeftover(event.getEntity()) || event.getEntity() instanceof ItemEntity item && Kits.isKit(item.getItem())) {
                event.setCanceled(true);
            }
            if (event.getEntity() instanceof AbstractArrow arrow && arrow.getOwner() instanceof ServerPlayer p && Lobbies.inLastMan(p.getUUID())) {
                arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
            }
        });
        lastManGuards();
        lentMountGuards();
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> {
            Lobbies.clear(event.getServer());
            LentMounts.clear();
            TreasureGame.forget();
            Tracks.clear();
            TrackGen.clear();
        });
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> HuddleCommands.register(event.getDispatcher(), event.getBuildContext()));
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> {
            BuyIns.refundLeftovers(event.getServer());
            LOGGER.info("Huddle ready on the server");
        });
    }

    private static void lastManGuards() {
        NeoForge.EVENT_BUS.addListener(ItemTossEvent.class, event -> {
            if (Kits.isKit(event.getEntity().getItem())) {
                event.setCanceled(true);
                event.getPlayer().getInventory().add(event.getEntity().getItem().copy());
            }
        });
        NeoForge.EVENT_BUS.addListener(ItemEntityPickupEvent.Pre.class, event -> {
            if (Lobbies.inLastMan(event.getPlayer().getUUID())) {
                event.setCanPickup(TriState.FALSE);
            }
        });
        // con el bloque no (cofres, puertas, mesas); el ítem en la mano sí (comer, el arco, el escudo)
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickBlock.class, event -> {
            if (Lobbies.inLastMan(event.getEntity().getUUID())) {
                event.setUseBlock(TriState.FALSE);
            }
        });
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.EntityInteract.class, event -> {
            if (Lobbies.inLastMan(event.getEntity().getUUID())) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.EntityInteractSpecific.class, event -> {
            if (Lobbies.inLastMan(event.getEntity().getUUID())) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener(BlockEvent.EntityPlaceEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer p && Lobbies.inLastMan(p.getUUID())) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, event -> {
            if (Lobbies.inLastMan(event.getPlayer().getUUID())) {
                event.setCanceled(true);
            }
        });
        // cualquier otro contenedor (de otros mods, por comando)
        NeoForge.EVENT_BUS.addListener(PlayerContainerEvent.Open.class, event -> {
            if (event.getEntity() instanceof ServerPlayer p && Lobbies.inLastMan(p.getUUID()) && event.getContainer() != p.inventoryMenu) {
                p.closeContainer();
            }
        });
    }

    // bote o caballo prestado de una carrera: no se rompe ni se mata (es invulnerable), no suelta nada, no lo monta otro
    // y no se abre el inventario del caballo para sacarle la montura
    private static void lentMountGuards() {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, PlayerInteractEvent.EntityInteract.class, event -> {
            if (event.getEntity() instanceof ServerPlayer p && LentMounts.isLent(event.getTarget()) && !LentMounts.mayUse(p, event.getTarget())) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, PlayerInteractEvent.EntityInteractSpecific.class, event -> {
            if (event.getEntity() instanceof ServerPlayer p && LentMounts.isLent(event.getTarget()) && !LentMounts.mayUse(p, event.getTarget())) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener(PlayerContainerEvent.Open.class, event -> {
            if (event.getEntity() instanceof ServerPlayer p && event.getContainer() instanceof HorseInventoryMenu
                    && p.getVehicle() != null && LentMounts.isLent(p.getVehicle())) {
                p.closeContainer();
            }
        });
        NeoForge.EVENT_BUS.addListener(LivingDropsEvent.class, event -> {
            if (LentMounts.isLent(event.getEntity())) {
                event.setCanceled(true);
            }
        });
    }
}
