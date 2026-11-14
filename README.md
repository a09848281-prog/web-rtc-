# WebRTC Security Camera

Android端末を、利用者が明示的に開始するリアルタイム監視カメラとして使い、iPad / PCのブラウザから接続する個人利用プロジェクトです。

- Android APK：Java + WebRTC + Foreground Service
- Web：HTML / CSS / JavaScript + GitHub Pages
- Signaling / 登録：Google Apps Script + Google Sheets
- Media：WebRTC (DTLS-SRTP)
- 録画・録音：なし

## ビルド状況

- Android APK：GitHub Actionsのdebug APKビルド成功
- Web / GAS / manifest：構文チェック成功
- GitHub Pages：リポジトリ設定でPagesを有効化する必要があります（Settings → Pages → Source: GitHub Actions）

[最新APKビルド結果](https://github.com/a09848281-prog/web-rtc-/actions/runs/43597309633)  
[リポジトリ](https://github.com/a09848281-prog/web-rtc-)

## 安全上の重要事項

Android OSの制限に従います。Webから接続要求が届いても、ロック中・バックグラウンド中にカメラ/マイクを無人で新規起動しません。Android側の通知をタップし、利用者が開始します。メディア使用中はForeground Service通知を表示します。

## ドキュメント

- [完全機能・開発仕様書](SPECIFICATION.md)
- [セットアップ手順](docs/SETUP.md)
- [開発状況・未完了機能](docs/STATUS.md)
- [Android実装メモ](android/README.md)

この初期ビルドにはペアリング、端末一覧、GASシグナリング、Androidのカメラ/マイク送信土台が含まれます。PTT、遠隔カメラ切替、FCM、温度保護などはまだ未実装です。実機検証前に完成扱いしません。
