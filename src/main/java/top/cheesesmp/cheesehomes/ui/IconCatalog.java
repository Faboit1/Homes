package top.cheesesmp.cheesehomes.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import top.cheesesmp.cheesehomes.config.CheeseConfig;

/**
 * The searchable item list behind the "Choose Icon" dialog.
 *
 * <p>Built once per config load. Each entry pre-computes its id, its search
 * haystack and its {@link ItemStack}, so rendering a page of the picker is just
 * a slice of an array - no {@code Material.values()} scan per click.
 */
public final class IconCatalog {

    public record Entry(Material material, String id, String haystack, ItemStack stack) {
    }

    private final List<Entry> entries;

    public IconCatalog(CheeseConfig config) {
        List<Entry> built = new ArrayList<>(1024);
        for (Material material : Material.values()) {
            if (material.isLegacy() || !material.isItem() || material.isAir()) {
                continue;
            }
            String id = material.getKey().toString();
            if (config.iconsDenied.contains(id)) {
                continue;
            }
            if (!config.iconsAllowed.isEmpty() && !config.iconsAllowed.contains(id)) {
                continue;
            }
            String plain = material.getKey().getKey();
            built.add(new Entry(material, id, plain + " " + plain.replace('_', ' '), ItemStack.of(material)));
        }
        built.sort((a, b) -> a.id().compareTo(b.id()));
        this.entries = List.copyOf(built);
    }

    public List<Entry> all() {
        return this.entries;
    }

    public int size() {
        return this.entries.size();
    }

    /** Substring match against the item id, blank query returns everything. */
    public List<Entry> search(@Nullable String query) {
        if (query == null || query.isBlank()) {
            return this.entries;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        String loose = needle.replace('_', ' ');
        List<Entry> hits = new ArrayList<>(64);
        for (Entry entry : this.entries) {
            if (entry.haystack().contains(needle) || entry.haystack().contains(loose)) {
                hits.add(entry);
            }
        }
        return hits;
    }
}
