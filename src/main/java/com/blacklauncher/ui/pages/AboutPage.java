package com.blacklauncher.ui.pages;

import com.blacklauncher.AppInfo;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.util.AppPaths;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.net.URI;

/**
 * About page — native labels + separator only (@5).
 */
public class AboutPage extends BorderPane {

    public AboutPage() {
        getStyleClass().add("page");
        setPadding(new Insets(4));

        Label title = new Label("关于");
        title.getStyleClass().add("page-title");

        Label name = new Label(AppInfo.NAME + "（" + AppInfo.NAME_EN + "）");
        name.setStyle("-fx-font-size: 20px; -fx-font-weight: 600;");
        Label version = new Label("版本 " + AppInfo.VERSION);
        Label developer = new Label("开发者：" + AppInfo.DEVELOPER);
        Label license = new Label("开源协议：" + AppInfo.LICENSE);

        Hyperlink link = new Hyperlink(AppInfo.OFFICIAL_URL);
        link.setOnAction(e -> openUrl(AppInfo.OFFICIAL_URL));

        TextArea libs = new TextArea("""
                第三方库声明：
                - JavaFX (OpenJFX)
                - OkHttp / Gson / SQLite JDBC / SLF4J·Logback
                - Modrinth API

                数据源：Mojang version_manifest、Modrinth
                """);
        libs.setEditable(false);
        libs.setWrapText(true);
        libs.setPrefRowCount(8);
        libs.getStyleClass().add("code-area");

        Button openLogs = new Button("打开日志目录");
        openLogs.setOnAction(e -> {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(AppPaths.logsDir().toFile());
                }
            } catch (Exception ex) {
                ExceptionHandler.handle("打开日志目录失败", ex);
            }
        });

        VBox box = new VBox(10,
                title, name, version, developer, license, link,
                new Separator(), libs, openLogs);
        box.setAlignment(Pos.TOP_LEFT);
        box.setPadding(new Insets(8, 4, 16, 4));
        setCenter(box);
    }

    private void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {
            // non-fatal
        }
    }
}
