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
    private static final int PERMISSION_REQUEST = 21;
    private static final String GAS_URL = "https://script.google.com/macros/s/AKfycbwokMuKlrwbetUzGj_OiSXXVC_QmLzpZtZj68NB3CLolkWsGz_Rf538ulSye164Rbz6/exec";
    private static final int BG = Color.rgb(255,255,255);
    private static final int TEXT = Color.rgb(28,39,56);
    private static final int MUTED = Color.rgb(111,126,147);
    private static final int BLUE = Color.rgb(35,102,209);
    private EditText pairCode, deviceName;
    private TextView status, registrationStatus;
    private Button pairButton, waitButton, disconnectButton;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private boolean pendingWaitStart = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("wsc", MODE_PRIVATE);
        prefs.edit().putString("gasUrl", GAS_URL).apply();
        buildScreen();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(34), dp(22), dp(30));
        scroll.addView(root);

        TextView mark = text("●", 28, BLUE, true);
        root.addView(mark, params(WRAP, WRAP, 0, 0, 0, 6));
        root.addView(text("WebRTC Security Camera", 23, TEXT, true),
            params(WRAP, WRAP, 0, 0, 0, 4));
        root.addView(text("Android端末をRTC待機カメラとして登録します", 12, MUTED, false),
            params(WRAP, WRAP, 0, 0, 0, 25));

        LinearLayout statusCard = card();
        statusCard.addView(text("現在の状態", 12, MUTED, true));
        status = text(initialStatus(), 15, TEXT, true);
        statusCard.addView(status, params(MATCH, WRAP, 0, dp(9), 0, 0));
        root.addView(statusCard, params(MATCH, WRAP, 0, 0, 0, 18));

        LinearLayout pairing = card();
        pairing.addView(text("① 端末ペアリング", 15, TEXT, true));
        pairing.addView(text("Web画面で発行した6桁コードを入力してください。登録後は次回から入力不要です。",
            12, MUTED, false), params(MATCH, WRAP, 0, dp(8), 0, dp(12)));

        deviceName = field("端末名");
        deviceName.setText(prefs.getString("deviceName", Build.MODEL));
        pairing.addView(deviceName, params(MATCH, dp(48), 0, 0, 0, 9));

        pairCode = field("6桁の登録コード");
        pairCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        pairCode.setLetterSpacing(0.18f);
        pairing.addView(pairCode, params(MATCH, dp(50), 0, 0, 0, 10));

        pairButton = button("この端末を登録", BLUE, Color.WHITE);
        pairing.addView(pairButton, params(MATCH, dp(48), 0, 0, 0, 0));
        root.addView(pairing, params(MATCH, WRAP, 0, 0, 0, 16));

        LinearLayout waiting = card();
        waiting.addView(text("② バックグラウンド待機", 15, TEXT, true));
        waiting.addView(text("待機中はカメラ・マイクを停止します。WebとRTC待機接続ができた後、Webの接続ボタンで映像・音声を開始します。",
            12, MUTED, false), params(MATCH, WRAP, 0, dp(8), 0, dp(12)));

        waitButton = button("バックグラウンドで待機開始", BLUE, Color.WHITE);
        waiting.addView(waitButton, params(MATCH, dp(50), 0, 0, 0, 9));

        disconnectButton = button("切断（映像・音声停止）", Color.rgb(255,247,247), Color.rgb(175,61,61));
        waiting.addView(disconnectButton, params(MATCH, dp(46), 0, 0, 0, 0));

        registrationStatus = text("登録状態: " + (prefs.contains("deviceToken") ? "登録済み" : "未登録"),
            11, MUTED, false);
        waiting.addView(registrationStatus, params(MATCH, WRAP, 0, dp(12), 0, 0));
        root.addView(waiting, params(MATCH, WRAP, 0, 0, 0, 16));

        LinearLayout note = card();
        note.addView(text("接続方式", 13, TEXT, true));
        note.addView(text("Firebase/FCMは使用しません。最初のRTCペアリングと切断後の再接続にだけGASを使い、RTC接続後の操作・自動接続要求はWebRTCデータチャネルで直接送受信します。Androidには常時通知が表示されます。",
            11, MUTED, false), params(MATCH, WRAP, 0, dp(8), 0, 0));
        root.addView(note, params(MATCH, WRAP, 0, 0, 0, 6));

        setContentView(scroll);

        pairButton.setOnClickListener(v -> pairDevice());
        waitButton.setOnClickListener(v -> {
            pendingWaitStart = true;
            requestPermissionsForWaiting();
        });
        disconnectButton.setOnClickListener(v -> disconnectMedia());
    }

    private String initialStatus() {
        if (prefs.getBoolean("standbyActive", false)) return "RTCバックグラウンド待機中";
        if (prefs.contains("deviceToken")) return "登録済み。バックグラウンド待機を開始できます。";
        return "未登録";
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(15), dp(15), dp(15), dp(15));
        box.setBackground(shape(Color.rgb(250,251,253), 14, Color.rgb(231,235,241)));
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
        e.setTextSize(14);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setHint(hint);
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(shape(Color.WHITE, 10, Color.rgb(220,227,236)));
        return e;
    }

    private Button button(String label, int background, int foreground) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(foreground);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(shape(background, 10, background));
        return b;
    }

    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout.LayoutParams params(int width, int height, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.setMargins(l, t, r, b);
        return p;
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }
    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private void pairDevice() {
        String code = pairCode.getText().toString().replaceAll("\\s+", "");
        String name = deviceName.getText().toString().trim();
        if (!code.matches("[0-9]{6}")) {
            status.setText("6桁の数字を入力してください。");
            return;
        }
        if (name.isEmpty()) name = Build.MODEL;
        pairButton.setEnabled(false);
        status.setText("登録中です…");
        final String finalName = name;
        worker.execute(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("action", "claimPairCode");
                request.put("pairCode", code);
                request.put("name", finalName);
                JSONObject response = post(GAS_URL, request);
                prefs.edit()
                    .putString("deviceId", response.getString("deviceId"))
                    .putString("deviceToken", response.getString("deviceToken"))
                    .putString("deviceName", finalName)
                    .putBoolean("standbyActive", false)
                    .apply();
                runOnUiThread(() -> {
                    status.setText("登録完了。Web側の端末一覧を更新してください。");
                    registrationStatus.setText("登録状態: 登録済み");
                    pairCode.setText("");
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("登録失敗: " + error.getMessage()));
            } finally {
                runOnUiThread(() -> pairButton.setEnabled(true));
            }
        });
    }

    private void requestPermissionsForWaiting() {
        if (!prefs.contains("deviceToken")) {
            status.setText("先にWebで登録コードを発行し、この端末を登録してください。");
            pendingWaitStart = false;
            return;
        }
        ArrayList<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS);

        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), PERMISSION_REQUEST);
        } else {
            startStandbyService();
        }
    }

    private void startStandbyService() {
        try {
            Intent intent = new Intent(this, WebRtcSessionService.class);
            intent.setAction(WebRtcSessionService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);

            prefs.edit().putBoolean("standbyActive", true).apply();
            status.setText("RTCバックグラウンド待機中です。Web画面を開いて接続してください。");
            waitButton.setText("バックグラウンド待機中");
        } catch (Exception error) {
            status.setText("待機を開始できません: " + error.getMessage());
        }
    }

    private void disconnectMedia() {
        try {
            Intent intent = new Intent(this, WebRtcSessionService.class);
            intent.setAction(WebRtcSessionService.ACTION_STOP);
            startService(intent);
            status.setText("映像・音声の停止要求を送信しました。RTC待機は継続します。");
        } catch (Exception error) {
            status.setText("切断できません: " + error.getMessage());
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMISSION_REQUEST) return;
        if (results.length == 0) {
            status.setText("権限の確認が完了しませんでした。");
            pendingWaitStart = false;
            return;
        }
        for (int result : results) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                status.setText("カメラ・マイク・通知の権限を許可してください。待機を開始できません。");
                pendingWaitStart = false;
                return;
            }
        }
        if (pendingWaitStart) {
            pendingWaitStart = false;
            startStandbyService();
        }
    }

    static JSONObject post(String endpoint, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
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
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            JSONObject response = new JSONObject(bytes.toString("UTF-8"));
            if (code < 200 || code >= 400) throw new Exception("GAS HTTP " + code);
            if (!response.optBoolean("ok")) throw new Exception(response.optString("error", "GAS APIエラー"));
            return response;
        } finally {
            connection.disconnect();
        }
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
