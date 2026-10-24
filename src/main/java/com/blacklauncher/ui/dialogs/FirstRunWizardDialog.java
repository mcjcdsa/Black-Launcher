package com.blacklauncher.ui.dialogs;

import com.blacklauncher.BlackLauncherApp;
import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.launch.JavaFinder;
import com.blacklauncher.memory.MemoryRecommend;
import com.blacklauncher.memory.SystemMemoryProbe;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * First-launch setup: language, theme, game dir, Java, memory.
 */
public final class FirstRunWizardDialog {

    private FirstRunWizardDialog() {
    }

    /**
     * @return true if the user completed the wizard (not cancelled)
     */
    public static boolean show(Window owner, ConfigManager cm) {
        if (cm == null) {
            return false;
        }
        AppConfig config = cm.getConfig();

        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("欢迎使用布莱克启动器");
        dialog.setHeaderText("首次运行向导 — 完成基本设置");
        if (owner != null) {
            dialog.initOwner(owner);
        }

        ComboBox<String> languageBox = new ComboBox<>(FXCollections.observableArrayList("zh_CN", "en_US"));
        languageBox.getSelectionModel().select(
                "en_US".equalsIgnoreCase(config.getLanguage()) ? "en_US" : "zh_CN");

        ComboBox<String> themeBox = new ComboBox<>(FXCollections.observableArrayList("dark", "light"));
        themeBox.getSelectionModel().select(
                "light".equalsIgnoreCase(config.getTheme()) ? "light" : "dark");

        TextField gameDirField = new TextField();
        gameDirField.setPromptText("游戏目录（versions / libraries / assets）");
        String gameDir = Validation.isNonBlank(config.getGameDirectory())
                ? config.getGameDirectory()
                : AppPaths.gameDir().toAbsolutePath().toString();
        gameDirField.setText(gameDir);
        HBox.setHgrow(gameDirField, Priority.ALWAYS);
        Button browse = new Button("浏览…");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择游戏目录");
            try {
                Path hint = Path.of(gameDirField.getText().trim());
                if (Files.isDirectory(hint)) {
                    chooser.setInitialDirectory(hint.toFile());
                }
            } catch (Exception ignored) {
                // ignore
            }
            var dir = chooser.showDialog(owner);
            if (dir != null) {
                gameDirField.setText(dir.getAbsolutePath());
            }
        });
        HBox gameDirRow = new HBox(8, gameDirField, browse);

        ComboBox<JavaFinder.JavaInstall> javaBox = new ComboBox<>();
        javaBox.setMaxWidth(Double.MAX_VALUE);
        List<JavaFinder.JavaInstall> installs = JavaFinder.discoverInstalls();
        javaBox.setItems(FXCollections.observableArrayList(installs));
        javaBox.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(JavaFinder.JavaInstall item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatJava(item));
            }
        });
        javaBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(JavaFinder.JavaInstall item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatJava(item));
            }
        });
        if (!installs.isEmpty()) {
            String configured = config.getJavaPath();
            JavaFinder.JavaInstall match = installs.stream()
                    .filter(j -> j.executable().equalsIgnoreCase(configured)
                            || (Validation.isNonBlank(configured) && j.executable().startsWith(configured)))
                    .findFirst()
                    .orElse(installs.get(0));
            javaBox.getSelectionModel().select(match);
        }

        Label memoryHint = new Label();
        memoryHint.getStyleClass().add("muted");
        memoryHint.setWrapText(true);
        memoryHint.setText("当前内存：最小 " + config.getMinMemoryMb() + " MB / 最大 " + config.getMaxMemoryMb() + " MB");

        Button recommendMem = new Button("应用推荐内存");
        recommendMem.setOnAction(e -> {
            MemoryRecommend.RecommendResult r = MemoryRecommend.recommend(
                    SystemMemoryProbe.probe().totalBytes(), false);
            config.setMinMemoryMb(r.xmsMb());
            config.setMaxMemoryMb(r.xmxMb());
            memoryHint.setText("已应用推荐：最小 " + r.xmsMb() + " MB / 最大 " + r.xmxMb() + " MB"
                    + (Validation.isNonBlank(r.warningZh()) ? "\n" + r.warningZh() : ""));
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(8, 4, 4, 4));
        int row = 0;
        grid.add(new Label("语言"), 0, row);
        grid.add(languageBox, 1, row++);
        grid.add(new Label("主题"), 0, row);
        grid.add(themeBox, 1, row++);
        grid.add(new Label("游戏目录"), 0, row);
        grid.add(gameDirRow, 1, row++);
        grid.add(new Label("Java"), 0, row);
        grid.add(javaBox, 1, row++);
        grid.add(recommendMem, 0, row);
        grid.add(memoryHint, 1, row);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().setPrefWidth(640);
        ButtonType finish = new ButtonType("完成", ButtonBar.ButtonData.OK_DONE);
        ButtonType skip = new ButtonType("跳过", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(finish, skip);

        dialog.setResultConverter(bt -> bt == finish);

        boolean completed = Boolean.TRUE.equals(dialog.showAndWait().orElse(false));
        if (!completed) {
            return false;
        }

        try {
            config.setLanguage(languageBox.getValue() == null ? "zh_CN" : languageBox.getValue());
            config.setTheme(themeBox.getValue() == null ? "dark" : themeBox.getValue());
            String gd = gameDirField.getText() == null ? "" : gameDirField.getText().trim();
            config.setGameDirectory(gd);
            JavaFinder.JavaInstall selectedJava = javaBox.getValue();
            if (selectedJava != null) {
                config.setJavaPath(selectedJava.executable());
            }
            config.setFirstRunWizardDone(true);
            AppPaths.applyFromConfig(config);
            cm.save();

            if (owner != null && owner.getScene() != null) {
                BlackLauncherApp.applyTheme(owner.getScene(), config.getTheme());
            }
            return true;
        } catch (Exception e) {
            ExceptionHandler.handle("保存首次运行设置失败", e);
            return false;
        }
    }

    private static String formatJava(JavaFinder.JavaInstall j) {
        return "Java " + j.major() + " (" + (j.sixtyFourBit() ? "64" : "32") + ") — " + j.executable();
    }
}
