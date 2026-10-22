package com.blacklauncher.mod;

import com.blacklauncher.core.util.Validation;

import java.util.Locale;

/**
 * Minimal Markdown → HTML for Modrinth body / changelog (no external deps).
 */
public final class SimpleMarkdown {

    private SimpleMarkdown() {
    }

    public static String toHtmlDocument(String markdown, boolean dark) {
        String body = toHtml(markdown);
        String bg = dark ? "#171b24" : "#f7f8fb";
        String fg = dark ? "#e8eaef" : "#1c2333";
        String link = dark ? "#8eb6ff" : "#2f6fed";
        return """
                <!DOCTYPE html><html><head><meta charset="UTF-8"/>
                <style>
                body{font-family:'Segoe UI','Microsoft YaHei UI',sans-serif;font-size:13px;
                color:%s;background:%s;margin:8px;line-height:1.55;word-wrap:break-word;}
                img{max-width:100%%;height:auto;border-radius:6px;cursor:pointer;}
                a{color:%s;} pre,code{background:rgba(127,127,127,0.15);padding:2px 4px;border-radius:4px;}
                pre{padding:8px;overflow:auto;} h1,h2,h3{margin:12px 0 6px;} ul{padding-left:20px;}
                </style></head><body>%s</body></html>
                """.formatted(fg, bg, link, body);
    }

    public static String toHtml(String markdown) {
        if (!Validation.isNonBlank(markdown)) {
            return "<p class='empty'>暂无介绍</p>";
        }
        String text = markdown.replace("\r\n", "\n");
        StringBuilder out = new StringBuilder();
        String[] lines = text.split("\n", -1);
        boolean inList = false;
        boolean inCode = false;
        StringBuilder code = new StringBuilder();
        for (String raw : lines) {
            String line = raw;
            if (line.trim().startsWith("```")) {
                if (inCode) {
                    out.append("<pre><code>").append(escape(code.toString())).append("</code></pre>");
                    code.setLength(0);
                    inCode = false;
                } else {
                    if (inList) {
                        out.append("</ul>");
                        inList = false;
                    }
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                code.append(line).append('\n');
                continue;
            }
            if (line.trim().startsWith("- ") || line.trim().startsWith("* ")) {
                if (!inList) {
                    out.append("<ul>");
                    inList = true;
                }
                out.append("<li>").append(inline(line.trim().substring(2))).append("</li>");
                continue;
            }
            if (inList) {
                out.append("</ul>");
                inList = false;
            }
            if (line.startsWith("### ")) {
                out.append("<h3>").append(inline(line.substring(4))).append("</h3>");
            } else if (line.startsWith("## ")) {
                out.append("<h2>").append(inline(line.substring(3))).append("</h2>");
            } else if (line.startsWith("# ")) {
                out.append("<h1>").append(inline(line.substring(2))).append("</h1>");
            } else if (line.trim().isEmpty()) {
                out.append("<br/>");
            } else {
                out.append("<p>").append(inline(line)).append("</p>");
            }
        }
        if (inList) {
            out.append("</ul>");
        }
        if (inCode) {
            out.append("<pre><code>").append(escape(code.toString())).append("</code></pre>");
        }
        return out.toString();
    }

    private static String inline(String s) {
        String e = escape(s);
        // images ![alt](url)
        e = e.replaceAll("!\\[([^\\]]*)]\\(([^)]+)\\)", "<img alt=\"$1\" src=\"$2\"/>");
        // links [text](url)
        e = e.replaceAll("\\[([^\\]]+)]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");
        // bold / italic
        e = e.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        e = e.replaceAll("\\*([^*]+)\\*", "<em>$1</em>");
        e = e.replaceAll("`([^`]+)`", "<code>$1</code>");
        return e;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0) {
            return "-";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    public static String formatTime(long epochMs) {
        if (epochMs <= 0) {
            return "-";
        }
        return java.time.Instant.ofEpochMilli(epochMs)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .toString();
    }
}
