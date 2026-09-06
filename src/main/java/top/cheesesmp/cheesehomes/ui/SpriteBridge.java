package top.cheesesmp.cheesehomes.ui;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Draws real item sprites on dialog buttons, using CheeseCore's atlas index.
 *
 * <p>Which atlas holds a given texture moved between 1.21.9 and 1.21.11, and a
 * sprite pointed at the wrong atlas renders as a missing-texture square, so the
 * lookup has to be per viewer rather than per server. CheeseCore already owns
 * that mapping; this only talks to it.
 *
 * <p>The link is reflective on purpose: CheeseCore is an optional runtime
 * dependency, not a build one. Installed, buttons get sprites; absent, they stay
 * text-only and nothing here throws. Resolved components are cached per client
 * version, because the icon picker asks for well over a thousand of them every
 * time it opens.
 */
public final class SpriteBridge {

    /** Cached "this material has no sprite" marker; compared by identity. */
    private static final Component NONE = Component.empty();

    private final Logger logger;
    private final boolean enabled;

    private final MethodHandle isAvailable;
    private final MethodHandle sprites;
    private final MethodHandle versionOf;
    private final MethodHandle spriteFor;
    private final MethodHandle asComponent;

    private final Map<Object, Map<Material, Component>> cache = new ConcurrentHashMap<>();

    private volatile boolean linked;

    public SpriteBridge(Logger logger, boolean enabled) {
        this.logger = logger;
        this.enabled = enabled;

        MethodHandle available = null;
        MethodHandle service = null;
        MethodHandle version = null;
        MethodHandle sprite = null;
        MethodHandle component = null;
        boolean ok = false;

        if (enabled) {
            try {
                Class<?> core = Class.forName("top.cheesesmp.cheesecore.api.CheeseCore");
                Class<?> spriteService = Class.forName("top.cheesesmp.cheesecore.api.SpriteService");
                Class<?> clientVersion = Class.forName("top.cheesesmp.cheesecore.api.ClientVersion");
                Class<?> spriteType = Class.forName("top.cheesesmp.cheesecore.api.Sprite");

                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                available = lookup.findStatic(core, "isAvailable", MethodType.methodType(boolean.class));
                service = lookup.findStatic(core, "sprites", MethodType.methodType(spriteService));
                version = lookup.findVirtual(spriteService, "versionOf",
                        MethodType.methodType(clientVersion, Player.class));
                sprite = lookup.findVirtual(spriteService, "sprite",
                        MethodType.methodType(Optional.class, Material.class, clientVersion));
                component = lookup.findVirtual(spriteType, "asComponent",
                        MethodType.methodType(Component.class));
                ok = true;
                logger.info("CheeseCore found - dialog buttons will use atlas sprites.");
            } catch (ClassNotFoundException absent) {
                logger.info("CheeseCore not installed - dialog buttons stay text-only.");
            } catch (Throwable failure) {
                logger.log(Level.WARNING,
                        "CheeseCore is installed but its sprite API did not match; "
                                + "dialog buttons stay text-only", failure);
            }
        }

        this.isAvailable = available;
        this.sprites = service;
        this.versionOf = version;
        this.spriteFor = sprite;
        this.asComponent = component;
        this.linked = ok;
    }

    public boolean active() {
        return this.enabled && this.linked;
    }

    /**
     * The sprite for {@code material} as this viewer's client can draw it, or
     * {@code null} when there is none (no CheeseCore, an old client, or an item
     * with no flat texture).
     */
    public @Nullable Component icon(Material material, Player viewer) {
        if (!active()) {
            return null;
        }
        try {
            Object service = this.sprites.invoke();
            Object version = this.versionOf.invoke(service, viewer);
            Component cached = this.cache
                    .computeIfAbsent(version, ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(material, key -> resolve(service, version, key));
            return cached == NONE ? null : cached;
        } catch (Throwable failure) {
            unlink(failure);
            return null;
        }
    }

    /** Drops the cache; call when the config is reloaded. */
    public void clear() {
        this.cache.clear();
    }

    private Component resolve(Object service, Object version, Material material) {
        try {
            if (!(boolean) this.isAvailable.invoke()) {
                return NONE;
            }
            Object found = this.spriteFor.invoke(service, material, version);
            Object sprite = ((Optional<?>) found).orElse(null);
            if (sprite == null) {
                return NONE;
            }
            Component drawn = (Component) this.asComponent.invoke(sprite);
            return drawn == null ? NONE : drawn;
        } catch (Throwable failure) {
            unlink(failure);
            return NONE;
        }
    }

    /** One failure is enough - stop calling in and fall back to text. */
    private void unlink(Throwable failure) {
        if (this.linked) {
            this.linked = false;
            this.logger.log(Level.WARNING,
                    "CheeseCore sprite lookup failed; falling back to text labels", failure);
        }
    }
}
