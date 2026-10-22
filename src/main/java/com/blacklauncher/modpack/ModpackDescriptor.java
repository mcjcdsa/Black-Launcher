package com.blacklauncher.modpack;

import com.blacklauncher.download.ModLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Normalized modpack metadata after format detection / parsing.
 */
public final class ModpackDescriptor {

    private ModpackFormat format = ModpackFormat.UNKNOWN;
    private String name = "modpack";
    private String version = "1.0";
    private String mcVersion;
    private ModLoader loader = ModLoader.VANILLA;
    private String loaderVersion;
    private String overridesDir = "overrides";
    private Path sourceArchive;
    private Path sourceFolder;
    private final List<ModpackFileEntry> files = new ArrayList<>();
    private boolean hasEmbeddedMods;

    public ModpackFormat getFormat() {
        return format;
    }

    public void setFormat(ModpackFormat format) {
        this.format = format == null ? ModpackFormat.UNKNOWN : format;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getMcVersion() {
        return mcVersion;
    }

    public void setMcVersion(String mcVersion) {
        this.mcVersion = mcVersion;
    }

    public ModLoader getLoader() {
        return loader;
    }

    public void setLoader(ModLoader loader) {
        this.loader = loader == null ? ModLoader.VANILLA : loader;
    }

    public String getLoaderVersion() {
        return loaderVersion;
    }

    public void setLoaderVersion(String loaderVersion) {
        this.loaderVersion = loaderVersion;
    }

    public String getOverridesDir() {
        return overridesDir;
    }

    public void setOverridesDir(String overridesDir) {
        this.overridesDir = overridesDir;
    }

    public Path getSourceArchive() {
        return sourceArchive;
    }

    public void setSourceArchive(Path sourceArchive) {
        this.sourceArchive = sourceArchive;
    }

    public Path getSourceFolder() {
        return sourceFolder;
    }

    public void setSourceFolder(Path sourceFolder) {
        this.sourceFolder = sourceFolder;
    }

    public List<ModpackFileEntry> getFiles() {
        return files;
    }

    public boolean isHasEmbeddedMods() {
        return hasEmbeddedMods;
    }

    public void setHasEmbeddedMods(boolean hasEmbeddedMods) {
        this.hasEmbeddedMods = hasEmbeddedMods;
    }
}
