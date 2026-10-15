package jp.hasami.geolog;

/**
 * NMEA の GGA 文から、海面からの標高（受信機がジオイドで補正した値）を読む。
 * Android 11 の Location.getAltitude() は楕円体からの高さで、日本では実際の標高より 30〜40m 高く出るため。
 */
public final class Nmea {
  private Nmea() {}

  /** GGA の結果。fix が 0 なら測位できていない */
  public static final class Gga {
    public int fix, sats;
    public double altMsl, geoidSep, hdop;
  }

  /** チェックサムが合う GGA（$GPGGA・$GNGGA など）なら中身を、それ以外は null */
  public static Gga parseGga(String line) {
    if (line == null) return null;
    line = line.trim();
    int star = line.lastIndexOf('*');
    if (!line.startsWith("$") || star < 0 || star + 3 > line.length()) return null;
    int sum = 0;
    for (int i = 1; i < star; i++) sum ^= line.charAt(i);
    try {
      if (Integer.parseInt(line.substring(star + 1, star + 3), 16) != sum) return null;
    } catch (NumberFormatException e) {
      return null;
    }
    String[] f = line.substring(0, star).split(",", -1);
    if (f.length < 12 || f[0].length() < 6 || !f[0].endsWith("GGA")) return null;
    Gga g = new Gga();
    g.fix = num(f[6], 0).intValue();
    g.sats = num(f[7], 0).intValue();
    g.hdop = num(f[8], Double.NaN).doubleValue();
    g.altMsl = num(f[9], Double.NaN).doubleValue();
    g.geoidSep = num(f[11], Double.NaN).doubleValue();
    return g;
  }

  private static Number num(String s, double def) {
    if (s == null || s.isEmpty()) return def;
    try {
      return Double.parseDouble(s);
    } catch (NumberFormatException e) {
      return def;
    }
  }
}
