package com.blacklauncher;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.config.ConfigManager;
import com.blacklauncher.core.db.Database;
import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.ui.MainShell;
import com.blacklauncher.ui.dialogs.FirstRunWizardDialog;
import javafx.application.Application;
import javafx.application.HostServices;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaFX application bootstrap.
 */
public class BlackLauncherApp extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(BlackLauncherApp.class);
    private static volatile HostServices hostServices;

    private ConfigManager configManager;
    private Database database;

    /** For opening external URLs from UI pages (§9). */
    public static HostServices hostServices() {
        return hostServices;
    }

    @Override
    public void init() {
        try {
            configManager = new ConfigManager();
            configManager.load();
            AppPaths.ensureDirectories();
            database = new Database();
            database.open();
            database.migrate();
        } catch (Exception e) {
            ExceptionHandler.handleFatal("初始化失败", e);
        }
    }

    @Override
    public void start(Stage stage) {
        hostServices = getHostServices();
        AppConfig config = configManager.getConfig();
        MainShell shell = new MainShell(configManager, database);
        Scene scene = new Scene(shell, 1180, 740);
        applyTheme(scene, config.getTheme());

        stage.setTitle("布莱克 Black Launcher");
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.show();
        if (!config.isFirstRunWizardDone()) {
            FirstRunWizardDialog.show(stage, configManager);
            applyTheme(scene, configManager.getConfig().getTheme());
        }
        LOG.info("Black Launcher started, version {}", AppInfo.VERSION);
    }

    @Override
    public void stop() {
        try {
            if (configManager != null) {
                configManager.save();
            }
            if (database != null) {
                database.close();
            }
        } catch (Exception e) {
            LOG.warn("Shutdown cleanup failed", e);
        }
    }

    public static void applyTheme(Scene scene, String theme) {
        scene.getStylesheets().clear();
        String stylesheet = "dark".equalsIgnoreCase(theme)
                ? "/styles/dark.css"
                : "/styles/light.css";
        var url = BlackLauncherApp.class.getResource(stylesheet);
        if (url != null) {
            scene.getStylesheets().add(url.toExternalForm());
        }
    }
}
