package io.aequicor.heartbeat.selector;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Предложение нового лаунчера из поезда: копия в соседний слот, активация пробного слота, отказы. */
class SelectorStageTest {
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

    /** Jar в хранилище поезда и запрос лаунчера с записью, подписанной {@code key}. */
    Map<String, String> request(String content, KeyPair key, int level) throws IOException {
        Path jar = Files.createTempFile(tmp, "train", ".jar");
        Files.writeString(jar, content, StandardCharsets.UTF_8);
        SlotRecord record = TestSigning.record(key, "launcher", "t" + level, SlotStore.sha256(jar), level);
        Map<String, String> request = new HashMap<>();
        request.put("jar", jar.toString());
        request.put("id", record.id());
        request.put("version", record.version());
        request.put("sha256", record.sha256());
        request.put("kind", record.kind());
        request.put("launcherApi", Integer.toString(record.launcherApi()));
        request.put("signature", record.signature());
        return request;
    }

    SlotState state() throws IOException {
        return selector.store().state();
    }

    /** Подтверждённый слот {@code slot}, установленный так же, как обновление. */
    void confirmed(String slot, int level) throws IOException {
        TestSigning.install(selector.store(), slot, Files.writeString(Files.createTempFile(tmp, "c", ".jar"),
            "confirmed-" + slot + level), signing.primary, level);
        assertTrue(selector.activateTrial(slot));
        selector.choose(NONE);
        selector.confirm();
    }

    @Test
    void firstLauncherGoesToSlotAAsATrial() throws IOException {
        assertTrue(selector.stageUpdate(request("v1", signing.primary, 1)));
        assertEquals("A", state().active);
        assertEquals("", state().confirmed);
        assertEquals("v1", Files.readString(selector.store().jar("A")));
        Choice choice = selector.choose(NONE);
        assertEquals("A", choice.slot());
        assertTrue(choice.trial());
    }

    @Test
    void updateGoesToTheNeighbourAndNeverTouchesTheConfirmedSlot() throws IOException {
        confirmed("A", 1);
        byte[] before = Files.readAllBytes(selector.store().jar("A"));
        byte[] recordBefore = Files.readAllBytes(selector.store().slotDir("A").resolve(SlotRecord.FILE_NAME));
        assertTrue(selector.stageUpdate(request("v2", signing.primary, 2)));
        assertEquals("B", state().active);
        assertEquals("A", state().confirmed);
        assertEquals("v2", Files.readString(selector.store().jar("B")));
        assertArrayEquals(before, Files.readAllBytes(selector.store().jar("A")));
        assertArrayEquals(recordBefore,
            Files.readAllBytes(selector.store().slotDir("A").resolve(SlotRecord.FILE_NAME)));
        assertFalse(Files.exists(selector.store().slotDir("B").resolve("launcher.jar.tmp")));
    }

    @Test
    void thenTheConfirmedNewSlotIsReplacedByTheNextUpdateInTheOtherSlot() throws IOException {
        confirmed("A", 1);
        assertTrue(selector.stageUpdate(request("v2", signing.primary, 2)));
        selector.choose(NONE);
        selector.confirm();
        assertEquals("B", state().confirmed);
        assertTrue(selector.stageUpdate(request("v3", signing.primary, 3)));
        assertEquals("A", state().active);
        assertEquals("v3", Files.readString(selector.store().jar("A")));
        assertEquals("v2", Files.readString(selector.store().jar("B")));
    }

    @Test
    void updateIsDeferredWhileATrialIsRunning() throws IOException {
        confirmed("A", 1);
        assertTrue(selector.stageUpdate(request("v2", signing.primary, 2)));
        selector.choose(NONE);   // B runs as a trial, not confirmed yet
        assertFalse(selector.stageUpdate(request("v3", signing.primary, 3)));
        assertEquals("v2", Files.readString(selector.store().jar("B")));
    }

    @Test
    void sameJarAsTheConfirmedOneIsNotInstalledAgain() throws IOException {
        confirmed("A", 1);
        Map<String, String> same = request("confirmed-A1", signing.primary, 1);
        assertFalse(selector.stageUpdate(same));
        assertFalse(selector.store().hasJar("B"));
    }

    @Test
    void rejectedJarIsNotInstalledAgain() throws IOException {
        confirmed("A", 1);
        Map<String, String> bad = request("bad", signing.primary, 2);
        assertTrue(selector.stageUpdate(bad));
        selector.choose(NONE);
        selector.choose(NONE);
        selector.choose(NONE);   // the trial B is rejected after two unconfirmed launches
        assertEquals("A", state().active);
        assertFalse(selector.stageUpdate(bad));
    }

    @Test
    void foreignSignatureAndLowLevelAreRefused() throws IOException {
        confirmed("A", 5);
        assertFalse(selector.stageUpdate(request("v2", TestSigning.newKeyPair(), 6)));
        assertFalse(selector.stageUpdate(request("old", signing.primary, 3)));
        assertFalse(selector.store().hasJar("B"));
        assertEquals("A", state().active);
    }

    @Test
    void jarThatDoesNotMatchTheRecordIsNotInstalled() throws IOException {
        confirmed("A", 1);
        Map<String, String> request = request("v2", signing.primary, 2);
        Files.writeString(Path.of(request.get("jar")), "damaged in the store", StandardCharsets.UTF_8);
        assertFalse(selector.stageUpdate(request));
        assertFalse(selector.store().hasJar("B"));
        assertFalse(Files.exists(selector.store().slotDir("B").resolve("launcher.jar.tmp")));
        assertEquals("A", state().active);
    }

    @Test
    void busyTargetSlotDefersTheUpdate() throws IOException {
        confirmed("A", 1);
        Files.writeString(selector.store().slotDir("B"), "a file where the slot directory should be",
            StandardCharsets.UTF_8);
        assertFalse(selector.stageUpdate(request("v2", signing.primary, 2)));
        assertEquals("A", state().active);
    }

    @Test
    void invalidRequestsAreRefused() throws IOException {
        confirmed("A", 1);
        Map<String, String> broken = request("v2", signing.primary, 2);
        broken.remove("signature");
        assertFalse(selector.stageUpdate(broken));
        Map<String, String> notANumber = request("v3", signing.primary, 3);
        notANumber.put("launcherApi", "x");
        assertFalse(selector.stageUpdate(notANumber));
        Map<String, String> traversal = request("v4", signing.primary, 4);
        traversal.put("id", "../evil");
        assertFalse(selector.stageUpdate(traversal));
    }

    @Test
    void nonOwnerDoesNotInstall() throws IOException {
        Selector other = new Selector(tmp.resolve("slots"), signing.trusted());
        assertFalse(other.becomeOwner());
        assertFalse(other.stageUpdate(request("v1", signing.primary, 1)));
        assertFalse(selector.store().hasJar("A"));
    }

    @Test
    void stateOfANewerFormatIsNotChanged() throws IOException {
        String newer = "format=" + (SlotState.FORMAT + 1) + "\nactive=A\nconfirmed=A\n";
        Files.createDirectories(selector.store().dir());
        Files.writeString(selector.store().stateFile(), newer, StandardCharsets.UTF_8);
        assertFalse(selector.stageUpdate(request("v2", signing.primary, 2)));
        assertEquals(newer, Files.readString(selector.store().stateFile(), StandardCharsets.UTF_8));
        assertFalse(selector.store().hasJar("B"));
    }
}
