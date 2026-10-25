package io.freemytv;

import android.graphics.*;
import android.text.*;
import java.time.*;
import java.time.format.TextStyle;
import java.util.*;

/** Phone-style month and selected-day agenda, using cached Canvas primitives and text layouts. */
final class MonthPanel {
    static final float LEFT=484,TOP=164,WIDTH=664,CELL=WIDTH/7;
    LocalDate selected=LocalDate.now();YearMonth month=YearMonth.from(selected);
    private LocalDate first=CalendarMonth.first(month);
    private long today;private int rowCount=6;private float rowHeight=50;
    private List<CalendarProtocol.Event> previous;
    private YearMonth indexed;
    private final List<List<CalendarProtocol.Event>> days=new ArrayList<>();
    private final String[] numbers=new String[42],weekdays=new String[7];
    private final boolean[] inside=new boolean[42];
    private final int[][] marks=new int[42][6];private final int[] markCount=new int[42];
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG),line=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title=text(34,0xFFB2ADA4),day=text(28,0xFF8F8B84),small=text(24,0xFF67645F),eventPaint=text(30,0xFFB2ADA4);
    private final java.util.List<Entry> rows=new ArrayList<>();
    private String heading="",date="",empty="";private StaticLayout emptyText;
    int total,visible;
    private static final class Entry {StaticLayout title;String start,end;int color;float y,height;}
    private static TextPaint text(float size,int color){TextPaint p=new TextPaint(Paint.ANTI_ALIAS_FLAG);p.setTextSize(size);p.setColor(color);p.setTypeface(Ui.regular);return p;}
    private static StaticLayout layout(String s,TextPaint paint,int width,int lines){return StaticLayout.Builder.obtain(s,0,s.length(),paint,width).setIncludePad(false).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build();}
    void select(LocalDate date){selected=date;month=YearMonth.from(date);}
    void update(java.util.List<CalendarProtocol.Event> events,String noEvents) {
        rowCount=CalendarMonth.rows(month);rowHeight=300f/rowCount;today=LocalDate.now().toEpochDay();first=CalendarMonth.first(month);ZoneId zone=ZoneId.of("Asia/Yekaterinburg");
        if(previous!=events||!month.equals(indexed)) {
            previous=events;indexed=month;days.clear();
            for(int i=0;i<42;i++){LocalDate d=first.plusDays(i);days.add(CalendarMonth.day(events,d,zone));numbers[i]=Integer.toString(d.getDayOfMonth());inside[i]=YearMonth.from(d).equals(month);markCount[i]=0;
                Set<String> ids=new HashSet<>();for(CalendarProtocol.Event e:days.get(i))if(ids.add(e.source)&&markCount[i]<6)marks[i][markCount[i]++]=e.color;}
        }
        String name=month.getMonth().getDisplayName(TextStyle.FULL_STANDALONE,Ui.locale());heading=name.substring(0,1).toUpperCase(Ui.locale())+name.substring(1)+" "+month.getYear();
        for(int i=0;i<7;i++)weekdays[i]=DayOfWeek.of(i+1).getDisplayName(TextStyle.SHORT,Ui.locale());
        date=selected.getDayOfWeek().getDisplayName(TextStyle.FULL,Ui.locale())+", "+selected.getDayOfMonth()+" "+selected.getMonth().getDisplayName(TextStyle.FULL,Ui.locale());
        rows.clear();java.util.List<CalendarProtocol.Event> agenda=events();total=agenda.size();float y=526;
        for(CalendarProtocol.Event e:agenda) {
            Entry row=new Entry();row.title=layout(e.title.isEmpty()?Ui.tr("Busy"):e.title,eventPaint,506,2);row.height=Math.max(56,row.title.getHeight())+12;if(y+row.height>698)break;
            row.y=y;row.color=e.color;row.start=e.allDay?Ui.tr("All day"):formatTime(e.start);row.end=e.allDay?"":formatTime(e.end);rows.add(row);y+=row.height;
        }
        visible=rows.size();empty=noEvents;emptyText=layout(empty,eventPaint,664,3);
    }
    java.util.List<CalendarProtocol.Event> events(){int i=(int)(selected.toEpochDay()-first.toEpochDay());return i>=0&&i<days.size()?days.get(i):Collections.emptyList();}
    private static String formatTime(long time){return java.time.format.DateTimeFormatter.ofPattern("HH:mm",Ui.locale()).format(Instant.ofEpochMilli(time).atZone(ZoneId.of("Asia/Yekaterinburg")));}
    void draw(Canvas c,boolean active) {
        c.drawText(heading,LEFT,108,title);
        small.setTextAlign(Paint.Align.CENTER);c.drawText("‹",968,107,title);c.drawText(Ui.tr("Today"),1047,106,small);c.drawText("›",1124,107,title);
        for(int i=0;i<7;i++)c.drawText(weekdays[i],LEFT+CELL*(i+.5f),148,small);
        day.setTextAlign(Paint.Align.CENTER);line.setColor(0xFF2C2F37);line.setStrokeWidth(1);
        for(int i=0;i<rowCount*7;i++) {
            if(!inside[i])continue;
            long epoch=first.toEpochDay()+i;float x=LEFT+CELL*(i%7+.5f),y=TOP+rowHeight*(i/7);
            boolean chosen=epoch==selected.toEpochDay();
            if(chosen){paint.setColor(active?0xFFB2ADA4:0xFF67645F);c.drawCircle(x,y+18,21,paint);}
            day.setColor(chosen?0xFF101114:epoch==today?0xFFD56A64:inside[i]?0xFF8F8B84:0xFF4A4845);c.drawText(numbers[i],x,y+28,day);
            int count=markCount[i];float width=count*7;
            for(int j=0;j<count;j++){paint.setColor(marks[i][j]);c.drawRoundRect(x-width/2+j*7,y+rowHeight-14,x-width/2+(j+1)*7,y+rowHeight-8,3,3,paint);}

        }
        for(int row=0;row<rowCount;row++)c.drawLine(LEFT,TOP+rowHeight*(row+1)-2,LEFT+WIDTH,TOP+rowHeight*(row+1)-2,line);
        small.setTextAlign(Paint.Align.LEFT);c.drawText(date,LEFT,508,small);
        if(rows.isEmpty()){c.save();c.translate(LEFT,538);emptyText.draw(c);c.restore();}
        small.setTextAlign(Paint.Align.RIGHT);
        for(int i=0;i<rows.size();i++){Entry row=rows.get(i);paint.setColor(row.color);c.drawRoundRect(LEFT,row.y+3,LEFT+6,row.y+row.height-12,3,3,paint);
            c.save();c.translate(LEFT+18,row.y);row.title.draw(c);c.restore();c.drawText(row.start,LEFT+WIDTH,row.y+25,small);c.drawText(row.end,LEFT+WIDTH,row.y+55,small);}
        small.setTextAlign(Paint.Align.LEFT);
    }
    LocalDate hit(float x,float y){int col=(int)((x-LEFT)/CELL),row=(int)((y-TOP)/rowHeight);if(x<LEFT||x>=LEFT+WIDTH||y<TOP||row<0||row>=rowCount)return null;LocalDate date=first.plusDays(row*7+col);return YearMonth.from(date).equals(month)?date:null;}
}
