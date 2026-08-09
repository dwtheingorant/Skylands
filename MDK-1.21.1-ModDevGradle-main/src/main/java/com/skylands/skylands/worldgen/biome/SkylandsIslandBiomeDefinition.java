package com.skylands.skylands.worldgen.biome;

import java.util.List;
import java.util.Optional;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;

public record SkylandsIslandBiomeDefinition(
        ResourceLocation biomeId,
        BlockState surfaceState,
        BlockState surfaceDetailState,
        SurfaceDetailDefinition surfaceDetail,
        List<SurfaceLayerDefinition> surfaceLayers,
        BlockState subsurfaceState,
        BlockState subsurfaceDetailState,
        BadlandsDetailDefinition badlandsDetail,
        boolean deepLayerEnabled,
        double deepLayerLine,
        BlockState deepState,
        List<DeepDetailDefinition> deepDetailDefinitions,
        List<OreSelection> oreSelections,
        List<LiquidPoolDefinition> liquidPoolDefinitions,
        List<SurfaceFeatureDefinition> surfaceFeatures,
        List<UnderhangDefinition> underhangs,
        BlockState hangingState,
        NoiseRequirements requirements,
        int priority,
        double probability,
        String terrainType,
        List<ResourceLocation> structureIds,
        List<String> integrations
) {
    public BlockState resolvedSurfaceState() {
        return surfaceState != null ? surfaceState : resolvedSubsurfaceState();
    }

    public BlockState resolvedSubsurfaceState() {
        return subsurfaceState != null ? subsurfaceState : Blocks.STONE.defaultBlockState();
    }

    public BlockState resolvedDeepState() {
        return deepState != null ? deepState : Blocks.DEEPSLATE.defaultBlockState();
    }

    public boolean matches(NoiseContext context) {
        return requirements != null && requirements.isComplete() && requirements.matches(context);
    }

    public record SurfaceLayerDefinition(BlockState state, int depth) {
        public int clampedDepth() {
            return Math.max(1, depth);
        }
    }

    public record OreSelection(
            ResourceLocation oreId,
            ResourceLocation sourceId
    ) {}

    public record SurfaceFeatureDefinition(
            BlockState featureBlock,
            ResourceLocation placedFeatureId,
            double density,
            double probability,
            List<BlockState> requiredBottoms,
            int heightRequired,
            int toleranceRequired,
            int radiusRequired
    ) {
        public boolean isPlacedFeature() {
            return placedFeatureId != null;
        }

        public double clampedDensity() {
            return Mth.clamp(density, 0.0D, 1.0D);
        }

        public double clampedProbability() {
            return Mth.clamp(probability, 0.0D, 1.0D);
        }

        public int clampedHeightRequired() {
            return Math.max(1, heightRequired);
        }

        public int clampedToleranceRequired() {
            return Math.max(0, toleranceRequired);
        }

        public int clampedRadiusRequired() {
            return Math.max(0, radiusRequired);
        }

        public boolean isValid() {
            return featureBlock != null || placedFeatureId != null;
        }

        public boolean matchesBottom(BlockState bottom) {
            if (requiredBottoms == null || requiredBottoms.isEmpty()) {
                return true;
            }
            for (BlockState rb : requiredBottoms) {
                if (rb != null && rb.equals(bottom)) {
                    return true;
                }
            }
            return false;
        }
    }

    public record UnderhangDefinition(
            BlockState block,
            BlockState tipBlock,
            int minExtend,
            int maxExtend,
            int triesPerChunk,
            List<BlockState> requiredCeilings,
            BlockState rootReplace
    ) {
        public int clampedMinExtend() {
            return Math.max(1, Math.min(minExtend, Math.max(1, maxExtend)));
        }

        public int clampedMaxExtend() {
            return Math.max(clampedMinExtend(), maxExtend);
        }

        public int clampedTriesPerChunk() {
            return Math.max(0, triesPerChunk);
        }

        public boolean isValid() {
            return block != null;
        }

        public boolean matchesCeiling(BlockState ceiling) {
            if (requiredCeilings == null || requiredCeilings.isEmpty()) return true;
            for (BlockState rc : requiredCeilings) {
                if (rc != null && rc.equals(ceiling)) return true;
            }
            return false;
        }
    }

    public record SurfaceDetailDefinition(
            SurfaceDetailType type,
            List<BlockState> states,
            BlockState targetState,
            double ratio,
            int blobSize
    ) {
        public double clampedRatio() {
            return Mth.clamp(ratio, 0.0D, 1.0D);
        }

        public int clampedBlobSize() {
            return Math.max(1, blobSize);
        }

        public boolean isValid() {
            return type != null && states != null && !states.isEmpty();
        }
    }

    public enum SurfaceDetailType {
        RANDOM_REPLACE,
        BLOB,
        BULB,
        SURFACE_PATCH
    }

    public record DeepDetailDefinition(BlockState state, int volume, double weight) {
        public int clampedVolume() {
            return Math.max(1, volume);
        }

        public double clampedWeight() {
            return Math.max(0.0D, weight);
        }
    }

    public record BadlandsDetailDefinition(
            BlockState nearSurfaceState,
            int minBandThickness,
            int maxBandThickness,
            double splitLine,
            List<BlockState> bandStates
    ) {
        public int clampedMinBandThickness() {
            return Math.max(1, minBandThickness);
        }

        public int clampedMaxBandThickness() {
            return Math.max(clampedMinBandThickness(), maxBandThickness);
        }

        public double clampedSplitLine() {
            return Mth.clamp(splitLine, 0.0D, 1.0D);
        }

        public boolean isValid() {
            return nearSurfaceState != null && !bandStates.isEmpty();
        }
    }

    public record LiquidPoolDefinition(
            BlockState liquidState,
            double density,
            String shape,
            int size,
            int depth,
            List<BlockState> bottomStates
    ) {
        public double clampedDensity() {
            return Mth.clamp(density, 0.0D, 1.0D);
        }

        public int clampedSize() {
            return Math.max(1, size);
        }

        public int clampedDepth() {
            return Math.max(1, depth);
        }

        public boolean isValid() {
            return liquidState != null && !bottomStates.isEmpty();
        }
    }

    public record NoiseRequirements(
            List<NoiseCondition> temperature,
            List<NoiseCondition> humidity,
            List<NoiseCondition> continentalness,
            List<NoiseCondition> erosion,
            List<NoiseCondition> weirdness,
            List<NoiseCondition> depth
    ) {
        public boolean isComplete() {
            return true;
        }

        public boolean matches(NoiseContext context) {
            return allMatch(temperature, context.temperature())
                    && allMatch(humidity, context.humidity())
                    && allMatch(continentalness, context.continentalness())
                    && allMatch(erosion, context.erosion())
                    && allMatch(weirdness, context.weirdness())
                    && allMatch(depth, context.depth());
        }

        private static boolean allMatch(List<NoiseCondition> conds, double value) {
            if (conds == null || conds.isEmpty()) {
                return true;
            }
            for (NoiseCondition cond : conds) {
                if (!cond.matches(value)) {
                    return false;
                }
            }
            return true;
        }
    }

    public record NoiseContext(
            double temperature,
            double humidity,
            double continentalness,
            double erosion,
            double weirdness,
            double depth
    ) {}

    public record NoiseCondition(Comparison comparison, double threshold) {
        public boolean matches(double value) {
            return switch (comparison) {
                case GREATER_THAN -> value > threshold;
                case GREATER_THAN_OR_EQUAL -> value >= threshold;
                case LESS_THAN -> value < threshold;
                case LESS_THAN_OR_EQUAL -> value <= threshold;
            };
        }
    }

    public enum Comparison {
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL,
        LESS_THAN,
        LESS_THAN_OR_EQUAL
    }
}
