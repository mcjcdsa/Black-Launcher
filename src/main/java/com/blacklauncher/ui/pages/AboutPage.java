package com.blacklauncher.ui.pages;

import com.blacklauncher.AppInfo;
import com.blacklauncher.BlackLauncherApp;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.ui.about.McSiteCatalog;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;

/**
 * About page with Minecraft site navigation (§9).
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
        libs.setPrefRowCount(7);
        libs.getStyleClass().add("code-area");

        Button openLogs = new Button("打开日志目录");
        openLogs.setOnAction(e -> {
            try {
                Files.createDirectories(AppPaths.logsDir());
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(AppPaths.logsDir().toFile());
                }
            } catch (Exception ex) {
                ExceptionHandler.handle("打开日志目录失败", ex);
            }
        });

        VBox intro = new VBox(10,
                title, name, version, developer, license, link,
                libs, openLogs);
        intro.setAlignment(Pos.TOP_LEFT);

        Separator split = new Separator();
        split.setPadding(new Insets(8, 0, 4, 0));

        VBox nav = buildSiteNavigation();
        VBox content = new VBox(14, intro, split, nav);
        content.setPadding(new Insets(8, 8, 20, 8));
        content.setFillWidth(true);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("about-scroll");
        setCenter(scroll);
    }

    private VBox buildSiteNavigation() {
        Label navTitle = new Label("Minecraft 相关网站导航");
        navTitle.getStyleClass().add("section-title");
        navTitle.setStyle("-fx-font-size: 16px;");

        VBox groupsBox = new VBox(14);
        for (McSiteCatalog.Group group : McSiteCatalog.groups()) {
            groupsBox.getChildren().add(buildGroup(group));
        }

        Label disclaimer = new Label(
                "免责提示：以下为第三方玩家资源站点，布莱克启动器不对站点内容、资源安全性、服务稳定性负责，下载资源请自行甄别风险。");
        disclaimer.getStyleClass().addAll("muted", "nav-disclaimer");
        disclaimer.setWrapText(true);
        disclaimer.setPadding(new Insets(8, 0, 0, 0));

        VBox nav = new VBox(12, navTitle, groupsBox, disclaimer);
        nav.getStyleClass().add("site-nav");
        return nav;
    }

    private VBox buildGroup(McSiteCatalog.Group group) {
        Label groupTitle = new Label(group.title());
        groupTitle.getStyleClass().add("site-group-title");

        VBox list = new VBox(4);
        for (McSiteCatalog.Site site : group.sites()) {
            list.getChildren().add(buildSiteRow(site));
        }

        VBox box = new VBox(6, groupTitle, list);
        box.getStyleClass().add("site-group");
        return box;
    }

    private HBox buildSiteRow(McSiteCatalog.Site site) {
        Label name = new Label(site.name());
        name.getStyleClass().add("site-name");

        Label url = new Label(site.url());
        url.getStyleClass().addAll("muted", "site-url");
        url.setCursor(Cursor.HAND);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(10, name, spacer, url);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(5, 8, 5, 8));
        row.getStyleClass().add("site-row");
        row.setCursor(Cursor.HAND);
        row.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                openUrl(site.url());
            }
        });
        url.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                e.consume();
                openUrl(site.url());
            }
        });

        MenuItem copy = new MenuItem("复制网址");
        copy.setOnAction(e -> copyUrl(site.url()));
        ContextMenu menu = new ContextMenu(copy);
        row.setOnContextMenuRequested(e -> menu.show(row, e.getScreenX(), e.getScreenY()));

        return row;
    }

    private void copyUrl(String url) {
        ClipboardContent content = new ClipboardContent();
        content.putString(url);
        Clipboard.getSystemClipboard().setContent(content);
        ExceptionHandler.showInfo("已复制", url);
    }

    private void openUrl(String url) {
        if (!Validation.isNonBlank(url)) {
            ExceptionHandler.showInfo("提示", "无法打开链接，请检查网址或系统浏览器配置");
            return;
        }
        try {
            URI.create(url);
        } catch (Exception e) {
            ExceptionHandler.showInfo("提示", "无法打开链接，请检查网址或系统浏览器配置");
            return;
        }
        try {
            var hs = BlackLauncherApp.hostServices();
            if (hs != null) {
                hs.showDocument(url);
                return;
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
            ExceptionHandler.showInfo("提示", "无法打开链接，请检查网址或系统浏览器配置");
        } catch (Exception e) {
            ExceptionHandler.showInfo("提示", "无法打开链接，请检查网址或系统浏览器配置");
        }
    }
}
