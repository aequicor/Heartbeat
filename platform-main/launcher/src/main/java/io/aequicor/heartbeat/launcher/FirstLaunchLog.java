package io.aequicor.heartbeat.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Журнал первого запуска: хранится в памяти (ограниченно) и выгружается пользователем в файл для обращения в поддержку. */
public final class FirstLaunchLog {
    static final int MAX_LINES = 500;

    private final List<String> lines = new ArrayList<>();

    public synchronized void add(String line) {
        if (lines.size() == MAX_LINES) {
            lines.remove(0);
        }
        lines.add(line);
    }

    public synchronized List<String> snapshot() {
        return List.copyOf(lines);
    }

    /** Записывает журнал в {@code target} (UTF-8, по строке на запись). */
    public void export(Path target) throws IOException {
        Files.writeString(target, String.join(System.lineSeparator(), snapshot()) + System.lineSeparator(),
            StandardCharsets.UTF_8);
    }
}
