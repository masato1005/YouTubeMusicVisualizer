# YouTube Music Visualizer

本アプリは生成AI(Codex)を用いて作成されています。
Windows上の専用Google Chromeで再生しているYouTube Musicだけを解析し、ジャケットとリアルタイム波形を表示する個人用アプリです。

## 必要環境

- Windows build 20348以降
- Google Chrome
- 開発時のみJDK 21とGradle

配布ZIPにはJavaランタイムが含まれるため、利用時のJavaインストールは不要です。

## 開発時の起動

```powershell
gradle run
```

初回起動時は専用Chromeプロファイルと、リモート制御を無効にしたログイン用Chromeが開きます。Chrome側でGoogleへログインし、YouTube Musicが表示されたらアプリへ戻って「ログイン完了・再接続」を押してください。その後は制御モードで自動起動します。ログイン情報をアプリが読み取ることはありません。

Googleが「このブラウザまたはアプリは安全でない可能性があります」と表示した場合は、設定画面の「一般」から「ログイン用Chrome」を開いてログインし、その後「ログイン完了・再接続」を押してください。ログイン用ChromeにはDevToolsのリモートデバッグ引数を付けません。

## 操作

| 領域 | 1クリック | 2クリック |
|---|---|---|
| 左30% | 10秒戻る | 前の曲 |
| 中央40% | 再生／停止 | 操作なし |
| 右30% | 10秒進む | 次の曲 |

- 右クリック：設定
- `F11`：全画面切替
- `Alt + ドラッグ`：ウィンドウ移動
- ウィンドウ端をドラッグ：サイズ変更
- 画像をドロップ：固定背景に変更

設定の「ビジュアル」では、最低音域（20Hz–5kHz）と最高音域（2kHz–24kHz）を変更できます。既定上限は22kHzです。直線方向を「上のみ」にすると基準線はウィンドウ底辺へ、「下のみ」にすると上辺へ配置されます。

## テスト

```powershell
gradle test
```

## ポータブルZIP

```powershell
gradle portableZip
```

生成物は `build/distributions/YouTubeMusicVisualizer-0.1.7-windows.zip` です。ZIP内から直接起動せず、`app`、`runtime`、`YouTubeMusicVisualizer.exe`を同じフォルダーへすべて展開してから起動してください。

## データ保存先

通常設定と専用Chromeプロファイルは `%LOCALAPPDATA%/YouTubeMusicVisualizer` に保存されます。

## 実装概要

- Kotlin/JVM + Compose Desktop
- JNA経由のWASAPI application loopback
- Radix-2 FFTと対数周波数バンド
- Chrome DevTools ProtocolとWeb Media Sessionによる曲情報・ジャケット・再生制御
- JSON設定の原子的な自動保存

WASAPI取得が使えない場合、ユーザー確認後にStereo Mixなどのシステム音声入力へ切り替えられます。

## 類似ツールとの違い

| ツール | 得意なこと | このアプリとの主な違い |
|---|---|---|
| [Wallpaper Engine](https://docs.wallpaperengine.io/en/web/audio/visualizer.html) | 壁紙内で左右各64帯域のシステム音声を使った表現 | 壁紙制作向け。本アプリは専用Chromeの音だけを対象にし、YouTube Musicのジャケット・曲情報・再生操作を一体化 |
| [projectM](https://github.com/projectM-visualizer/projectm) | PCM、FFT、ビート検出、OpenGLシェーダーとMilkDrop系プリセットによる多彩な表現 | 表現力重視の汎用ライブラリ。本アプリは波形中心の簡潔な表示とYouTube Music専用操作を優先 |
| [CAVA](https://github.com/karlstav/cava) | ターミナルまたはSDLでの軽量なバースペクトラム表示 | 汎用音声入力のバー表示が中心。本アプリは円形・滑らかな線・背景画像・曲情報・クリック操作を同じウィンドウで提供 |

本アプリの独自性は、視覚効果の種類を増やすことよりも「YouTube Music専用Chrome」「プロセス単位の音声取得」「ジャケット連動」「表示面そのものを使った最小限の操作」を一つにまとめた点にあります。
