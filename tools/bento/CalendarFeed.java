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

/** Credentials and minimal cached agenda encrypted using this installation's Android Keystore key. */
final class CalendarFeed {
    interface Listener {void calendarChanged();}
    static final String DEFAULT_URL="https://inbox.gemsdev.com/SOGo/dav/{login}/Calendar/personal/";
    private static final String KEY="haier-caldav-v1";
    private final SharedPreferences prefs;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Listener listener;
    private int generation;
    private boolean closed,busy;
    boolean ready,configured,failed;
    long updated,windowEnd,lastAttempt;
    String status="",url=DEFAULT_URL,user="";
    private String password="";
    List<CalendarProtocol.Event> events=Collections.emptyList();
    CalendarFeed(Context c) {
        prefs=c.getSharedPreferences("bento-calendar-private",Context.MODE_PRIVATE);
        worker.execute(()->{
            String address=DEFAULT_URL,login="",secret="",problem="";long at=0,until=0;List<CalendarProtocol.Event> cached=new ArrayList<>();
            try {
                if(prefs.contains("account")) {
                    JSONObject account=new JSONObject(decrypt(prefs.getString("account","")));
                    address=account.getString("url");login=account.getString("user");secret=account.getString("password");
                    if(prefs.contains("cache"))try {
                        JSONObject cache=new JSONObject(decrypt(prefs.getString("cache","")));at=cache.getLong("updated");until=cache.getLong("until");
                        JSONArray list=cache.getJSONArray("events");
                        for(int i=0;i<list.length()&&i<1000;i++){JSONObject item=list.getJSONObject(i);CalendarProtocol.Event e=new CalendarProtocol.Event();e.title=item.getString("title");e.start=item.getLong("start");e.end=item.getLong("end");e.allDay=item.getBoolean("allDay");cached.add(e);}
                    }catch(Exception ignored){at=0;until=0;cached.clear();}
                }
            }catch(Exception e){problem="Calendar storage unavailable; enter credentials again";}
            final String a=address,u=login,p=secret,error=problem;final long t=at,w=until;
            main.post(()->{if(closed||generation!=0)return;url=a;user=u;password=p;configured=!p.isEmpty();events=cached;updated=t;windowEnd=w;status=error;failed=!error.isEmpty();ready=true;notifyChanged();refresh(false);});
        });
    }
    void listen(Listener l){listener=l;}
    private void notifyChanged(){if(listener!=null)listener.calendarChanged();}
    void configure(String address,String login,String secret) {
        if(closed||busy)return;
        final int version=++generation;busy=true;status="Saving calendar settings";notifyChanged();
        worker.execute(()->{
            String error="";
            try {
                CalendarProtocol.endpoint(address);
                JSONObject account=new JSONObject();account.put("url",address);account.put("user",login);account.put("password",secret);
                if(!prefs.edit().putString("account",encrypt(account.toString())).remove("cache").commit())throw new Exception();
            }catch(Exception e){error="Could not save calendar settings";}
            final String problem=error;
            main.post(()->{
                if(closed||version!=generation)return;busy=false;
                if(!problem.isEmpty()){status=problem;failed=true;notifyChanged();return;}
                url=address;user=login;password=secret;configured=true;ready=true;events=Collections.emptyList();updated=0;windowEnd=0;lastAttempt=0;failed=false;status="";notifyChanged();refresh(true);
            });
        });
    }
    boolean isBusy(){return busy;}
    boolean hasPassword(){return !password.isEmpty();}
    void save(String address,String login,String secret){configure(address,login,secret.isEmpty()?password:secret);}
    void disconnect() {
        if(closed)return;final int version=++generation;busy=true;
        worker.execute(()->{
            boolean cleared=prefs.edit().clear().commit();
            main.post(()->{if(closed||version!=generation)return;busy=false;if(!cleared){failed=true;status="Could not save calendar settings";notifyChanged();return;}
                configured=false;password="";user="";url=DEFAULT_URL;events=Collections.emptyList();updated=0;windowEnd=0;lastAttempt=0;failed=false;status="";notifyChanged();});
        });
    }
    void refresh(boolean force) {
        if(closed||!ready||!configured||busy||listener==null)return;
        long now=System.currentTimeMillis();
        if(now>=lastAttempt&&now-lastAttempt<(force?30000:failed?120000:300000))return;
        lastAttempt=now;busy=true;status="Updating calendar";notifyChanged();
        final int version=generation;final String address=url,login=user,secret=password;
        final ZoneId zone=ZoneId.systemDefault();final LocalDate today=LocalDate.now(zone);
        final long from=today.atStartOfDay(zone).toInstant().toEpochMilli(),until=today.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli();
        worker.execute(()->{
            List<CalendarProtocol.Event> fetched=null;String error="";long at=0;
            try {
                fetched=CalendarProtocol.fetch(address,login,secret,from,until);at=System.currentTimeMillis();
                JSONObject cache=new JSONObject();cache.put("updated",at);cache.put("until",until);JSONArray list=new JSONArray();
                for(CalendarProtocol.Event e:fetched){JSONObject item=new JSONObject();item.put("title",e.title);item.put("start",e.start);item.put("end",e.end);item.put("allDay",e.allDay);list.put(item);}cache.put("events",list);
                // Serialize storage changes with configure/disconnect on this same executor.
                if(!prefs.edit().putString("cache",encrypt(cache.toString())).commit())throw new Exception();
            }catch(CalendarProtocol.Failure e){error=e.code;}catch(javax.net.ssl.SSLException e){error="Calendar TLS connection failed";}catch(Exception e){error="Calendar unavailable; check network and settings";}
            final List<CalendarProtocol.Event> result=fetched;final String problem=error;final long timestamp=at;
            main.post(()->{if(closed||version!=generation)return;busy=false;failed=!problem.isEmpty();status=problem;
                if(!failed){events=result;updated=timestamp;windowEnd=until;}
                notifyChanged();});
        });
    }
    void close(){closed=true;listener=null;generation++;worker.shutdownNow();}
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
