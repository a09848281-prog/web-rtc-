# WebRTC Security Camera

Android端末を、利用者の許可のもとリアルタイム監視カメラとして使用し、iPad / PCのブラウザから映像・音声を確認・操作する個人利用プロジェクトです。

- Android APK：Java + WebRTC + Foreground Service
- Web：HTML / CSS / JavaScript + GitHub Pages
- Signaling / 登録：Google Apps Script + Google Sheets
- Media：WebRTC（DTLS-SRTP / P2P）
- Push：Firebase / FCMは使用しない。GASへ通常5秒間隔で指令確認
- 録画・録音：なし

## 今回のUI / 機能更新

白基調の大画面ビューア、登録端末一覧、接続・自動起動要求、カメラ切替、ミュート、PTT、通信・電力モード、スケジュール、端末状態表示、バッテリー警告・高温停止・フレーム停止復旧を実装対象にしています。

## 公開先

- [GitHub Pages](https://a09848281-prog.github.io/web-rtc-/)
- [GitHub Actions](https://github.com/a09848281-prog/web-rtc-/actions)
- [Repository](https://github.com/a09848281-prog/web-rtc-)

## 重要

Webからの自動接続要求は維持しています。ただしAndroid 13ではバックグラウンドからカメラ・マイクを新規起動できない場合があります。OSが拒否した場合はAndroidの常時通知からアプリを開く必要があります。OS制限を回避する無人起動は行いません。

今回のGASコードはApps Scriptへ貼り付け、「デプロイを管理」→編集→新バージョン→デプロイが必要です。GitHubのCode.gsを更新しただけでは既存GAS URLに反映されません。

管理者認証は未実装です。GitHub Pagesを閲覧できる人が端末一覧・登録コード・指令APIを操作できるため、信頼できる個人利用に限定してください。実機検証が終わるまで完成版として扱わないでください。
