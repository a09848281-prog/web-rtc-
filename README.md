# WebRTC Security Camera

Android端末を利用者の許可のもとリアルタイムカメラとして使用し、iPad / PCブラウザから映像・音声を確認・操作する個人利用プロジェクトです。

- Android APK：Java + WebRTC + Foreground Service
- Web：HTML / CSS / JavaScript + GitHub Pages
- Pairing / Signaling：Google Apps Script + Google Sheets
- Media and controls：WebRTC P2P / DataChannel
- Firebase / FCM：使用しない
- Recording：なし（映像・音声ファイルを作成しない）

## 接続方式

初回ペアリングとSDP/ICE交換、RTC切断後の再接続にだけGASを使用します。AndroidのRTC DataChannelがWebと接続した後は、Webからの開始・停止、カメラ切替、PTT制御、通信/電力モード、状態通知をDataChannelで直接送ります。安定したRTC待機中の通常操作にGASポーリングは使いません。

WebRTC自体には初回の相手発見やSDP/ICE交換機能が含まれないため、RTC未接続状態から遠隔接続するための初期シグナリングは必要です。Webページを閉じた場合、次回は再度シグナリングします。

## Android画面

- 端末ペアリング（6桁コード）
- バックグラウンド待機開始
- 映像・音声切断（RTC待機は維持）

待機中はカメラ・マイクを使用しません。接続要求を受信した後にメディア開始を試みます。Android OSが拒否した場合は通知からアプリを開く必要があります。

## 公開先

- [GitHub Pages](https://a09848281-prog.github.io/web-rtc-/)
- [GitHub Actions](https://github.com/a09848281-prog/web-rtc-/actions)
- [Repository](https://github.com/a09848281-prog/web-rtc-)

## セットアップ

[docs/SETUP.md](docs/SETUP.md) を参照してください。GASコードはApps Scriptへ貼り付けて保存し、「デプロイを管理」→編集→新バージョン→デプロイが必要です。GitHubのCode.gsを更新しただけでは既存GAS URLには反映されません。

## 注意

- Firebase / FCMは使用しません。
- TURNサーバーは同梱していません。ネットワークによってはTURNが必要です。
- 管理者ログインはまだありません。個人の信頼できる範囲で使用してください。
- Android OSのバックグラウンド制限を回避する処理は行いません。
- GitHub Actionsビルド成功と、Android 13実機・iPad Safari・PCブラウザでの機能検証は別です。実機検証が終わるまで完成済みとは扱いません。
