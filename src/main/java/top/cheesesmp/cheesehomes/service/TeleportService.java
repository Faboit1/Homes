package top.cheesesmp.cheesehomes.service;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import top.cheesesmp.cheesehomes.config.CheeseConfig;
import top.cheesesmp.cheesehomes.config.Msg;
import top.cheesesmp.cheesehomes.model.Home;
import top.cheesesmp.cheesehomes.util.Text;

/**
 * Warm-up, cool-down and the actual teleport.
 *
 * <p>Everything that touches the player runs on that player's entity scheduler,
 * which is what makes this safe on Folia: the countdown ticks on whichever
 * region thread currently owns the player, and follows them if they cross a
 * region boundary.
 */
public final class TeleportService {

    private final Plugin plugin;
    private final Supplier<CheeseConfig> config;
    private final Msg msg;
    private final LimitResolver limits;

    private final Map<UUID, Warmup> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTeleport = new ConcurrentHashMap<>();

    public TeleportService(Plugin plugin, Supplier<CheeseConfig> config, Msg msg, LimitResolver limits) {
        this.plugin = plugin;
        this.config = config;
        this.msg = msg;
        this.limits = limits;
    }

    /** Entry point for both {@code /home <name>} and the dialog's Teleport button. */
    public void request(Player player, Home home) {
        World world = Bukkit.getWorld(home.world());
        if (world == null) {
            this.msg.send(player, "world-missing", Text.ph("world", home.world()));
            return;
        }

        UUID id = player.getUniqueId();
        double cooldown = this.limits.cooldownSeconds(player);
        if (cooldown > 0.0D) {
            Long last = this.lastTeleport.get(id);
            if (last != null) {
                double remaining = cooldown - (System.nanoTime() - last) / 1_000_000_000.0D;
                if (remaining > 0.0D) {
                    this.msg.send(player, "cooldown", Text.ph("time", Text.seconds(remaining)));
                    return;
                }
            }
        }

        double warmup = this.limits.warmupSeconds(player);
        if (warmup <= 0.0D) {
            teleport(player, home, world);
            return;
        }

        cancel(id, null);
        this.msg.send(player, "warmup-start", Text.ph("time", Text.seconds(warmup)));

        long deadline = System.nanoTime() + (long) (warmup * 1_000_000_000.0D);
        Warmup warmupState = new Warmup(home, player.getLocation(), deadline);
        this.pending.put(id, warmupState);

        // 4 ticks keeps the action bar smooth without being a per-tick task.
        warmupState.task = player.getScheduler().runAtFixedRate(this.plugin, task -> {
            Warmup current = this.pending.get(id);
            if (current != warmupState) {
                task.cancel();
                return;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                this.pending.remove(id, current);
                task.cancel();
                World target = Bukkit.getWorld(home.world());
                if (target == null) {
                    this.msg.send(player, "world-missing", Text.ph("world", home.world()));
                } else {
                    teleport(player, home, target);
                }
                return;
            }
            if (this.config.get().countdownActionBar) {
                int whole = (int) Math.ceil(remainingNanos / 1_000_000_000.0D);
                if (whole != current.lastShown) {
                    current.lastShown = whole;
                    this.msg.actionBar(player, "warmup-actionbar", Text.ph("time", Integer.toString(whole)));
                }
            }
        }, () -> this.pending.remove(id, warmupState), 4L, 4L);
    }

    private void teleport(Player player, Home home, World world) {
        Location destination = home.toLocation(world);
        if (destination == null) {
            return;
        }
        // teleportAsync loads the destination chunk for us and is the only
        // teleport that is legal across Folia regions.
        player.getScheduler().run(this.plugin, task ->
                player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
                        .whenComplete((success, failure) -> {
                            if (failure != null) {
                                this.plugin.getLogger().log(Level.WARNING,
                                        "Teleport to home '" + home.name() + "' threw", failure);
                                this.msg.send(player, "teleport-failed");
                                return;
                            }
                            if (Boolean.TRUE.equals(success)) {
                                this.lastTeleport.put(player.getUniqueId(), System.nanoTime());
                                this.msg.send(player, "teleported", Text.ph("name", home.name()));
                            } else {
                                // Refused by the server: dead, sleeping, riding
                                // something, or out of the world border.
                                this.msg.send(player, "teleport-refused");
                            }
                        }), null);
    }

    /** @param reasonKey message key to send, or {@code null} to cancel silently. */
    public void cancel(UUID id, String reasonKey) {
        Warmup warmup = this.pending.remove(id);
        if (warmup == null) {
            return;
        }
        if (warmup.task != null) {
            warmup.task.cancel();
        }
        if (reasonKey != null) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                this.msg.send(player, reasonKey);
            }
        }
    }

    public boolean isWarmingUp(UUID id) {
        return this.pending.containsKey(id);
    }

    /** Called from the move listener; cheap early-out when nothing is pending. */
    public void notifyMove(Player player, Location to) {
        if (this.pending.isEmpty() || !this.config.get().cancelOnMove) {
            return;
        }
        Warmup warmup = this.pending.get(player.getUniqueId());
        if (warmup == null) {
            return;
        }
        Location origin = warmup.origin;
        if (!origin.getWorld().equals(to.getWorld())
                || origin.distanceSquared(to) > this.config.get().cancelMoveDistanceSq) {
            cancel(player.getUniqueId(), "warmup-cancelled-move");
        }
    }

    public void notifyDamage(Player player) {
        if (this.pending.isEmpty() || !this.config.get().cancelOnDamage) {
            return;
        }
        cancel(player.getUniqueId(), "warmup-cancelled-damage");
    }

    public void forget(UUID id) {
        cancel(id, null);
        this.lastTeleport.remove(id);
    }

    public void clear() {
        for (UUID id : this.pending.keySet()) {
            cancel(id, null);
        }
        this.lastTeleport.clear();
    }

    private static final class Warmup {
        private final Home home;
        private final Location origin;
        private final long deadline;
        private volatile ScheduledTask task;
        private volatile int lastShown = -1;

        private Warmup(Home home, Location origin, long deadline) {
            this.home = home;
            this.origin = origin;
            this.deadline = deadline;
        }
    }
}
