package jp.hasami.geolog;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

/**
 * 映画「マトリックス」風の画面。黒地に緑の文字、後ろに流れる文字（デジタルレイン）と点描の日本地図、今いる所にピン。
 *   上：時刻（GPS）と日付
 *   中：日本地図（町字の代表点を緑の点で打ったもの）とピン。左上の日本海の空いた所に住所の札を重ねる
 *   下：時速・標高・衛星・位置・送信の状態
 * 幅 720 ドットを基準に、画面の大きさに合わせて縮める。
 */
public class HudView extends View {
  static final int GREEN = Color.rgb(0, 255, 65), GREEN_DIM = Color.rgb(0, 150, 40), GREEN_DARK = Color.rgb(0, 70, 20),
      AMBER = Color.rgb(255, 176, 0), RED = Color.rgb(255, 60, 60),
      WHITE = Color.rgb(245, 245, 245),
      CYAN = Color.rgb(0, 195, 195);   // 赤（255,60,60）の補色。YOU ARE HERE の地に使う

  private final Bitmap map;
  private final Typeface mono, jp, jpBold, bodoni;
  private final Paint pMap = new Paint(Paint.FILTER_BITMAP_FLAG);
  private final Paint pRain = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint pScan = new Paint();
  private final SimpleDateFormat fTime = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN);
  private final SimpleDateFormat fDate = new SimpleDateFormat("yyyy.MM.dd", Locale.JAPAN);
  private final SimpleDateFormat fWeek = new SimpleDateFormat("EEEE", Locale.JAPAN);
  private final SimpleDateFormat fHm = new SimpleDateFormat("HH:mm", Locale.JAPAN);

  // デジタルレイン
  private static final String RAIN_CHARS = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ0123456789:.=*+-<>";
  private final Random rnd = new Random();
  private float[] rainY, rainSpeed;
  private char[][] rainCol;
  private long lastFrame;
  boolean keepOn;

  // 地図の拡大（右下のボタンで 全国 → 都道府県 → 周辺 → 全国）
  private final SharedPreferences prefs;
  private int zoomMode;
  private ZoomMap zoom;
  private final RectF zoomBtn = new RectF();
  private boolean zoomBtnDown;
  // LOCAL のときだけ、指でなぞって地図を動かす。panE・panN は今いる所からまん中までのずれ（メートル、東・北が正）
  private final RectF zoomArea = new RectF();
  private final RectF centerBtn = new RectF();
  private boolean centerBtnDown, panTracking, panning;
  private float downX, downY, lastX, lastY;
  private double panE, panN;
  // 縦横の切り替え（右上のボタン。重力センサーでは変えない）
  private final RectF rotBtn = new RectF();
  // 住所の札の右上：今の名前 ↔ 古い名前（OLD：市町村は 1970 年、町字は 2000 年。時期が混ざるので「1970」とは書かない）
  private final RectF eraBtn = new RectF();
  private boolean eraBtnDown;
  boolean era1970;
  // タコメーター（右上の [TACHO]／[MAP] で地図と切り替え。ELM327 とつながっていなければデモの動き）
  private final RectF tachoBtn = new RectF();
  private boolean tachoBtnDown;
  boolean tacho;
  private float shownRpm;   // 針の位置（目標へ少しずつ近づけて、なめらかに動かす）
  private boolean rotBtnDown;
  Runnable onRotate;
  int rot;   // 今の向き（0：360 度、1：90 度、2：180 度、3：270 度）

  public HudView(Context ctx) {
    super(ctx);
    setBackgroundColor(Color.BLACK);
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inScaled = false;
    map = BitmapFactory.decodeResource(getResources(), R.drawable.japan_dots, o);
    // 書体：英数字は瀞ノグリッチ黒体（OFL。大きな数字は H3、ほかは H1。英数字だけに切り出したもの）、日本語は明朝体（端末の Noto Serif CJK）
    bodoni = glitchMincho(ctx, R.font.toro_glitch_h3);   // 大きな数字（時刻・時速・標高・日付）
    mono = glitchMincho(ctx, R.font.toro_glitch_h1);
    jp = mono;
    jpBold = mono;
    TimeZone jst = TimeZone.getTimeZone("Asia/Tokyo");
    for (SimpleDateFormat f : new SimpleDateFormat[] {fTime, fDate, fWeek, fHm}) f.setTimeZone(jst);
    pLine.setStyle(Paint.Style.STROKE);
    pScan.setColor(Color.argb(28, 0, 0, 0));
    prefs = ctx.getSharedPreferences("hud", Context.MODE_PRIVATE);
    zoomMode = prefs.getInt("zoom", ZoomMap.JAPAN);
    era1970 = prefs.getBoolean("era1970", false);
    tacho = prefs.getBoolean("tacho", false);
    if (tacho) ObdLink.get().start();
  }

  /**
   * 瀞ノグリッチ黒体（英数字だけ）に、明朝体（Noto Serif CJK の日本語、/system/fonts/NotoSerifCJK-Regular.ttc の 0 番）を足した書体。
   * 英数字に無い文字（日本語）は明朝体になる。明朝体が読めない端末では、端末の serif を使う
   */
  private static Typeface glitchMincho(Context ctx, int fontRes) {
    try {
      Font bod = new Font.Builder(ctx.getResources(), fontRes).build();
      Font min = new Font.Builder(new File("/system/fonts/NotoSerifCJK-Regular.ttc")).setTtcIndex(0).build();
      return new Typeface.CustomFallbackBuilder(new FontFamily.Builder(bod).build())
          .addCustomFallback(new FontFamily.Builder(min).build())
          .setSystemFallback("serif")
          .build();
    } catch (Exception e) {
      return Typeface.SERIF;
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    // ボタン（右下の ZOOM、右上の ROTATE）は、画面を点けたままにする操作（押す）・設定（長押し）より先に受け取る
    float x = e.getX(), y = e.getY();
    int act = e.getActionMasked();
    if (act == MotionEvent.ACTION_DOWN) {
      zoomBtnDown = zoomBtn.contains(x, y);
      rotBtnDown = rotBtn.contains(x, y);
      tachoBtnDown = tachoBtn.contains(x, y);
      eraBtnDown = eraBtn.contains(x, y);
      centerBtnDown = isPanned() && centerBtn.contains(x, y);
      boolean onButton = zoomBtnDown || rotBtnDown || eraBtnDown || centerBtnDown || tachoBtnDown;
      panTracking = !onButton && zoomMode == ZoomMap.LOCAL && zoomArea.contains(x, y);
      panning = false;
      downX = lastX = x;
      downY = lastY = y;
    }
    if (panTracking && handlePan(e, x, y, act)) return true;
    if (!zoomBtnDown && !rotBtnDown && !eraBtnDown && !centerBtnDown && !tachoBtnDown) return super.onTouchEvent(e);
    if (act == MotionEvent.ACTION_UP) {
      if (zoomBtnDown && zoomBtn.contains(x, y)) {
        zoomMode = (zoomMode + 1) % 3;
        panE = panN = 0;   // ZOOM を変えたら今いる所をまん中に戻す
        prefs.edit().putInt("zoom", zoomMode).apply();
      }
      if (rotBtnDown && rotBtn.contains(x, y) && onRotate != null) onRotate.run();
      if (tachoBtnDown && tachoBtn.contains(x, y)) {
        tacho = !tacho;
        prefs.edit().putBoolean("tacho", tacho).apply();
        if (tacho) ObdLink.get().start(); else ObdLink.get().stop();
      }
      if (eraBtnDown && eraBtn.contains(x, y)) {
        era1970 = !era1970;
        prefs.edit().putBoolean("era1970", era1970).apply();
      }
      if (centerBtnDown && centerBtn.contains(x, y)) panE = panN = 0;
      zoomBtnDown = rotBtnDown = eraBtnDown = centerBtnDown = tachoBtnDown = false;
    } else if (act == MotionEvent.ACTION_CANCEL) {
      zoomBtnDown = rotBtnDown = eraBtnDown = centerBtnDown = tachoBtnDown = false;
    }
    invalidate();
    return true;
  }

  /**
   * LOCAL の地図をなぞって動かす。指が少し動いたら（8dp）動かし始め、それまでは普通の押す・長押しとして扱う。
   * 動かし始めたら、押す・長押しの判定は取り消す。true を返したら、この指の動きはここで使い切った
   */
  private boolean handlePan(MotionEvent e, float x, float y, int act) {
    float slop = 8 * getResources().getDisplayMetrics().density;
    if (act == MotionEvent.ACTION_MOVE) {
      if (!panning && Math.hypot(x - downX, y - downY) > slop) {
        panning = true;
        MotionEvent cancel = MotionEvent.obtain(e);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.onTouchEvent(cancel);   // 長押し（設定を開く）や押す（画面を点けたまま）を取り消す
        cancel.recycle();
      }
      if (panning && zoom != null) {
        // 指が右へ動いたら地図も右へ＝まん中は西へ
        panE -= (x - lastX) * zoom.mpp;
        panN += (y - lastY) * zoom.mpp;
        lastX = x;
        lastY = y;
        invalidate();
        return true;
      }
      return false;
    }
    if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
      boolean was = panning;
      panTracking = panning = false;
      return was;
    }
    return false;
  }

  /** 地図を動かして、まん中が今いる所からずれているか（LOCAL のときだけ） */
  private boolean isPanned() { return zoomMode == ZoomMap.LOCAL && (panE != 0 || panN != 0); }

  @Override
  protected void onSizeChanged(int w, int h, int ow, int oh) {
    float s = Math.min(w, h) / 720f;
    int cols = (int) (w / (22 * s));
    rainY = new float[cols];
    rainSpeed = new float[cols];
    rainCol = new char[cols][24];
    for (int i = 0; i < cols; i++) {
      rainY[i] = rnd.nextFloat() * h;
      rainSpeed[i] = (60 + rnd.nextFloat() * 160) * s;   // ドット／秒
      for (int j = 0; j < 24; j++) rainCol[i][j] = RAIN_CHARS.charAt(rnd.nextInt(RAIN_CHARS.length()));
    }
  }

  @Override
  protected void onDraw(Canvas c) {
    int w = getWidth(), h = getHeight();
    boolean land = w > h;
    float s = Math.min(w, h) / 720f;   // 短い辺 720 ドットを基準に
    long nowMs = SystemClock.uptimeMillis();
    float dt = lastFrame == 0 ? 0 : (nowMs - lastFrame) / 1000f;
    lastFrame = nowMs;

    drawRain(c, w, h, s, dt);

    // ---- 時刻（縦：上／横：左の列の上） ----
    float topEnd = drawClock(c, s, land, w);

    // ---- 地図と計器の置き場所 ----
    //   縦：上に時刻、真ん中に地図、下に計器
    //   横：左の列に時刻と計器、右に地図（高さいっぱい）
    float mapL, mapT, mapR, mapB, by;
    if (land) {
      float colW = 660 * s;
      mapL = colW; mapT = 0; mapR = w; mapB = h;
      by = topEnd + 6 * s;
    } else {
      by = h - 350 * s;
      mapL = 0; mapT = topEnd; mapR = w; mapB = by;
    }
    float aw = mapR - mapL, ah = mapB - mapT;
    float scale = Math.min(aw / MapProj.W, ah / MapProj.H);
    float mw = MapProj.W * scale, mh = MapProj.H * scale;
    float mx = mapL + (aw - mw) / 2, my = mapT + (ah - mh) / 2;
    boolean zoomed = zoomMode != ZoomMap.JAPAN && State.fix && State.rgeo != null;
    if (tacho) {
      zoomArea.setEmpty();
      centerBtn.setEmpty();
      zoomBtn.setEmpty();
      eraBtn.setEmpty();
      drawTacho(c, mapL, mapT, aw, ah, s, nowMs);
    } else if (zoomed) {
      // 拡大した地図は、置き場所いっぱいに描く（住所の札とボタンもその枠に合わせる）
      zoomArea.set(mapL, mapT, mapL + aw, mapT + ah);
      drawZoom(c, mapL, mapT, aw, ah, s, nowMs);
      drawPlace(c, mapL, mapT, scale, s, nowMs);
      drawZoomButton(c, mapL, mapT, aw, ah, s);
      drawCenterButton(c, mapL, mapT, aw, ah, s);
    } else {
      zoomArea.setEmpty();
      centerBtn.setEmpty();
      pMap.setAlpha(State.fix ? 230 : 140);
      c.drawBitmap(map, null, new RectF(mx, my, mx + mw, my + mh), pMap);
      if (State.fix) drawPin(c, mx, my, scale, s, nowMs);
      drawPlace(c, mx, my, scale, s, nowMs);
      drawZoomButton(c, mx, my, mw, mh, s);
    }

    drawInstruments(c, by, land ? 660 * s : w, s);
    drawRotateButton(c, w, s);
    drawTachoButton(c, s);

    // 走査線
    for (int sy = 0; sy < h; sy += Math.max(3, (int) (4 * s))) c.drawLine(0, sy, w, sy, pScan);

    postInvalidateDelayed(50);   // 毎秒 20 回描き直す（画面が見えている間だけ）
  }

  /** 時刻と日付。戻り値は書き終えた所の y */
  private float drawClock(Canvas c, float s, boolean land, int w) {
    long now = System.currentTimeMillis() + (State.gpsTimeKnown ? State.gpsMinusSystem : 0);
    Date d = new Date(now);
    float y = 52 * s;
    label(c, "// SYS.TIME  " + (State.gpsTimeKnown ? "GPS-SYNC" : "LOCAL-CLOCK"), 24 * s, y, s);
    String time = fTime.format(d), date = fDate.format(d), week = fWeek.format(d);
    if (land) {
      y += 112 * s;
      bodoniFixed(c, time, 24 * s, y, 132 * s, GREEN, 14 * s, 600);
      y += 46 * s;
      float dw = bodoniFixed(c, date, 28 * s, y, 36 * s, GREEN_DIM, 0, 500);
      glow(c, week, 28 * s + dw + 18 * s, y, 28 * s, jp, GREEN_DIM, 0);
      return y + 18 * s;
    }
    // 縦：時刻は幅いっぱい（左右 16 ずつ空ける）で中央に。日付と曜日も中央に
    float base = 132 * s;
    float size = base * (w - 32 * s) / measureFixed(time, base);
    float tw = measureFixed(time, size);
    y += 22 * s + size * 0.78f;   // 右上の [ROT] ボタンと重ならないよう少し下げる
    bodoniFixed(c, time, (w - tw) / 2, y, size, GREEN, 16 * s, 600);
    y += 48 * s;
    float dw = measureFixed(date, 36 * s);
    pText.setTypeface(jp);
    pText.setTextSize(28 * s);
    float ww = pText.measureText(week);
    float x0 = (w - (dw + 18 * s + ww)) / 2;
    bodoniFixed(c, date, x0, y, 36 * s, GREEN_DIM, 0, 500);
    glow(c, week, x0 + dw + 18 * s, y, 28 * s, jp, GREEN_DIM, 0);
    return y + 18 * s;
  }

  /** bodoniFixed で書いたときの幅（書かない） */
  private float measureFixed(String str, float size) {
    setBodoni(size, 600);
    float digitW = pText.measureText("0"), total = 0;
    for (int i = 0; i < str.length(); i++) {
      total += Character.isDigit(str.charAt(i)) ? digitW : pText.measureText(str.substring(i, i + 1)) + size * 0.04f;
    }
    return total;
  }

  /** 計器（時速・標高・衛星・位置・送信）。by は区切り線の y、colW は使える幅 */
  private void drawInstruments(Canvas c, float by, float colW, float s) {
    pLine.setColor(GREEN_DARK);
    pLine.setStrokeWidth(2 * s);
    c.drawLine(24 * s, by, colW - 24 * s, by, pLine);

    float ly = by + 40 * s;
    label(c, "// VELOCITY", 24 * s, ly, s);
    label(c, "// ALTITUDE", 420 * s, ly, s);
    String kmh = State.fix && !Double.isNaN(State.kmh) ? String.format(Locale.ROOT, "%03.0f", State.kmh) : "---";
    float kw = bodoniFixed(c, kmh, 24 * s, ly + 100 * s, 120 * s, GREEN, 14 * s, 600);
    bodoni(c, "km/h", 24 * s + kw + 12 * s, ly + 100 * s, 36 * s, GREEN_DIM, 0, 500);
    String alt = State.fix && !Double.isNaN(State.altMsl) ? String.format(Locale.ROOT, "%+05.0f", State.altMsl) : "-----";
    float aw2 = bodoniFixed(c, alt, 420 * s, ly + 100 * s, 72 * s, GREEN, 10 * s, 600);
    bodoni(c, "m", 420 * s + aw2 + 10 * s, ly + 100 * s, 36 * s, GREEN_DIM, 0, 500);

    float iy = ly + 152 * s;
    String sat = String.format(Locale.ROOT, "SAT %02d/%02d  QZSS %d/%d", State.satsUsed, State.satsVisible, State.qzssUsed, State.qzssVisible);
    if (State.fix && !Double.isNaN(State.acc)) sat += String.format(Locale.ROOT, "  ACC +-%.0fm", State.acc);
    glow(c, sat, 24 * s, iy, 30 * s, mono, GREEN_DIM, 0);
    if (State.fix) {
      glow(c, String.format(Locale.ROOT, "LAT %9.5f  LON %10.5f", State.lat, State.lon), 24 * s, iy + 40 * s, 30 * s, mono, GREEN_DIM, 0);
    }
    String last = State.lastUploadAt == 0 ? "--:--" : fHm.format(new Date(State.lastUploadAt));
    int upColor = State.uploadStatus.startsWith("送信 OK") ? GREEN_DIM : State.uploadStatus.isEmpty() ? GREEN_DIM : AMBER;
    glow(c, String.format(Locale.ROOT, "UPLINK  QUEUE %d  LAST-TX %s%s", State.pending, last, keepOn ? "  [SCREEN-LOCK]" : ""),
        24 * s, iy + 80 * s, 30 * s, mono, upColor, 0);
    if (!State.uploadStatus.isEmpty() && !State.uploadStatus.startsWith("送信 OK")) {
      glow(c, State.uploadStatus, 24 * s, iy + 116 * s, 26 * s, jp, AMBER, 0);
    }
  }

  /** [ROT] の左の [TACHO]／[MAP] */
  private void drawTachoButton(Canvas c, float s) {
    float bw = 150 * s, bh = 50 * s;
    tachoBtn.set(rotBtn.left - 12 * s - bw, rotBtn.top, rotBtn.left - 12 * s, rotBtn.top + bh);
    pText.setStyle(Paint.Style.FILL);
    pText.setColor(tachoBtnDown ? Color.argb(200, 0, 120, 30) : Color.argb(190, 0, 0, 0));
    c.drawRect(tachoBtn, pText);
    pLine.setColor(GREEN);
    pLine.setStrokeWidth(2.5f * s);
    c.drawRect(tachoBtn, pLine);
    glow(c, tacho ? "[MAP]" : "[TACHO]", tachoBtn.left + 14 * s, tachoBtn.bottom - 15 * s, 28 * s, mono, GREEN, 8 * s);
  }

  // ---- タコメーター（スズキ ハスラー MR31S、R06A 660cc に合わせた目盛り。値は実車で直す） ----
  static final int TACHO_MAX = 8000, TACHO_RED = 6500, TACHO_SHIFT = 6000;

  /** デモの回転数：アイドル → 1 速でふかす → 変速 → 2 速 → 3 速 → 戻る（16 秒でくり返し） */
  private static float demoRpm(long t) {
    float x = (t % 16000) / 1000f;
    if (x < 2) return 800 + 30 * (float) Math.sin(x * 20);
    if (x < 4.5f) return 800 + (x - 2) / 2.5f * 6000;
    if (x < 5) return 6800 - (x - 4.5f) * 2 * 2800;
    if (x < 8) return 4000 + (x - 5) / 3 * 2800;
    if (x < 8.5f) return 6800 - (x - 8) * 2 * 2300;
    if (x < 12) return 4500 + (x - 8.5f) / 3.5f * 1500;
    return 6000 - (x - 12) / 4 * 5200;
  }

  /** 角度（Android の描き方：3 時が 0 度、時計回り）。0 回転が左下 135 度、最大が右下 45 度（270 度ぶん） */
  private static float tachoAngle(float rpm) { return 135 + 270 * Math.max(0, Math.min(rpm, TACHO_MAX)) / TACHO_MAX; }

  private void drawTacho(Canvas c, float l, float t, float w, float h, float s, long nowMs) {
    boolean linked = State.obdLinked && System.currentTimeMillis() - State.obdAt < 3000;
    float target = linked ? (float) State.obdRpm : demoRpm(nowMs);
    shownRpm += (target - shownRpm) * 0.35f;
    float cx = l + w / 2, cy = t + h / 2 + 10 * s, R = Math.min(w, h) / 2 - 16 * s;
    RectF oval = new RectF(cx - R, cy - R, cx + R, cy + R);
    pLine.setStrokeCap(Paint.Cap.BUTT);

    // 状態（左上）
    label(c, "// OBD LINK " + (linked ? "LOCKED" : State.obdStatus), l + 24 * s, t + 30 * s, s);
    if (!linked) glow(c, State.obdName.isEmpty() ? "DEMO MODE" : "DEMO  " + State.obdName, l + 24 * s, t + 60 * s, 24 * s, mono, AMBER, 0);

    // 外の輪（シフトの合図：赤く点滅）
    boolean shift = shownRpm >= TACHO_SHIFT && (nowMs / 120) % 2 == 0;
    pLine.setColor(shift ? RED : GREEN_DARK);
    pLine.setStrokeWidth((shift ? 6 : 2) * s);
    c.drawArc(oval, 135, 270, false, pLine);
    // 今の回転数までの光る帯
    RectF in = new RectF(oval);
    in.inset(14 * s, 14 * s);
    pLine.setStrokeWidth(10 * s);
    pLine.setColor(Color.argb(70, 0, 255, 65));
    c.drawArc(in, 135, 270, false, pLine);
    pLine.setColor(shownRpm >= TACHO_RED ? RED : GREEN);
    pLine.setShadowLayer(14 * s, 0, 0, shownRpm >= TACHO_RED ? RED : GREEN);
    c.drawArc(in, 135, tachoAngle(shownRpm) - 135, false, pLine);
    pLine.clearShadowLayer();
    // レッドゾーン
    RectF red = new RectF(oval);
    red.inset(30 * s, 30 * s);
    pLine.setColor(RED);
    pLine.setStrokeWidth(8 * s);
    c.drawArc(red, tachoAngle(TACHO_RED), tachoAngle(TACHO_MAX) - tachoAngle(TACHO_RED), false, pLine);
    // 目盛りと数字
    for (int r = 0; r <= TACHO_MAX; r += 250) {
      boolean major = r % 1000 == 0, mid = r % 500 == 0;
      double a = Math.toRadians(tachoAngle(r));
      float r0 = R - (major ? 58 : mid ? 46 : 40) * s, r1 = R - 26 * s;
      pLine.setColor(r >= TACHO_RED ? RED : major ? WHITE : GREEN_DIM);
      pLine.setStrokeWidth((major ? 4 : 2) * s);
      c.drawLine(cx + (float) Math.cos(a) * r0, cy + (float) Math.sin(a) * r0, cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1, pLine);
      if (major) {
        float rt = R - 84 * s;
        String n = String.valueOf(r / 1000);
        pText.setTypeface(mono);
        pText.setTextSize(40 * s);
        float tw = pText.measureText(n);
        glow(c, n, cx + (float) Math.cos(a) * rt - tw / 2, cy + (float) Math.sin(a) * rt + 14 * s, 40 * s, mono, r >= TACHO_RED ? RED : WHITE, 6 * s);
      }
    }
    // 針
    double a = Math.toRadians(tachoAngle(shownRpm));
    float tip = R - 30 * s, tail = 26 * s;
    pLine.setStrokeCap(Paint.Cap.ROUND);
    pLine.setColor(AMBER);
    pLine.setStrokeWidth(7 * s);
    pLine.setShadowLayer(12 * s, 0, 0, AMBER);
    c.drawLine(cx - (float) Math.cos(a) * tail, cy - (float) Math.sin(a) * tail, cx + (float) Math.cos(a) * tip, cy + (float) Math.sin(a) * tip, pLine);
    pLine.clearShadowLayer();
    pLine.setStrokeCap(Paint.Cap.BUTT);
    pText.setStyle(Paint.Style.FILL);
    pText.setColor(Color.rgb(40, 40, 40));
    c.drawCircle(cx, cy, 20 * s, pText);
    pText.setColor(AMBER);
    c.drawCircle(cx, cy, 10 * s, pText);
    // 回転数の数字（針の下）と単位
    String rv = String.valueOf(Math.round(shownRpm / 10) * 10);
    float size = 86 * s, rw = measureFixed(rv, size);
    bodoniFixed(c, rv, cx - rw / 2, cy + R * 0.48f, size, shownRpm >= TACHO_RED ? RED : WHITE, 14 * s, 600);
    String unit = "x1000 r/min";
    pText.setTypeface(mono);
    pText.setTextSize(24 * s);
    glow(c, unit, cx - pText.measureText(unit) / 2, cy + R * 0.48f + 34 * s, 24 * s, mono, GREEN_DIM, 0);
    // 今いる所（メーターの下の開いた所、単位と車速の行の間）。OLD 表示なら古い名前
    drawTachoPlace(c, cx, cy + R * 0.48f + 34 * s, t + h - 12 * s - 34 * s, Math.min(w, 2 * R) - 40 * s, s);
    // 下：車速・水温・電圧（OBD から。デモでは --）
    String sp = linked && State.obdKmh >= 0 ? State.obdKmh + " km/h" : "--- km/h";
    String wt = linked && State.obdCoolant != Integer.MIN_VALUE ? "WATER " + State.obdCoolant + "C" : "WATER --";
    String vt = linked && !Double.isNaN(State.obdVolt) ? String.format(Locale.ROOT, "BATT %.1fV", State.obdVolt) : "BATT --";
    String line = sp + "   " + wt + "   " + vt;
    pText.setTextSize(28 * s);
    glow(c, line, cx - pText.measureText(line) / 2, t + h - 12 * s, 28 * s, mono, CYAN, 0);
  }

  /** タコメーターの下に、今いる所を 2 行で（1 行目 都道府県＋市区町村、2 行目 町字）。top〜bottom の間のまん中に */
  private void drawTachoPlace(Canvas c, float cx, float top, float bottom, float maxW, float s) {
    RGeo.Place pl = State.demoPlace != null ? State.demoPlace : State.place;
    if (!State.fix || pl == null) return;
    String city = pl.cityLine(), town = pl.townLine();
    if (era1970) {
      String[] o = State.oldNames == null ? null : State.oldNames.at(pl.index);
      String[] tw = State.oldTowns == null ? null : State.oldTowns.at(pl.index);
      if (o != null) city = o[0] + o[1];
      if (tw != null && !tw[1].isEmpty()) town = tw[1];
    }
    fit(city, 30 * s, maxW, jp);
    float s1 = fitSize;
    fit(town, 48 * s, maxW, jpBold);
    float s2 = fitSize;
    float total = s1 + 10 * s + s2;
    float y = top + (bottom - top - total) / 2 + s1;
    pText.setTypeface(jp);
    pText.setTextSize(s1);
    glow(c, city, cx - pText.measureText(city) / 2, y, s1, jp, WHITE, 4 * s);
    y += 10 * s + s2;
    pText.setTypeface(jpBold);
    pText.setTextSize(s2);
    glow(c, town, cx - pText.measureText(town) / 2, y, s2, jpBold, WHITE, 10 * s);
  }

  /** 右上の向きのボタン（押すと MainActivity が 90 度ずつ向きを変える）。今の角度を出す */
  private void drawRotateButton(Canvas c, int w, float s) {
    float bw = 190 * s, bh = 50 * s, pad = 14 * s;
    rotBtn.set(w - bw - pad, pad, w - pad, pad + bh);
    pText.setStyle(Paint.Style.FILL);
    pText.setColor(rotBtnDown ? Color.argb(200, 0, 120, 30) : Color.argb(190, 0, 0, 0));
    c.drawRect(rotBtn, pText);
    pLine.setColor(GREEN);
    pLine.setStrokeWidth(2.5f * s);
    c.drawRect(rotBtn, pLine);
    glow(c, "[ROT] " + new String[] {"360", "090", "180", "270"}[rot], rotBtn.left + 18 * s, rotBtn.bottom - 15 * s, 28 * s, mono, GREEN, 8 * s);
  }

  /**
   * 住所の札：地図の日本海の空いた所（MapProj.TEXT_BOX）に重ねる。
   * 1 行目：都道府県＋市区町村（郡を含めて最長 13 文字、例：山梨県南都留郡富士河口湖町）、2 行目から：町字（長ければ 2 行に分ける）
   */
  private void drawPlace(Canvas c, float mx, float my, float scale, float s, long nowMs) {
    float l = mx + MapProj.TEXT_BOX[0] * scale, t = my + MapProj.TEXT_BOX[1] * scale;
    float r = mx + MapProj.TEXT_BOX[2] * scale, b = my + MapProj.TEXT_BOX[3] * scale;
    pText.setStyle(Paint.Style.FILL);
    pText.setColor(Color.argb(225, 0, 0, 0));   // 後ろの地図の地名が透けて読みにくくならない濃さ
    c.drawRect(l, t, r, b, pText);
    pLine.setColor(GREEN_DARK);
    pLine.setStrokeWidth(2 * s);
    c.drawRect(l, t, r, b, pLine);
    // 角の飾り
    pLine.setColor(GREEN);
    float k = 18 * s;
    c.drawLine(l, t, l + k, t, pLine); c.drawLine(l, t, l, t + k, pLine);
    c.drawLine(r, b, r - k, b, pLine); c.drawLine(r, b, r, b - k, pLine);

    float pad = 14 * s, x = l + pad, maxW = r - l - 2 * pad;
    float y = t + 34 * s;
    label(c, "// GEO.NODE " + (State.fix ? "LOCKED" : "SEARCHING"), x, y, s);
    // 右上の切り替えボタン（今 ↔ 1970 年）
    float bw = 92 * s, bh = 40 * s;
    eraBtn.set(r - bw - 8 * s, t + 8 * s, r - 8 * s, t + 8 * s + bh);
    pText.setStyle(Paint.Style.FILL);
    pText.setColor(eraBtnDown ? Color.argb(200, 0, 120, 30) : Color.argb(200, 0, 0, 0));
    c.drawRect(eraBtn, pText);
    pLine.setColor(GREEN);
    pLine.setStrokeWidth(2 * s);
    c.drawRect(eraBtn, pLine);
    glow(c, era1970 ? "[OLD]" : "[NOW]", eraBtn.left + 13 * s, eraBtn.bottom - 12 * s, 22 * s, mono, GREEN, 6 * s);
    RGeo.Place pl = State.demoPlace != null ? State.demoPlace : State.place;
    if (State.fix && pl != null && era1970) {
      drawPlace1970(c, pl, x, y, maxW, s);
      return;
    }
    if (!State.fix || pl == null) {
      boolean blink = (nowMs / 500) % 2 == 0;
      glow(c, State.fix ? "SCANNING..." : "ACQUIRING" + (blink ? "_" : " "), x, y + 70 * s, 44 * s, mono, GREEN, 10 * s);
      glow(c, State.fix ? "" : "SATELLITES", x, y + 124 * s, 44 * s, mono, GREEN, 10 * s);
      return;
    }
    fit(pl.cityLine(), 50 * s, maxW, jp);
    float citySize = fitSize;
    y += 18 * s + citySize;
    glow(c, pl.cityLine(), x, y, citySize, jp, WHITE, 6 * s);
    drawTown(c, pl.townLine(), x, y, maxW, s);
  }

  /** 町字（大）：1 行で 56 以上の大きさにできれば 1 行、無理なら半分で改行して 2 行。戻り値は書き終えた所の y */
  private float drawTown(Canvas c, String town, float x, float y, float maxW, float s) {
    fit(town, 96 * s, maxW, jpBold);
    if (fitSize >= 56 * s || town.length() < 4) {
      y += 22 * s + fitSize;
      glow(c, town, x, y, fitSize, jpBold, WHITE, 12 * s);
      return y;
    }
    int cut = (town.length() + 1) / 2;
    String a = town.substring(0, cut), bb = town.substring(cut);
    fit(a.length() >= bb.length() ? a : bb, 72 * s, maxW, jpBold);
    float sz = fitSize;
    y += 20 * s + sz;
    glow(c, a, x, y, sz, jpBold, WHITE, 10 * s);
    y += 8 * s + sz;
    glow(c, bb, x, y, sz, jpBold, WHITE, 10 * s);
    return y;
  }

  /**
   * 札の OLD 表示：1 行目 都道府県＋郡＋1970 年の市町村（例：茨城県筑波郡伊奈村）、2 行目 2000 年の町丁・字（大。例：大字中島。
   * 分からなければ今の町字）、3 行目 今の市区町村（小）。続けて読むと「茨城県筑波郡伊奈村大字中島」
   */
  private void drawPlace1970(Canvas c, RGeo.Place pl, float x, float y, float maxW, float s) {
    String[] o = State.oldNames == null ? null : State.oldNames.at(pl.index);
    String[] t = State.oldTowns == null ? null : State.oldTowns.at(pl.index);
    String town = t != null && !t[1].isEmpty() ? t[1] : pl.townLine();
    if (o == null) {
      y += 18 * s + 40 * s;
      glow(c, "1970年には無い土地", x, y, 40 * s, jp, WHITE, 6 * s);
      y += 16 * s + 26 * s;
      glow(c, "現在 " + pl.cityLine() + pl.townLine(), x, y, 26 * s, jp, GREEN_DIM, 0);
      return;
    }
    String muni = o[0] + o[1];
    fit(muni, 50 * s, maxW, jp);
    float sz1 = fitSize;
    y += 18 * s + sz1;
    glow(c, muni, x, y, sz1, jp, WHITE, 6 * s);
    y = drawTown(c, town, x, y, maxW, s);
    String now = "現在 " + pl.cityLine() + (town.equals(pl.townLine()) ? "" : pl.townLine());
    fit(now, 26 * s, maxW, jp);
    y += 16 * s + fitSize;
    glow(c, now, x, y, fitSize, jp, GREEN_DIM, 0);
  }

  /** 流れる文字 */
  private void drawRain(Canvas c, int w, int h, float s, float dt) {
    if (rainY == null) return;
    float cell = 22 * s;
    pRain.setTypeface(mono);
    pRain.setTextSize(20 * s);
    for (int i = 0; i < rainY.length; i++) {
      rainY[i] += rainSpeed[i] * dt;
      if (rainY[i] - 24 * cell > h) {
        rainY[i] = -rnd.nextFloat() * h * 0.5f;
        rainSpeed[i] = (60 + rnd.nextFloat() * 160) * s;
      }
      if (rnd.nextInt(8) == 0) rainCol[i][rnd.nextInt(24)] = RAIN_CHARS.charAt(rnd.nextInt(RAIN_CHARS.length()));
      for (int j = 0; j < 24; j++) {
        float cy = rainY[i] - j * cell;
        if (cy < 0 || cy > h + cell) continue;
        int a = j == 0 ? 110 : Math.max(0, 70 - j * 3);
        pRain.setColor(j == 0 ? Color.argb(a, 180, 255, 190) : Color.argb(a, 0, 255, 65));
        c.drawText(String.valueOf(rainCol[i][j]), i * cell + 4 * s, cy, pRain);
      }
    }
  }

  /** 拡大した地図（都道府県・周辺）。町字の点から描き直す */
  private void drawZoom(Canvas c, float mx, float my, float mw, float mh, float s, long nowMs) {
    if (zoom == null) zoom = new ZoomMap(State.rgeo, State.oldNames, State.oldTowns);
    float charW = (zoomMode == ZoomMap.PREF ? 24 : 22) * s;
    Bounds.Lines bl = State.bounds == null ? null : era1970 ? State.bounds.old1970 : State.bounds.now;
    // まん中：今いる所から、指で動かした分（LOCAL だけ）ずらす
    double cLat = State.lat, cLon = State.lon;
    if (zoomMode == ZoomMap.LOCAL) {
      cLat += panN / 110574.0;
      cLon += panE / (111320.0 * Math.cos(Math.toRadians(State.lat)));
    }
    zoom.update(zoomMode, cLat, cLon, mw, mh, charW, charW * 1.35f, bl, era1970);
    // 計算し直すのは 8 ドット以上ずれたときだけなので、その間の小さなずれは描く位置を動かして埋める（なぞるときに滑らかに）
    float[] rc = zoom.toScreen(cLat, cLon);
    float ox = zoom.cx - rc[0], oy = zoom.cy - rc[1];
    c.save();
    c.clipRect(mx, my, mx + mw, my + mh);
    c.translate(mx + ox, my + oy);
    // 枠と方眼
    pLine.setColor(GREEN_DARK);
    pLine.setStrokeWidth(1 * s);
    for (float gx = 0; gx < mw; gx += 60 * s) c.drawLine(gx, 0, gx, mh, pLine);
    for (float gy = 0; gy < mh; gy += 60 * s) c.drawLine(0, gy, mw, gy, pLine);
    // 境界線（今か 1970 年）：海岸・湖岸、市町村界、郡界、都道府県界の順に重ねる
    int[] lineAlpha = {235, 170, 110, 150};
    float[] lineW = {3.2f, 2.2f, 1.3f, 1.6f};
    for (int g : new int[] {3, 2, 1, 0}) {
      pLine.setColor(g == 3 ? Color.argb(lineAlpha[g], 0, 170, 45) : Color.argb(lineAlpha[g], 0, 255, 65));
      pLine.setStrokeWidth(lineW[g] * s);
      c.drawLines(zoom.lines[g], pLine);
    }
    // 点
    pLine.setStrokeCap(Paint.Cap.ROUND);
    pLine.setColor(Color.argb(150, 0, 110, 30));
    pLine.setStrokeWidth(3 * s);
    c.drawPoints(zoom.dimDots, pLine);
    pLine.setColor(Color.argb(220, 0, 255, 65));
    pLine.setStrokeWidth((zoomMode == ZoomMap.LOCAL ? 7 : 3.5f) * s);
    c.drawPoints(zoom.dots, pLine);
    pLine.setStrokeCap(Paint.Cap.BUTT);
    // 周辺：1・3・5km の輪
    float[] pin = zoom.toScreen(State.lat, State.lon);
    if (zoomMode == ZoomMap.LOCAL) {
      pLine.setColor(Color.argb(110, 0, 255, 65));
      pLine.setStrokeWidth(1.5f * s);
      for (int km : new int[] {1, 3, 5}) {
        float rr = (float) (km * 1000 / zoom.mpp);
        c.drawCircle(pin[0], pin[1], rr, pLine);
        glow(c, km + "KM", pin[0] + rr * 0.71f + 4 * s, pin[1] - rr * 0.71f, 20 * s, mono, GREEN_DIM, 0);
      }
    }
    // 名前（点の右）
    for (ZoomMap.Label lb : zoom.labels) {
      // 名前は白（地図の緑と分ける）。黒い縁取りで点の上でも読めるように
      glow(c, lb.text, lb.x + 6 * s, lb.y + charW * 0.4f, charW, jp, WHITE, 0, Color.BLACK);
    }
    drawPinAt(c, pin[0], pin[1], 0, 0, mw, mh, s, nowMs);
    c.restore();
    pLine.setColor(GREEN_DARK);
    pLine.setStrokeWidth(2 * s);
    c.drawRect(mx, my, mx + mw, my + mh, pLine);
  }

  /** 左下の [CENTER]：LOCAL で地図を動かしたときだけ出す。押すと今いる所をまん中に戻す */
  private void drawCenterButton(Canvas c, float mx, float my, float mw, float mh, float s) {
    if (!isPanned()) {
      centerBtn.setEmpty();
      return;
    }
    float bw = 200 * s, bh = 58 * s, pad = 8 * s;
    centerBtn.set(mx + pad, my + mh - bh - pad, mx + pad + bw, my + mh - pad);
    pText.setStyle(Paint.Style.FILL);
    pText.setColor(centerBtnDown ? Color.argb(200, 0, 120, 30) : Color.argb(190, 0, 0, 0));
    c.drawRect(centerBtn, pText);
    pLine.setColor(AMBER);
    pLine.setStrokeWidth(2.5f * s);
    c.drawRect(centerBtn, pLine);
    glow(c, "[CENTER]", centerBtn.left + 16 * s, centerBtn.bottom - 18 * s, 30 * s, mono, AMBER, 8 * s);
  }

  /** 右下の拡大ボタン */
  private void drawZoomButton(Canvas c, float mx, float my, float mw, float mh, float s) {
    float bw = 250 * s, bh = 58 * s, pad = 8 * s;
    zoomBtn.set(mx + mw - bw - pad, my + mh - bh - pad, mx + mw - pad, my + mh - pad);
    pText.setStyle(Paint.Style.FILL);
    pText.setColor(zoomBtnDown ? Color.argb(200, 0, 120, 30) : Color.argb(190, 0, 0, 0));
    c.drawRect(zoomBtn, pText);
    pLine.setColor(GREEN);
    pLine.setStrokeWidth(2.5f * s);
    c.drawRect(zoomBtn, pLine);
    glow(c, "[ZOOM] " + ZoomMap.NAMES[zoomMode], zoomBtn.left + 16 * s, zoomBtn.bottom - 18 * s, 30 * s, mono, GREEN, 8 * s);
  }

  /** 今いる所のピン（全国の地図） */
  private void drawPin(Canvas c, float mx, float my, float scale, float s, long nowMs) {
    float[] p = MapProj.project(State.lat, State.lon);
    if (p == null) return;
    drawPinAt(c, mx + p[0] * scale, my + p[1] * scale, mx, my, mx + MapProj.W * scale, my + MapProj.H * scale, s, nowMs);
  }

  /** ピン：十字の線と、脈打つ丸。十字の線は l, t, r, b の範囲に引く */
  private void drawPinAt(Canvas c, float px, float py, float l, float t, float r, float b, float s, long nowMs) {
    pLine.setColor(Color.argb(90, 0, 255, 65));
    pLine.setStrokeWidth(1.5f * s);
    c.drawLine(l, py, r, py, pLine);
    c.drawLine(px, t, px, b, pLine);
    float ph = (nowMs % 1500) / 1500f;   // 脈打つ輪の進み（0〜1）
    pLine.setStrokeWidth(3 * s);
    pLine.setColor(Color.argb((int) (255 * (1 - ph)), 255, 60, 60));
    c.drawCircle(px, py, (8 + 34 * ph) * s, pLine);
    pLine.setColor(RED);
    pLine.setStrokeWidth(2.5f * s);
    c.drawCircle(px, py, 9 * s, pLine);
    pText.setShadowLayer(12 * s, 0, 0, RED);
    pText.setStyle(Paint.Style.FILL);
    pText.setColor(RED);
    c.drawCircle(px, py, 4 * s, pText);
    pText.clearShadowLayer();
    // ピンの横に小さな札
    float tx = px + 16 * s, ty = py - 14 * s;
    if (tx > r - 150 * s) tx = px - 150 * s;
    // 文字は赤のまま、地だけ赤の補色（シアン）にして目立たせる
    pText.setTypeface(mono);
    pText.setTextSize(20 * s);
    float tw = pText.measureText("YOU ARE HERE");
    pText.setStyle(Paint.Style.FILL);
    pText.clearShadowLayer();
    pText.setColor(CYAN);
    c.drawRect(tx - 5 * s, ty - 18 * s, tx + tw + 5 * s, ty + 6 * s, pText);
    glow(c, "YOU ARE HERE", tx, ty, 20 * s, mono, RED, 0);
  }

  private void label(Canvas c, String s, float x, float y, float scale) {
    glow(c, s, x, y, 25 * scale, mono, GREEN_DIM, 0);
  }

  /** 大きな数字の書体（瀞ノグリッチ黒体 H3）で書く。wght は使わない（以前の Bodoni の名残。書体を戻すとき用） */
  private float bodoni(Canvas c, String str, float x, float y, float size, int color, float glow, int wght) {
    setBodoni(size, wght);
    pText.setColor(color);
    if (glow > 0) pText.setShadowLayer(glow, 0, 0, color);
    else pText.clearShadowLayer();
    c.drawText(str, x, y, pText);
    pText.clearShadowLayer();
    float w = pText.measureText(str);
    return w;
  }

  private void setBodoni(float size, int wght) {
    pText.setStyle(Paint.Style.FILL);
    pText.setTypeface(bodoni);
    pText.setTextSize(size);

  }

  /**
   * 数字を同じ幅の枠に 1 文字ずつ置く（瀞ノグリッチ黒体の数字はもともと同じ幅だが、書体を替えても左右にずれないように）。
   * 数字は一番広い「0」の幅、それ以外（: . + - /）はその字の幅。戻り値は書いた幅
   */
  private float bodoniFixed(Canvas c, String str, float x, float y, float size, int color, float glow, int wght) {
    setBodoni(size, wght);
    pText.setColor(color);
    if (glow > 0) pText.setShadowLayer(glow, 0, 0, color);
    else pText.clearShadowLayer();
    float digitW = pText.measureText("0");
    float cx = x;
    for (int i = 0; i < str.length(); i++) {
      String ch = str.substring(i, i + 1);
      float cw = pText.measureText(ch);
      float slot = Character.isDigit(str.charAt(i)) ? digitW : cw + size * 0.04f;
      c.drawText(ch, cx + (slot - cw) / 2, y, pText);
      cx += slot;
    }
    pText.clearShadowLayer();
    return cx - x;
  }

  /** 縁取りつきの文字（縁の色 outline で、縁の太さは文字の大きさの 1/6） */
  private void glow(Canvas c, String s, float x, float y, float size, Typeface tf, int color, float glow, int outline) {
    pText.setTypeface(tf);
    pText.setTextSize(size);
    pText.clearShadowLayer();
    pText.setStyle(Paint.Style.STROKE);
    pText.setStrokeWidth(size / 6);
    pText.setColor(outline);
    c.drawText(s, x, y, pText);
    pText.setStyle(Paint.Style.FILL);
    glow(c, s, x, y, size, tf, color, glow);
  }

  /** 光る文字 */
  private void glow(Canvas c, String s, float x, float y, float size, Typeface tf, int color, float glow) {
    pText.setStyle(Paint.Style.FILL);
    pText.setTypeface(tf);
    pText.setTextSize(size);
    pText.setColor(color);
    if (glow > 0) pText.setShadowLayer(glow, 0, 0, color);
    else pText.clearShadowLayer();
    c.drawText(s, x, y, pText);
    pText.clearShadowLayer();
  }

  private float fitSize;

  /** 幅に収まるよう文字の大きさを縮める（fitSize に入れる） */
  private String fit(String s, float size, float maxW, Typeface tf) {
    pText.setTypeface(tf);
    fitSize = size;
    pText.setTextSize(size);
    while (fitSize > size * 0.5f && pText.measureText(s) > maxW) {
      fitSize -= 2;
      pText.setTextSize(fitSize);
    }
    return s;
  }
}
