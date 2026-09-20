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
  "syncSeconds": 300,
  "enforceOverride": null,
  "checkOnJoin": true,
  "joinCheckTimeoutMillis": 2000
}
```

| Field | Meaning |
|---|---|
| `apiKey` | Workspace API key, from **Workspace → Settings → API Key** in the dashboard |
| `instance` | The instance's URL-safe **name**, not its display name. List them with the curl command below. |
| `syncSeconds` | How often to re-sync. Clamped to a 15-second minimum. |
| `enforceOverride` | `null` follows the instance's own `enforceWhitelist` setting. `true` or `false` overrides it. |
| `checkOnJoin` | Look a connecting player up against the API when the synced list does not know them, so someone added seconds ago gets in on their next attempt rather than after the next sync. |
| `joinCheckTimeoutMillis` | How long that background lookup may take before giving up. It never delays a login. Clamped to 250–10000. |

To find the instance name:

```bash
curl -s -H "X-API-Key: nl_..." https://api.neko-launcher.com/api/v1/server/instances
```

The key authenticates as the **whole workspace**, so it can read every instance in it.
Treat it like a password: it belongs in the server's config file, never in a repository,
a resource pack, or a screenshot. It is never written to the log. Rotating it in the dashboard
invalidates the old one immediately, so update the config before you rotate.

## The mod owns `whitelist.json`

The dashboard is the source of truth. On the first sync after startup the mod **drops
every entry in `whitelist.json` that it did not put there**, naming them in the log first,
and from then on the file mirrors the Neko Launcher whitelist. Entries it wrote on a
previous run are recognised and kept, so a restart does not rewrite the file.

Anything added with `/whitelist add` or by hand is removed on the next sync. To keep
someone, add them in the dashboard.

**The plugin does not do this.** Bukkit exposes its whitelist only as offline-player
entries, and rewriting it would fight `/whitelist` and other plugins, so the plugin checks
each login against the synced list instead and leaves `whitelist.json` alone.

`/whitelist reload` re-reads the file and will drop the mod's entries until the next sync
brings them back.

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

Three things keep the server close to the dashboard, in increasing order of immediacy:

1. **The poll.** `syncSeconds`, 5 minutes by default, down to 15 if you want it tighter.
   The API has no webhook, so polling is the only push-free option.
2. **`/nekowhitelist reload`.** Applies a dashboard change straight away.
3. **The join check.** A player the synced list does not know is looked up against the
   API when they connect. The lookup runs in the background and the first attempt is
   still refused, so **they get in on their next attempt** a second or two later, without
   waiting for the next sync. Turn it off with `checkOnJoin: false`.

The login check runs on the server thread, so the gate never blocks it: a slow or
unreachable API costs the server nothing, and a stranger reconnecting in a loop cannot
stall it. A player already in the synced list never triggers a lookup at all.

## How it behaves

The mod writes the server's whitelist and lets the vanilla login check reject anyone
missing, with the usual "You are not white-listed on this server!" message. The plugin
refuses the login itself, with the same message.
No HTTP call ever blocks a login: the join check above runs in the background, off the
server thread, so an API outage cannot stall a connection or a tick.

- **Offline API, server up.** The last known list stays in force, with backoff between
  retries. Nobody is kicked.
- **Offline API, server restarting.** The list is restored from a local cache file
  written after every successful sync.
- **No cache and the first sync fails.** Enforcement is left untouched and a warning is
  logged, rather than switching on an empty whitelist and locking out everyone including
  admins.
- **Bad key (`401`) or unknown instance (`404`).** Logged once and the sync loop stops,
  since retrying cannot fix either. Any list already applied keeps working; fix the config
  and restart.
- **Steady state.** For the mod, a sync with no dashboard changes writes nothing to disk,
  and a restart with no changes does not rewrite `whitelist.json` either.
- **Bans are left alone.** The join check only reconsiders a whitelist refusal. A banned
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
