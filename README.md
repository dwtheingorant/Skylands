# Skylands

> **Minecraft 1.21.1 NeoForge Mod** — 浮空岛世界生成模组

Skylands 是一个针对 Minecraft 主世界（Overworld）的世界生成模组，将原本的陆地替换为漂浮在虚空中的群岛。模组严格遵循原版读感（Vanilla Style），保持原版随机概率、方块状态和群系分布逻辑，同时提供高度可配置的岛屿生成参数。

***

## 项目特性

- **网格化岛屿生成**：基于间距（spacing）网格化投放岛屿，支持自定义密度、半径、高度抖动
- **33 个原版群系全覆盖**：通过 K-means 聚类分析气候噪声空间，确保所有定义群系均实现非零覆盖，冲突时默认 fallback 至 `plains`
- **原版矿石自动混入**：自动扫描并兼容模组矿石（如 Create 等），矿石生成遵循原版高度带与分布概率
- **多格植被与结构支持**：原生支持树木、大型蘑菇等多格高植被的正确生成
- **末地要塞适配**：`stronghold.wrapIsland` 选项可将要塞包裹于岛屿内部，并附加苔藓化效果
- **深层矿岛系统**：`oreIslands.*` 配置项在 Y<0 区域生成小型富矿岛，支持远古残骸碎片（debris）集群

***

## 代码库结构

```
skylands/
├── .gitignore                          # 根级 Git 忽略规则
├── README.md                           # 本文件
└── MDK-1.21.1-ModDevGradle-main/       # NeoForge MDK 工程（主代码）
    ├── .gitignore                      # MDK 子级 Git 忽略规则
    ├── .github/
    │   └── workflows/
    │       └── build.yml               # GitHub Actions 自动构建
    ├── .gitattributes
    ├── build.gradle                    # Gradle 构建脚本（ModDevGradle 2.x）
    ├── gradle.properties               # 模组版本、依赖版本、Mod ID 等
    ├── settings.gradle
    ├── gradlew / gradlew.bat           # Gradle Wrapper
    ├── gradle/
    │   └── wrapper/
    │       ├── gradle-wrapper.jar
    │       └── gradle-wrapper.properties
    ├── debug-run-client-launch.md      # 客户端启动调试备忘
    ├── TEMPLATE_LICENSE.txt            # MDK 原始许可模板
    ├── run/                            # 本地运行目录（Git 忽略）
    ├── build/                          # 编译输出目录（Git 忽略）
    └── src/
        ├── main/
        │   ├── java/com/skylands/skylands/
        │   │   ├── SkylandsMod.java                # Mod 主入口类 (@Mod)
        │   │   ├── SkylandsConfig.java             # 服务端配置（岛屿/群系/矿石/要塞）
        │   │   ├── command/
        │   │   │   └── SkylandsCommands.java       # 调试命令注册
        │   │   └── worldgen/
        │   │       ├── SkylandsChunkGenerator.java # 核心区块生成器（岛屿构造/矿石/植被/液池）
        │   │       ├── SkylandsIslands.java        # 岛屿网格化定位与候选筛选
        │   │       ├── SkylandsNoise.java          # 噪声采样封装
        │   │       └── biome/
        │   │           ├── SkylandsIslandBiomeDefinition.java  # 群系定义数据结构
        │   │           ├── SkylandsIslandBiomeSource.java      # 群系源（气候点匹配）
        │   │           └── SkylandsIslandBiomes.java           # 群系解析、条件、缓存
        │   ├── resources/
        │   │   ├── META-INF/
        │   │   ├── assets/skylands/
        │   │   │   └── lang/en_us.json             # 英文本地化
        │   │   └── data/
        │   │       ├── minecraft/tags/worldgen/world_preset/
        │   │       │   └── normal.json             # 将 Skylands 加入默认世界预设标签
        │   │       └── skylands/worldgen/
        │   │           └── world_preset/
        │   │               └── skylands.json       # Skylands 世界预设（仅覆盖主世界生成器）
        │   └── templates/
        │       └── META-INF/
        │           └── neoforge.mods.toml          # Mod 元数据模板（构建时属性展开）
        └── generated/                              # DataGen 输出（Git 忽略缓存）
```

***

## 环境要求

| 项目                 | 版本                     |
| ------------------ | ---------------------- |
| Minecraft          | 1.21.1                 |
| NeoForge           | 21.1.233               |
| Java               | 21（工具链自动下载）            |
| Gradle             | 9.2.1（通过 Wrapper 提供）   |
| ModDevGradle       | 2.0.141                |
| Parchment Mappings | 2024.11.17（for 1.21.1） |

***

## 编译与构建

### 1. 克隆项目

```bash
git clone git@github.com:dwtheingorant/Skylands.git
cd Skylands
```

### 2. 编译生产环境 JAR

```bash
cd MDK-1.21.1-ModDevGradle-main
./gradlew build
```

构建产物位于：

```
MDK-1.21.1-ModDevGradle-main/build/libs/skylands-<mod_version>.jar
```

将此 JAR 放入服务器/客户端的 `mods/` 目录即可加载。

### 3. 本地运行调试

```bash
# 运行客户端（单人游戏调试）
./gradlew runClient

# 运行服务端（无头服务器测试）
./gradlew runServer

# 运行 DataGen（资源/数据生成）
./gradlew runData

# 运行 GameTest
./gradlew runGameTestServer
```

> 首次运行会自动下载 Minecraft 客户端/服务端 jar、资源文件及映射表，需要稳定网络。
> 如果依赖缺失或 IDE 索引异常，可执行 `./gradlew --refresh-dependencies` 刷新缓存。

### 4. IDE 支持

推荐使用 **IntelliJ IDEA**。项目克隆后直接在 IDEA 中打开 `MDK-1.21.1-ModDevGradle-main/build.gradle`，IDEA 会自动导入 Gradle 项目并配置好运行任务。

构建脚本已启用：

- IDEA 自动下载依赖源码与 Javadoc
- Parchment 参数名映射（Minecraft 方法参数更清晰）
- 生成 `generateModMetadata` 任务在每次 IDE 同步时自动展开 `neoforge.mods.toml`

***

## 安装与使用

### 玩家/服务器安装

1. 确保已安装 **NeoForge 1.21.1-21.1.233** 或兼容版本加载器
2. 将 `skylands-*.jar` 复制到 `mods/` 文件夹
3. 创建新世界时，在「世界预设」中选择 **Skylands**
   - 或通过 `server.properties` 设置：
     ```properties
     level-type=skylands:skylands
     ```

### 与其他模组兼容

Skylands 的矿石生成系统会自动扫描注册到原版 OreFeature 的模组矿石并混入对应群系（Create、CoFH 等模组无需额外配置）。

配置文件中的 `ores=<* or comma separated ore ids>` 支持：

- `*` — 自动收录所有矿石
- `ore_id` — 指定通用矿石源
- `ore_id@placed_feature_id` — 指定具体放置特征源
- `ore_id@biome_id` — 引用某群系的全部特殊矿石源

***

## 配置说明

Skylands 使用服务端配置文件（`.toml`），首次启动后生成于：

```
<world>/serverconfig/skylands-server.toml
```

配置分 5 个大组：

### islands — 主岛屿参数

| 键                    | 默认值   | 说明                       |
| -------------------- | ----- | ------------------------ |
| `minRadius`          | 64    | 岛屿最小半径（方块）               |
| `maxRadius`          | 90    | 岛屿最大半径                   |
| `spacing`            | 1500  | 岛屿网格间距；**500 约等于 9 倍密度** |
| `centerY`            | 128   | 岛屿中心基准 Y                 |
| `heightJitter`       | 32    | 高度抖动幅度                   |
| `verticalScale`      | 0.35  | 垂直压缩系数（越小越扁）             |
| `edgeNoise`          | 24.0  | 边缘扰动幅度                   |
| `enableOceanIslands` | false | 是否生成海洋类岛屿                |
| `surfaceShellDepth`  | 6     | 表层壳厚度（地表副方块生效范围）         |

### islandBiomes — 群系覆盖 & 地层

| 键                  | 说明                  |
| ------------------ | ------------------- |
| `overrideEnabled`  | 是否启用群系地层覆盖（默认 true） |
| `margin`           | 岛屿边缘到群系判定的内缩        |
| `definitionFormat` | 单条定义的格式说明（见下方）      |
| `definitions`      | 33 条群系定义（默认内置全覆盖配置） |

**单条群系定义语法**：

```
biome=<biome_id>;
surface=<top_block>;
surfaceDetail=<detail_mode>;
surfaceLayers=[block,count;...];
subsurface=<stone_block>;
subsurfaceDetail=<...>;
badlandsDetail=<...>;
deepEnabled=<true/false>;
deepLayerLine=<0..1>;
deep=<deepslate_block>;
deepDetail=<[block,volume,ratio;...]>;
ores=<* or list>;
liquidPools=<...>;
features=<...>;
hanging=<block>;
requirements=temperature>,humidity>,continentalness>,erosion<,weirdness>,depth>;
priority=<int>;
probability=<0..1>;
terrain=<平坦/起伏/山丘/多山/高峰/平顶山/火山>;
structures=<list>;
modded=<...>
```

> **注意**：`priority` 相邻群系的差值保持 ≥ 1，以稳定 tie-breaker。
> `stony_peaks` 与 `windswept_hills` 的 `deepLayerLine` 默认 0.8（更厚的石层）。

### oreIslands — 深层富矿岛

| 键                         | 默认值    | 说明           |
| ------------------------- | ------ | ------------ |
| `spacing`                 | 1000   | 矿岛网格间距       |
| `minRadius` / `maxRadius` | 8 / 16 | 矿岛半径范围       |
| `centerY`                 | -55    | 生成中心 Y（深板岩层） |
| `passes`                  | 5      | 矿石生成轮次       |
| `oreChance`               | 0.45   | 单格矿石命中率      |
| `heightJitter`            | 24     | Y 抖动         |

### debris — 远古残骸集群

| <br />                  | 键     | 默认值      |
| :---------------------- | ----- | -------- |
| `largeIslandRadius`     | 700   | 残骸集群触发半径 |
| `minCount` / `maxCount` | 3 / 7 | 每集群碎片数   |
| `maxRadius`             | 6     | 碎片散布半径   |
| `oreChance`             | 0.14  | 碎片单格命中率  |

### stronghold — 末地要塞适配

| 键                                                    | 默认值           | 说明                      |
| ---------------------------------------------------- | ------------- | ----------------------- |
| `wrapIsland`                                         | true          | 把要塞包裹在一个岛屿方块壳里（避免悬浮/浮空） |
| `wrapPadding`                                        | 16            | 外壳离要塞结构的外扩距离            |
| `deleteIfNotOnIsland`                                | false         | 若要塞未落在岛屿上则移除            |
| `mossSeeds` / `mossSpreadSteps` / `mossSpreadChance` | 6 / 200 / 0.6 | 要塞苔藓化效果参数               |

***

## 分支与开发流程

- **`main`** — 主开发分支，日常提交与持续集成均在此分支
- **`V.0.1`** — 首个版本冻结分支（tag 可基于此打 `v0.1.0`）
- 新功能/实验性改动建议在 feature 分支开发后 PR 合入 `main`

### 首次提交流程

```bash
cd skylands
git init
git checkout -b main
git add .
git commit -m "Initial commit: Skylands 0.1 (NeoForge 1.21.1)"
git remote add origin git@github.com:dwtheingorant/Skylands.git
git checkout -b V.0.1
git push -u origin V.0.1
git checkout main
git push -u origin main
```

***

## CI / CD

仓库内置 GitHub Actions 工作流 `.github/workflows/build.yml`：

- 触发时机：push / pull\_request
- 运行环境：Ubuntu latest
- 自动执行：`./gradlew build`（JDK 21 Temurin + Setup Gradle v4）
- 确保提交后构建能通过、测试不回退

***

## 技术备忘

- **群系回退机制**：当气候点匹配失败或多群系冲突时，默认 fallback 至 `plains`
- **多格植被**：`SkylandsChunkGenerator.placeFeatureBlockDirect` 统一处理多格高植被的方块占位
- **空间切割策略**：利用 `weirdness` 轴正负区间或温湿度 4D 矩形进行二分，保证群系无重叠
- **噪声偏差修正**：`valueNoise` 极端值采样概率低，通过 fallback 空间聚类缩放 `requirements` 到对应气候簇边界，避免理论覆盖率与实际生成偏差

***

## 参考资源

- NeoForged 官方文档：<https://docs.neoforged.net/>
- NeoForged Discord：<https://discord.neoforged.net/>
- ModDevGradle README：<https://github.com/neoforged/ModDevGradle>
- Parchment Mappings：<https://parchmentmc.org/docs/getting-started>

