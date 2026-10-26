package io.aequicor.heartbeat.launcher;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Path;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * Экран первого запуска лаунчера (Swing: Compose и бандлы ещё не загружены): прогресс загрузки бандлов, «Повторить»
 * после сбоя и «Экспортировать журнал». Вся логика в {@link FirstLaunch}; экран только показывает её ход.
 */
public final class FirstLaunchScreen {
    /** Выполняется в фоновом потоке экрана: загрузка бандлов поезда. */
    public interface Task {
        void run(FirstLaunch.Listener listener) throws IOException;
    }

    private final Task task;
    private final Runnable onReady;
    private final FirstLaunchLog log = new FirstLaunchLog();
    private final JFrame frame = new JFrame("Heartbeat");
    private final JLabel status = new JLabel("Подготовка первого запуска…");
    private final JProgressBar bar = new JProgressBar(0, 100);
    private final JTextArea logView = new JTextArea(8, 48);
    private final JButton retry = new JButton("Повторить");
    private final JButton export = new JButton("Экспортировать журнал");

    /**
     * @param task загрузка бандлов
     * @param onReady вызывается в потоке UI после успеха, когда окно уже закрыто
     */
    public FirstLaunchScreen(Task task, Runnable onReady) {
        this.task = task;
        this.onReady = onReady;
    }

    /** Показывает окно и запускает загрузку. Вызывать из потока UI. */
    public void show() {
        bar.setIndeterminate(true);
        logView.setEditable(false);
        retry.setEnabled(false);
        retry.addActionListener(event -> start());
        export.addActionListener(event -> exportLog());
        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.add(status, BorderLayout.NORTH);
        top.add(bar, BorderLayout.SOUTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(export);
        buttons.add(retry);
        frame.setLayout(new BorderLayout(8, 8));
        frame.add(top, BorderLayout.NORTH);
        frame.add(new JScrollPane(logView), BorderLayout.CENTER);
        frame.add(buttons, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        start();
    }

    private void start() {
        retry.setEnabled(false);
        bar.setIndeterminate(true);
        status.setText("Загрузка компонентов…");
        Thread worker = new Thread(() -> {
            try {
                task.run(new FirstLaunch.Listener() {
                    @Override
                    public void onLog(String line) {
                        append(line);
                    }

                    @Override
                    public void onProgress(int done, int total, String bundleId, long bytes) {
                        SwingUtilities.invokeLater(() -> showProgress(done, total, bundleId));
                    }
                });
                SwingUtilities.invokeLater(() -> {
                    frame.dispose();
                    onReady.run();
                });
            } catch (IOException | RuntimeException e) {
                append("Ошибка: " + e);
                SwingUtilities.invokeLater(() -> {
                    bar.setIndeterminate(false);
                    status.setText("Не удалось загрузить компоненты. Проверьте соединение и повторите.");
                    retry.setEnabled(true);
                });
            }
        }, "heartbeat-first-launch");
        worker.setDaemon(true);
        worker.start();
    }

    private void showProgress(int done, int total, String bundleId) {
        bar.setIndeterminate(false);
        bar.setValue(total == 0 ? 100 : done * 100 / total);
        status.setText("Загрузка компонентов: " + Math.min(done + 1, total) + " из " + total + " (" + bundleId + ")");
    }

    private void append(String line) {
        log.add(line);
        SwingUtilities.invokeLater(() -> logView.append(line + System.lineSeparator()));
    }

    private void exportLog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(Path.of("heartbeat-first-launch.log").toFile());
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            log.export(chooser.getSelectedFile().toPath());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "Не удалось сохранить журнал: " + e.getMessage());
        }
    }
}
