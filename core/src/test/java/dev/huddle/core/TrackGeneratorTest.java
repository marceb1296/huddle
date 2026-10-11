package dev.huddle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.huddle.core.TrackGenerator.Failure;
import dev.huddle.core.TrackGenerator.Preset;
import dev.huddle.core.TrackGenerator.Result;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrackGeneratorTest {
    private static final TrackGenerator.Params PARAMS = new TrackGenerator.Params(16, 3, 60, 200, 20);
    private static final Pos HERE = new Pos(0.5, 64, 0.5);

    private static TerrainGrid land(int radius, int height, int headroom) {
        TerrainGrid g = new TerrainGrid(0, 0, radius);
        fill(g, -radius, -radius, radius, radius, TerrainGrid.LAND, height, headroom);
        return g;
    }

    private static void fill(TerrainGrid g, int x0, int z0, int x1, int z1, byte kind, int height, int headroom) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                g.set(x, z, kind, height, headroom);
            }
        }
    }

    private static Result gen(TerrainGrid g, Preset preset, Route.Mode mode, Pos at, int laps) {
        return TrackGenerator.generate(g, preset, mode, at, PARAMS, "gen", UUID.randomUUID(), "Steve", laps);
    }

    private static int cell(TerrainGrid g, Pos p) {
        return g.index((int) Math.floor(p.x()), (int) Math.floor(p.z()));
    }

    // cada punto sobre una celda donde el medio puede estar, también la largada
    private static void allOn(TerrainGrid g, Route r, byte kind) {
        assertEquals(kind, g.kind(cell(g, r.start())));
        for (Route.Checkpoint cp : r.checkpoints()) {
            assertEquals(kind, g.kind(cell(g, cp.center())), "punto en " + cp.center());
        }
    }

    // ningún par de puntos seguidos se pisa: Route.of los descartaría y la pista quedaría con menos puntos
    private static void spaced(Route r) {
        List<Route.Checkpoint> cps = r.checkpoints();
        for (int k = 1; k < cps.size(); k++) {
            assertTrue(!cps.get(k - 1).overlaps(cps.get(k)));
        }
    }

    @Test
    void sprintOnOpenGroundGoesFarAndStraight() {
        TerrainGrid g = land(120, 64, 3);
        Result r = gen(g, Preset.SPRINT, Route.Mode.FOOT, HERE, 5);
        Route route = r.route().orElseThrow();
        assertEquals(1, route.laps());
        assertEquals(Route.Mode.FOOT, route.mode());
        assertTrue(r.length() >= 150 && r.length() <= 200, "largo " + r.length());
        Pos end = route.checkpoints().get(route.checkpoints().size() - 1).center();
        assertTrue(end.dist(route.start()) >= 0.8 * r.length());
        assertTrue(route.start().dist(HERE) < 2);
        spaced(route);
    }

    // un escalón de 2 bloques no se sube a pie ni a caballo: la pista no puede cruzarlo
    @Test
    void sprintDoesNotClimbTwoBlockSteps() {
        TerrainGrid g = land(120, 64, 3);
        for (int x = -120; x <= 120; x++) {
            for (int z = -120; z <= 120; z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) >= 30) {
                    g.set(x, z, TerrainGrid.LAND, 66, 3);
                }
            }
        }
        Result r = gen(g, Preset.SPRINT, Route.Mode.FOOT, HERE, 1);
        assertTrue(r.route().isEmpty());
        assertEquals(Failure.TOO_SHORT, r.failure());
        assertTrue(r.best() <= 45, "mejor " + r.best());
    }

    // techo a un bloque del suelo: ni agachado se pasa (el jugador mide 1,8 y agachado 1,5)
    @Test
    void oneBlockOfHeadroomIsNotEnough() {
        assertEquals(Failure.NO_GROUND, gen(land(60, 64, 1), Preset.SPRINT, Route.Mode.FOOT, HERE, 1).failure());
    }

    // dos zonas que solo se tocan en una esquina: pasar en diagonal es atravesar dos bloques
    @Test
    void noCornerCutting() {
        TerrainGrid g = new TerrainGrid(0, 0, 120);
        fill(g, -120, -120, 120, 120, TerrainGrid.BLOCKED, 64, 0);
        fill(g, -40, -40, 0, 0, TerrainGrid.LAND, 64, 3);
        fill(g, 1, 1, 100, 100, TerrainGrid.LAND, 64, 3);
        Result r = gen(g, Preset.SPRINT, Route.Mode.FOOT, new Pos(-19.5, 64, -19.5), 1);
        assertTrue(r.route().isEmpty(), "cruzó por la esquina");
        assertEquals(Failure.TOO_SHORT, r.failure());
    }

    // "casi recto": el punto más lejano en línea recta queda detrás de un desvío largo y no se elige
    @Test
    void sprintAvoidsLongDetours() {
        TerrainGrid g = new TerrainGrid(0, 0, 160);
        fill(g, -160, -160, 160, 160, TerrainGrid.BLOCKED, 64, 0);
        fill(g, -2, -2, 62, 2, TerrainGrid.LAND, 64, 3);
        fill(g, 58, 2, 62, 72, TerrainGrid.LAND, 64, 3);
        fill(g, 58, 68, 120, 72, TerrainGrid.LAND, 64, 3);
        Result r = gen(g, Preset.SPRINT, Route.Mode.FOOT, HERE, 1);
        Route route = r.route().orElseThrow();
        Pos end = route.checkpoints().get(route.checkpoints().size() - 1).center();
        assertTrue(end.dist(route.start()) >= 0.8 * r.length(), "desvío de " + r.length() + " para " + end.dist(route.start()));
        assertTrue(end.x() < 70 && end.z() < 30, "meta en " + end);
    }

    @Test
    void sprintRespectsMaxLength() {
        TrackGenerator.Params shortMax = new TrackGenerator.Params(16, 3, 60, 100, 20);
        Result r = TrackGenerator.generate(land(120, 64, 3), Preset.SPRINT, Route.Mode.FOOT, HERE, shortMax, "s", null, "x", 1);
        assertTrue(r.length() <= 100 && r.length() >= 90, "largo " + r.length());
    }

    @Test
    void circuitTooShortFails() {
        TrackGenerator.Params longMin = new TrackGenerator.Params(16, 3, 300, 400, 20);
        Result r = TrackGenerator.generate(land(60, 64, 3), Preset.CIRCUIT, Route.Mode.FOOT, HERE, longMin, "c", null, "x", 3);
        assertEquals(Failure.TOO_SHORT, r.failure());
        assertTrue(r.best() > 100 && r.best() < 300, "mejor " + r.best());
    }

    // muchos charcos junto al creador no pueden gastar los intentos: el lago y el río de más allá se encuentran
    @Test
    void manyPondsDoNotHideTheLake() {
        TerrainGrid g = land(120, 64, 3);
        // 27 charcos, todos más cerca que el agua grande
        for (int x = -32; x <= 32; x += 8) {
            for (int z = -14; z >= -30; z -= 8) {
                fill(g, x, z, x + 4, z + 4, TerrainGrid.WATER, 63, 3);
            }
        }
        fill(g, -100, 60, 100, 110, TerrainGrid.WATER, 63, 3);
        assertTrue(gen(g, Preset.LAKE, Route.Mode.BOAT, HERE, 3).route().isPresent());
        assertTrue(gen(g, Preset.RIVER, Route.Mode.BOAT, HERE, 1).route().isPresent());
    }

    // dos anillos de agua, uno dentro del otro, separados por tierra. El interior queda más cerca del radio
    // buscado, pero el circuito del lago exterior no puede usar sus celdas: no se llega en bote
    @Test
    void lakeAnchorsStayInTheirOwnWater() {
        TerrainGrid g = land(80, 64, 3);
        for (int x = -50; x <= 50; x++) {
            for (int z = -50; z <= 50; z++) {
                double r = Math.hypot(x + 0.5, z + 0.5);
                if ((r >= 30 && r <= 45) || (r >= 20 && r <= 27)) {
                    g.set(x, z, TerrainGrid.WATER, 63, 3);
                }
            }
        }
        Route route = gen(g, Preset.LAKE, Route.Mode.BOAT, new Pos(60, 64, 0), 2).route().orElseThrow();
        for (Route.Checkpoint cp : route.checkpoints()) {
            assertTrue(Math.hypot(cp.center().x(), cp.center().z()) >= 29, "punto en el anillo interior: " + cp.center());
        }
        allOn(g, route, TerrainGrid.WATER);
    }

    // circuito chico: el primer punto pisa la meta (que está en la largada) y se quita; la meta queda
    @Test
    void placeDropsFirstPointTouchingTheFinishOnLoops() {
        List<Pos> circle = new java.util.ArrayList<>();
        for (int k = 0; k <= 40; k++) {
            double a = 2 * Math.PI * k / 40;
            circle.add(new Pos(6 * Math.cos(a), 64, 6 * Math.sin(a)));
        }
        List<Route.Checkpoint> loop = TrackGenerator.place(circle, 10, 5, true);
        Route.Checkpoint finish = loop.get(loop.size() - 1);
        assertEquals(circle.get(40), finish.center());
        assertTrue(!loop.get(0).overlaps(finish), "el primero pisa la meta");
        assertTrue(loop.size() >= 2, "quedaron " + loop.size());
        // ida sin vueltas: nada que quitar al principio
        List<Route.Checkpoint> line = TrackGenerator.place(circle, 10, 5, false);
        assertTrue(line.get(0).overlaps(line.get(line.size() - 1)));
    }

    @Test
    void oneBlockStepsAreFine() {
        TerrainGrid g = new TerrainGrid(0, 0, 120);
        for (int x = -120; x <= 120; x++) {
            for (int z = -120; z <= 120; z++) {
                g.set(x, z, TerrainGrid.LAND, 64 + Math.floorDiv(x, 10), 3);
            }
        }
        assertTrue(gen(g, Preset.SPRINT, Route.Mode.HORSE, HERE, 1).route().isPresent());
    }

    // chunks sin cargar: el generador nunca los pisa ni los cruza
    @Test
    void neverCrossesUnknownColumns() {
        TerrainGrid g = land(120, 64, 3);
        fill(g, -120, -120, 120, 120, TerrainGrid.UNKNOWN, 0, 0);
        fill(g, -40, -40, 40, 40, TerrainGrid.LAND, 64, 3);
        fill(g, 60, -120, 120, 120, TerrainGrid.LAND, 64, 3);
        Result r = gen(g, Preset.SPRINT, Route.Mode.FOOT, HERE, 1);
        assertTrue(r.route().isEmpty());
        assertTrue(r.best() <= 60, "mejor " + r.best());
    }

    @Test
    void blockedColumnsAreAvoided() {
        TerrainGrid g = land(120, 64, 3);
        fill(g, -120, 5, 120, 8, TerrainGrid.BLOCKED, 64, 3);
        fill(g, -120, -8, 120, -5, TerrainGrid.BLOCKED, 64, 3);
        fill(g, 5, -120, 8, 120, TerrainGrid.BLOCKED, 64, 3);
        fill(g, -8, -120, -5, 120, TerrainGrid.BLOCKED, 64, 3);
        Result r = gen(g, Preset.SPRINT, Route.Mode.FOOT, HERE, 1);
        assertTrue(r.route().isEmpty());
    }

    // techo de 2 bloques: a pie pasa, a caballo no
    @Test
    void horsesNeedThreeBlocksOfHeadroom() {
        TerrainGrid g = land(120, 64, 2);
        assertTrue(gen(g, Preset.SPRINT, Route.Mode.FOOT, HERE, 1).route().isPresent());
        Result horse = gen(g, Preset.SPRINT, Route.Mode.HORSE, HERE, 1);
        assertTrue(horse.route().isEmpty());
        assertEquals(Failure.NO_GROUND, horse.failure());
    }

    // un pasillo de un bloque sirve a pie pero un caballo se traba
    @Test
    void horsesNeedWidth() {
        TerrainGrid g = new TerrainGrid(0, 0, 120);
        fill(g, -120, -120, 120, 120, TerrainGrid.BLOCKED, 64, 0);
        fill(g, -2, -2, 2, 2, TerrainGrid.LAND, 64, 3);
        fill(g, 0, 0, 120, 0, TerrainGrid.LAND, 64, 3);
        assertTrue(gen(g, Preset.SPRINT, Route.Mode.FOOT, HERE, 1).route().isPresent());
        assertTrue(gen(g, Preset.SPRINT, Route.Mode.HORSE, HERE, 1).route().isEmpty());
    }

    @Test
    void creatorMustStandOnUsableGround() {
        TerrainGrid g = land(60, 64, 3);
        fill(g, -60, -60, 60, 60, TerrainGrid.WATER, 63, 3);
        Result r = gen(g, Preset.SPRINT, Route.Mode.FOOT, HERE, 1);
        assertEquals(Failure.NO_GROUND, r.failure());
        assertEquals(Failure.NO_GROUND, gen(g, Preset.CIRCUIT, Route.Mode.FOOT, HERE, 3).failure());
    }

    @Test
    void circuitLoopsAroundTheCreator() {
        TerrainGrid g = land(60, 64, 3);
        Result r = gen(g, Preset.CIRCUIT, Route.Mode.FOOT, HERE, 3);
        Route route = r.route().orElseThrow();
        assertEquals(3, route.laps());
        assertTrue(r.length() >= 100 && r.length() <= 160, "largo " + r.length());
        Pos finish = route.checkpoints().get(route.checkpoints().size() - 1).center();
        assertTrue(finish.dist(new Pos(route.start().x(), route.start().y() + 0.5, route.start().z())) < 0.01);
        for (Route.Checkpoint cp : route.checkpoints()) {
            double d = Math.hypot(cp.center().x() - HERE.x(), cp.center().z() - HERE.z());
            assertTrue(d >= 10 && d <= 30, "punto a " + d);
        }
        spaced(route);
        // la meta no pisa al primer punto: con vueltas, Route.of la quitaría
        assertTrue(!route.checkpoints().get(0).overlaps(route.checkpoints().get(route.checkpoints().size() - 1)));
    }

    @Test
    void circuitInACorridorHasNoRoom() {
        TerrainGrid g = new TerrainGrid(0, 0, 60);
        fill(g, -60, -60, 60, 60, TerrainGrid.BLOCKED, 64, 0);
        fill(g, -60, -1, 60, 1, TerrainGrid.LAND, 64, 3);
        assertEquals(Failure.NO_ROOM, gen(g, Preset.CIRCUIT, Route.Mode.FOOT, HERE, 3).failure());
    }

    private static TerrainGrid river(int width) {
        TerrainGrid g = land(120, 64, 3);
        fill(g, -100, 10, 100, 9 + width, TerrainGrid.WATER, 63, 3);
        return g;
    }

    @Test
    void riverRunsEndToEndStartingNearTheCreator() {
        TerrainGrid g = river(3);
        Pos creator = new Pos(90, 64, 0);
        TrackGenerator.Params capped = new TrackGenerator.Params(16, 3, 60, 120, 20);
        Result r = TrackGenerator.generate(g, Preset.RIVER, Route.Mode.FOOT, creator, capped, "rio", null, "x", 4);
        Route route = r.route().orElseThrow();
        assertEquals(Route.Mode.BOAT, route.mode());
        assertEquals(1, route.laps());
        assertTrue(route.start().x() > 80, "largada en " + route.start());
        // el tope de largo corta la punta lejana
        assertTrue(r.length() <= 120 && r.length() >= 118, "largo " + r.length());
        allOn(g, route, TerrainGrid.WATER);
        spaced(route);
    }

    // de 2 de ancho un bote roza las orillas y se traba
    @Test
    void riverTwoBlocksWideIsNotNavigable() {
        Result r = gen(river(2), Preset.RIVER, Route.Mode.BOAT, HERE, 1);
        assertTrue(r.route().isEmpty());
        assertEquals(Failure.NO_WATER, r.failure());
    }

    // un escalón de agua de un bloque también es una cascada para el bote
    @Test
    void oneBlockWaterfallSplitsToo() {
        TerrainGrid g = land(120, 64, 3);
        fill(g, -40, 10, -1, 14, TerrainGrid.WATER, 63, 3);
        fill(g, 0, 10, 40, 14, TerrainGrid.WATER, 62, 3);
        assertEquals(Failure.TOO_SHORT, gen(g, Preset.RIVER, Route.Mode.BOAT, HERE, 1).failure());
    }

    // una cascada parte el río: ninguno de los dos tramos alcanza el largo mínimo
    @Test
    void waterfallSplitsTheRiver() {
        TerrainGrid g = land(120, 64, 3);
        fill(g, -40, 10, -1, 14, TerrainGrid.WATER, 63, 3);
        fill(g, 0, 10, 40, 14, TerrainGrid.WATER, 60, 3);
        Result r = gen(g, Preset.RIVER, Route.Mode.BOAT, HERE, 1);
        assertTrue(r.route().isEmpty());
        assertEquals(Failure.TOO_SHORT, r.failure());
        assertTrue(r.best() > 30 && r.best() < 45, "mejor " + r.best());
        // dónde está lo mejor que se encontró: uno de los dos tramos, en su parte más cercana al creador
        assertTrue(r.near() != null && Math.abs(r.near().z() - 11.5) < 0.01 && Math.abs(r.near().x()) < 3, "cerca de " + r.near());
    }

    @Test
    void lakeLoopsInsideTheWater() {
        TerrainGrid g = land(120, 64, 3);
        for (int x = -60; x <= 60; x++) {
            for (int z = -60; z <= 60; z++) {
                if (x * x + z * z <= 50 * 50) {
                    g.set(x + 40, z, TerrainGrid.WATER, 63, 3);
                }
            }
        }
        Result r = gen(g, Preset.LAKE, Route.Mode.BOAT, HERE, 3);
        Route route = r.route().orElseThrow();
        assertEquals(3, route.laps());
        allOn(g, route, TerrainGrid.WATER);
        spaced(route);
        // la largada es la parte del lago más cercana al creador
        assertTrue(route.start().x() < 40, "largada en " + route.start());
        // lejos de la orilla: pegado a ella se roza la tierra en cada curva
        for (Route.Checkpoint cp : route.checkpoints()) {
            assertTrue(Math.hypot(cp.center().x() - 40.5, cp.center().z() - 0.5) <= 35, "punto junto a la orilla: " + cp.center());
        }
    }

    // un lago con forma de C: el camino entre dos puntos rodea la tierra del medio, nunca la cruza
    @Test
    void lakeWithLandInTheMiddleStaysOnWater() {
        TerrainGrid g = land(120, 64, 3);
        fill(g, -50, -50, 50, 50, TerrainGrid.WATER, 63, 3);
        fill(g, -30, -30, 50, 30, TerrainGrid.LAND, 64, 3);
        Result r = gen(g, Preset.LAKE, Route.Mode.BOAT, new Pos(-70, 64, 0), 2);
        Route route = r.route().orElseThrow();
        allOn(g, route, TerrainGrid.WATER);
    }

    // lagos chicos con radio de bote: el primer punto puede quedar pegado a la meta. Route.of la borraría
    // y bajaría a una sola vuelta sin avisar. Cada estanque da una pista con las vueltas pedidas y la meta
    // en la largada, o falla diciendo que no hay lugar
    @Test
    void smallLakesNeverLoseLapsOrTheFinish() {
        TrackGenerator.Params boat = new TrackGenerator.Params(10, 5, 20, 200, 60);
        int built = 0;
        for (int side = 12; side <= 40; side++) {
            TerrainGrid g = land(60, 64, 3);
            fill(g, 4, 4, 3 + side, 3 + side, TerrainGrid.WATER, 63, 3);
            Result r = TrackGenerator.generate(g, Preset.LAKE, Route.Mode.BOAT, HERE, boat, "lago", null, "x", 3);
            if (r.route().isEmpty()) {
                assertTrue(r.failure() == Failure.NO_ROOM || r.failure() == Failure.TOO_SHORT, side + ": " + r.failure());
                continue;
            }
            built++;
            Route route = r.route().get();
            List<Route.Checkpoint> cps = route.checkpoints();
            assertEquals(3, route.laps(), "lado " + side);
            Pos finish = cps.get(cps.size() - 1).center();
            assertEquals(route.start().x(), finish.x(), 1e-9, "lado " + side + ": la meta no está en la largada");
            assertEquals(route.start().z(), finish.z(), 1e-9, "lado " + side + ": la meta no está en la largada");
            assertTrue(cps.size() >= 2 && !cps.get(0).overlaps(cps.get(cps.size() - 1)), "lado " + side);
            allOn(g, route, TerrainGrid.WATER);
        }
        assertTrue(built >= 20, "solo " + built + " estanques dieron pista");
    }

    // un charco junto al creador no sirve; se usa el lago grande aunque esté más lejos
    @Test
    void smallPondNearbyIsSkippedForTheLake() {
        TerrainGrid g = land(120, 64, 3);
        fill(g, -3, 5, 3, 11, TerrainGrid.WATER, 63, 3);
        fill(g, 40, -40, 110, 40, TerrainGrid.WATER, 63, 3);
        Route route = gen(g, Preset.LAKE, Route.Mode.BOAT, HERE, 1).route().orElseThrow();
        assertTrue(route.start().x() >= 40);
        Route river = gen(g, Preset.RIVER, Route.Mode.BOAT, HERE, 1).route().orElseThrow();
        assertTrue(river.start().x() >= 40);
    }

    @Test
    void noWaterAtAll() {
        Result r = gen(land(60, 64, 3), Preset.LAKE, Route.Mode.BOAT, HERE, 1);
        assertEquals(Failure.NO_WATER, r.failure());
    }

    @Test
    void brokenInputsFailCleanly() {
        TerrainGrid g = land(60, 64, 3);
        Pos nan = new Pos(Double.NaN, 64, 0);
        assertEquals(Failure.NO_GROUND, gen(g, Preset.SPRINT, Route.Mode.FOOT, nan, 1).failure());
        assertEquals(Failure.NO_GROUND, TrackGenerator.generate(g, Preset.SPRINT, Route.Mode.FOOT, HERE,
                new TrackGenerator.Params(Double.NaN, 3, 10, 100, 20), "gen", null, "x", 1).failure());
        assertEquals(Failure.NO_GROUND, TrackGenerator.generate(null, Preset.SPRINT, Route.Mode.FOOT, HERE, PARAMS, "gen", null, "x", 1).failure());
        // creador fuera de la copia del terreno: el suelo más cercano tiene que estar a su lado
        assertEquals(Failure.NO_GROUND, gen(g, Preset.SPRINT, Route.Mode.FOOT, new Pos(100, 64, 100), 1).failure());
        assertEquals(Failure.NO_GROUND, gen(g, Preset.CIRCUIT, Route.Mode.FOOT, new Pos(100, 64, 100), 1).failure());
        TerrainGrid lake = land(60, 64, 3);
        fill(lake, -40, -40, 40, 40, TerrainGrid.WATER, 63, 3);
        assertEquals(Failure.NO_GROUND, gen(lake, Preset.LAKE, Route.Mode.BOAT, nan, 1).failure());
        // vueltas fuera de rango se corrigen en Route.of
        assertEquals(Route.MAX_LAPS, gen(g, Preset.CIRCUIT, Route.Mode.FOOT, HERE, 999).route().orElseThrow().laps());
        assertEquals(1, gen(g, Preset.CIRCUIT, Route.Mode.FOOT, HERE, -5).route().orElseThrow().laps());
        // élitros o bote en un preset de tierra: se usa a pie
        assertEquals(Route.Mode.FOOT, gen(g, Preset.SPRINT, Route.Mode.ELYTRA, HERE, 1).route().orElseThrow().mode());
        assertNull(gen(g, Preset.SPRINT, Route.Mode.FOOT, HERE, 1).failure());
    }

    @Test
    void radiusIsCapped() {
        TerrainGrid g = new TerrainGrid(0, 0, Integer.MAX_VALUE);
        assertEquals(2 * TerrainGrid.MAX_RADIUS + 1, g.size());
        g.set(Integer.MAX_VALUE, 0, TerrainGrid.LAND, 64, 3);
        g.set(0, 0, (byte) 9, 64, 3);
        assertEquals(TerrainGrid.BLOCKED, g.kind(g.index(0, 0)));
    }

    // el peor caso: todo agua en el radio máximo. Corre fuera del hilo del servidor, pero no puede tardar minutos
    @Test
    void largestGridStaysFast() {
        TerrainGrid g = new TerrainGrid(0, 0, TerrainGrid.MAX_RADIUS);
        int r = TerrainGrid.MAX_RADIUS;
        fill(g, -r, -r, r, r, TerrainGrid.WATER, 63, 3);
        long t0 = System.nanoTime();
        TrackGenerator.Params big = new TrackGenerator.Params(32, 5, 100, 600, 60);
        assertTrue(TrackGenerator.generate(g, Preset.LAKE, Route.Mode.BOAT, HERE, big, "a", null, "x", 1).route().isPresent());
        assertTrue(TrackGenerator.generate(g, Preset.RIVER, Route.Mode.BOAT, HERE, big, "a", null, "x", 1).route().isPresent());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 5000, ms + " ms");
    }

    private static void busy(TerrainGrid g, int x0, int z0, int x1, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                g.markBusy(x, z);
            }
        }
    }

    // ningún punto dentro de una base; en un circuito la meta es la largada y puede quedar ahí
    private static void noPointInABase(TerrainGrid g, Route r, boolean loop) {
        List<Route.Checkpoint> cps = r.checkpoints();
        for (int k = 0; k < cps.size() - (loop ? 1 : 0); k++) {
            assertTrue(!g.busy(cell(g, cps.get(k).center())), "punto " + k + " en una base: " + cps.get(k).center());
        }
    }

    // quien está en su base puede generar la pista: el camino sale de la base, pero ningún punto queda en ella
    @Test
    void sprintFromABase() {
        TerrainGrid g = land(120, 64, 3);
        busy(g, -25, -25, 25, 25);
        Route route = gen(g, Preset.SPRINT, Route.Mode.FOOT, HERE, 1).route().orElseThrow();
        assertTrue(route.start().dist(HERE) < 2, "largada en " + route.start());
        noPointInABase(g, route, false);
        spaced(route);
    }

    @Test
    void circuitFromABase() {
        TerrainGrid g = land(60, 64, 3);
        busy(g, -12, -12, 12, 12);
        Route route = gen(g, Preset.CIRCUIT, Route.Mode.FOOT, HERE, 3).route().orElseThrow();
        assertEquals(3, route.laps());
        noPointInABase(g, route, true);
        spaced(route);
    }

    // una base al final del río: la meta queda antes de entrar en ella
    @Test
    void riverEndingInABaseFinishesBeforeIt() {
        TerrainGrid g = river(5);
        busy(g, -100, 0, -60, 30);
        Pos creator = new Pos(90, 64, 0);
        Result r = TrackGenerator.generate(g, Preset.RIVER, Route.Mode.FOOT, creator, PARAMS, "rio", null, "x", 1);
        Route route = r.route().orElseThrow();
        noPointInABase(g, route, false);
        Pos finish = route.checkpoints().get(route.checkpoints().size() - 1).center();
        assertTrue(finish.x() > -60 && finish.x() < -40, "meta en " + finish);
    }

    // si sacando la parte dentro de la base no queda el largo mínimo, no se genera
    @Test
    void riverMostlyInABaseIsTooShort() {
        TerrainGrid g = river(5);
        busy(g, -100, 0, 60, 30);
        Result r = TrackGenerator.generate(g, Preset.RIVER, Route.Mode.FOOT, new Pos(90, 64, 0), PARAMS, "rio", null, "x", 1);
        assertEquals(Failure.TOO_SHORT, r.failure(), "generó " + r.route());
    }
}
