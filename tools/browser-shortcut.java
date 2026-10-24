package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opens a separate browser's home screen. No WebView, downloads or automatic installation. */
final class BrowserShortcut {
    private BrowserShortcut() {}
    private static final Paint INK = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF OVAL = new RectF();

    private static Intent category() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER);
    }

    private static Intent web(String scheme) {
        // Only resolve this intent; do not open or request the address.
        return new Intent(Intent.ACTION_VIEW, Uri.parse(scheme + "://example.org/"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
    }

    private static List<Intent> candidates(PackageManager pm) {
        Map<String, Intent> apps = new LinkedHashMap<>();
        for (Intent query : new Intent[] {category(), web("https"), web("http")}) {
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                if (info.activityInfo == null || !info.activityInfo.exported
                        || !info.activityInfo.enabled || !info.activityInfo.applicationInfo.enabled) continue;
                String pkg = info.activityInfo.packageName;
                Intent home = HomeApps.launchIntent(pm, pkg);
                if (home != null) apps.put(pkg, home.setPackage(pkg));
            }
        }
        return new ArrayList<>(apps.values());
    }

    static boolean open(Activity activity) {
        try {
            PackageManager pm = activity.getPackageManager();
            List<Intent> apps = candidates(pm);
            if (apps.isEmpty()) {
                Toast.makeText(activity, Ui.tr("Install a browser for Android TV first."), Toast.LENGTH_LONG).show();
                return true; // The user already received a specific explanation.
            }
            Intent selected = null;
            ResolveInfo defaultApp = pm.resolveActivity(web("https"), PackageManager.MATCH_DEFAULT_ONLY);
            if (defaultApp != null && defaultApp.activityInfo != null) {
                for (Intent home : apps) {
                    if (defaultApp.activityInfo.packageName.equals(home.getPackage())) selected = home;
                }
            }
            if (selected == null && apps.size() == 1) selected = apps.get(0);
            if (selected == null) {
                Intent first = apps.remove(0);
                selected = Intent.createChooser(first, Ui.tr("Browser"));
                selected.putExtra(Intent.EXTRA_INITIAL_INTENTS, apps.toArray(new Intent[0]));
            }
            activity.startActivity(selected.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("browser shortcut: " + e.getMessage());
            return false;
        }
    }

    /** Primitive globe, no bitmap decoding or per-frame allocations. */
    static void draw(Canvas canvas, float cx, float cy, float size, int color) {
        synchronized (INK) {
            float r = size * 0.52f;
            INK.setColor(color);
            INK.setStyle(Paint.Style.STROKE);
            INK.setStrokeWidth(Math.max(1, size * 0.045f));
            canvas.drawCircle(cx, cy, r, INK);
            OVAL.set(cx - r * 0.43f, cy - r, cx + r * 0.43f, cy + r);
            canvas.drawOval(OVAL, INK);
            canvas.drawLine(cx - r, cy, cx + r, cy, INK);
        }
    }
}
