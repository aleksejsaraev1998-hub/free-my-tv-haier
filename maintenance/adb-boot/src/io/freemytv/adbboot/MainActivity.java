package io.freemytv.adbboot;
import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import android.graphics.Color;
public final class MainActivity extends Activity {
 @Override public void onCreate(Bundle b){super.onCreate(b);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(60,40,60,40);root.setBackgroundColor(0xff091728);TextView title=new TextView(this);title.setTextColor(Color.WHITE);title.setTextSize(24);title.setText("Восстановление Wi-Fi ADB после загрузки\nТолько штатная отладка Android с сохранёнными сопряжениями.\nКлючи доступа и настройки авторизации не меняются.");root.addView(title);
 Button enable=new Button(this);enable.setText("Включить автоподключение");root.addView(enable);TextView status=new TextView(this);status.setTextColor(Color.WHITE);root.addView(status);enable.setOnClickListener(v->{BootReceiver.prefs(this).edit().putBoolean("enabled",true).apply();status.setText(BootReceiver.restore(this));});Button disable=new Button(this);disable.setText("Выключить автовосстановление");root.addView(disable);disable.setOnClickListener(v->{BootReceiver.prefs(this).edit().putBoolean("enabled",false).apply();status.setText("Выключено. Текущий сеанс ADB сохранён до ручного отключения / перезагрузки.");});status.setText("Автовосстановление: "+BootReceiver.prefs(this).getBoolean("enabled",false)+"\nПоследняя загрузка: "+BootReceiver.prefs(this).getString("last","ещё не проверено"));setContentView(root);enable.requestFocus();}
}
