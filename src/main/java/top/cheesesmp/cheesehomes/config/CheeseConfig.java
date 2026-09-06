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

    // --- homes -------------------------------------------------------------
    public final Material defaultIcon;
    public final String defaultNameFormat;
    public final int maxNameLength;
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
    public final String listEmptyLabel;
    public final String listEmptyTooltip;
    public final String listLockedLabel;
    public final String listLockedTooltip;

    public final String manageTitle;
    public final String manageBody;
    public final int manageWidth;
    public final String manageTeleportLabel;
    public final String manageTeleportTooltip;
    public final String manageIconLabel;
    public final String manageIconTooltip;
    public final String manageRenameLabel;
    public final String manageRenameTooltip;
    public final String manageDeleteLabel;
    public final String manageDeleteTooltip;
    public final String manageBackLabel;

    public final String renameTitle;
    public final String renameInputLabel;
    public final int renameInputWidth;
    public final String renameSaveLabel;
    public final String renameCancelLabel;
    public final int renameWidth;

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
        this.storageFile = cfg.getString("storage.file", "homes.db");
        this.flushIntervalSeconds = Math.max(0L, cfg.getLong("storage.flush-interval-seconds", 30L));
        this.busyTimeoutMs = Math.max(0, cfg.getInt("storage.busy-timeout-ms", 5000));
        this.cacheLingerSeconds = Math.max(0L, cfg.getLong("storage.cache-linger-seconds", 120L));

        this.hardCap = Math.max(1, cfg.getInt("limits.hard-cap", 100));
        this.defaultLimit = clamp(cfg.getInt("limits.default", 3), 0, this.hardCap);
        this.limitPrefix = normalisePrefix(cfg.getString("limits.permission-prefix", "cheesehomes.maxhomes."));
        this.unlimitedPermission = cfg.getString("limits.unlimited-permission", "cheesehomes.maxhomes.unlimited");
        this.limitGrants = readGrants(cfg.getConfigurationSection("limits.grants"));
        this.probeLimitPermissions = cfg.getBoolean("limits.probe-permissions", true);

        this.warmupSeconds = Math.max(0.0D, cfg.getDouble("teleport.warmup-seconds", 3.0D));
        this.warmupPrefix = normalisePrefix(cfg.getString("teleport.warmup-permission-prefix", "cheesehomes.warmup."));
        this.warmupBypass = cfg.getString("teleport.warmup-bypass-permission", "cheesehomes.bypass.warmup");
        this.cancelOnMove = cfg.getBoolean("teleport.cancel-on-move", true);
        double moveDistance = Math.max(0.01D, cfg.getDouble("teleport.cancel-on-move-distance", 0.35D));
        this.cancelMoveDistanceSq = moveDistance * moveDistance;
        this.cancelOnDamage = cfg.getBoolean("teleport.cancel-on-damage", true);
        this.cooldownSeconds = Math.max(0.0D, cfg.getDouble("teleport.cooldown-seconds", 5.0D));
        this.cooldownPrefix = normalisePrefix(cfg.getString("teleport.cooldown-permission-prefix", "cheesehomes.cooldown."));
        this.cooldownBypass = cfg.getString("teleport.cooldown-bypass-permission", "cheesehomes.bypass.cooldown");
        this.countdownActionBar = cfg.getBoolean("teleport.countdown-actionbar", true);

        this.worldBlacklist = lowerSet(cfg.getStringList("worlds.blacklist"));
        this.requireWorldPermission = cfg.getBoolean("worlds.require-per-world-permission", false);
        this.worldPermissionPrefix = normalisePrefix(cfg.getString("worlds.per-world-permission-prefix", "cheesehomes.world."));

        this.defaultIcon = material(cfg.getString("homes.default-icon", "minecraft:white_bed"), Material.WHITE_BED);
        this.defaultNameFormat = cfg.getString("homes.default-name", "Home <index>");
        this.maxNameLength = clamp(cfg.getInt("homes.max-name-length", 24), 1, 64);
        this.namePattern = Pattern.compile(cfg.getString("homes.name-pattern", "^[A-Za-z0-9 _\\-]+$"));

        this.callbackLifetimeSeconds = Math.max(10, cfg.getInt("dialog.callback-lifetime-seconds", 600));
        this.callbackUses = Math.max(1, cfg.getInt("dialog.callback-uses", 16));
        this.spritesEnabled = cfg.getBoolean("dialog.sprites.enabled", true);
        this.spritesInList = cfg.getBoolean("dialog.sprites.in-list", true);

        this.listTitle = cfg.getString("dialog.list.title", "<white>Homes");
        this.listIcon = material(cfg.getString("dialog.list.icon", "minecraft:white_bed"), Material.WHITE_BED);
        this.listColumns = clamp(cfg.getInt("dialog.list.columns", 5), 1, 8);
        this.listButtonWidth = clamp(cfg.getInt("dialog.list.button-width", 65), 1, 1024);
        this.listExpandSteps = readSteps(cfg.getIntegerList("dialog.list.expand-steps"), this.hardCap);
        this.listShowMoreLabel = cfg.getString("dialog.list.show-more-label", "<gray>Show More");
        this.listShowMoreTooltip = cfg.getString("dialog.list.show-more-tooltip", "");
        this.listShowMoreWidth = clamp(cfg.getInt("dialog.list.show-more-width", this.listButtonWidth), 1, 1024);
        this.listCloseLabel = cfg.getString("dialog.list.close-label", "<gray>Close");
        this.listEntryLabel = cfg.getString("dialog.list.entry-label", "<white><name>");
        this.listEntryTooltip = cfg.getString("dialog.list.entry-tooltip", "");
        this.listEmptyLabel = cfg.getString("dialog.list.empty-label", "<dark_gray>New Home");
        this.listEmptyTooltip = cfg.getString("dialog.list.empty-tooltip", "");
        this.listLockedLabel = cfg.getString("dialog.list.locked-label", "<red>Locked");
        this.listLockedTooltip = cfg.getString("dialog.list.locked-tooltip", "");

        this.manageTitle = cfg.getString("dialog.manage.title", "<white><name>");
        this.manageBody = cfg.getString("dialog.manage.body", "");
        this.manageWidth = clamp(cfg.getInt("dialog.manage.width", 140), 1, 1024);
        this.manageTeleportLabel = cfg.getString("dialog.manage.teleport-label", "<green>Teleport");
        this.manageTeleportTooltip = cfg.getString("dialog.manage.teleport-tooltip", "");
        this.manageIconLabel = cfg.getString("dialog.manage.icon-label", "Change Icon");
        this.manageIconTooltip = cfg.getString("dialog.manage.icon-tooltip", "");
        this.manageRenameLabel = cfg.getString("dialog.manage.rename-label", "<white>Rename");
        this.manageRenameTooltip = cfg.getString("dialog.manage.rename-tooltip", "");
        this.manageDeleteLabel = cfg.getString("dialog.manage.delete-label", "<red>Delete");
        this.manageDeleteTooltip = cfg.getString("dialog.manage.delete-tooltip", "");
        this.manageBackLabel = cfg.getString("dialog.manage.back-label", "<gray>Back");

        this.renameTitle = cfg.getString("dialog.rename.title", "<white>Rename Home");
        this.renameInputLabel = cfg.getString("dialog.rename.input-label", "<white>New Name");
        this.renameInputWidth = clamp(cfg.getInt("dialog.rename.input-width", 220), 1, 1024);
        this.renameSaveLabel = cfg.getString("dialog.rename.save-label", "<green>Save");
        this.renameCancelLabel = cfg.getString("dialog.rename.cancel-label", "<gray>Cancel");
        this.renameWidth = clamp(cfg.getInt("dialog.rename.width", 140), 1, 1024);

        this.deleteTitle = cfg.getString("dialog.delete.title", "<white>Delete <name>?");
        this.deleteBody = cfg.getString("dialog.delete.body", "");
        this.deleteConfirmLabel = cfg.getString("dialog.delete.confirm-label", "<red>Delete");
        this.deleteCancelLabel = cfg.getString("dialog.delete.cancel-label", "<gray>Cancel");

        this.iconsTitle = cfg.getString("dialog.icons.title", "<white>Choose Icon");
        this.iconsColumns = clamp(cfg.getInt("dialog.icons.columns", 4), 1, 8);
        this.iconsButtonWidth = clamp(cfg.getInt("dialog.icons.button-width", 130), 1, 1024);
        this.iconsControlWidth = clamp(cfg.getInt("dialog.icons.control-width", 100), 1, 1024);
        // 0 means "one page with everything on it".
        this.iconsPageSize = clamp(cfg.getInt("dialog.icons.page-size", 0), 0, 4096);
        this.iconsCallbackLifetimeSeconds =
                Math.max(10, cfg.getInt("dialog.icons.callback-lifetime-seconds", 120));
        this.iconsSearchLabel = cfg.getString("dialog.icons.search-label", "<white>Search");
        this.iconsSearchWidth = clamp(cfg.getInt("dialog.icons.search-width", 220), 1, 1024);
        this.iconsSearchMaxLength = clamp(cfg.getInt("dialog.icons.search-max-length", 40), 1, 256);
        this.iconsSearchButtonLabel = cfg.getString("dialog.icons.search-button-label", "<white>Search");
        this.iconsDefaultLabel = cfg.getString("dialog.icons.default-label", "<white>Default");
        this.iconsBackLabel = cfg.getString("dialog.icons.back-label", "<gray>Back");
        this.iconsNextLabel = cfg.getString("dialog.icons.next-label", "<gray>Next Page");
        this.iconsPrevLabel = cfg.getString("dialog.icons.prev-label", "<gray>Previous");
        this.iconsSelectedColor = cfg.getString("dialog.icons.selected-color", "green");
        this.iconsUnselectedColor = cfg.getString("dialog.icons.unselected-color", "white");
        this.iconsAllowed = lowerSet(cfg.getStringList("dialog.icons.allowed"));
        this.iconsDenied = lowerSet(cfg.getStringList("dialog.icons.denied"));

        this.modernHomePath = cfg.getString("import.modernhome.path", "plugins/ModernHome/storage.db");
        this.modernHomeServerId = cfg.getString("import.modernhome.server-id", "");
        this.modernHomeCenterOnBlock = cfg.getBoolean("import.modernhome.center-on-block", true);

        this.messages = new HashMap<>();
        ConfigurationSection messageSection = cfg.getConfigurationSection("messages");
        if (messageSection != null) {
            for (String key : messageSection.getKeys(true)) {
                if (messageSection.isString(key)) {
                    this.messages.put(key, messageSection.getString(key, ""));
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
