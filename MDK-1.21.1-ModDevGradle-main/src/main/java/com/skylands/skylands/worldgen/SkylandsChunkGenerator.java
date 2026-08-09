package com.skylands.skylands.worldgen;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skylands.skylands.SkylandsConfig;
import com.skylands.skylands.worldgen.biome.SkylandsIslandBiomeSource;
import com.skylands.skylands.worldgen.biome.SkylandsIslandBiomes;
import com.skylands.skylands.worldgen.biome.SkylandsIslandBiomeDefinition;

import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.heightproviders.BiasedToBottomHeight;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.heightproviders.TrapezoidHeight;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
import net.minecraft.world.level.levelgen.heightproviders.VeryBiasedToBottomHeight;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

public class SkylandsChunkGenerator extends ChunkGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final MapCodec<SkylandsChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ChunkGenerator.CODEC.fieldOf("delegate").forGetter(g -> g.delegate),
            com.mojang.serialization.Codec.LONG.optionalFieldOf("seed", 0L).forGetter(g -> g.seed)
    ).apply(instance, (delegate, seed) -> new SkylandsChunkGenerator(seed, delegate)));

    private static final ResourceLocation STRONGHOLD_ID = ResourceLocation.parse("minecraft:stronghold");
    private static final ResourceKey<Structure> STRONGHOLD_KEY = ResourceKey.create(Registries.STRUCTURE, STRONGHOLD_ID);
    private static final int PROFILE_ORE = 0;
    private static final int PROFILE_AMETHYST = 1;
    private static final int OCEAN_FLORA_NONE = 0;
    private static final int OCEAN_FLORA_REGULAR = 1;
    private static final int OCEAN_FLORA_CORAL = 2;
    private static final int VANILLA_ORE_MIN_Y = -64;
    private static final int VANILLA_ORE_MAX_Y = 64;
    private static final int VANILLA_ORE_WORLD_TOP_Y = 312;
    private static final int VANILLA_GEN_MIN_Y = -64;
    private static final int VANILLA_GEN_DEPTH = 384;
    private static final ResourceLocation[] CORAL_FEATURE_IDS = new ResourceLocation[] {
            ResourceLocation.parse("minecraft:coral_tree"),
            ResourceLocation.parse("minecraft:coral_claw"),
            ResourceLocation.parse("minecraft:coral_mushroom")
    };
    private static final BlockState[] SURFACE_ORE_STATES = new BlockState[] {
            Blocks.COAL_ORE.defaultBlockState(),
            Blocks.COAL_ORE.defaultBlockState(),
            Blocks.IRON_ORE.defaultBlockState(),
            Blocks.IRON_ORE.defaultBlockState(),
            Blocks.COPPER_ORE.defaultBlockState(),
            Blocks.COPPER_ORE.defaultBlockState(),
            Blocks.GOLD_ORE.defaultBlockState(),
            Blocks.REDSTONE_ORE.defaultBlockState(),
            Blocks.LAPIS_ORE.defaultBlockState(),
            Blocks.EMERALD_ORE.defaultBlockState(),
            Blocks.DIAMOND_ORE.defaultBlockState()
    };
    private static final BlockState[] DEEP_ORE_STATES = new BlockState[] {
            Blocks.DEEPSLATE_COAL_ORE.defaultBlockState(),
            Blocks.DEEPSLATE_COAL_ORE.defaultBlockState(),
            Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(),
            Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(),
            Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState(),
            Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState(),
            Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState(),
            Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState(),
            Blocks.DEEPSLATE_LAPIS_ORE.defaultBlockState(),
            Blocks.DEEPSLATE_EMERALD_ORE.defaultBlockState(),
            Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState()
    };
    private static List<ModOreVariant> cachedModOres;
    private static final Map<Long, Integer> ISLAND_SURFACE_CACHE = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> ISLAND_TARGET_Y_CACHE = new ConcurrentHashMap<>();
    private static final java.util.Set<Long> LOGGED_DEBUG_ISLANDS = ConcurrentHashMap.newKeySet();

    private final ChunkGenerator delegate;
    private long seed;
    private final SkylandsIslandBiomeSource islandBiomeSource;
    private final Map<CompositeIsland2DMapKey, CompositeIsland2DMap> compositeIsland2DMaps = new ConcurrentHashMap<>();
    private final Map<ChunkPos, List<SurfaceFeatureApplication>> pendingSurfaceFeaturesByChunk = new ConcurrentHashMap<>();
    private final Map<ChunkPos, List<UndersideFeatureApplication>> pendingUndersideFeaturesByChunk = new ConcurrentHashMap<>();
    private long lastFeatureTracePrintMs = 0L;
    private final java.util.concurrent.atomic.AtomicInteger printedBoostCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger printedPlaceResCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile Map<Block, OreFeatureTemplateSet> oreFeatureTemplates = Map.of();
    private volatile Map<Block, Map<ResourceLocation, List<ResourceLocation>>> oreBiomeSpecialSources = Map.of();
    private volatile List<BlockState> defaultSurfaceOreStates = List.of();
    private volatile List<BlockState> defaultDeepOreStates = List.of();

    public SkylandsChunkGenerator(long seed, ChunkGenerator delegate) {
        super(new SkylandsIslandBiomeSource(seed, delegate.getBiomeSource()));
        this.seed = seed;
        this.delegate = delegate;
        this.islandBiomeSource = (SkylandsIslandBiomeSource) super.getBiomeSource();
    }

    private void syncWorldSeed(RandomState randomState) {
        if (randomState == null) {
            return;
        }
        long derivedSeed = randomState.getOrCreateRandomFactory(ResourceLocation.parse("skylands:world_seed"))
                .fromHashOf("skylands:world_seed")
                .nextLong();
        if (this.seed != derivedSeed) {
            this.seed = derivedSeed;
            this.islandBiomeSource.setSeed(derivedSeed);
            ISLAND_SURFACE_CACHE.clear();
            ISLAND_TARGET_Y_CACHE.clear();
            LOGGED_DEBUG_ISLANDS.clear();
            compositeIsland2DMaps.clear();
            pendingSurfaceFeaturesByChunk.clear();
            pendingUndersideFeaturesByChunk.clear();
        }
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess chunk) {
        syncWorldSeed(randomState);
        return super.createBiomes(randomState, blender, structureManager, chunk);
    }

    @Override
    public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState, StructureManager structureManager, ChunkAccess chunk, StructureTemplateManager structureTemplateManager) {
        syncOreFeatureTemplates(registryAccess);
        delegate.createStructures(registryAccess, structureState, structureManager, chunk, structureTemplateManager);
    }

    @Override
    public void createReferences(WorldGenLevel level, StructureManager structureManager, ChunkAccess chunk) {
        delegate.createReferences(level, structureManager, chunk);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
        syncWorldSeed(randomState);
        generateSkyIslandTerrain(chunk);
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
        syncWorldSeed(randomState);
    }

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random, BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunk, GenerationStep.Carving step) {
        syncWorldSeed(random);
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        syncOreFeatureTemplates(level.registryAccess());
        List<BoundingBox> protectedBoxes = strongholdBoxesShifted(level.registryAccess(), structureManager, chunk);
        cutChunk(level, chunk, protectedBoxes);
        if (SkylandsConfig.STRONGHOLD_WRAP_ISLAND.getAsBoolean()) {
            wrapStrongholds(chunk, protectedBoxes);
        }
        generateOreFeatures(chunk, protectedBoxes);
        List<SurfaceFeatureApplication> surfaceApps = pendingSurfaceFeaturesByChunk.remove(chunk.getPos());
        if (surfaceApps != null) {
            System.out.println("[SKY-FTR] @DECOR chunk=" + chunk.getPos() + " apps.size=" + surfaceApps.size());
            Set<Long> placedTreeXZ = new HashSet<>(256);
            for (SurfaceFeatureApplication application : surfaceApps) {
                applySurfaceFeatures(level, chunk, application.segment(), application.x(), application.z(), application.topY(), placedTreeXZ);
            }
        }
        List<UndersideFeatureApplication> undersideApps = pendingUndersideFeaturesByChunk.remove(chunk.getPos());
        if (undersideApps != null && !undersideApps.isEmpty()) {
            System.out.println("[SKY-UND] @DECOR chunk=" + chunk.getPos() + " underside.size=" + undersideApps.size());
            Map<Long, List<UndersideFeatureApplication>> byIsland = new HashMap<>();
            for (UndersideFeatureApplication app : undersideApps) {
                long key = (long) app.segment().islandNoiseSeed();
                byIsland.computeIfAbsent(key, k -> new ArrayList<>()).add(app);
            }
            Set<Long> placedSet = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
            for (Map.Entry<Long, List<UndersideFeatureApplication>> e : byIsland.entrySet()) {
                List<UndersideFeatureApplication> apps = e.getValue();
                SkylandsColumnSegment sampleSeg = apps.get(0).segment();
                List<SkylandsIslandBiomeDefinition.UnderhangDefinition> underhangs = sampleSeg.islandBiome().underhangs();
                if (underhangs == null || underhangs.isEmpty()) continue;
                for (int ui = 0; ui < underhangs.size(); ui++) {
                    SkylandsIslandBiomeDefinition.UnderhangDefinition udef = underhangs.get(ui);
                    if (!udef.isValid()) continue;
                    int tries = udef.clampedTriesPerChunk();
                    int pool = apps.size();
                    if (tries >= pool || tries == Integer.MAX_VALUE) {
                        for (UndersideFeatureApplication app : apps) {
                            long pkey = (((long) app.x() & 0xFFFFFFFFL) << 32) | ((long) app.z() & 0xFFFFFFFFL);
                            if (!placedSet.add(pkey)) continue;
                            placeOneUnderhang(level, chunk, ui, udef, app.segment(), app.x(), app.z(), app.bottomY());
                        }
                    } else {
                        java.util.Random rnd = new java.util.Random(e.getKey() ^ (long) ui * 0x9E3779B97F4A7C15L ^ ((long) chunk.getPos().x << 16) ^ ((long) chunk.getPos().z));
                        int placed = 0;
                        int attempts = 0;
                        int maxAttempts = tries * 4;
                        while (placed < tries && attempts < maxAttempts) {
                            attempts++;
                            int idx = rnd.nextInt(pool);
                            UndersideFeatureApplication app = apps.get(idx);
                            long pkey = (((long) app.x() & 0xFFFFFFFFL) << 32) | ((long) app.z() & 0xFFFFFFFFL);
                            if (!placedSet.add(pkey)) continue;
                            boolean ok = placeOneUnderhang(level, chunk, ui, udef, app.segment(), app.x(), app.z(), app.bottomY());
                            if (ok) placed++;
                        }
                    }
                }
            }
        }
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
        delegate.spawnOriginalMobs(level);
    }

    @Override
    public int getGenDepth() {
        return delegate.getGenDepth();
    }

    @Override
    public int getSeaLevel() {
        return delegate.getSeaLevel();
    }

    @Override
    public int getMinY() {
        return delegate.getMinY();
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types heightmapType, LevelHeightAccessor level, RandomState randomState) {
        syncWorldSeed(randomState);
        return delegate.getBaseHeight(x, z, heightmapType, level, randomState);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
        syncWorldSeed(randomState);
        return delegate.getBaseColumn(x, z, heightAccessor, randomState);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        delegate.addDebugScreenInfo(info, randomState, pos);
    }

    @Override
    public BiomeSource getBiomeSource() {
        return super.getBiomeSource();
    }

    private void generateSkyIslandTerrain(ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        int minY = getMinY();
        int maxY = minY + getGenDepth() - 1;
        List<BadlandsColumnApplication> pendingBadlands = new ArrayList<>();
        List<IslandOreColumnApplication> pendingIslandOres = new ArrayList<>();
        List<LiquidPoolColumnApplication> pendingLiquidPools = new ArrayList<>();
        List<SurfaceFeatureApplication> pendingSurfaceFeatures = new ArrayList<>();
        List<UndersideFeatureApplication> pendingUndersideFeatures = new ArrayList<>();

        for (int x = chunkPos.getMinBlockX(); x <= chunkPos.getMaxBlockX(); x++) {
            for (int z = chunkPos.getMinBlockZ(); z <= chunkPos.getMaxBlockZ(); z++) {
                for (int y = minY; y <= maxY; y++) {
                    chunk.setBlockState(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), false);
                }

                List<SkylandsColumnCandidate> candidates = sampleSkylandsColumns(chunk, x, z);
                if (candidates.isEmpty()) {
                    continue;
                }

                int baseShellDepth = Math.max(1, SkylandsConfig.SURFACE_SHELL_DEPTH.getAsInt());
                List<SkylandsColumnSegment> segments = mergeColumnSegments(candidates);
                SkylandsColumnSegment topSegment = null;
                int topSegmentY = Integer.MIN_VALUE;
                for (SkylandsColumnSegment segment : segments) {
                    if (segment.topY() > topSegmentY) {
                        topSegmentY = segment.topY();
                        topSegment = segment;
                    }
                }

                for (SkylandsColumnSegment segment : segments) {
                    for (int y = segment.bottomY(); y <= segment.topY(); y++) {
                        chunk.setBlockState(new BlockPos(x, y, z), baseIslandInteriorState(segment, x, y, z), false);
                    }

                    int finalTopY = segment.topY();
                    if (segment == topSegment) {
                        int stacked = applyTerrainStack(chunk, segment, x, z);
                        finalTopY += stacked;
                    }

                    applyDeepLayerColumn(chunk, segment, x, z, finalTopY);
                    applyDeepDetailColumn(chunk, segment, x, z, finalTopY);
                    applySubsurfaceDetailColumn(chunk, segment, x, z, finalTopY);
                    int shellBottomY = Math.max(segment.bottomY(), finalTopY - baseShellDepth);
                    for (int y = finalTopY; y >= shellBottomY; y--) {
                        BlockState surfaceLayerState = surfaceLayerStateAt(segment, finalTopY, y);
                        if (surfaceLayerState != null) {
                            chunk.setBlockState(new BlockPos(x, y, z), surfaceLayerState, false);
                            continue;
                        }
                        if (y == finalTopY) {
                            chunk.setBlockState(new BlockPos(x, y, z), baseIslandSurfaceState(segment, x, y, z, finalTopY), false);
                            continue;
                        }
                        chunk.setBlockState(new BlockPos(x, y, z), baseIslandInteriorState(segment, x, y, z), false);
                    }
                    applySurfaceDetailColumn(chunk, segment, x, z, finalTopY, shellBottomY);
                    pendingBadlands.add(new BadlandsColumnApplication(segment, x, z, finalTopY));
                    pendingIslandOres.add(new IslandOreColumnApplication(segment, x, z, finalTopY));
                    pendingLiquidPools.add(new LiquidPoolColumnApplication(segment, x, z, finalTopY));
                    pendingSurfaceFeatures.add(new SurfaceFeatureApplication(segment, x, z, finalTopY));
                    pendingUndersideFeatures.add(new UndersideFeatureApplication(segment, x, z, segment.bottomY()));
                }
            }
        }
        for (BadlandsColumnApplication application : pendingBadlands) {
            applyBadlandsDetailColumn(chunk, application.segment(), application.x(), application.z(), application.topY());
        }
        for (IslandOreColumnApplication application : pendingIslandOres) {
            applyIslandOreColumn(chunk, application.segment(), application.x(), application.z(), application.topY());
        }
        for (LiquidPoolColumnApplication application : pendingLiquidPools) {
            applyLiquidPoolColumn(chunk, application.segment(), application.x(), application.z(), application.topY());
        }
        if (!pendingSurfaceFeatures.isEmpty()) {
            pendingSurfaceFeaturesByChunk.put(chunk.getPos(), List.copyOf(pendingSurfaceFeatures));
        }
        if (!pendingUndersideFeatures.isEmpty()) {
            pendingUndersideFeaturesByChunk.put(chunk.getPos(), List.copyOf(pendingUndersideFeatures));
        }
    }

    private List<SkylandsColumnCandidate> sampleSkylandsColumns(ChunkAccess chunk, int x, int z) {
        int seaLevel = getSeaLevel();
        TheoreticalTerrainSample columnSample = theoreticalTerrainAt(chunk, x, z, seaLevel);
        SkylandsIslands.Island[] islands = SkylandsIslands.nearbyIslands(seed, x, z);
        List<SkylandsColumnCandidate> columns = new ArrayList<>();
        for (SkylandsIslands.Island island : islands) {
            if (island == null) {
                continue;
            }
            sampleIslandGroupColumns(chunk, island, x, z, seaLevel, columnSample, columns);
        }
        return columns;
    }

    private void sampleIslandGroupColumns(
            ChunkAccess chunk,
            SkylandsIslands.Island island,
            int x,
            int z,
            int seaLevel,
            TheoreticalTerrainSample columnSample,
            List<SkylandsColumnCandidate> columns
    ) {
        TheoreticalTerrainSample centerSample = theoreticalTerrainAt(chunk, island.centerX(), island.centerZ(), seaLevel);
        int groupBaseY = islandTargetBaseY(chunk, island, centerSample);
        int mainRadius = Math.max(12, island.radius());
        addCompositeIslandColumns(
                island,
                x,
                z,
                columnSample,
                columns,
                island.centerX(),
                island.centerZ(),
                mainRadius,
                // Keep the island's upper anchor fixed per island so adjacent chunks
                // cannot build different versions of the same 2D island map.
                groupBaseY,
                0x632BE59BD9B4E019L
        );
    }

    private void spawnSideIslandsAround(
            SkylandsIslands.Island island,
            int x,
            int z,
            TheoreticalTerrainSample columnSample,
            List<SkylandsColumnCandidate> columns,
            List<IslandPlacement> placedIslands,
            int parentCenterX,
            int parentCenterZ,
            int parentRadius,
            int parentBaseY,
            long parentSalt,
            int mainRadius,
            int depth
    ) {
        if (depth >= 2) {
            return;
        }

        double branchingThreshold = mainRadius * 0.85D;
        if (depth > 0 && parentRadius < branchingThreshold) {
            return;
        }

        int sideIslandCount = 2 + (int) Math.floor(islandShapeValue(island.noiseSeed(), parentSalt ^ 0x41C64E6DL) * 4.0D);
        double baseAngle = islandShapeValue(island.noiseSeed(), parentSalt ^ 0x243F6A8885A308D3L) * (Math.PI * 2.0D);
        for (int sideIndex = 0; sideIndex < sideIslandCount; sideIndex++) {
            long sideSalt = parentSalt ^ (0x94D049BB133111EBL + (long) (sideIndex + 1) * 0x9E3779B97F4A7C15L);
            double radiusScale = Mth.lerp(islandShapeValue(island.noiseSeed(), sideSalt ^ 0x55AA55AAL), 0.05D, 0.99D);
            int sideRadius = Math.max(8, Mth.ceil(parentRadius * radiusScale));
            double angleBase = baseAngle
                    + sideIndex * ((Math.PI * 2.0D) / Math.max(1, sideIslandCount))
                    + islandShapeValue(island.noiseSeed(), sideSalt ^ 0x7F4A7C15L) * 1.10D;
            int sideCenterX = 0;
            int sideCenterZ = 0;
            boolean placed = false;
            for (int attempt = 0; attempt < 6; attempt++) {
                double angle = angleBase + attempt * 0.67D;
                double orbitDistance = (parentRadius + sideRadius)
                        * Mth.lerp(islandShapeValue(island.noiseSeed(), sideSalt ^ (0x6A09E667L + attempt * 17L)), 0.88D, 1.35D)
                        + attempt * Math.max(parentRadius, sideRadius) * 0.22D;
                int tryCenterX = parentCenterX + Mth.floor(Math.cos(angle) * orbitDistance);
                int tryCenterZ = parentCenterZ + Mth.floor(Math.sin(angle) * orbitDistance);
                if (hasEnoughIslandGap(tryCenterX, tryCenterZ, sideRadius, placedIslands)) {
                    sideCenterX = tryCenterX;
                    sideCenterZ = tryCenterZ;
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                continue;
            }

            int rawSideBaseY = parentBaseY
                    + Mth.floor(Mth.lerp(
                    islandShapeValue(island.noiseSeed(), sideSalt ^ 0x0F1E2D3CL),
                    -sideRadius * 0.35D,
                    sideRadius * 0.22D
            ));
            int maxNeighborDelta = Math.max(8, Mth.ceil(Math.max(parentRadius, sideRadius) * 0.18D));
            int sideBaseY = Mth.clamp(rawSideBaseY, parentBaseY - maxNeighborDelta, parentBaseY + maxNeighborDelta);

            addCompositeIslandColumns(
                    island,
                    x,
                    z,
                    columnSample,
                    columns,
                    sideCenterX,
                    sideCenterZ,
                    sideRadius,
                    sideBaseY,
                    sideSalt
            );
            placedIslands.add(new IslandPlacement(sideCenterX, sideCenterZ, sideRadius, sideBaseY));

            if (sideRadius >= branchingThreshold) {
                spawnSideIslandsAround(
                        island,
                        x,
                        z,
                        columnSample,
                        columns,
                        placedIslands,
                        sideCenterX,
                        sideCenterZ,
                        sideRadius,
                        sideBaseY,
                        sideSalt,
                        mainRadius,
                        depth + 1
                );
            }
        }
    }

    private void addCompositeIslandColumns(
            SkylandsIslands.Island island,
            int x,
            int z,
            TheoreticalTerrainSample columnSample,
            List<SkylandsColumnCandidate> columns,
            int localCenterX,
            int localCenterZ,
            int localRadius,
            int localBaseY,
            long islandSalt
    ) {
        SkylandsIslandBiomeDefinition islandBiome = SkylandsIslandBiomes.select(seed, island);
        CompositeIsland2DMap island2DMap = getOrCreateCompositeIsland2DMap(
                island,
                islandBiome.terrainType(),
                localCenterX,
                localCenterZ,
                localRadius,
                localBaseY,
                islandSalt
        );
        List<SkylandsColumn> islandColumns = island2DMap.columnsAt(
                x,
                z,
                islandBiome.resolvedSurfaceState(),
                islandBiome.resolvedSubsurfaceState(),
                localCenterX,
                localCenterZ,
                localRadius,
                island.noiseSeed()
        );
        if (islandColumns.isEmpty()) {
            return;
        }
        for (SkylandsColumn column : islandColumns) {
            columns.add(new SkylandsColumnCandidate(
                    column.topY(),
                    column.bottomY(),
                    column.topState(),
                    column.subsurfaceState(),
                    islandBiome,
                    column.islandCenterX(),
                    column.islandCenterZ(),
                    column.islandRadius(),
                    localBaseY,
                    column.islandNoiseSeed(),
                    column.crackStrength(),
                    column.bigCrack()
            ));
        }
    }

    private CompositeIsland2DMap getOrCreateCompositeIsland2DMap(
            SkylandsIslands.Island island,
            String terrainType,
            int localCenterX,
            int localCenterZ,
            int localRadius,
            int localBaseY,
            long islandSalt
    ) {
        CompositeIsland2DMapKey key = new CompositeIsland2DMapKey(
                island.noiseSeed(),
                localCenterX,
                localCenterZ,
                localRadius,
                localBaseY,
                islandSalt,
                terrainTypeLabel(terrainType)
        );
        return compositeIsland2DMaps.computeIfAbsent(
                key,
                unused -> buildCompositeIsland2DMap(island, terrainType, localCenterX, localCenterZ, localRadius, localBaseY, islandSalt)
        );
    }

    private CompositeIsland2DMap buildCompositeIsland2DMap(
            SkylandsIslands.Island island,
            String terrainType,
            int localCenterX,
            int localCenterZ,
            int localRadius,
            int localBaseY,
            long islandSalt
    ) {
        CompositeIslandPlan plan = buildCompositeIslandPlan(island, terrainType, localCenterX, localCenterZ, localRadius, localBaseY, islandSalt);
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int minLayerY = Integer.MAX_VALUE;
        int maxLayerY = Integer.MIN_VALUE;
        CompositeConeInfo[] coneInfos = new CompositeConeInfo[plan.coneCount()];
        for (int coneIndex = 0; coneIndex < plan.coneCount(); coneIndex++) {
            CompositeConeInfo coneInfo = describeCompositeCone(plan, island, coneIndex);
            coneInfos[coneIndex] = coneInfo;
            minX = Math.min(minX, coneInfo.coneCenterX() - coneInfo.coneRadius());
            minZ = Math.min(minZ, coneInfo.coneCenterZ() - coneInfo.coneRadius());
            maxX = Math.max(maxX, coneInfo.coneCenterX() + coneInfo.coneRadius());
            maxZ = Math.max(maxZ, coneInfo.coneCenterZ() + coneInfo.coneRadius());
            minLayerY = Math.min(minLayerY, coneInfo.minBottomY());
            maxLayerY = Math.max(maxLayerY, coneInfo.topY());
        }
        int width = Math.max(1, maxX - minX + 1);
        int height = Math.max(1, maxZ - minZ + 1);
        if (minLayerY == Integer.MAX_VALUE || maxLayerY == Integer.MIN_VALUE) {
            minLayerY = getMinY() + 1;
            maxLayerY = minLayerY;
        }
        if (plan.flatTerrain() || plan.rollingTerrain() || plan.hillsTerrain() || plan.mesaTerrain()) {
            int terrainHeadroom = plan.flatTerrain() ? 5 : plan.rollingTerrain() ? 12 : plan.hillsTerrain() ? 30 : 40;
            maxLayerY = Math.min(getMinY() + getGenDepth() - 1, maxLayerY + terrainHeadroom);
        }
        if (plan.mountainsTerrain()) {
            maxLayerY = Math.min(getMinY() + getGenDepth() - 1, maxLayerY + 44);
        }
        if (plan.peaksTerrain()) {
            maxLayerY = Math.min(getMinY() + getGenDepth() - 1, maxLayerY + 78);
        }
        if (plan.volcanoTerrain()) {
            maxLayerY = Math.min(getMinY() + getGenDepth() - 1, maxLayerY + 84);
        }
        java.util.BitSet[] layers = new java.util.BitSet[Math.max(1, maxLayerY - minLayerY + 1)];

        for (CompositeConeInfo coneInfo : coneInfos) {
            rasterizeCompositeCone(
                    plan,
                    island,
                    coneInfo,
                    minX,
                    minZ,
                    width,
                    height,
                    minLayerY,
                    maxLayerY,
                    layers
            );
        }
        if (plan.flatTerrain()) {
            applyFlatTerrain2DMap(island.noiseSeed(), minX, minZ, width, height, minLayerY, maxLayerY, layers);
        } else if (plan.rollingTerrain()) {
            applyRollingTerrain2DMap(island.noiseSeed(), localCenterX, localCenterZ, localRadius, localBaseY, minX, minZ, width, height, minLayerY, maxLayerY, layers);
        } else if (plan.hillsTerrain()) {
            applyHillsTerrain2DMap(island.noiseSeed(), localCenterX, localCenterZ, localRadius, localBaseY, minX, minZ, width, height, minLayerY, maxLayerY, layers);
        } else if (plan.mountainsTerrain()) {
            applyMountainsTerrain2DMap(island.noiseSeed(), localCenterX, localCenterZ, localRadius, localBaseY, minX, minZ, width, height, minLayerY, maxLayerY, layers);
        } else if (plan.peaksTerrain()) {
            applyPeaksTerrain2DMap(island.noiseSeed(), localCenterX, localCenterZ, localRadius, localBaseY, minX, minZ, width, height, minLayerY, maxLayerY, layers);
        } else if (plan.mesaTerrain()) {
            applyMesaTerrain2DMap(island.noiseSeed(), localCenterX, localCenterZ, localRadius, localBaseY, minX, minZ, width, height, minLayerY, maxLayerY, layers);
        } else if (plan.volcanoTerrain()) {
            applyVolcanoTerrain2DMap(island.noiseSeed(), localCenterX, localCenterZ, localRadius, localBaseY, minX, minZ, width, height, minLayerY, maxLayerY, layers);
        }
        return new CompositeIsland2DMap(minX, minZ, width, height, minLayerY, maxLayerY, layers);
    }

    private void applyFlatTerrain2DMap(
            int islandNoiseSeed,
            int minX,
            int minZ,
            int width,
            int height,
            int minLayerY,
            int maxLayerY,
            java.util.BitSet[] layers
    ) {
        int[] topYs = scanTopSurfaceHeights(width, height, minLayerY, maxLayerY, layers);
        int[] support = buildFootprintSupport(width, height, topYs, 4);
        long baseSeed = seed ^ ((long) islandNoiseSeed * 1181783497276652981L) ^ 0xD1B54A32D192ED03L;
        for (int localZ = 0; localZ < height; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int index = localZ * width + localX;
                if (topYs[index] == Integer.MIN_VALUE) {
                    continue;
                }
                double supportMask = support[index] / 81.0D;
                if (supportMask <= 0.10D) {
                    continue;
                }
                int x = minX + localX;
                int z = minZ + localZ;
                double macroA = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                        baseSeed ^ 0x243F6A8885A308D3L,
                        x,
                        z,
                        1.0D / 132.0D,
                        2,
                        0.55D
                );
                double macroB = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                        baseSeed ^ 0x13198A2E03707344L,
                        x,
                        z,
                        1.0D / 84.0D,
                        1,
                        0.50D
                );
                double macro = Mth.clamp(macroA * 0.78D + macroB * 0.22D, 0.0D, 0.999999D);
                int shelf = Mth.floor(macro * 3.0D);
                int baseHeight = 3 + shelf;
                int edgeCap = supportMask < 0.28D ? 1 : supportMask < 0.48D ? 3 : 5;
                int extraHeight = Math.min(baseHeight, edgeCap);
                if (extraHeight <= 0) {
                    continue;
                }
                int topY = topYs[index];
                for (int dy = 1; dy <= extraHeight && topY + dy <= maxLayerY; dy++) {
                    ensureLayer(layers, topY + dy - minLayerY, width * height).set(index);
                }
            }
        }
    }

    private void applyRollingTerrain2DMap(
            int islandNoiseSeed,
            int islandCenterX,
            int islandCenterZ,
            int islandRadius,
            int islandBaseY,
            int minX,
            int minZ,
            int width,
            int height,
            int minLayerY,
            int maxLayerY,
            java.util.BitSet[] layers
    ) {
        int[] topYs = scanTopSurfaceHeights(width, height, minLayerY, maxLayerY, layers);
        java.util.BitSet connected = connectedTopSurface(width, height, topYs, islandCenterX - minX, islandCenterZ - minZ);
        int[] edgeDistance = connectedEdgeDistance(width, height, connected);
        int[] support = buildFootprintSupport(width, height, topYs, 5);
        long baseSeed = seed ^ ((long) islandNoiseSeed * 1181783497276652981L) ^ 0x8CB92BA72F3D8DD7L;
        double[] masks = new double[width * height];
        double[] centeredValues = new double[width * height];
        double[] ridgeValues = new double[width * height];
        double[] targetTops = new double[width * height];
        double[] minTops = new double[width * height];
        java.util.BitSet highCore = new java.util.BitSet(width * height);
        java.util.Arrays.fill(targetTops, Double.NaN);
        java.util.Arrays.fill(minTops, Double.NaN);

        for (int localZ = 0; localZ < height; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int index = localZ * width + localX;
                if (topYs[index] == Integer.MIN_VALUE) {
                    continue;
                }
                if (!connected.get(index)) {
                    continue;
                }
                int distToEdge = edgeDistance[index];
                if (distToEdge < 0) {
                    continue;
                }
                double edgeMask = smoothstep(1.0D, 10.0D, (double) distToEdge);
                double supportMask = smoothstep(0.22D, 0.90D, support[index] / 121.0D);
                double mask = edgeMask * supportMask;
                if (mask <= 0.01D) {
                    continue;
                }
                int x = minX + localX;
                int z = minZ + localZ;
                double dx = x - islandCenterX;
                double dz = z - islandCenterZ;
                double r = Math.sqrt(dx * dx + dz * dz) / Math.max(1.0D, (double) islandRadius);
                double footprint = coneProfile(Mth.clamp(r, 0.0D, 1.0D), 0.0D, 1.0D, 1.35D);
                if (footprint <= 0.0D) {
                    continue;
                }
                mask *= footprint;
                mask = Math.pow(mask, 0.65D);
                masks[index] = mask;

                double broadA = SkylandsNoise.octaveNoise(
                        baseSeed ^ 0x243F6A8885A308D3L,
                        x,
                        z,
                        1.0D / 96.0D,
                        2,
                        0.56D
                );
                double broadB = SkylandsNoise.octaveNoise(
                        baseSeed ^ 0x13198A2E03707344L,
                        x,
                        z,
                        1.0D / 54.0D,
                        2,
                        0.52D
                );
                double broadC = SkylandsNoise.octaveNoise(
                        baseSeed ^ 0xA4093822299F31D0L,
                        x,
                        z,
                        1.0D / 30.0D,
                        1,
                        0.50D
                );
                double broad = broadA * 0.60D + broadB * 0.26D + broadC * 0.14D;
                double shaped = smoothstep(-0.35D, 0.35D, broad);
                double centered = shaped * 2.0D - 1.0D;
                centeredValues[index] = centered;
                double ridgeNoise = SkylandsNoise.octaveNoise(
                        baseSeed ^ 0xB7E151628AED2A6BL,
                        x,
                        z,
                        1.0D / 16.0D,
                        3,
                        0.55D
                );
                ridgeValues[index] = Math.max(0.0D, ridgeNoise);
                if (mask > 0.18D && footprint > 0.30D && supportMask > 0.25D && broad > 0.62D) {
                    highCore.set(index);
                }
            }
        }

        java.util.BitSet highBoundary = maskBoundary(width, height, highCore);
        int[] insideDistance = distanceWithinAllowed(width, height, highCore, highBoundary);
        int[] outsideDistance = distanceWithinAllowed(width, height, connected, highCore);
        double transitionHalfWidth = 7.5D;
        int minThickness = 12;
        int maxThicknessScan = 64;

        for (int localZ = 0; localZ < height; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int index = localZ * width + localX;
                int topY = topYs[index];
                if (topY == Integer.MIN_VALUE) {
                    continue;
                }
                double mask = masks[index];
                if (mask <= 0.01D) {
                    continue;
                }
                int thickness = 0;
                for (int scan = 0; scan < maxThicknessScan && topY - scan >= minLayerY; scan++) {
                    java.util.BitSet layer = layers[topY - scan - minLayerY];
                    if (layer == null || !layer.get(index)) {
                        break;
                    }
                    thickness++;
                }
                int maxCarve = Math.max(0, thickness - minThickness);
                int maxCarveCap = 6;
                maxCarve = Math.min(maxCarve, maxCarveCap);
                double centered = centeredValues[index];
                int signedDistance;
                if (highCore.get(index)) {
                    int d = insideDistance[index];
                    signedDistance = d < 0 ? 0 : d;
                } else {
                    int d = outsideDistance[index];
                    if (d < 0) {
                        d = (int) Math.ceil(transitionHalfWidth);
                    }
                    signedDistance = -d;
                }
                double highMask = smoothstep(-transitionHalfWidth, transitionHalfWidth, (double) signedDistance);
                int x = minX + localX;
                int z = minZ + localZ;
                double baseNoise = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                        baseSeed ^ 0x9E3779B97F4A7C15L,
                        x,
                        z,
                        1.0D / 40.0D,
                        3,
                        0.55D
                );
                double signedGround = (baseNoise - 0.5D) * 2.0D;
                double groundDelta = signedGround * 6.0D + 1.5D;
                double highHeight = highMask * (6.0D + Math.max(0.0D, centered) * 2.5D);
                double crest = ridgeValues[index] * highMask * 2.0D;
                double deltaValue = (groundDelta + highHeight + crest) * mask * 1.8D;
                double targetTop = topY + deltaValue;
                double minTop = topY - (double) maxCarve;
                if (targetTop < minTop) {
                    targetTop = minTop;
                }
                if (targetTop < (double) (minLayerY + 1)) {
                    targetTop = (double) (minLayerY + 1);
                }
                targetTops[index] = targetTop;
                minTops[index] = minTop;
            }
        }

        double[] smoothed = new double[targetTops.length];
        for (int pass = 0; pass < 1; pass++) {
            for (int localZ = 0; localZ < height; localZ++) {
                for (int localX = 0; localX < width; localX++) {
                    int index = localZ * width + localX;
                    double value = targetTops[index];
                    if (Double.isNaN(value)) {
                        smoothed[index] = Double.NaN;
                        continue;
                    }
                    double weightSum = 0.0D;
                    double sum = 0.0D;
                    for (int dz = -1; dz <= 1; dz++) {
                        int nz = localZ + dz;
                        if (nz < 0 || nz >= height) {
                            continue;
                        }
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = localX + dx;
                            if (nx < 0 || nx >= width) {
                                continue;
                            }
                            int nIndex = nz * width + nx;
                            double nValue = targetTops[nIndex];
                            if (Double.isNaN(nValue)) {
                                continue;
                            }
                            double w = masks[nIndex];
                            if (w <= 0.01D) {
                                continue;
                            }
                            weightSum += w;
                            sum += nValue * w;
                        }
                    }
                    if (weightSum <= 0.0D) {
                        smoothed[index] = Mth.clamp(value, minTops[index], (double) maxLayerY);
                        continue;
                    }
                    smoothed[index] = Mth.clamp(sum / weightSum, minTops[index], (double) maxLayerY);
                }
            }
            double[] tmp = targetTops;
            targetTops = smoothed;
            smoothed = tmp;
        }

        long roundSeed = baseSeed ^ 0x3C6EF372L;
        for (int localZ = 0; localZ < height; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int index = localZ * width + localX;
                int topY = topYs[index];
                if (topY == Integer.MIN_VALUE) {
                    continue;
                }
                double targetTop = targetTops[index];
                if (Double.isNaN(targetTop)) {
                    continue;
                }
                targetTop = Mth.clamp(targetTop, minTops[index], (double) maxLayerY);
                int base = Mth.floor(targetTop);
                double frac = targetTop - (double) base;
                int x = minX + localX;
                int z = minZ + localZ;
                double rnd = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                        roundSeed,
                        x,
                        z,
                        1.0D / 18.0D,
                        1,
                        0.50D
                );
                int newTopY = (frac > 0.0D && rnd < frac) ? base + 1 : base;
                newTopY = Mth.clamp(newTopY, (int) Math.ceil(minTops[index]), maxLayerY);
                if (newTopY > topY) {
                    for (int y = topY + 1; y <= newTopY; y++) {
                        ensureLayer(layers, y - minLayerY, width * height).set(index);
                    }
                } else if (newTopY < topY) {
                    for (int y = topY; y > newTopY; y--) {
                        int layerIndex = y - minLayerY;
                        if (layerIndex < 0 || layerIndex >= layers.length) {
                            continue;
                        }
                        java.util.BitSet layer = layers[layerIndex];
                        if (layer != null) {
                            layer.clear(index);
                        }
                    }
                }
            }
        }
    }

    private void applyHillsTerrain2DMap(
            int islandNoiseSeed,
            int islandCenterX,
            int islandCenterZ,
            int islandRadius,
            int islandBaseY,
            int minX,
            int minZ,
            int width,
            int height,
            int minLayerY,
            int maxLayerY,
            java.util.BitSet[] layers
    ) {
        int[] topYs = scanTopSurfaceHeights(width, height, minLayerY, maxLayerY, layers);
        java.util.BitSet connected = connectedTopSurface(width, height, topYs, islandCenterX - minX, islandCenterZ - minZ);
        int[] edgeDistance = connectedEdgeDistance(width, height, connected);
        int[] support = buildFootprintSupport(width, height, topYs, 6);
        long baseSeed = seed ^ ((long) islandNoiseSeed * 1181783497276652981L) ^ 0xC19BF174CF692694L;

        double[] masks = new double[width * height];
        double[] targetTops = new double[width * height];
        double[] minTops = new double[width * height];
        double[] upliftRegions = new double[width * height];
        java.util.Arrays.fill(targetTops, Double.NaN);
        java.util.Arrays.fill(minTops, Double.NaN);

        int minThickness = 12;
        int maxThicknessScan = 64;

        for (int localZ = 0; localZ < height; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int index = localZ * width + localX;
                int topY = topYs[index];
                if (topY == Integer.MIN_VALUE) {
                    continue;
                }
                if (!connected.get(index)) {
                    continue;
                }
                int distToEdge = edgeDistance[index];
                if (distToEdge < 0) {
                    continue;
                }
                double edgeMask = smoothstep(1.0D, 10.0D, (double) distToEdge);
                double supportMask = smoothstep(0.20D, 0.92D, support[index] / 169.0D);
                double mask = edgeMask * supportMask;
                if (mask <= 0.01D) {
                    continue;
                }
                int x = minX + localX;
                int z = minZ + localZ;
                double dx = x - islandCenterX;
                double dz = z - islandCenterZ;
                double r = Math.sqrt(dx * dx + dz * dz) / Math.max(1.0D, (double) islandRadius);
                double footprint = coneProfile(Mth.clamp(r, 0.0D, 1.0D), 0.0D, 1.0D, 1.35D);
                if (footprint <= 0.0D) {
                    continue;
                }
                mask *= footprint;
                mask = Math.pow(mask, 0.55D);
                masks[index] = mask;

                int thickness = 0;
                for (int scan = 0; scan < maxThicknessScan && topY - scan >= minLayerY; scan++) {
                    java.util.BitSet layer = layers[topY - scan - minLayerY];
                    if (layer == null || !layer.get(index)) {
                        break;
                    }
                    thickness++;
                }
                int maxCarve = Math.max(0, thickness - minThickness);

                double baseNoise = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                        baseSeed ^ 0x9E3779B97F4A7C15L,
                        x,
                        z,
                        1.0D / 44.0D,
                        3,
                        0.55D
                );
                double signed = (baseNoise - 0.5D) * 2.0D;
                double ridgeNoise = SkylandsNoise.octaveNoise(
                        baseSeed ^ 0xB7E151628AED2A6BL,
                        x,
                        z,
                        1.0D / 24.0D,
                        3,
                        0.58D
                );
                double ridge = 1.0D - Math.abs(ridgeNoise);
                double ridgeShape = smoothstep(0.22D, 0.95D, ridge);
                double deltaValue = (signed * 12.0D + (ridgeShape - 0.5D) * 12.0D + ridgeShape * 4.0D) * mask * 1.70D;
                if (deltaValue < 0.0D) {
                    deltaValue *= 0.45D;
                }

                double targetTop = (double) topY + deltaValue;
                double minTop = (double) topY - (double) maxCarve;
                if (targetTop < minTop) {
                    targetTop = minTop;
                }
                if (targetTop < (double) (minLayerY + 1)) {
                    targetTop = (double) (minLayerY + 1);
                }
                if (targetTop < (double) islandBaseY - 4.0D) {
                    targetTop = (double) islandBaseY - 4.0D;
                }
                targetTops[index] = targetTop;
                minTops[index] = minTop;
            }
        }

        double[] smoothed = new double[targetTops.length];
        for (int pass = 0; pass < 1; pass++) {
            for (int localZ = 0; localZ < height; localZ++) {
                for (int localX = 0; localX < width; localX++) {
                    int index = localZ * width + localX;
                    double value = targetTops[index];
                    if (Double.isNaN(value)) {
                        smoothed[index] = Double.NaN;
                        continue;
                    }
                    double weightSum = 0.0D;
                    double sum = 0.0D;
                    for (int dz = -1; dz <= 1; dz++) {
                        int nz = localZ + dz;
                        if (nz < 0 || nz >= height) {
                            continue;
                        }
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = localX + dx;
                            if (nx < 0 || nx >= width) {
                                continue;
                            }
                            int nIndex = nz * width + nx;
                            double nValue = targetTops[nIndex];
                            if (Double.isNaN(nValue)) {
                                continue;
                            }
                            double w = masks[nIndex];
                            if (w <= 0.01D) {
                                continue;
                            }
                            weightSum += w;
                            sum += nValue * w;
                        }
                    }
                    if (weightSum <= 0.0D) {
                        smoothed[index] = Mth.clamp(value, minTops[index], (double) maxLayerY);
                        continue;
                    }
                    smoothed[index] = Mth.clamp(sum / weightSum, minTops[index], (double) maxLayerY);
                }
            }
            double[] tmp = targetTops;
            targetTops = smoothed;
            smoothed = tmp;
        }

        long roundSeed = baseSeed ^ 0x3C6EF372L;
        for (int localZ = 0; localZ < height; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int index = localZ * width + localX;
                int topY = topYs[index];
                if (topY == Integer.MIN_VALUE) {
                    continue;
                }
                double targetTop = targetTops[index];
                if (Double.isNaN(targetTop)) {
                    continue;
                }
                targetTop = Mth.clamp(targetTop, minTops[index], (double) maxLayerY);
                int base = Mth.floor(targetTop);
                double frac = targetTop - (double) base;
                int x = minX + localX;
                int z = minZ + localZ;
                double rnd = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                        roundSeed,
                        x,
                        z,
                        1.0D / 14.0D,
                        1,
                        0.50D
                );
                int newTopY = (frac > 0.0D && rnd < frac) ? base + 1 : base;
                newTopY = Mth.clamp(newTopY, (int) Math.ceil(minTops[index]), maxLayerY);
                if (newTopY > topY) {
                    for (int y = topY + 1; y <= newTopY; y++) {
                        ensureLayer(layers, y - minLayerY, width * height).set(index);
                    }
                } else if (newTopY < topY) {
                    for (int y = topY; y > newTopY; y--) {
                        int layerIndex = y - minLayerY;
                        if (layerIndex < 0 || layerIndex >= layers.length) {
                            continue;
                        }
                        java.util.BitSet layer = layers[layerIndex];
                        if (layer != null) {
                            layer.clear(index);
                        }
                    }
                }
            }
        }
    }

    private void applyMountainsTerrain2DMap(
            int islandNoiseSeed,
            int islandCenterX,
            int islandCenterZ,
            int islandRadius,
            int islandBaseY,
            int minX,
            int minZ,
            int width,
            int height,
            int minLayerY,
            int maxLayerY,
            java.util.BitSet[] layers
    ) {
        applyHillsTerrain2DMap(islandNoiseSeed, islandCenterX, islandCenterZ, islandRadius, islandBaseY, minX, minZ, width, height, minLayerY, maxLayerY, layers);

        int[] topYs = scanTopSurfaceHeights(width, height, minLayerY, maxLayerY, layers);
        java.util.BitSet connected = connectedTopSurface(width, height, topYs, islandCenterX - minX, islandCenterZ - minZ);
        int[] edgeDistance = connectedEdgeDistance(width, height, connected);
        int[] support = buildFootprintSupport(width, height, topYs, 7);
        long baseSeed = seed ^ ((long) islandNoiseSeed * 1181783497276652981L) ^ 0xA24BAED4963EE407L;

        int targetPeakCount = islandRadius >= 86 ? 6 : islandRadius >= 72 ? 4 : 3;
        int[] peakXs = new int[targetPeakCount];
        int[] peakZs = new int[targetPeakCount];
        double[] peakRadii = new double[targetPeakCount];
        double[] peakAmps = new double[targetPeakCount];
        double minPeakRadius = Math.max(30.0D, (double) islandRadius * 0.22D);
        double maxPeakRadius = Math.min(78.0D, Math.max(minPeakRadius + 6.0D, (double) islandRadius * 0.45D));
        double peakCenterRadius = Math.max(targetPeakCount == 1 ? 8.0D : 12.0D, (double) islandRadius * (targetPeakCount == 1 ? 0.18D : 0.36D));
        double minPeakCenterRadius = targetPeakCount == 1 ? 0.0D : Math.max(10.0D, peakCenterRadius * 0.60D);
        double baseAngle = islandShapeValue(islandNoiseSeed, 0x6A09E667F3BCC909L) * (Math.PI * 2.0D);
        int peakCount = 0;
        for (int i = 0; i < targetPeakCount; i++) {
            double a = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                    baseSeed ^ (0x9E3779B97F4A7C15L + (long) i * 0x632BE59BD9B4E019L),
                    islandCenterX + i * 31,
                    islandCenterZ - i * 57,
                    1.0D / 128.0D,
                    1,
                    0.50D
            );
            double b = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                    baseSeed ^ (0xBF58476D1CE4E5B9L + (long) i * 0x94D049BB133111EBL),
                    islandCenterX - i * 19,
                    islandCenterZ + i * 43,
                    1.0D / 128.0D,
                    1,
                    0.50D
            );
            double c = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                    baseSeed ^ (0x94D049BB133111EBL + (long) i * 0xD6E8FEB86659FD93L),
                    islandCenterX + i * 71,
                    islandCenterZ + i * 17,
                    1.0D / 128.0D,
                    1,
                    0.50D
            );
            double rawPeakRadius = (double) islandRadius * (0.18D + 0.10D * c);
            double peakRadius = Mth.clamp(Math.max(minPeakRadius, rawPeakRadius), minPeakRadius, maxPeakRadius);
            double peakAmp = 24.0D + 8.0D * (0.5D + 0.5D * SkylandsNoise.octaveNoise(
                    baseSeed ^ (0x243F6A8885A308D3L + (long) i * 0xA4093822299F31D0L),
                    islandCenterX + i * 13,
                    islandCenterZ - i * 29,
                    1.0D / 128.0D,
                    1,
                    0.50D
            ));
            boolean placed = false;
            int attempts = targetPeakCount == 1 ? 1 : 12;
            for (int attempt = 0; attempt < attempts; attempt++) {
                double angleJitter = (a - 0.5D) * 0.45D + 0.11D * attempt;
                double angle = baseAngle + (((double) i + angleJitter) / (double) targetPeakCount) * (Math.PI * 2.0D);
                double distFactor = Mth.lerp(Mth.clamp(b + 0.08D * attempt, 0.0D, 1.0D), minPeakCenterRadius, peakCenterRadius);
                int peakX = islandCenterX + (int) Math.round(Math.cos(angle) * distFactor);
                int peakZ = islandCenterZ + (int) Math.round(Math.sin(angle) * distFactor);
                boolean overlaps = false;
                for (int p = 0; p < peakCount; p++) {
                    double dx = peakX - peakXs[p];
                    double dz = peakZ - peakZs[p];
                    double minDistance = Math.max(12.0D, (peakRadius + peakRadii[p]) * 0.82D);
                    if (dx * dx + dz * dz < minDistance * minDistance) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) {
                    continue;
                }
                peakXs[peakCount] = peakX;
                peakZs[peakCount] = peakZ;
                peakRadii[peakCount] = peakRadius;
                peakAmps[peakCount] = peakAmp;
                peakCount++;
                placed = true;
                break;
            }
            if (!placed && peakCount == 0) {
                peakXs[peakCount] = islandCenterX;
                peakZs[peakCount] = islandCenterZ;
                peakRadii[peakCount] = peakRadius;
                peakAmps[peakCount] = peakAmp;
                peakCount++;
            }
        }

        double[] masks = new double[width * height];
        double[] mountainMasks = new double[width * height];
        double[] targetTops = new double[width * height];
        double[] minTops = new double[width * height];
        java.util.Arrays.fill(targetTops, Double.NaN);
        java.util.Arrays.fill(minTops, Double.NaN);

        int minThickness = 12;
        int maxThicknessScan = 64;
        int maxCarveCap = 10;
        double capTop = (double) maxLayerY - 3.0D;

        for (int localZ = 0; localZ < height; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int index = localZ * width + localX;
                int topY = topYs[index];
                if (topY == Integer.MIN_VALUE) {
                    continue;
                }
                if (!connected.get(index)) {
                    continue;
                }
                int distToEdge = edgeDistance[index];
                if (distToEdge < 0) {
                    continue;
                }
                double edgeMask = smoothstep(2.0D, 10.0D, (double) distToEdge);
                double supportMask = smoothstep(0.20D, 0.92D, support[index] / 225.0D);
                double mask = edgeMask * supportMask;
                if (mask <= 0.01D) {
                    continue;
                }
                int x = minX + localX;
                int z = minZ + localZ;
                double dx = x - islandCenterX;
                double dz = z - islandCenterZ;
                double r = Math.sqrt(dx * dx + dz * dz) / Math.max(1.0D, (double) islandRadius);
                double footprint = coneProfile(Mth.clamp(r, 0.0D, 1.0D), 0.0D, 1.0D, 1.35D);
                if (footprint <= 0.0D) {
                    continue;
                }
                double centerMask = smoothstep(0.20D, 0.60D, 1.0D - r);
                mask *= footprint * centerMask;
                mask = Math.pow(mask, 0.65D);
                if (mask <= 0.01D) {
                    continue;
                }
                masks[index] = mask;

                int thickness = 0;
                for (int scan = 0; scan < maxThicknessScan && topY - scan >= minLayerY; scan++) {
                    java.util.BitSet layer = layers[topY - scan - minLayerY];
                    if (layer == null || !layer.get(index)) {
                        break;
                    }
                    thickness++;
                }
                int maxCarve = Math.min(maxCarveCap, Math.max(0, thickness - minThickness));

                double add = 0.0D;
                for (int p = 0; p < peakCount; p++) {
                    double pdx = x - peakXs[p];
                    double pdz = z - peakZs[p];
                    double dist = Math.sqrt(pdx * pdx + pdz * pdz);
                    double t = dist / Math.max(1.0D, peakRadii[p]);
                    if (t >= 1.0D) {
                        continue;
                    }
                    double shoulder = 0.28D;
                    double topDrop = smoothstep(0.0D, shoulder, t);
                    double topFactor = 1.0D - 0.18D * topDrop;
                    double side = 1.0D - smoothstep(shoulder, 1.0D, t);
                    side = Math.pow(side, 1.35D);
                    double s = Mth.clamp(topFactor * side, 0.0D, 1.0D);
                    add += peakAmps[p] * s;
                }

                add *= mask;
                add = Math.min(add, 40.0D);
                double mountainMask = Mth.clamp(add / 40.0D, 0.0D, 1.0D);
                mountainMasks[index] = mountainMask;
                if (mountainMask <= 0.01D) {
                    continue;
                }

                double erosionNoiseA = SkylandsNoise.octaveNoise(baseSeed ^ 0xB7E151628AED2A6BL, x, z, 1.0D / 18.0D, 4, 0.58D);
                double ridge = 1.0D - Math.abs(erosionNoiseA);
                double channel = smoothstep(0.40D, 0.92D, ridge);
                double erosionNoiseB = 0.5D + 0.5D * SkylandsNoise.octaveNoise(baseSeed ^ 0x9E3779B97F4A7C15L, x, z, 1.0D / 22.0D, 3, 0.58D);
                double carve = channel * smoothstep(0.35D, 0.90D, erosionNoiseB) * 20.0D * mask * mountainMask;
                carve = Math.min(carve, add + 8.0D);

                double delta = Math.max(0.0D, add - carve);
                if (delta > 0.0D) {
                    double headroom = capTop - (double) islandBaseY;
                    if (headroom <= 0.0D) {
                        delta = 0.0D;
                    } else {
                        delta = softCap(delta, headroom, 0.70D, 3.0D);
                    }
                }
                double targetTop = Math.max((double) topY, (double) islandBaseY + delta);
                double minTop = (double) topY - (double) maxCarve;
                if (targetTop < minTop) {
                    targetTop = minTop;
                }
                if (targetTop < (double) (minLayerY + 1)) {
                    targetTop = (double) (minLayerY + 1);
                }
                if (targetTop < (double) islandBaseY - 4.0D) {
                    targetTop = (double) islandBaseY - 4.0D;
                }
                targetTops[index] = targetTop;
                minTops[index] = minTop;
            }
        }

        double[] smoothed = new double[targetTops.length];
        for (int pass = 0; pass < 1; pass++) {
            for (int localZ = 0; localZ < height; localZ++) {
                for (int localX = 0; localX < width; localX++) {
                    int index = localZ * width + localX;
                    double value = targetTops[index];
                    if (Double.isNaN(value)) {
                        smoothed[index] = Double.NaN;
                        continue;
                    }
                    double centerMountain = mountainMasks[index];
                    double weightSum = 0.0D;
                    double sum = 0.0D;
                    for (int dz = -1; dz <= 1; dz++) {
                        int nz = localZ + dz;
                        if (nz < 0 || nz >= height) {
                            continue;
                        }
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = localX + dx;
                            if (nx < 0 || nx >= width) {
                                continue;
                            }
                            int nIndex = nz * width + nx;
                            double nValue = targetTops[nIndex];
                            if (Double.isNaN(nValue)) {
                                continue;
                            }
                            double nMountain = mountainMasks[nIndex];
                            if ((centerMountain > 0.03D) != (nMountain > 0.03D)) {
                                continue;
                            }
                            double w = masks[nIndex];
                            if (w <= 0.01D) {
                                continue;
                            }
                            weightSum += w;
                            sum += nValue * w;
                        }
                    }
                    if (weightSum <= 0.0D) {
                        smoothed[index] = Mth.clamp(value, minTops[index], (double) maxLayerY);
                        continue;
                    }
                    smoothed[index] = Mth.clamp(sum / weightSum, minTops[index], (double) maxLayerY);
                }
            }
            double[] tmp = targetTops;
            targetTops = smoothed;
            smoothed = tmp;
        }

        long roundSeed = baseSeed ^ 0x3C6EF372L;
        for (int localZ = 0; localZ < height; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int index = localZ * width + localX;
                int topY = topYs[index];
                if (topY == Integer.MIN_VALUE) {
                    continue;
                }
                double targetTop = targetTops[index];
                if (Double.isNaN(targetTop)) {
                    continue;
                }
                targetTop = Mth.clamp(targetTop, minTops[index], (double) maxLayerY);
                int base = Mth.floor(targetTop);
                double frac = targetTop - (double) base;
                int x = minX + localX;
                int z = minZ + localZ;
                double rnd = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                        roundSeed,
                        x,
                        z,
                        1.0D / 14.0D,
                        1,
                        0.50D
                );
                int newTopY = (frac > 0.0D && rnd < frac) ? base + 1 : base;
                newTopY = Mth.clamp(newTopY, (int) Math.ceil(minTops[index]), maxLayerY);
                if (newTopY > topY) {
                    for (int y = topY + 1; y <= newTopY; y++) {
                        ensureLayer(layers, y - minLayerY, width * height).set(index);
                    }
                } else if (newTopY < topY) {
                    for (int y = topY; y > newTopY; y--) {
                        int layerIndex = y - minLayerY;
                        if (layerIndex < 0 || layerIndex >= layers.length) {
                            continue;
                        }
                        java.util.BitSet layer = layers[layerIndex];
                        if (layer != null) {
                            layer.clear(index);
                        }
                    }
                }
            }
        }
    }

    private void applyPeaksTerrain2DMap(
            int islandNoiseSeed,
            int islandCenterX,
            int islandCenterZ,
            int islandRadius,
            int islandBaseY,
            int minX,
            int minZ,
            int width,
            int height,
            int minLayerY,
            int maxLayerY,
            java.util.BitSet[] layers
    ) {
        applyHillsTerrain2DMap(islandNoiseSeed, islandCenterX, islandCenterZ, islandRadius, islandBaseY, minX, minZ, width, height, minLayerY, maxLayerY, layers);

        int[] topYs = scanTopSurfaceHeights(width, height, minLayerY, maxLayerY, layers);
        java.util.BitSet connected = connectedTopSurface(width, height, topYs, islandCenterX - minX, islandCenterZ - minZ);
        int[] edgeDistance = connectedEdgeDistance(width, height, connected);
        int[] support = buildFootprintSupport(width, height, topYs, 7);
        long baseSeed = seed ^ ((long) islandNoiseSeed * 1181783497276652981L) ^ 0x243F6A8885A308D3L;

        int targetPeakCount = islandRadius >= 86 ? 6 : islandRadius >= 72 ? 4 : 3;
        int[] peakXs = new int[targetPeakCount];
        int[] peakZs = new int[targetPeakCount];
        double[] peakMajorRadii = new double[targetPeakCount];
        double[] peakMinorRadii = new double[targetPeakCount];
        double[] peakAngles = new double[targetPeakCount];
        double[] peakAmps = new double[targetPeakCount];
        double minPeakRadius = Math.max(30.0D, (double) islandRadius * 0.22D);
        double maxPeakRadius = Math.min(78.0D, Math.max(minPeakRadius + 6.0D, (double) islandRadius * 0.45D));
        double peakCenterRadius = Math.max(targetPeakCount == 1 ? 8.0D : 12.0D, (double) islandRadius * (targetPeakCount == 1 ? 0.16D : 0.28D));
        double minPeakCenterRadius = targetPeakCount == 1 ? 0.0D : Math.max(8.0D, peakCenterRadius * 0.45D);
        double baseAngle = islandShapeValue(islandNoiseSeed, 0x6A09E667F3BCC909L) * (Math.PI * 2.0D);
        int peakCount = 0;
        for (int i = 0; i < targetPeakCount; i++) {
            double a = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                    baseSeed ^ (0x9E3779B97F4A7C15L + (long) i * 0x632BE59BD9B4E019L),
                    islandCenterX + i * 31,
                    islandCenterZ - i * 57,
                    1.0D / 128.0D,
                    1,
                    0.50D
            );
            double b = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                    baseSeed ^ (0xBF58476D1CE4E5B9L + (long) i * 0x94D049BB133111EBL),
                    islandCenterX - i * 19,
                    islandCenterZ + i * 43,
                    1.0D / 128.0D,
                    1,
                    0.50D
            );
            double c = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                    baseSeed ^ (0x94D049BB133111EBL + (long) i * 0xD6E8FEB86659FD93L),
                    islandCenterX + i * 71,
                    islandCenterZ + i * 17,
                    1.0D / 128.0D,
                    1,
                    0.50D
            );
            double rawPeakRadius = (double) islandRadius * (0.18D + 0.10D * c);
            double peakRadius = Mth.clamp(Math.max(minPeakRadius, rawPeakRadius), minPeakRadius, maxPeakRadius);
            double peakMinorRadius = peakRadius;
            double peakAngle = (0.5D + 0.5D * SkylandsNoise.octaveNoise(
                    baseSeed ^ (0x8CB92BA72F3D8DD7L + (long) i * 0x9E3779B97F4A7C15L),
                    islandCenterX - i * 37,
                    islandCenterZ + i * 53,
                    1.0D / 112.0D,
                    1,
                    0.50D
            )) * Math.PI;
            double peakAmp = 50.0D + 22.0D * (0.5D + 0.5D * SkylandsNoise.octaveNoise(
                    baseSeed ^ (0x243F6A8885A308D3L + (long) i * 0xA4093822299F31D0L),
                    islandCenterX + i * 13,
                    islandCenterZ - i * 29,
                    1.0D / 128.0D,
                    1,
                    0.50D
            ));

            boolean placed = false;
            int attempts = targetPeakCount == 1 ? 1 : 8;
            for (int attempt = 0; attempt < attempts; attempt++) {
                double angleJitter = (a - 0.5D) * 0.45D + 0.11D * attempt;
                double angle = baseAngle + (((double) i + angleJitter) / (double) targetPeakCount) * (Math.PI * 2.0D);
                double distFactor = Mth.lerp(Mth.clamp(b + 0.08D * attempt, 0.0D, 1.0D), minPeakCenterRadius, peakCenterRadius);
                int peakX = islandCenterX + (int) Math.round(Math.cos(angle) * distFactor);
                int peakZ = islandCenterZ + (int) Math.round(Math.sin(angle) * distFactor);
                boolean overlaps = false;
                for (int p = 0; p < peakCount; p++) {
                    double dx = peakX - peakXs[p];
                    double dz = peakZ - peakZs[p];
                    double minDistance = Math.max(20.0D, (peakRadius + peakMajorRadii[p]) * 1.05D);
                    if (dx * dx + dz * dz < minDistance * minDistance) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) {
                    continue;
                }
                peakXs[peakCount] = peakX;
                peakZs[peakCount] = peakZ;
                peakMajorRadii[peakCount] = peakRadius;
                peakMinorRadii[peakCount] = peakMinorRadius;
                peakAngles[peakCount] = peakAngle;
                peakAmps[peakCount] = peakAmp;
                peakCount++;
                placed = true;
                break;
            }
            if (!placed && peakCount == 0) {
                peakXs[peakCount] = islandCenterX;
                peakZs[peakCount] = islandCenterZ;
                peakMajorRadii[peakCount] = peakRadius;
                peakMinorRadii[peakCount] = peakMinorRadius;
                peakAngles[peakCount] = peakAngle;
                peakAmps[peakCount] = peakAmp;
                peakCount++;
            }
        }

        double[] masks = new double[width * height];
        double[] targetTops = new double[width * height];
        double[] minTops = new double[width * height];
        java.util.Arrays.fill(targetTops, Double.NaN);
        java.util.Arrays.fill(minTops, Double.NaN);

        for (int localZ = 0; localZ < height; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int index = localZ * width + localX;
                int topY = topYs[index];
                if (topY == Integer.MIN_VALUE) {
                    continue;
                }
                if (!connected.get(index)) {
                    continue;
                }
                int distToEdge = edgeDistance[index];
                if (distToEdge < 0) {
                    continue;
                }
                double edgeMask = smoothstep(2.0D, 10.0D, (double) distToEdge);
                double supportMask = smoothstep(0.20D, 0.92D, support[index] / 225.0D);
                double mask = edgeMask * supportMask;
                if (mask <= 0.01D) {
                    continue;
                }
                int x = minX + localX;
                int z = minZ + localZ;
                double dx = x - islandCenterX;
                double dz = z - islandCenterZ;
                double r = Math.sqrt(dx * dx + dz * dz) / Math.max(1.0D, (double) islandRadius);
                double footprint = coneProfile(Mth.clamp(r, 0.0D, 1.0D), 0.0D, 1.0D, 1.35D);
                if (footprint <= 0.0D) {
                    continue;
                }
                double centerMask = smoothstep(0.20D, 0.60D, 1.0D - r);
                mask *= footprint * centerMask;
                mask = Math.pow(mask, 0.65D);
                if (mask <= 0.01D) {
                    continue;
                }
                masks[index] = mask;

                double add = 0.0D;
                for (int p = 0; p < peakCount; p++) {
                    double pdx = x - peakXs[p];
                    double pdz = z - peakZs[p];
                    double cosA = Math.cos(peakAngles[p]);
                    double sinA = Math.sin(peakAngles[p]);
                    double along = pdx * cosA + pdz * sinA;
                    double across = -pdx * sinA + pdz * cosA;
                    double majorRadius = Math.max(1.0D, peakMajorRadii[p]);
                    double minorRadius = Math.max(1.0D, peakMinorRadii[p]);
                    double t = Math.sqrt(
                            (along * along) / (majorRadius * majorRadius)
                                    + (across * across) / (minorRadius * minorRadius)
                    );
                    if (t >= 1.0D) {
                        continue;
                    }
                    double shoulder = 0.28D;
                    double topDrop = smoothstep(0.0D, shoulder, t);
                    double topFactor = 1.0D - 0.18D * topDrop;
                    double side = 1.0D - smoothstep(shoulder, 1.0D, t);
                    side = Math.pow(side, 1.55D);
                    double s = Mth.clamp(topFactor * side, 0.0D, 1.0D);
                    add += peakAmps[p] * s;
                }
                add *= mask;
                if (add <= 0.01D) {
                    continue;
                }

                double delta = add;
                double targetTop = Math.max((double) topY, (double) islandBaseY + delta);
                double minTop = (double) topY;
                if (targetTop < (double) (minLayerY + 1)) {
                    targetTop = (double) (minLayerY + 1);
                }
                targetTops[index] = targetTop;
                minTops[index] = minTop;
            }
        }

        long roundSeed = baseSeed ^ 0x3C6EF372L;
        for (int localZ = 0; localZ < height; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int index = localZ * width + localX;
                int topY = topYs[index];
                if (topY == Integer.MIN_VALUE) {
                    continue;
                }
                double targetTop = targetTops[index];
                if (Double.isNaN(targetTop)) {
                    continue;
                }
                targetTop = Mth.clamp(targetTop, minTops[index], (double) maxLayerY);
                int base = Mth.floor(targetTop);
                double frac = targetTop - (double) base;
                int x = minX + localX;
                int z = minZ + localZ;
                double rnd = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                        roundSeed,
                        x,
                        z,
                        1.0D / 14.0D,
                        1,
                        0.50D
                );
                int newTopY = (frac > 0.0D && rnd < frac) ? base + 1 : base;
                newTopY = Mth.clamp(newTopY, (int) Math.ceil(minTops[index]), maxLayerY);
                if (newTopY > topY) {
                    for (int y = topY + 1; y <= newTopY; y++) {
                        ensureLayer(layers, y - minLayerY, width * height).set(index);
                    }
                } else if (newTopY < topY) {
                    for (int y = topY; y > newTopY; y--) {
                        int layerIndex = y - minLayerY;
                        if (layerIndex < 0 || layerIndex >= layers.length) {
                            continue;
                        }
                        java.util.BitSet layer = layers[layerIndex];
                        if (layer != null) {
                            layer.clear(index);
                        }
                    }
                }
            }
        }
    }

    private void applyMesaTerrain2DMap(
            int islandNoiseSeed,
            int islandCenterX,
            int islandCenterZ,
            int islandRadius,
            int islandBaseY,
            int minX,
            int minZ,
            int width,
            int height,
            int minLayerY,
            int maxLayerY,
            java.util.BitSet[] layers
    ) {
        int[] topYs = scanTopSurfaceHeights(width, height, minLayerY, maxLayerY, layers);
        java.util.BitSet connected = connectedTopSurface(width, height, topYs, islandCenterX - minX, islandCenterZ - minZ);
        int[] edgeDistance = connectedEdgeDistance(width, height, connected);
        int[] support = buildFootprintSupport(width, height, topYs, 6);
        long baseSeed = seed ^ ((long) islandNoiseSeed * 1181783497276652981L) ^ 0xDB4F0B9175AE2165L;

        double[] masks = new double[width * height];
        double[] targetTops = new double[width * height];
        double[] minTops = new double[width * height];
        java.util.Arrays.fill(targetTops, Double.NaN);
        java.util.Arrays.fill(minTops, Double.NaN);

        int minThickness = 12;
        int maxThicknessScan = 84;
        int targetColumnCount = islandRadius >= 86 ? 8 : islandRadius >= 72 ? 6 : 5;
        int[] columnXs = new int[targetColumnCount];
        int[] columnZs = new int[targetColumnCount];
        double[] columnTopRadii = new double[targetColumnCount];
        double[] columnHeights = new double[targetColumnCount];
        double[] columnEdgeScales = new double[targetColumnCount];
        double[] columnRotations = new double[targetColumnCount];
        double[] columnAspectX = new double[targetColumnCount];
        double[] columnAspectZ = new double[targetColumnCount];
        double[] columnShapePowers = new double[targetColumnCount];

        columnXs[0] = islandCenterX;
        columnZs[0] = islandCenterZ;
        columnTopRadii[0] = Math.max(12.0D, (double) islandRadius * 0.22D);
        columnHeights[0] = 17.0D + 4.0D * (0.5D + 0.5D * SkylandsNoise.octaveNoise(
                baseSeed ^ 0x243F6A8885A308D3L,
                islandCenterX,
                islandCenterZ,
                1.0D / 128.0D,
                1,
                0.50D
        ));
        columnEdgeScales[0] = 1.42D;
        columnRotations[0] = islandShapeValue(islandNoiseSeed, 0xA4093822299F31D0L) * Math.PI * 2.0D;
        columnAspectX[0] = 1.02D;
        columnAspectZ[0] = 0.96D;
        columnShapePowers[0] = 5.0D;

        double baseAngle = islandShapeValue(islandNoiseSeed, 0x9E3779B97F4A7C15L) * Math.PI * 2.0D;
        int columnCount = 1;
        for (int i = 1; i < targetColumnCount; i++) {
            double angle = baseAngle + ((double) (i - 1) / (double) Math.max(1, targetColumnCount - 1)) * (Math.PI * 2.0D);
            boolean placed = false;
            for (int attempt = 0; attempt < 12 && !placed; attempt++) {
                long columnSalt = baseSeed ^ (0x94D049BB133111EBL + (long) i * 0x9E3779B97F4A7C15L + (long) attempt * 0x632BE59BD9B4E019L);
                double angleJitter = Mth.lerp(
                        islandShapeValue(islandNoiseSeed, columnSalt ^ 0xD6E8FEB86659FD93L),
                        -0.42D,
                        0.42D
                );
                double orbit = (double) islandRadius * Mth.lerp(
                        islandShapeValue(islandNoiseSeed, columnSalt ^ 0xBF58476D1CE4E5B9L),
                        0.14D,
                        0.46D
                );
                int cx = islandCenterX + roundToInt(Math.cos(angle + angleJitter) * orbit);
                int cz = islandCenterZ + roundToInt(Math.sin(angle + angleJitter) * orbit);
                double topRadius = (double) islandRadius * Mth.lerp(
                        islandShapeValue(islandNoiseSeed, columnSalt ^ 0xBB67AE8584CAA73BL),
                        0.18D,
                        0.30D
                );
                boolean overlaps = false;
                for (int existing = 0; existing < columnCount; existing++) {
                    double dx = (double) cx - (double) columnXs[existing];
                    double dz = (double) cz - (double) columnZs[existing];
                    double minDistance = (topRadius + columnTopRadii[existing]) * 0.42D;
                    if (dx * dx + dz * dz < minDistance * minDistance) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) {
                    continue;
                }
                columnXs[columnCount] = cx;
                columnZs[columnCount] = cz;
                columnTopRadii[columnCount] = topRadius;
                columnHeights[columnCount] = 15.0D + 7.0D * (0.5D + 0.5D * SkylandsNoise.octaveNoise(
                        columnSalt ^ 0x243F6A8885A308D3L,
                        cx,
                        cz,
                        1.0D / 128.0D,
                        1,
                        0.50D
                ));
                columnEdgeScales[columnCount] = Mth.lerp(
                        islandShapeValue(islandNoiseSeed, columnSalt ^ 0x13198A2E03707344L),
                        1.30D,
                        1.52D
                );
                columnRotations[columnCount] = islandShapeValue(islandNoiseSeed, columnSalt ^ 0xA4093822299F31D0L) * Math.PI * 2.0D;
                columnAspectX[columnCount] = Mth.lerp(
                        islandShapeValue(islandNoiseSeed, columnSalt ^ 0x6A09E667F3BCC909L),
                        0.84D,
                        1.28D
                );
                columnAspectZ[columnCount] = Mth.lerp(
                        islandShapeValue(islandNoiseSeed, columnSalt ^ 0x3C6EF372FE94F82AL),
                        0.84D,
                        1.24D
                );
                columnShapePowers[columnCount] = Mth.lerp(
                        islandShapeValue(islandNoiseSeed, columnSalt ^ 0xC2B2AE3D27D4EB4FL),
                        4.2D,
                        6.0D
                );
                columnCount++;
                placed = true;
            }
        }

        for (int localZ = 0; localZ < height; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int index = localZ * width + localX;
                int topY = topYs[index];
                if (topY == Integer.MIN_VALUE || !connected.get(index)) {
                    continue;
                }
                int distToEdge = edgeDistance[index];
                if (distToEdge < 0) {
                    continue;
                }
                double edgeMask = smoothstep(1.0D, 8.0D, (double) distToEdge);
                double supportMask = smoothstep(0.20D, 0.94D, support[index] / 169.0D);
                double mask = edgeMask * supportMask;
                if (mask <= 0.01D) {
                    continue;
                }

                int x = minX + localX;
                int z = minZ + localZ;
                double dx = x - islandCenterX;
                double dz = z - islandCenterZ;
                double r = Math.sqrt(dx * dx + dz * dz) / Math.max(1.0D, (double) islandRadius);
                double footprint = 1.0D - smoothstep(0.80D, 1.02D, r);
                if (footprint <= 0.0D) {
                    continue;
                }
                mask *= footprint;
                masks[index] = mask;

                int thickness = 0;
                for (int dy = 0; dy < maxThicknessScan && topY - dy >= minLayerY; dy++) {
                    java.util.BitSet layer = layers[topY - dy - minLayerY];
                    if (layer == null || !layer.get(index)) {
                        break;
                    }
                    thickness++;
                }
                int maxCarve = Math.min(Math.max(0, thickness - minThickness), 14);
                double terraceNoise = SkylandsNoise.octaveNoise(
                        baseSeed ^ 0xA4093822299F31D0L,
                        x,
                        z,
                        1.0D / 28.0D,
                        2,
                        0.54D
                );
                double capNoise = SkylandsNoise.octaveNoise(
                        baseSeed ^ 0xB7E151628AED2A6BL,
                        x,
                        z,
                        1.0D / 42.0D,
                        1,
                        0.50D
                );

                double topLift0 = 0.0D;
                double topLift1 = 0.0D;
                double topLift2 = 0.0D;
                double bestCapMask = 0.0D;
                double plateauCoverage = 0.0D;
                for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                    double localDx = (double) x - (double) columnXs[columnIndex];
                    double localDz = (double) z - (double) columnZs[columnIndex];
                    double cos = Math.cos(columnRotations[columnIndex]);
                    double sin = Math.sin(columnRotations[columnIndex]);
                    double rx = localDx * cos - localDz * sin;
                    double rz = localDx * sin + localDz * cos;
                    double ax = Math.max(1.0D, columnTopRadii[columnIndex] * columnAspectX[columnIndex]);
                    double az = Math.max(1.0D, columnTopRadii[columnIndex] * columnAspectZ[columnIndex]);
                    double p = columnShapePowers[columnIndex];
                    double q = superellipseDistance(rx, rz, ax, az, p);
                    double edgeScale = columnEdgeScales[columnIndex];
                    if (q >= edgeScale) {
                        continue;
                    }
                    double lift;
                    double capMask;
                    double bodyMask;
                    if (q <= 1.0D) {
                        lift = columnHeights[columnIndex] + capNoise * 0.45D;
                        capMask = 1.0D;
                        bodyMask = 1.0D;
                    } else {
                        double t = (q - 1.0D) / Math.max(1.0E-6D, edgeScale - 1.0D);
                        double wall = Math.pow(1.0D - Mth.clamp(t, 0.0D, 1.0D), 1.35D);
                        lift = 3.0D + wall * (columnHeights[columnIndex] - 3.0D);
                        capMask = 1.0D - smoothstep(0.94D, 1.14D, q);
                        bodyMask = 1.0D - smoothstep(1.0D, edgeScale, q);
                    }
                    plateauCoverage += bodyMask;
                    if (lift > topLift0) {
                        topLift2 = topLift1;
                        topLift1 = topLift0;
                        topLift0 = lift;
                    } else if (lift > topLift1) {
                        topLift2 = topLift1;
                        topLift1 = lift;
                    } else if (lift > topLift2) {
                        topLift2 = lift;
                    }
                    if (capMask > bestCapMask) {
                        bestCapMask = capMask;
                    }
                }
                double bestBridgeLift = 0.0D;
                for (int a = 0; a < columnCount; a++) {
                    for (int b = a + 1; b < columnCount; b++) {
                        double ax = (double) columnXs[a];
                        double az = (double) columnZs[a];
                        double bx = (double) columnXs[b];
                        double bz = (double) columnZs[b];
                        double abx = bx - ax;
                        double abz = bz - az;
                        double abLenSq = abx * abx + abz * abz;
                        if (abLenSq <= 1.0E-6D) {
                            continue;
                        }
                        double spanLimit = (columnTopRadii[a] + columnTopRadii[b]) * 2.30D;
                        if (abLenSq > spanLimit * spanLimit) {
                            continue;
                        }
                        double apx = (double) x - ax;
                        double apz = (double) z - az;
                        double t = Mth.clamp((apx * abx + apz * abz) / abLenSq, 0.0D, 1.0D);
                        double nearestX = ax + abx * t;
                        double nearestZ = az + abz * t;
                        double lateralDx = (double) x - nearestX;
                        double lateralDz = (double) z - nearestZ;
                        double lateral = Math.sqrt(lateralDx * lateralDx + lateralDz * lateralDz);
                        double bridgeWidth = Math.min(columnTopRadii[a], columnTopRadii[b]) * 0.58D + 2.0D;
                        double lateralMask = 1.0D - smoothstep(bridgeWidth * 0.56D, bridgeWidth, lateral);
                        double axialMask = smoothstep(0.06D, 0.22D, t) * (1.0D - smoothstep(0.78D, 0.94D, t));
                        double bridgeMask = lateralMask * axialMask;
                        if (bridgeMask <= 0.0D) {
                            continue;
                        }
                        double bridgeHeight = Math.max(0.0D, Math.min(columnHeights[a], columnHeights[b]) - 2.0D);
                        double bridgeLift = (bridgeHeight + capNoise * 0.20D) * bridgeMask;
                        if (bridgeLift > bestBridgeLift) {
                            bestBridgeLift = bridgeLift;
                        }
                    }
                }
                if (topLift0 <= 0.01D && bestBridgeLift <= 0.01D) {
                    continue;
                }
                double combinedLift = topLift0;
                if (topLift1 > 0.0D) {
                    combinedLift = Math.max(combinedLift, topLift0 * 0.70D + topLift1 * 0.55D);
                }
                if (topLift2 > 0.0D) {
                    combinedLift = Math.max(combinedLift, topLift0 * 0.55D + topLift1 * 0.33D + topLift2 * 0.24D);
                }
                combinedLift = Math.max(combinedLift, bestBridgeLift + topLift0 * 0.15D);
                double overlapMask = Mth.clamp((plateauCoverage - 0.85D) / 1.50D, 0.0D, 1.0D);
                double terracedLift = Math.floor((combinedLift + terraceNoise * 1.35D + 1.9D) / 4.0D) * 4.0D;
                double plateauBlend = Mth.clamp(bestCapMask * 0.70D + overlapMask * 0.45D, 0.0D, 1.0D);
                double finalLift = Mth.lerp(plateauBlend, terracedLift, combinedLift);
                double targetTop = (double) islandBaseY + finalLift * mask;
                double minTop = (double) topY - (double) maxCarve;
                targetTop = Mth.clamp(targetTop, minTop, (double) maxLayerY);
                if (targetTop < (double) (minLayerY + 1)) {
                    targetTop = (double) (minLayerY + 1);
                }
                targetTops[index] = targetTop;
                minTops[index] = minTop;
            }
        }

        double[] smoothed = new double[targetTops.length];
        for (int pass = 0; pass < 0; pass++) {
            for (int localZ = 0; localZ < height; localZ++) {
                for (int localX = 0; localX < width; localX++) {
                    int index = localZ * width + localX;
                    double value = targetTops[index];
                    if (Double.isNaN(value)) {
                        smoothed[index] = Double.NaN;
                        continue;
                    }
                    double weightSum = 0.0D;
                    double sum = 0.0D;
                    for (int dz = -1; dz <= 1; dz++) {
                        int nz = localZ + dz;
                        if (nz < 0 || nz >= height) {
                            continue;
                        }
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = localX + dx;
                            if (nx < 0 || nx >= width) {
                                continue;
                            }
                            int nIndex = nz * width + nx;
                            double nValue = targetTops[nIndex];
                            if (Double.isNaN(nValue)) {
                                continue;
                            }
                            double w = masks[nIndex];
                            if (w <= 0.01D) {
                                continue;
                            }
                            weightSum += w;
                            sum += nValue * w;
                        }
                    }
                    if (weightSum <= 0.0D) {
                        smoothed[index] = Mth.clamp(value, minTops[index], (double) maxLayerY);
                        continue;
                    }
                    smoothed[index] = Mth.clamp(sum / weightSum, minTops[index], (double) maxLayerY);
                }
            }
            double[] tmp = targetTops;
            targetTops = smoothed;
            smoothed = tmp;
        }

        long roundSeed = baseSeed ^ 0x3C6EF372L;
        for (int localZ = 0; localZ < height; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int index = localZ * width + localX;
                int topY = topYs[index];
                if (topY == Integer.MIN_VALUE) {
                    continue;
                }
                double targetTop = targetTops[index];
                if (Double.isNaN(targetTop)) {
                    continue;
                }
                targetTop = Mth.clamp(targetTop, minTops[index], (double) maxLayerY);
                int base = Mth.floor(targetTop);
                double frac = targetTop - (double) base;
                int x = minX + localX;
                int z = minZ + localZ;
                double rnd = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                        roundSeed,
                        x,
                        z,
                        1.0D / 16.0D,
                        1,
                        0.50D
                );
                int newTopY = (frac > 0.0D && rnd < frac) ? base + 1 : base;
                newTopY = Mth.clamp(newTopY, (int) Math.ceil(minTops[index]), maxLayerY);
                if (newTopY > topY) {
                    for (int y = topY + 1; y <= newTopY; y++) {
                        ensureLayer(layers, y - minLayerY, width * height).set(index);
                    }
                } else if (newTopY < topY) {
                    for (int y = topY; y > newTopY; y--) {
                        int layerIndex = y - minLayerY;
                        if (layerIndex < 0 || layerIndex >= layers.length) {
                            continue;
                        }
                        java.util.BitSet layer = layers[layerIndex];
                        if (layer != null) {
                            layer.clear(index);
                        }
                    }
                }
            }
        }
    }

    private void applyVolcanoTerrain2DMap(
            int islandNoiseSeed,
            int islandCenterX,
            int islandCenterZ,
            int islandRadius,
            int islandBaseY,
            int minX,
            int minZ,
            int width,
            int height,
            int minLayerY,
            int maxLayerY,
            java.util.BitSet[] layers
    ) {
        int[] topYs = scanTopSurfaceHeights(width, height, minLayerY, maxLayerY, layers);
        java.util.BitSet connected = connectedTopSurface(width, height, topYs, islandCenterX - minX, islandCenterZ - minZ);
        int[] edgeDistance = connectedEdgeDistance(width, height, connected);
        int[] support = buildFootprintSupport(width, height, topYs, 7);
        long baseSeed = seed ^ ((long) islandNoiseSeed * 1181783497276652981L) ^ 0xA24BAED4963EE407L;

        int maxConnectedDist = 0;
        for (int localZ = 0; localZ < height; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int index = localZ * width + localX;
                int topY = topYs[index];
                if (topY == Integer.MIN_VALUE) {
                    continue;
                }
                if (!connected.get(index)) {
                    continue;
                }
                int x = minX + localX;
                int z = minZ + localZ;
                double dx = x - islandCenterX;
                double dz = z - islandCenterZ;
                int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
                if (dist > maxConnectedDist) {
                    maxConnectedDist = dist;
                }
            }
        }
        double volcanoRadius = Math.max((double) islandRadius, (double) maxConnectedDist + 12.0D);
        int mainConeLength = estimatedConeLength(islandRadius);
        double baseAmp = (double) mainConeLength * 0.30D;

        double craterRadius = 0.0D;
        double craterDepth = 0.0D;
        {
            double rN = 0.5D + 0.5D * SkylandsNoise.octaveNoise(baseSeed ^ 0xB7E151628AED2A6BL, islandCenterX, islandCenterZ, 1.0D / 97.0D, 1, 0.50D);
            double dN = 0.5D + 0.5D * SkylandsNoise.octaveNoise(baseSeed ^ 0x9E3779B97F4A7C15L, islandCenterX, islandCenterZ, 1.0D / 113.0D, 1, 0.50D);
            craterRadius = 5.0D + 2.5D * rN;
            craterDepth = 6.0D + 4.0D * dN;
        }

        int polarBins = 192;
        double[] edgeRadius = new double[polarBins];
        boolean[] edgeSeen = new boolean[polarBins];
        for (int localZ = 0; localZ < height; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int index = localZ * width + localX;
                int topY = topYs[index];
                if (topY == Integer.MIN_VALUE || !connected.get(index)) {
                    continue;
                }
                if (edgeDistance[index] != 0 || support[index] < 36) {
                    continue;
                }
                int x = minX + localX;
                int z = minZ + localZ;
                double dx = (double) x - (double) islandCenterX;
                double dz = (double) z - (double) islandCenterZ;
                double angle = Math.atan2(dz, dx);
                int bin = Mth.floor((angle + Math.PI) * (double) polarBins / (Math.PI * 2.0D));
                if (bin < 0) {
                    bin = 0;
                } else if (bin >= polarBins) {
                    bin = polarBins - 1;
                }
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > edgeRadius[bin]) {
                    edgeRadius[bin] = dist;
                }
                edgeSeen[bin] = true;
            }
        }

        double[] filledEdgeRadius = new double[polarBins];
        boolean anyEdge = false;
        for (int i = 0; i < polarBins; i++) {
            if (edgeSeen[i]) {
                anyEdge = true;
                break;
            }
        }
        if (!anyEdge) {
            java.util.Arrays.fill(filledEdgeRadius, volcanoRadius);
        } else {
            for (int i = 0; i < polarBins; i++) {
                if (edgeSeen[i]) {
                    filledEdgeRadius[i] = edgeRadius[i];
                    continue;
                }
                int left = i;
                while (true) {
                    left = (left - 1 + polarBins) % polarBins;
                    if (edgeSeen[left] || left == i) {
                        break;
                    }
                }
                int right = i;
                while (true) {
                    right = (right + 1) % polarBins;
                    if (edgeSeen[right] || right == i) {
                        break;
                    }
                }
                if (!edgeSeen[left] || !edgeSeen[right]) {
                    filledEdgeRadius[i] = volcanoRadius;
                    continue;
                }
                int dl = (i - left + polarBins) % polarBins;
                int dr = (right - i + polarBins) % polarBins;
                double t = (double) dl / (double) (dl + dr);
                filledEdgeRadius[i] = Mth.lerp(t, edgeRadius[left], edgeRadius[right]);
            }
        }

        int smoothW = 7;
        double[] smoothEdgeRadius = new double[polarBins];
        for (int i = 0; i < polarBins; i++) {
            double sum = 0.0D;
            int n = 0;
            for (int j = -smoothW; j <= smoothW; j++) {
                int k = (i + j + polarBins) % polarBins;
                sum += filledEdgeRadius[k];
                n++;
            }
            smoothEdgeRadius[i] = n > 0 ? sum / (double) n : filledEdgeRadius[i];
        }

        double maxAbsDev = 0.0D;
        for (int i = 0; i < polarBins; i++) {
            double dev = filledEdgeRadius[i] - smoothEdgeRadius[i];
            maxAbsDev = Math.max(maxAbsDev, Math.abs(dev));
        }
        double devScale = 1.0D / Math.max(3.0D, maxAbsDev);
        double[] edgeDevUnit = new double[polarBins];
        for (int i = 0; i < polarBins; i++) {
            double dev = (filledEdgeRadius[i] - smoothEdgeRadius[i]) * devScale;
            double shaped = Math.copySign(Math.pow(Math.abs(dev), 0.70D), dev);
            edgeDevUnit[i] = Mth.clamp(shaped, -1.0D, 1.0D);
        }

        int maxValleys = 12;
        int valleyCount = 0;
        int[] valleyCenterBins = new int[maxValleys];
        double[] valleyStrengths = new double[maxValleys];
        double[] valleyHalfWidths = new double[maxValleys];
        int minValleySpacing = 10;
        for (int i = 0; i < polarBins; i++) {
            int prev = (i - 1 + polarBins) % polarBins;
            int next = (i + 1) % polarBins;
            double v = edgeDevUnit[i];
            if (v > -0.08D || v > edgeDevUnit[prev] || v > edgeDevUnit[next]) {
                continue;
            }
            double strength = Mth.clamp(-v, 0.0D, 1.0D);
            double halfWidth = 5.0D + 7.0D * strength;
            boolean merged = false;
            for (int existing = 0; existing < valleyCount; existing++) {
                int delta = Math.abs(i - valleyCenterBins[existing]);
                delta = Math.min(delta, polarBins - delta);
                if (delta > minValleySpacing) {
                    continue;
                }
                merged = true;
                if (strength > valleyStrengths[existing]) {
                    valleyCenterBins[existing] = i;
                    valleyStrengths[existing] = strength;
                    valleyHalfWidths[existing] = halfWidth;
                }
                break;
            }
            if (merged) {
                continue;
            }
            if (valleyCount < maxValleys) {
                valleyCenterBins[valleyCount] = i;
                valleyStrengths[valleyCount] = strength;
                valleyHalfWidths[valleyCount] = halfWidth;
                valleyCount++;
            } else {
                int weakest = 0;
                for (int existing = 1; existing < valleyCount; existing++) {
                    if (valleyStrengths[existing] < valleyStrengths[weakest]) {
                        weakest = existing;
                    }
                }
                if (strength > valleyStrengths[weakest]) {
                    valleyCenterBins[weakest] = i;
                    valleyStrengths[weakest] = strength;
                    valleyHalfWidths[weakest] = halfWidth;
                }
            }
        }

        double[] targetTops = new double[width * height];
        double[] minTops = new double[width * height];
        java.util.Arrays.fill(targetTops, Double.NaN);
        java.util.Arrays.fill(minTops, Double.NaN);

        double capTop = (double) maxLayerY - 3.0D;
        for (int localZ = 0; localZ < height; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int index = localZ * width + localX;
                int topY = topYs[index];
                if (topY == Integer.MIN_VALUE) {
                    continue;
                }
                if (!connected.get(index)) {
                    continue;
                }
                int distToEdge = edgeDistance[index];
                if (distToEdge < 0) {
                    continue;
                }
                double edgeMask = smoothstep(1.0D, 6.0D, (double) distToEdge);
                double supportMask = smoothstep(0.20D, 0.95D, support[index] / 225.0D);
                double mask = edgeMask * supportMask;
                if (mask <= 0.01D) {
                    continue;
                }

                int x = minX + localX;
                int z = minZ + localZ;
                double dx = x - islandCenterX;
                double dz = z - islandCenterZ;
                double r = Math.sqrt(dx * dx + dz * dz) / Math.max(1.0D, volcanoRadius);
                double footprint = coneProfile(Mth.clamp(r, 0.0D, 1.0D), 0.0D, 1.0D, 1.25D);
                if (footprint <= 0.0D) {
                    continue;
                }

                double sum = 0.0D;
                int samples = 0;
                for (int dzLocal = -1; dzLocal <= 1; dzLocal++) {
                    int nz = localZ + dzLocal;
                    if (nz < 0 || nz >= height) {
                        continue;
                    }
                    for (int dxLocal = -1; dxLocal <= 1; dxLocal++) {
                        int nx = localX + dxLocal;
                        if (nx < 0 || nx >= width) {
                            continue;
                        }
                        int nIdx = nz * width + nx;
                        int nTop = topYs[nIdx];
                        if (nTop == Integer.MIN_VALUE) {
                            continue;
                        }
                        if (!connected.get(nIdx)) {
                            continue;
                        }
                        sum += (double) nTop;
                        samples++;
                    }
                }
                double avg = samples > 0 ? sum / (double) samples : (double) topY;
                double depression = Math.max(0.0D, avg - (double) topY);
                double valley = smoothstep(1.5D, 8.0D, depression);
                double space = smoothstep(4.0D, 18.0D, (double) distToEdge);

                double base = 1.0D - smoothstep(0.0D, 1.0D, Mth.clamp(r, 0.0D, 1.0D));
                double ampNoise = 0.5D + 0.5D * SkylandsNoise.octaveNoise(baseSeed ^ 0x243F6A8885A308D3L, x, z, 1.0D / 220.0D, 2, 0.55D);

                double exponent = Mth.lerp(space, 2.40D, 1.10D) + valley * 1.05D;
                exponent = Mth.clamp(exponent, 1.05D, 4.20D);

                double amp = baseAmp * (0.85D + 0.30D * ampNoise);
                double add = amp * Math.pow(Mth.clamp(base, 0.0D, 1.0D), exponent);
                add *= mask * footprint;
                if (add <= 0.01D) {
                    continue;
                }

                double uplift = add;
                uplift = Math.max(0.0D, uplift);
                if (uplift > 0.0D) {
                    double headroom = capTop - (double) topY;
                    if (headroom <= 0.0D) {
                        uplift = 0.0D;
                    } else {
                        uplift = softCap(uplift, headroom, 0.70D, 3.0D);
                    }
                }

                double distCenter = Math.sqrt(dx * dx + dz * dz);
                double craterT = distCenter / Math.max(0.001D, craterRadius);
                double craterCore = 1.0D - Mth.clamp(craterT, 0.0D, 1.0D);
                double craterMask = smoothstep(0.78D, 0.95D, base);
                double craterDig = craterDepth * craterCore * craterMask;

                double baseSurface = (double) islandBaseY - 1.0D;

                double angle = Math.atan2(dz, dx);
                double u = (angle + Math.PI) * (double) polarBins / (Math.PI * 2.0D);
                int bin0 = Mth.floor(u);
                double frac = u - (double) bin0;
                bin0 = ((bin0 % polarBins) + polarBins) % polarBins;
                int bin1 = (bin0 + 1) % polarBins;
                double valleyRay = 0.0D;
                for (int valleyIndex = 0; valleyIndex < valleyCount; valleyIndex++) {
                    double delta = Math.abs(u - (double) valleyCenterBins[valleyIndex]);
                    delta = Math.min(delta, (double) polarBins - delta);
                    double halfWidth = valleyHalfWidths[valleyIndex];
                    if (delta >= halfWidth) {
                        continue;
                    }
                    double profile = 1.0D - delta / Math.max(0.001D, halfWidth);
                    double contribution = valleyStrengths[valleyIndex] * profile;
                    if (contribution > valleyRay) {
                        valleyRay = contribution;
                    }
                }
                double valleyBottomMask = smoothstep(0.42D, 0.66D, base);
                double valleyTopMask = 1.0D - smoothstep(0.95D, 0.99D, base);
                double valleyBand = valleyBottomMask * valleyTopMask;
                double valleyMidBoost = smoothstep(0.38D, 0.54D, base) * (1.0D - smoothstep(0.90D, 0.96D, base));
                double valleyDepth = valleyRay * Mth.lerp(space, 4.9D, 2.6D) * valleyBand * (0.75D + 1.20D * valleyMidBoost) * mask;

                double targetTop = baseSurface + uplift - valleyDepth - craterDig;
                double minTop = baseSurface - (valleyDepth + craterDepth * craterMask + 1.0D);
                if (targetTop < (double) (minLayerY + 1)) {
                    targetTop = (double) (minLayerY + 1);
                }
                targetTops[index] = targetTop;
                minTops[index] = minTop;
            }
        }

        long roundSeed = baseSeed ^ 0x3C6EF372L;
        for (int localZ = 0; localZ < height; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int index = localZ * width + localX;
                int topY = topYs[index];
                if (topY == Integer.MIN_VALUE) {
                    continue;
                }
                double targetTop = targetTops[index];
                if (Double.isNaN(targetTop)) {
                    continue;
                }
                targetTop = Mth.clamp(targetTop, minTops[index], (double) maxLayerY);
                int base = Mth.floor(targetTop);
                double frac = targetTop - (double) base;
                int x = minX + localX;
                int z = minZ + localZ;
                double rnd = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                        roundSeed,
                        x,
                        z,
                        1.0D / 14.0D,
                        1,
                        0.50D
                );
                int newTopY = (frac > 0.0D && rnd < frac) ? base + 1 : base;
                newTopY = Mth.clamp(newTopY, (int) Math.ceil(minTops[index]), maxLayerY);
                if (newTopY > topY) {
                    for (int y = topY + 1; y <= newTopY; y++) {
                        ensureLayer(layers, y - minLayerY, width * height).set(index);
                    }
                } else if (newTopY < topY) {
                    for (int y = topY; y > newTopY; y--) {
                        int layerIndex = y - minLayerY;
                        if (layerIndex < 0 || layerIndex >= layers.length) {
                            continue;
                        }
                        java.util.BitSet layer = layers[layerIndex];
                        if (layer != null) {
                            layer.clear(index);
                        }
                    }
                }
            }
        }
    }

    private java.util.BitSet connectedTopSurface(int width, int height, int[] topYs, int seedLocalX, int seedLocalZ) {
        java.util.BitSet visited = new java.util.BitSet(width * height);
        int seedIndex = -1;
        if (seedLocalX >= 0 && seedLocalZ >= 0 && seedLocalX < width && seedLocalZ < height) {
            int index = seedLocalZ * width + seedLocalX;
            if (topYs[index] != Integer.MIN_VALUE) {
                seedIndex = index;
            }
        }
        if (seedIndex < 0) {
            int best = -1;
            int bestDist = Integer.MAX_VALUE;
            for (int dz = -6; dz <= 6; dz++) {
                int z = seedLocalZ + dz;
                if (z < 0 || z >= height) {
                    continue;
                }
                for (int dx = -6; dx <= 6; dx++) {
                    int x = seedLocalX + dx;
                    if (x < 0 || x >= width) {
                        continue;
                    }
                    int idx = z * width + x;
                    if (topYs[idx] == Integer.MIN_VALUE) {
                        continue;
                    }
                    int dist = dx * dx + dz * dz;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = idx;
                    }
                }
            }
            seedIndex = best;
        }
        if (seedIndex < 0) {
            return visited;
        }
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        visited.set(seedIndex);
        queue.add(seedIndex);
        while (!queue.isEmpty()) {
            int idx = queue.removeFirst();
            int x = idx % width;
            int z = idx / width;
            int north = z > 0 ? idx - width : -1;
            int south = z + 1 < height ? idx + width : -1;
            int west = x > 0 ? idx - 1 : -1;
            int east = x + 1 < width ? idx + 1 : -1;
            if (north >= 0 && !visited.get(north) && topYs[north] != Integer.MIN_VALUE) {
                visited.set(north);
                queue.add(north);
            }
            if (south >= 0 && !visited.get(south) && topYs[south] != Integer.MIN_VALUE) {
                visited.set(south);
                queue.add(south);
            }
            if (west >= 0 && !visited.get(west) && topYs[west] != Integer.MIN_VALUE) {
                visited.set(west);
                queue.add(west);
            }
            if (east >= 0 && !visited.get(east) && topYs[east] != Integer.MIN_VALUE) {
                visited.set(east);
                queue.add(east);
            }
        }
        return visited;
    }

    private int[] connectedEdgeDistance(int width, int height, java.util.BitSet connected) {
        int[] dist = new int[width * height];
        java.util.Arrays.fill(dist, -1);
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int idx = z * width + x;
                if (!connected.get(idx)) {
                    continue;
                }
                boolean edge = false;
                if (x == 0 || x == width - 1 || z == 0 || z == height - 1) {
                    edge = true;
                } else if (!connected.get(idx - 1) || !connected.get(idx + 1) || !connected.get(idx - width) || !connected.get(idx + width)) {
                    edge = true;
                }
                if (edge) {
                    dist[idx] = 0;
                    queue.add(idx);
                }
            }
        }
        while (!queue.isEmpty()) {
            int idx = queue.removeFirst();
            int x = idx % width;
            int z = idx / width;
            int nextDist = dist[idx] + 1;
            int north = z > 0 ? idx - width : -1;
            int south = z + 1 < height ? idx + width : -1;
            int west = x > 0 ? idx - 1 : -1;
            int east = x + 1 < width ? idx + 1 : -1;
            if (north >= 0 && connected.get(north) && dist[north] < 0) {
                dist[north] = nextDist;
                queue.add(north);
            }
            if (south >= 0 && connected.get(south) && dist[south] < 0) {
                dist[south] = nextDist;
                queue.add(south);
            }
            if (west >= 0 && connected.get(west) && dist[west] < 0) {
                dist[west] = nextDist;
                queue.add(west);
            }
            if (east >= 0 && connected.get(east) && dist[east] < 0) {
                dist[east] = nextDist;
                queue.add(east);
            }
        }
        return dist;
    }

    private int[] scanTopSurfaceHeights(
            int width,
            int height,
            int minLayerY,
            int maxLayerY,
            java.util.BitSet[] layers
    ) {
        int[] topYs = new int[width * height];
        java.util.Arrays.fill(topYs, Integer.MIN_VALUE);
        for (int layerIndex = layers.length - 1; layerIndex >= 0; layerIndex--) {
            java.util.BitSet layer = layers[layerIndex];
            if (layer == null) {
                continue;
            }
            int y = minLayerY + layerIndex;
            for (int bit = layer.nextSetBit(0); bit >= 0; bit = layer.nextSetBit(bit + 1)) {
                if (topYs[bit] == Integer.MIN_VALUE) {
                    topYs[bit] = y;
                }
            }
        }
        return topYs;
    }

    private java.util.BitSet ensureLayer(java.util.BitSet[] layers, int layerIndex, int bitCount) {
        java.util.BitSet layer = layers[layerIndex];
        if (layer == null) {
            layer = new java.util.BitSet(bitCount);
            layers[layerIndex] = layer;
        }
        return layer;
    }

    private int[] buildFootprintSupport(int width, int height, int[] topYs, int radius) {
        int[] support = new int[width * height];
        for (int localZ = 0; localZ < height; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int index = localZ * width + localX;
                if (topYs[index] == Integer.MIN_VALUE) {
                    continue;
                }
                int count = 0;
                for (int dz = -radius; dz <= radius; dz++) {
                    int sampleZ = localZ + dz;
                    if (sampleZ < 0 || sampleZ >= height) {
                        continue;
                    }
                    for (int dx = -radius; dx <= radius; dx++) {
                        int sampleX = localX + dx;
                        if (sampleX < 0 || sampleX >= width) {
                            continue;
                        }
                        int sampleIndex = sampleZ * width + sampleX;
                        if (topYs[sampleIndex] != Integer.MIN_VALUE) {
                            count++;
                        }
                    }
                }
                support[index] = count;
            }
        }
        return support;
    }

    private java.util.BitSet maskBoundary(int width, int height, java.util.BitSet mask) {
        java.util.BitSet boundary = new java.util.BitSet(width * height);
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int index = z * width + x;
                if (!mask.get(index)) {
                    continue;
                }
                boolean edge = x == 0 || x == width - 1 || z == 0 || z == height - 1;
                if (!edge) {
                    edge = !mask.get(index - 1)
                            || !mask.get(index + 1)
                            || !mask.get(index - width)
                            || !mask.get(index + width);
                }
                if (edge) {
                    boundary.set(index);
                }
            }
        }
        return boundary;
    }

    private int[] distanceWithinAllowed(int width, int height, java.util.BitSet allowed, java.util.BitSet sources) {
        int[] dist = new int[width * height];
        java.util.Arrays.fill(dist, -1);
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int source = sources.nextSetBit(0); source >= 0; source = sources.nextSetBit(source + 1)) {
            if (!allowed.get(source)) {
                continue;
            }
            dist[source] = 0;
            queue.add(source);
        }
        while (!queue.isEmpty()) {
            int index = queue.removeFirst();
            int x = index % width;
            int z = index / width;
            int next = dist[index] + 1;
            int north = z > 0 ? index - width : -1;
            int south = z + 1 < height ? index + width : -1;
            int west = x > 0 ? index - 1 : -1;
            int east = x + 1 < width ? index + 1 : -1;
            if (north >= 0 && allowed.get(north) && dist[north] < 0) {
                dist[north] = next;
                queue.add(north);
            }
            if (south >= 0 && allowed.get(south) && dist[south] < 0) {
                dist[south] = next;
                queue.add(south);
            }
            if (west >= 0 && allowed.get(west) && dist[west] < 0) {
                dist[west] = next;
                queue.add(west);
            }
            if (east >= 0 && allowed.get(east) && dist[east] < 0) {
                dist[east] = next;
                queue.add(east);
            }
        }
        return dist;
    }

    private CompositeIslandPlan buildCompositeIslandPlan(
            SkylandsIslands.Island island,
            String terrainType,
            int localCenterX,
            int localCenterZ,
            int localRadius,
            int localBaseY,
            long islandSalt
    ) {
        double terrainRoughnessScale = terrainRoughnessScale(terrainType);
        boolean flatTerrain = isFlatTerrain(terrainType);
        boolean rollingTerrain = isRollingTerrain(terrainType);
        boolean hillsTerrain = isHillsTerrain(terrainType);
        boolean mountainsTerrain = isMountainsTerrain(terrainType);
        boolean peaksTerrain = isPeaksTerrain(terrainType);
        boolean mesaTerrain = isMesaTerrain(terrainType);
        boolean volcanoTerrain = isVolcanoTerrain(terrainType);
        double coneGaussian = islandGaussian(island.noiseSeed(), islandSalt ^ 0xD1B54A32D192ED03L);
        boolean forceMaxConeCount = true;
        int coneCount = forceMaxConeCount
                ? 30
                : Mth.clamp((int) Math.round(14.0D + coneGaussian * 4.0D), 7, 30);
        double maxOrbit = Math.max(1.0D, localRadius * 0.62D);
        int coneCapacity = Math.max(coneCount * 4, 64);
        int[] coneCenterXs = new int[coneCapacity];
        int[] coneCenterZs = new int[coneCapacity];
        int[] coneRadii = new int[coneCapacity];
        double[] coneOrbits = new double[coneCapacity];
        long[] coneSalts = new long[coneCapacity];

        coneCenterXs[0] = localCenterX;
        coneCenterZs[0] = localCenterZ;
        coneRadii[0] = Math.max(7, Mth.ceil(localRadius * Mth.lerp(islandShapeValue(island.noiseSeed(), islandSalt ^ 0xBB67AE85L), 0.38D, 0.62D)));
        coneOrbits[0] = 0.0D;
        coneSalts[0] = islandSalt ^ 0x94D049BB133111EBL;

        int mainConeLength = Math.max(6, Mth.ceil((double) coneRadii[0]
                * Mth.lerp(islandShapeValue(island.noiseSeed(), coneSalts[0] ^ 0xA54FF53AL), 1.30D, 1.85D)
                * 1.2D));
        double decayWidth = Math.max(1.0D, 4.0D * (double) mainConeLength);
        double minRadiusMultiplier = 0.10D;
        double decayPowerRadius = 3.0D;

        int shieldConeCount = Math.min(coneCapacity - 1,
                4 + Mth.floor(islandShapeValue(island.noiseSeed(), islandSalt ^ 0x243F6A8885A308D3L) * 2.0D));
        int filled = 1;
        for (int shieldIndex = 0; shieldIndex < shieldConeCount && filled < coneCapacity; shieldIndex++) {
            long shieldSalt = islandSalt
                    ^ 0xC6A4A7935BD1E995L
                    ^ ((long) (shieldIndex + 1) * 0x9E3779B97F4A7C15L);
            double angleStep = (Math.PI * 2.0D) / (double) shieldConeCount;
            double baseAngle = angleStep * (double) shieldIndex;
            double angleJitter = Mth.lerp(
                    islandShapeValue(island.noiseSeed(), shieldSalt ^ 0xD6E8FEB86659FD93L),
                    -0.24D,
                    0.24D
            );
            double angle = baseAngle + angleJitter;
            double orbit = coneRadii[0] * Mth.lerp(
                    islandShapeValue(island.noiseSeed(), shieldSalt ^ 0xA4093822299F31D0L),
                    0.57D,
                    0.86D
            );
            int shieldRadius = Math.max(4, Mth.ceil(coneRadii[0] * Mth.lerp(
                    islandShapeValue(island.noiseSeed(), shieldSalt ^ 0x13198A2E03707344L),
                    0.72D,
                    0.88D
            )));
            int shieldCenterX = localCenterX + roundToInt(Math.cos(angle) * orbit);
            int shieldCenterZ = localCenterZ + roundToInt(Math.sin(angle) * orbit);
            coneCenterXs[filled] = shieldCenterX;
            coneCenterZs[filled] = shieldCenterZ;
            coneRadii[filled] = shieldRadius;
            coneOrbits[filled] = Math.sqrt(
                    (double) ((shieldCenterX - localCenterX) * (shieldCenterX - localCenterX))
                            + (double) ((shieldCenterZ - localCenterZ) * (shieldCenterZ - localCenterZ))
            );
            coneSalts[filled] = shieldSalt;
            filled++;
        }

        int waveStart = 0;
        int waveEnd = filled;
        while (waveStart < waveEnd && filled < coneCount && filled < coneCapacity) {
            for (int head = waveStart; head < waveEnd && filled < coneCapacity; head++) {
                int parentX = coneCenterXs[head];
                int parentZ = coneCenterZs[head];
                int parentRadius = coneRadii[head];
                long parentSalt = coneSalts[head];

                int desiredChildren = 4 + Mth.floor(islandShapeValue(island.noiseSeed(), parentSalt ^ 0x6A09E667L) * 4.0D);
                int spawnCount = desiredChildren;

                double toCenterX = (double) (parentX - localCenterX);
                double toCenterZ = (double) (parentZ - localCenterZ);
                double toCenterLen = Math.sqrt(toCenterX * toCenterX + toCenterZ * toCenterZ);
                double baseDirX;
                double baseDirZ;
                if (toCenterLen < 1.0E-3D) {
                    double a = islandShapeValue(island.noiseSeed(), parentSalt ^ 0x9E3779B97F4A7C15L) * (Math.PI * 2.0D);
                    baseDirX = Math.cos(a);
                    baseDirZ = Math.sin(a);
                } else {
                    baseDirX = toCenterX / toCenterLen;
                    baseDirZ = toCenterZ / toCenterLen;
                }

                for (int childIndex = 0; childIndex < spawnCount && filled < coneCapacity; childIndex++) {
                    long coneSalt = islandSalt
                            ^ ((long) (head + 1) * 0x94D049BB133111EBL)
                            ^ ((long) (childIndex + 1) * 0x9E3779B97F4A7C15L)
                            ^ ((long) (filled + 1) * 0xD1B54A32D192ED03L);

                    double sizeSample = islandShapeValue(island.noiseSeed(), coneSalt ^ 0xBB67AE85L);
                    double radiusScale = Mth.lerp(sizeSample, 0.45D, 0.70D);
                    int radiusBase = Math.max(3, Mth.ceil((double) parentRadius * radiusScale));

                    double distSample = islandShapeValue(island.noiseSeed(), coneSalt ^ 0xD6E8FEB86659FD93L);
                    double dist = (double) parentRadius * Mth.lerp(distSample, 0.62D, 1.05D);

                    int centerX = parentX;
                    int centerZ = parentZ;
                    double baseOrbit = Math.sqrt((double) ((parentX - localCenterX) * (parentX - localCenterX) + (parentZ - localCenterZ) * (parentZ - localCenterZ)));
                    double estimateCenterDistance = baseOrbit + dist;
                    double decayX = Mth.clamp(estimateCenterDistance / decayWidth, 0.0D, 1.0D);
                    double sizeFalloff = Math.pow(decayX, decayPowerRadius);
                    double radiusMultiplier = Mth.lerp(sizeFalloff, 1.0D, minRadiusMultiplier);
                    radiusBase = Math.max(3, Mth.ceil((double) radiusBase * radiusMultiplier));
                    int radius = radiusBase;
                    boolean placed = false;
                    double minDistanceCoeff = 0.80D;
                    for (int fitPass = 0; fitPass < 3 && !placed; fitPass++) {
                        radius = radiusBase;
                        double maxCenterDist = Math.max(0.0D, localRadius * 0.92D - (double) radius);
                        double scaledDist = Math.min(dist, maxCenterDist + (double) parentRadius);
                        for (int attempt = 0; attempt < 12; attempt++) {
                            double randAngle = islandShapeValue(island.noiseSeed(), coneSalt ^ ((long) (attempt + 1) * 0xA4093822299F31D0L)) * (Math.PI * 2.0D);
                            double randDirX = Math.cos(randAngle);
                            double randDirZ = Math.sin(randAngle);
                            double mixX = baseDirX * 0.65D + randDirX * 0.35D;
                            double mixZ = baseDirZ * 0.65D + randDirZ * 0.35D;
                            double mixLen = Math.sqrt(mixX * mixX + mixZ * mixZ);
                            if (mixLen < 1.0E-3D) {
                                continue;
                            }
                            mixX /= mixLen;
                            mixZ /= mixLen;

                            double distJitter = Mth.lerp(
                                    islandShapeValue(island.noiseSeed(), coneSalt ^ ((long) (attempt + 1) * 0xBF58476D1CE4E5B9L)),
                                    0.85D,
                                    1.05D
                            );
                            double candidateDist = scaledDist * distJitter;
                            int candidateX = parentX + roundToInt(mixX * candidateDist);
                            int candidateZ = parentZ + roundToInt(mixZ * candidateDist);

                            int dcx = candidateX - localCenterX;
                            int dcz = candidateZ - localCenterZ;
                            double dCenter = Math.sqrt((double) (dcx * dcx + dcz * dcz));
                            if (dCenter > maxCenterDist && dCenter > 1.0E-3D) {
                                double s = maxCenterDist / dCenter;
                                candidateX = localCenterX + roundToInt((double) dcx * s);
                                candidateZ = localCenterZ + roundToInt((double) dcz * s);
                            }

                            boolean separated = true;
                            for (int i = 0; i < filled; i++) {
                                int dx = candidateX - coneCenterXs[i];
                                int dz = candidateZ - coneCenterZs[i];
                                double actualDistance = Math.sqrt((double) (dx * dx + dz * dz));
                                double minDistance = Math.max(6.0D, minDistanceCoeff * (double) (radius + coneRadii[i]));
                                if (actualDistance < minDistance) {
                                    separated = false;
                                    break;
                                }
                            }
                            if (!separated) {
                                continue;
                            }

                            centerX = candidateX;
                            centerZ = candidateZ;
                            placed = true;
                            break;
                        }
                        minDistanceCoeff *= 0.93D;
                    }
                    if (!placed) {
                        double fallbackAngle = islandShapeValue(island.noiseSeed(), coneSalt ^ 0x7F4A7C15L) * (Math.PI * 2.0D);
                        centerX = parentX + roundToInt(Math.cos(fallbackAngle) * dist);
                        centerZ = parentZ + roundToInt(Math.sin(fallbackAngle) * dist);
                    }

                    coneCenterXs[filled] = centerX;
                    coneCenterZs[filled] = centerZ;
                    coneRadii[filled] = radius;
                    coneSalts[filled] = coneSalt;
                    double ox = (double) (centerX - localCenterX);
                    double oz = (double) (centerZ - localCenterZ);
                    coneOrbits[filled] = Math.sqrt(ox * ox + oz * oz);
                    filled++;
                }
            }
            waveStart = waveEnd;
            waveEnd = filled;
        }

        return new CompositeIslandPlan(
                localCenterX,
                localCenterZ,
                localRadius,
                localBaseY,
                maxOrbit,
                decayWidth,
                terrainRoughnessScale,
                flatTerrain,
                rollingTerrain,
                hillsTerrain,
                mountainsTerrain,
                peaksTerrain,
                mesaTerrain,
                volcanoTerrain,
                coneCount,
                coneCenterXs,
                coneCenterZs,
                coneRadii,
                coneOrbits,
                coneSalts
        );
    }

    private CompositeConeInfo describeCompositeCone(
            CompositeIslandPlan plan,
            SkylandsIslands.Island island,
            int coneIndex
    ) {
        boolean centerCone = coneIndex == 0;
        long coneSalt = plan.coneSalts()[coneIndex];
        int coneCenterX = plan.coneCenterXs()[coneIndex];
        int coneCenterZ = plan.coneCenterZs()[coneIndex];
        int coneRadius = plan.coneRadii()[coneIndex];
        double coneOrbit = plan.coneOrbits()[coneIndex];
        double orbitNorm = centerCone ? 0.0D : Mth.clamp(coneOrbit / plan.maxOrbit(), 0.0D, 1.0D);
        double decayX = centerCone ? 0.0D : Mth.clamp(coneOrbit / plan.decayWidth(), 0.0D, 1.0D);

        double coneExponent = centerCone
                ? Mth.lerp(islandShapeValue(island.noiseSeed(), coneSalt ^ 0x3C6EF372L), 0.95D, 1.35D)
                : Mth.lerp(islandShapeValue(island.noiseSeed(), coneSalt ^ 0x3C6EF372L), 1.15D, 1.70D);
        double lengthScale = centerCone
                ? Mth.lerp(islandShapeValue(island.noiseSeed(), coneSalt ^ 0xA54FF53AL), 1.30D, 1.85D)
                : Mth.lerp(islandShapeValue(island.noiseSeed(), coneSalt ^ 0xA54FF53AL), 1.10D, 1.60D);
        double lengthFalloff = Math.pow(decayX, 3.0D);
        double lengthMultiplierBase = centerCone ? 1.0D : Mth.lerp(lengthFalloff, 1.0D, 0.15D);
        double lengthMultiplierJitter = Mth.lerp(
                islandShapeValue(island.noiseSeed(), coneSalt ^ 0x243F6A8885A308D3L),
                0.70D,
                1.0D
        );
        double lengthMultiplier = Mth.clamp(lengthMultiplierBase * lengthMultiplierJitter, 0.15D, 1.0D);
        int coneLength = Math.max(6, Mth.ceil(coneRadius * lengthScale * lengthMultiplier * 1.2D));
        int maxTopY = getMinY() + getGenDepth() - 8;
        int topJitter = plan.flatTerrain() || plan.rollingTerrain() || plan.mesaTerrain()
                ? 0
                : Mth.floor(Mth.lerp(
                islandShapeValue(island.noiseSeed(), coneSalt ^ 0xC2B2AE3DL),
                -1.0D,
                1.0D
        ));
        int orbitDrop = centerCone ? 0 : Mth.floor(orbitNorm * 1.5D);
        int componentDrop = centerCone
                ? 0
                : Mth.floor(Mth.lerp(islandShapeValue(island.noiseSeed(), coneSalt ^ 0x13198A2E03707344L), 0.0D, 1.0D));
        int coneTopYRaw = plan.localBaseY() + topJitter - orbitDrop - componentDrop;
        int topY = Mth.clamp(coneTopYRaw, getMinY() + 1, maxTopY);
        int minRough = (plan.flatTerrain() || plan.mesaTerrain()) ? 0 : 2;
        int maxRough = Math.max(minRough, Mth.floor((2.0D + coneRadius * 0.10D) * plan.terrainRoughnessScale()));
        int minBottomY = Mth.clamp(coneTopYRaw - coneLength - maxRough, getMinY() + 1, topY - 1);

        return new CompositeConeInfo(
                coneCenterX,
                coneCenterZ,
                coneRadius,
                coneSalt,
                coneTopYRaw,
                topY,
                coneLength,
                coneExponent,
                maxRough,
                minBottomY
        );
    }

    private void rasterizeCompositeCone(
            CompositeIslandPlan plan,
            SkylandsIslands.Island island,
            CompositeConeInfo coneInfo,
            int minX,
            int minZ,
            int width,
            int height,
            int minLayerY,
            int maxLayerY,
            java.util.BitSet[] layers
    ) {
        int sampleMinX = Math.max(minX, coneInfo.coneCenterX() - coneInfo.coneRadius());
        int sampleMaxX = Math.min(minX + width - 1, coneInfo.coneCenterX() + coneInfo.coneRadius());
        int sampleMinZ = Math.max(minZ, coneInfo.coneCenterZ() - coneInfo.coneRadius());
        int sampleMaxZ = Math.min(minZ + height - 1, coneInfo.coneCenterZ() + coneInfo.coneRadius());
        for (int z = sampleMinZ; z <= sampleMaxZ; z++) {
            for (int x = sampleMinX; x <= sampleMaxX; x++) {
                double dx = x - coneInfo.coneCenterX();
                double dz = z - coneInfo.coneCenterZ();
                double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
                double radial = horizontalDistance / Math.max(1.0D, coneInfo.coneRadius());
                if (radial >= 1.0D) {
                    continue;
                }

                double coneMask = coneProfile(radial, 0.0D, 1.0D, coneInfo.coneExponent());
                double groove = 0.0D;
                int below = Math.max(1, Mth.ceil(coneInfo.coneLength() * coneMask - groove));
                double roughNoise = SkylandsNoise.octaveNoise(
                        seed ^ ((long) island.noiseSeed() * 1181783497276652981L) ^ coneInfo.coneSalt() ^ 0xB9C8D1A9L,
                        x,
                        z,
                        1.0D / 10.0D,
                        2,
                        0.52D
                );
                double roughShape = Math.pow(Mth.clamp(0.5D + roughNoise * 0.5D, 0.0D, 1.0D), 2.2D);
                double roughMask = Math.pow(coneMask, 0.55D);
                int roughExtra = Mth.floor(roughShape * roughMask * (double) coneInfo.maxRough());
                int bottomY = Mth.clamp(coneInfo.coneTopYRaw() - below - roughExtra, getMinY() + 1, coneInfo.topY() - 1);
                int topY = coneInfo.topY();

                int localX = x - minX;
                int localZ = z - minZ;
                int index = localZ * width + localX;
                int startLayer = Math.max(bottomY, minLayerY) - minLayerY;
                int endLayer = Math.min(topY, maxLayerY) - minLayerY;
                for (int layerIndex = startLayer; layerIndex <= endLayer; layerIndex++) {
                    ensureLayer(layers, layerIndex, width * height).set(index);
                }
            }
        }
    }

    private boolean hasEnoughIslandGap(int centerX, int centerZ, int radius, List<IslandPlacement> placedIslands) {
        for (IslandPlacement placed : placedIslands) {
            double dx = centerX - placed.centerX();
            double dz = centerZ - placed.centerZ();
            double minDistance = radius + placed.radius() + 0.35D * Math.max(radius, placed.radius());
            if (dx * dx + dz * dz < minDistance * minDistance) {
                return false;
            }
        }
        return true;
    }

    private String terrainTypeLabel(String terrainType) {
        String normalized = terrainType == null ? "default" : terrainType.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "平坦" -> "flat";
            case "起伏" -> "rolling";
            case "山丘" -> "hills";
            case "多山" -> "mountains";
            case "高峰" -> "peaks";
            case "平顶山" -> "mesa";
            case "火山" -> "volcano";
            default -> normalized;
        };
    }

    private boolean isFlatTerrain(String terrainType) {
        return "flat".equals(terrainTypeLabel(terrainType));
    }

    private boolean isRollingTerrain(String terrainType) {
        return "rolling".equals(terrainTypeLabel(terrainType));
    }

    private boolean isHillsTerrain(String terrainType) {
        return "hills".equals(terrainTypeLabel(terrainType));
    }

    private boolean isMountainsTerrain(String terrainType) {
        return "mountains".equals(terrainTypeLabel(terrainType));
    }

    private boolean isPeaksTerrain(String terrainType) {
        return "peaks".equals(terrainTypeLabel(terrainType));
    }

    private boolean isMesaTerrain(String terrainType) {
        return "mesa".equals(terrainTypeLabel(terrainType));
    }

    private boolean isVolcanoTerrain(String terrainType) {
        return "volcano".equals(terrainTypeLabel(terrainType));
    }

    private double coneProfile(double radial, double inner, double outer, double exponent) {
        if (radial <= inner) {
            return 1.0D;
        }
        if (radial >= outer) {
            return 0.0D;
        }
        double t = (radial - inner) / Math.max(1.0E-6D, (outer - inner));
        double v = 1.0D - t;
        return exponent <= 1.0D ? v : Math.pow(v, exponent);
    }

    private double islandGaussian(int islandNoiseSeed, long salt) {
        double u1 = Math.max(1.0E-9D, islandShapeValue(islandNoiseSeed, salt ^ 0x9E3779B97F4A7C15L));
        double u2 = islandShapeValue(islandNoiseSeed, salt ^ 0xBB67AE8584CAA73BL);
        return Math.sqrt(-2.0D * Math.log(u1)) * Math.cos(u2 * (Math.PI * 2.0D));
    }

    private static int roundToInt(double value) {
        return (int) Math.floor(value + (value >= 0.0D ? 0.5D : -0.5D));
    }

    private BlockState baseIslandInteriorState(SkylandsColumnSegment segment, int x, int y, int z) {
        return segment.islandBiome().resolvedSubsurfaceState();
    }

    private BlockState baseIslandSurfaceState(SkylandsColumnSegment segment, int x, int y, int z, int topY) {
        return segment.surfaceState();
    }

    private BlockState surfaceLayerStateAt(SkylandsColumnSegment segment, int topY, int y) {
        List<SkylandsIslandBiomeDefinition.SurfaceLayerDefinition> layers = segment.islandBiome().surfaceLayers();
        if (layers == null || layers.isEmpty()) {
            return null;
        }
        int depthFromTop = topY - y;
        if (depthFromTop < 0) {
            return null;
        }
        int cursor = 0;
        for (SkylandsIslandBiomeDefinition.SurfaceLayerDefinition layer : layers) {
            int depth = layer.clampedDepth();
            if (depthFromTop < cursor + depth) {
                return layer.state();
            }
            cursor += depth;
        }
        return null;
    }

    private void applySubsurfaceDetailColumn(ChunkAccess chunk, SkylandsColumnSegment segment, int x, int z, int topY) {
        BlockState detailState = segment.islandBiome().subsurfaceDetailState();
        if (detailState == null) {
            return;
        }
        for (int y = segment.bottomY(); y <= topY; y++) {
            if (usesDeepMaterial(segment, topY, x, y, z)) {
                continue;
            }
            double cluster = SkylandsNoise.octaveNoise(
                    seed ^ ((long) segment.islandNoiseSeed() * 1181783497276652981L) ^ 0x9E3779B97F4A7C15L,
                    x,
                    z,
                    1.0D / 24.0D,
                    2,
                    0.55D
            );
            double baseChance = 0.025D + Math.max(0.0D, cluster) * 0.07D;
            if (blockSelectionValue(segment.islandNoiseSeed(), 0xA54FF53AL, x, y, z) < baseChance) {
                chunk.setBlockState(new BlockPos(x, y, z), detailState, false);
            }
        }
    }

    private void applySurfaceDetailColumn(
            ChunkAccess chunk,
            SkylandsColumnSegment segment,
            int x,
            int z,
            int topY,
            int shellBottomY
    ) {
        SkylandsIslandBiomeDefinition.SurfaceDetailDefinition detail = segment.islandBiome().surfaceDetail();
        if (detail == null || !detail.isValid()) {
            BlockState legacyState = segment.islandBiome().surfaceDetailState();
            if (legacyState == null) {
                return;
            }
            detail = new SkylandsIslandBiomeDefinition.SurfaceDetailDefinition(
                    SkylandsIslandBiomeDefinition.SurfaceDetailType.SURFACE_PATCH,
                    List.of(legacyState),
                    null,
                    1.0D,
                    64
            );
        }
        if (detail.type() == SkylandsIslandBiomeDefinition.SurfaceDetailType.RANDOM_REPLACE) {
            applySurfaceDetailRandomReplace(chunk, segment, detail, x, topY, z);
            return;
        }
        if (detail.type() == SkylandsIslandBiomeDefinition.SurfaceDetailType.BLOB) {
            applySurfaceDetailBlob(chunk, segment, detail, x, topY, z, shellBottomY);
            return;
        }
        if (detail.type() == SkylandsIslandBiomeDefinition.SurfaceDetailType.BULB) {
            applySurfaceDetailBulb(chunk, segment, detail, x, topY, z);
            return;
        }
        if (detail.type() == SkylandsIslandBiomeDefinition.SurfaceDetailType.SURFACE_PATCH) {
            applySurfaceDetailSurfacePatch(chunk, segment, detail, x, topY, z);
        }
    }

    private void applySurfaceDetailRandomReplace(
            ChunkAccess chunk,
            SkylandsColumnSegment segment,
            SkylandsIslandBiomeDefinition.SurfaceDetailDefinition detail,
            int x,
            int topY,
            int z
    ) {
        BlockPos pos = new BlockPos(x, topY, z);
        BlockState current = chunk.getBlockState(pos);
        BlockState target = detail.targetState();
        if (target != null) {
            if (!current.equals(target)) {
                return;
            }
        } else if (!current.equals(segment.surfaceState()) && !current.equals(segment.islandBiome().resolvedSurfaceState())) {
            return;
        }
        int blobSize = detail.clampedBlobSize();
        if (blobSize <= 1) {
            if (blockSelectionValue(segment.islandNoiseSeed(), 0xA6C3F2B7L, x, 0, z) > detail.clampedRatio()) {
                return;
            }
            List<BlockState> states = detail.states();
            int stateIndex = Mth.clamp(
                    Mth.floor(blockSelectionValue(segment.islandNoiseSeed(), 0x1F83D9ABL, x, 0, z) * (double) states.size()),
                    0,
                    states.size() - 1
            );
            chunk.setBlockState(pos, states.get(stateIndex), false);
            return;
        }
        double baseRadius = Math.sqrt((double) blobSize / Math.PI);
        double r = Math.max(3.5D, baseRadius * 1.50D);
        int checkRange = Math.max(0, Mth.floor(r / 16.0D)) + 1;
        int centerCellX = Math.floorDiv(x, 16);
        int centerCellZ = Math.floorDiv(z, 16);
        for (int dcx = -checkRange; dcx <= checkRange; dcx++) {
            for (int dcz = -checkRange; dcz <= checkRange; dcz++) {
                int ncx = centerCellX + dcx;
                int ncz = centerCellZ + dcz;
                long hash = mix64((long) segment.islandNoiseSeed() ^ (long) ncx * 0xA6C3F2B7L ^ (long) ncz * 0xB492B66FL);
                double roll = ((double) (hash & 0x7FFFFFFFFFFFFFFFL)) / (double) 0x7FFFFFFFFFFFFFFFL;
                if (roll > detail.clampedRatio()) {
                    continue;
                }
                long posHash = mix64(hash ^ 0x6A09E667L);
                double cx = (double) ncx * 16.0 + ((double) (posHash & 0xFFFF)) / 65535.0 * 16.0;
                double cz = (double) ncz * 16.0 + ((double) ((posHash >> 16) & 0xFFFF)) / 65535.0 * 16.0;
                int lobeCount = 2 + Mth.floor(blockSelectionValue(segment.islandNoiseSeed(), 0x5E8A1F4DL, ncx, 0, ncz) * 3.0D);
                for (int lobe = 0; lobe < lobeCount; lobe++) {
                    long lobeHash = mix64(posHash ^ (long) lobe * 0xD3B2F1A5L);
                    double lobeAngle = ((double) ((lobeHash >> 8) & 0xFFFF)) / 65535.0 * Math.PI * 2.0D;
                    double lobeDist = baseRadius * Mth.lerp(((double) ((lobeHash >> 24) & 0xFFFF)) / 65535.0, 0.05D, 0.50D);
                    double lcx = cx + Math.cos(lobeAngle) * lobeDist;
                    double lcz = cz + Math.sin(lobeAngle) * lobeDist;
                    double lobeScale = Mth.lerp(((double) (lobeHash & 0xFFFF)) / 65535.0, 0.45D, 0.85D);
                    double lrx = Math.max(1.0D, baseRadius * lobeScale * Mth.lerp(blockSelectionValue(segment.islandNoiseSeed(), 0x4CF5AD43L, ncx, lobe, ncz), 0.60D, 1.40D));
                    double lrz = Math.max(1.0D, baseRadius * lobeScale * Mth.lerp(blockSelectionValue(segment.islandNoiseSeed(), 0xD1B54A32L, ncx, lobe, ncz), 0.60D, 1.40D));
                    double nx = ((double) x - lcx) / lrx;
                    double nz = ((double) z - lcz) / lrz;
                    if (nx * nx + nz * nz > 1.0D) {
                        continue;
                    }
                    List<BlockState> states = detail.states();
                    int stateIndex = Mth.clamp(
                            Mth.floor(blockSelectionValue(segment.islandNoiseSeed(), 0x1F83D9ABL, ncx, lobe, ncz) * (double) states.size()),
                            0,
                            states.size() - 1
                    );
                    chunk.setBlockState(pos, states.get(stateIndex), false);
                    return;
                }
            }
        }
    }

    private void applySurfaceDetailSurfacePatch(
            ChunkAccess chunk,
            SkylandsColumnSegment segment,
            SkylandsIslandBiomeDefinition.SurfaceDetailDefinition detail,
            int x,
            int topY,
            int z
    ) {
        BlockPos pos = new BlockPos(x, topY, z);
        BlockState current = chunk.getBlockState(pos);
        BlockState target = detail.targetState();
        if (target != null) {
            if (!current.equals(target)) {
                return;
            }
        } else if (!current.equals(segment.surfaceState()) && !current.equals(segment.islandBiome().resolvedSurfaceState())) {
            return;
        }
        int volume = detail.clampedBlobSize();
        double baseRadius = Math.sqrt((double) volume / Math.PI);
        double r = Math.max(2.5D, baseRadius * 1.50D);
        int checkRange = Mth.floor(r / 16.0D) + 1;
        int centerChunkX = Math.floorDiv(x, 16);
        int centerChunkZ = Math.floorDiv(z, 16);
        for (int dcx = -checkRange; dcx <= checkRange; dcx++) {
            for (int dcz = -checkRange; dcz <= checkRange; dcz++) {
                int ncx = centerChunkX + dcx;
                int ncz = centerChunkZ + dcz;
                long hash = mix64((long) segment.islandNoiseSeed() ^ (long) ncx * 0x9E3779B9L ^ (long) ncz * 0x517CC1B7L);
                double roll = ((double) (hash & 0x7FFFFFFFFFFFFFFFL)) / (double) 0x7FFFFFFFFFFFFFFFL;
                if (roll > detail.clampedRatio()) {
                    continue;
                }
                long posHash = mix64(hash ^ 0x3C6EF372L);
                double cx = (double) ncx * 16.0 + ((double) (posHash & 0xFFFF)) / 65535.0 * 16.0;
                double cz = (double) ncz * 16.0 + ((double) ((posHash >> 16) & 0xFFFF)) / 65535.0 * 16.0;
                double rx = Math.max(1.5D, baseRadius * Mth.lerp(blockSelectionValue(segment.islandNoiseSeed(), 0x4CF5AD43L, ncx, 0, ncz), 0.60D, 1.50D));
                double rz = Math.max(1.5D, baseRadius * Mth.lerp(blockSelectionValue(segment.islandNoiseSeed(), 0xD1B54A32L, ncx, 0, ncz), 0.60D, 1.50D));
                double nx = ((double) x - cx) / rx;
                double nz = ((double) z - cz) / rz;
                if (nx * nx + nz * nz <= 1.0D) {
                    chunk.setBlockState(pos, detail.states().get(0), false);
                    return;
                }
            }
        }
    }

    private void applySurfaceDetailBlob(
            ChunkAccess chunk,
            SkylandsColumnSegment segment,
            SkylandsIslandBiomeDefinition.SurfaceDetailDefinition detail,
            int x,
            int topY,
            int z,
            int shellBottomY
    ) {
        int volume = detail.clampedBlobSize();
        double baseRadius = Math.cbrt((3.0D * (double) volume) / (4.0D * Math.PI));
        double r = Math.max(2.5D, baseRadius * 1.55D);
        int checkRange = Mth.floor(r / 16.0D) + 1;
        int centerChunkX = Math.floorDiv(x, 16);
        int centerChunkZ = Math.floorDiv(z, 16);
        List<BlockState> states = detail.states();
        for (int dcx = -checkRange; dcx <= checkRange; dcx++) {
            for (int dcz = -checkRange; dcz <= checkRange; dcz++) {
                int ncx = centerChunkX + dcx;
                int ncz = centerChunkZ + dcz;
                long hash = mix64((long) segment.islandNoiseSeed() ^ (long) ncx * 0x9E3779B9L ^ (long) ncz * 0x517CC1B7L ^ 0x7A3F5C2EL);
                double roll = ((double) (hash & 0x7FFFFFFFFFFFFFFFL)) / (double) 0x7FFFFFFFFFFFFFFFL;
                if (roll > detail.clampedRatio()) {
                    continue;
                }
                long posHash = mix64(hash ^ 0x3C6EF372L);
                double cx = (double) ncx * 16.0 + ((double) (posHash & 0xFFFF)) / 65535.0 * 16.0;
                double cz = (double) ncz * 16.0 + ((double) ((posHash >> 16) & 0xFFFF)) / 65535.0 * 16.0;
                double cy = (double) shellBottomY + ((double) ((posHash >> 32) & 0xFFFF)) / 65535.0 * (double) Math.max(1, topY - shellBottomY + 1);
                double rx = Math.max(1.2D, baseRadius * Mth.lerp(blockSelectionValue(segment.islandNoiseSeed(), 0x4CF5AD43L, ncx, 0, ncz), 0.70D, 1.55D));
                double ry = Math.max(1.0D, baseRadius * Mth.lerp(blockSelectionValue(segment.islandNoiseSeed(), 0x9E3779B9L, ncx, 0, ncz), 0.55D, 1.25D));
                double rz = Math.max(1.2D, baseRadius * Mth.lerp(blockSelectionValue(segment.islandNoiseSeed(), 0xD1B54A32L, ncx, 0, ncz), 0.70D, 1.55D));
                double nx = ((double) x - cx) / rx;
                double nz = ((double) z - cz) / rz;
                double lateralCheck = nx * nx + nz * nz;
                if (lateralCheck > 1.0D) {
                    continue;
                }
                int stateIndex = Math.floorMod(ncx + ncz, states.size());
                for (int y = shellBottomY; y <= topY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState current = chunk.getBlockState(pos);
                    BlockState target = detail.targetState();
                    if (target != null) {
                        if (!current.equals(target)) {
                            continue;
                        }
                    } else if (!current.equals(segment.subsurfaceState()) && !current.equals(segment.islandBiome().resolvedSubsurfaceState())
                            && !current.equals(segment.surfaceState()) && !current.equals(segment.islandBiome().resolvedSurfaceState())) {
                        continue;
                    }
                    double ny = ((double) y - cy) / ry;
                    if (lateralCheck + ny * ny > 1.0D) {
                        continue;
                    }
                    chunk.setBlockState(pos, states.get(stateIndex), false);
                }
                return;
            }
        }
    }

    private void applySurfaceDetailBulb(
            ChunkAccess chunk,
            SkylandsColumnSegment segment,
            SkylandsIslandBiomeDefinition.SurfaceDetailDefinition detail,
            int x,
            int topY,
            int z
    ) {
        int bulbCount = surfaceDetailBlobCount(segment, detail);
        int maxY = Math.min(getMinY() + getGenDepth() - 1, topY + detail.clampedBlobSize());
        for (int bulbIndex = 0; bulbIndex < bulbCount; bulbIndex++) {
            if (blockSelectionValue(segment.islandNoiseSeed(), 0x6C8E9CF5L, bulbIndex, 0, 0) > detail.clampedRatio()) {
                continue;
            }
            if (!isInsideSurfaceDetailSurfaceBlob(segment, detail, bulbIndex, x, z)) {
                continue;
            }
            int volume = detail.clampedBlobSize();
            double baseRadius = Math.cbrt((3.0D * (double) volume) / (4.0D * Math.PI));
            double lateralScale = Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0x4CF5AD43L, bulbIndex), 0.95D, 1.20D);
            double crossScale = Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0xD1B54A32L, bulbIndex), 0.92D, 1.12D);
            double verticalScale = Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0x9E3779B9L, bulbIndex), 0.82D, 1.08D);
            double rx = Math.max(1.8D, baseRadius * lateralScale);
            double rz = Math.max(1.8D, baseRadius * lateralScale * crossScale);
            double ry = Math.max(1.4D, baseRadius * verticalScale);
            double angle = blobSelectionValue(segment.islandNoiseSeed(), 0x94D049BBL, bulbIndex) * (Math.PI * 2.0D);
            double orbit = segment.islandRadius() * Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0xA54FF53AL, bulbIndex), 0.08D, 0.56D);
            double centerX = (double) segment.islandCenterX() + Math.cos(angle) * orbit;
            double centerZ = (double) segment.islandCenterZ() + Math.sin(angle) * orbit;
            double dx = ((double) x - centerX) / rx;
            double dz = ((double) z - centerZ) / rz;
            double lateral = dx * dx + dz * dz;
            if (lateral > 1.0D) {
                continue;
            }
            double dome = Math.sqrt(Math.max(0.0D, 1.0D - lateral));
            int rise = Math.max(1, Mth.floor(ry * 0.90D * dome));
            int bury = Math.max(1, Mth.floor(ry * 1.05D * dome));
            int minY = Math.max(segment.bottomY(), topY - bury);
            int capY = Math.min(maxY, topY + rise);
            for (int y = minY; y <= capY; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (y <= topY) {
                    BlockState current = chunk.getBlockState(pos);
                    if (!current.equals(segment.surfaceState()) && !current.equals(segment.subsurfaceState())
                            && !current.equals(segment.islandBiome().resolvedSurfaceState())
                            && !current.equals(segment.islandBiome().resolvedSubsurfaceState())) {
                        continue;
                    }
                }
                chunk.setBlockState(pos, detail.states().get(0), false);
            }
            return;
        }
    }

    private int surfaceDetailBlobCount(SkylandsColumnSegment segment, SkylandsIslandBiomeDefinition.SurfaceDetailDefinition detail) {
        int volume = detail.clampedBlobSize();
        double islandArea = Math.PI * (double) segment.islandRadius() * (double) segment.islandRadius();
        int count = Mth.ceil(islandArea / Math.max(1400.0D, (double) volume * 18.0D));
        return Mth.clamp(count, 1, 14);
    }

    private boolean isInsideSurfaceDetailSurfaceBlob(
            SkylandsColumnSegment segment,
            SkylandsIslandBiomeDefinition.SurfaceDetailDefinition detail,
            int blobIndex,
            int x,
            int z
    ) {
        int volume = detail.clampedBlobSize();
        double baseRadius = Math.sqrt((double) volume / Math.PI);
        double rx = Math.max(1.2D, baseRadius * Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0x4CF5AD43L, blobIndex), 0.70D, 1.45D));
        double rz = Math.max(1.2D, baseRadius * Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0xD1B54A32L, blobIndex), 0.70D, 1.45D));
        double angle = blobSelectionValue(segment.islandNoiseSeed(), 0x94D049BBL, blobIndex) * (Math.PI * 2.0D);
        double orbit = segment.islandRadius() * Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0xA54FF53AL, blobIndex), 0.10D, 0.64D);
        int centerX = segment.islandCenterX() + roundToInt(Math.cos(angle) * orbit);
        int centerZ = segment.islandCenterZ() + roundToInt(Math.sin(angle) * orbit);
        double nx = ((double) x - centerX) / rx;
        double nz = ((double) z - centerZ) / rz;
        return nx * nx + nz * nz <= 1.0D;
    }

    private boolean isInsideSurfaceDetailBlob(
            SkylandsColumnSegment segment,
            SkylandsIslandBiomeDefinition.SurfaceDetailDefinition detail,
            int topY,
            int bottomY,
            int blobIndex,
            int x,
            int y,
            int z
    ) {
        int volume = detail.clampedBlobSize();
        double baseRadius = Math.cbrt((3.0D * (double) volume) / (4.0D * Math.PI));
        double rx = Math.max(1.2D, baseRadius * Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0x4CF5AD43L, blobIndex), 0.70D, 1.55D));
        double ry = Math.max(1.0D, baseRadius * Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0x9E3779B9L, blobIndex), 0.55D, 1.25D));
        double rz = Math.max(1.2D, baseRadius * Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0xD1B54A32L, blobIndex), 0.70D, 1.55D));
        double angle = blobSelectionValue(segment.islandNoiseSeed(), 0x94D049BBL, blobIndex) * (Math.PI * 2.0D);
        double orbit = segment.islandRadius() * Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0xA54FF53AL, blobIndex), 0.08D, 0.58D);
        int centerX = segment.islandCenterX() + roundToInt(Math.cos(angle) * orbit);
        int centerZ = segment.islandCenterZ() + roundToInt(Math.sin(angle) * orbit);
        int centerY = Mth.clamp(
                bottomY + Mth.floor(blobSelectionValue(segment.islandNoiseSeed(), 0x243F6A88L, blobIndex) * (double) Math.max(1, topY - bottomY + 1)),
                bottomY,
                topY
        );
        double nx = ((double) x - centerX) / rx;
        double ny = ((double) y - centerY) / ry;
        double nz = ((double) z - centerZ) / rz;
        return nx * nx + ny * ny + nz * nz <= 1.0D;
    }

    private void applyBadlandsDetailColumn(ChunkAccess chunk, SkylandsColumnSegment segment, int x, int z, int topY) {
        SkylandsIslandBiomeDefinition.BadlandsDetailDefinition detail = segment.islandBiome().badlandsDetail();
        if (detail == null || !detail.isValid()) {
            return;
        }
        int columnHeight = Math.max(1, topY - segment.bottomY() + 1);
        double splitLine = detail.clampedSplitLine();
        int splitY = segment.bottomY() + Mth.floor((double) (columnHeight - 1) * splitLine);
        if (splitY > topY) {
            return;
        }
        int nearSurfaceRange = 10;
        int bandAnchorY = segment.islandBaseY() - estimatedConeLength(segment.islandRadius());
        for (int y = splitY; y <= topY; y++) {
            if (usesDeepMaterial(segment, topY, x, y, z)) {
                continue;
            }
            BlockPos pos = new BlockPos(x, y, z);
            BlockState currentState = chunk.getBlockState(pos);
            boolean nearExterior = isNearBadlandsExterior(chunk, x, y, z, nearSurfaceRange);
            boolean isBaseSubsurface = currentState.equals(segment.subsurfaceState());
            boolean isBadlandsBase = currentState.equals(detail.nearSurfaceState());
            if (!isBaseSubsurface && !isBadlandsBase) {
                continue;
            }
            if (!nearExterior && !isBadlandsBase) {
                continue;
            }
            BlockState bandState = badlandsBandStateAt(detail, segment.islandNoiseSeed(), bandAnchorY, y);
            BlockState targetState = bandState != null ? bandState : detail.nearSurfaceState();
            chunk.setBlockState(pos, targetState, false);
        }
    }

    private boolean isNearBadlandsExterior(ChunkAccess chunk, int x, int y, int z, int range) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int step = 1; step <= range; step++) {
            cursor.set(x, y + step, z);
            if (chunk.getBlockState(cursor).isAir()) {
                return true;
            }
        }
        for (int step = 1; step <= range; step++) {
            cursor.set(x + step, y, z);
            if (chunk.getBlockState(cursor).isAir()) {
                return true;
            }
            cursor.set(x - step, y, z);
            if (chunk.getBlockState(cursor).isAir()) {
                return true;
            }
            cursor.set(x, y, z + step);
            if (chunk.getBlockState(cursor).isAir()) {
                return true;
            }
            cursor.set(x, y, z - step);
            if (chunk.getBlockState(cursor).isAir()) {
                return true;
            }
        }
        return false;
    }

    private BlockState badlandsBandStateAt(
            SkylandsIslandBiomeDefinition.BadlandsDetailDefinition detail,
            int islandNoiseSeed,
            int bottomY,
            int y
    ) {
        int stateCount = detail.bandStates().size();
        if (stateCount <= 0) {
            return null;
        }
        int minGroupThickness = detail.clampedMinBandThickness();
        int thicknessSpan = detail.clampedMaxBandThickness() - minGroupThickness + 1;
        int currentY = bottomY;
        int salt = 0;
        while (currentY <= y && salt < 2048) {
            int groupThickness = minGroupThickness
                    + Mth.floor(blobSelectionValue(islandNoiseSeed, 0x72BE5D74L, salt) * (double) thicknessSpan);
            groupThickness = Math.max(1, groupThickness);
            int gapThickness = Math.max(1, groupThickness * 2);
            if (y < currentY + groupThickness) {
                int stateJitter = Mth.floor(blobSelectionValue(islandNoiseSeed, 0x517CC1B7L, salt) * (double) stateCount);
                int stateIndex = Math.floorMod((y - currentY) + stateJitter, stateCount);
                return detail.bandStates().get(stateIndex);
            }
            currentY += groupThickness;
            if (y < currentY + gapThickness) {
                return null;
            }
            currentY += gapThickness;
            salt++;
        }
        return null;
    }

    private void applyLiquidPoolColumn(ChunkAccess chunk, SkylandsColumnSegment segment, int x, int z, int topY) {
        List<SkylandsIslandBiomeDefinition.LiquidPoolDefinition> poolDefs = segment.islandBiome().liquidPoolDefinitions();
        if (poolDefs.isEmpty()) {
            return;
        }
        int seaLevel = getSeaLevel();
        for (SkylandsIslandBiomeDefinition.LiquidPoolDefinition poolDef : poolDefs) {
            if (!poolDef.isValid()) {
                continue;
            }
            int poolCount = liquidPoolCount(segment, poolDef);
            for (int poolIndex = 0; poolIndex < poolCount; poolIndex++) {
                LiquidPoolCenterInfo centerInfo = liquidPoolCenterInfo(segment, poolDef, poolIndex);
                if (!isInsideLiquidPool2D(centerInfo, x, z)) {
                    continue;
                }
                int waterLevelY = resolveLiquidPoolWaterLevel(chunk, centerInfo, poolDef, seaLevel);
                if (waterLevelY < 0) {
                    continue;
                }
                carveLiquidPoolDepression(chunk, segment, poolDef, poolIndex, x, z, waterLevelY, centerInfo);
                break;
            }
        }
    }

    private record LiquidPoolCenterInfo(double cx, double cz, double rx, double rz) {}

    private int liquidPoolCount(SkylandsColumnSegment segment, SkylandsIslandBiomeDefinition.LiquidPoolDefinition poolDef) {
        double islandArea = Math.PI * (double) segment.islandRadius() * (double) segment.islandRadius();
        int count = Mth.ceil(islandArea * poolDef.clampedDensity() / Math.max(120.0D, poolDef.clampedSize() * 45.0D));
        return Mth.clamp(count, 0, 18);
    }

    private LiquidPoolCenterInfo liquidPoolCenterInfo(
            SkylandsColumnSegment segment,
            SkylandsIslandBiomeDefinition.LiquidPoolDefinition poolDef,
            int poolIndex
    ) {
        int size = poolDef.clampedSize();
        double baseRadius = Math.max(1.5D, (double) size * 0.65D);
        double lateralScale = Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0x7A3F5C2EL, poolIndex), 0.75D, 1.35D);
        double crossScale = Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0xD1E84B9AL, poolIndex), 0.75D, 1.35D);
        double rx = Math.max(1.2D, baseRadius * lateralScale);
        double rz = Math.max(1.2D, baseRadius * lateralScale * crossScale);
        double angle = blobSelectionValue(segment.islandNoiseSeed(), 0xC56D3E1FL, poolIndex) * (Math.PI * 2.0D);
        double orbit = segment.islandRadius() * Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0x9A2F7B83L, poolIndex), 0.06D, 0.55D);
        double cx = (double) segment.islandCenterX() + Math.cos(angle) * orbit;
        double cz = (double) segment.islandCenterZ() + Math.sin(angle) * orbit;
        return new LiquidPoolCenterInfo(cx, cz, rx, rz);
    }

    private int resolveLiquidPoolWaterLevel(
            ChunkAccess chunk,
            LiquidPoolCenterInfo center,
            SkylandsIslandBiomeDefinition.LiquidPoolDefinition poolDef,
            int seaLevel
    ) {
        int cx = Mth.floor(center.cx());
        int cz = Mth.floor(center.cz());
        int centerY = findActualSurfaceY(chunk, cx, cz);
        if (centerY < 0) {
            centerY = theoreticalTerrainAt(chunk, cx, cz, seaLevel).surfaceY();
        }
        int dx1 = Mth.floor(center.rx() * 0.4D);
        int dz1 = Mth.floor(center.rz() * 0.4D);
        int[][] offsets = {{dx1, 0}, {-dx1, 0}, {0, dz1}, {0, -dz1}};
        int sampleSum = 0;
        int sampleCount = 0;
        for (int[] off : offsets) {
            int sx = cx + off[0];
            int sz = cz + off[1];
            int sy = findActualSurfaceY(chunk, sx, sz);
            if (sy < 0) {
                sy = theoreticalTerrainAt(chunk, sx, sz, seaLevel).surfaceY();
            }
            double dist = Math.sqrt((double)(off[0] * off[0] + off[1] * off[1]));
            if (dist > 0.0D && Math.abs(centerY - sy) / dist > 0.65D) {
                return -1;
            }
            sampleSum += sy;
            sampleCount++;
        }
        if (sampleCount > 0 && centerY > sampleSum / sampleCount) {
            return -1;
        }
        return centerY;
    }

    private int findActualSurfaceY(ChunkAccess chunk, int x, int z) {
        ChunkPos chunkPos = chunk.getPos();
        int cx = x - chunkPos.getMinBlockX();
        int cz = z - chunkPos.getMinBlockZ();
        if (cx < 0 || cx >= 16 || cz < 0 || cz >= 16) {
            return -1;
        }
        int maxY = getMinY() + getGenDepth() - 1;
        for (int y = maxY; y >= getMinY(); y--) {
            if (!chunk.getBlockState(new BlockPos(x, y, z)).isAir()) {
                return y;
            }
        }
        return -1;
    }

    private boolean isInsideLiquidPool2D(
            LiquidPoolCenterInfo center,
            int x,
            int z
    ) {
        double nx = ((double) x - center.cx()) / center.rx();
        double nz = ((double) z - center.cz()) / center.rz();
        return nx * nx + nz * nz <= 1.0D;
    }

    private void carveLiquidPoolDepression(
            ChunkAccess chunk,
            SkylandsColumnSegment segment,
            SkylandsIslandBiomeDefinition.LiquidPoolDefinition poolDef,
            int poolIndex,
            int x,
            int z,
            int waterLevelY,
            LiquidPoolCenterInfo center
    ) {
        int maxDepth = poolDef.clampedDepth();
        int columnSurfaceY = findActualSurfaceY(chunk, x, z);
        if (columnSurfaceY >= 0 && columnSurfaceY > waterLevelY + maxDepth + 2) {
            return;
        }
        double nx = ((double) x - center.cx()) / center.rx();
        double nz = ((double) z - center.cz()) / center.rz();
        double lateral = nx * nx + nz * nz;
        double depthFactor = 1.0D - Math.sqrt(lateral);
        int depressionDepth = Math.max(1, Mth.floor((double) maxDepth * depthFactor * Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0x5E8A1F4DL, poolIndex), 0.80D, 1.15D)));
        int depressionBottom = waterLevelY - depressionDepth;
        if (depressionBottom < segment.bottomY()) {
            depressionBottom = segment.bottomY();
        }
        if (depressionBottom > waterLevelY) {
            return;
        }
        BlockState liquid = poolDef.liquidState();
        List<BlockState> bottomStates = poolDef.bottomStates();
        int bottomIndex = Math.floorMod(poolIndex, bottomStates.size());
        BlockState bottomState = bottomStates.get(bottomIndex);
        for (int y = waterLevelY; y >= depressionBottom; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (anyHorizontalNeighborIsAir(chunk, pos, segment)) {
                return;
            }
        }
        for (int y = waterLevelY; y >= depressionBottom; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState current = chunk.getBlockState(pos);
            if (current.isAir()) {
                continue;
            }
            if (current.equals(liquid)) {
                continue;
            }
            if (y == depressionBottom) {
                chunk.setBlockState(pos, bottomState, false);
            } else {
                chunk.setBlockState(pos, liquid, false);
            }
        }
        ChunkPos cp = chunk.getPos();
        BlockPos abovePos = new BlockPos(x, waterLevelY + 1, z);
        if (abovePos.getX() >= cp.getMinBlockX() && abovePos.getX() <= cp.getMaxBlockX()
                && abovePos.getZ() >= cp.getMinBlockZ() && abovePos.getZ() <= cp.getMaxBlockZ()) {
            BlockState aboveState = chunk.getBlockState(abovePos);
            if (!aboveState.isAir() && !aboveState.equals(liquid)) {
                chunk.setBlockState(abovePos, Blocks.AIR.defaultBlockState(), false);
            }
        }
        BlockPos waterPos = new BlockPos(x, waterLevelY, z);
        BlockState edgeBlock = segment.islandBiome().resolvedSubsurfaceState();
        boolean isEdge = !isInsideLiquidPool2D(center, x - 1, z)
                || !isInsideLiquidPool2D(center, x + 1, z)
                || !isInsideLiquidPool2D(center, x, z - 1)
                || !isInsideLiquidPool2D(center, x, z + 1);
        if (isEdge && chunk.getBlockState(waterPos).equals(liquid)) {
            chunk.setBlockState(waterPos, edgeBlock, false);
        }
    }

    private boolean anyHorizontalNeighborIsAir(ChunkAccess chunk, BlockPos pos, SkylandsColumnSegment segment) {
        return isNearIslandEdge(chunk, pos.west(), segment)
                || isNearIslandEdge(chunk, pos.east(), segment)
                || isNearIslandEdge(chunk, pos.north(), segment)
                || isNearIslandEdge(chunk, pos.south(), segment);
    }

    private boolean isNearIslandEdge(ChunkAccess chunk, BlockPos pos, SkylandsColumnSegment segment) {
        ChunkPos cp = chunk.getPos();
        if (pos.getX() >= cp.getMinBlockX() && pos.getX() <= cp.getMaxBlockX()
                && pos.getZ() >= cp.getMinBlockZ() && pos.getZ() <= cp.getMaxBlockZ()) {
            return chunk.getBlockState(pos).isAir();
        }
        long dx = (long) pos.getX() - segment.islandCenterX();
        long dz = (long) pos.getZ() - segment.islandCenterZ();
        long distSq = dx * dx + dz * dz;
        long edgeSq = (long) (segment.islandRadius() + 6) * (segment.islandRadius() + 6);
        return distSq > edgeSq;
    }

    private static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static boolean hasTreeNearby(Set<Long> placedTreeXZ, int x, int z, int range) {
        int r = Math.max(1, range);
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (placedTreeXZ.contains(packXZ(x + dx, z + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void markTreeRangeOccupied(Set<Long> placedTreeXZ, int x, int z, int range) {
        int r = Math.max(1, range);
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                placedTreeXZ.add(packXZ(x + dx, z + dz));
            }
        }
    }

    private void applySurfaceFeatures(WorldGenLevel level, ChunkAccess chunk, SkylandsColumnSegment segment, int x, int z, int topY, Set<Long> placedTreeXZ) {
        List<SkylandsIslandBiomeDefinition.SurfaceFeatureDefinition> features = segment.islandBiome().surfaceFeatures();
        if (features.isEmpty()) {
            return;
        }
        BlockPos surfacePos = new BlockPos(x, topY, z);
        BlockState surfaceBlock = chunk.getBlockState(surfacePos);
        int islandRadius = Math.max(1, segment.islandRadius());
        double islandDiameter = (double) islandRadius * 2.0D;
        long now = System.currentTimeMillis();
        if (now - lastFeatureTracePrintMs >= 10000L) {
            lastFeatureTracePrintMs = now;
        }
        int printedHits = 0;
        int traceLimit = 1;
        boolean placedAny = false;
        for (int fi = 0; fi < features.size() && !placedAny; fi++) {
            SkylandsIslandBiomeDefinition.SurfaceFeatureDefinition feature = features.get(fi);
            if (!feature.isValid()) {
                continue;
            }
            if (!feature.matchesBottom(surfaceBlock)) {
                continue;
            }
            int radiusR = feature.clampedRadiusRequired();
            int heightR = feature.clampedHeightRequired();
            int toleranceR = feature.clampedToleranceRequired();
            int excludeRange = feature.isPlacedFeature() ? (radiusR + 1) : 0;
            if (excludeRange > 0 && hasTreeNearby(placedTreeXZ, x, z, excludeRange)) {
                continue;
            }
            double density = feature.clampedDensity();
            double gridSize = Math.sqrt(1.0D / Math.max(1e-6D, density));
            double step = Math.max(1.0D, Math.min(islandDiameter, gridSize));
            int ix = Mth.floor((double) x / step);
            int iz = Mth.floor((double) z / step);
            long cellHash = mix64((long) segment.islandNoiseSeed() ^ (long) fi * 0x9E3779B9L ^ (long) ix * 0x517CC1B7L ^ (long) iz * 0x3C6EF372L);
            double jx = ((double) (cellHash & 0xFFFF)) / 65535.0D;
            double jz = ((double) ((cellHash >> 16) & 0xFFFF)) / 65535.0D;
            double cellCenterX = ((double) ix + jx) * step;
            double cellCenterZ = ((double) iz + jz) * step;
            double dx = (double) x - cellCenterX;
            double dz = (double) z - cellCenterZ;
            if (dx * dx + dz * dz > 0.75D * 0.75D) {
                continue;
            }
            double roll = ((double) ((cellHash >> 32) & 0x7FFFFFFFFFFFFFFFL)) / (double) 0x7FFFFFFFFFFFFFFFL;
            if (roll > feature.clampedProbability()) {
                continue;
            }
            if (!surfaceBlock.isFaceSturdy(chunk, surfacePos, net.minecraft.core.Direction.UP)) {
                if (printedHits < traceLimit) System.out.println("[SKY-FTR] @STURDY-fail fidx=" + fi + " " + (feature.isPlacedFeature() ? "@"+feature.placedFeatureId() : feature.featureBlock()) + " surface=" + surfaceBlock.getBlock() + " at " + surfacePos);
                continue;
            }
            BlockPos placeCheckBase = surfacePos.above();
            if (!canPlaceFeatureV2(chunk, placeCheckBase, radiusR, heightR, toleranceR)) {
                if (printedHits < traceLimit) System.out.println("[SKY-FTR] @PLACE-fail fidx=" + fi + " " + (feature.isPlacedFeature() ? "@"+feature.placedFeatureId() : feature.featureBlock()) + " R="+radiusR+" H="+heightR+" tol="+toleranceR + " at " + surfacePos);
                continue;
            }
            printedHits++;
            boolean placed = false;
            if (feature.isPlacedFeature()) {
                placed = placePlacedFeature(level, chunk, feature.placedFeatureId(), surfacePos);
                if (placed && excludeRange > 0) {
                    markTreeRangeOccupied(placedTreeXZ, x, z, excludeRange);
                }
            } else {
                placeFeatureBlockDirect(level, chunk, feature, surfacePos);
                placed = true;
            }
            if (placed) {
                placedAny = true;
            }
        }
    }

    private static boolean isSoftReplaceableForFeature(BlockState st) {
        if (st.isAir()) return true;
        Block b = st.getBlock();
        return b instanceof net.minecraft.world.level.block.TallGrassBlock
                || b instanceof net.minecraft.world.level.block.BushBlock
                || b instanceof net.minecraft.world.level.block.FlowerBlock
                || b instanceof net.minecraft.world.level.block.MushroomBlock
                || b == net.minecraft.world.level.block.Blocks.MOSS_CARPET
                || st.is(net.minecraft.tags.BlockTags.REPLACEABLE);
    }

    private static void clearSoftAt(WorldGenLevel level, ChunkAccess chunk, BlockPos pos) {
        BlockState st = chunk.getBlockState(pos);
        if (isSoftReplaceableForFeature(st)) {
            chunk.setBlockState(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), false);
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private void placeFeatureBlockDirect(WorldGenLevel level, ChunkAccess chunk, SkylandsIslandBiomeDefinition.SurfaceFeatureDefinition feature, BlockPos surfacePos) {
        BlockState lowerState = feature.featureBlock();
        BlockPos abovePos = surfacePos.above();
        Block block = lowerState.getBlock();
        if (block instanceof net.minecraft.world.level.block.DoublePlantBlock doublePlant) {
            clearSoftAt(level, chunk, abovePos);
            clearSoftAt(level, chunk, abovePos.above());
            if (level.getBlockState(abovePos).isAir() && level.getBlockState(abovePos.above()).isAir()) {
                net.minecraft.world.level.block.DoublePlantBlock.placeAt(level, lowerState, abovePos, 3);
            } else {
                System.out.println("[SKY-FTR] @BLOCK DoublePlant still-blocked @"+abovePos+" low="+level.getBlockState(abovePos).getBlock()+" up="+level.getBlockState(abovePos.above()).getBlock());
            }
        } else if (block instanceof net.minecraft.world.level.block.SugarCaneBlock || block instanceof net.minecraft.world.level.block.CactusBlock) {
            int desiredHeight = feature.clampedHeightRequired();
            if (desiredHeight <= 0) desiredHeight = block instanceof net.minecraft.world.level.block.CactusBlock ? 3 : 3;
            for (int dy = 0; dy < desiredHeight; dy++) {
                BlockPos p = abovePos.above(dy);
                clearSoftAt(level, chunk, p);
                if (!chunk.getBlockState(p).isAir()) break;
                chunk.setBlockState(p, lowerState, false);
            }
        } else if (block instanceof net.minecraft.world.level.block.BambooStalkBlock || block instanceof net.minecraft.world.level.block.BambooSaplingBlock) {
            int desiredHeight = feature.clampedHeightRequired();
            if (desiredHeight <= 0) desiredHeight = 5;
            BlockState bambooStalk = net.minecraft.world.level.block.Blocks.BAMBOO.defaultBlockState();
            for (int dy = 0; dy < desiredHeight; dy++) {
                BlockPos p = abovePos.above(dy);
                clearSoftAt(level, chunk, p);
                if (!chunk.getBlockState(p).isAir()) break;
                chunk.setBlockState(p, bambooStalk, false);
            }
        } else {
            clearSoftAt(level, chunk, abovePos);
            chunk.setBlockState(abovePos, lowerState, false);
        }
    }

    private boolean placePlacedFeature(WorldGenLevel level, ChunkAccess chunk, ResourceLocation featureId, BlockPos pos) {
        try {
            var registry = level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
            var key = ResourceKey.create(Registries.PLACED_FEATURE, featureId);
            var holder = registry.get(key);
            if (holder.isEmpty()) {
                System.out.println("[SKY-FTR] @FAIL holder-empty id=" + featureId + " at " + pos);
                return false;
            }
            var random = RandomSource.create(seed ^ (long) pos.getX() * 0x9E3779B9L ^ (long) pos.getZ() * 0x517CC1B7L);
            boolean placed = holder.get().value().placeWithBiomeCheck(level, this, random, pos.above());
            if (printedPlaceResCount.getAndIncrement() < 50 || placed) {
                System.out.println("[SKY-FTR] @PLACE " + featureId + " at " + pos + " placed=" + placed);
            }
            return placed;
        } catch (Exception e) {
            System.out.println("[SKY-FTR] @FAIL exception id=" + featureId + " msg=" + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean canPlaceFeatureV2(ChunkAccess chunk, BlockPos basePos, int radius, int height, int tolerance) {
        int maxY = getMinY() + getGenDepth() - 1;
        ChunkPos cp = chunk.getPos();
        int minCX = cp.getMinBlockX();
        int maxCX = cp.getMaxBlockX();
        int minCZ = cp.getMinBlockZ();
        int maxCZ = cp.getMaxBlockZ();
        int hardBlockCount = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = 0; dy < height; dy++) {
                    int x = basePos.getX() + dx;
                    int z = basePos.getZ() + dz;
                    int y = basePos.getY() + dy;
                    if (y > maxY) {
                        hardBlockCount++;
                        if (hardBlockCount > tolerance) {
                            return false;
                        }
                        continue;
                    }
                    if (x < minCX || x > maxCX || z < minCZ || z > maxCZ) {
                        continue;
                    }
                    BlockState st = chunk.getBlockState(new BlockPos(x, y, z));
                    if (!isFeatureReplaceable(st)) {
                        hardBlockCount++;
                        if (hardBlockCount > tolerance) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static boolean isFeatureReplaceable(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        Block b = state.getBlock();
        if (b instanceof net.minecraft.world.level.block.LeavesBlock
                || b instanceof net.minecraft.world.level.block.SaplingBlock
                || b instanceof net.minecraft.world.level.block.TallGrassBlock
                || b instanceof net.minecraft.world.level.block.BushBlock
                || b instanceof net.minecraft.world.level.block.MushroomBlock
                || b instanceof net.minecraft.world.level.block.FlowerBlock
                || b instanceof net.minecraft.world.level.block.WaterlilyBlock
                || b instanceof net.minecraft.world.level.block.SugarCaneBlock
                || b instanceof net.minecraft.world.level.block.SeaPickleBlock
                || b instanceof net.minecraft.world.level.block.DoublePlantBlock
                || b instanceof net.minecraft.world.level.block.VineBlock
                || b instanceof net.minecraft.world.level.block.FlowerPotBlock) {
            return true;
        }
        if (b == net.minecraft.world.level.block.Blocks.MOSS_CARPET) {
            return true;
        }
        net.minecraft.tags.TagKey<Block> replaceableTag = net.minecraft.tags.BlockTags.REPLACEABLE;
        return state.is(replaceableTag);
    }

    private boolean placeOneUnderhang(WorldGenLevel level, ChunkAccess chunk, int ui, SkylandsIslandBiomeDefinition.UnderhangDefinition udef, SkylandsColumnSegment segment, int x, int z, int bottomY) {
        BlockPos ceilingPos = new BlockPos(x, bottomY, z);
        BlockState ceilingBlock = chunk.getBlockState(ceilingPos);
        if (ceilingBlock.isAir()) return false;
        if (!udef.matchesCeiling(ceilingBlock)) return false;
        if (!ceilingBlock.isFaceSturdy(chunk, ceilingPos, net.minecraft.core.Direction.DOWN)) return false;
        int minE = udef.clampedMinExtend();
        int maxE = udef.clampedMaxExtend();
        java.util.Random er = new java.util.Random(this.seed ^ ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) z * 0xC2B2AE3D27D4EB4FL) ^ ((long) ui * 0x165667B19E3779F9L) ^ 0x9E3779B9L);
        int extend = minE + (minE >= maxE ? 0 : er.nextInt(maxE - minE + 1));
        int tolerance = Math.max(0, Math.min(extend / 3 + 1, Math.max(1, (int) (extend * 0.25))));
        BlockPos hangStart = ceilingPos.below();
        if (!canPlaceUndersideV2(chunk, hangStart, 0, extend, tolerance)) {
            return false;
        }
        String tag = BuiltInRegistries.BLOCK.getKey(udef.block().getBlock()).toString();
        String tipTag = udef.tipBlock() == null ? "null" : BuiltInRegistries.BLOCK.getKey(udef.tipBlock().getBlock()).toString();
        String rootTag = udef.rootReplace() == null ? "null" : BuiltInRegistries.BLOCK.getKey(udef.rootReplace().getBlock()).toString();
        System.out.println("[SKY-UND] @PLACE u" + ui + " block=" + tag + " extend=" + extend + " tip=" + tipTag + " rootReplace=" + rootTag + " at " + ceilingPos);
        placeUnderhangColumn(level, chunk, udef, ceilingPos, extend);
        if (udef.rootReplace() != null) {
            chunk.setBlockState(ceilingPos, udef.rootReplace(), false);
            level.setBlock(ceilingPos, udef.rootReplace(), 3);
        }
        return true;
    }

    private void placeUnderhangColumn(WorldGenLevel level, ChunkAccess chunk, SkylandsIslandBiomeDefinition.UnderhangDefinition udef, BlockPos ceilingPos, int extend) {
        BlockState mainBlock = udef.block();
        BlockState tipBlock = udef.tipBlock();
        String mainName = BuiltInRegistries.BLOCK.getKey(mainBlock.getBlock()).getPath();
        boolean mainIsDripstone = mainName.contains("pointed_dripstone");
        boolean mainIsVine = mainBlock.getBlock() instanceof net.minecraft.world.level.block.VineBlock;
        boolean mainIsGlowBerry = mainName.contains("glow_berry") || mainBlock.getBlock() instanceof net.minecraft.world.level.block.CaveVinesBlock || mainBlock.getBlock() instanceof net.minecraft.world.level.block.CaveVinesPlantBlock;
        boolean mainIsMossCarpet = mainBlock.getBlock() == Blocks.MOSS_CARPET || mainName.equals("moss_carpet") || mainName.contains("hanging_moss");
        boolean mainIsChain = mainBlock.getBlock() == Blocks.CHAIN;

        for (int dy = 1; dy <= extend; dy++) {
            BlockPos cur = ceilingPos.below(dy);
            if (!chunk.getBlockState(cur).isAir()) break;
            boolean isTip = (dy == extend);
            BlockState use;
            use = mainBlock;
            if (tipBlock != null && isTip) {
                use = tipBlock;
            }
            BlockState placed = use;
            String useName = BuiltInRegistries.BLOCK.getKey(placed.getBlock()).getPath();
            boolean useIsDripstone = useName.contains("pointed_dripstone") || (mainIsDripstone && !isTip);
            boolean useIsVine = placed.getBlock() instanceof net.minecraft.world.level.block.VineBlock || (mainIsVine && !isTip);
            boolean useIsGlowHead = placed.getBlock() instanceof net.minecraft.world.level.block.CaveVinesBlock || (mainIsGlowBerry && isTip);
            boolean useIsGlowPlant = placed.getBlock() instanceof net.minecraft.world.level.block.CaveVinesPlantBlock || (mainIsGlowBerry && !isTip);
            boolean useIsMossCarpet = placed.getBlock() == Blocks.MOSS_CARPET || (mainIsMossCarpet && dy == 1);
            boolean useIsChain = placed.getBlock() == Blocks.CHAIN || (mainIsChain && placed.getBlock() instanceof net.minecraft.world.level.block.RotatedPillarBlock);

            if (useIsDripstone) {
                net.minecraft.world.level.block.state.properties.DripstoneThickness thickness;
                int fromTip = extend - dy;
                if (fromTip == 0) {
                    thickness = net.minecraft.world.level.block.state.properties.DripstoneThickness.TIP;
                } else if (fromTip == 1) {
                    thickness = net.minecraft.world.level.block.state.properties.DripstoneThickness.MIDDLE;
                } else {
                    thickness = net.minecraft.world.level.block.state.properties.DripstoneThickness.BASE;
                }
                placed = Blocks.POINTED_DRIPSTONE.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.PointedDripstoneBlock.TIP_DIRECTION, net.minecraft.core.Direction.DOWN)
                        .setValue(net.minecraft.world.level.block.PointedDripstoneBlock.THICKNESS, thickness)
                        .setValue(net.minecraft.world.level.block.PointedDripstoneBlock.WATERLOGGED, false);
            } else if (useIsVine) {
                if (mainIsGlowBerry || (tipBlock != null && (tipBlock.getBlock() instanceof net.minecraft.world.level.block.CaveVinesBlock || tipBlock.getBlock() instanceof net.minecraft.world.level.block.CaveVinesPlantBlock))) {
                    boolean berries = uniform01(this.seed, ceilingPos.getX(), ceilingPos.getZ(), dy) < 0.11D;
                    if (isTip) {
                        placed = Blocks.CAVE_VINES.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.GrowingPlantHeadBlock.AGE, net.minecraft.world.level.block.GrowingPlantHeadBlock.MAX_AGE)
                                .trySetValue(net.minecraft.world.level.block.CaveVines.BERRIES, berries);
                    } else {
                        placed = Blocks.CAVE_VINES_PLANT.defaultBlockState()
                                .trySetValue(net.minecraft.world.level.block.CaveVines.BERRIES, berries);
                    }
                } else {
                    net.minecraft.core.Direction vineSide = null;
                    for (net.minecraft.core.Direction d : new net.minecraft.core.Direction[]{net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST}) {
                        BlockPos side = cur.relative(d);
                        if (side.getX() >= chunk.getPos().getMinBlockX() && side.getX() <= chunk.getPos().getMaxBlockX()
                                && side.getZ() >= chunk.getPos().getMinBlockZ() && side.getZ() <= chunk.getPos().getMaxBlockZ()) {
                            BlockState sideSt = chunk.getBlockState(side);
                            if (sideSt.isFaceSturdy(chunk, side, d.getOpposite())) {
                                vineSide = d;
                                break;
                            }
                        }
                    }
                    BlockState vs = Blocks.VINE.defaultBlockState();
                    if (vineSide != null) {
                        vs = vs.setValue(net.minecraft.world.level.block.VineBlock.getPropertyForFace(vineSide), true);
                    } else {
                        vs = vs
                                .setValue(net.minecraft.world.level.block.VineBlock.UP, dy == 1)
                                .setValue(net.minecraft.world.level.block.VineBlock.NORTH, true);
                    }
                    placed = vs;
                }
            } else if (useIsGlowHead || useIsGlowPlant) {
                boolean berries = uniform01(this.seed, ceilingPos.getX(), ceilingPos.getZ(), dy) < 0.11D;
                if (isTip) {
                    placed = Blocks.CAVE_VINES.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.GrowingPlantHeadBlock.AGE, net.minecraft.world.level.block.GrowingPlantHeadBlock.MAX_AGE)
                            .trySetValue(net.minecraft.world.level.block.CaveVines.BERRIES, berries);
                } else {
                    placed = Blocks.CAVE_VINES_PLANT.defaultBlockState()
                            .trySetValue(net.minecraft.world.level.block.CaveVines.BERRIES, berries);
                }
            } else if (useIsMossCarpet) {
                placed = Blocks.MOSS_CARPET.defaultBlockState();
                if (dy == 1) {
                    chunk.setBlockState(cur, placed, false);
                    level.setBlock(cur, placed, 3);
                    continue;
                } else {
                    if (new java.util.Random(this.seed ^ (long) ceilingPos.getX() * 211L ^ (long) ceilingPos.getZ() * 1331L ^ (long) dy).nextInt(4) == 0 && dy > 2) break;
                    BlockState vs = Blocks.VINE.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.VineBlock.UP, dy == 2)
                            .setValue(net.minecraft.world.level.block.VineBlock.NORTH, true);
                    placed = vs;
                }
            } else if (useIsChain) {
                placed = Blocks.CHAIN.defaultBlockState().setValue(net.minecraft.world.level.block.ChainBlock.AXIS, net.minecraft.core.Direction.Axis.Y);
            } else if (placed.getBlock() instanceof net.minecraft.world.level.block.RotatedPillarBlock) {
                placed = placed.trySetValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.Y);
            }

            chunk.setBlockState(cur, placed, false);
            level.setBlock(cur, placed, 3);
        }
    }

    private boolean canPlaceUndersideV2(ChunkAccess chunk, BlockPos hangStart, int radius, int height, int tolerance) {
        int minY = getMinY();
        ChunkPos cp = chunk.getPos();
        int minCX = cp.getMinBlockX();
        int maxCX = cp.getMaxBlockX();
        int minCZ = cp.getMinBlockZ();
        int maxCZ = cp.getMaxBlockZ();
        int hardBlockCount = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = 0; dy < height; dy++) {
                    int x = hangStart.getX() + dx;
                    int z = hangStart.getZ() + dz;
                    int y = hangStart.getY() - dy;
                    if (y < minY) {
                        hardBlockCount++;
                        if (hardBlockCount > tolerance) return false;
                        continue;
                    }
                    if (x < minCX || x > maxCX || z < minCZ || z > maxCZ) {
                        continue;
                    }
                    BlockState st = chunk.getBlockState(new BlockPos(x, y, z));
                    if (!isFeatureReplaceable(st)) {
                        hardBlockCount++;
                        if (hardBlockCount > tolerance) return false;
                    }
                }
            }
        }
        return true;
    }

    private int applyTerrainStack(ChunkAccess chunk, SkylandsColumnSegment segment, int x, int z) {
        int extraHeight = terrainStackHeight(segment, x, z);
        if (extraHeight <= 0) {
            return 0;
        }
        int maxY = getMinY() + getGenDepth() - 8;
        int maxPlaced = 0;
        int baseTopY = segment.topY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = 1; dy <= extraHeight; dy++) {
            int y = baseTopY + dy;
            if (y > maxY) {
                break;
            }
            pos.set(x, y - 1, z);
            if (chunk.getBlockState(pos).isAir()) {
                continue;
            }
            pos.set(x, y, z);
            if (!chunk.getBlockState(pos).isAir()) {
                continue;
            }
            chunk.setBlockState(pos, baseIslandInteriorState(segment, x, y, z), false);
            maxPlaced = dy;
        }
        return maxPlaced;
    }

    private int terrainStackHeight(SkylandsColumnSegment segment, int x, int z) {
        if (isFlatTerrain(segment.islandBiome().terrainType())
                || isRollingTerrain(segment.islandBiome().terrainType())
                || isHillsTerrain(segment.islandBiome().terrainType())
                || isMountainsTerrain(segment.islandBiome().terrainType())
                || isMesaTerrain(segment.islandBiome().terrainType())) {
            return 0;
        }
        int offset = terrainOffset(
                segment.islandBiome().terrainType(),
                segment.islandNoiseSeed(),
                segment.islandCenterX(),
                segment.islandCenterZ(),
                segment.islandRadius(),
                x,
                z
        );
        return Math.max(0, offset);
    }

    private int terrainOffset(String terrainType, int islandNoiseSeed, int centerX, int centerZ, int radius, int x, int z) {
        String normalized = terrainType == null ? "default" : terrainType.trim().toLowerCase(java.util.Locale.ROOT);
        normalized = switch (normalized) {
            case "平坦" -> "flat";
            case "起伏" -> "rolling";
            case "山丘" -> "hills";
            case "多山" -> "mountains";
            case "高峰" -> "peaks";
            case "平顶山" -> "mesa";
            case "火山" -> "volcano";
            default -> normalized;
        };

        double dx = x - centerX;
        double dz = z - centerZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double r = Mth.clamp(dist / Math.max(1.0D, radius), 0.0D, 1.0D);

        long baseSeed = seed ^ ((long) islandNoiseSeed * 1181783497276652981L) ^ 0xD1B54A32D192ED03L;
        double footprint = coneProfile(r, 0.0D, 1.0D, 1.35D);
        if (footprint <= 0.0D) {
            return 0;
        }
        double detailMask = 0.70D + 0.30D * (0.5D + 0.5D * SkylandsNoise.octaveNoise(baseSeed ^ 0xBB67AE8584CAA73BL, x, z, 1.0D / 26.0D, 2, 0.55D));

        double value = switch (normalized) {
            case "flat" -> {
                // Flat keeps broad stepped shelves instead of fine-grained noise.
                double macro = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                        baseSeed ^ 0x243F6A8885A308D3L,
                        x,
                        z,
                        1.0D / 108.0D,
                        2,
                        0.55D
                );
                int shelves = Mth.clamp(Mth.floor(Mth.clamp(macro, 0.0D, 0.999999D) * 3.0D), 0, 2);
                double shelfHeight = 3.0D + shelves;
                double edgeBlend = 0.80D + 0.20D * footprint;
                yield footprint * shelfHeight * edgeBlend;
            }
            case "rolling" -> {
                double broad = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                        baseSeed,
                        x,
                        z,
                        1.0D / 96.0D,
                        2,
                        0.56D
                );
                double soft = 0.5D + 0.5D * SkylandsNoise.octaveNoise(
                        baseSeed ^ 0x243F6A8885A308D3L,
                        x,
                        z,
                        1.0D / 48.0D,
                        1,
                        0.50D
                );
                double shaped = Math.pow(Mth.clamp(broad * 0.86D + soft * 0.14D, 0.0D, 0.999999D), 1.04D);
                int shelves = Mth.clamp(Mth.floor(shaped * 3.0D), 0, 2);
                double stepHeight = shelves * 6.0D;
                double rollingMask = smoothstep(0.12D, 0.92D, footprint);
                yield rollingMask * stepHeight;
            }
            case "hills" -> {
                double n = 0.5D + 0.5D * SkylandsNoise.octaveNoise(baseSeed, x, z, 1.0D / 62.0D, 3, 0.55D);
                yield footprint * detailMask * Math.pow(Mth.clamp(n, 0.0D, 1.0D), 1.20D) * 10.0D;
            }
            case "mountains" -> {
                double n = SkylandsNoise.octaveNoise(baseSeed, x, z, 1.0D / 78.0D, 4, 0.58D);
                double ridge = 1.0D - Math.abs(n);
                double shaped = smoothstep(0.35D, 0.95D, ridge);
                yield footprint * detailMask * Math.pow(shaped, 2.10D) * 26.0D;
            }
            case "peaks" -> {
                double n = SkylandsNoise.octaveNoise(baseSeed, x, z, 1.0D / 90.0D, 4, 0.60D);
                double ridge = 1.0D - Math.abs(n);
                double shaped = smoothstep(0.45D, 0.98D, ridge);
                yield footprint * detailMask * Math.pow(shaped, 2.55D) * 34.0D;
            }
            case "mesa" -> {
                double plateauHeight = 12.0D;
                double plateauMask = 1.0D - smoothstep(0.40D, 0.62D, r);
                double capNoise = SkylandsNoise.octaveNoise(baseSeed ^ 0x243F6A8885A308D3L, x, z, 1.0D / 36.0D, 2, 0.52D);
                double cap = plateauMask * plateauHeight + capNoise * 2.0D;
                yield footprint * cap;
            }
            case "volcano" -> {
                yield 0.0D;
            }
            default -> {
                double n = 0.5D + 0.5D * SkylandsNoise.octaveNoise(baseSeed, x, z, 1.0D / 52.0D, 2, 0.55D);
                yield footprint * detailMask * n * 6.0D;
            }
        };

        value = Math.max(0.0D, value);
        if ("rolling".equals(normalized)) {
            return Math.max(0, Mth.floor((value + 3.0D) / 6.0D) * 6);
        }
        int base = Mth.floor(value);
        double frac = value - (double) base;
        if (frac > 0.0D && blockSelectionValue(islandNoiseSeed, 0x3C6EF372L, x, 0, z) < frac) {
            return base + 1;
        }
        return base;
    }

    private double terrainRoughnessScale(String terrainType) {
        String normalized = terrainTypeLabel(terrainType);
        return switch (normalized) {
            case "flat" -> 0.0D;
            case "rolling" -> 0.75D;
            case "hills" -> 1.15D;
            case "mountains" -> 1.35D;
            case "peaks" -> 1.55D;
            case "mesa" -> 1.05D;
            case "volcano" -> 1.25D;
            default -> 1.0D;
        };
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        if (edge1 == edge0) {
            return x < edge0 ? 0.0D : 1.0D;
        }
        double t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private static double superellipseDistance(double x, double z, double axisX, double axisZ, double power) {
        double px = Math.pow(Math.abs(x) / Math.max(1.0E-6D, axisX), power);
        double pz = Math.pow(Math.abs(z) / Math.max(1.0E-6D, axisZ), power);
        return Math.pow(px + pz, 1.0D / Math.max(1.0E-6D, power));
    }

    private static double softCap(double value, double cap, double kneeFrac, double strength) {
        if (cap <= 0.0D) {
            return 0.0D;
        }
        if (value <= 0.0D) {
            return value;
        }
        double knee = cap * Mth.clamp(kneeFrac, 0.0D, 1.0D);
        if (value <= knee) {
            return value;
        }
        double remain = Math.max(1.0E-6D, cap - knee);
        double over = value - knee;
        double t = 1.0D - Math.exp(-over / remain * Math.max(0.1D, strength));
        return knee + remain * t;
    }

    private void applyDeepLayerColumn(ChunkAccess chunk, SkylandsColumnSegment segment, int x, int z, int topY) {
        if (!segment.islandBiome().deepLayerEnabled()) {
            return;
        }
        BlockState deep = segment.islandBiome().resolvedDeepState();
        int deepCoreTopY = deepCoreTopY(segment);
        int transitionTopY = Math.min(topY, deepCoreTopY + 4);
        if (transitionTopY < segment.bottomY()) {
            return;
        }
        for (int y = segment.bottomY(); y <= transitionTopY; y++) {
            if (usesDeepMaterial(segment, topY, x, y, z)) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!chunk.getBlockState(pos).equals(segment.subsurfaceState())) {
                    continue;
                }
                chunk.setBlockState(pos, deep, false);
            }
        }
    }

    private void applyDeepDetailColumn(ChunkAccess chunk, SkylandsColumnSegment segment, int x, int z, int topY) {
        if (!segment.islandBiome().deepLayerEnabled()) {
            return;
        }
        BlockState deep = segment.islandBiome().resolvedDeepState();
        List<SkylandsIslandBiomeDefinition.DeepDetailDefinition> details = segment.islandBiome().deepDetailDefinitions();
        if (details.isEmpty()) {
            return;
        }
        for (int y = segment.bottomY(); y <= topY; y++) {
            if (!usesDeepMaterial(segment, topY, x, y, z)) {
                continue;
            }
            BlockPos pos = new BlockPos(x, y, z);
            if (!chunk.getBlockState(pos).equals(deep)) {
                continue;
            }
            BlockState detailState = deepDetailStateAt(segment, details, topY, x, y, z);
            if (detailState != null) {
                chunk.setBlockState(pos, detailState, false);
            }
        }
    }

    private void applyIslandOreColumn(ChunkAccess chunk, SkylandsColumnSegment segment, int x, int z, int topY) {
        int oreTopY = topY - 1;
        if (oreTopY < segment.bottomY()) {
            return;
        }
        int blobCount = islandOreBlobCount(segment, topY, oreTopY);
        if (blobCount <= 0) {
            return;
        }
        OrePalette orePalette = orePaletteFor(segment.islandBiome());
        for (int y = segment.bottomY(); y <= oreTopY; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState current = chunk.getBlockState(pos);
            if (!canReplaceWithIslandOre(segment, topY, x, y, z, current)) {
                continue;
            }
            BlockState oreState = islandOreStateAt(segment, orePalette, topY, oreTopY, x, y, z, blobCount);
            if (oreState != null) {
                chunk.setBlockState(pos, oreState, false);
            }
        }
    }

    public String debugIslandOreAt(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkAt(pos);
        List<SkylandsColumnCandidate> candidates = sampleSkylandsColumns(chunk, x, z);
        if (candidates.isEmpty()) {
            return "no_candidates";
        }
        List<SkylandsColumnSegment> segments = mergeColumnSegments(candidates);
        if (segments.isEmpty()) {
            return "no_segments";
        }
        SkylandsColumnSegment topSegment = null;
        int topSegmentY = Integer.MIN_VALUE;
        for (SkylandsColumnSegment segment : segments) {
            if (segment.topY() > topSegmentY) {
                topSegmentY = segment.topY();
                topSegment = segment;
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("pos=").append(x).append(",").append(y).append(",").append(z);
        out.append(" segments=").append(segments.size());
        BlockState currentAtPos = level.getBlockState(pos);

        int index = 0;
        for (SkylandsColumnSegment segment : segments) {
            int stacked = segment == topSegment ? terrainStackHeight(segment, x, z) : 0;
            int finalTopY = segment.topY() + stacked;
            int oreTopY = finalTopY - 1;
            int referenceY = islandOreReferenceY(segment);
            int horizonY = segment.islandBaseY();
            int localY = islandOreLocalY(segment, finalTopY, y);
            boolean inSegment = y >= segment.bottomY() && y <= finalTopY;
            boolean canReplace = canReplaceWithIslandOre(segment, finalTopY, x, y, z, currentAtPos);
            int blobCount = islandOreBlobCount(segment, finalTopY, oreTopY);
            OrePalette orePalette = orePaletteFor(segment.islandBiome());
            List<String> templateWeights = new ArrayList<>();
            List<BlockState> debugStates = baseOreStatesForLocalY(localY, orePalette);
            List<Block> debugSeen = new ArrayList<>();
            for (BlockState state : debugStates) {
                if (debugSeen.contains(state.getBlock())) {
                    continue;
                }
                debugSeen.add(state.getBlock());
                OreFeatureTemplate template = oreFeatureTemplateFor(state);
                double weight = oreTemplateWeightForLocalY(template, localY);
                templateWeights.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "=" + String.format(java.util.Locale.ROOT, "%.3f", weight));
            }

            int hitCount = 0;
            int samples = 9;
            int range = Math.max(1, oreTopY - segment.bottomY());
            int sampleTemplateNull = 0;
            int sampleOutsideVein = 0;
            int sampleDensityRejected = 0;
            int sampleAccepted = 0;
            for (int i = 0; i < samples; i++) {
                int sampleY = segment.bottomY() + (range * i) / (samples - 1);
                BlockState predicted = islandOreStateAt(segment, orePalette, finalTopY, oreTopY, x, sampleY, z, blobCount);
                OreDebugStats stats = analyzeIslandOreAt(segment, orePalette, finalTopY, oreTopY, x, sampleY, z, blobCount);
                sampleTemplateNull += stats.templateNullCount();
                sampleOutsideVein += stats.outsideVeinCount();
                sampleDensityRejected += stats.densityRejectedCount();
                sampleAccepted += stats.acceptedCount();
                if (predicted != null) {
                    hitCount++;
                }
            }

            BlockState predictedAtPos = null;
            OreDebugStats debugStatsAtPos = null;
            if (inSegment && y <= oreTopY && canReplace) {
                predictedAtPos = islandOreStateAt(segment, orePalette, finalTopY, oreTopY, x, y, z, blobCount);
            }
            if (inSegment && y <= oreTopY) {
                debugStatsAtPos = analyzeIslandOreAt(segment, orePalette, finalTopY, oreTopY, x, y, z, blobCount);
            }

            out.append("\nseg#").append(index++);
            out.append(" bottom=").append(segment.bottomY());
            out.append(" top=").append(segment.topY());
            out.append(" stacked=").append(stacked);
            out.append(" finalTop=").append(finalTopY);
            out.append(" refY=").append(referenceY);
            out.append(" horizonY=").append(horizonY);
            out.append(" oreTop=").append(oreTopY);
            out.append(" localY=").append(localY);
            out.append(" in=").append(inSegment);
            out.append(" replace=").append(canReplace);
            out.append(" blobs=").append(blobCount);
            out.append(" hits=").append(hitCount).append("/").append(samples);
            out.append(" predicted=").append(predictedAtPos == null ? "none" : BuiltInRegistries.BLOCK.getKey(predictedAtPos.getBlock()));
            out.append(" weights=").append(templateWeights);
            if (debugStatsAtPos != null) {
                out.append(" posStats=[templateNull=").append(debugStatsAtPos.templateNullCount());
                out.append(", outside=").append(debugStatsAtPos.outsideVeinCount());
                out.append(", density=").append(debugStatsAtPos.densityRejectedCount());
                out.append(", accepted=").append(debugStatsAtPos.acceptedCount()).append("]");
            }
            out.append(" sampleStats=[templateNull=").append(sampleTemplateNull);
            out.append(", outside=").append(sampleOutsideVein);
            out.append(", density=").append(sampleDensityRejected);
            out.append(", accepted=").append(sampleAccepted).append("]");

            // #region debug-point A:oredebug-segment
            try {
                java.net.http.HttpClient.newHttpClient().send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:7777/event"))
                                .header("Content-Type", "application/json")
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                        "{\"sessionId\":\"ore-middle-gap\",\"runId\":\"post-fix\",\"hypothesisId\":\"A\",\"location\":\"SkylandsChunkGenerator.debugIslandOreAt\",\"msg\":\"[DEBUG] oredebug segment\",\"data\":{"
                                                + "\"x\":" + x + ","
                                                + "\"y\":" + y + ","
                                                + "\"z\":" + z + ","
                                                + "\"segmentIndex\":" + index + ","
                                                + "\"bottomY\":" + segment.bottomY() + ","
                                                + "\"topY\":" + segment.topY() + ","
                                                + "\"finalTopY\":" + finalTopY + ","
                                                + "\"referenceY\":" + referenceY + ","
                                                + "\"horizonY\":" + horizonY + ","
                                                + "\"oreTopY\":" + oreTopY + ","
                                                + "\"localY\":" + localY + ","
                                                + "\"inSegment\":" + inSegment + ","
                                                + "\"canReplace\":" + canReplace + ","
                                                + "\"blobCount\":" + blobCount + ","
                                                + "\"hitCount\":" + hitCount + ","
                                                + "\"posTemplateNull\":" + (debugStatsAtPos == null ? -1 : debugStatsAtPos.templateNullCount()) + ","
                                                + "\"posOutsideVein\":" + (debugStatsAtPos == null ? -1 : debugStatsAtPos.outsideVeinCount()) + ","
                                                + "\"posDensityRejected\":" + (debugStatsAtPos == null ? -1 : debugStatsAtPos.densityRejectedCount()) + ","
                                                + "\"posAccepted\":" + (debugStatsAtPos == null ? -1 : debugStatsAtPos.acceptedCount()) + ","
                                                + "\"sampleTemplateNull\":" + sampleTemplateNull + ","
                                                + "\"sampleOutsideVein\":" + sampleOutsideVein + ","
                                                + "\"sampleDensityRejected\":" + sampleDensityRejected + ","
                                                + "\"sampleAccepted\":" + sampleAccepted + ","
                                                + "\"weights\":\"" + String.join(" | ", templateWeights).replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                                                + "}}"
                                ))
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding()
                );
            } catch (Exception ignored) {
            }
            // #endregion
        }
        return out.toString();
    }

    private boolean usesDeepMaterial(SkylandsColumnSegment segment, int topY, int x, int y, int z) {
        if (!segment.islandBiome().deepLayerEnabled()) {
            return false;
        }
        if (y < segment.bottomY() || y > topY) {
            return false;
        }
        int deepCoreTopY = deepCoreTopY(segment);
        if (y > deepCoreTopY + 4) {
            return false;
        }
        if (y <= deepCoreTopY) {
            return true;
        }
        int above = y - deepCoreTopY;
        if (above < 1 || above > 4) {
            return false;
        }
        double chance = switch (above) {
            case 1 -> 0.80D;
            case 2 -> 0.60D;
            case 3 -> 0.40D;
            case 4 -> 0.20D;
            default -> 0.0D;
        };
        return chance > 0.0D && blockSelectionValue(segment.islandNoiseSeed(), 0x5F356495L, x, y, z) < chance;
    }

    private int deepCoreTopY(SkylandsColumnSegment segment) {
        int mainConeLength = estimatedConeLength(segment.islandRadius());
        double line = Mth.clamp(segment.islandBiome().deepLayerLine(), 0.0D, 1.0D);
        int deepFromBottom = Mth.clamp(Mth.ceil((double) mainConeLength * line), 0, mainConeLength);
        int mainBottomY = segment.islandBaseY() - mainConeLength;
        return mainBottomY + deepFromBottom - 1;
    }

    private boolean canReplaceWithIslandOre(SkylandsColumnSegment segment, int topY, int x, int y, int z, BlockState current) {
        if (current.isAir() || y < segment.bottomY() || y > topY) {
            return false;
        }
        if (current.equals(segment.subsurfaceState()) || current.equals(segment.islandBiome().resolvedSubsurfaceState())) {
            return true;
        }
        BlockState subsurfaceDetail = segment.islandBiome().subsurfaceDetailState();
        if (subsurfaceDetail != null && current.equals(subsurfaceDetail)) {
            return true;
        }
        if (current.equals(segment.islandBiome().resolvedDeepState())) {
            return true;
        }
        for (SkylandsIslandBiomeDefinition.DeepDetailDefinition definition : segment.islandBiome().deepDetailDefinitions()) {
            if (current.equals(definition.state())) {
                return true;
            }
        }
        return false;
    }

    private static final int ISLAND_ORE_POINT_CELL_X = 30;
    private static final int ISLAND_ORE_POINT_CELL_Y = 30;
    private static final int ISLAND_ORE_POINT_CELL_Z = 30;
    private static final int ISLAND_ORE_POINT_SEARCH_RADIUS_XZ = 2;
    private static final int ISLAND_ORE_POINT_SEARCH_RADIUS_Y = 3;

    private BlockState islandOreStateAt(
            SkylandsColumnSegment segment,
            OrePalette orePalette,
            int coneTopY,
            int oreTopY,
            int x,
            int y,
            int z,
            int blobCount
    ) {
        int minX = segment.islandCenterX() - segment.islandRadius();
        int maxX = segment.islandCenterX() + segment.islandRadius();
        int minY = segment.bottomY();
        int maxY = oreTopY;
        int minZ = segment.islandCenterZ() - segment.islandRadius();
        int maxZ = segment.islandCenterZ() + segment.islandRadius();
        int cellCountX = islandOreCellCount(minX, maxX, ISLAND_ORE_POINT_CELL_X);
        int cellCountY = islandOreCellCount(minY, maxY, ISLAND_ORE_POINT_CELL_Y);
        int cellCountZ = islandOreCellCount(minZ, maxZ, ISLAND_ORE_POINT_CELL_Z);
        int baseCellX = Mth.clamp(Math.floorDiv(x - minX, ISLAND_ORE_POINT_CELL_X), 0, cellCountX - 1);
        int baseCellY = Mth.clamp(Math.floorDiv(y - minY, ISLAND_ORE_POINT_CELL_Y), 0, cellCountY - 1);
        int baseCellZ = Mth.clamp(Math.floorDiv(z - minZ, ISLAND_ORE_POINT_CELL_Z), 0, cellCountZ - 1);
        for (int cellY = Math.max(0, baseCellY - ISLAND_ORE_POINT_SEARCH_RADIUS_Y); cellY <= Math.min(cellCountY - 1, baseCellY + ISLAND_ORE_POINT_SEARCH_RADIUS_Y); cellY++) {
            for (int cellZ = Math.max(0, baseCellZ - ISLAND_ORE_POINT_SEARCH_RADIUS_XZ); cellZ <= Math.min(cellCountZ - 1, baseCellZ + ISLAND_ORE_POINT_SEARCH_RADIUS_XZ); cellZ++) {
                for (int cellX = Math.max(0, baseCellX - ISLAND_ORE_POINT_SEARCH_RADIUS_XZ); cellX <= Math.min(cellCountX - 1, baseCellX + ISLAND_ORE_POINT_SEARCH_RADIUS_XZ); cellX++) {
                    IslandOrePoint point = islandOrePoint(minX, minY, minZ, maxX, maxY, maxZ, cellCountX, cellCountZ, segment.islandNoiseSeed(), cellX, cellY, cellZ);
                    int localOreY = islandOreLocalY(segment, coneTopY, point.centerY());
                    OreFeatureTemplate template = selectIslandOreTemplate(localOreY, orePalette, segment.islandNoiseSeed(), point.pointIndex());
                    if (template == null) {
                        return null;
                    }
                    if (!isInsideIslandOreCluster(segment, point, template, x, y, z)) {
                        continue;
                    }
                    double density = 0.10D * Mth.clamp(
                            Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0x5F356495L, point.pointIndex()), 0.30D, 0.52D)
                                    * Mth.clamp(template.density() / 8.0D, 0.60D, 1.55D),
                            0.18D,
                            0.72D
                    );
                    if (blockSelectionValue(segment.islandNoiseSeed(), 0x27D4EB2FL, x, y, z) > density) {
                        continue;
                    }
                    return template.state();
                }
            }
        }
        return null;
    }

    private OreDebugStats analyzeIslandOreAt(
            SkylandsColumnSegment segment,
            OrePalette orePalette,
            int coneTopY,
            int oreTopY,
            int x,
            int y,
            int z,
            int blobCount
    ) {
        int templateNullCount = 0;
        int outsideVeinCount = 0;
        int densityRejectedCount = 0;
        int acceptedCount = 0;
        int minX = segment.islandCenterX() - segment.islandRadius();
        int maxX = segment.islandCenterX() + segment.islandRadius();
        int minY = segment.bottomY();
        int maxY = oreTopY;
        int minZ = segment.islandCenterZ() - segment.islandRadius();
        int maxZ = segment.islandCenterZ() + segment.islandRadius();
        int cellCountX = islandOreCellCount(minX, maxX, ISLAND_ORE_POINT_CELL_X);
        int cellCountY = islandOreCellCount(minY, maxY, ISLAND_ORE_POINT_CELL_Y);
        int cellCountZ = islandOreCellCount(minZ, maxZ, ISLAND_ORE_POINT_CELL_Z);
        int baseCellX = Mth.clamp(Math.floorDiv(x - minX, ISLAND_ORE_POINT_CELL_X), 0, cellCountX - 1);
        int baseCellY = Mth.clamp(Math.floorDiv(y - minY, ISLAND_ORE_POINT_CELL_Y), 0, cellCountY - 1);
        int baseCellZ = Mth.clamp(Math.floorDiv(z - minZ, ISLAND_ORE_POINT_CELL_Z), 0, cellCountZ - 1);
        for (int cellY = Math.max(0, baseCellY - ISLAND_ORE_POINT_SEARCH_RADIUS_Y); cellY <= Math.min(cellCountY - 1, baseCellY + ISLAND_ORE_POINT_SEARCH_RADIUS_Y); cellY++) {
            for (int cellZ = Math.max(0, baseCellZ - ISLAND_ORE_POINT_SEARCH_RADIUS_XZ); cellZ <= Math.min(cellCountZ - 1, baseCellZ + ISLAND_ORE_POINT_SEARCH_RADIUS_XZ); cellZ++) {
                for (int cellX = Math.max(0, baseCellX - ISLAND_ORE_POINT_SEARCH_RADIUS_XZ); cellX <= Math.min(cellCountX - 1, baseCellX + ISLAND_ORE_POINT_SEARCH_RADIUS_XZ); cellX++) {
                    IslandOrePoint point = islandOrePoint(minX, minY, minZ, maxX, maxY, maxZ, cellCountX, cellCountZ, segment.islandNoiseSeed(), cellX, cellY, cellZ);
                    int localOreY = islandOreLocalY(segment, coneTopY, point.centerY());
                    OreFeatureTemplate template = selectIslandOreTemplate(localOreY, orePalette, segment.islandNoiseSeed(), point.pointIndex());
                    if (template == null) {
                        templateNullCount++;
                        continue;
                    }
                    if (!isInsideIslandOreCluster(segment, point, template, x, y, z)) {
                        outsideVeinCount++;
                        continue;
                    }
                    double density = 0.10D * Mth.clamp(
                            Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0x5F356495L, point.pointIndex()), 0.30D, 0.52D)
                                    * Mth.clamp(template.density() / 8.0D, 0.60D, 1.55D),
                            0.18D,
                            0.72D
                    );
                    if (blockSelectionValue(segment.islandNoiseSeed(), 0x27D4EB2FL, x, y, z) > density) {
                        densityRejectedCount++;
                        continue;
                    }
                    acceptedCount++;
                }
            }
        }
        return new OreDebugStats(templateNullCount, outsideVeinCount, densityRejectedCount, acceptedCount);
    }

    private int islandOreBlobCount(SkylandsColumnSegment segment, int topY, int oreTopY) {
        int minX = segment.islandCenterX() - segment.islandRadius();
        int maxX = segment.islandCenterX() + segment.islandRadius();
        int minY = segment.bottomY();
        int maxY = oreTopY;
        int minZ = segment.islandCenterZ() - segment.islandRadius();
        int maxZ = segment.islandCenterZ() + segment.islandRadius();
        return islandOreCellCount(minX, maxX, ISLAND_ORE_POINT_CELL_X)
                * islandOreCellCount(minY, maxY, ISLAND_ORE_POINT_CELL_Y)
                * islandOreCellCount(minZ, maxZ, ISLAND_ORE_POINT_CELL_Z);
    }

    private int islandOreReferenceY(SkylandsColumnSegment segment) {
        if (segment.islandBiome().deepLayerEnabled()) {
            return deepCoreTopY(segment);
        }
        return segment.bottomY() + 8;
    }

    private int islandOreCellCount(int min, int max, int cellSize) {
        return Math.max(1, ((max - min + 1) + cellSize - 1) / cellSize);
    }

    private IslandOrePoint islandOrePoint(
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            int cellCountX,
            int cellCountZ,
            int islandNoiseSeed,
            int cellX,
            int cellY,
            int cellZ
    ) {
        int pointIndex = cellX + cellCountX * (cellZ + cellCountZ * cellY);
        int cellMinX = minX + cellX * ISLAND_ORE_POINT_CELL_X;
        int cellMinY = minY + cellY * ISLAND_ORE_POINT_CELL_Y;
        int cellMinZ = minZ + cellZ * ISLAND_ORE_POINT_CELL_Z;
        int centerX = Math.min(
                maxX,
                cellMinX + Mth.floor(blobSelectionValue(islandNoiseSeed, 0x94D049BBL, pointIndex) * (double) ISLAND_ORE_POINT_CELL_X)
        );
        int centerY = Math.min(
                maxY,
                cellMinY + Mth.floor(blobSelectionValue(islandNoiseSeed, 0x243F6A88L, pointIndex) * (double) ISLAND_ORE_POINT_CELL_Y)
        );
        int centerZ = Math.min(
                maxZ,
                cellMinZ + Mth.floor(blobSelectionValue(islandNoiseSeed, 0xA54FF53AL, pointIndex) * (double) ISLAND_ORE_POINT_CELL_Z)
        );
        return new IslandOrePoint(centerX, centerY, centerZ, pointIndex);
    }

    private int islandOreLocalY(SkylandsColumnSegment segment, int topY, int y) {
        int referenceY = islandOreReferenceY(segment);
        int horizonY = segment.islandBaseY();
        if (horizonY <= referenceY) {
            horizonY = referenceY + 1;
        }
        if (y >= referenceY) {
            int span = Math.max(1, horizonY - referenceY);
            double slope = 64.0D / (double) span;
            int localY = Mth.floor((double) (y - referenceY) * slope);
            int maxLocalY = 64 + Mth.floor((double) Math.max(0, topY - horizonY) * slope);
            return Mth.clamp(localY, 0, Math.max(64, maxLocalY));
        }
        int span = Math.max(1, referenceY - segment.bottomY());
        int minLocalY = segment.islandBiome().deepLayerEnabled() ? -64 : -8;
        double t = (double) (referenceY - y) / (double) span;
        return Mth.clamp(-Mth.floor(t * (double) Math.abs(minLocalY)), minLocalY, -1);
    }

    private boolean isInsideIslandOreCluster(
            SkylandsColumnSegment segment,
            IslandOrePoint point,
            OreFeatureTemplate template,
            int x,
            int y,
            int z
    ) {
        double baseRadius = Math.cbrt((3.0D * Math.max(1.0D, (double) template.veinSize() * 5.0D)) / (4.0D * Math.PI));
        int lobeCount = 1 + Mth.floor(blobSelectionValue(segment.islandNoiseSeed(), 0x6C8E9CF5L, point.pointIndex()) * 1.5D);
        double orbitScale = Math.max(0.10D, baseRadius * 0.18D);
        for (int lobeIndex = 0; lobeIndex < lobeCount; lobeIndex++) {
            long saltBase = 0x1F83D9ABL + (long) lobeIndex * 0x9E3779B97F4A7C15L;
            double rx = Math.max(4.95D, baseRadius * Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), saltBase, point.pointIndex()), 0.92D, 1.55D) * 3.0D);
            double ry = Math.max(3.75D, baseRadius * Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), saltBase ^ 0x4CF5AD43L, point.pointIndex()), 0.82D, 1.28D) * 3.0D);
            double rz = Math.max(4.95D, baseRadius * Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), saltBase ^ 0xD1B54A32L, point.pointIndex()), 0.92D, 1.55D) * 3.0D);
            double angle = blobSelectionValue(segment.islandNoiseSeed(), saltBase ^ 0x94D049BBL, point.pointIndex()) * (Math.PI * 2.0D);
            double orbit = orbitScale * Mth.lerp(
                    blobSelectionValue(segment.islandNoiseSeed(), saltBase ^ 0xA54FF53AL, point.pointIndex()),
                    0.15D,
                    0.60D
            );
            int centerX = point.centerX() + roundToInt(Math.cos(angle) * orbit);
            int centerZ = point.centerZ() + roundToInt(Math.sin(angle) * orbit);
            int centerY = point.centerY() + roundToInt((blobSelectionValue(segment.islandNoiseSeed(), saltBase ^ 0x243F6A88L, point.pointIndex()) * 2.0D - 1.0D) * Math.max(0.0D, baseRadius * 0.10D));
            double nx = ((double) x + 0.5D - (double) centerX) / rx;
            double ny = ((double) y + 0.5D - (double) centerY) / ry;
            double nz = ((double) z + 0.5D - (double) centerZ) / rz;
            if (nx * nx + ny * ny + nz * nz <= 1.0D) {
                return true;
            }
        }
        return false;
    }

    private BlockState deepDetailStateAt(
            SkylandsColumnSegment segment,
            List<SkylandsIslandBiomeDefinition.DeepDetailDefinition> details,
            int topY,
            int x,
            int y,
            int z
    ) {
        int blobCount = deepDetailBlobCount(segment, details, topY);
        for (int blobIndex = 0; blobIndex < blobCount; blobIndex++) {
            SkylandsIslandBiomeDefinition.DeepDetailDefinition detail = selectDeepDetail(details, segment.islandNoiseSeed(), blobIndex);
            if (detail == null) {
                continue;
            }
            if (isInsideDeepDetailBlob(segment, detail, topY, blobIndex, x, y, z)) {
                return detail.state();
            }
        }
        return null;
    }

    private int deepDetailBlobCount(
            SkylandsColumnSegment segment,
            List<SkylandsIslandBiomeDefinition.DeepDetailDefinition> details,
            int topY
    ) {
        double averageVolume = 0.0D;
        for (SkylandsIslandBiomeDefinition.DeepDetailDefinition detail : details) {
            averageVolume += detail.clampedVolume();
        }
        averageVolume /= Math.max(1, details.size());
        double islandVolumeEstimate = Math.PI
                * (double) segment.islandRadius()
                * (double) segment.islandRadius()
                * Math.max(1.0D, (double) Math.max(1, topY - segment.bottomY() + 1) * segment.islandBiome().deepLayerLine());
        int count = Mth.ceil(islandVolumeEstimate / Math.max(8000.0D, averageVolume * 420.0D));
        return Mth.clamp(count, 1, 10);
    }

    private SkylandsIslandBiomeDefinition.DeepDetailDefinition selectDeepDetail(
            List<SkylandsIslandBiomeDefinition.DeepDetailDefinition> details,
            int islandNoiseSeed,
            int blobIndex
    ) {
        double totalWeight = 0.0D;
        for (SkylandsIslandBiomeDefinition.DeepDetailDefinition detail : details) {
            totalWeight += detail.clampedWeight();
        }
        if (totalWeight <= 0.0D) {
            return null;
        }
        double roll = blobSelectionValue(islandNoiseSeed, 0x31B9A4E5L, blobIndex) * totalWeight;
        double accumulated = 0.0D;
        for (SkylandsIslandBiomeDefinition.DeepDetailDefinition detail : details) {
            accumulated += detail.clampedWeight();
            if (roll <= accumulated) {
                return detail;
            }
        }
        return details.get(details.size() - 1);
    }

    private boolean isInsideDeepDetailBlob(
            SkylandsColumnSegment segment,
            SkylandsIslandBiomeDefinition.DeepDetailDefinition detail,
            int topY,
            int blobIndex,
            int x,
            int y,
            int z
    ) {
        int deepTopY = deepCoreTopY(segment);
        if (deepTopY < segment.bottomY()) {
            return false;
        }
        double baseRadius = Math.cbrt((3.0D * Math.max(1, detail.clampedVolume())) / (4.0D * Math.PI));
        double rx = Math.max(1.2D, baseRadius * Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0x4CF5AD43L, blobIndex), 0.75D, 1.35D));
        double ry = Math.max(1.0D, baseRadius * Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0x9E3779B9L, blobIndex), 0.60D, 1.15D));
        double rz = Math.max(1.2D, baseRadius * Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0xD1B54A32L, blobIndex), 0.75D, 1.35D));
        double angle = blobSelectionValue(segment.islandNoiseSeed(), 0x94D049BBL, blobIndex) * (Math.PI * 2.0D);
        double orbit = segment.islandRadius() * Mth.lerp(blobSelectionValue(segment.islandNoiseSeed(), 0xA54FF53AL, blobIndex), 0.10D, 0.58D);
        int centerX = segment.islandCenterX() + roundToInt(Math.cos(angle) * orbit);
        int centerZ = segment.islandCenterZ() + roundToInt(Math.sin(angle) * orbit);
        int centerY = Mth.clamp(
                segment.bottomY() + Mth.floor(blobSelectionValue(segment.islandNoiseSeed(), 0x243F6A88L, blobIndex) * (double) Math.max(1, deepTopY - segment.bottomY() + 1)),
                segment.bottomY(),
                deepTopY
        );
        double nx = ((double) x - centerX) / rx;
        double ny = ((double) y - centerY) / ry;
        double nz = ((double) z - centerZ) / rz;
        return nx * nx + ny * ny + nz * nz <= 1.0D;
    }

    private double blockSelectionValue(int islandNoiseSeed, long salt, int x, int y, int z) {
        long mixed = mix64(
                seed
                        ^ ((long) islandNoiseSeed * 1181783497276652981L)
                        ^ salt
                        ^ ((long) x * 0x632BE59BD9B4E019L)
                        ^ ((long) y * 0x9E3779B97F4A7C15L)
                        ^ ((long) z * 0x94D049BB133111EBL)
        );
        return ((mixed >>> 11) * 0x1.0p-53);
    }

    private double blobSelectionValue(int islandNoiseSeed, long salt, int blobIndex) {
        long mixed = mix64(
                seed
                        ^ ((long) islandNoiseSeed * 1181783497276652981L)
                        ^ salt
                        ^ ((long) (blobIndex + 1) * 0x9E3779B97F4A7C15L)
        );
        return ((mixed >>> 11) * 0x1.0p-53);
    }

    private double islandShapeValue(int islandNoiseSeed, long salt) {
        long mixed = mix64(seed ^ ((long) islandNoiseSeed * 1181783497276652981L) ^ salt);
        return ((mixed >>> 11) * 0x1.0p-53);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    private double uniform01(long seed, int x, int z, int dy) {
        long h = 0x9E3779B97F4A7C15L ^ seed;
        h = mix64(h + (long) x * 0x517cc1b727220a95L);
        h = mix64(h + (long) z * 0x9e3779b97f4a7c15L);
        h = mix64(h + (long) dy * 0x165667b19e3779f9L);
        return (double) (h >>> 11) / (double) (1L << 53);
    }

    private List<SkylandsColumnSegment> mergeColumnSegments(List<SkylandsColumnCandidate> candidates) {
        candidates.sort((a, b) -> {
            int by = Integer.compare(a.bottomY(), b.bottomY());
            if (by != 0) {
                return by;
            }
            return Integer.compare(a.topY(), b.topY());
        });

        List<SkylandsColumnSegment> segments = new ArrayList<>();
        SkylandsColumnSegment current = null;
        for (SkylandsColumnCandidate candidate : candidates) {
            if (current == null) {
                current = new SkylandsColumnSegment(
                        candidate.topY(),
                        candidate.bottomY(),
                        candidate.surfaceState(),
                        candidate.subsurfaceState(),
                        candidate.islandBiome(),
                        candidate.islandCenterX(),
                        candidate.islandCenterZ(),
                        candidate.islandRadius(),
                        candidate.islandBaseY(),
                        candidate.islandNoiseSeed(),
                        candidate.crackStrength(),
                        candidate.bigCrack()
                );
                continue;
            }
            if (candidate.bottomY() <= current.topY() + 1) {
                int mergedBottom = Math.min(current.bottomY(), candidate.bottomY());
                int mergedTop = Math.max(current.topY(), candidate.topY());
                BlockState mergedSurfaceState = current.surfaceState();
                BlockState mergedSubState = current.subsurfaceState();
                SkylandsIslandBiomeDefinition mergedIslandBiome = current.islandBiome();
                if (candidate.topY() > current.topY()) {
                    mergedSurfaceState = candidate.surfaceState();
                    mergedSubState = candidate.subsurfaceState();
                }

                int mergedCenterX = current.islandCenterX();
                int mergedCenterZ = current.islandCenterZ();
                int mergedRadius = current.islandRadius();
                int mergedBaseY = current.islandBaseY();
                int mergedNoiseSeed = current.islandNoiseSeed();
                float mergedCrackStrength = current.crackStrength();
                boolean mergedBigCrack = current.bigCrack();
                if (candidate.bottomY() < current.bottomY()) {
                    mergedIslandBiome = candidate.islandBiome();
                    mergedCenterX = candidate.islandCenterX();
                    mergedCenterZ = candidate.islandCenterZ();
                    mergedRadius = candidate.islandRadius();
                    mergedBaseY = candidate.islandBaseY();
                    mergedNoiseSeed = candidate.islandNoiseSeed();
                    mergedCrackStrength = candidate.crackStrength();
                    mergedBigCrack = candidate.bigCrack();
                }

                current = new SkylandsColumnSegment(
                        mergedTop,
                        mergedBottom,
                        mergedSurfaceState,
                        mergedSubState,
                        mergedIslandBiome,
                        mergedCenterX,
                        mergedCenterZ,
                        mergedRadius,
                        mergedBaseY,
                        mergedNoiseSeed,
                        mergedCrackStrength,
                        mergedBigCrack
                );
            } else {
                segments.add(current);
                current = new SkylandsColumnSegment(
                        candidate.topY(),
                        candidate.bottomY(),
                        candidate.surfaceState(),
                        candidate.subsurfaceState(),
                        candidate.islandBiome(),
                        candidate.islandCenterX(),
                        candidate.islandCenterZ(),
                        candidate.islandRadius(),
                        candidate.islandBaseY(),
                        candidate.islandNoiseSeed(),
                        candidate.crackStrength(),
                        candidate.bigCrack()
                );
            }
        }
        if (current != null) {
            segments.add(current);
        }
        return segments;
    }

    private int islandTargetBaseY(ChunkAccess chunk, SkylandsIslands.Island island, TheoreticalTerrainSample centerSample) {
        long cacheKey = islandSurfaceCacheKey(island);
        Integer cached = ISLAND_TARGET_Y_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        int baseSurfaceY = centerSample.surfaceY();
        int jitter = deterministicJitter(island.noiseSeed(), SkylandsConfig.HEIGHT_JITTER.getAsInt());
        int targetY = Mth.clamp(baseSurfaceY + jitter, SkylandsConfig.MIN_TARGET_Y.getAsInt(), SkylandsConfig.MAX_TARGET_Y.getAsInt());
        int clamped = Mth.clamp(
                targetY,
                baseSurfaceY - SkylandsConfig.SURFACE_ANCHOR_MAX_DROP.getAsInt(),
                baseSurfaceY + SkylandsConfig.SURFACE_ANCHOR_MAX_RISE.getAsInt()
        );
        int minBottomY = -60;
        clamped = Math.max(clamped, minBottomY + estimatedConeLength(island.radius()));
        ISLAND_TARGET_Y_CACHE.put(cacheKey, clamped);
        return clamped;
    }

    private int estimatedConeLength(int islandRadius) {
        return Math.max(24, Mth.ceil(islandRadius * 0.90D * 1.20D));
    }

    private int deterministicJitter(int salt, int range) {
        if (range <= 0) {
            return 0;
        }
        long mixed = salt * 0x9e3779b97f4a7c15L;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return (int) (Math.floorMod(mixed, range * 2L + 1L) - range);
    }

    private void cutChunk(WorldGenLevel level, ChunkAccess chunk, List<BoundingBox> protectedBoxes) {
        boolean enableOceanIslands = SkylandsConfig.ENABLE_OCEAN_ISLANDS.getAsBoolean();

        ChunkPos pos = chunk.getPos();
        int minY = getMinY();
        int maxY = minY + getGenDepth() - 1;
        int seaLevel = delegate.getSeaLevel();
        SkylandsIslands.Island island = SkylandsIslands.nearestIsland(seed, pos.getMiddleBlockX(), pos.getMiddleBlockZ());
        if (!isIslandCenterChunk(pos, island)) {
            return;
        }

        int surfaceAnchorY = islandSurfaceAnchorY(chunk, island, minY, maxY, seaLevel, enableOceanIslands);
        int sampleCenterX = sampleCenterX(chunk, island);
        int sampleCenterZ = sampleCenterZ(chunk, island);
        int sampleRadius = sampleRadius(chunk, island);
        long cacheKey = islandSurfaceCacheKey(island);
        if (!LOGGED_DEBUG_ISLANDS.add(cacheKey)) {
            return;
        }

        TheoreticalTerrainSample centerSample = theoreticalTerrainAt(chunk, sampleCenterX, sampleCenterZ, seaLevel);
        int sourceSurfaceY = centerSample.surfaceY();
        Holder<Biome> biomeHolder = chunk.getNoiseBiome(sampleCenterX >> 2, surfaceAnchorY >> 2, sampleCenterZ >> 2);
        String biomeKey = biomeHolder.unwrapKey().map(key -> key.location().toString()).orElse("unknown");
        BlockState topState = centerSample.topState();
        LOGGER.info(
                "Skylands debug island center=({}, {}) sampleCenter=({}, {}) sampleRadius={} anchorY={} surfaceY={} topBlock={} biome={} landWeight={} ruggedness={} oceanLike={} protectedBoxes={}",
                island.centerX(),
                island.centerZ(),
                sampleCenterX,
                sampleCenterZ,
                sampleRadius,
                surfaceAnchorY,
                sourceSurfaceY,
                BuiltInRegistries.BLOCK.getKey(topState.getBlock()),
                biomeKey,
                String.format(java.util.Locale.ROOT, "%.3f", centerSample.landWeight()),
                String.format(java.util.Locale.ROOT, "%.3f", centerSample.ruggedness()),
                centerSample.oceanic(),
                protectedBoxes.size()
        );
    }

    private static boolean isOceanSourceState(BlockState state, int sourceY, int seaLevel) {
        if (!state.getFluidState().isSource() || !state.is(Blocks.WATER)) {
            return false;
        }
        return Math.abs(sourceY - seaLevel) <= 6;
    }

    private int islandSurfaceAnchorY(
            ChunkAccess chunk,
            SkylandsIslands.Island island,
            int minY,
            int maxY,
            int seaLevel,
            boolean enableOceanIslands
    ) {
        long cacheKey = islandSurfaceCacheKey(island);
        Integer cached = ISLAND_SURFACE_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        ChunkPos chunkPos = chunk.getPos();
        int sampleCenterX = sampleCenterX(chunk, island);
        int sampleCenterZ = sampleCenterZ(chunk, island);
        int sampleRadius = sampleRadius(chunk, island);
        int anchor = sampleSurfaceAnchor(
                chunk,
                sampleCenterX,
                sampleCenterZ,
                sampleRadius,
                4,
                minY,
                maxY,
                seaLevel,
                enableOceanIslands
        );
        ISLAND_SURFACE_CACHE.put(cacheKey, anchor);
        return anchor;
    }

    private int sampleCenterX(ChunkAccess chunk, SkylandsIslands.Island island) {
        ChunkPos chunkPos = chunk.getPos();
        return Mth.clamp(island.centerX(), chunkPos.getMinBlockX() + 2, chunkPos.getMaxBlockX() - 2);
    }

    private int sampleCenterZ(ChunkAccess chunk, SkylandsIslands.Island island) {
        ChunkPos chunkPos = chunk.getPos();
        return Mth.clamp(island.centerZ(), chunkPos.getMinBlockZ() + 2, chunkPos.getMaxBlockZ() - 2);
    }

    private int sampleRadius(ChunkAccess chunk, SkylandsIslands.Island island) {
        ChunkPos chunkPos = chunk.getPos();
        int centerChunkX = SectionPos.blockToSectionCoord(island.centerX());
        int centerChunkZ = SectionPos.blockToSectionCoord(island.centerZ());
        return chunkPos.x == centerChunkX && chunkPos.z == centerChunkZ
                ? Math.max(8, Math.min(24, island.radius() / 8))
                : 6;
    }

    private boolean isIslandCenterChunk(ChunkPos chunkPos, SkylandsIslands.Island island) {
        return chunkPos.x == SectionPos.blockToSectionCoord(island.centerX())
                && chunkPos.z == SectionPos.blockToSectionCoord(island.centerZ());
    }

    private static long islandSurfaceCacheKey(SkylandsIslands.Island island) {
        return island.noiseSeed() ^ (((long) island.centerX()) << 32) ^ (island.centerZ() & 0xffffffffL);
    }

    private int sampleSurfaceAnchor(
            ChunkAccess chunk,
            int centerX,
            int centerZ,
            int sampleRadius,
            int step,
            int minY,
            int maxY,
            int seaLevel,
            boolean enableOceanIslands
    ) {
        ChunkPos chunkPos = chunk.getPos();
        int minX = chunkPos.getMinBlockX();
        int maxX = chunkPos.getMaxBlockX();
        int minZ = chunkPos.getMinBlockZ();
        int maxZ = chunkPos.getMaxBlockZ();

        List<Integer> heights = new ArrayList<>();
        List<Integer> fallbackHeights = new ArrayList<>();
        for (int x = centerX - sampleRadius; x <= centerX + sampleRadius; x += step) {
            if (x < minX || x > maxX) {
                continue;
            }
            for (int z = centerZ - sampleRadius; z <= centerZ + sampleRadius; z += step) {
                if (z < minZ || z > maxZ) {
                    continue;
                }
                TheoreticalTerrainSample sample = theoreticalTerrainAt(chunk, x, z, seaLevel);
                fallbackHeights.add(sample.surfaceY());
                if (enableOceanIslands || !sample.oceanic()) {
                    heights.add(sample.surfaceY());
                }
            }
        }

        if (heights.isEmpty()) {
            heights.addAll(fallbackHeights);
        }
        if (heights.isEmpty()) {
            return seaLevel + 16;
        }

        Collections.sort(heights);
        int trim = heights.size() >= 8 ? Math.max(1, heights.size() / 5) : 0;
        int start = trim;
        int end = heights.size() - trim;
        if (start >= end) {
            start = 0;
            end = heights.size();
        }

        long sum = 0L;
        int count = 0;
        int median = heights.get((start + end - 1) / 2);
        for (int i = start; i < end; i++) {
            int height = heights.get(i);
            if (height < median) {
                continue;
            }
            sum += height;
            count++;
        }
        if (count == 0) {
            for (int i = start; i < end; i++) {
                sum += heights.get(i);
            }
            count = end - start;
        }
        return (int) Math.round((double) sum / (double) count);
    }

    private int findOpenSurfaceY(
            ChunkAccess chunk,
            int x,
            int z,
            int minY,
            int maxY,
            int seaLevel,
            boolean enableOceanIslands
    ) {
        return theoreticalTerrainAt(chunk, x, z, seaLevel).surfaceY();
    }

    private TheoreticalTerrainSample theoreticalTerrainAt(ChunkAccess chunk, int x, int z, int seaLevel) {
        Holder<Biome> biomeHolder = chunk.getNoiseBiome(x >> 2, seaLevel >> 2, z >> 2);
        String biomePath = biomeHolder.unwrapKey().map(key -> key.location().getPath()).orElse("");

        double continental = SkylandsNoise.octaveNoise(seed ^ 0x51f2ac4dL, x, z, 1.0D / 1600.0D, 4, 0.5D);
        double erosion = SkylandsNoise.octaveNoise(seed ^ 0x2c9277b5L, x, z, 1.0D / 900.0D, 3, 0.5D);
        double ridgeBase = SkylandsNoise.octaveNoise(seed ^ 0x7f4a7c15L, x, z, 1.0D / 600.0D, 4, 0.55D);
        double ridge = 1.0D - Math.abs(ridgeBase);
        double detail = SkylandsNoise.octaveNoise(seed ^ 0x13579bdfL, x, z, 1.0D / 220.0D, 2, 0.45D);

        double biomeHeightBias = biomeHeightBias(biomePath);
        double biomeLandBias = biomeLandBias(biomePath);
        double biomeReliefBoost = biomeReliefBoost(biomePath);

        double landWeight = Mth.clamp(0.5D + continental * 0.38D - erosion * 0.18D + biomeLandBias, 0.0D, 1.0D);
        double ruggedness = Mth.clamp(ridge * 0.7D + Math.max(0.0D, detail) * 0.3D + biomeReliefBoost, 0.0D, 1.0D);

        double height = seaLevel
                + biomeHeightBias
                + continental * 30.0D
                + detail * 8.0D
                + (ruggedness - 0.35D) * 34.0D;

        boolean oceanic = biomePath.contains("ocean")
                || biomePath.contains("river")
                || biomePath.contains("swamp")
                || landWeight < 0.43D;
        if (oceanic) {
            height -= 8.0D + (0.43D - Math.min(0.43D, landWeight)) * 42.0D;
        }

        int surfaceY = Mth.clamp(Mth.floor(height), getMinY() + 8, getMinY() + getGenDepth() - 16);
        BlockState topState = theoreticalTopBlock(biomePath, oceanic, surfaceY, seaLevel);
        BlockState subsurfaceState = theoreticalSubsurfaceBlock(topState, biomePath, oceanic);
        return new TheoreticalTerrainSample(surfaceY, landWeight, ruggedness, oceanic, topState, subsurfaceState);
    }

    private double biomeHeightBias(String biomePath) {
        if (biomePath.contains("jagged") || biomePath.contains("peak") || biomePath.contains("frozen_peak")) {
            return 42.0D;
        }
        if (biomePath.contains("mountain") || biomePath.contains("windswept") || biomePath.contains("stony")) {
            return 24.0D;
        }
        if (biomePath.contains("badlands")) {
            return 18.0D;
        }
        if (biomePath.contains("desert") || biomePath.contains("savanna")) {
            return 6.0D;
        }
        if (biomePath.contains("ocean")) {
            return -18.0D;
        }
        if (biomePath.contains("river") || biomePath.contains("swamp")) {
            return -14.0D;
        }
        return 0.0D;
    }

    private double biomeLandBias(String biomePath) {
        if (biomePath.contains("ocean")) {
            return -0.32D;
        }
        if (biomePath.contains("river")) {
            return -0.26D;
        }
        if (biomePath.contains("swamp")) {
            return -0.18D;
        }
        if (biomePath.contains("beach")) {
            return -0.10D;
        }
        if (biomePath.contains("peak") || biomePath.contains("jagged") || biomePath.contains("mountain")) {
            return 0.16D;
        }
        return 0.0D;
    }

    private double biomeReliefBoost(String biomePath) {
        if (biomePath.contains("peak") || biomePath.contains("jagged")) {
            return 0.28D;
        }
        if (biomePath.contains("mountain") || biomePath.contains("windswept") || biomePath.contains("badlands")) {
            return 0.18D;
        }
        if (biomePath.contains("river") || biomePath.contains("swamp") || biomePath.contains("ocean")) {
            return -0.12D;
        }
        return 0.0D;
    }

    private BlockState theoreticalTopBlock(String biomePath, boolean oceanic, int surfaceY, int seaLevel) {
        if (biomePath.contains("desert") || biomePath.contains("beach") || biomePath.contains("badlands")) {
            return Blocks.STONE.defaultBlockState();
        }
        if (biomePath.contains("snow") || biomePath.contains("frozen")) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        if (oceanic || biomePath.contains("stony") || biomePath.contains("grove") || biomePath.contains("mountain") || biomePath.contains("windswept")) {
            return Blocks.STONE.defaultBlockState();
        }
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private BlockState theoreticalSubsurfaceBlock(BlockState topState, String biomePath, boolean oceanic) {
        if (topState.is(Blocks.SNOW_BLOCK)) {
            return Blocks.STONE.defaultBlockState();
        }
        if (oceanic || biomePath.contains("stony") || biomePath.contains("grove") || biomePath.contains("mountain") || biomePath.contains("windswept")) {
            return Blocks.STONE.defaultBlockState();
        }
        return Blocks.DIRT.defaultBlockState();
    }

    private static BlockState islandFillerState(int sourceSurfaceY) {
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState normalizeIslandTopState(BlockState sourceState, int sourceSurfaceY, int seaLevel, boolean oceanColumn) {
        if (oceanColumn) {
            if (sourceState.is(Blocks.GRAVEL) || sourceState.is(Blocks.CLAY)) {
                return sourceState;
            }
            return Blocks.STONE.defaultBlockState();
        }
        if (sourceState.is(Blocks.SAND) || sourceState.is(Blocks.RED_SAND)) {
            return Blocks.STONE.defaultBlockState();
        }
        if (isSoilLikeTop(sourceState)) {
            return sourceState;
        }
        if (isRockLike(sourceState)) {
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }
        return sourceState;
    }

    private static BlockState normalizeIslandSubsurfaceState(BlockState sourceState, int sourceSurfaceY, int seaLevel, boolean oceanColumn) {
        if (oceanColumn) {
            if (sourceState.is(Blocks.GRAVEL) || sourceState.is(Blocks.CLAY)) {
                return sourceState;
            }
            return Blocks.STONE.defaultBlockState();
        }
        if (sourceState.is(Blocks.SAND) || sourceState.is(Blocks.RED_SAND)) {
            return Blocks.STONE.defaultBlockState();
        }
        if (isSoilLikeFill(sourceState)) {
            return sourceState;
        }
        if (isRockLike(sourceState)) {
            return Blocks.DIRT.defaultBlockState();
        }
        return sourceState;
    }

    private static boolean isSoilLikeTop(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.MUD)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.SNOW_BLOCK);
    }

    private static boolean isSoilLikeFill(BlockState state) {
        return isSoilLikeTop(state) || state.is(Blocks.STONE);
    }

    private static boolean isRockLike(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.GRANITE)
                || state.is(Blocks.TUFF)
                || state.is(Blocks.CALCITE)
                || state.is(Blocks.BLACKSTONE)
                || state.is(Blocks.BASALT);
    }

    private static boolean shouldStripOceanState(BlockState state) {
        if (state.is(Blocks.WATER)
                || state.is(Blocks.KELP)
                || state.is(Blocks.KELP_PLANT)
                || state.is(Blocks.SEAGRASS)
                || state.is(Blocks.TALL_SEAGRASS)
                || state.is(Blocks.SEA_PICKLE)) {
            return true;
        }

        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key != null && key.getPath().contains("coral");
    }

    private static OceanColumnInfo analyzeOceanSourceColumn(
            ChunkAccess chunk,
            int x,
            int z,
            int minY,
            int maxY,
            int seaLevel
    ) {
        int waterTop = Integer.MIN_VALUE;
        int floorY = Integer.MIN_VALUE;
        int waterCount = 0;

        for (int sy = Math.min(maxY, seaLevel + 2); sy >= minY; sy--) {
            BlockState state = chunk.getBlockState(new BlockPos(x, sy, z));
            if (state.is(Blocks.WATER)) {
                waterCount++;
                if (waterTop == Integer.MIN_VALUE) {
                    waterTop = sy;
                }
                continue;
            }
            if (waterTop != Integer.MIN_VALUE && !state.isAir()) {
                floorY = sy;
                break;
            }
        }

        if (waterTop == Integer.MIN_VALUE || floorY == Integer.MIN_VALUE || !isOceanSourceState(Blocks.WATER.defaultBlockState(), waterTop, seaLevel)) {
            return OceanColumnInfo.NOT_OCEAN;
        }

        int waterDepth = waterTop - floorY;
        Holder<Biome> biomeHolder = chunk.getNoiseBiome(x >> 2, seaLevel >> 2, z >> 2);
        String biomePath = biomeHolder.unwrapKey().map(key -> key.location().getPath()).orElse("");

        boolean coralOcean = biomePath.contains("warm_ocean");
        boolean warmOcean = coralOcean || biomePath.contains("lukewarm_ocean");
        boolean coldOcean = biomePath.contains("cold_ocean") || biomePath.contains("frozen_ocean");
        boolean gravelHeavy = coldOcean || (!warmOcean && (waterDepth >= 10 || waterCount >= 12 || biomePath.contains("deep_ocean")));
        int floraType = coralOcean ? OCEAN_FLORA_CORAL : OCEAN_FLORA_REGULAR;
        return new OceanColumnInfo(true, warmOcean, gravelHeavy, floraType, waterDepth);
    }

    private void applyOceanSeabed(
            WorldGenLevel level,
            ChunkAccess chunk,
            int x,
            int z,
            int low,
            int high,
            OceanColumnInfo oceanInfo,
            List<BoundingBox> protectedBoxes
    ) {
        boolean foundWater = false;
        for (int y = high; y >= low; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = chunk.getBlockState(pos);
            if (state.is(Blocks.WATER)) {
                foundWater = true;
                continue;
            }
            if (!foundWater || state.isAir() || isInsideProtected(pos, protectedBoxes)) {
                continue;
            }
            chunk.setBlockState(pos, seabedStateFor(oceanInfo, x, y, z), false);
            decorateOceanFloor(level, chunk, x, z, y, high, oceanInfo, protectedBoxes);
            return;
        }
    }

    private static BlockState seabedStateFor(OceanColumnInfo oceanInfo, int x, int y, int z) {
        long mixed = ((long) x * 341873128712L) ^ ((long) y * 42317861L) ^ ((long) z * 132897987541L);
        RandomSource random = new XoroshiroRandomSource(mixed);
        float roll = random.nextFloat();

        if (oceanInfo.warmOcean()) {
            if (roll < 0.7F) {
                return Blocks.SAND.defaultBlockState();
            }
            if (roll < 0.82F) {
                return Blocks.GRAVEL.defaultBlockState();
            }
            if (roll < 0.92F) {
                return Blocks.DIRT.defaultBlockState();
            }
            return Blocks.CLAY.defaultBlockState();
        }

        if (oceanInfo.gravelHeavy()) {
            if (roll < 0.66F) {
                return Blocks.GRAVEL.defaultBlockState();
            }
            if (roll < 0.8F) {
                return Blocks.SAND.defaultBlockState();
            }
            if (roll < 0.9F) {
                return Blocks.DIRT.defaultBlockState();
            }
            return Blocks.CLAY.defaultBlockState();
        }

        if (roll < 0.6F) {
            return Blocks.SAND.defaultBlockState();
        }
        if (roll < 0.76F) {
            return Blocks.GRAVEL.defaultBlockState();
        }
        if (roll < 0.9F) {
            return Blocks.DIRT.defaultBlockState();
        }
        return Blocks.CLAY.defaultBlockState();
    }

    private void decorateOceanFloor(
            WorldGenLevel level,
            ChunkAccess chunk,
            int x,
            int z,
            int floorY,
            int high,
            OceanColumnInfo oceanInfo,
            List<BoundingBox> protectedBoxes
    ) {
        if (oceanInfo.floraType() == OCEAN_FLORA_NONE) {
            return;
        }

        BlockPos waterPos = new BlockPos(x, floorY + 1, z);
        if (!chunk.getBlockState(waterPos).is(Blocks.WATER) || isInsideProtected(waterPos, protectedBoxes)) {
            return;
        }

        int waterDepth = 0;
        for (int y = floorY + 1; y <= high; y++) {
            if (!chunk.getBlockState(new BlockPos(x, y, z)).is(Blocks.WATER)) {
                break;
            }
            waterDepth++;
        }

        long mixed = seed ^ ((long) x * 73428767L) ^ ((long) floorY * 912931L) ^ ((long) z * 19349663L);
        RandomSource random = new XoroshiroRandomSource(mixed);
        if (oceanInfo.floraType() == OCEAN_FLORA_CORAL) {
            if (waterDepth >= 2 && random.nextFloat() < 0.18F) {
                tryPlaceCoralFeature(level, waterPos, random);
            }
            if (waterDepth >= 1 && random.nextFloat() < 0.15F) {
                BlockState seaPickle = Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES, 1 + random.nextInt(4));
                chunk.setBlockState(waterPos, seaPickle, false);
            }
            return;
        }

        if (waterDepth >= 3 && random.nextFloat() < 0.12F) {
            int height = Math.min(waterDepth, 2 + random.nextInt(4));
            for (int i = 1; i < height; i++) {
                chunk.setBlockState(new BlockPos(x, floorY + i, z), Blocks.KELP_PLANT.defaultBlockState(), false);
            }
            chunk.setBlockState(new BlockPos(x, floorY + height, z), Blocks.KELP.defaultBlockState(), false);
            return;
        }

        if (random.nextFloat() < 0.3F) {
            chunk.setBlockState(waterPos, Blocks.SEAGRASS.defaultBlockState(), false);
        }
    }

    private void tryPlaceCoralFeature(WorldGenLevel level, BlockPos pos, RandomSource random) {
        var configuredFeatures = level.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE);
        for (int attempt = 0; attempt < 2; attempt++) {
            ResourceLocation id = CORAL_FEATURE_IDS[random.nextInt(CORAL_FEATURE_IDS.length)];
            ResourceKey<ConfiguredFeature<?, ?>> key = ResourceKey.create(Registries.CONFIGURED_FEATURE, id);
            ConfiguredFeature<?, ?> feature = configuredFeatures.get(key).map(Holder::value).orElse(null);
            if (feature == null) {
                continue;
            }
            if (feature.place(level, this, random, pos)) {
                return;
            }
        }
    }

    private void sealOceanIslandBottom(
            ChunkAccess chunk,
            SkylandsIslands.Island island,
            int x,
            int z,
            int low,
            int high,
            List<BoundingBox> protectedBoxes
    ) {
        if (isOceanBreachColumn(island, x, z)) {
            return;
        }
        for (int y = low; y <= high; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = chunk.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (isInsideProtected(pos, protectedBoxes)) {
                return;
            }
            BlockPos below = pos.below();
            if (!chunk.getBlockState(below).isAir()) {
                continue;
            }
            chunk.setBlockState(pos, Blocks.GLASS.defaultBlockState(), false);
            return;
        }
    }

    private boolean isOceanBreachColumn(SkylandsIslands.Island island, int x, int z) {
        long mixed = seed ^ island.noiseSeed() ^ 0x6B72656163684CL;
        RandomSource random = new XoroshiroRandomSource(mixed);
        double breachAngle = random.nextDouble() * (Math.PI * 2.0D);
        double halfWidth = 0.24D + random.nextDouble() * 0.18D;
        double innerRadius = island.radius() * (0.62D + random.nextDouble() * 0.12D);
        double dx = x - island.centerX();
        double dz = z - island.centerZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < innerRadius) {
            return false;
        }

        double angle = Math.atan2(dz, dx);
        double angleDelta = Math.abs(Mth.wrapDegrees((float) Math.toDegrees(angle - breachAngle))) * (Math.PI / 180.0D);
        if (angleDelta > halfWidth) {
            return false;
        }

        long raggedSeed = mixed ^ ((long) x * 73428767L) ^ ((long) z * 912931L);
        RandomSource ragged = new XoroshiroRandomSource(raggedSeed);
        double raggedEdge = innerRadius + (angleDelta / halfWidth) * island.radius() * 0.14D + (ragged.nextDouble() - 0.5D) * 5.0D;
        return dist >= raggedEdge;
    }

    private int islandCenterY(SkylandsIslands.Island island) {
        int base = SkylandsConfig.CENTER_Y.getAsInt();
        int jitter = SkylandsConfig.HEIGHT_JITTER.getAsInt();
        if (jitter <= 0) {
            return base;
        }
        long mixed = seed ^ island.noiseSeed();
        RandomSource random = new XoroshiroRandomSource(mixed);
        return base + (random.nextInt(jitter * 2 + 1) - jitter);
    }

    private List<BoundingBox> strongholdBoxesShifted(RegistryAccess registryAccess, StructureManager structureManager, ChunkAccess chunk) {
        Structure stronghold = registryAccess.lookupOrThrow(Registries.STRUCTURE).get(STRONGHOLD_KEY).map(Holder::value).orElse(null);
        if (stronghold == null) {
            return List.of();
        }

        List<BoundingBox> boxes = new ArrayList<>();

        Map<Structure, StructureStart> starts = chunk.getAllStarts();
        StructureStart inChunk = starts.get(stronghold);
        if (inChunk != null && inChunk.isValid()) {
            boxes.add(shiftStructureBox(inChunk.getBoundingBox()));
        }

        structureManager.fillStartsForStructure(stronghold, chunk.getReferencesForStructure(stronghold), start -> {
            if (start != null && start.isValid()) {
                boxes.add(shiftStructureBox(start.getBoundingBox()));
            }
        });

        return boxes;
    }

    private BoundingBox shiftStructureBox(BoundingBox box) {
        int cx = (box.minX() + box.maxX()) / 2;
        int cz = (box.minZ() + box.maxZ()) / 2;
        SkylandsIslands.Island island = SkylandsIslands.nearestIsland(seed, cx, cz);
        int dy = islandCenterY(island) - delegate.getSeaLevel();
        return new BoundingBox(
                box.minX(), box.minY() + dy, box.minZ(),
                box.maxX(), box.maxY() + dy, box.maxZ()
        );
    }

    private void wrapStrongholds(ChunkAccess chunk, List<BoundingBox> boxes) {
        int padding = SkylandsConfig.STRONGHOLD_WRAP_PADDING.getAsInt();
        boolean deleteIfNotOnIsland = SkylandsConfig.STRONGHOLD_DELETE_IF_NOT_ON_ISLAND.getAsBoolean();
        int mossSeeds = SkylandsConfig.STRONGHOLD_MOSS_SEEDS.getAsInt();
        int mossSpreadSteps = SkylandsConfig.STRONGHOLD_MOSS_SPREAD_STEPS.getAsInt();
        double mossSpreadChance = SkylandsConfig.STRONGHOLD_MOSS_SPREAD_CHANCE.getAsDouble();

        for (BoundingBox box : boxes) {
            int cx = (box.minX() + box.maxX()) / 2;
            int cz = (box.minZ() + box.maxZ()) / 2;
            if (deleteIfNotOnIsland) {
                SkylandsIslands.Island island = SkylandsIslands.nearestIsland(seed, cx, cz);
                long dx = (long) cx - island.centerX();
                long dz = (long) cz - island.centerZ();
                if ((double) (dx * dx + dz * dz) > (double) island.radius() * (double) island.radius()) {
                    BoundingBox clipped = clipBoxToChunk(chunk, box);
                    if (clipped != null) {
                        clearBox(chunk, clipped);
                    }
                    continue;
                }
            }

            BoundingBox expanded = new BoundingBox(
                    box.minX() - padding, box.minY() - padding, box.minZ() - padding,
                    box.maxX() + padding, box.maxY() + padding, box.maxZ() + padding
            );
            BoundingBox clippedExpanded = clipBoxToChunk(chunk, expanded);
            if (clippedExpanded == null) {
                continue;
            }
            fillEllipsoid(chunk, clippedExpanded);
            if (mossSeeds > 0 && mossSpreadSteps > 0) {
                long mixed = seed ^ ((long) cx * 341873128712L) ^ ((long) cz * 132897987541L);
                RandomSource random = new XoroshiroRandomSource(mixed);
                applyMoss(chunk, clippedExpanded, box, random, mossSeeds, mossSpreadSteps, mossSpreadChance);
            }
        }
    }

    private void generateOreFeatures(ChunkAccess chunk, List<BoundingBox> protectedBoxes) {
        generateOreIslands(chunk, protectedBoxes);
        generateDebrisFields(chunk, protectedBoxes);
    }

    private void generateOreIslands(ChunkAccess chunk, List<BoundingBox> protectedBoxes) {
        int spacing = SkylandsConfig.ORE_ISLAND_SPACING.getAsInt();
        int minRadius = SkylandsConfig.ORE_ISLAND_MIN_RADIUS.getAsInt();
        int maxRadius = Math.max(minRadius, SkylandsConfig.ORE_ISLAND_MAX_RADIUS.getAsInt());
        double oreChance = SkylandsConfig.ORE_ISLAND_ORE_CHANCE.getAsDouble();

        ChunkPos chunkPos = chunk.getPos();
        int minX = chunkPos.getMinBlockX();
        int maxX = chunkPos.getMaxBlockX();
        int minZ = chunkPos.getMinBlockZ();
        int maxZ = chunkPos.getMaxBlockZ();
        int padding = maxRadius + 2;

        int minCellX = SkylandsIslands.cellForCoordinate(minX - padding, spacing);
        int maxCellX = SkylandsIslands.cellForCoordinate(maxX + padding, spacing);
        int minCellZ = SkylandsIslands.cellForCoordinate(minZ - padding, spacing);
        int maxCellZ = SkylandsIslands.cellForCoordinate(maxZ + padding, spacing);
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                ExtraIsland island = oreIslandForCell(cellX, cellZ, minRadius, maxRadius);
                if (!overlapsChunk(chunkPos, island)) {
                    continue;
                }
                placeExtraIsland(chunk, protectedBoxes, island, oreChance, true);
            }
        }
    }

    private void generateDebrisFields(ChunkAccess chunk, List<BoundingBox> protectedBoxes) {
        int threshold = SkylandsConfig.DEBRIS_LARGE_ISLAND_RADIUS.getAsInt();
        int minCount = SkylandsConfig.DEBRIS_MIN_COUNT.getAsInt();
        int maxCount = Math.max(minCount, SkylandsConfig.DEBRIS_MAX_COUNT.getAsInt());
        int maxRadius = SkylandsConfig.DEBRIS_MAX_RADIUS.getAsInt();
        double oreChance = SkylandsConfig.DEBRIS_ORE_CHANCE.getAsDouble();

        if (maxCount <= 0) {
            return;
        }

        ChunkPos chunkPos = chunk.getPos();
        int minX = chunkPos.getMinBlockX();
        int maxX = chunkPos.getMaxBlockX();
        int minZ = chunkPos.getMinBlockZ();
        int maxZ = chunkPos.getMaxBlockZ();
        int searchPadding = SkylandsConfig.MAX_RADIUS.getAsInt() + 128;
        int spacing = SkylandsConfig.SPACING.getAsInt();

        int minCellX = SkylandsIslands.cellForCoordinate(minX - searchPadding, spacing);
        int maxCellX = SkylandsIslands.cellForCoordinate(maxX + searchPadding, spacing);
        int minCellZ = SkylandsIslands.cellForCoordinate(minZ - searchPadding, spacing);
        int maxCellZ = SkylandsIslands.cellForCoordinate(maxZ + searchPadding, spacing);

        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                SkylandsIslands.Island parent = SkylandsIslands.islandForCell(seed, cellX, cellZ);
                if (parent.radius() < threshold) {
                    continue;
                }
                OrePalette orePalette = orePaletteFor(SkylandsIslandBiomes.select(seed, parent));

                RandomSource random = new XoroshiroRandomSource(seed ^ parent.noiseSeed() ^ 0x5EEDBEEFL);
                int count = Mth.nextInt(random, minCount, maxCount);
                for (int i = 0; i < count; i++) {
                    ExtraIsland debris = debrisIsland(parent, random, maxRadius, orePalette);
                    if (overlapsChunk(chunkPos, debris)) {
                        placeExtraIsland(chunk, protectedBoxes, debris, oreChance, true);
                    }
                    int satelliteCount = 2 + random.nextInt(3);
                    for (int j = 0; j < satelliteCount; j++) {
                        ExtraIsland satellite = satelliteDebris(debris, random);
                        if (!overlapsChunk(chunkPos, satellite)) {
                            continue;
                        }
                        placeExtraIsland(chunk, protectedBoxes, satellite, oreChance, true);
                    }
                }
            }
        }
    }

    private ExtraIsland oreIslandForCell(int cellX, int cellZ, int minRadius, int maxRadius) {
        int spacing = SkylandsConfig.ORE_ISLAND_SPACING.getAsInt();
        int heightJitter = SkylandsConfig.ORE_ISLAND_HEIGHT_JITTER.getAsInt();
        int orePasses = SkylandsConfig.ORE_ISLAND_PASSES.getAsInt();

        long mixed = seed ^ 0x0F1E15ADEL;
        mixed ^= (long) cellX * 341873128712L;
        mixed ^= (long) cellZ * 132897987541L;

        RandomSource random = new XoroshiroRandomSource(mixed);
        int radiusXZ = Mth.nextInt(random, minRadius, maxRadius);
        int radiusY = Math.max(2, Mth.floor((double) radiusXZ * Mth.lerp(random.nextDouble(), 0.85D, 1.10D)));

        int cellBaseX = cellX * spacing + spacing / 2;
        int cellBaseZ = cellZ * spacing + spacing / 2;
        int offsetLimit = Math.max(0, spacing / 2 - radiusXZ - 8);
        int offsetX = offsetLimit == 0 ? 0 : random.nextInt(offsetLimit * 2 + 1) - offsetLimit;
        int offsetZ = offsetLimit == 0 ? 0 : random.nextInt(offsetLimit * 2 + 1) - offsetLimit;
        int centerY = SkylandsConfig.ORE_ISLAND_CENTER_Y.getAsInt();
        if (heightJitter > 0) {
            centerY += random.nextInt(heightJitter * 2 + 1) - heightJitter;
        }
        int minCenterY = getMinY() + radiusY;
        int maxCenterY = getMinY() + getGenDepth() - 1 - radiusY;
        centerY = Mth.clamp(centerY, minCenterY, maxCenterY);

        SkylandsIslands.Island nearestIsland = SkylandsIslands.nearestIsland(seed, cellBaseX + offsetX, cellBaseZ + offsetZ);
        OrePalette orePalette = orePaletteFor(SkylandsIslandBiomes.select(seed, nearestIsland));
        int profile = random.nextDouble() < 0.05D ? PROFILE_AMETHYST : PROFILE_ORE;
        List<BlockState> dominantOreStates = profile == PROFILE_ORE
                ? selectDominantOreStates(centerY, orePalette, random)
                : List.of();
        ExtraIsland island = new ExtraIsland(
                cellBaseX + offsetX,
                centerY,
                cellBaseZ + offsetZ,
                radiusXZ,
                radiusY,
                radiusXZ,
                random.nextLong(),
                0.2D,
                profile,
                orePalette,
                orePasses,
                dominantOreStates
        );
        return island;
    }

    private ExtraIsland debrisIsland(SkylandsIslands.Island parent, RandomSource random, int maxRadius, OrePalette orePalette) {
        int radiusXZ = Mth.nextInt(random, 2, Math.max(2, maxRadius));
        int radiusY = Math.max(1, radiusXZ / 2);
        double angle = random.nextDouble() * (Math.PI * 2.0D);
        int distance = parent.radius() + 18 + random.nextInt(72);
        int centerX = parent.centerX() + Mth.floor(Math.cos(angle) * distance);
        int centerZ = parent.centerZ() + Mth.floor(Math.sin(angle) * distance);
        int parentCenterY = islandCenterY(parent);
        int centerY = parentCenterY - Mth.nextInt(random, 10, 28);
        return new ExtraIsland(
                centerX,
                centerY,
                centerZ,
                radiusXZ,
                radiusY,
                radiusXZ,
                random.nextLong(),
                0.42D,
                PROFILE_ORE,
                orePalette,
                1,
                selectDominantOreStates(centerY, orePalette, random)
        );
    }

    private ExtraIsland satelliteDebris(ExtraIsland parent, RandomSource random) {
        int radiusXZ = Math.max(1, parent.radiusX() - random.nextInt(3) - 1);
        int radiusY = Math.max(1, parent.radiusY() - (random.nextBoolean() ? 1 : 0));
        double angle = random.nextDouble() * (Math.PI * 2.0D);
        int distance = parent.radiusX() + 2 + random.nextInt(8);
        int centerX = parent.centerX() + Mth.floor(Math.cos(angle) * distance);
        int centerZ = parent.centerZ() + Mth.floor(Math.sin(angle) * distance);
        int centerY = parent.centerY() - random.nextInt(5);
        return new ExtraIsland(
                centerX,
                centerY,
                centerZ,
                radiusXZ,
                radiusY,
                radiusXZ,
                random.nextLong(),
                0.5D,
                PROFILE_ORE,
                parent.orePalette(),
                1,
                selectDominantOreStates(centerY, parent.orePalette(), random)
        );
    }

    private boolean overlapsChunk(ChunkPos chunkPos, ExtraIsland island) {
        return island.centerX() + island.radiusX() >= chunkPos.getMinBlockX()
                && island.centerX() - island.radiusX() <= chunkPos.getMaxBlockX()
                && island.centerZ() + island.radiusZ() >= chunkPos.getMinBlockZ()
                && island.centerZ() - island.radiusZ() <= chunkPos.getMaxBlockZ();
    }

    private void placeExtraIsland(
            ChunkAccess chunk,
            List<BoundingBox> protectedBoxes,
            ExtraIsland island,
            double oreChance,
            boolean onlyReplaceAir
    ) {
        ChunkPos chunkPos = chunk.getPos();
        int minX = Math.max(chunkPos.getMinBlockX(), island.centerX() - island.radiusX());
        int maxX = Math.min(chunkPos.getMaxBlockX(), island.centerX() + island.radiusX());
        int minZ = Math.max(chunkPos.getMinBlockZ(), island.centerZ() - island.radiusZ());
        int maxZ = Math.min(chunkPos.getMaxBlockZ(), island.centerZ() + island.radiusZ());
        int minY = Math.max(getMinY(), island.centerY() - island.radiusY());
        int maxY = Math.min(getMinY() + getGenDepth() - 1, island.centerY() + island.radiusY());

        double invRx2 = 1.0D / Math.max(1.0D, island.radiusX() * island.radiusX());
        double invRy2 = 1.0D / Math.max(1.0D, island.radiusY() * island.radiusY());
        double invRz2 = 1.0D / Math.max(1.0D, island.radiusZ() * island.radiusZ());
        for (int x = minX; x <= maxX; x++) {
            double dx = x - island.centerX();
            double dx2 = dx * dx * invRx2;
            for (int z = minZ; z <= maxZ; z++) {
                double dz = z - island.centerZ();
                double dxz2 = dx2 + dz * dz * invRz2;
                if (dxz2 > 1.0D) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    double dy = y - island.centerY();
                    if (dxz2 + dy * dy * invRy2 > 1.0D) {
                        continue;
                    }

                    BlockPos pos = new BlockPos(x, y, z);
                    if (isInsideProtected(pos, protectedBoxes)) {
                        continue;
                    }
                    if (onlyReplaceAir && !chunk.getBlockState(pos).isAir()) {
                        continue;
                    }
                    if (!passesIslandShape(island, x, y, z, dxz2 + dy * dy * invRy2)) {
                        continue;
                    }
                    double dist = normalizedDist(x, y, z, island);
                    BlockState placedState = islandStateFor(island, x, y, z, dist, oreChance);
                    chunk.setBlockState(pos, placedState, false);
                }
            }
        }
    }

    private static double normalizedDist(int x, int y, int z, ExtraIsland island) {
        double dx = (double) (x - island.centerX()) / Math.max(1.0D, island.radiusX());
        double dy = (double) (y - island.centerY()) / Math.max(1.0D, island.radiusY());
        double dz = (double) (z - island.centerZ()) / Math.max(1.0D, island.radiusZ());
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean passesIslandShape(ExtraIsland island, int x, int y, int z, double normalizedDist) {
        if (normalizedDist < 0.72D) {
            return true;
        }
        long mixed = island.seed();
        mixed ^= (long) x * 73428767L;
        mixed ^= (long) y * 912931L;
        mixed ^= (long) z * 19349663L;
        RandomSource random = new XoroshiroRandomSource(mixed);
        double threshold = 1.0D - island.roughness() * random.nextDouble();
        return normalizedDist <= threshold;
    }

    private static boolean isInsideProtected(BlockPos pos, List<BoundingBox> protectedBoxes) {
        for (BoundingBox box : protectedBoxes) {
            if (box.isInside(pos)) {
                return true;
            }
        }
        return false;
    }

    private BlockState islandStateFor(ExtraIsland island, int x, int y, int z, double normalizedDist, double oreChance) {
        if (island.profile() == PROFILE_AMETHYST) {
            return amethystStateFor(island, x, y, z, normalizedDist);
        }
        return rockStateFor(island, x, y, z, normalizedDist, oreChance);
    }

    private BlockState rockStateFor(ExtraIsland island, int x, int y, int z, double normalizedDist, double oreChance) {
        BlockState shellState = shellStateFor(island, normalizedDist);
        if (shellState != null) {
            return shellState;
        }
        boolean deep = island.centerY() < 0;
        int orePasses = Math.max(1, island.orePasses());
        for (int pass = 0; pass < orePasses; pass++) {
            BlockState dominantOre = dominantOreStateForPass(island, x, y, z, pass, oreChance);
            if (dominantOre != null) {
                return dominantOre;
            }
        }
        long mixed = island.seed();
        mixed ^= (long) x * 341873128712L;
        mixed ^= (long) y * 42317861L;
        mixed ^= (long) z * 132897987541L;
        RandomSource random = new XoroshiroRandomSource(mixed);
        double backgroundOreChance = oreChance * Mth.lerp(Mth.clamp(1.0D - normalizedDist, 0.0D, 1.0D), 0.10D, 0.26D);
        if (random.nextDouble() < backgroundOreChance) {
            return secondaryOreStateForSphere(random, deep, island.orePalette(), island.dominantOreStates());
        }
        return hostRockStateFor(island, x, y, z, random, deep);
    }

    private static BlockState shellStateFor(ExtraIsland island, double normalizedDist) {
        double radial = Math.sqrt(Math.max(0.0D, normalizedDist));
        double radiusScale = Math.max(3.0D, Math.min(island.radiusX(), Math.min(island.radiusY(), island.radiusZ())));
        double depthFromSurface = Math.max(0.0D, 1.0D - radial) * radiusScale;
        int outerThickness = shellThickness(island.seed(), 0x41C64E6DL);
        int innerThickness = shellThickness(island.seed(), 0x9E3779B9L);
        if (depthFromSurface <= (double) outerThickness) {
            return Blocks.SMOOTH_BASALT.defaultBlockState();
        }
        if (depthFromSurface <= (double) (outerThickness + innerThickness)) {
            return Blocks.CALCITE.defaultBlockState();
        }
        return null;
    }

    private static int shellThickness(long seed, long salt) {
        long mixed = seed ^ salt;
        return 1 + Math.floorMod((int) (mixed ^ (mixed >>> 32)), 2);
    }

    private static BlockState amethystStateFor(ExtraIsland island, int x, int y, int z, double normalizedDist) {
        long mixed = island.seed();
        mixed ^= (long) x * 984121L;
        mixed ^= (long) y * 611953L;
        mixed ^= (long) z * 357239L;
        RandomSource random = new XoroshiroRandomSource(mixed);
        if (normalizedDist > 0.83D) {
            return random.nextFloat() < 0.6F ? Blocks.CALCITE.defaultBlockState() : Blocks.SMOOTH_BASALT.defaultBlockState();
        }
        return random.nextFloat() < 0.1F ? Blocks.BUDDING_AMETHYST.defaultBlockState() : Blocks.AMETHYST_BLOCK.defaultBlockState();
    }

    private static BlockState hostRockStateFor(ExtraIsland island, int x, int y, int z, RandomSource random, boolean deep) {
        if (deep) {
            int band = Math.floorMod(y + island.centerY(), 7);
            if (band == 0 || band == 1) {
                return Blocks.TUFF.defaultBlockState();
            }
            return Blocks.DEEPSLATE.defaultBlockState();
        }

        float variant = random.nextFloat();
        if (variant < 0.10F) {
            return Blocks.ANDESITE.defaultBlockState();
        }
        if (variant < 0.17F) {
            return Blocks.DIORITE.defaultBlockState();
        }
        if (variant < 0.24F) {
            return Blocks.GRANITE.defaultBlockState();
        }
        return Blocks.STONE.defaultBlockState();
    }

    private BlockState oreStateFor(RandomSource random, boolean deep) {
        List<BlockState> defaults = defaultOreStates(deep);
        return defaults.get(random.nextInt(defaults.size()));
    }

    private BlockState oreStateFor(RandomSource random, boolean deep, OrePalette orePalette) {
        if (orePalette != null) {
            List<BlockState> configured = deep ? orePalette.deepStates() : orePalette.surfaceStates();
            if (!configured.isEmpty()) {
                return configured.get(random.nextInt(configured.size()));
            }
        }
        return oreStateFor(random, deep);
    }

    private static BlockState dominantOreStateForPass(
            ExtraIsland island,
            int x,
            int y,
            int z,
            int passIndex,
            double oreChance
    ) {
        List<BlockState> dominantStates = island.dominantOreStates();
        if (dominantStates.isEmpty()) {
            return null;
        }
        long mixed = island.seed() ^ ((long) (passIndex + 1) * 0x9E3779B97F4A7C15L);
        RandomSource passRandom = new XoroshiroRandomSource(mixed);
        double centerX = island.centerX() + Mth.lerp(passRandom.nextDouble(), -island.radiusX() * 0.42D, island.radiusX() * 0.42D);
        double centerY = island.centerY() + Mth.lerp(passRandom.nextDouble(), -island.radiusY() * 0.35D, island.radiusY() * 0.35D);
        double centerZ = island.centerZ() + Mth.lerp(passRandom.nextDouble(), -island.radiusZ() * 0.42D, island.radiusZ() * 0.42D);
        double rx = Math.max(1.6D, island.radiusX() * Mth.lerp(passRandom.nextDouble(), 0.18D, 0.34D));
        double ry = Math.max(1.4D, island.radiusY() * Mth.lerp(passRandom.nextDouble(), 0.22D, 0.40D));
        double rz = Math.max(1.6D, island.radiusZ() * Mth.lerp(passRandom.nextDouble(), 0.18D, 0.34D));
        double dx = ((double) x - centerX) / rx;
        double dy = ((double) y - centerY) / ry;
        double dz = ((double) z - centerZ) / rz;
        double normalized = dx * dx + dy * dy + dz * dz;
        if (normalized > 1.0D) {
            return null;
        }

        long localMixed = mixed;
        localMixed ^= (long) x * 73428767L;
        localMixed ^= (long) y * 912931L;
        localMixed ^= (long) z * 19349663L;
        RandomSource localRandom = new XoroshiroRandomSource(localMixed);
        double fillThreshold = Mth.lerp(localRandom.nextDouble(), 0.30D, 0.58D);
        if (normalized > fillThreshold || localRandom.nextDouble() >= oreChance) {
            return null;
        }
        int index = Math.floorMod(passIndex + localRandom.nextInt(dominantStates.size()), dominantStates.size());
        return dominantStates.get(index);
    }

    private List<BlockState> selectDominantOreStates(int centerY, OrePalette orePalette, RandomSource random) {
        List<BlockState> candidates = preferredOreStatesForY(centerY, orePalette);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<BlockState> pool = new ArrayList<>(candidates);
        int targetCount = Mth.nextInt(random, 1, Math.min(3, pool.size()));
        List<BlockState> selected = new ArrayList<>(targetCount);
        while (!pool.isEmpty() && selected.size() < targetCount) {
            selected.add(pool.remove(random.nextInt(pool.size())));
        }
        return List.copyOf(selected);
    }

    private List<BlockState> preferredOreStatesForY(int centerY, OrePalette orePalette) {
        List<BlockState> baseStates = new ArrayList<>();
        if (orePalette != null) {
            List<BlockState> configured = centerY < 0 ? orePalette.deepStates() : orePalette.surfaceStates();
            for (BlockState state : configured) {
                if (!isBlacklistedIslandOreState(state) && !containsOreBlock(baseStates, state)) {
                    baseStates.add(state);
                }
            }
        }
        if (baseStates.isEmpty()) {
            baseStates.addAll(defaultOreStates(centerY < 0));
        }
        List<BlockState> preferred = new ArrayList<>();
        List<BlockState> fallback = new ArrayList<>();
        for (BlockState state : baseStates) {
            if (containsOreBlock(fallback, state)) {
                continue;
            }
            fallback.add(state);
            if (isPreferredOreForY(state, centerY)) {
                preferred.add(state);
            }
        }
        return preferred.isEmpty() ? fallback : preferred;
    }

    private List<BlockState> baseOreStatesForLocalY(int localY, OrePalette orePalette) {
        List<BlockState> baseStates = new ArrayList<>();
        boolean deepBand = localY < 0;
        if (orePalette != null) {
            List<BlockState> configured = deepBand ? orePalette.deepStates() : orePalette.surfaceStates();
            for (BlockState state : configured) {
                if (!isBlacklistedIslandOreState(state) && !containsOreBlock(baseStates, state)) {
                    baseStates.add(state);
                }
            }
        }
        if (baseStates.isEmpty()) {
            for (BlockState state : defaultOreStates(deepBand)) {
                if (!containsOreBlock(baseStates, state)) {
                    baseStates.add(state);
                }
            }
        }
        return baseStates;
    }

    private List<OrePaletteEntry> baseOreEntriesForLocalY(int localY, OrePalette orePalette) {
        List<OrePaletteEntry> baseEntries = new ArrayList<>();
        boolean deepBand = localY < 0;
        if (orePalette != null) {
            List<OrePaletteEntry> configured = deepBand ? orePalette.deepEntries() : orePalette.surfaceEntries();
            for (OrePaletteEntry entry : configured) {
                if (!containsOreEntry(baseEntries, entry) && !isBlacklistedIslandOreState(entry.state())) {
                    baseEntries.add(entry);
                }
            }
        }
        if (baseEntries.isEmpty()) {
            for (BlockState state : defaultOreStates(deepBand)) {
                OrePaletteEntry entry = new OrePaletteEntry(state, null);
                if (!containsOreEntry(baseEntries, entry)) {
                    baseEntries.add(entry);
                }
            }
        }
        return baseEntries;
    }

    private List<BlockState> preferredOreStatesForLocalY(int localY, OrePalette orePalette) {
        List<BlockState> baseStates = baseOreStatesForLocalY(localY, orePalette);
        List<BlockState> preferred = new ArrayList<>();
        List<BlockState> fallback = new ArrayList<>();
        for (BlockState state : baseStates) {
            if (containsOreBlock(fallback, state)) {
                continue;
            }
            fallback.add(state);
            if (isPreferredOreForLocalY(state, localY)) {
                preferred.add(state);
            }
        }
        return preferred.isEmpty() ? fallback : preferred;
    }

    private OreFeatureTemplate selectIslandOreTemplate(int localY, OrePalette orePalette, int islandNoiseSeed, int blobIndex) {
        List<OrePaletteEntry> entries = baseOreEntriesForLocalY(localY, orePalette);
        if (entries.isEmpty()) {
            return null;
        }
        List<OreFeatureTemplate> templates = new ArrayList<>(entries.size());
        List<Double> weights = new ArrayList<>(entries.size());
        List<String> seen = new ArrayList<>();
        double totalWeight = 0.0D;
        for (OrePaletteEntry entry : entries) {
            String key = oreEntryKey(entry);
            if (seen.contains(key)) {
                continue;
            }
            seen.add(key);
            OreFeatureTemplate template = oreFeatureTemplateFor(entry);
            if (template == null) {
                continue;
            }
            double weight = oreTemplateWeightForLocalY(template, localY);
            if (weight <= 0.0D) {
                continue;
            }
            templates.add(template);
            weights.add(weight);
            totalWeight += weight;
        }
        if (templates.isEmpty()) {
            List<BlockState> fallbackStates = preferredOreStatesForLocalY(localY, orePalette);
            seen.clear();
            for (BlockState state : fallbackStates) {
                String key = oreEntryKey(new OrePaletteEntry(state, null));
                if (seen.contains(key)) {
                    continue;
                }
                seen.add(key);
                OreFeatureTemplate template = oreFeatureTemplateFor(state);
                double weight = Math.max(0.2D, template.density());
                templates.add(template);
                weights.add(weight);
                totalWeight += weight;
            }
        }
        if (templates.isEmpty()) {
            return null;
        }
        double roll = blobSelectionValue(islandNoiseSeed, 0x6A09E667L, blobIndex) * Math.max(1.0D, totalWeight);
        double accumulated = 0.0D;
        for (int i = 0; i < templates.size(); i++) {
            OreFeatureTemplate template = templates.get(i);
            accumulated += weights.get(i);
            if (roll <= accumulated) {
                return template;
            }
        }
        return templates.get(templates.size() - 1);
    }

    private double averageIslandOreDensity(SkylandsIslandBiomeDefinition islandBiome) {
        OrePalette orePalette = orePaletteFor(islandBiome);
        int[] sampleLocalYs = new int[] { -48, -24, 0, 24, 48, 64, 96, 160, 240, 312 };
        List<OrePaletteEntry> states = new ArrayList<>();
        for (int sampleLocalY : sampleLocalYs) {
            states.addAll(baseOreEntriesForLocalY(sampleLocalY, orePalette));
        }
        if (states.isEmpty()) {
            return 6.0D;
        }
        List<String> seen = new ArrayList<>();
        double total = 0.0D;
        int count = 0;
        for (OrePaletteEntry entry : states) {
            String key = oreEntryKey(entry);
            if (seen.contains(key)) {
                continue;
            }
            seen.add(key);
            OreFeatureTemplate template = oreFeatureTemplateFor(entry);
            if (template == null) {
                continue;
            }
            double sampledWeight = 0.0D;
            int sampledCount = 0;
            for (int sampleLocalY : sampleLocalYs) {
                double weight = oreTemplateWeightForLocalY(template, sampleLocalY);
                if (weight > 0.0D) {
                    sampledWeight += weight;
                    sampledCount++;
                }
            }
            total += sampledCount <= 0 ? Math.max(0.2D, template.density()) : sampledWeight / (double) sampledCount;
            count++;
        }
        return count <= 0 ? 6.0D : total / (double) count;
    }

    private OreFeatureTemplate oreFeatureTemplateFor(OrePaletteEntry entry) {
        if (entry == null || entry.state() == null) {
            return null;
        }
        OreFeatureTemplateSet templateSet = oreFeatureTemplates.get(entry.state().getBlock());
        if (templateSet != null) {
            if (entry.sourceId() != null) {
                OreFeatureTemplate special = templateSet.specialTemplates().get(entry.sourceId());
                if (special != null) {
                    return special;
                }
                return null;
            }
            if (templateSet.genericTemplate() != null) {
                return templateSet.genericTemplate();
            }
        }
        if (entry.sourceId() != null) {
            return null;
        }
        return new OreFeatureTemplate(entry.state(), 6, 6.0D, List.of(OreHeightBand.fullRange(6.0D)));
    }

    private OreFeatureTemplate oreFeatureTemplateFor(BlockState state) {
        return oreFeatureTemplateFor(new OrePaletteEntry(state, null));
    }

    private List<ResourceLocation> resolveConfiguredOreSources(BlockState state, ResourceLocation sourceOrBiomeId) {
        if (state == null || isBlacklistedIslandOreState(state)) {
            return List.of();
        }
        OreFeatureTemplateSet templateSet = oreFeatureTemplates.get(state.getBlock());
        if (sourceOrBiomeId == null) {
            return templateSet == null || templateSet.genericTemplate() != null ? List.of() : List.of();
        }
        if (templateSet != null && templateSet.specialTemplates().containsKey(sourceOrBiomeId)) {
            return List.of(sourceOrBiomeId);
        }
        Map<ResourceLocation, List<ResourceLocation>> biomeMappings = oreBiomeSpecialSources.get(state.getBlock());
        if (biomeMappings == null) {
            return List.of();
        }
        List<ResourceLocation> sourceIds = biomeMappings.get(sourceOrBiomeId);
        return sourceIds == null ? List.of() : sourceIds;
    }

    private static double oreTemplateWeightForLocalY(OreFeatureTemplate template, int localY) {
        if (template.heightBands().isEmpty()) {
            return Math.max(0.2D, template.density());
        }
        double total = 0.0D;
        for (OreHeightBand band : template.heightBands()) {
            total += band.weightAt(localY);
        }
        return total;
    }

    private static boolean containsOreBlock(List<BlockState> states, BlockState candidate) {
        for (BlockState state : states) {
            if (state.getBlock() == candidate.getBlock()) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsOreEntry(List<OrePaletteEntry> states, OrePaletteEntry candidate) {
        String candidateKey = oreEntryKey(candidate);
        for (OrePaletteEntry entry : states) {
            if (oreEntryKey(entry).equals(candidateKey)) {
                return true;
            }
        }
        return false;
    }

    private static String oreEntryKey(OrePaletteEntry entry) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(entry.state().getBlock());
        return blockId + "@" + (entry.sourceId() == null ? "generic" : entry.sourceId().toString());
    }

    private List<BlockState> defaultOreStates(boolean deep) {
        List<BlockState> configured = deep ? defaultDeepOreStates : defaultSurfaceOreStates;
        if (!configured.isEmpty()) {
            return configured;
        }
        List<BlockState> fallback = new ArrayList<>();
        for (BlockState state : deep ? DEEP_ORE_STATES : SURFACE_ORE_STATES) {
            if (!isImplicitlySpecialOreBlock(state.getBlock())
                    && !isBlacklistedIslandOreState(state)
                    && !containsOreBlock(fallback, state)) {
                fallback.add(state);
            }
        }
        for (BlockState state : modOreStates(deep)) {
            if (!isBlacklistedIslandOreState(state) && !containsOreBlock(fallback, state)) {
                fallback.add(state);
            }
        }
        return fallback;
    }

    private static boolean isImplicitlySpecialOreBlock(Block block) {
        return block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE;
    }

    private static boolean isBlacklistedIslandOreState(BlockState state) {
        return state.is(Blocks.NETHER_GOLD_ORE) || state.is(Blocks.NETHER_QUARTZ_ORE);
    }

    private static boolean isPreferredOreForY(BlockState state, int centerY) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (!"minecraft".equals(key.getNamespace())) {
            return true;
        }
        String path = key.getPath();
        if (centerY <= -48) {
            return containsOreKeyword(path, "diamond", "redstone", "lapis", "gold", "emerald");
        }
        if (centerY <= -32) {
            return containsOreKeyword(path, "diamond", "redstone", "lapis", "gold", "iron", "emerald");
        }
        return containsOreKeyword(path, "coal", "iron", "copper", "lapis", "gold");
    }

    private static boolean isPreferredOreForLocalY(BlockState state, int localY) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (!"minecraft".equals(key.getNamespace())) {
            return true;
        }
        String path = key.getPath();
        if (localY <= -48) {
            return containsOreKeyword(path, "diamond", "redstone", "lapis", "gold", "emerald");
        }
        if (localY <= -16) {
            return containsOreKeyword(path, "diamond", "redstone", "lapis", "gold", "iron", "emerald");
        }
        if (localY <= 16) {
            return containsOreKeyword(path, "redstone", "lapis", "gold", "iron", "copper", "emerald");
        }
        if (localY <= 40) {
            return containsOreKeyword(path, "iron", "copper", "coal", "gold", "lapis");
        }
        if (localY <= 80) {
            return containsOreKeyword(path, "coal", "iron", "copper", "gold");
        }
        return containsOreKeyword(path, "coal", "iron", "copper");
    }

    private static boolean containsOreKeyword(String path, String... keywords) {
        for (String keyword : keywords) {
            if (path.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private BlockState secondaryOreStateForSphere(
            RandomSource random,
            boolean deep,
            OrePalette orePalette,
            List<BlockState> dominantStates
    ) {
        List<BlockState> candidates = new ArrayList<>();
        if (orePalette != null) {
            List<BlockState> configured = deep ? orePalette.deepStates() : orePalette.surfaceStates();
            for (BlockState state : configured) {
                if (!isBlacklistedIslandOreState(state)
                        && !containsOreBlock(candidates, state)
                        && !containsOreBlock(dominantStates, state)) {
                    candidates.add(state);
                }
            }
        }
        for (BlockState state : defaultOreStates(deep)) {
            if (!isBlacklistedIslandOreState(state)
                    && !containsOreBlock(candidates, state)
                    && !containsOreBlock(dominantStates, state)) {
                candidates.add(state);
            }
        }
        if (!candidates.isEmpty()) {
            return candidates.get(random.nextInt(candidates.size()));
        }
        if (!dominantStates.isEmpty()) {
            return dominantStates.get(random.nextInt(dominantStates.size()));
        }
        return oreStateFor(random, deep, orePalette);
    }

    private OrePalette orePaletteFor(SkylandsIslandBiomeDefinition islandBiome) {
        List<SkylandsIslandBiomeDefinition.OreSelection> selections = islandBiome == null ? List.of() : islandBiome.oreSelections();
        if (selections.isEmpty()) {
            selections = List.of(new SkylandsIslandBiomeDefinition.OreSelection(SkylandsIslandBiomes.DEFAULT_ORE_SENTINEL, null));
        }
        List<OrePaletteEntry> surfaceStates = new ArrayList<>();
        List<OrePaletteEntry> deepStates = new ArrayList<>();
        boolean defaultInjected = false;
        for (SkylandsIslandBiomeDefinition.OreSelection oreSelection : selections) {
            if (SkylandsIslandBiomes.DEFAULT_ORE_SENTINEL.equals(oreSelection.oreId())) {
                if (defaultInjected) {
                    continue;
                }
                defaultInjected = true;
                for (BlockState state : defaultOreStates(false)) {
                    OrePaletteEntry entry = new OrePaletteEntry(state, null);
                    if (!containsOreEntry(surfaceStates, entry)) {
                        surfaceStates.add(entry);
                    }
                }
                for (BlockState state : defaultOreStates(true)) {
                    OrePaletteEntry entry = new OrePaletteEntry(state, null);
                    if (!containsOreEntry(deepStates, entry)) {
                        deepStates.add(entry);
                    }
                }
                continue;
            }
            ModOreVariant variant = oreVariantForId(oreSelection.oreId());
            if (variant == null) {
                continue;
            }
            List<ResourceLocation> resolvedSources = resolveConfiguredOreSources(variant.normal(), oreSelection.sourceId());
            if (resolvedSources.isEmpty()) {
                resolvedSources = resolveConfiguredOreSources(variant.deep(), oreSelection.sourceId());
            }
            if (oreSelection.sourceId() == null) {
                OrePaletteEntry normalEntry = variant.normal() == null ? null : new OrePaletteEntry(variant.normal(), null);
                OrePaletteEntry deepEntry = variant.deep() == null ? null : new OrePaletteEntry(variant.deep(), null);
                if (normalEntry != null && !containsOreEntry(surfaceStates, normalEntry)) {
                    surfaceStates.add(normalEntry);
                } else if (deepEntry != null && !containsOreEntry(surfaceStates, deepEntry)) {
                    surfaceStates.add(deepEntry);
                }
                if (deepEntry != null && !containsOreEntry(deepStates, deepEntry)) {
                    deepStates.add(deepEntry);
                } else if (normalEntry != null && !containsOreEntry(deepStates, normalEntry)) {
                    deepStates.add(normalEntry);
                }
                continue;
            }
            for (ResourceLocation resolvedSourceId : resolvedSources) {
                OrePaletteEntry normalEntry = variant.normal() == null ? null : new OrePaletteEntry(variant.normal(), resolvedSourceId);
                OrePaletteEntry deepEntry = variant.deep() == null ? null : new OrePaletteEntry(variant.deep(), resolvedSourceId);
                if (normalEntry != null && !containsOreEntry(surfaceStates, normalEntry)) {
                    surfaceStates.add(normalEntry);
                } else if (deepEntry != null && !containsOreEntry(surfaceStates, deepEntry)) {
                    surfaceStates.add(deepEntry);
                }
                if (deepEntry != null && !containsOreEntry(deepStates, deepEntry)) {
                    deepStates.add(deepEntry);
                } else if (normalEntry != null && !containsOreEntry(deepStates, normalEntry)) {
                    deepStates.add(normalEntry);
                }
            }
        }
        if (surfaceStates.isEmpty() && deepStates.isEmpty()) {
            return null;
        }
        return new OrePalette(List.copyOf(surfaceStates), List.copyOf(deepStates));
    }

    private void syncOreFeatureTemplates(RegistryAccess registryAccess) {
        if (!oreFeatureTemplates.isEmpty()) {
            return;
        }
        try {
            var placedFeatures = registryAccess.lookupOrThrow(Registries.PLACED_FEATURE);
            Map<Block, OreFeatureTemplateBuilder> builders = new HashMap<>();
            placedFeatures.listElements().forEach(holder -> collectOreFeatureTemplate(holder, builders));
            Map<Block, OreFeatureTemplateSet> next = new HashMap<>();
            for (Map.Entry<Block, OreFeatureTemplateBuilder> entry : builders.entrySet()) {
                OreFeatureTemplateSet templateSet = entry.getValue().buildTemplates(entry.getKey().defaultBlockState());
                if (templateSet.hasAnyTemplate()) {
                    next.put(entry.getKey(), templateSet);
                }
            }
            if (!next.isEmpty()) {
                oreFeatureTemplates = Map.copyOf(next);
                oreBiomeSpecialSources = collectBiomeSpecialOreSources(registryAccess, next);
                DefaultOreLists defaults = buildDefaultOreLists(builders);
                defaultSurfaceOreStates = defaults.surfaceStates();
                defaultDeepOreStates = defaults.deepStates();
            }
        } catch (Exception ignored) {
        }
    }

    private void collectOreFeatureTemplate(Holder<PlacedFeature> placedFeatureHolder, Map<Block, OreFeatureTemplateBuilder> builders) {
        PlacedFeature placedFeature = placedFeatureHolder.value();
        ConfiguredFeature<?, ?> configuredFeature = placedFeature.feature().value();
        if (!(configuredFeature.config() instanceof OreConfiguration oreConfiguration)) {
            return;
        }
        OreFeatureSourceDescriptor sourceDescriptor = describeOreFeatureSource(placedFeatureHolder, oreConfiguration);
        double density = oreDensityFromPlacement(placedFeature.placement());
        int size = Math.max(1, oreConfiguration.size);
        List<OreHeightBand> heightBands = extractOreHeightBands(placedFeature.placement());
        for (OreConfiguration.TargetBlockState targetState : oreConfiguration.targetStates) {
            Block block = targetState.state.getBlock();
            OreFeatureTemplateBuilder builder = builders.computeIfAbsent(block, unused -> new OreFeatureTemplateBuilder());
            builder.addSource(sourceDescriptor, density, size, heightBands);
        }
    }

    private static OreFeatureSourceDescriptor describeOreFeatureSource(Holder<PlacedFeature> placedFeatureHolder, OreConfiguration oreConfiguration) {
        ResourceLocation key = placedFeatureHolder.unwrapKey().map(ResourceKey::location).orElse(null);
        if (key != null && featurePathHasSpecialOreInfo(key.getPath())) {
            return new OreFeatureSourceDescriptor(true, key);
        }
        for (OreConfiguration.TargetBlockState targetState : oreConfiguration.targetStates) {
            if (isImplicitlySpecialOreBlock(targetState.state.getBlock())) {
                return new OreFeatureSourceDescriptor(true, key);
            }
        }
        return new OreFeatureSourceDescriptor(false, key);
    }

    private static boolean featurePathHasSpecialOreInfo(String path) {
        return path.contains("badlands")
                || path.contains("extra")
                || path.contains("emerald")
                || path.contains("mountain")
                || path.contains("windswept")
                || path.contains("meadow")
                || path.contains("peak")
                || path.contains("hill");
    }

    private static DefaultOreLists buildDefaultOreLists(Map<Block, OreFeatureTemplateBuilder> builders) {
        Map<String, ModOreVariantBuilder> variants = new LinkedHashMap<>();
        for (Map.Entry<Block, OreFeatureTemplateBuilder> entry : builders.entrySet()) {
            Block block = entry.getKey();
            OreFeatureTemplateBuilder builder = entry.getValue();
            if (!builder.isDefaultEligible()) {
                continue;
            }
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
            if (key == null || block == Blocks.AIR || isBlacklistedIslandOreState(block.defaultBlockState())) {
                continue;
            }
            String path = key.getPath();
            if (!path.endsWith("_ore")) {
                continue;
            }
            boolean deep = path.startsWith("deepslate_") || path.startsWith("deep_");
            String basePath = deep ? path.replaceFirst("^(deepslate_|deep_)", "") : path;
            String mapKey = key.getNamespace() + ":" + basePath;
            ModOreVariantBuilder variant = variants.computeIfAbsent(mapKey, unused -> new ModOreVariantBuilder());
            if (deep) {
                variant.deep = block.defaultBlockState();
            } else {
                variant.normal = block.defaultBlockState();
            }
        }

        List<BlockState> surfaceStates = new ArrayList<>();
        List<BlockState> deepStates = new ArrayList<>();
        for (ModOreVariantBuilder variant : variants.values()) {
            if (variant.normal != null && !containsOreBlock(surfaceStates, variant.normal)) {
                surfaceStates.add(variant.normal);
            }
            if (variant.deep != null) {
                if (!containsOreBlock(deepStates, variant.deep)) {
                    deepStates.add(variant.deep);
                }
            } else if (variant.normal != null && !containsOreBlock(deepStates, variant.normal)) {
                deepStates.add(variant.normal);
            }
        }
        return new DefaultOreLists(List.copyOf(surfaceStates), List.copyOf(deepStates));
    }

    private static Map<Block, Map<ResourceLocation, List<ResourceLocation>>> collectBiomeSpecialOreSources(
            RegistryAccess registryAccess,
            Map<Block, OreFeatureTemplateSet> templates
    ) {
        Map<Block, Map<ResourceLocation, List<ResourceLocation>>> result = new HashMap<>();
        try {
            var biomes = registryAccess.lookupOrThrow(Registries.BIOME);
            biomes.listElements().forEach(biomeHolder -> {
                ResourceLocation biomeId = biomeHolder.unwrapKey().map(ResourceKey::location).orElse(null);
                if (biomeId == null) {
                    return;
                }
                for (var featureStep : biomeHolder.value().getGenerationSettings().features()) {
                    for (Holder<PlacedFeature> featureHolder : featureStep) {
                        ResourceLocation sourceId = featureHolder.unwrapKey().map(ResourceKey::location).orElse(null);
                        if (sourceId == null) {
                            continue;
                        }
                        PlacedFeature placedFeature = featureHolder.value();
                        ConfiguredFeature<?, ?> configuredFeature = placedFeature.feature().value();
                        if (!(configuredFeature.config() instanceof OreConfiguration oreConfiguration)) {
                            continue;
                        }
                        for (OreConfiguration.TargetBlockState targetState : oreConfiguration.targetStates) {
                            Block block = targetState.state.getBlock();
                            OreFeatureTemplateSet templateSet = templates.get(block);
                            if (templateSet == null || !templateSet.specialTemplates().containsKey(sourceId)) {
                                continue;
                            }
                            Map<ResourceLocation, List<ResourceLocation>> byBiome = result.computeIfAbsent(block, unused -> new HashMap<>());
                            List<ResourceLocation> sourceIds = byBiome.computeIfAbsent(biomeId, unused -> new ArrayList<>());
                            if (!sourceIds.contains(sourceId)) {
                                sourceIds.add(sourceId);
                            }
                        }
                    }
                }
            });
        } catch (Exception ignored) {
        }
        Map<Block, Map<ResourceLocation, List<ResourceLocation>>> immutable = new HashMap<>();
        for (Map.Entry<Block, Map<ResourceLocation, List<ResourceLocation>>> blockEntry : result.entrySet()) {
            Map<ResourceLocation, List<ResourceLocation>> byBiome = new HashMap<>();
            for (Map.Entry<ResourceLocation, List<ResourceLocation>> biomeEntry : blockEntry.getValue().entrySet()) {
                byBiome.put(biomeEntry.getKey(), List.copyOf(biomeEntry.getValue()));
            }
            immutable.put(blockEntry.getKey(), Map.copyOf(byBiome));
        }
        return Map.copyOf(immutable);
    }

    private static List<OreHeightBand> extractOreHeightBands(List<PlacementModifier> placementModifiers) {
        List<OreHeightBand> bands = new ArrayList<>();
        for (PlacementModifier modifier : placementModifiers) {
            if (modifier instanceof HeightRangePlacement heightRangePlacement) {
                OreHeightBand band = extractOreHeightBand(heightRangePlacement);
                if (band != null) {
                    bands.add(band);
                }
            }
        }
        return bands;
    }

    private static OreHeightBand extractOreHeightBand(HeightRangePlacement placement) {
        HeightProvider provider = readPrivateField(placement, "height", HeightProvider.class);
        if (provider instanceof UniformHeight uniform) {
            VerticalAnchor min = readPrivateField(uniform, "minInclusive", VerticalAnchor.class);
            VerticalAnchor max = readPrivateField(uniform, "maxInclusive", VerticalAnchor.class);
            return min == null || max == null ? null : new OreHeightBand(
                    resolveAnchorToLocalY(min),
                    resolveAnchorToLocalY(max),
                    1.0D,
                    OreHeightShape.UNIFORM,
                    0,
                    1
            );
        }
        if (provider instanceof TrapezoidHeight trapezoid) {
            VerticalAnchor min = readPrivateField(trapezoid, "minInclusive", VerticalAnchor.class);
            VerticalAnchor max = readPrivateField(trapezoid, "maxInclusive", VerticalAnchor.class);
            Integer plateau = readPrivateField(trapezoid, "plateau", Integer.class);
            return min == null || max == null ? null : new OreHeightBand(
                    resolveAnchorToLocalY(min),
                    resolveAnchorToLocalY(max),
                    1.0D,
                    OreHeightShape.TRAPEZOID,
                    plateau == null ? 0 : Math.max(0, plateau),
                    1
            );
        }
        if (provider instanceof BiasedToBottomHeight biased) {
            VerticalAnchor min = readPrivateField(biased, "minInclusive", VerticalAnchor.class);
            VerticalAnchor max = readPrivateField(biased, "maxInclusive", VerticalAnchor.class);
            Integer inner = readPrivateField(biased, "inner", Integer.class);
            return min == null || max == null ? null : new OreHeightBand(
                    resolveAnchorToLocalY(min),
                    resolveAnchorToLocalY(max),
                    1.0D,
                    OreHeightShape.BIASED_TO_BOTTOM,
                    0,
                    inner == null ? 1 : Math.max(1, inner)
            );
        }
        if (provider instanceof VeryBiasedToBottomHeight veryBiased) {
            VerticalAnchor min = readPrivateField(veryBiased, "minInclusive", VerticalAnchor.class);
            VerticalAnchor max = readPrivateField(veryBiased, "maxInclusive", VerticalAnchor.class);
            Integer inner = readPrivateField(veryBiased, "inner", Integer.class);
            return min == null || max == null ? null : new OreHeightBand(
                    resolveAnchorToLocalY(min),
                    resolveAnchorToLocalY(max),
                    1.0D,
                    OreHeightShape.VERY_BIASED_TO_BOTTOM,
                    0,
                    inner == null ? 1 : Math.max(1, inner)
            );
        }
        return null;
    }

    private static int resolveAnchorToLocalY(VerticalAnchor anchor) {
        int absoluteY;
        if (anchor instanceof VerticalAnchor.Absolute absolute) {
            absoluteY = absolute.y();
        } else if (anchor instanceof VerticalAnchor.AboveBottom aboveBottom) {
            absoluteY = VANILLA_GEN_MIN_Y + aboveBottom.offset();
        } else if (anchor instanceof VerticalAnchor.BelowTop belowTop) {
            absoluteY = VANILLA_GEN_MIN_Y + VANILLA_GEN_DEPTH - 1 - belowTop.offset();
        } else {
            absoluteY = 0;
        }
        return Mth.clamp(absoluteY, VANILLA_ORE_MIN_Y, VANILLA_ORE_WORLD_TOP_Y);
    }

    private static <T> T readPrivateField(Object target, String fieldName, Class<T> type) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(target);
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static double oreDensityFromPlacement(List<PlacementModifier> placementModifiers) {
        double density = 1.0D;
        for (PlacementModifier modifier : placementModifiers) {
            if (modifier instanceof CountPlacement countPlacement) {
                density *= extractCountPlacementAverage(countPlacement);
            } else if (modifier instanceof RarityFilter rarityFilter) {
                density *= 1.0D / Math.max(1, extractRarityChance(rarityFilter));
            }
        }
        return Mth.clamp(density, 0.25D, 64.0D);
    }

    private static double extractCountPlacementAverage(CountPlacement placement) {
        try {
            java.lang.reflect.Field field = CountPlacement.class.getDeclaredField("count");
            field.setAccessible(true);
            net.minecraft.util.valueproviders.IntProvider provider =
                    (net.minecraft.util.valueproviders.IntProvider) field.get(placement);
            return averagePlacementCount(provider);
        } catch (Exception ignored) {
            return 1.0D;
        }
    }

    private static int extractRarityChance(RarityFilter filter) {
        try {
            java.lang.reflect.Field field = RarityFilter.class.getDeclaredField("chance");
            field.setAccessible(true);
            return Math.max(1, field.getInt(filter));
        } catch (Exception ignored) {
            return 1;
        }
    }

    private static double averagePlacementCount(net.minecraft.util.valueproviders.IntProvider provider) {
        return ((double) provider.getMinValue() + (double) provider.getMaxValue()) * 0.5D;
    }

    private static ModOreVariant oreVariantForId(ResourceLocation oreId) {
        BlockState direct = blockStateOrNull(oreId);
        if (direct == null) {
            return null;
        }
        String namespace = oreId.getNamespace();
        String path = oreId.getPath();
        boolean deep = path.startsWith("deepslate_") || path.startsWith("deep_");

        BlockState normal = null;
        BlockState deepState = null;
        if (deep) {
            String basePath = path.replaceFirst("^(deepslate_|deep_)", "");
            normal = blockStateOrNull(ResourceLocation.parse(namespace + ":" + basePath));
            deepState = direct;
        } else {
            normal = direct;
            deepState = blockStateOrNull(ResourceLocation.parse(namespace + ":deepslate_" + path));
            if (deepState == null) {
                deepState = blockStateOrNull(ResourceLocation.parse(namespace + ":deep_" + path));
            }
        }
        return new ModOreVariant(normal, deepState);
    }

    private static BlockState blockStateOrNull(ResourceLocation id) {
        Block block = BuiltInRegistries.BLOCK.get(id);
        return block == Blocks.AIR ? null : block.defaultBlockState();
    }

    private static List<BlockState> modOreStates(boolean deep) {
        if (cachedModOres == null) {
            cachedModOres = collectModOres();
        }

        List<BlockState> result = new ArrayList<>();
        for (ModOreVariant variant : cachedModOres) {
            if (deep) {
                if (variant.deep() != null) {
                    result.add(variant.deep());
                } else if (variant.normal() != null) {
                    result.add(variant.normal());
                }
            } else if (variant.normal() != null) {
                result.add(variant.normal());
            }
        }
        return result;
    }

    private static List<ModOreVariant> collectModOres() {
        Map<String, ModOreVariantBuilder> builders = new HashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
            if (key == null || "minecraft".equals(key.getNamespace())) {
                continue;
            }

            String path = key.getPath();
            if (!path.endsWith("_ore")) {
                continue;
            }

            boolean deep = path.startsWith("deepslate_") || path.startsWith("deep_");
            String basePath = deep ? path.replaceFirst("^(deepslate_|deep_)", "") : path;
            String mapKey = key.getNamespace() + ":" + basePath;
            ModOreVariantBuilder builder = builders.computeIfAbsent(mapKey, unused -> new ModOreVariantBuilder());
            if (deep) {
                builder.deep = block.defaultBlockState();
            } else {
                builder.normal = block.defaultBlockState();
            }
        }

        List<ModOreVariant> result = new ArrayList<>();
        for (ModOreVariantBuilder builder : builders.values()) {
            if (builder.normal != null || builder.deep != null) {
                result.add(new ModOreVariant(builder.normal, builder.deep));
            }
        }
        return result;
    }

    private static BoundingBox clipBoxToChunk(ChunkAccess chunk, BoundingBox box) {
        ChunkPos chunkPos = chunk.getPos();
        int minX = Math.max(box.minX(), chunkPos.getMinBlockX());
        int maxX = Math.min(box.maxX(), chunkPos.getMaxBlockX());
        int minZ = Math.max(box.minZ(), chunkPos.getMinBlockZ());
        int maxZ = Math.min(box.maxZ(), chunkPos.getMaxBlockZ());
        int minY = Math.max(box.minY(), chunk.getMinBuildHeight());
        int maxY = Math.min(box.maxY(), chunk.getMaxBuildHeight() - 1);
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return null;
        }
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void clearBox(ChunkAccess chunk, BoundingBox box) {
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    chunk.setBlockState(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), false);
                }
            }
        }
    }

    private static void fillEllipsoid(ChunkAccess chunk, BoundingBox box) {
        int cx = (box.minX() + box.maxX()) / 2;
        int cy = (box.minY() + box.maxY()) / 2;
        int cz = (box.minZ() + box.maxZ()) / 2;

        double rx = Math.max(1.0D, (box.maxX() - box.minX()) / 2.0D);
        double ry = Math.max(1.0D, (box.maxY() - box.minY()) / 2.0D);
        double rz = Math.max(1.0D, (box.maxZ() - box.minZ()) / 2.0D);

        double invRx2 = 1.0D / (rx * rx);
        double invRy2 = 1.0D / (ry * ry);
        double invRz2 = 1.0D / (rz * rz);

        for (int x = box.minX(); x <= box.maxX(); x++) {
            double dx = (x - cx);
            double dx2 = dx * dx * invRx2;
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                double dz = (z - cz);
                double dz2 = dz * dz * invRz2;
                double dxz2 = dx2 + dz2;
                if (dxz2 > 1.0D) {
                    continue;
                }
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    double dy = (y - cy);
                    double dy2 = dy * dy * invRy2;
                    if (dxz2 + dy2 > 1.0D) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(x, y, z);
                    if (chunk.getBlockState(pos).isAir()) {
                        chunk.setBlockState(pos, Blocks.STONE.defaultBlockState(), false);
                    }
                }
            }
        }
    }

    private static void applyMoss(
            ChunkAccess chunk,
            BoundingBox islandBox,
            BoundingBox structureBox,
            RandomSource random,
            int seeds,
            int spreadSteps,
            double spreadChance
    ) {
        List<BlockPos> surfaces = new ArrayList<>();
        for (int x = islandBox.minX(); x <= islandBox.maxX(); x++) {
            for (int z = islandBox.minZ(); z <= islandBox.maxZ(); z++) {
                int y = findSurfaceY(chunk, islandBox, structureBox, x, z);
                if (y == Integer.MIN_VALUE) {
                    continue;
                }
                surfaces.add(new BlockPos(x, y, z));
            }
        }

        if (surfaces.isEmpty()) {
            return;
        }

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (int i = 0; i < seeds; i++) {
            BlockPos pos = surfaces.get(random.nextInt(surfaces.size()));
            if (tryMossifyTop(chunk, islandBox, structureBox, pos, random)) {
                queue.add(pos);
            }
        }

        int steps = 0;
        while (steps < spreadSteps && !queue.isEmpty()) {
            BlockPos cur = queue.pollFirst();
            int x = cur.getX();
            int z = cur.getZ();

            steps++;

            if (random.nextDouble() > spreadChance) {
                continue;
            }

            int dir = random.nextInt(4);
            int nx = x + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
            int nz = z + (dir == 2 ? 1 : dir == 3 ? -1 : 0);

            if (nx < islandBox.minX() || nx > islandBox.maxX() || nz < islandBox.minZ() || nz > islandBox.maxZ()) {
                continue;
            }

            int ny = findSurfaceY(chunk, islandBox, structureBox, nx, nz);
            if (ny == Integer.MIN_VALUE) {
                continue;
            }

            BlockPos next = new BlockPos(nx, ny, nz);
            if (tryMossifyTop(chunk, islandBox, structureBox, next, random)) {
                queue.addLast(next);
            }
        }
    }

    private static int findSurfaceY(ChunkAccess chunk, BoundingBox islandBox, BoundingBox structureBox, int x, int z) {
        for (int y = islandBox.maxY(); y >= islandBox.minY(); y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (structureBox.isInside(pos)) {
                continue;
            }
            if (chunk.getBlockState(pos).isAir()) {
                continue;
            }
            BlockPos above = pos.above();
            if (islandBox.isInside(above) && !chunk.getBlockState(above).isAir()) {
                continue;
            }
            return y;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean tryMossifyTop(ChunkAccess chunk, BoundingBox islandBox, BoundingBox structureBox, BlockPos topPos, RandomSource random) {
        if (structureBox.isInside(topPos)) {
            return false;
        }
        if (chunk.getBlockState(topPos).isAir()) {
            return false;
        }
        BlockPos above = topPos.above();
        if (!chunk.getBlockState(above).isAir()) {
            return false;
        }
        chunk.setBlockState(topPos, Blocks.MOSS_BLOCK.defaultBlockState(), false);
        if (!tryPlaceVegetation(chunk, islandBox, structureBox, above, random) && random.nextFloat() < 0.25F) {
            chunk.setBlockState(above, Blocks.MOSS_CARPET.defaultBlockState(), false);
        }
        return true;
    }

    private static boolean tryPlaceVegetation(
            ChunkAccess chunk,
            BoundingBox islandBox,
            BoundingBox structureBox,
            BlockPos placePos,
            RandomSource random
    ) {
        if (structureBox.isInside(placePos) || !chunk.getBlockState(placePos).isAir()) {
            return false;
        }

        float r = random.nextFloat();
        if (r < 0.005F) {
            return tryPlaceAzaleaTree(chunk, islandBox, structureBox, placePos, random, random.nextBoolean());
        }
        if (r < 0.025F) {
            chunk.setBlockState(placePos, Blocks.FLOWERING_AZALEA.defaultBlockState(), false);
            return true;
        }
        if (r < 0.045F) {
            chunk.setBlockState(placePos, Blocks.AZALEA.defaultBlockState(), false);
            return true;
        }
        if (r < 0.095F) {
            BlockPos upper = placePos.above();
            if (!chunk.getBlockState(upper).isAir() || structureBox.isInside(upper)) {
                return false;
            }
            BlockState lowerState = Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER);
            BlockState upperState = Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);
            chunk.setBlockState(placePos, lowerState, false);
            chunk.setBlockState(upper, upperState, false);
            return true;
        }
        if (r < 0.295F) {
            chunk.setBlockState(placePos, Blocks.SHORT_GRASS.defaultBlockState(), false);
            return true;
        }
        return false;
    }

    private static boolean tryPlaceAzaleaTree(
            ChunkAccess chunk,
            BoundingBox islandBox,
            BoundingBox structureBox,
            BlockPos basePos,
            RandomSource random,
            boolean flowering
    ) {
        int height = 4 + random.nextInt(3);
        for (int i = 0; i <= height + 2; i++) {
            BlockPos p = basePos.above(i);
            if (!chunk.getBlockState(p).isAir() || structureBox.isInside(p)) {
                return false;
            }
        }

        for (int i = 0; i < height; i++) {
            BlockPos p = basePos.above(i);
            if (!islandBox.isInside(p)) {
                return false;
            }
            chunk.setBlockState(p, Blocks.OAK_LOG.defaultBlockState(), false);
        }

        BlockPos top = basePos.above(height - 1);
        BlockPos canopy = top.above();

        BlockState leaf = (flowering ? Blocks.FLOWERING_AZALEA_LEAVES : Blocks.AZALEA_LEAVES).defaultBlockState();
        BlockState leafAlt = (flowering ? Blocks.AZALEA_LEAVES : Blocks.FLOWERING_AZALEA_LEAVES).defaultBlockState();

        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int md = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (md > 4) {
                        continue;
                    }
                    if (dy == 2 && (Math.abs(dx) + Math.abs(dz) > 1)) {
                        continue;
                    }

                    BlockPos p = canopy.offset(dx, dy, dz);
                    if (!islandBox.isInside(p) || structureBox.isInside(p)) {
                        continue;
                    }
                    if (!chunk.getBlockState(p).isAir()) {
                        continue;
                    }

                    BlockState toPlace = random.nextFloat() < 0.12F ? leafAlt : leaf;
                    chunk.setBlockState(p, toPlace, false);
                }
            }
        }

        return true;
    }

    private static final class ModOreVariantBuilder {
        private BlockState normal;
        private BlockState deep;
    }

    private static final class OreFeatureTemplateBuilder {
        private double genericWeightedSize;
        private double genericTotalWeight;
        private final List<OreHeightBand> genericHeightBands = new ArrayList<>();
        private double specialTotalWeight;
        private final Map<ResourceLocation, OreTemplateAccumulator> specialSources = new HashMap<>();

        private void addSource(OreFeatureSourceDescriptor descriptor, double density, int size, List<OreHeightBand> heightBands) {
            if (descriptor.special()) {
                specialTotalWeight += density;
                if (descriptor.sourceId() != null) {
                    specialSources.computeIfAbsent(descriptor.sourceId(), unused -> new OreTemplateAccumulator())
                            .add(density, size, heightBands);
                }
            } else {
                genericTotalWeight += density;
                genericWeightedSize += density * (double) size;
                if (heightBands.isEmpty()) {
                    genericHeightBands.add(OreHeightBand.fullRange(density));
                } else {
                    for (OreHeightBand band : heightBands) {
                        genericHeightBands.add(band.scaled(density));
                    }
                }
            }
        }

        private OreFeatureTemplateSet buildTemplates(BlockState state) {
            Map<ResourceLocation, OreFeatureTemplate> specialTemplates = new HashMap<>();
            for (Map.Entry<ResourceLocation, OreTemplateAccumulator> entry : specialSources.entrySet()) {
                OreFeatureTemplate template = entry.getValue().buildTemplate(state);
                if (template != null) {
                    specialTemplates.put(entry.getKey(), template);
                }
            }
            return new OreFeatureTemplateSet(
                    buildTemplate(state, genericWeightedSize, genericTotalWeight, genericHeightBands),
                    Map.copyOf(specialTemplates)
            );
        }

        private static OreFeatureTemplate buildTemplate(BlockState state, double weightedSize, double totalWeight, List<OreHeightBand> heightBands) {
            if (totalWeight <= 0.0D) {
                return null;
            }
            int veinSize = Math.max(1, Mth.floor(weightedSize / Math.max(1.0D, totalWeight)));
            double density = Math.max(0.2D, totalWeight);
            List<OreHeightBand> resolvedBands = heightBands.isEmpty()
                    ? List.of(OreHeightBand.fullRange(density))
                    : List.copyOf(heightBands);
            return new OreFeatureTemplate(state, veinSize, density, resolvedBands);
        }

        private boolean isDefaultEligible() {
            return genericTotalWeight > 0.0D && specialTotalWeight <= 0.0D;
        }
    }

    private static final class OreTemplateAccumulator {
        private double weightedSize;
        private double totalWeight;
        private final List<OreHeightBand> heightBands = new ArrayList<>();

        private void add(double density, int size, List<OreHeightBand> sourceBands) {
            totalWeight += density;
            weightedSize += density * (double) size;
            if (sourceBands.isEmpty()) {
                heightBands.add(OreHeightBand.fullRange(density));
            } else {
                for (OreHeightBand band : sourceBands) {
                    heightBands.add(band.scaled(density));
                }
            }
        }

        private OreFeatureTemplate buildTemplate(BlockState state) {
            return OreFeatureTemplateBuilder.buildTemplate(state, weightedSize, totalWeight, heightBands);
        }
    }

    private record OreFeatureSourceDescriptor(
            boolean special,
            ResourceLocation sourceId
    ) {}

    private record OreFeatureTemplateSet(
            OreFeatureTemplate genericTemplate,
            Map<ResourceLocation, OreFeatureTemplate> specialTemplates
    ) {
        private boolean hasAnyTemplate() {
            return genericTemplate != null || !specialTemplates.isEmpty();
        }
    }

    private enum OreHeightShape {
        UNIFORM,
        TRAPEZOID,
        BIASED_TO_BOTTOM,
        VERY_BIASED_TO_BOTTOM
    }

    private record OreHeightBand(
            int minLocalY,
            int maxLocalY,
            double weight,
            OreHeightShape shape,
            int plateau,
            int inner
    ) {
        private static OreHeightBand fullRange(double weight) {
            return new OreHeightBand(VANILLA_ORE_MIN_Y, VANILLA_ORE_WORLD_TOP_Y, weight, OreHeightShape.UNIFORM, 0, 1);
        }

        private OreHeightBand scaled(double scale) {
            return new OreHeightBand(minLocalY, maxLocalY, weight * scale, shape, plateau, inner);
        }

        private double weightAt(int localY) {
            int min = Math.min(minLocalY, maxLocalY);
            int max = Math.max(minLocalY, maxLocalY);
            if (localY < min || localY > max) {
                return 0.0D;
            }
            if (max <= min) {
                return weight;
            }
            double t = (double) (localY - min) / (double) (max - min);
            double profile = switch (shape) {
                case UNIFORM -> 1.0D;
                case TRAPEZOID -> trapezoidProfile(t, max - min, plateau);
                case BIASED_TO_BOTTOM -> Math.pow(1.0D - t, 0.85D) * biasedInnerFloor(max - min, inner);
                case VERY_BIASED_TO_BOTTOM -> Math.pow(1.0D - t, 1.65D) * biasedInnerFloor(max - min, inner);
            };
            return weight * Math.max(0.0D, profile);
        }
    }

    private static double trapezoidProfile(double t, int range, int plateau) {
        if (range <= 0) {
            return 1.0D;
        }
        double plateauRatio = Mth.clamp((double) Math.max(0, plateau) / (double) range, 0.0D, 1.0D);
        double ramp = (1.0D - plateauRatio) * 0.5D;
        if (ramp <= 0.0D) {
            return 1.0D;
        }
        if (t < ramp) {
            return t / ramp;
        }
        if (t > 1.0D - ramp) {
            return (1.0D - t) / ramp;
        }
        return 1.0D;
    }

    private static double biasedInnerFloor(int range, int inner) {
        if (range <= 0) {
            return 1.0D;
        }
        double innerRatio = Mth.clamp((double) Math.max(1, inner) / (double) (range + Math.max(1, inner)), 0.0D, 1.0D);
        return Mth.lerp(innerRatio, 0.75D, 1.0D);
    }

    private record OceanColumnInfo(boolean isOcean, boolean warmOcean, boolean gravelHeavy, int floraType, int waterDepth) {
        private static final OceanColumnInfo NOT_OCEAN = new OceanColumnInfo(false, false, false, OCEAN_FLORA_NONE, 0);
    }

    private record TheoreticalTerrainSample(
            int surfaceY,
            double landWeight,
            double ruggedness,
            boolean oceanic,
            BlockState topState,
            BlockState subsurfaceState
    ) {}

    private record SkylandsColumn(
            int topY,
            int bottomY,
            BlockState topState,
            BlockState subsurfaceState,
            int islandCenterX,
            int islandCenterZ,
            int islandRadius,
            int islandNoiseSeed,
            float crackStrength,
            boolean bigCrack
    ) {}

    private record SkylandsColumnCandidate(
            int topY,
            int bottomY,
            BlockState surfaceState,
            BlockState subsurfaceState,
            SkylandsIslandBiomeDefinition islandBiome,
            int islandCenterX,
            int islandCenterZ,
            int islandRadius,
            int islandBaseY,
            int islandNoiseSeed,
            float crackStrength,
            boolean bigCrack
    ) {}

    private record SkylandsColumnSegment(
            int topY,
            int bottomY,
            BlockState surfaceState,
            BlockState subsurfaceState,
            SkylandsIslandBiomeDefinition islandBiome,
            int islandCenterX,
            int islandCenterZ,
            int islandRadius,
            int islandBaseY,
            int islandNoiseSeed,
            float crackStrength,
            boolean bigCrack
    ) {}

    private record BadlandsColumnApplication(
            SkylandsColumnSegment segment,
            int x,
            int z,
            int topY
    ) {}

    private record IslandOreColumnApplication(
            SkylandsColumnSegment segment,
            int x,
            int z,
            int topY
    ) {}

    private record LiquidPoolColumnApplication(
            SkylandsColumnSegment segment,
            int x,
            int z,
            int topY
    ) {}

    private record SurfaceFeatureApplication(
            SkylandsColumnSegment segment,
            int x,
            int z,
            int topY
    ) {}

    private record UndersideFeatureApplication(
            SkylandsColumnSegment segment,
            int x,
            int z,
            int bottomY
    ) {}

    private record CompositeIslandPlan(
            int localCenterX,
            int localCenterZ,
            int localRadius,
            int localBaseY,
            double maxOrbit,
            double decayWidth,
            double terrainRoughnessScale,
            boolean flatTerrain,
            boolean rollingTerrain,
            boolean hillsTerrain,
            boolean mountainsTerrain,
            boolean peaksTerrain,
            boolean mesaTerrain,
            boolean volcanoTerrain,
            int coneCount,
            int[] coneCenterXs,
            int[] coneCenterZs,
            int[] coneRadii,
            double[] coneOrbits,
            long[] coneSalts
    ) {}

    private record CompositeConeInfo(
            int coneCenterX,
            int coneCenterZ,
            int coneRadius,
            long coneSalt,
            int coneTopYRaw,
            int topY,
            int coneLength,
            double coneExponent,
            int maxRough,
            int minBottomY
    ) {}

    private record CompositeIsland2DMapKey(
            int islandNoiseSeed,
            int localCenterX,
            int localCenterZ,
            int localRadius,
            int localBaseY,
            long islandSalt,
            String terrainType
    ) {}

    private record CompositeIsland2DMap(
            int minX,
            int minZ,
            int width,
            int height,
            int minY,
            int maxY,
            java.util.BitSet[] layers
    ) {
        private List<SkylandsColumn> columnsAt(
                int x,
                int z,
                BlockState topState,
                BlockState subsurfaceState,
                int islandCenterX,
                int islandCenterZ,
                int islandRadius,
                int islandNoiseSeed
        ) {
            int localX = x - minX;
            int localZ = z - minZ;
            if (localX < 0 || localZ < 0 || localX >= width || localZ >= height) {
                return java.util.List.of();
            }
            int index = localZ * width + localX;
            java.util.ArrayList<SkylandsColumn> columns = new java.util.ArrayList<>();
            boolean inSegment = false;
            int segmentBottomY = Integer.MIN_VALUE;
            int segmentTopY = Integer.MIN_VALUE;
            for (int layerIndex = 0; layerIndex < layers.length; layerIndex++) {
                java.util.BitSet layer = layers[layerIndex];
                boolean occupied = layer != null && layer.get(index);
                int y = minY + layerIndex;
                if (occupied) {
                    if (!inSegment) {
                        inSegment = true;
                        segmentBottomY = y;
                    }
                    segmentTopY = y;
                    continue;
                }
                if (!inSegment) {
                    continue;
                }
                columns.add(new SkylandsColumn(
                        segmentTopY,
                        segmentBottomY,
                        topState,
                        subsurfaceState,
                        islandCenterX,
                        islandCenterZ,
                        islandRadius,
                        islandNoiseSeed,
                        0.0F,
                        false
                ));
                inSegment = false;
            }
            if (inSegment) {
                columns.add(new SkylandsColumn(
                        segmentTopY,
                        segmentBottomY,
                        topState,
                        subsurfaceState,
                        islandCenterX,
                        islandCenterZ,
                        islandRadius,
                        islandNoiseSeed,
                        0.0F,
                        false
                ));
            }
            return columns;
        }
    }

    private record ModOreVariant(BlockState normal, BlockState deep) {}

    private record OrePalette(List<OrePaletteEntry> surfaceEntries, List<OrePaletteEntry> deepEntries) {
        private List<BlockState> surfaceStates() {
            List<BlockState> result = new ArrayList<>();
            for (OrePaletteEntry entry : surfaceEntries) {
                if (!containsOreBlock(result, entry.state())) {
                    result.add(entry.state());
                }
            }
            return List.copyOf(result);
        }

        private List<BlockState> deepStates() {
            List<BlockState> result = new ArrayList<>();
            for (OrePaletteEntry entry : deepEntries) {
                if (!containsOreBlock(result, entry.state())) {
                    result.add(entry.state());
                }
            }
            return List.copyOf(result);
        }
    }

    private record OrePaletteEntry(BlockState state, ResourceLocation sourceId) {}

    private record OreFeatureTemplate(BlockState state, int veinSize, double density, List<OreHeightBand> heightBands) {}

    private record DefaultOreLists(List<BlockState> surfaceStates, List<BlockState> deepStates) {}

    private record IslandOrePoint(int centerX, int centerY, int centerZ, int pointIndex) {}

    private record OreDebugStats(int templateNullCount, int outsideVeinCount, int densityRejectedCount, int acceptedCount) {}

    private record ExtraIsland(
            int centerX,
            int centerY,
            int centerZ,
            int radiusX,
            int radiusY,
            int radiusZ,
            long seed,
            double roughness,
            int profile,
            OrePalette orePalette,
            int orePasses,
            List<BlockState> dominantOreStates
    ) {}

    private record IslandPlacement(int centerX, int centerZ, int radius, int baseY) {}
}
