(()=>{"use strict";
const $=id=>document.getElementById(id),video=$("remoteVideo");
const urlInput=$("gasUrl"),apiState=$("apiState"),devices=$("devices"),message=$("message");
const state={selected:null,pc:null,sessionId:null,pollTimer:null,statsTimer:null,listTimer:null,darkTimer:null,connecting:false,everRequested:false,remoteStream:null,micStream:null,micTrack:null,pttSender:null,pttHeld:false,muted:false,lastBytes:0,lastFrames:0,lastFrameAt:0,reconnectTried:false};
const savedUrl=localStorage.getItem("wsc_gas_url")||window.RDC_CONFIG?.GAS_URL||"";
urlInput.value=savedUrl;video.autoplay=true;video.playsInline=true;
let deviceCache=[];
function config(){const url=urlInput.value.trim();if(!/^https:\/\/script\.google\.com\/macros\/s\/[^\s]+\/exec$/.test(url))throw Error("GAS WebアプリURLを正しく入力してください。");return{url};}
async function api(action,data={}){
 const c=config(),bytes=new Uint8Array(16);crypto.getRandomValues(bytes);
 const requestId="wsc-"+Array.from(bytes,b=>b.toString(16).padStart(2,"0")).join(""),target=requestId.replace(/[^a-zA-Z0-9_-]/g,"");
 return new Promise((resolve,reject)=>{
  const frame=document.createElement("iframe"),form=document.createElement("form");
  frame.name=target;frame.title="GAS通信";frame.hidden=true;form.method="POST";form.action=c.url;form.target=target;form.hidden=true;
  for(const [name,value] of Object.entries({rdcPayload:JSON.stringify({action,...data}),rdcRequestId:requestId})){const input=document.createElement("input");input.type="hidden";input.name=name;input.value=value;form.append(input);}
  let done=false;const cleanup=()=>{window.removeEventListener("message",listener);clearTimeout(timer);form.remove();frame.remove();};
  const finish=(fn,value)=>{if(done)return;done=true;cleanup();fn(value);};
  const listener=e=>{const p=e.data;if(e.source!==frame.contentWindow||!p||p.channel!=="rdc-api"||p.requestId!==requestId)return;const result=p.response;if(!result||typeof result!=="object")return finish(reject,new Error("GASから不正な応答が返りました。"));if(!result.ok)return finish(reject,new Error(result.error||"GAS処理に失敗しました。"));finish(resolve,result);};
  const timer=setTimeout(()=>finish(reject,new Error("GAS応答がタイムアウトしました。GASの公開設定と通信を確認してください。")),20000);
  window.addEventListener("message",listener);document.body.append(frame,form);form.submit();
 });
}
const status=(text)=>{$("commandStatus").textContent=text;};
const connection=(text,kind="idle")=>{$("connectionState").textContent=text;$("connectionDot").className="status-dot"+(kind==="online"?" online":kind==="error"?" error":"");};
const badge=text=>$("mediaBadge").textContent=text;
const setMicState=(text,allowed=false)=>{$("micState").textContent=text;$("micState").classList.toggle("allowed",allowed);$("webAudioState").textContent="Webマイク: "+text;};
function render(list){
 devices.replaceChildren();
 if(!list.length){const p=document.createElement("p");p.className="empty";p.textContent="登録済み端末はありません。登録コードを発行してください。";devices.append(p);return;}
 for(const d of list){const card=document.createElement("article"),info=document.createElement("div"),name=document.createElement("strong"),meta=document.createElement("small"),button=document.createElement("button");
 card.className="device"+(state.selected?.deviceId===d.deviceId?" selected":"");name.textContent=d.name||"Android";meta.textContent=(d.online?"● オンライン":"○ オフライン")+" · "+(d.lastSeen||"最終確認不明");
 button.textContent=state.selected?.deviceId===d.deviceId?"選択中":"選択";button.addEventListener("click",()=>selectDevice(d));info.append(name,meta);card.append(info,button);devices.append(card);
 }
}
function displayTelemetry(s={}){
 const val=(id,v)=>$(id).textContent=v;
 const n=Number(s.batteryPct);val("batteryValue",Number.isFinite(n)?Math.round(n)+"%":"取得不可");
 val("chargingValue",s.charging===true?"充電中":s.charging===false?"未充電":"取得不可");
 const t=Number(s.batteryTempC);val("temperatureValue",(Number.isFinite(t)?t.toFixed(1)+"℃":"取得不可")+" / "+(s.thermalStatus||"取得不可"));
 val("networkValue",s.networkType||"取得不可");
 const cpu=s.cpuLoadPct===null||s.cpuLoadPct===undefined?NaN:Number(s.cpuLoadPct),mem=Number(s.memoryUsedMb),total=Number(s.memoryTotalMb);
 val("loadValue",(Number.isFinite(cpu)?Math.round(cpu)+"%":"取得不可")+" / "+(Number.isFinite(mem)&&Number.isFinite(total)?Math.round(mem)+"/"+Math.round(total)+"MB":"取得不可"));
 const fps=Number(s.fps),w=Number(s.width),h=Number(s.height);
 val("videoValue",(Number.isFinite(fps)?fps+"fps":"取得不可")+" / "+(w>0&&h>0?w+"×"+h:"取得不可"));
 const warning=$("healthWarning");
 if(Number.isFinite(n)&&n<=20){warning.textContent="バッテリー残量が20%以下です。";warning.hidden=false;}
 else if(["SEVERE","CRITICAL","EMERGENCY","SHUTDOWN"].includes(String(s.thermalStatus||"").toUpperCase())){warning.textContent="Androidが高温状態です。安全保護のため監視が停止する場合があります。";warning.hidden=false;}
 else warning.hidden=true;
}
function selectDevice(d){
 if(state.selected?.deviceId!==d.deviceId){endView(true,true);state.everRequested=false;}
 state.selected=d;$("controlPanel").hidden=false;$("selectedName").textContent=d.name||"端末操作";$("viewerTitle").textContent=d.name||"ライブ映像";$("viewerSub").textContent="登録済みAndroid。接続ボタンから接続要求を送信できます。";
 displayTelemetry(d.status||{});render(deviceCache);status("接続ボタンを押すとAndroidに接続要求を送信し、応答後にWebRTCを開始します。");
}
async function refresh(){
 try{const r=await api("listDevices");deviceCache=r.devices||[];render(deviceCache);apiState.textContent="サーバー接続済み";connection("サーバー接続済み","online");
  if(state.selected){const found=deviceCache.find(x=>x.deviceId===state.selected.deviceId);if(found){state.selected=found;displayTelemetry(found.status||{});}}
 }catch(e){apiState.textContent="サーバー接続エラー";connection("サーバー接続エラー","error");$("message").textContent=e.message;}
}
async function command(cmd,payload={}){if(!state.selected)throw Error("Android端末を選択してください。");return api("queueCommand",{deviceId:state.selected.deviceId,command:cmd,payload:JSON.stringify(payload)});}
async function signal(type,payload,sid=state.sessionId){if(!state.selected)throw Error("端末を選択してください。");return api("sendSignal",{fromRole:"web",deviceId:state.selected.deviceId,type,payload,sessionId:sid});}
function stopTimers(){for(const id of [state.pollTimer,state.statsTimer,state.darkTimer])if(id)clearInterval(id);state.pollTimer=state.statsTimer=state.darkTimer=null;}
function releasePeer(){
 if(state.pc){state.pc.ontrack=null;state.pc.onconnectionstatechange=null;state.pc.close();}
 state.pc=null;state.sessionId=null;state.remoteStream=null;state.pttSender=null;
 video.pause();video.srcObject=null;$("videoPlaceholder").hidden=false;$("staleBadge").hidden=true;
 $("androidAudioState").textContent="Android音声: 未接続";$("resolutionOverlay").textContent="解像度 --";$("fpsOverlay").textContent="FPS --";$("iceOverlay").textContent="ICE --";
}
function endView(sendBye=true,stopAndroid=false){
 const sid=state.sessionId;if(sendBye&&sid&&state.selected)signal("bye","",sid).catch(()=>{});
 stopTimers();releasePeer();state.connecting=false;state.reconnectTried=false;badge("未接続");connection("待機中");
 if(stopAndroid&&state.selected){state.everRequested=false;command("stop_session").catch(()=>{});}
}
async function ensureMic(){
 if(state.micTrack&&state.micTrack.readyState==="live")return state.micTrack;
 if(!navigator.mediaDevices?.getUserMedia)throw Error("このブラウザではマイクを利用できません。HTTPSのSafariまたはChromeで開いてください。");
 const stream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true},video:false});
 state.micStream=stream;state.micTrack=stream.getAudioTracks()[0];state.micTrack.enabled=false;setMicState("許可済み",true);$("requestMic").textContent="マイク許可済み";
 if(state.pttSender)await state.pttSender.replaceTrack(state.micTrack);
 $("pttButton").disabled=!state.pttSender;
 return state.micTrack;
}
function stopPtt(){state.pttHeld=false;if(state.micTrack)state.micTrack.enabled=false;$("pttButton").classList.remove("active");$("pttButton").textContent="🎙 押して話す";$("pttStatus").textContent="送信停止中";}
async function startPtt(){
 if(!state.pttSender||!state.pc||state.pc.connectionState!=="connected"){state.pttHeld=false;status("先にAndroidとの通話を接続してください。");return;}
 try{
  const track=await ensureMic();
  if(!state.pttHeld){track.enabled=false;return;}
  track.enabled=true;$("pttButton").classList.add("active");$("pttButton").textContent="🎙 話しています（離すと停止）";$("pttStatus").textContent="Webマイク音声をAndroidへ送信中";
 }catch(e){state.pttHeld=false;$("pttStatus").textContent=e.message;setMicState("許可が必要");}
}
async function pollSignals(){
 const pc=state.pc;if(!pc||!state.selected||!state.sessionId)return;
 try{const r=await api("pollSignals",{role:"web",deviceId:state.selected.deviceId,sessionId:state.sessionId});
  for(const s of r.signals||[]){if(pc!==state.pc)return;if(s.type==="ice")await pc.addIceCandidate(new RTCIceCandidate(JSON.parse(s.payload)));else if(s.type==="bye"){status("Android側から監視が終了しました。");endView(false);return;}}
 }catch(e){status("接続情報を再取得中: "+e.message);}
}
async function updateStats(pc){
 try{const stats=await pc.getStats();let bytes=0,frames=0,fps=0,width=0,height=0,audio=false;
  stats.forEach(x=>{if(x.type==="inbound-rtp"&&!x.isRemote){if(x.kind==="video"||x.mediaType==="video"){bytes+=x.bytesReceived||0;frames+=x.framesDecoded||0;fps=x.framesPerSecond||fps;width=x.frameWidth||width;height=x.frameHeight||height;}if(x.kind==="audio"||x.mediaType==="audio")audio=(x.bytesReceived||0)>0;}});
  const moving=bytes>state.lastBytes||frames>state.lastFrames;if(moving){state.lastFrameAt=Date.now();$("staleBadge").hidden=true;}else if(state.sessionId&&state.lastFrameAt&&Date.now()-state.lastFrameAt>5000){$("staleBadge").hidden=false;}
  state.lastBytes=bytes;state.lastFrames=frames;
  const w=width||video.videoWidth,h=height||video.videoHeight;
  $("resolutionOverlay").textContent=w&&h?"解像度 "+w+"×"+h:"解像度 --";$("fpsOverlay").textContent="FPS "+(fps?fps.toFixed(0):"--");$("iceOverlay").textContent="ICE "+pc.iceConnectionState;
  $("videoValue").textContent=(fps?fps.toFixed(0)+"fps":"取得不可")+" / "+(w&&h?w+"×"+h:"取得不可");
  $("androidAudioState").textContent="Android音声: "+(audio?"受信中":"待機中");
 }catch(_){}
}
function startDarkCheck(){
 const canvas=document.createElement("canvas"),ctx=canvas.getContext("2d",{willReadFrequently:true});canvas.width=48;canvas.height=27;let consecutive=0;
 state.darkTimer=setInterval(()=>{if(video.readyState<2||!video.videoWidth)return;try{ctx.drawImage(video,0,0,48,27);const data=ctx.getImageData(0,0,48,27).data;let sum=0;for(let i=0;i<data.length;i+=4)sum+=(data[i]+data[i+1]+data[i+2])/3;const avg=sum/(data.length/4);consecutive=avg<14?consecutive+1:0;if(consecutive>=8){$("healthWarning").textContent="暗い映像が続いています。照明とカメラの向きを確認してください。";$("healthWarning").hidden=false;}}catch(_){}},5000);
}
async function connect(forceRestart=false){
 if(!state.selected){status("先にAndroid端末を選択してください。");return;}
 if(state.connecting)return;
 const restart=forceRestart||state.everRequested||!!state.sessionId||!!state.pc;
 const oldSession=state.sessionId;
 if(oldSession)signal("bye","",oldSession).catch(()=>{});
 stopTimers();releasePeer();state.connecting=true;badge("接続要求中");connection("Androidへ接続要求中");
 status(restart?"再接続要求を送信しています。":"Androidへ自動接続要求を送信しています。OSがカメラ・マイクの起動を許可すると映像が開始します。");
 try{
  await command(restart?"restart_session":"start_session");
  state.everRequested=true;
  const iceServers=[{urls:["stun:stun.l.google.com:19302","stun:stun1.l.google.com:19302"]}];
  const pc=new RTCPeerConnection({iceServers,bundlePolicy:"max-bundle",rtcpMuxPolicy:"require",iceCandidatePoolSize:4});
  state.pc=pc;state.remoteStream=new MediaStream();
  pc.addTransceiver("audio",{direction:"sendrecv"});
  let answerSent=false;const queued=[];
  pc.ontrack=e=>{
   const stream=e.streams?.[0];if(stream)state.remoteStream=stream;else if(!state.remoteStream.getTracks().some(t=>t.id===e.track.id))state.remoteStream.addTrack(e.track);
   video.srcObject=state.remoteStream;video.muted=state.muted;video.playsInline=true;$("videoPlaceholder").hidden=true;
   if(e.track.kind==="audio")$("androidAudioState").textContent="Android音声: 受信トラックあり";
   e.track.onended=()=>status(e.track.kind==="video"?"Android映像が終了しました。":"Android音声が終了しました。");
   video.play().catch(()=>status("映像トラックは届いています。ブラウザの再生許可を確認してください。"));
  };
  pc.onicecandidate=e=>{if(!e.candidate||!state.sessionId)return;const payload=JSON.stringify(e.candidate.toJSON());if(!answerSent)queued.push(payload);else signal("ice",payload).catch(err=>status("ICE送信失敗: "+err.message));};
  pc.onconnectionstatechange=()=>{
   if(pc!==state.pc)return;
   if(pc.connectionState==="connected"){badge("ライブ");connection("WebRTC接続済み","online");status("ライブ映像・音声に接続しました。");$("viewerSub").textContent="ライブストリームを受信しています。";state.lastFrameAt=Date.now();state.reconnectTried=false;}
   else if(pc.connectionState==="failed"||pc.connectionState==="disconnected"){badge("切断");connection("通信切断","error");status("通信が切断されました。Android待機状態とネットワークを確認してください。");$("staleBadge").hidden=false;
    if(pc.connectionState==="failed"&&!state.reconnectTried){state.reconnectTried=true;setTimeout(()=>{if(state.selected&&state.pc===pc){connect(true);}},2500);}
   }else if(pc.connectionState==="connecting"){badge("接続中");connection("WebRTC接続中");}
  };
  const deadline=Date.now()+100000;
  while(Date.now()<deadline&&state.pc===pc&&!state.sessionId){
   const r=await api("pollSignals",{role:"web",deviceId:state.selected.deviceId});
   for(const s of r.signals||[]){
    if(s.type==="offer"&&!state.sessionId){
     state.sessionId=s.sessionId;await pc.setRemoteDescription(new RTCSessionDescription({type:"offer",sdp:s.payload}));
     const audioTransceiver=pc.getTransceivers().find(t=>t.receiver?.track?.kind==="audio");
     if(audioTransceiver){audioTransceiver.direction="sendrecv";state.pttSender=audioTransceiver.sender;if(state.micTrack)await state.pttSender.replaceTrack(state.micTrack);}
     const answer=await pc.createAnswer();await pc.setLocalDescription(answer);await signal("answer",answer.sdp,state.sessionId);answerSent=true;
     for(const c of queued)await signal("ice",c,state.sessionId);queued.length=0;
    }else if(s.type==="ice"&&state.sessionId===s.sessionId){await pc.addIceCandidate(new RTCIceCandidate(JSON.parse(s.payload)));}
    else if(s.type==="bye")throw Error("Androidから接続が終了されました。");
   }
   if(!state.sessionId)await new Promise(resolve=>setTimeout(resolve,900));
  }
  if(!state.sessionId)throw Error("Androidからの接続応答が届きません。Androidが待機中か、OSがカメラ・マイク起動を許可したか確認してください。");
  state.pollTimer=setInterval(pollSignals,1100);state.statsTimer=setInterval(()=>updateStats(pc),2000);state.connecting=false;startDarkCheck();
  $("pttButton").disabled=!state.pttSender;$("viewerSub").textContent="接続情報を交換しました。メディア到着を確認しています。";
  status("接続情報を交換しました。映像・音声の到着を待っています。");
 }catch(e){state.connecting=false;status(e.message);badge("接続失敗");connection("接続失敗","error");endView(false,false);$("viewerSub").textContent="接続に失敗しました。Androidの待機通知とネットワークを確認してください。";}
}
$("saveUrl").addEventListener("click",async()=>{try{const c=config();localStorage.setItem("wsc_gas_url",c.url);await api("ping");apiState.textContent="サーバー接続済み";$("message").textContent="GAS接続を確認しました。";await refresh();}catch(e){$("message").textContent=e.message;apiState.textContent="接続エラー";}});
$("refresh").addEventListener("click",refresh);
$("createPair").addEventListener("click",async()=>{try{const r=await api("createPairCode");$("pairCodeText").textContent=r.pairCode;$("pairResult").hidden=false;$("message").textContent="登録コードを発行しました。10分以内にAndroidへ入力してください。";}catch(e){$("message").textContent=e.message;}});
$("closeControl").addEventListener("click",()=>{endView(true,true);$("controlPanel").hidden=true;});
$("viewBtn").addEventListener("click",connect);
$("endViewBtn").addEventListener("click",()=>{endView(true,true);status("Androidへ停止要求を送信しました。待機サービスは継続します。");});
$("switchCamera").addEventListener("click",async()=>{try{await command("switch_camera");status("カメラ切替要求をAndroidへ送信しました。");}catch(e){status(e.message);}});
$("statusRequest").addEventListener("click",async()=>{try{await command("request_status");await refresh();status("端末状態の更新を要求しました。");}catch(e){status(e.message);}});
$("muteRemote").addEventListener("click",()=>{state.muted=!state.muted;video.muted=state.muted;$("muteRemote").textContent=state.muted?"🔇 ミュート解除":"🔊 音声ミュート";});
$("enablePlayback").addEventListener("click",async()=>{try{video.muted=state.muted;await video.play();status("映像・音声の再生を有効にしました。");}catch(e){status("再生できません: "+e.message);}});
$("fullscreen").addEventListener("click",async()=>{try{if(document.fullscreenElement)await document.exitFullscreen();else await $("videoFrame").requestFullscreen();}catch(e){status("全画面表示に対応していません: "+e.message);}});
$("requestMic").addEventListener("click",async()=>{try{await ensureMic();$("pttButton").disabled=!state.pttSender;status("マイクの使用を許可しました。PTTボタンを押している間だけ送信します。");}catch(e){setMicState("許可が必要");$("pttStatus").textContent=e.message;}});
const ptt=$("pttButton");
ptt.addEventListener("pointerdown",e=>{e.preventDefault();state.pttHeld=true;startPtt();});ptt.addEventListener("pointerup",stopPtt);window.addEventListener("pointerup",stopPtt);ptt.addEventListener("pointercancel",stopPtt);ptt.addEventListener("pointerleave",()=>{if(state.pttHeld)stopPtt();});
window.addEventListener("blur",stopPtt);document.addEventListener("visibilitychange",()=>{if(document.hidden)stopPtt();});
$("applyModes").addEventListener("click",async()=>{try{await command("set_modes",{networkMode:$("networkMode").value,powerMode:$("powerMode").value});status("通信・電力モードをAndroidへ送信しました。端末が受信すると適用します。");}catch(e){status(e.message);}});
$("saveSchedule").addEventListener("click",async()=>{try{const start=$("scheduleStart").value,end=$("scheduleEnd").value;if(!start||!end||start===end)throw Error("開始時刻と終了時刻を確認してください。");await command("set_schedule",{enabled:$("scheduleEnabled").checked,days:$("scheduleDays").value,start,end});status("スケジュールをAndroidへ送信しました。自動起動はAndroid OSの制限に従います。");}catch(e){status(e.message);}});
if(navigator.mediaDevices?.getUserMedia)setMicState("未許可");else setMicState("このブラウザでは非対応");
if(savedUrl)refresh();
state.listTimer=setInterval(refresh,20000);
window.addEventListener("beforeunload",()=>{stopPtt();stopTimers();if(state.micStream)state.micStream.getTracks().forEach(t=>t.stop());if(state.pc)state.pc.close();});
})();