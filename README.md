# GeoLog（Rakuten Hand 5G 用）

GPS だけで（ネット不要）時刻・町字・時速・標高・衛星の数を大きく表示し、5 秒ごとに位置を記録する。
**ホームアプリに設定済み**（`adb shell cmd package set-home-activity jp.hasami.geolog/.MainActivity`、2026-10-16）。

## 画面（0.2.0〜、`HudView.java`）

映画「マトリックス」風：黒地に緑（#00FF41）の光る文字、後ろに流れるカタカナと数字（デジタルレイン、毎秒 20 回描き直す）、走査線。
- 上：`// SYS.TIME GPS-SYNC`、時刻（大）、日付と曜日。縦のときは時刻を幅いっぱい（左右 16 ずつ）で中央揃え、日付と曜日も中央（0.7.1〜）。
- 住所の札（0.3.0〜）：地図の左上、日本海の空いた所（`MapProj.TEXT_BOX`）に半透明の黒い枠で重ねる。`// GEO.NODE LOCKED`、
  1 行目に都道府県＋市区町村（最大 50。郡を含めると最長は 13 文字「山梨県南都留郡富士河口湖町」「和歌山県東牟婁郡那智勝浦町」、郡なしでは「鹿児島県いちき串木野市」の 11 文字。幅に合わせて縮める）、
  改行して町字（最大 96 の太字。56 より小さくなる長い名前は半分で改行して 2 行。最長は京都の 23 文字）。
  確認用：`adb shell am start -n jp.hasami.geolog/.MainActivity --es demo_city 県市 --es demo_town 町字`（`--ez demo_off true` で戻す）。
- 中：**点描の日本地図**（町字の代表点 約 21 万点を緑の点で打ったもの。`tools/gen_map.py` で作る `japan_dots.png` 720x795）。
  小笠原は右下の枠。南西諸島（沖縄・奄美）は描かない（0.3.0〜、ユーザーの希望。そこにいるときはピンが出ない）。今いる所に赤い脈打つピンと十字線、「YOU ARE HERE」。地図の位置の式は `MapProj.java`（gen_map.py が同じ式で書き出す）。
  ユーザーからもらった日本地図の画像は出どころとライセンスが分からないので使わず、自前のデータから作った。
- **古い地名（OLD、0.9.0〜）**：住所の札の右上の `[NOW]`／`[OLD]` で切り替え（覚える）。OLD では
  「都道府県＋郡＋**1970 年の市町村**」「**2000 年の町丁・字**（大。分からなければ今の町字）」「現在 今の住所（小）」。続けて読むと「茨城県筑波郡伊奈村大字中島」。
  市町村と町字で時期が違うので、ボタンは「1970」ではなく「OLD」。拡大の LOCAL の名前も 2000 年の町丁・字になる（同じ名前は 1 回）。
  データは `assets/old1970.bin`（rgeo_all.bin の点ごとの 1970-04-01 の市町村、`OldNames.java`）。1970 年に無かった埋立地は「1970年には無い土地」。
  拡大地図の境界線も 1970 年のものになり、PREF の名前も当時の市町村になる。
  元データ：「地図地理Sandbox」市区町村境界時系列データ（製作 はにしな）。作り方は https://github.com/fujio-ux/reverse-geocoding の README。
- 拡大地図の境界線（0.8.0〜）：`assets/bounds.bin`（同じく地図地理Sandbox の境界線、今と 1970 年、`Bounds.java`）。都道府県界は太く、郡界・市町村界は細く、海岸線は暗い緑。
- 地図の拡大（0.4.0〜、`ZoomMap.java`。0.8.0〜 は**常に今いる所がまん中、決まった広さ**：PREF は短い辺 120km、LOCAL は 10km）：地図の右下の `[ZOOM]` ボタンで JAPAN → PREF → LOCAL → JAPAN（選んだものは覚える）。
  - PREF：今いる都道府県が枠いっぱい（その都道府県の点のうち 300km 以内・緯度経度の 1〜99% の範囲。東京の小笠原や鹿児島の離島で広がりすぎない）。
    都道府県の点は明るく、隣の県は暗く。市区町村の名前を点の多い順に、重ならないものだけ。
  - LOCAL：今いる所を中心に短い辺で 10km。町字の点と名前（中心に近い順に最大 60、重ならないものだけ）、1・3・5km の輪。8 ドット以上動いたら描き直す。
  - 町字のデータは LogService が開いたものを `State.rgeo` で共有し、`RGeo.decodeAll()` で全部の点を配列にしてある。
  - 測位できていないときは全国の地図に戻る。
- 色（0.4.1〜）：地図は緑。地図の上に出す市区町村名・町字名（住所の札と拡大時の名前）は白（拡大時の名前は黒い縁取りつき）。
  「YOU ARE HERE」は文字は赤のまま、地だけ赤の補色のシアン（0,195,195）。
- 下：`// VELOCITY`（km/h、3 桁）、`// ALTITUDE`（m、符号つき）、衛星・みちびき・誤差、緯度経度、送信の状態（失敗は黄色）。
- 書体（0.7.0〜）：**英数字は瀞ノグリッチ黒体**（作者 あまずさ鴒、源ノ角ゴシックの改変、SIL Open Font License、https://github.com/amazusa/ToronoGlitch ）。
  大きな数字（時刻・日付・時速・標高）は H3、ほかは H1。日本語まで入った元のファイル（13〜15MB）を英数字だけに切り出して（fontTools 4.67.0 の `pyftsubset --unicodes=U+0020-007E,U+00B0,U+00B1`）
  `res/font/toro_glitch_h1.otf`・`toro_glitch_h3.otf`（各 32KB）として入れた。ライセンスと由来は `tools/OFL-ToronoGlitch.txt`。地図の絵の文字（経緯度・OGASAWARA）も H1。
  **日本語は明朝体**（端末の `/system/fonts/NotoSerifCJK-Regular.ttc` の 0 番）。`Typeface.CustomFallbackBuilder` で「英数字＝グリッチ、無い文字＝明朝体」の 1 つの書体にしている。
  経緯：Share Tech Mono（〜0.5）→ Bodoni Moda（0.6）→ 瀞ノグリッチ黒体（0.7）。Bauer Bodoni は商用フォントで、アプリへの埋め込みに別のライセンスが要るので使っていない。
- 右上の `[ROT]`（0.5.1〜）：押すたびに 360（縦）→ 090（横）→ 180（逆さの縦）→ 270（逆の横）→ 360 と変えて覚える（重力センサーでは変えない。`setRequestedOrientation`、向きが変わっても作り直さない `configChanges`）。
  横向き：左の列に時刻と計器、右に地図（拡大中は右側いっぱい）。文字の大きさは短い辺 720 ドットを基準にする。
- 画面を軽く押す：画面を点けたままにする／戻す。長押し：Android の設定を開く（ホームアプリにしたときの出口）。
- ホームアプリにもなれる（設定 → アプリ → 標準のアプリ → ホームアプリ で GeoLog を選ぶ）。
- 記録は通知を出す前面サービスで動き、画面を消しても続く。電源を入れたら自動で始まる。
- 記録の形：端末の `files/queue.csv`（時刻ミリ秒,緯度,経度,標高,km/h,向き,誤差,衛星,町字）。サーバーの返事の `last` までを消す（二重に送っても、サーバー側で同じ時刻以前は捨てる）。
- 端末名：`P780-` ＋ ANDROID_ID の先頭 6 文字。

## ビルド・テスト・入れ方（Mac）

```bash
cd geolog
./test.sh                     # RGeo（総当たりと 200 点比較・展開）・ZoomMap・OldNames・Bounds・Nmea・Track のテスト（33 件）。通ってから入れる
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 初回だけ：許可・電池の最適化の対象外・サーバーの鍵（値は画面に出さずにサーバーから直接入れる）
adb shell pm grant jp.hasami.geolog android.permission.ACCESS_FINE_LOCATION
adb shell pm grant jp.hasami.geolog android.permission.ACCESS_BACKGROUND_LOCATION
adb shell dumpsys deviceidle whitelist +jp.hasami.geolog
<サーバーの鍵を標準出力に出すコマンド> | adb shell "run-as jp.hasami.geolog sh -c 'mkdir -p files && cat > files/token.txt'"   # 鍵の値は画面に出さない
```

- 町字のデータを新しくしたら reverse-geocoding の `data/rgeo_all.bin`・`data/history/*.bin` を `app/src/main/assets/` に写してからビルド（`./test.sh` は reverse-geocoding が `~/Documents/reverse-geocoding` にある前提で、`data/towns.csv` と総当たりで比べる）。
- `gradle.properties` で Gradle が使う Java を 21 に固定している（Mac の Java 27 は Gradle がまだ対応していない）。`./gradlew` を起動するためだけに JAVA_HOME も要る。

## サーバー側（このリポジトリには入っていない）

送り先は `LogService.UPLOAD_URL`（PHP の受け口）。どの操作もヘッダー `X-Geolog-Token`（32 文字以上の鍵）が必要。
- `POST ?action=upload` 本文 JSON `{"device":"…","points":[[時刻ミリ秒,緯度,経度,標高,km/h,向き,誤差,衛星,町字],…]}` → `{"ok":true,"saved":n,"skipped":n,"rejected":n,"last":最後の時刻}`
  （端末は `last` までの点を消す。サーバーは（端末, 時刻）が同じ点を二重に保存しない）
- `GET ?action=days&device=…` → 記録のある日付、`GET ?action=gpx&device=…&date=YYYY-MM-DD` → GPX

## ライセンス

- プログラム：MIT（`LICENSE`）。
- `assets/rgeo_all.bin`：デジタル庁 アドレス・ベース・レジストリ（CC BY 4.0）を加工（https://github.com/fujio-ux/reverse-geocoding ）。
- `assets/old1970.bin`・`bounds.bin`：「地図地理Sandbox」市区町村境界時系列データ（製作 はにしな、https://hanishina.net/maps/historydata.html ）を加工。
- `assets/old2000.bin`：政府統計の総合窓口（e-Stat）平成 12 年国勢調査 町丁・字等別境界データ（https://www.e-stat.go.jp/ ）を加工。
- `res/drawable-nodpi/japan_dots.png`：上の rgeo_all.bin の代表点から作った点描の地図。
- `res/font/toro_glitch_h1.otf`・`h3.otf`：瀞ノグリッチ黒体（あまずさ鴒、SIL Open Font License 1.1）の英数字を切り出したもの（`tools/OFL-ToronoGlitch.txt`）。
