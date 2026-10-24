package com.blacklauncher.modpack;

import java.nio.file.Path;

/**
 * User choices for exporting a local version as a modpack (§6.3.1).
 */
public final class ModpackExportOptions {

    public enum Format {
        MODRINTH,
        CURSEFORGE,
        OFFLINE_ZIP
    }

    private Path outputFile;
    private String packName = "modpack";
    private Format format = Format.OFFLINE_ZIP;
    private boolean includeMods = true;
    private boolean includeConfig = true;
    private boolean includeResourcepacks;
    private boolean includeSaves;
    private boolean includeServers = true;
    private boolean includeScreenshots;
    private boolean includeOptions;
    private boolean includeLaunchScript;
    private boolean includeJvmArgs;
    private boolean stripPrivate = true;
    /** Index + overrides only (no version jar / saves / screenshots). */
    private boolean lightUpdatePack;

    public Path getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(Path outputFile) {
        this.outputFile = outputFile;
    }

    public String getPackName() {
        return packName;
    }

    public void setPackName(String packName) {
        this.packName = packName;
    }

    public Format getFormat() {
        return format;
    }

    public void setFormat(Format format) {
        this.format = format == null ? Format.OFFLINE_ZIP : format;
    }

    public boolean isIncludeMods() {
        return includeMods;
    }

    public void setIncludeMods(boolean includeMods) {
        this.includeMods = includeMods;
    }

    public boolean isIncludeConfig() {
        return includeConfig;
    }

    public void setIncludeConfig(boolean includeConfig) {
        this.includeConfig = includeConfig;
    }

    public boolean isIncludeResourcepacks() {
        return includeResourcepacks;
    }

    public void setIncludeResourcepacks(boolean includeResourcepacks) {
        this.includeResourcepacks = includeResourcepacks;
    }

    public boolean isIncludeSaves() {
        return includeSaves;
    }

    public void setIncludeSaves(boolean includeSaves) {
        this.includeSaves = includeSaves;
    }

    public boolean isIncludeServers() {
        return includeServers;
    }

    public void setIncludeServers(boolean includeServers) {
        this.includeServers = includeServers;
    }

    public boolean isIncludeScreenshots() {
        return includeScreenshots;
    }

    public void setIncludeScreenshots(boolean includeScreenshots) {
        this.includeScreenshots = includeScreenshots;
    }

    public boolean isIncludeOptions() {
        return includeOptions;
    }

    public void setIncludeOptions(boolean includeOptions) {
        this.includeOptions = includeOptions;
    }

    public boolean isIncludeLaunchScript() {
        return includeLaunchScript;
    }

    public void setIncludeLaunchScript(boolean includeLaunchScript) {
        this.includeLaunchScript = includeLaunchScript;
    }

    public boolean isIncludeJvmArgs() {
        return includeJvmArgs;
    }

    /** Preset for §7 full version export. */
    public static ModpackExportOptions fullPack(String packName, Path output) {
        ModpackExportOptions o = new ModpackExportOptions();
        o.setPackName(packName);
        o.setOutputFile(output);
        o.setFormat(Format.OFFLINE_ZIP);
        o.setIncludeMods(true);
        o.setIncludeConfig(true);
        o.setIncludeResourcepacks(true);
        o.setIncludeSaves(true);
        o.setIncludeServers(true);
        o.setIncludeScreenshots(true);
        o.setIncludeOptions(true);
        o.setIncludeLaunchScript(true);
        o.setIncludeJvmArgs(true);
        o.setStripPrivate(false);
        return o;
    }

    public void setIncludeJvmArgs(boolean includeJvmArgs) {
        this.includeJvmArgs = includeJvmArgs;
    }

    public boolean isStripPrivate() {
        return stripPrivate;
    }

    public void setStripPrivate(boolean stripPrivate) {
        this.stripPrivate = stripPrivate;
    }

    public boolean isLightUpdatePack() {
        return lightUpdatePack;
    }

    public void setLightUpdatePack(boolean lightUpdatePack) {
        this.lightUpdatePack = lightUpdatePack;
    }

    /** Preset for light update packs: index + overrides (mods/config/resourcepacks). */
    public static ModpackExportOptions lightUpdatePack(String packName, Path output) {
        ModpackExportOptions o = new ModpackExportOptions();
        o.setPackName(packName);
        o.setOutputFile(output);
        o.setFormat(Format.MODRINTH);
        o.setIncludeMods(true);
        o.setIncludeConfig(true);
        o.setIncludeResourcepacks(true);
        o.setIncludeSaves(false);
        o.setIncludeScreenshots(false);
        o.setIncludeServers(false);
        o.setIncludeLaunchScript(false);
        o.setIncludeJvmArgs(false);
        o.setIncludeOptions(false);
        o.setLightUpdatePack(true);
        o.setStripPrivate(true);
        return o;
    }
}
