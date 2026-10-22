package com.blacklauncher.auth;

import com.blacklauncher.core.config.AppConfig;
import com.blacklauncher.core.exception.LauncherException;
import com.blacklauncher.core.net.HttpClientFactory;
import com.blacklauncher.core.util.JsonUtil;
import com.blacklauncher.core.util.Validation;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Microsoft account → Xbox Live → XSTS → Minecraft token
 * (device-code + PKCE authorization-code flows, HMCL-aligned).
 * <p>
 * Requires a Mojang-approved Azure {@code client_id} in {@link AppConfig#getMicrosoftClientId()}.
 * For browser PKCE, register redirect {@code http://127.0.0.1} (any port) on the Azure app.
 */
public class MicrosoftAuthService {

    private static final Logger LOG = LoggerFactory.getLogger(MicrosoftAuthService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final String AUTH_AUTHORIZE = "https://login.live.com/oauth20_authorize.srf";
    private static final String AUTH_TOKEN = "https://login.live.com/oauth20_token.srf";

    private final OkHttpClient client;
    private final AppConfig config;

    public MicrosoftAuthService(AppConfig config) {
        this.config = config;
        this.client = HttpClientFactory.create(config);
    }

    public record DeviceCode(String deviceCode, String userCode, String verificationUri,
                             int intervalSec, int expiresInSec, String message) {
    }

    public record Session(
            String minecraftAccessToken,
            String refreshToken,
            String microsoftAccessToken,
            String xboxUserHash,
            String playerName,
            String playerUuid,
            long expiresAtEpochMs
    ) {
    }

    /** Device-code poll outcome (HMCL: pending / slow_down / done). */
    public record PollResult(Session session, boolean slowDown) {
        public static PollResult pending() {
            return new PollResult(null, false);
        }

        public static PollResult needsSlowDown() {
            return new PollResult(null, true);
        }

        public static PollResult done(Session session) {
            return new PollResult(session, false);
        }

        public boolean isDone() {
            return session != null;
        }
    }

    public DeviceCode requestDeviceCode() throws LauncherException {
        String clientId = requireClientId();
        RequestBody body = new FormBody.Builder()
                .add("client_id", clientId)
                .add("scope", SCOPE)
                .build();
        Request request = new Request.Builder()
                .url("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(body)
                .build();
        JsonObject json = executeJson(request);
        return new DeviceCode(
                json.get("device_code").getAsString(),
                json.get("user_code").getAsString(),
                json.has("verification_uri") ? json.get("verification_uri").getAsString()
                        : "https://www.microsoft.com/link",
                json.has("interval") ? json.get("interval").getAsInt() : 5,
                json.has("expires_in") ? json.get("expires_in").getAsInt() : 900,
                json.has("message") ? json.get("message").getAsString() : ""
        );
    }

    /**
     * Browser PKCE authorization-code login. Caller should open {@code authorizeUrl} in a browser
     * after starting {@link OAuthLocalServer}.
     */
    public record PkceLogin(OAuthLocalServer server, String authorizeUrl) implements AutoCloseable {
        @Override
        public void close() {
            server.close();
        }
    }

    public PkceLogin beginAuthorizationCodeLogin() throws LauncherException {
        String clientId = requireClientId();
        OAuthLocalServer server = OAuthLocalServer.start();
        String challenge = server.codeChallengeS256();
        String url = AUTH_AUTHORIZE
                + "?client_id=" + urlEncode(clientId)
                + "&response_type=code"
                + "&redirect_uri=" + urlEncode(server.redirectUri())
                + "&scope=" + urlEncode(SCOPE)
                + "&prompt=select_account"
                + "&code_challenge=" + urlEncode(challenge)
                + "&code_challenge_method=S256"
                + "&state=" + urlEncode(server.state());
        return new PkceLogin(server, url);
    }

    public Session finishAuthorizationCodeLogin(PkceLogin pkce) throws LauncherException {
        if (pkce == null) {
            throw new LauncherException("PKCE 会话无效");
        }
        String code = pkce.server().waitForCode(900);
        String clientId = requireClientId();
        RequestBody body = new FormBody.Builder()
                .add("client_id", clientId)
                .add("code", code)
                .add("grant_type", "authorization_code")
                .add("code_verifier", pkce.server().codeVerifier())
                .add("redirect_uri", pkce.server().redirectUri())
                .add("scope", SCOPE)
                .build();
        Request request = new Request.Builder()
                .url(AUTH_TOKEN)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            JsonObject json = JsonUtil.parse(raw).getAsJsonObject();
            if (!response.isSuccessful()) {
                String error = json.has("error") ? json.get("error").getAsString() : ("HTTP " + response.code());
                throw new LauncherException("授权码换取令牌失败: " + error
                        + (json.has("error_description") ? " — " + json.get("error_description").getAsString() : ""));
            }
            String msAccess = json.get("access_token").getAsString();
            String refresh = json.has("refresh_token") ? json.get("refresh_token").getAsString() : "";
            return completeWithMicrosoftToken(msAccess, refresh);
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("授权码登录失败: " + e.getMessage(), e);
        }
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Poll until authorized, then complete Xbox/Minecraft login.
     */
    public PollResult pollAndLogin(DeviceCode device) throws LauncherException {
        String clientId = requireClientId();
        RequestBody body = new FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("client_id", clientId)
                .add("device_code", device.deviceCode())
                .build();
        Request request = new Request.Builder()
                .url("https://login.microsoftonline.com/consumers/oauth2/v2.0/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            JsonObject json = JsonUtil.parse(raw).getAsJsonObject();
            if (!response.isSuccessful()) {
                String error = json.has("error") ? json.get("error").getAsString() : ("HTTP " + response.code());
                if ("authorization_pending".equals(error)) {
                    return PollResult.pending();
                }
                if ("slow_down".equals(error)) {
                    return PollResult.needsSlowDown();
                }
                if ("expired_token".equals(error)) {
                    throw new LauncherException("设备代码已过期，请重新开始登录");
                }
                throw new LauncherException("微软授权失败: " + error
                        + (json.has("error_description") ? " — " + json.get("error_description").getAsString() : ""));
            }
            String msAccess = json.get("access_token").getAsString();
            String refresh = json.has("refresh_token") ? json.get("refresh_token").getAsString() : "";
            return PollResult.done(completeWithMicrosoftToken(msAccess, refresh));
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("轮询微软授权失败: " + e.getMessage(), e);
        }
    }

    public Session refreshSession() throws LauncherException {
        if (!Validation.isNonBlank(config.getMicrosoftRefreshToken())) {
            throw new LauncherException("没有可用于刷新的 refresh_token，请重新登录");
        }
        String clientId = requireClientId();
        FormBody.Builder form = new FormBody.Builder()
                .add("client_id", clientId)
                .add("refresh_token", config.getMicrosoftRefreshToken())
                .add("grant_type", "refresh_token")
                .add("scope", SCOPE);
        Request request = new Request.Builder()
                .url("https://login.microsoftonline.com/consumers/oauth2/v2.0/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(form.build())
                .build();
        try (Response response = client.newCall(request).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            JsonObject json = JsonUtil.parse(raw).getAsJsonObject();
            if (!response.isSuccessful()) {
                String error = json.has("error") ? json.get("error").getAsString() : ("HTTP " + response.code());
                if ("invalid_grant".equals(error)) {
                    logout();
                    throw new LauncherException("登录已失效（invalid_grant），请在设置中重新微软登录");
                }
                throw new LauncherException("刷新令牌失败: " + error
                        + (json.has("error_description") ? " — " + json.get("error_description").getAsString() : ""));
            }
            String msAccess = json.get("access_token").getAsString();
            String refresh = json.has("refresh_token")
                    ? json.get("refresh_token").getAsString()
                    : config.getMicrosoftRefreshToken();
            return completeWithMicrosoftToken(msAccess, refresh);
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("刷新令牌失败: " + e.getMessage(), e);
        }
    }

    public Session completeWithMicrosoftToken(String msAccessToken, String refreshToken)
            throws LauncherException {
        XboxTokens xbox = authenticateXbox(msAccessToken);
        McLogin mc = loginMinecraft(xbox.uhs(), xbox.xstsToken());
        ensureOwnsGame(mc.accessToken());
        Profile profile = fetchProfile(mc.accessToken());
        long ttlMs = Math.max(60L, mc.expiresInSec()) * 1000L;
        long expires = System.currentTimeMillis() + ttlMs;
        return new Session(mc.accessToken(), refreshToken, msAccessToken, xbox.uhs(),
                profile.name(), profile.uuid(), expires);
    }

    public void applyToConfig(Session session) {
        config.setAccountType("msa");
        config.setMinecraftAccessToken(session.minecraftAccessToken());
        config.setMicrosoftRefreshToken(session.refreshToken());
        config.setXboxUserHash(session.xboxUserHash());
        config.setTokenExpiresAt(session.expiresAtEpochMs());
        config.setPlayerName(session.playerName());
        config.setPlayerUuid(session.playerUuid());
        config.setOnlineModeEnabled(true);
    }

    public void logout() {
        config.setAccountType("offline");
        config.setMinecraftAccessToken("");
        config.setMicrosoftRefreshToken("");
        config.setXboxUserHash("");
        config.setTokenExpiresAt(0);
    }

    private record XboxTokens(String uhs, String xblToken, String xstsToken) {
    }

    private record Profile(String uuid, String name) {
    }

    private record McLogin(String accessToken, long expiresInSec) {
    }

    private XboxTokens authenticateXbox(String msAccessToken) throws LauncherException {
        JsonObject body = new JsonObject();
        JsonObject props = new JsonObject();
        props.addProperty("AuthMethod", "RPS");
        props.addProperty("SiteName", "user.auth.xboxlive.com");
        props.addProperty("RpsTicket", "d=" + msAccessToken);
        body.add("Properties", props);
        body.addProperty("RelyingParty", "http://auth.xboxlive.com");
        body.addProperty("TokenType", "JWT");

        JsonObject xbl = executeJson(new Request.Builder()
                .url("https://user.auth.xboxlive.com/user/authenticate")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        String xblToken = xbl.get("Token").getAsString();
        String uhs = xbl.getAsJsonObject("DisplayClaims").getAsJsonArray("xui")
                .get(0).getAsJsonObject().get("uhs").getAsString();

        JsonObject xstsBody = new JsonObject();
        JsonObject xstsProps = new JsonObject();
        xstsProps.addProperty("SandboxId", "RETAIL");
        JsonArray tokens = new JsonArray();
        tokens.add(xblToken);
        xstsProps.add("UserTokens", tokens);
        xstsBody.add("Properties", xstsProps);
        xstsBody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsBody.addProperty("TokenType", "JWT");

        JsonObject xsts = executeXsts(new Request.Builder()
                .url("https://xsts.auth.xboxlive.com/xsts/authorize")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(RequestBody.create(xstsBody.toString(), JSON))
                .build());
        String xstsToken = xsts.get("Token").getAsString();
        String xstsUhs = xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui")
                .get(0).getAsJsonObject().get("uhs").getAsString();
        if (!uhs.equals(xstsUhs)) {
            throw new LauncherException("XSTS 用户哈希不匹配，请重试登录");
        }
        return new XboxTokens(uhs, xblToken, xstsToken);
    }

    private McLogin loginMinecraft(String uhs, String xstsToken) throws LauncherException {
        JsonObject body = new JsonObject();
        body.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        JsonObject json = executeJson(new Request.Builder()
                .url("https://api.minecraftservices.com/authentication/login_with_xbox")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        String token = json.get("access_token").getAsString();
        long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 86400L;
        return new McLogin(token, expiresIn);
    }

    private void ensureOwnsGame(String mcToken) throws LauncherException {
        // HMCL: mcstore must return 200; item names vary by catalog revisions.
        Request request = new Request.Builder()
                .url("https://api.minecraftservices.com/entitlements/mcstore")
                .header("Authorization", "Bearer " + mcToken)
                .get()
                .build();
        JsonObject json = executeJson(request);
        if (!json.has("items") || !json.get("items").isJsonArray()) {
            throw new LauncherException("该微软账号未拥有 Minecraft Java 版（无 entitlements）");
        }
        JsonArray items = json.getAsJsonArray("items");
        if (items.isEmpty()) {
            throw new LauncherException("该微软账号未拥有 Minecraft Java 版");
        }
        boolean owns = false;
        for (var el : items) {
            if (!el.isJsonObject() || !el.getAsJsonObject().has("name")) {
                continue;
            }
            String name = el.getAsJsonObject().get("name").getAsString().toLowerCase();
            if (name.contains("minecraft")) {
                owns = true;
                break;
            }
        }
        if (!owns) {
            // Some accounts return opaque product ids; HTTP 200 + non-empty items is enough (HMCL).
            LOG.info("mcstore items present but no minecraft* name; treating as owned");
        }
    }

    private Profile fetchProfile(String mcToken) throws LauncherException {
        Request request = new Request.Builder()
                .url("https://api.minecraftservices.com/minecraft/profile")
                .header("Authorization", "Bearer " + mcToken)
                .get()
                .build();
        JsonObject json = executeJson(request);
        if (!json.has("id") || !json.has("name")) {
            throw new LauncherException("无法获取 Minecraft 档案（可能未创建档案）");
        }
        return new Profile(json.get("id").getAsString(), json.get("name").getAsString());
    }

    private String requireClientId() throws LauncherException {
        String id = config.getMicrosoftClientId();
        if (!Validation.isNonBlank(id)) {
            throw new LauncherException(
                    "请先在设置中填写已获 Mojang 审批的 Azure 应用程序 client_id（微软设备代码流登录需要）");
        }
        return id.trim();
    }

    private JsonObject executeXsts(Request request) throws LauncherException {
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String raw = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                String mapped = mapXstsError(raw);
                LOG.warn("XSTS HTTP {} body={}", response.code(), raw);
                throw new LauncherException(mapped != null ? mapped
                        : ("XSTS 认证失败 HTTP " + response.code()));
            }
            return JsonUtil.parse(raw).getAsJsonObject();
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("XSTS 认证失败: " + e.getMessage(), e);
        }
    }

    private static String mapXstsError(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonObject json = JsonUtil.parse(raw).getAsJsonObject();
            if (!json.has("XErr")) {
                return null;
            }
            long code = json.get("XErr").getAsLong();
            if (code == 2148916233L) {
                return "该微软账号未创建 Xbox 档案，请先在 xbox.com 注册";
            }
            if (code == 2148916235L) {
                return "该账号所在地区不可用 Xbox Live";
            }
            if (code == 2148916236L || code == 2148916237L) {
                return "该账号需要成人验证（家庭账户限制）";
            }
            if (code == 2148916238L) {
                return "该账号是儿童账户，需家长同意后才能登录";
            }
            return "Xbox 认证被拒绝（XErr=" + code + "）";
        } catch (Exception e) {
            return null;
        }
    }

    private JsonObject executeJson(Request request) throws LauncherException {
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String raw = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                LOG.warn("Auth HTTP {} body={}", response.code(), raw);
                throw new LauncherException("认证请求失败 HTTP " + response.code()
                        + (raw.isBlank() ? "" : ": " + raw.substring(0, Math.min(200, raw.length()))));
            }
            return JsonUtil.parse(raw).getAsJsonObject();
        } catch (LauncherException e) {
            throw e;
        } catch (Exception e) {
            throw new LauncherException("认证请求失败: " + e.getMessage(), e);
        }
    }
}
