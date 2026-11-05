package vip.naya.finiteloot.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import vip.naya.finiteloot.claim.ClaimStateMachine.ClaimContext;
import vip.naya.finiteloot.config.FinalClaimAction;
import vip.naya.finiteloot.data.ClaimAllocation;

/**
 * Exhaustive tests for the claim state machine.
 *
 * <p>This is the part of the port that is easiest to get subtly wrong and hardest to notice: a wrong
 * branch here either strands a player with a container they cannot open, or hands out loot twice. Because
 * the machine is pure, every combination can simply be enumerated.
 */
class ClaimStateMachineTest {

    /** A context builder with the shipped defaults, so each test only states what it changes. */
    private static ContextBuilder defaultContext() {
        return new ContextBuilder();
    }

    private static final class ContextBuilder {
        private ClaimAllocation.Kind kind = ClaimAllocation.Kind.NEW;
        private boolean counted = true;
        private int claimCount;
        private int maxClaims = 3;
        private boolean hasContentsBlob = true;
        private boolean contentsEmpty;
        private FinalClaimAction finalClaimAction = FinalClaimAction.VANILLA_CONTAINER;
        private boolean completedContainerBecomesNormal = true;
        private boolean preventItemInsertion;
        private boolean allowBreakingExhaustedContainers;

        ContextBuilder kind(ClaimAllocation.Kind value) {
            this.kind = value;
            return this;
        }

        ContextBuilder counted(boolean value) {
            this.counted = value;
            return this;
        }

        ContextBuilder claims(int count, int max) {
            this.claimCount = count;
            this.maxClaims = max;
            return this;
        }

        ContextBuilder blob(boolean present) {
            this.hasContentsBlob = present;
            return this;
        }

        ContextBuilder empty(boolean value) {
            this.contentsEmpty = value;
            return this;
        }

        ContextBuilder finalAction(FinalClaimAction value) {
            this.finalClaimAction = value;
            return this;
        }

        ContextBuilder becomesNormal(boolean value) {
            this.completedContainerBecomesNormal = value;
            return this;
        }

        ContextBuilder preventInsertion(boolean value) {
            this.preventItemInsertion = value;
            return this;
        }

        ContextBuilder allowBreaking(boolean value) {
            this.allowBreakingExhaustedContainers = value;
            return this;
        }

        ClaimContext build() {
            return new ClaimContext(kind, counted, claimCount, maxClaims, hasContentsBlob,
                    contentsEmpty, finalClaimAction, completedContainerBecomesNormal,
                    preventItemInsertion, allowBreakingExhaustedContainers);
        }
    }

    // ------------------------------------------------------------------ first claim

    @Test
    void firstClaimGeneratesLoot() {
        assertEquals(ClaimAction.PERSIST_NEW, ClaimStateMachine.decide(defaultContext().build()));
    }

    @Test
    void firstClaimOnTheLastSlotOfAVanillaFinalContainerTransitionsImmediately() {
        // claimCount is the count *after* the allocation, so the last slot is claimCount == maxClaims.
        ClaimContext context = defaultContext().kind(ClaimAllocation.Kind.NEW).claims(3, 3).build();
        assertEquals(ClaimAction.PERSIST_NEW_FINAL_VANILLA, ClaimStateMachine.decide(context));
    }

    @Test
    void aFirstClaimOneSlotEarlyDoesNotTransition() {
        // Guards against an off-by-one in the other direction: maxClaims - 1 is not the last slot.
        ClaimContext context = defaultContext().kind(ClaimAllocation.Kind.NEW).claims(2, 3).build();
        assertFalse(context.finalVanillaClaim());
        assertEquals(ClaimAction.PERSIST_NEW, ClaimStateMachine.decide(context));
    }

    @Test
    void anUncountedPlayerNeverTriggersTheFinalTransition() {
        // A creative player who does not count must not retire the container: they would consume the
        // last slot and the counted players would never receive the loot.
        ClaimContext context = defaultContext()
                .kind(ClaimAllocation.Kind.NEW).claims(3, 3).counted(false).build();
        assertFalse(context.finalVanillaClaim());
        assertEquals(ClaimAction.PERSIST_NEW, ClaimStateMachine.decide(context));
    }

    // ------------------------------------------------------------------ stored inventory

    @Test
    void returningPlayerOpensTheStoredInventory() {
        assertEquals(ClaimAction.OPEN_PERSONAL,
                ClaimStateMachine.decide(defaultContext().kind(ClaimAllocation.Kind.EXISTING).build()));
    }

    @Test
    void aMissingBlobIsADatabaseErrorNotAnEmptyChest() {
        // Showing an empty chest would look like a successful claim and silently lose the loot.
        assertEquals(ClaimAction.SHOW_DATABASE_ERROR, ClaimStateMachine.decide(
                defaultContext().kind(ClaimAllocation.Kind.EXISTING).blob(false).build()));
    }

    @Test
    void anEmptyStoredInventoryCompletesTheContainerWhenPersonalCompletionIsOn() {
        ClaimContext context = defaultContext()
                .kind(ClaimAllocation.Kind.EXISTING).empty(true)
                .finalAction(FinalClaimAction.PERSONAL).becomesNormal(true).build();
        assertEquals(ClaimAction.COMPLETE_AND_OPEN_NORMAL, ClaimStateMachine.decide(context));
    }

    @Test
    void anEmptyStoredInventoryJustOpensWhenCompletionIsOff() {
        ClaimContext context = defaultContext()
                .kind(ClaimAllocation.Kind.EXISTING).empty(true)
                .finalAction(FinalClaimAction.PERSONAL).becomesNormal(false).build();
        assertEquals(ClaimAction.OPEN_PERSONAL, ClaimStateMachine.decide(context));
        assertEquals(ClaimAction.OPEN_PERSONAL, ClaimStateMachine.decide(
                defaultContext().kind(ClaimAllocation.Kind.EXISTING).empty(true).build()));
    }

    @Test
    void theLastCountedClaimantRecoversTheFinalInventory() {
        ClaimContext context = defaultContext()
                .kind(ClaimAllocation.Kind.EXISTING).claims(3, 3).build();
        assertTrue(context.finalVanillaClaim());
        assertEquals(ClaimAction.RECOVER_FINAL_VANILLA, ClaimStateMachine.decide(context));
    }

    @Test
    void aNonFinalClaimantOnAFullContainerStillOpensTheirOwnInventory() {
        // claimCount has reached maxClaims but this player is not counted (e.g. creative), so they are not
        // the one who retires the container.
        ClaimContext context = defaultContext()
                .kind(ClaimAllocation.Kind.EXISTING).claims(3, 3).counted(false).build();
        assertEquals(ClaimAction.OPEN_PERSONAL, ClaimStateMachine.decide(context));
    }

    @Test
    void theFinalRecoveryWinsOverPersonalCompletion() {
        // If both rules could fire, the container would be retired without ever handing the loot over.
        ClaimContext context = defaultContext()
                .kind(ClaimAllocation.Kind.EXISTING).claims(3, 3).empty(true)
                .finalAction(FinalClaimAction.VANILLA_CONTAINER).build();
        assertEquals(ClaimAction.RECOVER_FINAL_VANILLA, ClaimStateMachine.decide(context));
    }

    // ------------------------------------------------------------------ already finished

    @Test
    void aFinishedContainerReportsCompletedWhenItStaysManaged() {
        ClaimContext context = defaultContext()
                .kind(ClaimAllocation.Kind.COMPLETED).finalAction(FinalClaimAction.PERSONAL)
                .becomesNormal(false).build();
        assertEquals(ClaimAction.SHOW_COMPLETED, ClaimStateMachine.decide(context));
    }

    @Test
    void aFinishedContainerOpensAsNormalWhenPersonalCompletionIsOn() {
        ClaimContext context = defaultContext()
                .kind(ClaimAllocation.Kind.COMPLETED).finalAction(FinalClaimAction.PERSONAL)
                .becomesNormal(true).build();
        assertEquals(ClaimAction.OPEN_NORMAL, ClaimStateMachine.decide(context));
    }

    @Test
    void aFinishedContainerOnTheVanillaPathStillRecoversForTheLastClaimant() {
        ClaimContext context = defaultContext()
                .kind(ClaimAllocation.Kind.COMPLETED).claims(3, 3).build();
        assertEquals(ClaimAction.RECOVER_FINAL_VANILLA, ClaimStateMachine.decide(context));
    }

    // ------------------------------------------------------------------ exhausted

    @Test
    void exhaustedContainerIsReportedNotOpened() {
        assertEquals(ClaimAction.SHOW_EXHAUSTED,
                ClaimStateMachine.decide(defaultContext().kind(ClaimAllocation.Kind.EXHAUSTED).build()));
    }

    @Test
    void exhaustedContainerCanBeReleasedWhenTheOperatorAllowsIt() {
        assertEquals(ClaimAction.RELEASE_AND_OPEN_NORMAL, ClaimStateMachine.decide(
                defaultContext().kind(ClaimAllocation.Kind.EXHAUSTED).allowBreaking(true).build()));
    }

    // ------------------------------------------------------------------ transient states

    @Test
    void pendingAllocationAsksThePlayerToWait() {
        assertEquals(ClaimAction.SHOW_LOADING,
                ClaimStateMachine.decide(defaultContext().kind(ClaimAllocation.Kind.PENDING).build()));
    }

    @Test
    void notFoundIsReportedAsADatabaseError() {
        assertEquals(ClaimAction.SHOW_DATABASE_ERROR,
                ClaimStateMachine.decide(defaultContext().kind(ClaimAllocation.Kind.NOT_FOUND).build()));
    }

    // ------------------------------------------------------------------ after generation

    @Test
    void nonEmptyLootOpensThePersonalInventory() {
        ClaimContext context = defaultContext().build();
        assertEquals(GeneratedClaimAction.OPEN_PERSONAL,
                ClaimStateMachine.afterGeneration(context, false, false));
    }

    @Test
    void emptyLootWithInsertionPreventedCompletesTheContainer() {
        // With insertion prevented an empty personal inventory can never be refilled, so leaving it open
        // would strand the player in a chest they cannot use.
        ClaimContext context = defaultContext().preventInsertion(true).build();
        assertEquals(GeneratedClaimAction.SHOW_COMPLETED,
                ClaimStateMachine.afterGeneration(context, true, false));
    }

    @Test
    void emptyLootOnThePersonalPathOpensTheContainerAsNormal() {
        ClaimContext context = defaultContext()
                .finalAction(FinalClaimAction.PERSONAL).becomesNormal(true).build();
        assertEquals(GeneratedClaimAction.OPEN_COMPLETED_NORMAL,
                ClaimStateMachine.afterGeneration(context, true, false));
    }

    @Test
    void emptyLootOnTheVanillaPathTransitionsToTheContainer() {
        ClaimContext context = defaultContext().build();
        assertEquals(GeneratedClaimAction.TRANSITION_TO_VANILLA,
                ClaimStateMachine.afterGeneration(context, true, true));
    }

    @Test
    void theFinalClaimTransitionsEvenWithLootRemaining() {
        // The container is being retired, not completed: remaining loot must not be silently dropped, so
        // the transition still happens and the loot is written into the real container.
        ClaimContext context = defaultContext().build();
        assertEquals(GeneratedClaimAction.TRANSITION_TO_VANILLA,
                ClaimStateMachine.afterGeneration(context, false, true));
    }

    @Test
    void emptyLootWithEverythingOffStillOpens() {
        ClaimContext context = defaultContext()
                .finalAction(FinalClaimAction.PERSONAL).becomesNormal(false).preventInsertion(false).build();
        assertEquals(GeneratedClaimAction.OPEN_PERSONAL,
                ClaimStateMachine.afterGeneration(context, true, false));
    }

    // ------------------------------------------------------------------ reopening

    @Test
    void reopeningIsOnlyAllowedWhenInsertionIsAllowed() {
        assertFalse(ClaimStateMachine.shouldReopenCompleted(
                FinalClaimAction.VANILLA_CONTAINER, true, true));
        assertTrue(ClaimStateMachine.shouldReopenCompleted(
                FinalClaimAction.VANILLA_CONTAINER, true, false));
        assertFalse(ClaimStateMachine.shouldReopenCompleted(
                FinalClaimAction.PERSONAL, true, false));
        assertTrue(ClaimStateMachine.shouldReopenCompleted(
                FinalClaimAction.PERSONAL, false, false));
    }

    // ------------------------------------------------------------------ properties

    @Test
    void decideIsTotalOverEveryAllocationKind() {
        // No combination may fall through: an unhandled kind would surface as a switch exception at
        // runtime, on a player's click, rather than in a test.
        for (ClaimAllocation.Kind kind : ClaimAllocation.Kind.values()) {
            for (FinalClaimAction action : FinalClaimAction.values()) {
                for (boolean counted : new boolean[] {true, false}) {
                    for (int count = 0; count <= 3; count++) {
                        ClaimContext context = new ClaimContext(kind, counted, count, 3, true, false,
                                action, true, false, false);
                        ClaimAction result = ClaimStateMachine.decide(context);
                        assertTrue(result != null, "null decision for " + kind);
                    }
                }
            }
        }
    }

    @Test
    void remainingClaimsIsNeverNegative() {
        // Shown verbatim in chat; a negative number would be the visible symptom of a counter bug.
        assertEquals(0, new ClaimContext(ClaimAllocation.Kind.NEW, true, 5, 3, true, false,
                FinalClaimAction.VANILLA_CONTAINER, true, false, false).remainingClaims());
        assertEquals(3, new ClaimContext(ClaimAllocation.Kind.NEW, true, 0, 3, true, false,
                FinalClaimAction.VANILLA_CONTAINER, true, false, false).remainingClaims());
        assertEquals(1, new ClaimContext(ClaimAllocation.Kind.NEW, true, 2, 3, true, false,
                FinalClaimAction.VANILLA_CONTAINER, true, false, false).remainingClaims());
    }

    @Test
    void everyActionIsReachable() {
        // A dead enum constant would mean a branch was dropped in the port.
        List<ClaimAction> produced = new ArrayList<>();
        for (ClaimAllocation.Kind kind : ClaimAllocation.Kind.values()) {
            for (FinalClaimAction action : FinalClaimAction.values()) {
                for (boolean becomesNormal : new boolean[] {true, false}) {
                    for (boolean counted : new boolean[] {true, false}) {
                        for (int count = 0; count <= 3; count++) {
                            for (boolean blob : new boolean[] {true, false}) {
                                for (boolean empty : new boolean[] {true, false}) {
                                    for (boolean allowBreaking : new boolean[] {true, false}) {
                                        produced.add(ClaimStateMachine.decide(new ClaimContext(kind, counted,
                                                count, 3, blob, empty, action, becomesNormal, empty,
                                                allowBreaking)));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        for (ClaimAction action : ClaimAction.values()) {
            assertTrue(produced.contains(action), "no input reaches " + action);
        }
    }

    @Test
    void everyGeneratedActionIsReachable() {
        List<GeneratedClaimAction> produced = new ArrayList<>();
        for (FinalClaimAction action : FinalClaimAction.values()) {
            for (boolean becomesNormal : new boolean[] {true, false}) {
                for (boolean preventInsertion : new boolean[] {true, false}) {
                    for (boolean empty : new boolean[] {true, false}) {
                        for (boolean finalClaim : new boolean[] {true, false}) {
                            ClaimContext context = new ClaimContext(ClaimAllocation.Kind.NEW, true, 0, 3,
                                    true, false, action, becomesNormal, preventInsertion, false);
                            produced.add(ClaimStateMachine.afterGeneration(context, empty, finalClaim));
                        }
                    }
                }
            }
        }
        for (GeneratedClaimAction action : GeneratedClaimAction.values()) {
            assertTrue(produced.contains(action), "no input reaches " + action);
        }
    }
}
