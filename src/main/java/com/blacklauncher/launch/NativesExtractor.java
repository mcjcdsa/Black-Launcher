package com.blacklauncher.launch;

import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.download.OsRules;
import com.blacklauncher.model.VersionJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts <b>classic</b> natives jars (natives map + classifiers) into {@code natives/{versionId}}.
 * <p>
 * Modern {@code :natives-*} artifacts stay on the classpath (HMCL / LWJGL3 model) and are not extracted.
 */
public final class NativesExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(NativesExtractor.class);
    private static final String STAMP = ".extracted-stamp";

    private NativesExtractor() {
    }

    public static Path extract(VersionJson version, String versionId) throws LauncherException {
        Validation.requireSafeVersionId(versionId);
        Path nativesRoot = AppPaths.nativesDir().resolve(versionId);
        try {
            Files.createDirectories(nativesRoot);
        } catch (Exception e) {
            throw new LauncherException("创建 natives 目录失败", e);
        }
        if (version == null || version.getLibraries() == null) {
            return nativesRoot;
        }

        List<Path> classicJars = new ArrayList<>();
        String os = OsRules.currentOsName();
        for (VersionJson.Library library : version.getLibraries()) {
            if (!OsRules.allows(library.getRules())) {
                continue;
            }
            if (library.getNatives() == null
                    || library.getDownloads() == null
                    || library.getDownloads().getClassifiers() == null) {
                continue;
            }
            String raw = library.getNatives().get(os);
            if (raw == null) {
                continue;
            }
            String classifier = OsRules.resolveClassifier(raw);
            VersionJson.Artifact art = library.getDownloads().getClassifiers().get(classifier);
            if (art == null && library.getDownloads().getClassifiers().containsKey(raw)) {
                art = library.getDownloads().getClassifiers().get(raw);
            }
            if (art == null || !Validation.isNonBlank(art.getPath())) {
                // Try alternate descriptors (HMCL POSSIBLE_NATIVE_DESCRIPTORS style).
                art = findClassifierArtifact(library, classifier);
            }
            if (art == null || !Validation.isNonBlank(art.getPath())) {
                continue;
            }
            Path zip = AppPaths.librariesDir().resolve(art.getPath().replace('\\', '/'));
            classicJars.add(zip);
            if (!Files.isRegularFile(zip)) {
                LOG.warn("Missing classic natives jar: {}", zip);
                continue;
            }
        }

        if (classicJars.isEmpty()) {
            return nativesRoot;
        }
        if (stampMatches(nativesRoot, classicJars)) {
            LOG.debug("Natives already extracted for {}", versionId);
            return nativesRoot;
        }

        for (VersionJson.Library library : version.getLibraries()) {
            if (!OsRules.allows(library.getRules()) || library.getNatives() == null
                    || library.getDownloads() == null || library.getDownloads().getClassifiers() == null) {
                continue;
            }
            String raw = library.getNatives().get(os);
            if (raw == null) {
                continue;
            }
            String classifier = OsRules.resolveClassifier(raw);
            VersionJson.Artifact art = library.getDownloads().getClassifiers().get(classifier);
            if (art == null) {
                art = findClassifierArtifact(library, classifier);
            }
            if (art == null || !Validation.isNonBlank(art.getPath())) {
                continue;
            }
            Path zip = AppPaths.librariesDir().resolve(art.getPath().replace('\\', '/'));
            extractZip(zip, nativesRoot, library.getExtract());
        }
        writeStamp(nativesRoot, classicJars);
        return nativesRoot;
    }

    /**
     * True when the Maven name is a modern platform natives jar (belongs on classpath, not extract).
     */
    public static boolean isNativeLibraryName(String name, String classifierKey) {
        if (!Validation.isNonBlank(name)) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.contains(":natives-")) {
            return false;
        }
        if (!Validation.isNonBlank(classifierKey)) {
            return true;
        }
        String key = classifierKey.toLowerCase(Locale.ROOT);
        // Accept exact classifier or broader platform family (natives-windows matches natives-windows-arm64 key).
        if (lower.endsWith(":" + key) || lower.contains(":" + key)) {
            return true;
        }
        // Also accept base platform when key is arm64 variant.
        if (key.endsWith("-arm64")) {
            String base = key.substring(0, key.length() - "-arm64".length());
            return lower.endsWith(":" + base) || lower.contains(":" + base + ":") || lower.contains(":" + base);
        }
        return false;
    }

    /** Classic natives-only entry (no artifact) — must not appear on -cp. */
    public static boolean isClassicNativesOnly(VersionJson.Library library) {
        return library != null
                && library.getNatives() != null
                && (library.getDownloads() == null || library.getDownloads().getArtifact() == null);
    }

    private static VersionJson.Artifact findClassifierArtifact(VersionJson.Library library, String preferred) {
        var classifiers = library.getDownloads().getClassifiers();
        if (classifiers.containsKey(preferred)) {
            return classifiers.get(preferred);
        }
        String os = OsRules.currentOsName();
        String[] candidates = {
                preferred,
                "natives-" + os,
                "native-" + os,
                os,
                "natives-" + os + "-" + OsRules.mojangArch(),
                "natives-" + os + "-arm64"
        };
        for (String c : candidates) {
            if (classifiers.containsKey(c)) {
                return classifiers.get(c);
            }
        }
        return null;
    }

    private static boolean stampMatches(Path nativesRoot, List<Path> jars) {
        Path stamp = nativesRoot.resolve(STAMP);
        if (!Files.isRegularFile(stamp)) {
            return false;
        }
        try {
            String expected = stampContent(jars);
            String actual = Files.readString(stamp, StandardCharsets.UTF_8);
            return expected.equals(actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeStamp(Path nativesRoot, List<Path> jars) {
        try {
            Files.writeString(nativesRoot.resolve(STAMP), stampContent(jars), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.debug("Failed to write natives stamp", e);
        }
    }

    private static String stampContent(List<Path> jars) {
        return jars.stream()
                .map(p -> {
                    try {
                        long size = Files.isRegularFile(p) ? Files.size(p) : -1L;
                        return p.toAbsolutePath() + "|" + size;
                    } catch (Exception e) {
                        return p.toAbsolutePath() + "|-1";
                    }
                })
                .sorted()
                .collect(Collectors.joining("\n"));
    }

    private static void extractZip(Path zipPath, Path targetDir, VersionJson.Extract extract)
            throws LauncherException {
        if (zipPath == null || !Files.isRegularFile(zipPath)) {
            LOG.debug("Skip missing natives jar: {}", zipPath);
            return;
        }
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                    continue;
                }
                if (name.endsWith(".sha1") || name.endsWith(".git") || name.startsWith("META-INF/")) {
                    continue;
                }
                if (extract != null && extract.getExclude() != null) {
                    boolean excluded = false;
                    for (String ex : extract.getExclude()) {
                        if (name.startsWith(ex)) {
                            excluded = true;
                            break;
                        }
                    }
                    if (excluded) {
                        continue;
                    }
                }
                Path out = targetDir.resolve(name).normalize();
                if (!out.startsWith(targetDir)) {
                    throw new LauncherException("非法 zip 路径: " + name);
                }
                if (Files.isRegularFile(out)) {
                    try {
                        if (Files.size(out) == entry.getSize() && entry.getSize() >= 0) {
                            continue;
                        }
                    } catch (Exception ignored) {
                        // rewrite
                    }
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = zipFile.getInputStream(entry)) {
                    Files.write(out, in.readAllBytes());
                }
            }
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Extract natives failed: {}", zipPath, e);
            throw new LauncherException("解压 natives 失败: " + e.getMessage(), e);
        }
    }
}
