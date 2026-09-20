package com.furimoe.nekowhitelist.mc;

import com.furimoe.nekowhitelist.api.NekoWhitelistClient;
import com.furimoe.nekowhitelist.api.WhitelistApiException;
import com.furimoe.nekowhitelist.api.WhitelistConfig;
import com.furimoe.nekowhitelist.api.WhitelistSnapshot;

import org.slf4j.Logger;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Decides whether a player the synced list rejected should be let in anyway.
 *
 * <p>The synced list is only as fresh as the last poll, so someone added in the dashboard
 * a minute ago would be turned away until the next sync. This closes that window.
 *
 * <p><strong>Never blocks.</strong> {@code PlayerList#canPlayerLogin} runs on the server
 * thread, verified on 1.21.1 by logging the thread name from a real rejected login. Waiting
 * on an HTTP call here would stall the entire server for the duration, and a stranger
 * reconnecting in a loop could hold it stalled. So the call answers from what is already
 * known and starts a lookup in the background; the answer is ready a moment later and the
 * player gets in on their next attempt, which follows immediately because the first
 * rejection is instant.
 */
public final class JoinGate {

    /**
     * How long a background answer stays usable.
     *
     * <p>Long enough for the player to reconnect and find it waiting, short enough that a
     * removal from the dashboard is not honoured minutes late.
     */
    private static final Duration ANSWER_TTL = Duration.ofSeconds(30);

    private final WhitelistConfig config;
    private final NekoWhitelistClient client;
    private final Executor executor;
    private final Logger log;

    private volatile WhitelistSnapshot snapshot = WhitelistSnapshot.EMPTY;
    private volatile boolean enforcing;

    /** Answers from background lookups, keyed by player. */
    private final Map<UUID, Answer> answers = new ConcurrentHashMap<>();

    /** Players a lookup is already running for, so a reconnect loop cannot pile up calls. */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public JoinGate(WhitelistConfig config, NekoWhitelistClient client, Executor executor,
                    Logger log) {
        this.config = config;
        this.client = client;
        this.executor = executor;
        this.log = log;
    }

    /** Called after every sync so the gate answers from the newest list. */
    public void update(WhitelistSnapshot snapshot, boolean enforcing) {
        this.snapshot = snapshot;
        this.enforcing = enforcing;
        // A fresh list supersedes anything a lookup told us.
        answers.clear();
    }

    /**
     * Whether to overturn a whitelist rejection vanilla is about to send.
     *
     * <p>Returns immediately, always. Only called for a login vanilla already refused over
     * the whitelist, so the only question is whether our list is stale.
     */
    public boolean allowsFresh(UUID uuid, String username) {
        if (!enforcing) {
            // Someone else's whitelist is in force, not ours; leave their answer alone.
            return false;
        }

        if (isInSyncedList(uuid, username)) {
            // Our synced list says yes; the server just has not applied it yet.
            return true;
        }

        if (!config.checkOnJoin()) {
            return false;
        }

        Answer answer = answers.get(uuid);
        if (answer != null && !answer.isStale()) {
            return answer.whitelisted();
        }

        lookupInBackground(uuid, username);
        return false;
    }

    private boolean isInSyncedList(UUID uuid, String username) {
        return snapshot.uuids().contains(uuid)
                || (username != null
                        && snapshot.names().contains(username.toLowerCase(Locale.ROOT)));
    }

    /**
     * Asks the API off the server thread, so a slow or unreachable API costs the server
     * nothing. The result is waiting for the player's next connection attempt.
     */
    private void lookupInBackground(UUID uuid, String username) {
        if (!inFlight.add(uuid)) {
            return;
        }

        executor.execute(() -> {
            try {
                boolean whitelisted = client.isWhitelisted(uuid, username,
                        Duration.ofMillis(config.joinCheckTimeoutMillis()));
                answers.put(uuid, new Answer(whitelisted, System.nanoTime()));

                if (whitelisted) {
                    log.info("{} is whitelisted in Neko Launcher but was not in the last sync; "
                            + "they can join on their next attempt.", username);
                } else {
                    // Says plainly that the refusal was the dashboard's answer and not a
                    // stale list, which is the first thing asked when someone cannot join.
                    log.info("{} tried to join and is not whitelisted in Neko Launcher.",
                            username);
                }
            } catch (WhitelistApiException e) {
                log.warn("Could not check {} against the API ({}); the synced list stands.",
                        username, config.redact(e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("Could not check {} against the API; the synced list stands.", username);
            } finally {
                inFlight.remove(uuid);
            }
        });
    }

    private record Answer(boolean whitelisted, long takenAtNanos) {

        boolean isStale() {
            return System.nanoTime() - takenAtNanos > ANSWER_TTL.toNanos();
        }
    }
}
