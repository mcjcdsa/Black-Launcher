package com.blacklauncher.core.net;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadProgressSinkTest {

    @Test
    void formatsPercentAndSpeed() {
        assertEquals("50.0%", DownloadProgressSink.formatPercent(0.5));
        assertTrue(DownloadProgressSink.formatSpeed(1536).contains("KB/s"));
    }

    @Test
    void publishesStatus() {
        AtomicReference<DownloadProgressSink.Snapshot> last = new AtomicReference<>();
        DownloadProgressSink sink = new DownloadProgressSink(last::set);
        sink.status("测试", 0.25);
        assertEquals("测试", last.get().message());
        assertEquals("25.0%", last.get().percentText());
    }
}
