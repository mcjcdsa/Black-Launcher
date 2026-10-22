package com.blacklauncher.core.net;

import com.blacklauncher.core.exception.LauncherException;

/**
 * Thrown when a download is cancelled.
 */
public class DownloadCancelledException extends LauncherException {

    public DownloadCancelledException() {
        super("下载已取消");
    }
}
