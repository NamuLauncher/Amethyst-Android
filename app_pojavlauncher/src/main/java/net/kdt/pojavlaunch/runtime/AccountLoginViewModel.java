package net.kdt.pojavlaunch.runtime;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import java.util.List;
import java.util.concurrent.CancellationException;

/** 인증 요청은 회전 중에도 유지하고 Activity나 인증 코드는 화면 상태에 보관하지 않는다. */
final class AccountLoginViewModel extends ViewModel {
    enum Phase {
        BROWSING,
        AUTHENTICATING,
        CANCELLING,
        COMMITTING,
        FINISHED
    }

    enum Outcome {
        SUCCESS, CANCELLED, FAILURE
    }

    static final class State {
        final Phase phase;
        final Outcome outcome;
        final AccountAuthenticationException.Reason failureReason;

        State(Phase phase, Outcome outcome, AccountAuthenticationException.Reason failureReason) {
            this.phase = phase;
            this.outcome = outcome;
            this.failureReason = failureReason;
        }
    }

    interface Starter {
        AmethystRuntime.AuthenticationRequest start(
                Context context,
                String code,
                AmethystRuntime.AuthenticationListener listener
        );
    }

    static final class Factory implements ViewModelProvider.Factory {
        private final Starter starter;

        Factory(Starter starter) {
            this.starter = starter;
        }

        @NonNull
        @Override
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            if (modelClass != AccountLoginViewModel.class) {
                throw new IllegalArgumentException("지원하지 않는 계정 화면 모델입니다");
            }
            return modelClass.cast(new AccountLoginViewModel(starter));
        }
    }

    private final Starter starter;
    private final MutableLiveData<State> state = new MutableLiveData<>(new State(Phase.BROWSING, null, null));
    private AmethystRuntime.AuthenticationRequest request;
    private boolean cleared;

    AccountLoginViewModel(Starter starter) {
        this.starter = starter;
    }

    LiveData<State> states() {
        return state;
    }

    State current() {
        return state.getValue();
    }

    void restoreInterruptedLogin(boolean hadStarted) {
        // 프로세스가 사라진 뒤 인증 코드를 복원하거나 로그인을 자동 재시작하지 않는다.
        if (hadStarted && current().phase == Phase.BROWSING) {
            finish(Outcome.CANCELLED, null);
        }
    }

    void authorize(Context context, Uri uri) {
        if (cleared || current().phase != Phase.BROWSING) {
            return;
        }
        String code;
        try {
            if (uri == null || !"ms-xal-00000000402b5328".equals(uri.getScheme())) {
                throw new IllegalArgumentException();
            }
            if ("access_denied".equals(uri.getQueryParameter("error"))) {
                finish(Outcome.CANCELLED, null);
                return;
            }
            List<String> codes = uri.getQueryParameters("code");
            if (codes.size() != 1) {
                throw new IllegalArgumentException();
            }
            code = codes.get(0);
            if (code.isEmpty()) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | UnsupportedOperationException invalidResponse) {
            finish(Outcome.FAILURE, AccountAuthenticationException.Reason.INVALID_RESPONSE);
            return;
        }

        publish(Phase.AUTHENTICATING);
        try {
            AmethystRuntime.AuthenticationRequest started = starter.start(
                    context.getApplicationContext(),
                    code,
                    new AmethystRuntime.AuthenticationListener() {
                        @Override
                        public void onReady() {
                            finish(Outcome.SUCCESS, null);
                        }

                        @Override
                        public void onFailure(Throwable failure) {
                            finish(Outcome.FAILURE, AccountAuthenticationException.from(failure).reason);
                        }

                        @Override
                        public void onCancelled() {
                            finish(Outcome.CANCELLED, null);
                        }
                    }
            );
            if (cleared) {
                started.cancel();
            } else if (current().phase != Phase.FINISHED) {
                request = started;
                if (current().phase == Phase.CANCELLING) {
                    cancelRequest();
                }
            }
        } catch (CancellationException cancelled) {
            finish(Outcome.CANCELLED, null);
        } catch (Exception failure) {
            finish(Outcome.FAILURE, AccountAuthenticationException.from(failure).reason);
        }
    }

    void cancel() {
        if (cleared) {
            return;
        }
        if (current().phase == Phase.BROWSING) {
            finish(Outcome.CANCELLED, null);
        } else if (current().phase == Phase.AUTHENTICATING) {
            publish(Phase.CANCELLING);
            cancelRequest();
        }
    }

    private void cancelRequest() {
        if (request != null) {
            boolean accepted = request.cancel();
            if (!accepted && current().phase != Phase.FINISHED) {
                publish(Phase.COMMITTING);
            }
        }
    }

    private void publish(Phase phase) {
        state.setValue(new State(phase, null, null));
    }

    private void finish(Outcome outcome, AccountAuthenticationException.Reason reason) {
        if (cleared || current().phase == Phase.FINISHED) {
            return;
        }
        request = null;
        state.setValue(new State(Phase.FINISHED, outcome, reason));
    }

    @Override
    protected void onCleared() {
        cleared = true;
        AmethystRuntime.AuthenticationRequest active = request;
        request = null;
        if (active != null) {
            active.cancel();
        }
    }
}
