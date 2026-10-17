package jp.hasami.geolog;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * ELM327（OBD2 アダプター、クラシック Bluetooth の SPP）とつないで、回転数などを読み続ける。結果は State の obd〜 に入れる。
 * 端末の設定で先にペアリングしておく（名前に OBD・ELM・V-LINK などが入っている機器を選ぶ）。切れたら 3 秒後につなぎ直す。
 * 回転数（01 0C）は毎回、車速（01 0D）は 3 回に 1 回、水温（01 05）と電圧（ATRV）は 30 回に 1 回。
 */
final class ObdLink {
  private static final String TAG = "GeoLog.OBD";
  private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
  private static final String[] NAME_KEYS = {"OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "KONNWEI", "VIECAR", "VEEPEAK"};
  private static final ObdLink INSTANCE = new ObdLink();

  static ObdLink get() { return INSTANCE; }

  private Thread thread;
  private volatile boolean running;
  private volatile BluetoothSocket socket;

  synchronized void start() {
    if (thread != null) return;
    running = true;
    thread = new Thread(this::loop, "obd");
    thread.start();
  }

  synchronized void stop() {
    running = false;
    closeSocket();
    if (thread != null) thread.interrupt();
    thread = null;
    State.obdLinked = false;
    State.obdStatus = "OFF";
  }

  private void closeSocket() {
    BluetoothSocket s = socket;
    socket = null;
    if (s != null) try { s.close(); } catch (IOException ignored) {}
  }

  @SuppressLint("MissingPermission")
  private static BluetoothDevice pick(Set<BluetoothDevice> bonded) {
    if (bonded == null) return null;
    for (BluetoothDevice d : bonded) {
      String n = d.getName() == null ? "" : d.getName().toUpperCase();
      for (String k : NAME_KEYS) if (n.contains(k)) return d;
    }
    return null;
  }

  @SuppressLint("MissingPermission")
  private void loop() {
    while (running) {
      State.obdLinked = false;
      try {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || !ad.isEnabled()) { State.obdStatus = "BLUETOOTH OFF"; nap(3000); continue; }
        BluetoothDevice dev = pick(ad.getBondedDevices());
        if (dev == null) { State.obdStatus = "PAIR ELM327"; State.obdName = ""; nap(3000); continue; }
        State.obdName = dev.getName();
        State.obdStatus = "CONNECTING";
        ad.cancelDiscovery();
        BluetoothSocket s;
        try {
          s = dev.createRfcommSocketToServiceRecord(SPP);
          s.connect();
        } catch (IOException e) {
          // 安いアダプターは SDP が無いことがあるので、チャンネル 1 に直接
          s = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", int.class).invoke(dev, 1);
          s.connect();
        }
        socket = s;
        session(s.getInputStream(), s.getOutputStream());
      } catch (Exception e) {
        Log.w(TAG, "つながらない・切れた", e);
        State.obdStatus = "LINK LOST";
      } finally {
        closeSocket();
        State.obdLinked = false;
      }
      nap(3000);
    }
  }

  private void session(InputStream in, OutputStream out) throws IOException {
    State.obdStatus = "INIT";
    send(in, out, "ATZ", 3000);           // リセット
    for (String c : new String[] {"ATE0", "ATL0", "ATS0", "ATH0", "ATSP0"}) send(in, out, c, 1500);   // こだま・改行・空白・ヘッダーなし、通信方式は自動
    State.obdStatus = "SEARCHING";
    String r = send(in, out, "0100", 10000);   // 最初の問い合わせで通信方式を探す（数秒かかる）
    if (Elm327.noCar(r)) State.obdStatus = "NO ENGINE";
    Log.i(TAG, "0100 の返事：" + Elm327.clean(r));
    State.obdOk = State.obdNg = 0;
    for (int i = 0; running; i++) {
      r = send(in, out, "010C", 2500);
      State.obdRaw = Elm327.clean(r);
      double rpm = Elm327.rpm(r);
      if (i < 30 || i % 100 == 0) Log.i(TAG, "010C の返事 #" + i + "：" + State.obdRaw + " → " + rpm);
      if (rpm >= 0) {
        State.obdRpm = rpm;
        State.obdAt = System.currentTimeMillis();
        State.obdLinked = true;
        State.obdStatus = "LINKED";
        State.obdOk++;
      } else {
        State.obdNg++;
        if (Elm327.noCar(r)) {
          State.obdStatus = "NO ENGINE";
          nap(1000);
        }
      }
      if (i % 3 == 0) {
        int k = Elm327.speed(send(in, out, "010D", 2500));
        if (k >= 0) State.obdKmh = k;
      }
      if (i % 30 == 0) {
        int c = Elm327.coolant(send(in, out, "0105", 2500));
        if (c != Integer.MIN_VALUE) State.obdCoolant = c;
        double v = Elm327.volt(send(in, out, "ATRV", 2500));
        if (!Double.isNaN(v)) State.obdVolt = v;
      }
    }
  }

  /** 命令を送り、プロンプト「>」が来るまで（または時間切れまで）の返事を返す */
  private static String send(InputStream in, OutputStream out, String cmd, long timeoutMs) throws IOException {
    while (in.available() > 0) in.read();   // 前の残り
    out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
    out.flush();
    StringBuilder sb = new StringBuilder();
    long end = System.currentTimeMillis() + timeoutMs;
    boolean prompt = false;
    while (System.currentTimeMillis() < end) {
      if (in.available() > 0) {
        int c = in.read();
        if (c < 0) throw new IOException("切れた");
        if (c == '>') { prompt = true; break; }
        sb.append((char) c);
      } else {
        nap(5);
      }
    }
    if (!prompt) {   // 時間切れ：遅れて来る残りを 0.5 秒待って捨てる（次の命令の返事とずれないように）
      Log.w(TAG, cmd + "：時間切れ（" + Elm327.clean(sb.toString()) + "）");
      long drainEnd = System.currentTimeMillis() + 500;
      while (System.currentTimeMillis() < drainEnd) {
        while (in.available() > 0) if (in.read() == '>') drainEnd = 0;
        nap(10);
      }
    }
    return sb.toString();
  }

  private static void nap(long ms) {
    try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
  }
}
