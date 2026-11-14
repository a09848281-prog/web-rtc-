# セットアップ手順（Firebase不使用版）

## 1. GASを更新する（必須）

今回の自動接続・端末状態送信・遠隔操作に対応するため、GASの更新が必要です。URLは従来のものをそのまま使用します。

1. GitHubの [gas/Code.gs](https://github.com/a09848281-prog/web-rtc-/blob/main/gas/Code.gs) を開き、全内容をコピーします。
2. 使用中のGoogle Sheets → 拡張機能 → Apps Scriptを開きます。
3. 既存の `Code.gs` を全置換して保存します。
4. 関数一覧から `setup` を選び、1回実行します。既存のDevices / Signalsデータは削除せず、Devicesの状態列を追加します。
5. デプロイ → デプロイを管理 → 鉛筆アイコン → バージョン「新バージョン」→ デプロイ。
6. 既存のWebアプリURLを維持したまま更新されていることを確認します。

## 2. GitHub Pages

1. リポジトリ Settings → Pages → Sourceを「GitHub Actions」にします。
2. Actionsで「Deploy WebRTC Security Camera Web」が成功するのを確認します。
3. [WebRTC Security Camera](https://a09848281-prog.github.io/web-rtc-/) を開きます。
4. GAS URLは `web/config.js` に登録済みです。通常は入力不要です。

## 3. Android APK

1. GitHubの [Actions](https://github.com/a09848281-prog/web-rtc-/actions) を開きます。
2. 最新の「Build WebRTC Security Camera APK」の成功実行を開きます。
3. Artifactsの `webrtc-security-camera-debug-apk` をダウンロードし、ZIPを展開します。
4. `app-debug.apk` をGalaxy A22 5GなどのAndroid端末へ転送してインストールします。
5. Webで登録コードを発行し、Androidアプリに端末名と6桁コードを入力して登録します。
6. Androidでカメラ・マイク・通知の権限を許可し、「接続待機を開始」を押します。常時通知が表示されたら待機中です。

## 4. Webから接続

1. Web画面で登録済みAndroidを選び、「接続・自動起動」を押します。
2. WebはGASへ開始要求を送り、Androidは通常約5秒以内に指令を確認します。
3. Android OSがカメラ・マイクの新規起動を許可した場合は、そのままWebRTC接続を開始します。
4. OSがバックグラウンド起動を拒否した場合はAndroid通知をタップしてください。アプリ画面が開くとカメラ・マイクの開始を試みます。
5. Webマイクを使う場合は「マイクを許可」を押し、ブラウザの許可を与えてください。PTTボタンを押している間だけAndroidへ音声を送信します。
6. 「監視終了」はカメラ・マイク・WebRTCを停止します。Androidの接続待機サービスは継続します。

## 5. Firebase・通信について

- Firebase / FCMは使用しません。
- Androidへの接続要求はGASを通常5秒間隔で確認します。通信失敗時は10秒、20秒、最大30秒へ間隔を延ばします。
- これはプッシュ通知ではなくポーリングです。待機中はGAS実行回数が増えるため、常時運用時はApps Scriptの割当・実行履歴を確認してください。
- 映像・音声はWebRTCで直接通信し、GASは登録・指令・SDP/ICE交換のみを担当します。
- 既定ではGoogle STUNを利用します。STUNだけでは接続できないネットワークもあり、その場合は別途TURNサーバーと安全な認証情報配布が必要です。TURNサーバーは同梱していません。

## 6. 重要な制限・安全性

- Androidのカメラとマイクは「使用中」の権限です。Android 13でも、バックグラウンドからの新規起動が拒否される場合があります。端末の設定だけでこのOS制限を解除することはできません。
- 画面ロック後に開始済みのセッションが継続するかは、端末メーカーの電源管理を含めて実機確認が必要です。
- アプリ未起動、強制停止、電源OFF、通信圏外では自動接続を保証できません。
- 管理者認証は未実装です。GitHub Pagesを見られる人は端末一覧表示・登録コード発行・指令送信が可能です。信頼できる個人利用の範囲に限定してください。
- 録画・録音ファイルは作りません。映像・音声はリアルタイム転送です。

## 7. GAS更新の注意

GASはGitHubのファイルを更新しただけでは反映されません。必ずApps Scriptへ貼り付けて保存し、「デプロイを管理」から新しいバージョンをデプロイしてください。
