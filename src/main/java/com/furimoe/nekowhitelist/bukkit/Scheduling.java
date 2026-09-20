package com.furimoe.nekowhitelist.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * Runs work off the main thread, on Folia as well as Paper, Spigot and Bukkit.
 *
 * <p>Folia removed {@code BukkitScheduler}: calling it throws
 * {@code UnsupportedOperationException}. Its replacement, the global region
 * scheduler, does not exist on the older servers this plugin also supports, so the
 * choice is made once at startup by reflection rather than by compiling against both.
 *
 * <p>Only asynchronous work is scheduled here. Everything this plugin does to the
 * whitelist is thread-safe on both server types, so there is no need for the region
 * schedulers that Folia requires for world state.
 */
final class Scheduling {

    private final Plugin plugin;

    /** Folia's {@code Server#getAsyncScheduler} result, or null on a non-Folia server. */
    private final Object asyncScheduler;
    private final Method runAtFixedRate;
    private final Method runNow;

    private Object foliaTask;
    private int bukkitTaskId = -1;

    Scheduling(Plugin plugin) {
        this.plugin = plugin;

        Object scheduler = null;
        Method fixedRate = null;
        Method now = null;
        try {
            // Present on Folia (and on Paper builds that ship the API), absent elsewhere.
            Method getter = Bukkit.getServer().getClass().getMethod("getAsyncScheduler");
            scheduler = getter.invoke(Bukkit.getServer());
            Class<?> type = scheduler.getClass();
            fixedRate = findMethod(type, "runAtFixedRate");
            now = findMethod(type, "runNow");
            if (fixedRate == null || now == null) {
                scheduler = null;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Not Folia; the Bukkit scheduler below is the right tool.
            scheduler = null;
        }

        this.asyncScheduler = scheduler;
        this.runAtFixedRate = fixedRate;
        this.runNow = now;
    }

    /** True when running on a server whose Bukkit scheduler would refuse us. */
    boolean isFolia() {
        return asyncScheduler != null;
    }

    /**
     * Repeats {@code task} off the main thread, starting after {@code delaySeconds}.
     */
    void repeatAsync(Runnable task, long delaySeconds, long periodSeconds) {
        if (asyncScheduler != null) {
            try {
                foliaTask = runAtFixedRate.invoke(asyncScheduler, plugin,
                        (java.util.function.Consumer<Object>) ignored -> task.run(),
                        delaySeconds, periodSeconds, TimeUnit.SECONDS);
                return;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not schedule on Folia", e);
            }
        }

        // Bukkit counts in ticks, at twenty per second.
        bukkitTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, task, delaySeconds * 20L, periodSeconds * 20L).getTaskId();
    }

    /** Runs {@code task} once, off the main thread, as soon as possible. */
    void runAsync(Runnable task) {
        if (asyncScheduler != null) {
            try {
                runNow.invoke(asyncScheduler, plugin,
                        (java.util.function.Consumer<Object>) ignored -> task.run());
                return;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not schedule on Folia", e);
            }
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    void cancel() {
        if (foliaTask != null) {
            try {
                foliaTask.getClass().getMethod("cancel").invoke(foliaTask);
            } catch (ReflectiveOperationException e) {
                // Shutting down anyway; a task that outlives us is harmless.
            }
            foliaTask = null;
        }
        if (bukkitTaskId != -1) {
            Bukkit.getScheduler().cancelTask(bukkitTaskId);
            bukkitTaskId = -1;
        }
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }
}
