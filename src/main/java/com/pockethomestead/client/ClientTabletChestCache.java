package com.pockethomestead.client;

import com.pockethomestead.network.ChestSyncPacket;
import com.pockethomestead.network.TabletChestSyncPacket;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ClientTabletChestCache {
    private static boolean bound;
    private static boolean available;
    private static String message = "";
    private static String chestId = "";
    private static String dimensionKey = "";
    private static BlockPos pos = BlockPos.ZERO;
    private static int usedCapacity;
    private static int maxCapacity;
    private static net.minecraft.world.item.ItemStack carriedStack = net.minecraft.world.item.ItemStack.EMPTY;
    private static List<ChestSyncPacket.ItemEntry> items = List.of();
    private static long version;

    private ClientTabletChestCache() {
    }

    public static void apply(TabletChestSyncPacket packet) {
        bound = packet.bound();
        available = packet.available();
        message = packet.message();
        chestId = packet.chestId();
        dimensionKey = packet.dimensionKey();
        pos = packet.pos();
        usedCapacity = packet.usedCapacity();
        maxCapacity = packet.maxCapacity();
        carriedStack = packet.carriedStack().copy();
        items = Collections.unmodifiableList(new ArrayList<>(packet.items()));
        version++;
    }

    public static boolean bound() { return bound; }
    public static boolean available() { return available; }
    public static String message() { return message; }
    public static String chestId() { return chestId; }
    public static String dimensionKey() { return dimensionKey; }
    public static BlockPos pos() { return pos; }
    public static int usedCapacity() { return usedCapacity; }
    public static int maxCapacity() { return maxCapacity; }
    public static net.minecraft.world.item.ItemStack carriedStack() { return carriedStack; }
    public static List<ChestSyncPacket.ItemEntry> items() { return items; }
    public static long version() { return version; }
}
