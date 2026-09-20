package com.furimoe.nekowhitelist.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * User-facing configuration, read from {@code config/neko-whitelist.json}.
 *
 * @param baseUrl         API origin, no trailing slash
 * @param apiKey          workspace API key ({@code nl_} + 32 hex); never logged
 * @param instance        the URL-safe instance {@code name}, as listed by
 *                        {@code GET /api/v1/server/instances}
 * @param syncSeconds     how often to re-sync
 * @param enforceOverride {@code null} follows the API's {@code enforceWhitelist};
 *                        a non-null value overrides the instance owner's setting
 */
public record WhitelistConfig(
        String baseUrl,
        String apiKey,
        String instance,
        int syncSeconds,
        Boolean enforceOverride) {

    public static final String FILE_NAME = "neko-whitelist.json";

    private static final String DEFAULT_BASE_URL = "https://api.neko-launcher.com";
    private static final int DEFAULT_SYNC_SECONDS = 300;
    private static final int MIN_SYNC_SECONDS = 60;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static WhitelistConfig defaults() {
        return new WhitelistConfig(DEFAULT_BASE_URL, "", "", DEFAULT_SYNC_SECONDS, null);
    }

    /** True once both the key and the instance name are filled in. */
    public boolean isConfigured() {
        return !apiKey.isBlank() && !instance.isBlank();
    }

    /**
     * Reads the config, writing a default file first if none exists.
     *
     * @throws IOException if the file exists but cannot be read or parsed
     */
    public static WhitelistConfig load(Path configDir) throws IOException {
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            Files.createDirectories(configDir);
            Files.writeString(file, GSON.toJson(defaults().toJson()), StandardCharsets.UTF_8);
            return defaults();
        }

        JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        return fromJson(json);
    }

    static WhitelistConfig fromJson(JsonObject json) {
        WhitelistConfig defaults = defaults();

        String baseUrl = string(json, "baseUrl", defaults.baseUrl());
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        int syncSeconds = json.has("syncSeconds") && json.get("syncSeconds").isJsonPrimitive()
                ? json.get("syncSeconds").getAsInt()
                : defaults.syncSeconds();
        // A too-eager poll is what the API docs explicitly ask plugins not to do.
        syncSeconds = Math.max(MIN_SYNC_SECONDS, syncSeconds);

        Boolean enforceOverride = json.has("enforceOverride") && !json.get("enforceOverride").isJsonNull()
                ? json.get("enforceOverride").getAsBoolean()
                : null;

        return new WhitelistConfig(
                baseUrl,
                string(json, "apiKey", defaults.apiKey()).trim(),
                string(json, "instance", defaults.instance()).trim(),
                syncSeconds,
                enforceOverride);
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("baseUrl", baseUrl);
        json.addProperty("apiKey", apiKey);
        json.addProperty("instance", instance);
        json.addProperty("syncSeconds", syncSeconds);
        if (enforceOverride == null) {
            json.add("enforceOverride", com.google.gson.JsonNull.INSTANCE);
        } else {
            json.addProperty("enforceOverride", enforceOverride);
        }
        return json;
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive()
                ? json.get(key).getAsString()
                : fallback;
    }

    /** Never let the key reach a log line or an exception message. */
    public String redact(String message) {
        if (message == null || apiKey.isBlank()) {
            return message;
        }
        return message.replace(apiKey, "***");
    }
}
