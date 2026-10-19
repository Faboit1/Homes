package top.cheesesmp.cheesehomes.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import top.cheesesmp.cheesehomes.config.Msg;
import top.cheesesmp.cheesehomes.model.Home;
import top.cheesesmp.cheesehomes.model.PlayerHomes;
import top.cheesesmp.cheesehomes.service.HomeOperations;
import top.cheesesmp.cheesehomes.service.HomeService;
import top.cheesesmp.cheesehomes.service.LimitResolver;
import top.cheesesmp.cheesehomes.service.TeleportService;
import top.cheesesmp.cheesehomes.ui.HomeDialogs;
import top.cheesesmp.cheesehomes.util.Text;

/**
 * {@code /homeadmin} - look at, teleport to and edit anybody's homes, online or
 * not.
 *
 * <p>Two permissions split it: {@code cheesehomes.admin.see} covers everything
 * that only reads ({@code see}, {@code list}, {@code tp}, {@code limits}) and
 * {@code cheesehomes.admin.edit} everything that writes, including the edit
 * buttons in the admin menu. Every write is logged to the console.
 *
 * <p>Home names may contain spaces. The last argument of a command is taken
 * whole, so {@code /homeadmin tp Steve My Base} works; {@code rename} has two
 * names and needs the old one in quotes when it has a space in it.
 */
public final class HomeAdminCommand implements BasicCommand {

    public static final String PERM_SEE = "cheesehomes.admin.see";
    public static final String PERM_EDIT = "cheesehomes.admin.edit";

    private static final List<String> SUBCOMMANDS =
            List.of("see", "list", "tp", "set", "delete", "rename", "clear", "limits");

    private final Plugin plugin;
    private final Msg msg;
    private final HomeService homes;
    private final HomeOperations operations;
    private final LimitResolver limits;
    private final TeleportService teleports;
    private final HomeDialogs dialogs;

    public HomeAdminCommand(Plugin plugin, Msg msg, HomeService homes, HomeOperations operations,
                            LimitResolver limits, TeleportService teleports, HomeDialogs dialogs) {
        this.plugin = plugin;
        this.msg = msg;
        this.homes = homes;
        this.operations = operations;
        this.limits = limits;
        this.teleports = teleports;
        this.dialogs = dialogs;
    }

    /** Whose homes a command is about. */
    private record Owner(UUID id, String name) {
    }

    @Override
    public void execute(CommandSourceStack source, String[] rawArgs) {
        CommandSender sender = source.getSender();
        List<String> args = tokens(rawArgs);
        if (args.size() < 2) {
            this.msg.send(sender, "admin-usage");
            return;
        }
        String sub = args.get(0).toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(sub)) {
            this.msg.send(sender, "admin-usage");
            return;
        }
        boolean writes = sub.equals("set") || sub.equals("delete") || sub.equals("rename") || sub.equals("clear");
        if (writes && !sender.hasPermission(PERM_EDIT)) {
            this.msg.send(sender, "no-permission");
            return;
        }
        boolean needsPlayer = sub.equals("see") || sub.equals("tp") || sub.equals("set");
        if (needsPlayer && !(sender instanceof Player)) {
            this.msg.send(sender, "players-only");
            return;
        }

        Owner owner = resolve(args.get(1));
        if (owner == null) {
            this.msg.send(sender, "admin-unknown-player", Text.ph("player", args.get(1)));
            return;
        }
        String rest = String.join(" ", args.subList(2, args.size()));

        switch (sub) {
            case "see" -> {
                Player viewer = (Player) sender;
                this.dialogs.openList(HomeDialogs.Target.admin(viewer, owner.id(), owner.name(),
                        viewer.hasPermission(PERM_EDIT)), 0);
            }
            case "list" -> withOwnerHomes(sender, owner, owned -> list(sender, owner, owned));
            case "limits" -> withOwnerHomes(sender, owner, owned -> {
                Player online = Bukkit.getPlayer(owner.id());
                this.msg.send(sender, "admin-limits",
                        Text.ph("player", owner.name()),
                        Text.ph("count", owned.size()),
                        Text.ph("limit", online == null ? "?" : Integer.toString(this.limits.maxHomes(online))));
            });
            case "tp" -> withHome(sender, owner, rest, (owned, home) -> {
                this.msg.send(sender, "admin-teleporting",
                        Text.ph("player", owner.name()), Text.ph("name", home.name()));
                this.teleports.teleportNow((Player) sender, home);
            });
            case "set" -> {
                if (rest.isEmpty()) {
                    this.msg.send(sender, "admin-usage");
                    return;
                }
                Player admin = (Player) sender;
                withOwnerHomes(sender, owner, owned -> {
                    HomeOperations.Result result = this.operations.forceSet(owned, rest, admin.getLocation());
                    switch (result.status()) {
                        case OK -> {
                            this.msg.send(sender, "admin-home-set",
                                    Text.ph("player", owner.name()), Text.ph("name", result.home().name()));
                            audit(sender, owner, "set home '" + result.home().name() + "' at their own position");
                        }
                        case UPDATED -> {
                            this.msg.send(sender, "admin-home-moved",
                                    Text.ph("player", owner.name()), Text.ph("name", result.home().name()));
                            audit(sender, owner, "moved home '" + result.home().name() + "' to their own position");
                        }
                        case LIMIT -> this.msg.send(sender, "admin-full", Text.ph("player", owner.name()));
                        default -> this.operations.report(admin, result);
                    }
                });
            }
            case "delete" -> withHome(sender, owner, rest, (owned, home) -> {
                this.operations.delete(owned, home);
                this.msg.send(sender, "admin-home-deleted",
                        Text.ph("player", owner.name()), Text.ph("name", home.name()));
                audit(sender, owner, "deleted home '" + home.name() + "'");
            });
            case "rename" -> {
                if (args.size() < 4) {
                    this.msg.send(sender, "admin-usage");
                    return;
                }
                String oldName = args.get(2);
                String newName = String.join(" ", args.subList(3, args.size()));
                withHome(sender, owner, oldName, (owned, home) -> {
                    String before = home.name();
                    HomeOperations.Result result = this.operations.rename(owned, home, newName);
                    if (result.status() == HomeOperations.Status.OK) {
                        this.msg.send(sender, "admin-home-renamed",
                                Text.ph("player", owner.name()), Text.ph("name", home.name()));
                        audit(sender, owner, "renamed home '" + before + "' to '" + home.name() + "'");
                    } else if (sender instanceof Player admin) {
                        this.operations.report(admin, result);
                    } else {
                        sender.sendMessage(Text.mm("<red>Rename refused: <white>" + result.status()));
                    }
                });
            }
            case "clear" -> withOwnerHomes(sender, owner, owned -> {
                if (owned.isEmpty()) {
                    this.msg.send(sender, "admin-no-homes", Text.ph("player", owner.name()));
                    return;
                }
                if (!rest.equalsIgnoreCase("confirm")) {
                    this.msg.send(sender, "admin-clear-confirm",
                            Text.ph("player", owner.name()), Text.ph("count", owned.size()));
                    return;
                }
                int removed = this.operations.clear(owned);
                this.msg.send(sender, "admin-cleared",
                        Text.ph("player", owner.name()), Text.ph("count", removed));
                audit(sender, owner, "deleted all " + removed + " homes");
            });
            default -> this.msg.send(sender, "admin-usage");
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] rawArgs) {
        // Brigadier hands over a trailing empty string as no argument at all,
        // so a finished word followed by a space means "suggest the next one".
        List<String> args = new ArrayList<>(List.of(rawArgs));
        if (args.isEmpty()) {
            args.add("");
        }
        if (args.size() == 1) {
            return startingWith(SUBCOMMANDS, args.get(0));
        }
        if (args.size() == 2) {
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                names.add(online.getName());
            }
            return startingWith(names, args.get(1));
        }
        String sub = args.get(0).toLowerCase(Locale.ROOT);
        if (sub.equals("clear")) {
            return args.size() == 3 ? startingWith(List.of("confirm"), args.get(2)) : List.of();
        }
        if (!sub.equals("tp") && !sub.equals("delete") && !sub.equals("rename") && !sub.equals("set")) {
            return List.of();
        }
        if (sub.equals("rename") && args.size() > 3) {
            return List.of();
        }
        Owner owner = resolve(args.get(1));
        if (owner == null) {
            return List.of();
        }
        PlayerHomes owned = this.homes.cached(owner.id());
        if (owned == null) {
            // Never block the netty thread; the next tab press will have them.
            this.homes.load(owner.id());
            return List.of();
        }
        String prefix = String.join(" ", args.subList(2, args.size())).toLowerCase(Locale.ROOT);
        if (prefix.startsWith("\"")) {
            prefix = prefix.substring(1);
        }
        List<String> out = new ArrayList<>();
        for (Home home : owned.ordered()) {
            String name = home.name();
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(name.indexOf(' ') >= 0 ? '"' + name + '"' : name);
            }
        }
        return out;
    }

    @Override
    public String permission() {
        return PERM_SEE;
    }

    // -----------------------------------------------------------------------

    private void list(CommandSender sender, Owner owner, PlayerHomes owned) {
        if (owned.isEmpty()) {
            this.msg.send(sender, "admin-no-homes", Text.ph("player", owner.name()));
            return;
        }
        this.msg.send(sender, "admin-list-header",
                Text.ph("player", owner.name()), Text.ph("count", owned.size()));
        for (Home home : owned.ordered()) {
            var placeholders = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]{
                    Text.ph("player", owner.name()),
                    Text.ph("name", home.name()),
                    Text.ph("index", home.slot() + 1),
                    Text.ph("world", home.world()),
                    Text.ph("x", Long.toString(Math.round(home.x()))),
                    Text.ph("y", Long.toString(Math.round(home.y()))),
                    Text.ph("z", Long.toString(Math.round(home.z()))),
                    Text.ph("description", home.description() == null ? "" : home.description())
            };
            Component line = this.msg.bare("admin-list-entry", placeholders);
            if (sender instanceof Player) {
                String quoted = home.name().indexOf(' ') >= 0 ? '"' + home.name() + '"' : home.name();
                line = line.clickEvent(ClickEvent.runCommand("/homeadmin tp " + owner.name() + " " + quoted))
                        .hoverEvent(HoverEvent.showText(this.msg.bare("admin-list-hover", placeholders)));
            }
            sender.sendMessage(line);
        }
    }

    /**
     * Online players by exact name first, then anyone the server has seen
     * before (never a blocking profile lookup), then a raw UUID.
     */
    private @Nullable Owner resolve(String input) {
        String name = unquote(input);
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return new Owner(online.getUniqueId(), online.getName());
        }
        OfflinePlayer known = Bukkit.getOfflinePlayerIfCached(name);
        if (known != null) {
            return new Owner(known.getUniqueId(), known.getName() == null ? name : known.getName());
        }
        try {
            UUID id = UUID.fromString(name);
            OfflinePlayer byId = Bukkit.getOfflinePlayer(id);
            return new Owner(id, byId.getName() == null ? id.toString() : byId.getName());
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }

    /**
     * Runs {@code action} with the owner's homes - on the admin's own scheduler
     * when the admin is a player, otherwise on the global region.
     */
    private void withOwnerHomes(CommandSender sender, Owner owner, Consumer<PlayerHomes> action) {
        PlayerHomes cached = this.homes.cached(owner.id());
        if (cached != null) {
            action.accept(cached);
            return;
        }
        this.homes.load(owner.id()).thenAccept(loaded -> {
            if (loaded == null) {
                this.msg.send(sender, "storage-error");
                return;
            }
            if (sender instanceof Player player) {
                player.getScheduler().run(this.plugin, task -> {
                    if (player.isOnline()) {
                        action.accept(loaded);
                    }
                }, null);
            } else {
                Bukkit.getGlobalRegionScheduler().execute(this.plugin, () -> action.accept(loaded));
            }
        });
    }

    private void withHome(CommandSender sender, Owner owner, String name,
                          java.util.function.BiConsumer<PlayerHomes, Home> action) {
        if (name.isEmpty()) {
            this.msg.send(sender, "admin-usage");
            return;
        }
        withOwnerHomes(sender, owner, owned -> {
            Home home = owned.byName(name);
            if (home == null) {
                this.msg.send(sender, "admin-home-not-found",
                        Text.ph("player", owner.name()), Text.ph("name", name));
                return;
            }
            action.accept(owned, home);
        });
    }

    private void audit(CommandSender sender, Owner owner, String what) {
        this.plugin.getLogger().log(Level.INFO, "[admin] " + sender.getName() + " " + what
                + " for " + owner.name() + " (" + owner.id() + ")");
    }

    /** Splits on spaces but keeps a {@code "quoted name"} together. */
    static List<String> tokens(String[] args) {
        String joined = String.join(" ", args).trim();
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < joined.length(); i++) {
            char c = joined.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (c == ' ' && !quoted) {
                if (any) {
                    out.add(current.toString());
                    current.setLength(0);
                    any = false;
                }
            } else {
                current.append(c);
                any = true;
            }
        }
        if (any) {
            out.add(current.toString());
        }
        return out;
    }

    private static String unquote(String raw) {
        if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static List<String> startingWith(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(options.size());
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(option);
            }
        }
        return out;
    }
}
