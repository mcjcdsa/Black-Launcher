package com.blacklauncher;

import com.blacklauncher.core.exception.ExceptionHandler;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.ProcessCharsets;
import javafx.application.Application;

/**
 * Entry point for Black Launcher.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        // Before Logback binds: console encoder uses this so Chinese is not garbled on Windows.
        if (System.getProperty("blacklauncher.console.charset") == null) {
            System.setProperty("blacklauncher.console.charset", ProcessCharsets.encodingName());
        }
        Thread.setDefaultUncaughtExceptionHandler(ExceptionHandler::handleUncaught);
        AppPaths.bootstrap();
        AppPaths.ensureDirectories();
        Application.launch(BlackLauncherApp.class, args);
    }
}
