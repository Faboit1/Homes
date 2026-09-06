package top.cheesesmp.cheesehomes.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.Nullable;

/**
 * Every home a single player owns, indexed twice: by slot for the dialog grid
 * and by lower-cased name for {@code /home <name>}. Both indexes are concurrent
 * because dialog callbacks, commands and the storage flusher can all touch a
 * player's data from different region threads.
 */
public final class PlayerHomes {

    private final UUID owner;
    private final Map<Integer, Home> bySlot = new ConcurrentHashMap<>();
    private final Map<String, Home> byName = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private volatile long lastTouched = System.nanoTime();

    public PlayerHomes(UUID owner) {
        this.owner = owner;
    }

    public UUID owner() {
        return this.owner;
    }

    public boolean isDirty() {
        return this.dirty.get();
    }

    public void markDirty() {
        this.dirty.set(true);
    }

    /** Atomically clears the dirty flag; returns true when there was work to do. */
    public boolean consumeDirty() {
        return this.dirty.compareAndSet(true, false);
    }

    public void touch() {
        this.lastTouched = System.nanoTime();
    }

    public long lastTouched() {
        return this.lastTouched;
    }

    public int size() {
        return this.bySlot.size();
    }

    public boolean isEmpty() {
        return this.bySlot.isEmpty();
    }

    public @Nullable Home bySlot(int slot) {
        return this.bySlot.get(slot);
    }

    public @Nullable Home byName(String name) {
        return this.byName.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Home> all() {
        return this.bySlot.values();
    }

    /** Slot-ordered snapshot, used for tab completion and the list command. */
    public List<Home> ordered() {
        List<Home> out = new ArrayList<>(this.bySlot.values());
        out.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        return out;
    }

    /** Lowest unused slot below {@code limit}, or -1 when the player is full. */
    public int firstFreeSlot(int limit) {
        for (int i = 0; i < limit; i++) {
            if (!this.bySlot.containsKey(i)) {
                return i;
            }
        }
        return -1;
    }

    /** Adds without touching the dirty flag - used while loading from storage. */
    public void index(Home home) {
        this.bySlot.put(home.slot(), home);
        this.byName.put(home.name().toLowerCase(Locale.ROOT), home);
    }

    public void add(Home home) {
        index(home);
        markDirty();
    }

    public void remove(Home home) {
        this.bySlot.remove(home.slot(), home);
        this.byName.remove(home.name().toLowerCase(Locale.ROOT), home);
        markDirty();
    }

    public void rename(Home home, String newName) {
        this.byName.remove(home.name().toLowerCase(Locale.ROOT), home);
        home.name(newName);
        this.byName.put(newName.toLowerCase(Locale.ROOT), home);
        markDirty();
    }
}
