package jp.hasami.geolog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * RGEO 形式（~/Documents/reverse-geocoding の README）から、緯度経度に一番近い町字を探す。
 * c/rgeo.h と同じ探し方。Android に依存しないので、パソコンの Java でもテストできる（test/RunTests.java）。
 */
public final class RGeo {
  /** 見つかった町字 */
  public static final class Place {
    public String pref, city, town, chome;
    public int lgCode;
    public int index;   // 点の番号（rgeo_all.bin の中の順。1970 年の名前を引くのに使う）
    public double lat, lon, distM;

    /** 例：茨城県つくばみらい市 / 谷井田 */
    public String cityLine() { return pref + city; }
    public String townLine() { return town + chome; }
  }

  private final ByteBuffer b;
  public final int nPoints, nCities, rows, cols, nChome, dataDate;
  private final int lat0, lon0, cell, offCells, offPoints, offCities, offChome, offStrings, sizeStrings;

  public RGeo(ByteBuffer buf) {
    b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    if (b.get(0) != 'R' || b.get(1) != 'G' || b.get(2) != 'E' || b.get(3) != 'O' || u16(4) != 1 || u16(6) != 68) {
      throw new IllegalArgumentException("RGEO 形式ではない");
    }
    nPoints = b.getInt(8);
    nCities = b.getInt(12);
    lat0 = b.getInt(16);
    lon0 = b.getInt(20);
    cell = b.getInt(24);
    rows = u16(28);
    cols = u16(30);
    offCells = b.getInt(32);
    offPoints = b.getInt(36);
    offCities = b.getInt(40);
    offChome = b.getInt(44);
    offStrings = b.getInt(48);
    sizeStrings = b.getInt(52);
    nChome = u16(56);
    dataDate = b.getInt(60);
  }

  private int u16(int off) { return b.getShort(off) & 0xFFFF; }

  private String str(int off) {
    if (off < 0 || off >= sizeStrings) return "";
    int p = offStrings + off, e = p;
    while (b.get(e) != 0) e++;
    byte[] a = new byte[e - p];
    for (int i = 0; i < a.length; i++) a[i] = b.get(p + i);
    return new String(a, StandardCharsets.UTF_8);
  }

  // ---- 地図の拡大表示用：全部の点を配列に展開する（decodeAll を 1 回呼ぶ） ----
  public float[] allLat, allLon;   // 点ごとの代表点
  public int[] allCity;            // 点ごとの市区町村の番号

  /** 全部の点の緯度経度と市区町村を配列にする（約 21 万点、数十ミリ秒） */
  public void decodeAll() {
    float[] la = new float[nPoints], lo = new float[nPoints];
    int[] ci = new int[nPoints];
    double cellDeg = cell / 1e6;
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        int k = r * cols + c;
        int s = b.getInt(offCells + k * 4), e = b.getInt(offCells + k * 4 + 4);
        double clat = (lat0 + (double) r * cell) / 1e6, clon = (lon0 + (double) c * cell) / 1e6;
        for (int i = s; i < e; i++) {
          int p = offPoints + i * 10;
          la[i] = (float) (clat + u16(p) * cellDeg / 65535.0);
          lo[i] = (float) (clon + u16(p + 2) * cellDeg / 65535.0);
          ci[i] = u16(p + 4);
        }
      }
    }
    allLat = la;
    allLon = lo;
    allCity = ci;
  }

  /** i 番目の点の町字（大字・町＋小字＋丁目） */
  public String townLineAt(int i) {
    int p = offPoints + i * 10;
    int name = (b.get(p + 6) & 0xFF) | (b.get(p + 7) & 0xFF) << 8 | (b.get(p + 8) & 0xFF) << 16;
    int chome = b.get(p + 9) & 0xFF;
    return str(name) + (chome > 0 && chome < nChome ? str(b.getInt(offChome + chome * 4)) : "");
  }

  /** 市区町村の名前（郡・市・区。都道府県は含まない） */
  public String cityNameAt(int city) { return str(b.getInt(offCities + city * 12 + 8)); }

  /** 市区町村がどの都道府県か（都道府県名の文字列の位置。同じ都道府県なら同じ値） */
  public int prefKeyOf(int city) { return b.getInt(offCities + city * 12 + 4); }

  /** 2 点の距離（メートル、正距円筒近似） */
  public static double dist(double lat1, double lon1, double lat2, double lon2) {
    double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
    double y = Math.toRadians(lat2 - lat1);
    return 6371000.0 * Math.sqrt(x * x + y * y);
  }

  /** 一番近い町字。データの範囲に点が 1 つも無ければ null */
  public Place nearest(double lat, double lon) {
    double cellDeg = cell / 1e6;
    int r0 = (int) Math.floor((lat * 1e6 - lat0) / cell);
    int c0 = (int) Math.floor((lon * 1e6 - lon0) / cell);
    double best = Double.POSITIVE_INFINITY;
    int bestOff = -1, bestR = 0, bestC = 0;
    double cosMax = Math.cos(Math.toRadians(Math.abs(lat) + cellDeg));
    double ringM = cellDeg * 111195.0 * Math.min(1.0, cosMax);
    int maxRing = rows + cols + Math.abs(r0) + Math.abs(c0);
    for (int k = 0; k <= maxRing; k++) {
      if (k > 0 && best <= (k - 1) * ringM) break;   // k 周目より外は、少なくとも (k-1) 区画ぶん離れている
      for (int dr = -k; dr <= k; dr++) {
        int step = (dr == -k || dr == k) ? 1 : 2 * k;
        for (int dc = -k; dc <= k; dc += step) {
          int r = r0 + dr, c = c0 + dc;
          if (r < 0 || c < 0 || r >= rows || c >= cols) continue;
          int ci = r * cols + c;
          int s = b.getInt(offCells + ci * 4), e = b.getInt(offCells + ci * 4 + 4);
          double clat = (lat0 + (double) r * cell) / 1e6, clon = (lon0 + (double) c * cell) / 1e6;
          for (int i = s; i < e; i++) {
            int p = offPoints + i * 10;
            double plat = clat + u16(p) * cellDeg / 65535.0;
            double plon = clon + u16(p + 2) * cellDeg / 65535.0;
            double d = dist(lat, lon, plat, plon);
            if (d < best) { best = d; bestOff = p; bestR = r; bestC = c; }
          }
        }
      }
    }
    if (bestOff < 0) return null;
    Place pl = new Place();
    pl.lat = (lat0 + (double) bestR * cell) / 1e6 + u16(bestOff) * cellDeg / 65535.0;
    pl.lon = (lon0 + (double) bestC * cell) / 1e6 + u16(bestOff + 2) * cellDeg / 65535.0;
    pl.distM = best;
    pl.index = (bestOff - offPoints) / 10;
    int city = u16(bestOff + 4);
    int name = (b.get(bestOff + 6) & 0xFF) | (b.get(bestOff + 7) & 0xFF) << 8 | (b.get(bestOff + 8) & 0xFF) << 16;
    int chome = b.get(bestOff + 9) & 0xFF;
    int ce = offCities + city * 12;
    pl.lgCode = b.getInt(ce);
    pl.pref = str(b.getInt(ce + 4));
    pl.city = str(b.getInt(ce + 8));
    pl.town = str(name);
    pl.chome = chome > 0 && chome < nChome ? str(b.getInt(offChome + chome * 4)) : "";
    return pl;
  }
}
