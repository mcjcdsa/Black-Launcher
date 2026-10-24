package com.blacklauncher.ui.about;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McSiteCatalogTest {

    @Test
    void groupsOrderAndCount() {
        var groups = McSiteCatalog.groups();
        assertEquals(10, groups.size());
        assertTrue(groups.get(0).title().contains("官方网站"));
        assertTrue(groups.get(8).title().contains("基岩版专属"));
        assertTrue(groups.get(9).title().contains("导航站"));
        assertEquals(4, groups.get(0).sites().size());
        assertEquals("https://www.minecraft.net/zh-hans", groups.get(0).sites().get(0).url());
        assertEquals("https://mcisee.top", groups.get(9).sites().get(1).url());
        int total = groups.stream().mapToInt(g -> g.sites().size()).sum();
        assertEquals(42, total);
    }
}
