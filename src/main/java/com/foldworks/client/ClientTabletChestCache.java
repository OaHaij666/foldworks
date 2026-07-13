package com.foldworks.client;

import com.foldworks.network.ChestSyncPacket;
import com.foldworks.network.TabletChestSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

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
    private static boolean suiteUpgradeInstalled;
    private static ItemStack carriedStack = ItemStack.EMPTY;
    private static List<ItemStack> furnaceItems = List.of();
    private static int furnaceLitTime;
    private static int furnaceLitDuration;
    private static int furnaceCookingProgress;
    private static int furnaceCookingTotalTime;
    private static int furnaceMode;
    private static List<ItemStack> workbenchInputs = List.of();
    private static ItemStack workbenchResult = ItemStack.EMPTY;
    private static List<ItemStack> smithingInputs = List.of();
    private static ItemStack smithingResult = ItemStack.EMPTY;
    private static ItemStack stonecutterInput = ItemStack.EMPTY;
    private static List<ItemStack> stonecutterResults = List.of();
    private static int stonecutterSelectedIndex = -1;
    private static ItemStack stonecutterResult = ItemStack.EMPTY;
    private static ItemStack suiteOrderTarget = ItemStack.EMPTY;
    private static int suiteOrderQuantity = 1;
    private static int suiteMaxOrders;
    private static List<ItemStack> suiteTools = List.of();
    private static List<ItemStack> suiteResources = List.of();
    private static List<TabletChestSyncPacket.OrderEntry> suiteOrders = List.of();
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
        suiteUpgradeInstalled = packet.suiteUpgradeInstalled();
        carriedStack = packet.carriedStack().copy();
        List<ItemStack> furnace = new ArrayList<>();
        for (ItemStack stack : packet.furnaceItems()) furnace.add(stack.copy());
        while (furnace.size() < 3) furnace.add(ItemStack.EMPTY);
        if (furnace.size() > 3) furnace = new ArrayList<>(furnace.subList(0, 3));
        furnaceItems = Collections.unmodifiableList(furnace);
        furnaceLitTime = packet.furnaceLitTime();
        furnaceLitDuration = packet.furnaceLitDuration();
        furnaceCookingProgress = packet.furnaceCookingProgress();
        furnaceCookingTotalTime = packet.furnaceCookingTotalTime();
        furnaceMode = packet.furnaceMode();
        List<ItemStack> inputs = new ArrayList<>();
        for (ItemStack stack : packet.workbenchInputs()) inputs.add(stack.copy());
        while (inputs.size() < 9) inputs.add(ItemStack.EMPTY);
        if (inputs.size() > 9) inputs = new ArrayList<>(inputs.subList(0, 9));
        workbenchInputs = Collections.unmodifiableList(inputs);
        workbenchResult = packet.workbenchResult().copy();
        List<ItemStack> smithing = new ArrayList<>();
        for (ItemStack stack : packet.smithingInputs()) smithing.add(stack.copy());
        while (smithing.size() < 3) smithing.add(ItemStack.EMPTY);
        if (smithing.size() > 3) smithing = new ArrayList<>(smithing.subList(0, 3));
        smithingInputs = Collections.unmodifiableList(smithing);
        smithingResult = packet.smithingResult().copy();
        stonecutterInput = packet.stonecutterInput().copy();
        List<ItemStack> cutting = new ArrayList<>();
        for (ItemStack stack : packet.stonecutterResults()) cutting.add(stack.copy());
        stonecutterResults = Collections.unmodifiableList(cutting);
        stonecutterSelectedIndex = packet.stonecutterSelectedIndex();
        stonecutterResult = packet.stonecutterResult().copy();
        suiteOrderTarget = packet.suiteOrderTarget().copy();
        suiteOrderQuantity = Math.max(1, packet.suiteOrderQuantity());
        suiteMaxOrders = packet.suiteMaxOrders();
        List<ItemStack> tools = new ArrayList<>();
        for (ItemStack stack : packet.suiteTools()) tools.add(stack.copy());
        suiteTools = Collections.unmodifiableList(tools);
        List<ItemStack> resources = new ArrayList<>();
        for (ItemStack stack : packet.suiteResources()) resources.add(stack.copy());
        suiteResources = Collections.unmodifiableList(resources);
        suiteOrders = Collections.unmodifiableList(new ArrayList<>(packet.suiteOrders()));
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
    public static boolean suiteUpgradeInstalled() { return suiteUpgradeInstalled; }
    public static ItemStack carriedStack() { return carriedStack; }
    public static List<ItemStack> furnaceItems() { return furnaceItems; }
    public static int furnaceLitTime() { return furnaceLitTime; }
    public static int furnaceLitDuration() { return furnaceLitDuration; }
    public static int furnaceCookingProgress() { return furnaceCookingProgress; }
    public static int furnaceCookingTotalTime() { return furnaceCookingTotalTime; }
    public static int furnaceMode() { return furnaceMode; }
    public static List<ItemStack> workbenchInputs() { return workbenchInputs; }
    public static ItemStack workbenchResult() { return workbenchResult; }
    public static List<ItemStack> smithingInputs() { return smithingInputs; }
    public static ItemStack smithingResult() { return smithingResult; }
    public static ItemStack stonecutterInput() { return stonecutterInput; }
    public static List<ItemStack> stonecutterResults() { return stonecutterResults; }
    public static int stonecutterSelectedIndex() { return stonecutterSelectedIndex; }
    public static ItemStack stonecutterResult() { return stonecutterResult; }
    public static ItemStack suiteOrderTarget() { return suiteOrderTarget; }
    public static int suiteOrderQuantity() { return suiteOrderQuantity; }
    public static int suiteMaxOrders() { return suiteMaxOrders; }
    public static List<ItemStack> suiteTools() { return suiteTools; }
    public static List<ItemStack> suiteResources() { return suiteResources; }
    public static List<TabletChestSyncPacket.OrderEntry> suiteOrders() { return suiteOrders; }
    public static List<ChestSyncPacket.ItemEntry> items() { return items; }
    public static long version() { return version; }
}
