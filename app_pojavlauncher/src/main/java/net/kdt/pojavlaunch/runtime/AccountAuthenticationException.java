package net.kdt.pojavlaunch.runtime;

import com.google.gson.JsonParseException;

import org.json.JSONException;

import java.io.IOException;

/** 인증 실패를 원문 응답·토큰·원인 예외 없이 호스트에 전달한다. */
public final class AccountAuthenticationException extends IOException {
    public enum Reason {
        REAUTHENTICATION_REQUIRED,
        NETWORK,
        RATE_LIMITED,
        ACCOUNT_RESTRICTED,
        NO_GAME,
        PROFILE_REQUIRED,
        SERVICE_UNAVAILABLE,
        INVALID_RESPONSE,
        STORAGE,
        UNKNOWN
    }

    public final Reason reason;

    public AccountAuthenticationException(Reason reason) {
        super(message(reason), null);
        this.reason = reason;
    }

    /** 취소는 호출 측에서 먼저 처리하며, 알려지지 않은 오류의 원문은 전달하지 않는다. */
    public static AccountAuthenticationException from(Throwable failure) {
        if (failure instanceof AccountAuthenticationException) {
            return (AccountAuthenticationException) failure;
        }
        if (failure instanceof IOException) {
            return new AccountAuthenticationException(Reason.NETWORK);
        }
        if (failure instanceof JSONException || failure instanceof JsonParseException) {
            return new AccountAuthenticationException(Reason.INVALID_RESPONSE);
        }
        return new AccountAuthenticationException(Reason.UNKNOWN);
    }

    private static String message(Reason reason) {
        switch (reason) {
            case REAUTHENTICATION_REQUIRED:
                return "Microsoft 계정에 다시 로그인해 주세요";
            case NETWORK:
                return "인증 서버에 연결하지 못했습니다";
            case RATE_LIMITED:
                return "인증 요청이 많습니다. 잠시 후 다시 시도해 주세요";
            case ACCOUNT_RESTRICTED:
                return "계정의 Xbox 또는 Microsoft 이용 제한을 확인해 주세요";
            case NO_GAME:
                return "Minecraft Java Edition을 보유한 계정이 필요합니다";
            case PROFILE_REQUIRED:
                return "Minecraft 프로필을 먼저 설정해 주세요";
            case SERVICE_UNAVAILABLE:
                return "인증 서버를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요";
            case INVALID_RESPONSE:
                return "인증 서버 응답의 형식이 올바르지 않습니다";
            case STORAGE:
                return "계정 정보를 저장하거나 읽지 못했습니다";
            case UNKNOWN:
            default:
                return "계정 인증에 실패했습니다";
        }
    }
}
