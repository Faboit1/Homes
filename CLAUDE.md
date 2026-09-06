# CheeseHomes

## Versioning

**Bump the version on every change.** It lives in `build.gradle.kts`:

```kotlin
version = "1.0.0"
```

`paper-plugin.yml` reads it through `processResources`, so that one line is the
only place to touch. Bump it in the same commit as the change, never as a
follow-up — every jar handed over should be tellable apart by its version alone.

- **patch** (`1.0.0` → `1.0.1`) — fixes, config defaults, docs, tweaks
- **minor** (`1.0.1` → `1.1.0`) — new commands, screens or config sections
- **major** (`1.1.0` → `2.0.0`) — anything that breaks an existing config or database

## Building

```bash
./gradlew build
```

Needs JDK 25 (what Minecraft 26.2 requires). If Gradle cannot find a toolchain,
point it at one: `./gradlew build -Porg.gradle.java.installations.paths=/path/to/jdk-25`.

The jar lands in `build/libs/`. `sqlite-jdbc` is not shaded — Paper resolves it
at load time from the `libraries` block in `paper-plugin.yml`.

## Things that are easy to get wrong

- **Folia.** Teleports must go through `teleportAsync`, and anything touching a
  player must run on that player's `EntityScheduler`. No `BukkitScheduler`.
- **Config upgrades.** `getConfig().options().copyDefaults(true)` is what stops a
  new message key showing up as `Missing message:` on an existing install. New
  keys still need adding to `src/main/resources/config.yml` for fresh installs.
- **CheeseCore** is an optional runtime dependency reached by reflection in
  `ui/SpriteBridge.java`, not a build dependency. It must keep working with the
  plugin absent.
