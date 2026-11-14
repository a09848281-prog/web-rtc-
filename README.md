# WebRTC Security Camera

Android端末を、利用者の明示操作で開始するリアルタイム監視カメラとして使い、iPad / PCのブラウザから確認する個人利用プロジェクトです。

- Android: Java + WebRTC + Foreground Service
- Web: GitHub Pages / HTML / CSS / JavaScript
- Signaling: Google Apps Script + Google Sheets
- Media: WebRTC (DTLS-SRTP)
- Recording: なし（映像・音声を保存しません）

## 安全上の重要事項

AndroidのOS制限に従います。Webから接続要求を受け取っても、ロック中やバックグラウンド状態からカメラ・マイクを無人で新規起動しません。Androidに通知し、利用者が通知またはアプリ画面を操作して開始します。継続中のセッションはForeground Serviceの通知を表示します。

## 現在の開発段階

初期リポジトリを作成しました。GitHub ActionsでAPKをビルドし、ビルド結果を確認しながら機能を段階的に実装します。Firebase設定が必要なFCMとTURN認証情報はリポジトリへ秘密情報を直接書かず、別途設定します。

## ドキュメント

- [完全機能・開発仕様書](SPECIFICATION.md)
- [セットアップ手順](docs/SETUP.md)
- [開発状況](docs/STATUS.md)
