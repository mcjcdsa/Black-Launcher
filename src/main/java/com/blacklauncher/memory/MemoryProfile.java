package com.blacklauncher.memory;

/**
 * Per-version memory / JVM / system-optimize settings ({@code memory.json}).
 */
public class MemoryProfile {

    /** balanced_g1 | zgc | low_mem | custom */
    private String schemeId = "balanced_g1";
    private int xmsMb = 512;
    private int xmxMb = 2048;
    private String customJvmArgs = "";
    /** Optional; default off per PRD. */
    private boolean trimBeforeLaunch;
    /** Default on. */
    private boolean boostProcessPriority = true;
    private boolean trimAfterExit;
    /** User marks instance as modpack for recommend. */
    private boolean modpackHint;

    public String getSchemeId() {
        return schemeId;
    }

    public void setSchemeId(String schemeId) {
        this.schemeId = schemeId;
    }

    public int getXmsMb() {
        return xmsMb;
    }

    public void setXmsMb(int xmsMb) {
        this.xmsMb = xmsMb;
    }

    public int getXmxMb() {
        return xmxMb;
    }

    public void setXmxMb(int xmxMb) {
        this.xmxMb = xmxMb;
    }

    public String getCustomJvmArgs() {
        return customJvmArgs;
    }

    public void setCustomJvmArgs(String customJvmArgs) {
        this.customJvmArgs = customJvmArgs;
    }

    public boolean isTrimBeforeLaunch() {
        return trimBeforeLaunch;
    }

    public void setTrimBeforeLaunch(boolean trimBeforeLaunch) {
        this.trimBeforeLaunch = trimBeforeLaunch;
    }

    public boolean isBoostProcessPriority() {
        return boostProcessPriority;
    }

    public void setBoostProcessPriority(boolean boostProcessPriority) {
        this.boostProcessPriority = boostProcessPriority;
    }

    public boolean isTrimAfterExit() {
        return trimAfterExit;
    }

    public void setTrimAfterExit(boolean trimAfterExit) {
        this.trimAfterExit = trimAfterExit;
    }

    public boolean isModpackHint() {
        return modpackHint;
    }

    public void setModpackHint(boolean modpackHint) {
        this.modpackHint = modpackHint;
    }
}
