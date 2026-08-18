package com.skylands.skylands.worldgen;

import com.skylands.skylands.SkylandsConfig;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

public final class SkylandsIslands {
    public record Island(int centerX, int centerZ, int radius, int noiseSeed, boolean isSpawnOrigin) {
        public static Island of(int centerX, int centerZ, int radius, int noiseSeed) {
            return new Island(centerX, centerZ, radius, noiseSeed, false);
        }
    }

    private SkylandsIslands() {}

    public static Island nearestIsland(long seed, int x, int z) {
        int spacing = SkylandsConfig.SPACING.getAsInt();

        int cellX = cellForCoordinate(x, spacing);
        int cellZ = cellForCoordinate(z, spacing);

        Island best = null;
        long bestDistSq = Long.MAX_VALUE;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (isSuppressedSpawnNeighbor(cellX + dx, cellZ + dz)) {
                    continue;
                }
                Island island = islandForCell(seed, cellX + dx, cellZ + dz);
                long ddx = (long) x - island.centerX();
                long ddz = (long) z - island.centerZ();
                long distSq = ddx * ddx + ddz * ddz;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = island;
                }
            }
        }

        return best;
    }

    public static Island islandForCell(long seed, int cellX, int cellZ) {
        int spacing = SkylandsConfig.SPACING.getAsInt();
        boolean isSpawnCell = cellX == 0 && cellZ == 0;
        int minRadius = isSpawnCell
                ? Math.max(SkylandsConfig.SPAWN_MIN_RADIUS.getAsInt(), SkylandsConfig.MIN_RADIUS.getAsInt())
                : SkylandsConfig.MIN_RADIUS.getAsInt();
        int maxRadius = isSpawnCell
                ? Math.max(minRadius, SkylandsConfig.SPAWN_MAX_RADIUS.getAsInt())
                : Math.max(minRadius, SkylandsConfig.MAX_RADIUS.getAsInt());
        boolean fixedPosition = isSpawnCell && SkylandsConfig.SPAWN_FIXED_POSITION.get();
        long mixed = seed;
        mixed ^= (long) cellX * 341873128712L;
        mixed ^= (long) cellZ * 132897987541L;

        RandomSource random = new XoroshiroRandomSource(mixed);
        int radius = Mth.nextInt(random, minRadius, maxRadius);

        int cellBaseX = isSpawnCell ? 0 : cellX * spacing + spacing / 2;
        int cellBaseZ = isSpawnCell ? 0 : cellZ * spacing + spacing / 2;

        int offsetLimit = fixedPosition ? 0 : Math.max(0, spacing / 2 - radius - 16);
        Candidate best = null;
        int candidateCount = fixedPosition ? 1 : (isSpawnCell ? 9 : 7);

        for (int i = 0; i < candidateCount; i++) {
            int offsetX;
            int offsetZ;
            if (fixedPosition || i == 0) {
                offsetX = 0;
                offsetZ = 0;
            } else {
                offsetX = offsetLimit == 0 ? 0 : (random.nextInt(offsetLimit * 2 + 1) - offsetLimit);
                offsetZ = offsetLimit == 0 ? 0 : (random.nextInt(offsetLimit * 2 + 1) - offsetLimit);
            }

            int centerX = cellBaseX + offsetX;
            int centerZ = cellBaseZ + offsetZ;
            double score = SkylandsNoise.centerScore(seed, centerX, centerZ);

            if (isSpawnCell && !fixedPosition) {
                double distPenalty = Math.sqrt((double) centerX * (double) centerX + (double) centerZ * (double) centerZ)
                        / Math.max(1.0D, spacing * 0.35D);
                score -= distPenalty * 0.35D;
            }

            if (best == null || score > best.score()) {
                best = new Candidate(centerX, centerZ, score);
            }
        }

        if (best == null) {
            best = new Candidate(cellBaseX, cellBaseZ, 0.0D);
        }

        return new Island(best.centerX(), best.centerZ(), radius, random.nextInt(), isSpawnCell);
    }

    public static int cellForCoordinate(int value, int divisor) {
        int r = value / divisor;
        if ((value ^ divisor) < 0 && r * divisor != value) {
            r--;
        }
        return r;
    }

    public static Island[] nearbyIslands(long seed, int x, int z) {
        int spacing = SkylandsConfig.SPACING.getAsInt();
        int cellX = cellForCoordinate(x, spacing);
        int cellZ = cellForCoordinate(z, spacing);
        Island[] islands = new Island[9];
        int idx = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (isSuppressedSpawnNeighbor(cellX + dx, cellZ + dz)) {
                    continue;
                }
                islands[idx++] = islandForCell(seed, cellX + dx, cellZ + dz);
            }
        }
        return islands;
    }

    private static boolean isSuppressedSpawnNeighbor(int cellX, int cellZ) {
        return false;
    }

    private record Candidate(int centerX, int centerZ, double score) {}
}
