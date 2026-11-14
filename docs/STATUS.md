# 開発状況（2026-11-14）

## 実装済み
- Java Android Gradleプロジェクト
- ManifestのACCESS_NETWORK_STATE / Wi-Fi / Foreground Service権限
- Androidの6桁ペアリング・端末トークン登録
- GASによる端末一覧、heartbeat、接続要求、SDP/ICEシグナリング
- Android WebRTCのカメラ/マイク送信サービス土台
- GitHub Pages向けWeb管理画面
- 白背景のレスポンシブUI
- GitHub ActionsのAndroid APKビルドとWeb構文検査
- 背面カメラを初期選択、Camera1 fallback、TURN URLの受け入れ
- 待機時の長時間WakeLockを廃止し、低頻度pollingへ変更

## 検証済み
- Android APK：GitHub Actions Build成功（run #6）
- APK artifact：生成・取得済み（debug APK）
- Android Manifestの必須permissionチェック成功
- Web JavaScript / GAS / webmanifest構文チェック成功
- GitHub Pages deployment：未完了。リポジトリのSettings → PagesでSourceをGitHub Actionsに設定する必要あり

## 過去エラーへの対策
- 以前不足していたACCESS_NETWORK_STATEをManifestへ追加。
- カメラ、マイク、通知権限を開始前に検査。
- WebRTC初期化/カメラ初期化をワーカースレッドで実行し、失敗段階を保存・通知。
- GASネットワーク処理をUIスレッドから分離。
- TURN/STUN URLをネイティブICE設定で受け付ける。

## 未完了
- FCM受信サービス（Firebaseプロジェクト・google-services.json等の設定が必要）
- TURN資格情報の設定画面 / 安全な配布経路
- 遠隔カメラ切替
- WebからAndroidへのPTT音声
- 通信/電力モードの実制御
- 熱状態監視・高温時の強制停止
- 暗闇/フレーム停止/バッテリー警告
- スケジュール
- Android 13実機、iPad Safari、PCの実通信検証

## 6項目チェック
- [x] ACCESS_NETWORK_STATEを追加
- [x] 権限確認と例外表示
- [x] Android利用者の明示操作でメディア開始
- [x] GAS通信をバックグラウンド実行
- [x] GitHub Actions APKビルド成功
- [ ] Android 13実機で画面ロック・長時間・再接続を検証

ビルド成功と実機動作確認は別の判定です。
