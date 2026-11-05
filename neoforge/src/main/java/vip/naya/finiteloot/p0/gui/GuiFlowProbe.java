package vip.naya.finiteloot.p0.gui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import vip.naya.finiteloot.claim.ClaimController;
import vip.naya.finiteloot.config.ConfigManager;
import vip.naya.finiteloot.config.Messages;
import vip.naya.finiteloot.container.ContainerManager;
import vip.naya.finiteloot.container.ContainerTarget;
import vip.naya.finiteloot.container.ExhaustedContainers;
import vip.naya.finiteloot.data.ContainerRecord;
import vip.naya.finiteloot.data.Database;
import vip.naya.finiteloot.gui.RewardMenu;
import vip.naya.finiteloot.gui.RewardSessions;
import vip.naya.finiteloot.loot.ItemStacks;
import vip.naya.finiteloot.loot.LootAdvancementService;
import vip.naya.finiteloot.loot.LootGenerator;
import vip.naya.finiteloot.p0.ProbeGate;
import vip.naya.finiteloot.p0.ProbeMod;

/**
 * P5: exercises the real personal-loot window against a real database and a real player.
 *
 * <p>The interesting question is not "does a chest screen appear" -- P0-2 settled that. It is whether the
 * window is <em>safe</em>: a reward inventory that accepts items is an item-duplication machine, because
 * the contents are written to the database on close and replayed into the real container on the final
 * claim. So most of this probe is attempts to put things in through every path vanilla offers, each of
 * which must fail.
 *
 * <p>Everything is driven server-side by calling the same entry points the game calls
 * ({@code AbstractContainerMenu#clicked}, {@code quickMoveStack}, {@code closeContainer}), so no clicking
 * client is needed and the assertions are about server state -- which is the state that decides what lands
 * in the database.
 *
 * <p>Judged by grepping for the {@code [P5} prefix.
 */
@EventBusSubscriber(modid = ProbeMod.MOD_ID)
public final class GuiFlowProbe {

    // Near spawn on purpose. The first version used x=180/200, which made the server generate two fresh
    // chunk columns synchronously: the log showed "Running 16303ms or 326 ticks behind", which ate most of
    // the probe client's 20-second session before the first assertion could run. These columns are inside
    // the area the server prepares at startup, so the rig costs no generation.
    //
    // They are also within five blocks of where the probe teleports the player. That matters: a vanilla
    // ChestMenu consults the block's own stillValid, which is a distance check, so opening the real
    // container from thirty blocks away opens and is then closed again within a tick. The first version of
    // this probe read that as "the handover never happened".
    private static final BlockPos PERSONAL_POS = new BlockPos(8, 70, 40);
    private static final BlockPos VANILLA_POS = new BlockPos(18, 70, 40);
    private static final BlockPos THIRD_POS = new BlockPos(28, 70, 40);
    private static final double PLAYER_X = 13.5;
    private static final double PLAYER_Y = 70.0;
    private static final double PLAYER_Z = 40.5;
    private static final Path CONFIG_DIR = Path.of("config", "finiteloot-p5");
    private static final Path DATA_DIR = Path.of("finiteloot-p5");

    private static Scenario scenario;
    private static boolean started;

    private GuiFlowProbe() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!ProbeGate.enabled("p5")) {
            return;
        }
        if (started || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        started = true;
        try {
            scenario = new Scenario(player);
        } catch (Throwable throwable) {
            ProbeMod.LOG.error("[P5] setup FAILED", throwable);
            scenario = null;
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (scenario == null) {
            return;
        }
        try {
            if (scenario.tick()) {
                scenario = null;
            }
        } catch (Throwable throwable) {
            ProbeMod.LOG.error("[P5] FAILED with exception", throwable);
            scenario.close();
            scenario = null;
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (scenario != null) {
            scenario.close();
            scenario = null;
        }
    }

    private static final class Scenario {

        private final ServerPlayer player;
        private final MinecraftServer server;
        private final Database database;
        private final ContainerManager containers;
        private final RewardSessions sessions;
        private final ClaimController controller;
        private final ConfigManager config;

        private int phase;
        private int waitTicks;
        private int ticksInPhase;
        private UUID personalId;
        private UUID vanillaId;
        private UUID thirdId;
        private int vanillaBlockSlots = -1;
        private int failures;
        private int lootCountBefore;
        private String contentsBefore;

        Scenario(ServerPlayer player) throws Exception {
            this.player = player;
            this.server = player.server;

            Files.createDirectories(CONFIG_DIR);
            Files.writeString(CONFIG_DIR.resolve("config.yml"), """
                    default-max-claims: 3
                    show-remaining-claims: false
                    show-final-claim-message: false
                    clear-personal-inventories-on-final-claim: false
                    completed-container-becomes-normal: true
                    prevent-item-insertion: true
                    exhausted-action: DENY
                    per-player-limit: 1
                    final-claim-action: VANILLA_CONTAINER
                    loot-table-overrides:
                      minecraft:chests/simple_dungeon: 2
                      minecraft:chests/abandoned_mineshaft: 1
                    """, StandardCharsets.UTF_8);
            ConfigManager config = new ConfigManager(CONFIG_DIR);
            config.load();
            this.config = config;

            Files.createDirectories(DATA_DIR);
            this.database = new Database(DATA_DIR.resolve("claims.db"));
            database.initialize();

            this.containers = new ContainerManager(ProbeMod.LOG, config.settings()::isWorldExcluded);
            this.sessions = new RewardSessions(server, database, config::settings, ProbeMod.LOG);
            this.controller = new ClaimController(
                    server, database, containers, new ExhaustedContainers(), sessions,
                    new LootGenerator(), new LootAdvancementService(),
                    config::settings, () -> new Messages(config.language()), ProbeMod.LOG);
        }

        boolean tick() throws Exception {
            ServerLevel level = server.overworld();

            // Fail loudly instead of hanging. The first version returned false forever when a precondition
            // never became true (the client had disconnected), and the run simply stopped logging with no
            // verdict -- the worst possible outcome for a probe.
            if (server.getPlayerList().getPlayer(player.getUUID()) != player || !player.isAlive()) {
                ProbeMod.LOG.error("[P5] FAILED - player left during phase {} (menu={})",
                        phase, menuName());
                close();
                return true;
            }
            if (++ticksInPhase > 400) {
                ProbeMod.LOG.error("[P5] FAILED - phase {} stuck for 400 ticks (menu={} sessions={})",
                        phase, menuName(), sessions.openSessionCount());
                close();
                return true;
            }
            switch (phase) {
                case 0 -> {
                    placeChest(level, PERSONAL_POS, "chests/simple_dungeon");
                    placeChest(level, VANILLA_POS, "chests/abandoned_mineshaft");
                    // Stand between the two containers so that distance-based validity (the vanilla chest
                    // menu's own rule) cannot close the window we are about to assert on.
                    player.teleportTo(level, PLAYER_X, PLAYER_Y, PLAYER_Z, 0.0F, 0.0F);
                    ContainerTarget personal = containers.resolve(level, PERSONAL_POS).orElseThrow();
                    ContainerTarget vanilla = containers.resolve(level, VANILLA_POS).orElseThrow();
                    personalId = personal.id();
                    vanillaId = vanilla.id();
                    // Recorded here, before the handover. After it, resolve() returns empty on purpose (a
                    // handed-back container must not be taken over again), so asking afterwards yields -1.
                    vanillaBlockSlots = vanilla.sourceInventory().getContainerSize();
                    ProbeMod.LOG.info("[P5] rig ready: personal={} (limit 2) vanilla={} (limit 1)",
                            personalId, vanillaId);

                    controller.open(player, personal);
                    phase = 1;
                }
                case 1 -> {
                    if (!(player.containerMenu instanceof RewardMenu menu)) {
                        return false;
                    }
                    int rows = menu.rewards().getContainerSize() / 9;
                    int filled = countNonEmpty(menu);
                    boolean rightMenuType = player.containerMenu.getType()
                            == net.minecraft.world.inventory.MenuType.GENERIC_9x3;
                    String titleText = vip.naya.finiteloot.gui.RewardSessions.personalTitle().getString();
                    boolean rightTitle = "FiniteLoot - Personal Loot".equals(titleText);
                    ProbeMod.LOG.info("[P5] personal window open: rows={} slots={} filledSlots={} "
                                    + "sessionCount={} menuType9x3={} title='{}'",
                            rows, menu.rewards().getContainerSize(), filled,
                            sessions.openSessionCount(), rightMenuType, titleText);
                    boolean pass = rows == 3 && filled > 0 && sessions.openSessionCount() == 1
                            && rightMenuType && rightTitle;
                    check("open personal", pass);
                    lootCountBefore = filled;
                    contentsBefore = fingerprint(menu);
                    wait(2);
                    phase = 2;
                }
                case 2 -> {
                    // Every insertion route vanilla exposes, on a window that should refuse all of them.
                    if (!settled() || !(player.containerMenu instanceof RewardMenu menu)) {
                        return !settled();
                    }
                    ItemStack bait = new ItemStack(Items.DIAMOND, 5);

                    // (a) cursor -> empty top slot
                    int emptyTop = firstEmptyTopSlot(menu);
                    menu.setCarried(bait.copy());
                    menu.clicked(emptyTop, 0, ClickType.PICKUP, player);
                    boolean cursorPlaceBlocked = menu.getSlot(emptyTop).getItem().isEmpty()
                            && menu.getCarried().getCount() == 5;
                    menu.setCarried(ItemStack.EMPTY);

                    // (b) shift-click from the player inventory into the top
                    int playerSlot = firstNonEmptyPlayerSlot(menu);
                    ItemStack before = menu.getSlot(playerSlot).getItem().copy();
                    menu.quickMoveStack(player, playerSlot);
                    boolean shiftFromPlayerBlocked = ItemStack.matches(before, menu.getSlot(playerSlot).getItem())
                            && countNonEmpty(menu) == lootCountBefore;

                    // (c) hotbar swap (number key) onto a filled top slot
                    int filledTop = firstNonEmptyTopSlot(menu);
                    ItemStack topBefore = menu.getSlot(filledTop).getItem().copy();
                    menu.clicked(filledTop, 0, ClickType.SWAP, player);
                    boolean hotbarSwapBlocked = ItemStack.matches(topBefore, menu.getSlot(filledTop).getItem());

                    // (d) drag: QUICK_CRAFT distributes the cursor stack over slots
                    menu.setCarried(bait.copy());
                    menu.clicked(-999, 0, ClickType.QUICK_CRAFT, player);
                    menu.clicked(emptyTop, 1, ClickType.QUICK_CRAFT, player);
                    menu.clicked(-999, 2, ClickType.QUICK_CRAFT, player);
                    boolean dragBlocked = menu.getSlot(emptyTop).getItem().isEmpty();
                    menu.setCarried(ItemStack.EMPTY);

                    ProbeMod.LOG.info("[P5] insertion attempts: cursorPlace={} shiftFromPlayer={} "
                                    + "hotbarSwap={} drag={} filledSlots={} (was {})",
                            cursorPlaceBlocked, shiftFromPlayerBlocked, hotbarSwapBlocked, dragBlocked,
                            countNonEmpty(menu), lootCountBefore);
                    boolean pass = cursorPlaceBlocked && shiftFromPlayerBlocked && hotbarSwapBlocked
                            && dragBlocked && countNonEmpty(menu) == lootCountBefore;
                    check("insertion blocked", pass);
                    wait(2);
                    phase = 3;
                }
                case 3 -> {
                    // Extraction has to keep working, or the player cannot claim anything.
                    if (!settled() || !(player.containerMenu instanceof RewardMenu menu)) {
                        return !settled();
                    }
                    int filledTop = firstNonEmptyTopSlot(menu);
                    ItemStack expected = menu.getSlot(filledTop).getItem().copy();
                    menu.setCarried(ItemStack.EMPTY);
                    menu.clicked(filledTop, 0, ClickType.PICKUP, player);
                    boolean taken = menu.getSlot(filledTop).getItem().isEmpty()
                            && ItemStack.matches(expected, menu.getCarried());
                    ProbeMod.LOG.info("[P5] extraction: slotEmptied={} carried={}",
                            menu.getSlot(filledTop).getItem().isEmpty(),
                            menu.getCarried().getCount());
                    check("extraction", taken);
                    // Leave the cursor empty so the close path saves only what is left in the window.
                    menu.setCarried(ItemStack.EMPTY);
                    wait(2);
                    phase = 4;
                }
                case 4 -> {
                    if (!settled()) {
                        return false;
                    }
                    // Snapshot AFTER the extraction, not before: comparing against the pre-extraction
                    // fingerprint made the reopen check fail for the right reason but the wrong assertion.
                    int stillInWindow = countNonEmpty(player.containerMenu);
                    contentsBefore = fingerprint(player.containerMenu);
                    player.closeContainer();
                    wait(4);
                    phase = 5;
                    lootCountBefore = stillInWindow;
                }
                case 5 -> {
                    if (!settled()) {
                        return false;
                    }
                    boolean closed = sessions.openSessionCount() == 0;
                    ProbeMod.LOG.info("[P5] after close: sessions={} windowHadSlots={}",
                            sessions.openSessionCount(), lootCountBefore);
                    check("session released on close", closed);

                    // Reopening is the persistence check: the contents have to come back out of the
                    // database, not out of the menu that was just closed.
                    controller.open(player, containers.resolve(server.overworld(), PERSONAL_POS).orElseThrow());
                    wait(6);
                    phase = 6;
                }
                case 6 -> {
                    if (!settled() || !(player.containerMenu instanceof RewardMenu menu)) {
                        return !settled();
                    }
                    int reopened = countNonEmpty(menu);
                    String fingerprint = fingerprint(menu);
                    ProbeMod.LOG.info("[P5] reopened window: filledSlots={} sessions={} sameAsBefore={}",
                            reopened, sessions.openSessionCount(), contentsBefore.equals(fingerprint));
                    boolean pass = reopened == lootCountBefore && contentsBefore.equals(fingerprint);
                    check("reopen replays saved state", pass);
                    player.closeContainer();
                    wait(4);
                    phase = 7;
                }
                case 7 -> {
                    if (!settled()) {
                        return false;
                    }
                    // The vanilla-facing branch: limit 1 means the first claim retires the container, and
                    // the player must end up looking at the REAL block, not a personal window.
                    controller.open(player, containers.resolve(server.overworld(), VANILLA_POS).orElseThrow());
                    wait(6);
                    phase = 8;
                }
                case 8 -> {
                    if (!settled()) {
                        return false;
                    }
                    AbstractContainerMenu menu = player.containerMenu;
                    // The final-claim path is asynchronous: the container is opened from the completion of
                    // the database write, so the window is not there on the very next tick. Retrying is the
                    // point -- the previous version checked once, saw the player's own 46-slot inventory
                    // menu, and reported PASS for "shows the vanilla container" when it showed nothing.
                    if (!(menu instanceof net.minecraft.world.inventory.ChestMenu) || menu instanceof RewardMenu) {
                        return false;
                    }
                    boolean isRewardWindow = menu instanceof RewardMenu;
                    boolean isChestMenu = true;
                    int menuSlots = menu.slots.size();
                    int realSlots = countNonEmpty(menu);
                    int blockSlots = vanillaBlockSlots;
                    ProbeMod.LOG.info("[P5] final-claim window: rewardWindow={} chestMenu={} menuSlots={} "
                                    + "expectedSlots={} filled={} sessions={}",
                            isRewardWindow, isChestMenu, menuSlots, blockSlots + 36, realSlots,
                            sessions.openSessionCount());
                    boolean pass = !isRewardWindow && blockSlots > 0
                            && menuSlots == blockSlots + 36 && realSlots > 0;
                    check("final claim shows the vanilla container", pass);

                    // R28 follow-up: this run has clear-personal-inventories-on-final-claim: false, so the
                    // container row and its claims must still be there afterwards. What matters for safety
                    // is the next phase: a retained row must not make the container claimable again.
                    check("container row retained when clear-on-final-claim is false",
                            database.findContainer(vanillaId).join() != null);
                    player.closeContainer();
                    phase = 9;
                }
                case 9 -> {
                    if (!settled()) {
                        return false;
                    }
                    // The anti-duplication question in one line: the identity marker is what gates takeover,
                    // so after the handover the container must resolve to nothing even though its database
                    // row still says count == max with a stored blob.
                    boolean stillManaged = containers.resolve(server.overworld(), VANILLA_POS).isPresent();
                    check("handed-back container cannot be resolved again", !stillManaged);

                    // Flip the setting and prove the other branch removes the rows.
                    Files.writeString(CONFIG_DIR.resolve("config.yml"), """
                            default-max-claims: 3
                            show-remaining-claims: false
                            show-final-claim-message: false
                            clear-personal-inventories-on-final-claim: true
                            completed-container-becomes-normal: true
                            prevent-item-insertion: true
                            exhausted-action: DENY
                            per-player-limit: 1
                            final-claim-action: VANILLA_CONTAINER
                            loot-table-overrides:
                              minecraft:chests/simple_dungeon: 2
                              minecraft:chests/abandoned_mineshaft: 1
                              minecraft:chests/nether_bridge: 1
                            """, StandardCharsets.UTF_8);
                    config.load();
                    check("setting flipped to clear-on-final-claim: true",
                            config.settings().clearPersonalInventoriesOnFinalClaim());

                    placeChest(server.overworld(), THIRD_POS, "chests/nether_bridge");
                    ContainerTarget third = containers.resolve(server.overworld(), THIRD_POS).orElseThrow();
                    thirdId = third.id();
                    // Within reach, or the vanilla chest menu closes the moment it opens (R27).
                    player.teleportTo(server.overworld(), 23.0, 70.0, 40.5, 0.0F, 0.0F);
                    controller.open(player, third);
                    phase = 10;
                }
                case 10 -> {
                    if (!settled()) {
                        return false;
                    }
                    if (!(player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu)
                            || player.containerMenu instanceof RewardMenu) {
                        return false;
                    }
                    // The delete is async, so retry rather than assert once.
                    if (database.findContainer(thirdId).join() != null) {
                        return false;
                    }
                    check("container row removed when clear-on-final-claim is true", true);
                    check("no reward sessions left behind", sessions.openSessionCount() == 0);

                    ProbeMod.LOG.info("[P5] {} - personal loot window works and refuses insertion",
                            failures == 0 ? "SUCCESS" : "FAILURE (see FAIL lines above)");
                    player.closeContainer();
                    close();
                    return true;
                }
                default -> {
                    return true;
                }
            }
            return false;
        }

        private void check(String what, boolean ok) {
            if (!ok) {
                failures++;
            }
            ProbeMod.LOG.info("[P5] {} {}", what, ok ? "PASS" : "FAIL");
        }

        private void wait(int ticks) {
            // Settling is now ownership of the phase precondition rather than a fixed sleep: a phase that
            // is not ready returns false and is retried next tick, so there is nothing to wait for.
            waitTicks = 0;
            ticksInPhase = 0;
        }

        private boolean settled() {
            return true;
        }

        private String menuName() {
            return player.containerMenu == null ? "null" : player.containerMenu.getClass().getSimpleName();
        }

        private int countNonEmpty(AbstractContainerMenu menu) {
            if (menu == null) {
                return -1;
            }
            int total = 0;
            for (int slot = 0; slot < menu.slots.size(); slot++) {
                if (!menu.getSlot(slot).getItem().isEmpty()) {
                    total++;
                }
            }
            return total;
        }

        private String fingerprint(AbstractContainerMenu menu) {
            StringBuilder text = new StringBuilder();
            for (int slot = 0; slot < menu.slots.size(); slot++) {
                ItemStack stack = menu.getSlot(slot).getItem();
                if (!stack.isEmpty()) {
                    text.append(stack.getItem()).append('x').append(stack.getCount()).append(';');
                }
            }
            return text.toString();
        }

        private int firstEmptyTopSlot(RewardMenu menu) {
            int top = menu.rewards().getContainerSize();
            for (int slot = 0; slot < top; slot++) {
                if (menu.getSlot(slot).getItem().isEmpty()) {
                    return slot;
                }
            }
            return -1;
        }

        private int firstNonEmptyTopSlot(RewardMenu menu) {
            int top = menu.rewards().getContainerSize();
            for (int slot = 0; slot < top; slot++) {
                if (!menu.getSlot(slot).getItem().isEmpty()) {
                    return slot;
                }
            }
            return -1;
        }

        private int firstNonEmptyPlayerSlot(RewardMenu menu) {
            for (int slot = menu.rewards().getContainerSize(); slot < menu.slots.size(); slot++) {
                if (!menu.getSlot(slot).getItem().isEmpty()) {
                    return slot;
                }
            }
            return menu.slots.size() - 1;
        }

        private void placeChest(ServerLevel level, BlockPos pos, String lootTablePath) {
            BlockState state = Blocks.CHEST.defaultBlockState()
                    .setValue(ChestBlock.FACING, Direction.NORTH)
                    .setValue(ChestBlock.TYPE, ChestType.SINGLE);
            level.getChunk(pos);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(pos, state, 3);
            BlockEntity tile = level.getBlockEntity(pos);
            if (tile instanceof RandomizableContainerBlockEntity lootable) {
                lootable.setLootTable(ResourceKey.create(Registries.LOOT_TABLE,
                        ResourceLocation.withDefaultNamespace(lootTablePath)));
                tile.setChanged();
            }
        }

        void close() {
            try {
                sessions.flushAll();
                // The database is deliberately NOT closed here. Closing it shuts down its executor, and the
                // claim path still has queued callbacks: the previous version produced
                // "RejectedExecutionException ... Terminated" from Database.submit right after the probe
                // printed its verdict. The process is about to exit anyway.
            } catch (RuntimeException exception) {
                ProbeMod.LOG.warn("[P5] closing the probe failed", exception);
            }
        }
    }
}
