package com.blacklauncher.ui;

import com.blacklauncher.AppInfo;
import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.download.InstalledVersionService;
import com.blacklauncher.launch.GameLauncher;
import com.blacklauncher.ui.pages.AboutPage;
import com.blacklauncher.ui.pages.DownloadPage;
import com.blacklauncher.ui.pages.HomePage;
import com.blacklauncher.ui.pages.NewsPage;
import com.blacklauncher.ui.pages.OnlinePage;
import com.blacklauncher.ui.pages.SettingsPage;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * BorderPane shell: collapsible sidebar, page stack, status bar (@5).
 */
public class MainShell extends BorderPane {

    private static final double SIDEBAR_EXPANDED = 180;
    private static final double SIDEBAR_COLLAPSED = 60;

    private final ConfigManager configManager;
    private final StackPane content = new StackPane();
    private final StackPane centerStack = new StackPane();
    private final Label dropHint = new Label("松手释放以导入整合包");
    private final StackPane dropOverlay = new StackPane(dropHint);
    private final Label statusLeft = new Label("就绪");
    private final Label statusRight = new Label();
    private final Label sidebarFooter = new Label();
    private final VBox sidebar = new VBox();
    private final ToggleGroup navGroup = new ToggleGroup();

    private final HomePage homePage;
    private final DownloadPage downloadPage;
    private final SettingsPage settingsPage;
    private final AboutPage aboutPage;
    private final NewsPage newsPage;
    private final OnlinePage onlinePage;

    private final ToggleButton homeNav;
    private final ToggleButton downloadNav;
    private final ToggleButton onlineNav;
    private final ToggleButton newsNav;
    private final ToggleButton settingsNav;
    private final ToggleButton aboutNav;
    private final Button collapseBtn = new Button("«");

    private Node currentPage;
    private boolean collapsed;

    public MainShell(ConfigManager configManager, Database database) {
        this.configManager = configManager;
        getStyleClass().add("root-shell");

        UiAnimation.setEnabled(configManager.getConfig().isEnableAnimation());
        collapsed = configManager.getConfig().isSidebarCollapsed();

        InstalledVersionService installedVersionService =
                new InstalledVersionService(database, configManager.getConfig());
        GameLauncher gameLauncher = new GameLauncher(configManager.getConfig(), database);

        homePage = new HomePage(configManager, database, installedVersionService, gameLauncher, this::setStatus);
        downloadPage = new DownloadPage(configManager, database,
                () -> homePage.refresh(), homePage::importModpackFiles);
        settingsPage = new SettingsPage(configManager, database, this::getScene, this::onAnimationSettingChanged);
        aboutPage = new AboutPage();
        newsPage = new NewsPage(configManager, database);

        homeNav = navToggle("主页", "主");
        downloadNav = navToggle("下载", "下");
        onlineNav = navToggle("联机", "联");
        newsNav = navToggle("新闻", "新");
        settingsNav = navToggle("设置", "设");
        aboutNav = navToggle("关于", "关");

        onlinePage = new OnlinePage(() -> selectAndShow(homeNav, homePage));
        wireNav(homeNav, homePage);
        wireNav(downloadNav, downloadPage);
        wireNav(onlineNav, onlinePage);
        wireNav(newsNav, newsPage);
        wireNav(settingsNav, settingsPage);
        wireNav(aboutNav, aboutPage);

        dropHint.getStyleClass().add("drop-hint-label");
        dropOverlay.getStyleClass().add("drop-overlay");
        dropOverlay.setVisible(false);
        dropOverlay.setMouseTransparent(true);
        centerStack.getChildren().setAll(content, dropOverlay);
        StackPane.setAlignment(dropHint, Pos.CENTER);

        setLeft(buildSidebar());
        setCenter(centerStack);
        setBottom(buildStatusBar());
        BorderPane.setMargin(centerStack, new Insets(12, 14, 8, 14));

        installModpackDragDrop();

        applySidebarWidth(false);
        updateSidebarLabels();
        updateFooter();
        setStatus("就绪", "联机：开发中");
        selectAndShow(homeNav, homePage);
    }

    private void installModpackDragDrop() {
        setOnDragOver(e -> {
            if (e.getGestureSource() != this && e.getDragboard().hasFiles()
                    && containsModpackFile(e.getDragboard())) {
                e.acceptTransferModes(TransferMode.COPY);
                dropOverlay.setVisible(true);
            }
            e.consume();
        });
        setOnDragEntered(e -> {
            if (e.getDragboard().hasFiles() && containsModpackFile(e.getDragboard())) {
                dropOverlay.setVisible(true);
            }
            e.consume();
        });
        setOnDragExited(e -> {
            dropOverlay.setVisible(false);
            e.consume();
        });
        setOnDragDropped(e -> {
            dropOverlay.setVisible(false);
            Dragboard db = e.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                List<Path> packs = new ArrayList<>();
                for (File f : db.getFiles()) {
                    if (f == null) {
                        continue;
                    }
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".zip") || n.endsWith(".mrpack")) {
                        packs.add(f.toPath());
                    }
                }
                if (!packs.isEmpty()) {
                    selectAndShow(homeNav, homePage);
                    homePage.importModpackFiles(packs);
                    success = true;
                    setStatus("正在导入整合包…");
                }
            }
            e.setDropCompleted(success);
            e.consume();
        });
    }

    private static boolean containsModpackFile(Dragboard db) {
        if (db == null || db.getFiles() == null) {
            return false;
        }
        for (File f : db.getFiles()) {
            if (f == null) {
                continue;
            }
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".zip") || n.endsWith(".mrpack")) {
                return true;
            }
        }
        return false;
    }

    private void wireNav(ToggleButton nav, Node page) {
        nav.setOnAction(e -> {
            if (!nav.isSelected()) {
                nav.setSelected(true);
            }
            showPage(page, nav);
        });
    }

    private void selectAndShow(ToggleButton nav, Node page) {
        if (nav != null) {
            nav.setSelected(true);
        }
        showPage(page, nav);
    }

    public void setStatus(String left) {
        setStatus(left, statusRight.getText());
    }

    public void setStatus(String left, String right) {
        if (left != null) {
            statusLeft.setText(left);
        }
        if (right != null) {
            statusRight.setText(right);
        }
        updateFooter();
    }

    private void onAnimationSettingChanged() {
        UiAnimation.setEnabled(configManager.getConfig().isEnableAnimation());
    }

    private VBox buildSidebar() {
        collapseBtn.getStyleClass().add("button");
        collapseBtn.setMaxWidth(Double.MAX_VALUE);
        collapseBtn.setOnAction(e -> toggleSidebar());

        VBox navButtons = new VBox(2, homeNav, downloadNav, onlineNav, newsNav, settingsNav, aboutNav);
        VBox.setVgrow(navButtons, Priority.ALWAYS);

        sidebarFooter.getStyleClass().add("sidebar-footer");
        sidebarFooter.setWrapText(true);

        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(10, 0, 0, 0));
        sidebar.getChildren().setAll(collapseBtn, navButtons, sidebarFooter);
        sidebar.setPrefWidth(collapsed ? SIDEBAR_COLLAPSED : SIDEBAR_EXPANDED);
        sidebar.setMinWidth(SIDEBAR_COLLAPSED);
        return sidebar;
    }

    private HBox buildStatusBar() {
        statusLeft.getStyleClass().add("muted");
        statusRight.getStyleClass().add("muted");
        HBox bar = new HBox(12, statusLeft, new javafx.scene.layout.Region(), statusRight);
        HBox.setHgrow(bar.getChildren().get(1), Priority.ALWAYS);
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private ToggleButton navToggle(String expandedText, String collapsedText) {
        ToggleButton btn = new ToggleButton(expandedText);
        btn.setUserData(new String[]{expandedText, collapsedText});
        btn.getStyleClass().add("nav-toggle");
        btn.setToggleGroup(navGroup);
        btn.setMaxWidth(Double.MAX_VALUE);
        return btn;
    }

    private void showPage(Node page, ToggleButton nav) {
        if (nav != null && !nav.isSelected()) {
            nav.setSelected(true);
        }
        Node previous = currentPage;
        if (page == previous) {
            return;
        }
        content.getChildren().setAll(page);
        currentPage = page;
        UiAnimation.fadeSwap(previous, page, () -> {
            if (page instanceof HomePage hp) {
                hp.refresh();
            }
        });
        if (page instanceof HomePage) {
            setStatus("主页", "联机：开发中");
        } else if (page instanceof DownloadPage) {
            setStatus("下载", "联机：开发中");
        } else if (page instanceof OnlinePage) {
            setStatus("联机大厅（占位）", "联机：开发中");
        } else if (page instanceof NewsPage) {
            setStatus("新闻", "联机：开发中");
        } else if (page instanceof SettingsPage) {
            setStatus("设置", "联机：开发中");
        } else if (page instanceof AboutPage) {
            setStatus("关于", "联机：开发中");
        }
    }

    private void toggleSidebar() {
        collapsed = !collapsed;
        configManager.getConfig().setSidebarCollapsed(collapsed);
        try {
            configManager.save();
        } catch (Exception ignored) {
            // non-fatal
        }
        applySidebarWidth(true);
        updateSidebarLabels();
    }

    private void applySidebarWidth(boolean animate) {
        double w = collapsed ? SIDEBAR_COLLAPSED : SIDEBAR_EXPANDED;
        collapseBtn.setText(collapsed ? "»" : "«");
        if (animate) {
            UiAnimation.animatePrefWidth(sidebar, w);
        } else {
            sidebar.setPrefWidth(w);
        }
    }

    private void updateSidebarLabels() {
        for (ToggleButton btn : new ToggleButton[]{
                homeNav, downloadNav, onlineNav, newsNav, settingsNav, aboutNav}) {
            Object data = btn.getUserData();
            if (data instanceof String[] texts && texts.length == 2) {
                btn.setText(collapsed ? texts[1] : texts[0]);
                btn.setAlignment(collapsed ? Pos.CENTER : Pos.CENTER_LEFT);
            }
        }
    }

    private void updateFooter() {
        String online = "开发中";
        sidebarFooter.setText(collapsed
                ? "v" + AppInfo.VERSION
                : AppInfo.NAME + "\nv" + AppInfo.VERSION + "\n联机：" + online);
    }
}
