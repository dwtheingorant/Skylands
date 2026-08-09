package com.skylands.skylands.worldgen;

import net.minecraft.util.Mth;

final class SkylandsNoise {
    private SkylandsNoise() {}

    static double octaveNoise(long noiseSeed, int x, int z, double frequency, int octaves, double persistence) {
        double value = 0.0D;
        double amplitude = 1.0D;
        double amplitudeSum = 0.0D;
        double currentFrequency = frequency;

        for (int octave = 0; octave < octaves; octave++) {
            value += valueNoise2d(noiseSeed + octave * 341873128712L, x * currentFrequency, z * currentFrequency) * amplitude;
            amplitudeSum += amplitude;
            amplitude *= persistence;
            currentFrequency *= 2.0D;
        }

        return amplitudeSum <= 0.0D ? 0.0D : value / amplitudeSum;
    }

    static double landPreference(long seed, int x, int z) {
        double continental = octaveNoise(seed ^ 0x51f2ac4dL, x, z, 1.0D / 1600.0D, 4, 0.5D);
        double erosion = octaveNoise(seed ^ 0x2c9277b5L, x, z, 1.0D / 900.0D, 3, 0.5D);
        double ridgeBase = octaveNoise(seed ^ 0x7f4a7c15L, x, z, 1.0D / 600.0D, 4, 0.55D);
        double ridge = 1.0D - Math.abs(ridgeBase);
        return Mth.clamp(0.5D + continental * 0.38D - erosion * 0.18D + ridge * 0.08D, 0.0D, 1.0D);
    }

    static double ruggedness(long seed, int x, int z) {
        double ridgeBase = octaveNoise(seed ^ 0x7f4a7c15L, x, z, 1.0D / 600.0D, 4, 0.55D);
        double ridge = 1.0D - Math.abs(ridgeBase);
        double detail = octaveNoise(seed ^ 0x13579bdfL, x, z, 1.0D / 220.0D, 2, 0.45D);
        return Mth.clamp(ridge * 0.7D + Math.max(0.0D, detail) * 0.3D, 0.0D, 1.0D);
    }

    static double centerScore(long seed, int x, int z) {
        double land = landPreference(seed, x, z);
        double rugged = ruggedness(seed, x, z);
        double broad = octaveNoise(seed ^ 0x2468ace1L, x, z, 1.0D / 2400.0D, 2, 0.5D);
        return land * 1.15D + rugged * 0.35D + broad * 0.18D;
    }

    private static double valueNoise2d(long noiseSeed, double x, double z) {
        int x0 = Mth.floor(x);
        int z0 = Mth.floor(z);
        int x1 = x0 + 1;
        int z1 = z0 + 1;

        double tx = x - x0;
        double tz = z - z0;
        double sx = tx * tx * (3.0D - 2.0D * tx);
        double sz = tz * tz * (3.0D - 2.0D * tz);

        double v00 = hashToUnit(noiseSeed, x0, z0);
        double v10 = hashToUnit(noiseSeed, x1, z0);
        double v01 = hashToUnit(noiseSeed, x0, z1);
        double v11 = hashToUnit(noiseSeed, x1, z1);
        double ix0 = Mth.lerp(sx, v00, v10);
        double ix1 = Mth.lerp(sx, v01, v11);
        return Mth.lerp(sz, ix0, ix1);
    }

    private static double hashToUnit(long noiseSeed, int x, int z) {
        long mixed = noiseSeed;
        mixed ^= (long) x * 0x632be59bd9b4e019L;
        mixed ^= (long) z * 0x9e3779b97f4a7c15L;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        mixed *= 0xc4ceb9fe1a85ec53L;
        mixed ^= mixed >>> 33;
        return ((mixed & 0x1fffffffffffffL) / (double) 0x1fffffffffffffL) * 2.0D - 1.0D;
    }
}
