package com.blacklauncher.core.util;

import com.blacklauncher.core.util.JsonUtil;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Detects the §8 install layout:
 * <pre>
 *   {root}/
 *     BlackLauncher/   ← program dir (exe / jar / install.json)
 *     MinecraftGame/   ← game dir
 * </pre>
 */
public final class InstallLayout {

    private static final Logger LOG = LoggerFactory.getLogger(InstallLayout.class);
    public static final String LAUNCHER_FOLDER = "BlackLauncher";
    public static final String GAME_FOLDER = "MinecraftGame";
    public static final String INSTALL_JSON = "install.json";

    public record Layout(Path root, Path launcherDir, Path gameDir) {
    }

    private InstallLayout() {
    }

    /** Resolve layout from install.json beside the running app, or sibling folder heuristic. */
    public static Optional<Layout> detect() {
        Optional<Path> appDir = resolveAppDirectory();
        if (appDir.isEmpty()) {
            return Optional.empty();
        }
        Path dir = appDir.get();

        // 1) install.json written by the Windows installer
        Path json = dir.resolve(INSTALL_JSON);
        Optional<Layout> fromJson = readInstallJson(json);
        if (fromJson.isPresent()) {
            return fromJson;
        }

        // 2) Heuristic: .../BlackLauncher next to .../MinecraftGame
        Path parent = dir.getParent();
        if (parent != null) {
            String folderName = dir.getFileName() == null ? "" : dir.getFileName().toString();
            Path siblingGame = parent.resolve(GAME_FOLDER);
            if (LAUNCHER_FOLDER.equalsIgnoreCase(folderName) && Files.isDirectory(siblingGame)) {
                Layout layout = new Layout(parent.toAbsolutePath().normalize(),
                        dir.toAbsolutePath().normalize(),
                        siblingGame.toAbsolutePath().normalize());
                LOG.info("Detected install layout (sibling): {}", layout.root());
                return Optional.of(layout);
            }
            // Also accept running from root with subfolders present
            Path launcherSub = dir.resolve(LAUNCHER_FOLDER);
            Path gameSub = dir.resolve(GAME_FOLDER);
            if (Files.isDirectory(launcherSub) && Files.isDirectory(gameSub)) {
                Layout layout = new Layout(dir.toAbsolutePath().normalize(),
                        launcherSub.toAbsolutePath().normalize(),
                        gameSub.toAbsolutePath().normalize());
                LOG.info("Detected install layout (root): {}", layout.root());
                return Optional.of(layout);
            }
        }
        return Optional.empty();
    }

    public static Optional<Layout> readInstallJson(Path jsonFile) {
        if (jsonFile == null || !Files.isRegularFile(jsonFile)) {
            return Optional.empty();
        }
        try {
            JsonObject obj = JsonUtil.parse(Files.readString(jsonFile, StandardCharsets.UTF_8)).getAsJsonObject();
            String root = obj.has("root") ? obj.get("root").getAsString() : "";
            String launcher = obj.has("launcherDir") ? obj.get("launcherDir").getAsString() : "";
            String game = obj.has("gameDir") ? obj.get("gameDir").getAsString() : "";
            if (!Validation.isNonBlank(root) || !Validation.isNonBlank(game)) {
                return Optional.empty();
            }
            Path rootPath = Paths.get(root).toAbsolutePath().normalize();
            Path launcherPath = Validation.isNonBlank(launcher)
                    ? Paths.get(launcher).toAbsolutePath().normalize()
                    : rootPath.resolve(LAUNCHER_FOLDER);
            Path gamePath = Paths.get(game).toAbsolutePath().normalize();
            Layout layout = new Layout(rootPath, launcherPath, gamePath);
            LOG.info("Loaded install.json: root={} game={}", rootPath, gamePath);
            return Optional.of(layout);
        } catch (Exception e) {
            LOG.warn("Failed to read {}", jsonFile, e);
            return Optional.empty();
        }
    }

    public static String toInstallJson(Layout layout) {
        JsonObject obj = new JsonObject();
        obj.addProperty("root", layout.root().toString());
        obj.addProperty("launcherDir", layout.launcherDir().toString());
        obj.addProperty("gameDir", layout.gameDir().toString());
        obj.addProperty("schema", 1);
        return JsonUtil.toJsonPretty(obj);
    }

    private static Optional<Path> resolveAppDirectory() {
        try {
            var loc = InstallLayout.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc != null) {
                Path p = Paths.get(loc.toURI());
                if (Files.isRegularFile(p)) {
                    return Optional.of(p.getParent().toAbsolutePath().normalize());
                }
                if (Files.isDirectory(p)) {
                    // Running from classes/ — use working directory if it looks like install
                    Path cwd = Paths.get("").toAbsolutePath().normalize();
                    return Optional.of(cwd);
                }
            }
        } catch (Exception e) {
            LOG.debug("CodeSource resolve failed: {}", e.toString());
        }
        try {
            return Optional.of(Paths.get("").toAbsolutePath().normalize());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
