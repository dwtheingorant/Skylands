package com.skylands.skylands.worldgen.biome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.skylands.skylands.SkylandsConfig;
import com.skylands.skylands.worldgen.SkylandsIslands;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class SkylandsIslandBiomes {
    private static volatile Cache cache;

    public static final ResourceLocation DEFAULT_ORE_SENTINEL = ResourceLocation.fromNamespaceAndPath("skylands", "default_ore_sentinel");

    private SkylandsIslandBiomes() {}

    public static List<SkylandsIslandBiomeDefinition> definitions() {
        List<? extends String> raw = SkylandsConfig.ISLAND_BIOME_DEFINITIONS.get();
        Cache current = cache;
        if (current != null && Objects.equals(current.raw(), raw)) {
            return current.definitions();
        }
        List<SkylandsIslandBiomeDefinition> parsed = parseDefinitions(raw);
        Cache next = new Cache(List.copyOf(raw), parsed);
        cache = next;
        return next.definitions();
    }

    public static SkylandsIslandBiomeDefinition select(long worldSeed, SkylandsIslands.Island island) {
        if (island.isSpawnOrigin()) {
            List<? extends String> poolRaw = SkylandsConfig.SPAWN_BIOME_POOL.get();
            if (poolRaw != null && !poolRaw.isEmpty()) {
                List<String> pool = poolRaw.stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(String::trim)
                        .toList();
                if (!pool.isEmpty()) {
                    List<SkylandsIslandBiomeDefinition> defs = definitions();
                    List<SkylandsIslandBiomeDefinition> matched = new ArrayList<>();
                    for (String id : pool) {
                        String normalized = id.startsWith("minecraft:") ? id : "minecraft:" + id;
                        for (SkylandsIslandBiomeDefinition def : defs) {
                            String defId = def.biomeId().toString();
                            int colon = defId.indexOf(':');
                            String defPath = colon >= 0 ? defId.substring(colon + 1) : defId;
                            if (normalized.equals(defId) || id.equals(defId) || id.equals(defPath)) {
                                matched.add(def);
                                break;
                            }
                        }
                    }
                    if (matched.isEmpty()) {
                        String firstId = pool.get(0);
                        String normalized = firstId.startsWith("minecraft:") ? firstId : "minecraft:" + firstId;
                        ResourceLocation key = ResourceLocation.tryParse(normalized);
                        if (key != null) {
                            return SkylandsIslandBiomeDefinition.spawnFallback(key, Blocks.GRASS_BLOCK.defaultBlockState());
                        }
                    } else {
                        long mix = worldSeed ^ Long.rotateLeft((long) island.noiseSeed() * 0x9E3779B97F4A7C15L, 17) ^ 0x5EED151ADE1L;
                        int idx = Math.floorMod(Long.hashCode(mix), matched.size());
                        return matched.get(idx);
                    }
                }
            }
        }

        List<SkylandsIslandBiomeDefinition> defs = definitions();
        if (defs.isEmpty()) {
            return defaultDefinition();
        }
        SkylandsIslandBiomeDefinition.NoiseContext context = sampleNoiseContext(worldSeed, island.centerX(), island.centerZ());
        List<SkylandsIslandBiomeDefinition> matched = new ArrayList<>();
        int highestPriority = Integer.MIN_VALUE;
        for (SkylandsIslandBiomeDefinition def : defs) {
            if (!def.matches(context)) {
                continue;
            }
            if (def.priority() > highestPriority) {
                matched.clear();
                highestPriority = def.priority();
            }
            if (def.priority() == highestPriority) {
                matched.add(def);
            }
        }
        if (matched.isEmpty()) {
            return defaultDefinition();
        }
        matched.sort(Comparator.comparingLong(def -> candidateHash(worldSeed, island.noiseSeed(), def.biomeId())));
        for (SkylandsIslandBiomeDefinition def : matched) {
            double roll = normalizedHash(worldSeed, island.noiseSeed(), def.biomeId(), 0x6A09E667F3BCC909L);
            if (roll <= Mth.clamp(def.probability(), 0.0D, 1.0D)) {
                return def;
            }
        }
        return defaultDefinition();
    }

    private static List<SkylandsIslandBiomeDefinition> parseDefinitions(List<? extends String> raw) {
        List<SkylandsIslandBiomeDefinition> out = new ArrayList<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Map<String, String> values = splitKeyValueEntry(entry);
            ResourceLocation biomeId = parseResourceLocation(values.get("biome"));
            BlockState subsurface = parseOptionalBlockState(values.get("subsurface"));
            if (biomeId == null) {
                continue;
            }
            SkylandsIslandBiomeDefinition.NoiseRequirements requirements = parseRequirements(values.get("requirements"));
            List<SkylandsIslandBiomeDefinition.SurfaceFeatureDefinition> features = parseSurfaceFeatures(values.get("features"));
            List<SkylandsIslandBiomeDefinition.UnderhangDefinition> underhangs = parseUnderhangs(values.get("underhang"));
            BlockState hangingState = parseOptionalBlockState(values.get("hanging"));
            if (hangingState != null && underhangs.isEmpty()) {
                underhangs = List.of(new SkylandsIslandBiomeDefinition.UnderhangDefinition(
                        hangingState, null, 1, 1, Integer.MAX_VALUE, List.of(), null
                ));
            }
            List<SkylandsIslandBiomeDefinition.OreSelection> oreSelections = parseOreSelections(values.get("ores"));
            int defaultCount = 0;
            int explicitCount = 0;
            for (SkylandsIslandBiomeDefinition.OreSelection oreSelection : oreSelections) {
                if (SkylandsIslandBiomes.DEFAULT_ORE_SENTINEL.equals(oreSelection.oreId())) {
                    defaultCount++;
                } else {
                    explicitCount++;
                }
            }
            System.out.println("[SKY-BIOME] parsed biomeId=" + biomeId + " features.size=" + features.size() + " underhangs.size=" + underhangs.size() + " ores.defaultTokens=" + defaultCount + " ores.explicitCount=" + explicitCount + " ores.selections.size=" + oreSelections.size());
            for (int i = 0; i < features.size(); i++) {
                SkylandsIslandBiomeDefinition.SurfaceFeatureDefinition f = features.get(i);
                System.out.println("[SKY-BIOME]   f" + i + " isPlaced=" + f.isPlacedFeature() + " placedId=" + f.placedFeatureId() + " block=" + f.featureBlock() + " d=" + f.clampedDensity() + " p=" + f.clampedProbability() + " H=" + f.clampedHeightRequired() + " tol=" + f.clampedToleranceRequired() + " R=" + f.clampedRadiusRequired() + " bottoms=" + (f.requiredBottoms() == null ? 0 : f.requiredBottoms().size()));
            }
            for (int i = 0; i < underhangs.size(); i++) {
                SkylandsIslandBiomeDefinition.UnderhangDefinition u = underhangs.get(i);
                System.out.println("[SKY-BIOME]   u" + i + " block=" + u.block() + " tip=" + u.tipBlock() + " min=" + u.clampedMinExtend() + " max=" + u.clampedMaxExtend() + " triesPerChunk=" + u.clampedTriesPerChunk() + " ceilings.size=" + (u.requiredCeilings() == null ? 0 : u.requiredCeilings().size()) + " rootReplace=" + u.rootReplace());
            }
            out.add(new SkylandsIslandBiomeDefinition(
                    biomeId,
                    parseOptionalBlockState(values.get("surface")),
                    parseOptionalBlockState(values.get("surfacedetail")),
                    parseSurfaceDetail(values.get("surfacedetail")),
                    parseSurfaceLayers(values.get("surfacelayers")),
                    subsurface,
                    parseOptionalBlockState(values.get("subsurfacedetail")),
                    parseBadlandsDetail(values.get("badlandsdetail")),
                    Boolean.parseBoolean(values.getOrDefault("deepenabled", "false")),
                    Mth.clamp(parseDouble(values.get("deeplayerline"), 0.5D), 0.0D, 1.0D),
                    parseBlockState(values.get("deep"), Blocks.DEEPSLATE.defaultBlockState()),
                    parseDeepDetails(values.get("deepdetail")),
                    oreSelections,
                    parseLiquidPools(values.get("liquidpools")),
                    features,
                    underhangs,
                    hangingState,
                    requirements,
                    parseInt(values.get("priority"), 0),
                    parseDouble(values.get("probability"), 1.0D),
                    values.getOrDefault("terrain", "default"),
                    parseResourceLocationList(values.get("structures")),
                    parseStringList(values.get("modded"))
            ));
        }
        return out.isEmpty() ? List.of(defaultDefinition()) : List.copyOf(out);
    }

    private static BlockState parseBlockState(String id, BlockState fallback) {
        if (id == null || id.isBlank()) {
            return fallback;
        }
        try {
            ResourceLocation key = ResourceLocation.parse(id);
            Block block = BuiltInRegistries.BLOCK.get(key);
            if (block == Blocks.AIR) {
                return fallback;
            }
            return block.defaultBlockState();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static BlockState parseOptionalBlockState(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String normalized = normalizeBlockIdAlias(id);
        try {
            ResourceLocation key = ResourceLocation.tryParse(normalized);
            if (key == null) {
                System.out.println("[SKY-BIOME] WARN invalid block id format: '" + id + "' normalized='" + normalized + "' -> fallback null");
                return null;
            }
            if (!BuiltInRegistries.BLOCK.containsKey(key)) {
                String fallbackName = key.getNamespace().equals("minecraft") ? key.toString() : key.toString();
                System.out.println("[SKY-BIOME] WARN unknown/unloaded block id: " + fallbackName + " (namespace=" + key.getNamespace() + ") — 请确认该模组方块已注册");
                return null;
            }
            Block block = BuiltInRegistries.BLOCK.get(key);
            return block == Blocks.AIR ? null : block.defaultBlockState();
        } catch (Exception e) {
            System.out.println("[SKY-BIOME] WARN parse block failed id='" + id + "' err=" + e.getMessage());
            return null;
        }
    }

    private static String normalizeBlockIdAlias(String raw) {
        String trimmed = raw.trim();
        if (trimmed.contains(":")) {
            return trimmed;
        }
        return switch (trimmed) {
            case "草方块" -> "minecraft:grass_block";
            case "泥土" -> "minecraft:dirt";
            case "砂土" -> "minecraft:coarse_dirt";
            case "石头" -> "minecraft:stone";
            case "圆石" -> "minecraft:cobblestone";
            case "沙子" -> "minecraft:sand";
            case "红沙" -> "minecraft:red_sand";
            case "陶瓦", "无色陶瓦" -> "minecraft:terracotta";
            case "白色陶瓦" -> "minecraft:white_terracotta";
            case "黑色陶瓦" -> "minecraft:black_terracotta";
            case "橙色陶瓦" -> "minecraft:orange_terracotta";
            case "黄色陶瓦" -> "minecraft:yellow_terracotta";
            case "红色陶瓦" -> "minecraft:red_terracotta";
            case "棕色陶瓦" -> "minecraft:brown_terracotta";
            default -> trimmed;
        };
    }

    private static Map<String, String> splitKeyValueEntry(String entry) {
        Map<String, String> values = new HashMap<>();
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bracketDepth = 0;
        for (int i = 0; i < entry.length(); i++) {
            char ch = entry.charAt(i);
            if (ch == '[') {
                bracketDepth++;
            } else if (ch == ']') {
                bracketDepth = Math.max(0, bracketDepth - 1);
            }
            if (ch == ';' && bracketDepth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        for (String part : parts) {
            int split = part.indexOf('=');
            if (split <= 0) {
                continue;
            }
            String key = part.substring(0, split).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(split + 1).trim();
            values.put(key, value);
        }
        return values;
    }

    private static List<SkylandsIslandBiomeDefinition.DeepDetailDefinition> parseDeepDetails(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        String trimmed = input.trim();
        if (!trimmed.contains(",")) {
            BlockState legacyState = parseOptionalBlockState(trimmed);
            if (legacyState == null) {
                return List.of();
            }
            return List.of(new SkylandsIslandBiomeDefinition.DeepDetailDefinition(legacyState, 24, 1.0D));
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<SkylandsIslandBiomeDefinition.DeepDetailDefinition> details = new ArrayList<>();
        for (String entry : trimmed.split(";")) {
            String token = entry.trim();
            if (token.isEmpty()) {
                continue;
            }
            String[] parts = token.split(",");
            BlockState state = parseOptionalBlockState(parts[0].trim());
            if (state == null) {
                continue;
            }
            int volume = parts.length >= 2 ? parseInt(parts[1].trim(), 24) : 24;
            double weight = parts.length >= 3 ? parseDouble(parts[2].trim(), 1.0D) : 1.0D;
            details.add(new SkylandsIslandBiomeDefinition.DeepDetailDefinition(state, Math.max(1, volume), Math.max(0.0D, weight)));
        }
        return List.copyOf(details);
    }

    private static SkylandsIslandBiomeDefinition.SurfaceDetailDefinition parseSurfaceDetail(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String trimmed = input.trim();
        int openIndex = trimmed.indexOf('[');
        int closeIndex = trimmed.lastIndexOf(']');
        if (openIndex <= 0 || closeIndex <= openIndex) {
            return null;
        }
        String rawType = trimmed.substring(0, openIndex).trim();
        String payload = trimmed.substring(openIndex + 1, closeIndex).trim();
        SkylandsIslandBiomeDefinition.SurfaceDetailType type = switch (rawType) {
            case "随机替换", "随机", "random" -> SkylandsIslandBiomeDefinition.SurfaceDetailType.RANDOM_REPLACE;
            case "团块", "blob" -> SkylandsIslandBiomeDefinition.SurfaceDetailType.BLOB;
            case "bulb", "巨石", "石包" -> SkylandsIslandBiomeDefinition.SurfaceDetailType.BULB;
            case "表层", "surface" -> SkylandsIslandBiomeDefinition.SurfaceDetailType.SURFACE_PATCH;
            default -> null;
        };
        if (type == null || payload.isEmpty()) {
            return null;
        }
        String[] parts = payload.split("[,，]", 4);
        if (type == SkylandsIslandBiomeDefinition.SurfaceDetailType.SURFACE_PATCH) {
            String[] surfaceParts = payload.split("[,，]", 3);
            if (surfaceParts.length < 2) {
                return null;
            }
            BlockState state = parseOptionalBlockState(surfaceParts[0].trim());
            if (state == null) {
                return null;
            }
            int volume = parseInt(surfaceParts[1].trim(), 64);
            double ratio = surfaceParts.length >= 3 ? parseDouble(surfaceParts[2].trim(), 1.0D) : 1.0D;
            return new SkylandsIslandBiomeDefinition.SurfaceDetailDefinition(
                    type,
                    List.of(state),
                    null,
                    ratio,
                    Math.max(1, volume)
            );
        }
        if (type == SkylandsIslandBiomeDefinition.SurfaceDetailType.BULB) {
            String[] bulbParts = payload.split("[,，]", 3);
            if (bulbParts.length < 3) {
                return null;
            }
            BlockState state = parseOptionalBlockState(bulbParts[0].trim());
            if (state == null) {
                return null;
            }
            int volume = parseInt(bulbParts[1].trim(), 48);
            double ratio = parseDouble(bulbParts[2].trim(), 0.18D);
            return new SkylandsIslandBiomeDefinition.SurfaceDetailDefinition(
                    type,
                    List.of(state),
                    null,
                    ratio,
                    Math.max(1, volume)
            );
        }
        if (parts.length < 3) {
            return null;
        }
        List<BlockState> states = new ArrayList<>();
        for (String token : parts[0].trim().split("[|;；]")) {
            BlockState state = parseOptionalBlockState(token.trim());
            if (state != null) {
                states.add(state);
            }
        }
        if (states.isEmpty()) {
            return null;
        }
        BlockState target = null;
        double ratio;
        int blobSize;
        if (type == SkylandsIslandBiomeDefinition.SurfaceDetailType.BLOB) {
            if (parts.length == 4) {
                target = parseOptionalBlockState(parts[1].trim());
                blobSize = parseInt(parts[2].trim(), 24);
                ratio = parseDouble(parts[3].trim(), 0.15D);
            } else {
                blobSize = parseInt(parts[1].trim(), 24);
                ratio = parseDouble(parts[2].trim(), 0.15D);
            }
        } else {
            if (parts.length == 4) {
                target = parseOptionalBlockState(parts[1].trim());
                ratio = parseDouble(parts[2].trim(), 0.15D);
                blobSize = parseInt(parts[3].trim(), 10);
            } else {
                ratio = parseDouble(parts[1].trim(), 0.15D);
                blobSize = parseInt(parts[2].trim(), 10);
            }
        }
        return new SkylandsIslandBiomeDefinition.SurfaceDetailDefinition(
                type,
                List.copyOf(states),
                target,
                ratio,
                Math.max(1, blobSize)
        );
    }

    private static List<SkylandsIslandBiomeDefinition.SurfaceLayerDefinition> parseSurfaceLayers(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        String trimmed = input.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<SkylandsIslandBiomeDefinition.SurfaceLayerDefinition> layers = new ArrayList<>();
        for (String entry : trimmed.split("[;；]")) {
            String token = entry.trim();
            if (token.isEmpty()) {
                continue;
            }
            String[] parts = token.split("[,，]", 2);
            BlockState state = parseOptionalBlockState(parts[0].trim());
            if (state == null) {
                continue;
            }
            int depth = parts.length >= 2 ? parseInt(parts[1].trim(), 1) : 1;
            layers.add(new SkylandsIslandBiomeDefinition.SurfaceLayerDefinition(state, Math.max(1, depth)));
        }
        return List.copyOf(layers);
    }

    private static SkylandsIslandBiomeDefinition.BadlandsDetailDefinition parseBadlandsDetail(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String trimmed = input.trim();
        String[] parts = trimmed.split(",", 4);
        if (parts.length < 4) {
            return null;
        }
        BlockState nearSurfaceState = parseOptionalBlockState(parts[0].trim());
        if (nearSurfaceState == null) {
            return null;
        }
        String[] thicknessParts = parts[1].trim().split("\\s*-\\s*", 2);
        int minThickness = thicknessParts.length >= 1 ? parseInt(thicknessParts[0].trim(), 1) : 1;
        int maxThickness = thicknessParts.length >= 2 ? parseInt(thicknessParts[1].trim(), minThickness) : minThickness;
        double splitLine = Mth.clamp(parseDouble(parts[2].trim(), 0.55D), 0.0D, 1.0D);
        String bandInput = parts[3].trim();
        if (bandInput.startsWith("[") && bandInput.endsWith("]")) {
            bandInput = bandInput.substring(1, bandInput.length() - 1).trim();
        }
        if (bandInput.isEmpty()) {
            return null;
        }
        List<BlockState> bandStates = new ArrayList<>();
        for (String token : bandInput.split(";")) {
            BlockState state = parseOptionalBlockState(token.trim());
            if (state != null) {
                bandStates.add(state);
            }
        }
        if (bandStates.isEmpty()) {
            return null;
        }
        return new SkylandsIslandBiomeDefinition.BadlandsDetailDefinition(
                nearSurfaceState,
                Math.max(1, minThickness),
                Math.max(Math.max(1, minThickness), maxThickness),
                splitLine,
                List.copyOf(bandStates)
        );
    }

    private static ResourceLocation parseResourceLocation(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            return ResourceLocation.parse(input.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static List<ResourceLocation> parseResourceLocationList(String input) {
        if (input == null || input.isBlank() || input.equals("*")) {
            return List.of();
        }
        List<ResourceLocation> result = new ArrayList<>();
        for (String part : input.split(",")) {
            ResourceLocation parsed = parseResourceLocation(part.trim());
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return List.copyOf(result);
    }

    private static List<SkylandsIslandBiomeDefinition.OreSelection> parseOreSelections(String input) {
        List<SkylandsIslandBiomeDefinition.OreSelection> result = new ArrayList<>();
        if (input == null || input.isBlank() || input.equals("*")) {
            result.add(new SkylandsIslandBiomeDefinition.OreSelection(SkylandsIslandBiomes.DEFAULT_ORE_SENTINEL, null));
            return List.copyOf(result);
        }
        boolean explicitDefault = false;
        for (String part : input.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            if ("*".equals(value) || "default".equalsIgnoreCase(value)) {
                if (!explicitDefault) {
                    explicitDefault = true;
                    result.add(new SkylandsIslandBiomeDefinition.OreSelection(SkylandsIslandBiomes.DEFAULT_ORE_SENTINEL, null));
                }
                continue;
            }
            int sourceSeparator = value.indexOf('@');
            String orePart = sourceSeparator >= 0 ? value.substring(0, sourceSeparator).trim() : value;
            String sourcePart = sourceSeparator >= 0 ? value.substring(sourceSeparator + 1).trim() : "";
            ResourceLocation oreId = parseResourceLocation(orePart);
            if (oreId == null) {
                System.out.println("[SKY-BIOME] WARN invalid ore id format: " + orePart + " — 需形如 minecraft:coal_ore 或 create:zinc_ore 或 default");
                continue;
            }
            if (SkylandsIslandBiomes.DEFAULT_ORE_SENTINEL.equals(oreId)) {
                if (!explicitDefault) {
                    explicitDefault = true;
                    result.add(new SkylandsIslandBiomeDefinition.OreSelection(SkylandsIslandBiomes.DEFAULT_ORE_SENTINEL, null));
                }
                continue;
            }
            if (!BuiltInRegistries.BLOCK.containsKey(oreId)) {
                String ns = oreId.getNamespace();
                if ("minecraft".equals(ns)) {
                    System.out.println("[SKY-BIOME] WARN unknown vanilla ore block: " + oreId + " — 请检查原版方块 ID 是否拼写正确");
                } else {
                    System.out.println("[SKY-BIOME] WARN unknown/unloaded ore block: " + oreId + " (namespace=" + ns + ") — 请确认对应模组（如 " + ns + "）已加载且该矿物方块已注册");
                }
                continue;
            }
            ResourceLocation sourceId = sourcePart.isEmpty() ? null : parseResourceLocation(sourcePart);
            result.add(new SkylandsIslandBiomeDefinition.OreSelection(oreId, sourceId));
        }
        if (result.isEmpty()) {
            result.add(new SkylandsIslandBiomeDefinition.OreSelection(SkylandsIslandBiomes.DEFAULT_ORE_SENTINEL, null));
        }
        return List.copyOf(result);
    }

    private static List<SkylandsIslandBiomeDefinition.LiquidPoolDefinition> parseLiquidPools(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        List<SkylandsIslandBiomeDefinition.LiquidPoolDefinition> result = new ArrayList<>();
        List<String> poolEntries = splitBracketGroup(input);
        for (String poolEntry : poolEntries) {
            String trimmed = poolEntry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            List<String> parts = splitCommaTopLevel(trimmed);
            if (parts.size() < 6) {
                continue;
            }
            BlockState liquid = parseOptionalBlockState(parts.get(0).trim());
            if (liquid == null) {
                continue;
            }
            double density = parseDouble(parts.get(1).trim(), 0.3D);
            String shape = parts.get(2).trim().toLowerCase(Locale.ROOT);
            int size = parseInt(parts.get(3).trim(), 3);
            int depth = parseInt(parts.get(4).trim(), 2);
            List<BlockState> bottomStates = parseBlockList(parts.get(5).trim());
            if (bottomStates.isEmpty()) {
                continue;
            }
            result.add(new SkylandsIslandBiomeDefinition.LiquidPoolDefinition(liquid, density, shape, size, depth, bottomStates));
        }
        return List.copyOf(result);
    }

    private static int estimatePlacedFeatureHeight(ResourceLocation id) {
        if (id == null) {
            return 6;
        }
        String name = id.getPath().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("mega_") || name.contains("huge_") || name.contains("dark_oak") || name.contains("fancy_oak") || name.contains("mangrove")) {
            return 14;
        }
        if (name.contains("pine") || name.contains("spruce") || name.contains("tall_birch") || name.contains("cherry") || name.contains("jungle")) {
            return 12;
        }
        if (name.contains("tree") || name.contains("_oak") || name.contains("oak_") || name.contains("birch") || name.contains("acacia")) {
            return 9;
        }
        if (name.contains("bush") || name.contains("mushroom") || name.contains("azalea")) {
            return 6;
        }
        return 8;
    }

    private static int estimatePlacedFeatureRadius(ResourceLocation id) {
        if (id == null) {
            return 2;
        }
        String name = id.getPath().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("mega_") || name.contains("huge_") || name.contains("fancy_oak") || name.contains("mangrove") || name.contains("dark_oak")) {
            return 4;
        }
        if (name.contains("pine") || name.contains("spruce") || name.contains("cherry") || name.contains("jungle") || name.contains("oak") || name.contains("birch") || name.contains("acacia") || name.contains("tree")) {
            return 3;
        }
        if (name.contains("bush") || name.contains("mushroom") || name.contains("azalea")) {
            return 2;
        }
        return 2;
    }

    private static int estimatePlacedFeatureTolerance(ResourceLocation id, int estimatedHeight, int estimatedRadius) {
        if (id != null) {
            String name = id.getPath().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("mega_") || name.contains("huge_") || name.contains("fancy_oak") || name.contains("mangrove") || name.contains("dark_oak")) {
                return 12;
            }
            if (name.contains("pine") || name.contains("spruce") || name.contains("cherry") || name.contains("jungle") || name.contains("oak") || name.contains("birch") || name.contains("acacia") || name.contains("tree")) {
                return 8;
            }
            if (name.contains("bush") || name.contains("mushroom") || name.contains("azalea")) {
                return 4;
            }
        }
        int volume = (2 * estimatedRadius + 1) * (2 * estimatedRadius + 1) * estimatedHeight;
        return Math.max(1, (int) Math.min(volume * 0.04D, estimatedHeight + estimatedRadius * 2));
    }

    private static List<SkylandsIslandBiomeDefinition.SurfaceFeatureDefinition> parseSurfaceFeatures(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        List<SkylandsIslandBiomeDefinition.SurfaceFeatureDefinition> result = new ArrayList<>();
        for (String part : input.split("#")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] fields = trimmed.split(",");
            if (fields.length < 4) {
                continue;
            }
            String nameField = fields[0].trim();
            BlockState featureBlock = null;
            ResourceLocation placedFeatureId = null;
            if (nameField.startsWith("@")) {
                placedFeatureId = parseResourceLocation(nameField.substring(1).trim());
            } else {
                featureBlock = parseOptionalBlockState(nameField);
            }
            if (featureBlock == null && placedFeatureId == null) {
                continue;
            }
            double density = parseDouble(fields[1].trim(), 0.1D);
            double probability = parseDouble(fields[2].trim(), 0.5D);
            List<BlockState> requiredBottoms = new ArrayList<>();
            String bottomField = fields[3].trim();
            if (!bottomField.isEmpty()) {
                for (String b : bottomField.split("\\|")) {
                    String trimmedB = b.trim();
                    if (!trimmedB.isEmpty()) {
                        BlockState bs = parseOptionalBlockState(trimmedB);
                        if (bs != null) {
                            requiredBottoms.add(bs);
                        }
                    }
                }
            }
            int heightRequired;
            int toleranceRequired;
            int radiusRequired;
            if (placedFeatureId != null) {
                int defH = estimatePlacedFeatureHeight(placedFeatureId);
                int defR = estimatePlacedFeatureRadius(placedFeatureId);
                int defT = estimatePlacedFeatureTolerance(placedFeatureId, defH, defR);
                heightRequired = fields.length >= 5 && !fields[4].trim().isEmpty() ? parseInt(fields[4].trim(), defH) : defH;
                toleranceRequired = fields.length >= 6 && !fields[5].trim().isEmpty() ? parseInt(fields[5].trim(), defT) : defT;
                radiusRequired = fields.length >= 7 && !fields[6].trim().isEmpty() ? parseInt(fields[6].trim(), defR) : defR;
            } else if (featureBlock != null) {
                Block fb = featureBlock.getBlock();
                int defH;
                int defR = 0;
                if (fb instanceof DoublePlantBlock || fb instanceof TallFlowerBlock) {
                    defH = 2;
                } else if (fb instanceof CactusBlock || fb instanceof SugarCaneBlock) {
                    defH = 3;
                } else if (fb instanceof BambooStalkBlock || fb instanceof BambooSaplingBlock) {
                    defH = 6;
                } else {
                    defH = 1;
                }
                int defT = Math.max(0, defH / 3);
                heightRequired = fields.length >= 5 && !fields[4].trim().isEmpty() ? parseInt(fields[4].trim(), defH) : defH;
                toleranceRequired = fields.length >= 6 && !fields[5].trim().isEmpty() ? parseInt(fields[5].trim(), defT) : defT;
                radiusRequired = fields.length >= 7 && !fields[6].trim().isEmpty() ? parseInt(fields[6].trim(), defR) : defR;
            } else {
                heightRequired = 1;
                toleranceRequired = 0;
                radiusRequired = 0;
            }
            result.add(new SkylandsIslandBiomeDefinition.SurfaceFeatureDefinition(featureBlock, placedFeatureId, density, probability, List.copyOf(requiredBottoms), heightRequired, toleranceRequired, radiusRequired));
        }
        return List.copyOf(result);
    }

    private static List<SkylandsIslandBiomeDefinition.UnderhangDefinition> parseUnderhangs(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        List<SkylandsIslandBiomeDefinition.UnderhangDefinition> result = new ArrayList<>();
        for (String part : input.split("#")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String inner = trimmed;
            if (inner.startsWith("underhang") || inner.startsWith("Underhang") || inner.startsWith("UNDERHANG")) {
                int lp = inner.indexOf('(');
                int rp = inner.lastIndexOf(')');
                if (lp >= 0 && rp > lp) {
                    inner = inner.substring(lp + 1, rp);
                } else {
                    int c = inner.indexOf('=');
                    if (c >= 0) inner = inner.substring(c + 1);
                }
            }
            inner = inner.replace('，', ',');
            String[] fields = inner.split(",");
            if (fields.length < 4) {
                continue;
            }
            BlockState block = parseOptionalBlockState(fields[0].trim());
            if (block == null) continue;
            int minExt = parseInt(fields[1].trim(), 1);
            int maxExt = parseInt(fields[2].trim(), minExt);
            int tries = parseInt(fields[3].trim(), 20);
            BlockState tip = fields.length >= 5 ? parseOptionalBlockState(fields[4].trim()) : null;
            List<BlockState> ceilings = List.of();
            if (fields.length >= 6) {
                String ceilStr = fields[5].trim();
                if (ceilStr.startsWith("[") && ceilStr.endsWith("]")) {
                    ceilStr = ceilStr.substring(1, ceilStr.length() - 1).trim();
                }
                if (!ceilStr.isEmpty()) {
                    List<BlockState> parsedCeils = new ArrayList<>();
                    for (String seg : ceilStr.split("\\|")) {
                        BlockState bs = parseOptionalBlockState(seg.trim());
                        if (bs != null) parsedCeils.add(bs);
                    }
                    ceilings = List.copyOf(parsedCeils);
                }
            }
            BlockState rootReplace = fields.length >= 7 ? parseOptionalBlockState(fields[6].trim()) : null;
            result.add(new SkylandsIslandBiomeDefinition.UnderhangDefinition(block, tip, minExt, maxExt, tries, ceilings, rootReplace));
        }
        return List.copyOf(result);
    }

    private static List<String> splitBracketGroup(String input) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<String> groups = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth = Math.max(0, depth - 1);
            } else if ((c == ';' || c == '；') && depth == 0) {
                String group = trimmed.substring(start, i).trim();
                if (!group.isEmpty()) {
                    groups.add(group);
                }
                start = i + 1;
            }
        }
        String last = trimmed.substring(start).trim();
        if (!last.isEmpty()) {
            groups.add(last);
        }
        return groups;
    }

    private static List<String> splitCommaTopLevel(String input) {
        String trimmed = input.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth = Math.max(0, depth - 1);
            } else if ((c == ',' || c == '，') && depth == 0) {
                parts.add(trimmed.substring(start, i).trim());
                start = i + 1;
            }
        }
        parts.add(trimmed.substring(start).trim());
        return parts;
    }

    private static List<BlockState> parseBlockList(String input) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<BlockState> result = new ArrayList<>();
        for (String part : trimmed.split("[;,；]")) {
            BlockState state = parseOptionalBlockState(part.trim());
            if (state != null) {
                result.add(state);
            }
        }
        return result;
    }

    private static List<String> parseStringList(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : input.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }

    private static SkylandsIslandBiomeDefinition.NoiseRequirements parseRequirements(String input) {
        if (input == null || input.isBlank()) {
            return new SkylandsIslandBiomeDefinition.NoiseRequirements(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        Map<String, List<SkylandsIslandBiomeDefinition.NoiseCondition>> requirements = new HashMap<>();
        for (String token : input.split(",")) {
            List<ParsedCondition> parsed = parseConditions(token.trim());
            for (ParsedCondition pc : parsed) {
                if (pc == null) {
                    continue;
                }
                requirements.computeIfAbsent(pc.key(), k -> new ArrayList<>()).add(pc.condition());
            }
        }
        return new SkylandsIslandBiomeDefinition.NoiseRequirements(
                requirements.getOrDefault("temperature", List.of()),
                requirements.getOrDefault("humidity", List.of()),
                requirements.getOrDefault("continentalness", List.of()),
                requirements.getOrDefault("erosion", List.of()),
                requirements.getOrDefault("weirdness", List.of()),
                requirements.getOrDefault("depth", List.of())
        );
    }

    private static List<ParsedCondition> parseConditions(String token) {
        if (token.isBlank()) {
            return List.of();
        }
        int rangeIdx = findRangeSeparator(token);
        if (rangeIdx >= 0) {
            String key = token.substring(0, rangeIdx).trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                return List.of();
            }
            String loStr = "";
            String hiStr = "";
            int eq = token.indexOf('=', 0);
            if (eq >= 0 && eq < rangeIdx) {
                loStr = token.substring(eq + 1, rangeIdx).trim();
            } else if (rangeIdx > 0) {
                int opEnd = rangeIdx;
                while (opEnd > 0 && "<>=".indexOf(token.charAt(opEnd - 1)) >= 0) {
                    opEnd--;
                }
                loStr = token.substring(opEnd, rangeIdx).trim();
                if (opEnd >= 2 && "<>=".indexOf(token.charAt(opEnd - 1)) < 0) {
                    key = token.substring(0, opEnd).trim().toLowerCase(Locale.ROOT);
                } else {
                    key = token.substring(0, Math.max(0, opEnd)).trim().toLowerCase(Locale.ROOT);
                }
                if (key.isEmpty()) {
                    return List.of();
                }
            }
            String rest = token.substring(rangeIdx + 2);
            int hiSep = -1;
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (i == 0 && (c == '+' || c == '-')) continue;
                if (c != '.' && !Character.isDigit(c)) {
                    hiSep = i;
                    break;
                }
            }
            hiStr = (hiSep < 0 ? rest : rest.substring(0, hiSep)).trim();
            double lo = parseDouble(loStr, Double.NaN);
            double hi = parseDouble(hiStr, Double.NaN);
            boolean bad = Double.isNaN(lo) || Double.isNaN(hi) || lo > hi;
            if (bad) {
                return List.of();
            }
            List<ParsedCondition> result = new ArrayList<>(2);
            result.add(new ParsedCondition(key, new SkylandsIslandBiomeDefinition.NoiseCondition(SkylandsIslandBiomeDefinition.Comparison.GREATER_THAN_OR_EQUAL, lo)));
            result.add(new ParsedCondition(key, new SkylandsIslandBiomeDefinition.NoiseCondition(SkylandsIslandBiomeDefinition.Comparison.LESS_THAN_OR_EQUAL, hi)));
            return List.copyOf(result);
        }
        ParsedCondition single = parseCondition(token);
        return single == null ? List.of() : List.of(single);
    }

    private static int findRangeSeparator(String token) {
        for (int i = 0; i + 1 < token.length(); i++) {
            char a = token.charAt(i);
            char b = token.charAt(i + 1);
            if ((a == '.' && b == '.') || (a == '~' && b == '~') || (a == '.' && b == '.' && i + 2 < token.length() && token.charAt(i + 2) == '.')) {
                return i;
            }
            if (a == '～' || a == '—') {
                return i;
            }
        }
        return -1;
    }

    private static ParsedCondition parseCondition(String token) {
        if (token.isBlank()) {
            return null;
        }
        String[] operators = new String[] {">=", "<=", ">", "<"};
        for (String operator : operators) {
            int idx = token.indexOf(operator);
            if (idx <= 0) {
                continue;
            }
            String key = token.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            double threshold = parseDouble(token.substring(idx + operator.length()).trim(), Double.NaN);
            if (Double.isNaN(threshold)) {
                return null;
            }
            return new ParsedCondition(key, new SkylandsIslandBiomeDefinition.NoiseCondition(parseComparison(operator), threshold));
        }
        return null;
    }

    private static SkylandsIslandBiomeDefinition.Comparison parseComparison(String operator) {
        return switch (operator) {
            case ">=" -> SkylandsIslandBiomeDefinition.Comparison.GREATER_THAN_OR_EQUAL;
            case "<=" -> SkylandsIslandBiomeDefinition.Comparison.LESS_THAN_OR_EQUAL;
            case "<" -> SkylandsIslandBiomeDefinition.Comparison.LESS_THAN;
            default -> SkylandsIslandBiomeDefinition.Comparison.GREATER_THAN;
        };
    }

    private static int parseInt(String input, int fallback) {
        if (input == null || input.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(input.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double parseDouble(String input, double fallback) {
        if (input == null || input.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(input.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static SkylandsIslandBiomeDefinition.NoiseContext sampleNoiseContext(long worldSeed, int x, int z) {
        double temperature = sampleSignedNoise(worldSeed ^ 0x1234ABCDL, x, z, 1.0D / 1800.0D, 3, 0.55D);
        double humidity = sampleSignedNoise(worldSeed ^ 0x55667788L, x, z, 1.0D / 1600.0D, 3, 0.50D);
        double continentalness = sampleSignedNoise(worldSeed ^ 0x51F2AC4DL, x, z, 1.0D / 2400.0D, 4, 0.5D);
        double erosion = sampleSignedNoise(worldSeed ^ 0x2C9277B5L, x, z, 1.0D / 1300.0D, 3, 0.5D);
        double weirdness = sampleSignedNoise(worldSeed ^ 0x7F4A7C15L, x, z, 1.0D / 900.0D, 4, 0.55D);
        double depth = sampleSignedNoise(worldSeed ^ 0x2468ACE1L, x, z, 1.0D / 2000.0D, 2, 0.5D);
        return new SkylandsIslandBiomeDefinition.NoiseContext(temperature, humidity, continentalness, erosion, weirdness, depth);
    }

    private static double sampleSignedNoise(long noiseSeed, int x, int z, double frequency, int octaves, double persistence) {
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

    private static long candidateHash(long worldSeed, int islandNoiseSeed, ResourceLocation biomeId) {
        return mix64(worldSeed
                ^ ((long) islandNoiseSeed * 0x9E3779B97F4A7C15L)
                ^ biomeId.hashCode() * 0x94D049BB133111EBL);
    }

    private static double normalizedHash(long worldSeed, int islandNoiseSeed, ResourceLocation biomeId, long salt) {
        long mixed = mix64(worldSeed
                ^ ((long) islandNoiseSeed * 0x9E3779B97F4A7C15L)
                ^ biomeId.hashCode() * 0x94D049BB133111EBL
                ^ salt);
        return ((mixed >>> 11) * 0x1.0p-53);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    private static SkylandsIslandBiomeDefinition defaultDefinition() {
        return new SkylandsIslandBiomeDefinition(
                ResourceLocation.parse("minecraft:plains"),
                Blocks.GRASS_BLOCK.defaultBlockState(),
                null,
                null,
                List.of(),
                Blocks.DIRT.defaultBlockState(),
                null,
                null,
                false,
                0.5D,
                Blocks.DEEPSLATE.defaultBlockState(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                new SkylandsIslandBiomeDefinition.NoiseRequirements(
                        List.of(new SkylandsIslandBiomeDefinition.NoiseCondition(SkylandsIslandBiomeDefinition.Comparison.GREATER_THAN_OR_EQUAL, -1.0D)),
                        List.of(new SkylandsIslandBiomeDefinition.NoiseCondition(SkylandsIslandBiomeDefinition.Comparison.GREATER_THAN_OR_EQUAL, -1.0D)),
                        List.of(new SkylandsIslandBiomeDefinition.NoiseCondition(SkylandsIslandBiomeDefinition.Comparison.GREATER_THAN_OR_EQUAL, -1.0D)),
                        List.of(new SkylandsIslandBiomeDefinition.NoiseCondition(SkylandsIslandBiomeDefinition.Comparison.GREATER_THAN_OR_EQUAL, -1.0D)),
                        List.of(new SkylandsIslandBiomeDefinition.NoiseCondition(SkylandsIslandBiomeDefinition.Comparison.GREATER_THAN_OR_EQUAL, -1.0D)),
                        List.of(new SkylandsIslandBiomeDefinition.NoiseCondition(SkylandsIslandBiomeDefinition.Comparison.GREATER_THAN_OR_EQUAL, -1.0D))
                ),
                Integer.MIN_VALUE,
                1.0D,
                "default",
                List.of(),
                List.of()
        );
    }

    private record ParsedCondition(String key, SkylandsIslandBiomeDefinition.NoiseCondition condition) {}

    private record Cache(List<? extends String> raw, List<SkylandsIslandBiomeDefinition> definitions) {}
}
