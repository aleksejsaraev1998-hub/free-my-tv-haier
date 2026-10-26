package io.freemytv;

import android.graphics.*;
import android.text.*;
import java.time.*;
import java.time.format.TextStyle;
import java.util.*;

/** Phone-style month and selected-day agenda, using cached Canvas primitives and text layouts. */
final class MonthPanel {
    static final float LEFT=104,TOP=318,WIDTH=546,CELL=WIDTH/7;
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
    private final TextPaint title=text(34,0xFFF4F7FC),day=text(28,0xFFD1DBE9),small=text(24,0xFFAABBD0),eventPaint=text(30,0xFFF4F7FC);
    private final java.util.List<Entry> rows=new ArrayList<>();
    private String heading="",date="",empty="";private StaticLayout emptyText;
    int total,visible;
    private static final class Entry {StaticLayout title;String start,end;int color;float y,height;}
    private static TextPaint text(float size,int color){TextPaint p=new TextPaint(Paint.ANTI_ALIAS_FLAG);p.setTextSize(size);p.setColor(color);p.setTypeface(Ui.regular);return p;}
    private static StaticLayout layout(String s,TextPaint paint,int width,int lines){return StaticLayout.Builder.obtain(s,0,s.length(),paint,width).setIncludePad(false).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build();}
    void select(LocalDate date){selected=date;month=YearMonth.from(date);}
    void update(java.util.List<CalendarProtocol.Event> events,String noEvents) {
        rowCount=CalendarMonth.rows(month);rowHeight=330f/rowCount;today=LocalDate.now().toEpochDay();first=CalendarMonth.first(month);ZoneId zone=ZoneId.systemDefault();
        if(previous!=events||!month.equals(indexed)) {
            previous=events;indexed=month;days.clear();
            for(int i=0;i<42;i++){LocalDate d=first.plusDays(i);days.add(CalendarMonth.day(events,d,zone));numbers[i]=Integer.toString(d.getDayOfMonth());inside[i]=YearMonth.from(d).equals(month);markCount[i]=0;
                Set<String> ids=new HashSet<>();for(CalendarProtocol.Event e:days.get(i))if(ids.add(e.source)&&markCount[i]<6)marks[i][markCount[i]++]=e.color;}
        }
        String name=month.getMonth().getDisplayName(TextStyle.FULL_STANDALONE,Ui.locale());heading=name.substring(0,1).toUpperCase(Ui.locale())+name.substring(1)+" "+month.getYear();
        for(int i=0;i<7;i++)weekdays[i]=DayOfWeek.of(i+1).getDisplayName(TextStyle.SHORT,Ui.locale());
        date=selected.getDayOfMonth()+" "+selected.getMonth().getDisplayName(TextStyle.FULL,Ui.locale());
        rows.clear();java.util.List<CalendarProtocol.Event> agenda=events();total=agenda.size();float y=324;
        for(CalendarProtocol.Event e:agenda) {
            Entry row=new Entry();row.title=layout(e.title.isEmpty()?Ui.tr("Busy"):e.title,eventPaint,350,2);row.height=Math.max(56,row.title.getHeight())+12;if(y+row.height>652)break;
            row.y=y;row.color=e.color;row.start=e.allDay?Ui.tr("All day"):formatTime(e.start);row.end=e.allDay?"":formatTime(e.end);rows.add(row);y+=row.height;
        }
        visible=rows.size();empty=noEvents;emptyText=layout(empty,eventPaint,430,5);
    }
    java.util.List<CalendarProtocol.Event> events(){int i=(int)(selected.toEpochDay()-first.toEpochDay());return i>=0&&i<days.size()?days.get(i):Collections.emptyList();}
    private static String formatTime(long time){return java.time.format.DateTimeFormatter.ofPattern("HH:mm",Ui.locale()).format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));}
    void draw(Canvas c,boolean active) {
        c.drawText(heading,LEFT,274,title);
        small.setTextAlign(Paint.Align.CENTER);
        for(int i=0;i<7;i++)c.drawText(weekdays[i],LEFT+CELL*(i+.5f),312,small);
        day.setTextAlign(Paint.Align.CENTER);
        for(int i=0;i<rowCount*7;i++) {
            long epoch=first.toEpochDay()+i;float x=LEFT+CELL*(i%7+.5f),y=TOP+rowHeight*(i/7);
            boolean chosen=epoch==selected.toEpochDay();
            if(chosen){paint.setColor(active?0xFF168DFF:0xFF1268CD);c.drawRoundRect(x-27,y+1,x+27,y+44,10,10,paint);}
            day.setColor(chosen?0xFFFFFFFF:epoch==today?0xFF69CBFF:inside[i]?0xFFE1E8F4:0xFF7B8CA3);c.drawText(numbers[i],x,y+33,day);
            int count=markCount[i];float width=count*7;
            for(int j=0;j<count;j++){paint.setColor(marks[i][j]);c.drawCircle(x-width/2+j*7+3,y+rowHeight-5,2.5f,paint);}
        }
        line.setColor(0x406C829C);line.setStrokeWidth(1);c.drawLine(678,246,678,656,line);
        small.setTextAlign(Paint.Align.LEFT);c.drawText(selected.toEpochDay()==today?Ui.tr("Today"):date,712,274,title);
        c.drawLine(712,298,1168,298,line);
        if(rows.isEmpty()){c.save();c.translate(712,330);emptyText.draw(c);c.restore();}
        for(int i=0;i<rows.size();i++){Entry row=rows.get(i);paint.setColor(row.color);c.drawRoundRect(706,row.y,711,row.y+row.height-12,2,2,paint);c.drawText(row.start,724,row.y+25,small);c.save();c.translate(814,row.y);row.title.draw(c);c.restore();}
        small.setTextAlign(Paint.Align.LEFT);
    }
    LocalDate hit(float x,float y){int col=(int)((x-LEFT)/CELL),row=(int)((y-TOP)/rowHeight);if(x<LEFT||x>=LEFT+WIDTH||y<TOP||row<0||row>=rowCount)return null;LocalDate date=first.plusDays(row*7+col);return YearMonth.from(date).equals(month)?date:null;}
}
