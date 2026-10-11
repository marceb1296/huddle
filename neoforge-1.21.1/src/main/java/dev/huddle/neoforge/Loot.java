package dev.huddle.neoforge;

import java.util.List;
import java.util.Random;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

// premio de una de las tablas de botín del admin; lo que no entra en el inventario cae a sus pies y solo él lo junta
final class Loot {
    private Loot() {
    }

    // devuelve cuántas pilas dio, o -1 si no había tabla válida
    static int give(ServerPlayer p, List<? extends String> tables, Vec3 origin) {
        if (tables.isEmpty()) {
            return -1;
        }
        String id = tables.get(new Random().nextInt(tables.size()));
        ResourceLocation rl = ResourceLocation.tryParse(id);
        LootTable table = rl == null ? LootTable.EMPTY : p.server.reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, rl));
        if (table == LootTable.EMPTY) {
            Huddle.LOGGER.warn("Huddle: the loot table {} does not exist", id);
            return -1;
        }
        LootParams params = new LootParams.Builder(p.serverLevel()).withParameter(LootContextParams.ORIGIN, origin)
                .withParameter(LootContextParams.THIS_ENTITY, p).withLuck(p.getLuck()).create(LootContextParamSets.CHEST);
        List<ItemStack> items = table.getRandomItems(params);
        for (ItemStack stack : items) {
            p.getInventory().add(stack);
            if (!stack.isEmpty()) {
                ItemEntity dropped = new ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), stack);
                dropped.setTarget(p.getUUID());
                dropped.setNoPickUpDelay();
                p.level().addFreshEntity(dropped);
            }
        }
        p.getInventory().setChanged();
        p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1f);
        Huddle.LOGGER.info("Loot for {}: {} stacks from {}", p.getGameProfile().getName(), items.size(), id);
        return items.size();
    }
}
