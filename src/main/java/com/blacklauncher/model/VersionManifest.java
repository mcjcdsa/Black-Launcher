package com.blacklauncher.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Mojang version_manifest.json model.
 */
public class VersionManifest {

    private Latest latest;
    private List<VersionEntry> versions = new ArrayList<>();

    public Latest getLatest() {
        return latest;
    }

    public void setLatest(Latest latest) {
        this.latest = latest;
    }

    public List<VersionEntry> getVersions() {
        return versions;
    }

    public void setVersions(List<VersionEntry> versions) {
        this.versions = versions;
    }

    public static class Latest {
        private String release;
        private String snapshot;

        public String getRelease() {
            return release;
        }

        public void setRelease(String release) {
            this.release = release;
        }

        public String getSnapshot() {
            return snapshot;
        }

        public void setSnapshot(String snapshot) {
            this.snapshot = snapshot;
        }
    }

    public static class VersionEntry {
        private String id;
        private String type;
        private String url;
        private String time;
        private String releaseTime;

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

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getTime() {
            return time;
        }

        public void setTime(String time) {
            this.time = time;
        }

        public String getReleaseTime() {
            return releaseTime;
        }

        public void setReleaseTime(String releaseTime) {
            this.releaseTime = releaseTime;
        }
    }
}
