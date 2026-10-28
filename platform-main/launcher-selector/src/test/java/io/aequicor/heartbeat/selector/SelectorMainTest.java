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

    final TestSigning signing = new TestSigning();
    Selector selector;
    Path embedded;

    @BeforeEach
    void setUp() throws Exception {
        System.clearProperty(LauncherFixtures.LOG);
        selector = new Selector(tmp.resolve("slots"), signing.trusted());
        selector.becomeOwner();
        embedded = LauncherFixtures.working(tmp.resolve("embedded/launcher.jar"), "embedded");
    }

    @AfterEach
    void tearDown() {
        selector.store().releaseOwnerLock();
        System.clearProperty(LauncherFixtures.LOG);
    }

    void slot(String slot, Path built) throws Exception {
        TestSigning.install(selector.store(), slot, built, signing.primary, 1);
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
        Selector other = new Selector(tmp.resolve("slots"), signing.trusted());
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

    void requestFor(Path jar, int level) throws Exception {
        SlotRecord record = TestSigning.record(signing.primary, "launcher", "t" + level, SlotStore.sha256(jar), level);
        System.setProperty("fixture.req.jar", jar.toString());
        System.setProperty("fixture.req.id", record.id());
        System.setProperty("fixture.req.version", record.version());
        System.setProperty("fixture.req.sha256", record.sha256());
        System.setProperty("fixture.req.kind", record.kind());
        System.setProperty("fixture.req.launcherApi", Integer.toString(level));
        System.setProperty("fixture.req.signature", record.signature());
    }

    void clearRequest() {
        for (String key : new String[] {"jar", "id", "version", "sha256", "kind", "launcherApi", "signature"}) {
            System.clearProperty("fixture.req." + key);
        }
        System.clearProperty(SelectorMain.TRIAL);
    }

    @Test
    void runningLauncherStagesTheNextOneAndTheTrialFlagFollowsTheSlot() throws Exception {
        try {
            slot("A", LauncherFixtures.staging(tmp.resolve("built/a.jar"), "A"));
            selector.activateTrial("A");
            Path next = LauncherFixtures.working(tmp.resolve("built/next.jar"), "next");
            requestFor(next, 2);
            SelectorMain.run(selector, ARGS, null, embedded, true);
            // A runs as a trial; it confirms itself before offering, so the neighbour is staged right away
            assertEquals("A:trial=true;staged=true;", System.getProperty(LauncherFixtures.LOG));
            SlotState state = selector.store().state();
            assertEquals("A", state.confirmed);
            assertEquals("B", state.active);
            System.clearProperty(LauncherFixtures.LOG);
            SelectorMain.run(selector, ARGS, null, embedded, true);   // the staged launcher is the trial now
            assertEquals("next;", System.getProperty(LauncherFixtures.LOG));
            assertEquals("B", selector.store().state().confirmed);
        } finally {
            clearRequest();
        }
    }

    @Test
    void confirmedSlotIsNotATrialAndNonOwnerGetsNoStager() throws Exception {
        try {
            slot("A", LauncherFixtures.staging(tmp.resolve("built/a.jar"), "A"));
            selector.activateTrial("A");
            selector.choose(NONE);
            selector.confirm();
            SelectorMain.run(selector, ARGS, null, embedded, true);
            assertTrue(System.getProperty(LauncherFixtures.LOG).startsWith("A:trial=false;"));
            System.clearProperty(LauncherFixtures.LOG);
            Selector other = new Selector(tmp.resolve("slots"), signing.trusted());
            assertFalse(other.becomeOwner());
            SelectorMain.run(other, ARGS, null, embedded, false);
            assertEquals("A:trial=false;nostager;", System.getProperty(LauncherFixtures.LOG));
        } finally {
            clearRequest();
        }
    }

    @Test
    void deferredDataOperationReleasesTheTrialAndTheConfirmedLauncherRunsInTheSameProcess() throws Exception {
        slot("A", LauncherFixtures.working(tmp.resolve("built/a.jar"), "A"));
        selector.activateTrial("A");
        SelectorMain.run(selector, ARGS, null, embedded, true);
        System.clearProperty(LauncherFixtures.LOG);
        slot("B", LauncherFixtures.deferring(tmp.resolve("built/b.jar"), "B"));
        selector.activateTrial("B");
        SelectorMain.run(selector, ARGS, null, embedded, true);
        assertEquals("B;A;", System.getProperty(LauncherFixtures.LOG));
        SlotState state = selector.store().state();
        assertEquals("A", state.active);
        assertEquals("A", state.confirmed);
        assertTrue(state.bad.isEmpty());
        assertTrue(selector.store().hasJar("B"));
        assertNull(System.getProperty(SelectorMain.DEFERRED));
    }

    @Test
    void deferralWithoutAConfirmedSlotFallsBackToTheEmbeddedLauncher() throws Exception {
        slot("A", LauncherFixtures.deferring(tmp.resolve("built/a.jar"), "A"));
        selector.activateTrial("A");
        SelectorMain.run(selector, ARGS, null, embedded, true);
        assertEquals("A;embedded;", System.getProperty(LauncherFixtures.LOG));
        assertEquals("", selector.store().state().active);
        assertTrue(selector.store().state().bad.isEmpty());
    }

    @Test
    void failureOfANonTrialLauncherIsNotTreatedAsDeferral() throws Exception {
        slot("A", LauncherFixtures.working(tmp.resolve("built/a.jar"), "A"));
        selector.activateTrial("A");
        SelectorMain.run(selector, ARGS, null, embedded, true);
        slot("A", LauncherFixtures.deferring(tmp.resolve("built/a2.jar"), "A2"));   // the confirmed slot itself fails
        assertThrows(java.io.IOException.class, () -> SelectorMain.run(selector, ARGS, null, embedded, true));
    }

    @Test
    void corruptJarIsAbandonedForGood() throws Exception {
        slot("A", LauncherFixtures.garbage(tmp.resolve("built/a.jar")));
        selector.activateTrial("A");
        SelectorMain.run(selector, ARGS, null, embedded, true);
        assertEquals("embedded;", System.getProperty(LauncherFixtures.LOG));
        assertEquals(1, selector.store().state().bad.size());
    }

    @Test
    void launcherReceivesInstrumentationAfterItsStartMethodIsFound() throws Exception {
        java.lang.instrument.Instrumentation inst = (java.lang.instrument.Instrumentation) java.lang.reflect.Proxy
            .newProxyInstance(getClass().getClassLoader(), new Class<?>[] {java.lang.instrument.Instrumentation.class},
                (proxy, method, args) -> null);
        slot("A", LauncherFixtures.withAgent(tmp.resolve("built/a.jar"), "A"));
        selector.activateTrial("A");
        SelectorMain.run(selector, ARGS, inst, embedded, true);
        assertEquals("premain:true;A;", System.getProperty(LauncherFixtures.LOG));
    }

    @Test
    void launcherWithoutStartNeverRunsItsAgent() throws Exception {
        java.lang.instrument.Instrumentation inst = (java.lang.instrument.Instrumentation) java.lang.reflect.Proxy
            .newProxyInstance(getClass().getClassLoader(), new Class<?>[] {java.lang.instrument.Instrumentation.class},
                (proxy, method, args) -> null);
        slot("A", LauncherFixtures.withoutStartButWithAgent(tmp.resolve("built/a.jar")));
        selector.activateTrial("A");
        Path embeddedWithAgent = LauncherFixtures.withAgent(tmp.resolve("embedded/with-agent.jar"), "embedded");
        SelectorMain.run(selector, ARGS, inst, embeddedWithAgent, true);
        // only the embedded launcher's premain ran: the slot was refused before its agent was called
        assertEquals("premain:true;embedded;", System.getProperty(LauncherFixtures.LOG));
    }
}
