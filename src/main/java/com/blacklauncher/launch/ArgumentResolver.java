package com.blacklauncher.launch;

import com.blacklauncher.core.util.Validation;
import com.blacklauncher.download.OsRules;
import com.blacklauncher.model.VersionJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves modern version JSON {@code arguments.jvm} / {@code arguments.game} arrays
 * (1.13+) including OS/feature rules and {@code ${...}} placeholders.
 */
public final class ArgumentResolver {

    private ArgumentResolver() {
    }

    public static List<String> resolve(JsonElement argumentsRoot,
                                       String side,
                                       Map<String, String> placeholders,
                                       Map<String, Boolean> features) {
        List<String> out = new ArrayList<>();
        if (argumentsRoot == null || !argumentsRoot.isJsonObject()) {
            return out;
        }
        JsonObject root = argumentsRoot.getAsJsonObject();
        if (!root.has(side) || !root.get(side).isJsonArray()) {
            return out;
        }
        for (JsonElement el : root.getAsJsonArray(side)) {
            appendElement(el, out, placeholders, features);
        }
        return out;
    }

    private static void appendElement(JsonElement el,
                                      List<String> out,
                                      Map<String, String> placeholders,
                                      Map<String, Boolean> features) {
        if (el == null || el.isJsonNull()) {
            return;
        }
        if (el.isJsonPrimitive()) {
            String value = substitute(el.getAsString(), placeholders);
            if (Validation.isNonBlank(value)) {
                out.add(value);
            }
            return;
        }
        if (!el.isJsonObject()) {
            return;
        }
        JsonObject obj = el.getAsJsonObject();
        if (obj.has("rules") && !rulesAllow(obj.getAsJsonArray("rules"), features)) {
            return;
        }
        if (!obj.has("value")) {
            return;
        }
        JsonElement value = obj.get("value");
        if (value.isJsonPrimitive()) {
            String s = substitute(value.getAsString(), placeholders);
            if (Validation.isNonBlank(s)) {
                out.add(s);
            }
        } else if (value.isJsonArray()) {
            for (JsonElement part : value.getAsJsonArray()) {
                if (part.isJsonPrimitive()) {
                    String s = substitute(part.getAsString(), placeholders);
                    if (Validation.isNonBlank(s)) {
                        out.add(s);
                    }
                }
            }
        }
    }

    private static boolean rulesAllow(JsonArray rules, Map<String, Boolean> features) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        String currentOs = OsRules.currentOsName();
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osVersion = System.getProperty("os.version", "");
        for (JsonElement el : rules) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject rule = el.getAsJsonObject();
            boolean matches = true;
            if (rule.has("os") && rule.get("os").isJsonObject()) {
                JsonObject os = rule.getAsJsonObject("os");
                VersionJson.Os osRule = new VersionJson.Os();
                if (os.has("name")) {
                    osRule.setName(os.get("name").getAsString());
                }
                if (os.has("arch")) {
                    osRule.setArch(os.get("arch").getAsString());
                }
                if (os.has("version")) {
                    osRule.setVersion(os.get("version").getAsString());
                }
                matches = OsRules.osMatches(osRule, currentOs, arch, osVersion);
            }
            if (matches && rule.has("features") && rule.get("features").isJsonObject()) {
                JsonObject feats = rule.getAsJsonObject("features");
                for (Map.Entry<String, JsonElement> e : feats.entrySet()) {
                    boolean expected = e.getValue().getAsBoolean();
                    boolean actual = features != null && Boolean.TRUE.equals(features.get(e.getKey()));
                    if (expected != actual) {
                        matches = false;
                        break;
                    }
                }
            }
            if (!matches) {
                continue;
            }
            String action = rule.has("action") ? rule.get("action").getAsString() : "allow";
            allowed = "allow".equalsIgnoreCase(action);
        }
        return allowed;
    }

    public static String substitute(String template, Map<String, String> placeholders) {
        if (template == null) {
            return "";
        }
        if (placeholders == null || placeholders.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            result = result.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return result;
    }
}
