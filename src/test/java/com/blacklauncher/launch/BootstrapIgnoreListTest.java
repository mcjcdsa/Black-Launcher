package com.blacklauncher.launch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapIgnoreListTest {

    @Test
    void expandsSubstringTokensToExactJars() {
        String rewritten = BootstrapIgnoreList.rewrite(
                "client-extra,asm",
                "1.20.1.jar",
                "C:/mc/versions/1.20.1/1.20.1.jar",
                List.of(
                        "C:/mc/libraries/org/ow2/asm/asm/9.6/asm-9.6.jar",
                        "C:/mc/libraries/net/minecraft/client/extra/client-extra.jar",
                        "C:/mc/libraries/com/example/foo/1.0/foo-1.0.jar"
                ));
        assertTrue(rewritten.contains("1.20.1.jar"));
        assertTrue(rewritten.contains("asm-9.6.jar"));
        assertTrue(rewritten.contains("client-extra.jar"));
        assertTrue(!rewritten.contains(",asm,") && !rewritten.endsWith(",asm"));
    }
}
