package com.blacklauncher.moddev;

import com.blacklauncher.core.util.Validation;

import java.util.regex.Pattern;

/**
 * Input rules for Fabric mod template generation.
 */
public final class TemplateValidation {

    public static final String DEFAULT_MOD_ID = "template-mod";

    private static final Pattern MOD_ID = Pattern.compile("^[a-z][a-z0-9_-]*$");
    private static final Pattern PACKAGE_SEGMENT = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private TemplateValidation() {
    }

    /**
     * Empty / blank → {@link #DEFAULT_MOD_ID}; otherwise must match {@code ^[a-z][a-z0-9_-]*$}.
     */
    public static String normalizeModId(String raw) {
        if (!Validation.isNonBlank(raw)) {
            return DEFAULT_MOD_ID;
        }
        return raw.trim();
    }

    public static boolean isValidModId(String modId) {
        return Validation.isNonBlank(modId) && MOD_ID.matcher(modId.trim()).matches();
    }

    /** @return null if valid; otherwise a short Chinese error message */
    public static String modIdViolation(String raw) {
        if (!Validation.isNonBlank(raw)) {
            return null; // empty allowed → template-mod
        }
        String id = raw.trim();
        if (!MOD_ID.matcher(id).matches()) {
            return "模组 ID 须以小写字母开头，仅含小写字母、数字、下划线与短横线";
        }
        return null;
    }

    public static boolean isValidPackageName(String packageName) {
        if (!Validation.isNonBlank(packageName)) {
            return false;
        }
        String[] parts = packageName.trim().split("\\.", -1);
        if (parts.length == 0) {
            return false;
        }
        for (String part : parts) {
            if (!PACKAGE_SEGMENT.matcher(part).matches()) {
                return false;
            }
        }
        return true;
    }

    /** @return null if valid; otherwise a short Chinese error message */
    public static String packageNameViolation(String packageName) {
        if (!Validation.isNonBlank(packageName)) {
            return "包名不能为空";
        }
        if (!isValidPackageName(packageName)) {
            return "包名须符合 Java 规范（各段字母/下划线开头，仅含字母数字下划线）";
        }
        return null;
    }

    /** @return null if valid; otherwise a short Chinese error message */
    public static String modNameViolation(String modName) {
        if (!Validation.isNonBlank(modName)) {
            return "模组名称不能为空";
        }
        return null;
    }

    public static String validate(TemplateRequest req) {
        if (req == null) {
            return "请求为空";
        }
        String nameErr = modNameViolation(req.modName());
        if (nameErr != null) {
            return nameErr;
        }
        String idErr = modIdViolation(req.modId());
        if (idErr != null) {
            return idErr;
        }
        String pkgErr = packageNameViolation(req.packageName());
        if (pkgErr != null) {
            return pkgErr;
        }
        if (!Validation.isNonBlank(req.minecraftVersion())) {
            return "请选择 Minecraft 版本";
        }
        return null;
    }
}
