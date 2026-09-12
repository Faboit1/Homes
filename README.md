# CheeseHomes

A homes plugin for **Folia 26.2** built around the Minecraft **Dialog API** — no chest GUIs,
no inventory hacks, just native dialogs the client renders itself. Data lives in SQLite,
and every limit is driven by permissions.

## Highlights

- **Folia-native.** Every teleport goes through `teleportAsync`, every task runs on the
  right region/entity/async scheduler. No `BukkitScheduler` anywhere.
- **Dialog-driven UI.** A slot grid with a *Show More* expansion, a per-home management
  screen, an inline rename form, a delete confirmation, and a searchable icon picker
  listing every obtainable item in one scrollable dialog.
- **Per-home descriptions.** A free-text note on each home, shown on hover in the
  list and on the home's own screen, edited from a multi-line box in that screen.
- **Streamer mode.** `/showhomecoordinates false` hides coordinates from the menu for
  that player only, so opening your homes on camera does not hand out your base.
- **Real item sprites.** With [CheeseCore](https://github.com/Faboit1/CheeseCore) installed,
  every icon button and every home in the list draws the item's actual texture, resolved
  against the atlas layout of the client reading the dialog.
- **Permission-tiered slots.** `cheesehomes.maxhomes.<amount>` — highest match wins.
- **SQLite storage.** WAL mode, one dedicated I/O thread, prepared statements held open,
  reads served from an in-memory cache and writes batched behind it.
- **ModernHome import.** Point it at an existing `storage.db` and it converts in one command.
- **Everything is configurable.** Titles, labels, tooltips, colours, widths, columns,
  page sizes, warm-ups, cooldowns, name rules, icon allow/deny lists, and every message.

## Hiding coordinates

`/showhomecoordinates false` is a per-player switch, stored alongside that player's
homes, for anyone who would rather not broadcast where their base is.

The masking happens where the `<x>`, `<y>` and `<z>` placeholders are resolved, not
at each screen, so a coordinate cannot leak through a label or tooltip you wrote
yourself — they render as `privacy.hidden-coordinate` (`???` by default) everywhere.
The home screen additionally swaps `dialog.manage.body` for
`dialog.manage.body-hidden`, which by default prints the world and nothing else.

`privacy.show-coordinates-default` decides what a player who has never chosen sees.

## Descriptions

Each home can carry a note — what is stored there, who it is shared with, whatever.
It shows on hover in the homes list and as a line on the home's own screen, and is
edited from the **Description** button there (multi-line, `homes.max-description-length`
characters, saving an empty box clears it).

Both the tooltip line and the body line are configurable and are omitted entirely
when a home has no description.

## Item sprites (optional)

Install [CheeseCore](https://github.com/Faboit1/CheeseCore) alongside this plugin and the
dialogs stop being text-only: the icon picker shows each item's real texture next to its
name, and every home in the list is prefixed with its own icon.

Which atlas holds a given texture changed in 1.21.11 — items moved out of `blocks` into a
new `items` atlas — so a sprite pinned to the wrong atlas renders as a purple-and-black
square for half your players. CheeseCore owns that mapping per client version, and
CheeseHomes asks it per viewer, so a 1.21.9 player and a 26.2 player both see a diamond.

The dependency is optional and resolved at runtime. Without CheeseCore, or for a client
older than 1.21.9, buttons quietly fall back to plain text. Turn it off entirely with
`dialog.sprites.enabled: false`, or keep it in the picker only with
`dialog.sprites.in-list: false`.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/homes` (`/homelist`) | `cheesehomes.use` | Open the homes dialog |
| `/home [name]` | `cheesehomes.use` | Teleport to a home, or open the dialog with no argument |
| `/sethome [name]` (`/createhome`) | `cheesehomes.set` | Set a home here, or move an existing one |
| `/delhome <name>` (`/removehome`) | `cheesehomes.delete` | Delete a home |
| `/showhomecoordinates [true\|false]` (`/homecoords`) | `cheesehomes.coordinates` | Show or hide coordinates in your own menu; no argument toggles |
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
