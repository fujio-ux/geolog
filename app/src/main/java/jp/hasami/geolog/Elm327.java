package jp.hasami.geolog;

/**
 * ELM327（OBD2 アダプター）の返事を読む。Android に依存しない（パソコンでもテストできる）。
 * 返事の例：「41 0C 1A F8」（回転数）、「41 0D 3C」（車速）、「41 05 7B」（水温）、「12.6V」（ATRV の電圧）。
 * 空白・改行・プロンプト「>」・こちらの送った命令のこだま（ATE0 の前）・「SEARCHING...」が混ざっていてもよい。
 */
public final class Elm327 {
  private Elm327() {}

  /** 空白と改行を取り、大文字にした返事 */
  static String clean(String resp) {
    return resp == null ? "" : resp.replaceAll("[\\s>]", "").toUpperCase();
  }

  /** モード 01 の PID の返事のデータ（「41」「PID」の後ろのバイト）。見つからなければ null */
  static int[] data01(String resp, int pid, int bytes) {
    String s = clean(resp);
    String key = String.format("41%02X", pid);
    int i = s.indexOf(key);
    if (i < 0 || s.length() < i + 4 + bytes * 2) return null;
    int[] out = new int[bytes];
    try {
      for (int k = 0; k < bytes; k++) out[k] = Integer.parseInt(s.substring(i + 4 + k * 2, i + 6 + k * 2), 16);
    } catch (NumberFormatException e) {
      return null;
    }
    return out;
  }

  /** エンジン回転数（PID 0C）：(A×256＋B)÷4 回転。読めなければ -1 */
  public static double rpm(String resp) {
    int[] d = data01(resp, 0x0C, 2);
    return d == null ? -1 : (d[0] * 256 + d[1]) / 4.0;
  }

  /** 車速（PID 0D）：A km/h。読めなければ -1 */
  public static int speed(String resp) {
    int[] d = data01(resp, 0x0D, 1);
    return d == null ? -1 : d[0];
  }

  /** 水温（PID 05）：A−40 ℃。読めなければ Integer.MIN_VALUE */
  public static int coolant(String resp) {
    int[] d = data01(resp, 0x05, 1);
    return d == null ? Integer.MIN_VALUE : d[0] - 40;
  }

  /** ATRV の電圧（例「12.6V」）。読めなければ NaN */
  public static double volt(String resp) {
    if (resp == null) return Double.NaN;
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2}\\.\\d)\\s*V", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(resp);
    return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
  }

  /** 車とつながっていないときの返事か（エンジンが止まっている、キーがオフ など） */
  public static boolean noCar(String resp) {
    String s = clean(resp);
    return s.contains("UNABLETOCONNECT") || s.contains("NODATA") || s.contains("CANERROR") || s.contains("BUSINIT") || s.contains("STOPPED");
  }
}
