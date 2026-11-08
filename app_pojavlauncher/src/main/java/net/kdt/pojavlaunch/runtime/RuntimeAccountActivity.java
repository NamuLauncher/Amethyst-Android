package net.kdt.pojavlaunch.runtime;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import net.kdt.pojavlaunch.fragments.MicrosoftLoginFragment;

/** 라이브러리의 계정 로그인 화면이며 호스트의 서비스 정책을 포함하지 않는다. */
public final class RuntimeAccountActivity extends AppCompatActivity {
    public static final String EXTRA_AUTH_REASON = "net.kdt.pojavlaunch.runtime.AUTH_REASON";
    private static final int CONTENT_ID = 0x4a010001;
    private static final String AUTH_STARTED = "amethyst.auth.started";
    private AccountLoginViewModel model;
    private FrameLayout page;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        float density = getResources()
                .getDisplayMetrics().density;
        int padding = (int) (24 * density);
        status.setPadding(padding, padding, padding, padding);
        status.setVisibility(View.GONE);
        content.addView(status);
        page = new FrameLayout(this);
        page.setId(CONTENT_ID);
        content.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(content);

        model = new ViewModelProvider(
                this,
                new AccountLoginViewModel.Factory(AmethystRuntime::startAccountLogin)
        )
                .get(AccountLoginViewModel.class);
        if (savedState != null) {
            model.restoreInterruptedLogin(savedState.getBoolean(AUTH_STARTED));
        }
        getOnBackPressedDispatcher()
                .addCallback(this, new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        model.cancel();
                    }
                });
        model.states()
                .observe(this, this::showState);
        FragmentManager fragments = getSupportFragmentManager();
        if (model.current().phase == AccountLoginViewModel.Phase.BROWSING
                && fragments.findFragmentById(CONTENT_ID) == null) {
            fragments.beginTransaction()
                    .replace(CONTENT_ID, new MicrosoftLoginFragment())
                    .commit();
        }
    }

    public void completeAuthorization(Uri uri) {
        model.authorize(getApplicationContext(), uri);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(AUTH_STARTED, model.current().phase != AccountLoginViewModel.Phase.BROWSING);
        super.onSaveInstanceState(outState);
    }

    private void showState(AccountLoginViewModel.State state) {
        if (state.phase == AccountLoginViewModel.Phase.BROWSING) {
            return;
        }
        page.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        // 인증 응답을 받은 페이지를 다시 복원하거나 같은 code로 재요청하지 않는다.
        FragmentManager fragments = getSupportFragmentManager();
        Fragment fragment = fragments.findFragmentById(CONTENT_ID);
        if (fragment != null && !fragments.isStateSaved()) {
            fragments.beginTransaction()
                    .remove(fragment)
                    .commit();
        }
        if (state.phase == AccountLoginViewModel.Phase.FINISHED) {
            setResult(resultCode(state), resultIntent(state));
            finish();
        } else if (state.phase == AccountLoginViewModel.Phase.CANCELLING) {
            status.setText("로그인 취소 처리 중입니다. 작업이 끝날 때까지 기다려 주세요.");
        } else if (state.phase == AccountLoginViewModel.Phase.COMMITTING) {
            status.setText("계정 정보를 저장하는 중입니다. 작업이 끝날 때까지 기다려 주세요.");
        } else {
            status.setText("Minecraft 계정을 확인하는 중입니다. 뒤로가기로 취소할 수 있습니다.");
        }
    }

    static int resultCode(AccountLoginViewModel.State state) {
        if (state.phase != AccountLoginViewModel.Phase.FINISHED) {
            throw new IllegalArgumentException("인증 작업이 아직 끝나지 않았습니다");
        }
        if (state.outcome == AccountLoginViewModel.Outcome.SUCCESS) {
            return RESULT_OK;
        }
        if (state.outcome == AccountLoginViewModel.Outcome.CANCELLED) {
            return RESULT_CANCELED;
        }
        return RESULT_FIRST_USER;
    }

    static Intent resultIntent(AccountLoginViewModel.State state) {
        Intent result = new Intent();
        if (state.outcome == AccountLoginViewModel.Outcome.FAILURE) {
            result.putExtra(EXTRA_AUTH_REASON, state.failureReason.name());
        }
        return result;
    }
}
