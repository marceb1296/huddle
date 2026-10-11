package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class KitTest {
    @Test
    void readsItemsWithAndWithoutCount() {
        Kit.Parsed p = Kit.parse(List.of("minecraft:iron_sword", "minecraft:arrow 16", "  minecraft:bread   8 ", "somemod:thing.v2/x 256"));
        assertEquals(List.of(new Kit.Entry("minecraft:iron_sword", 1), new Kit.Entry("minecraft:arrow", 16), new Kit.Entry("minecraft:bread", 8),
                new Kit.Entry("somemod:thing.v2/x", 256)), p.entries());
        assertEquals(List.of(), p.bad());
    }

    // nada de esto puede llegar a crear ítems: cantidades raras, ids mal escritos, texto de más
    @Test
    void hostileLinesAreReportedNotUsed() {
        List<String> lines = Arrays.asList("minecraft:arrow 0", "minecraft:arrow -5", "minecraft:arrow 257", "minecraft:arrow 99999999999",
                "minecraft:arrow 1e3", "Minecraft:Arrow", "arrow", "minecraft:arrow 16 extra", "", "minecraft:", ":arrow", null,
                "minecraft:arrow 16", "minecraft:arrow NaN");
        Kit.Parsed p = Kit.parse(lines);
        assertEquals(List.of(), p.entries());
        assertEquals(lines.size(), p.bad().size());
    }

    @Test
    void tooManyEntriesAreCut() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            lines.add("minecraft:stone 1");
        }
        Kit.Parsed p = Kit.parse(lines);
        assertEquals(Kit.MAX_ENTRIES, p.entries().size());
        assertEquals(100 - Kit.MAX_ENTRIES, p.bad().size());
    }
}
