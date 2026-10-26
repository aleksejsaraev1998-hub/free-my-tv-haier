package io.freemytv;

import android.content.Context;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import java.util.*;

/** Only system-reported sources and names; never infer HDMI port numbers or video capabilities. */
final class InputCatalog {
    static List<HomeApps.Item> read(Context context) {
        List<HomeApps.Item> result=new ArrayList<>();
        TvInputManager manager=context.getSystemService(TvInputManager.class);
        if(manager==null)return result;
        try {
            List<TvInputInfo> sources=manager.getTvInputList();
            Map<String,String> cec=new HashMap<>();
            for(TvInputInfo info:sources)if(info.getParentId()!=null)cec.put(info.getParentId(),String.valueOf(info.loadLabel(context)));
            for(TvInputInfo info:sources) {
                if(info.getParentId()!=null)continue;
                CharSequence label=info.loadLabel(context),custom=info.loadCustomLabel(context);
                boolean connected=manager.getInputState(info.getId())==TvInputManager.INPUT_STATE_CONNECTED;
                String name=label==null?info.getId():label.toString();
                if(custom!=null&&custom.length()>0)name=custom.toString();
                if(connected&&cec.containsKey(info.getId()))name+=" — "+cec.get(info.getId());
                if(info.isPassthroughInput()) {
                    HomeApps.Item item=HomeApps.action(HomeApps.INPUT,info.getId(),name,"hdmi");
                    item.inputId=info.getId();item.connected=connected;result.add(item);
                } else {
                    HomeApps.Item item=HomeApps.action(HomeApps.LIVE_TV,info.getId(),name,"tv");result.add(item);
                }
            }
        } catch(RuntimeException ignored) { }
        Collections.sort(result,(a,b)->a.label.compareToIgnoreCase(b.label));
        return result;
    }
}
