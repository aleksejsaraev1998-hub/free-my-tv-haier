package io.freemytv;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;

/** User-initiated Russian STT diagnostic, completely separate from TV HOME. */
public final class VoiceProbeActivity extends Activity {
    private static final ComponentName KATNISS = new ComponentName(
        "com.google.android.katniss",
        "com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService");
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private SpeechRecognizer recognizer;
    private boolean listening;
    private int session;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setBackgroundColor(0xFF071321);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(64,48,64,36);
        TextView title = new TextView(this);
        title.setText("BENTO VOICE  •  ПРЯМОЕ РАСПОЗНАВАНИЕ");
        title.setTextSize(29);title.setTextColor(0xFFEAF5FF);
        root.addView(title);
        Button test = new Button(this);
        test.setText("Начать распознавание на русском");
        test.setOnClickListener(v -> startTest());
        root.addView(test);
        Button stop = new Button(this);
        stop.setText("Остановить");
        stop.setOnClickListener(v -> stopTest());
        root.addView(stop);
        status=new TextView(this);
        status.setTextColor(0xFFCEE1EF);
        status.setTextSize(22);
        status.setPadding(8,24,8,0);
        ScrollView scroll=new ScrollView(this);
        scroll.addView(status);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        append("Компонент: " + KATNISS.flattenToShortString());
        append("Разрешение микрофона: " + (checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED?"выдано":"не выдано"));
        append("Нажмите Начать и скажите: «Бенто, открой настройки»");
    }
    private void append(String line) {
        if(status!=null)status.append(line+"\n");
        android.util.Log.i("BentoVoiceDirect",line);
    }
    private void startTest() {
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},7);
            append("Запрошено разрешение RECORD_AUDIO; после разрешения нажмите Начать");
            return;
        }
        stopTest();
        int current=++session;
        try{
            recognizer=SpeechRecognizer.createSpeechRecognizer(this,KATNISS);
            recognizer.setRecognitionListener(new RecognitionListener(){
                @Override public void onReadyForSpeech(Bundle p){append("READY: слушаю");}
                @Override public void onBeginningOfSpeech(){append("BEGIN: речь обнаружена");}
                @Override public void onRmsChanged(float rms){}
                @Override public void onBufferReceived(byte[] buffer){}
                @Override public void onEndOfSpeech(){append("END: обработка речи");}
                @Override public void onError(int code){listening=false;append("ERROR: код "+code+" (1 network timeout; 2 network; 3 audio; 5 client; 6 speech timeout; 7 no match; 8 busy; 9 permissions; 10 too many requests; 12 language unavailable)");}
                @Override public void onResults(Bundle results){
                    listening=false;
                    ArrayList<String> heard=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    append("RESULT: "+(heard==null?"null":heard.toString()));
                }
                @Override public void onPartialResults(Bundle results){
                    ArrayList<String> p=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if(p!=null && !p.isEmpty())append("PARTIAL: "+p.get(0));
                }
                @Override public void onEvent(int type,Bundle params){append("EVENT: "+type);}
            });
            Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ru-RU");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"ru-RU");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3);
            append("START direct SpeechRecognizer on Katniss (ru-RU), session "+current);
            listening=true;
            recognizer.startListening(intent);
        }catch(Exception e){listening=false;append("EXCEPTION: "+e.getClass().getSimpleName()+" "+e.getMessage());}
    }
    private void stopTest(){
        session++;
        if(recognizer!=null){
            try{recognizer.cancel();recognizer.destroy();}catch(Exception ignored){}
            recognizer=null;
        }
        listening=false;
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(requestCode,permissions,grants);
        if(requestCode==7)append("RECORD_AUDIO granted="+(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED));
    }
    @Override protected void onDestroy(){stopTest();super.onDestroy();}
}