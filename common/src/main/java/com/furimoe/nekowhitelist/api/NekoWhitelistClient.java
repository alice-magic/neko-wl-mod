package com.furimoe.nekowhitelist.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads an instance's whitelist from the Neko Launcher API.
 *
 * <p>Stateless and thread-safe: every call builds a fresh snapshot and returns it, so a
 * failed sync can never leave a half-built list behind.
 */
public final class NekoWhitelistClient {

    /** The API clamps above 2000; 1000 keeps pages small enough to not stall on a big list. */
    private static final int PAGE_LIMIT = 1000;

    /** Our UUIDs arrive undashed; UUID.fromString wants the dashes back. */
    private static final Pattern UNDASHED = Pattern.compile(
            "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)");

    /** A cursor that is not a 24-char hex id is rejected by the API, so never send one. */
    private static final Pattern CURSOR = Pattern.compile("[0-9a-f]{24}");

    /** Refuse to follow a cursor chain forever if the API ever loops one back on us. */
    private static final int MAX_PAGES = 64;

    private final WhitelistConfig config;
    private final HttpClient http;

    public NekoWhitelistClient(WhitelistConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    NekoWhitelistClient(WhitelistConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    /**
     * Pages through {@code /whitelist} and returns the complete list.
     *
     * <p>The API warns that the list can change mid-sync, so this is a best-effort
     * snapshot rather than a consistent one, which is why we re-sync on a timer.
     */
    public WhitelistSnapshot fetch() throws WhitelistApiException {
        Set<UUID> uuids = new HashSet<>();
        Set<String> names = new HashSet<>();
        boolean enforce = false;

        String cursor = null;
        Set<String> seenCursors = new LinkedHashSet<>();

        for (int page = 0; page < MAX_PAGES; page++) {
            JsonObject data = get(whitelistUrl(cursor));

            enforce = data.has("enforceWhitelist") && data.get("enforceWhitelist").getAsBoolean();
            readEntries(data, uuids, names);

            JsonElement next = data.get("nextCursor");
            if (next == null || next.isJsonNull()) {
                return new WhitelistSnapshot(uuids, names, enforce);
            }

            cursor = next.getAsString();
            if (!CURSOR.matcher(cursor).matches() || !seenCursors.add(cursor)) {
                throw new WhitelistApiException(-1,
                        "API returned an unusable nextCursor; stopping this sync", false);
            }
        }

        throw new WhitelistApiException(-1,
                "Whitelist paging exceeded " + MAX_PAGES + " pages; stopping this sync", false);
    }

    static void readEntries(JsonObject data, Set<UUID> uuids, Set<String> names)
            throws WhitelistApiException {
        JsonElement entries = data.get("entries");
        if (entries == null || !entries.isJsonArray()) {
            throw new WhitelistApiException(-1, "Whitelist response had no entries array", false);
        }

        for (JsonElement element : entries.getAsJsonArray()) {
            JsonObject entry = element.getAsJsonObject();
            // matchType decides which of the other two fields is non-null.
            String matchType = entry.has("matchType") ? entry.get("matchType").getAsString() : "";

            if ("uuid".equals(matchType)) {
                JsonElement raw = entry.get("minecraftUuid");
                if (raw != null && !raw.isJsonNull()) {
                    UUID parsed = parseUndashed(raw.getAsString());
                    if (parsed != null) {
                        uuids.add(parsed);
                    }
                }
            } else if ("username".equals(matchType)) {
                JsonElement raw = entry.get("username");
                if (raw != null && !raw.isJsonNull()) {
                    // Stored lower-case already, but never trust that on the compare side.
                    names.add(raw.getAsString().toLowerCase(Locale.ROOT));
                }
            }
            // An unknown matchType is a newer API than this mod; skipping is the safe read.
        }
    }

    /** Returns null rather than throwing, so one malformed row cannot fail a whole sync. */
    static UUID parseUndashed(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().replace("-", "");
        if (trimmed.length() != 32) {
            return null;
        }
        try {
            return UUID.fromString(UNDASHED.matcher(trimmed).replaceFirst("$1-$2-$3-$4-$5"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String whitelistUrl(String cursor) {
        StringBuilder url = new StringBuilder(config.baseUrl())
                .append("/api/v1/server/instances/")
                .append(URLEncoder.encode(config.instance(), StandardCharsets.UTF_8))
                .append("/whitelist?limit=")
                .append(PAGE_LIMIT);
        if (cursor != null) {
            url.append("&cursor=").append(cursor);
        }
        return url.toString();
    }

    /** Sends one request and unwraps the {@code {code, message, data}} envelope. */
    private JsonObject get(String url) throws WhitelistApiException {
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    // Not Authorization: Bearer, that header is for player sessions.
                    .header("X-API-Key", config.apiKey())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new WhitelistApiException(
                    "Could not reach the Neko Launcher API: "
                            + config.redact(String.valueOf(e.getMessage())), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WhitelistApiException("Whitelist sync was interrupted", e);
        }

        int status = response.statusCode();
        if (status != 200) {
            throw new WhitelistApiException(status, describe(status, response.body()), isTerminal(status));
        }

        JsonObject body;
        try {
            body = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new WhitelistApiException(-1, "API returned a response that was not JSON", false);
        }

        JsonElement data = body.get("data");
        if (data == null || !data.isJsonObject()) {
            throw new WhitelistApiException(-1,
                    "API returned an envelope with no data: " + message(body), false);
        }
        return data.getAsJsonObject();
    }

    /**
     * 401 and 404 will not fix themselves: a rotated key stays rotated and a wrong
     * instance name stays wrong, so both stop the loop instead of retrying.
     */
    private static boolean isTerminal(int status) {
        return status == 401 || status == 404;
    }

    private String describe(int status, String body) {
        String detail = message(safeParse(body));
        return switch (status) {
            case 401 -> "API rejected the key (401 " + detail + "). Check apiKey in "
                    + WhitelistConfig.FILE_NAME + "; a rotated key must be updated by hand.";
            case 404 -> "No instance named '" + config.instance() + "' in this key's workspace (404).";
            case 400 -> "API rejected the request (400 " + detail + ").";
            default -> "API returned HTTP " + status + " " + detail;
        };
    }

    private static JsonObject safeParse(String body) {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static String message(JsonObject body) {
        JsonElement message = body == null ? null : body.get("message");
        return message == null || message.isJsonNull() ? "" : message.getAsString();
    }
}
