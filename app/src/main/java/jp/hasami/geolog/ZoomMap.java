package jp.hasami.geolog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 地図の拡大表示の計算（Android に依存しない。描くのは HudView）。どちらも今いる所を枠のまん中に置き、決まった広さで描く。
 *   PREF ：短い辺で 120km（都道府県くらい）。今いる都道府県の点は明るく、ほかは暗く。市区町村（1970 年表示なら当時の市町村）の名前
 *   LOCAL：短い辺で 10km。町字の点と名前（重なる名前は省く。まん中に近いものから）
 * 点は町字の代表点（RGeo.decodeAll の配列）、線は境界線（Bounds。今か 1970 年）。
 */
final class ZoomMap {
  static final int JAPAN = 0, PREF = 1, LOCAL = 2;
  static final String[] NAMES = {"JAPAN", "PREF", "LOCAL"};
  static final double PREF_SPAN_M = 120000;    // PREF の短い辺
  static final double LOCAL_SPAN_M = 10000;    // LOCAL の短い辺
  private static final double M_LAT = 110574, M_LON = 111320;
  /** 境界線の組（描くときの太さ・色を分ける）：0 都道府県界、1 郡・支庁界、2 市町村・区界、3 海岸・湖岸 */
  static final int LINE_GROUPS = 4;

  static final class Label {
    final String text;
    final float x, y;
    Label(String text, float x, float y) { this.text = text; this.x = x; this.y = y; }
  }

  private final RGeo g;
  private final OldNames old;     // 1970 年の市町村（null なら使わない）
  private final OldNames oldTown; // 2000 年の町丁・字（null なら今の町字）
  // 計算の結果（枠の左上が 0,0）
  float[] dots = new float[0], dimDots = new float[0];
  final float[][] lines = new float[LINE_GROUPS][];   // drawLines の形（x0, y0, x1, y1, ...）
  final List<Label> labels = new ArrayList<>();
  double cLat, cLon, mpp;        // まん中と、1 ドットあたりのメートル
  float cx, cy;                  // まん中を置く位置
  // 何で計算したか（変わったら計算し直す）
  private int kMode = -1;
  private double kLat, kLon;
  private float kW, kH;
  private Bounds.Lines kLines;
  private boolean kOld;

  ZoomMap(RGeo g, OldNames old, OldNames oldTown) {
    this.g = g;
    this.old = old;
    this.oldTown = oldTown;
    for (int i = 0; i < LINE_GROUPS; i++) lines[i] = new float[0];
  }

  float[] toScreen(double lat, double lon) {
    return new float[] {(float) (cx + (lon - cLon) * Math.cos(Math.toRadians(cLat)) * M_LON / mpp),
                        (float) (cy - (lat - cLat) * M_LAT / mpp)};
  }

  /**
   * 必要なら計算し直す。w, h は地図の枠の大きさ、charW は名前の 1 文字の幅、lineH は 1 行の高さ。
   * bl は描く境界線（null なら線なし）、old1970 は OLD 表示（市町村は 1970 年、町字は 2000 年の名前）にするか
   */
  void update(int mode, double lat, double lon, float w, float h, float charW, float lineH, Bounds.Lines bl, boolean old1970) {
    if (g == null || g.allLat == null) return;
    double span = mode == PREF ? PREF_SPAN_M : LOCAL_SPAN_M;
    double newMpp = span / Math.min(w, h);
    boolean same = mode == kMode && w == kW && h == kH && bl == kLines && old1970 == kOld
        && RGeo.dist(lat, lon, kLat, kLon) < newMpp * 8;   // 8 ドット以上動いたら描き直す
    if (same) return;
    kMode = mode; kLat = lat; kLon = lon; kW = w; kH = h; kLines = bl; kOld = old1970;
    cLat = lat;
    cLon = lon;
    cx = w / 2;
    cy = h / 2;
    mpp = newMpp;
    int pref = mode == PREF ? prefAt(lat, lon) : -1;
    collectDots(pref, w, h);
    collectLines(bl, w, h);
    labels.clear();
    if (mode == PREF) cityLabels(w, h, charW, lineH, old1970);
    else townLabels(w, h, charW, lineH, old1970);
  }

  /** 今いる所に一番近い点の都道府県 */
  private int prefAt(double lat, double lon) {
    int best = -1;
    double bd = Double.MAX_VALUE, k = Math.cos(Math.toRadians(lat));
    for (int i = 0; i < g.nPoints; i++) {
      double dy = g.allLat[i] - lat, dx = (g.allLon[i] - lon) * k, d = dx * dx + dy * dy;
      if (d < bd) { bd = d; best = i; }
    }
    return best < 0 ? -1 : g.prefKeyOf(g.allCity[best]);
  }

  /** 枠に入る点の番号 */
  private int[] visible(float w, float h) {
    int[] out = new int[1024];
    int n = 0;
    double k = Math.cos(Math.toRadians(cLat)) * M_LON / mpp, kl = M_LAT / mpp;
    for (int i = 0; i < g.nPoints; i++) {
      double x = cx + (g.allLon[i] - cLon) * k, y = cy - (g.allLat[i] - cLat) * kl;
      if (x < -2 || y < -2 || x > w + 2 || y > h + 2) continue;
      if (n == out.length) out = Arrays.copyOf(out, n * 2);
      out[n++] = i;
    }
    return Arrays.copyOf(out, n);
  }

  /** 枠に入る点を、明るい点（pref が同じ。pref < 0 なら全部）と暗い点に分けて集める */
  private void collectDots(int pref, float w, float h) {
    int[] vis = visible(w, h);
    float[] bright = new float[vis.length * 2], dim = new float[vis.length * 2];
    int nb = 0, nd = 0;
    for (int i : vis) {
      float[] p = toScreen(g.allLat[i], g.allLon[i]);
      if (pref < 0 || g.prefKeyOf(g.allCity[i]) == pref) { bright[nb++] = p[0]; bright[nb++] = p[1]; }
      else { dim[nd++] = p[0]; dim[nd++] = p[1]; }
    }
    dots = Arrays.copyOf(bright, nb);
    dimDots = Arrays.copyOf(dim, nd);
  }

  /** 枠にかかる境界線を、組ごとの線分（drawLines の形）にする */
  private void collectLines(Bounds.Lines bl, float w, float h) {
    float[][] buf = new float[LINE_GROUPS][4096];
    int[] n = new int[LINE_GROUPS];
    if (bl != null) {
      double latTop = cLat + cy * mpp / M_LAT, latBot = cLat - (h - cy) * mpp / M_LAT;
      double kx = Math.cos(Math.toRadians(cLat)) * M_LON;
      double lonL = cLon - cx * mpp / kx, lonR = cLon + (w - cx) * mpp / kx;
      for (int i = 0; i < bl.n; i++) {
        if (bl.bbox[i * 4] > latTop || bl.bbox[i * 4 + 2] < latBot || bl.bbox[i * 4 + 1] > lonR || bl.bbox[i * 4 + 3] < lonL) continue;
        int t = bl.type[i], grp = t == 1 ? 0 : t <= 3 ? 1 : t <= 5 ? 2 : 3;
        float[] prev = null;
        for (int j = bl.start[i], e = j + bl.count[i]; j < e; j++) {
          float[] p = toScreen(bl.lat[j], bl.lon[j]);
          if (prev != null) {
            if (n[grp] + 4 > buf[grp].length) buf[grp] = Arrays.copyOf(buf[grp], buf[grp].length * 2);
            buf[grp][n[grp]++] = prev[0]; buf[grp][n[grp]++] = prev[1];
            buf[grp][n[grp]++] = p[0]; buf[grp][n[grp]++] = p[1];
          }
          prev = p;
        }
      }
    }
    for (int k = 0; k < LINE_GROUPS; k++) lines[k] = Arrays.copyOf(buf[k], n[k]);
  }

  /** PREF：市区町村（1970 年表示なら当時の市町村）の名前を、その点の平均の所に。点の多い順、重ならないものだけ */
  private void cityLabels(float w, float h, float charW, float lineH, boolean old1970) {
    Map<Integer, double[]> sum = new HashMap<>();
    for (int i : visible(w, h)) {
      int key = old1970 && old != null ? old.indexAt(i) : g.allCity[i];
      if (old1970 && key == 0) continue;
      double[] s = sum.computeIfAbsent(key, k -> new double[3]);
      s[0] += g.allLat[i]; s[1] += g.allLon[i]; s[2]++;
    }
    List<Map.Entry<Integer, double[]>> cities = new ArrayList<>(sum.entrySet());
    cities.sort((x, y) -> Double.compare(y.getValue()[2], x.getValue()[2]));
    Occupancy occ = new Occupancy(w, h, charW, lineH);
    for (Map.Entry<Integer, double[]> e : cities) {
      double[] s = e.getValue();
      float[] p = toScreen(s[0] / s[2], s[1] / s[2]);
      String name;
      if (old1970 && old != null) {
        String[] o = old.name(e.getKey());
        name = o == null ? "" : o[1];
      } else {
        name = g.cityNameAt(e.getKey());
      }
      if (!name.isEmpty() && occ.take(p[0], p[1], name.length())) labels.add(new Label(name, p[0], p[1]));
    }
  }

  /** LOCAL：町字の名前（OLD 表示なら 2000 年の町丁・字。同じ名前は 1 回だけ）。まん中に近いものから、重ならないものだけ（最大 60） */
  private void townLabels(float w, float h, float charW, float lineH, boolean oldNames) {
    java.util.Set<String> seen = new java.util.HashSet<>();
    List<int[]> in = new ArrayList<>();
    for (int i : visible(w, h)) in.add(new int[] {i, (int) RGeo.dist(cLat, cLon, g.allLat[i], g.allLon[i])});
    in.sort((x, y) -> Integer.compare(x[1], y[1]));
    Occupancy occ = new Occupancy(w, h, charW, lineH);
    for (int[] e : in) {
      float[] p = toScreen(g.allLat[e[0]], g.allLon[e[0]]);
      String name = g.townLineAt(e[0]);
      if (oldNames && oldTown != null) {
        String[] o = oldTown.at(e[0]);
        if (o != null) name = o[1];
      }
      if (seen.contains(name)) continue;
      if (occ.take(p[0], p[1], name.length())) { labels.add(new Label(name, p[0], p[1])); seen.add(name); }
      if (labels.size() >= 60) break;
    }
  }

  /** 名前どうしが重ならないように、枠を升目（1 文字 × 1 行）に分けて使った所を覚える。名前は点の右に出す */
  private static final class Occupancy {
    private final boolean[][] used;
    private final float cw, ch;

    Occupancy(float w, float h, float charW, float lineH) {
      cw = charW;
      ch = lineH;
      used = new boolean[(int) (h / lineH) + 2][(int) (w / charW) + 2];
    }

    boolean take(float x, float y, int chars) {
      int r = (int) (y / ch), c0 = (int) (x / cw);
      if (r < 0 || r >= used.length || c0 < 0 || c0 >= used[0].length) return false;
      int c1 = Math.min(c0 + chars + 1, used[0].length - 1);
      for (int c = c0; c <= c1; c++) if (used[r][c]) return false;
      for (int c = c0; c <= c1; c++) used[r][c] = true;
      return true;
    }
  }
}
