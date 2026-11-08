package net.kdt.pojavlaunch.authenticator.microsoft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import net.kdt.pojavlaunch.runtime.AccountAuthenticationException;
import net.kdt.pojavlaunch.runtime.AccountAuthenticationException.Reason;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.Test;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MicrosoftBackgroundLoginTest {
    @Test
    public void completeExchangeReturnsAnUnsavedCandidateWithResponseExpiry() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            enqueueSuccess(server, "{\"access_token\":\"test-ms\",\"refresh_token\":\"test-refresh-new\"}");
            List<Integer> progress = new ArrayList<>();
            long before = System.currentTimeMillis();

            MinecraftAccount candidate = login(server, true).authenticate(progress::add);

            long after = System.currentTimeMillis();
            assertEquals(Arrays.asList(1, 2, 3, 4, 5), progress);
            assertEquals("TestPlayer", candidate.username);
            assertEquals("11111111-1111-1111-1111-111111111111", candidate.profileId);
            assertEquals("test-minecraft", candidate.accessToken);
            assertEquals("test-refresh-new", candidate.msaRefreshToken);
            assertTrue(candidate.isMicrosoft);
            assertTrue(candidate.expiresAt >= before + 7_200_000);
            assertTrue(candidate.expiresAt <= after + 7_200_000);
            assertEquals(5, server.getRequestCount());
            RecordedRequest token = server.takeRequest();
            assertEquals("/oauth20_token.srf", token.getPath());
            String tokenBody = token.getBody()
                    .readUtf8();
            assertTrue(tokenBody.contains("grant_type=refresh_token"));
            assertTrue(tokenBody.contains("refresh_token=test-credential"));
            server.takeRequest();
            server.takeRequest();
            server.takeRequest();
            RecordedRequest profile = server.takeRequest();
            assertEquals("Bearer test-minecraft", profile.getHeader("Authorization"));
        }
    }

    @Test
    public void successfulRefreshWithoutRotatedTokenPreservesTheExistingRefreshCredential() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            enqueueSuccess(server, "{\"access_token\":\"test-ms\"}");
            MinecraftAccount candidate = login(server, true).authenticate(null);
            assertEquals("test-credential", candidate.msaRefreshToken);
        }
    }

    @Test
    public void rejectedRefreshKeepsStructuredReasonWithoutResponseDetails() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(400)
                    .setBody("{\"error\":\"invalid_grant\",\"error_description\":\"test-private-value\"}"));
            AccountAuthenticationException failure = assertThrows(
                    AccountAuthenticationException.class,
                    () -> login(server, true).authenticate(null)
            );
            assertEquals(Reason.REAUTHENTICATION_REQUIRED, failure.reason);
            assertFalse(failure.toString().contains("test-private-value"));
            assertNull(failure.getCause());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    public void xstsRestrictionDoesNotBecomeReauthenticationOrLeakTheResponse() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("{\"access_token\":\"test-ms\",\"refresh_token\":\"test-refresh\"}"));
            server.enqueue(json("{\"Token\":\"test-xbox\"}"));
            server.enqueue(new MockResponse().setResponseCode(401)
                    .setBody("{\"XErr\":2148916238,\"Message\":\"test-private-value\"}"));
            AccountAuthenticationException failure = assertThrows(
                    AccountAuthenticationException.class,
                    () -> login(server, false).authenticate(null)
            );
            assertEquals(Reason.ACCOUNT_RESTRICTED, failure.reason);
            assertFalse(failure.toString().contains("test-private-value"));
            assertNull(failure.getCause());
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test
    public void malformedTokenAndRedirectFailWithoutFollowingOrExposingPayloads() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("{\"access_token\":\"test-private-value\",\"refresh_token\":"));
            AccountAuthenticationException malformed = assertThrows(
                    AccountAuthenticationException.class,
                    () -> login(server, false).authenticate(null)
            );
            assertEquals(Reason.INVALID_RESPONSE, malformed.reason);
            assertNull(malformed.getCause());
            assertFalse(malformed.toString().contains("test-private-value"));

            server.enqueue(new MockResponse().setResponseCode(302)
                    .addHeader("Location", server.url("/unexpected")));
            assertThrows(AccountAuthenticationException.class, () -> login(server, false).authenticate(null));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    public void missingProfileKeepsTheOwnershipDistinctionWithoutSavingADemoAccount() throws Exception {
        for (boolean owned : new boolean[]{false, true}) {
            try (MockWebServer server = new MockWebServer()) {
                enqueueTokens(server, "{\"access_token\":\"test-ms\",\"refresh_token\":\"test-refresh\"}");
                server.enqueue(new MockResponse().setResponseCode(404));
                String entitlements = owned ? "[\"product_minecraft\"]" : "[]";
                String payload = "{\"entitlements\":" + entitlements + "}";
                String encoded = java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
                server.enqueue(json("{\"signature\":\"header." + encoded + ".signature\"}"));
                if (owned) {
                    AccountAuthenticationException failure = assertThrows(
                            AccountAuthenticationException.class,
                            () -> login(server, false).authenticate(null)
                    );
                    assertEquals(Reason.PROFILE_REQUIRED, failure.reason);
                } else {
                    MinecraftAccount candidate = login(server, false).authenticate(null);
                    assertTrue(candidate.isDemo());
                }
                assertEquals(6, server.getRequestCount());
            }
        }
    }

    private static MicrosoftBackgroundLogin login(MockWebServer server, boolean refresh) {
        return new MicrosoftBackgroundLogin(refresh, "test-credential", url ->
                (HttpURLConnection) server.url(url.getPath())
                        .url()
                        .openConnection());
    }

    private static void enqueueSuccess(MockWebServer server, String tokenResponse) {
        enqueueTokens(server, tokenResponse);
        server.enqueue(json("{\"name\":\"TestPlayer\",\"id\":\"11111111111111111111111111111111\"}"));
    }

    private static void enqueueTokens(MockWebServer server, String tokenResponse) {
        server.enqueue(json(tokenResponse));
        server.enqueue(json("{\"Token\":\"test-xbox\"}"));
        server.enqueue(json("{\"Token\":\"test-xsts\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"test-user-hash\"}]}}"));
        server.enqueue(json("{\"access_token\":\"test-minecraft\",\"expires_in\":7200}"));
    }

    private static MockResponse json(String body) {
        return new MockResponse().setBody(body);
    }
}
