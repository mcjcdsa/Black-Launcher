package com.blacklauncher.core.net;

import com.blacklauncher.core.exception.LauncherException;

import java.io.IOException;

/**
 * Classifies failures so only network link errors auto-retry / resume.
 */
public enum NetworkErrorKind {
    /** Disconnect, DNS, timeout, rate-limit, temporary unreachable — may auto-resume. */
    NETWORK,
    /** Checksum, 401/403/404, permission — do not auto-resume. */
    BUSINESS,
    /** User paused the task — do not auto-resume. */
    USER_PAUSE,
    /** Cancelled by user / launcher. */
    CANCELLED
}

final class NetworkErrors {

    private NetworkErrors() {
    }

    static boolean isNetworkFailure(Throwable error) {
        return classify(error) == NetworkErrorKind.NETWORK;
    }

    static boolean isBusinessFailure(Throwable error) {
        return classify(error) == NetworkErrorKind.BUSINESS;
    }

    static NetworkErrorKind classify(Throwable error) {
        if (error == null) {
            return NetworkErrorKind.BUSINESS;
        }
        if (error instanceof UserPausedException) {
            return NetworkErrorKind.USER_PAUSE;
        }
        if (error instanceof DownloadCancelledException) {
            return NetworkErrorKind.CANCELLED;
        }

        if (NetworkErrorClassifier.looksLikeIoNetwork(error)) {
            return NetworkErrorKind.NETWORK;
        }

        Throwable cur = error;
        while (cur != null) {
            if (cur instanceof LauncherException) {
                Integer code = NetworkErrorClassifier.extractHttpCode(cur.getMessage());
                if (code != null && (NetworkErrorClassifier.isTransientHttp(code)
                        || NetworkErrorClassifier.isStaleRangeHttp(code))) {
                    // 416: stale Range/.part — retry after clear (treated as recoverable).
                    return NetworkErrorKind.NETWORK;
                }
                if (code != null && NetworkErrorClassifier.isBusinessHttp(code)) {
                    return NetworkErrorKind.BUSINESS;
                }
                String msg = NetworkErrorClassifier.safeMsg(cur).toLowerCase();
                if (NetworkErrorClassifier.containsAny(msg,
                        "sha-1", "校验", "大小不匹配", "磁盘空间不足")) {
                    return NetworkErrorKind.BUSINESS;
                }
                // Parallel probe races / AV locks look like "无权限" but are retryable.
                if (NetworkErrorClassifier.containsAny(msg, "不可写", "无权限", "无写权限")
                        && hasAccessDenied(error)) {
                    return NetworkErrorKind.NETWORK;
                }
                if (NetworkErrorClassifier.containsAny(msg, "不可写", "无权限", "无写权限")) {
                    return NetworkErrorKind.BUSINESS;
                }
            }
            cur = cur.getCause();
        }

        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        if (root instanceof java.nio.file.AccessDeniedException) {
            return NetworkErrorKind.NETWORK;
        }
        if (root instanceof IOException) {
            return NetworkErrorKind.NETWORK;
        }
        return NetworkErrorKind.BUSINESS;
    }

    private static boolean hasAccessDenied(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            if (cur instanceof java.nio.file.AccessDeniedException) {
                return true;
            }
            String msg = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase();
            if (msg.contains("access is denied") || msg.contains("另一个程序正在使用")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
