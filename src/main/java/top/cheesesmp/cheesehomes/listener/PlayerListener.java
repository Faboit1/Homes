package top.cheesesmp.cheesehomes.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import top.cheesesmp.cheesehomes.service.HomeService;
import top.cheesesmp.cheesehomes.service.LimitResolver;
import top.cheesesmp.cheesehomes.service.TeleportService;

public final class PlayerListener implements Listener {

    private final HomeService homes;
    private final TeleportService teleports;
    private final LimitResolver limits;

    public PlayerListener(HomeService homes, TeleportService teleports, LimitResolver limits) {
        this.homes = homes;
        this.teleports = teleports;
        this.limits = limits;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        this.homes.markOnline(event.getPlayer().getUniqueId());
        this.limits.invalidate(event.getPlayer().getUniqueId());
        // Warm the cache so the first /homes never waits on disk.
        this.homes.load(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.homes.markOffline(event.getPlayer().getUniqueId());
        this.teleports.forget(event.getPlayer().getUniqueId());
        this.limits.invalidate(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // hasChangedPosition() keeps looking around from cancelling a warm-up.
        if (event.hasChangedPosition()) {
            this.teleports.notifyMove(event.getPlayer(), event.getTo());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            this.teleports.notifyDamage(player);
        }
    }
}
