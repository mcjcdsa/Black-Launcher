package com.blacklauncher.moddev;

/**
 * Persistent preferences for the Fabric mod-dev tools (template generator + docs).
 */
public class ModDevConfig {

    private String packagePrefix = "com.example";
    /** Blank = {@link com.blacklauncher.core.util.AppPaths#modDevDir()}. */
    private String workDirectory = "";
    private String lastMinecraftVersion = "";
    private boolean useKotlin;
    private boolean dataGeneration;
    private boolean splitSources;
    private boolean kotlinDsl;
    private long docsLastSyncEpochMs;
    private String docsCommitSha = "";

    public String getPackagePrefix() {
        return packagePrefix;
    }

    public void setPackagePrefix(String packagePrefix) {
        this.packagePrefix = packagePrefix;
    }

    public String getWorkDirectory() {
        return workDirectory;
    }

    public void setWorkDirectory(String workDirectory) {
        this.workDirectory = workDirectory;
    }

    public String getLastMinecraftVersion() {
        return lastMinecraftVersion;
    }

    public void setLastMinecraftVersion(String lastMinecraftVersion) {
        this.lastMinecraftVersion = lastMinecraftVersion;
    }

    public boolean isUseKotlin() {
        return useKotlin;
    }

    public void setUseKotlin(boolean useKotlin) {
        this.useKotlin = useKotlin;
    }

    public boolean isDataGeneration() {
        return dataGeneration;
    }

    public void setDataGeneration(boolean dataGeneration) {
        this.dataGeneration = dataGeneration;
    }

    public boolean isSplitSources() {
        return splitSources;
    }

    public void setSplitSources(boolean splitSources) {
        this.splitSources = splitSources;
    }

    public boolean isKotlinDsl() {
        return kotlinDsl;
    }

    public void setKotlinDsl(boolean kotlinDsl) {
        this.kotlinDsl = kotlinDsl;
    }

    public long getDocsLastSyncEpochMs() {
        return docsLastSyncEpochMs;
    }

    public void setDocsLastSyncEpochMs(long docsLastSyncEpochMs) {
        this.docsLastSyncEpochMs = docsLastSyncEpochMs;
    }

    public String getDocsCommitSha() {
        return docsCommitSha;
    }

    public void setDocsCommitSha(String docsCommitSha) {
        this.docsCommitSha = docsCommitSha;
    }
}
