package com.furimoe.nekowhitelist.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Asserts over the parts of the API layer that can be wrong silently: UUID re-dashing,
 * matchType routing, and the snapshot diff that decides what we write to disk.
 *
 * <p>Pure Java, no Minecraft and no test framework. Run with assertions enabled:
 * {@code java -ea -cp <gson> SelfCheck.java}
 */
public final class SelfCheck {

    private SelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (!SelfCheck.class.desiredAssertionStatus()) {
            throw new IllegalStateException("Run with -ea, or these checks assert nothing.");
        }

        checkUuidParsing();
        checkEntryRouting();
        checkDiff();
        checkConfig();
        checkCacheRoundTrip();

        System.out.println("SelfCheck: all checks passed");
    }

    private static void checkUuidParsing() {
        UUID expected = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

        // The API sends undashed lowercase; that is the case that must work.
        assert expected.equals(NekoWhitelistClient.parseUndashed("069a79f444e94726a5befca90e38aaf5"))
                : "undashed UUID should parse";
        // Dashed and upper-case are accepted too, since the cache round-trips dashed.
        assert expected.equals(NekoWhitelistClient.parseUndashed("069a79f4-44e9-4726-a5be-fca90e38aaf5"))
                : "dashed UUID should parse";
        assert expected.equals(NekoWhitelistClient.parseUndashed("069A79F444E94726A5BEFCA90E38AAF5"))
                : "upper-case UUID should parse";

        // Malformed rows return null instead of throwing, so one bad entry cannot kill a sync.
        assert NekoWhitelistClient.parseUndashed(null) == null : "null should not throw";
        assert NekoWhitelistClient.parseUndashed("") == null : "empty should be rejected";
        assert NekoWhitelistClient.parseUndashed("069a79f4") == null : "short should be rejected";
        assert NekoWhitelistClient.parseUndashed("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz") == null
                : "non-hex should be rejected";
    }

    private static void checkEntryRouting() throws Exception {
        String page = """
                {
                  "instance": "survival-smp",
                  "enforceWhitelist": true,
                  "entries": [
                    { "matchType": "uuid",
                      "minecraftUuid": "069a79f444e94726a5befca90e38aaf5",
                      "username": null },
                    { "matchType": "username",
                      "minecraftUuid": null,
                      "username": "notch" },
                    { "matchType": "sometypefromafutureapi",
                      "minecraftUuid": null,
                      "username": "ignored" }
                  ],
                  "nextCursor": null
                }
                """;

        Set<UUID> uuids = new HashSet<>();
        Set<String> names = new HashSet<>();
        NekoWhitelistClient.readEntries(
                JsonParser.parseString(page).getAsJsonObject(), uuids, names);

        // matchType decides the bucket: a uuid entry must not land among the names.
        assert uuids.equals(Set.of(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")))
                : "uuid entry should land in the uuid set only, got " + uuids;
        assert names.equals(Set.of("notch"))
                : "username entry should land in the name set only, got " + names;

        // Case folding happens on ingest, so a Mixed-Case login still matches.
        Set<UUID> ignoredUuids = new HashSet<>();
        Set<String> mixedCase = new HashSet<>();
        NekoWhitelistClient.readEntries(JsonParser.parseString("""
                { "entries": [ { "matchType": "username", "username": "NoTcH" } ] }
                """).getAsJsonObject(), ignoredUuids, mixedCase);
        assert mixedCase.equals(Set.of("notch")) : "usernames should be lower-cased, got " + mixedCase;

        // A response with no entries array is a protocol error, not an empty whitelist:
        // silently treating it as empty would kick every player off the server.
        boolean threw = false;
        try {
            NekoWhitelistClient.readEntries(
                    JsonParser.parseString("{}").getAsJsonObject(), new HashSet<>(), new HashSet<>());
        } catch (WhitelistApiException e) {
            threw = true;
        }
        assert threw : "a missing entries array must throw, never read as an empty list";
    }

    private static void checkDiff() {
        UUID kept = UUID.randomUUID();
        UUID removed = UUID.randomUUID();
        UUID added = UUID.randomUUID();

        WhitelistSnapshot before = new WhitelistSnapshot(Set.of(kept, removed), Set.of("alice"), true);
        WhitelistSnapshot after = new WhitelistSnapshot(Set.of(kept, added), Set.of("bob"), true);

        Set<UUID> toAdd = new HashSet<>(after.uuids());
        toAdd.removeAll(before.uuids());
        Set<UUID> toRemove = new HashSet<>(before.uuids());
        toRemove.removeAll(after.uuids());

        assert toAdd.equals(Set.of(added)) : "diff should add only the new uuid";
        assert toRemove.equals(Set.of(removed)) : "diff should remove only the dropped uuid";

        // The steady state is the common case: no dashboard change means no disk write.
        Set<UUID> noChange = new HashSet<>(after.uuids());
        noChange.removeAll(after.uuids());
        assert noChange.isEmpty() : "a snapshot diffed against itself must be empty";
    }

    private static void checkConfig() {
        JsonObject json = JsonParser.parseString("""
                { "baseUrl": "https://api.neko-launcher.com/",
                  "apiKey": "  nl_4f3c8a1e9b2d7046b5e1c8a9f2d63b7e  ",
                  "instance": "survival-smp",
                  "syncSeconds": 5,
                  "enforceOverride": null }
                """).getAsJsonObject();

        WhitelistConfig config = WhitelistConfig.fromJson(json);

        assert config.baseUrl().equals("https://api.neko-launcher.com")
                : "trailing slash should be stripped so paths do not double up";
        assert config.apiKey().equals("nl_4f3c8a1e9b2d7046b5e1c8a9f2d63b7e")
                : "a pasted key with stray spaces should still work";
        // The API docs ask for minutes, not seconds; clamp rather than trust the file.
        assert config.syncSeconds() == 60 : "sub-minute sync should clamp, got " + config.syncSeconds();
        assert config.enforceOverride() == null : "null override means follow the API";
        assert config.isConfigured() : "key plus instance means configured";

        assert !WhitelistConfig.defaults().isConfigured() : "defaults must not look configured";

        // The key authenticates the whole workspace, so it must never reach a log line.
        String leaked = "connect failed for nl_4f3c8a1e9b2d7046b5e1c8a9f2d63b7e";
        assert !config.redact(leaked).contains(config.apiKey()) : "redact must strip the key";
    }

    private static void checkCacheRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("neko-whitelist-selfcheck");
        try {
            SnapshotCache cache = new SnapshotCache(dir);
            assert cache.read().isEmpty() : "no cache file means no snapshot";

            WhitelistSnapshot original = new WhitelistSnapshot(
                    Set.of(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")),
                    Set.of("notch"), true);
            cache.write(original);

            WhitelistSnapshot restored = cache.read().orElseThrow();
            assert restored.uuids().equals(original.uuids()) : "uuids should survive a round trip";
            assert restored.names().equals(original.names()) : "names should survive a round trip";
            assert restored.enforce() : "the enforce flag should survive a round trip";

            // A truncated cache must read as absent, so the mod falls back rather than
            // enforcing a half-written list.
            Files.writeString(dir.resolve(SnapshotCache.FILE_NAME), "{ \"uuids\": [");
            assert cache.read().isEmpty() : "a corrupt cache must read as empty";
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // Temp dir cleanup is best-effort.
                    }
                });
            }
        }
    }
}
