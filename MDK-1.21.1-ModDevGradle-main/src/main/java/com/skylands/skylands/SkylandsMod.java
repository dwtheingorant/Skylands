package com.skylands.skylands;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import com.skylands.skylands.command.SkylandsCommands;
import com.skylands.skylands.worldgen.SkylandsChunkGenerator;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(SkylandsMod.MODID)
public class SkylandsMod {
    public static final String MODID = "skylands";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, MODID);

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<SkylandsChunkGenerator>> SKYLANDS_CHUNK_GENERATOR =
            CHUNK_GENERATORS.register("skylands", () -> SkylandsChunkGenerator.CODEC);

    public SkylandsMod(IEventBus modEventBus, ModContainer modContainer) {
        CHUNK_GENERATORS.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(SkylandsCommands::register);
        modContainer.registerConfig(ModConfig.Type.SERVER, SkylandsConfig.SPEC);
    }
}
