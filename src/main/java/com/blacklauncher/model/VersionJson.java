package com.blacklauncher.model;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minecraft version JSON model (subset needed for download/launch).
 */
public class VersionJson {

    private String id;
    private String type;
    private String mainClass;
    private String inheritsFrom;
    private String minecraftArguments;
    private String assets;
    private AssetIndex assetIndex;
    private Downloads downloads;
    private List<Library> libraries = new ArrayList<>();
    /** Modern argument block; kept as JsonElement to avoid schema mismatch. */
    private JsonElement arguments;
    /** Official launcher Java requirement (e.g. majorVersion 17 / 21). */
    private JavaVersionSpec javaVersion;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMainClass() {
        return mainClass;
    }

    public void setMainClass(String mainClass) {
        this.mainClass = mainClass;
    }

    public String getInheritsFrom() {
        return inheritsFrom;
    }

    public void setInheritsFrom(String inheritsFrom) {
        this.inheritsFrom = inheritsFrom;
    }

    public String getMinecraftArguments() {
        return minecraftArguments;
    }

    public void setMinecraftArguments(String minecraftArguments) {
        this.minecraftArguments = minecraftArguments;
    }

    public String getAssets() {
        return assets;
    }

    public void setAssets(String assets) {
        this.assets = assets;
    }

    public AssetIndex getAssetIndex() {
        return assetIndex;
    }

    public void setAssetIndex(AssetIndex assetIndex) {
        this.assetIndex = assetIndex;
    }

    public Downloads getDownloads() {
        return downloads;
    }

    public void setDownloads(Downloads downloads) {
        this.downloads = downloads;
    }

    public List<Library> getLibraries() {
        return libraries;
    }

    public void setLibraries(List<Library> libraries) {
        this.libraries = libraries;
    }

    public JsonElement getArguments() {
        return arguments;
    }

    public void setArguments(JsonElement arguments) {
        this.arguments = arguments;
    }

    public JavaVersionSpec getJavaVersion() {
        return javaVersion;
    }

    public void setJavaVersion(JavaVersionSpec javaVersion) {
        this.javaVersion = javaVersion;
    }

    public static class JavaVersionSpec {
        private String component;
        private int majorVersion;

        public String getComponent() {
            return component;
        }

        public void setComponent(String component) {
            this.component = component;
        }

        public int getMajorVersion() {
            return majorVersion;
        }

        public void setMajorVersion(int majorVersion) {
            this.majorVersion = majorVersion;
        }
    }

    public static class AssetIndex {
        private String id;
        private String sha1;
        private long size;
        private long totalSize;
        private String url;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getSha1() {
            return sha1;
        }

        public void setSha1(String sha1) {
            this.sha1 = sha1;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }

        public long getTotalSize() {
            return totalSize;
        }

        public void setTotalSize(long totalSize) {
            this.totalSize = totalSize;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    public static class Downloads {
        private Artifact client;
        private Artifact server;

        public Artifact getClient() {
            return client;
        }

        public void setClient(Artifact client) {
            this.client = client;
        }

        public Artifact getServer() {
            return server;
        }

        public void setServer(Artifact server) {
            this.server = server;
        }
    }

    public static class Artifact {
        private String path;
        private String sha1;
        private long size;
        private String url;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getSha1() {
            return sha1;
        }

        public void setSha1(String sha1) {
            this.sha1 = sha1;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    public static class Library {
        private String name;
        private String url;
        private DownloadsInfo downloads;
        private List<Rule> rules;
        private Map<String, String> natives;
        private Extract extract;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public DownloadsInfo getDownloads() {
            return downloads;
        }

        public void setDownloads(DownloadsInfo downloads) {
            this.downloads = downloads;
        }

        public List<Rule> getRules() {
            return rules;
        }

        public void setRules(List<Rule> rules) {
            this.rules = rules;
        }

        public Map<String, String> getNatives() {
            return natives;
        }

        public void setNatives(Map<String, String> natives) {
            this.natives = natives;
        }

        public Extract getExtract() {
            return extract;
        }

        public void setExtract(Extract extract) {
            this.extract = extract;
        }
    }

    public static class DownloadsInfo {
        private Artifact artifact;
        private Map<String, Artifact> classifiers;

        public Artifact getArtifact() {
            return artifact;
        }

        public void setArtifact(Artifact artifact) {
            this.artifact = artifact;
        }

        public Map<String, Artifact> getClassifiers() {
            return classifiers;
        }

        public void setClassifiers(Map<String, Artifact> classifiers) {
            this.classifiers = classifiers;
        }
    }

    public static class Rule {
        private String action;
        private Os os;

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public Os getOs() {
            return os;
        }

        public void setOs(Os os) {
            this.os = os;
        }
    }

    public static class Os {
        private String name;
        private String arch;
        /** Regex matched against {@code os.version} (used heavily on macOS rules). */
        private String version;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getArch() {
            return arch;
        }

        public void setArch(String arch) {
            this.arch = arch;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }
    }

    public static class Extract {
        private List<String> exclude = new ArrayList<>();

        public List<String> getExclude() {
            return exclude;
        }

        public void setExclude(List<String> exclude) {
            this.exclude = exclude;
        }
    }

}
