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

/** A single, quiet Canvas. All text layout, package queries and bitmap scaling happen outside onDraw. */
final class BentoView extends View implements BentoData.Listener, CalendarFeed.Listener {
    private static final int BG=0xFF101114, TILE=0xFF1E2026, EDGE=0xFF2C2F37, FOCUSED=0xFF262931;
    private static final int TEXT=0xFF8F8B84, MUTED=0xFF67645F, DIM=0xFF4A4845, UP=0xFF5E8466, DOWN=0xFF9C6660;
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final TextPaint label = text(28,MUTED,Ui.regular), body = text(34,TEXT,Ui.regular);
    private final TextPaint number = text(48,TEXT,Ui.light), clock = text(170,TEXT,Ui.thin);
    private final TextPaint doneText = text(34,DIM,Ui.regular), timeText = text(28,MUTED,Ui.regular);
    private final Rect glyph = new Rect();
    private final RectF scratch = new RectF();
    private final RectF[] tiles = new RectF[8];
    private final List<App> apps = new ArrayList<>();
    private final List<App> quickApps = new ArrayList<>();
    private final MonthPanel monthPanel=new MonthPanel();
    private final TextPaint quickLabel=text(24,TEXT,Ui.regular);
    private boolean calendarActive;private int quickFocus;
    private List<String[]> candidates = new ArrayList<>();
    private List<HomeApps.Item> inputs = new ArrayList<>();
    private final SharedPreferences board;
    private final BentoData data;
    private final CalendarFeed calendar;
    private AlertDialog dialog;
    private ValueAnimator animator;
    private boolean running, destroyed, loading,reloadApps;
    private int loadedVersion=-1, focus=2, shelfFocus, hiddenTasks;
    private float scale=1, offsetX, offsetY, focusProgress=1;
    private long minute=-1;
    private String date1="",date2="",hours="",minutes="",boardFooter="",weatherRange="",rateFooter="",city="";
    private int hourLeft,hourTop,minuteLeft,minuteTop;
    private StaticLayout calendarFooter;
    private final String[] headings = {"", "", Ui.tr("Calendar"), Ui.tr("Live TV"), Ui.tr("HDMI Inputs"), Ui.tr("Settings"), Ui.tr("Exchange rates · RUB"), Ui.tr("Quick launch")};
    private final String[] symbols = {"","","","live_tv","hdmi","settings","",""};
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            updateClock();
            data.refresh();
            calendar.refresh(false);
            rebuildBoard();
            handler.postDelayed(this,60000-System.currentTimeMillis()%60000);
        }
    };
    private static final class App {
        String label,pkg; int kind; Bitmap image; float dim=.65f; RectF rect=new RectF(); StaticLayout text;
    }
    BentoView(Activity a) {
        super(a); activity=a;
        board=a.getSharedPreferences("bento-board",0);
        data=new BentoData(a);
        calendar=new CalendarFeed(a);
        for(int i=0;i<tiles.length;i++) {float[] r=BentoLayout.TILES[i];tiles[i]=new RectF(r[0],r[1],r[2],r[3]);}
        border.setStyle(Paint.Style.STROKE);
        doneText.setStrikeThruText(true);
        setFocusable(true); setContentDescription(Ui.tr("Bento home screen"));
        updateClock(); rebuildBoard(); changed();
    }
    private static TextPaint text(float size,int color,Typeface face) {
        TextPaint p=new TextPaint(Paint.ANTI_ALIAS_FLAG);p.setTextSize(size);p.setColor(color);p.setTypeface(face);return p;
    }
    private static StaticLayout layout(String value,TextPaint paint,int width,int maxLines) {
        // Short Russian function words stay with the following word.
        value=value.replaceAll("(?iu)(?<!\\p{L})([а-яё]{1,2}) +", "$1\u00a0");
        return StaticLayout.Builder.obtain(value,0,value.length(),paint,width).setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END).build();
    }
    void resume() {
        running=true;data.listen(this);calendar.listen(this);calendar.refresh(false);data.refresh();updateClock();rebuildBoard();
        handler.removeCallbacks(tick);handler.post(tick);
        // Returning from Google Play may have changed installed apps without changing preferences.
        loadApps();
    }
    void pause() {
        running=false;handler.removeCallbacks(tick);data.listen(null);calendar.listen(null);
        if(animator!=null) animator.cancel();
        if(dialog!=null) {dialog.dismiss();dialog=null;}
    }
    void destroy() {pause();destroyed=true;data.close();calendar.close();loader.shutdownNow();}
    void reset(boolean animate) { focus=2;animateFocus(); }
    @Override public void changed() {
        city=Prefs.city().isEmpty()?Ui.tr("Weather"):Prefs.city();
        weatherRange=data.weatherAt==0?Ui.tr("No weather data"):data.range+" · "+Ui.tr("24 h");
        rateFooter=data.ratesAt==0?Ui.tr("No exchange rate data"):Ui.tr("CBR date")+" "+data.ratesDate;
        if(data.ratesFailed && data.ratesAt!=0) rateFooter+=" · "+Ui.tr("Cached");
        if(data.weatherFailed && data.weatherAt!=0) weatherRange+=" · "+Ui.tr("Cached");
        invalidate();
    }
    private void updateClock() {
        long now=System.currentTimeMillis(), current=now/60000;
        if(current==minute) return;minute=current;
        Date date=new Date(now);
        date1=new SimpleDateFormat("EEEE",Ui.locale()).format(date);
        date2=new SimpleDateFormat("d MMMM",Ui.locale()).format(date);
        hours=new SimpleDateFormat("HH",Ui.locale()).format(date);minutes=new SimpleDateFormat("mm",Ui.locale()).format(date);
        clock.getTextBounds(hours,0,hours.length(),glyph);hourLeft=glyph.left;hourTop=glyph.top;
        clock.getTextBounds(minutes,0,minutes.length(),glyph);minuteLeft=glyph.left;minuteTop=glyph.top;
        invalidate();
    }
    private void loadApps() {
        if(loading){reloadApps=true;return;}loading=true; final int version=Prefs.version;
        loader.execute(()->{
            List<String[]> choices=HomeApps.shelfCandidates(activity);
            List<String> selected=Prefs.shelf();
            if(selected==null) selected=HomeApps.shelfPackages(activity);
            List<App> result=new ArrayList<>();
            for(String pkg:selected) {
                if(result.size()>=4) break;
                if(HomeApps.launchIntent(activity.getPackageManager(),pkg)==null) continue;
                result.add(loadApp(pkg,choices));
            }
            List<App> quick=new ArrayList<>();JSONArray savedQuick;
            try{savedQuick=new JSONArray(board.getString("quick-apps","[]"));}catch(Exception e){savedQuick=new JSONArray();}
            for(int i=0;i<4;i++) {String pkg=savedQuick.optString(i);App app;
                if(!pkg.isEmpty()&&HomeApps.launchIntent(activity.getPackageManager(),pkg)!=null)app=loadApp(pkg,choices);
                else {app=new App();app.kind=4;app.label=Ui.tr("Add app");}
                int col=i%2,row=i/2;app.rect.set(1576+144*col,406+174*row,1696+144*col,556+174*row);app.text=layout(app.label,quickLabel,108,2);quick.add(app);
            }
            App browser=new App();browser.kind=1;browser.label=Ui.tr("Browser");result.add(browser);
            App vpn=new App();vpn.kind=2;vpn.label="VPN";result.add(vpn);
            App all=new App();all.kind=3;all.label=Ui.tr("All Apps");result.add(all);
            List<HomeApps.Item> found=HomeApps.allInputs(activity);
            handler.post(()->{
                loading=false;if(destroyed) return;
                candidates=choices;inputs=found;apps.clear();apps.addAll(result);quickApps.clear();quickApps.addAll(quick);loadedVersion=version;
                shelfFocus=Math.min(shelfFocus,apps.size()-1);layoutApps();invalidate();if(reloadApps){reloadApps=false;loadApps();}
            });
        });
    }
    private App loadApp(String pkg,List<String[]> choices) {
        App app=new App(); app.pkg=pkg;app.kind=0;app.label=pkg;
        for(String[] c:choices) if(c[0].equals(pkg)) app.label=c[1];
        try {
            Drawable drawable=activity.getPackageManager().getApplicationBanner(pkg);
            if(drawable==null) drawable=activity.getPackageManager().getApplicationIcon(pkg);
            int dw=Math.max(1,drawable.getIntrinsicWidth()),dh=Math.max(1,drawable.getIntrinsicHeight());
            float k=Math.min(240f/dw,96f/dh);
            int w=Math.max(1,Math.round(dw*k)),h=Math.max(1,Math.round(dh*k));
            app.image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            drawable.setBounds(0,0,w,h);drawable.draw(new Canvas(app.image));
            long brightness=0;int count=0;
            for(int y=0;y<h;y+=Math.max(1,h/16)) for(int x=0;x<w;x+=Math.max(1,w/16)) {
                int color=app.image.getPixel(x,y);
                if(Color.alpha(color)<32) continue;
                brightness+=(Color.red(color)*2126L+Color.green(color)*7152L+Color.blue(color)*722L)/10000;count++;
            }
            if(count>0) app.dim=1f-.58f*(brightness/(255f*count));
        } catch(Exception ignored) {}
        app.text=layout(app.label,label,220,1);return app;
    }
    private void layoutApps() {
        int n=apps.size();if(n==0)return;
        float width=(1760-24*(n-1))/n;
        for(int i=0;i<n;i++) {App app=apps.get(i);app.rect.set(80+i*(width+24),888,80+i*(width+24)+width,1012);app.text=layout(app.label,label,(int)width-20,1);}
    }
    @Override protected void onSizeChanged(int w,int h,int ow,int oh) {
        scale=Math.min(w/1920f,h/1080f);offsetX=(w-1920*scale)/2;offsetY=(h-1080*scale)/2;
        for(String s:symbols) if(!s.isEmpty()) Symbols.warm(getContext(),s,48*scale);
        Symbols.warm(getContext(),"sync",36*scale);Symbols.warm(getContext(),"apps",36*scale);
        layoutApps();
    }
    private void tile(Canvas c,int id) {
        RectF r=tiles[id];float grow=focus==id?1+.04f*focusProgress:1;
        c.save();c.scale(grow,grow,r.centerX(),r.centerY());
        fill.setColor(focus==id?FOCUSED:TILE);c.drawRoundRect(r,36,36,fill);
        border.setColor(focus==id?TEXT:EDGE);border.setStrokeWidth(focus==id?2:1);c.drawRoundRect(r,36,36,border);
        // Every tile clips its content to the 40px inner rectangle.
        c.clipRect(r.left+40,r.top+40,r.right-40,r.bottom-40);
        if(id==0) {
            c.drawText(date1,80,108,label);c.drawText(date2,80,146,label);
            c.drawText(hours,80-hourLeft,190-hourTop,clock);
            c.drawText(minutes,80-minuteLeft,350-minuteTop,clock);
        } else if(id==1) {
            c.drawText(city,80,644,label);
            c.drawText(data.temperature,80,704,number);
            c.drawText(weatherRange,80,740,label);
            c.drawText(data.condition,80,772,label);
        } else if(id==2) {
            monthPanel.draw(c,calendarActive);
            drawLayout(c,calendarFooter,r.left+40,708);
        } else if(id>=3&&id<=5) {
            // Draw icons at logical size, prewarmed for the physical UI scale.
            Symbols.draw(getContext(),c,symbols[id],r.centerX(),r.centerY()-26,48,TEXT);
            body.setTextAlign(Paint.Align.CENTER);c.drawText(headings[id],r.centerX(),r.centerY()+52,body);body.setTextAlign(Paint.Align.LEFT);
        } else if(id==6) {
            c.drawText(data.ratesAt==0?headings[id]:rateFooter,r.left+40,108,label);
            c.drawText("$",r.left+40,158,body);c.drawText(data.usd,r.left+83,158,number);arrow(c,data.usdDirection,r.right-54,158);
            c.drawText("€",r.left+40,219,body);c.drawText(data.eur,r.left+83,219,number);arrow(c,data.eurDirection,r.right-54,219);
        } else {
            c.drawText(headings[id],r.left+40,376,label);
            for(int i=0;i<quickApps.size();i++) {
                App app=quickApps.get(i);RectF cell=app.rect;fill.setColor(focus==7&&quickFocus==i?FOCUSED:0xFF24262C);c.drawRoundRect(cell,18,18,fill);
                if(focus==7&&quickFocus==i){border.setColor(TEXT);border.setStrokeWidth(2);c.drawRoundRect(cell,18,18,border);}
                c.save();c.clipRect(cell.left+6,cell.top+6,cell.right-6,cell.bottom-6);
                if(app.image!=null){float k=Math.min(100f/app.image.getWidth(),60f/app.image.getHeight());float w=app.image.getWidth()*k,h=app.image.getHeight()*k;
                    scratch.set(cell.centerX()-w/2,cell.top+12,cell.centerX()+w/2,cell.top+12+h);bitmapPaint.setAlpha(focus==7&&quickFocus==i?255:Math.round(255*app.dim));c.drawBitmap(app.image,null,scratch,bitmapPaint);
                }else {number.setTextAlign(Paint.Align.CENTER);c.drawText("+",cell.centerX(),cell.top+65,number);number.setTextAlign(Paint.Align.LEFT);}
                drawLayout(c,app.text,cell.left+6,cell.top+82);c.restore();
            }
        }
        c.restore();
    }
    private void arrow(Canvas c,int direction,float x,float y) {
        label.setColor(direction>0?UP:direction<0?DOWN:MUTED);c.drawText(direction>0?"↗":direction<0?"↘":"—",x,y,label);label.setColor(MUTED);
    }
    private static void drawLayout(Canvas c,StaticLayout value,float x,float y) {c.save();c.translate(x,y);value.draw(c);c.restore();}
    @Override protected void onDraw(Canvas c) {
        c.drawColor(BG);c.save();c.translate(offsetX,offsetY);c.scale(scale,scale);
        for(int i=0;i<tiles.length;i++) tile(c,i);
        fill.setColor(TILE);scratch.set(40,860,1880,1040);c.drawRoundRect(scratch,36,36,fill);
        border.setStrokeWidth(1);border.setColor(EDGE);c.drawRoundRect(scratch,36,36,border);
        for(int i=0;i<apps.size();i++) {
            App app=apps.get(i);RectF r=app.rect;boolean selected=focus==8&&shelfFocus==i;
            float grow=selected?1+.06f*focusProgress:1;
            c.save();c.scale(grow,grow,r.centerX(),r.centerY());
            fill.setColor(selected?FOCUSED:0xFF24262C);c.drawRoundRect(r,22,22,fill);
            if(selected) {border.setColor(TEXT);border.setStrokeWidth(2);c.drawRoundRect(r,22,22,border);}
            c.clipRect(r.left+8,r.top+8,r.right-8,r.bottom-8);
            bitmapPaint.setAlpha(Math.round(255*(selected?app.dim+(1-app.dim)*focusProgress:app.dim)));
            if(app.image!=null) {
                float k=Math.min((r.width()-24)/app.image.getWidth(),64f/app.image.getHeight());
                float w=app.image.getWidth()*k,h=app.image.getHeight()*k;
                scratch.set(r.centerX()-w/2,r.top+12,r.centerX()+w/2,r.top+12+h);c.drawBitmap(app.image,null,scratch,bitmapPaint);
                drawLayout(c,app.text,r.left+10,r.bottom-36);
            } else {
                if(app.kind==1) BrowserShortcut.draw(c,r.centerX(),r.centerY()-20,38,selected?TEXT:MUTED);
                else Symbols.draw(getContext(),c,app.kind==2?"sync":"apps",r.centerX(),r.centerY()-20,36,selected?TEXT:MUTED);
                drawLayout(c,app.text,r.left+10,r.bottom-42);
            }
            c.restore();
        }
        c.restore();
    }
    private void animateFocus() {
        if(animator!=null) animator.cancel();
        focusProgress=0;animator=ValueAnimator.ofFloat(0,1);animator.setDuration(150);
        animator.addUpdateListener(value->{focusProgress=(float)value.getAnimatedValue();invalidate();});animator.start();
        setContentDescription(focus==8&&!apps.isEmpty()?apps.get(shelfFocus).label:focus==0?hours+":"+minutes:focus==1?data.temperature:headings[focus]);
    }
    boolean onKey(KeyEvent event) {
        int code=event.getKeyCode();boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
        if(code==KeyEvent.KEYCODE_BACK) {if(!down){if(calendarActive){calendarActive=false;rebuildBoard();}else activity.finish();}return true;}
        if(code==KeyEvent.KEYCODE_MENU) {if(!down){if(calendarActive)calendarMenu();else if(focus==7)editQuickApps();else settings();}return true;}
        if(calendarActive&&(code==92||code==93)){if(down)changeDate(CalendarMonth.shiftMonth(monthPanel.selected,code==92?-1:1));return true;}
        if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER) {if(!down&&!event.isCanceled()) activate();return true;}
        if(code!=19&&code!=20&&code!=21&&code!=22)return false;
        if(!down)return true;
        if(calendarActive){changeDate(monthPanel.selected.plusDays(code==21?-1:code==22?1:code==19?-7:7));return true;}
        if(focus==7){
            if(code==21&&quickFocus%2==1){quickFocus--;invalidate();return true;}
            if(code==22&&quickFocus%2==0){quickFocus++;invalidate();return true;}
            if(code==19&&quickFocus>=2){quickFocus-=2;invalidate();return true;}
            if(code==20&&quickFocus<2){quickFocus+=2;invalidate();return true;}
        }
        if(focus==8) {
            if(code==21)shelfFocus=Math.max(0,shelfFocus-1);
            if(code==22)shelfFocus=Math.min(apps.size()-1,shelfFocus+1);
            if(code==19)focus=2;
        } else if(code==20&&(focus==1||focus==2||focus==5||focus==7)) focus=8;
        else {
            RectF current=tiles[focus];float best=Float.MAX_VALUE;int next=focus;
            for(int i=0;i<8;i++) {
                if(i==focus)continue;RectF target=tiles[i];float dx=target.centerX()-current.centerX(),dy=target.centerY()-current.centerY();
                boolean valid=code==19?target.bottom<=current.top+1:code==20?target.top>=current.bottom-1:code==21?target.right<=current.left+1:target.left>=current.right-1;
                if(!valid)continue;
                float score=(code==19||code==20)?Math.abs(dy)+Math.abs(dx)*.65f:Math.abs(dx)+Math.abs(dy)*.65f;
                if(score<best){best=score;next=i;}
            }
            focus=next;
        }
        animateFocus();return true;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(event.getAction()!=MotionEvent.ACTION_UP)return true;
        float x=(event.getX()-offsetX)/scale,y=(event.getY()-offsetY)/scale;
        if(tiles[2].contains(x,y)) {
            focus=2;if(y<130&&x>930){changeDate(x<1000?CalendarMonth.shiftMonth(monthPanel.selected,-1):x>1100?CalendarMonth.shiftMonth(monthPanel.selected,1):LocalDate.now());return true;}
            LocalDate day=monthPanel.hit(x,y);if(day!=null){calendarActive=true;changeDate(day);return true;}
            activate();return true;
        }
        calendarActive=false;
        for(int i=0;i<quickApps.size();i++)if(quickApps.get(i).rect.contains(x,y)){focus=7;quickFocus=i;activate();return true;}
        for(int i=0;i<tiles.length;i++)if(tiles[i].contains(x,y)){focus=i;animateFocus();activate();return true;}
        for(int i=0;i<apps.size();i++)if(apps.get(i).rect.contains(x,y)){focus=8;shelfFocus=i;animateFocus();activate();return true;}
        return true;
    }
    private void message(String text) {Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
    private void open(HomeApps.Item item) {
        if(!HomeApps.open(activity,item,this,0,0,getWidth(),getHeight()))message(Ui.tr("Could not open this item"));
    }
    private void activate() {
        if(focus==0)return;
        if(focus==1||focus==6){data.refresh();message(Ui.tr("Online data refresh requested"));return;}
        if(focus==2){if(!calendar.configured){calendarSettings();return;}if(calendarActive)calendarEvents();else {calendarActive=true;rebuildBoard();}return;}
        if(focus==3){open(HomeApps.action(HomeApps.LIVE_TV,"live",headings[3],"live_tv"));return;}
        if(focus==4){showInputs();return;}
        if(focus==5){settings();return;}
        if(focus==7){if(quickApps.isEmpty()||quickApps.get(quickFocus).kind==4)editQuickApps();else open(HomeApps.app(quickApps.get(quickFocus).pkg,quickApps.get(quickFocus).label));return;}
        if(apps.isEmpty())return;App app=apps.get(shelfFocus);
        if(app.kind==0)open(HomeApps.app(app.pkg,app.label));
        else if(app.kind==1){if(!BrowserShortcut.open(activity))message(Ui.tr("Could not open this item"));}
        else if(app.kind==2)openVpn();
        else showAllApps();
    }
    private void settings() {
        dialog=new NightDialogs.Builder(activity).setTitle(Ui.tr("Settings")).setItems(new String[]{Ui.tr("Calendars"),Ui.tr("Shelf"),Ui.tr("Quick launch"),Ui.tr("Home Settings"),Ui.tr("TV Settings"),Ui.tr("Data sources")},(d,index)->{
            if(index==0)calendarSettings();
            else if(index==1)editShelf();
            else if(index==2)editQuickApps();
            else if(index==3)activity.startActivity(new Intent(activity,SettingsActivity.class));
            else if(index==4)activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
            else dialog=new NightDialogs.Builder(activity).setTitle(Ui.tr("Data sources"))
                    .setMessage("MET Norway · CC BY 4.0\nhttps://api.met.no\n\nCBR: https://www.cbr-xml-daily.ru\nhttps://www.cbr.ru")
                    .setPositiveButton(Ui.tr("Close"),null).show();
        }).show();
    }
    private void showInputs() {
        List<HomeApps.Item> hdmi=new ArrayList<>();for(HomeApps.Item i:inputs)if(i.kind==HomeApps.INPUT)hdmi.add(i);
        if(hdmi.isEmpty()){message(Ui.tr("No HDMI inputs found"));return;}
        String[] labels=new String[hdmi.size()];for(int i=0;i<labels.length;i++)labels[i]=hdmi.get(i).label;
        dialog=new NightDialogs.Builder(activity).setTitle(Ui.tr("HDMI Inputs")).setItems(labels,(d,n)->open(hdmi.get(n))).show();
    }
    private void showAllApps() {
        String[] labels=new String[candidates.size()];for(int i=0;i<labels.length;i++)labels[i]=candidates.get(i)[1];
        dialog=new NightDialogs.Builder(activity).setTitle(Ui.tr("All Apps")).setItems(labels,(d,n)->open(HomeApps.app(candidates.get(n)[0],labels[n]))).show();
    }
    private void openVpn() {
        List<String[]> choices=new ArrayList<>();
        for(String pkg:new String[]{"com.happproxy","com.v2raytun.android"})
            if(HomeApps.launchIntent(activity.getPackageManager(),pkg)!=null)choices.add(new String[]{pkg,pkg.equals("com.happproxy")?"Happ":"v2RayTun"});
        if(choices.isEmpty()){message(Ui.tr("VPN app is not installed"));return;}
        if(choices.size()==1){open(HomeApps.app(choices.get(0)[0],choices.get(0)[1]));return;}
        dialog=new NightDialogs.Builder(activity).setTitle("VPN").setItems(new String[]{"Happ","v2RayTun"},(d,n)->open(HomeApps.app(choices.get(n)[0],choices.get(n)[1]))).show();
    }
    private void editShelf() {
        List<String> saved=Prefs.shelf();if(saved==null)saved=HomeApps.shelfPackages(activity);
        List<String> selection=new ArrayList<>(saved);boolean[] checked=new boolean[candidates.size()];String[] names=new String[checked.length];
        for(int i=0;i<checked.length;i++){names[i]=candidates.get(i)[1];checked[i]=selection.contains(candidates.get(i)[0]);}
        dialog=new NightDialogs.Builder(activity).setTitle(Ui.tr("Choose up to four apps"))
                .setMultiChoiceItems(names,checked,(d,index,on)->{
                    String pkg=candidates.get(index)[0];
                    if(on&&selection.size()>=4){((AlertDialog)d).getListView().setItemChecked(index,false);message(Ui.tr("Choose up to four apps"));}
                    else if(on)selection.add(pkg);else selection.remove(pkg);
                }).setPositiveButton(Ui.tr("Save"),(d,n)->{Prefs.setShelf(selection);loadApps();}).setNegativeButton(Ui.tr("Cancel"),null).show();
    }
    private void editQuickApps() {
        JSONArray old;try{old=new JSONArray(board.getString("quick-apps","[]"));}catch(Exception e){old=new JSONArray();}
        List<String> selected=new ArrayList<>();for(int i=0;i<old.length();i++)selected.add(old.optString(i));
        String[] names=new String[candidates.size()];boolean[] checks=new boolean[names.length];
        for(int i=0;i<names.length;i++){names[i]=candidates.get(i)[1];checks[i]=selected.contains(candidates.get(i)[0]);}
        dialog=new NightDialogs.Builder(activity).setTitle(Ui.tr("Quick launch")).setMultiChoiceItems(names,checks,(d,index,on)->{
            String pkg=candidates.get(index)[0];if(on&&selected.size()>=4){((AlertDialog)d).getListView().setItemChecked(index,false);message(Ui.tr("Choose up to four apps"));}else if(on)selected.add(pkg);else selected.remove(pkg);
        }).setPositiveButton(Ui.tr("Save"),(d,n)->{board.edit().putString("quick-apps",new JSONArray(selected).toString()).apply();loadApps();}).setNegativeButton(Ui.tr("Cancel"),null).show();
    }
    @Override public void calendarChanged() {if(!destroyed)rebuildBoard();}
    private void changeDate(LocalDate date) {
        if(date.getYear()<1970||date.getYear()>2100)return;
        monthPanel.select(date);if(!calendar.month.equals(monthPanel.month))calendar.setMonth(monthPanel.month);else rebuildBoard();
    }
    private void rebuildBoard() {
        String empty=!calendar.configured?Ui.tr("Connect your calendar in Settings"):calendar.activeCount==0?Ui.tr("Choose visible calendars"):calendar.loaded?Ui.tr("No events on this day"):calendar.failed?Ui.tr("Calendar could not be loaded"):Ui.tr("Waiting for calendar data");
        monthPanel.update(calendar.events,empty);
        String footer=calendar.updated==0?"":Ui.tr("Updated")+" "+new SimpleDateFormat("dd.MM HH:mm",Ui.locale()).format(new Date(calendar.updated));
        if(calendar.updated>0&&calendar.failed)footer=Ui.tr("Cached")+" · "+footer;
        if(!calendar.status.isEmpty())footer=CalendarStrings.translate(calendar.status)+(calendar.problemSource.isEmpty()?"":" · "+calendar.problemSource)+(footer.isEmpty()?"":" · "+footer);
        else if(!calendar.configured)footer=Ui.tr("OK to connect calendar");
        else if(monthPanel.total>monthPanel.visible)footer=Ui.tr("More")+" "+(monthPanel.total-monthPanel.visible)+" · "+footer;
        else footer=(calendarActive?Ui.tr("Arrows: day · OK: events · Back: exit"):Ui.tr("OK: choose day · Menu: calendars"))+(footer.isEmpty()?"":" · "+footer);
        calendarFooter=layout(footer,label,664,2);invalidate();
    }
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
