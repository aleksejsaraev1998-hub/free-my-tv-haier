package io.freemytv;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.*;
import android.widget.*;

/** Consistent dark menus. These allocate only while a dialog is opened or a row is attached. */
final class NightDialogs {
    static final int TEXT=0xFFB2ADA4,MUTED=0xFF8F8B84,TILE=0xFF1E2026,EDGE=0xFF2C2F37,FOCUS=0xFF8F8B84;
    static GradientDrawable background(int color,int edge,int width){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(18);if(width>0)d.setStroke(width,edge);return d;}
    static StateListDrawable selector(){StateListDrawable s=new StateListDrawable();s.addState(new int[]{android.R.attr.state_pressed},background(0xFF262931,FOCUS,2));s.addState(new int[]{android.R.attr.state_focused},background(0xFF262931,FOCUS,2));s.addState(new int[]{android.R.attr.state_selected},background(0xFF262931,FOCUS,2));s.addState(new int[]{},background(Color.TRANSPARENT,EDGE,0));return s;}
    static void tint(View v){
        if(v instanceof TextView){TextView t=(TextView)v;t.setTextColor(TEXT);t.setTextSize(20);if(v instanceof EditText){t.setHintTextColor(MUTED);t.setPadding(18,12,18,12);t.setBackground(selector());}else if(v instanceof Button)t.setBackground(selector());}
        if(v instanceof CheckedTextView)((CheckedTextView)v).setCheckMarkTintList(ColorStateList.valueOf(MUTED));
        if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++)tint(group.getChildAt(i));}
    }
    static final class Builder extends AlertDialog.Builder {
        Builder(Context context){super(new ContextThemeWrapper(context,android.R.style.Theme_Material_Dialog_Alert));}
        @Override public AlertDialog create(){
            AlertDialog dialog=super.create();dialog.setOnShowListener(d->{
                Window w=dialog.getWindow();if(w==null)return;
                w.setBackgroundDrawable(background(TILE,EDGE,1));
                w.setDimAmount(.45f);View decor=w.getDecorView();if(android.os.Build.VERSION.SDK_INT>=29)decor.setForceDarkAllowed(false);tint(decor);
                ListView list=dialog.getListView();if(list!=null){list.setSelector(selector());list.setDividerHeight(8);list.setPadding(16,8,16,8);list.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener(){public void onChildViewAdded(View p,View c){tint(c);}public void onChildViewRemoved(View p,View c){}});}
            });return dialog;
        }
        @Override public AlertDialog show(){AlertDialog d=create();d.show();return d;}
    }
}
