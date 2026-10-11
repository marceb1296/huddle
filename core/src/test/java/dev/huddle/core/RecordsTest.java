package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordsTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void firstTimeIsTheRecord() {
        Records r = new Records();
        assertEquals(1, r.submit(a, "A", 300));
        assertEquals(1, r.submit(b, "B", 200));
        assertEquals(List.of(b, a), r.top().stream().map(Records.Entry::player).toList());
    }

    // un mismo jugador no llena la tabla con sus cinco vueltas
    @Test
    void onePlacePerPlayerKeepingTheBest() {
        Records r = new Records();
        r.submit(a, "A", 300);
        assertEquals(0, r.submit(a, "A", 400));
        assertEquals(0, r.submit(a, "A", 300));
        assertEquals(1, r.submit(a, "A", 250));
        assertEquals(List.of(new Records.Entry(a, "A", 250)), r.top());
    }

    @Test
    void onlyTopFiveStay() {
        Records r = new Records();
        for (int i = 0; i < Records.TOP; i++) {
            r.submit(UUID.randomUUID(), "p" + i, 100 + i);
        }
        assertEquals(0, r.submit(a, "A", 500));
        assertEquals(Records.TOP, r.submit(b, "B", 103.5));
        assertEquals(Records.TOP, r.top().size());
        assertEquals(103.5, r.top().get(Records.TOP - 1).ticks());
    }

    @Test
    void brokenTimesAreRejected() {
        Records r = new Records();
        assertEquals(0, r.submit(a, "A", Double.NaN));
        assertEquals(0, r.submit(a, "A", -1));
        assertEquals(0, r.submit(a, "A", Double.POSITIVE_INFINITY));
        assertEquals(0, r.submit(null, "A", 10));
        assertEquals(List.of(), r.top());
    }

    // datos guardados rotos o editados a mano: se corrigen al cargar
    @Test
    void restoreToleratesCorruptData() {
        List<Records.Entry> saved = Arrays.asList(new Records.Entry(a, "A", 300), null, new Records.Entry(a, "A", 200),
                new Records.Entry(b, null, Double.NaN), new Records.Entry(null, "x", 5), new Records.Entry(b, null, 250));
        Records r = Records.restore(saved);
        assertEquals(List.of(new Records.Entry(a, "A", 200), new Records.Entry(b, "?", 250)), r.top());
        assertEquals(List.of(), Records.restore(null).top());
    }
}
