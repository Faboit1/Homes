package top.cheesesmp.cheesehomes.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import top.cheesesmp.cheesehomes.config.CheeseConfig;
import top.cheesesmp.cheesehomes.model.Home;
import top.cheesesmp.cheesehomes.model.PlayerHomes;

/**
 * Reads a ModernHome {@code storage.db} and turns it into CheeseHomes data.
 *
 * <p>ModernHome's schema is
 * {@code homes(uuid, home_index, world, x, y, z, name, server_id)} with integer
 * block coordinates, a 1-based index and a name that is often blank. Slots come
 * straight across as {@code home_index - 1}; blank names fall back to the
 * configured default name format, and every name is put through the same
 * validation new homes get so nothing unrenderable ends up in a dialog.
 */
public final class ModernHomeImporter {

    /** Rows that could not be used, and the homes that could. */
    public record Batch(List<PlayerHomes> owners, int homes, int dropped) {
    }

    private ModernHomeImporter() {
    }

    public static Batch read(File source, CheeseConfig config, String serverId) throws Exception {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setReadOnly(true);
        sqlite.setBusyTimeout(config.busyTimeoutMs);

        SQLiteDataSource dataSource = new SQLiteDataSource(sqlite);
        dataSource.setUrl("jdbc:sqlite:" + source.getAbsolutePath());

        Map<UUID, PlayerHomes> byOwner = new HashMap<>();
        int homes = 0;
        int dropped = 0;
        double offset = config.modernHomeCenterOnBlock ? 0.5D : 0.0D;

        String sql = "SELECT uuid, home_index, world, x, y, z, name FROM homes"
                + (serverId.isEmpty() ? "" : " WHERE server_id = ?")
                + " ORDER BY uuid, home_index";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (!serverId.isEmpty()) {
                statement.setString(1, serverId);
            }
            statement.setFetchSize(1000);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID owner;
                    try {
                        owner = UUID.fromString(rows.getString(1));
                    } catch (IllegalArgumentException malformed) {
                        dropped++;
                        continue;
                    }
                    int slot = rows.getInt(2) - 1;
                    String world = rows.getString(3);
                    if (slot < 0 || slot >= config.hardCap || world == null || world.isBlank()) {
                        dropped++;
                        continue;
                    }

                    PlayerHomes owned = byOwner.computeIfAbsent(owner, PlayerHomes::new);
                    if (owned.bySlot(slot) != null) {
                        dropped++;
                        continue;
                    }

                    String name = sanitise(rows.getString(7), slot, owned, config);
                    owned.index(new Home(slot, name, world,
                            rows.getDouble(4) + offset,
                            rows.getDouble(5),
                            rows.getDouble(6) + offset,
                            0.0F, 0.0F,
                            config.defaultIcon,
                            System.currentTimeMillis()));
                    homes++;
                }
            }
        }

        List<PlayerHomes> owners = new ArrayList<>(byOwner.values());
        for (PlayerHomes owned : owners) {
            owned.markDirty();
        }
        return new Batch(owners, homes, dropped);
    }

    /**
     * ModernHome does not validate names, so strip anything the configured
     * pattern rejects and fall back to the default name when nothing is left.
     */
    private static String sanitise(String raw, int slot, PlayerHomes owned, CheeseConfig config) {
        String fallback = config.defaultNameFormat.replace("<index>", Integer.toString(slot + 1));
        String name = raw == null ? "" : raw.trim();
        if (name.length() > config.maxNameLength) {
            name = name.substring(0, config.maxNameLength);
        }
        if (name.isEmpty() || !config.namePattern.matcher(name).matches()) {
            name = fallback;
        }
        if (owned.byName(name) == null) {
            return name;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String tail = " " + suffix;
            String head = name.length() + tail.length() > config.maxNameLength
                    ? name.substring(0, config.maxNameLength - tail.length())
                    : name;
            if (owned.byName(head + tail) == null) {
                return head + tail;
            }
        }
        return name + " " + slot;
    }
}
