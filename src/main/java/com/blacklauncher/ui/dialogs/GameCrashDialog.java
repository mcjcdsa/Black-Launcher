package com.blacklauncher.ui.dialogs;

import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.launch.GameExitInfo;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.awt.Desktop;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Shown when the monitored game process crashes or exits abnormally.
 */
public final class GameCrashDialog {

    private GameCrashDialog() {
    }

    public static void show(Window owner, GameExitInfo info) {
        if (info == null) {
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(info.crashed() ? "游戏异常退出" : "游戏已结束");
        dialog.setHeaderText(info.crashed()
                ? "检测到游戏崩溃或闪退"
                : "游戏进程已结束");
        if (owner != null) {
            dialog.initOwner(owner);
        }

        Label summary = new Label(info.summary() == null ? "" : info.summary());
        summary.setWrapText(true);

        Label diagnosis = new Label();
        diagnosis.setWrapText(true);
        diagnosis.getStyleClass().add("status-pill");
        if (Validation.isNonBlank(info.diagnosis())) {
            diagnosis.setText("诊断建议\n" + info.diagnosis());
            diagnosis.setVisible(true);
            diagnosis.setManaged(true);
        } else {
            diagnosis.setVisible(false);
            diagnosis.setManaged(false);
        }

        TextArea preview = new TextArea();
        preview.setEditable(false);
        preview.setWrapText(true);
        preview.setPrefRowCount(12);
        preview.getStyleClass().add("code-area");
        preview.setText(readTail(info.logFile(), 80));
        VBox.setVgrow(preview, Priority.ALWAYS);

        Button viewLog = new Button("查看完整日志");
        viewLog.setOnAction(e -> openPath(info.logFile()));

        Button exportLog = new Button("导出日志");
        exportLog.setOnAction(e -> exportLog(owner, info.logFile()));

        Button openCrashReports = new Button("打开崩溃报告目录");
        Path crashDir = info.gameDir() == null ? null : info.gameDir().resolve("crash-reports");
        openCrashReports.setDisable(crashDir == null || !Files.isDirectory(crashDir));
        Path crashDirFinal = crashDir;
        openCrashReports.setOnAction(e -> openPath(crashDirFinal));

        HBox actions = new HBox(10, viewLog, exportLog, openCrashReports);

        VBox box = new VBox(12, summary, diagnosis, new Label("日志末尾预览"), preview, actions);
        box.setPadding(new Insets(8, 4, 4, 4));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().setPrefWidth(720);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.showAndWait();
    }

    private static String readTail(Path logFile, int maxLines) {
        if (logFile == null || !Files.isRegularFile(logFile)) {
            return "（日志文件不存在）";
        }
        try {
            var lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - maxLines);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (Exception e) {
            return "读取日志失败: " + e.getMessage();
        }
    }

    private static void openPath(Path path) {
        if (path == null) {
            ExceptionHandler.showInfo("提示", "路径无效");
            return;
        }
        try {
            Path target = path;
            if (!Files.exists(target) && target.getParent() != null) {
                target = target.getParent();
            }
            if (!Files.exists(target)) {
                ExceptionHandler.showInfo("提示", "文件不存在: " + path);
                return;
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(target.toFile());
            } else {
                ExceptionHandler.showInfo("路径", target.toAbsolutePath().toString());
            }
        } catch (Exception e) {
            ExceptionHandler.handle("打开失败", e);
        }
    }

    private static void exportLog(Window owner, Path logFile) {
        if (logFile == null || !Files.isRegularFile(logFile)) {
            ExceptionHandler.showInfo("提示", "没有可导出的日志文件");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出启动日志");
        chooser.setInitialFileName(logFile.getFileName().toString());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("日志文件", "*.log", "*.txt"));
        var file = chooser.showSaveDialog(owner);
        if (file == null) {
            return;
        }
        try {
            Files.copy(logFile, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            ExceptionHandler.showInfo("完成", "日志已导出到：\n" + file.getAbsolutePath());
        } catch (Exception e) {
            ExceptionHandler.handle("导出日志失败", e);
        }
    }
}
