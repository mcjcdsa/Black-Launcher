package com.blacklauncher.core.net;

import com.blacklauncher.core.exception.LauncherException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Classifies failures into network (auto-retry) vs business (no auto-retry).
 */
public final class NetworkErrorClassifier {

    private NetworkErrorClassifier() {
    }

    public static boolean isNetworkError(Throwable error) {
        return NetworkErrors.classify(error) == NetworkErrorKind.NETWORK;
    }

    public static boolean isBusinessError(Throwable error) {
        return NetworkErrors.classify(error) == NetworkErrorKind.BUSINESS;
    }

    /**
     * Transient HTTP statuses: timeouts, rate-limit, gateway / Cloudflare origin failures.
     * These must auto-wait and resume — never treat as hard business errors.
     */
    public static boolean isTransientHttp(int code) {
        return code == 408 || code == 425 || code == 429
                || code == 500 || code == 502 || code == 503 || code == 504 || code == 509
                || code == 520 || code == 521 || code == 522 || code == 523 || code == 524
                || code == 525 || code == 526 || code == 527 || code == 530;
    }

    /**
     * Recoverable by clearing local .part and restarting (not a permanent business failure).
     */
    public static boolean isStaleRangeHttp(int code) {
        return code == 416;
    }

    public static boolean isBusinessHttp(int code) {
        return code == 400 || code == 401 || code == 403 || code == 404
                || code == 405 || code == 410 || code == 451;
    }

    public static Integer extractHttpCode(String message) {
        if (message == null) {
            return null;
        }
        String upper = message.toUpperCase(Locale.ROOT);
        int idx = upper.indexOf("HTTP ");
        if (idx < 0) {
            // also accept "HTTP522" without space
            idx = upper.indexOf("HTTP");
            if (idx < 0) {
                return null;
            }
            int start = idx + 4;
            while (start < message.length() && !Character.isDigit(message.charAt(start))) {
                start++;
            }
            int end = start;
            while (end < message.length() && Character.isDigit(message.charAt(end))) {
                end++;
            }
            if (end == start) {
                return null;
            }
            try {
                return Integer.parseInt(message.substring(start, end));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        int start = idx + 5;
        int end = start;
        while (end < message.length() && Character.isDigit(message.charAt(end))) {
            end++;
        }
        if (end == start) {
            return null;
        }
        try {
            return Integer.parseInt(message.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean looksLikeIoNetwork(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            if (cur instanceof UnknownHostException
                    || cur instanceof ConnectException
                    || cur instanceof NoRouteToHostException
                    || cur instanceof SocketTimeoutException
                    || cur instanceof SocketException
                    || cur instanceof TimeoutException
                    || cur instanceof java.io.InterruptedIOException) {
                return true;
            }
            if (cur instanceof IOException) {
                String msg = safeMsg(cur).toLowerCase(Locale.ROOT);
                if (containsAny(msg,
                        "connection reset", "broken pipe", "network is unreachable",
                        "connection refused", "timed out", "timeout", "temporarily unavailable",
                        "software caused connection abort", "connection closed",
                        "unexpected end of stream", "stream was reset")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    static boolean containsAny(String msg, String... keys) {
        if (msg == null || msg.isEmpty()) {
            return false;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        for (String key : keys) {
            if (lower.contains(key.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static String safeMsg(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
