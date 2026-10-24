package com.blacklauncher.ui.pages;

import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.launch.GameLauncher;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Online multiplayer hub.
 * <p>
 * Full LAN implementation lives under {@code src/private/java} (not open-sourced).
 * When that source set is present locally, {@code LanPage} is loaded via reflection;
 * otherwise a placeholder is shown so the public tree still compiles.
 */
public class OnlinePage extends BorderPane {

    private static final Logger LOG = LoggerFactory.getLogger(OnlinePage.class);
    private static final String LAN_PAGE_CLASS = "com.blacklauncher.ui.pages.LanPage";

    private final Object lanPage;
    private final Method onActivatedMethod;

    public OnlinePage(ConfigManager configManager, Database database, GameLauncher gameLauncher,
                      Consumer<String> statusSink) {
        getStyleClass().add("page");

        Object loaded = null;
        Method activated = null;
        Node centerContent;
        try {
            Class<?> clazz = Class.forName(LAN_PAGE_CLASS);
            Constructor<?> ctor = clazz.getConstructor(
                    ConfigManager.class, Database.class, GameLauncher.class, Consumer.class);
            loaded = ctor.newInstance(configManager, database, gameLauncher, statusSink);
            activated = clazz.getMethod("onActivated");
            centerContent = (Node) loaded;
        } catch (ClassNotFoundException e) {
            LOG.info("LAN module not present (open-source build); showing placeholder");
            centerContent = placeholder();
        } catch (ReflectiveOperationException e) {
            LOG.warn("Failed to load LAN module", e);
            centerContent = placeholder();
        }

        this.lanPage = loaded;
        this.onActivatedMethod = activated;

        Tab lanTab = new Tab("局域网联机", centerContent);
        lanTab.setClosable(false);
        TabPane tabs = new TabPane(lanTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        setCenter(tabs);

        sceneProperty().addListener((o, oldScene, newScene) -> {
            if (newScene != null) {
                onActivated();
            }
        });
        visibleProperty().addListener((o, was, now) -> {
            if (Boolean.TRUE.equals(now) && getScene() != null) {
                onActivated();
            }
        });
    }

    public void onActivated() {
        if (lanPage == null || onActivatedMethod == null) {
            return;
        }
        try {
            onActivatedMethod.invoke(lanPage);
        } catch (ReflectiveOperationException e) {
            LOG.debug("LanPage.onActivated failed: {}", e.toString());
        }
    }

    /** @return LanPage instance when private module is present; otherwise null */
    public Object lanPage() {
        return lanPage;
    }

    public boolean isLanModulePresent() {
        return lanPage != null;
    }

    private static Node placeholder() {
        Label title = new Label("联机模块未包含在开源版本中");
        title.getStyleClass().add("page-title");
        Label body = new Label(
                "局域网联机（房间 / 本地服务器 / 发现与加入）为闭源能力，"
                        + "仅随完整发行版或本地 private 源码集提供。\n"
                        + "开源仓库可正常编译运行其余功能。");
        body.setWrapText(true);
        body.getStyleClass().add("muted");
        VBox box = new VBox(12, title, body);
        box.setPadding(new Insets(24));
        return box;
    }
}
