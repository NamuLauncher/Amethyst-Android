package net.kdt.pojavlaunch.authenticator.microsoft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import com.google.gson.JsonSyntaxException;

import net.kdt.pojavlaunch.runtime.AccountAuthenticationException;
import net.kdt.pojavlaunch.runtime.AccountAuthenticationException.Reason;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketTimeoutException;

public final class AuthenticationResponsesTest {
    private static final String SECRET = "test-private-auth-value";

    @Test
    public void onlyKnownMicrosoftGrantErrorsRequireInteractiveLogin() {
        for (String error : new String[]{
                "invalid_grant",
                "interaction_required",
                "login_required",
                "consent_required"
        }) {
            AccountAuthenticationException failure = AuthenticationResponses.httpFailure(
                    400,
                    true,
                    false,
                    "{\"error\":\"" + error + "\",\"error_description\":\"" + SECRET + "\"}"
            );
            assertEquals(Reason.REAUTHENTICATION_REQUIRED, failure.reason);
            assertSafe(failure);
        }

        AccountAuthenticationException unknown = AuthenticationResponses.httpFailure(
                400,
                true,
                false,
                "{\"error\":\"invalid_client\",\"error_description\":\"invalid_grant " + SECRET + "\"}"
        );
        assertEquals(Reason.UNKNOWN, unknown.reason);
        assertSafe(unknown);
        assertEquals(
                Reason.UNKNOWN,
                AuthenticationResponses.httpFailure(
                        400,
                        false,
                        false,
                        "{\"error\":\"invalid_grant\"}"
                ).reason
        );
    }

    @Test
    public void responseStatusDistinguishesRetryableAndAccountRestrictedFailures() {
        assertReason(401, false, Reason.REAUTHENTICATION_REQUIRED);
        assertReason(401, true, Reason.ACCOUNT_RESTRICTED);
        assertReason(403, false, Reason.ACCOUNT_RESTRICTED);
        assertReason(429, false, Reason.RATE_LIMITED);
        assertReason(429, true, Reason.RATE_LIMITED);
        assertReason(500, false, Reason.SERVICE_UNAVAILABLE);
        assertReason(503, true, Reason.SERVICE_UNAVAILABLE);
        assertReason(404, false, Reason.UNKNOWN);

        for (String body : new String[]{
                "{\"XErr\":2148916233,\"Message\":\"" + SECRET + "\"}",
                "{\"XErr\":987654321,\"Message\":\"" + SECRET + "\"}",
                "malformed " + SECRET,
                null
        }) {
            AccountAuthenticationException failure = AuthenticationResponses.httpFailure(
                    401,
                    false,
                    true,
                    body
            );
            assertEquals(Reason.ACCOUNT_RESTRICTED, failure.reason);
            assertSafe(failure);
        }
    }

    @Test
    public void malformedOAuthResponseDoesNotLeakItsContentsOrBecomeReauthentication() {
        for (String body : new String[]{
                "{\"error\":\"" + SECRET,
                "{\"error\":{\"private\":\"" + SECRET + "\"}}",
                "{\"error\":123,\"error_description\":\"" + SECRET + "\"}",
                "{\"error\":null}",
                "{\"error\":\"\"}",
                "{}",
                null
        }) {
            AccountAuthenticationException failure = AuthenticationResponses.httpFailure(
                    400,
                    true,
                    false,
                    body
            );
            assertEquals(Reason.INVALID_RESPONSE, failure.reason);
            assertSafe(failure);
        }
    }

    @Test
    public void throwableConversionPreservesTypedReasonsAndDiscardsOriginalDetails() {
        AccountAuthenticationException typed = new AccountAuthenticationException(Reason.STORAGE);
        assertSame(typed, AccountAuthenticationException.from(typed));

        Throwable[] errors = {
                new IOException(SECRET),
                new SocketTimeoutException(SECRET),
                new JSONException(SECRET),
                new JsonSyntaxException(SECRET),
                new IllegalStateException(SECRET, new IOException(SECRET))
        };
        Reason[] expected = {
                Reason.NETWORK,
                Reason.NETWORK,
                Reason.INVALID_RESPONSE,
                Reason.INVALID_RESPONSE,
                Reason.UNKNOWN
        };
        for (int index = 0; index < errors.length; index++) {
            AccountAuthenticationException failure = AccountAuthenticationException.from(errors[index]);
            assertEquals(expected[index], failure.reason);
            assertSafe(failure);
        }
        for (Reason reason : Reason.values()) {
            assertSafe(new AccountAuthenticationException(reason));
        }
    }

    @Test
    public void suppliedTokenLifetimeAndAbsentLegacyLifetimeHaveDifferentExpiries() throws Exception {
        long now = 1_700_000_000_000L;
        assertEquals(
                now + 3_600_000,
                AuthenticationResponses.minecraftExpiry(new JSONObject("{\"expires_in\":3600}"), now)
        );
        assertEquals(
                now + 86_400_000,
                AuthenticationResponses.minecraftExpiry(new JSONObject("{}"), now)
        );
        assertEquals(
                Long.MAX_VALUE,
                AuthenticationResponses.minecraftExpiry(
                        new JSONObject("{\"expires_in\":1}"),
                        Long.MAX_VALUE - 1000
                )
        );
    }

    @Test
    public void invalidOrOverflowingLifetimesCannotCreateAnApparentlyValidToken() throws Exception {
        for (String value : new String[]{
                "0",
                "-1",
                "null",
                "\"3600\"",
                "1.5",
                "1.0",
                "true",
                "9223372036854775807"
        }) {
            JSONObject response = new JSONObject("{\"expires_in\":" + value + "}");
            AccountAuthenticationException failure = assertThrows(
                    AccountAuthenticationException.class,
                    () -> AuthenticationResponses.minecraftExpiry(response, 1_700_000_000_000L)
            );
            assertEquals(Reason.INVALID_RESPONSE, failure.reason);
            assertSafe(failure);
        }
        for (JSONObject response : new JSONObject[]{new JSONObject("{}"), new JSONObject("{\"expires_in\":1}")}) {
            AccountAuthenticationException failure = assertThrows(
                    AccountAuthenticationException.class,
                    () -> AuthenticationResponses.minecraftExpiry(response, Long.MAX_VALUE)
            );
            assertEquals(Reason.INVALID_RESPONSE, failure.reason);
            assertSafe(failure);
        }
    }

    private static void assertReason(int status, boolean xsts, Reason reason) {
        AccountAuthenticationException failure = AuthenticationResponses.httpFailure(
                status,
                false,
                xsts,
                SECRET
        );
        assertEquals(reason, failure.reason);
        assertSafe(failure);
    }

    private static void assertSafe(AccountAuthenticationException failure) {
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        assertFalse(text.toString()
                .contains(SECRET));
    }
}
