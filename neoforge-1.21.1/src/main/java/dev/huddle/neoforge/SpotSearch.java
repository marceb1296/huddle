package dev.huddle.neoforge;

import dev.huddle.core.Pos;
import dev.huddle.core.TerrainGrid;
import dev.huddle.core.TreasureFinder;
import java.util.List;
import java.util.Random;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

// lugar de un evento elegido mientras la sala está abierta: se copia el terreno cargado alrededor del creador,
// unas columnas por tick, y al terminar se elige la celda. Nunca carga ni genera chunks
final class SpotSearch {
    private final TreasureFinder.Preset preset;
    private final ServerLevel level;
    private final Pos from;
    private final TerrainGrid grid;
    private final int minDistance;
    private final int maxDistance;
    private final int caveDepth;
    private final List<Pos> avoid;
    private final double avoidRadius;
    private boolean inBases;
    private int next;
    private TreasureFinder.Result found;

    // caveDepth > 0 también busca huecos bajo tierra (más lento: lee la columna entera)
    SpotSearch(ServerPlayer creator, TreasureFinder.Preset preset, int minDistance, int maxDistance, int caveDepth) {
        this(creator, preset, minDistance, maxDistance, caveDepth, List.of(), 0);
    }

    // avoid: lugares de búsquedas recientes, que no se repiten a avoidRadius o menos mientras quede otro lugar
    SpotSearch(ServerPlayer creator, TreasureFinder.Preset preset, int minDistance, int maxDistance, int caveDepth,
               List<Pos> avoid, double avoidRadius) {
        this.preset = preset;
        this.level = creator.serverLevel();
        this.from = Tracks.pos(creator);
        int radius = HuddleConfig.GENERATOR_RADIUS.get();
        this.grid = new TerrainGrid(creator.getBlockX(), creator.getBlockZ(), radius);
        this.minDistance = minDistance;
        this.maxDistance = Math.min(maxDistance, radius);
        this.caveDepth = caveDepth;
        this.avoid = List.copyOf(avoid);
        this.avoidRadius = avoidRadius;
    }

    // si no hay lugar fuera de las bases, se acepta uno dentro
    SpotSearch orInBases() {
        inBases = true;
        return this;
    }

    // devuelve true el tick en que termina
    boolean tick() {
        if (found != null) {
            return false;
        }
        int budget = HuddleConfig.GENERATOR_COLUMNS_PER_TICK.get();
        if (caveDepth > 0) {
            budget = Math.max(1, budget / 8);
        }
        next += TrackGen.read(level, grid, next, budget, caveDepth);
        if (next < grid.cells()) {
            return false;
        }
        found = TreasureFinder.find(grid, preset, from, new TreasureFinder.Params(minDistance, maxDistance, inBases), new Random(),
                avoid, avoidRadius);
        return true;
    }

    boolean ready() {
        return found != null;
    }

    // null mientras busca o si no encontró
    Pos spot() {
        return found == null ? null : found.spot().orElse(null);
    }

    // null mientras busca o si encontró
    TreasureFinder.Failure failure() {
        return found == null || found.spot().isPresent() ? null : found.failure();
    }

    // altura de los pies en la superficie sobre el lugar (en una cueva, el suelo de arriba)
    int surface(Pos at) {
        return grid.height(grid.index((int) Math.floor(at.x()), (int) Math.floor(at.z())));
    }

    ServerLevel level() {
        return level;
    }

    TerrainGrid grid() {
        return grid;
    }

    Pos from() {
        return from;
    }

    TreasureFinder.Preset preset() {
        return preset;
    }

    int maxDistance() {
        return maxDistance;
    }
}
