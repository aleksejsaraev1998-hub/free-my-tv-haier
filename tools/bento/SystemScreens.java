package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.provider.Settings;

/** TV firmware often omits the phone Bluetooth settings intent. */
final class SystemScreens {
    static boolean openBluetooth(Activity activity) {
        Intent[] candidates={
            new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            new Intent("android.settings.CONNECTED_DEVICE_SETTINGS"),
            new Intent().setClassName("com.android.tv.settings","com.android.tv.settings.accessories.AccessoryActivity"),
            new Intent().setClassName("com.android.tv.settings","com.android.tv.settings.connecteddevice.ConnectedDevicesActivity")
        };
        for(Intent intent:candidates){
            try{activity.startActivity(intent);return true;}
            catch(android.content.ActivityNotFoundException|SecurityException ignored){}
        }
        return false;
    }
}
