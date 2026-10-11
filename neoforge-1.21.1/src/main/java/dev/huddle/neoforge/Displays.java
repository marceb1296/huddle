package dev.huddle.neoforge;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

// entidades de display de los eventos. Las de eventos vivos figuran aquí: cualquier otra con la etiqueta
// quedó de una caída del servidor y se borra al cargarse. Solo se usa desde el hilo del servidor
final class Displays {
    static final String TAG = "huddle_display";
    private static final Set<UUID> LIVE = new HashSet<>();

    private Displays() {
    }

    static boolean isLeftover(Entity e) {
        return e.getTags().contains(TAG) && !LIVE.contains(e.getUUID());
    }

    static UUID spawn(ServerLevel level, CompoundTag tag, double x, double y, double z) {
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(TAG));
        tag.put("Tags", tags);
        Entity e = EntityType.loadEntityRecursive(tag, level, en -> {
            en.moveTo(x, y, z, 0, 0);
            return en;
        });
        if (e == null) {
            throw new IllegalStateException("display entity could not be created");
        }
        LIVE.add(e.getUUID());
        level.addFreshEntity(e);
        return e.getUUID();
    }

    // las que están en chunks sin cargar se borran al cargarse: ya no figuran como vivas
    static void discard(ServerLevel level, UUID id) {
        LIVE.remove(id);
        Entity e = level.getEntity(id);
        if (e != null) {
            e.discard();
        }
    }

    private static ListTag floats(float... v) {
        ListTag l = new ListTag();
        for (float f : v) {
            l.add(FloatTag.valueOf(f));
        }
        return l;
    }

    // desplazamiento y escala por eje, sin rotación
    static CompoundTag transformation(float tx, float ty, float tz, float sx, float sy, float sz) {
        CompoundTag t = new CompoundTag();
        t.put("left_rotation", floats(0, 0, 0, 1));
        t.put("right_rotation", floats(0, 0, 0, 1));
        t.put("translation", floats(tx, ty, tz));
        t.put("scale", floats(sx, sy, sz));
        return t;
    }

    static CompoundTag transformation(float offset, float scale) {
        CompoundTag t = new CompoundTag();
        t.put("left_rotation", floats(0, 0, 0, 1));
        t.put("right_rotation", floats(0, 0, 0, 1));
        t.put("translation", floats(offset, offset, offset));
        t.put("scale", floats(scale, scale, scale));
        return t;
    }
}
