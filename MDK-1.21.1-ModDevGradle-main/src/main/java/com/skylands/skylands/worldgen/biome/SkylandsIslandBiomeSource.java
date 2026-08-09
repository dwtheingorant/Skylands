package com.skylands.skylands.worldgen.biome;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import com.mojang.serialization.MapCodec;

import com.skylands.skylands.SkylandsConfig;
import com.skylands.skylands.worldgen.SkylandsIslands;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

public final class SkylandsIslandBiomeSource extends BiomeSource {
    private long seed;
    private final BiomeSource delegate;
    private volatile Map<ResourceLocation, Holder<Biome>> biomeLookup;

    public SkylandsIslandBiomeSource(long seed, BiomeSource delegate) {
        super();
        this.seed = seed;
        this.delegate = delegate;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return MapCodec.unit(this);
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return delegate.possibleBiomes().stream();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        if (!SkylandsConfig.ISLAND_BIOME_OVERRIDE_ENABLED.getAsBoolean()) {
            return delegate.getNoiseBiome(x, y, z, sampler);
        }
        int blockX = (x << 2) + 2;
        int blockZ = (z << 2) + 2;
        SkylandsIslands.Island island = islandAt(blockX, blockZ);
        if (island == null) {
            return delegate.getNoiseBiome(x, y, z, sampler);
        }
        SkylandsIslandBiomeDefinition def = SkylandsIslandBiomes.select(seed, island);
        Holder<Biome> fallback = delegate.getNoiseBiome(x, y, z, sampler);
        Holder<Biome> resolved = biomeLookup().get(def.biomeId());
        return resolved == null ? fallback : resolved;
    }

    private SkylandsIslands.Island islandAt(int x, int z) {
        SkylandsIslands.Island[] nearby = SkylandsIslands.nearbyIslands(seed, x, z);
        int margin = SkylandsConfig.ISLAND_BIOME_MARGIN.getAsInt();
        SkylandsIslands.Island best = null;
        long bestDistSq = Long.MAX_VALUE;
        for (SkylandsIslands.Island island : nearby) {
            if (island == null) {
                continue;
            }
            int r = Math.max(12, island.radius()) + margin;
            long dx = (long) x - island.centerX();
            long dz = (long) z - island.centerZ();
            long distSq = dx * dx + dz * dz;
            if (distSq <= (long) r * (long) r && distSq < bestDistSq) {
                bestDistSq = distSq;
                best = island;
            }
        }
        return best;
    }

    private Map<ResourceLocation, Holder<Biome>> biomeLookup() {
        Map<ResourceLocation, Holder<Biome>> lookup = biomeLookup;
        if (lookup != null) {
            return lookup;
        }
        Map<ResourceLocation, Holder<Biome>> next = new HashMap<>();
        for (Holder<Biome> holder : delegate.possibleBiomes()) {
            holder.unwrapKey().ifPresent(key -> next.put(key.location(), holder));
        }
        biomeLookup = next;
        return next;
    }
}
