# セットアップ手順

## GAS
1. Google Sheetsを作成し、拡張機能 → Apps Scriptを開く。
2. `gas/Code.gs` を貼り付け、保存。
3. `setup()` を一度実行してDevices / Signalsを作成。
4. デプロイ → 新しいデプロイ → ウェブアプリ。実行ユーザーは自分、アクセスできるユーザーは全員。
5. 発行された `https://script.google.com/macros/s/.../exec` を控える。

## GitHub Pages
1. リポジトリ Settings → Pages を開く。
2. Sourceを「GitHub Actions」にする。
3. Actions成功後、`https://a09848281-prog.github.io/web-rtc-/` を開く。
4. GAS URLは `web/config.js` に設定済みなので、通常は変更せず接続確認。

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
- 管理者キー認証は無効化されています。GitHub Pagesを見られる人は端末一覧の閲覧、登録コード発行、接続要求などを実行できます。個人利用・信頼できる範囲でのみ公開してください。
- GASコードを変更した後は、Apps Scriptで「デプロイを管理」→編集→新バージョンを選んで再デプロイしてください。既存URLを維持できます。
- APKはdebug署名。配布用署名ではない。
- FCMはFirebaseプロジェクトとアプリ設定が必要。設定前はGASポーリングが暫定方式。
- TURNは必要環境で別途設定。資格情報を公開リポジトリへ保存しない。
- 強制停止・電源OFF・OS制限中の遠隔起動は不可。
