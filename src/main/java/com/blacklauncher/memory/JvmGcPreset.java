package com.blacklauncher.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Built-in GC optimization presets (PRD §3.2).
 */
public enum JvmGcPreset {

    BALANCED_G1("balanced_g1", "平衡 G1GC（默认）"),
    ZGC("zgc", "ZGC 低延迟"),
    LOW_MEM("low_mem", "低配省内存"),
    CUSTOM("custom", "自定义");

    private final String id;
    private final String displayNameZh;

    JvmGcPreset(String id, String displayNameZh) {
        this.id = id;
        this.displayNameZh = displayNameZh;
    }

    public String id() {
        return id;
    }

    public String displayNameZh() {
        return displayNameZh;
    }

    public static JvmGcPreset fromId(String schemeId) {
        if (schemeId == null || schemeId.isBlank()) {
            return BALANCED_G1;
        }
        String key = schemeId.trim().toLowerCase(Locale.ROOT);
        for (JvmGcPreset p : values()) {
            if (p.id.equals(key)) {
                return p;
            }
        }
        return BALANCED_G1;
    }

    public boolean isAvailable(int javaMajor) {
        if (this == ZGC) {
            return javaMajor >= 17;
        }
        return true;
    }

    public List<String> jvmFlags(int javaMajor) {
        List<String> flags = new ArrayList<>();
        switch (this) {
            case BALANCED_G1 -> {
                flags.add("-XX:+UseG1GC");
                flags.add("-XX:+ParallelRefProcEnabled");
                flags.add("-XX:MaxGCPauseMillis=200");
            }
            case ZGC -> {
                if (javaMajor >= 17) {
                    flags.add("-XX:+UseZGC");
                    // Product in 17+; UnlockExperimental kept for older transitional JDKs.
                    flags.add("-XX:+UnlockExperimentalVMOptions");
                }
            }
            case LOW_MEM -> {
                flags.add("-XX:+UseG1GC");
                flags.add("-XX:G1HeapRegionSize=16M");
                flags.add("-XX:+ParallelRefProcEnabled");
            }
            case CUSTOM -> {
                // no built-in GC flags
            }
        }
        return flags;
    }
}
