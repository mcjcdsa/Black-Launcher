package com.blacklauncher.mod;

import com.blacklauncher.mod.ModrinthClient.ProjectDetail;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModCompatTest {

    @Test
    void detectsLoaderFromSelectedVersion() {
        ModCompat.InstanceHint hint = ModCompat.fromSelectedVersion("fabric-loader-0.16-1.20.1");
        assertTrue(hint.loader().equals("fabric"));
        assertTrue(hint.mcVersion().contains("1.20"));
    }

    @Test
    void warnsOnMismatch() {
        ProjectDetail project = new ProjectDetail(
                "id", "slug", "Title", "desc", "body", 1, 1, "a",
                List.of(), List.of("forge"), List.of("1.19.2"),
                "", "", "", "", "", "", 0, 0, List.of());
        ModCompat.InstanceHint hint = new ModCompat.InstanceHint("fabric-1.20.1", "1.20.1", "fabric");
        String warn = ModCompat.projectMismatchWarning(project, hint);
        assertFalse(warn.isBlank());
    }
}
