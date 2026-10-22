package com.blacklauncher.minecraft;

/**
 * Semantic categories of paths under a Minecraft game / instance root
 * (see project doc {@code 3}).
 */
public enum DirectoryKind {
    /** System-maintained; tools must treat as read-only. */
    ASSETS(true, false, "原版静态资源缓存"),
    LIBRARIES(true, false, "运行依赖库"),
    VERSIONS(true, false, "版本本体"),
    NATIVES(true, false, "原生库"),

    /** User content; isolation-aware. */
    MODS(false, true, "模组"),
    SAVES(false, true, "单人存档"),
    RESOURCEPACKS(false, true, "资源包"),
    SHADERPACKS(false, true, "光影包"),
    CONFIG(false, true, "模组配置"),
    SCREENSHOTS(false, true, "截图"),

    /** Diagnostics; readable / cleanable. */
    LOGS(false, true, "运行日志"),
    CRASH_REPORTS(false, true, "崩溃报告"),

    /** Root config files. */
    OPTIONS_TXT(false, true, "游戏设置 options.txt"),
    SERVERS_DAT(false, true, "服务器列表"),
    FABRIC_EXT(false, true, "Fabric 扩展目录"),
    FORGE_EXT(false, true, "Forge/NeoForge 扩展目录");

    private final boolean systemManaged;
    private final boolean userWritable;
    private final String description;

    DirectoryKind(boolean systemManaged, boolean userWritable, String description) {
        this.systemManaged = systemManaged;
        this.userWritable = userWritable;
        this.description = description;
    }

    public boolean systemManaged() {
        return systemManaged;
    }

    /** Whether tools may create/update/delete under normal policies. */
    public boolean userWritable() {
        return userWritable;
    }

    public String description() {
        return description;
    }

    /** True when this folder moves under the instance root when isolation is on. */
    public boolean isolationScoped() {
        return this == MODS || this == SAVES || this == RESOURCEPACKS
                || this == SHADERPACKS || this == CONFIG || this == SCREENSHOTS
                || this == OPTIONS_TXT || this == FABRIC_EXT || this == FORGE_EXT
                || this == LOGS || this == CRASH_REPORTS || this == SERVERS_DAT;
    }
}
