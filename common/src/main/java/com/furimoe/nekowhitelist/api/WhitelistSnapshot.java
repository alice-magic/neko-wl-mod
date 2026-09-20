package com.furimoe.nekowhitelist.api;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * An immutable view of an instance's whitelist as the API last reported it.
 *
 * <p>Entries arrive keyed either by UUID or by username (never both), so both sets are
 * kept and a player matches if either hits. Usernames are stored lower-cased.
 */
public record WhitelistSnapshot(Set<UUID> uuids, Set<String> names, boolean enforce) {

    public static final WhitelistSnapshot EMPTY =
            new WhitelistSnapshot(Set.of(), Set.of(), false);

    public WhitelistSnapshot {
        uuids = Collections.unmodifiableSet(Set.copyOf(uuids));
        names = Collections.unmodifiableSet(Set.copyOf(names));
    }

    public int size() {
        return uuids.size() + names.size();
    }

    public boolean isEmpty() {
        return uuids.isEmpty() && names.isEmpty();
    }
}
