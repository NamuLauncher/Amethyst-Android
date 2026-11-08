package net.kdt.pojavlaunch.authenticator.microsoft;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.listener.DoneListener;
import net.kdt.pojavlaunch.authenticator.listener.ErrorListener;
import net.kdt.pojavlaunch.authenticator.listener.ProgressListener;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CancellationException;
import net.kdt.pojavlaunch.runtime.AccountAuthenticationException;
import net.kdt.pojavlaunch.runtime.AccountAuthenticationException.Reason;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Microsoft 인증 교환과 기존 비동기 로그인 호출을 제공한다. 저장은 교환 이후에 수행한다. */
public class MicrosoftBackgroundLogin {
    private static final String authTokenUrl = "https://login.live.com/oauth20_token.srf";
    private static final String xblAuthUrl = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String xstsAuthUrl = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String mcLoginUrl = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String mcProfileUrl = "https://api.minecraftservices.com/minecraft/profile";
    private static final String mcStoreUrl = "https://api.minecraftservices.com/entitlements/mcstore";

    private final boolean mIsRefresh;
    private final String mAuthCode;
    private String microsoftAccessToken;
    private volatile boolean cancelled;
    private Thread worker;
    private boolean committing;
    private boolean finished;
    private volatile HttpURLConnection activeConnection;
    private final ConnectionFactory connections;

    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    /* Fields used to fill the account  */
    public String msRefreshToken;
    public String mcName;
    public String mcToken;
    public String mcUuid;
    public boolean doesOwnGame, hasProfile = false;
    public long expiresAt;

    public MicrosoftBackgroundLogin(boolean isRefresh, String authCode) {
        this(isRefresh, authCode, url -> (HttpURLConnection) url.openConnection());
    }

    MicrosoftBackgroundLogin(boolean isRefresh, String authCode, ConnectionFactory connections) {
        mIsRefresh = isRefresh;
        mAuthCode = authCode;
        this.connections = connections;
    }

    /** Device Code로 받은 Microsoft 토큰에 기존 Xbox/Minecraft 교환을 적용한다. */
    public static MicrosoftBackgroundLogin fromMicrosoftTokens(MicrosoftDeviceCodeLogin.Tokens tokens) {
        MicrosoftBackgroundLogin login = new MicrosoftBackgroundLogin(false, "");
        login.microsoftAccessToken = tokens.accessToken;
        login.msRefreshToken = tokens.refreshToken;
        return login;
    }

    /** 기존 void API는 best-effort 취소다. 저장이 시작되면 중단하지 않는다. */
    public synchronized void cancel() {
        if (committing && !finished) {
            return;
        }
        cancelled = true;
        if (worker != null) {
            worker.interrupt();
        }
    }

    /** 기존 호출자의 저장 완료 후 메인 스레드 callback 계약을 유지한다. */
    public void performLogin(
            @Nullable final ProgressListener progressListener,
            @Nullable final DoneListener doneListener,
            @Nullable final ErrorListener errorListener
    ) {
        sExecutorService.execute(() -> {
            synchronized (this) {
                worker = Thread.currentThread();
            }
            MinecraftAccount account = null;
            AccountAuthenticationException failure = null;
            try {
                account = authenticate(step -> notifyProgress(progressListener, step));
                MinecraftAccount previous = MinecraftAccount.load(account.username);
                if (previous != null) {
                    account.selectedVersion = previous.selectedVersion;
                }
                // 새 runtime 경로에는 얼굴 캐시의 네트워크·파일 부수 효과를 포함하지 않는다.
                account.updateSkinFace();
                synchronized (this) {
                    checkCancelled();
                    committing = true;
                }
                try {
                    account.save();
                } catch (IOException storageFailure) {
                    throw new AccountAuthenticationException(Reason.STORAGE);
                }
            } catch (CancellationException ignored) {
                cancelled = true;
            } catch (Exception error) {
                failure = AccountAuthenticationException.from(error);
            } finally {
                ProgressLayout.clearProgress(ProgressLayout.AUTHENTICATE_MICROSOFT);
                synchronized (this) {
                    Thread.interrupted();
                    worker = null;
                    finished = true;
                }
            }
            MinecraftAccount completed = account;
            AccountAuthenticationException error = failure;
            Tools.runOnUiThread(() -> {
                if (cancelled) {
                    return;
                }
                if (error != null) {
                    if (errorListener != null) {
                        errorListener.onLoginError(legacyFailure(error));
                    }
                } else if (doneListener != null) {
                    doneListener.onLoginDone(completed);
                }
            });
        });
    }

    /** 라이브러리 내부 조합용 동기 교환이다. 후보만 반환하며 계정·선택·스킨을 저장하지 않는다. */
    public MinecraftAccount authenticate(@Nullable ProgressListener progress) throws AccountAuthenticationException {
        try {
            checkCancelled();
            progress(progress, 1);
            String accessToken = microsoftAccessToken;
            if (accessToken == null) {
                accessToken = acquireAccessToken(mIsRefresh, mAuthCode);
            }
            checkCancelled();
            progress(progress, 2);
            String xboxLiveToken = acquireXBLToken(accessToken);
            checkCancelled();
            progress(progress, 3);
            String[] xsts = acquireXsts(xboxLiveToken);
            checkCancelled();
            progress(progress, 4);
            String minecraftToken = acquireMinecraftToken(xsts[0], xsts[1]);
            checkCancelled();
            progress(progress, 5);
            checkMcProfile(minecraftToken);
            checkCancelled();
            fetchOwnedItems(minecraftToken);
            checkCancelled();
            if (!hasProfile && doesOwnGame) {
                throw new AccountAuthenticationException(Reason.PROFILE_REQUIRED);
            } else if (!doesOwnGame) {
                mcName = "Demo.Player";
                mcUuid = "00000000-0000-0000-0000-000000000000";
            } else if (mcName == null || mcUuid == null) {
                throw new AccountAuthenticationException(Reason.INVALID_RESPONSE);
            }
            MinecraftAccount account = new MinecraftAccount();
            account.xuid = xsts[0];
            account.clientToken = "0";
            account.accessToken = minecraftToken;
            account.username = mcName;
            account.profileId = mcUuid;
            account.isMicrosoft = true;
            account.msaRefreshToken = msRefreshToken;
            account.expiresAt = expiresAt;
            return account;
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            checkCancelled();
            throw AccountAuthenticationException.from(failure);
        } finally {
            HttpURLConnection connection = activeConnection;
            if (connection != null) {
                connection.disconnect();
                activeConnection = null;
            }
        }
    }

    private void checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("계정 인증이 취소됐습니다");
        }
    }

    private static void progress(ProgressListener listener, int step) {
        if (listener != null) {
            listener.onLoginProgress(step);
        }
    }

    private static Throwable legacyFailure(AccountAuthenticationException failure) {
        if (failure.reason == Reason.RATE_LIMITED) {
            return new PresentedException(R.string.microsoft_login_retry_later);
        }
        if (failure.reason == Reason.PROFILE_REQUIRED) {
            return new PresentedException(R.string.minecraft_no_username_set);
        }
        return failure;
    }

    private HttpURLConnection openConnection(URL url) throws IOException {
        checkCancelled();
        HttpURLConnection connection = connections.open(url);
        connection.setInstanceFollowRedirects(false);
        activeConnection = connection;
        return connection;
    }

    public String acquireAccessToken(boolean isRefresh, String authcode) throws IOException, JSONException {
        URL url = new URL(authTokenUrl);

        String formData = convertToFormData(
                "client_id", MicrosoftDeviceCodeLogin.CLIENT_ID,
                isRefresh ? "refresh_token" : "code", authcode,
                "grant_type", isRefresh ? "refresh_token" : "authorization_code",
                "redirect_url", "https://login.live.com/oauth20_desktop.srf",
                "scope", MicrosoftDeviceCodeLogin.SCOPE
        );

        HttpURLConnection conn = openConnection(url);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("charset", "utf-8");
        conn.setRequestProperty("Content-Length", Integer.toString(formData.getBytes(StandardCharsets.UTF_8).length));
        conn.setRequestMethod("POST");
        conn.setUseCaches(false);
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.connect();
        try(OutputStream wr = conn.getOutputStream()) {
            wr.write(formData.getBytes(StandardCharsets.UTF_8));
        }
        if(conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
            JSONObject jo = new JSONObject(readResponse(conn.getInputStream()));
            if (jo.has("refresh_token")) {
                msRefreshToken = requiredString(jo, "refresh_token");
            } else if (isRefresh) {
                msRefreshToken = authcode;
            } else {
                throw new AccountAuthenticationException(Reason.INVALID_RESPONSE);
            }
            conn.disconnect();
            return requiredString(jo, "access_token");
            //acquireXBLToken(requiredString(jo, "access_token"));
        }else{
            throw getResponseThrowable(conn, true, false);
        }
    }

    private String acquireXBLToken(String accessToken) throws IOException, JSONException {
        URL url = new URL(xblAuthUrl);

        JSONObject data = new JSONObject();
        JSONObject properties = new JSONObject();
        properties.put("AuthMethod", "RPS");
        properties.put("SiteName", "user.auth.xboxlive.com");
        properties.put("RpsTicket", accessToken);
        data.put("Properties",properties);
        data.put("RelyingParty", "http://auth.xboxlive.com");
        data.put("TokenType", "JWT");

        String req = data.toString();
        HttpURLConnection conn = openConnection(url);
        setCommonProperties(conn, req);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.connect();

        try(OutputStream wr = conn.getOutputStream()) {
            wr.write(req.getBytes(StandardCharsets.UTF_8));
        }
        if(conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
            JSONObject jo = new JSONObject(readResponse(conn.getInputStream()));
            conn.disconnect();
            return requiredString(jo, "Token");
            //acquireXsts(requiredString(jo, "Token"));
        }else{
            throw getResponseThrowable(conn);
        }
    }

    /** @return [uhs, token]*/
    private @NonNull String[] acquireXsts(String xblToken) throws IOException, JSONException {
        URL url = new URL(xstsAuthUrl);

        JSONObject data = new JSONObject();
        JSONObject properties = new JSONObject();
        properties.put("SandboxId", "RETAIL");
        properties.put("UserTokens", new JSONArray(Collections.singleton(xblToken)));
        data.put("Properties", properties);
        data.put("RelyingParty", "rp://api.minecraftservices.com/");
        data.put("TokenType", "JWT");

        String req = data.toString();
        HttpURLConnection conn = openConnection(url);
        setCommonProperties(conn, req);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.connect();

        try(OutputStream wr = conn.getOutputStream()) {
            wr.write(req.getBytes(StandardCharsets.UTF_8));
        }

        if(conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
            JSONObject jo = new JSONObject(readResponse(conn.getInputStream()));
            String uhs = jo.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs");
            String token = requiredString(jo, "Token");
            conn.disconnect();
            return new String[]{uhs, token};
            //acquireMinecraftToken(uhs,requiredString(jo, "Token"));
        } else {
            throw getResponseThrowable(conn, false, true);
        }
    }

    private String acquireMinecraftToken(String xblUhs, String xblXsts) throws IOException, JSONException {
        URL url = new URL(mcLoginUrl);

        JSONObject data = new JSONObject();
        data.put("identityToken", "XBL3.0 x=" + xblUhs + ";" + xblXsts);

        String req = data.toString();
        HttpURLConnection conn = openConnection(url);
        setCommonProperties(conn, req);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.connect();

        try(OutputStream wr = conn.getOutputStream()) {
            wr.write(req.getBytes(StandardCharsets.UTF_8));
        }

        if(conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
            JSONObject jo = new JSONObject(readResponse(conn.getInputStream()));
            expiresAt = AuthenticationResponses.minecraftExpiry(jo, System.currentTimeMillis());
            conn.disconnect();
            mcToken = requiredString(jo, "access_token");
            //checkMcProfile(requiredString(jo, "access_token"));
            return requiredString(jo, "access_token");
        }else{
            throw getResponseThrowable(conn);
        }
    }

    private void fetchOwnedItems(String mcAccessToken) throws IOException {
        // We only need to do this if user does not have a profile/username yet
        if (hasProfile) {
            return;
        }
        URL url = new URL(mcStoreUrl);
        String s = "";

        // For some reason, minecraftservices APIs are significantly more unreliable
        // Automatically retry because the user gets annoyed when they have to log in again
        for (int retryCount = 0; retryCount < 5; ++retryCount) {
            checkCancelled();
            HttpURLConnection conn = openConnection(url);
            conn.setRequestProperty("Authorization", "Bearer " + mcAccessToken);
            conn.setUseCaches(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.connect();
            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
                s = readResponse(conn.getInputStream());
                conn.disconnect();
                break;
            } else if (conn.getResponseCode() == 401 || conn.getResponseCode() == 403
                    || conn.getResponseCode() == 429 || retryCount == 4) {
                throw getResponseThrowable(conn);
            }
            conn.disconnect();
            try { Thread.sleep(500L * (1L << retryCount)); // 0.5s, 1s, 2s, 4s, 8s
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException();
            }
        }
        try {
            String jwtSignature = requiredString(new JSONObject(s), "signature");
            String[] parts = jwtSignature.split("\\.");
            if (parts.length < 2) {
                throw new AccountAuthenticationException(Reason.INVALID_RESPONSE);
            }
            String jwtBody = parts[1];

            String signature = new String(
                    java.util.Base64.getUrlDecoder()
                            .decode(jwtBody),
                    StandardCharsets.UTF_8
            );
            JSONObject jsonSignature = new JSONObject(signature);
            JSONArray entitlements = jsonSignature.getJSONArray("entitlements");
            for (int i = 0; i < entitlements.length(); ++i) {
                switch (entitlements.getString(i)) {
                    // These four are guaranteed to always be present because Java & Bedrock are 1 pack
                    case "product_minecraft":
                    case "game_minecraft":
                    case "product_minecraft_bedrock":
                    case "game_minecraft_bedrock":
                        doesOwnGame = true;
                        break;
                    case "product_game_pass_pc":
                    case "product_game_pass_ultimate":
                        // TODO: Implement gamepass detection
                        break;
                    // idk, pad the LoC or sm
                    case "product_dungeons":
                    case "game_dungeons":
                    case "product_legends":
                    case "game_legends":
                    default:
                        break;
                }
            }
        }
        catch (JSONException | IllegalArgumentException e){
            throw new AccountAuthenticationException(Reason.INVALID_RESPONSE);
        }

    }

    private void checkMcProfile(String mcAccessToken) throws IOException, JSONException {
        URL url = new URL(mcProfileUrl);

        // For some reason, minecraftservices APIs are significantly more unreliable
        // Automatically retry because the user gets annoyed when they have to log in again
        for (int retryCount = 0; retryCount < 5; ++retryCount) {
            checkCancelled();
            HttpURLConnection conn = openConnection(url);
            conn.setRequestProperty("Authorization", "Bearer " + mcAccessToken);
            conn.setUseCaches(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.connect();

            if(conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
                String s= readResponse(conn.getInputStream());
                conn.disconnect();
                JSONObject jsonObject = new JSONObject(s);
                String name = requiredString(jsonObject, "name");
                String uuid = requiredString(jsonObject, "id");
                if (!name.matches("[A-Za-z0-9_]{3,16}") || !uuid.matches("[0-9A-Fa-f]{32}")) {
                    throw new AccountAuthenticationException(Reason.INVALID_RESPONSE);
                }
                String uuidDashes = uuid.replaceFirst(
                        "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5"
                );
                doesOwnGame = true;
                hasProfile = true;
                mcName = name;
                mcUuid = uuidDashes;
                break;
            } else if (conn.getResponseCode() == 404){
                conn.disconnect();
                hasProfile = false;
                break;
            } else if (conn.getResponseCode() == 401 || conn.getResponseCode() == 403
                    || conn.getResponseCode() == 429 || retryCount == 4) {
                throw getResponseThrowable(conn);
            }
            conn.disconnect();
            try { Thread.sleep(500L * (1L << retryCount)); // 0.5s, 1s, 2s, 4s, 8s
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException();
            }
        }
    }

    /** Wrapper to ease notifying the listener */
    private void notifyProgress(@Nullable ProgressListener listener, int step){
        if(listener != null){
            Tools.runOnUiThread(() -> listener.onLoginProgress(step));
        }
        ProgressLayout.setProgress(ProgressLayout.AUTHENTICATE_MICROSOFT, step*20);
    }


    /** Set common properties for the connection. Given that all requests are POST, interactivity is always enabled */
    private static void setCommonProperties(HttpURLConnection conn, String formData) {
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("charset", "utf-8");
        try {
            conn.setRequestProperty("Content-Length", Integer.toString(formData.getBytes(StandardCharsets.UTF_8).length));
            conn.setRequestMethod("POST");
        } catch (ProtocolException failure) {
            throw new IllegalStateException("인증 요청을 구성하지 못했습니다");
        }
        conn.setUseCaches(false);
        conn.setDoInput(true);
        conn.setDoOutput(true);
    }

    /**
     * @param data A series a strings: key1, value1, key2, value2...
     * @return the data converted as a form string for a POST request
     */
    private static String convertToFormData(String... data) throws UnsupportedEncodingException {
        StringBuilder builder = new StringBuilder();
        for(int i=0; i<data.length; i+=2){
            if (builder.length() > 0) builder.append("&");
            builder.append(URLEncoder.encode(data[i], "UTF-8"))
                    .append("=")
                    .append(URLEncoder.encode(data[i+1], "UTF-8"));
        }
        return builder.toString();
    }

    private static String readResponse(InputStream input) throws IOException {
        try (InputStream response = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = response.read(buffer)) != -1) {
                if (bytes.size() + count > 65536) {
                    throw new AccountAuthenticationException(Reason.INVALID_RESPONSE);
                }
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String requiredString(JSONObject object, String key) throws JSONException, AccountAuthenticationException {
        Object value = object.get(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new AccountAuthenticationException(Reason.INVALID_RESPONSE);
        }
        return (String) value;
    }

    private AccountAuthenticationException getResponseThrowable(HttpURLConnection connection) throws IOException {
        return getResponseThrowable(connection, false, false);
    }

    private AccountAuthenticationException getResponseThrowable(
            HttpURLConnection connection,
            boolean microsoftToken,
            boolean xsts
    ) throws IOException {
        try {
            int status = connection.getResponseCode();
            String body = "";
            InputStream input = connection.getErrorStream();
            if (input != null) {
                body = readResponse(input);
            }
            return AuthenticationResponses.httpFailure(status, microsoftToken, xsts, body);
        } finally {
            connection.disconnect();
        }
    }
}
