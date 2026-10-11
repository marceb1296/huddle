package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.huddle.core.TreasureFinder.Failure;
import dev.huddle.core.TreasureFinder.Preset;
import dev.huddle.core.TreasureFinder.Result;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TreasureFinderTest {
    private static final TreasureFinder.Params RING = new TreasureFinder.Params(40, 80);
    private static final Pos HERE = new Pos(0.5, 64, 0.5);

    private static TerrainGrid land(int radius, int height) {
        TerrainGrid g = new TerrainGrid(0, 0, radius);
        fill(g, -radius, -radius, radius, radius, TerrainGrid.LAND, height, 3);
        return g;
    }

    private static void fill(TerrainGrid g, int x0, int z0, int x1, int z1, byte kind, int height, int headroom) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                g.set(x, z, kind, height, headroom);
            }
        }
    }

    private static Result find(TerrainGrid g, Preset preset, long seed) {
        return TreasureFinder.find(g, preset, HERE, RING, new Random(seed));
    }

    private static double flat(Pos a, Pos b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    private static int cell(TerrainGrid g, Pos p) {
        return g.index((int) Math.floor(p.x()), (int) Math.floor(p.z()));
    }

    @Test
    void classicLandsOnGroundInsideTheRing() {
        TerrainGrid g = land(100, 64);
        for (long seed = 0; seed < 30; seed++) {
            Pos spot = find(g, Preset.CLASSIC, seed).spot().orElseThrow();
            double d = flat(spot, HERE);
            assertTrue(d >= 40 && d <= 80, "a " + d + " del creador");
            assertEquals(64, spot.y());
            assertEquals(spot.x() - 0.5, Math.floor(spot.x()));
        }
    }

    // el lugar se sortea: con semillas distintas no cae siempre en la misma celda
    @Test
    void spotsSpreadOverTheRing() {
        TerrainGrid g = land(100, 64);
        Set<Pos> seen = new HashSet<>();
        Set<TreasureHunt.Heading> sides = new HashSet<>();
        for (long seed = 0; seed < 40; seed++) {
            Pos spot = find(g, Preset.CLASSIC, seed).spot().orElseThrow();
            seen.add(spot);
            sides.add(TreasureHunt.heading(HERE, spot));
        }
        assertTrue(seen.size() >= 30, "solo " + seen.size() + " lugares distintos");
        assertTrue(sides.size() >= 6, "solo " + sides.size() + " rumbos");
    }

    // un escalón de dos bloques no se sube sin construir
    @Test
    void neverAboveATwoBlockStep() {
        TerrainGrid g = land(100, 64);
        for (int x = -100; x <= 100; x++) {
            for (int z = -100; z <= 100; z++) {
                if (Math.hypot(x + 0.5, z + 0.5) >= 30) {
                    g.set(x, z, TerrainGrid.LAND, 66, 3);
                }
            }
        }
        assertEquals(Failure.NO_SPOT, find(g, Preset.CLASSIC, 1).failure());
    }

    // con escalones de uno se llega: la loma sube un bloque cada diez
    @Test
    void oneBlockStepsAreReachable() {
        TerrainGrid g = new TerrainGrid(0, 0, 100);
        for (int x = -100; x <= 100; x++) {
            for (int z = -100; z <= 100; z++) {
                g.set(x, z, TerrainGrid.LAND, 64 + Math.floorDiv(x, 10), 3);
            }
        }
        Pos spot = find(g, Preset.CLASSIC, 3).spot().orElseThrow();
        assertEquals(g.height(cell(g, spot)), spot.y());
    }

    // agua, chunks sin cargar, bases y techos bajos en el anillo; solo un pasillo de tierra hacia el este sirve
    @Test
    void onlyOnOpenGround() {
        TerrainGrid g = land(100, 64);
        fill(g, -100, -100, 100, 100, TerrainGrid.WATER, 63, 3);
        fill(g, -20, -20, 20, 20, TerrainGrid.LAND, 64, 3);
        fill(g, 21, -2, 100, 2, TerrainGrid.LAND, 64, 3);
        fill(g, -100, 30, -40, 100, TerrainGrid.BLOCKED, 0, 0);
        fill(g, 40, 30, 100, 100, TerrainGrid.UNKNOWN, 0, 0);
        // techo bajo pegado a la tierra del creador: se llega, pero no se puede estar de pie
        fill(g, -100, -2, -21, 2, TerrainGrid.LAND, 64, 1);
        for (long seed = 0; seed < 30; seed++) {
            Pos spot = find(g, Preset.CLASSIC, seed).spot().orElseThrow();
            assertTrue(spot.x() > 40 && Math.abs(spot.z()) < 3, "lugar en " + spot);
        }
    }

    // ni en diagonal entre dos bloques que se tocan en una esquina
    @Test
    void noCornerCutting() {
        TerrainGrid g = new TerrainGrid(0, 0, 100);
        fill(g, -100, -100, 100, 100, TerrainGrid.BLOCKED, 64, 0);
        fill(g, -20, -20, 0, 0, TerrainGrid.LAND, 64, 3);
        fill(g, 1, 1, 100, 100, TerrainGrid.LAND, 64, 3);
        Result r = TreasureFinder.find(g, Preset.CLASSIC, new Pos(-9.5, 64, -9.5), RING, new Random(1));
        assertEquals(Failure.NO_SPOT, r.failure(), "cruzó por la esquina: " + r.spot());
    }

    // quien abre el evento nadando en medio de un lago también puede: se sale nadando
    @Test
    void creatorSwimmingStillStarts() {
        TerrainGrid g = land(100, 64);
        fill(g, -15, -15, 15, 15, TerrainGrid.WATER, 63, 3);
        Pos spot = find(g, Preset.CLASSIC, 1).spot().orElseThrow();
        assertEquals(TerrainGrid.LAND, g.kind(cell(g, spot)));
    }

    // sin nada que pisar ni nadar cerca no hay desde dónde empezar
    @Test
    void creatorOverLavaHasNoGround() {
        TerrainGrid g = land(100, 64);
        fill(g, -15, -15, 15, 15, TerrainGrid.BLOCKED, 0, 0);
        assertEquals(Failure.NO_GROUND, find(g, Preset.CLASSIC, 1).failure());
    }

    // el creador puede estar a unos bloques del suelo firme (en un bote en la orilla, sobre un tronco)
    @Test
    void creatorNextToTheShoreStillStarts() {
        TerrainGrid g = land(100, 64);
        fill(g, -4, -4, 4, 4, TerrainGrid.WATER, 63, 3);
        assertTrue(find(g, Preset.CLASSIC, 1).spot().isPresent());
    }

    @Test
    void hostileInput() {
        TerrainGrid g = land(100, 64);
        Random rnd = new Random(1);
        assertEquals(Failure.NO_GROUND, TreasureFinder.find(g, Preset.CLASSIC, new Pos(Double.NaN, 64, 0), RING, rnd).failure());
        assertEquals(Failure.NO_GROUND, TreasureFinder.find(g, Preset.CLASSIC, new Pos(1e300, 64, 0), RING, rnd).failure());
        assertEquals(Failure.NO_GROUND, TreasureFinder.find(null, Preset.CLASSIC, HERE, RING, rnd).failure());
        assertEquals(Failure.NO_GROUND, TreasureFinder.find(g, null, HERE, RING, rnd).failure());
        assertEquals(Failure.NO_SPOT, TreasureFinder.find(g, Preset.CLASSIC, HERE, new TreasureFinder.Params(80, 40), rnd).failure());
        assertEquals(Failure.NO_SPOT, TreasureFinder.find(g, Preset.CLASSIC, HERE, new TreasureFinder.Params(-5, -1), rnd).failure());
        assertTrue(TreasureFinder.find(g, Preset.CLASSIC, HERE, RING, null).spot().isPresent());
    }

    // los huecos bajo tierra solo cuentan si se llega a pie hasta encima: después se cava
    @Test
    void cavesUseTheCaveHeightUnderReachableGround() {
        TerrainGrid g = land(100, 64);
        g.setCave(50, 0, 30);
        g.setCave(51, 0, 31);
        g.setCave(-60, 0, 20);
        fill(g, -100, -100, -40, 100, TerrainGrid.LAND, 70, 3);
        g.setCave(10, 0, 40);
        for (long seed = 0; seed < 20; seed++) {
            Pos spot = find(g, Preset.CAVES, seed).spot().orElseThrow();
            assertTrue(spot.x() == 50.5 && spot.y() == 30 || spot.x() == 51.5 && spot.y() == 31, "lugar en " + spot);
        }
    }

    @Test
    void cavesWithoutCavesFail() {
        assertEquals(Failure.NO_SPOT, find(land(100, 64), Preset.CAVES, 1).failure());
    }

    // un hueco sobre la superficie (dato roto del loader) no es una cueva
    @Test
    void caveAboveTheSurfaceIsIgnored() {
        TerrainGrid g = land(100, 64);
        g.setCave(50, 0, 64);
        g.setCave(55, 0, 90);
        assertEquals(Failure.NO_SPOT, find(g, Preset.CAVES, 1).failure());
    }

    // cima: la celda más alta a la que se llega a pie; una torre que no se puede subir no cuenta
    @Test
    void peakIsTheHighestReachable() {
        TerrainGrid g = new TerrainGrid(0, 0, 100);
        for (int x = -100; x <= 100; x++) {
            for (int z = -100; z <= 100; z++) {
                // loma que sube un bloque cada 4 hacia el este hasta x = 60 y después baja
                int h = 64 + Math.max(0, Math.min(x, 120 - x)) / 4;
                g.set(x, z, TerrainGrid.LAND, h, 3);
            }
        }
        // torre de 30 de alto que no se puede subir
        fill(g, -60, -2, -58, 2, TerrainGrid.LAND, 120, 3);
        Set<Pos> seen = new HashSet<>();
        for (long seed = 0; seed < 20; seed++) {
            Pos spot = find(g, Preset.PEAK, seed).spot().orElseThrow();
            assertEquals(64 + 15, spot.y(), "lugar en " + spot);
            assertTrue(spot.x() >= 59 && spot.x() <= 62, "lugar en " + spot);
            seen.add(spot);
        }
        // empate en la cima: no siempre la misma celda
        assertTrue(seen.size() >= 5, "solo " + seen.size());
    }
    // reparto del último en pie: cuatro jugadores en una zona de 60 por 60 quedan lejos unos de otros
    @Test
    void spreadPutsPlayersApartInsideTheZone() {
        TerrainGrid g = land(60, 64);
        for (long seed = 0; seed < 10; seed++) {
            java.util.List<Pos> spots = TreasureFinder.spread(g, HERE, HERE, 30, 4, new Random(seed));
            assertEquals(4, spots.size());
            double closest = Double.POSITIVE_INFINITY;
            for (int i = 0; i < spots.size(); i++) {
                Pos p = spots.get(i);
                assertTrue(Math.abs(p.x() - HERE.x()) <= 30 && Math.abs(p.z() - HERE.z()) <= 30, "fuera de la zona: " + p);
                assertEquals(64, p.y(), 1e-9);
                for (int j = 0; j < i; j++) {
                    closest = Math.min(closest, flat(p, spots.get(j)));
                }
            }
            assertTrue(closest >= 55, "dos quedaron a " + closest);
        }
    }

    // del otro lado de una pared no se llega a pie: nadie aparece encerrado ahí
    @Test
    void spreadOnlyUsesReachableGround() {
        TerrainGrid g = land(40, 64);
        fill(g, 10, -40, 10, 40, TerrainGrid.BLOCKED, 64, 0);
        for (long seed = 0; seed < 10; seed++) {
            for (Pos p : TreasureFinder.spread(g, HERE, HERE, 30, 6, new Random(seed))) {
                assertTrue(p.x() < 10, "del otro lado de la pared: " + p);
            }
        }
        // un escalón de 2 tampoco se sube
        TerrainGrid h = land(40, 64);
        fill(h, -40, 10, 40, 40, TerrainGrid.LAND, 66, 3);
        for (Pos p : TreasureFinder.spread(h, HERE, HERE, 30, 6, new Random(1))) {
            assertTrue(p.z() < 10, "arriba del escalón: " + p);
        }
    }

    @Test
    void spreadWithFewCellsRepeatsAndBrokenInputGivesNothing() {
        TerrainGrid g = new TerrainGrid(0, 0, 10);
        fill(g, 0, 0, 1, 0, TerrainGrid.LAND, 64, 3);
        java.util.List<Pos> spots = TreasureFinder.spread(g, HERE, HERE, 5, 5, new Random(1));
        assertEquals(5, spots.size());
        assertEquals(2, new HashSet<>(spots).size());
        assertTrue(TreasureFinder.spread(g, HERE, new Pos(Double.NaN, 64, 0), 5, 3, new Random(1)).isEmpty());
        assertTrue(TreasureFinder.spread(g, HERE, HERE, Double.NaN, 3, new Random(1)).isEmpty());
        assertTrue(TreasureFinder.spread(g, HERE, HERE, 0, 3, new Random(1)).isEmpty());
        assertTrue(TreasureFinder.spread(g, HERE, HERE, 5, 0, new Random(1)).isEmpty());
        assertTrue(TreasureFinder.spread(g, new Pos(500.5, 64, 500.5), HERE, 5, 3, new Random(1)).isEmpty());
        assertTrue(TreasureFinder.spread(null, HERE, HERE, 5, 3, new Random(1)).isEmpty());
    }

    // la grilla más grande y muchísimos jugadores: no congela el servidor
    @Test
    void spreadStaysFastWithHugeInput() {
        TerrainGrid g = land(TerrainGrid.MAX_RADIUS, 64);
        long t = System.nanoTime();
        java.util.List<Pos> spots = TreasureFinder.spread(g, HERE, HERE, 1000, 100_000, new Random(1));
        long ms = (System.nanoTime() - t) / 1_000_000;
        assertEquals(100_000, spots.size());
        assertTrue(ms < 500, "tardó " + ms + " ms");
    }

    // quien abre el evento desde su base: los chunks habitados se cruzan a pie, pero el lugar nunca queda en ellos
    @Test
    void busyGroundIsWalkedButNeverChosen() {
        TerrainGrid g = land(100, 64);
        for (int x = -55; x <= 55; x++) {
            for (int z = -55; z <= 55; z++) {
                if (Math.hypot(x + 0.5, z + 0.5) <= 55) {
                    g.markBusy(x, z);
                }
            }
        }
        for (long seed = 0; seed < 30; seed++) {
            Result r = find(g, Preset.CLASSIC, seed);
            Pos spot = r.spot().orElseThrow(() -> new AssertionError("sin lugar: " + r.failure()));
            assertTrue(!g.busy(cell(g, spot)), "en una base: " + spot);
            assertTrue(flat(spot, HERE) <= 80, "fuera del anillo: " + spot);
        }
    }

    // el último en pie abierto desde una base: prefiere fuera, pero si todo el anillo es base lo pone ahí
    @Test
    void lastManFallsBackToABase() {
        TerrainGrid g = land(40, 64);
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                g.markBusy(x, z);
            }
        }
        TreasureFinder.Params near = new TreasureFinder.Params(0, 16, true);
        assertEquals(Failure.NO_SPOT, TreasureFinder.find(g, Preset.CLASSIC, HERE, new TreasureFinder.Params(0, 16), new Random(1)).failure());
        Result r = TreasureFinder.find(g, Preset.CLASSIC, HERE, near, new Random(1));
        Pos spot = r.spot().orElseThrow(() -> new AssertionError("sin lugar: " + r.failure()));
        assertTrue(flat(spot, HERE) <= 16, "fuera del anillo: " + spot);
        // con algo libre dentro del anillo, se elige eso
        TerrainGrid h = land(40, 64);
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                if (x < 10 || x > 12 || Math.abs(z) > 1) {
                    h.markBusy(x, z);
                }
            }
        }
        for (long seed = 0; seed < 20; seed++) {
            Pos free = TreasureFinder.find(h, Preset.CLASSIC, HERE, near, new Random(seed)).spot().orElseThrow();
            assertTrue(!h.busy(cell(h, free)), "en la base habiendo lugar libre: " + free);
        }
    }

    // un río entre el creador y el anillo se cruza nadando; el lugar nunca queda en el agua
    @Test
    void waterIsSwumAcrossButNeverChosen() {
        TerrainGrid g = land(100, 64);
        for (int x = -100; x <= 100; x++) {
            for (int z = -100; z <= 100; z++) {
                double d = Math.hypot(x + 0.5, z + 0.5);
                if ((d >= 20 && d <= 30) || (d >= 40 && x > 0)) {
                    g.set(x, z, TerrainGrid.WATER, 64, 3);
                }
            }
        }
        for (long seed = 0; seed < 30; seed++) {
            Result r = find(g, Preset.CLASSIC, seed);
            Pos spot = r.spot().orElseThrow(() -> new AssertionError("sin lugar: " + r.failure()));
            assertEquals(TerrainGrid.LAND, g.kind(cell(g, spot)), "en el agua: " + spot);
        }
        for (long seed = 0; seed < 10; seed++) {
            for (Pos p : TreasureFinder.spread(g, HERE, new Pos(30.5, 64, 0.5), 20, 6, new Random(seed))) {
                assertEquals(TerrainGrid.LAND, g.kind(cell(g, p)), "jugador repartido en el agua: " + p);
            }
        }
    }

    // un acantilado de agua no se sube: del agua a la orilla se pasa con un escalón de un bloque como mucho
    @Test
    void highBankIsNotClimbedFromTheWater() {
        TerrainGrid g = land(100, 64);
        for (int x = -100; x <= 100; x++) {
            for (int z = -100; z <= 100; z++) {
                double d = Math.hypot(x + 0.5, z + 0.5);
                if (d >= 20 && d <= 30) {
                    g.set(x, z, TerrainGrid.WATER, 64, 3);
                } else if (d > 30) {
                    g.set(x, z, TerrainGrid.LAND, 70, 3);
                }
            }
        }
        assertEquals(Failure.NO_SPOT, find(g, Preset.CLASSIC, 1).failure());
    }

    // no repite la zona de las búsquedas recientes mientras quede otro lugar
    @Test
    void recentSpotsAreAvoided() {
        TerrainGrid g = land(100, 64);
        java.util.List<Pos> recent = java.util.List.of(new Pos(60.5, 64, 0.5), new Pos(-60.5, 64, 0.5));
        for (long seed = 0; seed < 40; seed++) {
            Pos spot = TreasureFinder.find(g, Preset.CLASSIC, HERE, RING, new Random(seed), recent, 40).spot().orElseThrow();
            for (Pos r : recent) {
                assertTrue(flat(spot, r) > 40, "a " + flat(spot, r) + " de una búsqueda reciente");
            }
        }
    }

    // si todo el anillo queda cerca de búsquedas recientes, se usa igual
    @Test
    void avoidingEverythingFallsBack() {
        TerrainGrid g = land(100, 64);
        Result r = TreasureFinder.find(g, Preset.CLASSIC, HERE, RING, new Random(1), java.util.List.of(HERE), 1000);
        assertTrue(r.spot().isPresent(), "sin lugar: " + r.failure());
    }
}
