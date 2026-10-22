package com.blacklauncher.launch;

import com.blacklauncher.model.VersionJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaVersionProbeTest {

    @Test
    void requiredMajorFromJson() {
        VersionJson v = new VersionJson();
        assertEquals(0, JavaVersionProbe.requiredMajor(v));
        VersionJson.JavaVersionSpec spec = new VersionJson.JavaVersionSpec();
        spec.setMajorVersion(21);
        v.setJavaVersion(spec);
        assertEquals(21, JavaVersionProbe.requiredMajor(v));
    }
}
