package com.furimoe.nekowhitelist.mc;

import com.furimoe.nekowhitelist.api.NekoWhitelistClient;
import com.furimoe.nekowhitelist.api.WhitelistSnapshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pushes a snapshot into the server's own whitelist, so vanilla does the enforcing in
 * {@code PlayerList#canPlayerLogin} and no login-path HTTP call is ever needed.
 *
 * <p>One of two version-divergent classes, alongside {@code PlayerListMixin}. Here the
 * whitelist is keyed by {@link NameAndId} and the enforcement toggle lives on
 * {@link DedicatedServer}; on 1.20.1 and 1.21.1 they were {@code GameProfile} and
 * {@code PlayerList}.
 *
 * <p>Must run on the server thread: {@code UserWhiteList} is HashMap-backed and is not
 * safe to touch from the sync executor.
 */
public final class WhitelistApplier {

    private final MinecraftServer server;
    private final Logger log;

    /**
     * What we last wrote, so the next sync can diff against it.
     *
     * <p>{@code StoredUserEntry#getUser} is not public, so the live list cannot be read
     * back as profiles. Tracking our own writes is enough for a diff and avoids reflection
     * or an access widener, which would be one more thing to maintain per version.
     */
    private final Map<UUID, NameAndId> applied = new HashMap<>();

    /** whitelist.json is taken over once, on the first sync, not every five minutes. */
    private boolean reconciledExisting;

    /**
     * UUIDs the previous run left in whitelist.json, from our own cache.
     *
     * <p>Set before the first apply so the takeover can tell our entries from ones the
     * owner added by hand.
     */
    private Set<UUID> knownFromCache = Set.of();

    public WhitelistApplier(MinecraftServer server, Logger log) {
        this.server = server;
        this.log = log;
    }

    /**
     * Applies the snapshot as a diff against what we last wrote.
     *
     * <p>Every {@code add}/{@code remove} on a {@code StoredUserList} writes
     * whitelist.json, so a diff keeps a no-change sync at zero disk writes. It also means
     * the list is never briefly empty, which a clear-and-refill could not promise.
     *
     * @param enforce whether the whitelist should be switched on at all
     */
    /** Tells the first apply which entries a previous run of this mod left behind. */
    public void rememberCached(WhitelistSnapshot cached) {
        this.knownFromCache = Set.copyOf(cached.uuids());
    }

    public void apply(WhitelistSnapshot snapshot, boolean enforce) {
        UserWhiteList whitelist = server.getPlayerList().getWhiteList();
        Set<UUID> wanted = resolve(snapshot);

        reconcileExistingEntries(whitelist, knownFromCache);

        int removed = 0;
        for (UUID uuid : List.copyOf(applied.keySet())) {
            if (!wanted.contains(uuid)) {
                whitelist.remove(applied.remove(uuid));
                removed++;
            }
        }

        int added = 0;
        for (UUID uuid : wanted) {
            if (!applied.containsKey(uuid)) {
                NameAndId profile = entryFor(uuid);
                whitelist.add(new UserWhiteListEntry(profile));
                applied.put(uuid, profile);
                added++;
            }
        }

        if (added > 0 || removed > 0) {
            log.info("Whitelist synced: {} added, {} removed, {} total",
                    added, removed, applied.size());
        }

        setUsingWhitelist(enforce);
    }

    /**
     * Takes over whitelist.json once, on the first sync.
     *
     * <p>Entries this mod wrote on a previous run are recognised by name and kept, so a
     * restart neither rewrites the file nor accuses the owner of adding them. Everything
     * else came from {@code /whitelist add} or an editor; the dashboard is the source of
     * truth, so it is named in the log and dropped.
     *
     * <p>{@code known} is the previous run's snapshot, read back from our own cache. It is
     * the only thing that can tell our entries from the owner's, since whitelist.json
     * records no provenance. Our entries carry the UUID as their name, which is what makes
     * them recognisable here.
     *
     * <p>Entries are dropped by handing back the objects from
     * {@link UserWhiteList#getEntries()}. Removing by key would mean reconstructing a UUID
     * from a name, and a reconstructed key that misses turns the removal into a silent
     * no-op while the log claims success. {@code clear()} would be simpler but only exists
     * on 26.x.
     */
    private void reconcileExistingEntries(UserWhiteList whitelist, Set<UUID> known) {
        if (reconciledExisting) {
            return;
        }
        reconciledExisting = true;

        List<UserWhiteListEntry> existing = List.copyOf(whitelist.getEntries());
        if (existing.isEmpty()) {
            return;
        }

        Set<String> ours = new HashSet<>();
        for (UUID uuid : known) {
            ours.add(uuid.toString());
        }

        List<String> foreign = new ArrayList<>();
        for (String name : whitelist.getUserList()) {
            UUID uuid = ours.contains(name) ? NekoWhitelistClient.parseUndashed(name) : null;
            if (uuid != null) {
                applied.put(uuid, new NameAndId(uuid, name));
            } else {
                foreign.add(name);
            }
        }

        if (foreign.isEmpty()) {
            return;
        }

        log.warn("This mod owns whitelist.json. Dropping {} entry/entries that did not come "
                        + "from Neko Launcher: {}. Add them in the dashboard to keep them.",
                foreign.size(), String.join(", ", foreign));

        // An entry exposes neither its UUID nor its name publicly, so which object is
        // which cannot be read off. Drop them all and let the add loop below put ours
        // straight back: one extra rewrite, only on the run that finds foreign entries.
        existing.forEach(whitelist::remove);
        applied.clear();
    }

    /**
     * Turns the server's whitelist on or off.
     *
     * <p>26.x keeps this on {@code DedicatedServer} rather than {@code PlayerList}, so it
     * needs the {@code instanceof} guard below.
     */
    private void setUsingWhitelist(boolean enforce) {
        // 26.x moved the setter to DedicatedServer; an integrated server has no whitelist
        // to speak of, and this mod is server-side anyway.
        if (!(server instanceof DedicatedServer dedicated)) {
            return;
        }

        if (dedicated.isUsingWhitelist() != enforce) {
            dedicated.setUsingWhitelist(enforce);
            log.info(enforce
                    ? "Whitelist enforcement is now ON (per the Neko Launcher instance)."
                    : "Whitelist enforcement is now OFF (per the Neko Launcher instance).");
        }
    }

    /**
     * Turns a snapshot into the set of UUIDs the whitelist should hold.
     *
     * <p>Username entries are resolved here, on the sync thread, never on the login path.
     * On an offline-mode server the UUID is derived; online, it needs a profile-cache hit,
     * which can miss for a player who has never joined.
     */
    private Set<UUID> resolve(WhitelistSnapshot snapshot) {
        Set<UUID> resolved = new HashSet<>(snapshot.uuids());

        for (String name : snapshot.names()) {
            Optional<UUID> uuid = resolveName(name);
            if (uuid.isPresent()) {
                resolved.add(uuid.get());
            } else {
                log.warn("Whitelist entry '{}' could not be resolved to a UUID yet; "
                        + "on an online-mode server this resolves once they have joined, "
                        + "or you can add them by UUID in the dashboard.", name);
            }
        }
        return resolved;
    }

    private Optional<UUID> resolveName(String name) {
        if (!server.usesAuthentication()) {
            // 26.x derives the offline id for us.
            return Optional.of(NameAndId.createOffline(name).id());
        }
        return server.services().nameToIdCache() == null
                ? Optional.empty()
                : server.services().nameToIdCache().get(name).map(NameAndId::id);
    }

    /**
     * The whitelist is keyed by UUID string, so the name here is cosmetic: it only shows
     * up in whitelist.json and /whitelist list. A cache hit gives a readable name; a miss
     * still matches correctly.
     */
    private NameAndId entryFor(UUID uuid) {
        String name = server.services().nameToIdCache() == null
                ? null
                : server.services().nameToIdCache().get(uuid).map(NameAndId::name).orElse(null);
        return new NameAndId(uuid, name == null ? uuid.toString() : name);
    }
}
