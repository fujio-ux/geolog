package jp.hasami.geolog;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * GeoLog の画面（HudView）。ホーム画面にもなる。
 * 画面を軽く押す：画面を点けたままにする／戻す。長押し：Android の設定を開く（ホームにしたときの出口）。
 * 右上の [ROT]：360→90→180→270 度と順に向きを変える。右下の [ZOOM]：地図の拡大。
 */
public class MainActivity extends Activity {
  /** 0：360 度（縦）、1：90 度（横）、2：180 度（逆さの縦）、3：270 度（逆の横） */
  static final int[] ORIENTATIONS = {
      ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
      ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE};
  private HudView hud;

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().setStatusBarColor(Color.BLACK);
    getWindow().setNavigationBarColor(Color.BLACK);
    hud = new HudView(this);
    setContentView(hud);
    // 向き：右上のボタンで 360（縦）→ 90（横）→ 180（逆さの縦）→ 270（逆の横）→ 360 … と順に変えて覚える（重力センサーは使わない）
    SharedPreferences prefs = getSharedPreferences("hud", MODE_PRIVATE);
    if (!prefs.contains("rot") && prefs.getBoolean("landscape", false)) prefs.edit().putInt("rot", 1).apply();   // 0.5.0 の設定を引き継ぐ
    hud.rot = prefs.getInt("rot", 0);
    setRequestedOrientation(ORIENTATIONS[hud.rot]);
    hud.onRotate = () -> {
      hud.rot = (hud.rot + 1) % 4;
      prefs.edit().putInt("rot", hud.rot).apply();
      setRequestedOrientation(ORIENTATIONS[hud.rot]);
    };
    hud.setOnClickListener(v -> {
      hud.keepOn = !hud.keepOn;
      if (hud.keepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      Toast.makeText(this, hud.keepOn ? "画面を点けたままにします" : "画面は自動で消えます", Toast.LENGTH_SHORT).show();
    });
    hud.setOnLongClickListener(v -> {
      startActivity(new Intent(Settings.ACTION_SETTINGS));
      return true;
    });
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
  }

  @Override
  protected void onResume() {
    // 表示の確認用：adb shell am start -n jp.hasami.geolog/.MainActivity --es demo_city 県市 --es demo_town 町字（--ez demo_off true で戻す）
    String dc = getIntent().getStringExtra("demo_city");
    if (getIntent().getBooleanExtra("demo_off", false)) {
      State.demoPlace = null;
    } else if (dc != null) {
      {
        RGeo.Place p = new RGeo.Place();
        p.pref = "";
        p.city = dc;
        p.town = String.valueOf(getIntent().getStringExtra("demo_town"));
        p.chome = "";
        State.demoPlace = p;
      }
    }
    super.onResume();
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, 1);
    } else {
      startForegroundService(new Intent(this, LogService.class));
    }
    hud.invalidate();
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
    if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
      startForegroundService(new Intent(this, LogService.class));
    }
  }

  @Override
  public void onBackPressed() {
    // ホーム画面として使うので、戻るでは閉じない
  }
}
