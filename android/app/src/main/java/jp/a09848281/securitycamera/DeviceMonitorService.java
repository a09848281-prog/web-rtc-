package jp.a09848281.securitycamera;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DeviceMonitorService extends Service {
    private static final String CHANNEL = "wsc_monitor";
    private static final int NOTIFICATION_ID = 41;
    private ScheduledExecutorService scheduler;
    private volatile int retrySeconds = 5;
    private volatile boolean thermalStopIssued = false;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Firebase不使用・自動接続待機中", false));
        scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulePoll(0);
    }

    private void schedulePoll(long delaySeconds) {
        if (scheduler == null || scheduler.isShutdown()) return;
        scheduler.schedule(this::poll, delaySeconds, TimeUnit.SECONDS);
    }

    private void poll() {
        long nextDelay = retrySeconds;
        try {
            SharedPreferences prefs = getSharedPreferences("wsc", MODE_PRIVATE);
            String url = prefs.getString("gasUrl", "");
            String id = prefs.getString("deviceId", "");
            String token = prefs.getString("deviceToken", "");
            if (url.isEmpty() || id.isEmpty() || token.isEmpty()) {
                update("端末未登録です。アプリを開いて6桁コードを登録してください。", true);
                retrySeconds = 10;
                nextDelay = retrySeconds;
                return;
            }

            JSONObject deviceStatus = collectStatus();
            JSONObject request = new JSONObject();
            request.put("deviceId", id);
            request.put("deviceToken", token);
            request.put("action", "pollCommand");
            request.put("status", deviceStatus);
            JSONObject response = MainActivity.post(url, request);
            checkBatteryWarning(deviceStatus);
            retrySeconds = 5;

            JSONObject command = response.optJSONObject("command");
            if (command != null) {
                String name = command.optString("command", "");
                JSONObject payload = command.optJSONObject("payload");
                if (payload == null) payload = new JSONObject();
                handleCommand(name, payload);
            }
            checkThermalSafety();
            checkSchedule(prefs);
            nextDelay = 5;
        } catch (SecurityException error) {
            retrySeconds = Math.min(30, Math.max(10, retrySeconds * 2));
            update("Androidの権限制限です。アプリを開いて必要な権限を確認してください。", true);
            nextDelay = retrySeconds;
        } catch (Exception error) {
            retrySeconds = Math.min(30, Math.max(10, retrySeconds * 2));
            update("GAS通信に失敗しました。自動再接続中（次回 " + retrySeconds + " 秒後）", false);
            nextDelay = retrySeconds;
        } finally {
            schedulePoll(nextDelay);
        }
    }

    private void handleCommand(String command, JSONObject payload) {
        try {
            if ("start_session".equals(command)) {
                startMediaFromCommand();
            } else if ("restart_session".equals(command)) {
                Intent stop = new Intent(this, WebRtcSessionService.class);
                stop.setAction(WebRtcSessionService.ACTION_STOP);
                startService(stop);
                if (scheduler != null && !scheduler.isShutdown()) {
                    scheduler.schedule(this::startMediaFromCommand, 3, TimeUnit.SECONDS);
                }
                update("再接続要求を受信しました。現在のセッションを終了して再起動します。", false);
            } else if ("stop_session".equals(command)) {
                Intent stop = new Intent(this, WebRtcSessionService.class);
                stop.setAction(WebRtcSessionService.ACTION_STOP);
                startService(stop);
                update("Webから停止要求を受信。カメラ・マイクを停止し、待機を継続します。", false);
            } else if ("switch_camera".equals(command) || "set_modes".equals(command)) {
                Intent control = new Intent(this, WebRtcSessionService.class);
                control.setAction(WebRtcSessionService.ACTION_COMMAND);
                control.putExtra("command", command);
                control.putExtra("payload", payload.toString());
                startService(control);
            } else if ("set_schedule".equals(command)) {
                getSharedPreferences("wsc", MODE_PRIVATE).edit()
                    .putString("schedule", payload.toString())
                    .apply();
                update("監視スケジュールを保存しました。Android OSの制限内で実行します。", false);
            } else if ("request_status".equals(command)) {
                update("端末状態を確認しました。バッテリー・通信・熱状態をWebへ送信しています。", false);
            }
        } catch (Exception error) {
            update("指令の実行に失敗しました: " + error.getMessage(), true);
        }
    }

    private void startMediaFromCommand() {
        Intent media = new Intent(this, WebRtcSessionService.class);
        media.setAction(WebRtcSessionService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(media);
            else startService(media);
            update("Webから接続要求を受信。カメラ・マイク起動を試みています。", false);
        } catch (Exception error) {
            update("Android OSがバックグラウンドでのカメラ・マイク起動を許可しませんでした。通知をタップしてアプリを開き、開始してください。", true);
        }
    }

    private JSONObject collectStatus() {
        JSONObject out = new JSONObject();
        try {
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) out.put("batteryPct", Math.round(level * 100f / scale));
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                out.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL);
                int temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                if (temp != Integer.MIN_VALUE) out.put("batteryTempC", temp / 10.0);
            }
        } catch (Exception ignored) {}

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                PowerManager power = getSystemService(PowerManager.class);
                if (power != null) {
                    int thermal = power.getCurrentThermalStatus();
                    String[] labels = {"NONE","LIGHT","MODERATE","SEVERE","CRITICAL","EMERGENCY","SHUTDOWN"};
                    out.put("thermalStatus", thermal >= 0 && thermal < labels.length ? labels[thermal] : "取得不可");
                }
            }
        } catch (Exception ignored) {}

        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network network = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = cm == null || network == null ? null : cm.getNetworkCapabilities(network);
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) out.put("networkType", "Wi-Fi");
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) out.put("networkType", "モバイル通信");
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) out.put("networkType", "Ethernet");
                else out.put("networkType", "その他");
            } else out.put("networkType", "オフライン");
        } catch (Exception ignored) {}

        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
            if (am != null) {
                am.getMemoryInfo(mem);
                out.put("memoryUsedMb", Math.max(0, (mem.totalMem - mem.availMem) / (1024 * 1024)));
                out.put("memoryTotalMb", mem.totalMem / (1024 * 1024));
            }
        } catch (Exception ignored) {}
        out.put("cpuLoadPct", JSONObject.NULL);
        try {
            SharedPreferences prefs = getSharedPreferences("wsc", MODE_PRIVATE);
            if (prefs.contains("videoWidth")) out.put("width", prefs.getInt("videoWidth", 0));
            if (prefs.contains("videoHeight")) out.put("height", prefs.getInt("videoHeight", 0));
            if (prefs.contains("videoFps")) out.put("fps", prefs.getInt("videoFps", 0));
        } catch (Exception ignored) {}
        return out;
    }

    private void checkBatteryWarning(JSONObject status) {
        try {
            int level = status.optInt("batteryPct", 100);
            SharedPreferences prefs = getSharedPreferences("wsc", MODE_PRIVATE);
            boolean warned = prefs.getBoolean("lowBatteryWarned", false);
            if (level <= 20 && !warned) {
                prefs.edit().putBoolean("lowBatteryWarned", true).apply();
                update("バッテリー残量が20%以下です。充電してください。", false);
            } else if (level > 22 && warned) {
                prefs.edit().putBoolean("lowBatteryWarned", false).apply();
            }
        } catch (Exception ignored) {}
    }

    private void checkThermalSafety() {
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            PowerManager power = getSystemService(PowerManager.class);
            if (power == null) return;
            int thermal = power.getCurrentThermalStatus();
            if (thermal >= PowerManager.THERMAL_STATUS_SEVERE && !thermalStopIssued) {
                thermalStopIssued = true;
                Intent stop = new Intent(this, WebRtcSessionService.class);
                stop.setAction(WebRtcSessionService.ACTION_STOP);
                startService(stop);
                update("高温を検知したためカメラ・マイクを安全停止しました。端末を冷ましてください。", true);
            } else if (thermal < PowerManager.THERMAL_STATUS_MODERATE) {
                thermalStopIssued = false;
            }
        } catch (Exception ignored) {}
    }

    private void checkSchedule(SharedPreferences prefs) {
        try {
            String raw = prefs.getString("schedule", "");
            if (raw.isEmpty()) return;
            JSONObject schedule = new JSONObject(raw);
            boolean enabled = schedule.optBoolean("enabled", false);
            if (!enabled) {
                if (prefs.getBoolean("scheduleActive", false)) {
                    prefs.edit().putBoolean("scheduleActive", false).apply();
                    Intent stop = new Intent(this, WebRtcSessionService.class);
                    stop.setAction(WebRtcSessionService.ACTION_STOP);
                    startService(stop);
                    update("監視スケジュール終了。カメラ・マイクを停止しました。", false);
                }
                return;
            }
            Calendar now = Calendar.getInstance();
            int day = now.get(Calendar.DAY_OF_WEEK);
            String days = schedule.optString("days", "everyday");
            if ("weekdays".equals(days) && (day == Calendar.SATURDAY || day == Calendar.SUNDAY)) return;
            if ("weekend".equals(days) && day != Calendar.SATURDAY && day != Calendar.SUNDAY) return;
            String start = schedule.optString("start", "00:00"), end = schedule.optString("end", "00:00");
            int current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            int from = parseMinutes(start), to = parseMinutes(end);
            boolean active = from < to ? current >= from && current < to : current >= from || current < to;
            int minutesToEnd = from < to ? to - current : (current >= from ? 1440 - current + to : to - current);
            if (active && minutesToEnd > 0 && minutesToEnd <= 5) {
                String warningKey = now.get(Calendar.YEAR) + "-" + now.get(Calendar.DAY_OF_YEAR) + "-" + end;
                if (!warningKey.equals(prefs.getString("scheduleWarningKey", ""))) {
                    prefs.edit().putString("scheduleWarningKey", warningKey).apply();
                    update("監視スケジュール終了まで約5分です。", false);
                }
            }
            boolean wasActive = prefs.getBoolean("scheduleActive", false);
            if (active && !wasActive) {
                prefs.edit().putBoolean("scheduleActive", true).apply();
                startMediaFromCommand();
            } else if (!active && wasActive) {
                prefs.edit().putBoolean("scheduleActive", false).apply();
                Intent stop = new Intent(this, WebRtcSessionService.class);
                stop.setAction(WebRtcSessionService.ACTION_STOP);
                startService(stop);
                update("監視スケジュールの終了時刻です。カメラ・マイクを停止しました。", false);
            }
        } catch (Exception ignored) {}
    }

    private int parseMinutes(String value) {
        String[] parts = value.split(":");
        if (parts.length != 2) return 0;
        return Math.max(0, Math.min(1439, Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1])));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL, "端末接続監視", NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text, boolean showStartAction) {
        Intent stopIntent = new Intent(this, StopReceiver.class);
        PendingIntent stopPending = PendingIntent.getBroadcast(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setAction(MainActivity.ACTION_REQUEST_MEDIA);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
            this, 51, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("WebRTC Security Camera")
            .setContentText(text)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_view),
                "アプリを開く", openPending
            ).build())
            .addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                "待機停止", stopPending
            ).build());
        if (showStartAction) builder.setContentIntent(openPending);
        return builder.build();
    }

    private void update(String text, boolean showStartAction) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text, showStartAction));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}