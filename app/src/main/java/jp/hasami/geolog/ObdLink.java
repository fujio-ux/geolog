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
 * 回転数をできるだけ速く読む（session の説明）。水温（01 05）と電圧（ATRV）は 100 回に 1 回。
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

  // 返事の受け取り：専用の流れで読み続け、プロンプト「>」までを 1 つの返事として渡す（細かい待ち時間を無くす）
  private final java.util.concurrent.LinkedBlockingQueue<String> replies = new java.util.concurrent.LinkedBlockingQueue<>();
  private volatile IOException readError;
  private volatile int readerGen;   // つなぎ直すたびに増やす。前の接続の受け取りの流れが、新しい接続を邪魔しないように

  private void startReader(InputStream in) {
    readError = null;
    replies.clear();
    final int gen = ++readerGen;
    Thread t = new Thread(() -> {
      StringBuilder sb = new StringBuilder();
      try {
        while (running && gen == readerGen) {
          int c = in.read();   // 来るまで待つ
          if (c < 0) throw new IOException("切れた");
          if (gen != readerGen) break;
          if (c == '>') { replies.offer(sb.toString()); sb.setLength(0); }
          else sb.append((char) c);
        }
      } catch (IOException e) {
        if (gen == readerGen) {
          readError = e;
          replies.offer("");   // 待っている側を起こす
        }
      }
    }, "obd-read");
    t.setDaemon(true);
    t.start();
  }

  /** 命令を送り、返事（「>」まで）を待つ。時間切れなら null。前の命令の遅れた返事は捨てる */
  private String ask(OutputStream out, String cmd, long timeoutMs) throws IOException {
    replies.clear();
    out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
    out.flush();
    try {
      String r = replies.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
      if (readError != null) throw readError;
      if (r == null) Log.w(TAG, cmd + "：時間切れ");
      return r;
    } catch (InterruptedException e) {
      throw new IOException("止めた");
    }
  }

  // 1 秒あたりに回転数が取れた回数（確認用の行に出す）
  private final long[] okTimes = new long[32];
  private int okIdx;

  private void countOk() {
    long now = System.currentTimeMillis();
    okTimes[okIdx++ % okTimes.length] = now;
    int n = 0;
    for (long t : okTimes) if (now - t <= 2000) n++;
    State.obdHz = n / 2.0f;
  }

  /**
   * 回転数をできるだけ速く読む工夫：
   *   ・ATAT2（ELM327 の待ち時間を自動で短く）
   *   ・問い合わせの末尾に「1」（返事は 1 つと伝え、ほかの ECU の返事を待つ時間をなくす。v1.3 以降）
   *   ・回転数と車速をまとめて聞く（010C0D。CAN なら 1 つの返事で両方）
   * 対応していないアダプター・車もあるので、速い形から順に試して、読めた形を使う
   */
  private void session(InputStream in, OutputStream out) throws IOException {
    startReader(in);
    State.obdStatus = "INIT";
    ask(out, "ATZ", 4000);   // リセット
    for (String c : new String[] {"ATE0", "ATL0", "ATS0", "ATH0", "ATSP0"}) ask(out, c, 1500);   // こだま・改行・空白・ヘッダーなし、通信方式は自動
    State.obdStatus = "SEARCHING";
    String r = ask(out, "0100", 12000);   // 最初の問い合わせで通信方式を探す（数秒かかる）
    Log.i(TAG, "0100 の返事：" + Elm327.clean(r));
    if (r != null && Elm327.noCar(r)) State.obdStatus = "NO ENGINE";
    ask(out, "ATAT2", 1500);
    Log.i(TAG, "通信方式：" + Elm327.clean(ask(out, "ATDP", 1500)));

    // 使う形を決める（速い順）
    String[] forms = {"010C0D1", "010C0D", "010C1", "010C"};
    String form = "010C";
    boolean both = false;
    probe:
    for (String f : forms) {
      for (int k = 0; k < 2; k++) {
        String t = ask(out, f, 2500);
        boolean ok = f.startsWith("010C0D") ? Elm327.rpmSpeed(t) != null : Elm327.rpm(t) >= 0;
        Log.i(TAG, "試し " + f + "：" + Elm327.clean(t) + (ok ? " → 使える" : ""));
        if (ok) { form = f; both = f.startsWith("010C0D"); break probe; }
      }
    }
    String speedCmd = form.endsWith("1") ? "010D1" : "010D";
    State.obdForm = form;
    Log.i(TAG, "回転数の問い合わせ：" + form);

    State.obdOk = State.obdNg = 0;
    for (int i = 0; running; i++) {
      r = ask(out, form, 2500);
      State.obdRaw = Elm327.clean(r);
      double rpm = -1;
      if (both) {
        double[] rs = Elm327.rpmSpeed(r);
        if (rs != null) { rpm = rs[0]; State.obdKmh = (int) rs[1]; }
      }
      if (rpm < 0) rpm = Elm327.rpm(r);
      if (i < 30 || i % 200 == 0) Log.i(TAG, form + " の返事 #" + i + "：" + State.obdRaw + " → " + rpm);
      if (rpm >= 0) {
        State.obdRpm = rpm;
        State.obdAt = System.currentTimeMillis();
        State.obdLinked = true;
        State.obdStatus = "LINKED";
        State.obdOk++;
        countOk();
      } else {
        State.obdNg++;
        if (r != null && Elm327.noCar(r)) {
          State.obdStatus = "NO ENGINE";
          nap(1000);
        }
      }
      if (!both && i % 5 == 0) {   // まとめ聞きできないときだけ、車速を 5 回に 1 回
        int k = Elm327.speed(ask(out, speedCmd, 2500));
        if (k >= 0) State.obdKmh = k;
      }
      if (i % 100 == 0) {   // 水温・電圧はほとんど変わらないので、ときどき
        int c = Elm327.coolant(ask(out, form.endsWith("1") ? "01051" : "0105", 2500));
        if (c != Integer.MIN_VALUE) State.obdCoolant = c;
        double v = Elm327.volt(ask(out, "ATRV", 2500));
        if (!Double.isNaN(v)) State.obdVolt = v;
      }
    }
  }

  private static void nap(long ms) {
    try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
  }
}
