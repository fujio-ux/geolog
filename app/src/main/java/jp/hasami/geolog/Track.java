package jp.hasami.geolog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * まだサーバーへ送っていない記録（端末の中の queue.csv）。1 行 1 点：
 *   時刻ミリ秒,緯度,経度,標高,km/h,向き,誤差,衛星の数,町字（不明な値は空欄。町字のカンマ・改行は空白にする）
 * サーバーに届いたら、その時刻までの行を消す。Android に依存しない（パソコンでもテストできる）。
 */
public final class Track {
  private final File file;

  public Track(File file) { this.file = file; }

  /** 1 点 */
  public static final class Point {
    public long ts;
    public double lat, lon;
    public Double alt, kmh, bearing, acc;
    public Integer sats;
    public String place;   // 一番近い町字（例：茨城県つくばみらい市谷井田）

    public String csv() {
      return ts + "," + fmt(lat, 7) + "," + fmt(lon, 7) + "," + fmt(alt, 1) + "," + fmt(kmh, 1) + ","
          + fmt(bearing, 0) + "," + fmt(acc, 1) + "," + (sats == null ? "" : sats) + ","
          + (place == null ? "" : place.replaceAll("[,\\r\\n]", " "));
    }

    static Point parse(String line) {
      String[] c = line.split(",", -1);
      if (c.length < 8) return null;
      try {
        Point p = new Point();
        p.ts = Long.parseLong(c[0]);
        p.lat = Double.parseDouble(c[1]);
        p.lon = Double.parseDouble(c[2]);
        p.alt = dbl(c[3]);
        p.kmh = dbl(c[4]);
        p.bearing = dbl(c[5]);
        p.acc = dbl(c[6]);
        p.sats = c[7].isEmpty() ? null : Integer.valueOf(c[7]);
        p.place = c.length > 8 && !c[8].isEmpty() ? c[8] : null;   // 0.1.0 の行には無い
        return p;
      } catch (NumberFormatException e) {
        return null;
      }
    }

    /** サーバーに送る JSON の 1 要素 [時刻,緯度,経度,標高,km/h,向き,誤差,衛星,町字] */
    String json() {
      return "[" + ts + "," + fmt(lat, 7) + "," + fmt(lon, 7) + "," + js(alt, 1) + "," + js(kmh, 1) + ","
          + js(bearing, 0) + "," + js(acc, 1) + "," + (sats == null ? "null" : sats) + "," + jsonString(place) + "]";
    }
  }

  /** JSON の文字列（null はそのまま null） */
  static String jsonString(String s) {
    if (s == null) return "null";
    StringBuilder sb = new StringBuilder("\"");
    for (char ch : s.toCharArray()) {
      if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
      else if (ch < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
      else sb.append(ch);
    }
    return sb.append('"').toString();
  }

  private static Double dbl(String s) { return s.isEmpty() ? null : Double.valueOf(s); }

  private static String fmt(Double v, int digits) {
    return v == null || v.isNaN() ? "" : String.format(Locale.ROOT, "%." + digits + "f", v);
  }

  private static String js(Double v, int digits) {
    String s = fmt(v, digits);
    return s.isEmpty() ? "null" : s;
  }

  public synchronized void append(Point p) throws IOException {
    try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
      w.write(p.csv() + "\n");
    }
  }

  /** 送っていない点を古い順に最大 max 個 */
  public synchronized List<Point> pending(int max) throws IOException {
    List<Point> out = new ArrayList<>();
    if (!file.exists()) return out;
    try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null && out.size() < max) {
        Point p = Point.parse(line);
        if (p != null) out.add(p);
      }
    }
    return out;
  }

  /** 送っていない点の数 */
  public synchronized int count() throws IOException {
    if (!file.exists()) return 0;
    int n = 0;
    try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
      while (r.readLine() != null) n++;
    }
    return n;
  }

  /** サーバーが「last まで保存した」と返したら、その時刻までの行を消す */
  public synchronized void dropUpTo(long last) throws IOException {
    if (!file.exists()) return;
    File tmp = new File(file.getPath() + ".tmp");
    try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
         Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
      String line;
      while ((line = r.readLine()) != null) {
        Point p = Point.parse(line);
        if (p != null && p.ts > last) w.write(line + "\n");
      }
    }
    if (!tmp.renameTo(file)) throw new IOException("置き換えられない");
  }

  /** アップロード用の JSON 本文 */
  public static String uploadJson(String device, List<Point> pts) {
    StringBuilder sb = new StringBuilder("{\"device\":\"").append(device).append("\",\"points\":[");
    for (int i = 0; i < pts.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(pts.get(i).json());
    }
    return sb.append("]}").toString();
  }

  /** サーバーの返事 {"ok":true,...,"last":123} から last を取り出す（取れなければ -1） */
  public static long parseLast(String body) {
    if (body == null || !body.contains("\"ok\":true")) return -1;
    int i = body.indexOf("\"last\":");
    if (i < 0) return -1;
    int s = i + 7, e = s;
    while (e < body.length() && Character.isDigit(body.charAt(e))) e++;
    try {
      return Long.parseLong(body.substring(s, e));
    } catch (NumberFormatException ex) {
      return -1;
    }
  }
}
