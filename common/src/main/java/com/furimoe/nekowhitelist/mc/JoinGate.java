package com.furimoe.nekowhitelist.mc;

import com.furimoe.nekowhitelist.api.NekoWhitelistClient;
import com.furimoe.nekowhitelist.api.WhitelistApiException;
import com.furimoe.nekowhitelist.api.WhitelistConfig;
import com.furimoe.nekowhitelist.api.WhitelistSnapshot;

import org.slf4j.Logger;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * Decides whether a connecting player may in, for the loader-specific login hooks.
 *
 * <p>The synced whitelist is only as fresh as the last poll, so someone added in the
 * dashboard a minute ago would otherwise be turned away until the next sync. This closes
 * that window: a cached "no" is re-asked of the API, and only a confirmed no is final.
 *
 * <p>A cached "yes" is never re-checked. It costs a round trip to confirm something we
 * already believe, and the failure mode of being briefly generous is much kinder than
 * making every login wait on the network.
 */
public final class JoinGate {

    private final WhitelistConfig config;
    private final NekoWhitelistClient client;
    private final Logger log;

    private volatile WhitelistSnapshot snapshot = WhitelistSnapshot.EMPTY;
    private volatile boolean enforcing;

    public JoinGate(WhitelistConfig config, NekoWhitelistClient client, Logger log) {
        this.config = config;
        this.client = client;
        this.log = log;
    }

    /** Called after every sync so the gate answers from the newest list. */
    public void update(WhitelistSnapshot snapshot, boolean enforcing) {
        this.snapshot = snapshot;
        this.enforcing = enforcing;
    }

    /**
     * Whether a player the synced list rejected should be let in anyway.
     *
     * <p>Only called for a login vanilla is about to refuse over the whitelist, so the
     * synced list has already said no and the only question left is whether it is stale.
     *
     * <p>Runs on a login thread with a player waiting, so the API call is bounded by
     * {@code joinCheckTimeoutMillis} and any failure leaves the refusal standing. An
     * outage must never turn into a login that hangs.
     */
    public boolean allowsFresh(UUID uuid, String username) {
        if (!enforcing) {
            // Someone else's whitelist is in force, not ours; leave their answer alone.
            return false;
        }

        if (isCached(uuid, username)) {
            // The list we synced says yes, so a rejection means the server has not
            // applied our latest sync yet. Nothing to ask the API about.
            return true;
        }

        if (!config.checkOnJoin()) {
            return false;
        }

        try {
            boolean allowed = client.isWhitelisted(uuid, username,
                    Duration.ofMillis(config.joinCheckTimeoutMillis()));
            if (allowed) {
                log.info("{} was not in the synced list, but the API says they are whitelisted; "
                        + "letting them in.", username);
            }
            return allowed;
        } catch (WhitelistApiException e) {
            log.warn("Could not check {} against the API ({}); the synced list stands.",
                    username, config.redact(e.getMessage()));
            return false;
        }
    }

    private boolean isCached(UUID uuid, String username) {
        return snapshot.uuids().contains(uuid)
                || (username != null && snapshot.names().contains(username.toLowerCase(Locale.ROOT)));
    }
}
