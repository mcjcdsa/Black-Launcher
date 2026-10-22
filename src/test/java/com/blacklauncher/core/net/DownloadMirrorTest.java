package com.blacklauncher.core.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadMirrorTest {

    @Test
    void rewritesLibrariesToBmcl() {
        String url = "https://libraries.minecraft.net/com/mojang/patchy/1.3.9/patchy-1.3.9.jar";
        String rewritten = DownloadMirror.rewrite(url, DownloadSource.BMCLAPI);
        assertTrue(rewritten.startsWith("https://bmclapi2.bangbang93.com/maven/"));
        assertTrue(rewritten.endsWith("patchy-1.3.9.jar"));
    }

    @Test
    void officialKeepsOriginal() {
        String url = "https://resources.download.minecraft.net/ab/abcdef";
        assertEquals(url, DownloadMirror.rewrite(url, DownloadSource.OFFICIAL));
    }

    @Test
    void toOfficialMapsBmclAssets() {
        String bmcl = "https://bmclapi2.bangbang93.com/assets/ab/abcdef";
        assertEquals(
                "https://resources.download.minecraft.net/ab/abcdef",
                DownloadMirror.toOfficial(bmcl));
    }
}
