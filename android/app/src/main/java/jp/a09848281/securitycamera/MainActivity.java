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
    public static final String ACTION_REQUEST_MEDIA = "jp.a09848281.securitycamera.REQUEST_MEDIA";
    private static final int PERMISSION_REQUEST = 21;
    private static final String GAS_URL = "https://script.google.com/macros/s/AKfycbwokMuKlrwbetUzGj_OiSXXVC_QmLzpZtZj68NB3CLolkWsGz_Rf538ulSye164Rbz6/exec";
    private static final int BG = Color.rgb(255,255,255);
    private static final int TEXT = Color.rgb(28,39,56);
    private static final int MUTED = Color.rgb(111,126,147);
    private static final int BLUE = Color.rgb(35,102,209);
    private EditText pairCode, deviceName;
    private TextView status, registrationStatus;
    private Button pairButton, waitButton;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private boolean pendingWaitStart = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("wsc", MODE_PRIVATE);
        prefs.edit().putString("gasUrl", GAS_URL).apply();
        buildScreen();
        if (ACTION_REQUEST_MEDIA.equals(getIntent().getAction())) {
            status.setText("Webから接続要求を受信しました。カメラ・マイクを準備します。");
            requestMediaPermissions();
        }
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(30), dp(22), dp(28));
        scroll.addView(root);

        TextView dot = text("●", 28, BLUE, true);
        root.addView(dot, params(WRAP, WRAP, 0, 0, 0, 6));
        TextView title = text("Security Camera", 25, TEXT, true);
        root.addView(title, params(WRAP, WRAP, 0, 0, 0, 3));
        TextView subtitle = text("Android端末をWebカメラとして待機させます", 12, MUTED, false);
        root.addView(subtitle, params(WRAP, WRAP, 0, 0, 0, 25));

        LinearLayout statusCard = card();
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.addView(text("端末状態", 12, MUTED, true));
        status = text(initialStatus(), 15, TEXT, true);
        statusCard.addView(status, params(MATCH, WRAP, 0, dp(8), 0, 0));
        root.addView(statusCard, params(MATCH, WRAP, 0, 0, 0, 18));

        sectionTitle(root, "端末登録");
        LinearLayout pairing = card();
        pairing.setOrientation(LinearLayout.VERTICAL);
        pairing.addView(text("Web画面で発行した6桁の登録コードを入力してください。登録後は再入力不要です。", 12, MUTED, false),
            params(MATCH, WRAP, 0, 0, 0, 12));
        deviceName = field("端末名");
        deviceName.setText(prefs.getString("deviceName", Build.MODEL));
        pairing.addView(deviceName, params(MATCH, dp(48), 0, 0, 0, 9));
        pairCode = field("6桁の登録コード");
        pairCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        pairCode.setLetterSpacing(0.18f);
        pairing.addView(pairCode, params(MATCH, dp(50), 0, 0, 0, 10));
        pairButton = button("この端末を登録", BLUE, Color.WHITE);
        pairing.addView(pairButton, params(MATCH, dp(48), 0, 0, 0, 0));
        root.addView(pairing, params(MATCH, WRAP, 0, 0, 0, 20));

        sectionTitle(root, "バックグラウンド待機");
        LinearLayout waiting = card();
        waiting.setOrientation(LinearLayout.VERTICAL);
        waiting.addView(text("待機中はカメラ・マイクを使用しません。Webから接続要求が届くと、OSが許可する条件で自動起動を試みます。", 12, MUTED, false),
            params(MATCH, WRAP, 0, 0, 0, 12));
        waitButton = button(prefs.getBoolean("monitoring", false) ? "待機サービスを再開" : "接続待機を開始", BLUE, Color.WHITE);
        waiting.addView(waitButton, params(MATCH, dp(50), 0, 0, 0, 8));
        Button stop = button("待機サービスを停止", Color.rgb(245,247,250), TEXT);
        waiting.addView(stop, params(MATCH, dp(46), 0, 0, 0, 0));
        registrationStatus = text("登録状態: " + (prefs.contains("deviceToken") ? "登録済み" : "未登録"), 11, MUTED, false);
        waiting.addView(registrationStatus, params(MATCH, WRAP, 0, dp(12), 0, 0));
        root.addView(waiting, params(MATCH, WRAP, 0, 0, 0, 18));

        LinearLayout note = card();
        note.setOrientation(LinearLayout.VERTICAL);
        note.addView(text("自動接続について", 13, TEXT, true));
        note.addView(text("Firebaseは使用しません。GASへの短周期確認で接続要求を受け取ります。Android 13はバックグラウンドからのカメラ・マイク新規起動を制限するため、OSが拒否した場合は通知をタップしてアプリを開く必要があります。画面ロック後の動作も実機で確認してください。", 11, MUTED, false),
            params(MATCH, WRAP, 0, dp(8), 0, 0));
        root.addView(note, params(MATCH, WRAP, 0, 0, 0, 6));
        setContentView(scroll);

        pairButton.setOnClickListener(v -> pairDevice());
        waitButton.setOnClickListener(v -> {
            pendingWaitStart = true;
            requestPermissionsForWaiting();
        });
        stop.setOnClickListener(v -> stopWaiting());
    }

    private String initialStatus() {
        String error = prefs.getString("lastMediaError", "");
        if (!error.isEmpty()) return "前回のカメラ起動結果: " + error;
        return prefs.contains("deviceToken") ? "登録済み・待機サービスを開始できます" : "未登録";
    }

    private void sectionTitle(LinearLayout root, String label) {
        TextView text = text(label, 15, TEXT, true);
        root.addView(text, params(MATCH, WRAP, 0, 0, 0, 9));
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
        t.setText(value);t.setTextSize(size);t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);e.setSingleLine(true);e.setTextSize(14);
        e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setHint(hint);
        e.setPadding(dp(12),0,dp(12),0);e.setBackground(shape(Color.WHITE,10,Color.rgb(220,227,236)));
        return e;
    }

    private Button button(String label, int background, int foreground) {
        Button b = new Button(this);b.setText(label);b.setTextSize(13);b.setTextColor(foreground);
        b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setBackground(shape(background,10,background));return b;
    }

    private GradientDrawable shape(int color,int radius,int stroke) {
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));
        d.setStroke(dp(1),stroke);return d;
    }

    private LinearLayout.LayoutParams params(int width,int height,int l,int t,int r,int b) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(width,height);p.setMargins(l,t,r,b);return p;
    }
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private static final int MATCH=LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP=LinearLayout.LayoutParams.WRAP_CONTENT;

    private void pairDevice() {
        String code=pairCode.getText().toString().replaceAll("\\s+","");
        String name=deviceName.getText().toString().trim();
        if(!code.matches("[0-9]{6}")){status.setText("6桁の数字を入力してください。");return;}
        if(name.isEmpty())name=Build.MODEL;
        pairButton.setEnabled(false);status.setText("登録中です…");
        final String finalName=name;
        worker.execute(()->{
            try{
                JSONObject request=new JSONObject();request.put("action","claimPairCode");
                request.put("pairCode",code);request.put("name",finalName);
                JSONObject response=post(GAS_URL,request);
                prefs.edit().putString("deviceId",response.getString("deviceId"))
                    .putString("deviceToken",response.getString("deviceToken"))
                    .putString("deviceName",finalName).putString("iceServers","")
                    .putLong("iceConfigFetchedAt",0L).apply();
                runOnUiThread(()->{status.setText("登録完了。次回からコード入力は不要です。");registrationStatus.setText("登録状態: 登録済み");pairCode.setText("");});
            }catch(Exception error){runOnUiThread(()->status.setText("登録失敗: "+error.getMessage()));}
            finally{runOnUiThread(()->pairButton.setEnabled(true));}
        });
    }

    private void requestPermissionsForWaiting() {
        if(!prefs.contains("deviceToken")){status.setText("先にWebで発行した6桁コードを使って登録してください。");return;}
        ArrayList<String> needed=new ArrayList<>();
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.CAMERA);
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.RECORD_AUDIO);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!needed.isEmpty()){requestPermissions(needed.toArray(new String[0]),PERMISSION_REQUEST);return;}
        startMonitor();
    }

    private void requestMediaPermissions() {
        ArrayList<String> needed=new ArrayList<>();
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.CAMERA);
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.RECORD_AUDIO);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!needed.isEmpty()){requestPermissions(needed.toArray(new String[0]),PERMISSION_REQUEST);return;}
        startMediaServiceFromVisibleActivity();
    }

    private void stopWaiting() {
        try {
            Intent mediaStop = new Intent(this, WebRtcSessionService.class);
            mediaStop.setAction(WebRtcSessionService.ACTION_STOP);
            startService(mediaStop);
        } catch (Exception ignored) {}
        stopService(new Intent(this, DeviceMonitorService.class));
        prefs.edit().putBoolean("monitoring", false).apply();
        status.setText("カメラ・マイクとバックグラウンド待機を停止しました。");
        waitButton.setText("接続待機を開始");
    }

    private void startMonitor() {
        try {
            Intent intent=new Intent(this,DeviceMonitorService.class);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
            prefs.edit().putBoolean("monitoring",true).apply();
            status.setText("自動接続待機中です。常時通知を確認してください。");
            waitButton.setText("待機サービス稼働中");
        } catch(Exception error) {status.setText("待機を開始できません: "+error.getMessage());}
    }

    private void startMediaServiceFromVisibleActivity() {
        try{
            Intent intent=new Intent(this,WebRtcSessionService.class);intent.setAction(WebRtcSessionService.ACTION_START);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
            status.setText("カメラ・マイクを起動しています。Androidのプライバシー表示を確認してください。");
        }catch(Exception error){status.setText("開始できません: "+error.getMessage());}
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==PERMISSION_REQUEST){
            if(results.length==0){status.setText("権限の確認が完了しませんでした。");return;}
            for(int result:results){if(result!=PackageManager.PERMISSION_GRANTED){status.setText("カメラ・マイク・通知の権限を許可してください。");return;}}
            if(ACTION_REQUEST_MEDIA.equals(getIntent().getAction()))startMediaServiceFromVisibleActivity();
            else if(pendingWaitStart)startMonitor();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);setIntent(intent);
        if(ACTION_REQUEST_MEDIA.equals(intent.getAction())){
            status.setText("接続要求を受信しました。カメラ・マイクを開始します。");
            requestMediaPermissions();
        }
    }

    static JSONObject post(String endpoint,JSONObject body)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");connection.setConnectTimeout(15000);connection.setReadTimeout(15000);
        connection.setDoOutput(true);connection.setUseCaches(false);
        connection.setRequestProperty("Content-Type","text/plain; charset=utf-8");
        try{
            try(OutputStream output=connection.getOutputStream()){output.write(body.toString().getBytes(StandardCharsets.UTF_8));output.flush();}
            int code=connection.getResponseCode();
            InputStream stream=code>=200&&code<400?connection.getInputStream():connection.getErrorStream();
            if(stream==null)throw new Exception("GAS HTTPエラー: "+code);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream input=stream){byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);}
            JSONObject response=new JSONObject(bytes.toString("UTF-8"));
            if(code<200||code>=400)throw new Exception("GAS HTTP "+code);
            if(!response.optBoolean("ok"))throw new Exception(response.optString("error","GAS APIエラー"));
            return response;
        }finally{connection.disconnect();}
    }

    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}