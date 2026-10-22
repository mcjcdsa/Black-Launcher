package com.blacklauncher.core.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/**
 * Subprocess / console charset helpers (aligned with HMCL {@code OperatingSystem.NATIVE_CHARSET}).
 * <p>
 * On Chinese Windows the game JVM typically emits GBK/GB18030 unless {@code -Dfile.encoding}
 * and stdout/stderr encoding flags match; reading that stream as UTF-8 produces mojibake.
 */
public final class ProcessCharsets {

    /** Native console charset (GB18030 on zh-CN Windows when default is GBK). */
    public static final Charset NATIVE;

    static {
        Charset charset = Charset.defaultCharset();
        try {
            String override = System.getProperty("blacklauncher.native.encoding");
            if (override != null && !override.isBlank()) {
                charset = Charset.forName(override.trim());
            } else {
                String nativeEncoding = System.getProperty("native.encoding");
                if (nativeEncoding != null && !nativeEncoding.equalsIgnoreCase(charset.name())) {
                    charset = Charset.forName(nativeEncoding);
                }
                if (charset.equals(StandardCharsets.UTF_8) || charset.equals(StandardCharsets.US_ASCII)) {
                    charset = StandardCharsets.UTF_8;
                } else if ("GBK".equalsIgnoreCase(charset.name()) || "GB2312".equalsIgnoreCase(charset.name())) {
                    charset = Charset.forName("GB18030");
                }
            }
        } catch (UnsupportedCharsetException e) {
            charset = Charset.defaultCharset();
        }
        NATIVE = charset;
    }

    private ProcessCharsets() {
    }

    /** Charset used for Minecraft process stdout/stderr when we inject matching JVM flags. */
    public static Charset gameOutputCharset() {
        return NATIVE;
    }

    public static String encodingName() {
        return NATIVE.name();
    }
}
