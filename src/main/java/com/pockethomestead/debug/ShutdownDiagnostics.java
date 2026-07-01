package com.pockethomestead.debug;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.config.ModConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ShutdownDiagnostics {
    private static final int SAMPLE_COUNT = 45;
    private static final long SAMPLE_INTERVAL_MS = 2_000L;
    private static final int DETAIL_LIMIT = 8;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile boolean stopRequested;

    private ShutdownDiagnostics() {
    }

    public static boolean enabled() {
        try {
            return ModConfig.SHUTDOWN_DIAGNOSTICS.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void start(MinecraftServer server) {
        if (!enabled() || server == null) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        stopRequested = false;
        Thread serverThread = serverThread(server);
        Thread thread = new Thread(() -> run(server, serverThread), "PocketHomestead Shutdown Diagnostics");
        thread.setDaemon(true);
        thread.start();
    }

    public static void stop() {
        stopRequested = true;
        RUNNING.set(false);
    }

    private static void run(MinecraftServer server, Thread serverThread) {
        try {
            PocketHomestead.LOGGER.warn("[shutdown-diagnostics] started serverThread={}", threadName(serverThread));
            for (int sample = 1; sample <= SAMPLE_COUNT && !stopRequested; sample++) {
                logSample(server, serverThread, sample);
                Thread.sleep(SAMPLE_INTERVAL_MS);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            PocketHomestead.LOGGER.error("[shutdown-diagnostics] failed", t);
        } finally {
            RUNNING.set(false);
        }
    }

    private static void logSample(MinecraftServer server, Thread serverThread, int sample) {
        PocketHomestead.LOGGER.warn(
                "[shutdown-diagnostics] sample={} saving={} running={} serverThread={} state={}",
                sample,
                safe(server::isCurrentlySaving),
                safe(server::isRunning),
                threadName(serverThread),
                serverThread == null ? "unknown" : serverThread.getState()
        );

        for (ServerLevel level : server.getAllLevels()) {
            logLevel(level);
        }

        if (sample == 1 || sample % 3 == 0) {
            logThreadStack(serverThread);
        }
    }

    private static void logLevel(ServerLevel level) {
        try {
            ChunkMap map = level.getChunkSource().chunkMap;
            Object updating = field(map, "updatingChunkMap");
            Object visible = field(map, "visibleChunkMap");
            Object pendingUnloads = field(map, "pendingUnloads");
            Object toDrop = field(map, "toDrop");
            Object unloadQueue = field(map, "unloadQueue");
            Object pendingGenerationTasks = field(map, "pendingGenerationTasks");
            Object lightEngine = field(map, "lightEngine");
            Object poiManager = field(map, "poiManager");
            Object queueSorter = field(map, "queueSorter");
            Object distanceManager = field(map, "distanceManager");

            PocketHomestead.LOGGER.warn(
                    "[shutdown-diagnostics] level={} hasWork={} lightWork={} poiWork={} queueSorterWork={} tickets={} " +
                            "updating={} visible={} pendingUnloads={} toDrop={} unloadQueue={} pendingGeneration={}",
                    level.dimension().location(),
                    map.hasWork(),
                    boolMethod(lightEngine, "hasLightWork"),
                    boolMethod(poiManager, "hasWork"),
                    boolMethod(queueSorter, "hasWork"),
                    boolMethod(distanceManager, "hasTickets"),
                    size(updating),
                    size(visible),
                    size(pendingUnloads),
                    size(toDrop),
                    size(unloadQueue),
                    size(pendingGenerationTasks)
            );

            logDetails("pendingUnloads", pendingUnloads);
            logDetails("toDrop", toDrop);
            logDetails("unloadQueue", unloadQueue);
            logDetails("pendingGeneration", pendingGenerationTasks);
        } catch (Throwable t) {
            PocketHomestead.LOGGER.warn("[shutdown-diagnostics] failed to inspect level {}", level.dimension().location(), t);
        }
    }

    private static void logDetails(String name, Object value) {
        int count = size(value);
        if (count <= 0) return;
        PocketHomestead.LOGGER.warn("[shutdown-diagnostics] {} sample={}", name, sampleEntries(value));
    }

    private static String sampleEntries(Object value) {
        StringBuilder out = new StringBuilder("[");
        int emitted = 0;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (emitted++ > 0) out.append(", ");
                out.append(formatKey(entry.getKey())).append("=>").append(formatValue(entry.getValue()));
                if (emitted >= DETAIL_LIMIT) break;
            }
        } else if (value instanceof Iterable<?> iterable) {
            Iterator<?> iterator = iterable.iterator();
            while (iterator.hasNext() && emitted < DETAIL_LIMIT) {
                if (emitted++ > 0) out.append(", ");
                out.append(formatValue(iterator.next()));
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object entry : collection) {
                if (emitted++ > 0) out.append(", ");
                out.append(formatValue(entry));
                if (emitted >= DETAIL_LIMIT) break;
            }
        } else {
            out.append(value);
        }
        if (size(value) > DETAIL_LIMIT) out.append(", ...");
        return out.append(']').toString();
    }

    private static String formatKey(Object key) {
        if (key instanceof Number number) return new ChunkPos(number.longValue()).toString();
        return String.valueOf(key);
    }

    private static String formatValue(Object value) {
        if (value instanceof ChunkHolder holder) {
            return holder.getPos()
                    + "{ticket=" + holder.getTicketLevel()
                    + ",queue=" + holder.getQueueLevel()
                    + ",gen=" + holder.getGenerationRefCount()
                    + ",ready=" + holder.isReadyForSaving()
                    + ",status=" + holder.getLatestStatus()
                    + "}";
        }
        if (value instanceof Number number) return new ChunkPos(number.longValue()).toString();
        return String.valueOf(value);
    }

    private static void logThreadStack(Thread thread) {
        if (thread == null) return;
        StackTraceElement[] stack = thread.getStackTrace();
        StringBuilder out = new StringBuilder();
        int limit = Math.min(stack.length, 24);
        for (int i = 0; i < limit; i++) {
            out.append("\n  at ").append(stack[i]);
        }
        PocketHomestead.LOGGER.warn("[shutdown-diagnostics] server thread stack state={}{}", thread.getState(), out);
    }

    private static Thread serverThread(MinecraftServer server) {
        Object thread = field(server, "serverThread");
        if (thread instanceof Thread serverThread) return serverThread;
        for (Thread candidate : Thread.getAllStackTraces().keySet()) {
            if ("Server thread".equals(candidate.getName())) return candidate;
        }
        return null;
    }

    private static Object field(Object target, String name) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                return "error:" + t.getClass().getSimpleName();
            }
        }
        return null;
    }

    private static int size(Object value) {
        if (value == null) return -1;
        if (value instanceof Map<?, ?> map) return map.size();
        if (value instanceof Collection<?> collection) return collection.size();
        Object result = call(value, "size");
        return result instanceof Number number ? number.intValue() : -1;
    }

    private static boolean boolMethod(Object target, String name) {
        Object result = call(target, name);
        return result instanceof Boolean value && value;
    }

    private static Object call(Object target, String name) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static String threadName(Thread thread) {
        return thread == null ? "unknown" : thread.getName();
    }

    private static boolean safe(BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
