package com.blacklauncher.auth;

import com.blacklauncher.core.exception.LauncherException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Localhost OAuth callback + PKCE (S256), HMCL {@code OAuthServer}-style.
 */
public final class OAuthLocalServer implements AutoCloseable {

    private final HttpServer server;
    private final int port;
    private final String state;
    private final String codeVerifier;
    private final CompletableFuture<String> codeFuture = new CompletableFuture<>();

    private OAuthLocalServer(HttpServer server, int port, String state, String codeVerifier) {
        this.server = server;
        this.port = port;
        this.state = state;
        this.codeVerifier = codeVerifier;
    }

    public static OAuthLocalServer start() throws LauncherException {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            int port = server.getAddress().getPort();
            SecureRandom random = new SecureRandom();
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            byte[] stateBytes = new byte[32];
            random.nextBytes(stateBytes);
            String state = encoder.encodeToString(stateBytes);
            byte[] verifierBytes = new byte[64];
            random.nextBytes(verifierBytes);
            String codeVerifier = encoder.encodeToString(verifierBytes);

            OAuthLocalServer session = new OAuthLocalServer(server, port, state, codeVerifier);
            server.createContext("/auth-response", session::handle);
            server.setExecutor(null);
            server.start();
            return session;
        } catch (IOException e) {
            throw new LauncherException("无法启动本地 OAuth 回调服务: " + e.getMessage(), e);
        }
    }

    public String redirectUri() {
        return "http://127.0.0.1:" + port + "/auth-response";
    }

    public String state() {
        return state;
    }

    public String codeVerifier() {
        return codeVerifier;
    }

    public String codeChallengeS256() throws LauncherException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new LauncherException("生成 PKCE code_challenge 失败", e);
        }
    }

    public String waitForCode(long timeoutSec) throws LauncherException {
        try {
            String code = codeFuture.get(timeoutSec, TimeUnit.SECONDS);
            if (code == null || code.isBlank()) {
                throw new LauncherException("未收到授权码");
            }
            return code;
        } catch (LauncherException e) {
            throw e;
        } catch (java.util.concurrent.TimeoutException e) {
            throw new LauncherException("浏览器授权超时，请重试");
        } catch (Exception e) {
            throw new LauncherException("等待授权码失败: " + e.getMessage(), e);
        }
    }

    public void cancel() {
        codeFuture.completeExceptionally(new InterruptedException("cancelled"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                    && !"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "Method Not Allowed");
                return;
            }
            Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
            String err = q.get("error");
            if (err != null) {
                String desc = q.getOrDefault("error_description", err);
                codeFuture.completeExceptionally(new LauncherException("授权被拒绝: " + desc));
                respondHtml(exchange, 400, "登录失败", desc);
                return;
            }
            String returnedState = q.get("state");
            if (returnedState == null || !returnedState.equals(state)) {
                codeFuture.completeExceptionally(new LauncherException("OAuth state 不匹配，请重试"));
                respondHtml(exchange, 400, "登录失败", "state 校验失败");
                return;
            }
            String code = q.get("code");
            if (code == null || code.isBlank()) {
                codeFuture.completeExceptionally(new LauncherException("回调缺少授权码"));
                respondHtml(exchange, 400, "登录失败", "缺少 code");
                return;
            }
            codeFuture.complete(code);
            respondHtml(exchange, 200, "登录成功", "可以关闭此页面，返回布莱克启动器。");
        } catch (Exception e) {
            codeFuture.completeExceptionally(e);
            respondHtml(exchange, 500, "错误", e.getMessage() == null ? "unknown" : e.getMessage());
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            map.put(k, v);
        }
        return map;
    }

    private static void respondHtml(HttpExchange exchange, int code, String title, String body) throws IOException {
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>" + title
                + "</title></head><body style=\"font-family:sans-serif;padding:2rem\">"
                + "<h2>" + title + "</h2><p>" + body + "</p></body></html>";
        respond(exchange, code, html);
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        try {
            server.stop(0);
        } catch (Exception ignored) {
            // ignore
        }
    }
}
