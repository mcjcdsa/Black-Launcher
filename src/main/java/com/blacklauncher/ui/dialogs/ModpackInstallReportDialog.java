package com.blacklauncher.ui.dialogs;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.launch.JavaFinder;
import com.blacklauncher.launch.LaunchQuickFixService;
import com.blacklauncher.memory.MemoryProfile;
import com.blacklauncher.memory.MemoryProfileStore;
import com.blacklauncher.memory.MemoryRecommend;
import com.blacklauncher.memory.SystemMemoryProbe;
import com.blacklauncher.modpack.ModpackInstallReport;
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
import javafx.stage.Window;

import java.util.Optional;

/**
 * Post-import report with optional one-click launch and memory/Java tips (§6 / P1.3).
 */
public final class ModpackInstallReportDialog {

    public enum Action {
        CLOSE,
        LAUNCH
    }

    private ModpackInstallReportDialog() {
    }

    public static Optional<Action> show(Window owner, ModpackInstallReport report) {
        return show(owner, report, null);
    }

    public static Optional<Action> show(Window owner, ModpackInstallReport report, AppConfig config) {
        Dialog<Action> dialog = new Dialog<>();
        dialog.setTitle("整合包导入完成");
        String header = report.isPartial() ? "导入完成（部分缺失）" : "导入成功";
        dialog.setHeaderText(header);
        if (owner != null) {
            dialog.initOwner(owner);
        }

        Label summary = new Label(report.getSummary());
        summary.setWrapText(true);

        StringBuilder body = new StringBuilder();
        body.append("版本 ID：").append(report.getVersionId()).append('\n');
        body.append("成功文件：").append(report.getInstalled().size()).append('\n');
        body.append("缺失文件：").append(report.getMissing().size()).append('\n');
        if (!report.getMissing().isEmpty()) {
            body.append("\n—— 缺失 / 失效 ——\n");
            for (String m : report.getMissing()) {
                body.append("· ").append(m).append('\n');
            }
        }
        if (!report.getWarnings().isEmpty()) {
            body.append("\n—— 警告 ——\n");
            for (String w : report.getWarnings()) {
                body.append("· ").append(w).append('\n');
            }
        }

        TextArea area = new TextArea(body.toString());
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(12);
        VBox.setVgrow(area, Priority.ALWAYS);

        VBox box = new VBox(10, summary, area);
        box.setPadding(new Insets(8, 4, 4, 4));

        MemoryRecommend.RecommendResult memTip = null;
        JavaFinder.JavaInstall javaTip = null;
        if (report.isSuccess()) {
            try {
                memTip = MemoryRecommend.recommend(SystemMemoryProbe.probe().totalBytes(), true);
            } catch (Exception ignored) {
                // tip optional
            }
            if (config != null && Validation.isNonBlank(report.getVersionId())) {
                try {
                    javaTip = LaunchQuickFixService.suggestJavaForVersion(config, report.getVersionId())
                            .orElse(null);
                } catch (Exception ignored) {
                    // tip optional
                }
            }
            Label tips = new Label(buildTipsText(memTip, javaTip));
            tips.setWrapText(true);
            tips.getStyleClass().add("muted");
            box.getChildren().add(tips);

            if (config != null && memTip != null && Validation.isNonBlank(report.getVersionId())) {
                MemoryRecommend.RecommendResult applyMem = memTip;
                JavaFinder.JavaInstall applyJava = javaTip;
                Button applyMemBtn = new Button("应用建议内存");
                applyMemBtn.setOnAction(e -> {
                    try {
                        applySuggestedMemory(config, report.getVersionId(), applyMem);
                        tips.setText(buildTipsText(applyMem, applyJava) + "\n已应用建议内存到本版本与全局配置。");
                        applyMemBtn.setDisable(true);
                    } catch (Exception ex) {
                        tips.setText(buildTipsText(applyMem, applyJava) + "\n应用失败：" + ex.getMessage());
                    }
                });
                box.getChildren().add(new HBox(8, applyMemBtn));
            }
        }

        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().setPrefWidth(520);

        ButtonType launch = new ButtonType("一键启动", ButtonBar.ButtonData.OK_DONE);
        ButtonType close = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        if (report.isSuccess()) {
            dialog.getDialogPane().getButtonTypes().setAll(launch, close);
        } else {
            dialog.getDialogPane().getButtonTypes().setAll(close);
        }

        dialog.setResultConverter(btn -> btn == launch ? Action.LAUNCH : Action.CLOSE);
        return dialog.showAndWait();
    }

    private static String buildTipsText(MemoryRecommend.RecommendResult mem, JavaFinder.JavaInstall java) {
        StringBuilder sb = new StringBuilder("—— 启动建议 ——\n");
        if (mem != null) {
            sb.append("建议内存：Xms ").append(mem.xmsMb()).append(" MB / Xmx ")
                    .append(mem.xmxMb()).append(" MB");
            if (Validation.isNonBlank(mem.warningZh())) {
                sb.append("（").append(mem.warningZh()).append('）');
            }
            sb.append('\n');
        } else {
            sb.append("建议内存：暂无法探测本机内存\n");
        }
        if (java != null) {
            sb.append("建议 Java：").append(java.major()).append("（")
                    .append(java.executable()).append('）');
        } else {
            sb.append("建议 Java：未找到匹配本机安装（可在设置中指定）");
        }
        return sb.toString();
    }

    private static void applySuggestedMemory(AppConfig config, String versionId,
                                            MemoryRecommend.RecommendResult mem) throws Exception {
        MemoryProfile profile = MemoryProfileStore.load(config, versionId);
        profile.setXmsMb(mem.xmsMb());
        profile.setXmxMb(mem.xmxMb());
        profile.setModpackHint(true);
        MemoryProfileStore.save(config, versionId, profile);
        config.setMinMemoryMb(mem.xmsMb());
        config.setMaxMemoryMb(Math.max(mem.xmsMb(), mem.xmxMb()));
    }
}
