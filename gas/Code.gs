const WSC_SHEET = "Devices";
const WSC_SIGNAL_SHEET = "Signals";
const WSC_DEVICE_HEADERS = ["deviceId","name","tokenHash","pairCode","paired","lastSeen","status","pendingCommand","pairExpiresAt","statusJson","webTokenHash"];
const WSC_SIGNAL_HEADERS = ["signalId","deviceId","sessionId","fromRole","toRole","type","payload","createdAt","consumed"];
const WSC_SIGNAL_TTL_MS = 10 * 60 * 1000;

function doGet() {
  return json_({ok:true,service:"WebRTC Security Camera",version:7,signaling:"GAS only for pairing/SDP-ICE bootstrap",firebase:false});
}

function doPost(e) {
  const formMode=!!(e&&e.parameter&&e.parameter.rdcPayload);
  const requestId=formMode?String(e.parameter.rdcRequestId||""):"";
  try {
    const body=formMode?e.parameter.rdcPayload:(e&&e.postData&&e.postData.contents||"{}");
    const p=JSON.parse(body);
    const result=route_(p);
    return formMode?iframeResponse_(result,requestId):json_(result);
  } catch(err) {
    const result={ok:false,error:String(err&&err.message||err)};
    return formMode?iframeResponse_(result,requestId):json_(result);
  }
}

function iframeResponse_(result,requestId) {
  const message=JSON.stringify({channel:"rdc-api",requestId:String(requestId||""),response:result}).replace(/</g,"\\u003c");
  const html='<!doctype html><meta charset="utf-8"><script>(function(){var response='+message+';try{window.top.postMessage(response,"*");}catch(e){window.parent.postMessage(response,"*");}})();</script>';
  return HtmlService.createHtmlOutput(html).setXFrameOptionsMode(HtmlService.XFrameOptionsMode.ALLOWALL);
}

function setup() {
  const ss=SpreadsheetApp.getActiveSpreadsheet();
  if(!ss)throw new Error("このスクリプトをGoogle Sheetsから開いてください。");
  let sh=ss.getSheetByName(WSC_SHEET);
  if(!sh)sh=ss.insertSheet(WSC_SHEET);
  sh.getRange(1,1,1,WSC_DEVICE_HEADERS.length).setValues([WSC_DEVICE_HEADERS]);
  let sig=ss.getSheetByName(WSC_SIGNAL_SHEET);
  if(!sig)sig=ss.insertSheet(WSC_SIGNAL_SHEET);
  sig.getRange(1,1,1,WSC_SIGNAL_HEADERS.length).setValues([WSC_SIGNAL_HEADERS]);
  return "セットアップ完了。DevicesとSignalsを確認してください。";
}

function route_(p) {
  const sh=sheet_();
  switch(String(p.action||"")) {
    case "ping": return {ok:true,version:7,firebase:false};
    case "createPairCode": return createPairCode_(sh);
    case "claimPairCode": return claimPairCode_(sh,p);
    case "listDevices": return list_(sh,p);
    case "sendSignal": return sendSignal_(p);
    case "pollSignals": return pollSignals_(p);
    default: throw new Error("未対応の操作です。");
  }
}

function createPairCode_(sh) {
  const lock=LockService.getScriptLock();lock.waitLock(10000);
  try {
    const rows=sh.getDataRange().getValues();
    let code="";
    do {
      const randomHex=Utilities.getUuid().replace(/-/g,"").substring(0,12);
      code=String(100000+(parseInt(randomHex,16)%900000));
    } while(rows.some((row,index)=>index>0&&String(row[3])===code&&!row[4]&&row[8]&&new Date(row[8]).getTime()>Date.now()));
    const deviceId=Utilities.getUuid();
    const webToken=Utilities.getUuid()+Utilities.getUuid();
    sh.appendRow([deviceId,"Android","",code,false,"","pairing","",new Date(Date.now()+10*60*1000),"",sha_(webToken)]);
    return {ok:true,deviceId:deviceId,webToken:webToken,pairCode:code,expiresInMinutes:10};
  } finally {lock.releaseLock();}
}

function claimPairCode_(sh,p) {
  const lock=LockService.getScriptLock();lock.waitLock(10000);
  try {
    const props=PropertiesService.getScriptProperties(),now=Date.now();
    let windowStart=Number(props.getProperty("WSC_PAIR_WINDOW_START")||0);
    let attempts=Number(props.getProperty("WSC_PAIR_ATTEMPT_COUNT")||0);
    if(!windowStart||now-windowStart>=5*60*1000){windowStart=now;attempts=0;}
    if(attempts>=20)throw new Error("登録コードの試行回数が上限に達しました。5分ほど待ってから再試行してください。");
    props.setProperties({WSC_PAIR_WINDOW_START:String(windowStart),WSC_PAIR_ATTEMPT_COUNT:String(attempts+1)});
    const code=String(p.pairCode||"");
    if(!/^\d{6}$/.test(code))throw new Error("登録コードは6桁で入力してください。");
    const rows=sh.getDataRange().getValues();
    for(let i=1;i<rows.length;i++) {
      if(String(rows[i][3])===code&&!rows[i][4]&&rows[i][6]==="pairing"&&rows[i][8]&&new Date(rows[i][8]).getTime()>Date.now()) {
        const token=Utilities.getUuid()+Utilities.getUuid();
        const name=String(p.name||"Android").slice(0,40);
        sh.getRange(i+1,2).setValue(name);
        sh.getRange(i+1,3).setValue(sha_(token));
        sh.getRange(i+1,4).clearContent();
        sh.getRange(i+1,5).setValue(true);
        sh.getRange(i+1,6).clearContent();
        sh.getRange(i+1,7).setValue("registered");
        sh.getRange(i+1,9).clearContent();
        sh.getRange(i+1,10).setValue("{}");
        return {ok:true,deviceId:String(rows[i][0]),deviceToken:token,name:name};
      }
    }
    throw new Error("登録コードが無効か、使用済みです。");
  } finally {lock.releaseLock();}
}

function list_(sh,p) {
  const tokens=p&&p.deviceTokens&&typeof p.deviceTokens==="object"?p.deviceTokens:{};
  const rows=sh.getDataRange().getValues(),devices=[];
  for(let i=1;i<rows.length;i++) {
    if(!rows[i][4])continue;
    const id=String(rows[i][0]),token=String(tokens[id]||"");
    if(!token||!rows[i][10]||sha_(token)!==String(rows[i][10]))continue;
    devices.push({deviceId:id,name:String(rows[i][1]||"Android"),registered:true,online:null,lastSeen:"RTC接続時に確認"});
  }
  return {ok:true,devices:devices};
}

function sendSignal_(p) {
  const role=String(p.fromRole||""),deviceId=String(p.deviceId||""),sessionId=String(p.sessionId||"");
  if(role==="android")authRow_(sheet_(),p);
  else if(role==="web")authWebRow_(sheet_(),p);
  else throw new Error("送信元が不正です。");
  if(!deviceId||deviceId.length>128)throw new Error("端末IDが不正です。");
  if(!sessionId||sessionId.length>128)throw new Error("セッションIDが不正です。");
  const to=role==="android"?"web":"android",type=String(p.type||"");
  if(!["offer","answer","ice","bye"].includes(type))throw new Error("シグナル種別が不正です。");
  const payload=String(p.payload||"");
  if(payload.length>30000)throw new Error("シグナルが大きすぎます。");
  const sig=signalSheet_(),lock=LockService.getScriptLock();lock.waitLock(10000);
  try {
    pruneSignals_(sig);
    if(type==="offer") {
      const rows=sig.getDataRange().getValues();
      for(let i=1;i<rows.length;i++) {
        if(String(rows[i][1])===deviceId&&!rows[i][8])sig.getRange(i+1,9).setValue(true);
      }
    }
    sig.appendRow([Utilities.getUuid(),deviceId,sessionId,role,to,type,payload,new Date(),false]);
    return {ok:true};
  } finally {lock.releaseLock();}
}

function pollSignals_(p) {
  const role=String(p.role||""),deviceId=String(p.deviceId||"");
  if(role==="android")authRow_(sheet_(),p);
  else if(role==="web")authWebRow_(sheet_(),p);
  else throw new Error("受信側が不正です。");
  if(!deviceId||deviceId.length>128)throw new Error("端末IDが不正です。");
  const sig=signalSheet_(),lock=LockService.getScriptLock();lock.waitLock(10000);
  try {
    pruneSignals_(sig);
    const rows=sig.getDataRange().getValues(),out=[];
    for(let i=1;i<rows.length;i++) {
      if(String(rows[i][1])===deviceId&&String(rows[i][4])===role&&!rows[i][8]&&(!p.sessionId||String(rows[i][2])===String(p.sessionId))) {
        out.push({sessionId:String(rows[i][2]),type:String(rows[i][5]),payload:String(rows[i][6])});
        sig.getRange(i+1,9).setValue(true);
        if(out.length>=30)break;
      }
    }
    return {ok:true,signals:out};
  } finally {lock.releaseLock();}
}

function pruneSignals_(sig) {
  const last=sig.getLastRow();if(last<2)return;
  const cutoff=Date.now()-WSC_SIGNAL_TTL_MS,rows=sig.getRange(2,1,last-1,WSC_SIGNAL_HEADERS.length).getValues();
  for(let i=rows.length-1;i>=0;i--) {
    const created=rows[i][7] instanceof Date?rows[i][7].getTime():new Date(rows[i][7]).getTime();
    if(!created||created<cutoff)sig.deleteRow(i+2);
  }
}

function authRow_(sh,p) {
  const id=String(p.deviceId||""),hash=sha_(String(p.deviceToken||"")),rows=sh.getDataRange().getValues();
  for(let i=1;i<rows.length;i++)if(String(rows[i][0])===id&&rows[i][4]&&String(rows[i][2])===hash)return i+1;
  throw new Error("Android端末認証に失敗しました。");
}

function authWebRow_(sh,p) {
  const id=String(p.deviceId||""),hash=sha_(String(p.webToken||"")),rows=sh.getDataRange().getValues();
  for(let i=1;i<rows.length;i++)if(String(rows[i][0])===id&&rows[i][4]&&String(rows[i][10])===hash)return i+1;
  throw new Error("このブラウザは端末へのアクセスを許可されていません。Androidを再登録してください。");
}

function sheet_() {
  const ss=SpreadsheetApp.getActiveSpreadsheet();
  if(!ss)throw new Error("このスクリプトをGoogle Sheetsから開いてください。");
  const sh=ss.getSheetByName(WSC_SHEET);
  if(!sh)throw new Error("先にsetup()を実行してください。");
  return sh;
}

function signalSheet_() {
  const ss=SpreadsheetApp.getActiveSpreadsheet();
  let sh=ss.getSheetByName(WSC_SIGNAL_SHEET);
  if(!sh){sh=ss.insertSheet(WSC_SIGNAL_SHEET);sh.getRange(1,1,1,WSC_SIGNAL_HEADERS.length).setValues([WSC_SIGNAL_HEADERS]);}
  return sh;
}

function sha_(s) {
  return Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256,s).map(b=>(b<0?b+256:b).toString(16).padStart(2,"0")).join("");
}

function json_(value) {
  return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON);
}
