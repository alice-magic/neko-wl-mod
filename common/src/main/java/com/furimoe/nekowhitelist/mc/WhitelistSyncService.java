package com.furimoe.nekowhitelist.mc;

import com.furimoe.nekowhitelist.api.NekoWhitelistClient;
import com.furimoe.nekowhitelist.api.SnapshotCache;
import com.furimoe.nekowhitelist.api.WhitelistApiException;
import com.furimoe.nekowhitelist.api.WhitelistConfig;
import com.furimoe.nekowhitelist.api.WhitelistSnapshot;

import net.minecraft.server.MinecraftServer;

import org.slf4j.Logger;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls the whitelist on a timer and hands each result to the {@link WhitelistApplier}.
 *
 * <p>HTTP happens on a daemon executor; the apply hops back to the server thread via
 * {@link MinecraftServer#execute}. A failed sync keeps the previous list rather than
 * clearing it, because a network blip must never empty a whitelist.
 */
public final class WhitelistSyncService {

    /** Backoff ceiling: keep retrying a flaky API, but no faster than this. */
    private static final int MAX_BACKOFF_MULTIPLIER = 8;

    private final MinecraftServer server;
    private final WhitelistConfig config;
    private final SnapshotCache cache;
    private final NekoWhitelistClient client;
    private final WhitelistApplier applier;
    private final Logger log;

    private final AtomicBoolean stopped = new AtomicBoolean();
    private ScheduledExecutorService executor;

    /** Kept so a failed sync can re-apply the last good list instead of an empty one. */
    private volatile WhitelistSnapshot lastGood;
    private int consecutiveFailures;

    public WhitelistSyncService(MinecraftServer server, WhitelistConfig config,
                                SnapshotCache cache, Logger log) {
        this.server = server;
        this.config = config;
        this.cache = cache;
        this.log = log;
        this.client = new NekoWhitelistClient(config);
        this.applier = new WhitelistApplier(server, log);
    }

    /**
     * Applies the cached list immediately, then starts the poll loop.
     *
     * <p>The cache comes first on purpose: if the API is down during a restart, the server
     * still enforces the last known whitelist instead of locking everyone out.
     */
    public void start() {
        Optional<WhitelistSnapshot> cached = cache.read();
        if (cached.isPresent()) {
            lastGood = cached.get();
            log.info("Loaded {} cached whitelist entries while the first sync runs.",
                    lastGood.size());
            applyOnServerThread(lastGood);
        } else {
            log.info("No whitelist cache yet; waiting for the first sync before enforcing.");
        }

        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "neko-whitelist-sync");
            thread.setDaemon(true);
            return thread;
        });

        executor.schedule(this::syncOnce, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        stopped.set(true);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** One sync attempt, which reschedules itself unless the failure is terminal. */
    private void syncOnce() {
        if (stopped.get()) {
            return;
        }

        try {
            WhitelistSnapshot snapshot = client.fetch();
            consecutiveFailures = 0;
            lastGood = snapshot;

            try {
                cache.write(snapshot);
            } catch (IOException e) {
                // A cache we cannot write only costs us the next cold start.
                log.warn("Could not write the whitelist cache: {}",
                        config.redact(String.valueOf(e.getMessage())));
            }

            applyOnServerThread(snapshot);
            reschedule(config.syncSeconds());
        } catch (WhitelistApiException e) {
            handleFailure(e);
        } catch (RuntimeException e) {
            // Never let an unexpected error kill the loop silently.
            log.error("Unexpected error during whitelist sync: {}",
                    config.redact(String.valueOf(e.getMessage())));
            handleRetryableFailure();
        }
    }

    private void handleFailure(WhitelistApiException e) {
        if (e.terminal()) {
            // Retrying a rotated key or a wrong instance name only hammers the API.
            log.error("Whitelist sync stopped: {}", config.redact(e.getMessage()));
            if (lastGood != null) {
                log.error("Keeping the last known whitelist ({} entries). "
                        + "Fix the config and restart the server to resume syncing.",
                        lastGood.size());
            }
            stop();
            return;
        }

        log.warn("Whitelist sync failed: {}", config.redact(e.getMessage()));
        handleRetryableFailure();
    }

    private void handleRetryableFailure() {
        if (lastGood != null) {
            log.warn("Keeping the last known whitelist ({} entries) until the next sync.",
                    lastGood.size());
        } else {
            // Fail open: with no list at all, enforcing would lock out everyone
            // including admins, which is worse than briefly letting strangers in.
            log.warn("No whitelist available yet; leaving enforcement untouched.");
        }

        consecutiveFailures++;
        int multiplier = Math.min(MAX_BACKOFF_MULTIPLIER, 1 << Math.min(consecutiveFailures, 3));
        reschedule((long) config.syncSeconds() * multiplier);
    }

    private void reschedule(long seconds) {
        if (stopped.get() || executor == null || executor.isShutdown()) {
            return;
        }
        executor.schedule(this::syncOnce, seconds, TimeUnit.SECONDS);
    }

    /** PlayerList and UserWhiteList are server-thread-only, so never apply inline. */
    private void applyOnServerThread(WhitelistSnapshot snapshot) {
        boolean enforce = config.enforceOverride() == null
                ? snapshot.enforce()
                : config.enforceOverride();

        server.execute(() -> {
            try {
                applier.apply(snapshot, enforce);
            } catch (RuntimeException e) {
                log.error("Could not apply the whitelist: {}",
                        config.redact(String.valueOf(e.getMessage())));
            }
        });
    }
}
