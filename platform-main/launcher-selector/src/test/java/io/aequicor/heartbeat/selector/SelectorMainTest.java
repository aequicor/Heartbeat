package io.aequicor.heartbeat.selector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Запуск реальных jar с классом {@code LauncherMain}: подтверждение, откат и отказ слота, не открывшегося вовсе. */
class SelectorMainTest {
    static final Set<String> NONE = Set.of();
    static final String[] ARGS = {};

    @TempDir
    Path tmp;

    Selector selector;
    Path embedded;

    @BeforeEach
    void setUp() throws Exception {
        System.clearProperty(LauncherFixtures.LOG);
        selector = new Selector(tmp.resolve("slots"));
        selector.becomeOwner();
        embedded = LauncherFixtures.working(tmp.resolve("embedded/launcher.jar"), "embedded");
    }

    @AfterEach
    void tearDown() {
        selector.store().releaseOwnerLock();
        System.clearProperty(LauncherFixtures.LOG);
    }

    void slot(String slot, Path built) throws Exception {
        Path target = selector.store().jar(slot);
        java.nio.file.Files.createDirectories(target.getParent());
        java.nio.file.Files.copy(built, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    void withoutSlotsTheEmbeddedLauncherRuns() throws Exception {
        SelectorMain.run(selector, ARGS, null, embedded, true);
        assertEquals("embedded;", System.getProperty(LauncherFixtures.LOG));
    }

    @Test
    void trialSlotIsConfirmedWhenTheLauncherEntersTheApplication() throws Exception {
        slot("A", LauncherFixtures.working(tmp.resolve("built/a.jar"), "A"));
        selector.activateTrial("A");
        SelectorMain.run(selector, ARGS, null, embedded, true);
        assertEquals("A;", System.getProperty(LauncherFixtures.LOG));
        assertEquals("A", selector.store().state().confirmed);
        assertFalse(selector.store().state().launching);
    }

    @Test
    void launcherFailureBeforeConfirmationPropagatesAndIsCountedAtTheNextStart() throws Exception {
        slot("A", LauncherFixtures.failingBeforeConfirmation(tmp.resolve("built/a.jar"), "A"));
        selector.activateTrial("A");
        assertThrows(IllegalStateException.class, () -> SelectorMain.run(selector, ARGS, null, embedded, true));
        assertEquals("", selector.store().state().confirmed);
        assertTrue(selector.store().state().launching);
        assertThrows(IllegalStateException.class, () -> SelectorMain.run(selector, ARGS, null, embedded, true));
        SelectorMain.run(selector, ARGS, null, embedded, true);   // limit reached: the embedded launcher starts
        assertEquals("A;A;embedded;", System.getProperty(LauncherFixtures.LOG));
        assertEquals(1, selector.store().state().bad.size());
    }

    @Test
    void slotThatCannotBeStartedIsAbandonedAndTheConfirmedOneStartsInTheSameProcess() throws Exception {
        slot("A", LauncherFixtures.working(tmp.resolve("built/a.jar"), "A"));
        selector.activateTrial("A");
        SelectorMain.run(selector, ARGS, null, embedded, true);
        System.clearProperty(LauncherFixtures.LOG);
        slot("B", LauncherFixtures.withoutStart(tmp.resolve("built/b.jar")));
        selector.activateTrial("B");
        SelectorMain.run(selector, ARGS, null, embedded, true);
        assertEquals("A;", System.getProperty(LauncherFixtures.LOG));
        SlotState state = selector.store().state();
        assertEquals("A", state.active);
        assertEquals(1, state.bad.size());
    }

    @Test
    void jarWithoutTheLauncherClassFallsBackToTheEmbeddedLauncher() throws Exception {
        slot("A", LauncherFixtures.empty(tmp.resolve("built/a.jar")));
        selector.activateTrial("A");
        SelectorMain.run(selector, ARGS, null, embedded, true);
        assertEquals("embedded;", System.getProperty(LauncherFixtures.LOG));
        assertEquals("", selector.store().state().active);
    }

    @Test
    void embeddedLauncherThatCannotStartIsAnError() throws Exception {
        Path broken = LauncherFixtures.empty(tmp.resolve("embedded/broken.jar"));
        assertThrows(SlotLauncher.NotStartedException.class,
            () -> SelectorMain.run(selector, ARGS, null, broken, true));
    }

    @Test
    void missingEmbeddedLauncherIsAnError() {
        assertThrows(IllegalStateException.class, () -> SelectorMain.run(selector, ARGS, null, null, true));
    }

    @Test
    void nonOwnerRunsTheSlotWithoutConfirmingIt() throws Exception {
        slot("A", LauncherFixtures.working(tmp.resolve("built/a.jar"), "A"));
        selector.activateTrial("A");
        Selector other = new Selector(tmp.resolve("slots"));
        assertFalse(other.becomeOwner());
        SelectorMain.run(other, ARGS, null, embedded, false);
        assertEquals("A;", System.getProperty(LauncherFixtures.LOG));
        assertEquals("", selector.store().state().confirmed);
        assertFalse(selector.store().state().launching);
    }

    @Test
    void auxiliaryProcessesAreRecognizedByTheirFirstArgument() {
        assertTrue(SelectorMain.isAuxiliary(new String[] {"--heartbeat-plantuml-worker"}));
        assertFalse(SelectorMain.isAuxiliary(new String[] {"file.md"}));
        assertFalse(SelectorMain.isAuxiliary(new String[0]));
        assertNull(System.getProperty(LauncherFixtures.LOG));
    }
}
