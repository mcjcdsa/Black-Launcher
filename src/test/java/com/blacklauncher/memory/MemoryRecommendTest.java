package com.blacklauncher.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRecommendTest {

    @Test
    void recommendsFor8to16G() {
        long total = 16L * 1024 * 1024 * 1024;
        MemoryRecommend.RecommendResult r = MemoryRecommend.recommend(total, false);
        assertEquals(3072, r.xmxMb());
        assertTrue(r.xmsMb() >= 512 && r.xmsMb() <= r.xmxMb());
        assertTrue(MemoryRecommend.exceeds70Percent(12000, total));
        assertTrue(!MemoryRecommend.exceeds70Percent(4096, total));
    }

    @Test
    void lowMemXmsFixed() {
        assertEquals(512, MemoryRecommend.suggestXms(6144, true));
        assertEquals(3072, MemoryRecommend.suggestXms(6144, false));
    }
}
