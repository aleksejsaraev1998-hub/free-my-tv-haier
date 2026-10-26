package io.freemytv;

import android.content.Context;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import java.util.*;

/** Only system-reported sources and names; never infer HDMI port numbers or video capabilities. */
final class InputCatalog {
    private static final Map<String,String> states=new HashMap<>();
    static String state(HomeApps.Item item){String value=states.get(item.inputId);return value==null?"Состояние недоступно":value;}
    static List<HomeApps.Item> read(Context context) {
        List<HomeApps.Item> result=new ArrayList<>();states.clear();
        TvInputManager manager=context.getSystemService(TvInputManager.class);
        if(manager==null)return result;
        try {
            List<TvInputInfo> sources=manager.getTvInputList();
            Map<String,String> cec=new HashMap<>();
            for(TvInputInfo info:sources)if(info.getParentId()!=null)cec.put(info.getParentId(),String.valueOf(info.loadLabel(context)));
            for(TvInputInfo info:sources) {
                if(info.getParentId()!=null)continue;
                CharSequence label=info.loadLabel(context),custom=info.loadCustomLabel(context);
                int state=manager.getInputState(info.getId());
                boolean connected=state==TvInputManager.INPUT_STATE_CONNECTED;
                String name=label==null?info.getId():label.toString();
                if(custom!=null&&custom.length()>0)name=custom.toString();
                if(cec.containsKey(info.getId()))name+=" — "+cec.get(info.getId());
                if(info.isPassthroughInput()) {
                    HomeApps.Item item=HomeApps.action(HomeApps.INPUT,info.getId(),name,info.getType()==TvInputInfo.TYPE_HDMI?"hdmi":"tv");
                    item.inputId=info.getId();item.connected=connected;states.put(item.inputId,state==TvInputManager.INPUT_STATE_CONNECTED?"Подключено":state==TvInputManager.INPUT_STATE_CONNECTED_STANDBY?"Ожидание":"Нет сигнала");result.add(item);
                } else if(info.getType()==TvInputInfo.TYPE_TUNER&&info.getServiceInfo().packageName.equals("com.mediatek.tvinput")) {
                    HomeApps.Item item=HomeApps.action(HomeApps.LIVE_TV,info.getId(),name,"tv");
                    item.inputId=info.getId();states.put(item.inputId,"ТВ / Антенна");result.add(item);
                }
            }
        } catch(RuntimeException ignored) { }
        Collections.sort(result,(a,b)->a.symbol.equals(b.symbol)?a.label.compareToIgnoreCase(b.label):a.symbol.equals("hdmi")?-1:1);
        return result;
    }
}
