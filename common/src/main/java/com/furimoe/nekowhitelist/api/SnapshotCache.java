package com.furimoe.nekowhitelist.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The last good snapshot, persisted to disk.
 *
 * <p>Without this, an API outage during a server restart has no previous list to fall
 * back on and would lock out everyone, including admins. Loading the cache before the
 * first HTTP call keeps a restart no worse than staying up.
 */
public final class SnapshotCache {

    public static final String FILE_NAME = "neko-whitelist-cache.json";

    private final Path file;

    public SnapshotCache(Path configDir) {
        this.file = configDir.resolve(FILE_NAME);
    }

    /** Empty when there is no cache yet or it is unreadable; never throws. */
    public Optional<WhitelistSnapshot> read() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            JsonObject json = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();

            Set<UUID> uuids = new HashSet<>();
            for (JsonElement element : array(json, "uuids")) {
                UUID parsed = NekoWhitelistClient.parseUndashed(element.getAsString());
                if (parsed != null) {
                    uuids.add(parsed);
                }
            }

            Set<String> names = new HashSet<>();
            for (JsonElement element : array(json, "names")) {
                names.add(element.getAsString().toLowerCase(Locale.ROOT));
            }

            boolean enforce = json.has("enforce") && json.get("enforce").getAsBoolean();
            return Optional.of(new WhitelistSnapshot(uuids, names, enforce));
        } catch (IOException | RuntimeException e) {
            // A corrupt cache is not worth failing over; the next sync rewrites it.
            return Optional.empty();
        }
    }

    /**
     * Writes via a temp file and an atomic move, so a crash mid-write cannot leave a
     * truncated cache that the next boot would read as a short whitelist.
     */
    public void write(WhitelistSnapshot snapshot) throws IOException {
        JsonObject json = new JsonObject();

        JsonArray uuids = new JsonArray();
        snapshot.uuids().forEach(uuid -> uuids.add(uuid.toString()));
        json.add("uuids", uuids);

        JsonArray names = new JsonArray();
        snapshot.names().forEach(names::add);
        json.add("names", names);

        json.addProperty("enforce", snapshot.enforce());

        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(FILE_NAME + ".tmp");
        Files.writeString(temp, json.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Some filesystems refuse ATOMIC_MOVE; a plain replace still beats a partial write.
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static JsonArray array(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }
}
