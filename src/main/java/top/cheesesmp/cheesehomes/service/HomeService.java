package top.cheesesmp.cheesehomes.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import top.cheesesmp.cheesehomes.config.CheeseConfig;
import top.cheesesmp.cheesehomes.model.PlayerHomes;
import top.cheesesmp.cheesehomes.storage.HomeStorage;

/**
 * Read-through cache in front of {@link HomeStorage}.
 *
 * <p>Reads are served from memory so opening the dialog never touches disk.
 * Writes are write-behind: mutations only flip a dirty flag and a periodic
 * async task batches them into SQLite. Quitting players linger in the cache for
 * a configurable grace period so a relog does not cost a round trip.
 */
public final class HomeService {

    private final Plugin plugin;
    private final HomeStorage storage;
    private final Supplier<CheeseConfig> config;

    private final Map<UUID, CompletableFuture<PlayerHomes>> cache = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> online = ConcurrentHashMap.newKeySet();

    private io.papermc.paper.threadedregions.scheduler.ScheduledTask flusher;

    public HomeService(Plugin plugin, HomeStorage storage, Supplier<CheeseConfig> config) {
        this.plugin = plugin;
        this.storage = storage;
        this.config = config;
    }

    public void start() {
        long interval = Math.max(5L, this.config.get().flushIntervalSeconds);
        this.flusher = Bukkit.getAsyncScheduler().runAtFixedRate(this.plugin,
                task -> tick(), interval, interval, TimeUnit.SECONDS);
    }

    public void stop() {
        if (this.flusher != null) {
            this.flusher.cancel();
            this.flusher = null;
        }
        List<PlayerHomes> pending = new ArrayList<>();
        for (CompletableFuture<PlayerHomes> future : this.cache.values()) {
            PlayerHomes homes = future.getNow(null);
            if (homes != null && homes.consumeDirty()) {
                pending.add(homes);
            }
        }
        if (!pending.isEmpty()) {
            this.storage.saveNow(pending);
        }
        this.cache.clear();
        this.online.clear();
    }

    /** Loads (or returns the in-flight load for) a player's homes. */
    public CompletableFuture<PlayerHomes> load(UUID owner) {
        return this.cache.computeIfAbsent(owner, id -> this.storage.load(id)
                .exceptionally(failure -> {
                    this.plugin.getLogger().log(Level.SEVERE, "Could not load homes for " + id, failure);
                    // Drop the poisoned entry so the next attempt retries.
                    this.cache.remove(id);
                    return null;
                }));
    }

    /** Already-resident homes, or {@code null} when nothing is loaded yet. */
    public @Nullable PlayerHomes cached(UUID owner) {
        CompletableFuture<PlayerHomes> future = this.cache.get(owner);
        if (future == null) {
            return null;
        }
        PlayerHomes homes = future.getNow(null);
        if (homes != null) {
            homes.touch();
        }
        return homes;
    }

    public void markOnline(UUID owner) {
        this.online.add(owner);
    }

    public void markOffline(UUID owner) {
        this.online.remove(owner);
        PlayerHomes homes = cached(owner);
        if (homes != null) {
            homes.touch();
            if (homes.consumeDirty()) {
                this.storage.save(homes).exceptionally(failure -> {
                    this.plugin.getLogger().log(Level.SEVERE, "Could not save homes for " + owner, failure);
                    homes.markDirty();
                    return null;
                });
            }
        }
    }

    /** Flushes dirty entries and evicts cold offline players. */
    private void tick() {
        long linger = TimeUnit.SECONDS.toNanos(this.config.get().cacheLingerSeconds);
        long now = System.nanoTime();

        for (Map.Entry<UUID, CompletableFuture<PlayerHomes>> entry : this.cache.entrySet()) {
            PlayerHomes homes = entry.getValue().getNow(null);
            if (homes == null) {
                continue;
            }
            boolean dirty = homes.consumeDirty();
            if (dirty) {
                this.storage.save(homes).exceptionally(failure -> {
                    this.plugin.getLogger().log(Level.SEVERE,
                            "Could not save homes for " + entry.getKey(), failure);
                    homes.markDirty();
                    return null;
                });
            }
            if (!dirty && !this.online.contains(entry.getKey()) && now - homes.lastTouched() > linger) {
                this.cache.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Writes out every dirty entry, blocking until storage has them. */
    public void flushDirtyNow() {
        List<PlayerHomes> pending = new ArrayList<>();
        for (CompletableFuture<PlayerHomes> future : this.cache.values()) {
            PlayerHomes homes = future.getNow(null);
            if (homes != null && homes.consumeDirty()) {
                pending.add(homes);
            }
        }
        if (!pending.isEmpty()) {
            this.storage.saveNow(pending);
        }
    }

    /** Drops every cached entry so the next read comes from storage again. */
    public void evictAll() {
        this.cache.clear();
    }

    public int cachedPlayers() {
        return this.cache.size();
    }

    public HomeStorage storage() {
        return this.storage;
    }
}
