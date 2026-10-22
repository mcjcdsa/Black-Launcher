package com.blacklauncher.download;

import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.net.ResumableDownloader;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.model.VersionJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves and downloads libraries that only declare Maven coordinates.
 */
public final class MavenLibraryResolver {

    private static final Logger LOG = LoggerFactory.getLogger(MavenLibraryResolver.class);

    private static final String DEFAULT_MAVEN = "https://libraries.minecraft.net/";
    private static final String FABRIC_MAVEN = "https://maven.fabricmc.net/";
    private static final String QUILT_MAVEN = "https://maven.quiltmc.org/repository/release/";
    private static final String FORGE_MAVEN = "https://maven.minecraftforge.net/";
    private static final String NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/";

    private MavenLibraryResolver() {
    }

    public static void downloadLibraries(VersionJson versionJson, ResumableDownloader downloader)
            throws LauncherException {
        downloadLibraries(versionJson, downloader, false);
    }

    /**
     * @param missingOnly when true, skip jars that already exist on disk
     */
    public static void downloadLibraries(VersionJson versionJson, ResumableDownloader downloader, boolean missingOnly)
            throws LauncherException {
        List<VersionJson.Library> libraries = versionJson.getLibraries();
        if (libraries == null || downloader == null) {
            return;
        }
        List<String> failures = new ArrayList<>();
        for (VersionJson.Library library : libraries) {
            if (!OsRules.allows(library.getRules())) {
                continue;
            }
            // Natives-only entries without artifact are handled elsewhere.
            if (library.getNatives() != null
                    && (library.getDownloads() == null || library.getDownloads().getArtifact() == null)
                    && !Validation.isNonBlank(library.getName())) {
                continue;
            }
            try {
                ResolvedLib resolved = resolve(library);
                if (resolved == null) {
                    continue;
                }
                if (missingOnly && Files.isRegularFile(resolved.target())) {
                    continue;
                }
                LOG.info("Downloading library {} -> {}", resolved.url(), resolved.target());
                downloader.download(resolved.url(), resolved.target(), resolved.sha1(), null);
                if (!Files.isRegularFile(resolved.target())) {
                    failures.add(resolved.label() + " (文件未写入)");
                }
            } catch (Exception e) {
                String label = library.getName() != null ? library.getName() : String.valueOf(library);
                LOG.warn("Library download failed: {}", label, e);
                failures.add(label + ": " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new LauncherException("部分依赖库下载失败（" + failures.size() + "）:\n• "
                    + String.join("\n• ", failures.subList(0, Math.min(8, failures.size()))));
        }
    }

    /** Re-download only missing jars (used before launch / repair). */
    public static void ensureLibraries(VersionJson versionJson, ResumableDownloader downloader)
            throws LauncherException {
        downloadLibraries(versionJson, downloader, true);
    }

    public static List<Path> listMissingLibraries(VersionJson versionJson) {
        List<Path> missing = new ArrayList<>();
        if (versionJson == null || versionJson.getLibraries() == null) {
            return missing;
        }
        for (VersionJson.Library library : versionJson.getLibraries()) {
            if (!OsRules.allows(library.getRules())) {
                continue;
            }
            try {
                ResolvedLib resolved = resolve(library);
                if (resolved != null && !Files.isRegularFile(resolved.target())) {
                    missing.add(resolved.target());
                }
            } catch (Exception ignored) {
                // skip unresolvable
            }
        }
        return missing;
    }

    private static ResolvedLib resolve(VersionJson.Library library) {
        if (library.getDownloads() != null && library.getDownloads().getArtifact() != null
                && Validation.isNonBlank(library.getDownloads().getArtifact().getUrl())) {
            VersionJson.Artifact artifact = library.getDownloads().getArtifact();
            Path target = resolveTarget(artifact.getPath(), library.getName());
            String label = Validation.isNonBlank(library.getName()) ? library.getName() : artifact.getUrl();
            return new ResolvedLib(artifact.getUrl(), target, artifact.getSha1(), label);
        }
        if (!Validation.isNonBlank(library.getName())) {
            return null;
        }
        // Skip natives classifiers without a plain artifact name path when downloads absent —
        // name like group:artifact:ver:natives-windows still resolves to a jar path.
        String path = mavenPath(library.getName());
        Path target = AppPaths.librariesDir().resolve(path);
        String base = resolveMavenBase(library);
        String url = base + encodeMavenUrlPath(path);
        return new ResolvedLib(url, target, null, library.getName());
    }

    private static Path resolveTarget(String artifactPath, String name) {
        if (Validation.isNonBlank(artifactPath)) {
            return AppPaths.librariesDir().resolve(artifactPath.replace('\\', '/'));
        }
        return AppPaths.librariesDir().resolve(mavenPath(name));
    }

    public static String mavenPath(String name) {
        String[] parts = name.split(":");
        if (parts.length < 3) {
            throw new IllegalArgumentException("非法 Maven 坐标: " + name);
        }
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length >= 4 ? parts[3] : null;
        String fileName = classifier == null
                ? artifact + "-" + version + ".jar"
                : artifact + "-" + version + "-" + classifier + ".jar";
        return group + "/" + artifact + "/" + version + "/" + fileName;
    }

    /**
     * Encode path segments for HTTP (e.g. {@code +} in Fabric mixin versions → {@code %2B}).
     * Filesystem path must keep the raw {@code +}.
     */
    public static String encodeMavenUrlPath(String mavenPath) {
        if (!Validation.isNonBlank(mavenPath)) {
            return mavenPath;
        }
        String[] segs = mavenPath.replace('\\', '/').split("/");
        StringBuilder sb = new StringBuilder();
        for (String seg : segs) {
            if (seg.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    public static String resolveMavenBase(VersionJson.Library library) {
        if (Validation.isNonBlank(library.getUrl())) {
            String base = library.getUrl().trim();
            return base.endsWith("/") ? base : base + "/";
        }
        String name = library.getName() == null ? "" : library.getName();
        if (name.startsWith("net.fabricmc:") || name.startsWith("net.fabricmc.")) {
            return FABRIC_MAVEN;
        }
        if (name.startsWith("org.quiltmc:")) {
            return QUILT_MAVEN;
        }
        if (name.startsWith("net.minecraftforge:") || name.startsWith("net.minecraftforge.")) {
            return FORGE_MAVEN;
        }
        if (name.startsWith("net.neoforged:")) {
            return NEOFORGE_MAVEN;
        }
        // ASM and other Fabric meta libs often use Fabric maven URL in profile;
        // fall back to Mojang libraries for everything else.
        return DEFAULT_MAVEN;
    }

    private record ResolvedLib(String url, Path target, String sha1, String label) {
    }
}
