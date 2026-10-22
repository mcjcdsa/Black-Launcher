package com.blacklauncher.modpack;

/** How to handle an existing version directory with the same name. */
public enum ModpackConflictPolicy {
    OVERWRITE,
    COPY_SUFFIX,
    CANCEL
}
