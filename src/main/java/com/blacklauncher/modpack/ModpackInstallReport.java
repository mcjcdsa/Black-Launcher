package com.blacklauncher.modpack;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a modpack import attempt (§6).
 */
public final class ModpackInstallReport {

    private String versionId;
    private boolean success;
    private boolean partial;
    private String summary = "";
    private final List<String> installed = new ArrayList<>();
    private final List<String> missing = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public boolean isPartial() {
        return partial;
    }

    public void setPartial(boolean partial) {
        this.partial = partial;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary == null ? "" : summary;
    }

    public List<String> getInstalled() {
        return installed;
    }

    public List<String> getMissing() {
        return missing;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void addInstalled(String item) {
        if (item != null && !item.isBlank()) {
            installed.add(item);
        }
    }

    public void addMissing(String item) {
        if (item != null && !item.isBlank()) {
            missing.add(item);
        }
    }

    public void addWarning(String item) {
        if (item != null && !item.isBlank()) {
            warnings.add(item);
        }
    }
}
