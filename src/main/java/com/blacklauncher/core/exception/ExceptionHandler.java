package com.blacklauncher.core.exception;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized exception handling for UI and background threads.
 */
public final class ExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ExceptionHandler.class);

    private ExceptionHandler() {
    }

    public static void handleUncaught(Thread thread, Throwable throwable) {
        LOG.error("Uncaught exception on thread {}", thread.getName(), throwable);
        showError("未捕获异常", sanitize(throwable.getMessage()));
    }

    public static void handle(String context, Throwable throwable) {
        LOG.error(context, throwable);
        showError(context, sanitize(throwable.getMessage()));
    }

    public static void handleFatal(String context, Throwable throwable) {
        LOG.error("Fatal: {}", context, throwable);
        showError("致命错误", context + ": " + sanitize(throwable.getMessage()));
    }

    public static void showError(String title, String message) {
        Runnable action = () -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(title);
            alert.setContentText(message == null || message.isBlank() ? "发生未知错误" : message);
            alert.showAndWait();
        };
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    public static void showInfo(String title, String message) {
        Runnable action = () -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        };
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        return message
                .replaceAll("(?i)(password|token|secret|key)=\\S+", "$1=***")
                .replaceAll("[\\r\\n]+", " ")
                .trim();
    }
}
