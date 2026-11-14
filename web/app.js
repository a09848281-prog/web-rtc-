(() => {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const video = $("remoteVideo");
  const urlInput = $("gasUrl");
  const apiState = $("apiState");
  const devicesElement = $("devices");
  const messageElement = $("message");
  const TOKEN_KEY = "wsc_browser_devices_v2";

  const state = {
    selected: null,
    pc: null,
    dataChannel: null,
    sessionId: null,
    connecting: false,
    remoteStream: null,
    audioTransceiver: null,
    localMicStream: null,
    localMicTrack: null,
    pttHeld: false,
    muted: false,
    mediaActive: false,
    statsTimer: null,
    darkTimer: null,
    lastFrames: 0,
    lastBytes: 0,
    lastFrameAt: 0,
    darkCount: 0,
    signalsFinished: false
  };

  const savedUrl = localStorage.getItem("wsc_gas_url") || window.RDC_CONFIG?.GAS_URL || "";
  urlInput.value = savedUrl;
  video.autoplay = true;
  video.playsInline = true;
  video.muted = false;

  function config() {
    const url = urlInput.value.trim();
    if (!/^https:\/\/script\.google\.com\/macros\/s\/[^\s]+\/exec$/.test(url)) {
      throw new Error("GAS WebアプリURLを正しく入力してください。");
    }
    return { url };
  }

  function readBrowserDevices() {
    try {
      const value = JSON.parse(localStorage.getItem(TOKEN_KEY) || "[]");
      return Array.isArray(value) ? value.filter(x =>
        x && typeof x.deviceId === "string" && typeof x.webToken === "string"
      ) : [];
    } catch (_) {
      return [];
    }
  }

  function saveBrowserDevices(list) {
    localStorage.setItem(TOKEN_KEY, JSON.stringify(list));
  }

  function findBrowserDevice(deviceId) {
    return readBrowserDevices().find(x => x.deviceId === deviceId) || null;
  }

  function tokenMap() {
    const map = {};
    for (const item of readBrowserDevices()) map[item.deviceId] = item.webToken;
    return map;
  }

  async function api(action, data = {}) {
    const c = config();
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    const requestId = "wsc-" + Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
    const target = requestId.replace(/[^a-zA-Z0-9_-]/g, "");

    return new Promise((resolve, reject) => {
      const frame = document.createElement("iframe");
      const form = document.createElement("form");
      frame.name = target;
      frame.title = "GAS通信";
      frame.hidden = true;
      form.method = "POST";
      form.action = c.url;
      form.target = target;
      form.hidden = true;

      const payload = { action, ...data };
      for (const [name, value] of Object.entries({
        rdcPayload: JSON.stringify(payload),
        rdcRequestId: requestId
      })) {
        const input = document.createElement("input");
        input.type = "hidden";
        input.name = name;
        input.value = value;
        form.append(input);
      }

      let done = false;
      const cleanup = () => {
        window.removeEventListener("message", listener);
        clearTimeout(timer);
        form.remove();
        frame.remove();
      };
      const finish = (fn, value) => {
        if (done) return;
        done = true;
        cleanup();
        fn(value);
      };
      const listener = (event) => {
        const packet = event.data;
        if (event.source !== frame.contentWindow || !packet ||
            packet.channel !== "rdc-api" || packet.requestId !== requestId) return;
        const result = packet.response;
        if (!result || typeof result !== "object") {
          finish(reject, new Error("GASから不正な応答が返りました。"));
        } else if (!result.ok) {
          finish(reject, new Error(result.error || "GAS処理に失敗しました。"));
        } else {
          finish(resolve, result);
        }
      };
      const timer = setTimeout(() => {
        finish(reject, new Error("GAS応答がタイムアウトしました。公開設定と通信を確認してください。"));
      }, 20000);

      window.addEventListener("message", listener);
      document.body.append(frame, form);
      form.submit();
    });
  }

  function setStatus(text) {
    $("commandStatus").textContent = text;
  }

  function setConnection(text, kind = "idle") {
    $("connectionState").textContent = text;
    $("connectionDot").className = "status-dot" +
      (kind === "online" ? " online" : kind === "error" ? " error" : "");
  }

  function setBadge(text) {
    $("mediaBadge").textContent = text;
  }

  function setMicState(text, allowed = false) {
    $("micState").textContent = text;
    $("micState").classList.toggle("allowed", allowed);
    $("webAudioState").textContent = "Webマイク: " + text;
  }

  function renderDevices(list) {
    devicesElement.replaceChildren();
    if (!list.length) {
      const p = document.createElement("p");
      p.className = "empty";
      p.textContent = "このブラウザに登録済み端末はありません。新規登録してください。";
      devicesElement.append(p);
      return;
    }

    for (const device of list) {
      const card = document.createElement("article");
      const info = document.createElement("div");
      const name = document.createElement("strong");
      const meta = document.createElement("small");
      const button = document.createElement("button");
      card.className = "device" +
        (state.selected?.deviceId === device.deviceId ? " selected" : "");
      name.textContent = device.name || "Android";
      meta.textContent = "登録済み · RTC待機状態は接続時に確認";
      button.textContent = state.selected?.deviceId === device.deviceId ? "選択中" : "選択";
      button.addEventListener("click", () => selectDevice(device));
      info.append(name, meta);
      card.append(info, button);
      devicesElement.append(card);
    }
  }

  function displayTelemetry(s = {}) {
    const value = (id, text) => { $(id).textContent = text; };
    const battery = Number(s.batteryPct);
    value("batteryValue", Number.isFinite(battery) ? Math.round(battery) + "%" : "取得不可");
    value("chargingValue", s.charging === true ? "充電中" :
      s.charging === false ? "未充電" : "取得不可");

    const temperature = Number(s.batteryTempC);
    value("temperatureValue",
      (Number.isFinite(temperature) ? temperature.toFixed(1) + "℃" : "取得不可") +
      " / " + (s.thermalStatus || "取得不可"));
    value("networkValue", s.networkType || "取得不可");

    const cpu = Number(s.cpuLoadPct);
    const used = Number(s.memoryUsedMb);
    const total = Number(s.memoryTotalMb);
    value("loadValue",
      (Number.isFinite(cpu) ? Math.round(cpu) + "%" : "取得不可") + " / " +
      (Number.isFinite(used) && Number.isFinite(total)
        ? Math.round(used) + "/" + Math.round(total) + "MB" : "取得不可"));

    const fps = Number(s.fps);
    const width = Number(s.width);
    const height = Number(s.height);
    value("videoValue",
      (Number.isFinite(fps) && fps > 0 ? Math.round(fps) + "fps" : "取得不可") +
      " / " + (width > 0 && height > 0 ? width + "×" + height : "取得不可"));

    const warning = $("healthWarning");
    if (Number.isFinite(battery) && battery <= 20) {
      warning.textContent = "Androidのバッテリー残量が20%以下です。";
      warning.hidden = false;
    } else if (["SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN"]
      .includes(String(s.thermalStatus || "").toUpperCase())) {
      warning.textContent = "Androidが高温状態です。安全保護のため映像・音声を停止しました。";
      warning.hidden = false;
    } else {
      warning.hidden = true;
    }
  }

  async function refreshDevices() {
    try {
      const result = await api("listDevices", { deviceTokens: tokenMap() });
      window.__wscDevices = result.devices || [];
      renderDevices(window.__wscDevices);
      apiState.textContent = "GASペアリング接続済み";
      setConnection("RTC接続待機", "online");
      $("message").textContent = "";
    } catch (error) {
      window.__wscDevices = [];
      apiState.textContent = "シグナリング接続エラー";
      setConnection("サーバー接続エラー", "error");
      messageElement.textContent = error.message;
    }
  }

  function selectDevice(device) {
    if (state.selected?.deviceId !== device.deviceId) {
      stopMedia(false);
      closePeer();
    }
    state.selected = device;
    $("controlPanel").hidden = false;
    $("selectedName").textContent = device.name || "Android端末";
    $("viewerTitle").textContent = device.name || "ライブ映像";
    $("viewerSub").textContent = "登録済み端末を選択中。RTC待機接続を確認してください。";
    renderDevices(window.__wscDevices || [device]);
    setStatus("「接続・映像開始」を押すと、RTCデータチャネル経由でAndroidへ起動要求を送ります。");
  }

  function authSignal(type, payload, sessionId = state.sessionId) {
    const credential = findBrowserDevice(state.selected?.deviceId);
    if (!credential) throw new Error("このブラウザの端末アクセスキーがありません。Androidを再登録してください。");
    return api("sendSignal", {
      fromRole: "web",
      deviceId: credential.deviceId,
      webToken: credential.webToken,
      type,
      payload,
      sessionId
    });
  }

  async function pollInitialSignals() {
    const credential = findBrowserDevice(state.selected?.deviceId);
    if (!credential) throw new Error("アクセスキーが見つかりません。Androidを再登録してください。");
    return api("pollSignals", {
      role: "web",
      deviceId: credential.deviceId,
      webToken: credential.webToken
    });
  }

  async function pollSessionSignals() {
    if (!state.pc || !state.sessionId) return;
    const credential = findBrowserDevice(state.selected?.deviceId);
    if (!credential) return;
    const result = await api("pollSignals", {
      role: "web",
      deviceId: credential.deviceId,
      webToken: credential.webToken,
      sessionId: state.sessionId
    });
    for (const signal of result.signals || []) {
      if (signal.type === "ice" && state.pc) {
        try {
          await state.pc.addIceCandidate(new RTCIceCandidate(JSON.parse(signal.payload)));
        } catch (error) {
          setStatus("ICE候補を追加できませんでした: " + error.message);
        }
      } else if (signal.type === "bye") {
        setStatus("Android側がRTC接続を終了しました。");
        setConnection("切断", "error");
      }
    }
  }

  function closePeer() {
    clearInterval(state.statsTimer);
    clearInterval(state.darkTimer);
    state.statsTimer = null;
    state.darkTimer = null;

    if (state.dataChannel) {
      try { state.dataChannel.close(); } catch (_) {}
    }
    if (state.pc) {
      try { state.pc.close(); } catch (_) {}
    }
    state.dataChannel = null;
    state.pc = null;
    state.sessionId = null;
    state.audioTransceiver = null;
    state.remoteStream = null;
    state.signalsFinished = false;
    state.mediaActive = false;
    video.pause();
    video.srcObject = null;
    $("videoPlaceholder").hidden = false;
    $("staleBadge").hidden = true;
    $("switchCamera").disabled = true;
    $("androidAudioState").textContent = "Android音声: 未接続";
    $("resolutionOverlay").textContent = "解像度 --";
    $("fpsOverlay").textContent = "FPS --";
    $("iceOverlay").textContent = "RTC --";
    setBadge("未接続");
  }

  function sendControl(type, payload = {}) {
    const channel = state.dataChannel;
    if (!channel || channel.readyState !== "open") {
      throw new Error("RTC待機チャネルが接続されていません。Android側の待機とWeb画面を確認してください。");
    }
    channel.send(JSON.stringify({ type, payload, at: Date.now() }));
  }

  function handleControlMessage(raw) {
    let packet;
    try { packet = JSON.parse(raw); } catch (_) { return; }
    const type = String(packet.type || "");
    const payload = packet.payload && typeof packet.payload === "object" ? packet.payload : {};
    if (type === "STATUS") {
      displayTelemetry(payload);
      if (payload.mediaActive === true) {
        state.mediaActive = true;
        setBadge("ライブ");
        $("switchCamera").disabled = false;
      } else if (payload.mediaActive === false) {
        state.mediaActive = false;
        setBadge("RTC待機中");
        $("switchCamera").disabled = true;
      }
      $("pttButton").disabled = !state.mediaActive || !state.audioTransceiver;
      if (payload.message) setStatus(String(payload.message));
    } else if (type === "MEDIA_STARTED") {
      state.mediaActive = true;
      $("pttButton").disabled = !state.audioTransceiver;
      setBadge("ライブ");
      $("videoPlaceholder").hidden = false;
      $("staleBadge").hidden = true;
      $("switchCamera").disabled = false;
      setStatus("Androidのカメラ・マイクを起動しました。映像の到着を待っています。");
    } else if (type === "MEDIA_STOPPED") {
      state.mediaActive = false;
      $("pttButton").disabled = true;
      setBadge("RTC待機中");
      $("videoPlaceholder").hidden = false;
      $("staleBadge").hidden = true;
      $("switchCamera").disabled = true;
      $("androidAudioState").textContent = "Android音声: 停止";
      setStatus("映像・音声を停止しました。RTC待機接続は維持しています。");
    } else if (type === "ERROR") {
      setStatus(payload.message || "Android側でエラーが発生しました。");
    } else if (type === "NOTICE") {
      setStatus(payload.message || "Androidから通知を受信しました。");
    } else if (type === "CAMERA") {
      setStatus(payload.message || "カメラ状態が更新されました。");
    }
  }

  function configureDataChannel(channel) {
    state.dataChannel = channel;
    channel.onopen = () => {
      setConnection("RTC待機接続済み", "online");
      apiState.textContent = "RTC直接接続";
      setBadge("RTC待機中");
      $("viewerSub").textContent = "AndroidとのRTC待機接続が確立しました。接続要求はデータチャネルで送信します。";
      setStatus("RTC待機接続済み。映像を開始するときだけ「接続・映像開始」を押してください。");
      $("viewBtn").disabled = false;
      $("endViewBtn").disabled = false;
      sendControl("GET_STATUS");
    };
    channel.onmessage = (event) => handleControlMessage(event.data);
    channel.onclose = () => {
      setConnection("RTC待機接続が切断されました", "error");
      setBadge("RTC切断");
      setStatus("RTC待機接続が切断されました。Androidの待機を再開し、Webで再接続してください。");
      stopPtt();
    };
    channel.onerror = () => setStatus("RTCデータチャネルで通信エラーが発生しました。");
  }

  function configurePeerConnection(pc) {
    pc.ondatachannel = (event) => {
      if (event.channel.label === "wsc-control") configureDataChannel(event.channel);
    };

    pc.ontrack = (event) => {
      if (!state.remoteStream) state.remoteStream = new MediaStream();
      const stream = event.streams && event.streams[0];
      if (stream) {
        for (const track of stream.getTracks()) {
          if (!state.remoteStream.getTracks().some(existing => existing.id === track.id)) {
            state.remoteStream.addTrack(track);
          }
        }
      } else if (!state.remoteStream.getTracks().some(track => track.id === event.track.id)) {
        state.remoteStream.addTrack(event.track);
      }
      video.srcObject = state.remoteStream;
      if (event.track.kind === "audio") {
        $("androidAudioState").textContent = "Android音声: 受信トラック接続";
        event.track.onunmute = () => $("androidAudioState").textContent = "Android音声: 受信中";
        event.track.onmute = () => $("androidAudioState").textContent = "Android音声: ミュート/停止";
      }
      if (event.track.kind === "video") {
        event.track.onunmute = () => {
          $("videoPlaceholder").hidden = false;
          video.play().catch(() => setStatus("映像受信中です。音声再生ボタンを押してください。"));
        };
      }
      video.play().catch(() => {});
    };

    pc.oniceconnectionstatechange = () => {
      $("iceOverlay").textContent = "ICE " + pc.iceConnectionState;
      if (pc.iceConnectionState === "connected" || pc.iceConnectionState === "completed") {
        setConnection("RTC接続済み", "online");
      } else if (pc.iceConnectionState === "failed") {
        setConnection("RTC接続失敗", "error");
        setStatus("直接接続できません。ネットワークによってはTURNサーバーが必要です。");
      } else if (pc.iceConnectionState === "disconnected") {
        setConnection("RTC再接続中", "error");
      }
    };

    pc.onconnectionstatechange = () => {
      if (pc.connectionState === "connected") {
        setConnection("RTC接続済み", "online");
      } else if (pc.connectionState === "failed" || pc.connectionState === "closed") {
        setConnection("RTC切断", "error");
        setBadge("RTC切断");
        $("staleBadge").hidden = false;
      }
    };

    pc.onicecandidate = (event) => {
      if (!event.candidate) return;
      const payload = JSON.stringify(event.candidate.toJSON());
      if (!state.sessionId || !state.signalsFinished) {
        if (!pc.__wscLocalIce) pc.__wscLocalIce = [];
        pc.__wscLocalIce.push(payload);
      } else {
        authSignal("ice", payload).catch(error => setStatus("ICE候補送信失敗: " + error.message));
      }
    };
  }

  async function connect() {
    if (!state.selected) {
      setStatus("先にAndroid端末を選択してください。");
      return;
    }
    if (state.connecting) return;

    if (state.dataChannel && state.dataChannel.readyState === "open") {
      try {
        sendControl("START_MEDIA");
        state.mediaActive = true;
        setBadge("起動要求中");
        setConnection("Androidへ映像開始要求を送信", "online");
        setStatus("RTCデータチャネル経由でAndroidへカメラ・マイク開始要求を送りました。");
      } catch (error) {
        setStatus(error.message);
      }
      return;
    }

    closePeer();
    state.connecting = true;
    $("viewBtn").disabled = true;
    setBadge("RTCペアリング中");
    setConnection("RTC接続情報を交換中");
    setStatus("Androidが発行したRTC接続情報を取得しています。Android側でバックグラウンド待機を開始してください。");

    const pc = new RTCPeerConnection({
      iceServers: [
        { urls: "stun:stun.l.google.com:19302" },
        { urls: "stun:stun1.l.google.com:19302" }
      ],
      bundlePolicy: "max-bundle",
      rtcpMuxPolicy: "require",
      iceCandidatePoolSize: 4
    });
    state.pc = pc;
    state.remoteStream = new MediaStream();
    configurePeerConnection(pc);

    try {
      const deadline = Date.now() + 90000;
      let offerSignal = null;
      while (Date.now() < deadline && state.pc === pc && !offerSignal) {
        const result = await pollInitialSignals();
        offerSignal = (result.signals || []).find(signal => signal.type === "offer") || null;
        if (!offerSignal) await new Promise(resolve => setTimeout(resolve, 900));
      }
      if (!offerSignal) throw new Error("AndroidからOfferが届きません。Androidの「バックグラウンド待機を開始」を押してください。");

      state.sessionId = offerSignal.sessionId;
      await pc.setRemoteDescription(new RTCSessionDescription({
        type: "offer",
        sdp: offerSignal.payload
      }));

      const transceivers = pc.getTransceivers();
      state.audioTransceiver = transceivers.find(t => t.receiver && t.receiver.track && t.receiver.track.kind === "audio") || null;
      const videoTransceiver = transceivers.find(t => t.receiver && t.receiver.track && t.receiver.track.kind === "video") || null;
      if (state.audioTransceiver) state.audioTransceiver.direction = "sendrecv";
      if (videoTransceiver) videoTransceiver.direction = "recvonly";

      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      await authSignal("answer", answer.sdp, state.sessionId);
      state.signalsFinished = true;

      const queued = pc.__wscLocalIce || [];
      for (const candidate of queued) await authSignal("ice", candidate, state.sessionId);
      pc.__wscLocalIce = [];

      const handshakeDeadline = Date.now() + 90000;
      while (Date.now() < handshakeDeadline && state.pc === pc &&
             (!state.dataChannel || state.dataChannel.readyState !== "open") &&
             pc.connectionState !== "failed") {
        await pollSessionSignals();
        await new Promise(resolve => setTimeout(resolve, 1000));
      }
      if (!state.dataChannel || state.dataChannel.readyState !== "open") {
        throw new Error("RTC待機接続を確立できませんでした。ネットワーク制限やTURNサーバーの必要性を確認してください。");
      }

      if (state.localMicTrack && state.audioTransceiver) {
        await state.audioTransceiver.sender.replaceTrack(state.localMicTrack);
        state.localMicTrack.enabled = false;
      }
      state.statsTimer = setInterval(() => updateStats(pc), 2000);
      $("pttButton").disabled = !state.audioTransceiver || !state.mediaActive;
      startDarkCheck();
      sendControl("START_MEDIA");
      state.mediaActive = true;
      setBadge("起動要求中");
      setStatus("RTC待機チャネルが接続しました。カメラ・マイク開始要求をAndroidへ送信しました。");
    } catch (error) {
      closePeer();
      setConnection("接続失敗", "error");
      setBadge("接続失敗");
      setStatus(error.message);
    } finally {
      state.connecting = false;
      $("viewBtn").disabled = false;
    }
  }

  async function requestMicPermission() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      setMicState("このブラウザは非対応");
      setStatus("HTTPSのSafariまたはChromeで開いてください。");
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
      stream.getTracks().forEach(track => track.stop());
      setMicState("許可済み（PTT時のみ取得）", true);
      $("requestMic").textContent = "許可済み";
      $("pttButton").disabled = !state.mediaActive || !state.audioTransceiver;
      $("pttStatus").textContent = "PTTボタンを押している間だけマイクを起動します。";
    } catch (error) {
      setMicState("許可されていません");
      setStatus("マイクを使用できません: " + error.message);
    }
  }

  async function startPtt() {
    if (!state.pttHeld) return;
    if (!state.pc || state.pc.connectionState !== "connected" || !state.audioTransceiver) {
      state.pttHeld = false;
      $("pttStatus").textContent = "AndroidとのRTC接続後に使用できます。";
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
        video: false
      });
      if (!state.pttHeld) {
        stream.getTracks().forEach(track => track.stop());
        return;
      }
      state.localMicStream = stream;
      state.localMicTrack = stream.getAudioTracks()[0];
      state.localMicTrack.enabled = true;
      await state.audioTransceiver.sender.replaceTrack(state.localMicTrack);
      $("pttButton").classList.add("active");
      $("pttButton").textContent = "🎙 送信中（離すと停止）";
      $("pttStatus").textContent = "Webマイク音声をAndroidへ送信中";
      setMicState("送信中", true);
    } catch (error) {
      state.pttHeld = false;
      $("pttStatus").textContent = "マイクを開始できません: " + error.message;
      setMicState("許可が必要");
    }
  }

  function stopPtt() {
    state.pttHeld = false;
    $("pttButton").classList.remove("active");
    $("pttButton").textContent = "🎙 押して話す";
    $("pttStatus").textContent = "送信停止中";
    if (state.localMicTrack) {
      state.localMicTrack.enabled = false;
      if (state.audioTransceiver) {
        state.audioTransceiver.sender.replaceTrack(null).catch(() => {});
      }
      state.localMicStream?.getTracks().forEach(track => track.stop());
    }
    state.localMicTrack = null;
    state.localMicStream = null;
    setMicState("許可済み（PTT停止中）", true);
  }

  function stopMedia(showMessage = true) {
    stopPtt();
    if (state.dataChannel && state.dataChannel.readyState === "open") {
      try { sendControl("STOP_MEDIA"); } catch (_) {}
    }
    state.mediaActive = false;
    $("videoPlaceholder").hidden = false;
    $("staleBadge").hidden = true;
    $("switchCamera").disabled = true;
    setBadge(state.dataChannel?.readyState === "open" ? "RTC待機中" : "未接続");
    if (showMessage) setStatus("映像・音声の停止要求を送信しました。RTC待機接続は維持します。");
  }

  async function updateStats(pc) {
    if (pc !== state.pc) return;
    try {
      const stats = await pc.getStats();
      let bytes = 0, frames = 0, fps = 0, width = 0, height = 0, packetLost = 0, packets = 0, rtt = NaN;
      stats.forEach(report => {
        if (report.type === "inbound-rtp" && !report.isRemote) {
          if (report.kind === "video" || report.mediaType === "video") {
            bytes += report.bytesReceived || 0;
            frames += report.framesDecoded || 0;
            fps = report.framesPerSecond || fps;
            width = report.frameWidth || width;
            height = report.frameHeight || height;
          }
          if (report.kind === "audio" || report.mediaType === "audio") {
            packetLost += report.packetsLost || 0;
            packets += report.packetsReceived || 0;
          }
        }
        if (report.type === "candidate-pair" && report.state === "succeeded" && report.nominated) {
          if (Number.isFinite(report.currentRoundTripTime)) rtt = report.currentRoundTripTime * 1000;
        }
      });

      const moving = bytes > state.lastBytes || frames > state.lastFrames;
      if (moving) {
        state.lastFrameAt = Date.now();
        $("staleBadge").hidden = true;
        $("videoPlaceholder").hidden = video.videoWidth === 0;
      } else if (state.mediaActive && state.lastFrameAt && Date.now() - state.lastFrameAt > 6000) {
        $("staleBadge").hidden = false;
      }
      state.lastBytes = bytes;
      state.lastFrames = frames;

      const w = width || video.videoWidth;
      const h = height || video.videoHeight;
      $("resolutionOverlay").textContent = w && h ? "解像度 " + w + "×" + h : "解像度 --";
      $("fpsOverlay").textContent = "FPS " + (fps ? Math.round(fps) : "--");
      $("iceOverlay").textContent = "RTC " + pc.iceConnectionState;
      $("videoValue").textContent = (fps ? Math.round(fps) + "fps" : "取得不可") +
        " / " + (w && h ? w + "×" + h : "取得不可");
      $("qualityValue").textContent =
        (Number.isFinite(rtt) ? Math.round(rtt) + "ms" : "取得不可") +
        " / " + (packets > 0 ? Math.max(0, Math.round(packetLost / (packetLost + packets) * 100)) + "%" : "取得不可");
      $("androidAudioState").textContent = bytes > 0 ? "Android音声/映像: RTC受信中" : "Android音声: 待機中";
    } catch (_) {}
  }

  function startDarkCheck() {
    const canvas = document.createElement("canvas");
    const ctx = canvas.getContext("2d", { willReadFrequently: true });
    canvas.width = 48;
    canvas.height = 27;
    state.darkTimer = setInterval(() => {
      if (!state.mediaActive || video.readyState < 2 || !video.videoWidth) return;
      try {
        ctx.drawImage(video, 0, 0, 48, 27);
        const data = ctx.getImageData(0, 0, 48, 27).data;
        let total = 0;
        for (let i = 0; i < data.length; i += 4) total += (data[i] + data[i + 1] + data[i + 2]) / 3;
        const average = total / (data.length / 4);
        state.darkCount = average < 14 ? state.darkCount + 1 : 0;
        if (state.darkCount >= 6) {
          $("healthWarning").textContent = "暗い映像が続いています。照明とカメラの向きを確認してください。";
          $("healthWarning").hidden = false;
        }
      } catch (_) {}
    }, 5000);
  }

  async function createPair() {
    $("createPair").disabled = true;
    try {
      const result = await api("createPairCode");
      const list = readBrowserDevices();
      list.push({ deviceId: result.deviceId, webToken: result.webToken, name: "Android" });
      saveBrowserDevices(list);
      $("pairCodeText").textContent = result.pairCode;
      $("pairResult").hidden = false;
      $("message").textContent = "Androidアプリに6桁コードを入力してください。登録後「更新」を押します。";
      await refreshDevices();
    } catch (error) {
      $("message").textContent = error.message;
    } finally {
      $("createPair").disabled = false;
    }
  }

  function sendModes() {
    try {
      sendControl("SET_MODES", {
        networkMode: $("networkMode").value,
        powerMode: $("powerMode").value
      });
      setStatus("通信・電力モードをAndroidへ送信しました。");
    } catch (error) {
      setStatus(error.message);
    }
  }

  function sendSchedule() {
    try {
      sendControl("SET_SCHEDULE", {
        enabled: $("scheduleEnabled").checked,
        days: $("scheduleDays").value,
        start: $("scheduleStart").value,
        end: $("scheduleEnd").value
      });
      setStatus("スケジュールをAndroidへ送信しました。");
    } catch (error) {
      setStatus(error.message);
    }
  }

  $("refresh").addEventListener("click", refreshDevices);
  $("createPair").addEventListener("click", createPair);
  $("viewBtn").addEventListener("click", connect);
  $("endViewBtn").addEventListener("click", () => stopMedia(true));
  $("closeControl").addEventListener("click", () => {
    stopMedia(false);
    closePeer();
    state.selected = null;
    $("controlPanel").hidden = true;
    $("viewerTitle").textContent = "ライブ映像";
    $("viewerSub").textContent = "Android端末を選択してください。";
    renderDevices(window.__wscDevices || []);
    setConnection("待機中");
  });
  $("switchCamera").addEventListener("click", () => {
    try { sendControl("SWITCH_CAMERA"); } catch (error) { setStatus(error.message); }
  });
  $("muteRemote").addEventListener("click", () => {
    state.muted = !state.muted;
    video.muted = state.muted;
    $("muteRemote").textContent = state.muted ? "🔇 音声ミュート解除" : "🔊 音声ミュート";
  });
  $("fullscreen").addEventListener("click", async () => {
    try {
      if (!document.fullscreenElement) await $("videoFrame").requestFullscreen();
      else await document.exitFullscreen();
    } catch (error) {
      setStatus("全画面表示を開始できません: " + error.message);
    }
  });
  $("statusRequest").addEventListener("click", () => {
    try { sendControl("GET_STATUS"); setStatus("Androidの状態を取得しています。"); }
    catch (error) { setStatus(error.message); }
  });
  $("applyModes").addEventListener("click", sendModes);
  $("saveSchedule").addEventListener("click", sendSchedule);
  $("requestMic").addEventListener("click", requestMicPermission);
  $("enablePlayback").addEventListener("click", () => {
    video.muted = false;
    state.muted = false;
    $("muteRemote").textContent = "🔊 音声ミュート";
    video.play().then(() => setStatus("音声再生を有効にしました。"))
      .catch(error => setStatus("再生できません: " + error.message));
  });

  const ptt = $("pttButton");
  ptt.addEventListener("pointerdown", event => {
    if (ptt.disabled) return;
    event.preventDefault();
    state.pttHeld = true;
    try { ptt.setPointerCapture(event.pointerId); } catch (_) {}
    startPtt();
  });
  ["pointerup", "pointercancel", "lostpointercapture", "pointerleave"].forEach(name => {
    ptt.addEventListener(name, () => {
      if (state.pttHeld) stopPtt();
    });
  });
  window.addEventListener("blur", stopPtt);
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      stopPtt();
      stopMedia(false);
    }
  });

  $("saveUrl").addEventListener("click", async () => {
    try {
      config();
      localStorage.setItem("wsc_gas_url", urlInput.value.trim());
      await api("ping");
      apiState.textContent = "GAS接続済み";
      $("message").textContent = "GAS接続を確認しました。";
      await refreshDevices();
    } catch (error) {
      $("message").textContent = error.message;
    }
  });

  window.addEventListener("beforeunload", () => {
    stopPtt();
    if (state.dataChannel && state.dataChannel.readyState === "open") {
      try { sendControl("STOP_MEDIA"); } catch (_) {}
    }
    try { state.pc?.close(); } catch (_) {}
  });

  window.__wscDevices = [];
  refreshDevices();
})();
