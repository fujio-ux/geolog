import jp.hasami.geolog.Nmea;
import jp.hasami.geolog.RGeo;
import jp.hasami.geolog.Track;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Android に依存しない部品（RGeo・Nmea・Track）のテスト。./test.sh で実行する（JDK 21 だけで動く。ライブラリなし）。
 * RGeo は ~/Documents/reverse-geocoding/data/towns.csv を総当たりした答えと比べる。
 */
public class RunTests {
  static int failed = 0, passed = 0;

  static void check(boolean ok, String what) {
    if (ok) passed++;
    else { failed++; System.out.println("失敗：" + what); }
  }

  public static void main(String[] args) throws Exception {
    testNmea();
    testTrack();
    testRGeo(args.length > 0 ? args[0] : "app/src/main/assets/rgeo_all.bin",
             System.getProperty("user.home") + "/Documents/reverse-geocoding/data/towns.csv");
    System.out.println(failed == 0 ? "OK（" + passed + " 件）" : "失敗 " + failed + " 件／成功 " + passed + " 件");
    System.exit(failed == 0 ? 0 : 1);
  }

  static void testNmea() {
    // u-blox で実際に出ていた形（チェックサムは計算して付ける）
    String body = "GNGGA,112233.00,3557.24675,N,14003.23495,E,1,08,1.10,12.3,M,39.4,M,,";
    String line = "$" + body + "*" + String.format("%02X", body.chars().reduce(0, (a, c) -> a ^ c));
    Nmea.Gga g = Nmea.parseGga(line);
    check(g != null && g.fix == 1 && g.sats == 8 && g.altMsl == 12.3 && g.geoidSep == 39.4 && g.hdop == 1.1, "GGA を読む");
    check(Nmea.parseGga(line.substring(0, line.length() - 1) + "0") == null || line.endsWith("0"), "チェックサムが違えば捨てる");
    check(Nmea.parseGga("$GPRMC,1,2*00") == null, "GGA 以外は捨てる");
    String nofix = "GPGGA,110055.00,,,,,0,00,99.99,,,,,,";
    Nmea.Gga n = Nmea.parseGga("$" + nofix + "*" + String.format("%02X", nofix.chars().reduce(0, (a, c) -> a ^ c)));
    check(n != null && n.fix == 0 && Double.isNaN(n.altMsl), "測位前の GGA");
    check(Nmea.parseGga(null) == null && Nmea.parseGga("") == null, "空");
  }

  static void testTrack() throws Exception {
    File f = File.createTempFile("queue", ".csv");
    f.delete();
    Track t = new Track(f);
    check(t.count() == 0 && t.pending(10).isEmpty(), "ファイルが無いとき");
    for (int i = 0; i < 5; i++) {
      Track.Point p = new Track.Point();
      p.ts = 1792022400000L + i * 5000;
      p.lat = 35.9541234;
      p.lon = 140.0539251;
      p.alt = i == 0 ? null : 12.34;
      p.kmh = 3.0;
      p.sats = i == 1 ? null : 9;
      p.place = i == 0 ? "茨城県つくばみらい市\"谷井田\",\n" : null;
      t.append(p);
    }
    check(t.count() == 5, "5 点たまる");
    List<Track.Point> ps = t.pending(3);
    check(ps.size() == 3 && ps.get(0).ts == 1792022400000L && ps.get(0).alt == null && ps.get(1).alt == 12.3, "古い順に 3 点、標高は 0.1m に丸め");
    String json = Track.uploadJson("P780-test", ps.subList(0, 2));
    check(json.equals("{\"device\":\"P780-test\",\"points\":[[1792022400000,35.9541234,140.0539251,null,3.0,null,null,9,\"茨城県つくばみらい市\\\"谷井田\\\"  \"],"
        + "[1792022405000,35.9541234,140.0539251,12.3,3.0,null,null,null,null]]}"), "送る JSON：" + json);
    t.dropUpTo(1792022405000L);
    check(t.count() == 3 && t.pending(1).get(0).ts == 1792022410000L, "届いた分を消す");
    check(Track.parseLast("{\"ok\":true,\"saved\":2,\"skipped\":0,\"last\":1792022405000,\"rejected\":0}") == 1792022405000L, "返事の last");
    check(Track.parseLast("{\"ok\":false,\"error\":\"forbidden\"}") == -1 && Track.parseLast(null) == -1, "失敗の返事");
    f.delete();
  }

  /** 地図の拡大（ZoomMap はパッケージの中だけで使うので、反射で呼ぶ） */
  static void testZoom(RGeo g) throws Exception {
    jp.hasami.geolog.OldNames on;
    jp.hasami.geolog.Bounds bd;
    try (RandomAccessFile a = new RandomAccessFile("app/src/main/assets/old1970.bin", "r");
         RandomAccessFile b = new RandomAccessFile("app/src/main/assets/bounds.bin", "r")) {
      on = new jp.hasami.geolog.OldNames(a.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, a.length()));
      bd = new jp.hasami.geolog.Bounds(b.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, b.length()));
    }
    check(bd.now.n > 6000 && bd.old1970.n > 10000 && bd.old1970.date == 19700401, "境界線 今 " + bd.now.n + "・1970 年 " + bd.old1970.n);
    Class<?> zc = Class.forName("jp.hasami.geolog.ZoomMap");
    jp.hasami.geolog.OldNames ot;
    try (RandomAccessFile c = new RandomAccessFile("app/src/main/assets/old2000.bin", "r")) {
      ot = new jp.hasami.geolog.OldNames(c.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, c.length()));
    }
    check(ot.nPoints == g.nPoints && ot.date == 20001001, "2000 年：点の数と日付");
    String[] t2 = ot.at(g.nearest(35.951149, 140.049357).index);
    check(t2 != null && t2[1].equals("大字谷井田"), "谷井田 → 2000 年は大字谷井田 " + (t2 == null ? null : t2[1]));
    t2 = ot.at(g.nearest(35.681236, 139.767125).index);
    check(t2 != null && t2[1].equals("丸の内１丁目"), "東京駅 → 2000 年は丸の内１丁目 " + (t2 == null ? null : t2[1]));
    java.lang.reflect.Constructor<?> ctor = zc.getDeclaredConstructor(RGeo.class, jp.hasami.geolog.OldNames.class, jp.hasami.geolog.OldNames.class);
    ctor.setAccessible(true);
    Object z = ctor.newInstance(g, on, ot);
    java.lang.reflect.Method upd = zc.getDeclaredMethod("update", int.class, double.class, double.class, float.class, float.class,
        float.class, float.class, jp.hasami.geolog.Bounds.Lines.class, boolean.class);
    java.lang.reflect.Method ts = zc.getDeclaredMethod("toScreen", double.class, double.class);
    upd.setAccessible(true);
    ts.setAccessible(true);
    java.lang.reflect.Field fDots = zc.getDeclaredField("dots"), fLabels = zc.getDeclaredField("labels"), fLines = zc.getDeclaredField("lines");
    fDots.setAccessible(true);
    fLabels.setAccessible(true);
    fLines.setAccessible(true);
    double lat = 35.951149, lon = 140.049357;
    float w = 720, h = 800;

    upd.invoke(z, 1, lat, lon, w, h, 20f, 26f, bd.now, false);   // PREF（今）
    float[] pin = (float[]) ts.invoke(z, lat, lon);
    check(Math.abs(pin[0] - w / 2) < 1 && Math.abs(pin[1] - h / 2) < 1, "PREF：今いる所がまん中");
    float[] dots = (float[]) fDots.get(z);
    check(dots.length / 2 > 1000, "PREF：茨城県の点 " + dots.length / 2);
    float[][] lines = (float[][]) fLines.get(z);
    check(lines[0].length > 0 && lines[2].length > 0, "PREF：都道府県界と市町村界の線がある");
    List<String> names = labelTexts(fLabels.get(z));
    check(names.contains("つくばみらい市") || names.contains("取手市") || names.contains("つくば市"), "PREF：今の市の名前 " + names.size());

    upd.invoke(z, 1, lat, lon, w, h, 20f, 26f, bd.old1970, true);   // PREF（1970 年）
    names = labelTexts(fLabels.get(z));
    boolean oldName = false;
    for (String n : names) if (n.endsWith("村") || n.endsWith("町")) oldName = true;
    check(oldName && !names.contains("つくばみらい市"), "PREF：1970 年の市町村の名前 " + names.subList(0, Math.min(6, names.size())));

    upd.invoke(z, 2, lat, lon, w, h, 20f, 26f, bd.now, false);   // LOCAL
    pin = (float[]) ts.invoke(z, lat, lon);
    check(Math.abs(pin[0] - w / 2) < 1 && Math.abs(pin[1] - h / 2) < 1, "LOCAL：今いる所がまん中");
    names = labelTexts(fLabels.get(z));
    check(names.get(0).equals("谷井田") && names.size() >= 20, "LOCAL：まん中に近い町字から名前を出す " + names.size());

    upd.invoke(z, 2, lat, lon, w, h, 20f, 26f, bd.old1970, true);   // LOCAL（OLD）
    names = labelTexts(fLabels.get(z));
    check(names.get(0).equals("大字谷井田"), "LOCAL（OLD）：2000 年の町丁・字の名前 " + names.subList(0, Math.min(5, names.size())));
  }

  @SuppressWarnings("unchecked")
  static List<String> labelTexts(Object labels) throws Exception {
    List<String> out = new ArrayList<>();
    for (Object l : (List<Object>) labels) {
      java.lang.reflect.Field t = l.getClass().getDeclaredField("text");
      t.setAccessible(true);
      out.add((String) t.get(l));
    }
    return out;
  }

  static void testRGeo(String bin, String csv) throws Exception {
    RGeo g;
    try (RandomAccessFile raf = new RandomAccessFile(bin, "r")) {
      MappedByteBuffer m = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, raf.length());
      g = new RGeo(m);
    }
    check(g.nPoints == 209330, "点の数 " + g.nPoints);
    RGeo.Place p = g.nearest(35.681236, 139.767125);
    check(p != null && p.cityLine().equals("東京都千代田区") && p.townLine().equals("丸の内一丁目"), "東京駅 → " + (p == null ? null : p.cityLine() + p.townLine()));
    p = g.nearest(35.951149, 140.049357);
    check(p != null && p.townLine().equals("谷井田") && Math.abs(p.distM - 395) < 2, "つくばみらい市谷井田");
    check(g.nearest(24.0, 140.0) != null, "海の上でも答える");

    // 拡大表示用の展開：nearest の答えと同じ点が、同じ名前・同じ位置で入っている
    g.decodeAll();
    check(g.allLat.length == g.nPoints, "展開した点の数");
    RGeo.Place yt = g.nearest(35.951149, 140.049357);
    int hit = -1;
    for (int i = 0; i < g.nPoints; i++) {
      if (Math.abs(g.allLat[i] - yt.lat) < 1e-5 && Math.abs(g.allLon[i] - yt.lon) < 1e-5) { hit = i; break; }
    }
    check(hit >= 0 && g.townLineAt(hit).equals("谷井田") && g.cityNameAt(g.allCity[hit]).equals("つくばみらい市"), "展開した点の名前");
    int ibaraki = g.prefKeyOf(g.allCity[hit]), n08 = 0;
    for (int i = 0; i < g.nPoints; i++) if (g.prefKeyOf(g.allCity[i]) == ibaraki) n08++;
    check(n08 > 3000 && n08 < 6000, "茨城県の点の数 " + n08);
    testZoom(g);

    // 1970 年の市町村名
    try (RandomAccessFile raf = new RandomAccessFile("app/src/main/assets/old1970.bin", "r")) {
      jp.hasami.geolog.OldNames on = new jp.hasami.geolog.OldNames(raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, raf.length()));
      check(on.nPoints == g.nPoints && on.date == 19700401, "1970 年：点の数と日付");
      String[] o = on.at(g.nearest(35.951149, 140.049357).index);
      check(o != null && o[0].equals("茨城県筑波郡") && o[1].equals("伊奈村"), "谷井田 → 1970 年は茨城県筑波郡伊奈村 " + (o == null ? null : o[0] + o[1]));
      o = on.at(g.nearest(35.681236, 139.767125).index);
      check(o != null && o[1].equals("千代田区"), "東京駅 → 千代田区");
      o = on.at(g.nearest(35.4437, 139.6380).index);
      check(o != null && o[0].equals("神奈川県横浜市") && o[1].endsWith("区"), "横浜 → 横浜市の区 " + (o == null ? null : o[0] + o[1]));
      check(on.at(-1) == null && on.at(g.nPoints) == null, "範囲外");
    }

    // 総当たりと比べる
    List<double[]> pts = new ArrayList<>();
    List<String> lines = Files.readAllLines(new File(csv).toPath());
    for (String l : lines.subList(1, lines.size())) {
      String[] c = l.split(",");
      pts.add(new double[] {Double.parseDouble(c[8]), Double.parseDouble(c[9])});
    }
    Random rnd = new Random(20261015);
    int n = 200, ok = 0;
    for (int i = 0; i < n; i++) {
      double[] base = pts.get(rnd.nextInt(pts.size()));
      double qa = base[0] + (rnd.nextDouble() - 0.5) * 0.1, qo = base[1] + (rnd.nextDouble() - 0.5) * 0.1;
      double best = Double.POSITIVE_INFINITY;
      for (double[] q : pts) best = Math.min(best, RGeo.dist(qa, qo, q[0], q[1]));
      RGeo.Place r = g.nearest(qa, qo);
      if (r != null && Math.abs(r.distM - best) <= 1.0) ok++;
      else System.out.printf("違う：%.6f,%.6f 検索=%.1f 総当たり=%.1f%n", qa, qo, r == null ? -1 : r.distM, best);
    }
    check(ok == n, "総当たりと一致 " + ok + "/" + n);
  }
}
