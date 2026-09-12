package top.cheesesmp.cheesehomes.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import java.io.File;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import top.cheesesmp.cheesehomes.config.CheeseConfig;
import top.cheesesmp.cheesehomes.config.Msg;
import top.cheesesmp.cheesehomes.model.Home;
import top.cheesesmp.cheesehomes.model.PlayerHomes;
import top.cheesesmp.cheesehomes.service.HomeOperations;
import top.cheesesmp.cheesehomes.service.HomeService;
import top.cheesesmp.cheesehomes.service.LimitResolver;
import top.cheesesmp.cheesehomes.service.TeleportService;
import top.cheesesmp.cheesehomes.storage.ModernHomeImporter;
import top.cheesesmp.cheesehomes.ui.HomeDialogs;
import top.cheesesmp.cheesehomes.util.Text;

/** {@code /homes}, {@code /home}, {@code /sethome}, {@code /delhome}, {@code /cheesehomes}. */
public final class CheeseCommands {

    public static final String PERM_USE = "cheesehomes.use";
    public static final String PERM_SET = "cheesehomes.set";
    public static final String PERM_DELETE = "cheesehomes.delete";
    public static final String PERM_ADMIN = "cheesehomes.admin";
    public static final String PERM_COORDINATES = "cheesehomes.coordinates";

    private final Plugin plugin;
    private final Supplier<CheeseConfig> config;
    private final Msg msg;
    private final HomeService homes;
    private final HomeOperations operations;
    private final LimitResolver limits;
    private final TeleportService teleports;
    private final HomeDialogs dialogs;
    private final Runnable reload;

    public CheeseCommands(Plugin plugin, Supplier<CheeseConfig> config, Msg msg, HomeService homes,
                          HomeOperations operations, LimitResolver limits, TeleportService teleports,
                          HomeDialogs dialogs, Runnable reload) {
        this.plugin = plugin;
        this.config = config;
        this.msg = msg;
        this.homes = homes;
        this.operations = operations;
        this.limits = limits;
        this.teleports = teleports;
        this.dialogs = dialogs;
        this.reload = reload;
    }

    public BasicCommand homesCommand() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                Player player = requirePlayer(source);
                if (player != null) {
                    CheeseCommands.this.dialogs.openList(player, 0);
                }
            }

            @Override
            public String permission() {
                return PERM_USE;
            }
        };
    }

    public BasicCommand homeCommand() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                Player player = requirePlayer(source);
                if (player == null) {
                    return;
                }
                if (args.length == 0) {
                    CheeseCommands.this.dialogs.openList(player, 0);
                    return;
                }
                String name = joinArgs(args);
                withHomes(player, owned -> {
                    Home home = owned.byName(name);
                    if (home == null) {
                        CheeseCommands.this.msg.send(player, "home-not-found", Text.ph("name", name));
                        return;
                    }
                    CheeseCommands.this.teleports.request(player, home);
                });
            }

            @Override
            public Collection<String> suggest(CommandSourceStack source, String[] args) {
                return suggestHomeNames(source, args);
            }

            @Override
            public String permission() {
                return PERM_USE;
            }
        };
    }

    public BasicCommand setHomeCommand() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                Player player = requirePlayer(source);
                if (player == null) {
                    return;
                }
                String name = args.length == 0 ? null : joinArgs(args);
                withHomes(player, owned -> {
                    HomeOperations.Result result = CheeseCommands.this.operations
                            .setOrUpdate(player, owned, name);
                    CheeseCommands.this.operations.report(player, result);
                });
            }

            @Override
            public Collection<String> suggest(CommandSourceStack source, String[] args) {
                return suggestHomeNames(source, args);
            }

            @Override
            public String permission() {
                return PERM_SET;
            }
        };
    }

    public BasicCommand delHomeCommand() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                Player player = requirePlayer(source);
                if (player == null) {
                    return;
                }
                if (args.length == 0) {
                    CheeseCommands.this.msg.send(player, "name-empty");
                    return;
                }
                String name = joinArgs(args);
                withHomes(player, owned -> {
                    Home home = owned.byName(name);
                    if (home == null) {
                        CheeseCommands.this.msg.send(player, "home-not-found", Text.ph("name", name));
                        return;
                    }
                    CheeseCommands.this.operations.delete(owned, home);
                    CheeseCommands.this.msg.send(player, "home-deleted", Text.ph("name", home.name()));
                });
            }

            @Override
            public Collection<String> suggest(CommandSourceStack source, String[] args) {
                return suggestHomeNames(source, args);
            }

            @Override
            public String permission() {
                return PERM_DELETE;
            }
        };
    }

    /**
     * {@code /showhomecoordinates [true|false]} - a per-player switch for whether
     * the menu prints coordinates, so a streamer can open their homes on camera
     * without handing out their base.
     */
    public BasicCommand showCoordinatesCommand() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                Player player = requirePlayer(source);
                if (player == null) {
                    return;
                }
                Boolean requested;
                if (args.length == 0) {
                    requested = null;
                } else if (args[0].equalsIgnoreCase("true") || args[0].equalsIgnoreCase("on")
                        || args[0].equalsIgnoreCase("show")) {
                    requested = Boolean.TRUE;
                } else if (args[0].equalsIgnoreCase("false") || args[0].equalsIgnoreCase("off")
                        || args[0].equalsIgnoreCase("hide")) {
                    requested = Boolean.FALSE;
                } else {
                    CheeseCommands.this.msg.send(player, "coordinates-usage");
                    return;
                }

                withHomes(player, owned -> {
                    CheeseConfig cfg = CheeseCommands.this.config.get();
                    Boolean current = owned.showCoordinates();
                    boolean effective = current == null ? cfg.showCoordinatesDefault : current;
                    // No argument flips it, which is what people actually type.
                    boolean next = requested == null ? !effective : requested;
                    owned.setShowCoordinates(next);
                    CheeseCommands.this.msg.send(player,
                            next ? "coordinates-shown" : "coordinates-hidden");
                });
            }

            @Override
            public Collection<String> suggest(CommandSourceStack source, String[] args) {
                if (args.length <= 1) {
                    return startingWith(List.of("true", "false"), args.length == 0 ? "" : args[0]);
                }
                return List.of();
            }

            @Override
            public String permission() {
                return PERM_COORDINATES;
            }
        };
    }

    public BasicCommand adminCommand() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                CommandSender sender = source.getSender();
                String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
                switch (sub) {
                    case "reload" -> {
                        CheeseCommands.this.reload.run();
                        CheeseCommands.this.msg.send(sender, "reloaded");
                    }
                    case "limits" -> {
                        if (sender instanceof Player player) {
                            sender.sendMessage(Text.mm(
                                    "<gray>Slots: <white><slots> <dark_gray>| <gray>warm-up: <white><warmup>s "
                                            + "<dark_gray>| <gray>cooldown: <white><cooldown>s",
                                    Text.ph("slots", CheeseCommands.this.limits.maxHomes(player)),
                                    Text.ph("warmup", Text.seconds(CheeseCommands.this.limits.warmupSeconds(player))),
                                    Text.ph("cooldown", Text.seconds(CheeseCommands.this.limits.cooldownSeconds(player)))));
                        } else {
                            CheeseCommands.this.msg.send(sender, "players-only");
                        }
                    }
                    case "info" -> sender.sendMessage(Text.mm(
                            "<color:#FFD24A>Cheese<white>Homes <gray>v<white><version> "
                                    + "<dark_gray>| <gray>cached players: <white><cached> "
                                    + "<dark_gray>| <gray>stored homes: <white><stored>",
                            Text.ph("version", CheeseCommands.this.plugin.getPluginMeta().getVersion()),
                            Text.ph("cached", CheeseCommands.this.homes.cachedPlayers()),
                            Text.ph("stored", CheeseCommands.this.homes.storage().countHomes())));
                    case "import" -> runImport(sender, args);
                    default -> sender.sendMessage(Text.mm("<gray>Usage: <white>/cheesehomes <reload|info|limits|import>"));
                }
            }

            @Override
            public Collection<String> suggest(CommandSourceStack source, String[] args) {
                if (args.length <= 1) {
                    return startingWith(List.of("reload", "info", "limits", "import"),
                            args.length == 0 ? "" : args[0]);
                }
                if (args.length == 2 && args[0].equalsIgnoreCase("import")) {
                    return startingWith(List.of("modernhome"), args[1]);
                }
                if (args.length == 3 && args[0].equalsIgnoreCase("import")) {
                    return startingWith(List.of("overwrite"), args[2]);
                }
                return List.of();
            }

            @Override
            public String permission() {
                return PERM_ADMIN;
            }
        };
    }

    // -----------------------------------------------------------------------

    private static List<String> startingWith(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(options.size());
        for (String option : options) {
            if (option.startsWith(prefix)) {
                out.add(option);
            }
        }
        return out;
    }

    /**
     * {@code /cheesehomes import modernhome [overwrite]}. Reading a few thousand
     * rows and writing them back is far too much for a server thread, so the
     * whole thing runs on the async scheduler and reports back when it lands.
     */
    private void runImport(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("modernhome")) {
            this.msg.send(sender, "import-usage");
            return;
        }
        boolean overwrite = args.length > 2 && args[2].equalsIgnoreCase("overwrite");
        CheeseConfig cfg = this.config.get();

        File source = new File(cfg.modernHomePath);
        if (!source.isAbsolute()) {
            source = new File(this.plugin.getServer().getWorldContainer(), cfg.modernHomePath);
        }
        if (!source.isFile()) {
            this.msg.send(sender, "import-missing", Text.ph("path", source.getPath()));
            return;
        }

        File file = source;
        this.msg.send(sender, "import-started", Text.ph("path", file.getPath()));
        Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            try {
                // Anything a player changed since the last flush has to hit disk
                // first, otherwise the eviction below would throw it away.
                this.homes.flushDirtyNow();
                ModernHomeImporter.Batch batch =
                        ModernHomeImporter.read(file, cfg, cfg.modernHomeServerId);
                int[] result = this.homes.storage()
                        .importOwners(batch.owners(), overwrite)
                        .join();
                this.homes.evictAll();
                this.msg.send(sender, "import-done",
                        Text.ph("homes", result[2]),
                        Text.ph("owners", result[0]),
                        Text.ph("skipped", result[1]),
                        Text.ph("dropped", batch.dropped()));
            } catch (Exception failure) {
                this.plugin.getLogger().log(Level.SEVERE, "ModernHome import failed", failure);
                this.msg.send(sender, "import-failed",
                        Text.ph("error", String.valueOf(failure.getMessage())));
            }
        });
    }

    /** Joins the raw args and drops the quotes Brigadier keeps around a name with spaces. */
    private static String joinArgs(String[] args) {
        String joined = String.join(" ", args).trim();
        if (joined.length() >= 2 && joined.charAt(0) == '"' && joined.charAt(joined.length() - 1) == '"') {
            return joined.substring(1, joined.length() - 1);
        }
        return joined;
    }

    private Player requirePlayer(CommandSourceStack source) {
        if (source.getSender() instanceof Player player) {
            return player;
        }
        this.msg.send(source.getSender(), "players-only");
        return null;
    }

    private void withHomes(Player player, Consumer<PlayerHomes> action) {
        PlayerHomes cached = this.homes.cached(player.getUniqueId());
        if (cached != null) {
            action.accept(cached);
            return;
        }
        this.homes.load(player.getUniqueId()).thenAccept(loaded -> {
            if (loaded == null) {
                this.msg.send(player, "storage-error");
                return;
            }
            player.getScheduler().run(this.plugin, task -> {
                if (player.isOnline()) {
                    action.accept(loaded);
                }
            }, null);
        });
    }

    /** Only suggests from what is already cached - never blocks the netty thread. */
    private Collection<String> suggestHomeNames(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            return List.of();
        }
        PlayerHomes owned = this.homes.cached(player.getUniqueId());
        if (owned == null || owned.isEmpty()) {
            return List.of();
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(owned.size());
        for (Home home : owned.ordered()) {
            String name = home.name();
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                // Brigadier splits on spaces, so quote names that contain one.
                out.add(name.indexOf(' ') >= 0 ? '"' + name + '"' : name);
            }
        }
        return out;
    }
}
