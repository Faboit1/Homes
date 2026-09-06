# CheeseHomes

A homes plugin for **Folia 26.2** built around the Minecraft **Dialog API** — no chest GUIs,
no inventory hacks, just native dialogs the client renders itself. Data lives in SQLite,
and every limit is driven by permissions.

## Highlights

- **Folia-native.** Every teleport goes through `teleportAsync`, every task runs on the
  right region/entity/async scheduler. No `BukkitScheduler` anywhere.
- **Dialog-driven UI.** A slot grid with a *Show More* expansion, a per-home management
  screen, an inline rename form, a delete confirmation, and a searchable icon picker
  covering every obtainable item.
- **Permission-tiered slots.** `cheesehomes.maxhomes.<amount>` — highest match wins.
- **SQLite storage.** WAL mode, one dedicated I/O thread, prepared statements held open,
  reads served from an in-memory cache and writes batched behind it.
- **ModernHome import.** Point it at an existing `storage.db` and it converts in one command.
- **Everything is configurable.** Titles, labels, tooltips, colours, widths, columns,
  page sizes, warm-ups, cooldowns, name rules, icon allow/deny lists, and every message.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/homes` (`/homelist`) | `cheesehomes.use` | Open the homes dialog |
| `/home [name]` | `cheesehomes.use` | Teleport to a home, or open the dialog with no argument |
| `/sethome [name]` (`/createhome`) | `cheesehomes.set` | Set a home here, or move an existing one |
| `/delhome <name>` (`/removehome`) | `cheesehomes.delete` | Delete a home |
| `/cheesehomes reload` | `cheesehomes.admin` | Re-read `config.yml` |
| `/cheesehomes info` | `cheesehomes.admin` | Version, cached players, stored homes |
| `/cheesehomes limits` | `cheesehomes.admin` | Show your own resolved slots / warm-up / cooldown |
| `/cheesehomes import modernhome [overwrite]` | `cheesehomes.admin` | Import from ModernHome |

## Permissions

| Node | Effect |
| --- | --- |
| `cheesehomes.maxhomes.<amount>` | Slot count. The **highest** matching amount wins. |
| `cheesehomes.maxhomes.unlimited` | Grants `limits.hard-cap` slots. |
| `cheesehomes.cooldown.<seconds>` | Teleport cooldown. The **lowest** match wins. `_` stands in for a decimal point (`cheesehomes.cooldown.2_5` = 2.5s). |
| `cheesehomes.warmup.<seconds>` | Teleport warm-up, lowest match wins, same `_` rule. |
| `cheesehomes.bypass.cooldown` | No cooldown. |
| `cheesehomes.bypass.warmup` | Teleport instantly. |
| `cheesehomes.world.<world>` | Set homes in that world (only checked when `worlds.require-per-world-permission` is on). |

`limits.grants` in the config maps any flat permission to a slot count, for rank plugins
that would rather hand out `cheesehomes.rank.vip` than a numeric node.

With `limits.probe-permissions` on (the default) the resolver also asks
`hasPermission` directly, counting down from the hard cap, so wildcard grants and
`permissions.yml` defaults work even when they never appear in a player's effective
permission set. Turn it off to hold operators to the default slot count.

## Importing from ModernHome

```
/cheesehomes import modernhome
/cheesehomes import modernhome overwrite
```

Reads `plugins/ModernHome/storage.db` (path configurable under `import.modernhome`).
`home_index` becomes the slot, blank names fall back to the configured default name, and
names that would not pass `homes.name-pattern` are replaced rather than imported broken.
Block coordinates are centred so players do not land in a corner. Players who already have
homes in CheeseHomes are skipped unless you pass `overwrite`. The whole thing runs off the
server threads and commits in a single transaction.

## Building

```
./gradlew build
```

Produces `build/libs/CheeseHomes-<version>.jar`. Requires JDK 25 (Minecraft 26.2's toolchain);
`sqlite-jdbc` is resolved at load time by Paper's `libraries` mechanism rather than shaded in,
which keeps the jar under 100 KB.

GitHub Actions builds every push and attaches the jar as an artifact; pushing a `v*` tag
also publishes a release.

## Tested against

Folia 26.2 (build 7), Java 25 — boot, all commands, every dialog screen and button, the icon
search, permission-tiered slots, teleport warm-up/cooldown/move-cancel, and a 6 289-row
ModernHome import.
