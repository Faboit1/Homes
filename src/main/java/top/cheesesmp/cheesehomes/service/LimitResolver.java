package top.cheesesmp.cheesehomes.service;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import top.cheesesmp.cheesehomes.config.CheeseConfig;

/**
 * Turns permissions into numbers.
 *
 * <ul>
 *   <li>{@code cheesehomes.maxhomes.<amount>} - highest match wins.</li>
 *   <li>{@code cheesehomes.cooldown.<seconds>} - lowest match wins.</li>
 *   <li>{@code cheesehomes.warmup.<seconds>} - lowest match wins.</li>
 * </ul>
 *
 * <p>Resolving means walking every effective permission, which is not something
 * we want to do once per dialog button, so results are memoised for a few
 * seconds and dropped when the player logs out or a permission plugin tells us
 * to recalculate.
 */
public final class LimitResolver {

    private static final long TTL_NANOS = 3_000_000_000L;

    private final Supplier<CheeseConfig> config;
    private final Map<UUID, Snapshot> cache = new ConcurrentHashMap<>();

    public LimitResolver(Supplier<CheeseConfig> config) {
        this.config = config;
    }

    public int maxHomes(Player player) {
        return snapshot(player).maxHomes;
    }

    public double cooldownSeconds(Player player) {
        return snapshot(player).cooldown;
    }

    public double warmupSeconds(Player player) {
        return snapshot(player).warmup;
    }

    public void invalidate(UUID player) {
        this.cache.remove(player);
    }

    public void invalidateAll() {
        this.cache.clear();
    }

    private Snapshot snapshot(Player player) {
        long now = System.nanoTime();
        Snapshot cached = this.cache.get(player.getUniqueId());
        if (cached != null && now - cached.stamp < TTL_NANOS) {
            return cached;
        }
        Snapshot fresh = compute(player);
        this.cache.put(player.getUniqueId(), fresh);
        return fresh;
    }

    private Snapshot compute(Player player) {
        CheeseConfig cfg = this.config.get();

        int limit = cfg.defaultLimit;
        double cooldown = cfg.cooldownSeconds;
        double warmup = cfg.warmupSeconds;

        boolean unlimited = !cfg.unlimitedPermission.isEmpty() && player.hasPermission(cfg.unlimitedPermission);
        boolean noCooldown = !cfg.cooldownBypass.isEmpty() && player.hasPermission(cfg.cooldownBypass);
        boolean noWarmup = !cfg.warmupBypass.isEmpty() && player.hasPermission(cfg.warmupBypass);

        // One pass over the effective permissions covers all three prefixes.
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String node = info.getPermission().toLowerCase(Locale.ROOT);

            if (!cfg.limitPrefix.isEmpty() && node.startsWith(cfg.limitPrefix)) {
                int amount = parseInt(node.substring(cfg.limitPrefix.length()));
                if (amount > limit) {
                    limit = amount;
                }
            }
            if (!cfg.cooldownPrefix.isEmpty() && node.startsWith(cfg.cooldownPrefix)) {
                double value = parseDouble(node.substring(cfg.cooldownPrefix.length()));
                if (value >= 0.0D && value < cooldown) {
                    cooldown = value;
                }
            }
            if (!cfg.warmupPrefix.isEmpty() && node.startsWith(cfg.warmupPrefix)) {
                double value = parseDouble(node.substring(cfg.warmupPrefix.length()));
                if (value >= 0.0D && value < warmup) {
                    warmup = value;
                }
            }
            Integer granted = cfg.limitGrants.get(node);
            if (granted != null && granted > limit) {
                limit = granted;
            }
        }

        if (unlimited) {
            limit = cfg.hardCap;
        } else if (cfg.probeLimitPermissions && limit < cfg.hardCap && !cfg.limitPrefix.isEmpty()) {
            // Not every permission plugin puts wildcard-backed nodes into the
            // effective set, and a node that only exists in permissions.yml is
            // not there either. Ask directly, highest first, and stop at the
            // first hit - hasPermission is a map lookup and this whole result
            // is memoised for a few seconds.
            for (int amount = cfg.hardCap; amount > limit; amount--) {
                if (player.hasPermission(cfg.limitPrefix + amount)) {
                    limit = amount;
                    break;
                }
            }
        }
        limit = Math.max(0, Math.min(limit, cfg.hardCap));

        return new Snapshot(System.nanoTime(), limit,
                noCooldown ? 0.0D : cooldown,
                noWarmup ? 0.0D : warmup);
    }

    /** {@code -1} for anything that is not a plain non-negative integer. */
    private static int parseInt(String raw) {
        if (raw.isEmpty() || raw.length() > 9) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }

    /** Accepts {@code 5} and {@code 2_5} (a dot is not legal in a permission node). */
    private static double parseDouble(String raw) {
        if (raw.isEmpty()) {
            return -1.0D;
        }
        try {
            return Double.parseDouble(raw.replace('_', '.'));
        } catch (NumberFormatException ignored) {
            return -1.0D;
        }
    }

    private record Snapshot(long stamp, int maxHomes, double cooldown, double warmup) {
    }
}
