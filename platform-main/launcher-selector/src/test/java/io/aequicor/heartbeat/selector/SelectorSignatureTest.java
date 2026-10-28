package io.aequicor.heartbeat.selector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Слот запускается и активируется только с подписью доверенного ключа и уровнем не ниже порога. */
class SelectorSignatureTest {
    static final Set<String> NONE = Set.of();

    @TempDir
    Path tmp;

    final TestSigning signing = new TestSigning();
    Selector selector;

    @BeforeEach
    void setUp() {
        selector = new Selector(tmp.resolve("slots"), signing.trusted());
        selector.becomeOwner();
    }

    @AfterEach
    void tearDown() {
        selector.store().releaseOwnerLock();
    }

    Path built(String content) throws IOException {
        Path file = Files.createTempFile(tmp, "launcher", ".jar");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    SlotRecord install(String slot, String content, KeyPair key, int level) throws IOException {
        return TestSigning.install(selector.store(), slot, built(content), key, level);
    }

    SlotState state() throws IOException {
        return selector.store().state();
    }

    void confirmed(String slot, int level) throws IOException {
        install(slot, "v-" + slot + level, signing.primary, level);
        assertTrue(selector.activateTrial(slot));
        selector.choose(NONE);
        selector.confirm();
    }

    @Test
    void slotSignedByTheBackupKeyStarts() throws IOException {
        install("A", "v1", signing.backup, 1);
        assertTrue(selector.activateTrial("A"));
        assertEquals("A", selector.choose(NONE).slot());
    }

    @Test
    void slotWithoutARecordIsNotActivatedAndNotStarted() throws IOException {
        Files.createDirectories(selector.store().slotDir("A"));
        Files.writeString(selector.store().jar("A"), "unsigned", StandardCharsets.UTF_8);
        assertFalse(selector.activateTrial("A"));
        Files.writeString(selector.store().stateFile(), "active=A\nconfirmed=A\n", StandardCharsets.UTF_8);
        assertEquals(Choice.Kind.EMBEDDED, selector.choose(NONE).kind());
        assertEquals(1, state().bad.size());
    }

    @Test
    void slotSignedByAForeignKeyIsRejected() throws IOException {
        install("A", "v1", TestSigning.newKeyPair(), 1);
        assertFalse(selector.activateTrial("A"));
    }

    @Test
    void jarReplacedAfterInstallFallsBackToTheConfirmedSlot() throws IOException {
        confirmed("A", 1);
        install("B", "v2", signing.primary, 2);
        selector.activateTrial("B");
        Files.writeString(selector.store().jar("B"), "tampered", StandardCharsets.UTF_8);
        Choice choice = selector.choose(NONE);
        assertEquals("A", choice.slot());
        assertFalse(choice.trial());
        assertEquals("A", state().active);
        assertFalse(state().launching);
    }

    @Test
    void recordWithAChangedFieldFailsTheSignature() throws IOException {
        SlotRecord signed = install("A", "v1", signing.primary, 5);
        new SlotRecord(signed.id(), signed.version(), signed.sha256(), signed.kind(), 6, signed.signature())
            .write(selector.store().slotDir("A").resolve(SlotRecord.FILE_NAME));
        assertFalse(selector.activateTrial("A"));
    }

    @Test
    void signedRecordOfAnotherKindIsNotALauncher() throws IOException {
        Path jar = built("v1");
        Path target = selector.store().jar("A");
        Files.createDirectories(target.getParent());
        Files.copy(jar, target);
        SlotRecord record = TestSigning.sign(signing.primary,
            new SlotRecord("foundation", "t1", SlotStore.sha256(target), "base", 1, "A".repeat(86) + "=="));
        record.write(selector.store().slotDir("A").resolve(SlotRecord.FILE_NAME));
        assertFalse(selector.activateTrial("A"));
    }

    @Test
    void confirmationRaisesTheFloorAndOlderSignedLauncherIsRefused() throws IOException {
        confirmed("A", 5);
        assertEquals(5, state().floor);
        install("B", "old", signing.primary, 3);
        assertFalse(selector.activateTrial("B"));
        install("B", "same", signing.primary, 5);
        assertTrue(selector.activateTrial("B"));
        selector.choose(NONE);
        selector.confirm();
        assertEquals(5, state().floor);
    }

    @Test
    void confirmedSlotReplacedByAnOlderSignedLauncherIsNotStarted() throws IOException {
        confirmed("A", 5);
        install("A", "downgrade", signing.primary, 2);
        Choice choice = selector.choose(NONE);
        assertEquals(Choice.Kind.EMBEDDED, choice.kind());
        assertEquals("", state().confirmed);
        assertEquals(5, state().floor);
    }

    @Test
    void floorOnlyGrows() throws IOException {
        confirmed("A", 5);
        install("B", "newer", signing.primary, 9);
        selector.activateTrial("B");
        selector.choose(NONE);
        selector.confirm();
        assertEquals(9, state().floor);
        assertEquals(9, selector.store().state().floor);
    }

    @Test
    void withoutTrustedKeysEveryoneFallsBackToTheEmbeddedLauncher() throws IOException {
        install("A", "v1", signing.primary, 1);
        Selector distrusting = new Selector(tmp.resolve("slots"), TrustedKeys.of(List.of()));
        // the owner lock is held by `selector`; the distrusting instance reads only, which is enough to choose
        assertEquals(Choice.Kind.EMBEDDED, distrusting.choose(NONE).kind());
        assertFalse(distrusting.activateTrial("A"));
    }

    @Test
    void nonOwnerSkipsAnInvalidSlotWithoutWriting() throws IOException {
        confirmed("A", 1);
        install("B", "v2", signing.primary, 2);
        selector.activateTrial("B");
        Files.writeString(selector.store().jar("B"), "tampered", StandardCharsets.UTF_8);
        Selector other = new Selector(tmp.resolve("slots"), signing.trusted());
        assertFalse(other.becomeOwner());
        byte[] before = Files.readAllBytes(selector.store().stateFile());
        assertEquals("A", other.choose(NONE).slot());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(selector.store().stateFile())));
    }

    @Test
    void everyFieldOfTheRecordIsBoundBySignatureAndJar() throws IOException {
        SlotRecord signed = install("A", "v1", signing.primary, 5);
        Path file = selector.store().slotDir("A").resolve(SlotRecord.FILE_NAME);
        SlotRecord[] tampered = {
            new SlotRecord("other", signed.version(), signed.sha256(), signed.kind(), signed.launcherApi(), signed.signature()),
            new SlotRecord(signed.id(), "t999", signed.sha256(), signed.kind(), signed.launcherApi(), signed.signature()),
            new SlotRecord(signed.id(), signed.version(), signed.sha256(), "base", signed.launcherApi(), signed.signature()),
            new SlotRecord(signed.id(), signed.version(), "f".repeat(64), signed.kind(), signed.launcherApi(), signed.signature()),
        };
        for (SlotRecord record : tampered) {
            record.write(file);
            assertFalse(selector.activateTrial("A"), record.toString());
        }
        signed.write(file);
        assertTrue(selector.activateTrial("A"));
    }

    @Test
    void unreadableRecordSkipsTheSlotWithoutRejectingIt() throws IOException {
        confirmed("A", 1);
        Path record = selector.store().slotDir("A").resolve(SlotRecord.FILE_NAME);
        byte[] before = Files.readAllBytes(record);
        Files.delete(record);
        Files.createDirectory(record);   // cannot be read now, which says nothing about the slot
        assertEquals(Choice.Kind.EMBEDDED, selector.choose(NONE).kind());
        SlotState state = state();
        assertEquals("A", state.confirmed);
        assertTrue(state.bad.isEmpty());
        Files.delete(record);
        Files.write(record, before);
        assertEquals("A", selector.choose(NONE).slot());
    }

    @Test
    void unreadableJarSkipsTheSlotWithoutRejectingIt() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                && !"root".equals(System.getProperty("user.name")));
        confirmed("A", 1);
        Path jar = selector.store().jar("A");
        java.util.Set<java.nio.file.attribute.PosixFilePermission> before = Files.getPosixFilePermissions(jar);
        Files.setPosixFilePermissions(jar, java.util.Set.of());
        try {
            assertEquals(Choice.Kind.EMBEDDED, selector.choose(NONE).kind());
            assertEquals("A", state().confirmed);
            assertTrue(state().bad.isEmpty());
        } finally {
            Files.setPosixFilePermissions(jar, before);
        }
        assertEquals("A", selector.choose(NONE).slot());
    }

    @Test
    void temporarilyUnreadableTrialSlotStaysTheTrial() throws IOException {
        confirmed("A", 1);
        install("B", "trial-b", signing.primary, 2);
        assertTrue(selector.activateTrial("B"));
        Path record = selector.store().slotDir("B").resolve(SlotRecord.FILE_NAME);
        byte[] before = Files.readAllBytes(record);
        Files.delete(record);
        Files.createDirectory(record);
        assertEquals("A", selector.choose(NONE).slot());
        assertEquals("B", state().active);
        assertTrue(state().bad.isEmpty());
        Files.delete(record);
        Files.write(record, before);
        Choice choice = selector.choose(NONE);
        assertEquals("B", choice.slot());
        assertTrue(choice.trial());
    }
}
