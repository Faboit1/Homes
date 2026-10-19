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
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
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
 *
 * <p>The same screens serve {@code /homeadmin see}: a {@link Target} says whose
 * homes are on screen and who is looking. Callbacks that change something look
 * the home up again instead of trusting what was captured at render time,
 * because an offline owner's homes can be evicted from the cache while an admin
 * still has the dialog open.
 */
public final class HomeDialogs {

    /**
     * Who is looking at whose homes. For a player's own menu both are the same
     * person; for an admin, {@code admin} lifts slot limits and the warm-up, and
     * {@code canEdit} decides whether anything beyond looking and teleporting is
     * offered.
     */
    public record Target(Player viewer, UUID owner, String ownerName, boolean admin, boolean canEdit) {

        public static Target self(Player player) {
            return new Target(player, player.getUniqueId(), player.getName(), false, true);
        }

        public static Target admin(Player viewer, UUID owner, String ownerName, boolean canEdit) {
            return new Target(viewer, owner, ownerName, true, canEdit);
        }
    }

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

    /** The player's own home grid. {@code step} indexes {@code dialog.list.expand-steps}. */
    public void openList(Player player, int step) {
        openList(Target.self(player), step);
    }

    /** The home grid. {@code step} indexes {@code dialog.list.expand-steps}. */
    public void openList(Target target, int step) {
        Player player = target.viewer();
        withHomes(target, owned -> {
            CheeseConfig cfg = this.config.get();
            int[] steps = cfg.listExpandSteps;
            int index = Math.max(0, Math.min(step, steps.length - 1));

            int highestUsed = 0;
            for (Home home : owned.all()) {
                highestUsed = Math.max(highestUsed, home.slot() + 1);
            }
            // An admin is not held to the owner's slot count - every slot up to
            // the hard cap is theirs to fill.
            int limit = target.admin() ? cfg.hardCap : this.limits.maxHomes(player);
            boolean coords = coordinatesVisible(target, owned, cfg);
            int visible = Math.min(cfg.hardCap, Math.max(steps[index], highestUsed));

            List<ActionButton> buttons = new ArrayList<>(visible + 1);
            for (int slot = 0; slot < visible; slot++) {
                Home home = owned.bySlot(slot);
                if (home != null) {
                    buttons.add(entryButton(target, cfg, home, coords));
                } else if (slot < limit) {
                    buttons.add(emptyButton(target, cfg, slot, index));
                } else {
                    buttons.add(lockedButton(cfg));
                }
            }
            if (index + 1 < steps.length && steps[index] < cfg.hardCap) {
                int next = index + 1;
                buttons.add(ActionButton.builder(Text.mm(cfg.listShowMoreLabel))
                        .tooltip(Text.mm(cfg.listShowMoreTooltip))
                        .width(cfg.listShowMoreWidth)
                        .action(action(player, view -> openList(target, next)))
                        .build());
            }

            List<DialogBody> body = new ArrayList<>(2);
            body.add(DialogBody.item(ItemStack.of(cfg.listIcon)).showTooltip(false).build());
            if (target.admin() && !cfg.adminListBody.isBlank()) {
                body.add(DialogBody.plainMessage(Text.mm(cfg.adminListBody,
                        Text.ph("owner", target.ownerName()),
                        Text.ph("count", owned.size()),
                        Text.ph("limit", ownerLimit(target)))));
            }

            String title = target.admin() ? cfg.adminListTitle : cfg.listTitle;
            show(player, DialogBase.builder(Text.mm(title, Text.ph("owner", target.ownerName())))
                            .canCloseWithEscape(true)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(body)
                            .build(),
                    DialogType.multiAction(buttons)
                            .columns(cfg.listColumns)
                            .exitAction(ActionButton.builder(Text.mm(cfg.listCloseLabel)).build())
                            .build());
        });
    }

    /** Teleport / Change Icon / Rename / Delete for one home. */
    public void openManage(Target target, int slot) {
        Player player = target.viewer();
        withHomes(target, owned -> {
            Home home = owned.bySlot(slot);
            if (home == null) {
                openList(target, 0);
                return;
            }
            CheeseConfig cfg = this.config.get();
            boolean coords = coordinatesVisible(target, owned, cfg);
            TagResolver[] placeholders = homePlaceholders(target, home, coords, cfg);

            List<DialogBody> body = new ArrayList<>(4);
            body.add(DialogBody.item(ItemStack.of(home.icon())).showTooltip(false).build());
            if (target.admin() && !cfg.adminManageBody.isBlank()) {
                body.add(DialogBody.plainMessage(Text.mm(cfg.adminManageBody, placeholders)));
            }
            String location = coords ? cfg.manageBody : cfg.manageBodyHidden;
            if (!location.isBlank()) {
                body.add(DialogBody.plainMessage(Text.mm(location, placeholders)));
            }
            if (home.hasDescription() && !cfg.manageDescriptionBody.isBlank()) {
                body.add(DialogBody.plainMessage(Text.mm(cfg.manageDescriptionBody, placeholders)));
            }

            List<ActionButton> buttons = new ArrayList<>(7);
            buttons.add(ActionButton.builder(Text.mm(cfg.manageTeleportLabel, placeholders))
                    .tooltip(Text.mm(target.admin() ? cfg.adminTeleportTooltip : cfg.manageTeleportTooltip,
                            placeholders))
                    .width(cfg.manageWidth)
                    .action(action(player, view -> withHome(target, slot, (fresh, current) -> {
                        if (target.admin()) {
                            this.teleports.teleportNow(player, current);
                        } else {
                            this.teleports.request(player, current);
                        }
                    })))
                    .build());
            if (target.canEdit()) {
                buttons.add(ActionButton.builder(Text.mm(cfg.manageIconLabel))
                        .tooltip(Text.mm(cfg.manageIconTooltip))
                        .width(cfg.manageWidth)
                        .action(action(player, view -> openIcons(target, slot, "", 0)))
                        .build());
                buttons.add(ActionButton.builder(Text.mm(cfg.manageRenameLabel))
                        .tooltip(Text.mm(cfg.manageRenameTooltip))
                        .width(cfg.manageWidth)
                        .action(action(player, view -> openRename(target, slot)))
                        .build());
                buttons.add(ActionButton.builder(Text.mm(cfg.manageDescriptionLabel))
                        .tooltip(Text.mm(cfg.manageDescriptionTooltip))
                        .width(cfg.manageWidth)
                        .action(action(player, view -> openDescription(target, slot)))
                        .build());
                if (target.admin()) {
                    buttons.add(ActionButton.builder(Text.mm(cfg.adminMoveLabel))
                            .tooltip(Text.mm(cfg.adminMoveTooltip, placeholders))
                            .width(cfg.manageWidth)
                            .action(action(player, view -> withHome(target, slot, (fresh, current) -> {
                                this.operations.moveTo(fresh, current, player.getLocation());
                                this.msg.send(player, "admin-home-moved",
                                        Text.ph("name", current.name()),
                                        Text.ph("player", target.ownerName()));
                                audit(target, "moved home '" + current.name() + "' to their own position");
                                openManage(target, slot);
                            })))
                            .build());
                }
                buttons.add(ActionButton.builder(Text.mm(cfg.manageDeleteLabel))
                        .tooltip(Text.mm(cfg.manageDeleteTooltip))
                        .width(cfg.manageWidth)
                        .action(action(player, view -> openDelete(target, slot)))
                        .build());
            }
            buttons.add(ActionButton.builder(Text.mm(cfg.manageBackLabel))
                    .width(cfg.manageWidth)
                    .action(action(player, view -> openList(target, 0)))
                    .build());

            show(player, DialogBase.builder(Text.mm(cfg.manageTitle, placeholders))
                            .canCloseWithEscape(true)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(body)
                            .build(),
                    DialogType.multiAction(buttons).build());
        });
    }

    public void openRename(Target target, int slot) {
        Player player = target.viewer();
        withHomes(target, owned -> {
            Home home = owned.bySlot(slot);
            if (home == null || !target.canEdit()) {
                openList(target, 0);
                return;
            }
            CheeseConfig cfg = this.config.get();
            boolean coords = coordinatesVisible(target, owned, cfg);

            List<ActionButton> buttons = List.of(
                    ActionButton.builder(Text.mm(cfg.renameSaveLabel))
                            .width(cfg.renameWidth)
                            .action(action(player, view -> withHome(target, slot, (fresh, current) -> {
                                String typed = view.getText("newname");
                                String before = current.name();
                                HomeOperations.Result result = this.operations.rename(fresh, current,
                                        typed == null ? "" : typed.trim());
                                if (result.status() == HomeOperations.Status.OK) {
                                    this.msg.send(player, "home-renamed", Text.ph("name", current.name()));
                                    audit(target, "renamed home '" + before + "' to '" + current.name() + "'");
                                } else {
                                    this.operations.report(player, result);
                                }
                                openManage(target, slot);
                            })))
                            .build(),
                    ActionButton.builder(Text.mm(cfg.renameCancelLabel))
                            .width(cfg.renameWidth)
                            .action(action(player, view -> openManage(target, slot)))
                            .build());

            show(player, DialogBase.builder(Text.mm(cfg.renameTitle, homePlaceholders(target, home, coords, cfg)))
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
    public void openDescription(Target target, int slot) {
        Player player = target.viewer();
        withHomes(target, owned -> {
            Home home = owned.bySlot(slot);
            if (home == null || !target.canEdit()) {
                openList(target, 0);
                return;
            }
            CheeseConfig cfg = this.config.get();
            boolean coords = coordinatesVisible(target, owned, cfg);

            List<ActionButton> buttons = List.of(
                    ActionButton.builder(Text.mm(cfg.describeSaveLabel))
                            .width(cfg.describeWidth)
                            .action(action(player, view -> withHome(target, slot, (fresh, current) -> {
                                applyDescription(target, fresh, current, view.getText("newdescription"));
                                openManage(target, slot);
                            })))
                            .build(),
                    ActionButton.builder(Text.mm(cfg.describeClearLabel))
                            .width(cfg.describeWidth)
                            .action(action(player, view -> withHome(target, slot, (fresh, current) -> {
                                applyDescription(target, fresh, current, null);
                                openManage(target, slot);
                            })))
                            .build(),
                    ActionButton.builder(Text.mm(cfg.describeCancelLabel))
                            .width(cfg.describeWidth)
                            .action(action(player, view -> openManage(target, slot)))
                            .build());

            show(player, DialogBase.builder(Text.mm(cfg.describeTitle, homePlaceholders(target, home, coords, cfg)))
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

    private void applyDescription(Target target, PlayerHomes owned, Home home, String raw) {
        Player player = target.viewer();
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
        audit(target, (text.isEmpty() ? "cleared" : "changed") + " the description of '" + home.name() + "'");
    }

    public void openDelete(Target target, int slot) {
        Player player = target.viewer();
        withHomes(target, owned -> {
            Home home = owned.bySlot(slot);
            if (home == null || !target.canEdit()) {
                openList(target, 0);
                return;
            }
            CheeseConfig cfg = this.config.get();
            boolean coords = coordinatesVisible(target, owned, cfg);
            TagResolver[] placeholders = homePlaceholders(target, home, coords, cfg);

            ActionButton confirm = ActionButton.builder(Text.mm(cfg.deleteConfirmLabel))
                    .width(cfg.manageWidth)
                    .action(action(player, view -> withHome(target, slot, (fresh, current) -> {
                        this.operations.delete(fresh, current);
                        this.msg.send(player, "home-deleted", Text.ph("name", current.name()));
                        audit(target, "deleted home '" + current.name() + "'");
                        openList(target, 0);
                    })))
                    .build();
            ActionButton cancel = ActionButton.builder(Text.mm(cfg.deleteCancelLabel))
                    .width(cfg.manageWidth)
                    .action(action(player, view -> openManage(target, slot)))
                    .build();

            List<DialogBody> body = new ArrayList<>(2);
            body.add(DialogBody.item(ItemStack.of(home.icon())).showTooltip(false).build());
            if (!cfg.deleteBody.isBlank()) {
                body.add(DialogBody.plainMessage(Text.mm(cfg.deleteBody, placeholders)));
            }

            show(player, DialogBase.builder(Text.mm(cfg.deleteTitle, placeholders))
                            .canCloseWithEscape(true)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(body)
                            .build(),
                    DialogType.confirmation(confirm, cancel));
        });
    }

    /** Searchable icon picker. Stays open so several icons can be tried out. */
    public void openIcons(Target target, int slot, String query, int page) {
        Player player = target.viewer();
        withHomes(target, owned -> {
            Home home = owned.bySlot(slot);
            if (home == null || !target.canEdit()) {
                openList(target, 0);
                return;
            }
            CheeseConfig cfg = this.config.get();
            boolean coords = coordinatesVisible(target, owned, cfg);
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
                        openIcons(target, slot, typed == null ? "" : typed, 0);
                    }))
                    .build());
            buttons.add(ActionButton.builder(Text.mm(cfg.iconsDefaultLabel))
                    .width(cfg.iconsControlWidth)
                    .action(action(player, lifetime, view -> withHome(target, slot, (fresh, picked) -> {
                        this.operations.setIcon(fresh, picked, cfg.defaultIcon);
                        this.msg.send(player, "icon-changed");
                        openIcons(target, slot, query, current);
                    })))
                    .build());
            buttons.add(ActionButton.builder(Text.mm(cfg.iconsBackLabel))
                    .width(cfg.iconsControlWidth)
                    .action(action(player, lifetime, view -> openManage(target, slot)))
                    .build());
            if (paged && current > 0) {
                buttons.add(ActionButton.builder(Text.mm(cfg.iconsPrevLabel))
                        .width(cfg.iconsControlWidth)
                        .action(action(player, lifetime, view -> openIcons(target, slot, query, current - 1)))
                        .build());
            }
            if (paged && current + 1 < pages) {
                buttons.add(ActionButton.builder(Text.mm(cfg.iconsNextLabel))
                        .width(cfg.iconsControlWidth)
                        .action(action(player, lifetime, view -> openIcons(target, slot, query, current + 1)))
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
                        .action(action(player, lifetime, view -> withHome(target, slot, (fresh, picked) -> {
                            this.operations.setIcon(fresh, picked, entry.material());
                            this.msg.send(player, "icon-changed");
                            openIcons(target, slot, query, current);
                        })))
                        .build());
            }

            show(player, DialogBase.builder(Text.mm(cfg.iconsTitle, homePlaceholders(target, home, coords, cfg)))
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

    private ActionButton entryButton(Target target, CheeseConfig cfg, Home home, boolean coords) {
        Player player = target.viewer();
        Component label = Text.mm(cfg.listEntryLabel, homePlaceholders(target, home, coords, cfg));
        int slot = home.slot();
        return ActionButton.builder(cfg.spritesInList ? withSprite(player, home.icon(), label) : label)
                .tooltip(entryTooltip(target, cfg, home, coords))
                .width(cfg.listButtonWidth)
                .action(action(player, view -> openManage(target, slot)))
                .build();
    }

    private ActionButton emptyButton(Target target, CheeseConfig cfg, int slot, int step) {
        Player player = target.viewer();
        ActionButton.Builder button = ActionButton.builder(Text.mm(cfg.listEmptyLabel, Text.ph("index", slot + 1)))
                .tooltip(Text.mm(target.admin() ? cfg.adminEmptyTooltip : cfg.listEmptyTooltip,
                        Text.ph("index", slot + 1), Text.ph("owner", target.ownerName())))
                .width(cfg.listButtonWidth);
        if (!target.canEdit()) {
            // A look-only admin sees the gap but cannot fill it.
            return button.build();
        }
        return button.action(action(player, view -> {
                    PlayerHomes owned = this.homes.cached(target.owner());
                    if (owned == null) {
                        this.msg.send(player, "still-loading");
                        return;
                    }
                    HomeOperations.Result result = target.admin()
                            ? this.operations.forceCreateInSlot(owned, slot, player.getLocation())
                            : this.operations.createInSlot(player, owned, slot);
                    if (target.admin() && result.ok()) {
                        this.msg.send(player, "admin-home-set",
                                Text.ph("name", result.home().name()),
                                Text.ph("player", target.ownerName()));
                        audit(target, "set home '" + result.home().name() + "' at their own position");
                    } else {
                        this.operations.report(player, result);
                    }
                    if (result.ok()) {
                        openManage(target, slot);
                    } else {
                        openList(target, step);
                    }
                }))
                .build();
    }

    /** The list tooltip, with the description appended when there is one. */
    private Component entryTooltip(Target target, CheeseConfig cfg, Home home, boolean coords) {
        TagResolver[] placeholders = homePlaceholders(target, home, coords, cfg);
        Component tooltip = Text.mm(cfg.listEntryTooltip, placeholders);
        if (home.hasDescription() && !cfg.listEntryTooltipDescription.isBlank()) {
            tooltip = tooltip.append(Text.mm(cfg.listEntryTooltipDescription, placeholders));
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

    /** Runs {@code action} with the owner's homes, loading them first if needed. */
    private void withHomes(Target target, Consumer<PlayerHomes> action) {
        Player player = target.viewer();
        PlayerHomes cached = this.homes.cached(target.owner());
        if (cached != null) {
            action.accept(cached);
            return;
        }
        this.homes.load(target.owner()).thenAccept(loaded -> {
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

    /** Re-resolves the home in {@code slot} right before changing it. */
    private void withHome(Target target, int slot, BiConsumer<PlayerHomes, Home> action) {
        withHomes(target, owned -> {
            Home home = owned.bySlot(slot);
            if (home == null) {
                openList(target, 0);
                return;
            }
            action.accept(owned, home);
        });
    }

    /** Admin edits land in the console so there is a trail of who changed what. */
    private void audit(Target target, String what) {
        if (target.admin()) {
            this.plugin.getLogger().log(Level.INFO, "[admin] " + target.viewer().getName() + " "
                    + what + " for " + target.ownerName() + " (" + target.owner() + ")");
        }
    }

    /** The owner's own slot count, which only resolves while they are online. */
    private String ownerLimit(Target target) {
        Player owner = Bukkit.getPlayer(target.owner());
        return owner == null ? "?" : Integer.toString(this.limits.maxHomes(owner));
    }

    /**
     * Masking happens here rather than at each call site, so a coordinate cannot
     * leak through a label or tooltip an admin wrote themselves.
     */
    private static TagResolver[] homePlaceholders(Target target, Home home, boolean showCoordinates,
                                                  CheeseConfig cfg) {
        String x = showCoordinates ? Long.toString(Math.round(home.x())) : cfg.hiddenCoordinate;
        String y = showCoordinates ? Long.toString(Math.round(home.y())) : cfg.hiddenCoordinate;
        String z = showCoordinates ? Long.toString(Math.round(home.z())) : cfg.hiddenCoordinate;
        return new TagResolver[]{
                Text.ph("name", home.name()),
                Text.ph("index", home.slot() + 1),
                Text.ph("world", home.world()),
                Text.ph("x", x),
                Text.ph("y", y),
                Text.ph("z", z),
                Text.ph("icon", home.icon().getKey().toString()),
                Text.ph("description", home.description() == null ? "" : home.description()),
                Text.ph("owner", target.ownerName())
        };
    }

    /**
     * The owner's own choice, falling back to the configured default. The
     * privacy switch is there to keep a base off a stream, not away from staff,
     * so an admin always sees coordinates.
     */
    private static boolean coordinatesVisible(Target target, PlayerHomes owned, CheeseConfig cfg) {
        if (target.admin()) {
            return true;
        }
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
