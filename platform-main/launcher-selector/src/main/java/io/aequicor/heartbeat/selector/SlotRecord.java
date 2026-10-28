package io.aequicor.heartbeat.selector;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Запись о jar лаунчера в слоте (файл {@code slot.properties} рядом с {@code launcher.jar}): та же запись, что в
 * манифесте поезда, вместе с подписью. Селектор проверяет слот по этой копии, не читая манифест поезда.
 *
 * <p>{@code launcherApi} у записи лаунчера — его монотонный уровень: слот с уровнем ниже самого высокого из
 * подтверждённых не принимается, подписанный старый лаунчер нельзя подсунуть заново. {@code version} в упорядочивании не
 * участвует (это идентификатор поезда), но подписывается.
 *
 * <p>Подпись Ed25519 покрывает {@link #canonical()} — запись целиком, а не только байты jar, иначе подмена метаданных
 * проходила бы. Подписывающий (build-logic) обязан получать те же байты; общий тестовый вектор — {@code SlotRecordTest}.
 */
record SlotRecord(String id, String version, String sha256, String kind, int launcherApi, String signature) {
    static final String FILE_NAME = "slot.properties";
    static final String KIND_LAUNCHER = "launcher";
    private static final String HEADER = "heartbeat-launcher-record-v1";

    SlotRecord {
        require(id.matches("[A-Za-z0-9._+-]{1,64}"), "id");
        require(version.matches("[A-Za-z0-9._+-]{1,64}"), "version");
        require(sha256.matches("[0-9a-f]{64}"), "sha256");
        require(kind.matches("[a-z]{1,16}"), "kind");
        require(launcherApi >= 0, "launcherApi");
        require(signature.matches("[A-Za-z0-9+/]{86}=="), "signature");
    }

    /** Байты, которые подписываются: версия формата записи и поля в фиксированном порядке, в UTF-8, с переводом строки. */
    byte[] canonical() {
        return (HEADER + "\nid=" + id + "\nversion=" + version + "\nsha256=" + sha256 + "\nkind=" + kind
            + "\nlauncherApi=" + launcherApi + "\n").getBytes(StandardCharsets.UTF_8);
    }

    static SlotRecord read(Path file) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IllegalArgumentException e) {
            throw new IOException("Slot record " + file + " is corrupted: " + e.getMessage(), e);
        }
        try {
            return new SlotRecord(props.getProperty("id", ""), props.getProperty("version", ""),
                props.getProperty("sha256", ""), props.getProperty("kind", ""),
                Integer.parseInt(props.getProperty("launcherApi", "-1").trim()), props.getProperty("signature", ""));
        } catch (IllegalArgumentException e) {
            throw new IOException("Slot record " + file + " is invalid: " + e.getMessage(), e);
        }
    }

    void write(Path file) throws IOException {
        Properties props = new Properties();
        props.setProperty("id", id);
        props.setProperty("version", version);
        props.setProperty("sha256", sha256);
        props.setProperty("kind", kind);
        props.setProperty("launcherApi", Integer.toString(launcherApi));
        props.setProperty("signature", signature);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            props.store(out, null);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void require(boolean valid, String field) {
        if (!valid) {
            throw new IllegalArgumentException("Invalid " + field);
        }
    }
}
