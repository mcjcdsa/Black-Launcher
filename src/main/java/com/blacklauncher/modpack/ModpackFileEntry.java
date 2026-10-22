package com.blacklauncher.modpack;

import java.util.ArrayList;
import java.util.List;

/**
 * A single file to install from a modpack (download URL and/or CurseForge ids).
 */
public final class ModpackFileEntry {

    private String path = "mods/unknown.jar";
    private String sha1;
    private String sha512;
    private long fileSize = -1;
    private final List<String> downloads = new ArrayList<>();
    private int curseProjectId;
    private int curseFileId;
    private boolean required = true;
    private boolean clientOptional;

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

    public String getSha512() {
        return sha512;
    }

    public void setSha512(String sha512) {
        this.sha512 = sha512;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public List<String> getDownloads() {
        return downloads;
    }

    public int getCurseProjectId() {
        return curseProjectId;
    }

    public void setCurseProjectId(int curseProjectId) {
        this.curseProjectId = curseProjectId;
    }

    public int getCurseFileId() {
        return curseFileId;
    }

    public void setCurseFileId(int curseFileId) {
        this.curseFileId = curseFileId;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public boolean isClientOptional() {
        return clientOptional;
    }

    public void setClientOptional(boolean clientOptional) {
        this.clientOptional = clientOptional;
    }
}
