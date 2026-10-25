package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Multiple read-only calendars. Entire state (including credentials and month caches) is encrypted. */
final class CalendarFeed {
    interface Listener {void calendarChanged();}
    interface Discovery {void complete(List<CalendarProtocol.Collection> calendars,String error);}
    static final String DEFAULT_URL="https://inbox.gemsdev.com/SOGo/dav/{login}/Calendar/personal/";
    private static final String KEY="haier-caldav-v1";
    static final int[] COLORS={0xFFCA665F,0xFF7B75BD,0xFF529A9C,0xFFB0A253,0xFF73976C,0xFF698DAF};
    static final class Cache {long updated;String error="";List<CalendarProtocol.Event> events=Collections.emptyList();}
    static final class Source {
        String id=UUID.randomUUID().toString(),name="",url=DEFAULT_URL,user="",password="";boolean enabled=true,ics;
        int color=COLORS[0];LinkedHashMap<String,Cache> months=new LinkedHashMap<>();Map<String,Long> attempts=new HashMap<>();
        Source copy(){Source s=new Source();s.id=id;s.name=name;s.url=url;s.user=user;s.password=password;s.enabled=enabled;s.ics=ics;s.color=color;s.months=new LinkedHashMap<>(months);s.attempts=new HashMap<>(attempts);return s;}
        boolean hasPassword(){return !password.isEmpty();}
    }
    private final SharedPreferences prefs;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Listener listener;private int generation;private boolean closed,busy;
    boolean ready,configured,failed,loaded;int activeCount;
    long updated;String status="",problemSource="";
    YearMonth month=YearMonth.now();
    List<Source> sources=Collections.emptyList();
    List<CalendarProtocol.Event> events=Collections.emptyList();
    CalendarFeed(Context c) {
        prefs=c.getSharedPreferences("bento-calendar-private",Context.MODE_PRIVATE);
        worker.execute(()->{
            List<Source> list=new ArrayList<>();String error="";
            try {
                if(prefs.contains("state-v2"))list=read(new JSONObject(decrypt(prefs.getString("state-v2",""))));
                else if(prefs.contains("account")) {
                    JSONObject old=new JSONObject(decrypt(prefs.getString("account","")));Source s=new Source();s.name="SOGo";s.url=old.getString("url");s.user=old.getString("user");s.password=old.getString("password");list.add(s);
                    // Migrate credentials atomically; a 7-day cache cannot stand in for a full month.
                    if(!prefs.edit().putString("state-v2",encrypt(write(list).toString())).remove("account").remove("cache").commit())throw new Exception();
                }
            }catch(Exception e){error="Calendar storage unavailable; enter credentials again";}
            final List<Source> result=list;final String problem=error;
            main.post(()->{if(closed)return;sources=result;ready=true;aggregate();if(!problem.isEmpty()){status=problem;failed=true;}notifyChanged();refresh(false);});
        });
    }
    void listen(Listener l){listener=l;}
    boolean isBusy(){return busy;}
    void setMonth(YearMonth value){month=value;aggregate();notifyChanged();refresh(false);}
    private void notifyChanged(){if(listener!=null)listener.calendarChanged();}
    private List<Source> copySources(){List<Source> list=new ArrayList<>();for(Source s:sources)list.add(s.copy());return list;}
    void save(Source source){List<Source> list=copySources();int i=0;while(i<list.size()&&!list.get(i).id.equals(source.id))i++;
        Source next=source.copy();next.months.clear();next.attempts.clear();if(i<list.size())list.set(i,next);else if(list.size()<12)list.add(next);else return;persist(list);}
    void choose(Set<String> enabled){List<Source> list=copySources();for(Source s:list)s.enabled=enabled.contains(s.id);persist(list);}
    void remove(String id){List<Source> list=copySources();list.removeIf(s->s.id.equals(id));persist(list);}
    void addDiscovered(Source account,List<CalendarProtocol.Collection> collections) {
        List<Source> list=copySources();
        for(CalendarProtocol.Collection c:collections){boolean exists=false;for(Source s:list)if(s.url.equals(c.url)&&!s.ics){exists=true;break;}
            if(exists||list.size()>=12)continue;Source s=new Source();s.name=c.name;s.url=c.url;s.user=account.user;s.password=account.password;s.color=c.color;list.add(s);}
        persist(list);
    }
    private void persist(List<Source> list) {
        if(closed||busy)return;busy=true;final int version=++generation;status="Saving calendar settings";notifyChanged();
        worker.execute(()->{boolean success=false;try{success=prefs.edit().putString("state-v2",encrypt(write(list).toString())).commit();}catch(Exception ignored){}final boolean ok=success;
            main.post(()->{if(closed||version!=generation)return;busy=false;if(ok)sources=list;aggregate();if(!ok){status="Could not save calendar settings";failed=true;}notifyChanged();if(ok)refresh(false);});});
    }
    void discover(Source account,Discovery callback) {
        if(closed||busy){callback.complete(Collections.emptyList(),"Calendar is busy; try again shortly");return;}
        busy=true;status="Finding calendars";notifyChanged();final int version=generation;
        worker.execute(()->{List<CalendarProtocol.Collection> list=Collections.emptyList();String error="";
            try{list=CalendarProtocol.discover(account.url,account.user,account.password);}catch(CalendarProtocol.Failure e){error=e.code;}catch(Exception e){error="Calendar unavailable; check network and settings";}
            final List<CalendarProtocol.Collection> found=list;final String problem=error;
            main.post(()->{if(closed||version!=generation)return;busy=false;aggregate();notifyChanged();callback.complete(found,problem);});});
    }
    private void aggregate() {
        List<CalendarProtocol.Event> merged=new ArrayList<>();configured=!sources.isEmpty();failed=false;loaded=true;updated=0;problemSource="";String error="";int count=0;
        for(Source s:sources)if(s.enabled) {count++;Cache cache=s.months.get(month.toString());if(cache==null||cache.updated==0)loaded=false;
            if(cache!=null) {merged.addAll(cache.events);if(cache.updated>0)updated=updated==0?cache.updated:Math.min(updated,cache.updated);
                if(!cache.error.isEmpty()){failed=true;if(error.isEmpty()){error=cache.error;problemSource=s.name;}}}
        }
        activeCount=count;if(count==0)loaded=false;
        merged.sort(Comparator.comparingLong(e->e.start));events=merged;
        if(!busy)status=error;
    }
    void refresh(boolean force) {
        if(closed||!ready||busy||listener==null||sources.isEmpty())return;
        long now=System.currentTimeMillis();final String key=month.toString();List<Source> next=copySources();boolean needed=false;
        for(Source s:next)if(s.enabled) {Cache cache=s.months.get(key);long attempt=s.attempts.containsKey(key)?s.attempts.get(key):0;
            long delay=force?30000:cache!=null&&!cache.error.isEmpty()?120000:300000;
            if(attempt==0||now<attempt||now-attempt>=delay)needed=true;
        }
        if(!needed)return;
        busy=true;status="Updating calendar";notifyChanged();final int version=generation;final YearMonth requested=month;final long[] range=CalendarMonth.window(month,ZoneId.of("Asia/Yekaterinburg"));
        worker.execute(()->{
            for(Source s:next)if(s.enabled) {
                long at=System.currentTimeMillis(),attempt=s.attempts.containsKey(key)?s.attempts.get(key):0;Cache old=s.months.get(key);
                long delay=force?30000:old!=null&&!old.error.isEmpty()?120000:300000;
                if(attempt>0&&at>=attempt&&at-attempt<delay)continue;s.attempts.put(key,at);Cache cache=new Cache();if(old!=null){cache.updated=old.updated;cache.events=old.events;}
                try {
                    List<CalendarProtocol.Event> loaded=s.ics?CalendarProtocol.fetchIcs(s.url,s.user,s.password,range[0],range[1]):CalendarProtocol.fetch(s.url,s.user,s.password,range[0],range[1]);
                    for(CalendarProtocol.Event e:loaded){e.source=s.id;e.sourceName=s.name;e.color=s.color;}
                    cache.events=loaded;cache.updated=System.currentTimeMillis();
                }catch(CalendarProtocol.Failure e){cache.error=e.code;}catch(javax.net.ssl.SSLException e){cache.error="Calendar TLS connection failed";}catch(Exception e){cache.error="Calendar unavailable; check network and settings";}
                s.months.remove(key);s.months.put(key,cache);while(s.months.size()>3){String oldest=s.months.keySet().iterator().next();s.months.remove(oldest);s.attempts.remove(oldest);}
                if(Thread.currentThread().isInterrupted())return;
            }
            boolean saved=false;try{saved=prefs.edit().putString("state-v2",encrypt(write(next).toString())).commit();}catch(Exception ignored){}final boolean stored=saved;
            main.post(()->{if(closed||version!=generation)return;busy=false;sources=next;aggregate();if(!stored){failed=true;status="Could not save calendar settings";}notifyChanged();if(!month.equals(requested))refresh(false);});
        });
    }
    void close(){closed=true;listener=null;generation++;worker.shutdownNow();}
    private static JSONObject write(List<Source> sources) throws Exception {
        JSONArray list=new JSONArray();
        for(Source s:sources) {JSONObject item=new JSONObject();item.put("id",s.id);item.put("name",s.name);item.put("url",s.url);item.put("user",s.user);item.put("password",s.password);item.put("enabled",s.enabled);item.put("ics",s.ics);item.put("color",s.color);JSONArray months=new JSONArray();
            for(Map.Entry<String,Cache> entry:s.months.entrySet()){Cache c=entry.getValue();JSONObject cache=new JSONObject();cache.put("month",entry.getKey());cache.put("updated",c.updated);cache.put("error",c.error);JSONArray events=new JSONArray();
                for(CalendarProtocol.Event e:c.events){JSONObject ev=new JSONObject();ev.put("title",e.title);ev.put("uid",e.uid);ev.put("start",e.start);ev.put("end",e.end);ev.put("allDay",e.allDay);events.put(ev);}cache.put("events",events);months.put(cache);}
            item.put("months",months);list.put(item);
        }
        JSONObject root=new JSONObject();root.put("sources",list);return root;
    }
    private static List<Source> read(JSONObject root) throws Exception {
        List<Source> list=new ArrayList<>();JSONArray values=root.getJSONArray("sources");
        for(int i=0;i<values.length()&&i<12;i++){JSONObject item=values.getJSONObject(i);Source s=new Source();s.id=item.getString("id");s.name=item.getString("name");s.url=item.getString("url");s.user=item.getString("user");s.password=item.getString("password");s.enabled=item.getBoolean("enabled");s.ics=item.getBoolean("ics");s.color=item.getInt("color");JSONArray months=item.getJSONArray("months");
            for(int j=Math.max(0,months.length()-3);j<months.length();j++){JSONObject value=months.getJSONObject(j);YearMonth.parse(value.getString("month"));Cache cache=new Cache();cache.updated=value.getLong("updated");cache.error=value.optString("error");JSONArray events=value.getJSONArray("events");List<CalendarProtocol.Event> loaded=new ArrayList<>();
                for(int k=0;k<events.length()&&k<1000;k++){JSONObject v=events.getJSONObject(k);CalendarProtocol.Event e=new CalendarProtocol.Event();e.title=v.getString("title");e.uid=v.optString("uid");e.start=v.getLong("start");e.end=v.getLong("end");e.allDay=v.getBoolean("allDay");e.source=s.id;e.sourceName=s.name;e.color=s.color;loaded.add(e);}cache.events=loaded;s.months.put(value.getString("month"),cache);}
            list.add(s);
        }
        return list;
    }
    private static SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(KEY))return (SecretKey)store.getKey(KEY,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    private static String encrypt(String value) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        return Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
    }
    private static String decrypt(String value) throws Exception {
        String[] parts=value.split(":",2);if(parts.length!=2)throw new Exception();
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
}
