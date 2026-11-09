package net.kdt.pojavlaunch;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ApplicationContextProbeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        setResultCode(Activity.RESULT_OK);
        setResultData(context.getPackageName());
    }
}
