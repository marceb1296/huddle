package dev.huddle.neoforge;

import dev.huddle.core.Mailbox;
import dev.huddle.core.Payment;
import dev.huddle.core.Pot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// compra de entrada: cada participante paga lo mismo en ítems y el ganador se lleva el pozo.
// Cobrar y depositar ocurren en el mismo tick, igual que sacar del buzón y entregar al inventario:
// el guardado del mundo guarda inventarios y HuddleData juntos, así una caída no duplica ni pierde.
public final class BuyIns {
    public record Price(Item item, int count) {
        public String id() {
            return BuiltInRegistries.ITEM.getKey(item).toString();
        }

        public Component name() {
            return new ItemStack(item).getHoverName();
        }
    }

    // lo que se guarda: el id del ítem y no el ítem, por si el mod que lo agrega ya no está al cargar
    public record Stake(String item, int count) {
    }

    private BuyIns() {
    }

    // solo ítems comunes: una espada gastada o un diamante con nombre no valen lo mismo
    static boolean plain(ItemStack s, Item item) {
        return !s.isEmpty() && s.is(item) && s.getComponentsPatch().isEmpty();
    }

    // inventario principal y mano secundaria; nunca la armadura puesta
    private static List<ItemStack> payable(ServerPlayer p) {
        Inventory inv = p.getInventory();
        List<ItemStack> out = new ArrayList<>(inv.items);
        out.addAll(inv.offhand);
        return out;
    }

    private static int[] available(List<ItemStack> slots, Item item) {
        int[] a = new int[slots.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = plain(slots.get(i), item) ? slots.get(i).getCount() : 0;
        }
        return a;
    }

    public static boolean canPay(ServerPlayer p, Price price) {
        return Payment.plan(available(payable(p), price.item()), price.count()) != null;
    }

    static boolean take(ServerPlayer p, Price price) {
        List<ItemStack> slots = payable(p);
        int[] plan = Payment.plan(available(slots, price.item()), price.count());
        if (plan == null) {
            return false;
        }
        for (int i = 0; i < plan.length; i++) {
            if (plan[i] > 0) {
                slots.get(i).shrink(plan[i]);
            }
        }
        p.getInventory().setChanged();
        return true;
    }

    // al confirmarse la sala: cobra a cada uno y guarda el pozo con el mundo
    static void collect(MinecraftServer server, UUID event, Price price, List<ServerPlayer> payers) {
        Pot<Stake> pot = new Pot<>();
        for (ServerPlayer p : payers) {
            if (take(p, price)) {
                pot.deposit(p.getUUID(), new Stake(price.id(), price.count()));
            } else {
                Huddle.LOGGER.warn("Huddle: {} could not pay the entry it had a moment ago", p.getGameProfile().getName());
            }
        }
        HuddleData data = HuddleData.get(server);
        data.pots().put(event, pot);
        data.changed();
    }

    static void forfeit(MinecraftServer server, UUID event, UUID player) {
        Pot<Stake> pot = HuddleData.get(server).pots().get(event);
        if (pot != null) {
            pot.forfeit(player);
            HuddleData.get(server).changed();
        }
    }

    // winner null o que abandonó: cada entrada vuelve a su dueño. Devuelve true si el ganador cobró.
    static boolean settle(MinecraftServer server, UUID event, UUID winner) {
        HuddleData data = HuddleData.get(server);
        Pot<Stake> pot = data.pots().remove(event);
        if (pot == null) {
            return false;
        }
        List<Pot.Delivery<Stake>> out = winner == null ? List.of() : pot.settleWinner(winner);
        boolean won = !out.isEmpty();
        if (!won) {
            out = pot.refundAll();
        }
        data.mailbox().postAll(out);
        data.changed();
        for (Pot.Delivery<Stake> d : out) {
            ServerPlayer p = server.getPlayerList().getPlayer(d.to());
            if (p != null) {
                deliver(p);
            }
        }
        return won;
    }

    // pozos de eventos que una caída del servidor dejó sin terminar: cada uno recupera lo suyo
    public static void refundLeftovers(MinecraftServer server) {
        HuddleData data = HuddleData.get(server);
        if (data.pots().isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Pot<Stake>> e : data.pots().entrySet()) {
            data.mailbox().postAll(e.getValue().refundAll());
            Huddle.LOGGER.info("Huddle: refunded the entries of event {}, left unfinished by a restart", e.getKey());
        }
        data.pots().clear();
        data.changed();
    }

    // lo que no entra en el inventario cae a sus pies y solo él puede juntarlo
    public static void deliver(ServerPlayer p) {
        HuddleData data = HuddleData.get(p.server);
        Mailbox<Stake> box = data.mailbox();
        if (box.count(p.getUUID()) == 0 || p.isDeadOrDying()) {
            return;
        }
        for (Stake s : box.take(p.getUUID())) {
            Optional<Item> item = Optional.ofNullable(ResourceLocation.tryParse(s.item())).flatMap(BuiltInRegistries.ITEM::getOptional);
            if (item.isEmpty() || item.get() == Items.AIR) {
                // el mod que agrega el ítem ya no está: queda guardado para cuando vuelva
                box.post(p.getUUID(), s);
                Huddle.LOGGER.warn("Huddle: kept {} x{} for {}, that item does not exist now", s.item(), s.count(), p.getGameProfile().getName());
                continue;
            }
            for (int n : Payment.stacks(s.count(), item.get().getDefaultMaxStackSize())) {
                ItemStack stack = new ItemStack(item.get(), n);
                p.getInventory().add(stack);
                if (!stack.isEmpty()) {
                    ItemEntity drop = new ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), stack);
                    drop.setTarget(p.getUUID());
                    drop.setNoPickUpDelay();
                    p.level().addFreshEntity(drop);
                }
            }
        }
        p.getInventory().setChanged();
        data.changed();
    }
}
