package top.cheesesmp.cheesehomes.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import top.cheesesmp.cheesehomes.config.CheeseConfig;
import top.cheesesmp.cheesehomes.config.Msg;
import top.cheesesmp.cheesehomes.model.Home;
import top.cheesesmp.cheesehomes.model.PlayerHomes;
import top.cheesesmp.cheesehomes.service.HomeOperations;
import top.cheesesmp.cheesehomes.service.HomeService;
import top.cheesesmp.cheesehomes.service.LimitResolver;
import top.cheesesmp.cheesehomes.service.TeleportService;
import top.cheesesmp.cheesehomes.util.Text;

/**
 * Every screen CheeseHomes shows, built with the Dialog API.
 *
 * <p>Dialogs are rendered per player and per click rather than registered up
 * front, because what a player may see depends on their permissions, their
 * homes and where they are in the menu. Buttons carry a click callback instead
 * of a namespaced custom-click id, which keeps the whole flow inside the plugin
 * with no packet handling.
 *
 * <p>Every callback hops onto the clicking player's entity scheduler before it
 * touches anything, so the handlers are Folia-safe no matter which thread the
 * click arrives on.
 */
public final class HomeDialogs {

    private final Plugin plugin;
    private final Supplier<CheeseConfig> config;
    private final Supplier<IconCatalog> catalog;
    private final Msg msg;
    private final HomeService homes;
    private final HomeOperations operations;
    private final LimitResolver limits;
    private final TeleportService teleports;
    private final SpriteBridge sprites;

    public HomeDialogs(Plugin plugin, Supplier<CheeseConfig> config, Supplier<IconCatalog> catalog,
                       Msg msg, HomeService homes, HomeOperations operations,
                       LimitResolver limits, TeleportService teleports, SpriteBridge sprites) {
        this.plugin = plugin;
        this.config = config;
        this.catalog = catalog;
        this.msg = msg;
        this.homes = homes;
        this.operations = operations;
        this.limits = limits;
        this.teleports = teleports;
        this.sprites = sprites;
    }

    // === screens ===========================================================

    /** The home grid. {@code step} indexes {@code dialog.list.expand-steps}. */
    public void openList(Player player, int step) {
        withHomes(player, owned -> {
            CheeseConfig cfg = this.config.get();
            int[] steps = cfg.listExpandSteps;
            int index = Math.max(0, Math.min(step, steps.length - 1));

            int highestUsed = 0;
            for (Home home : owned.all()) {
                highestUsed = Math.max(highestUsed, home.slot() + 1);
            }
            int limit = this.limits.maxHomes(player);
            boolean coords = coordinatesVisible(owned, cfg);
            int visible = Math.min(cfg.hardCap, Math.max(steps[index], highestUsed));

            List<ActionButton> buttons = new ArrayList<>(visible + 1);
            for (int slot = 0; slot < visible; slot++) {
                Home home = owned.bySlot(slot);
                if (home != null) {
                    buttons.add(entryButton(player, cfg, home, coords));
                } else if (slot < limit) {
                    buttons.add(emptyButton(player, cfg, slot, index));
                } else {
                    buttons.add(lockedButton(cfg));
                }
            }
            if (index + 1 < steps.length && steps[index] < cfg.hardCap) {
                int next = index + 1;
                buttons.add(ActionButton.builder(Text.mm(cfg.listShowMoreLabel))
                        .tooltip(Text.mm(cfg.listShowMoreTooltip))
                        .width(cfg.listShowMoreWidth)
                        .action(action(player, view -> openList(player, next)))
                        .build());
            }

            show(player, DialogBase.builder(Text.mm(cfg.listTitle))
                            .canCloseWithEscape(true)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(List.of(DialogBody.item(ItemStack.of(cfg.listIcon))
                                    .showTooltip(false)
                                    .build()))
                            .build(),
                    DialogType.multiAction(buttons)
                            .columns(cfg.listColumns)
                            .exitAction(ActionButton.builder(Text.mm(cfg.listCloseLabel)).build())
                            .build());
        });
    }

    /** Teleport / Change Icon / Rename / Delete for one home. */
    public void openManage(Player player, int slot) {
        withHomes(player, owned -> {
            Home home = owned.bySlot(slot);
            if (home == null) {
                openList(player, 0);
                return;
            }
            CheeseConfig cfg = this.config.get();
            boolean coords = coordinatesVisible(owned, cfg);

            List<DialogBody> body = new ArrayList<>(3);
            body.add(DialogBody.item(ItemStack.of(home.icon())).showTooltip(false).build());
            String location = coords ? cfg.manageBody : cfg.manageBodyHidden;
            if (!location.isBlank()) {
                body.add(DialogBody.plainMessage(Text.mm(location, homePlaceholders(home, coords, cfg))));
            }
            if (home.hasDescription() && !cfg.manageDescriptionBody.isBlank()) {
                body.add(DialogBody.plainMessage(
                        Text.mm(cfg.manageDescriptionBody, homePlaceholders(home, coords, cfg))));
            }

            List<ActionButton> buttons = List.of(
                    ActionButton.builder(Text.mm(cfg.manageTeleportLabel, homePlaceholders(home, coords, cfg)))
                            .tooltip(Text.mm(cfg.manageTeleportTooltip, homePlaceholders(home, coords, cfg)))
                            .width(cfg.manageWidth)
                            .action(action(player, view -> this.teleports.request(player, home)))
                            .build(),
                    ActionButton.builder(Text.mm(cfg.manageIconLabel))
                            .tooltip(Text.mm(cfg.manageIconTooltip))
                            .width(cfg.manageWidth)
                            .action(action(player, view -> openIcons(player, slot, "", 0)))
                            .build(),
                    ActionButton.builder(Text.mm(cfg.manageRenameLabel))
                            .tooltip(Text.mm(cfg.manageRenameTooltip))
                            .width(cfg.manageWidth)
                            .action(action(player, view -> openRename(player, slot)))
                            .build(),
                    ActionButton.builder(Text.mm(cfg.manageDescriptionLabel))
                            .tooltip(Text.mm(cfg.manageDescriptionTooltip))
                            .width(cfg.manageWidth)
                            .action(action(player, view -> openDescription(player, slot)))
                            .build(),
                    ActionButton.builder(Text.mm(cfg.manageDeleteLabel))
                            .tooltip(Text.mm(cfg.manageDeleteTooltip))
                            .width(cfg.manageWidth)
                            .action(action(player, view -> openDelete(player, slot)))
                            .build(),
                    ActionButton.builder(Text.mm(cfg.manageBackLabel))
                            .width(cfg.manageWidth)
                            .action(action(player, view -> openList(player, 0)))
                            .build());

            show(player, DialogBase.builder(Text.mm(cfg.manageTitle, homePlaceholders(home, coords, cfg)))
                            .canCloseWithEscape(true)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(body)
                            .build(),
                    DialogType.multiAction(buttons).build());
        });
    }

    public void openRename(Player player, int slot) {
        withHomes(player, owned -> {
            Home home = owned.bySlot(slot);
            if (home == null) {
                openList(player, 0);
                return;
            }
            CheeseConfig cfg = this.config.get();
            boolean coords = coordinatesVisible(owned, cfg);

            List<ActionButton> buttons = List.of(
                    ActionButton.builder(Text.mm(cfg.renameSaveLabel))
                            .width(cfg.renameWidth)
                            .action(action(player, view -> {
                                String typed = view.getText("newname");
                                HomeOperations.Result result = this.operations.rename(owned, home,
                                        typed == null ? "" : typed.trim());
                                if (result.status() == HomeOperations.Status.OK) {
                                    this.msg.send(player, "home-renamed", Text.ph("name", home.name()));
                                } else {
                                    this.operations.report(player, result);
                                }
                                openManage(player, slot);
                            }))
                            .build(),
                    ActionButton.builder(Text.mm(cfg.renameCancelLabel))
                            .width(cfg.renameWidth)
                            .action(action(player, view -> openManage(player, slot)))
                            .build());

            show(player, DialogBase.builder(Text.mm(cfg.renameTitle, homePlaceholders(home, coords, cfg)))
                            .canCloseWithEscape(true)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(List.of(DialogBody.item(ItemStack.of(home.icon())).showTooltip(false).build()))
                            .inputs(List.of(DialogInput.text("newname", Text.mm(cfg.renameInputLabel))
                                    .initial(home.name())
                                    .width(cfg.renameInputWidth)
                                    .maxLength(cfg.maxNameLength)
                                    .build()))
                            .build(),
                    DialogType.multiAction(buttons).columns(1).build());
        });
    }

    /** Free-text note on a home, shown on hover in the list. */
    public void openDescription(Player player, int slot) {
        withHomes(player, owned -> {
            Home home = owned.bySlot(slot);
            if (home == null) {
                openList(player, 0);
                return;
            }
            CheeseConfig cfg = this.config.get();
            boolean coords = coordinatesVisible(owned, cfg);

            List<ActionButton> buttons = List.of(
                    ActionButton.builder(Text.mm(cfg.describeSaveLabel))
                            .width(cfg.describeWidth)
                            .action(action(player, view -> {
                                String typed = view.getText("newdescription");
                                applyDescription(player, owned, home, typed);
                                openManage(player, slot);
                            }))
                            .build(),
                    ActionButton.builder(Text.mm(cfg.describeClearLabel))
                            .width(cfg.describeWidth)
                            .action(action(player, view -> {
                                applyDescription(player, owned, home, null);
                                openManage(player, slot);
                            }))
                            .build(),
                    ActionButton.builder(Text.mm(cfg.describeCancelLabel))
                            .width(cfg.describeWidth)
                            .action(action(player, view -> openManage(player, slot)))
                            .build());

            show(player, DialogBase.builder(Text.mm(cfg.describeTitle, homePlaceholders(home, coords, cfg)))
                            .canCloseWithEscape(true)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(List.of(DialogBody.item(ItemStack.of(home.icon())).showTooltip(false).build()))
                            .inputs(List.of(DialogInput.text("newdescription", Text.mm(cfg.describeInputLabel))
                                    .initial(home.description() == null ? "" : home.description())
                                    .width(cfg.describeInputWidth)
                                    .maxLength(cfg.maxDescriptionLength)
                                    .multiline(TextDialogInput.MultilineOptions.create(
                                            cfg.describeInputMaxLines, cfg.describeInputHeight))
                                    .build()))
                            .build(),
                    DialogType.multiAction(buttons).columns(1).build());
        });
    }

    private void applyDescription(Player player, PlayerHomes owned, Home home, String raw) {
        CheeseConfig cfg = this.config.get();
        String text = raw == null ? "" : raw.trim();
        if (text.length() > cfg.maxDescriptionLength) {
            this.msg.send(player, "description-too-long", Text.ph("max", cfg.maxDescriptionLength));
            return;
        }
        home.description(text);
        owned.markDirty();
        this.msg.send(player, text.isEmpty() ? "description-cleared" : "description-set",
                Text.ph("name", home.name()));
    }

    public void openDelete(Player player, int slot) {
        withHomes(player, owned -> {
            Home home = owned.bySlot(slot);
            if (home == null) {
                openList(player, 0);
                return;
            }
            CheeseConfig cfg = this.config.get();
            boolean coords = coordinatesVisible(owned, cfg);

            ActionButton confirm = ActionButton.builder(Text.mm(cfg.deleteConfirmLabel))
                    .width(cfg.manageWidth)
                    .action(action(player, view -> {
                        this.operations.delete(owned, home);
                        this.msg.send(player, "home-deleted", Text.ph("name", home.name()));
                        openList(player, 0);
                    }))
                    .build();
            ActionButton cancel = ActionButton.builder(Text.mm(cfg.deleteCancelLabel))
                    .width(cfg.manageWidth)
                    .action(action(player, view -> openManage(player, slot)))
                    .build();

            List<DialogBody> body = new ArrayList<>(2);
            body.add(DialogBody.item(ItemStack.of(home.icon())).showTooltip(false).build());
            if (!cfg.deleteBody.isBlank()) {
                body.add(DialogBody.plainMessage(Text.mm(cfg.deleteBody, homePlaceholders(home, coords, cfg))));
            }

            show(player, DialogBase.builder(Text.mm(cfg.deleteTitle, homePlaceholders(home, coords, cfg)))
                            .canCloseWithEscape(true)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(body)
                            .build(),
                    DialogType.confirmation(confirm, cancel));
        });
    }

    /** Searchable icon picker. Stays open so several icons can be tried out. */
    public void openIcons(Player player, int slot, String query, int page) {
        withHomes(player, owned -> {
            Home home = owned.bySlot(slot);
            if (home == null) {
                openList(player, 0);
                return;
            }
            CheeseConfig cfg = this.config.get();
            boolean coords = coordinatesVisible(owned, cfg);
            List<IconCatalog.Entry> hits = this.catalog.get().search(query);

            // page-size 0 means "everything in one dialog"; the client scrolls it.
            int pageSize = cfg.iconsPageSize;
            boolean paged = pageSize > 0;
            int pages = paged ? Math.max(1, (hits.size() + pageSize - 1) / pageSize) : 1;
            int current = paged ? Math.max(0, Math.min(page, pages - 1)) : 0;
            int from = paged ? current * pageSize : 0;
            int to = paged ? Math.min(hits.size(), from + pageSize) : hits.size();
            int lifetime = cfg.iconsCallbackLifetimeSeconds;

            TextColor selected = color(cfg.iconsSelectedColor, NamedTextColor.GREEN);
            TextColor unselected = color(cfg.iconsUnselectedColor, NamedTextColor.WHITE);

            List<ActionButton> buttons = new ArrayList<>((to - from) + 5);
            buttons.add(ActionButton.builder(Text.mm(cfg.iconsSearchButtonLabel))
                    .width(cfg.iconsControlWidth)
                    .action(action(player, lifetime, view -> {
                        String typed = view.getText("search");
                        openIcons(player, slot, typed == null ? "" : typed, 0);
                    }))
                    .build());
            buttons.add(ActionButton.builder(Text.mm(cfg.iconsDefaultLabel))
                    .width(cfg.iconsControlWidth)
                    .action(action(player, lifetime, view -> {
                        this.operations.setIcon(owned, home, cfg.defaultIcon);
                        this.msg.send(player, "icon-changed");
                        openIcons(player, slot, query, current);
                    }))
                    .build());
            buttons.add(ActionButton.builder(Text.mm(cfg.iconsBackLabel))
                    .width(cfg.iconsControlWidth)
                    .action(action(player, lifetime, view -> openManage(player, slot)))
                    .build());
            if (paged && current > 0) {
                buttons.add(ActionButton.builder(Text.mm(cfg.iconsPrevLabel))
                        .width(cfg.iconsControlWidth)
                        .action(action(player, lifetime, view -> openIcons(player, slot, query, current - 1)))
                        .build());
            }
            if (paged && current + 1 < pages) {
                buttons.add(ActionButton.builder(Text.mm(cfg.iconsNextLabel))
                        .width(cfg.iconsControlWidth)
                        .action(action(player, lifetime, view -> openIcons(player, slot, query, current + 1)))
                        .build());
            }

            for (int i = from; i < to; i++) {
                IconCatalog.Entry entry = hits.get(i);
                boolean isCurrent = entry.material() == home.icon();
                Component name = Component.translatable(entry.material().translationKey())
                        .color(isCurrent ? selected : unselected)
                        .decoration(TextDecoration.ITALIC, false);
                buttons.add(ActionButton.builder(withSprite(player, entry.material(), name))
                        .tooltip(Component.text(entry.id(), NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false))
                        .width(cfg.iconsButtonWidth)
                        .action(action(player, lifetime, view -> {
                            this.operations.setIcon(owned, home, entry.material());
                            this.msg.send(player, "icon-changed");
                            openIcons(player, slot, query, current);
                        }))
                        .build());
            }

            show(player, DialogBase.builder(Text.mm(cfg.iconsTitle, homePlaceholders(home, coords, cfg)))
                            .canCloseWithEscape(true)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(List.of(DialogBody.item(ItemStack.of(home.icon())).showTooltip(false).build()))
                            .inputs(List.of(DialogInput.text("search", Text.mm(cfg.iconsSearchLabel))
                                    .initial(query == null ? "" : query)
                                    .width(cfg.iconsSearchWidth)
                                    .maxLength(cfg.iconsSearchMaxLength)
                                    .build()))
                            .build(),
                    DialogType.multiAction(buttons).columns(cfg.iconsColumns).build());
        });
    }

    // === buttons ===========================================================

    private ActionButton entryButton(Player player, CheeseConfig cfg, Home home, boolean coords) {
        Component label = Text.mm(cfg.listEntryLabel, homePlaceholders(home, coords, cfg));
        return ActionButton.builder(cfg.spritesInList ? withSprite(player, home.icon(), label) : label)
                .tooltip(entryTooltip(cfg, home, coords))
                .width(cfg.listButtonWidth)
                .action(action(player, view -> openManage(player, home.slot())))
                .build();
    }

    private ActionButton emptyButton(Player player, CheeseConfig cfg, int slot, int step) {
        return ActionButton.builder(Text.mm(cfg.listEmptyLabel, Text.ph("index", slot + 1)))
                .tooltip(Text.mm(cfg.listEmptyTooltip, Text.ph("index", slot + 1)))
                .width(cfg.listButtonWidth)
                .action(action(player, view -> {
                    PlayerHomes owned = this.homes.cached(player.getUniqueId());
                    if (owned == null) {
                        this.msg.send(player, "still-loading");
                        return;
                    }
                    HomeOperations.Result result = this.operations.createInSlot(player, owned, slot);
                    this.operations.report(player, result);
                    if (result.ok()) {
                        openManage(player, slot);
                    } else {
                        openList(player, step);
                    }
                }))
                .build();
    }

    /** The list tooltip, with the description appended when there is one. */
    private Component entryTooltip(CheeseConfig cfg, Home home, boolean coords) {
        Component tooltip = Text.mm(cfg.listEntryTooltip, homePlaceholders(home, coords, cfg));
        if (home.hasDescription() && !cfg.listEntryTooltipDescription.isBlank()) {
            tooltip = tooltip.append(
                    Text.mm(cfg.listEntryTooltipDescription, homePlaceholders(home, coords, cfg)));
        }
        return tooltip;
    }

    /** No action at all - the client renders it, clicking does nothing. */
    private ActionButton lockedButton(CheeseConfig cfg) {
        return ActionButton.builder(Text.mm(cfg.listLockedLabel))
                .tooltip(Text.mm(cfg.listLockedTooltip))
                .width(cfg.listButtonWidth)
                .build();
    }

    // === plumbing ==========================================================

    private void show(Player player, DialogBase base, DialogType type) {
        Dialog dialog = Dialog.create(factory -> factory.empty().base(base).type(type));
        player.showDialog(dialog);
    }

    /** Prefixes a label with the material's atlas sprite, when one is available. */
    private Component withSprite(Player player, Material material, Component label) {
        Component sprite = this.sprites.icon(material, player);
        if (sprite == null) {
            return label;
        }
        return Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(sprite)
                .append(Component.space())
                .append(label);
    }

    private DialogAction action(Player player, Consumer<DialogResponseView> handler) {
        return action(player, this.config.get().callbackLifetimeSeconds, handler);
    }

    private DialogAction action(Player player, int lifetimeSeconds, Consumer<DialogResponseView> handler) {
        CheeseConfig cfg = this.config.get();
        ClickCallback.Options options = ClickCallback.Options.builder()
                .uses(cfg.callbackUses)
                .lifetime(Duration.ofSeconds(lifetimeSeconds))
                .build();
        return DialogAction.customClick((view, audience) ->
                player.getScheduler().run(this.plugin, task -> {
                    if (player.isOnline()) {
                        handler.accept(view);
                    }
                }, null), options);
    }

    /** Runs {@code action} with the player's homes, loading them first if needed. */
    private void withHomes(Player player, Consumer<PlayerHomes> action) {
        PlayerHomes cached = this.homes.cached(player.getUniqueId());
        if (cached != null) {
            action.accept(cached);
            return;
        }
        this.homes.load(player.getUniqueId()).thenAccept(loaded -> {
            if (loaded == null) {
                this.msg.send(player, "storage-error");
                return;
            }
            player.getScheduler().run(this.plugin, task -> {
                if (player.isOnline()) {
                    action.accept(loaded);
                }
            }, null);
        });
    }

    /**
     * Masking happens here rather than at each call site, so a coordinate cannot
     * leak through a label or tooltip an admin wrote themselves.
     */
    private static net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] homePlaceholders(
            Home home, boolean showCoordinates, CheeseConfig cfg) {
        String x = showCoordinates ? Long.toString(Math.round(home.x())) : cfg.hiddenCoordinate;
        String y = showCoordinates ? Long.toString(Math.round(home.y())) : cfg.hiddenCoordinate;
        String z = showCoordinates ? Long.toString(Math.round(home.z())) : cfg.hiddenCoordinate;
        return new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]{
                Text.ph("name", home.name()),
                Text.ph("index", home.slot() + 1),
                Text.ph("world", home.world()),
                Text.ph("x", x),
                Text.ph("y", y),
                Text.ph("z", z),
                Text.ph("icon", home.icon().getKey().toString()),
                Text.ph("description", home.description() == null ? "" : home.description())
        };
    }

    /** The player's own choice, falling back to the configured default. */
    private static boolean coordinatesVisible(PlayerHomes owned, CheeseConfig cfg) {
        Boolean choice = owned.showCoordinates();
        return choice == null ? cfg.showCoordinatesDefault : choice;
    }

    private static TextColor color(String raw, TextColor fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        if (raw.charAt(0) == '#') {
            TextColor hex = TextColor.fromCSSHexString(raw);
            return hex == null ? fallback : hex;
        }
        NamedTextColor named = NamedTextColor.NAMES.value(raw.toLowerCase(Locale.ROOT));
        return named == null ? fallback : named;
    }
}
