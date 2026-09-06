package top.cheesesmp.cheesehomes.storage;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import top.cheesesmp.cheesehomes.model.PlayerHomes;

/** Persistence backend. Every call is off the server threads. */
public interface HomeStorage {

    void init() throws Exception;

    CompletableFuture<PlayerHomes> load(UUID owner);

    CompletableFuture<Void> save(PlayerHomes homes);

    /** Runs the write on the caller's thread - used on shutdown only. */
    void saveNow(Collection<PlayerHomes> homes);

    /**
     * Bulk-writes imported data in one transaction.
     *
     * @param overwrite replace owners that already have homes instead of skipping them
     * @return {@code {ownersWritten, ownersSkipped, homesWritten}}
     */
    CompletableFuture<int[]> importOwners(Collection<PlayerHomes> homes, boolean overwrite);

    int countHomes();

    void close();
}
