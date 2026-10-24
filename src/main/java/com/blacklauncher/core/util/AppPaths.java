package com.blacklauncher.core.util;

import com.blacklauncher.core.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves launcher / game directories.
 * <p>
 * Launcher home can be redirected via {@code ~/.black-launcher/launcher-home.txt}
 * (so config can live on another drive). Game dir defaults to {@code home/minecraft}
 * or {@link AppConfig#getGameDirectory()}.
 */
public final class AppPaths {

    private static final Logger LOG = LoggerFactory.getLogger(AppPaths.class);
    private static final String APP_DIR_NAME = ".black-launcher";
    private static final String REDIRECT_FILE = "launcher-home.txt";

    private static volatile Path launcherHome;
    private static volatile Path gameDirectory;

    private AppPaths() {
    }

    /** Fixed bootstrap location under the user profile (never moves). */
    public static Path bootstrapHome() {
        return Paths.get(System.getProperty("user.home"), APP_DIR_NAME);
    }

    public static Path redirectFile() {
        return bootstrapHome().resolve(REDIRECT_FILE);
    }

    /**
     * Resolve launcher / game roots.
     * Priority: user redirect → §8 install layout (BlackLauncher + MinecraftGame) → ~/.black-launcher.
     */
    public static void bootstrap() {
        gameDirectory = null;
        var redirect = readRedirectTarget();
        if (redirect.isPresent()) {
            launcherHome = redirect.get().toAbsolutePath().normalize();
            LOG.info("Launcher home (redirect): {}", launcherHome);
        } else {
            var layout = InstallLayout.detect();
            if (layout.isPresent()) {
                InstallLayout.Layout L = layout.get();
                launcherHome = L.launcherDir();
                gameDirectory = L.gameDir();
                LOG.info("Launcher home (install layout): {}  game: {}", launcherHome, gameDirectory);
            } else {
                launcherHome = bootstrapHome().toAbsolutePath().normalize();
                LOG.info("Launcher home (default): {}", launcherHome);
            }
        }
        System.setProperty("blacklauncher.home", launcherHome.toString());
    }

    /** Apply game-directory override from loaded config (blank = keep bootstrap default). */
    public static void applyFromConfig(AppConfig config) {
        if (config == null) {
            return;
        }
        String raw = config.getGameDirectory();
        if (Validation.isNonBlank(raw)) {
            gameDirectory = Paths.get(raw.trim()).toAbsolutePath().normalize();
        }
        // If still unset and install layout exists, lock to MinecraftGame.
        if (gameDirectory == null) {
            InstallLayout.detect().ifPresent(L -> gameDirectory = L.gameDir());
        }
        String homeStr = home().toAbsolutePath().toString();
        if (!homeStr.equals(config.getDataDirectory())) {
            config.setDataDirectory(homeStr);
        }
        if (gameDirectory != null) {
            config.setGameDirectory(gameDirectory.toString());
        }
    }

    public static Path gameDir() {
        if (gameDirectory != null) {
            return gameDirectory;
        }
        // Prefer sibling MinecraftGame even if install.json missing after first run.
        var layout = InstallLayout.detect();
        if (layout.isPresent()) {
            return layout.get().gameDir();
        }
        return home().resolve("minecraft");
    }

    public static Path home() {
        if (launcherHome == null) {
            bootstrap();
        }
        return launcherHome;
    }

    public static void setLauncherHome(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("launcher home is null");
        }
        launcherHome = path.toAbsolutePath().normalize();
        System.setProperty("blacklauncher.home", launcherHome.toString());
    }

    public static void setGameDir(Path path) {
        if (path == null) {
            gameDirectory = null;
            return;
        }
        gameDirectory = path.toAbsolutePath().normalize();
    }

    /** Persist redirect so next launch finds the custom launcher home. */
    public static void writeLauncherRedirect(Path newHome) throws Exception {
        Path target = newHome.toAbsolutePath().normalize();
        Path bootstrap = bootstrapHome();
        Files.createDirectories(bootstrap);
        Path file = redirectFile();
        Path tmp = file.resolveSibling(REDIRECT_FILE + ".tmp");
        Files.writeString(tmp, target.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    public static void clearLauncherRedirect() throws Exception {
        Files.deleteIfExists(redirectFile());
    }

    public static Path configFile() {
        return home().resolve("config.json");
    }

    public static Path databaseFile() {
        return home().resolve("black.db");
    }

    public static Path logsDir() {
        return home().resolve("logs");
    }

    public static Path versionsDir() {
        return gameDir().resolve("versions");
    }

    public static Path librariesDir() {
        return gameDir().resolve("libraries");
    }

    public static Path assetsDir() {
        return gameDir().resolve("assets");
    }

    public static Path nativesDir() {
        return gameDir().resolve("natives");
    }

    public static Path skinsDir() {
        return home().resolve("skins");
    }

    public static Path downloadsTempDir() {
        return home().resolve("downloads");
    }

    public static Path modpacksDir() {
        return home().resolve("modpacks");
    }

    public static Path launchScriptsDir() {
        return home().resolve("LaunchScript");
    }

    /** Default working directory for generated Fabric mod projects. */
    public static Path modDevDir() {
        return home().resolve("mod-dev");
    }

    /** Local cache of FabricMC/fabric-docs Markdown. */
    public static Path fabricDocsCacheDir() {
        return home().resolve("cache").resolve("fabric-docs");
    }

    public static void ensureDirectories() {
        try {
            if (launcherHome == null) {
                bootstrap();
            }
            Files.createDirectories(bootstrapHome());
            Files.createDirectories(home());
            Files.createDirectories(logsDir());
            Files.createDirectories(gameDir());
            Files.createDirectories(versionsDir());
            Files.createDirectories(librariesDir());
            Files.createDirectories(assetsDir());
            Files.createDirectories(nativesDir());
            Files.createDirectories(skinsDir());
            Files.createDirectories(downloadsTempDir());
            Files.createDirectories(modpacksDir());
            Files.createDirectories(launchScriptsDir());
            Files.createDirectories(modDevDir());
            Files.createDirectories(fabricDocsCacheDir());
        } catch (Exception e) {
            throw new IllegalStateException("无法创建应用数据目录: " + home(), e);
        }
    }

    private static java.util.Optional<Path> readRedirectTarget() {
        Path file = redirectFile();
        if (!Files.isRegularFile(file)) {
            return java.util.Optional.empty();
        }
        try {
            String line = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (!Validation.isNonBlank(line)) {
                return java.util.Optional.empty();
            }
            Path target = Paths.get(line).toAbsolutePath().normalize();
            if (!Files.isDirectory(target)) {
                Files.createDirectories(target);
            }
            // Ignore redirect that points at bootstrap itself.
            if (target.equals(bootstrapHome().toAbsolutePath().normalize())) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(target);
        } catch (Exception e) {
            LOG.warn("Invalid launcher-home redirect, using default", e);
            return java.util.Optional.empty();
        }
    }
}
