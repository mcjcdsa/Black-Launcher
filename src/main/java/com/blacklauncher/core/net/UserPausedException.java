package com.blacklauncher.core.net;

import com.blacklauncher.core.exception.LauncherException;

/**
 * Thrown when the user manually pauses a download (must not auto-resume).
 */
public class UserPausedException extends LauncherException {

    public UserPausedException() {
        super("下载已暂停");
    }
}
