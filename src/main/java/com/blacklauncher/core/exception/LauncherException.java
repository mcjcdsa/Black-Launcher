package com.blacklauncher.core.exception;

/**
 * Checked domain exception for recoverable launcher failures.
 */
public class LauncherException extends Exception {

    public LauncherException(String message) {
        super(message);
    }

    public LauncherException(String message, Throwable cause) {
        super(message, cause);
    }
}
