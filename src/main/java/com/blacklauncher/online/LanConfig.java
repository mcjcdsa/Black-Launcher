package com.blacklauncher.online;

/**
 * LAN multiplayer settings persisted under {@code config.json} → {@code lan}.
 */
public class LanConfig {

    private int broadcastUdpPort = 25566;
    private int broadcastIntervalMs = 1500;
    private int roomTimeoutMs = 5000;
    private int defaultMaxPlayer = 8;
    private boolean autoLoadLanHelper = true;
    private String passwordHashAlgorithm = "SHA256";
    private int defaultServerPort = 25565;
    /** Optional Liveworld cloud assist base URL (empty = disabled). */
    private String cloudBaseUrl = "";
    /** Optional Modrinth project id for the LANHelper Fabric mod. */
    private String lanHelperModrinthId = "";
    private boolean firewallHintShown;
    /** Localhost TCP port for launcher ↔ LANHelper bridge. */
    private int helperBridgePort = 25567;

    public int getBroadcastUdpPort() {
        return broadcastUdpPort;
    }

    public void setBroadcastUdpPort(int broadcastUdpPort) {
        this.broadcastUdpPort = broadcastUdpPort;
    }

    public int getBroadcastIntervalMs() {
        return broadcastIntervalMs;
    }

    public void setBroadcastIntervalMs(int broadcastIntervalMs) {
        this.broadcastIntervalMs = broadcastIntervalMs;
    }

    public int getRoomTimeoutMs() {
        return roomTimeoutMs;
    }

    public void setRoomTimeoutMs(int roomTimeoutMs) {
        this.roomTimeoutMs = roomTimeoutMs;
    }

    public int getDefaultMaxPlayer() {
        return defaultMaxPlayer;
    }

    public void setDefaultMaxPlayer(int defaultMaxPlayer) {
        this.defaultMaxPlayer = defaultMaxPlayer;
    }

    public boolean isAutoLoadLanHelper() {
        return autoLoadLanHelper;
    }

    public void setAutoLoadLanHelper(boolean autoLoadLanHelper) {
        this.autoLoadLanHelper = autoLoadLanHelper;
    }

    public String getPasswordHashAlgorithm() {
        return passwordHashAlgorithm;
    }

    public void setPasswordHashAlgorithm(String passwordHashAlgorithm) {
        this.passwordHashAlgorithm = passwordHashAlgorithm;
    }

    public int getDefaultServerPort() {
        return defaultServerPort;
    }

    public void setDefaultServerPort(int defaultServerPort) {
        this.defaultServerPort = defaultServerPort;
    }

    public String getCloudBaseUrl() {
        return cloudBaseUrl;
    }

    public void setCloudBaseUrl(String cloudBaseUrl) {
        this.cloudBaseUrl = cloudBaseUrl;
    }

    public String getLanHelperModrinthId() {
        return lanHelperModrinthId;
    }

    public void setLanHelperModrinthId(String lanHelperModrinthId) {
        this.lanHelperModrinthId = lanHelperModrinthId;
    }

    public boolean isFirewallHintShown() {
        return firewallHintShown;
    }

    public void setFirewallHintShown(boolean firewallHintShown) {
        this.firewallHintShown = firewallHintShown;
    }

    public int getHelperBridgePort() {
        return helperBridgePort;
    }

    public void setHelperBridgePort(int helperBridgePort) {
        this.helperBridgePort = helperBridgePort;
    }
}
