# 開発状況（2026-11-14）

## 初期実装
- Java Android Gradleプロジェクト
- ManifestのACCESS_NETWORK_STATE / Wi-Fi / Foreground Service権限
- 端末登録、接続要求、GASシグナリング
- WebRTCのAndroidカメラ/マイク送信サービス土台
- GitHub Pages向け管理画面
- APKビルド / PagesデプロイのActions

## 過去エラーへの対策
- 以前不足していたACCESS_NETWORK_STATEをManifestに明記。
- カメラ、マイク、通知権限を開始前に検査。
- WebRTC初期化/カメラ初期化をバックグラウンドExecutorで実行し、失敗段階を保存・通知。
- GASネットワーク処理をUIスレッドから分離。
- TURN URLもICE設定で許可し、背面カメラを初期選択。

## 未完了
- FCM実装（Firebase project/google-services.json待ち）
- TURN設定画面/安全な資格情報配布
- 遠隔カメラ切替、PTT、通信/電力モード
- 熱状態/暗闇/フレーム停止/バッテリー監視
- スケジュール
- Android 13実機、iPad Safari、PCの接続検証

## 6項目チェック
- [x] ACCESS_NETWORK_STATEを追加
- [x] 権限確認と例外表示
- [x] 明示操作でメディア開始
- [x] GAS通信をバックグラウンド実行
- [ ] Actions APKビルド成功
- [ ] 実機ロック画面・長時間・再接続検証

ビルド成功と実機動作確認は別の判定です。
