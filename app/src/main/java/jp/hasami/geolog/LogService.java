package jp.hasami.geolog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GPS を受け続け、State を更新し、5 秒ごとに記録（queue.csv）へ足し、ネットにつながっていれば 1 分ごとにサーバーへ送る。
 * 画面を消しても止まらないよう、通知を出す「前面サービス」で動かす。
 */
public class LogService extends Service {
  private static final String TAG = "GeoLog";
  static final String UPLOAD_URL = "https://api.hasami.uk/geolog/api.php?action=upload";
  private static final long LOG_INTERVAL_MS = 5000;     // 記録の間隔
  private static final long UPLOAD_INTERVAL_MS = 60000; // 送る間隔
  private static final int UPLOAD_BATCH = 2000;          // 1 回に送る点の上限

  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private LocationManager lm;
  private Track track;
  private RGeo rgeo;
  private long lastLogged;
  private Nmea.Gga lastGga;
  private long lastGgaAt;
  private boolean placeBusy;

  @Override
  public IBinder onBind(Intent intent) { return null; }

  @Override
  public void onCreate() {
    super.onCreate();
    startAsForeground();
    track = new Track(new File(getFilesDir(), "queue.csv"));
    String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
    State.device = "P780-" + (id == null ? "0000" : id.substring(0, Math.min(6, id.length())));
    worker.execute(() -> {
      try (AssetFileDescriptor afd = getAssets().openFd("rgeo_all.bin");
           FileInputStream in = new FileInputStream(afd.getFileDescriptor())) {
        MappedByteBuffer m = in.getChannel().map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getLength());
        rgeo = new RGeo(m);
        rgeo.decodeAll();   // 地図の拡大表示用
        RGeo r = rgeo;
        OldNames on = new OldNames(mapAsset("old1970.bin"));
        OldNames ot = new OldNames(mapAsset("old2000.bin"));
        Bounds bd = new Bounds(mapAsset("bounds.bin"));
        main.post(() -> { State.rgeo = r; State.oldNames = on; State.oldTowns = ot; State.bounds = bd; });
      } catch (Exception e) {
        Log.e(TAG, "町字のデータを開けない", e);
      }
      refreshPending();
    });
    lm = getSystemService(LocationManager.class);
    try {
      lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener, Looper.getMainLooper());
      lm.registerGnssStatusCallback(gnssCallback, main);
      lm.addNmeaListener(nmeaListener, main);
    } catch (SecurityException e) {
      Log.e(TAG, "位置情報の許可が無い", e);
      State.uploadStatus = "位置情報の許可が無い";
    }
    main.postDelayed(uploadTick, 10000);
    main.postDelayed(fixWatch, 2000);
  }

  /** アセットのファイルを、圧縮せずに入れてある前提でメモリに割り当てる */
  private MappedByteBuffer mapAsset(String name) throws java.io.IOException {
    try (AssetFileDescriptor afd = getAssets().openFd(name); FileInputStream in = new FileInputStream(afd.getFileDescriptor())) {
      return in.getChannel().map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getLength());
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

  @Override
  public void onDestroy() {
    lm.removeUpdates(locationListener);
    lm.unregisterGnssStatusCallback(gnssCallback);
    lm.removeNmeaListener(nmeaListener);
    main.removeCallbacksAndMessages(null);
    worker.shutdown();
    super.onDestroy();
  }

  private void startAsForeground() {
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(new NotificationChannel("log", "行動記録", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    Notification n = new Notification.Builder(this, "log")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("GeoLog 記録中")
        .setContentText("5 秒ごとに位置を記録しています")
        .setContentIntent(open)
        .setOngoing(true)
        .build();
    startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
  }

  private final LocationListener locationListener = new LocationListener() {
    @Override
    public void onLocationChanged(Location loc) { onLocation(loc); }

    @Override
    public void onProviderEnabled(String p) {}

    @Override
    public void onProviderDisabled(String p) { State.fix = false; }
  };

  private void onLocation(Location loc) {
    State.fix = true;
    State.fixAt = System.currentTimeMillis();
    State.lat = loc.getLatitude();
    State.lon = loc.getLongitude();
    State.kmh = loc.hasSpeed() ? loc.getSpeed() * 3.6 : Double.NaN;
    State.bearing = loc.hasBearing() ? loc.getBearing() : Double.NaN;
    State.acc = loc.hasAccuracy() ? loc.getAccuracy() : Double.NaN;
    State.altEllipsoid = loc.hasAltitude() ? loc.getAltitude() : Double.NaN;
    // 標高：直前（3 秒以内）の GGA の値。無ければ楕円体の高さからジオイドの高さを引く
    boolean ggaFresh = lastGga != null && lastGga.fix > 0 && SystemClock.elapsedRealtime() - lastGgaAt < 3000;
    if (ggaFresh && !Double.isNaN(lastGga.altMsl)) State.altMsl = lastGga.altMsl;
    else if (lastGga != null && !Double.isNaN(lastGga.geoidSep) && loc.hasAltitude()) State.altMsl = loc.getAltitude() - lastGga.geoidSep;
    else State.altMsl = Double.NaN;
    State.gpsMinusSystem = loc.getTime() - System.currentTimeMillis();
    State.gpsTimeKnown = true;

    if (rgeo != null && !placeBusy) {
      placeBusy = true;
      double la = loc.getLatitude(), lo = loc.getLongitude();
      worker.execute(() -> {
        RGeo.Place p = rgeo.nearest(la, lo);
        main.post(() -> { State.place = p; placeBusy = false; });
      });
    }

    if (loc.getTime() - lastLogged >= LOG_INTERVAL_MS - 200) {
      lastLogged = loc.getTime();
      Track.Point p = new Track.Point();
      p.ts = loc.getTime();
      p.lat = loc.getLatitude();
      p.lon = loc.getLongitude();
      p.alt = Double.isNaN(State.altMsl) ? null : State.altMsl;
      p.kmh = Double.isNaN(State.kmh) ? null : State.kmh;
      p.bearing = Double.isNaN(State.bearing) ? null : State.bearing;
      p.acc = Double.isNaN(State.acc) ? null : State.acc;
      p.sats = State.satsUsed;
      RGeo.Place pl = State.place;
      p.place = pl == null ? null : pl.cityLine() + pl.townLine();
      worker.execute(() -> {
        try {
          track.append(p);
        } catch (Exception e) {
          Log.e(TAG, "記録できない", e);
        }
        refreshPending();
      });
    }
  }

  private final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
    @Override
    public void onSatelliteStatusChanged(GnssStatus s) {
      int used = 0, qv = 0, qu = 0;
      for (int i = 0; i < s.getSatelliteCount(); i++) {
        boolean u = s.usedInFix(i);
        if (u) used++;
        if (s.getConstellationType(i) == GnssStatus.CONSTELLATION_QZSS) {
          qv++;
          if (u) qu++;
        }
      }
      State.satsVisible = s.getSatelliteCount();
      State.satsUsed = used;
      State.qzssVisible = qv;
      State.qzssUsed = qu;
    }
  };

  private final OnNmeaMessageListener nmeaListener = (msg, ts) -> {
    Nmea.Gga g = Nmea.parseGga(msg);
    if (g != null) {
      lastGga = g;
      lastGgaAt = SystemClock.elapsedRealtime();
    }
  };

  // 10 秒位置が来なければ「測位できていない」にする
  private final Runnable fixWatch = new Runnable() {
    @Override
    public void run() {
      if (State.fix && System.currentTimeMillis() - State.fixAt > 10000) State.fix = false;
      main.postDelayed(this, 2000);
    }
  };

  private final Runnable uploadTick = new Runnable() {
    @Override
    public void run() {
      worker.execute(LogService.this::uploadAll);
      main.postDelayed(this, UPLOAD_INTERVAL_MS);
    }
  };

  private void refreshPending() {
    try {
      int n = track.count();
      main.post(() -> State.pending = n);
    } catch (Exception ignored) {
    }
  }

  private String token() {
    File f = new File(getFilesDir(), "token.txt");
    try {
      String t = f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim() : "";
      return t.length() >= 32 ? t : null;
    } catch (Exception e) {
      return null;
    }
  }

  private boolean online() {
    ConnectivityManager cm = getSystemService(ConnectivityManager.class);
    Network n = cm.getActiveNetwork();
    NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
    return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
  }

  /** 送っていない点を、無くなるか失敗するまで送る（worker スレッドで呼ぶ） */
  private void uploadAll() {
    String tok = token();
    main.post(() -> State.hasToken = tok != null);
    if (tok == null) { setStatus("鍵が無い（token.txt）"); return; }
    try {
      for (int round = 0; round < 50; round++) {
        List<Track.Point> pts = track.pending(UPLOAD_BATCH);
        if (pts.isEmpty()) return;
        if (!online()) { setStatus("オフライン（未送信 " + track.count() + "）"); return; }
        String body = Track.uploadJson(State.device, pts);
        HttpURLConnection c = (HttpURLConnection) new URL(UPLOAD_URL).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-Geolog-Token", tok);
        try (OutputStream o = c.getOutputStream()) {
          o.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        String resp;
        try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) {
          ByteArrayOutputStream bo = new ByteArrayOutputStream();
          byte[] buf = new byte[4096];
          int r;
          while (in != null && (r = in.read(buf)) > 0) bo.write(buf, 0, r);
          resp = bo.toString("UTF-8");
        }
        long last = Track.parseLast(resp);
        if (code != 200 || last < 0) { setStatus("送信失敗 " + code); return; }
        track.dropUpTo(last);
        refreshPending();
        main.post(() -> State.lastUploadAt = System.currentTimeMillis());
        setStatus("送信 OK（" + pts.size() + " 点）");
      }
    } catch (Exception e) {
      Log.w(TAG, "送信できない", e);
      setStatus("送信できない：" + e.getClass().getSimpleName());
    }
  }

  private void setStatus(String s) { main.post(() -> State.uploadStatus = s); }
}
