package io.freemytv.adbboot;
import android.content.*;
import android.provider.Settings;
import android.content.pm.PackageManager;
/** One bounded boot operation; no server, network permission, root, or TCP/5555. */
public final class BootReceiver extends BroadcastReceiver {
 static android.content.SharedPreferences prefs(Context c){return c.createDeviceProtectedStorageContext().getSharedPreferences("boot",0);}
 static String restore(Context c){
  if(!prefs(c).getBoolean("enabled",false))return "Автовосстановление выключено";
  if(c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")!=PackageManager.PERMISSION_GRANTED)return "Нет разрешения WRITE_SECURE_SETTINGS";
  if(Settings.Global.getInt(c.getContentResolver(),"development_settings_enabled",0)!=1)return "Режим разработчика выключен владельцем";
  try {boolean ok=Settings.Global.putInt(c.getContentResolver(),"adb_wifi_enabled",1);return ok?"Беспроводная отладка включена":"Android отклонил настройку";}
  catch(SecurityException e){return "Нет разрешения на изменение настройки";}
 }
 @Override public void onReceive(Context c,Intent intent){
  if(!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())&&!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction()))return;
  final PendingResult pending=goAsync();
  new Thread(()->{try{String status=restore(c);prefs(c).edit().putString("last",status).putLong("at",System.currentTimeMillis()).apply();}finally{pending.finish();}},"adb-boot-once").start();
 }
}
