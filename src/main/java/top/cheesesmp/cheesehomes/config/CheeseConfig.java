package top.cheesesmp.cheesehomes.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Immutable snapshot of config.yml. Reloading builds a brand new instance so
 * running dialogs keep a consistent view of their own settings and nothing has
 * to be synchronised.
 */
public final class CheeseConfig {

    // --- storage -----------------------------------------------------------
    public final String storageFile;
    public final long flushIntervalSeconds;
    public final int busyTimeoutMs;
    public final long cacheLingerSeconds;

    // --- limits ------------------------------------------------------------
    public final int defaultLimit;
    public final int hardCap;
    public final String limitPrefix;
    public final String unlimitedPermission;
    public final Map<String, Integer> limitGrants;
    public final boolean probeLimitPermissions;

    // --- teleport ----------------------------------------------------------
    public final double warmupSeconds;
    public final String warmupPrefix;
    public final String warmupBypass;
    public final boolean cancelOnMove;
    public final double cancelMoveDistanceSq;
    public final boolean cancelOnDamage;
    public final double cooldownSeconds;
    public final String cooldownPrefix;
    public final String cooldownBypass;
    public final boolean countdownActionBar;

    // --- worlds ------------------------------------------------------------
    public final Set<String> worldBlacklist;
    public final boolean requireWorldPermission;
    public final String worldPermissionPrefix;

    // --- privacy -----------------------------------------------------------
    public final boolean showCoordinatesDefault;
    public final String hiddenCoordinate;

    // --- homes -------------------------------------------------------------
    public final Material defaultIcon;
    public final String defaultNameFormat;
    public final int maxNameLength;
    public final int maxDescriptionLength;
    public final Pattern namePattern;

    // --- dialog ------------------------------------------------------------
    public final int callbackLifetimeSeconds;
    public final int callbackUses;
    public final boolean spritesEnabled;
    public final boolean spritesInList;

    public final String listTitle;
    public final Material listIcon;
    public final int listColumns;
    public final int listButtonWidth;
    public final int[] listExpandSteps;
    public final String listShowMoreLabel;
    public final String listShowMoreTooltip;
    public final int listShowMoreWidth;
    public final String listCloseLabel;
    public final String listEntryLabel;
    public final String listEntryTooltip;
    public final String listEntryTooltipDescription;
    public final String listEmptyLabel;
    public final String listEmptyTooltip;
    public final String listLockedLabel;
    public final String listLockedTooltip;

    public final String manageTitle;
    public final String manageBody;
    public final String manageBodyHidden;
    public final String manageDescriptionBody;
    public final int manageWidth;
    public final String manageTeleportLabel;
    public final String manageTeleportTooltip;
    public final String manageIconLabel;
    public final String manageIconTooltip;
    public final String manageRenameLabel;
    public final String manageRenameTooltip;
    public final String manageDescriptionLabel;
    public final String manageDescriptionTooltip;
    public final String manageDeleteLabel;
    public final String manageDeleteTooltip;
    public final String manageBackLabel;

    public final String renameTitle;
    public final String renameInputLabel;
    public final int renameInputWidth;
    public final String renameSaveLabel;
    public final String renameCancelLabel;
    public final int renameWidth;

    public final String describeTitle;
    public final String describeInputLabel;
    public final int describeInputWidth;
    public final int describeInputMaxLines;
    public final int describeInputHeight;
    public final String describeSaveLabel;
    public final String describeClearLabel;
    public final String describeCancelLabel;
    public final int describeWidth;

    public final String deleteTitle;
    public final String deleteBody;
    public final String deleteConfirmLabel;
    public final String deleteCancelLabel;

    public final String iconsTitle;
    public final int iconsColumns;
    public final int iconsButtonWidth;
    public final int iconsControlWidth;
    public final int iconsPageSize;
    public final int iconsCallbackLifetimeSeconds;
    public final String iconsSearchLabel;
    public final int iconsSearchWidth;
    public final int iconsSearchMaxLength;
    public final String iconsSearchButtonLabel;
    public final String iconsDefaultLabel;
    public final String iconsBackLabel;
    public final String iconsNextLabel;
    public final String iconsPrevLabel;
    public final String iconsSelectedColor;
    public final String iconsUnselectedColor;
    public final Set<String> iconsAllowed;
    public final Set<String> iconsDenied;

    // --- import ------------------------------------------------------------
    public final String modernHomePath;
    public final String modernHomeServerId;
    public final boolean modernHomeCenterOnBlock;

    // --- messages ----------------------------------------------------------
    private final Map<String, String> messages;
    public final String prefix;

    public CheeseConfig(Plugin plugin, FileConfiguration cfg) {
        this.storageFile = str(cfg, "storage.file", "homes.db");
        this.flushIntervalSeconds = Math.max(0L, lng(cfg, "storage.flush-interval-seconds", 30L));
        this.busyTimeoutMs = Math.max(0, integer(cfg, "storage.busy-timeout-ms", 5000));
        this.cacheLingerSeconds = Math.max(0L, lng(cfg, "storage.cache-linger-seconds", 120L));

        this.hardCap = Math.max(1, integer(cfg, "limits.hard-cap", 100));
        this.defaultLimit = clamp(integer(cfg, "limits.default", 3), 0, this.hardCap);
        this.limitPrefix = normalisePrefix(str(cfg, "limits.permission-prefix", "cheesehomes.maxhomes."));
        this.unlimitedPermission = str(cfg, "limits.unlimited-permission", "cheesehomes.maxhomes.unlimited");
        this.limitGrants = readGrants(section(cfg, "limits.grants"));
        this.probeLimitPermissions = bool(cfg, "limits.probe-permissions", true);

        this.warmupSeconds = Math.max(0.0D, dbl(cfg, "teleport.warmup-seconds", 3.0D));
        this.warmupPrefix = normalisePrefix(str(cfg, "teleport.warmup-permission-prefix", "cheesehomes.warmup."));
        this.warmupBypass = str(cfg, "teleport.warmup-bypass-permission", "cheesehomes.bypass.warmup");
        this.cancelOnMove = bool(cfg, "teleport.cancel-on-move", true);
        double moveDistance = Math.max(0.01D, dbl(cfg, "teleport.cancel-on-move-distance", 0.35D));
        this.cancelMoveDistanceSq = moveDistance * moveDistance;
        this.cancelOnDamage = bool(cfg, "teleport.cancel-on-damage", true);
        this.cooldownSeconds = Math.max(0.0D, dbl(cfg, "teleport.cooldown-seconds", 5.0D));
        this.cooldownPrefix = normalisePrefix(str(cfg, "teleport.cooldown-permission-prefix", "cheesehomes.cooldown."));
        this.cooldownBypass = str(cfg, "teleport.cooldown-bypass-permission", "cheesehomes.bypass.cooldown");
        this.countdownActionBar = bool(cfg, "teleport.countdown-actionbar", true);

        this.worldBlacklist = lowerSet(cfg.getStringList("worlds.blacklist"));
        this.requireWorldPermission = bool(cfg, "worlds.require-per-world-permission", false);
        this.worldPermissionPrefix = normalisePrefix(str(cfg, "worlds.per-world-permission-prefix", "cheesehomes.world."));

        this.showCoordinatesDefault = bool(cfg, "privacy.show-coordinates-default", true);
        this.hiddenCoordinate = str(cfg, "privacy.hidden-coordinate", "???");

        this.defaultIcon = material(str(cfg, "homes.default-icon", "minecraft:white_bed"), Material.WHITE_BED);
        this.defaultNameFormat = str(cfg, "homes.default-name", "Home <index>");
        this.maxNameLength = clamp(integer(cfg, "homes.max-name-length", 24), 1, 64);
        this.maxDescriptionLength = clamp(integer(cfg, "homes.max-description-length", 128), 1, 1024);
        this.namePattern = Pattern.compile(str(cfg, "homes.name-pattern", "^[A-Za-z0-9 _\\-]+$"));

        this.callbackLifetimeSeconds = Math.max(10, integer(cfg, "dialog.callback-lifetime-seconds", 600));
        this.callbackUses = Math.max(1, integer(cfg, "dialog.callback-uses", 16));
        this.spritesEnabled = bool(cfg, "dialog.sprites.enabled", true);
        this.spritesInList = bool(cfg, "dialog.sprites.in-list", true);

        this.listTitle = str(cfg, "dialog.list.title", "<white>Homes");
        this.listIcon = material(str(cfg, "dialog.list.icon", "minecraft:white_bed"), Material.WHITE_BED);
        this.listColumns = clamp(integer(cfg, "dialog.list.columns", 5), 1, 8);
        this.listButtonWidth = clamp(integer(cfg, "dialog.list.button-width", 65), 1, 1024);
        this.listExpandSteps = readSteps(cfg.getIntegerList("dialog.list.expand-steps"), this.hardCap);
        this.listShowMoreLabel = str(cfg, "dialog.list.show-more-label", "<gray>Show More");
        this.listShowMoreTooltip = str(cfg, "dialog.list.show-more-tooltip", "");
        this.listShowMoreWidth = clamp(integer(cfg, "dialog.list.show-more-width", this.listButtonWidth), 1, 1024);
        this.listCloseLabel = str(cfg, "dialog.list.close-label", "<gray>Close");
        this.listEntryLabel = str(cfg, "dialog.list.entry-label", "<white><name>");
        this.listEntryTooltip = str(cfg, "dialog.list.entry-tooltip", "");
        this.listEntryTooltipDescription =
                str(cfg, "dialog.list.entry-tooltip-description", "");
        this.listEmptyLabel = str(cfg, "dialog.list.empty-label", "<dark_gray>New Home");
        this.listEmptyTooltip = str(cfg, "dialog.list.empty-tooltip", "");
        this.listLockedLabel = str(cfg, "dialog.list.locked-label", "<red>Locked");
        this.listLockedTooltip = str(cfg, "dialog.list.locked-tooltip", "");

        this.manageTitle = str(cfg, "dialog.manage.title", "<white><name>");
        this.manageBody = str(cfg, "dialog.manage.body", "");
        this.manageBodyHidden = str(cfg, "dialog.manage.body-hidden", "");
        this.manageDescriptionBody = str(cfg, "dialog.manage.description-body", "");
        this.manageWidth = clamp(integer(cfg, "dialog.manage.width", 140), 1, 1024);
        this.manageTeleportLabel = str(cfg, "dialog.manage.teleport-label", "<green>Teleport");
        this.manageTeleportTooltip = str(cfg, "dialog.manage.teleport-tooltip", "");
        this.manageIconLabel = str(cfg, "dialog.manage.icon-label", "Change Icon");
        this.manageIconTooltip = str(cfg, "dialog.manage.icon-tooltip", "");
        this.manageRenameLabel = str(cfg, "dialog.manage.rename-label", "<white>Rename");
        this.manageRenameTooltip = str(cfg, "dialog.manage.rename-tooltip", "");
        this.manageDescriptionLabel = str(cfg, "dialog.manage.description-label", "<white>Description");
        this.manageDescriptionTooltip = str(cfg, "dialog.manage.description-tooltip", "");
        this.manageDeleteLabel = str(cfg, "dialog.manage.delete-label", "<red>Delete");
        this.manageDeleteTooltip = str(cfg, "dialog.manage.delete-tooltip", "");
        this.manageBackLabel = str(cfg, "dialog.manage.back-label", "<gray>Back");

        this.renameTitle = str(cfg, "dialog.rename.title", "<white>Rename Home");
        this.renameInputLabel = str(cfg, "dialog.rename.input-label", "<white>New Name");
        this.renameInputWidth = clamp(integer(cfg, "dialog.rename.input-width", 220), 1, 1024);
        this.renameSaveLabel = str(cfg, "dialog.rename.save-label", "<green>Save");
        this.renameCancelLabel = str(cfg, "dialog.rename.cancel-label", "<gray>Cancel");
        this.renameWidth = clamp(integer(cfg, "dialog.rename.width", 140), 1, 1024);

        this.describeTitle = str(cfg, "dialog.description.title", "<white>Describe <name>");
        this.describeInputLabel = str(cfg, "dialog.description.input-label", "<white>Description");
        this.describeInputWidth = clamp(integer(cfg, "dialog.description.input-width", 300), 1, 1024);
        this.describeInputMaxLines = clamp(integer(cfg, "dialog.description.input-max-lines", 4), 1, 64);
        this.describeInputHeight = clamp(integer(cfg, "dialog.description.input-height", 80), 1, 512);
        this.describeSaveLabel = str(cfg, "dialog.description.save-label", "<green>Save");
        this.describeClearLabel = str(cfg, "dialog.description.clear-label", "<red>Clear");
        this.describeCancelLabel = str(cfg, "dialog.description.cancel-label", "<gray>Cancel");
        this.describeWidth = clamp(integer(cfg, "dialog.description.width", 140), 1, 1024);

        this.deleteTitle = str(cfg, "dialog.delete.title", "<white>Delete <name>?");
        this.deleteBody = str(cfg, "dialog.delete.body", "");
        this.deleteConfirmLabel = str(cfg, "dialog.delete.confirm-label", "<red>Delete");
        this.deleteCancelLabel = str(cfg, "dialog.delete.cancel-label", "<gray>Cancel");

        this.iconsTitle = str(cfg, "dialog.icons.title", "<white>Choose Icon");
        this.iconsColumns = clamp(integer(cfg, "dialog.icons.columns", 4), 1, 8);
        this.iconsButtonWidth = clamp(integer(cfg, "dialog.icons.button-width", 130), 1, 1024);
        this.iconsControlWidth = clamp(integer(cfg, "dialog.icons.control-width", 100), 1, 1024);
        // 0 means "one page with everything on it".
        this.iconsPageSize = clamp(integer(cfg, "dialog.icons.page-size", 0), 0, 4096);
        this.iconsCallbackLifetimeSeconds =
                Math.max(10, integer(cfg, "dialog.icons.callback-lifetime-seconds", 120));
        this.iconsSearchLabel = str(cfg, "dialog.icons.search-label", "<white>Search");
        this.iconsSearchWidth = clamp(integer(cfg, "dialog.icons.search-width", 220), 1, 1024);
        this.iconsSearchMaxLength = clamp(integer(cfg, "dialog.icons.search-max-length", 40), 1, 256);
        this.iconsSearchButtonLabel = str(cfg, "dialog.icons.search-button-label", "<white>Search");
        this.iconsDefaultLabel = str(cfg, "dialog.icons.default-label", "<white>Default");
        this.iconsBackLabel = str(cfg, "dialog.icons.back-label", "<gray>Back");
        this.iconsNextLabel = str(cfg, "dialog.icons.next-label", "<gray>Next Page");
        this.iconsPrevLabel = str(cfg, "dialog.icons.prev-label", "<gray>Previous");
        this.iconsSelectedColor = str(cfg, "dialog.icons.selected-color", "green");
        this.iconsUnselectedColor = str(cfg, "dialog.icons.unselected-color", "white");
        this.iconsAllowed = lowerSet(cfg.getStringList("dialog.icons.allowed"));
        this.iconsDenied = lowerSet(cfg.getStringList("dialog.icons.denied"));

        this.modernHomePath = str(cfg, "import.modernhome.path", "plugins/ModernHome/storage.db");
        this.modernHomeServerId = str(cfg, "import.modernhome.server-id", "");
        this.modernHomeCenterOnBlock = bool(cfg, "import.modernhome.center-on-block", true);

        this.messages = new HashMap<>();
        ConfigurationSection messageSection = section(cfg, "messages");
        if (messageSection != null) {
            for (String key : messageSection.getKeys(true)) {
                if (messageSection.isString(key)) {
                    this.messages.put(key, messageSection.getString(key));
                }
            }
        }
        this.prefix = this.messages.getOrDefault("prefix", "");
    }

    /** Raw MiniMessage for a message key, prefix already applied. */
    public String message(String key) {
        String raw = this.messages.get(key);
        if (raw == null) {
            return "<red>Missing message: " + key;
        }
        return this.prefix + raw;
    }

    /** Raw MiniMessage without the prefix - for action bars and dialog text. */
    public String bare(String key) {
        return this.messages.getOrDefault(key, "");
    }

    // Bukkit's two-argument getters return the default you hand them without
    // ever consulting the defaults loaded from the jar, so a key added in a
    // later version stays missing on an existing config.yml. These read through
    // the one-argument form (or isSet, which does see the defaults) and keep the
    // inline value only as a last resort.

    private static String str(FileConfiguration cfg, String path, String fallback) {
        String value = cfg.getString(path);
        return value == null ? fallback : value;
    }

    private static int integer(FileConfiguration cfg, String path, int fallback) {
        return cfg.isSet(path) ? cfg.getInt(path) : fallback;
    }

    private static long lng(FileConfiguration cfg, String path, long fallback) {
        return cfg.isSet(path) ? cfg.getLong(path) : fallback;
    }

    private static double dbl(FileConfiguration cfg, String path, double fallback) {
        return cfg.isSet(path) ? cfg.getDouble(path) : fallback;
    }

    private static boolean bool(FileConfiguration cfg, String path, boolean fallback) {
        return cfg.isSet(path) ? cfg.getBoolean(path) : fallback;
    }

    /** getConfigurationSection also skips the defaults, so fall back explicitly. */
    private static ConfigurationSection section(FileConfiguration cfg, String path) {
        ConfigurationSection found = cfg.getConfigurationSection(path);
        if (found != null) {
            return found;
        }
        Configuration defaults = cfg.getDefaults();
        return defaults == null ? null : defaults.getConfigurationSection(path);
    }

    private static Map<String, Integer> readGrants(ConfigurationSection section) {
        Map<String, Integer> out = new HashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                int amount = section.getInt(key, 0);
                if (amount > 0) {
                    out.put(key.toLowerCase(Locale.ROOT), amount);
                }
            }
        }
        return Map.copyOf(out);
    }

    private static int[] readSteps(List<Integer> raw, int hardCap) {
        List<Integer> steps = new ArrayList<>();
        for (Integer value : raw) {
            if (value != null && value > 0) {
                steps.add(Math.min(value, hardCap));
            }
        }
        if (steps.isEmpty()) {
            steps.add(Math.min(9, hardCap));
        }
        steps.sort(Integer::compareTo);
        int[] out = new int[steps.size()];
        int written = 0;
        for (int value : steps) {
            if (written == 0 || out[written - 1] != value) {
                out[written++] = value;
            }
        }
        int[] trimmed = new int[written];
        System.arraycopy(out, 0, trimmed, 0, written);
        return trimmed;
    }

    private static Set<String> lowerSet(List<String> values) {
        Set<String> out = new HashSet<>(values.size() * 2);
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }

    private static String normalisePrefix(String prefix) {
        String value = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return value.endsWith(".") || value.isEmpty() ? value : value + ".";
    }

    private static Material material(String id, Material fallback) {
        if (id == null || id.isBlank()) {
            return fallback;
        }
        Material found = Material.matchMaterial(id);
        return found == null || !found.isItem() ? fallback : found;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
