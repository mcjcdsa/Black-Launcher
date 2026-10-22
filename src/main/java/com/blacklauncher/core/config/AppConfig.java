package com.blacklauncher.core.config;

/**
 * Persistent launcher configuration.
 */
public class AppConfig {

    private String language = "zh_CN";
    private String theme = "dark";
    /** Global UI transition switch (sidebar / page fade / progress easing). */
    private boolean enableAnimation = true;
    /** Sidebar collapsed to icon-only width. */
    private boolean sidebarCollapsed;
    private String logLevel = "INFO";
    /** Launcher data root (config/db/logs/…). Empty = default ~/.black-launcher or redirect. */
    private String dataDirectory = "";
    /** Minecraft game root (versions/libraries/assets/instances). Empty = {dataDirectory}/minecraft. */
    private String gameDirectory = "";
    private String javaPath = "";
    private int minMemoryMb = 512;
    private int maxMemoryMb = 2048;
    private String jvmArgs = "";
    private int gameWidth = 854;
    private int gameHeight = 480;
    private boolean fullscreen;
    private String proxyHost = "";
    private int proxyPort;
    private boolean isolateGameDirs = true;
    private String playerName = "Player";
    private String playerUuid = "";
    private String selectedVersion = "";
    private boolean onlineModeEnabled;
    private String selectedSkinPath = "";
    /** bmclapi | official */
    private String downloadSource = "bmclapi";
    /** Concurrent file downloads for libraries/assets. */
    private int downloadThreads = 32;
    /** When downloading/installing a version, write options.txt language. */
    private boolean autoConfigureGameLanguage = true;
    /** Minecraft options.txt language code preference (stored as zh_CN, written as zh_cn). */
    private String gameLanguage = "zh_CN";

    /** offline | msa */
    private String accountType = "offline";
    /** Azure app client_id (Mojang-approved) for Microsoft device-code login. */
    private String microsoftClientId = "";
    private String microsoftRefreshToken = "";
    private String minecraftAccessToken = "";
    private String xboxUserHash = "";
    private long tokenExpiresAt;

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public boolean isEnableAnimation() {
        return enableAnimation;
    }

    public void setEnableAnimation(boolean enableAnimation) {
        this.enableAnimation = enableAnimation;
    }

    public boolean isSidebarCollapsed() {
        return sidebarCollapsed;
    }

    public void setSidebarCollapsed(boolean sidebarCollapsed) {
        this.sidebarCollapsed = sidebarCollapsed;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(String logLevel) {
        this.logLevel = logLevel;
    }

    public String getDataDirectory() {
        return dataDirectory;
    }

    public void setDataDirectory(String dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public String getGameDirectory() {
        return gameDirectory;
    }

    public void setGameDirectory(String gameDirectory) {
        this.gameDirectory = gameDirectory;
    }

    public String getJavaPath() {
        return javaPath;
    }

    public void setJavaPath(String javaPath) {
        this.javaPath = javaPath;
    }

    public int getMinMemoryMb() {
        return minMemoryMb;
    }

    public void setMinMemoryMb(int minMemoryMb) {
        this.minMemoryMb = minMemoryMb;
    }

    public int getMaxMemoryMb() {
        return maxMemoryMb;
    }

    public void setMaxMemoryMb(int maxMemoryMb) {
        this.maxMemoryMb = maxMemoryMb;
    }

    public String getJvmArgs() {
        return jvmArgs;
    }

    public void setJvmArgs(String jvmArgs) {
        this.jvmArgs = jvmArgs;
    }

    public int getGameWidth() {
        return gameWidth;
    }

    public void setGameWidth(int gameWidth) {
        this.gameWidth = gameWidth;
    }

    public int getGameHeight() {
        return gameHeight;
    }

    public void setGameHeight(int gameHeight) {
        this.gameHeight = gameHeight;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
    }

    public boolean isIsolateGameDirs() {
        return isolateGameDirs;
    }

    public void setIsolateGameDirs(boolean isolateGameDirs) {
        this.isolateGameDirs = isolateGameDirs;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public String getPlayerUuid() {
        return playerUuid;
    }

    public void setPlayerUuid(String playerUuid) {
        this.playerUuid = playerUuid;
    }

    public String getSelectedVersion() {
        return selectedVersion;
    }

    public void setSelectedVersion(String selectedVersion) {
        this.selectedVersion = selectedVersion;
    }

    public boolean isOnlineModeEnabled() {
        return onlineModeEnabled;
    }

    public void setOnlineModeEnabled(boolean onlineModeEnabled) {
        this.onlineModeEnabled = onlineModeEnabled;
    }

    public String getSelectedSkinPath() {
        return selectedSkinPath;
    }

    public void setSelectedSkinPath(String selectedSkinPath) {
        this.selectedSkinPath = selectedSkinPath;
    }

    public String getDownloadSource() {
        return downloadSource;
    }

    public void setDownloadSource(String downloadSource) {
        this.downloadSource = downloadSource;
    }

    public int getDownloadThreads() {
        return downloadThreads;
    }

    public void setDownloadThreads(int downloadThreads) {
        this.downloadThreads = downloadThreads;
    }

    public boolean isAutoConfigureGameLanguage() {
        return autoConfigureGameLanguage;
    }

    public void setAutoConfigureGameLanguage(boolean autoConfigureGameLanguage) {
        this.autoConfigureGameLanguage = autoConfigureGameLanguage;
    }

    public String getGameLanguage() {
        return gameLanguage;
    }

    public void setGameLanguage(String gameLanguage) {
        this.gameLanguage = gameLanguage;
    }

    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }

    public String getMicrosoftClientId() {
        return microsoftClientId;
    }

    public void setMicrosoftClientId(String microsoftClientId) {
        this.microsoftClientId = microsoftClientId;
    }

    public String getMicrosoftRefreshToken() {
        return microsoftRefreshToken;
    }

    public void setMicrosoftRefreshToken(String microsoftRefreshToken) {
        this.microsoftRefreshToken = microsoftRefreshToken;
    }

    public String getMinecraftAccessToken() {
        return minecraftAccessToken;
    }

    public void setMinecraftAccessToken(String minecraftAccessToken) {
        this.minecraftAccessToken = minecraftAccessToken;
    }

    public String getXboxUserHash() {
        return xboxUserHash;
    }

    public void setXboxUserHash(String xboxUserHash) {
        this.xboxUserHash = xboxUserHash;
    }

    public long getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public void setTokenExpiresAt(long tokenExpiresAt) {
        this.tokenExpiresAt = tokenExpiresAt;
    }
}
