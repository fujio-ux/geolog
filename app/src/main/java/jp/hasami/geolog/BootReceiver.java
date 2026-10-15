package jp.hasami.geolog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 電源を入れたら記録を始める */
public class BootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context ctx, Intent intent) {
    if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
      ctx.startForegroundService(new Intent(ctx, LogService.class));
    }
  }
}
