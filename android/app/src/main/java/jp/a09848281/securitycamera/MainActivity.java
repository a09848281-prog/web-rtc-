package jp.a09848281.securitycamera;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    public static final String ACTION_REQUEST_MEDIA = "jp.a09848281.securitycamera.REQUEST_MEDIA";
    private static final int PERMISSION_REQUEST = 21;
    private static final String GAS_URL = "https://script.google.com/macros/s/AKfycbwokMuKlrwbetUzGj_OiSXXVC_QmLzpZtZj68NB3CLolkWsGz_Rf538ulSye164Rbz6/exec";
    private static final int BG = Color.rgb(10, 16, 29);
    private static final int CARD = Color.rgb(20, 29, 47);
    private static final int TEXT = Color.rgb(239, 244, 255);
    private static final int MUTED = Color.rgb(157, 172, 194);
    private static final int ACCENT = Color.rgb(65, 211, 163);

    private EditText pairCode, deviceName;
    private TextView status, statusDot;
    private Button pairButton;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("wsc", MODE_PRIVATE);
        // Always use the configured endpoint so users never need to type the GAS URL.
        prefs.edit().putString("gasUrl", GAS_URL).apply();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root);

        TextView eyebrow = text("WEBRTC  /  ANDROID DEVICE", 11, MUTED, true);
        eyebrow.setLetterSpacing(0.12f);
        root.addView(eyebrow, params(WRAP, dp(22), 0, 0, 0, 8));

        TextView title = text("Security Camera", 29, TEXT, true);
        root.addView(title, params(WRAP, WRAP, 0, 0, 0, 4));
        TextView subtitle = text("Android端末をカメラとして接続します", 13, MUTED, false);
        root.addView(subtitle, params(WRAP, WRAP, 0, 0, 0, 22));

        LinearLayout statusCard = card();
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = text("●", 17, prefs.contains("deviceToken") ? ACCENT : Color.rgb(255, 190, 90), true);
        statusCard.addView(statusDot, params(dp(28), WRAP, 0, 0, 0, 0));
        LinearLayout statusTexts = new LinearLayout(this);
        statusTexts.setOrientation(LinearLayout.VERTICAL);
        TextView statusTitle = text("端末ステータス", 12, MUTED, true);
        statusTexts.addView(statusTitle);
        status = text(initialStatus(), 15, TEXT, true);
        statusTexts.addView(status, params(WRAP, WRAP, 0, 4, 0, 0));
        statusCard.addView(statusTexts, new LinearLayout.LayoutParams(0, WRAP, 1));
        root.addView(statusCard, params(MATCH, WRAP, 0, 0, 0, 20));

        sectionTitle(root, "01", "端末登録");
        LinearLayout pairing = card();
        pairing.setOrientation(LinearLayout.VERTICAL);
        TextView hint = text("iPadの管理画面に表示された6桁コードを入力してください。", 13, MUTED, false);
        pairing.addView(hint, params(MATCH, WRAP, 0, 0, 0, 14));
        deviceName = field("端末名");
        deviceName.setText(prefs.getString("deviceName", Build.MODEL));
        pairing.addView(deviceName, params(MATCH, dp(50), 0, 0, 0, 10));
        pairCode = field("6桁のペアリングコード");
        pairCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        pairCode.setLetterSpacing(0.18f);
        pairing.addView(pairCode, params(MATCH, dp(54), 0, 0, 0, 14));
        pairButton = button("この端末を登録", ACCENT, BG);
        pairing.addView(pairButton, params(MATCH, dp(52), 0, 0, 0, 0));
        root.addView(pairing, params(MATCH, WRAP, 0, 0, 0, 22));

        sectionTitle(root, "02", "接続コントロール");
        LinearLayout controls = card();
        controls.setOrientation(LinearLayout.VERTICAL);
        Button monitor = button("接続監視を開始", Color.rgb(43, 93, 142), TEXT);
        controls.addView(monitor, params(MATCH, dp(50), 0, 0, 0, 10));
        Button media = button("カメラ・マイクを開始", ACCENT, BG);
        controls.addView(media, params(MATCH, dp(50), 0, 0, 0, 10));
        Button stop = button("映像・音声セッションを停止", Color.rgb(74, 45, 58), Color.rgb(255, 190, 198));
        controls.addView(stop, params(MATCH, dp(50), 0, 0, 0, 0));
        root.addView(controls, params(MATCH, WRAP, 0, 0, 0, 20));

        LinearLayout info = card();
        info.setOrientation(LinearLayout.VERTICAL);
        info.addView(text("プライバシーと動作について", 14, TEXT, true));
        info.addView(text("カメラ・マイクは、アプリ画面またはAndroidの通知から利用者が開始します。開始後は継続通知が表示されます。iPadからの要求だけで、無人のまま撮影・録音を開始する動作には対応しません。", 12, MUTED, false),
            params(MATCH, WRAP, 0, 8, 0, 0));
        root.addView(info, params(MATCH, WRAP, 0, 0, 0, 8));

        setContentView(scroll);
        pairButton.setOnClickListener(v -> pairDevice());
        monitor.setOnClickListener(v -> startMonitor());
        media.setOnClickListener(v -> requestMediaPermissions());
        stop.setOnClickListener(v -> stopMedia());

        if (ACTION_REQUEST_MEDIA.equals(getIntent().getAction())) {
            root.post(this::requestMediaPermissions);
        }
    }

    private String initialStatus() {
        String error = prefs.getString("lastMediaError", "");
        if (!error.isEmpty()) return "前回の起動エラー: " + error;
        return prefs.contains("deviceToken") ? "登録済み・待機中" : "未登録";
    }

    private void sectionTitle(LinearLayout root, String number, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = text(number, 11, ACCENT, true);
        row.addView(n, params(dp(34), WRAP, 0, 0, 0, 0));
        row.addView(text(label, 17, TEXT, true));
        root.addView(row, params(MATCH, dp(34), 0, 0, 0, 8));
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.setBackground(shape(CARD, 18));
        return box;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextSize(15);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setHint(hint);
        e.setPadding(dp(14), 0, dp(14), 0);
        e.setBackground(shape(Color.rgb(11, 19, 33), 12));
        return e;
    }

    private Button button(String label, int background, int foreground) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setTextColor(foreground);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(shape(background, 13));
        b.setElevation(dp(1));
        return b;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private LinearLayout.LayoutParams params(int width, int height, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.setMargins(l, t, r, b);
        return p;
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private void pairDevice() {
        String endpoint = GAS_URL;
        String code = pairCode.getText().toString().replaceAll("\\s+", "");
        String name = deviceName.getText().toString().trim();
        if (!code.matches("[0-9]{6}")) {
            status.setText("6桁の数字を入力してください。");
            return;
        }
        if (name.isEmpty()) name = Build.MODEL;
        pairButton.setEnabled(false);
        prefs.edit().putString("gasUrl", GAS_URL).apply();
        status.setText("GASに登録中…");
        final String finalName = name;
        worker.execute(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("action", "claimPairCode");
                request.put("pairCode", code);
                request.put("name", finalName);
                JSONObject response = post(endpoint, request);
                String deviceId = response.getString("deviceId");
                String deviceToken = response.getString("deviceToken");
                prefs.edit().putString("deviceId", deviceId).putString("deviceToken", deviceToken)
                    .putString("deviceName", finalName).putString("iceServers", "")
                    .putLong("iceConfigFetchedAt", 0L).apply();
                runOnUiThread(() -> {
                    status.setText("登録完了！次回からコードは不要です。");
                    statusDot.setTextColor(ACCENT);
                    pairCode.setText("");
                });
            } catch (Exception ex) {
                runOnUiThread(() -> status.setText("登録失敗: " + ex.getMessage()));
            } finally {
                runOnUiThread(() -> pairButton.setEnabled(true));
            }
        });
    }

    private void startMonitor() {
        if (!prefs.contains("deviceToken")) {
            status.setText("先にiPadで発行したコードを使って端末登録してください。");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
            return;
        }
        Intent intent = new Intent(this, DeviceMonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        status.setText("接続監視を開始しました。通知を確認してください。");
    }

    private void requestMediaPermissions() {
        if (!prefs.contains("deviceToken")) {
            status.setText("先に端末登録を完了してください。");
            return;
        }
        ArrayList<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), PERMISSION_REQUEST);
            return;
        }
        startMediaService();
    }

    private void startMediaService() {
        try {
            Intent intent = new Intent(this, WebRtcSessionService.class);
            intent.setAction(WebRtcSessionService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
            status.setText("カメラ・マイクの起動処理中です。通知で状態を確認してください。");
        } catch (Exception e) {
            status.setText("開始できません: " + e.getMessage());
        }
    }

    private void stopMedia() {
        stopService(new Intent(this, WebRtcSessionService.class));
        status.setText("映像・音声セッションを停止しました。");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 10) {
            if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
                status.setText("通知を許可してください。"); return;
            }
            startMonitor();
        } else if (requestCode == PERMISSION_REQUEST) {
            if (results.length == 0) { status.setText("権限の確認が完了しませんでした。"); return; }
            for (int result : results) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    status.setText("カメラ・マイク・通知の許可が必要です。"); return;
                }
            }
            startMediaService();
        }
    }

    static JSONObject post(String endpoint, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(20000);
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) throw new Exception("GAS HTTPエラー: " + code);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = stream) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            JSONObject response = new JSONObject(bytes.toString("UTF-8"));
            if (code < 200 || code >= 400) throw new Exception("GAS HTTP " + code + ": " + response.optString("error", "通信エラー"));
            if (!response.optBoolean("ok")) throw new Exception(response.optString("error", "GAS APIエラー"));
            return response;
        } finally { connection.disconnect(); }
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
