# Android APK

- Java / Android Gradle
- Package: `jp.a09848281.securitycamera`
- minSdk 26 / compileSdk 35 / targetSdk 35
- WebRTC native library: `io.github.webrtc-sdk:android:150.7871.04`
- Firebase SDK / FCM: 未使用。GAS command pollingを使用。

## 使用手順

1. Androidアプリを端末がロック解除された状態で起動します。
2. Web側で発行した6桁コードと端末名を入力して登録します。
3. 「接続待機を開始」を押し、カメラ・マイク・通知権限を許可します。
4. 常時通知が表示されたら待機状態です。待機中はカメラ・マイクを起動しません。
5. Webの「接続・自動起動」で接続要求を送信します。OSが許可する場合はメディアサービスを自動起動します。
6. OSがバックグラウンド起動を拒否した場合は、通知をタップしてアプリを開いてください。アプリ画面からカメラ・マイクを開始します。

## 実装されている主な処理

- 6桁ペアリングコードと端末トークン登録
- GASへの約5秒間隔の指令確認、通信失敗時の段階的再試行
- Foreground Serviceによる低電力待機
- AndroidマイクからWebへのWebRTC送信
- WebからAndroidへの音声トラック受信（PTT）
- Webからのリア／フロントカメラ切替指令
- Wi-Fi / モバイル回線および電力モードに基づく画質調整
- 端末状態（バッテリー、充電、バッテリー温度、熱状態、ネットワーク、メモリ、映像情報）の送信
- バッテリー20%警告、重度の高温時の安全停止
- フレーム停止検出とカメラ再初期化の試行
- Android通知からの明示的な開始・停止

## Android OS上の制限

カメラとマイクはwhile-in-use権限の対象です。Android 13でもバックグラウンドから新しいカメラ／マイク処理を開始することは保証できません。Webからの自動接続要求は維持しますが、OSが拒否した場合は通知をタップして利用者がアプリを前面に出す必要があります。アプリ強制停止・電源OFFからの起動は保証しません。

## ビルド・確認

GitHub Actionsの `Build WebRTC Security Camera APK` からdebug APKを取得します。APKビルド成功と、Galaxy A22 5G実機でのWebRTC映像・音声・画面ロック試験成功は別の判定です。
