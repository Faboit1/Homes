package top.cheesesmp.cheesehomes.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** MiniMessage helpers. Every user facing string in CheeseHomes goes through here. */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    public static Component mm(String raw, TagResolver... resolvers) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        return MM.deserialize(raw, resolvers)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static TagResolver ph(String key, String value) {
        return Placeholder.unparsed(key, value == null ? "" : value);
    }

    public static TagResolver ph(String key, int value) {
        return Placeholder.unparsed(key, Integer.toString(value));
    }

    /** Formats a duration the way players expect: 3, 2.5, 0.75. */
    public static String seconds(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.005D) {
            return Long.toString(Math.round(value));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
