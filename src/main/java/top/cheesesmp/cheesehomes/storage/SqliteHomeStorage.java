package top.cheesesmp.cheesehomes.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import top.cheesesmp.cheesehomes.model.Home;
import top.cheesesmp.cheesehomes.model.PlayerHomes;

/**
 * SQLite backend.
 *
 * <p>All JDBC work happens on one dedicated thread. SQLite serialises writers
 * anyway, so a pool would only add contention; a single thread lets us keep the
 * prepared statements open for the lifetime of the plugin and skip re-parsing
 * every statement. Server threads never block - they get a
 * {@link CompletableFuture} back.
 */
public final class SqliteHomeStorage implements HomeStorage {

    private static final String CREATE_HOMES = """
            CREATE TABLE IF NOT EXISTS homes (
              owner   TEXT    NOT NULL,
              slot    INTEGER NOT NULL,
              name    TEXT    NOT NULL,
              world   TEXT    NOT NULL,
              x       REAL    NOT NULL,
              y       REAL    NOT NULL,
              z       REAL    NOT NULL,
              yaw     REAL    NOT NULL,
              pitch   REAL    NOT NULL,
              icon    TEXT    NOT NULL,
              created INTEGER NOT NULL,
              description TEXT,
              PRIMARY KEY (owner, slot)
            )""";
    private static final String CREATE_SETTINGS = """
            CREATE TABLE IF NOT EXISTS player_settings (
              owner            TEXT PRIMARY KEY,
              show_coordinates INTEGER
            )""";
    private static final String CREATE_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_homes_owner ON homes(owner)";
    private static final String SELECT =
            "SELECT slot,name,world,x,y,z,yaw,pitch,icon,created,description FROM homes WHERE owner=?";
    private static final String DELETE_OWNER = "DELETE FROM homes WHERE owner=?";
    private static final String EXISTS = "SELECT 1 FROM homes WHERE owner=? LIMIT 1";
    private static final String INSERT =
            "INSERT INTO homes(owner,slot,name,world,x,y,z,yaw,pitch,icon,created,description) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String SELECT_SETTINGS =
            "SELECT show_coordinates FROM player_settings WHERE owner=?";
    private static final String UPSERT_SETTINGS =
            "INSERT INTO player_settings(owner,show_coordinates) VALUES(?,?) "
                    + "ON CONFLICT(owner) DO UPDATE SET show_coordinates=excluded.show_coordinates";

    private final File file;
    private final int busyTimeoutMs;
    private final Logger logger;
    private final Supplier<Material> fallbackIcon;
    private final ExecutorService io;

    private Connection connection;
    private PreparedStatement select;
    private PreparedStatement deleteOwner;
    private PreparedStatement insert;
    private PreparedStatement exists;
    private PreparedStatement selectSettings;
    private PreparedStatement upsertSettings;

    public SqliteHomeStorage(File file, int busyTimeoutMs, Logger logger, Supplier<Material> fallbackIcon) {
        this.file = file;
        this.busyTimeoutMs = busyTimeoutMs;
        this.logger = logger;
        this.fallbackIcon = fallbackIcon;
        this.io = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CheeseHomes-SQLite");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void init() throws Exception {
        File parent = this.file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create " + parent);
        }

        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqlite.setTempStore(SQLiteConfig.TempStore.MEMORY);
        sqlite.setBusyTimeout(this.busyTimeoutMs);

        SQLiteDataSource source = new SQLiteDataSource(sqlite);
        source.setUrl("jdbc:sqlite:" + this.file.getAbsolutePath());

        // Open on the IO thread so the connection is only ever touched there.
        submit(() -> {
            this.connection = source.getConnection();
            this.connection.setAutoCommit(true);
            try (Statement statement = this.connection.createStatement()) {
                statement.executeUpdate(CREATE_HOMES);
                statement.executeUpdate(CREATE_INDEX);
                statement.executeUpdate(CREATE_SETTINGS);
            }
            migrate();
            this.select = this.connection.prepareStatement(SELECT);
            this.deleteOwner = this.connection.prepareStatement(DELETE_OWNER);
            this.insert = this.connection.prepareStatement(INSERT);
            this.exists = this.connection.prepareStatement(EXISTS);
            this.selectSettings = this.connection.prepareStatement(SELECT_SETTINGS);
            this.upsertSettings = this.connection.prepareStatement(UPSERT_SETTINGS);
            return null;
        }).get(30L, TimeUnit.SECONDS);
    }

    @Override
    public CompletableFuture<PlayerHomes> load(UUID owner) {
        return submit(() -> readOwner(owner));
    }

    @Override
    public CompletableFuture<Void> save(PlayerHomes homes) {
        return submit(() -> {
            writeOwner(homes);
            return null;
        });
    }

    @Override
    public void saveNow(Collection<PlayerHomes> homes) {
        try {
            submit(() -> {
                boolean previous = this.connection.getAutoCommit();
                this.connection.setAutoCommit(false);
                try {
                    for (PlayerHomes entry : homes) {
                        writeOwner(entry);
                    }
                    this.connection.commit();
                } catch (SQLException failure) {
                    this.connection.rollback();
                    throw failure;
                } finally {
                    this.connection.setAutoCommit(previous);
                }
                return null;
            }).get(30L, TimeUnit.SECONDS);
        } catch (Exception failure) {
            this.logger.log(Level.SEVERE, "Failed to flush homes to disk", failure);
        }
    }

    @Override
    public CompletableFuture<int[]> importOwners(Collection<PlayerHomes> homes, boolean overwrite) {
        return submit(() -> {
            int written = 0;
            int skipped = 0;
            int rows = 0;
            boolean previous = this.connection.getAutoCommit();
            this.connection.setAutoCommit(false);
            try {
                for (PlayerHomes owned : homes) {
                    if (!overwrite && hasRows(owned.owner())) {
                        skipped++;
                        continue;
                    }
                    writeOwner(owned);
                    written++;
                    rows += owned.size();
                }
                this.connection.commit();
            } catch (SQLException failure) {
                this.connection.rollback();
                throw failure;
            } finally {
                this.connection.setAutoCommit(previous);
            }
            return new int[]{written, skipped, rows};
        });
    }

    private boolean hasRows(UUID owner) throws SQLException {
        this.exists.setString(1, owner.toString());
        try (ResultSet found = this.exists.executeQuery()) {
            return found.next();
        }
    }

    @Override
    public int countHomes() {
        try {
            return submit(() -> {
                try (Statement statement = this.connection.createStatement();
                     ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM homes")) {
                    return rows.next() ? rows.getInt(1) : 0;
                }
            }).get(10L, TimeUnit.SECONDS);
        } catch (Exception failure) {
            return -1;
        }
    }

    @Override
    public void close() {
        try {
            submit(() -> {
                closeQuietly(this.select);
                closeQuietly(this.deleteOwner);
                closeQuietly(this.insert);
                closeQuietly(this.exists);
                closeQuietly(this.selectSettings);
                closeQuietly(this.upsertSettings);
                if (this.connection != null) {
                    try (Statement statement = this.connection.createStatement()) {
                        // Fold the WAL back into the database file so a copied
                        // homes.db is always complete.
                        statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                    } catch (SQLException ignored) {
                        // Best effort only.
                    }
                    this.connection.close();
                }
                return null;
            }).get(15L, TimeUnit.SECONDS);
        } catch (Exception failure) {
            this.logger.log(Level.WARNING, "Unclean SQLite shutdown", failure);
        } finally {
            this.io.shutdown();
        }
    }

    // -----------------------------------------------------------------------

    /**
     * Brings a database written by an older version up to date. SQLite has no
     * "ADD COLUMN IF NOT EXISTS", so the column list is read first.
     */
    private void migrate() throws SQLException {
        boolean hasDescription = false;
        try (Statement statement = this.connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(homes)")) {
            while (columns.next()) {
                if ("description".equalsIgnoreCase(columns.getString("name"))) {
                    hasDescription = true;
                    break;
                }
            }
        }
        if (!hasDescription) {
            try (Statement statement = this.connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE homes ADD COLUMN description TEXT");
            }
            this.logger.info("Added the description column to homes.db");
        }
    }

    private PlayerHomes readOwner(UUID owner) throws SQLException {
        PlayerHomes homes = new PlayerHomes(owner);
        this.select.setString(1, owner.toString());
        this.selectSettings.setString(1, owner.toString());
        try (ResultSet settings = this.selectSettings.executeQuery()) {
            if (settings.next()) {
                int value = settings.getInt(1);
                homes.showCoordinates(settings.wasNull() ? null : value != 0);
            }
        }
        try (ResultSet rows = this.select.executeQuery()) {
            while (rows.next()) {
                Material icon = Material.matchMaterial(rows.getString(9));
                if (icon == null || !icon.isItem()) {
                    icon = this.fallbackIcon.get();
                }
                homes.index(new Home(
                        rows.getInt(1),
                        rows.getString(2),
                        rows.getString(3),
                        rows.getDouble(4),
                        rows.getDouble(5),
                        rows.getDouble(6),
                        rows.getFloat(7),
                        rows.getFloat(8),
                        icon,
                        rows.getLong(10),
                        rows.getString(11)));
            }
        }
        return homes;
    }

    /**
     * Replaces every row for one owner. A player owns at most a few dozen rows,
     * so a delete plus a batched insert is both simpler and faster than diffing
     * against what is already stored.
     */
    private void writeOwner(PlayerHomes homes) throws SQLException {
        String owner = homes.owner().toString();

        this.upsertSettings.setString(1, owner);
        Boolean showCoordinates = homes.showCoordinates();
        if (showCoordinates == null) {
            this.upsertSettings.setNull(2, java.sql.Types.INTEGER);
        } else {
            this.upsertSettings.setInt(2, showCoordinates ? 1 : 0);
        }
        this.upsertSettings.executeUpdate();

        this.deleteOwner.setString(1, owner);
        this.deleteOwner.executeUpdate();

        if (homes.isEmpty()) {
            return;
        }
        for (Home home : homes.all()) {
            this.insert.setString(1, owner);
            this.insert.setInt(2, home.slot());
            this.insert.setString(3, home.name());
            this.insert.setString(4, home.world());
            this.insert.setDouble(5, home.x());
            this.insert.setDouble(6, home.y());
            this.insert.setDouble(7, home.z());
            this.insert.setFloat(8, home.yaw());
            this.insert.setFloat(9, home.pitch());
            this.insert.setString(10, home.icon().getKey().toString());
            this.insert.setLong(11, home.created());
            this.insert.setString(12, home.description());
            this.insert.addBatch();
        }
        this.insert.executeBatch();
    }

    private <T> CompletableFuture<T> submit(SqlTask<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        this.io.execute(() -> {
            try {
                future.complete(task.run());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Nothing useful to do while shutting down.
            }
        }
    }

    @FunctionalInterface
    private interface SqlTask<T> {
        T run() throws Exception;
    }
}
