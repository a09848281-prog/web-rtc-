package jp.a09848281.securitycamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class StopReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("wsc", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("monitoring", false).apply();

        try {
            Intent mediaStop = new Intent(context, WebRtcSessionService.class);
            mediaStop.setAction(WebRtcSessionService.ACTION_STOP);
            context.startService(mediaStop);
        } catch (Exception ignored) {}

        context.stopService(new Intent(context, DeviceMonitorService.class));
    }
}