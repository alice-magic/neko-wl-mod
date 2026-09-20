package com.furimoe.nekowhitelist.bukkit;

import com.furimoe.nekowhitelist.api.NekoWhitelistClient;
import com.furimoe.nekowhitelist.api.SnapshotCache;
import com.furimoe.nekowhitelist.api.WhitelistApiException;
import com.furimoe.nekowhitelist.api.WhitelistConfig;
import com.furimoe.nekowhitelist.api.WhitelistSnapshot;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the server's whitelist in step with a Neko Launcher instance.
 *
 * <p>Unlike the mod, this does not write Bukkit's own whitelist. Bukkit exposes it only
 * as {@code OfflinePlayer} entries, and writing it would fight with {@code /whitelist}
 * and with other plugins. Enforcement happens in {@link LoginListener} instead, which is
 * the same decision point Bukkit uses for its own whitelist.
 */
public final class NekoWhitelistPlugin extends JavaPlugin {

    private WhitelistConfig config;
    private NekoWhitelistClient client;
    private SnapshotCache cache;
    private Scheduling scheduling;

    private volatile WhitelistSnapshot snapshot = WhitelistSnapshot.EMPTY;
    private volatile boolean enforcing;

    /** Answers from per-join lookups, so a rejected player can retry straight away. */
    private final ConcurrentHashMap<UUID, Long> recentlyAllowed = new ConcurrentHashMap<>();

    private final AtomicBoolean stopped = new AtomicBoolean();

    @Override
    public void onEnable() {
        try {
            config = WhitelistConfig.load(getDataFolder().toPath());
        } catch (IOException e) {
            getLogger().severe("Could not read " + WhitelistConfig.FILE_NAME + ": " + e.getMessage()
                    + ". Whitelist sync is disabled.");
            return;
        }

        if (!config.isConfigured()) {
            getLogger().warning("Whitelist sync is idle: set apiKey and instance in plugins/"
                    + getName() + "/" + WhitelistConfig.FILE_NAME
                    + ", then restart. Find the instance name with GET /api/v1/server/instances.");
            return;
        }

        client = new NekoWhitelistClient(config);
        cache = new SnapshotCache(getDataFolder().toPath());
        scheduling = new Scheduling(this);

        // The cache goes in before the first request, so an API outage during a restart
        // does not leave the server with no list at all.
        Optional<WhitelistSnapshot> cached = cache.read();
        if (cached.isPresent()) {
            applySnapshot(cached.get());
            getLogger().info("Loaded " + snapshot.size()
                    + " cached whitelist entries while the first sync runs.");
        } else {
            getLogger().info("No whitelist cache yet; not enforcing until the first sync.");
        }

        getServer().getPluginManager().registerEvents(new LoginListener(this), this);

        getLogger().info("Syncing '" + config.instance() + "' every " + config.syncSeconds()
                + "s" + (scheduling.isFolia() ? " (Folia scheduler)" : "") + ".");
        scheduling.repeatAsync(this::sync, 1, config.syncSeconds());
    }

    @Override
    public void onDisable() {
        stopped.set(true);
        if (scheduling != null) {
            scheduling.cancel();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (client == null) {
            sender.sendMessage("Neko Launcher whitelist is idle: set apiKey and instance in "
                    + WhitelistConfig.FILE_NAME + ", then restart.");
            return true;
        }

        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "reload" -> {
                sender.sendMessage("Syncing the whitelist from Neko Launcher...");
                scheduling.runAsync(this::sync);
            }
            case "status" -> sender.sendMessage("Neko Launcher whitelist: " + snapshot.size()
                    + " entries from '" + config.instance() + "', enforcement "
                    + (enforcing ? "on" : "off") + ", syncing every " + config.syncSeconds() + "s.");
            default -> sender.sendMessage("Usage: /" + label + " <reload|status>");
        }
        return true;
    }

    /** One sync attempt. Runs off the main thread; never touches Bukkit state. */
    private void sync() {
        if (stopped.get()) {
            return;
        }

        try {
            WhitelistSnapshot fetched = client.fetch();
            applySnapshot(fetched);

            try {
                cache.write(fetched);
            } catch (IOException e) {
                getLogger().warning("Could not write the whitelist cache: "
                        + config.redact(String.valueOf(e.getMessage())));
            }

            getLogger().info("Whitelist synced: " + fetched.size() + " entries, enforcement "
                    + (enforcing ? "on" : "off") + ".");
        } catch (WhitelistApiException e) {
            if (e.terminal()) {
                // A rotated key or a wrong instance name will not fix itself.
                getLogger().severe("Whitelist sync stopped: " + config.redact(e.getMessage()));
                getLogger().severe("Keeping the last known whitelist (" + snapshot.size()
                        + " entries). Fix the config and restart.");
                stopped.set(true);
                scheduling.cancel();
                return;
            }
            getLogger().warning("Whitelist sync failed: " + config.redact(e.getMessage())
                    + ". Keeping the last known list (" + snapshot.size() + " entries).");
        } catch (RuntimeException e) {
            getLogger().warning("Unexpected error during whitelist sync: "
                    + config.redact(String.valueOf(e.getMessage())));
        }
    }

    private void applySnapshot(WhitelistSnapshot fetched) {
        snapshot = fetched;
        enforcing = config.enforceOverride() == null ? fetched.enforce() : config.enforceOverride();
        // A fresh list supersedes any per-join answer we cached.
        recentlyAllowed.clear();
    }

    /** True when the plugin should reject players who are not on the list. */
    boolean isEnforcing() {
        return enforcing && !snapshot.isEmpty();
    }

    boolean isWhitelisted(UUID uuid, String name) {
        if (snapshot.uuids().contains(uuid)) {
            return true;
        }
        if (name != null && snapshot.names().contains(name.toLowerCase(Locale.ROOT))) {
            return true;
        }

        Long allowedAt = recentlyAllowed.get(uuid);
        return allowedAt != null
                && System.nanoTime() - allowedAt < java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    }

    /**
     * Asks the API about a player the synced list does not know, in the background.
     *
     * <p>The login event runs on a server thread with the player waiting, so this never
     * blocks it. The answer is ready for their next attempt, which is how a player added
     * seconds ago gets in without waiting for the next sync.
     */
    void lookupInBackground(UUID uuid, String name) {
        if (!config.checkOnJoin() || stopped.get()) {
            return;
        }

        scheduling.runAsync(() -> {
            try {
                boolean allowed = client.isWhitelisted(uuid, name,
                        java.time.Duration.ofMillis(config.joinCheckTimeoutMillis()));
                if (allowed) {
                    recentlyAllowed.put(uuid, System.nanoTime());
                    getLogger().info(name + " is whitelisted in Neko Launcher but was not in the "
                            + "last sync; they can join on their next attempt.");
                } else {
                    getLogger().info(name + " tried to join and is not whitelisted in Neko Launcher.");
                }
            } catch (WhitelistApiException e) {
                getLogger().warning("Could not check " + name + " against the API ("
                        + config.redact(e.getMessage()) + "); the synced list stands.");
            }
        });
    }
}
