package jp.hasami.geolog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 1970 年（昭和 45 年 4 月 1 日）の市町村名（old1970.bin、~/Documents/reverse-geocoding/tools/build_history.py で作る）。
 * rgeo_all.bin の点の番号ごとに、その点が 1970 年にどの市町村だったか。
 * 元データ：「地図地理Sandbox」市区町村境界時系列データ（製作 はにしな）。
 * 形式：'OLD1', u32 点の数, u32 名前の数, u32 日付 / u16 × 点の数（名前の番号、0 は分からない）/ u32 × 名前の数（文字列の位置）/ 文字列（UTF-8、NUL 終わり、「都道府県＋郡\t市町村」）
 * 'OLD2' は名前の番号が u32（名前が 65535 を超えるとき）。2000 年の町丁・字（old2000.bin、e-Stat 平成 12 年国勢調査の境界から）がこれで、文字列は町丁・字の名前だけ。
 */
public final class OldNames {
  private final ByteBuffer b;
  public final int nPoints, nNames, date;
  private final int offIdx = 16, offOffs, offStr, idxSize;

  public OldNames(ByteBuffer buf) {
    b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    if (b.get(0) != 'O' || b.get(1) != 'L' || b.get(2) != 'D' || (b.get(3) != '1' && b.get(3) != '2')) throw new IllegalArgumentException("OLD1/OLD2 形式ではない");
    idxSize = b.get(3) == '2' ? 4 : 2;
    nPoints = b.getInt(4);
    nNames = b.getInt(8);
    date = b.getInt(12);
    offOffs = offIdx + nPoints * idxSize;
    offStr = offOffs + nNames * 4;
  }

  /** 点 i の 1970 年の名前の番号（0 は分からない） */
  public int indexAt(int i) {
    if (i < 0 || i >= nPoints) return 0;
    return idxSize == 4 ? b.getInt(offIdx + i * 4) : b.getShort(offIdx + i * 2) & 0xFFFF;
  }

  /** 点 i の 1970 年の名前 {都道府県＋郡（政令市の区は市）, 市町村}。分からなければ null */
  public String[] at(int i) { return name(indexAt(i)); }

  /** 番号 k の名前 {都道府県＋郡, 市町村}。0 や範囲外は null */
  public String[] name(int k) {
    if (k <= 0 || k >= nNames) return null;
    int p = offStr + b.getInt(offOffs + k * 4), e = p;
    while (b.get(e) != 0) e++;
    byte[] a = new byte[e - p];
    for (int j = 0; j < a.length; j++) a[j] = b.get(p + j);
    String s = new String(a, StandardCharsets.UTF_8);
    int tab = s.indexOf('\t');
    return tab < 0 ? new String[] {"", s} : new String[] {s.substring(0, tab), s.substring(tab + 1)};
  }
}
