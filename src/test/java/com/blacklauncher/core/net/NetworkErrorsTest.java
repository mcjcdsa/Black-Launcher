package com.blacklauncher.core.net;

import com.blacklauncher.core.exception.LauncherException;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkErrorsTest {

    @Test
    void networkFailuresAreRetryable() {
        assertTrue(NetworkErrors.isNetworkFailure(new UnknownHostException("dns")));
        assertTrue(NetworkErrors.isNetworkFailure(new SocketTimeoutException("timeout")));
        assertTrue(NetworkErrors.isNetworkFailure(new LauncherException("HTTP 503")));
        assertTrue(NetworkErrors.isNetworkFailure(new LauncherException("HTTP 429")));
        assertTrue(NetworkErrors.isNetworkFailure(
                new LauncherException("下载失败 HTTP 522: https://bmclapi2.bangbang93.com/assets/ab/cd")));
        assertTrue(NetworkErrorClassifier.isTransientHttp(522));
        assertTrue(NetworkErrorClassifier.isNetworkError(
                new LauncherException("下载失败 HTTP 522: https://example")));
        assertTrue(NetworkErrorClassifier.isStaleRangeHttp(416));
        assertTrue(NetworkErrors.isNetworkFailure(
                new LauncherException("下载失败 HTTP 416: https://resources.download.minecraft.net/ab/cd")));
    }

    @Test
    void businessFailuresAreNotRetryable() {
        assertTrue(NetworkErrors.isBusinessFailure(new LauncherException("HTTP 404")));
        assertTrue(NetworkErrors.isBusinessFailure(new LauncherException("SHA-1 校验失败")));
        assertFalse(NetworkErrors.isNetworkFailure(new LauncherException("HTTP 403")));
    }

    @Test
    void userPauseAndCancelAreDistinct() {
        assertEquals(NetworkErrorKind.USER_PAUSE, NetworkErrors.classify(new UserPausedException()));
        assertEquals(NetworkErrorKind.CANCELLED, NetworkErrors.classify(new DownloadCancelledException()));
    }
}
