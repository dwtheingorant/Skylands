package com.skylands.skylands.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.skylands.skylands.worldgen.SkylandsChunkGenerator;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class SkylandsCommands {
    private static final int DEFAULT_RADIUS = 24;
    private static final int MAX_RADIUS = 48;

    private SkylandsCommands() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("clearrocks")
                .requires(source -> source.hasPermission(2))
                .executes(context -> clearRocks(context.getSource(), DEFAULT_RADIUS))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                        .executes(context -> clearRocks(
                                context.getSource(),
                                IntegerArgumentType.getInteger(context, "radius")
                        ))));
        dispatcher.register(Commands.literal("oredebug")
                .requires(source -> source.hasPermission(2))
                .executes(context -> oreDebug(context.getSource())));
    }

    private static int clearRocks(CommandSourceStack source, int radius) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        int minY = Math.max(level.getMinBuildHeight(), center.getY() - radius);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + radius);
        int cleared = 0;

        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    if (!shouldClear(level.getBlockState(cursor))) {
                        continue;
                    }
                    level.setBlock(cursor, air, 3);
                    cleared++;
                }
            }
        }

        int finalCleared = cleared;
        source.sendSuccess(
                () -> Component.literal("已清除半径 " + radius + " 内的 stone/deepslate，共 " + finalCleared + " 个方块"),
                true
        );
        return cleared;
    }

    private static int oreDebug(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        if (!(level.getChunkSource().getGenerator() instanceof SkylandsChunkGenerator generator)) {
            source.sendFailure(Component.literal("当前维度的 ChunkGenerator 不是 SkylandsChunkGenerator"));
            return 0;
        }
        String report = generator.debugIslandOreAt(level, player.blockPosition());
        source.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static boolean shouldClear(BlockState state) {
        if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.TERRACOTTA)) {
            return true;
        }
        return state.is(Blocks.CALCITE) || state.is(Blocks.SMOOTH_BASALT);
    }
}
