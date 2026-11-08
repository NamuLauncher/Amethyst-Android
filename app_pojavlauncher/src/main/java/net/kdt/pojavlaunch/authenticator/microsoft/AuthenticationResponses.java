package net.kdt.pojavlaunch.authenticator.microsoft;

import net.kdt.pojavlaunch.runtime.AccountAuthenticationException;
import net.kdt.pojavlaunch.runtime.AccountAuthenticationException.Reason;

import org.json.JSONException;
import org.json.JSONObject;

/** 응답의 알려진 상태만 판정하고 인증 원문을 예외에 포함하지 않는다. */
final class AuthenticationResponses {
    private static final long LEGACY_EXPIRY_SECONDS = 24L * 60 * 60;

    private AuthenticationResponses() {}

    static AccountAuthenticationException httpFailure(
            int status,
            boolean microsoftToken,
            boolean xsts,
            String responseBody
    ) {
        if (status == 429) {
            return new AccountAuthenticationException(Reason.RATE_LIMITED);
        }
        if (status >= 500) {
            return new AccountAuthenticationException(Reason.SERVICE_UNAVAILABLE);
        }
        if (status == 401) {
            if (xsts) {
                // XSTS의 이용 제한은 재로그인만으로 해결되지 않으며 XErr 원문은 필요하지 않다.
                return new AccountAuthenticationException(Reason.ACCOUNT_RESTRICTED);
            }
            return new AccountAuthenticationException(Reason.REAUTHENTICATION_REQUIRED);
        }
        if (status == 403) {
            return new AccountAuthenticationException(Reason.ACCOUNT_RESTRICTED);
        }
        if (status == 400 && microsoftToken) {
            return microsoftTokenFailure(responseBody);
        }
        return new AccountAuthenticationException(Reason.UNKNOWN);
    }

    private static AccountAuthenticationException microsoftTokenFailure(String responseBody) {
        if (responseBody == null) {
            return new AccountAuthenticationException(Reason.INVALID_RESPONSE);
        }
        try {
            Object error = new JSONObject(responseBody)
                    .opt("error");
            if (!(error instanceof String) || ((String) error).isEmpty()) {
                return new AccountAuthenticationException(Reason.INVALID_RESPONSE);
            }
            switch ((String) error) {
                case "invalid_grant":
                case "interaction_required":
                case "login_required":
                case "consent_required":
                    return new AccountAuthenticationException(Reason.REAUTHENTICATION_REQUIRED);
                default:
                    return new AccountAuthenticationException(Reason.UNKNOWN);
            }
        } catch (JSONException failure) {
            return new AccountAuthenticationException(Reason.INVALID_RESPONSE);
        }
    }

    static long minecraftExpiry(JSONObject response, long now) throws AccountAuthenticationException {
        if (response == null) {
            throw new AccountAuthenticationException(Reason.INVALID_RESPONSE);
        }
        long seconds = LEGACY_EXPIRY_SECONDS;
        if (response.has("expires_in")) {
            Object expiry = response.opt("expires_in");
            if (!(expiry instanceof Integer) && !(expiry instanceof Long)) {
                throw new AccountAuthenticationException(Reason.INVALID_RESPONSE);
            }
            seconds = ((Number) expiry).longValue();
            if (seconds <= 0) {
                throw new AccountAuthenticationException(Reason.INVALID_RESPONSE);
            }
        }

        try {
            // 필드가 생략된 응답만 기존 24시간 수명을 유지한다. 명시한 잘못된 값은 보정하지 않는다.
            return Math.addExact(now, Math.multiplyExact(seconds, 1000));
        } catch (ArithmeticException failure) {
            throw new AccountAuthenticationException(Reason.INVALID_RESPONSE);
        }
    }
}
