package jp.a09848281.securitycamera;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpSender;
import org.webrtc.RtpTransceiver;
import org.webrtc.SessionDescription;
import org.webrtc.SdpObserver;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class WebRtcSessionService extends Service {
    public static final String ACTION_START = "jp.a09848281.securitycamera.START_STANDBY";
    public static final String ACTION_STOP = "jp.a09848281.securitycamera.STOP_MEDIA";
    public static final String ACTION_STOP_WAIT = "jp.a09848281.securitycamera.STOP_WAIT";
    private static final String CHANNEL = "wsc_media";
    private static final int NOTIFICATION_ID = 42;

    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService startup = Executors.newSingleThreadExecutor();
    private final ExecutorService network = Executors.newSingleThreadExecutor();

    private static volatile boolean webRtcInitialized = false;
    private volatile boolean serviceActive = false;
    private volatile boolean mediaActive = false;
    private volatile boolean mediaStarting = false;
    private volatile boolean offerQueued = false;
    private volatile boolean remoteDescriptionSet = false;
    private volatile boolean restartScheduled = false;
    private volatile String startupStage = "待機中";
    private volatile long lastPollErrorNotice = 0L;

    private final List<IceCandidate> pendingLocalIce = Collections.synchronizedList(new ArrayList<>());
    private final List<IceCandidate> pendingRemoteIce = Collections.synchronizedList(new ArrayList<>());

    private EglBase egl;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private DataChannel controlChannel;
    private DataChannel.Observer controlObserver;
    private RtpTransceiver audioTransceiver;
    private RtpTransceiver videoTransceiver;
    private RtpSender audioSender;
    private RtpSender videoSender;

    private VideoCapturer cameraCapturer;
    private SurfaceTextureHelper textureHelper;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private VideoTrack videoTrack;
    private MediaStreamTrack remoteAudioTrack;
    private VideoSink telemetrySink;

    private volatile long fpsWindowStart = 0L;
    private volatile int framesInWindow = 0;
    private volatile long lastFrameAt = 0L;
    private volatile int lastFrameWidth = 0;
    private volatile int lastFrameHeight = 0;
    private volatile int observedFps = 0;
    private volatile long cameraStartAt = 0L;
    private volatile int cameraRecoveryCount = 0;
    private volatile String networkMode = "auto";
    private volatile String powerMode = "auto";
    private volatile boolean networkDegraded = false;
    private volatile long networkRttMs = -1L;
    private volatile int networkLossPercent = -1;
    private volatile int currentWidth = 0;
    private volatile int currentHeight = 0;
    private volatile int currentFps = 0;
    private volatile int currentBitrate = 0;

    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private int previousAudioMode = AudioManager.MODE_NORMAL;
    private boolean previousSpeakerphone = false;
    private String sessionId = "";

    private ScheduledFuture<?> signalPollFuture;
    private ScheduledFuture<?> offerRenewFuture;
    private ScheduledFuture<?> telemetryFuture;
    private ScheduledFuture<?> watchdogFuture;
    private ScheduledFuture<?> scheduleFuture;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP_WAIT.equals(action)) {
            stopWaitingAndService();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            if (!serviceActive) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            stopMediaOnly();
            return START_STICKY;
        }
        if (ACTION_START.equals(action) && !serviceActive) {
            beginStandby();
        }
        return START_STICKY;
    }

    private void beginStandby() {
        try {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("カメラの権限がありません。アプリを開いて許可してください。");
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("マイクの権限がありません。アプリを開いて許可してください。");

            Notification notification = buildNotification("RTC待機を準備しています（カメラ・マイク停止）");
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            serviceActive = true;
            getSharedPreferences("wsc", MODE_PRIVATE).edit()
                .putBoolean("standbyActive", true)
                .remove("lastMediaError")
                .apply();

            startup.execute(() -> {
                try {
                    startupStage = "WebRTC初期化";
                    initializeWebRtcFactory();
                    startupStage = "RTC待機接続";
                    createPeerAndOffer();
                    scheduleFuture = watchdog.scheduleWithFixedDelay(this::checkScheduleAndSafety, 15, 30, TimeUnit.SECONDS);
                    notifyStatus("RTC待機を開始しました。カメラ・マイクは使用していません。Webから接続できます。");
                } catch (Exception error) {
                    fail("RTC待機を開始できません（" + startupStage + "）: " +
                        (error.getMessage() == null ? error.toString() : error.getMessage()));
                } catch (LinkageError error) {
                    fail("WebRTCネイティブ初期化に失敗しました: " + error);
                }
            });
        } catch (Exception error) {
            fail("待機開始に失敗しました: " + (error.getMessage() == null ? error.toString() : error.getMessage()));
        }
    }

    private void initializeWebRtcFactory() {
        synchronized (WebRtcSessionService.class) {
            if (!webRtcInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(getApplicationContext())
                        .createInitializationOptions()
                );
                webRtcInitialized = true;
            }
        }
        if (egl == null) egl = EglBase.create();
        if (factory == null) {
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .createPeerConnectionFactory();
        }
        if (factory == null) throw new IllegalStateException("PeerConnectionFactoryを作成できません。");
    }

    private void createPeerAndOffer() throws Exception {
        closePeerOnly();
        pendingLocalIce.clear();
        pendingRemoteIce.clear();
        offerQueued = false;
        remoteDescriptionSet = false;
        sessionId = UUID.randomUUID().toString();

        SharedPreferences prefs = getSharedPreferences("wsc", MODE_PRIVATE);
        PeerConnection.RTCConfiguration configuration =
            new PeerConnection.RTCConfiguration(buildIceServers(prefs));
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        configuration.iceCandidatePoolSize = 4;
        configuration.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;

        peer = factory.createPeerConnection(configuration, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState state) {}

            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                if (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED) {
                    notifyStatus("WebとRTC接続しました。待機チャネルを維持しています。");
                } else if (state == PeerConnection.IceConnectionState.FAILED) {
                    notifyStatus("RTC接続が失敗しました。再接続を試みます。");
                    schedulePeerRestart(2500);
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    notifyStatus("RTC通信が一時切断されました。再接続を確認しています。");
                    schedulePeerRestart(12000);
                }
            }

            @Override public void onIceConnectionReceivingChange(boolean receiving) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}

            @Override public void onIceCandidate(IceCandidate candidate) {
                if (!offerQueued) {
                    pendingLocalIce.add(candidate);
                } else {
                    sendIce(candidate);
                }
            }

            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
            @Override public void onAddStream(MediaStream stream) {}
            @Override public void onRemoveStream(MediaStream stream) {}
            @Override public void onDataChannel(DataChannel channel) {
                if ("wsc-control".equals(channel.label())) observeControlChannel(channel);
            }
            @Override public void onRenegotiationNeeded() {}

            @Override public void onAddTrack(org.webrtc.RtpReceiver receiver, MediaStream[] streams) {
                MediaStreamTrack track = receiver.track();
                if (track != null && track.kind().equals("audio")) {
                    remoteAudioTrack = track;
                    remoteAudioTrack.setEnabled(mediaActive);
                    notifyStatus("WebからのPTT音声トラックを受信しました。");
                }
            }
        });

        if (peer == null) throw new IllegalStateException("PeerConnectionを作成できません。");

        audioTransceiver = peer.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        );
        videoTransceiver = peer.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
        );
        audioSender = audioTransceiver.getSender();
        videoSender = videoTransceiver.getSender();

        DataChannel.Init init = new DataChannel.Init();
        init.ordered = true;
        init.maxRetransmits = -1;
        controlChannel = peer.createDataChannel("wsc-control", init);
        if (controlChannel == null) throw new IllegalStateException("RTCデータチャネルを作成できません。");
        observeControlChannel(controlChannel);

        MediaConstraints offerConstraints = new MediaConstraints();
        peer.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription offer) {
                PeerConnection current = peer;
                if (current == null) return;
                current.setLocalDescription(new SdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription description) {}
                    @Override public void onSetSuccess() {
                        offerQueued = true;
                        sendSignal("offer", offer.description);
                        synchronized (pendingLocalIce) {
                            for (IceCandidate candidate : pendingLocalIce) sendIce(candidate);
                            pendingLocalIce.clear();
                        }
                        notifyStatus("RTCペアリング情報を発行しました。Web画面から端末を選択してください。");
                    }
                    @Override public void onCreateFailure(String error) { fail("Offerのローカル設定に失敗: " + error); }
                    @Override public void onSetFailure(String error) { fail("Offerの登録に失敗: " + error); }
                }, offer);
            }
            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String error) { fail("Offer作成に失敗: " + error); }
            @Override public void onSetFailure(String error) { fail("Offer設定に失敗: " + error); }
        }, offerConstraints);

        signalPollFuture = io.scheduleWithFixedDelay(this::pollSignals, 1, 2, TimeUnit.SECONDS);
        offerRenewFuture = io.schedule(() -> {
            DataChannel channel = controlChannel;
            if (!serviceActive || (channel != null && channel.state() == DataChannel.State.OPEN)) return;
            try {
                startup.execute(() -> {
                    DataChannel current = controlChannel;
                    if (!serviceActive || (current != null && current.state() == DataChannel.State.OPEN)) return;
                    stopMediaOnlyInternal(false);
                    try {
                        createPeerAndOffer();
                        notifyStatus("未接続Offerを更新しました。Web画面から接続できます。");
                    } catch (Exception error) {
                        fail("RTC待機情報の更新に失敗しました: " + error.getMessage());
                    }
                });
            } catch (Exception ignored) {}
        }, 8, TimeUnit.MINUTES);
    }

    private void observeControlChannel(DataChannel channel) {
        if (channel == null) return;
        try {
            if (controlObserver != null) channel.unregisterObserver();
        } catch (Exception ignored) {}

        controlChannel = channel;
        controlObserver = new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long previousAmount) {}

            @Override public void onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    onControlChannelReady();
                } else if (channel.state() == DataChannel.State.CLOSED) {
                    notifyStatus("RTC操作チャネルが閉じました。再接続を試みます。");
                }
            }

            @Override public void onMessage(DataChannel.Buffer buffer) {
                if (buffer.binary) return;
                ByteBuffer data = buffer.data.duplicate();
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                String raw = new String(bytes, StandardCharsets.UTF_8);
                try {
                    JSONObject packet = new JSONObject(raw);
                    String type = packet.optString("type", "");
                    JSONObject payload = packet.optJSONObject("payload");
                    if (payload == null) payload = new JSONObject();
                    handleControlMessage(type, payload);
                } catch (Exception error) {
                    notifyStatus("Webからの操作データを読み取れませんでした。");
                }
            }
        };
        channel.registerObserver(controlObserver);
        if (channel.state() == DataChannel.State.OPEN) onControlChannelReady();
    }

    private void onControlChannelReady() {
        ScheduledFuture<?> poll = signalPollFuture;
        if (poll != null) {
            poll.cancel(false);
            signalPollFuture = null;
        }
        if (offerRenewFuture != null) {
            offerRenewFuture.cancel(false);
            offerRenewFuture = null;
        }
        notifyStatus("RTC待機接続済み。カメラ・マイクは停止中です。");
        sendDataMessage("STATUS", collectStatus());
        if (telemetryFuture == null || telemetryFuture.isCancelled()) {
            telemetryFuture = io.scheduleWithFixedDelay(this::sendTelemetry, 5, 5, TimeUnit.SECONDS);
        }
    }

    private void handleControlMessage(String type, JSONObject payload) {
        switch (type) {
            case "START_MEDIA":
                startMediaOnly();
                break;
            case "STOP_MEDIA":
                stopMediaOnly();
                break;
            case "SWITCH_CAMERA":
                switchCamera();
                break;
            case "SET_MODES":
                applyModes(payload);
                break;
            case "GET_STATUS":
                sendDataMessage("STATUS", collectStatus());
                break;
            case "NETWORK_STATS":
                long rtt = payload.optLong("rttMs", -1L);
                int loss = payload.optInt("packetLossPct", -1);
                boolean wasDegraded = networkDegraded;
                networkRttMs = rtt;
                networkLossPercent = loss;
                networkDegraded = (rtt >= 0 && rtt >= 350) || (loss >= 0 && loss >= 5);
                if (wasDegraded != networkDegraded && mediaActive) autoAdaptQuality();
                break;
            case "SET_SCHEDULE":
                saveSchedule(payload);
                break;
            default:
                notifyStatus("未対応のWeb操作です: " + type);
                break;
        }
    }

    private void startMediaOnly() {
        if (!serviceActive) {
            sendError("AndroidのRTC待機サービスが停止しています。アプリから待機を開始してください。");
            return;
        }
        if (mediaActive || mediaStarting) return;
        try {
            startup.execute(this::startMediaOnlyInternal);
        } catch (Exception error) {
            sendError("映像開始処理を実行できません: " + error.getMessage());
        }
    }

    private void startMediaOnlyInternal() {
        if (mediaActive || mediaStarting || !serviceActive) return;
        mediaStarting = true;
        startupStage = "カメラ・マイク起動";
        try {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("カメラ権限がありません。Androidアプリを開いて許可してください。");
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("マイク権限がありません。Androidアプリを開いて許可してください。");
            if (peer == null || controlChannel == null || controlChannel.state() != DataChannel.State.OPEN)
                throw new IllegalStateException("RTC待機接続がありません。Web画面から再接続してください。");

            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power == null) throw new IllegalStateException("電源管理サービスを取得できません。");
            if (wakeLock == null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebRTCSecurityCamera:Media");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(2L * 60L * 60L * 1000L);
            }

            audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager != null) {
                previousAudioMode = audioManager.getMode();
                previousSpeakerphone = audioManager.isSpeakerphoneOn();
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(true);
            }

            startupStage = "マイク初期化";
            MediaConstraints audioConstraints = new MediaConstraints();
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
            audioSource = factory.createAudioSource(audioConstraints);
            audioTrack = factory.createAudioTrack("wsc_audio_" + System.currentTimeMillis(), audioSource);
            audioTrack.setEnabled(true);
            if (audioSender == null || !audioSender.setTrack(audioTrack, false))
                throw new IllegalStateException("AndroidマイクトラックをRTCへ接続できません。");
            if (remoteAudioTrack != null) remoteAudioTrack.setEnabled(true);

            startupStage = "カメラ初期化";
            cameraCapturer = createCameraCapturer();
            if (cameraCapturer == null) throw new IllegalStateException("使用可能なカメラが見つかりません。");
            textureHelper = SurfaceTextureHelper.create("WSC-Camera", egl.getEglBaseContext());
            videoSource = factory.createVideoSource(false);
            cameraCapturer.initialize(textureHelper, getApplicationContext(), videoSource.getCapturerObserver());
            videoTrack = factory.createVideoTrack("wsc_video_" + System.currentTimeMillis(), videoSource);
            videoTrack.setEnabled(true);
            telemetrySink = this::recordVideoFrame;
            videoTrack.addSink(telemetrySink);
            if (videoSender == null || !videoSender.setTrack(videoTrack, false))
                throw new IllegalStateException("AndroidカメラトラックをRTCへ接続できません。");

            startupStage = "映像取得開始";
            int[] quality = startCaptureWithFallback(calculateQuality(getSharedPreferences("wsc", MODE_PRIVATE)));
            currentWidth = quality[0];
            currentHeight = quality[1];
            currentFps = quality[2];
            currentBitrate = quality[3];
            cameraStartAt = System.currentTimeMillis();
            lastFrameAt = 0L;
            cameraRecoveryCount = 0;
            applySenderBitrate(quality[3], quality[2]);
            mediaActive = true;
            mediaStarting = false;
            if (remoteAudioTrack != null) remoteAudioTrack.setEnabled(true);
            if (watchdogFuture != null) watchdogFuture.cancel(false);
            watchdogFuture = watchdog.scheduleWithFixedDelay(this::monitorVideoFrames, 10, 5, TimeUnit.SECONDS);
            updateNotification("カメラ・マイク使用中（RTC接続済み）");
            sendDataMessage("MEDIA_STARTED", collectStatus());
            sendTelemetry();
        } catch (SecurityException error) {
            mediaStarting = false;
            releaseMediaResources();
            sendError("Android OSがカメラ・マイクの起動を許可しませんでした。通知を開いてアプリを前面に表示してください。");
            notifyStatus("カメラ・マイク起動がOSに拒否されました。アプリを開いてください。");
        } catch (Exception error) {
            mediaStarting = false;
            releaseMediaResources();
            String detail = error.getMessage() == null ? error.toString() : error.getMessage();
            sendError("映像・音声の開始に失敗（" + startupStage + "）: " + detail);
            notifyStatus("映像・音声を開始できませんでした: " + detail);
        } catch (LinkageError error) {
            mediaStarting = false;
            releaseMediaResources();
            sendError("カメラ・マイクのネイティブ初期化に失敗しました。");
            notifyStatus("ネイティブ初期化エラー: " + error);
        }
    }

    private void stopMediaOnly() {
        try {
            if (startup.isShutdown()) return;
            startup.execute(() -> stopMediaOnlyInternal(true));
        } catch (Exception ignored) {}
    }

    private void stopMediaOnlyInternal(boolean notifyWeb) {
        mediaStarting = false;
        boolean hadMedia = mediaActive || cameraCapturer != null || audioTrack != null || videoTrack != null;
        mediaActive = false;
        if (watchdogFuture != null) watchdogFuture.cancel(false);
        watchdogFuture = null;
        releaseMediaResources();
        if (notifyWeb && controlChannel != null && controlChannel.state() == DataChannel.State.OPEN) {
            sendDataMessage("MEDIA_STOPPED", collectStatus());
        }
        updateNotification("RTC待機中（カメラ・マイク停止）");
        if (hadMedia) notifyStatus("映像・音声を停止し、RTC待機に戻りました。");
    }

    private void releaseMediaResources() {
        if (remoteAudioTrack != null) {
            try { remoteAudioTrack.setEnabled(false); } catch (Exception ignored) {}
        }
        if (audioTrack != null) {
            try { audioTrack.setEnabled(false); } catch (Exception ignored) {}
            try { if (audioSender != null) audioSender.setTrack(null, false); } catch (Exception ignored) {}
            try { audioTrack.dispose(); } catch (Exception ignored) {}
            audioTrack = null;
        }
        if (audioSource != null) {
            try { audioSource.dispose(); } catch (Exception ignored) {}
            audioSource = null;
        }
        if (cameraCapturer != null) {
            try { cameraCapturer.stopCapture(); } catch (Exception ignored) {}
            try { cameraCapturer.dispose(); } catch (Exception ignored) {}
            cameraCapturer = null;
        }
        if (videoTrack != null) {
            try { if (telemetrySink != null) videoTrack.removeSink(telemetrySink); } catch (Exception ignored) {}
            try { if (videoSender != null) videoSender.setTrack(null, false); } catch (Exception ignored) {}
            try { videoTrack.dispose(); } catch (Exception ignored) {}
            videoTrack = null;
        }
        telemetrySink = null;
        if (videoSource != null) {
            try { videoSource.dispose(); } catch (Exception ignored) {}
            videoSource = null;
        }
        if (textureHelper != null) {
            try { textureHelper.dispose(); } catch (Exception ignored) {}
            textureHelper = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        wakeLock = null;
        if (audioManager != null) {
            try { audioManager.setSpeakerphoneOn(previousSpeakerphone); } catch (Exception ignored) {}
            try { audioManager.setMode(previousAudioMode); } catch (Exception ignored) {}
            audioManager = null;
        }
        cameraStartAt = 0L;
    }

    private void switchCamera() {
        if (!mediaActive || !(cameraCapturer instanceof CameraVideoCapturer)) {
            sendError("カメラ切替は映像配信中のみ利用できます。");
            return;
        }
        ((CameraVideoCapturer) cameraCapturer).switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
            @Override public void onCameraSwitchDone(boolean isFrontCamera) {
                JSONObject payload = new JSONObject();
                try {
                    payload.put("frontCamera", isFrontCamera);
                    payload.put("message", isFrontCamera ? "フロントカメラに切り替えました。" : "リアカメラに切り替えました。");
                } catch (Exception ignored) {}
                sendDataMessage("CAMERA", payload);
                notifyStatus(isFrontCamera ? "フロントカメラに切り替えました。" : "リアカメラに切り替えました。");
            }
            @Override public void onCameraSwitchError(String errorDescription) {
                sendError("カメラ切替に失敗しました: " + errorDescription);
            }
        });
    }

    private void applyModes(JSONObject payload) {
        networkMode = payload.optString("networkMode", "auto");
        powerMode = payload.optString("powerMode", "auto");
        SharedPreferences prefs = getSharedPreferences("wsc", MODE_PRIVATE);
        prefs.edit().putString("networkMode", networkMode).putString("powerMode", powerMode).apply();

        int[] quality = calculateQuality(prefs);
        if (mediaActive && cameraCapturer != null) {
            try {
                cameraCapturer.changeCaptureFormat(quality[0], quality[1], quality[2]);
                currentWidth = quality[0];
                currentHeight = quality[1];
                currentFps = quality[2];
                currentBitrate = quality[3];
            } catch (Exception error) {
                try { cameraCapturer.changeCaptureFormat(640, 480, 15); } catch (Exception ignored) {}
                notifyStatus("指定画質に変更できないため低負荷設定を試しました。");
            }
            applySenderBitrate(quality[3], quality[2]);
        }
        notifyStatus("通信・電力モードを適用しました。");
        sendDataMessage("STATUS", collectStatus());
    }

    private void saveSchedule(JSONObject payload) {
        SharedPreferences.Editor editor = getSharedPreferences("wsc", MODE_PRIVATE).edit();
        editor.putBoolean("scheduleEnabled", payload.optBoolean("enabled", false));
        editor.putString("scheduleDays", payload.optString("days", "weekdays"));
        editor.putString("scheduleStart", payload.optString("start", "08:00"));
        editor.putString("scheduleEnd", payload.optString("end", "20:00"));
        editor.apply();
        notifyStatus(payload.optBoolean("enabled", false) ? "監視スケジュールを保存しました。" : "監視スケジュールを無効にしました。");
        sendDataMessage("NOTICE", messagePayload("監視スケジュールを保存しました。"));
    }

    private void checkScheduleAndSafety() {
        if (!serviceActive) return;
        checkThermalSafety();
        checkSchedule();
        autoAdaptQuality();
        sendTelemetry();
    }

    private void checkThermalSafety() {
        if (!mediaActive || Build.VERSION.SDK_INT < 29) return;
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null && pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) {
                sendError("Androidが危険な高温状態です。安全のため映像・音声を停止しました。");
                stopMediaOnly();
            }
        } catch (Exception ignored) {}
    }

    private void checkSchedule() {
        SharedPreferences prefs = getSharedPreferences("wsc", MODE_PRIVATE);
        if (!prefs.getBoolean("scheduleEnabled", false)) return;

        java.util.Calendar now = java.util.Calendar.getInstance();
        int day = now.get(java.util.Calendar.DAY_OF_WEEK);
        String days = prefs.getString("scheduleDays", "weekdays");
        boolean dayAllowed = "everyday".equals(days) ||
            ("weekdays".equals(days) && day != java.util.Calendar.SATURDAY && day != java.util.Calendar.SUNDAY) ||
            ("weekend".equals(days) && (day == java.util.Calendar.SATURDAY || day == java.util.Calendar.SUNDAY));
        if (!dayAllowed) return;

        int current = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        int start = parseMinutes(prefs.getString("scheduleStart", "08:00"));
        int end = parseMinutes(prefs.getString("scheduleEnd", "20:00"));
        if (start < 0 || end < 0 || start == end) return;

        String dayKey = String.format(Locale.US, "%04d%02d%02d",
            now.get(java.util.Calendar.YEAR),
            now.get(java.util.Calendar.MONTH) + 1,
            now.get(java.util.Calendar.DAY_OF_MONTH));

        if (current >= start && current < end) {
            if (!dayKey.equals(prefs.getString("scheduleStartedDay", ""))) {
                prefs.edit().putString("scheduleStartedDay", dayKey).apply();
                notifyStatus("スケジュールの開始時刻です。カメラ・マイクの起動を試みます。");
                startMediaOnly();
            }
            if (current >= end - 5 && current < end - 4 &&
                !dayKey.equals(prefs.getString("scheduleEndNoticeDay", ""))) {
                prefs.edit().putString("scheduleEndNoticeDay", dayKey).apply();
                notifyStatus("スケジュール終了まで5分です。");
            }
        } else if (current >= end && dayKey.equals(prefs.getString("scheduleStartedDay", "")) &&
            !dayKey.equals(prefs.getString("scheduleStoppedDay", ""))) {
            prefs.edit().putString("scheduleStoppedDay", dayKey).apply();
            notifyStatus("スケジュール終了時刻です。");
            stopMediaOnly();
        }
    }

    private int parseMinutes(String value) {
        try {
            String[] parts = value.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return -1;
            return hour * 60 + minute;
        } catch (Exception error) {
            return -1;
        }
    }

    private JSONObject collectStatus() {
        JSONObject result = new JSONObject();
        try {
            Intent battery = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                if (scale > 0 && level >= 0) result.put("batteryPct", Math.round(level * 100f / scale));
                if (temp >= 0) result.put("batteryTempC", temp / 10.0);
                result.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                PowerManager pm = getSystemService(PowerManager.class);
                if (pm != null) result.put("thermalStatus", thermalName(pm.getCurrentThermalStatus()));
            }
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network network = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = cm == null || network == null ? null : cm.getNetworkCapabilities(network);
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) result.put("networkType", "Wi-Fi");
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) result.put("networkType", "モバイル通信");
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) result.put("networkType", "Ethernet");
                else result.put("networkType", "その他");
            }
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (manager != null) {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                manager.getMemoryInfo(info);
                result.put("memoryUsedMb", Math.max(0, Debug.getPss() / 1024));
                result.put("memoryTotalMb", info.totalMem / (1024 * 1024));
            }
            result.put("cpuLoadPct", JSONObject.NULL);
            result.put("fps", observedFps);
            result.put("width", lastFrameWidth);
            result.put("height", lastFrameHeight);
            result.put("networkRttMs", networkRttMs >= 0 ? networkRttMs : JSONObject.NULL);
            result.put("networkLossPercent", networkLossPercent >= 0 ? networkLossPercent : JSONObject.NULL);
            result.put("mediaActive", mediaActive);
            result.put("standby", serviceActive);
        } catch (Exception ignored) {}
        return result;
    }

    private String thermalName(int status) {
        if (status == PowerManager.THERMAL_STATUS_NONE) return "NONE";
        if (status == PowerManager.THERMAL_STATUS_LIGHT) return "LIGHT";
        if (status == PowerManager.THERMAL_STATUS_MODERATE) return "MODERATE";
        if (status == PowerManager.THERMAL_STATUS_SEVERE) return "SEVERE";
        if (status == PowerManager.THERMAL_STATUS_CRITICAL) return "CRITICAL";
        if (status == PowerManager.THERMAL_STATUS_EMERGENCY) return "EMERGENCY";
        if (status == PowerManager.THERMAL_STATUS_SHUTDOWN) return "SHUTDOWN";
        return "取得不可";
    }

    private JSONObject messagePayload(String message) {
        JSONObject payload = new JSONObject();
        try { payload.put("message", message); } catch (Exception ignored) {}
        return payload;
    }

    private void sendTelemetry() {
        if (!serviceActive || controlChannel == null || controlChannel.state() != DataChannel.State.OPEN) return;
        JSONObject status = collectStatus();
        sendDataMessage("STATUS", status);
        try {
            int battery = status.optInt("batteryPct", 100);
            if (battery <= 20 && !getSharedPreferences("wsc", MODE_PRIVATE).getBoolean("batteryWarningShown", false)) {
                getSharedPreferences("wsc", MODE_PRIVATE).edit().putBoolean("batteryWarningShown", true).apply();
                notifyStatus("バッテリー残量が20%以下です。");
                sendError("バッテリー残量が20%以下です。");
            } else if (battery > 20) {
                getSharedPreferences("wsc", MODE_PRIVATE).edit().putBoolean("batteryWarningShown", false).apply();
            }
        } catch (Exception ignored) {}
    }

    private void sendError(String message) {
        sendDataMessage("ERROR", messagePayload(message));
        notifyStatus(message);
    }

    private void sendDataMessage(String type, JSONObject payload) {
        DataChannel channel = controlChannel;
        if (channel == null || channel.state() != DataChannel.State.OPEN) return;
        try {
            JSONObject packet = new JSONObject();
            packet.put("type", type);
            packet.put("payload", payload == null ? new JSONObject() : payload);
            packet.put("at", System.currentTimeMillis());
            byte[] bytes = packet.toString().getBytes(StandardCharsets.UTF_8);
            boolean sent = channel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
            if (!sent) notifyStatus("RTC操作メッセージを送信できませんでした。");
        } catch (Exception error) {
            notifyStatus("RTCデータ送信に失敗しました。");
        }
    }

    private void schedulePeerRestart(long delayMs) {
        if (restartScheduled || !serviceActive) return;
        restartScheduled = true;
        io.schedule(() -> {
            restartScheduled = false;
            PeerConnection current = peer;
            if (!serviceActive || current == null ||
                (current.iceConnectionState() != PeerConnection.IceConnectionState.DISCONNECTED &&
                 current.iceConnectionState() != PeerConnection.IceConnectionState.FAILED)) return;
            try {
                startup.execute(() -> {
                    try {
                        stopMediaOnlyInternal(false);
                        createPeerAndOffer();
                        notifyStatus("RTC再接続情報を更新しました。Web画面で再接続してください。");
                    } catch (Exception error) {
                        fail("RTC再接続に失敗しました: " + error.getMessage());
                    }
                });
            } catch (Exception ignored) {}
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void pollSignals() {
        if (!serviceActive || !offerQueued || sessionId.isEmpty()) return;
        try {
            SharedPreferences prefs = getSharedPreferences("wsc", MODE_PRIVATE);
            JSONObject request = auth(prefs);
            request.put("action", "pollSignals");
            request.put("role", "android");
            request.put("sessionId", sessionId);
            JSONObject response = MainActivity.post(prefs.getString("gasUrl", ""), request);
            lastPollErrorNotice = 0L;
            JSONArray signals = response.optJSONArray("signals");
            if (signals == null) return;
            for (int i = 0; i < signals.length(); i++) {
                JSONObject signal = signals.getJSONObject(i);
                String type = signal.optString("type");
                String payload = signal.optString("payload");
                if ("answer".equals(type) && peer != null && !remoteDescriptionSet) {
                    PeerConnection current = peer;
                    current.setRemoteDescription(new SdpObserver() {
                        @Override public void onSetSuccess() {
                            remoteDescriptionSet = true;
                            synchronized (pendingRemoteIce) {
                                for (IceCandidate candidate : pendingRemoteIce) current.addIceCandidate(candidate);
                                pendingRemoteIce.clear();
                            }
                            notifyStatus("Webの応答を受信しました。RTC待機チャネルを確立しています。");
                        }
                        @Override public void onSetFailure(String error) {
                            notifyStatus("Webの応答を設定できません: " + error);
                        }
                        @Override public void onCreateSuccess(SessionDescription description) {}
                        @Override public void onCreateFailure(String error) {}
                    }, new SessionDescription(SessionDescription.Type.ANSWER, payload));
                } else if ("ice".equals(type) && peer != null) {
                    JSONObject candidateJson = new JSONObject(payload);
                    IceCandidate candidate = new IceCandidate(
                        candidateJson.optString("sdpMid"),
                        candidateJson.optInt("sdpMLineIndex"),
                        candidateJson.optString("candidate")
                    );
                    if (remoteDescriptionSet) peer.addIceCandidate(candidate);
                    else pendingRemoteIce.add(candidate);
                } else if ("bye".equals(type)) {
                    schedulePeerRestart(1000);
                    return;
                }
            }
        } catch (Exception error) {
            long now = System.currentTimeMillis();
            if (now - lastPollErrorNotice > 15000L) {
                lastPollErrorNotice = now;
                notifyStatus("初回RTCシグナリングを再試行中です。ネットワークを確認してください。");
            }
        }
    }

    private void sendIce(IceCandidate candidate) {
        JSONObject json = new JSONObject();
        try {
            json.put("sdpMid", candidate.sdpMid);
            json.put("sdpMLineIndex", candidate.sdpMLineIndex);
            json.put("candidate", candidate.sdp);
            sendSignal("ice", json.toString());
        } catch (Exception error) {
            notifyStatus("ICE候補を作成できませんでした。");
        }
    }

    private void sendSignal(String type, String payload) {
        if (network.isShutdown()) return;
        final String signalSession = sessionId;
        try {
            network.execute(() -> {
                try {
                    SharedPreferences prefs = getSharedPreferences("wsc", MODE_PRIVATE);
                    JSONObject request = auth(prefs);
                    request.put("action", "sendSignal");
                    request.put("fromRole", "android");
                    request.put("type", type);
                    request.put("sessionId", signalSession);
                    request.put("payload", payload);
                    MainActivity.post(prefs.getString("gasUrl", ""), request);
                } catch (Exception error) {
                    notifyStatus("GASシグナリングに失敗しました。ネットワークを確認してください。");
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private JSONObject auth(SharedPreferences prefs) throws Exception {
        JSONObject request = new JSONObject();
        request.put("deviceId", prefs.getString("deviceId", ""));
        request.put("deviceToken", prefs.getString("deviceToken", ""));
        return request;
    }

    private int[] calculateQuality(SharedPreferences prefs) {
        String network = prefs.getString("networkMode", networkMode);
        String power = prefs.getString("powerMode", powerMode);
        if ("auto".equals(network)) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                Network activeNetwork = cm == null ? null : cm.getActiveNetwork();
                NetworkCapabilities caps = cm == null || activeNetwork == null ? null : cm.getNetworkCapabilities(activeNetwork);
                network = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi" : "mobile";
            } catch (Exception ignored) { network = "mobile"; }
        }
        int width = 1280, height = 720, fps = 24, bitrate = 1400000;
        if ("wifi".equals(network)) { width = 1920; height = 1080; fps = 30; bitrate = 2500000; }
        else if ("mobile".equals(network)) { width = 640; height = 360; fps = 15; bitrate = 500000; }

        if ("eco".equals(power)) {
            width = Math.min(width, 640); height = Math.min(height, 480);
            fps = Math.min(fps, 12); bitrate = Math.min(bitrate, 450000);
        } else if ("normal".equals(power)) {
            width = Math.min(width, 1280); height = Math.min(height, 720);
            fps = Math.min(fps, 24); bitrate = Math.min(bitrate, 1400000);
        } else if ("performance".equals(power)) {
            width = Math.min(width, 1920); height = Math.min(height, 1080);
            fps = Math.min(fps, 30);
        }

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                PowerManager pm = getSystemService(PowerManager.class);
                if (pm != null && pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE) {
                    width = Math.min(width, 960); height = Math.min(height, 540);
                    fps = Math.min(fps, 15); bitrate = Math.min(bitrate, 850000);
                }
            }
            Intent battery = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (scale > 0 && level * 100 / scale <= 20) {
                    width = Math.min(width, 640); height = Math.min(height, 480);
                    fps = Math.min(fps, 12); bitrate = Math.min(bitrate, 450000);
                }
            }
        } catch (Exception ignored) {}
        if (networkDegraded) {
            if (networkLossPercent >= 10 || networkRttMs >= 700) {
                width = Math.min(width, 640);
                height = Math.min(height, 480);
                fps = Math.min(fps, 12);
                bitrate = Math.min(bitrate, 400000);
            } else {
                width = Math.min(width, 960);
                height = Math.min(height, 540);
                fps = Math.min(fps, 15);
                bitrate = Math.min(bitrate, 850000);
            }
        }
        return new int[]{width, height, fps, bitrate};
    }

    private void autoAdaptQuality() {
        if (!mediaActive || cameraCapturer == null) return;
        int[] quality = calculateQuality(getSharedPreferences("wsc", MODE_PRIVATE));
        if (quality[0] == currentWidth && quality[1] == currentHeight &&
            quality[2] == currentFps && quality[3] == currentBitrate) return;
        try {
            cameraCapturer.changeCaptureFormat(quality[0], quality[1], quality[2]);
            currentWidth = quality[0];
            currentHeight = quality[1];
            currentFps = quality[2];
            currentBitrate = quality[3];
            applySenderBitrate(quality[3], quality[2]);
            notifyStatus("端末温度・バッテリー・ネットワーク状態に合わせて画質を自動調整しました。");
            sendDataMessage("STATUS", collectStatus());
        } catch (Exception error) {
            try {
                cameraCapturer.changeCaptureFormat(640, 480, 12);
                currentWidth = 640;
                currentHeight = 480;
                currentFps = 12;
                currentBitrate = Math.min(quality[3], 400000);
                applySenderBitrate(currentBitrate, currentFps);
                notifyStatus("通信状態が不安定なため低負荷画質へ変更しました。");
            } catch (Exception ignored) {
                notifyStatus("自動画質調整に失敗しました: " + error.getMessage());
            }
        }
    }

    private int[] startCaptureWithFallback(int[] requested) throws Exception {
        int[][] attempts = {
            requested,
            {1280, 720, 24, Math.min(requested[3], 1400000)},
            {640, 480, 15, Math.min(requested[3], 600000)}
        };
        Exception last = null;
        for (int i = 0; i < attempts.length; i++) {
            try {
                cameraCapturer.startCapture(attempts[i][0], attempts[i][1], attempts[i][2]);
                if (i > 0) notifyStatus("指定画質に非対応のため " + attempts[i][0] + "×" + attempts[i][1] + " に変更しました。");
                return attempts[i];
            } catch (Exception error) {
                last = error;
            }
        }
        throw last == null ? new Exception("カメラ起動に失敗しました。") : last;
    }

    private void applySenderBitrate(int maxBitrate, int maxFps) {
        try {
            if (videoSender == null) return;
            RtpParameters parameters = videoSender.getParameters();
            for (RtpParameters.Encoding encoding : parameters.encodings) {
                encoding.maxBitrateBps = maxBitrate;
                encoding.maxFramerate = maxFps;
            }
            videoSender.setParameters(parameters);
        } catch (Exception error) {
            notifyStatus("ビットレート調整は端末側で適用できませんでした。");
        }
    }

    private List<PeerConnection.IceServer> buildIceServers(SharedPreferences prefs) {
        ArrayList<PeerConnection.IceServer> servers = new ArrayList<>();
        String stored = prefs.getString("iceServers", "");
        try {
            JSONArray configured = new JSONArray(stored);
            for (int i = 0; i < configured.length(); i++) {
                JSONObject item = configured.getJSONObject(i);
                Object urlsValue = item.opt("urls");
                ArrayList<String> urls = new ArrayList<>();
                if (urlsValue instanceof JSONArray) {
                    JSONArray urlArray = (JSONArray) urlsValue;
                    for (int j = 0; j < urlArray.length(); j++) {
                        String url = urlArray.optString(j, "");
                        if (!url.isEmpty()) urls.add(url);
                    }
                } else if (urlsValue instanceof String && !((String) urlsValue).isEmpty()) {
                    urls.add((String) urlsValue);
                }
                String username = item.optString("username", "");
                String credential = item.optString("credential", "");
                for (String url : urls) {
                    String lower = url.toLowerCase(Locale.ROOT);
                    if (!lower.startsWith("stun:") && !lower.startsWith("stuns:") &&
                        !lower.startsWith("turn:") && !lower.startsWith("turns:")) continue;
                    PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(url);
                    if (!username.isEmpty() && !credential.isEmpty()) {
                        builder.setUsername(username);
                        builder.setPassword(credential);
                    }
                    servers.add(builder.createIceServer());
                }
            }
        } catch (Exception error) {
            notifyStatus("ICE設定を読み込めません。STUN設定を使用します。");
        }
        if (servers.isEmpty()) {
            servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
            servers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        }
        return servers;
    }

    private VideoCapturer createCameraCapturer() {
        if (Camera2Enumerator.isSupported(this)) {
            try {
                VideoCapturer capturer = findCapturer(new Camera2Enumerator(this));
                if (capturer != null) return capturer;
            } catch (RuntimeException error) {
                notifyStatus("Camera2を利用できないためCamera1を試します。");
            }
        }
        try {
            return findCapturer(new Camera1Enumerator(true));
        } catch (RuntimeException error) {
            notifyStatus("Camera1初期化に失敗しました: " + error.getMessage());
            return null;
        }
    }

    private VideoCapturer findCapturer(CameraEnumerator enumerator) {
        String[] names = enumerator.getDeviceNames();
        for (String name : names) {
            if (enumerator.isBackFacing(name)) {
                try {
                    VideoCapturer capturer = enumerator.createCapturer(name, null);
                    if (capturer != null) return capturer;
                } catch (RuntimeException ignored) {}
            }
        }
        for (String name : names) {
            if (enumerator.isFrontFacing(name)) {
                try {
                    VideoCapturer capturer = enumerator.createCapturer(name, null);
                    if (capturer != null) return capturer;
                } catch (RuntimeException ignored) {}
            }
        }
        return null;
    }

    private void recordVideoFrame(VideoFrame frame) {
        long now = System.currentTimeMillis();
        lastFrameAt = now;
        lastFrameWidth = frame.getBuffer().getWidth();
        lastFrameHeight = frame.getBuffer().getHeight();
        if (fpsWindowStart == 0L) fpsWindowStart = now;
        framesInWindow++;
        if (now - fpsWindowStart >= 1000L) {
            observedFps = Math.round(framesInWindow * 1000f / Math.max(1L, now - fpsWindowStart));
            framesInWindow = 0;
            fpsWindowStart = now;
        }
    }

    private void monitorVideoFrames() {
        if (!mediaActive || cameraCapturer == null || cameraStartAt == 0L) return;
        long now = System.currentTimeMillis();
        long reference = lastFrameAt > 0L ? lastFrameAt : cameraStartAt;
        if (now - reference < 10000L) return;
        if (cameraRecoveryCount >= 2) {
            sendError("映像フレームが停止し、再初期化を2回試しても復旧しませんでした。安全のため映像・音声を停止しました。");
            stopMediaOnly();
            return;
        }
        cameraRecoveryCount++;
        notifyStatus("映像停止を検知。カメラを再初期化しています（" + cameraRecoveryCount + "/2）。");
        try {
            cameraCapturer.stopCapture();
            int[] quality = startCaptureWithFallback(calculateQuality(getSharedPreferences("wsc", MODE_PRIVATE)));
            cameraStartAt = now;
            lastFrameAt = 0L;
            applySenderBitrate(quality[3], quality[2]);
        } catch (Exception error) {
            notifyStatus("カメラ再初期化に失敗しました: " + error.getMessage());
        }
    }

    private void stopWaitingAndService() {
        getSharedPreferences("wsc", MODE_PRIVATE).edit().putBoolean("standbyActive", false).apply();
        stopMediaOnlyInternal(false);
        serviceActive = false;
        final String oldSession = sessionId;
        if (!oldSession.isEmpty() && !network.isShutdown()) {
            try {
                network.execute(() -> {
                    try {
                        SharedPreferences prefs = getSharedPreferences("wsc", MODE_PRIVATE);
                        JSONObject request = auth(prefs);
                        request.put("action", "sendSignal");
                        request.put("fromRole", "android");
                        request.put("type", "bye");
                        request.put("sessionId", oldSession);
                        request.put("payload", "");
                        MainActivity.post(prefs.getString("gasUrl", ""), request);
                    } catch (Exception ignored) {
                    } finally {
                        stopSelf();
                    }
                });
                return;
            } catch (Exception ignored) {}
        }
        stopSelf();
    }

    private void closePeerOnly() {
        if (signalPollFuture != null) signalPollFuture.cancel(false);
        signalPollFuture = null;
        if (offerRenewFuture != null) offerRenewFuture.cancel(false);
        offerRenewFuture = null;
        if (telemetryFuture != null) telemetryFuture.cancel(false);
        telemetryFuture = null;

        if (controlChannel != null) {
            try { controlChannel.unregisterObserver(); } catch (Exception ignored) {}
            try { controlChannel.close(); } catch (Exception ignored) {}
            try { controlChannel.dispose(); } catch (Exception ignored) {}
        }
        controlChannel = null;
        controlObserver = null;

        if (peer != null) {
            try { peer.close(); } catch (Exception ignored) {}
            try { peer.dispose(); } catch (Exception ignored) {}
        }
        peer = null;
        audioTransceiver = null;
        videoTransceiver = null;
        audioSender = null;
        videoSender = null;
        remoteAudioTrack = null;
        offerQueued = false;
        remoteDescriptionSet = false;
        sessionId = "";
    }

    private void fail(String message) {
        serviceActive = false;
        mediaActive = false;
        getSharedPreferences("wsc", MODE_PRIVATE).edit()
            .putBoolean("standbyActive", false)
            .putString("lastMediaError", message)
            .putLong("lastMediaErrorAt", System.currentTimeMillis())
            .apply();
        releaseMediaResources();
        sendError(message);
        notifyStatus(message);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL, "RTC待機・映像音声", NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
            this, 43, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent mediaStop = new Intent(this, WebRtcSessionService.class);
        mediaStop.setAction(ACTION_STOP);
        PendingIntent mediaStopPending = PendingIntent.getService(
            this, 42, mediaStop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent waitStop = new Intent(this, WebRtcSessionService.class);
        waitStop.setAction(ACTION_STOP_WAIT);
        PendingIntent waitStopPending = PendingIntent.getService(
            this, 44, waitStop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this);

        builder.setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("WebRTC Security Camera")
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true);

        if (mediaActive) {
            builder.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                "映像・音声を切断", mediaStopPending
            ).build());
        }
        builder.addAction(new Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "待機を終了", waitStopPending
        ).build());
        return builder.build();
    }

    private void notifyStatus(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void updateNotification(String text) {
        notifyStatus(text);
    }

    @Override public void onDestroy() {
        serviceActive = false;
        mediaActive = false;
        if (signalPollFuture != null) signalPollFuture.cancel(true);
        if (offerRenewFuture != null) offerRenewFuture.cancel(true);
        if (telemetryFuture != null) telemetryFuture.cancel(true);
        if (watchdogFuture != null) watchdogFuture.cancel(true);
        if (scheduleFuture != null) scheduleFuture.cancel(true);
        releaseMediaResources();
        closePeerOnly();
        startup.shutdownNow();
        io.shutdownNow();
        watchdog.shutdownNow();
        network.shutdownNow();
        if (factory != null) {
            try { factory.dispose(); } catch (Exception ignored) {}
            factory = null;
        }
        if (egl != null) {
            try { egl.release(); } catch (Exception ignored) {}
            egl = null;
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
