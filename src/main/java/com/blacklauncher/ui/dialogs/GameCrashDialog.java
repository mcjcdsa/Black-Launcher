package com.blacklauncher.ui.dialogs;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.launch.GameExitInfo;
import com.blacklauncher.launch.JavaFinder;
import com.blacklauncher.launch.LaunchQuickFixService;
import com.blacklauncher.support.FeedbackPackExporter;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
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
import java.util.Optional;

/**
 * Shown when the monitored game process crashes or exits abnormally.
 */
public final class GameCrashDialog {

    private GameCrashDialog() {
    }

    /** @deprecated Prefer {@link #show(Window, GameExitInfo, AppConfig)}. */
    @Deprecated
    public static void show(Window owner, GameExitInfo info) {
        show(owner, info, null);
    }

    public static void show(Window owner, GameExitInfo info, AppConfig config) {
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

        Button copyAdvice = new Button("复制诊断建议");
        copyAdvice.setOnAction(e -> copyDiagnosis(info));

        Button exportPack = new Button("导出反馈包");
        exportPack.setOnAction(e -> exportFeedbackPack(owner, info, config));

        Button quickFix = new Button("快速修复");
        quickFix.setDisable(config == null || !Validation.isSafeVersionId(info.versionId()));
        quickFix.setOnAction(e -> showQuickFix(owner, dialog.getDialogPane().getScene().getWindow(), info, config));

        HBox actions = new HBox(10, viewLog, exportLog, openCrashReports);
        HBox extra = new HBox(10, copyAdvice, exportPack, quickFix);

        VBox box = new VBox(12, summary, diagnosis, new Label("日志末尾预览"), preview, actions, extra);
        box.setPadding(new Insets(8, 4, 4, 4));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().setPrefWidth(720);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.showAndWait();
    }

    private static void copyDiagnosis(GameExitInfo info) {
        StringBuilder sb = new StringBuilder();
        if (Validation.isNonBlank(info.diagnosis())) {
            sb.append(info.diagnosis().trim());
        }
        if (Validation.isNonBlank(info.summary())) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(info.summary().trim());
        }
        if (sb.length() == 0) {
            ExceptionHandler.showInfo("提示", "没有可复制的诊断建议");
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(content);
        ExceptionHandler.showInfo("完成", "诊断建议已复制到剪贴板");
    }

    private static void exportFeedbackPack(Window owner, GameExitInfo info, AppConfig config) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出反馈包");
        String base = Validation.isSafeVersionId(info.versionId())
                ? "blacklauncher-feedback-" + info.versionId()
                : "blacklauncher-feedback";
        chooser.setInitialFileName(base + ".zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP 反馈包", "*.zip"));
        var file = chooser.showSaveDialog(owner);
        if (file == null) {
            return;
        }
        try {
            Path zip = FeedbackPackExporter.export(config, info.versionId(), info.logFile(), file.toPath());
            ExceptionHandler.showInfo("完成", "反馈包已导出到：\n" + zip.toAbsolutePath());
        } catch (Exception e) {
            ExceptionHandler.handle("导出反馈包失败", e);
        }
    }

    private static void showQuickFix(Window owner, Window dialogOwner, GameExitInfo info, AppConfig config) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("快速修复");
        alert.setHeaderText("选择一项修复操作");
        alert.setContentText("降低内存 / 禁用最近模组 / 建议更换 Java");
        if (dialogOwner != null) {
            alert.initOwner(dialogOwner);
        } else if (owner != null) {
            alert.initOwner(owner);
        }

        ButtonType lowerMem = new ButtonType("降低内存");
        ButtonType disableMod = new ButtonType("禁用最近模组");
        ButtonType suggestJava = new ButtonType("打开设置换 Java");
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(lowerMem, disableMod, suggestJava, cancel);

        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == cancel) {
            return;
        }
        try {
            if (choice.get() == lowerMem) {
                LaunchQuickFixService.applyLowerMemory(config, info.versionId());
                ExceptionHandler.showInfo("完成", "已降低该版本最大内存并写入 memory.json");
            } else if (choice.get() == disableMod) {
                Optional<Path> disabled = LaunchQuickFixService.disableNewestMod(config, info.versionId());
                if (disabled.isPresent()) {
                    ExceptionHandler.showInfo("完成", "已禁用模组：\n" + disabled.get().getFileName());
                } else {
                    ExceptionHandler.showInfo("提示", "未找到可禁用的 .jar 模组");
                }
            } else if (choice.get() == suggestJava) {
                Optional<JavaFinder.JavaInstall> suggested =
                        LaunchQuickFixService.suggestJavaForVersion(config, info.versionId());
                if (suggested.isPresent()) {
                    JavaFinder.JavaInstall j = suggested.get();
                    config.setJavaPath(j.executable());
                    ExceptionHandler.showInfo("建议 Java",
                            "已填入推荐路径（请在设置页确认并保存）：\n"
                                    + "Java " + j.major() + " (" + (j.sixtyFourBit() ? "64" : "32") + " 位)\n"
                                    + j.executable());
                } else {
                    ExceptionHandler.showInfo("提示", "未找到匹配的本机 Java，请在设置中手动指定。");
                }
            }
        } catch (Exception e) {
            ExceptionHandler.handle("快速修复失败", e);
        }
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
