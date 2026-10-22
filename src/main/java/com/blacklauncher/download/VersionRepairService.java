package com.blacklauncher.download;

import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.util.AppPaths;
import com.blacklauncher.core.util.HashUtil;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.blacklauncher.model.VersionJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects corrupted installed version files and reports / prepares repair.
 */
public class VersionRepairService {

    private static final Logger LOG = LoggerFactory.getLogger(VersionRepairService.class);

    public record RepairReport(String versionId, boolean healthy, List<String> issues) {
    }

    public RepairReport inspect(String versionId) {
        List<String> issues = new ArrayList<>();
        if (!Validation.isSafeVersionId(versionId)) {
            return new RepairReport(versionId, false, List.of("非法版本 ID"));
        }
        Path dir = AppPaths.versionsDir().resolve(versionId);
        Path json = dir.resolve(versionId + ".json");
        Path jar = dir.resolve(versionId + ".jar");
        try {
            if (!Files.isDirectory(dir)) {
                issues.add("版本目录不存在");
            }
            if (!Files.isRegularFile(json)) {
                issues.add("缺少版本 JSON");
            } else {
                try {
                    VersionJson parsed = JsonUtil.fromJson(Files.readString(json, StandardCharsets.UTF_8), VersionJson.class);
                    if (parsed == null || !Validation.isNonBlank(parsed.getId())) {
                        issues.add("版本 JSON 损坏");
                    } else if (parsed.getDownloads() != null && parsed.getDownloads().getClient() != null) {
                        String sha1 = parsed.getDownloads().getClient().getSha1();
                        long size = parsed.getDownloads().getClient().getSize();
                        if (Files.isRegularFile(jar)) {
                            if (size > 0 && Files.size(jar) != size) {
                                issues.add("客户端 jar 大小不匹配");
                            } else if (Validation.isNonBlank(sha1) && !HashUtil.matchesSha1(jar, sha1)) {
                                issues.add("客户端 jar SHA-1 校验失败");
                            }
                        } else {
                            issues.add("缺少客户端 jar");
                        }
                    } else if (!Files.isRegularFile(jar)) {
                        // Inherited profiles may omit downloads; jar may be copied from parent.
                        String parent = parsed.getInheritsFrom();
                        if (!Validation.isNonBlank(parent)
                                || !Files.isRegularFile(AppPaths.versionsDir().resolve(parent).resolve(parent + ".jar"))) {
                            if (!Files.isRegularFile(jar)) {
                                issues.add("缺少客户端 jar（且无可用父版本）");
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to parse version json {}", json, e);
                    issues.add("版本 JSON 解析失败");
                }
            }
        } catch (Exception e) {
            LOG.error("Inspect version failed: {}", versionId, e);
            issues.add("检测过程异常: " + e.getMessage());
        }
        return new RepairReport(versionId, issues.isEmpty(), List.copyOf(issues));
    }

    /**
     * Deletes broken jar/json markers so the next download can re-fetch (keeps .part for resume).
     */
    public void prepareRepair(String versionId) throws LauncherException {
        RepairReport report = inspect(versionId);
        if (report.healthy()) {
            return;
        }
        Validation.requireSafeVersionId(versionId);
        Path dir = AppPaths.versionsDir().resolve(versionId);
        try {
            Path jar = dir.resolve(versionId + ".jar");
            Path json = dir.resolve(versionId + ".json");
            for (String issue : report.issues()) {
                if (issue.contains("jar") && Files.isRegularFile(jar)) {
                    Files.deleteIfExists(jar);
                    LOG.info("Removed corrupted jar for {}", versionId);
                }
                if (issue.contains("JSON") && Files.isRegularFile(json)) {
                    Path bak = dir.resolve(versionId + ".json.corrupt");
                    Files.deleteIfExists(bak);
                    Files.move(json, bak);
                    LOG.info("Quarantined corrupted json for {}", versionId);
                }
            }
        } catch (Exception e) {
            throw new LauncherException("准备修复失败: " + e.getMessage(), e);
        }
    }
}
