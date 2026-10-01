# Neko Launcher Whitelist

Keeps a Minecraft server's whitelist in sync with a
[Neko Launcher](https://neko-launcher.com) instance. Manage who may join from the
dashboard; the server follows.

Ships as a **mod** for Fabric, Forge and NeoForge, and as a **plugin** for Paper, Spigot,
Bukkit, Folia and CanvasMC. Both are server-side: clients install nothing, and a vanilla
client can join a server running either.

## Supported versions

### Mod

| Minecraft | Loaders | Branch |
|---|---|---|
| 1.20.1 | Fabric, Forge | `mc/1.20.1` |
| 1.20.4 | Fabric, Forge | `mc/1.20.4` |
| 1.20.6 | Fabric, NeoForge | `mc/1.20.6` |
| 1.21.1 | Fabric, NeoForge | `mc/1.21.1` |
| 1.21.4 | Fabric, NeoForge | `mc/1.21.4` |
| 1.21.8 | Fabric, NeoForge | `mc/1.21.8` |
| 1.21.11 | Fabric, NeoForge | `mc/1.21.11` |
| 26.1.2 | Fabric, NeoForge | `mc/26.1.2` |
| 26.2 | Fabric, NeoForge | `mc/26.2` |

Architectury ships a Forge target only through 1.20.4; later versions use NeoForge. The
Fabric jar also runs on Quilt. This is a **server-side** mod: clients need nothing, and a
vanilla client can join a server running it.

### Plugin

| Server | Branch |
|---|---|
| Paper, Spigot, Bukkit, Folia, Purpur, CanvasMC | `plugin/bukkit` |

One jar covers all of them, on Minecraft 1.20.1 and later. They share the Bukkit API, and
the Folia scheduler difference is handled at runtime. The plugin needs no Architectury API.

Use the plugin on a Bukkit-family server and the mod on a Fabric, Forge or NeoForge
server; they do the same job and are configured the same way.

The mod requires [Architectury API](https://modrinth.com/mod/architectury-api), plus
[Fabric API](https://modrinth.com/mod/fabric-api) on Fabric. The plugin requires neither.

## Setup

**Mod:** drop the jar for your Minecraft version and loader into `mods/`, with Architectury
API. **Plugin:** drop the single jar into `plugins/`; it needs nothing else.

Then start the server once. It writes its config and logs that it is idle:
`config/neko-whitelist.json` for the mod, `plugins/NekoWhitelist/neko-whitelist.json` for
the plugin. Fill in the key and the instance name, then restart.

```json
{
  "baseUrl": "https://api.neko-launcher.com",
  "apiKey": "nl_...",
  "instance": "survival-smp",
  "syncSeconds": 60,
  "enforceOverride": null
}
```

| Field | Meaning |
|---|---|
| `apiKey` | Workspace API key, from **Workspace → Settings → API Key** in the dashboard |
| `instance` | The instance's URL-safe **name**, not its display name. List them with the curl command below. |
| `syncSeconds` | How often to re-sync. Defaults to 60 seconds and cannot be lower. |
| `enforceOverride` | `null` follows the instance's own `enforceWhitelist` setting. `true` or `false` overrides it. |

To find the instance name:

```bash
curl -s -H "X-API-Key: nl_..." https://api.neko-launcher.com/api/v1/server/instances
```

The key authenticates as the **whole workspace**, so it can read every instance in it.
Treat it like a password: it belongs in the server's config file, never in a repository,
a resource pack, or a screenshot. It is never written to the log. Rotating it in the dashboard
invalidates the old one immediately, so update the config before you rotate.

The dashboard is the source of truth. The server loads the saved snapshot on startup,
fetches the full list from the API, then refreshes it every 60 seconds. Login checks use
only that in-memory snapshot; a player missing from it is rejected without an API request.
If the first fetch has not completed, logins are rejected until it does. A failed refresh
keeps the last good snapshot.

## Commands

| Command | Does |
|---|---|
| `/nekowhitelist reload` | Syncs now instead of waiting for the timer |
| `/nekowhitelist status` | Reports the entry count, instance, enforcement and interval |

Both are gated at the same bar as `/stop` and always work from the console. The command
reaches an API key and decides who may join, so it is deliberately out of reach for
ordinary operators. On the plugin the permission node is `nekowhitelist.admin`, and the
command is also available as `/nekowl`.

## Staying current

The API has no webhook, so dashboard changes reach the server on the next 60-second poll.
Run `/nekowhitelist reload` to fetch immediately.

## How it behaves

The mod writes the server's whitelist and lets the vanilla login check reject anyone
missing, with the usual "You are not white-listed on this server!" message. The plugin
refuses the login itself, with the same message.
Login checks read memory only and never call the API. Sync requests run in the background,
so API outages do not stall connections or server ticks.

- **Offline API, server up.** The last known list stays in force, with backoff between
  retries. Nobody is kicked.
- **Offline API, server restarting.** The list is restored from a local cache file
  written after every successful sync.
- **No cache and the first sync fails.** Joins are rejected until a sync succeeds.
- **Bad key (`401`) or unknown instance (`404`).** Logged once and the sync loop stops,
  since retrying cannot fix either. Any list already applied keeps working; fix the config
  and restart.
- **Steady state.** For the mod, a sync with no dashboard changes writes nothing to disk,
  and a restart with no changes does not rewrite `whitelist.json` either.
- **Bans are left alone.** The login hook only reconsiders a whitelist refusal. A banned
  player, an IP ban and a full server are never overridden.

### Username entries

A whitelist entry can name a UUID or a username. UUID entries are exact. Username entries
are resolved to a UUID when the list syncs:

- **Offline-mode servers** derive the UUID, so it always resolves.
- **Online-mode servers** need the name in the server's profile cache, which means the
  player has joined before. Until then the entry is logged and skipped. Prefer UUID
  entries in the dashboard for players who have never joined.

The plugin has no such limit: it matches the connecting player's name directly, so a
username entry works on the first join.

## Building

On a `mc/**` branch:

```bash
./gradlew build              # jars land in fabric/build/libs and neoforge/build/libs
./gradlew :common:selfCheck  # assert-based checks over the Minecraft-free api layer
./gradlew :fabric:runServer  # a dev server for manual testing
```

On `plugin/bukkit`:

```bash
./gradlew build        # one jar in build/libs
./gradlew selfCheck    # the same checks over the same api layer
```

Each Minecraft version lives on its own branch, because the toolchains differ too much to
share: Java 17 through 25, and 26.x ships deobfuscated, so it declares no mappings, uses
the `loom-no-remap` plugin, and needs a newer Gradle.
Inside `common`, the `api` package has no Minecraft imports and is identical on every
branch, including on the plugin, where it is copied verbatim.

Three files differ across branches: `mc/WhitelistApplier`, `mixin/PlayerListMixin` and
`mc/WhitelistCommand`. The split is **between 1.21.8 and 1.21.11**, not at 26.x as you
might expect: from 1.21.11 onward the whitelist is keyed on `NameAndId` rather than
`GameProfile`, `setUsingWhitelist` lives on `DedicatedServer` rather than `PlayerList`, and
integer permission levels are replaced by named permission sets. Branches on the same side
of that line differ only in version numbers.

## License

MIT
