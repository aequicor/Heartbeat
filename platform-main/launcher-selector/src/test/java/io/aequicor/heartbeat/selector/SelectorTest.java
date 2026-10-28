package io.aequicor.heartbeat.selector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelectorTest {
    static final Set<String> NONE = Set.of();

    @TempDir
    Path tmp;

    final TestSigning signing = new TestSigning();
    Path slots;
    Selector selector;

    @BeforeEach
    void setUp() {
        slots = tmp.resolve("slots");
        selector = new Selector(slots, signing.trusted());
        selector.becomeOwner();
    }

    @AfterEach
    void tearDown() {
        selector.store().releaseOwnerLock();
    }

    void jar(String slot, String content) throws IOException {
        jar(slot, content, 1);
    }

    /** Слот с подписанной основным ключом записью уровня {@code level}. */
    void jar(String slot, String content, int level) throws IOException {
        Path jar = selector.store().jar(slot);
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, content, StandardCharsets.UTF_8);
        TestSigning.record(signing.primary, "launcher", "t" + level, SlotStore.sha256(jar), level)
            .write(selector.store().slotDir(slot).resolve(SlotRecord.FILE_NAME));
    }

    SlotState state() throws IOException {
        return selector.store().state();
    }

    /** Слот {@code slot} подтверждён: запущен как пробный и дошёл до входа в приложение. */
    void confirmedSlot(String slot) throws IOException {
        jar(slot, "v-" + slot);
        assertTrue(selector.activateTrial(slot));
        selector.choose(NONE);
        selector.confirm();
    }

    @Test
    void withoutSlotsTheEmbeddedLauncherStarts() throws IOException {
        assertEquals(Choice.Kind.EMBEDDED, selector.choose(NONE).kind());
        assertFalse(state().launching);
    }

    @Test
    void newSlotRunsAsTrialAndLeavesTheMark() throws IOException {
        jar("A", "v1");
        assertTrue(selector.activateTrial("A"));
        Choice choice = selector.choose(NONE);
        assertEquals("A", choice.slot());
        assertTrue(choice.trial());
        assertTrue(state().launching);
        assertEquals("", state().confirmed);
    }

    @Test
    void confirmationMakesTheSlotConfirmedAndResetsTheCounter() throws IOException {
        jar("A", "v1");
        selector.activateTrial("A");
        selector.choose(NONE);
        selector.confirm();
        SlotState state = state();
        assertEquals("A", state.confirmed);
        assertEquals(0, state.crashes);
        assertFalse(state.launching);
        Choice next = selector.choose(NONE);
        assertEquals("A", next.slot());
        assertFalse(next.trial());
        assertFalse(state().launching);
    }

    @Test
    void trialThatNeverConfirmsRollsBackToTheConfirmedSlot() throws IOException {
        confirmedSlot("A");
        jar("B", "v2");
        selector.activateTrial("B");
        assertEquals("B", selector.choose(NONE).slot());   // first launch, not confirmed
        assertEquals("B", selector.choose(NONE).slot());   // second launch: the first one is counted
        assertEquals(1, state().crashes);
        Choice rolledBack = selector.choose(NONE);          // the second one is counted: limit reached
        assertEquals("A", rolledBack.slot());
        assertFalse(rolledBack.trial());
        SlotState state = state();
        assertEquals("A", state.active);
        assertEquals("A", state.confirmed);
        assertEquals(0, state.crashes);
        assertEquals(Set.of(SlotStore.sha256(selector.store().jar("B"))), state.bad);
    }

    @Test
    void trialWithoutAConfirmedSlotRollsBackToTheEmbeddedLauncher() throws IOException {
        jar("A", "v1");
        selector.activateTrial("A");
        selector.choose(NONE);
        selector.choose(NONE);
        assertEquals(Choice.Kind.EMBEDDED, selector.choose(NONE).kind());
        assertEquals("", state().active);
        assertEquals(1, state().bad.size());
    }

    @Test
    void confirmationBeforeTheLimitKeepsTheTrialSlot() throws IOException {
        confirmedSlot("A");
        jar("B", "v2");
        selector.activateTrial("B");
        selector.choose(NONE);
        selector.choose(NONE);   // one unconfirmed launch counted
        selector.confirm();
        assertEquals("B", state().confirmed);
        assertEquals("B", selector.choose(NONE).slot());
        assertTrue(state().bad.isEmpty());
    }

    @Test
    void activationRefusesTheConfirmedSlotAMissingJarAndARejectedJar() throws IOException {
        confirmedSlot("A");
        assertThrows(IllegalArgumentException.class, () -> selector.activateTrial("A"));
        assertFalse(selector.activateTrial("B"));
        jar("B", "bad");
        selector.activateTrial("B");
        selector.choose(NONE);
        selector.choose(NONE);
        selector.choose(NONE);   // B is rejected
        assertFalse(selector.activateTrial("B"));
        jar("B", "fixed");
        assertTrue(selector.activateTrial("B"));
    }

    @Test
    void missingJarOfTheActiveSlotFallsBackToTheConfirmedOne() throws IOException {
        confirmedSlot("A");
        jar("B", "v2");
        selector.activateTrial("B");
        Files.delete(selector.store().jar("B"));
        assertEquals("A", selector.choose(NONE).slot());
        assertEquals("A", state().active);
    }

    @Test
    void abandonedTrialIsRejectedAtOnce() throws IOException {
        confirmedSlot("A");
        jar("B", "v2");
        selector.activateTrial("B");
        selector.choose(NONE);
        selector.abandon("B");
        SlotState state = state();
        assertEquals("A", state.active);
        assertFalse(state.launching);
        assertEquals(1, state.bad.size());
        assertEquals("A", selector.choose(NONE).slot());
    }

    @Test
    void abandonedConfirmedSlotLeavesTheEmbeddedLauncher() throws IOException {
        confirmedSlot("A");
        selector.abandon("A");
        assertEquals("", state().confirmed);
        assertEquals(Choice.Kind.EMBEDDED, selector.choose(NONE).kind());
    }

    @Test
    void excludedSlotsAreSkipped() throws IOException {
        confirmedSlot("A");
        jar("B", "v2");
        selector.activateTrial("B");
        assertEquals("A", selector.choose(Set.of("B")).slot());
    }

    @Test
    void secondProcessReadsSlotsWithoutCountingOrWriting() throws IOException {
        jar("A", "v1");
        selector.activateTrial("A");
        selector.choose(NONE);   // the owner marks the launch
        Selector other = new Selector(slots, signing.trusted());
        assertFalse(other.becomeOwner());
        byte[] before = Files.readAllBytes(selector.store().stateFile());
        Choice choice = other.choose(NONE);
        assertEquals("A", choice.slot());
        other.confirm();
        other.abandon("A");
        assertFalse(other.activateTrial("A"));
        assertEquals(Set.of(), Set.copyOf(state().bad));
        assertEquals(1, state().crashes + 1);
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(selector.store().stateFile())));
    }

    @Test
    void stateOfANewerFormatStartsTheEmbeddedLauncherAndIsNotTouched() throws IOException {
        confirmedSlot("A");
        String newer = "format=" + (SlotState.FORMAT + 1) + "\nactive=A\nconfirmed=A\nfuture=1\n";
        Files.writeString(selector.store().stateFile(), newer, StandardCharsets.UTF_8);
        assertEquals(Choice.Kind.EMBEDDED, selector.choose(NONE).kind());
        selector.confirm();
        selector.abandon("A");
        jar("B", "v2");
        assertFalse(selector.activateTrial("B"));
        assertEquals(newer, Files.readString(selector.store().stateFile(), StandardCharsets.UTF_8));
    }

    @Test
    void unknownFieldsOfTheStateSurviveRewrites() throws IOException {
        confirmedSlot("A");
        SlotState state = state();
        state.unknown.put("hint", "x");
        state.save(selector.store().stateFile());
        selector.choose(NONE);
        selector.confirm();
        assertEquals("x", state().unknown.get("hint"));
    }

    @Test
    void stateWithoutFormatIsReadAsTheFirstFormat() throws IOException {
        jar("A", "v1");
        Files.writeString(selector.store().stateFile(), "active=A\nconfirmed=A\n", StandardCharsets.UTF_8);
        Choice choice = selector.choose(NONE);
        assertEquals("A", choice.slot());
        assertFalse(choice.trial());
    }

    @Test
    void corruptedSlotNamesMeanNoSlot() throws IOException {
        jar("A", "v1");
        Files.writeString(selector.store().stateFile(), "active=../evil\nconfirmed=Z\n", StandardCharsets.UTF_8);
        assertEquals(Choice.Kind.EMBEDDED, selector.choose(NONE).kind());
    }

    @Test
    void unreadableStateStartsTheEmbeddedLauncherInsteadOfFailing() throws IOException {
        confirmedSlot("A");
        Path state = selector.store().stateFile();
        byte[] before = Files.readAllBytes(state);
        Files.delete(state);
        Files.createDirectory(state);   // reading a directory fails like a locked or damaged file
        assertEquals(Choice.Kind.EMBEDDED, selector.choose(NONE).kind());
        Files.delete(state);
        Files.write(state, before);
        assertEquals("A", selector.choose(NONE).slot());
    }

    @Test
    void emptyStateFileIsAnEmptyStateNotAFailure() throws IOException {
        confirmedSlot("A");
        Files.write(selector.store().stateFile(), new byte[0]);
        assertEquals(Choice.Kind.EMBEDDED, selector.choose(NONE).kind());
    }

    @Test
    void releasedTrialIsNotRejected() throws IOException {
        confirmedSlot("A");
        jar("B", "v2", 2);
        selector.activateTrial("B");
        selector.choose(NONE);
        selector.release("B");
        SlotState state = state();
        assertEquals("A", state.active);
        assertEquals("A", state.confirmed);
        assertFalse(state.launching);
        assertTrue(state.bad.isEmpty());
        assertTrue(selector.store().hasJar("B"));
        assertTrue(selector.activateTrial("B"));   // the same jar may be tried again
    }

    @Test
    void secondStoreOfTheSameDirectoryNeverBecomesOwnerWhileTheFirstHoldsIt() {
        Selector other = new Selector(slots, signing.trusted());
        assertFalse(other.becomeOwner());
        assertFalse(new Selector(slots, signing.trusted()).becomeOwner());
        selector.store().releaseOwnerLock();
        assertTrue(other.becomeOwner());
        other.store().releaseOwnerLock();
    }

    @Test
    void stateWriteLeavesNoTemporaryFile() throws IOException {
        confirmedSlot("A");
        assertFalse(Files.exists(selector.store().stateFile().resolveSibling("state.properties.tmp")));
    }
}
