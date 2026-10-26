package io.freemytv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.view.WindowManager;
import android.text.InputType;
import java.time.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reference-layout launcher: cached Canvas pages, bounded icons, event-driven updates. */
final class BentoView extends View implements BentoData.Listener, CalendarFeed.Listener {
    private static final int TEXT=0xFFF4F7FC,MUTED=0xFFB6C3D4,BLUE=0xFF38B9FF;
    private static final int HOME=0,APPS=1,INPUTS=2,SETTINGS=3,QUICK=4;
    private final Activity activity;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService loader=Executors.newSingleThreadExecutor();
    private final Paint fill=new Paint(3),stroke=new Paint(3),bitmapPaint=new Paint(3);
    private final TextPaint label=text(27,MUTED,Ui.regular),body=text(32,TEXT,Ui.regular),title=text(42,TEXT,Ui.regular),clock=text(64,TEXT,Ui.light),temp=text(78,TEXT,Ui.light),caption=text(25,TEXT,Ui.regular);
    private final RectF rect=new RectF();
    private final SharedPreferences board;
    private final BentoData data;
    private final CalendarFeed calendar;
    private final MonthPanel monthPanel=new MonthPanel();
    private final List<App> apps=new ArrayList<>(),catalog=new ArrayList<>(),filtered=new ArrayList<>();
    private List<String[]> candidates=new ArrayList<>();
    private List<HomeApps.Item> inputs=new ArrayList<>();
    private final List<String> rowLabels=new ArrayList<>(),rowValues=new ArrayList<>();
    private final List<StaticLayout> rowLayouts=new ArrayList<>();
    private final String[] sections={"Главный экран","Приложения и док","HDMI / Источники","Календари","Погода","Оформление","Производительность","Восстановление"};
    private final String[] filters={"Все приложения","Избранное"};
    private AlertDialog dialog;
    private ValueAnimator animator;
    private Bitmap background;
    private boolean running,destroyed,loading,reloadApps,calendarActive,sidebar=true;
    private int page=HOME,focus=8,shelfFocus,dockFirstVisible,gridFocus,gridFirstRow,inputFocus,section,rowFocus,filter;
    private float scale=1,offsetX,offsetY,focusProgress=1;
    private String time="",date="",city="",weatherRange="",network="",hdmiName="",hdmiState="",footer="";
    private StaticLayout weatherDescription,hdmiDescription,calendarFooter;
    private final Runnable tick=new Runnable(){public void run(){if(!running)return;updateClock();data.refresh();calendar.refresh(false);refreshSystem();rebuildBoard();handler.postDelayed(this,60000-System.currentTimeMillis()%60000);}};
    private final android.media.tv.TvInputManager.TvInputCallback inputCallback=new android.media.tv.TvInputManager.TvInputCallback(){
        @Override public void onInputStateChanged(String id,int state){refreshSystem();}
        @Override public void onInputAdded(String id){refreshSystem();}
        @Override public void onInputRemoved(String id){refreshSystem();}
        @Override public void onInputUpdated(String id){refreshSystem();}
    };
    private static final class App {String label,pkg;int kind;Bitmap image;StaticLayout caption;RectF box=new RectF();}
    BentoView(Activity a){
        super(a);activity=a;board=a.getSharedPreferences("bento-board",0);data=new BentoData(a);calendar=new CalendarFeed(a);
        stroke.setStyle(Paint.Style.STROKE);setFocusable(true);setContentDescription("Bento HOME");
        try(java.io.InputStream in=a.getAssets().open("bento-lake.jpg")){android.graphics.BitmapFactory.Options opt=new android.graphics.BitmapFactory.Options();opt.inPreferredConfig=Bitmap.Config.RGB_565;background=android.graphics.BitmapFactory.decodeStream(in,null,opt);}catch(Exception ignored){}
        updateClock();changed();refreshSystem();rebuildBoard();
    }
    private static TextPaint text(float size,int color,Typeface face){TextPaint p=new TextPaint(3);p.setTextSize(size);p.setColor(color);p.setTypeface(face);return p;}
    private static StaticLayout layout(String s,TextPaint p,int width,int lines){return StaticLayout.Builder.obtain(s,0,s.length(),p,width).setIncludePad(false).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build();}
    private static void drawLayout(Canvas c,StaticLayout l,float x,float y){if(l==null)return;c.save();c.translate(x,y);l.draw(c);c.restore();}
    void resume(){running=true;data.listen(this);calendar.listen(this);calendar.refresh(false);data.refresh();updateClock();refreshSystem();rebuildBoard();loadApps();handler.removeCallbacks(tick);handler.post(tick);try{activity.getSystemService(android.media.tv.TvInputManager.class).registerCallback(inputCallback,handler);}catch(Exception ignored){}}
    void pause(){running=false;handler.removeCallbacks(tick);data.listen(null);calendar.listen(null);if(animator!=null)animator.cancel();if(dialog!=null){dialog.dismiss();dialog=null;}try{activity.getSystemService(android.media.tv.TvInputManager.class).unregisterCallback(inputCallback);}catch(Exception ignored){}}
    void destroy(){pause();destroyed=true;data.close();calendar.close();loader.shutdownNow();}
    void reset(boolean animate){page=HOME;calendarActive=false;focus=8;animateFocus();}
    private void updateClock(){Date now=new Date();time=new SimpleDateFormat("HH:mm",Ui.locale()).format(now);date=new SimpleDateFormat("EEE, d MMM",Ui.locale()).format(now);invalidate();}
    @Override public void changed(){city=Prefs.city().isEmpty()?Ui.tr("Weather"):Prefs.city();weatherRange=data.weatherAt==0?Ui.tr("No weather data"):data.range+" · 24 ч"+(data.weatherFailed?" · "+Ui.tr("Cached"):"");weatherDescription=layout(data.condition,label,490,2);if(page==SETTINGS)buildRows();invalidate();}
    private void refreshSystem(){
        network="Нет сети";
        try{android.net.ConnectivityManager cm=activity.getSystemService(android.net.ConnectivityManager.class);android.net.NetworkCapabilities n=cm.getNetworkCapabilities(cm.getActiveNetwork());if(n!=null)network=n.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":n.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)?"Ethernet":"Сеть";}catch(Exception ignored){}
        inputs=InputCatalog.read(activity);inputFocus=Math.max(0,Math.min(inputFocus,inputs.size()-1));hdmiName="Источники";hdmiState="Нет сигнала";
        for(HomeApps.Item i:inputs)if(i.symbol.equals("hdmi")){hdmiName=i.label;hdmiState=InputCatalog.state(i);if(i.connected||i.label.contains(" — "))break;}
        hdmiDescription=layout(hdmiName,body,270,3);layoutHdmiState=layout(hdmiState,label,266,1);updateInputTitle();invalidate();
    }
    private void loadApps(){
        if(loading){reloadApps=true;return;}loading=true;
        loader.execute(()->{
            List<String[]> choices=HomeApps.shelfCandidates(activity);List<App> all=new ArrayList<>();
            for(String[] choice:choices)all.add(loadApp(choice));
            List<String> selected=Prefs.shelf();if(selected==null)selected=defaultDock(all);
            List<App> dock=new ArrayList<>();for(String pkg:selected)for(App a:all)if(pkg.equals(a.pkg)){dock.add(a);break;}
            App hdmi=new App();hdmi.kind=5;hdmi.label="HDMI";hdmi.caption=layout(hdmi.label,caption,174,2);dock.add(hdmi);
            App more=new App();more.kind=3;more.label="Все приложения";more.caption=layout(more.label,caption,174,2);dock.add(more);
            handler.post(()->{loading=false;if(destroyed)return;candidates=choices;catalog.clear();catalog.addAll(all);apps.clear();apps.addAll(dock);shelfFocus=Math.max(0,Math.min(shelfFocus,apps.size()-1));filterApps();layoutApps();invalidate();if(reloadApps){reloadApps=false;loadApps();}});
        });
    }
    private App loadApp(String[] choice){App a=new App();a.pkg=choice[0];a.label=choice[1];
        try{Drawable d=activity.getPackageManager().getApplicationIcon(a.pkg);a.image=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);d.setBounds(0,0,128,128);d.draw(new Canvas(a.image));}catch(Exception ignored){}
        a.caption=layout(a.label,caption,174,2);return a;
    }
    private List<String> defaultDock(List<App> all){List<String> result=new ArrayList<>();for(String hint:new String[]{"youtube","vk видео","wink","rutube","evo tv","twitch","zona"})for(App a:all)if(a.label.toLowerCase(Locale.ROOT).equals(hint)||a.label.toLowerCase(Locale.ROOT).startsWith(hint+" -")){if(!result.contains(a.pkg))result.add(a.pkg);break;}if(result.isEmpty())for(App a:all){if(a.label.startsWith("Free My TV"))continue;result.add(a.pkg);if(result.size()==7)break;}return result;}
    private List<String> dockPackages(){List<String> result=new ArrayList<>();for(App a:apps)if(a.kind==0)result.add(a.pkg);return result;}
    private void filterApps(){filtered.clear();List<String> saved=Prefs.shelf();if(saved==null)saved=dockPackages();for(App a:catalog)if(filter==0||saved.contains(a.pkg))filtered.add(a);gridFocus=Math.max(0,Math.min(gridFocus,filtered.size()-1));gridFirstRow=0;}
    private void layoutApps(){int visible=BentoLayout.DOCK_VISIBLE;if(shelfFocus<dockFirstVisible)dockFirstVisible=shelfFocus;if(shelfFocus>=dockFirstVisible+visible)dockFirstVisible=shelfFocus-visible+1;dockFirstVisible=Math.max(0,Math.min(dockFirstVisible,Math.max(0,apps.size()-visible)));for(int i=0;i<apps.size();i++){float x=104+(i-dockFirstVisible)*190;apps.get(i).box.set(x,798,x+144,942);}}
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){scale=Math.min(w/1920f,h/1080f);offsetX=(w-1920*scale)/2;offsetY=(h-1080*scale)/2;for(String symbol:new String[]{"wifi","bluetooth","settings","hdmi","apps","tv","sync","power_settings_new"})Symbols.warm(getContext(),symbol,64*scale);Symbols.warmWeather(getContext(),110*scale);layoutApps();}
    private void panel(Canvas c,float l,float t,float r,float b,boolean selected){rect.set(l,t,r,b);fill.setColor(selected?0xCC204C78:0xA81A283B);c.drawRoundRect(rect,22,22,fill);stroke.setStrokeWidth(selected?3:1);stroke.setColor(selected?BLUE:0x667F91A8);c.drawRoundRect(rect,22,22,stroke);}
    private void icon(Canvas c,String s,float x,float y,float size,int color){
        if(s.equals("hdmi")){stroke.setColor(color);stroke.setStrokeWidth(Math.max(2,size*.055f));rect.set(x-size*.45f,y-size*.25f,x+size*.45f,y+size*.25f);c.drawRoundRect(rect,size*.08f,size*.08f,stroke);c.drawLine(x-size*.25f,y+size*.28f,x+size*.25f,y+size*.28f,stroke);c.drawLine(x-size*.25f,y-size*.06f,x+size*.25f,y-size*.06f,stroke);return;}

        if(s.equals("wifi")){stroke.setColor(color);stroke.setStrokeWidth(3);for(int i=0;i<3;i++){float r=size*(.25f+i*.23f);rect.set(x-r,y-r*.7f,x+r,y+r*1.3f);c.drawArc(rect,220,100,false,stroke);}fill.setColor(color);c.drawCircle(x,y+size*.25f,2.5f,fill);}
        else if(s.equals("bluetooth")){stroke.setColor(color);stroke.setStrokeWidth(3);c.drawLine(x,y-size*.5f,x,y+size*.5f,stroke);c.drawLine(x,y-size*.5f,x+size*.3f,y-size*.22f,stroke);c.drawLine(x+size*.3f,y-size*.22f,x-size*.27f,y+size*.27f,stroke);c.drawLine(x-size*.27f,y-size*.27f,x+size*.3f,y+size*.22f,stroke);c.drawLine(x+size*.3f,y+size*.22f,x,y+size*.5f,stroke);}
        else Symbols.draw(getContext(),c,s,x,y,size,color);
    }
    private void centered(Canvas c,String s,float x,float y,TextPaint p){p.setTextAlign(Paint.Align.CENTER);c.drawText(s,x,y,p);p.setTextAlign(Paint.Align.LEFT);}
    private void home(Canvas c){
        c.drawText(time,82,112,clock);c.drawText(date,84,158,label);
        panel(c,1538,60,1654,146,focus==6);icon(c,"wifi",1596,94,32,network.equals("Нет сети")?MUTED:TEXT);centered(c,network,1596,131,label);
        panel(c,1674,60,1758,146,focus==7);icon(c,"bluetooth",1716,102,34,MUTED);
        panel(c,1778,60,1862,146,focus==0);icon(c,"settings",1820,102,36,TEXT);
        if(board.getBoolean("calendar-visible",true)){panel(c,72,212,1202,722,focus==2);monthPanel.draw(c,calendarActive);drawLayout(c,calendarFooter,104,681);}
        else {panel(c,72,212,1202,722,focus==2);c.drawText("Календарь скрыт",110,280,title);c.drawText("OK — настройки календаря",110,330,label);}
        panel(c,1230,212,1850,464,focus==1);c.drawText(city,1266,264,body);
        if(board.getBoolean("weather-visible",true)){c.drawText(data.temperature,1266,352,temp);if(data.weatherAt>0)icon(c,data.weatherSymbol,1760,326,100,TEXT);drawLayout(c,weatherDescription,1266,373);c.drawText(weatherRange,1266,444,label);}else c.drawText("Погода скрыта",1266,352,body);
        panel(c,1230,490,1542,722,focus==4);c.drawText("HDMI",1264,537,body);drawLayout(c,hdmiDescription,1264,556);label.setColor(hdmiState.equals("Подключено")?0xFF62E2AF:MUTED);drawLayout(c,layoutHdmiState,1264,677);label.setColor(MUTED);
        panel(c,1566,490,1850,722,focus==5);c.drawText("Настройки",1598,537,body);icon(c,"settings",1708,620,78,TEXT);
        panel(c,72,778,1850,1028,false);c.save();c.clipRect(82,762,1840,1038);
        for(int i=dockFirstVisible;i<Math.min(apps.size(),dockFirstVisible+BentoLayout.DOCK_VISIBLE);i++){App a=apps.get(i);appIcon(c,a,a.box.left,a.box.top,focus==8&&i==shelfFocus);}
        c.restore();
        if(focus==8&&!apps.isEmpty())centered(c,apps.get(shelfFocus).label,960,1065,label);
    }
    private StaticLayout layoutHdmiState;
    private void appIcon(Canvas c,App a,float x,float y,boolean selected){
        float grow=selected?1+.10f*focusProgress:1;c.save();c.scale(grow,grow,x+72,y+72);
        if(selected){rect.set(x-12,y-12,x+156,y+156);fill.setColor(0x4038B9FF);c.drawRoundRect(rect,28,28,fill);}
        panel(c,x,y,x+144,y+144,selected);
        if(a.image!=null){rect.set(x+10,y+10,x+134,y+134);c.drawBitmap(a.image,null,rect,bitmapPaint);}else icon(c,a.kind==5?"hdmi":"apps",x+72,y+72,66,TEXT);
        c.restore();drawLayout(c,a.caption,x-15,y+163);
    }
    private void pageHeader(Canvas c,String name,String hint){c.drawText(name,80,112,title);c.drawText(time,1670,108,body);c.drawText(hint,80,1036,label);}
    private void appPage(Canvas c){
        pageHeader(c,"Все приложения","OK — запуск · MENU — добавить / убрать из дока · Назад — главный экран");
        for(int i=0;i<filters.length;i++){panel(c,70,174+i*88,410,248+i*88,sidebar&&filter==i);c.drawText(filters[i],96,221+i*88,body);}
        if(filtered.isEmpty())c.drawText("В этой категории нет приложений",490,232,body);
        for(int i=gridFirstRow*6;i<Math.min(filtered.size(),(gridFirstRow+3)*6);i++){int col=i%6,row=i/6-gridFirstRow;appIcon(c,filtered.get(i),480+col*225,184+row*260,!sidebar&&gridFocus==i);}
        if(!sidebar&&!filtered.isEmpty()){c.save();c.clipRect(470,965,1840,1007);c.drawText(filtered.get(gridFocus).label,480,995,body);c.restore();}
    }
    private void inputPage(Canvas c){
        pageHeader(c,"HDMI / Источники","OK — переключить источник · Назад — главный экран");
        if(inputs.isEmpty()){c.drawText("Android TV не сообщил доступные источники",90,230,body);return;}
        int first=Math.max(0,inputFocus-5);
        for(int i=first;i<Math.min(inputs.size(),first+6);i++){HomeApps.Item a=inputs.get(i);float y=180+(i-first)*118;panel(c,76,y,976,y+98,i==inputFocus);icon(c,a.symbol,122,y+48,36,TEXT);c.save();c.clipRect(170,y+10,950,y+90);c.drawText(a.label,178,y+40,body);c.drawText(a.kind==HomeApps.INPUT?InputCatalog.state(a):"ТВ / Антенна",178,y+76,label);c.restore();}
        HomeApps.Item a=inputs.get(inputFocus);panel(c,1020,180,1840,890,false);icon(c,a.symbol,1430,360,150,TEXT);
        drawLayout(c,inputTitle,1070,520);c.drawText(a.kind==HomeApps.INPUT?InputCatalog.state(a):"ТВ / Антенна",1070,660,body);
        c.drawText("Данные Android TV / HDMI-CEC",1070,800,label);
    }
    private StaticLayout inputTitle;
    private void updateInputTitle(){inputTitle=layout(inputs.isEmpty()?"":inputs.get(inputFocus).label,title,700,3);}
    private void settingsPage(Canvas c){
        pageHeader(c,"Настройки Bento","← → — раздел / параметры · OK — изменить · Назад — главный экран");
        for(int i=0;i<sections.length;i++){panel(c,70,166+i*98,488,248+i*98,sidebar&&section==i);c.drawText(sections[i],96,216+i*98,body);}
        c.drawText(sections[section],548,200,title);
        for(int i=0;i<rowLabels.size();i++){float y=242+i*116;panel(c,538,y,1846,y+100,!sidebar&&rowFocus==i);drawLayout(c,rowLayouts.get(i),566,y+17);c.drawText(rowValues.get(i),566,y+81,label);}
    }
    private void quickPage(Canvas c){pageHeader(c,"Быстрые действия","OK — открыть системные параметры · Назад — главный экран");for(int i=0;i<rowLabels.size();i++){float y=200+i*140;panel(c,160,y,1760,y+116,rowFocus==i);c.drawText(rowLabels.get(i),210,y+47,body);c.drawText(rowValues.get(i),210,y+88,label);}}
    @Override protected void onDraw(Canvas c){c.drawColor(0xFF071322);c.save();c.translate(offsetX,offsetY);c.scale(scale,scale);if(background!=null&&!board.getBoolean("plain-background",false)){rect.set(0,0,1920,1080);c.drawBitmap(background,null,rect,bitmapPaint);}c.drawColor(page==HOME?0x40000B19:0xD0081322);if(page==HOME)home(c);else if(page==APPS)appPage(c);else if(page==INPUTS)inputPage(c);else if(page==SETTINGS)settingsPage(c);else quickPage(c);c.restore();}
    private void animateFocus(){if(animator!=null)animator.cancel();if(!board.getBoolean("animations",true)){focusProgress=1;invalidate();return;}focusProgress=0;animator=ValueAnimator.ofFloat(0,1);animator.setDuration(150);animator.addUpdateListener(v->{focusProgress=(float)v.getAnimatedValue();invalidate();});animator.start();}
    boolean onKey(KeyEvent e){int key=e.getKeyCode();boolean down=e.getAction()==KeyEvent.ACTION_DOWN;
        if(key==KeyEvent.KEYCODE_BACK){if(!down){if(calendarActive){calendarActive=false;rebuildBoard();}else if(page!=HOME){page=HOME;focus=8;invalidate();}else {focus=8;invalidate();}}return true;}
        if(key==KeyEvent.KEYCODE_MENU){if(!down){if(calendarActive)calendarMenu();else if(page==APPS&&!sidebar)toggleFavorite();else settings();}return true;}
        if(calendarActive&&(key==92||key==93)){if(down)changeDate(CalendarMonth.shiftMonth(monthPanel.selected,key==92?-1:1));return true;}
        if(key==23||key==66){if(!down&&!e.isCanceled())activate();return true;}
        if(key<19||key>22)return false;if(!down)return true;
        if(page==APPS){if(sidebar){if(key==19)filter=0;if(key==20)filter=1;filterApps();if(key==22&&!filtered.isEmpty())sidebar=false;}else{if(key==21&&gridFocus%6==0)sidebar=true;else gridFocus=Math.max(0,Math.min(filtered.size()-1,gridFocus+(key==21?-1:key==22?1:key==19?-6:6)));if(gridFocus/6<gridFirstRow)gridFirstRow=gridFocus/6;if(gridFocus/6>=gridFirstRow+3)gridFirstRow=gridFocus/6-2;}}
        else if(page==INPUTS){inputFocus=Math.max(0,Math.min(inputs.size()-1,inputFocus+(key==19?-1:key==20?1:0)));updateInputTitle();}
        else if(page==SETTINGS){if(sidebar){section=Math.max(0,Math.min(sections.length-1,section+(key==19?-1:key==20?1:0)));buildRows();if(key==22)sidebar=false;}else if(key==21)sidebar=true;else rowFocus=Math.max(0,Math.min(rowLabels.size()-1,rowFocus+(key==19?-1:key==20?1:0)));}
        else if(page==QUICK)rowFocus=Math.max(0,Math.min(rowLabels.size()-1,rowFocus+(key==19?-1:key==20?1:0)));
        else if(calendarActive){changeDate(monthPanel.selected.plusDays(key==21?-1:key==22?1:key==19?-7:7));return true;}
        else if(focus==8){if(key==21)shelfFocus=Math.max(0,shelfFocus-1);if(key==22)shelfFocus=Math.min(apps.size()-1,shelfFocus+1);if(key==19)focus=2;layoutApps();}
        else {focus=BentoLayout.next(focus,key);}
        animateFocus();return true;
    }
    @Override public boolean onTouchEvent(MotionEvent e){if(e.getAction()!=MotionEvent.ACTION_UP)return true;float x=(e.getX()-offsetX)/scale,y=(e.getY()-offsetY)/scale;
        if(page==HOME){if(x>=104&&x<=662&&y>=300&&y<654){LocalDate day=monthPanel.hit(x,y);if(day!=null){focus=2;calendarActive=true;changeDate(day);return true;}}for(int i=0;i<BentoLayout.TILES.length;i++){float[] r=BentoLayout.TILES[i];if(x>=r[0]&&x<r[2]&&y>=r[1]&&y<r[3]){focus=i;activate();return true;}}if(y>=778){int i=dockFirstVisible+(int)((x-104)/190);if(i>=0&&i<apps.size()){shelfFocus=i;focus=8;activate();}}}
        return true;
    }
    private void message(String s){Toast.makeText(activity,s,Toast.LENGTH_LONG).show();}
    private void open(HomeApps.Item item){if(!HomeApps.open(activity,item,this,0,0,getWidth(),getHeight()))message(Ui.tr("Could not open this item"));}
    private void system(String action){try{activity.startActivity(new Intent(action));}catch(Exception e){try{activity.startActivity(new Intent(Settings.ACTION_SETTINGS));}catch(Exception ignored){message("Системные настройки недоступны");}}}
    private void activate(){
        if(page==APPS){if(sidebar){sidebar=false;invalidate();}else if(!filtered.isEmpty())open(HomeApps.app(filtered.get(gridFocus).pkg,filtered.get(gridFocus).label));return;}
        if(page==INPUTS){if(!inputs.isEmpty())open(inputs.get(inputFocus));return;}
        if(page==SETTINGS){if(sidebar){sidebar=false;invalidate();}else settingAction();return;}
        if(page==QUICK){system(rowFocus==0?Settings.ACTION_SETTINGS:rowFocus==1?Settings.ACTION_WIFI_SETTINGS:rowFocus==2?Settings.ACTION_BLUETOOTH_SETTINGS:rowFocus==3?Settings.ACTION_VPN_SETTINGS:Settings.ACTION_DISPLAY_SETTINGS);return;}
        if(focus==2){if(!board.getBoolean("calendar-visible",true)){settings();section=3;buildRows();}else if(!calendar.configured)calendarSettings();else if(calendarActive)calendarEvents();else{calendarActive=true;rebuildBoard();}return;}
        if(focus==1){settings();section=4;buildRows();return;}
        if(focus==4){showInputs();return;}if(focus==0||focus==5){settings();return;}if(focus==6){system(Settings.ACTION_WIFI_SETTINGS);return;}if(focus==7){system(Settings.ACTION_BLUETOOTH_SETTINGS);return;}
        if(focus==8&&!apps.isEmpty()){App a=apps.get(shelfFocus);if(a.kind==5)showInputs();else if(a.kind==3)showAllApps();else open(HomeApps.app(a.pkg,a.label));}
    }
    private void settings(){page=SETTINGS;sidebar=true;section=0;rowFocus=0;calendarActive=false;buildRows();invalidate();}
    private void showInputs(){refreshSystem();page=INPUTS;inputFocus=0;updateInputTitle();invalidate();}
    private void showAllApps(){page=APPS;sidebar=false;filter=0;gridFocus=0;filterApps();invalidate();}
    private void row(String name,String value){rowLabels.add(name);rowValues.add(value);rowLayouts.add(layout(name,body,1240,1));}
    private String on(String key,boolean def){return board.getBoolean(key,def)?"Включено":"Выключено";}
    private void buildRows(){rowLabels.clear();rowValues.clear();rowLayouts.clear();
        if(page==QUICK){row("Настройки","Системные параметры Android TV");row("Сеть",network);row("Bluetooth","Состояние и устройства — в настройках Android TV");row("VPN","Подключения Android TV");row("Экран и питание","Доступные параметры телевизора");}
        else if(section==0){row("Показывать календарь",on("calendar-visible",true));row("Показывать погоду",on("weather-visible",true));row("Быстрые действия","Сеть, Bluetooth, VPN, системные параметры");}
        else if(section==1){row("Все приложения","OK — каталог; MENU в каталоге — избранное / док");row("Настроить док","Выбор приложений без ограничения в четыре иконки");row("Изменить порядок","Перемещение выбранного приложения в начало дока");}
        else if(section==2){row("HDMI / Источники","Подключения и названия из Android TV / CEC");row("Системные параметры HDMI", "Настройка CEC в меню телевизора");}
        else if(section==3){row("Календари и подписки","CalDAV и ICS; учётные данные хранятся на ТВ");row("Обновить календарь",calendar.failed?"Ошибка загрузки — проверьте подключение":"Загрузить события с сервера");row("Сегодня","Вернуться к текущему месяцу");}
        else if(section==4){row("Город и координаты",city);row("Обновить погоду",weatherRange);row("Показывать погоду",on("weather-visible",true));row("Источник данных","MET Norway · CC BY 4.0");}
        else if(section==5){row("Горный пейзаж",board.getBoolean("plain-background",false)?"Выключен — однотонный фон":"Включён");row("Анимация фокуса",on("animations",true));}
        else if(section==6){row("Анимация фокуса",on("animations",true));row("Режим отрисовки","По событиям; нет шейдеров и видеозаставок");row("Кэш данных","Сохранённые события и погода при отсутствии сети");}
        else {row("Выбор главного экрана","Открыть системные настройки HOME");row("Резервный Google Launcher","Открыть установленный резервный лаунчер");row("Системные параметры","Android TV");row("О Bento", "UI release 11 · календарь без записи на сервер");}
        rowFocus=Math.max(0,Math.min(rowFocus,rowLabels.size()-1));invalidate();
    }
    private void toggle(String key,boolean def){board.edit().putBoolean(key,!board.getBoolean(key,def)).apply();buildRows();}
    private void settingAction(){
        if(section==0){if(rowFocus==0)toggle("calendar-visible",true);else if(rowFocus==1)toggle("weather-visible",true);else{page=QUICK;rowFocus=0;refreshSystem();buildRows();}}
        else if(section==1){if(rowFocus==0)showAllApps();else if(rowFocus==1)editShelf();else reorderShelf();}
        else if(section==2){if(rowFocus==0)showInputs();else system(Settings.ACTION_SETTINGS);}
        else if(section==3){if(rowFocus==0)calendarSettings();else if(rowFocus==1){calendar.refresh(true);message("Обновление календаря запрошено");}else{changeDate(LocalDate.now());page=HOME;focus=2;}}
        else if(section==4){if(rowFocus==0)weatherSettings();else if(rowFocus==1){data.refresh(true);message("Обновление погоды запрошено");}else if(rowFocus==2)toggle("weather-visible",true);else message("MET Norway · https://api.met.no · CC BY 4.0");}
        else if(section==5)toggle(rowFocus==0?"plain-background":"animations",rowFocus!=0);
        else if(section==6){if(rowFocus==0)toggle("animations",true);}
        else if(section==7){if(rowFocus==0)system(Settings.ACTION_HOME_SETTINGS);else if(rowFocus==1)open(HomeApps.app("com.google.android.tvlauncher","Google TV"));else if(rowFocus==2)system(Settings.ACTION_SETTINGS);}
        invalidate();
    }
    private void toggleFavorite(){if(filtered.isEmpty())return;App a=filtered.get(gridFocus);List<String> saved=Prefs.shelf();if(saved==null)saved=dockPackages();List<String> next=new ArrayList<>(saved);if(next.remove(a.pkg))message("Убрано из дока: "+a.label);else{next.add(a.pkg);message("Добавлено в док: "+a.label);}Prefs.setShelf(next);loadApps();}
    private void editShelf(){List<String> saved=Prefs.shelf();if(saved==null)saved=dockPackages();List<String> selection=new ArrayList<>(saved);boolean[] checked=new boolean[candidates.size()];String[] names=new String[checked.length];for(int i=0;i<names.length;i++){names[i]=candidates.get(i)[1];checked[i]=selection.contains(candidates.get(i)[0]);}dialog=new NightDialogs.Builder(activity).setTitle("Приложения в доке").setMultiChoiceItems(names,checked,(d,i,on)->{String pkg=candidates.get(i)[0];if(on)selection.add(pkg);else selection.remove(pkg);}).setPositiveButton(Ui.tr("Save"),(d,n)->{Prefs.setShelf(selection);loadApps();}).setNegativeButton(Ui.tr("Cancel"),null).show();}
    private void reorderShelf(){List<String> saved=Prefs.shelf();if(saved==null)saved=dockPackages();final List<String> order=new ArrayList<>(saved);List<String> names=new ArrayList<>();for(String pkg:order){String name=pkg;for(App a:catalog)if(pkg.equals(a.pkg))name=a.label;names.add(name);}dialog=new NightDialogs.Builder(activity).setTitle("Переместить в начало дока").setItems(names.toArray(new String[0]),(d,i)->{String pkg=order.remove(i);order.add(0,pkg);Prefs.setShelf(order);loadApps();}).show();}
    private void weatherSettings(){LinearLayout form=new LinearLayout(activity);form.setOrientation(1);form.setPadding(24,12,24,12);EditText name=calendarField(form,"Город",Prefs.city(),InputType.TYPE_CLASS_TEXT);EditText lat=calendarField(form,"Широта",Prefs.latitude(),InputType.TYPE_CLASS_TEXT);EditText lon=calendarField(form,"Долгота",Prefs.longitude(),InputType.TYPE_CLASS_TEXT);dialog=new NightDialogs.Builder(activity).setTitle("Местоположение погоды").setView(form).setPositiveButton(Ui.tr("Save"),null).setNegativeButton(Ui.tr("Cancel"),null).create();dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{double la=Double.parseDouble(lat.getText().toString()),lo=Double.parseDouble(lon.getText().toString());if(!Double.isFinite(la)||!Double.isFinite(lo)||Math.abs(la)>90||Math.abs(lo)>180)throw new Exception();Prefs.setCity(name.getText().toString().trim(),Double.toString(la),Double.toString(lo));data.refresh(true);changed();dialog.dismiss();}catch(Exception ex){message("Проверьте широту и долготу");}});}
    @Override public void calendarChanged(){if(!destroyed)rebuildBoard();}
    private void changeDate(LocalDate date){if(date.getYear()<1970||date.getYear()>2100)return;monthPanel.select(date);if(!calendar.month.equals(monthPanel.month))calendar.setMonth(monthPanel.month);else rebuildBoard();}
    private void rebuildBoard(){String empty=!calendar.configured?Ui.tr("Connect your calendar in Settings"):calendar.activeCount==0?Ui.tr("Choose visible calendars"):calendar.loaded?Ui.tr("No events on this day"):calendar.failed?Ui.tr("Calendar could not be loaded"):Ui.tr("Waiting for calendar data");monthPanel.update(calendar.events,empty);footer=calendarActive?"← → ↑ ↓ — день · OK — события · MENU — месяц / подписки":"OK — выбрать день · MENU — настройки";if(calendar.failed)footer="Ошибка обновления"+(calendar.updated>0?" · показаны сохранённые события":"");calendarFooter=layout(footer,label,1060,1);layoutHdmiState=layout(hdmiState,label,266,1);invalidate();}
    private void calendarMenu() {
        dialog=new NightDialogs.Builder(activity).setTitle(Ui.tr("Calendar"))
            .setItems(new String[]{Ui.tr("Today"),Ui.tr("Previous month"),Ui.tr("Next month"),Ui.tr("Refresh calendar"),Ui.tr("Calendars")},(d,index)->{
                if(index==0)changeDate(LocalDate.now());else if(index==1)changeDate(CalendarMonth.shiftMonth(monthPanel.selected,-1));else if(index==2)changeDate(CalendarMonth.shiftMonth(monthPanel.selected,1));else if(index==3)calendar.refresh(true);else calendarSettings();
            }).show();
    }
    private void calendarEvents() {
        List<CalendarProtocol.Event> events=monthPanel.events();LinearLayout list=new LinearLayout(activity);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(20,8,20,8);
        if(events.isEmpty()){android.widget.TextView t=new android.widget.TextView(activity);t.setText(calendar.loaded?Ui.tr("No events on this day"):Ui.tr("Waiting for calendar data"));list.addView(t);}
        for(CalendarProtocol.Event e:events) {
            android.widget.TextView t=new android.widget.TextView(activity);String times=e.allDay?Ui.tr("All day"):new SimpleDateFormat("HH:mm",Ui.locale()).format(new Date(e.start))+"–"+new SimpleDateFormat("HH:mm",Ui.locale()).format(new Date(e.end));
            t.setText(times+" · "+e.sourceName+"\n"+(e.title.isEmpty()?Ui.tr("Busy"):e.title));t.setPadding(18,14,18,14);t.setFocusable(true);t.setBackground(NightDialogs.selector());list.addView(t);
        }
        ScrollView scroll=new ScrollView(activity);scroll.addView(list);dialog=new NightDialogs.Builder(activity).setTitle(monthPanel.selected.toString()).setView(scroll).setPositiveButton(Ui.tr("Close"),null).show();
        dialog.getWindow().setLayout(Math.round(getWidth()*.72f),Math.round(getHeight()*.76f));
    }
    private EditText calendarField(LinearLayout form,String hint,String value,int type) {
        EditText input=new EditText(activity);input.setSingleLine(true);input.setHint(hint);input.setText(value);input.setInputType(type);input.setSaveEnabled(false);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2048)});form.addView(input,new LinearLayout.LayoutParams(-1,-2));return input;
    }
    private boolean calendarReady() {if(!calendar.ready||calendar.isBusy()){message(Ui.tr("Calendar is busy; try again shortly"));return false;}return true;}
    private void calendarSettings() {
        if(!calendar.ready){message(Ui.tr("Calendar is busy; try again shortly"));return;}
        dialog=new NightDialogs.Builder(activity).setTitle(Ui.tr("Calendars")).setItems(new String[]{Ui.tr("Visible calendars"),Ui.tr("Find calendars on server"),Ui.tr("Add calendar by URL"),Ui.tr("Manage calendars")},(d,index)->{
            if(!calendarReady())return;
            if(index==0)chooseCalendars();else if(index==1)findCalendars();else if(index==2)addCalendar();else manageCalendars();
        }).show();
    }
    private void chooseCalendars() {
        String[] names=new String[calendar.sources.size()];boolean[] checked=new boolean[names.length];Set<String> selected=new java.util.HashSet<>();
        for(int i=0;i<names.length;i++){CalendarFeed.Source source=calendar.sources.get(i);names[i]=source.name;checked[i]=source.enabled;if(source.enabled)selected.add(source.id);}
        dialog=new NightDialogs.Builder(activity).setTitle(Ui.tr("Visible calendars")).setMultiChoiceItems(names,checked,(d,n,on)->{String id=calendar.sources.get(n).id;if(on)selected.add(id);else selected.remove(id);})
            .setPositiveButton(Ui.tr("Save"),(d,n)->{if(calendarReady())calendar.choose(selected);}).setNegativeButton(Ui.tr("Cancel"),null).show();
    }
    private void addCalendar() {
        if(calendar.sources.size()>=12){message(Ui.tr("Calendar limit reached"));return;}
        dialog=new NightDialogs.Builder(activity).setTitle(Ui.tr("Add calendar by URL")).setItems(new String[]{"CalDAV",Ui.tr("ICS subscription")},(d,n)->{CalendarFeed.Source source=new CalendarFeed.Source();source.ics=n==1;source.color=CalendarFeed.COLORS[calendar.sources.size()%CalendarFeed.COLORS.length];if(source.ics)source.url="";editCalendar(source);}).show();
    }
    private void manageCalendars() {
        String[] names=new String[calendar.sources.size()];for(int i=0;i<names.length;i++)names[i]=calendar.sources.get(i).name;
        dialog=new NightDialogs.Builder(activity).setTitle(Ui.tr("Manage calendars")).setItems(names,(d,n)->sourceMenu(calendar.sources.get(n))).show();
    }
    private void sourceMenu(CalendarFeed.Source source) {
        dialog=new NightDialogs.Builder(activity).setTitle(source.name).setItems(new String[]{Ui.tr("Edit connection"),Ui.tr("Calendar color"),Ui.tr("Remove calendar")},(d,index)->{
            if(!calendarReady())return;if(index==0)editCalendar(source.copy());else if(index==1) {
                dialog=new NightDialogs.Builder(activity).setTitle(Ui.tr("Calendar color")).setItems(new String[]{Ui.tr("Red"),Ui.tr("Purple"),Ui.tr("Teal"),Ui.tr("Yellow"),Ui.tr("Green"),Ui.tr("Blue")},(x,n)->{CalendarFeed.Source next=source.copy();next.color=CalendarFeed.COLORS[n];calendar.save(next);}).show();
            }else dialog=new NightDialogs.Builder(activity).setTitle(Ui.tr("Remove calendar")).setMessage(Ui.tr("Remove this calendar from the TV? Server events stay unchanged."))
                .setPositiveButton(Ui.tr("Remove calendar"),(x,n)->calendar.remove(source.id)).setNegativeButton(Ui.tr("Cancel"),null).show();
        }).show();
    }
    private void editCalendar(CalendarFeed.Source source) {
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(24,12,24,12);form.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        EditText name=calendarField(form,Ui.tr("Calendar name"),source.name,InputType.TYPE_CLASS_TEXT);
        EditText address=calendarField(form,source.ics?Ui.tr("HTTPS / webcal ICS URL"):Ui.tr("CalDAV URL ({login} uses your login)"),source.url,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        EditText login=calendarField(form,source.ics?Ui.tr("Login (optional for public ICS)"):Ui.tr("Calendar login"),source.user,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText secret=calendarField(form,source.hasPassword()?Ui.tr("Password (blank keeps saved password)"):Ui.tr("Calendar password"),"",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);dialog=new NightDialogs.Builder(activity).setTitle(Ui.tr("Calendar settings")).setView(scroll).setPositiveButton(Ui.tr("Save"),null).setNegativeButton(Ui.tr("Cancel"),null).create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.show();final AlertDialog editor=dialog;
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!calendarReady())return;String user=login.getText().toString().trim(),password=secret.getText().toString(),url=address.getText().toString().trim().replace("{login}",android.net.Uri.encode(user));
            try{if(source.ics)url=CalendarProtocol.resource(url).toString();else CalendarProtocol.endpoint(url);
                if(user.contains(":")||!source.ics&&user.isEmpty()||!user.isEmpty()&&password.isEmpty()&&(!source.hasPassword()||!user.equals(source.user)||!url.equals(source.url)))throw new Exception();
            }catch(Exception e){message(Ui.tr("Check HTTPS URL, login and password"));return;}
            CalendarFeed.Source next=source.copy();next.name=name.getText().toString().trim();if(next.name.isEmpty())next.name=source.ics?"ICS":"CalDAV";next.url=url;next.user=user;next.password=user.isEmpty()?"":password.isEmpty()?source.password:password;calendar.save(next);secret.setText("");editor.dismiss();
        });
    }
    private void findCalendars() {
        List<CalendarFeed.Source> accounts=new ArrayList<>();for(CalendarFeed.Source source:calendar.sources)if(!source.ics&&!source.user.isEmpty())accounts.add(source);
        if(accounts.isEmpty()){message(Ui.tr("Connect a CalDAV account first"));addCalendar();return;}
        String[] names=new String[accounts.size()];for(int i=0;i<names.length;i++)names[i]=accounts.get(i).name;
        dialog=new NightDialogs.Builder(activity).setTitle(Ui.tr("Find calendars on server")).setItems(names,(d,n)->{
            CalendarFeed.Source account=accounts.get(n);calendar.discover(account,(found,error)->{
                if(destroyed||!running)return;if(!error.isEmpty()){message(CalendarStrings.translate(error));return;}if(found.isEmpty()){message(Ui.tr("No calendars found; add a URL manually"));return;}
                String[] labels=new String[found.size()];boolean[] checks=new boolean[labels.length];Set<Integer> selected=new java.util.HashSet<>();for(int i=0;i<labels.length;i++)labels[i]=found.get(i).name;
                dialog=new NightDialogs.Builder(activity).setTitle(Ui.tr("Add selected calendars")).setMultiChoiceItems(labels,checks,(x,index,on)->{if(on)selected.add(index);else selected.remove(index);})
                    .setPositiveButton(Ui.tr("Add selected calendars"),(x,index)->{if(!calendarReady())return;List<CalendarProtocol.Collection> chosen=new ArrayList<>();for(int i:selected)chosen.add(found.get(i));calendar.addDiscovered(account,chosen);})
                    .setNegativeButton(Ui.tr("Cancel"),null).show();
            });
        }).show();
    }
}
