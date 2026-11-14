package jp.a09848281.securitycamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class StopReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("wsc", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("standbyActive", false).apply();

        try {
            Intent stop = new Intent(context, WebRtcSessionService.class);
            stop.setAction(WebRtcSessionService.ACTION_STOP_WAIT);
            context.startService(stop);
        } catch (Exception ignored) {}
    }
}
