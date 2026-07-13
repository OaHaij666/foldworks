package com.foldworks.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.foldworks.config.ModConfig;
import com.foldworks.dimension.ProductionSpaceManager;
import com.foldworks.space.SpaceData;
import com.foldworks.space.SpaceManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class FoldworksCommand {
    private FoldworksCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("foldworks")
                .requires(src -> src.hasPermission(0))
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("size", IntegerArgumentType.integer(16, 512))
                        .then(Commands.argument("terrain", StringArgumentType.word())
                        .executes(FoldworksCommand::create)))))
                .then(Commands.literal("list").executes(FoldworksCommand::list))
                .then(Commands.literal("enter")
                        .then(Commands.argument("space", StringArgumentType.word())
                        .executes(FoldworksCommand::enter)))
                .then(Commands.literal("exit").executes(FoldworksCommand::exit))
                .then(Commands.literal("delete")
                        .then(Commands.argument("space", StringArgumentType.word())
                        .executes(FoldworksCommand::delete))));
    }

    private static int create(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = getPlayer(context);
        if (player == null) return 0;

        String name = StringArgumentType.getString(context, "name");
        int size = IntegerArgumentType.getInteger(context, "size");
        SpaceData.TerrainType terrain = parseTerrain(StringArgumentType.getString(context, "terrain"));
        if (terrain == null) {
            send(context, Component.translatable("commands.foldworks.create.invalid_terrain"), ChatFormatting.RED);
            return 0;
        }

        MinecraftServer server = context.getSource().getServer();
        try {
            int height = ModConfig.DEFAULT_SPACE_HEIGHT.get();
            String biome = ModConfig.DEFAULT_SPACE_BIOME.get();
            net.minecraft.resources.ResourceLocation sourceDim =
                    net.minecraft.resources.ResourceLocation.parse(ModConfig.DEFAULT_SPACE_SOURCE_DIMENSION.get());
            boolean mobSpawning = ModConfig.DEFAULT_SPACE_MOB_SPAWNING.get();
            boolean structures = ModConfig.DEFAULT_SPACE_STRUCTURES.get();
            float amplitude = (float) ModConfig.DEFAULT_SPACE_AMPLITUDE.get().doubleValue();
            SpaceData space = SpaceManager.getInstance().createSpace(server, player.getUUID(), size, height, size,
                    terrain, biome, sourceDim,
                    mobSpawning, structures, false, amplitude);
            space.setName(name);
            ProductionSpaceManager.getInstance().queueTeleportToSpace(player, space);
            send(context, Component.translatable("commands.foldworks.create.success", name, shortId(space)), ChatFormatting.GREEN);
        } catch (com.foldworks.space.SpaceLimitExceededException e) {
            send(context, Component.translatable("commands.foldworks.create.limit_exceeded", e.max()), ChatFormatting.RED);
        }
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = getPlayer(context);
        if (player == null) return 0;

        var spaces = SpaceManager.getInstance().getAccessibleSpaces(player.getUUID());
        if (spaces.isEmpty()) {
            send(context, Component.translatable("commands.foldworks.list.empty"), ChatFormatting.YELLOW);
            return 0;
        }

        for (SpaceData space : spaces) {
            send(context, Component.translatable("commands.foldworks.list.entry",
                    shortId(space), space.getName(), space.getWidth() + "x" + space.getDepth(),
                    space.getTerrainType().name(), space.getDimensionId().toString()), ChatFormatting.AQUA);
        }
        return spaces.size();
    }

    private static int enter(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = getPlayer(context);
        if (player == null) return 0;

        SpaceData space = findSpace(context, StringArgumentType.getString(context, "space"));
        if (space == null || !space.canAccess(player.getUUID())) {
            send(context, Component.translatable("commands.foldworks.enter.not_found"), ChatFormatting.RED);
            return 0;
        }

        ProductionSpaceManager.getInstance().teleportToSpace(player, space);
        send(context, Component.translatable("commands.foldworks.enter.success", space.getName()), ChatFormatting.GREEN);
        return 1;
    }

    private static int exit(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = getPlayer(context);
        if (player == null) return 0;
        ProductionSpaceManager.getInstance().exitToReturnPosition(player);
        send(context, Component.translatable("commands.foldworks.exit.success"), ChatFormatting.GREEN);
        return 1;
    }

    private static int delete(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = getPlayer(context);
        if (player == null) return 0;

        SpaceData space = findSpace(context, StringArgumentType.getString(context, "space"));
        if (space == null) {
            send(context, Component.translatable("commands.foldworks.delete.not_found"), ChatFormatting.RED);
            return 0;
        }

        boolean deleted = SpaceManager.getInstance().deleteSpace(context.getSource().getServer(), space.getSpaceId(), player.getUUID());
        if (deleted) {
            send(context, Component.translatable("commands.foldworks.delete.success", space.getName()), ChatFormatting.GREEN);
        } else {
            send(context, Component.translatable("commands.foldworks.delete.not_owner"), ChatFormatting.RED);
        }
        return deleted ? 1 : 0;
    }

    private static ServerPlayer getPlayer(CommandContext<CommandSourceStack> context) {
        try {
            return context.getSource().getPlayerOrException();
        } catch (Exception e) {
            send(context, Component.translatable("commands.foldworks.requires_player"), ChatFormatting.RED);
            return null;
        }
    }

    private static SpaceData findSpace(CommandContext<CommandSourceStack> context, String token) {
        for (SpaceData space : SpaceManager.getInstance().getAllSpaces()) {
            if (space.getSpaceId().toString().startsWith(token) || space.getName().equalsIgnoreCase(token)) {
                return space;
            }
        }
        try {
            return SpaceManager.getInstance().getSpace(UUID.fromString(token));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static SpaceData.TerrainType parseTerrain(String value) {
        return switch (value.toLowerCase()) {
            case "superflat" -> SpaceData.TerrainType.SUPERFLAT;
            case "flat" -> SpaceData.TerrainType.FLAT;
            case "natural" -> SpaceData.TerrainType.NATURAL;
            default -> null;
        };
    }

    private static String shortId(SpaceData space) {
        return space.getSpaceId().toString().substring(0, 8);
    }

    private static void send(CommandContext<CommandSourceStack> context, Component message, ChatFormatting formatting) {
        context.getSource().sendSuccess(() -> Component.literal("[Foldworks] ").withStyle(formatting).append(message.copy().withStyle(formatting)), false);
    }
}
