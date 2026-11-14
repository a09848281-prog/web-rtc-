# WebRTC Security Camera — 完全版機能・開発仕様書 v1.0

作成日：2026年11月14日 / 用途：個人利用 / 対象：Android 13・iPad・PC

## 1. 概要・構成
Androidを遠隔監視カメラとして使い、iPad / PCブラウザからリアルタイム映像・音声を確認・操作する。録画・録音はしない。Android APKはJava + WebRTC + Camera2/Camera1 + Foreground Service。WebはHTML/CSS/JavaScript + GitHub Pages。サーバーはGAS + Sheets + FCM（Firebase設定後）。メディアはDTLS-SRTPでWebRTC端末間直接通信し、GASは認証・端末管理・シグナリングのみとする。

## 2. ペアリング・認証
Androidごとに6桁の一時ペアリングコードを発行し、Android側で入力する。コードは10分で失効し1回のみ使用可能。試行回数を制限する。登録後はランダムな端末トークンをAndroidに保存し、Sheetsにはハッシュのみ保存。登録済み複数端末を一覧表示し、選択した1台だけ接続する。管理者キーや端末トークンをHTMLへ直書きしない。

## 3. 待機・遠隔接続
待機中はカメラ・マイクを使用しない。接続要求はFCMを優先予定とし、Firebase設定前は低頻度GASポーリングを暫定使用。Webから要求が来たらAndroidへ通知する。**新規のカメラ・マイク起動はAndroid利用者が通知またはアプリを操作して開始する。** OS制限を回避する無人起動は行わない。アプリ未起動・強制停止・電源OFF状態からの接続は保証しない。既存セッションの画面ロック後継続は通知付きForeground Serviceで実機検証する。

## 4. カメラ・音声
背面カメラを初期選択し、前面切替に対応。1080p / 30fpsを目標とし、端末性能と通信品質で調整。アスペクト比を維持。対応する場合はHWエンコード。暗所補正、フレーム停止検知、再初期化を実装対象とする。AndroidマイクからWebへ送信し、WebからAndroidスピーカーへPTT送信する。静止画・録画・録音ファイルは作らない。

## 5. Web画面
白基調・日本語・iPad横画面・PC対応。端末一覧、接続状態、ライブ映像、全画面、カメラ切替、音声ミュート、PTT、終了ボタン、通信統計、FPS/解像度、バッテリー/充電/温度/CPU推定/通信種別、通信・電力モードを表示。切断時はライブと誤認させない。最後のフレームを残す場合は古い映像であることを明示する。

## 6. 通信・電力モード
通信：Wi-Fi高画質（最大目標1080p/30fps）、モバイル低画質、通信品質に応じる自動。STUNだけで通らない環境向けにTURNを設定可能とする。電力：省電力/通常/高性能/自動。自動は熱状態、CPU推定、バッテリー、充電、通信品質を考慮。高温保護は解除不可。

## 7. 端末状態・最適化
取得可能なバッテリー、充電、バッテリー温度、Android熱状態、CPU推定、メモリ、ネットワーク、FPS、解像度、WebRTC統計を表示。取得不可は「取得不可」。高温・高負荷・低残量・モバイル回線・通信不安定時は解像度/FPS/ビットレートを下げる。

## 8. 異常検知・安全停止
暗闇は輝度の継続値で検出。カメラのエラー/フリーズ/フレーム停止を検出し、回数制限付きで再初期化。バッテリー警告は初期20%。WebRTC/ネットワーク/サービス状態を監視。**Android熱状態がSEVERE以上、またはシステムが継続使用不可と判断した場合はカメラ・マイク・WebRTCを安全停止する。ユーザー設定で無効化できない。** 通常エラーは自動復旧を試し、復旧不能なら停止通知する。

## 9. スケジュール・終了
曜日、開始/終了時刻、終了前通知を設定可能とする。ただし時刻到来だけでOS制限を越えてカメラ/マイクを自動起動しない。終了時はカメラ・マイク・WebRTC・WakeLock等を解放。Web/Androidの両方から停止可能。

## 10. 保存方針・Android権限
映像・音声・接続履歴・エラー履歴・端末状態履歴は保存しない。必要な登録・認証・一時コマンド・SDP/ICEのみ有効期限付きで保持。対象権限：INTERNET、ACCESS_NETWORK_STATE、ACCESS_WIFI_STATE、CHANGE_NETWORK_STATE、CHANGE_WIFI_STATE、CAMERA、RECORD_AUDIO、FOREGROUND_SERVICE、FOREGROUND_SERVICE_CAMERA、FOREGROUND_SERVICE_MICROPHONE、FOREGROUND_SERVICE_CONNECTED_DEVICE、POST_NOTIFICATIONS、WAKE_LOCK。起動前に権限・WebRTC・サーバー接続・Foreground Serviceを検査し原因を日本語表示する。

## 11. 開発方針・必須チェック
既存WebRTC Linkを上書きせず独立開発。Java APK、GitHub Pages、GAS、WebRTCを実装。Firebase/TURN秘密情報はリポジトリへコミットしない。
6項目：①権限不足でクラッシュしない ②WebRTC初期化の例外処理 ③カメラ/マイク起動の例外処理と解放 ④Foreground Service通知 ⑤GAS/FCM通信例外処理 ⑥画面ロック/長時間/再接続をAndroid 13実機検証。

## 12. 完成判定
手動ペアリング、自動接続要求、安定映像/音声、遠隔カメラ切替、PTT、通信/電力モード、端末状態、異常検知/復旧、Android 13実機、iPad Safari、PC、録画/録音なしを確認。実機検証前に完成扱いしない。
