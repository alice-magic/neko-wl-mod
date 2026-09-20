package com.furimoe.nekowhitelist.mc;

import com.furimoe.nekowhitelist.api.WhitelistSnapshot;
import com.mojang.authlib.GameProfile;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;

import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pushes a snapshot into the server's own whitelist, so vanilla does the enforcing in
 * {@code PlayerList#canPlayerLogin} and no login-path HTTP call is ever needed.
 *
 * <p>This is the only version-divergent class in the mod. On 1.20.1 and 1.21.1 the
 * whitelist is keyed by {@link GameProfile} and the toggle lives on {@code PlayerList};
 * on 26.x both moved ({@code NameAndId} and {@code DedicatedServer} respectively).
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
    private final Map<UUID, GameProfile> applied = new HashMap<>();

    /** The owner is warned about hand-added entries once, not every five minutes. */
    private boolean adoptedExisting;

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
    public void apply(WhitelistSnapshot snapshot, boolean enforce) {
        UserWhiteList whitelist = server.getPlayerList().getWhiteList();
        Set<UUID> wanted = resolve(snapshot);

        adoptExistingEntries(whitelist, wanted);

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
                GameProfile profile = profileFor(uuid);
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
     * Takes ownership of whatever was already in whitelist.json on the first sync.
     *
     * <p>The dashboard is the source of truth, so entries added by hand or with
     * {@code /whitelist add} are dropped. Name them once so the owner can move them into
     * the dashboard rather than wondering where they went.
     */
    private void adoptExistingEntries(UserWhiteList whitelist, Set<UUID> wanted) {
        if (adoptedExisting) {
            return;
        }
        adoptedExisting = true;

        List<String> orphans = new ArrayList<>();
        for (String name : whitelist.getUserList()) {
            Optional<UUID> uuid = resolveName(name.toLowerCase(Locale.ROOT));
            if (uuid.isEmpty()) {
                // Unresolvable, so it cannot be diffed; vanilla keeps it and we say so.
                log.warn("Existing whitelist entry '{}' could not be resolved to a UUID; "
                        + "leaving it in place.", name);
                continue;
            }

            GameProfile profile = new GameProfile(uuid.get(), name);
            applied.put(uuid.get(), profile);
            if (!wanted.contains(uuid.get())) {
                orphans.add(name);
            }
        }

        if (!orphans.isEmpty()) {
            log.warn("This mod owns whitelist.json. Removing {} entry/entries that are not in "
                            + "the Neko Launcher whitelist: {}. Add them in the dashboard to keep them.",
                    orphans.size(), String.join(", ", orphans));
        }
    }

    /**
     * Turns the server's whitelist on or off.
     *
     * <p>26.x note: this method moves to {@code DedicatedServer#setUsingWhitelist} and
     * needs an {@code instanceof} guard there.
     */
    private void setUsingWhitelist(boolean enforce) {
        if (server.getPlayerList().isUsingWhitelist() != enforce) {
            server.getPlayerList().setUsingWhiteList(enforce);
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
            return Optional.of(offlineUuid(name));
        }
        return server.getProfileCache() == null
                ? Optional.empty()
                : server.getProfileCache().get(name).map(GameProfile::getId);
    }

    /** How vanilla derives a UUID for an offline-mode player. */
    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The whitelist is keyed by UUID string, so the name here is cosmetic: it only shows
     * up in whitelist.json and /whitelist list. A cache hit gives a readable name; a miss
     * still matches correctly.
     */
    private GameProfile profileFor(UUID uuid) {
        String name = server.getProfileCache() == null
                ? null
                : server.getProfileCache().get(uuid).map(GameProfile::getName).orElse(null);
        return new GameProfile(uuid, name == null ? uuid.toString() : name);
    }
}
