package dev.huddle.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;

// arma una pista sobre una copia del terreno. Los caminos se buscan por celdas transitables para el medio,
// así la pista siempre se puede recorrer. No toca el mundo: corre fuera del hilo del servidor.
public final class TrackGenerator {
    public enum Preset {
        LAKE(true, true), RIVER(true, false), SPRINT(false, false), CIRCUIT(false, true);

        private final boolean water;
        private final boolean loop;

        Preset(boolean water, boolean loop) {
            this.water = water;
            this.loop = loop;
        }

        public boolean water() {
            return water;
        }

        public boolean loop() {
            return loop;
        }
    }

    public enum Failure { NO_GROUND, NO_WATER, NO_ROOM, TOO_SHORT }

    // spacing: bloques entre puntos; radius: radio de cada punto; minLength y maxLength: largo de una vuelta
    // o del recorrido; loopRadius: radio del circuito alrededor del creador y tope del lago
    public record Params(double spacing, double radius, int minLength, int maxLength, int loopRadius) {
    }

    // route vacía si falló; best: el recorrido más largo encontrado y near, dónde está (o null), para explicar el fallo
    public record Result(Optional<Route> route, Failure failure, int length, int best, Pos near) {
        static Result fail(Failure f, int best) {
            return new Result(Optional.empty(), f, 0, best, null);
        }
    }

    private static final int SECTORS = 8;
    private static final int MIN_ANCHORS = 4;
    private static final int MAX_WATER_TRIES = 12;
    private static final double LAND_SEARCH = 8;
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    private final TerrainGrid g;
    private final Route.Mode mode;
    private final Params p;
    private final boolean[] ok;

    private TrackGenerator(TerrainGrid g, Route.Mode mode, Params p) {
        this.g = g;
        this.mode = mode;
        this.p = p;
        this.ok = passable();
    }

    public static Route.Mode modeFor(Preset preset, Route.Mode wanted) {
        if (preset.water()) {
            return Route.Mode.BOAT;
        }
        return wanted == Route.Mode.HORSE ? Route.Mode.HORSE : Route.Mode.FOOT;
    }

    public static Result generate(TerrainGrid g, Preset preset, Route.Mode wanted, Pos creator, Params params,
                                  String name, UUID author, String authorName, int laps) {
        if (g == null || preset == null || creator == null || !creator.finite() || params == null
                || !(params.spacing() > 0) || !(params.radius() > 0)) {
            return Result.fail(Failure.NO_GROUND, 0);
        }
        TrackGenerator gen = new TrackGenerator(g, modeFor(preset, wanted), params);
        Found f = switch (preset) {
            case LAKE, RIVER -> gen.water(preset, creator);
            case SPRINT -> gen.sprint(creator);
            case CIRCUIT -> gen.circuit(creator);
        };
        if (f.path == null) {
            return new Result(Optional.empty(), f.failure, 0, (int) Math.round(f.best), f.at >= 0 ? gen.feet(f.at) : null);
        }
        // el camino puede cruzar bases, pero la meta de una pista de ida no queda en una
        int[] path = f.path;
        if (!preset.loop()) {
            int end = path.length;
            while (end > 1 && g.busy(path[end - 1])) {
                end--;
            }
            if (end < path.length) {
                path = Arrays.copyOf(path, end);
                double len = gen.length(path);
                if (len < params.minLength()) {
                    return Result.fail(Failure.TOO_SHORT, (int) Math.round(len));
                }
                f = new Found(path, len, null, len);
            }
        }
        int l = preset.loop() ? laps : 1;
        List<Pos> centers = new ArrayList<>();
        boolean[] skip = new boolean[path.length];
        for (int k = 0; k < path.length; k++) {
            centers.add(gen.center(path[k]));
            skip[k] = g.busy(path[k]);
        }
        List<Route.Checkpoint> cps = place(centers, skip, params.spacing(), params.radius(), preset.loop());
        // si el primer punto pisa la meta, Route.of la quitaría y dejaría el circuito en una sola vuelta
        if (preset.loop() && cps.get(0).overlaps(cps.get(cps.size() - 1))) {
            return Result.fail(Failure.NO_ROOM, (int) Math.round(f.length));
        }
        Optional<Route> route = Route.of(name, author, authorName, gen.mode, l, gen.feet(path[0]), cps);
        return new Result(route, route.isEmpty() ? Failure.TOO_SHORT : null, (int) Math.round(f.length), (int) Math.round(f.length), null);
    }

    private record Found(int[] path, double length, Failure failure, double best, int at) {
        Found(int[] path, double length, Failure failure, double best) {
            this(path, length, failure, best, -1);
        }

        static Found fail(Failure f, double best) {
            return new Found(null, 0, f, best, -1);
        }
    }

    // celdas donde puede estar el centro del jugador. El bote y el caballo necesitan margen a los costados:
    // el agua de 3 de ancho y el caballo con espacio para no trabarse en un borde
    private boolean[] passable() {
        int n = g.cells();
        boolean[] base = new boolean[n];
        for (int i = 0; i < n; i++) {
            base[i] = switch (mode) {
                case BOAT -> g.kind(i) == TerrainGrid.WATER;
                case HORSE -> g.kind(i) == TerrainGrid.LAND && g.headroom(i) >= 3;
                default -> g.kind(i) == TerrainGrid.LAND && g.headroom(i) >= 2;
            };
        }
        if (mode == Route.Mode.FOOT) {
            return base;
        }
        boolean[] out = new boolean[n];
        int size = g.size();
        for (int i = 0; i < n; i++) {
            if (!base[i]) {
                continue;
            }
            int x = i % size, z = i / size;
            boolean all = true;
            for (int d = 0; d < 8 && all; d++) {
                int nx = x + DX[d], nz = z + DZ[d];
                all = nx >= 0 && nz >= 0 && nx < size && nz < size && base[nz * size + nx] && step(i, nz * size + nx);
            }
            out[i] = all;
        }
        return out;
    }

    // en el agua sin desniveles (una cascada no se sube en bote); en tierra, escalones de un bloque
    private boolean step(int a, int b) {
        int dh = Math.abs(g.height(a) - g.height(b));
        return mode == Route.Mode.BOAT ? dh == 0 : dh <= 1;
    }

    // vecino d de la celda i si se puede pasar; en diagonal, sin cortar esquinas
    private int next(int i, int d) {
        int size = g.size();
        int x = i % size + DX[d], z = i / size + DZ[d];
        if (x < 0 || z < 0 || x >= size || z >= size) {
            return -1;
        }
        int j = z * size + x;
        if (!ok[j] || !step(i, j)) {
            return -1;
        }
        if (d >= 4) {
            int a = (i / size) * size + x, b = z * size + i % size;
            if (!ok[a] || !ok[b] || !step(i, a) || !step(i, b) || !step(a, j) || !step(b, j)) {
                return -1;
            }
        }
        return j;
    }

    // distancias en décimas de bloque (10 recto, 14 en diagonal, más 10 por bloque de subida)
    private int[] dijkstra(int src, int[] parent) {
        return dijkstra(src, parent, -1);
    }

    // stop: celda a la que se quiere llegar; al sacarla de la cola su distancia ya es la definitiva
    private int[] dijkstra(int src, int[] parent, int stop) {
        int n = g.cells();
        int[] dist = new int[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        if (parent != null) {
            Arrays.fill(parent, -1);
        }
        dist[src] = 0;
        PriorityQueue<long[]> q = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        q.add(new long[]{0, src});
        while (!q.isEmpty()) {
            long[] top = q.poll();
            int i = (int) top[1];
            if (top[0] > dist[i]) {
                continue;
            }
            if (i == stop) {
                break;
            }
            for (int d = 0; d < 8; d++) {
                int j = next(i, d);
                if (j < 0) {
                    continue;
                }
                int nd = dist[i] + (d < 4 ? 10 : 14) + 10 * Math.abs(g.height(i) - g.height(j));
                if (nd < dist[j]) {
                    dist[j] = nd;
                    if (parent != null) {
                        parent[j] = i;
                    }
                    q.add(new long[]{nd, j});
                }
            }
        }
        return dist;
    }

    private static int[] walkBack(int[] parent, int target) {
        List<Integer> rev = new ArrayList<>();
        for (int i = target; i >= 0; i = parent[i]) {
            rev.add(i);
        }
        int[] path = new int[rev.size()];
        for (int k = 0; k < path.length; k++) {
            path[k] = rev.get(path.length - 1 - k);
        }
        return path;
    }

    private double hop(int a, int b) {
        double dx = g.x(a) - g.x(b), dz = g.z(a) - g.z(b), dy = g.height(a) - g.height(b);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double length(int[] path) {
        double l = 0;
        for (int k = 1; k < path.length; k++) {
            l += hop(path[k - 1], path[k]);
        }
        return l;
    }

    private double flat(int i, Pos at) {
        double dx = g.x(i) + 0.5 - at.x(), dz = g.z(i) + 0.5 - at.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // celda transitable más cercana al creador; en tierra tiene que estar cerca de él
    private int nearest(Pos at, double within) {
        int best = -1;
        double bestD = within;
        for (int i = 0; i < g.cells(); i++) {
            if (ok[i]) {
                double d = flat(i, at);
                if (d <= bestD) {
                    bestD = d;
                    best = i;
                }
            }
        }
        return best;
    }

    private Found water(Preset preset, Pos creator) {
        int n = g.cells();
        int[] label = new int[n];
        Arrays.fill(label, -1);
        List<int[]> parts = new ArrayList<>();
        int[] queue = new int[n];
        for (int s = 0; s < n; s++) {
            if (!ok[s] || label[s] >= 0) {
                continue;
            }
            int id = parts.size(), head = 0, tail = 0, closest = s;
            label[s] = id;
            queue[tail++] = s;
            while (head < tail) {
                int i = queue[head++];
                if (flat(i, creator) < flat(closest, creator)) {
                    closest = i;
                }
                for (int d = 0; d < 8; d++) {
                    int j = next(i, d);
                    if (j >= 0 && label[j] < 0) {
                        label[j] = id;
                        queue[tail++] = j;
                    }
                }
            }
            parts.add(new int[]{closest, tail});
        }
        if (parts.isEmpty()) {
            return Found.fail(Failure.NO_WATER, 0);
        }
        // las más cercanas primero. Un recorrido de largo L pasa por al menos L / 1,5 celdas: los charcos
        // más chicos no alcanzan y no gastan intentos (si no, unos cuantos charcos cerca taparían un lago)
        parts.sort((a, b) -> Double.compare(flat(a[0], creator), flat(b[0], creator)));
        // si ninguna alcanza, se informa la mejor y dónde está su parte más cercana al creador
        double best = 0;
        int bestAt = -1;
        int tries = 0;
        for (int[] part : parts) {
            if (tries >= MAX_WATER_TRIES) {
                break;
            }
            double found;
            if (part[1] * 1.5 < p.minLength()) {
                found = part[1];
            } else {
                tries++;
                Found f = preset == Preset.RIVER ? river(part[0], creator) : lake(part[0], label, creator);
                if (f.path != null) {
                    return f;
                }
                found = f.best;
            }
            if (found > best) {
                best = found;
                bestAt = part[0];
            }
        }
        return new Found(null, 0, Failure.TOO_SHORT, best, bestAt);
    }

    // de punta a punta: el camino más largo del agua (dos búsquedas desde los extremos)
    private Found river(int any, Pos creator) {
        int[] parent = new int[g.cells()];
        int a = farthest(dijkstra(any, null));
        int[] dist = dijkstra(a, parent);
        int b = farthest(dist);
        int[] path = walkBack(parent, b);
        if (flat(path[path.length - 1], creator) < flat(path[0], creator)) {
            reverse(path);
        }
        path = cut(path, p.maxLength());
        double len = length(path);
        return len >= p.minLength() ? new Found(path, len, null, len) : Found.fail(Failure.TOO_SHORT, len);
    }

    private static int farthest(int[] dist) {
        int best = -1;
        for (int i = 0; i < dist.length; i++) {
            if (dist[i] != Integer.MAX_VALUE && (best < 0 || dist[i] > dist[best])) {
                best = i;
            }
        }
        return best;
    }

    private static void reverse(int[] a) {
        for (int i = 0, j = a.length - 1; i < j; i++, j--) {
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    private int[] cut(int[] path, double max) {
        double l = 0;
        for (int k = 1; k < path.length; k++) {
            l += hop(path[k - 1], path[k]);
            if (l > max) {
                return Arrays.copyOf(path, k);
            }
        }
        return path;
    }

    // vueltas alrededor del centro del agua, a poco más de la mitad del camino a la orilla
    private Found lake(int any, int[] label, Pos creator) {
        int id = label[any];
        double sx = 0, sz = 0;
        int count = 0;
        for (int i = 0; i < label.length; i++) {
            if (label[i] == id) {
                sx += g.x(i) + 0.5;
                sz += g.z(i) + 0.5;
                count++;
            }
        }
        Pos center = new Pos(sx / count, 0, sz / count);
        double[] reach = new double[SECTORS];
        for (int i = 0; i < label.length; i++) {
            if (label[i] == id) {
                int s = sector(i, center);
                reach[s] = Math.max(reach[s], flat(i, center));
            }
        }
        double[] target = new double[SECTORS];
        for (int s = 0; s < SECTORS; s++) {
            target[s] = Math.min(0.6 * reach[s], p.loopRadius());
        }
        return loop(anchors(i -> label[i] == id, center, target, 0), creator);
    }

    // circuito alrededor del creador por tierra que se puede alcanzar desde donde está
    private Found circuit(Pos creator) {
        int src = nearest(creator, LAND_SEARCH);
        if (src < 0) {
            return Found.fail(Failure.NO_GROUND, 0);
        }
        int[] dist = dijkstra(src, null);
        double[] target = new double[SECTORS];
        Arrays.fill(target, p.loopRadius());
        return loop(anchors(i -> dist[i] != Integer.MAX_VALUE, creator, target, p.loopRadius() / 2.0), creator);
    }

    private interface Cells {
        boolean in(int i);
    }

    // en cada sector, la celda más cercana a la distancia buscada; tol > 0 descarta las que quedan lejos de ella
    private List<Integer> anchors(Cells cells, Pos center, double[] target, double tol) {
        int[] pick = new int[SECTORS];
        double[] err = new double[SECTORS];
        Arrays.fill(pick, -1);
        Arrays.fill(err, Double.MAX_VALUE);
        for (int i = 0; i < g.cells(); i++) {
            if (!ok[i] || !cells.in(i)) {
                continue;
            }
            int s = sector(i, center);
            double e = Math.abs(flat(i, center) - target[s]);
            if (target[s] >= 3 && e < err[s] && (tol <= 0 || e <= tol)) {
                err[s] = e;
                pick[s] = i;
            }
        }
        // dos sectores vecinos pueden elegir celdas casi juntas (un pasillo): sin separación el circuito
        // sería una ida y vuelta por el mismo lugar
        List<Integer> out = new ArrayList<>();
        for (int s = 0; s < SECTORS; s++) {
            if (pick[s] < 0) {
                continue;
            }
            double min = 0.4 * target[s];
            boolean apart = true;
            for (int o : out) {
                apart &= hop(o, pick[s]) >= min;
            }
            if (apart) {
                out.add(pick[s]);
            }
        }
        return out;
    }

    private int sector(int i, Pos center) {
        double a = Math.atan2(g.z(i) + 0.5 - center.z(), g.x(i) + 0.5 - center.x());
        int s = (int) Math.floor((a + Math.PI) / (2 * Math.PI) * SECTORS);
        return Math.max(0, Math.min(s, SECTORS - 1));
    }

    // une los puntos en orden y vuelve al primero; la largada es el más cercano al creador
    private Found loop(List<Integer> anchors, Pos creator) {
        if (anchors.size() < MIN_ANCHORS) {
            return Found.fail(Failure.NO_ROOM, 0);
        }
        int first = 0;
        for (int k = 1; k < anchors.size(); k++) {
            if (flat(anchors.get(k), creator) < flat(anchors.get(first), creator)) {
                first = k;
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k <= anchors.size(); k++) {
            order.add(anchors.get((first + k) % anchors.size()));
        }
        List<Integer> all = new ArrayList<>();
        all.add(order.get(0));
        int[] parent = new int[g.cells()];
        for (int k = 1; k < order.size(); k++) {
            int[] dist = dijkstra(order.get(k - 1), parent, order.get(k));
            if (dist[order.get(k)] == Integer.MAX_VALUE) {
                return Found.fail(Failure.NO_ROOM, 0);
            }
            int[] leg = walkBack(parent, order.get(k));
            for (int s = 1; s < leg.length; s++) {
                all.add(leg[s]);
            }
        }
        int[] path = all.stream().mapToInt(Integer::intValue).toArray();
        double len = length(path);
        if (len < p.minLength()) {
            return Found.fail(Failure.TOO_SHORT, len);
        }
        return new Found(path, len, null, len);
    }

    // de A a B casi en línea recta: el punto más lejano que no obliga a dar muchas vueltas
    private Found sprint(Pos creator) {
        int src = nearest(creator, LAND_SEARCH);
        if (src < 0) {
            return Found.fail(Failure.NO_GROUND, 0);
        }
        int[] parent = new int[g.cells()];
        int[] dist = dijkstra(src, parent);
        int best = -1;
        double bestStraight = 0, longest = 0;
        for (int i = 0; i < dist.length; i++) {
            if (dist[i] == Integer.MAX_VALUE) {
                continue;
            }
            double geo = dist[i] / 10.0;
            double straight = hop(src, i);
            longest = Math.max(longest, Math.min(geo, p.maxLength()));
            if (straight >= 0.8 * geo && straight > bestStraight) {
                bestStraight = straight;
                best = i;
            }
        }
        if (best < 0) {
            return Found.fail(Failure.TOO_SHORT, longest);
        }
        // las distancias de la búsqueda cuentan la diagonal como 1,4: el largo real puede pasar el tope
        int[] path = cut(walkBack(parent, best), p.maxLength());
        double len = length(path);
        return len >= p.minLength() ? new Found(path, len, null, len) : Found.fail(Failure.TOO_SHORT, len);
    }

    private Pos feet(int i) {
        return new Pos(g.x(i) + 0.5, g.height(i), g.z(i) + 0.5);
    }

    private Pos center(int i) {
        return new Pos(g.x(i) + 0.5, g.height(i) + 0.5, g.z(i) + 0.5);
    }

    // un punto cada spacing bloques de camino; el último es la meta (en un circuito, la largada).
    // Si el último intermedio queda pegado a la meta, la meta lo reemplaza
    static List<Route.Checkpoint> place(List<Pos> path, double spacing, double radius, boolean loop) {
        return place(path, null, spacing, radius, loop);
    }

    // skip: celdas del camino donde no va un punto intermedio (bases); el punto pasa a la primera celda libre siguiente
    static List<Route.Checkpoint> place(List<Pos> path, boolean[] skip, double spacing, double radius, boolean loop) {
        double step = Math.max(spacing, 2 * radius + 1);
        List<Route.Checkpoint> out = new ArrayList<>();
        double acc = 0;
        for (int k = 1; k < path.size() - 1; k++) {
            acc += path.get(k - 1).dist(path.get(k));
            if (acc >= step && (skip == null || !skip[k])) {
                out.add(new Route.Checkpoint(path.get(k), radius));
                acc = 0;
            }
        }
        Route.Checkpoint finish = new Route.Checkpoint(path.get(path.size() - 1), radius);
        if (!out.isEmpty() && out.get(out.size() - 1).overlaps(finish)) {
            out.remove(out.size() - 1);
        }
        out.add(finish);
        // en un circuito la meta está en la largada; si el primer punto la pisa, Route.of quitaría la meta
        while (loop && out.size() > 2 && out.get(0).overlaps(finish)) {
            out.remove(0);
        }
        return out;
    }
}
