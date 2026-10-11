package dev.huddle.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

// elige dónde poner el tesoro o un suministro sobre una copia del terreno: una celda a la que se llega a pie o nadando
// desde el creador (escalones de un bloque, sin cortar esquinas) y a una distancia dentro del anillo pedido. PEAK: la más alta
public final class TreasureFinder {
    public enum Preset { CLASSIC, CAVES, PEAK }

    public enum Failure { NO_GROUND, NO_SPOT }

    // busyIfNeeded: si no hay lugar fuera de las bases, se usa uno dentro
    public record Params(int minDistance, int maxDistance, boolean busyIfNeeded) {
        public Params(int minDistance, int maxDistance) {
            this(minDistance, maxDistance, false);
        }
    }

    public record Result(Optional<Pos> spot, Failure failure) {
        static Result fail(Failure f) {
            return new Result(Optional.empty(), f);
        }
    }

    private static final double START_SEARCH = 8;
    private static final long MAX_SPREAD_WORK = 2_000_000;
    private static final int MAX_SPREAD_PLAYERS = 1000;
    // pies en el hueco, dos de aire y al menos un bloque de techo bajo los pies de la superficie
    private static final int CAVE_ROOF = 3;
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    private TreasureFinder() {
    }

    public static Result find(TerrainGrid g, Preset preset, Pos from, Params p, Random rnd) {
        return find(g, preset, from, p, rnd, List.of(), 0);
    }

    public static Result find(TerrainGrid g, Preset preset, Pos from, Params p, Random rnd, List<Pos> avoid, double avoidRadius) {
        if (g == null || preset == null || from == null || !from.finite()) {
            return Result.fail(Failure.NO_GROUND);
        }
        int n = g.cells();
        boolean[] ok = new boolean[n];
        for (int i = 0; i < n; i++) {
            ok[i] = walkable(g, i);
        }
        int src = -1;
        double bestD = START_SEARCH;
        for (int i = 0; i < n; i++) {
            double d = flat(g, i, from);
            if (ok[i] && d <= bestD) {
                bestD = d;
                src = i;
            }
        }
        if (src < 0) {
            return Result.fail(Failure.NO_GROUND);
        }
        if (p == null) {
            return Result.fail(Failure.NO_SPOT);
        }
        boolean[] reached = reach(g, ok, src);
        List<Integer> candidates = new ArrayList<>();
        List<Integer> inBases = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // se cruzan nadando y por las bases, pero el lugar nunca queda en el agua ni (salvo busyIfNeeded) dentro de una base
            if (!reached[i] || g.kind(i) != TerrainGrid.LAND) {
                continue;
            }
            double d = flat(g, i, from);
            if (d < p.minDistance() || d > p.maxDistance()) {
                continue;
            }
            if (preset == Preset.CAVES && (g.cave(i) == TerrainGrid.NO_CAVE || g.cave(i) > g.height(i) - CAVE_ROOF)) {
                continue;
            }
            (g.busy(i) ? inBases : candidates).add(i);
        }
        if (candidates.isEmpty() && p.busyIfNeeded()) {
            candidates = inBases;
        }
        if (candidates.isEmpty()) {
            return Result.fail(Failure.NO_SPOT);
        }
        // lejos de las búsquedas recientes mientras quede algún lugar
        if (avoid != null && !avoid.isEmpty()) {
            List<Integer> fresh = new ArrayList<>(candidates);
            fresh.removeIf(c -> avoid.stream().anyMatch(a -> a != null && flat(g, c, a) <= avoidRadius));
            if (!fresh.isEmpty()) {
                candidates = fresh;
            }
        }
        if (preset == Preset.PEAK) {
            int top = Integer.MIN_VALUE;
            for (int c : candidates) {
                top = Math.max(top, g.height(c));
            }
            int max = top;
            candidates.removeIf(c -> g.height(c) != max);
        }
        int i = candidates.get((rnd == null ? new Random() : rnd).nextInt(candidates.size()));
        int y = preset == Preset.CAVES ? g.cave(i) : g.height(i);
        return new Result(Optional.of(new Pos(g.x(i) + 0.5, y, g.z(i) + 0.5)), null);
    }

    // un lugar por jugador para repartirlos al empezar: suelo al que se llega a pie desde el creador, dentro del
    // cuadrado de medio lado half alrededor de center, cada uno lo más lejos posible de los ya elegidos. Con menos
    // lugares que jugadores se repiten; sin ninguno, la lista vuelve vacía
    public static List<Pos> spread(TerrainGrid g, Pos from, Pos center, double half, int count, Random rnd) {
        List<Pos> out = new ArrayList<>();
        if (g == null || from == null || center == null || !from.finite() || !(half > 0) || count <= 0) {
            return out;
        }
        Random r = rnd == null ? new Random() : rnd;
        int n = g.cells();
        boolean[] ok = new boolean[n];
        for (int i = 0; i < n; i++) {
            ok[i] = walkable(g, i);
        }
        int src = -1;
        double bestD = START_SEARCH;
        for (int i = 0; i < n; i++) {
            double d = flat(g, i, from);
            if (ok[i] && d <= bestD) {
                bestD = d;
                src = i;
            }
        }
        if (src < 0) {
            return out;
        }
        boolean[] reached = reach(g, ok, src);
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (reached[i] && g.kind(i) == TerrainGrid.LAND && Math.abs(g.x(i) + 0.5 - center.x()) <= half && Math.abs(g.z(i) + 0.5 - center.z()) <= half) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            return out;
        }
        // el costo es celdas por jugadores: con muchos, se usa una muestra al azar de las celdas
        int players = Math.min(Math.min(count, candidates.size()), MAX_SPREAD_PLAYERS);
        int cells = (int) Math.max(players, Math.min(candidates.size(), MAX_SPREAD_WORK / players));
        java.util.Collections.shuffle(candidates, r);
        if (candidates.size() > cells) {
            candidates = new ArrayList<>(candidates.subList(0, cells));
        }
        double[] nearest = new double[candidates.size()];
        java.util.Arrays.fill(nearest, Double.POSITIVE_INFINITY);
        // el primero, lo más lejos del centro: desde ahí, cada uno lejos de los demás aprovecha las esquinas
        int pick = 0;
        double far = -1;
        for (int c = 0; c < candidates.size(); c++) {
            int o = candidates.get(c);
            double dx = g.x(o) + 0.5 - center.x(), dz = g.z(o) + 0.5 - center.z();
            if (dx * dx + dz * dz > far) {
                far = dx * dx + dz * dz;
                pick = c;
            }
        }
        for (int k = 0; k < players; k++) {
            int cell = candidates.get(pick);
            out.add(new Pos(g.x(cell) + 0.5, g.height(cell), g.z(cell) + 0.5));
            int next = 0;
            for (int c = 0; c < candidates.size(); c++) {
                int o = candidates.get(c);
                double dx = g.x(o) - g.x(cell), dz = g.z(o) - g.z(cell);
                nearest[c] = Math.min(nearest[c], dx * dx + dz * dz);
                if (nearest[c] > nearest[next]) {
                    next = c;
                }
            }
            pick = next;
        }
        for (int k = players; k < count; k++) {
            out.add(out.get(k % players));
        }
        return out;
    }

    private static double flat(TerrainGrid g, int i, Pos at) {
        double dx = g.x(i) + 0.5 - at.x(), dz = g.z(i) + 0.5 - at.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static boolean[] reach(TerrainGrid g, boolean[] ok, int src) {
        int n = g.cells(), size = g.size();
        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        int head = 0, tail = 0;
        seen[src] = true;
        queue[tail++] = src;
        while (head < tail) {
            int i = queue[head++];
            int x = i % size, z = i / size;
            for (int d = 0; d < 8; d++) {
                int nx = x + DX[d], nz = z + DZ[d];
                if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
                    continue;
                }
                int j = nz * size + nx;
                if (seen[j] || !walk(g, ok, i, j)) {
                    continue;
                }
                // en diagonal, las dos celdas del costado también tienen que poder pisarse
                if (d >= 4 && (!walk(g, ok, i, z * size + nx) || !walk(g, ok, i, nz * size + x))) {
                    continue;
                }
                seen[j] = true;
                queue[tail++] = j;
            }
        }
        return seen;
    }

    private static boolean walkable(TerrainGrid g, int i) {
        return g.kind(i) == TerrainGrid.WATER || (g.kind(i) == TerrainGrid.LAND && g.headroom(i) >= 2);
    }

    private static boolean walk(TerrainGrid g, boolean[] ok, int a, int b) {
        return ok[b] && Math.abs(g.height(a) - g.height(b)) <= 1;
    }
}
