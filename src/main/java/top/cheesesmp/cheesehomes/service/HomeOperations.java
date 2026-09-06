package top.cheesesmp.cheesehomes.service;

import java.util.Locale;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import top.cheesesmp.cheesehomes.config.CheeseConfig;
import top.cheesesmp.cheesehomes.config.Msg;
import top.cheesesmp.cheesehomes.model.Home;
import top.cheesesmp.cheesehomes.model.PlayerHomes;
import top.cheesesmp.cheesehomes.util.Text;

/**
 * The rules around creating, renaming and deleting a home, in one place so the
 * dialogs and the commands can never drift apart.
 */
public final class HomeOperations {

    public enum Status {
        OK,
        UPDATED,
        LIMIT,
        NAME_TAKEN,
        NAME_INVALID,
        NAME_TOO_LONG,
        NAME_EMPTY,
        WORLD_BLACKLISTED,
        WORLD_NO_PERMISSION,
        NOT_FOUND,
        UNCHANGED
    }

    public record Result(Status status, @Nullable Home home) {
        public boolean ok() {
            return this.status == Status.OK || this.status == Status.UPDATED;
        }
    }

    private final Supplier<CheeseConfig> config;
    private final Msg msg;
    private final LimitResolver limits;

    public HomeOperations(Supplier<CheeseConfig> config, Msg msg, LimitResolver limits) {
        this.config = config;
        this.msg = msg;
        this.limits = limits;
    }

    /** Creates a home in a specific slot; used by the dialog's empty buttons. */
    public Result createInSlot(Player player, PlayerHomes homes, int slot) {
        CheeseConfig cfg = this.config.get();
        Location location = player.getLocation();

        Status world = checkWorld(player, location);
        if (world != Status.OK) {
            return new Result(world, null);
        }
        if (slot < 0 || slot >= this.limits.maxHomes(player)) {
            return new Result(Status.LIMIT, null);
        }
        if (homes.bySlot(slot) != null) {
            return new Result(Status.NAME_TAKEN, null);
        }

        String name = uniqueName(homes, cfg.defaultNameFormat.replace("<index>", Integer.toString(slot + 1)));
        Home home = Home.of(slot, name, location, cfg.defaultIcon);
        homes.add(home);
        return new Result(Status.OK, home);
    }

    /** {@code /sethome [name]} - updates the home when the name already exists. */
    public Result setOrUpdate(Player player, PlayerHomes homes, @Nullable String requestedName) {
        CheeseConfig cfg = this.config.get();
        Location location = player.getLocation();

        Status world = checkWorld(player, location);
        if (world != Status.OK) {
            return new Result(world, null);
        }

        if (requestedName != null) {
            Status nameCheck = checkName(requestedName);
            if (nameCheck != Status.OK) {
                return new Result(nameCheck, null);
            }
            Home existing = homes.byName(requestedName);
            if (existing != null) {
                existing.moveTo(location);
                homes.markDirty();
                return new Result(Status.UPDATED, existing);
            }
        }

        int limit = this.limits.maxHomes(player);
        int slot = homes.firstFreeSlot(limit);
        if (slot < 0) {
            return new Result(Status.LIMIT, null);
        }
        String name = requestedName != null
                ? requestedName
                : uniqueName(homes, cfg.defaultNameFormat.replace("<index>", Integer.toString(slot + 1)));
        Home home = Home.of(slot, name, location, cfg.defaultIcon);
        homes.add(home);
        return new Result(Status.OK, home);
    }

    public Result rename(PlayerHomes homes, Home home, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        Status check = checkName(name);
        if (check != Status.OK) {
            return new Result(check, home);
        }
        if (name.equals(home.name())) {
            return new Result(Status.UNCHANGED, home);
        }
        Home clash = homes.byName(name);
        if (clash != null && clash != home) {
            return new Result(Status.NAME_TAKEN, home);
        }
        homes.rename(home, name);
        return new Result(Status.OK, home);
    }

    public void delete(PlayerHomes homes, Home home) {
        homes.remove(home);
    }

    public void setIcon(PlayerHomes homes, Home home, Material icon) {
        home.icon(icon);
        homes.markDirty();
    }

    /** Sends the message that matches a result. */
    public void report(Player player, Result result) {
        CheeseConfig cfg = this.config.get();
        String name = result.home() == null ? "" : result.home().name();
        switch (result.status()) {
            case OK -> this.msg.send(player, "home-set", Text.ph("name", name));
            case UPDATED -> this.msg.send(player, "home-updated", Text.ph("name", name));
            case LIMIT -> this.msg.send(player, "limit-reached",
                    Text.ph("limit", this.limits.maxHomes(player)));
            case NAME_TAKEN -> this.msg.send(player, "name-taken", Text.ph("name", name));
            case NAME_INVALID -> this.msg.send(player, "name-invalid");
            case NAME_TOO_LONG -> this.msg.send(player, "name-too-long",
                    Text.ph("max", cfg.maxNameLength));
            case NAME_EMPTY -> this.msg.send(player, "name-empty");
            case WORLD_BLACKLISTED -> this.msg.send(player, "world-blacklisted");
            case WORLD_NO_PERMISSION -> this.msg.send(player, "world-no-permission");
            case NOT_FOUND -> this.msg.send(player, "home-not-found", Text.ph("name", name));
            case UNCHANGED -> {
                // Nothing changed, nothing worth saying.
            }
        }
    }

    public Status checkName(String name) {
        CheeseConfig cfg = this.config.get();
        if (name == null || name.isBlank()) {
            return Status.NAME_EMPTY;
        }
        if (name.length() > cfg.maxNameLength) {
            return Status.NAME_TOO_LONG;
        }
        if (!cfg.namePattern.matcher(name).matches()) {
            return Status.NAME_INVALID;
        }
        return Status.OK;
    }

    private Status checkWorld(Player player, Location location) {
        CheeseConfig cfg = this.config.get();
        String world = location.getWorld().getName().toLowerCase(Locale.ROOT);
        if (cfg.worldBlacklist.contains(world)) {
            return Status.WORLD_BLACKLISTED;
        }
        if (cfg.requireWorldPermission && !player.hasPermission(cfg.worldPermissionPrefix + world)) {
            return Status.WORLD_NO_PERMISSION;
        }
        return Status.OK;
    }

    private String uniqueName(PlayerHomes homes, String base) {
        CheeseConfig cfg = this.config.get();
        String candidate = base.length() > cfg.maxNameLength
                ? base.substring(0, cfg.maxNameLength)
                : base;
        if (homes.byName(candidate) == null) {
            return candidate;
        }
        for (int suffix = 2; suffix < 10_000; suffix++) {
            String tail = " " + suffix;
            String trimmed = candidate.length() + tail.length() > cfg.maxNameLength
                    ? candidate.substring(0, cfg.maxNameLength - tail.length())
                    : candidate;
            String next = trimmed + tail;
            if (homes.byName(next) == null) {
                return next;
            }
        }
        return candidate + " " + System.currentTimeMillis();
    }
}
