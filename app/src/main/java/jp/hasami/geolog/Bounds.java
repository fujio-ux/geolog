package jp.hasami.geolog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 拡大地図の境界線（bounds.bin、~/Documents/reverse-geocoding/tools/build_history.py で作る）。今と 1970 年の 2 組。
 * 元データ：「地図地理Sandbox」市区町村境界時系列データの境界線（製作 はにしな）。
 * 種類：1 都道府県界、2 支庁界、3 郡界、4 市町村界、5 区界、7 海岸線、8 湖岸、9 その他
 */
public final class Bounds {
  public static final class Lines {
    public final int date, n;
    public final byte[] type;
    public final int[] start, count;    // 点の配列の中の位置と数
    public final float[] lat, lon;      // 全部の線の点
    public final float[] bbox;          // 線ごとに 南, 西, 北, 東

    Lines(int date, int n, byte[] type, int[] start, int[] count, float[] lat, float[] lon, float[] bbox) {
      this.date = date; this.n = n; this.type = type; this.start = start; this.count = count;
      this.lat = lat; this.lon = lon; this.bbox = bbox;
    }
  }

  public final Lines now, old1970;

  public Bounds(ByteBuffer buf) {
    ByteBuffer b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    if (b.get(0) != 'B' || b.get(1) != 'N' || b.get(2) != 'D' || b.get(3) != '1') throw new IllegalArgumentException("BND1 形式ではない");
    int sets = b.getInt(4), p = 8;
    Lines[] out = new Lines[sets];
    for (int s = 0; s < sets; s++) {
      int date = b.getInt(p), n = b.getInt(p + 4);
      p += 8;
      // 1 回目：点の数を数える
      int q = p, total = 0;
      for (int i = 0; i < n; i++) {
        int c = b.getShort(q + 2) & 0xFFFF;
        total += c;
        q += 20 + c * 8;
      }
      byte[] type = new byte[n];
      int[] start = new int[n], count = new int[n];
      float[] lat = new float[total], lon = new float[total], bbox = new float[n * 4];
      int k = 0;
      for (int i = 0; i < n; i++) {
        type[i] = b.get(p);
        int c = b.getShort(p + 2) & 0xFFFF;
        for (int j = 0; j < 4; j++) bbox[i * 4 + j] = b.getInt(p + 4 + j * 4) / 1e6f;
        start[i] = k;
        count[i] = c;
        p += 20;
        for (int j = 0; j < c; j++, k++, p += 8) {
          lat[k] = b.getInt(p) / 1e6f;
          lon[k] = b.getInt(p + 4) / 1e6f;
        }
      }
      out[s] = new Lines(date, n, type, start, count, lat, lon, bbox);
    }
    now = out[0];
    old1970 = sets > 1 ? out[1] : out[0];
  }
}
