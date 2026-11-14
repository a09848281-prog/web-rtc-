# セットアップ手順

## GAS
1. Google Sheetsを作成し、拡張機能 → Apps Scriptを開く。
2. `gas/Code.gs` を貼り付け、保存。
3. `setup()` を一度実行してDevices / Signalsを作成。
4. Apps Scriptのプロジェクト設定 → スクリプトプロパティに `ADMIN_KEY` を設定。長いランダム文字列を推奨。
5. デプロイ → 新しいデプロイ → ウェブアプリ。実行ユーザーは自分、アクセスできるユーザーは全員。
6. 発行された `https://script.google.com/macros/s/.../exec` を控える。

## GitHub Pages
1. リポジトリ Settings → Pages を開く。
2. Sourceを「GitHub Actions」にする。
3. Actions成功後、`https://a09848281-prog.github.io/web-rtc-/` を開く。
4. GAS URLとADMIN_KEYを設定して接続確認。

## APK
1. GitHub → Actions → Build WebRTC Security Camera APK。
2. 最新の成功実行を開き、Artifactsの `webrtc-security-camera-debug-apk` をダウンロード。
3. ZIPを展開してAPKをAndroid 13端末へ転送、インストール。
4. AndroidアプリにGAS URLと端末名を入力。
5. Webで登録コードを発行し、Androidに6桁コードを入力して登録。
6. Androidで通知権限を許可し「接続監視を開始」。
7. カメラ/マイクを使うときはAndroidアプリを前面にして「カメラ・マイクを開始」を押す。

## 接続
1. Webで端末一覧を更新して対象端末を選ぶ。
2. 「Androidに開始通知を送る」を押す。
3. Android通知をタップし、利用者がカメラ・マイク開始操作を行う。
4. Web側で映像・音声に接続。

## 注意
- APKはdebug署名。配布用署名ではない。
- FCMはFirebaseプロジェクトとアプリ設定が必要。設定前はGASポーリングが暫定方式。
- TURNは必要環境で別途設定。資格情報を公開リポジトリへ保存しない。
- 強制停止・電源OFF・OS制限中の遠隔起動は不可。
