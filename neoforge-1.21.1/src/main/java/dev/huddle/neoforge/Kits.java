package dev.huddle.neoforge;

import dev.huddle.core.Kit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

// kit prestado: el jugador entra con el inventario vacío (o solo con la entrada), recibe ítems marcados y se los
// quitan al salir del evento. El mod nunca guarda ítems del jugador; si el servidor se cae, al volver a entrar se barre
// lo marcado. Las ranuras de otros mods (Curios, mochilas) no se ven
public final class Kits {
    private static final String TAG = "huddle_kit";

    private Kits() {
    }

    public static boolean isKit(ItemStack s) {
        CustomData data = s.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(TAG);
    }

    private static List<List<ItemStack>> compartments(Inventory inv) {
        return List.of(inv.items, inv.armor, inv.offhand);
    }

    // inventario, armadura, mano secundaria, el cursor y la mesa de 2x2 vacíos; con entrada, solo la entrada justa
    static boolean ready(ServerPlayer p, BuyIns.Price price) {
        int entry = 0;
        for (List<ItemStack> slots : compartments(p.getInventory())) {
            for (ItemStack s : slots) {
                if (s.isEmpty()) {
                    continue;
                }
                if (price == null || !BuyIns.plain(s, price.item())) {
                    return false;
                }
                entry += s.getCount();
            }
        }
        Container craft = p.inventoryMenu.getCraftSlots();
        for (int i = 0; i < craft.getContainerSize(); i++) {
            if (!craft.getItem(i).isEmpty()) {
                return false;
            }
        }
        return p.containerMenu.getCarried().isEmpty() && (price == null || entry == price.count());
    }

    // las entradas que no se entienden o los ítems que no existen se avisan en el log y se saltean
    static List<ItemStack> build(List<? extends String> lines) {
        Kit.Parsed parsed = Kit.parse(List.copyOf(lines));
        for (String bad : parsed.bad()) {
            Huddle.LOGGER.warn("Huddle: the kit entry \"{}\" is not valid (use \"namespace:item\" or \"namespace:item count\")", bad);
        }
        List<ItemStack> out = new ArrayList<>();
        for (Kit.Entry e : parsed.entries()) {
            ResourceLocation id = ResourceLocation.tryParse(e.id());
            Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR) {
                Huddle.LOGGER.warn("Huddle: the kit item {} does not exist", e.id());
                continue;
            }
            int left = e.count();
            int max = Math.max(1, new ItemStack(item).getMaxStackSize());
            while (left > 0) {
                int n = Math.min(left, max);
                out.add(new ItemStack(item, n));
                left -= n;
            }
        }
        return out;
    }

    // la armadura y el escudo van puestos; lo que no entra se pierde (nunca cae al suelo)
    static void give(ServerPlayer p, List<ItemStack> kit, UUID event) {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG, event.toString());
        for (ItemStack template : kit) {
            ItemStack s = template.copy();
            s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            EquipmentSlot slot = p.getEquipmentSlotForItem(s);
            if (slot != EquipmentSlot.MAINHAND && p.getItemBySlot(slot).isEmpty()) {
                p.setItemSlot(slot, s);
            } else {
                p.getInventory().add(s);
            }
        }
        p.getInventory().setChanged();
        p.inventoryMenu.broadcastChanges();
    }

    // devuelve cuántas pilas quitó
    static int strip(ServerPlayer p) {
        int n = 0;
        for (List<ItemStack> slots : compartments(p.getInventory())) {
            for (int i = 0; i < slots.size(); i++) {
                if (isKit(slots.get(i))) {
                    slots.set(i, ItemStack.EMPTY);
                    n++;
                }
            }
        }
        Container craft = p.inventoryMenu.getCraftSlots();
        for (int i = 0; i < craft.getContainerSize(); i++) {
            if (isKit(craft.getItem(i))) {
                craft.removeItemNoUpdate(i);
                n++;
            }
        }
        if (isKit(p.containerMenu.getCarried())) {
            p.containerMenu.setCarried(ItemStack.EMPTY);
            n++;
        }
        if (n > 0) {
            p.getInventory().setChanged();
            p.inventoryMenu.broadcastChanges();
            p.containerMenu.broadcastChanges();
        }
        return n;
    }
}
