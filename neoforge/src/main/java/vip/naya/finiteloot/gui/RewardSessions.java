package vip.naya.finiteloot.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;

import vip.naya.finiteloot.claim.RewardPresenter;
import vip.naya.finiteloot.config.FinalClaimAction;
import vip.naya.finiteloot.config.MessageRenderer;
import vip.naya.finiteloot.config.PluginSettings;
import vip.naya.finiteloot.container.ContainerTarget;
import vip.naya.finiteloot.data.Database;
import vip.naya.finiteloot.loot.ItemStacks;

/**
 * Owns the personal-loot windows: opens them, tracks who has one open, and persists on close.
 *
 * <p>Port of Paper's {@code RewardSessionManager}. The shape is the same -- one session per player, a save
 * when a session ends -- but three mechanisms had to change:
 *
 * <table>
 *   <caption>Mechanism mapping</caption>
 *   <tr><th>Paper</th><th>Here</th><th>Why</th></tr>
 *   <tr><td>{@code InventoryClickEvent}</td><td>{@link RewardMenu} slot rules</td>
 *       <td>There is no cross-plugin click event; the menu is the authority.</td></tr>
 *   <tr><td>{@code InventoryCloseEvent}</td><td>{@code AbstractContainerMenu#removed}</td>
 *       <td>Called exactly once per menu, including on disconnect.</td></tr>
 *   <tr><td>{@code runTask} deferred save</td><td>save on close + explicit flush</td>
 *       <td>Slot changes are cheap to accumulate; one query per moved item is not.</td></tr>
 * </table>
 *
 * <p><b>Why saving happens on close and not on every change:</b> the reward inventory lives in memory while
 * it is open, so a player moving ten stacks would otherwise cause ten database writes. The risk is a crash
 * with a window open, which loses those moves -- accepted, and the same trade-off Paper made.
 */
public final class RewardSessions implements RewardPresenter {

    /** The title Paper used, kept verbatim so the GUI is recognisable across the migration. */
    private static final String TITLE = "<gold>FiniteLoot</gold> <gray>- Personal Loot</gray>";

    private final MinecraftServer server;
    private final Database database;
    private final Supplier<PluginSettings> settings;
    private final Logger logger;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public RewardSessions(MinecraftServer server, Database database, Supplier<PluginSettings> settings,
            Logger logger) {
        this.server = server;
        this.database = database;
        this.settings = settings;
        this.logger = logger;
    }

    @Override
    public void openPersonal(ServerPlayer player, UUID containerId, ItemStack[] contents,
            ContainerTarget target) {
        // Replace any window this player already has, so one player can never hold two reward sessions
        // (which would make the save order undefined).
        closeSession(player.getUUID());

        int size = normalizedSize(contents.length);
        int rows = size / 9;
        RewardInventory inventory = new RewardInventory(containerId, player.getUUID(), size, () -> {
        });
        inventory.fill(contents);

        Session session = new Session(player.getUUID(), containerId, inventory,
                ContainerPresentation.of(target));
        sessions.put(player.getUUID(), session);

        boolean blockInsertion = settings.get().preventItemInsertion();
        player.openMenu(new SimpleMenuProvider(
                (menuId, playerInventory, who) -> new RewardMenu(
                        menuId, playerInventory, inventory, rows, blockInsertion,
                        () -> endSession(session)),
                title()));
    }

    @Override
    public void openNormal(ServerPlayer player, ContainerTarget target) {
        Container container = target.sourceInventory();
        int size = container.getContainerSize();
        if (size <= 0 || size > 54 || size % 9 != 0) {
            // Vanilla chests are always a multiple of nine. A modded container with another size cannot be
            // shown through a chest menu without inventing a slot layout, so it is reported rather than
            // silently opened as something it is not. The generic answer belongs to the protection layer.
            logger.warn("Cannot open container {} as a chest: {} slots is not a multiple of nine",
                    target.id(), size);
            return;
        }
        int rows = size / 9;
        player.openMenu(new SimpleMenuProvider(
                (containerId, playerInventory, who) -> new ChestMenu(
                        menuTypeFor(rows), containerId, playerInventory, container, rows),
                title()));
        logger.info("Handed container {} back to vanilla: {} slots across {} rows", target.id(), size, rows);
    }

    @Override
    public void closeContainer(UUID containerId) {
        for (Session session : sessionsFor(containerId)) {
            ServerPlayer player = server.getPlayerList().getPlayer(session.playerId);
            if (player != null) {
                player.closeContainer();
            }
        }
    }

    @Override
    public CompletableFuture<Void> closeContainerAndSave(UUID containerId) {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (Session session : sessionsFor(containerId)) {
            pending.add(session.saved);
            ServerPlayer player = server.getPlayerList().getPlayer(session.playerId);
            if (player != null) {
                // Closing the menu is what triggers the save; the future completes when that write lands.
                player.closeContainer();
            } else {
                // Already disconnected: the menu's removal ran then, so this is only a safety net for a
                // session that somehow outlived its player.
                endSession(session);
            }
        }
        if (pending.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
    }

    /**
     * Saves and closes everything. Called when the server stops, so a player with a window open does not
     * lose what they moved.
     */
    public CompletableFuture<Void> flushAll() {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (Session session : List.copyOf(sessions.values())) {
            pending.add(session.saved);
            ServerPlayer player = server.getPlayerList().getPlayer(session.playerId);
            if (player != null) {
                player.closeContainer();
            } else {
                endSession(session);
            }
        }
        if (pending.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
    }

    /** How many windows are open; used by the P5 probe to assert that sessions do not leak. */
    public int openSessionCount() {
        return sessions.size();
    }

    private List<Session> sessionsFor(UUID containerId) {
        List<Session> matches = new ArrayList<>();
        for (Session session : sessions.values()) {
            if (session.containerId.equals(containerId)) {
                matches.add(session);
            }
        }
        return matches;
    }

    /**
     * Swings the lid shut and plays the closing sound, if either is enabled.
     *
     * <p>Wrapped in a try/catch on purpose: this runs while a menu is being torn down, possibly because the
     * player disconnected or the server is stopping. A presentation failure must not prevent the inventory from
     * being written out -- that would turn a cosmetic problem into lost loot.
     */
    private void closePresentation(ServerPlayer viewer, Session session) {
        try {
            if (!(viewer.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                return;
            }
            if (settings.get().playContainerAnimation()) {
                session.presentation.animate(level, false);
            }
            if (settings.get().playContainerSounds()) {
                session.presentation.playSound(level, false);
            }
        } catch (RuntimeException exception) {
            logger.warn("Could not close the container animation for {}", session.containerId, exception);
        }
    }

    private void closeSession(UUID playerId) {
        Session session = sessions.get(playerId);
        if (session == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.closeContainer();
        } else {
            endSession(session);
        }
    }

    /**
     * Ends a session and writes it out. Idempotent: a menu's removal, an explicit close and a server stop
     * can all reach the same session, and only the first one should write.
     */
    private void endSession(Session session) {
        if (session.ended) {
            return;
        }
        session.ended = true;
        sessions.remove(session.playerId, session);

        // Close the lid and the sound here rather than in the menu's removal: every close path (menu removed,
        // explicit close, server stop) ends up in this method, and only the first one gets past the guard above.
        ServerPlayer viewer = server.getPlayerList().getPlayer(session.playerId);
        if (viewer != null) {
            closePresentation(viewer, session);
        }
        ItemStack[] contents = session.inventory.copyContents();
        byte[] bytes;
        try {
            bytes = ItemStacks.serialize(contents, server.registryAccess());
        } catch (Exception exception) {
            logger.error("Could not serialise the reward inventory of {} for {}",
                    session.playerId, session.containerId, exception);
            session.saved.complete(null);
            return;
        }

        PluginSettings current = settings.get();
        boolean personalCompletion = current.finalClaimAction() == FinalClaimAction.PERSONAL
                && current.completedContainerBecomesNormal();
        boolean completed = ItemStacks.isEmpty(contents)
                && (current.preventItemInsertion() || personalCompletion);

        database.saveInventory(session.containerId, session.playerId, bytes, completed)
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        logger.error("Failed to save the reward inventory of {} for {}",
                                session.playerId, session.containerId, throwable);
                    }
                    session.saved.complete(null);
                });
    }

    /**
     * Rounds a container size up to a whole number of rows, clamped to what a chest menu can show.
     *
     * <p>Same rule as Paper's {@code normalizedSize}: a 20-slot container becomes three rows. Rounding up
     * rather than down is the only option that cannot drop contents.
     */
    static int normalizedSize(int requested) {
        int bounded = Math.max(9, Math.min(54, requested));
        return ((bounded + 8) / 9) * 9;
    }

    /**
     * The window title, exposed so a probe can assert the rendered plain text. The client only shows it; the
     * bot used for verification decodes the component naively and reports the first string leaf, which is a
     * colour name -- so the text is checked here instead.
     */
    public static Component personalTitle() {
        return MessageRenderer.render(TITLE, Map.of());
    }

    private static Component title() {
        return personalTitle();
    }

    private static net.minecraft.world.inventory.MenuType<?> menuTypeFor(int rows) {
        return switch (rows) {
            case 1 -> net.minecraft.world.inventory.MenuType.GENERIC_9x1;
            case 2 -> net.minecraft.world.inventory.MenuType.GENERIC_9x2;
            case 3 -> net.minecraft.world.inventory.MenuType.GENERIC_9x3;
            case 4 -> net.minecraft.world.inventory.MenuType.GENERIC_9x4;
            case 5 -> net.minecraft.world.inventory.MenuType.GENERIC_9x5;
            case 6 -> net.minecraft.world.inventory.MenuType.GENERIC_9x6;
            default -> throw new IllegalArgumentException("Unsupported rows: " + rows);
        };
    }

    private static final class Session {
        private final UUID playerId;
        private final UUID containerId;
        private final RewardInventory inventory;
        private final CompletableFuture<Void> saved = new CompletableFuture<>();
        /**
         * Captured when the window opens, because by the time it closes the block may be gone. Held as its own
         * list of positions plus sounds rather than as a {@code ContainerTarget}: the target points at live block
         * entities, and keeping one alive for the whole session would pin a chunk's worth of state for a window
         * that only needs to know where to swing a lid.
         */
        private final ContainerPresentation presentation;
        private boolean ended;

        private Session(UUID playerId, UUID containerId, RewardInventory inventory,
                ContainerPresentation presentation) {
            this.playerId = playerId;
            this.containerId = containerId;
            this.inventory = inventory;
            this.presentation = presentation;
        }
    }
}
