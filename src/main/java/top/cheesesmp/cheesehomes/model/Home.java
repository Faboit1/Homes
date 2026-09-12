package top.cheesesmp.cheesehomes.model;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/**
 * A single home. Homes live in a fixed slot so the dialog grid stays stable
 * between openings - slot 0 is always the first button.
 */
public final class Home {

    private final int slot;
    private final long created;
    private String name;
    private String world;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private Material icon;
    private String description;

    public Home(int slot, String name, String world, double x, double y, double z,
                float yaw, float pitch, Material icon, long created) {
        this(slot, name, world, x, y, z, yaw, pitch, icon, created, null);
    }

    public Home(int slot, String name, String world, double x, double y, double z,
                float yaw, float pitch, Material icon, long created, @Nullable String description) {
        this.slot = slot;
        this.name = name;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.icon = icon;
        this.created = created;
        this.description = description;
    }

    public static Home of(int slot, String name, Location location, Material icon) {
        return new Home(slot, name, location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(), icon, System.currentTimeMillis());
    }

    public int slot() {
        return this.slot;
    }

    public long created() {
        return this.created;
    }

    public String name() {
        return this.name;
    }

    public void name(String name) {
        this.name = name;
    }

    public String world() {
        return this.world;
    }

    public double x() {
        return this.x;
    }

    public double y() {
        return this.y;
    }

    public double z() {
        return this.z;
    }

    public float yaw() {
        return this.yaw;
    }

    public float pitch() {
        return this.pitch;
    }

    public Material icon() {
        return this.icon;
    }

    /** {@code null} when the owner has not written one. */
    public @Nullable String description() {
        return this.description;
    }

    public void description(@Nullable String description) {
        this.description = description == null || description.isBlank() ? null : description;
    }

    public boolean hasDescription() {
        return this.description != null;
    }

    public void icon(Material icon) {
        this.icon = icon;
    }

    public void moveTo(Location location) {
        this.world = location.getWorld().getName();
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
        this.yaw = location.getYaw();
        this.pitch = location.getPitch();
    }

    /** {@code null} when the world is not loaded. */
    public @Nullable Location toLocation(@Nullable World resolved) {
        return resolved == null ? null : new Location(resolved, this.x, this.y, this.z, this.yaw, this.pitch);
    }
}
