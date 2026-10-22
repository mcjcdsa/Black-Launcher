package com.blacklauncher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArgumentResolverTest {

    @Test
    void substitutesPlaceholders() {
        Map<String, String> ph = Map.of("auth_player_name", "Steve", "auth_uuid", "abc");
        assertEquals("Steve", ArgumentResolver.substitute("${auth_player_name}", ph));
        assertEquals("--uuid abc", ArgumentResolver.substitute("--uuid ${auth_uuid}", ph));
    }

    @Test
    void resolvesPlainJvmAndGameArgs() {
        JsonObject root = JsonParser.parseString("""
                {
                  "jvm": ["-cp", "${classpath}", "-Djava.library.path=${natives_directory}"],
                  "game": ["--username", "${auth_player_name}", "--accessToken", "${auth_access_token}"]
                }
                """).getAsJsonObject();
        Map<String, String> ph = new HashMap<>();
        ph.put("classpath", "a.jar;b.jar");
        ph.put("natives_directory", "C:/natives");
        ph.put("auth_player_name", "Alex");
        ph.put("auth_access_token", "tok");
        Map<String, Boolean> features = Map.of();

        List<String> jvm = ArgumentResolver.resolve(root, "jvm", ph, features);
        assertEquals(List.of("-cp", "a.jar;b.jar", "-Djava.library.path=C:/natives"), jvm);

        List<String> game = ArgumentResolver.resolve(root, "game", ph, features);
        assertEquals(List.of("--username", "Alex", "--accessToken", "tok"), game);
    }

    @Test
    void featureRulesGateCustomResolution() {
        JsonObject root = new JsonObject();
        JsonArray game = new JsonArray();
        game.add("--username");
        game.add("P");
        JsonObject widthRule = new JsonObject();
        JsonArray rules = new JsonArray();
        JsonObject allow = new JsonObject();
        allow.addProperty("action", "allow");
        JsonObject feats = new JsonObject();
        feats.addProperty("has_custom_resolution", true);
        allow.add("features", feats);
        rules.add(allow);
        widthRule.add("rules", rules);
        widthRule.addProperty("value", "--width");
        game.add(widthRule);
        JsonObject heightVal = new JsonObject();
        heightVal.add("rules", rules.deepCopy());
        heightVal.addProperty("value", "1280");
        game.add(heightVal);
        root.add("game", game);

        Map<String, String> ph = Map.of();
        List<String> off = ArgumentResolver.resolve(root, "game", ph, Map.of("has_custom_resolution", false));
        assertEquals(List.of("--username", "P"), off);

        List<String> on = ArgumentResolver.resolve(root, "game", ph, Map.of("has_custom_resolution", true));
        assertTrue(on.contains("--width"));
        assertTrue(on.contains("1280"));
        assertFalse(off.contains("--width"));
    }

    @Test
    void valueArrayIsExpanded() {
        JsonObject root = JsonParser.parseString("""
                {
                  "jvm": [
                    { "value": ["-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC"] }
                  ]
                }
                """).getAsJsonObject();
        List<String> jvm = ArgumentResolver.resolve(root, "jvm", Map.of(), Map.of());
        assertEquals(List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC"), jvm);
    }
}
