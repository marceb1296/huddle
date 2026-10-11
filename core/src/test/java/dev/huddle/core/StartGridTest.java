package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class StartGridTest {
    private static final Pos START = new Pos(100, 64, -50);

    // hacia +x: nadie queda delante de la línea, la primera fila está sobre ella y nadie se pisa
    @Test
    void rowsBehindTheLineWithoutOverlap() {
        List<Pos> s = StartGrid.spots(START, new Pos(140, 64, -50), 2, 10);
        assertEquals(10, s.size());
        for (int i = 0; i < s.size(); i++) {
            Pos p = s.get(i);
            assertTrue(p.x() <= START.x() + 1e-9, "delante de la línea: " + p);
            assertEquals(64, p.y());
            if (i < StartGrid.PER_ROW) {
                assertEquals(START.x(), p.x(), 1e-9);
            }
            for (int j = 0; j < i; j++) {
                assertTrue(p.dist(s.get(j)) >= 2 - 1e-9, "se pisan " + i + " y " + j);
            }
        }
        assertEquals(START.x() - 2, s.get(StartGrid.PER_ROW).x(), 1e-9);
        assertEquals(START.x() - 4, s.get(2 * StartGrid.PER_ROW).x(), 1e-9);
    }

    // los dos primeros a los lados de la largada, a la misma distancia, y después los de afuera
    @Test
    void firstRowGrowsFromTheMiddle() {
        List<Pos> s = StartGrid.spots(START, new Pos(100, 64, 0), 3, 4);
        assertEquals(1.5, Math.abs(s.get(0).x() - START.x()), 1e-9);
        assertEquals(-(s.get(0).x() - START.x()), s.get(1).x() - START.x(), 1e-9);
        assertEquals(4.5, Math.abs(s.get(2).x() - START.x()), 1e-9);
        assertEquals(-(s.get(2).x() - START.x()), s.get(3).x() - START.x(), 1e-9);
        for (Pos p : s) {
            assertEquals(START.z(), p.z(), 1e-9);
        }
    }

    // en diagonal la fila sigue perpendicular: todos a la misma distancia de la meta del primer punto
    @Test
    void diagonalRowIsPerpendicular() {
        Pos ahead = new Pos(START.x() + 30, 64, START.z() + 30);
        List<Pos> s = StartGrid.spots(START, ahead, 2, 2);
        assertEquals(s.get(0).dist(ahead), s.get(1).dist(ahead), 1e-9);
        assertEquals(2, s.get(0).dist(s.get(1)), 1e-9);
    }

    // datos rotos: sin dirección, separación NaN, enorme o cero, cantidades negativas o gigantes
    @Test
    void hostileInputs() {
        List<Pos> same = StartGrid.spots(START, START, 2, 2);
        assertEquals(2, same.get(0).dist(same.get(1)), 1e-9);
        List<Pos> nan = StartGrid.spots(START, new Pos(Double.NaN, 0, 0), Double.NaN, 3);
        assertEquals(3, nan.size());
        assertTrue(nan.stream().allMatch(Pos::finite));
        assertTrue(nan.get(0).dist(nan.get(1)) >= 1 - 1e-9);
        assertEquals(1, StartGrid.spots(START, null, 0, 2).get(0).dist(StartGrid.spots(START, null, 0, 2).get(1)), 1e-9);
        assertEquals(8, StartGrid.spots(START, null, 1e9, 2).get(0).dist(StartGrid.spots(START, null, 1e9, 2).get(1)), 1e-9);
        assertTrue(StartGrid.spots(START, null, 2, -3).isEmpty());
        assertTrue(StartGrid.spots(new Pos(Double.NaN, 0, 0), null, 2, 3).isEmpty());
        assertTrue(StartGrid.spots(null, null, 2, 3).isEmpty());
        assertEquals(StartGrid.MAX, StartGrid.spots(START, null, 2, Integer.MAX_VALUE).size());
    }

    @Test
    void yawFacesTheTarget() {
        assertEquals(0, StartGrid.yaw(START, new Pos(100, 64, 0)), 1e-4);
        assertEquals(-90, StartGrid.yaw(START, new Pos(200, 64, -50)), 1e-4);
        assertEquals(90, StartGrid.yaw(START, new Pos(0, 64, -50)), 1e-4);
        assertEquals(180, Math.abs(StartGrid.yaw(START, new Pos(100, 64, -100))), 1e-4);
        assertEquals(0, StartGrid.yaw(START, START), 1e-4);
        assertEquals(0, StartGrid.yaw(START, new Pos(Double.NaN, 0, 0)), 1e-4);
    }
}
