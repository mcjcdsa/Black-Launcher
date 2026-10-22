package com.blacklauncher.mod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleMarkdownTest {

    @Test
    void rendersHeadingsAndLinks() {
        String html = SimpleMarkdown.toHtml("# Hello\n\nSee [Modrinth](https://modrinth.com)");
        assertTrue(html.contains("<h1>"));
        assertTrue(html.contains("<a href=\"https://modrinth.com\">"));
    }
}
