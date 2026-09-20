# Neko Launcher Whitelist

A server-side mod that keeps a Minecraft server's whitelist in sync with a
[Neko Launcher](https://neko-launcher.com) instance. Manage who may join from the
dashboard; the server follows.

Built with Architectury, so one codebase covers Fabric, Forge and NeoForge.

## Supported versions

| Minecraft | Loaders | Branch |
|---|---|---|
| 1.20.1 | Fabric, Forge | `mc/1.20.1` |
| 1.21.1 | Fabric, NeoForge | `mc/1.21.1` |
| 26.1.2 | Fabric, NeoForge | `mc/26.1.2` |
| 26.2 | Fabric, NeoForge | `mc/26.2` |

Forge exists only on 1.20.1; later versions use NeoForge. The Fabric jar also runs on
Quilt. This is a **server-side** mod: clients need nothing, and a vanilla client can join
a server running it.

Requires [Architectury API](https://modrinth.com/mod/architectury-api) and, on Fabric,
[Fabric API](https://modrinth.com/mod/fabric-api).

## Setup

1. Drop the jar for your version and loader into the server's `mods/` folder, along with
   Architectury API.
2. Start the server once. It writes `config/neko-whitelist.json` and logs that it is idle.
3. Fill in the key and the instance name, then restart.

```json
{
  "baseUrl": "https://api.neko-launcher.com",
  "apiKey": "nl_...",
  "instance": "survival-smp",
  "syncSeconds": 300,
  "enforceOverride": null
}
```

| Field | Meaning |
|---|---|
| `apiKey` | Workspace API key, from **Workspace → Settings → API Key** in the dashboard |
| `instance` | The instance's URL-safe **name**, not its display name. List them with the curl command below. |
| `syncSeconds` | How often to re-sync. Clamped to a 60-second minimum. |
| `enforceOverride` | `null` follows the instance's own `enforceWhitelist` setting. `true` or `false` overrides it. |

To find the instance name:

```bash
curl -s -H "X-API-Key: nl_..." https://api.neko-launcher.com/api/v1/server/instances
```

The key authenticates as the **whole workspace**, so it can read every instance in it.
Treat it like a password: it belongs in the server's config file, never in a repository,
a resource pack, or a screenshot. The mod never logs it. Rotating it in the dashboard
invalidates the old one immediately, so update the config before you rotate.

## This mod owns `whitelist.json`

The dashboard is the source of truth. On the first sync after startup the mod **drops
every entry already in `whitelist.json`**, naming them in the log first, and from then on
the file mirrors the Neko Launcher whitelist.

Anything added with `/whitelist add` or by hand is removed on the next sync. To keep
someone, add them in the dashboard.

`/whitelist reload` re-reads the file and will drop the mod's entries until the next sync
brings them back.

## How it behaves

Enforcement is vanilla's: the mod writes the whitelist and the server's own login check
rejects anyone missing, with the usual "You are not white-listed on this server!" message.
No HTTP call happens while a player is connecting, so an API outage cannot stall a login.

- **Offline API, server up.** The last known list stays in force, with backoff between
  retries. Nobody is kicked.
- **Offline API, server restarting.** The list is restored from
  `config/neko-whitelist-cache.json`, written after every successful sync.
- **No cache and the first sync fails.** Enforcement is left untouched and a warning is
  logged, rather than switching on an empty whitelist and locking out everyone including
  admins.
- **Bad key (`401`) or unknown instance (`404`).** Logged once and the sync loop stops,
  since retrying cannot fix either. Any list already applied keeps working; fix the config
  and restart.
- **Steady state.** A sync with no dashboard changes writes nothing to disk.

### Username entries

A whitelist entry can name a UUID or a username. UUID entries are exact. Username entries
are resolved to a UUID when the list syncs, never during a login:

- **Offline-mode servers** derive the UUID, so it always resolves.
- **Online-mode servers** need the name in the server's profile cache, which means the
  player has joined before. Until then the entry is logged and skipped. Prefer UUID
  entries in the dashboard for players who have never joined.

## Building

```bash
./gradlew build              # jars land in fabric/build/libs and neoforge/build/libs
./gradlew :common:selfCheck  # assert-based checks over the Minecraft-free api layer
./gradlew :fabric:runServer  # a dev server for manual testing
```

Each Minecraft version lives on its own branch, because the toolchains differ too much to
share (Java 17 through 25, and 26.x ships deobfuscated with a different Loom plugin).
Inside `common`, the `api` package has no Minecraft imports and is identical on every
branch; only `mc/WhitelistApplier` differs between the 1.x and 26.x lines.

## License

MIT
