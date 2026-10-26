package io.freemytv.voiceprobe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;

/** Foreground-only diagnostics. No recording files, background service or API key. */
public final class MainActivity extends Activity {
    private TextView output;
    private TextToSpeech tts;
    private boolean ttsReady;
    private volatile boolean capturing;
    private volatile boolean alive=true;
    private Thread captureThread;
    private int sourceIndex;
    private final int[] sources={MediaRecorder.AudioSource.VOICE_RECOGNITION,MediaRecorder.AudioSource.MIC,MediaRecorder.AudioSource.VOICE_COMMUNICATION};
    private final String[] names={"VOICE_RECOGNITION","MIC","VOICE_COMMUNICATION"};
    private void report(String line){Log.i("BentoVoiceTest",line);runOnUiThread(()->{if(alive)output.append("\n"+line);});}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(32,16,32,16);
        TextView title=new TextView(this);title.setText("Bento — проверка голоса\nЗапись только после OK. PCM остаётся в памяти, максимум 8 секунд.\nШтатное распознавание Google может использовать сеть. HOME / BACK прекращает тест.");title.setTextSize(18);layout.addView(title);
        button(layout,"1. Штатная диктовка → текст",()->dictate());
        button(layout,"2. Проверить русские голоса TTS",()->voices());
        button(layout,"3. Произнести тестовую фразу",()->speak());
        button(layout,"Источник PCM: VOICE_RECOGNITION — нажать для смены",()->{sourceIndex=(sourceIndex+1)%sources.length;report("Выбран источник: "+names[sourceIndex]);});
        button(layout,"4. Уровни микрофона — 8 секунд",()->capture());
        button(layout,"СТОП",()->{capturing=false;if(tts!=null)tts.stop();report("Остановка запрошена");});
        output=new TextView(this);output.setTextSize(15);output.setText("Тест ещё не запускался.");layout.addView(output);
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
        tts=new TextToSpeech(this,status->{ttsReady=status==TextToSpeech.SUCCESS;report("TTS init="+status);if(ttsReady)tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){public void onStart(String id){report("TTS START "+id);}public void onDone(String id){report("TTS DONE "+id);}public void onError(String id){report("TTS ERROR "+id);}});});
    }
    private void button(LinearLayout parent,String text,Runnable run){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setOnClickListener(v->run.run());parent.addView(b,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));}
    private void dictate(){
        capturing=false;
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ru-RU");intent.putExtra(RecognizerIntent.EXTRA_PROMPT,"Скажите: Бенто, открой настройки");
        try{report("STT start; handler="+intent.resolveActivity(getPackageManager()));startActivityForResult(intent,7);}catch(Exception e){report("STT unavailable: "+e.getClass().getSimpleName());}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==7){ArrayList<String> lines=data==null?null:data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);report("STT result="+result+" text="+lines);}}
    private void voices(){
        if(!ttsReady){report("TTS ещё не готов");return;}
        report("ru-RU availability="+tts.isLanguageAvailable(Locale.forLanguageTag("ru-RU"))+" (negative=unavailable)");
        int count=0;if(tts.getVoices()!=null)for(Voice v:tts.getVoices())if("ru".equals(v.getLocale().getLanguage())){report("VOICE "+v.getName()+" network="+v.isNetworkConnectionRequired()+" features="+v.getFeatures());count++;}
        report("Русских голосов: "+count);
    }
    private void speak(){if(!ttsReady){report("TTS ещё не готов");return;}int code=tts.setLanguage(Locale.forLanguageTag("ru-RU"));report("TTS setLanguage="+code);if(code>=0)report("TTS speak="+tts.speak("Проверка Бенто. Сегодня телевизор говорит по-русски. Один, два, три.",TextToSpeech.QUEUE_FLUSH,null,"bento-test"));}
    private void capture(){
        if(captureThread!=null&&captureThread.isAlive()){report("Дождитесь завершения предыдущего захвата");return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},9);report("Нужно разрешение. После выдачи нажмите тест ещё раз.");return;}
        final int selected=sourceIndex;capturing=true;
        captureThread=new Thread(()->{
            AudioRecord recorder=null;
            try{
                int size=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if(size<=0)throw new IllegalStateException("buffer="+size);
                recorder=new AudioRecord(sources[selected],16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(size,8192));
                if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("not initialized");
                short[] pcm=new short[1024];long start=SystemClock.elapsedRealtime(),samples=0,last=start;double sum=0;int peak=0;
                recorder.startRecording();report("PCM START "+names[selected]);
                while(capturing&&SystemClock.elapsedRealtime()-start<8000){
                    int n=recorder.read(pcm,0,pcm.length,AudioRecord.READ_NON_BLOCKING);if(n<0)throw new IllegalStateException("read="+n);
                    for(int i=0;i<n;i++){double s=pcm[i];sum+=s*s;peak=Math.max(peak,Math.abs((int)pcm[i]));}samples+=n;
                    long now=SystemClock.elapsedRealtime();if(now-last>=1000){AudioDeviceInfo device=recorder.getRoutedDevice();report("PCM samples="+samples+" RMS="+(samples==0?0:(int)Math.sqrt(sum/samples))+" peak="+peak+" route="+(device==null?"unknown":device.getType()+":"+device.getProductName()));last=now;}
                    if(n==0)SystemClock.sleep(20);
                }
                report("PCM END samples="+samples+"; уровни не доказывают захват речи или конкретный микрофон");
            }catch(Exception e){report("PCM ERROR "+e.getClass().getSimpleName()+": "+e.getMessage());}
            finally{capturing=false;if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();}report("PCM RELEASED");}
        },"BentoMicProbe");captureThread.start();
    }
    @Override protected void onPause(){capturing=false;if(tts!=null)tts.stop();super.onPause();}
    @Override protected void onDestroy(){alive=false;capturing=false;if(tts!=null)tts.shutdown();super.onDestroy();}
}
