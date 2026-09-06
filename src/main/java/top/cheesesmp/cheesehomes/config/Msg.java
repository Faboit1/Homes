package top.cheesesmp.cheesehomes.config;

import java.util.function.Supplier;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import top.cheesesmp.cheesehomes.util.Text;

/** Thin wrapper so message lookups survive a config reload without re-wiring. */
public final class Msg {

    private final Supplier<CheeseConfig> config;

    public Msg(Supplier<CheeseConfig> config) {
        this.config = config;
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        String raw = this.config.get().message(key);
        if (!raw.isEmpty()) {
            audience.sendMessage(Text.mm(raw, resolvers));
        }
    }

    public void actionBar(Audience audience, String key, TagResolver... resolvers) {
        String raw = this.config.get().bare(key);
        if (!raw.isEmpty()) {
            audience.sendActionBar(Text.mm(raw, resolvers));
        }
    }

    public Component bare(String key, TagResolver... resolvers) {
        return Text.mm(this.config.get().bare(key), resolvers);
    }
}
