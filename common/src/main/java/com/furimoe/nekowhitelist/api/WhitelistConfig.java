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
 * @param checkOnJoin     ask the API about a player the cached list rejects, so someone
 *                        added seconds ago gets in without waiting for the next sync
 * @param joinCheckTimeoutMillis how long that lookup may hold up a login before the
 *                        cached answer stands
 */
public record WhitelistConfig(
        String baseUrl,
        String apiKey,
        String instance,
        int syncSeconds,
        Boolean enforceOverride,
        boolean checkOnJoin,
        int joinCheckTimeoutMillis) {

    public static final String FILE_NAME = "neko-whitelist.json";

    private static final String DEFAULT_BASE_URL = "https://api.neko-launcher.com";
    private static final int DEFAULT_SYNC_SECONDS = 300;
    /** The API docs ask for a sync every few minutes, not every few seconds. */
    private static final int MIN_SYNC_SECONDS = 15;

    private static final int DEFAULT_JOIN_CHECK_TIMEOUT_MILLIS = 2000;
    /** Long enough to hold a login open; beyond this the cached answer is kinder. */
    private static final int MAX_JOIN_CHECK_TIMEOUT_MILLIS = 10000;
    private static final int MIN_JOIN_CHECK_TIMEOUT_MILLIS = 250;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static WhitelistConfig defaults() {
        return new WhitelistConfig(DEFAULT_BASE_URL, "", "", DEFAULT_SYNC_SECONDS, null,
                true, DEFAULT_JOIN_CHECK_TIMEOUT_MILLIS);
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

        // A too-eager poll is what the API docs explicitly ask plugins not to do.
        int syncSeconds = Math.max(MIN_SYNC_SECONDS,
                integer(json, "syncSeconds", defaults.syncSeconds()));

        Boolean enforceOverride = json.has("enforceOverride") && !json.get("enforceOverride").isJsonNull()
                ? json.get("enforceOverride").getAsBoolean()
                : null;

        return new WhitelistConfig(
                baseUrl,
                string(json, "apiKey", defaults.apiKey()).trim(),
                string(json, "instance", defaults.instance()).trim(),
                syncSeconds,
                enforceOverride,
                bool(json, "checkOnJoin", defaults.checkOnJoin()),
                clamp(integer(json, "joinCheckTimeoutMillis", defaults.joinCheckTimeoutMillis()),
                        MIN_JOIN_CHECK_TIMEOUT_MILLIS, MAX_JOIN_CHECK_TIMEOUT_MILLIS));
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("baseUrl", baseUrl);
        json.addProperty("apiKey", apiKey);
        json.addProperty("instance", instance);
        json.addProperty("syncSeconds", syncSeconds);
        json.addProperty("checkOnJoin", checkOnJoin);
        json.addProperty("joinCheckTimeoutMillis", joinCheckTimeoutMillis);
        if (enforceOverride == null) {
            json.add("enforceOverride", com.google.gson.JsonNull.INSTANCE);
        } else {
            json.addProperty("enforceOverride", enforceOverride);
        }
        return json;
    }

    private static int integer(JsonObject json, String key, int fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive()
                ? json.get(key).getAsInt()
                : fallback;
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive()
                ? json.get(key).getAsBoolean()
                : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
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
