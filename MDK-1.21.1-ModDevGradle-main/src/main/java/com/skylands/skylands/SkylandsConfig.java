package com.skylands.skylands;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class SkylandsConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // 关于如何定义biome
    //  在配置文件中写入一种空岛
    //  包含
    //  地表方块（表层，可以为空，为空会被地下方块代替）
    //  地表副方块（点缀，可以为空）
    //  地下方块（表层下填充）
    //  地下副方块（点缀，类似原版的闪长岩，可以为空）
    //  是否启用深层（是否启用深层方块，默认否）
    //  深层启用线（比例，下而上，比如0.7代表下部分70%为深层，默认0.5）
    //  深层方块（深层，默认深板岩，即使不启用深层也可以填，只是不生成）
    //  深层副方块（点缀，类似原版凝灰岩，可以为空）
    //  矿石列表（可生成矿物，不填默认全选）
    //  悬挂方块（悬挂在洞穴顶部与岛屿尖锥上，可以为空）
    //  生成要求（噪声要求，必须全填不然不生成，例如湿度>0.5、温度>0.5等）
    //  生成优先度（决定在多个满足的群系中生成哪个）
    //  生成概率（目前没用，默认1，效果为生成成功的概率）
    //  地形（决定地形起伏等）
    //  结构列表（决定可生成结构）
    //  与模组联动内容（先不急，但是留个位置）

    public static final ModConfigSpec.IntValue MIN_RADIUS =
            BUILDER.comment(
                    "关于如何定义biome",
                    " 在配置文件中写入一种空岛",
                    " 包含",
                    " 地表方块（表层，可以为空，为空会被地下方块代替）",
                    " 地表副方块（点缀，可以为空）",
                    " 地下方块（表层下填充）",
                    " 地下副方块（点缀，类似原版的闪长岩，可以为空）",
                    " 是否启用深层（是否启用深层方块，默认否）",
                    " 深层启用线（比例，下而上，比如0.7代表下部分70%为深层，默认0.5）",
                    " 深层方块（深层，默认深板岩，即使不启用深层也可以填，只是不生成）",
                    " 深层副方块（点缀，类似原版凝灰岩，可以为空）",
                    " 矿石列表（可生成矿物，不填默认全选）",
                    " 悬挂方块（悬挂在洞穴顶部与岛屿尖锥上，可以为空）",
                    " 生成要求（噪声要求，必须全填不然不生成，例如湿度>0.5、温度>0.5等）",
                    " 生成优先度（决定在多个满足的群系中生成哪个）",
                    " 生成概率（目前没用，默认1，效果为生成成功的概率）",
                    " 地形（决定地形起伏等）",
                    " 结构列表（决定可生成结构）",
                    " 与模组联动内容（先不急，但是留个位置）"
            ).defineInRange("islands.minRadius", 64, 50, 100000);
    public static final ModConfigSpec.IntValue MAX_RADIUS =
            BUILDER.defineInRange("islands.maxRadius", 90, 50, 100000);
    public static final ModConfigSpec.IntValue SPACING =
            BUILDER.defineInRange("islands.spacing", 1500, 128, 100000);
    public static final ModConfigSpec.IntValue CENTER_Y =
            BUILDER.defineInRange("islands.centerY", 128, -64, 320);
    public static final ModConfigSpec.IntValue HEIGHT_JITTER =
            BUILDER.defineInRange("islands.heightJitter", 32, 0, 256);
    public static final ModConfigSpec.IntValue MIN_TARGET_Y =
            BUILDER.defineInRange("islands.minTargetY", 20, -64, 320);
    public static final ModConfigSpec.IntValue MAX_TARGET_Y =
            BUILDER.defineInRange("islands.maxTargetY", 120, -64, 320);
    public static final ModConfigSpec.IntValue SURFACE_ANCHOR_MAX_RISE =
            BUILDER.defineInRange("islands.surfaceAnchorMaxRise", 50, 0, 256);
    public static final ModConfigSpec.IntValue SURFACE_ANCHOR_MAX_DROP =
            BUILDER.defineInRange("islands.surfaceAnchorMaxDrop", 40, 0, 256);
    public static final ModConfigSpec.IntValue SURFACE_SHELL_DEPTH =
            BUILDER.defineInRange("islands.surfaceShellDepth", 6, 1, 32);
    public static final ModConfigSpec.DoubleValue VERTICAL_SCALE =
            BUILDER.defineInRange("islands.verticalScale", 0.35D, 0.05D, 5.0D);
    public static final ModConfigSpec.DoubleValue EDGE_NOISE =
            BUILDER.defineInRange("islands.edgeNoise", 24.0D, 0.0D, 512.0D);
    public static final ModConfigSpec.BooleanValue ENABLE_OCEAN_ISLANDS =
            BUILDER.define("islands.enableOceanIslands", false);

    public static final ModConfigSpec.BooleanValue ISLAND_BIOME_OVERRIDE_ENABLED =
            BUILDER.define("islandBiomes.overrideEnabled", true);
    public static final ModConfigSpec.IntValue ISLAND_BIOME_MARGIN =
            BUILDER.defineInRange("islandBiomes.margin", 50, 0, 64);
    public static final ModConfigSpec.ConfigValue<String> ISLAND_BIOME_DEFINITION_FORMAT =
            BUILDER.comment(
                    "Biome entry format:",
                    "surfaceDetail modes:",
                    "- empty or <block_id>: legacy surface patch (top block only)",
                    "- 随机替换[blocks,(target),ratio,patchSize]: top-only patch; patchSize=1 becomes per-block random",
                    "- 团块[blocks,(target),volume,ratio]: blob across surface shell depth",
                    "- bulb[block,volume,ratio] or 巨石[block,volume,ratio]: ground boulder sitting on the surface",
                    "- 表层[block,volume,(ratio)]: top-only patch (large plates); ignores steep side exposure",
                    "biome=<biome_id>;" +
                            "surface=<block_id_or_empty>;" +
                            "surfaceDetail=<block_id_or_empty>;" +
                            "surfaceLayers=<empty or [block_id,count;block_id,count;...]>;" +
                            "subsurface=<block_id_or_empty (default stone)>;" +
                            "subsurfaceDetail=<block_id_or_empty>;" +
                            "badlandsDetail=<empty or near_surface_block,min-max,split_line,[band_block;band_block;...] >;" +
                            "deepEnabled=<true_or_false>;" +
                            "deepLayerLinie=<0..1>;" +
                            "deep=<block_d>;" +
                            "deepDetail=<empty or block_id or [block_id,volume,ratio;block_id,volume,ratio;...]>;" +
                            "ores=<* or comma separated ore ids; ore_id=generic source, ore_id@placed_feature_id=specific special source, ore_id@biome_id=all special sources referenced by that biome>;" +
                            "liquidPools=<empty or [[liquid_id,density,shape,size,depth,[bottom1;bottom2;...]];[definition2;...]]>;" +
                            "features=<empty or block_id,density,probability,required_bottom;...>;" +
                            "hanging=<block_id_or_empty>;" +
                            "requirements=temperature>value,humidity>value,continentalness>value,erosion<value,weirdness>value,depth>value;" +
                            "priority=<int>;" +
                            "probability=<0..1>;" +
                            "terrain=<name: 平坦/起伏/山丘/多山/高峰/平顶山/火山 or flat/rolling/hills/mountains/peaks/mesa/volcano>;" +
                            "structures=<comma separated ids or empty>;" +
                            "modded=<comma separated placeholders or empty>"
            ).define("islandBiomes.definitionFormat", "");
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> ISLAND_BIOME_DEFINITIONS =
            BUILDER.defineListAllowEmpty(
                    "islandBiomes.definitions",
                    java.util.List.of(
                            "biome=minecraft:desert;surface=;surfaceDetail=;surfaceLayers=[minecraft:grass_block,1;minecraft:dirt,2;minecraft:coarse_dirt,1;minecraft:rooted_dirt,2;minecraft:stone,1;minecraft:andesite,2;minecraft:granite,1;minecraft:diorite,2;minecraft:tuff,3;minecraft:gravel,2];subsurface=minecraft:stone;subsurfaceDetail=;badlandsDetail=;deepEnabled=true;deepLayerLine=0.5;deep=minecraft:deepslate;deepDetail=[minecraft:tuff,500,7;minecraft:dirt,100,3];ores=*;hanging=;requirements=temperature>=-1.0,humidity>=-1.0,continentalness>=-1.0,erosion>=-1.0,weirdness>=-1.0,depth>=-1.0;priority=-2147483648;probability=1.0;terrain=mesa;structures=;modded="
                    ),
                    entry -> entry instanceof String
            );

    public static final ModConfigSpec.IntValue ORE_ISLAND_SPACING =
            BUILDER.defineInRange("oreIslands.spacing", 1000, 64, 100000);
    public static final ModConfigSpec.IntValue ORE_ISLAND_MIN_RADIUS =
            BUILDER.defineInRange("oreIslands.minRadius", 8, 1, 64);
    public static final ModConfigSpec.IntValue ORE_ISLAND_MAX_RADIUS =
            BUILDER.defineInRange("oreIslands.maxRadius", 16, 1, 64);
    public static final ModConfigSpec.IntValue ORE_ISLAND_CENTER_Y =
            BUILDER.defineInRange("oreIslands.centerY", -55, -64, -1);
    public static final ModConfigSpec.IntValue ORE_ISLAND_PASSES =
            BUILDER.defineInRange("oreIslands.passes", 5, 1, 16);
    public static final ModConfigSpec.DoubleValue ORE_ISLAND_ORE_CHANCE =
            BUILDER.defineInRange("oreIslands.oreChance", 0.45D, 0.0D, 1.0D);
    public static final ModConfigSpec.IntValue ORE_ISLAND_HEIGHT_JITTER =
            BUILDER.defineInRange("oreIslands.heightJitter", 24, 0, 128);

    public static final ModConfigSpec.IntValue DEBRIS_LARGE_ISLAND_RADIUS =
            BUILDER.defineInRange("debris.largeIslandRadius", 700, 64, 100000);
    public static final ModConfigSpec.IntValue DEBRIS_MIN_COUNT =
            BUILDER.defineInRange("debris.minCount", 3, 0, 32);
    public static final ModConfigSpec.IntValue DEBRIS_MAX_COUNT =
            BUILDER.defineInRange("debris.maxCount", 7, 0, 64);
    public static final ModConfigSpec.IntValue DEBRIS_MAX_RADIUS =
            BUILDER.defineInRange("debris.maxRadius", 6, 1, 32);
    public static final ModConfigSpec.DoubleValue DEBRIS_ORE_CHANCE =
            BUILDER.defineInRange("debris.oreChance", 0.14D, 0.0D, 1.0D);

    public static final ModConfigSpec.BooleanValue STRONGHOLD_WRAP_ISLAND =
            BUILDER.define("stronghold.wrapIsland", true);
    public static final ModConfigSpec.IntValue STRONGHOLD_WRAP_PADDING =
            BUILDER.defineInRange("stronghold.wrapPadding", 16, 0, 256);
    public static final ModConfigSpec.BooleanValue STRONGHOLD_DELETE_IF_NOT_ON_ISLAND =
            BUILDER.define("stronghold.deleteIfNotOnIsland", false);
    public static final ModConfigSpec.IntValue STRONGHOLD_MOSS_SEEDS =
            BUILDER.defineInRange("stronghold.mossSeeds", 6, 0, 256);
    public static final ModConfigSpec.IntValue STRONGHOLD_MOSS_SPREAD_STEPS =
            BUILDER.defineInRange("stronghold.mossSpreadSteps", 200, 0, 100000);
    public static final ModConfigSpec.DoubleValue STRONGHOLD_MOSS_SPREAD_CHANCE =
            BUILDER.defineInRange("stronghold.mossSpreadChance", 0.6D, 0.0D, 1.0D);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private SkylandsConfig() {}
}
