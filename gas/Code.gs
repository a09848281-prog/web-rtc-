const WSC_SHEET = "Devices";
const WSC_SIGNAL_SHEET = "Signals";
const WSC_DEVICE_HEADERS = ["deviceId","name","tokenHash","pairCode","paired","lastSeen","status","pendingCommand","pairExpiresAt","statusJson"];
function doGet(){return json_({ok:true,service:"WebRTC Security Camera GAS",version:6,push:"GAS short polling (Firebase unused)"});}
function doPost(e){
  const formMode=!!(e&&e.parameter&&e.parameter.rdcPayload);
  const requestId=formMode?String(e.parameter.rdcRequestId||""):"";
  try{
    const body=formMode?e.parameter.rdcPayload:(e?.postData?.contents||"{}");
    const p=JSON.parse(body);
    const result=route_(p);
    return formMode?iframeResponse_(result,requestId):json_(result);
  }catch(err){
    const result={ok:false,error:String(err.message||err)};
    return formMode?iframeResponse_(result,requestId):json_(result);
  }
}
function iframeResponse_(result,requestId){
  const message=JSON.stringify({channel:"rdc-api",requestId:String(requestId||""),response:result}).replace(/</g,"\\u003c");
  const html='<!doctype html><meta charset="utf-8"><script>(function(){var response='+message+';try{window.top.postMessage(response,"*");}catch(e){window.parent.postMessage(response,"*");}})();</script>';
  return HtmlService.createHtmlOutput(html).setXFrameOptionsMode(HtmlService.XFrameOptionsMode.ALLOWALL);
}
function setup(){
  const ss=SpreadsheetApp.getActiveSpreadsheet();
  if(!ss)throw new Error("このスクリプトをGoogle Sheetsから開いてください。");
  let sh=ss.getSheetByName(WSC_SHEET);
  if(!sh)sh=ss.insertSheet(WSC_SHEET);
  if(sh.getLastRow()===0)sh.appendRow(WSC_DEVICE_HEADERS);
  if(sh.getLastColumn()<WSC_DEVICE_HEADERS.length)sh.getRange(1,1,1,WSC_DEVICE_HEADERS.length).setValues([WSC_DEVICE_HEADERS]);
  else sh.getRange(1,1,1,WSC_DEVICE_HEADERS.length).setValues([WSC_DEVICE_HEADERS]);
  let sig=ss.getSheetByName(WSC_SIGNAL_SHEET);
  if(!sig)sig=ss.insertSheet(WSC_SIGNAL_SHEET);
  if(sig.getLastRow()===0)sig.appendRow(["signalId","deviceId","sessionId","fromRole","toRole","type","payload","createdAt","consumed"]);
  return "セットアップ完了。";
}
function route_(p){
  const sh=sheet_();
  switch(p.action){
    case "ping":return {ok:true,version:6};
    case "createPairCode":return createPairCode_(sh);
    case "claimPairCode":return claimPairCode_(sh,p);
    case "listDevices":return list_(sh);
    case "heartbeat":return heartbeat_(sh,p);
    case "pollCommand":return poll_(sh,p);
    case "queueCommand":return queue_(sh,p);
    case "sendSignal":return sendSignal_(p);
    case "pollSignals":return pollSignals_(p);
    default:throw new Error("未対応の操作です。");
  }
}
function createPairCode_(sh){
  const lock=LockService.getScriptLock();lock.waitLock(10000);
  try{
    const rows=sh.getDataRange().getValues();let code="";
    do{
      const randomHex=Utilities.getUuid().replace(/-/g,"").substring(0,12);
      code=String(100000+(parseInt(randomHex,16)%900000));
    }while(rows.some((row,index)=>index>0&&String(row[3])===code&&!row[4]&&row[8]&&new Date(row[8]).getTime()>Date.now()));
    sh.appendRow([Utilities.getUuid(),"Android","",""+code,false,"","pairing","",new Date(Date.now()+10*60*1000),""]);
    return {ok:true,pairCode:code,expiresInMinutes:10};
  }finally{lock.releaseLock();}
}
function claimPairCode_(sh,p){
  const lock=LockService.getScriptLock();lock.waitLock(10000);
  try{
    const props=PropertiesService.getScriptProperties(),now=Date.now();
    let windowStart=Number(props.getProperty("WSC_PAIR_WINDOW_START")||0);
    let attempts=Number(props.getProperty("WSC_PAIR_ATTEMPT_COUNT")||0);
    if(!windowStart||now-windowStart>=5*60*1000){windowStart=now;attempts=0;}
    if(attempts>=20)throw new Error("登録コードの試行回数が上限に達しました。5分ほど待ってから再試行してください。");
    props.setProperties({WSC_PAIR_WINDOW_START:String(windowStart),WSC_PAIR_ATTEMPT_COUNT:String(attempts+1)});
    const code=String(p.pairCode||"");
    if(!/^\d{6}$/.test(code))throw new Error("登録コードは6桁で入力してください。");
    const rows=sh.getDataRange().getValues();
    for(let i=1;i<rows.length;i++){
      if(String(rows[i][3])===code&&!rows[i][4]&&rows[i][6]==="pairing"&&rows[i][8]&&new Date(rows[i][8]).getTime()>Date.now()){
        const token=Utilities.getUuid()+Utilities.getUuid(),name=String(p.name||"Android").slice(0,40);
        sh.getRange(i+1,2).setValue(name);sh.getRange(i+1,3).setValue(sha_(token));
        sh.getRange(i+1,4).clearContent();sh.getRange(i+1,5).setValue(true);sh.getRange(i+1,9).clearContent();
        sh.getRange(i+1,6).setValue(new Date());sh.getRange(i+1,7).setValue("online");sh.getRange(i+1,10).setValue("{}");
        return {ok:true,deviceId:String(rows[i][0]),deviceToken:token,name:name};
      }
    }
    throw new Error("登録コードが無効か、使用済みです。");
  }finally{lock.releaseLock();}
}
function list_(sh){
  const rows=sh.getDataRange().getValues(),now=Date.now(),devices=[];
  for(let i=1;i<rows.length;i++){
    if(!rows[i][4])continue;
    const seen=rows[i][5]?new Date(rows[i][5]).getTime():0;
    let status={};
    try{status=JSON.parse(String(rows[i][9]||"{}"));}catch(_){}
    devices.push({deviceId:String(rows[i][0]),name:String(rows[i][1]),online:!!seen&&now-seen<25000,lastSeen:rows[i][5]?new Date(rows[i][5]).toLocaleString("ja-JP"):"未接続",status:status});
  }
  return {ok:true,devices:devices};
}
function heartbeat_(sh,p){
  const row=authRow_(sh,p);
  sh.getRange(row,6).setValue(new Date());sh.getRange(row,7).setValue("online");
  const status=p.status&&typeof p.status==="object"?p.status:{};
  sh.getRange(row,10).setValue(JSON.stringify(status).slice(0,4000));
  return {ok:true,serverTime:new Date().toISOString()};
}
function poll_(sh,p){
  const row=authRow_(sh,p),lock=LockService.getScriptLock();lock.waitLock(10000);
  try{
    const raw=sh.getRange(row,8).getValue();
    if(raw)sh.getRange(row,8).clearContent();
    let command=null;
    if(raw){try{command=JSON.parse(String(raw));}catch(_){command={command:String(raw),payload:{}};}}
    return {ok:true,command:command};
  }finally{lock.releaseLock();}
}
function queue_(sh,p){
  const id=String(p.deviceId||""),command=String(p.command||"");
  const allowed=["start_session","stop_session","request_status","switch_camera","set_modes","set_schedule"];
  if(!allowed.includes(command))throw new Error("許可されていない操作です。");
  let payload={};
  if(p.payload!==undefined&&p.payload!==null){
    if(typeof p.payload==="object")payload=p.payload;
    else{try{payload=JSON.parse(String(p.payload));}catch(_){throw new Error("コマンド設定の形式が不正です。");}}
  }
  const serialized=JSON.stringify({command:command,payload:payload,createdAt:Date.now()});
  if(serialized.length>2000)throw new Error("コマンド設定が大きすぎます。");
  const lock=LockService.getScriptLock();lock.waitLock(10000);
  try{
    const rows=sh.getDataRange().getValues();
    for(let i=1;i<rows.length;i++)if(String(rows[i][0])===id&&rows[i][4]){
      sh.getRange(i+1,8).setValue(serialized);return {ok:true,queued:true};
    }
    throw new Error("端末が見つかりません。");
  }finally{lock.releaseLock();}
}
function sendSignal_(p){
  const role=String(p.fromRole||""),deviceId=String(p.deviceId||""),sessionId=String(p.sessionId||"");
  if(role==="android")authRow_(sheet_(),p);else if(role!=="web")throw new Error("送信元が不正です。");
  if(!deviceId||deviceId.length>128)throw new Error("端末IDが不正です。");
  if(!sessionId||sessionId.length>128)throw new Error("セッションIDが不正です。");
  const to=role==="android"?"web":"android",type=String(p.type||"");
  if(!["offer","answer","ice","bye"].includes(type))throw new Error("シグナル種別が不正です。");
  const payload=String(p.payload||"");if(payload.length>30000)throw new Error("シグナルが大きすぎます。");
  const sig=signalSheet_(),lock=LockService.getScriptLock();lock.waitLock(10000);
  try{
    if(type==="offer"){
      pruneSignals_(sig);
      const rows=sig.getDataRange().getValues();
      for(let i=1;i<rows.length;i++)if(String(rows[i][1])===deviceId&&!rows[i][8])sig.getRange(i+1,9).setValue(true);
    }
    sig.appendRow([Utilities.getUuid(),deviceId,sessionId,role,to,type,payload,new Date(),false]);
    return {ok:true};
  }finally{lock.releaseLock();}
}
function pruneSignals_(sig){
  const last=sig.getLastRow();if(last<2)return;
  const cutoff=Date.now()-60*60*1000,rows=sig.getRange(2,1,last-1,9).getValues();
  for(let i=rows.length-1;i>=0;i--){
    const created=rows[i][7] instanceof Date?rows[i][7].getTime():new Date(rows[i][7]).getTime();
    if(!created||created<cutoff)sig.deleteRow(i+2);
  }
}
function pollSignals_(p){
  const role=String(p.role||""),deviceId=String(p.deviceId||"");
  if(role==="android")authRow_(sheet_(),p);else if(role!=="web")throw new Error("受信側が不正です。");
  if(!deviceId||deviceId.length>128)throw new Error("端末IDが不正です。");
  const sig=signalSheet_(),lock=LockService.getScriptLock();lock.waitLock(10000);
  try{
    const rows=sig.getDataRange().getValues(),out=[];
    for(let i=1;i<rows.length;i++){
      if(String(rows[i][1])===deviceId&&String(rows[i][4])===role&&!rows[i][8]&&(!p.sessionId||String(rows[i][2])===String(p.sessionId))){
        out.push({sessionId:String(rows[i][2]),type:String(rows[i][5]),payload:String(rows[i][6])});
        sig.getRange(i+1,9).setValue(true);if(out.length>=30)break;
      }
    }
    return {ok:true,signals:out};
  }finally{lock.releaseLock();}
}
function authRow_(sh,p){
  const id=String(p.deviceId||""),hash=sha_(String(p.deviceToken||"")),rows=sh.getDataRange().getValues();
  for(let i=1;i<rows.length;i++)if(String(rows[i][0])===id&&rows[i][4]&&String(rows[i][2])===hash)return i+1;
  throw new Error("端末認証に失敗しました。");
}
function sheet_(){
  const ss=SpreadsheetApp.getActiveSpreadsheet();if(!ss)throw new Error("スプレッドシートに紐付けてください。");
  const sh=ss.getSheetByName(WSC_SHEET);if(!sh)throw new Error("先にsetup()を実行してください。");return sh;
}
function signalSheet_(){
  const ss=SpreadsheetApp.getActiveSpreadsheet();let sh=ss.getSheetByName(WSC_SIGNAL_SHEET);
  if(!sh){sh=ss.insertSheet(WSC_SIGNAL_SHEET);sh.appendRow(["signalId","deviceId","sessionId","fromRole","toRole","type","payload","createdAt","consumed"]);}
  return sh;
}
function sha_(s){return Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256,s).map(b=>(b<0?b+256:b).toString(16).padStart(2,"0")).join("");}
function json_(value){return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON);}
