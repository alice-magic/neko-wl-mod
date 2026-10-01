package com.furimoe.nekowhitelist.mc;

import com.furimoe.nekowhitelist.api.WhitelistSnapshot;

import java.util.Locale;
import java.util.UUID;

/** Answers login checks from the most recently fetched whitelist snapshot. */
public final class JoinGate {

    private volatile WhitelistSnapshot snapshot;
    private volatile boolean enforcing;

    /** Called after each successful fetch; null means the first fetch has not completed. */
    public void update(WhitelistSnapshot snapshot, boolean enforcing) {
        this.snapshot = snapshot;
        this.enforcing = enforcing;
    }

    /** Denies unknown players until the first complete snapshot is available. */
    public boolean allows(UUID uuid, String username) {
        WhitelistSnapshot current = snapshot;
        if (current == null) {
            return false;
        }
        return !enforcing || current.uuids().contains(uuid)
                || (username != null && current.names().contains(username.toLowerCase(Locale.ROOT)));
    }
}
