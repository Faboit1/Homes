package top.cheesesmp.cheesehomes;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;
import top.cheesesmp.cheesehomes.command.CheeseCommands;
import top.cheesesmp.cheesehomes.config.CheeseConfig;
import top.cheesesmp.cheesehomes.config.Msg;
import top.cheesesmp.cheesehomes.listener.PlayerListener;
import top.cheesesmp.cheesehomes.service.HomeOperations;
import top.cheesesmp.cheesehomes.service.HomeService;
import top.cheesesmp.cheesehomes.service.LimitResolver;
import top.cheesesmp.cheesehomes.service.TeleportService;
import top.cheesesmp.cheesehomes.storage.HomeStorage;
import top.cheesesmp.cheesehomes.storage.SqliteHomeStorage;
import top.cheesesmp.cheesehomes.ui.HomeDialogs;
import top.cheesesmp.cheesehomes.ui.IconCatalog;
import top.cheesesmp.cheesehomes.ui.SpriteBridge;

public final class CheeseHomesPlugin extends JavaPlugin {

    private final AtomicReference<CheeseConfig> config = new AtomicReference<>();
    private final AtomicReference<IconCatalog> catalog = new AtomicReference<>();

    private HomeStorage storage;
    private HomeService homeService;
    private TeleportService teleportService;
    private LimitResolver limitResolver;
    private SpriteBridge spriteBridge;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Keys added by a later version fall back to the bundled defaults, so
        // upgrading never leaves an existing config.yml with holes in it.
        getConfig().options().copyDefaults(true);
        this.config.set(new CheeseConfig(this, getConfig()));
        this.catalog.set(new IconCatalog(this.config.get()));

        this.storage = new SqliteHomeStorage(
                new File(getDataFolder(), this.config.get().storageFile),
                this.config.get().busyTimeoutMs,
                getLogger(),
                () -> this.config.get().defaultIcon);
        try {
            this.storage.init();
        } catch (Exception failure) {
            getLogger().log(Level.SEVERE, "SQLite could not be opened - disabling CheeseHomes", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Msg msg = new Msg(this.config::get);
        this.limitResolver = new LimitResolver(this.config::get);
        this.homeService = new HomeService(this, this.storage, this.config::get);
        this.homeService.start();
        this.teleportService = new TeleportService(this, this.config::get, msg, this.limitResolver);

        HomeOperations operations = new HomeOperations(this.config::get, msg, this.limitResolver);
        this.spriteBridge = new SpriteBridge(getLogger(), this.config.get().spritesEnabled);
        HomeDialogs dialogs = new HomeDialogs(this, this.config::get, this.catalog::get, msg,
                this.homeService, operations, this.limitResolver, this.teleportService,
                this.spriteBridge);

        getServer().getPluginManager().registerEvents(
                new PlayerListener(this.homeService, this.teleportService, this.limitResolver), this);

        CheeseCommands commands = new CheeseCommands(this, this.config::get, msg, this.homeService,
                operations, this.limitResolver, this.teleportService, dialogs, this::reloadEverything);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var registrar = event.registrar();
            registrar.register("homes", "Open your homes menu", List.of("homelist"), commands.homesCommand());
            registrar.register("home", "Teleport to a home, or open the menu", List.of(), commands.homeCommand());
            registrar.register("sethome", "Set a home where you stand", List.of("createhome"), commands.setHomeCommand());
            registrar.register("delhome", "Delete one of your homes", List.of("removehome"), commands.delHomeCommand());
            registrar.register("showhomecoordinates", "Show or hide coordinates in your homes menu",
                    List.of("homecoords", "homecoordinates"), commands.showCoordinatesCommand());
            registrar.register("cheesehomes", "CheeseHomes administration", List.of("chomes"), commands.adminCommand());
        });

        // Anyone already online when the plugin loads (a /reload or a hot install).
        getServer().getOnlinePlayers().forEach(player -> {
            this.homeService.markOnline(player.getUniqueId());
            this.homeService.load(player.getUniqueId());
        });

        getLogger().info("CheeseHomes ready - " + this.catalog.get().size()
                + " icons available, Folia-safe scheduling active.");
    }

    @Override
    public void onDisable() {
        if (this.teleportService != null) {
            this.teleportService.clear();
        }
        if (this.homeService != null) {
            this.homeService.stop();
        }
        if (this.storage != null) {
            this.storage.close();
        }
    }

    /**
     * Re-reads config.yml and rebuilds everything derived from it. The storage
     * file itself is not re-opened, so changing {@code storage.file} still needs
     * a restart.
     */
    private void reloadEverything() {
        reloadConfig();
        getConfig().options().copyDefaults(true);
        CheeseConfig fresh = new CheeseConfig(this, getConfig());
        this.config.set(fresh);
        this.catalog.set(new IconCatalog(fresh));
        this.limitResolver.invalidateAll();
        if (this.spriteBridge != null) {
            this.spriteBridge.clear();
        }
    }
}
