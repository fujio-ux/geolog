package jp.hasami.geolog;

/** LogService が更新し、画面（MainActivity）が読む、今の状態。どれも main スレッドから読み書きする */
final class State {
  private State() {}

  static boolean fix;              // GPS で測位できているか
  static long fixAt;               // 最後に位置が来た時刻（端末の時計、ミリ秒）
  static double lat, lon;
  static double kmh = Double.NaN, bearing = Double.NaN, acc = Double.NaN;
  static double altMsl = Double.NaN;      // 海面からの標高（NMEA の GGA）
  static double altEllipsoid = Double.NaN;
  static long gpsMinusSystem;      // GPS の時刻 − 端末の時計（ミリ秒）。時刻の表示に使う
  static boolean gpsTimeKnown;
  static int satsUsed, satsVisible, qzssUsed, qzssVisible;
  static RGeo.Place place;
  static RGeo rgeo;                // 町字のデータ（地図の拡大表示でも使う。LogService が開いて decodeAll する）
  static OldNames oldNames;        // 1970 年の市町村名（old1970.bin）
  static OldNames oldTowns;        // 2000 年の町丁・字の名前（old2000.bin）
  static Bounds bounds;            // 拡大地図の境界線（bounds.bin。今と 1970 年）
  static RGeo.Place demoPlace;     // 表示の確認用（adb で am start に --es demo_city/demo_town を付けたときだけ）

  // 記録と送信
  static int pending;              // まだ送っていない点の数
  static long lastUploadAt;        // 最後に送れた時刻（端末の時計）
  static String uploadStatus = "";
  static boolean hasToken;
  static String device = "";
}
