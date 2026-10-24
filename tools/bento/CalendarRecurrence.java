package io.freemytv;

import java.time.*;
import java.util.*;
import org.dmfs.rfc5545.DateTime;
import org.dmfs.rfc5545.recur.RecurrenceRule;
import org.dmfs.rfc5545.recur.RecurrenceRuleIterator;

/** RFC 5545 rule calculation is delegated to lib-recur, never approximated as fixed intervals. */
final class CalendarRecurrence {
    private static final long DAY=86400000L;
    private static final class Property {
        final String key,value;
        Property(String k,String v){key=k;value=v;}
    }
    private static final class Component {
        final Map<String,List<Property>> properties=new HashMap<>();
        String uid;long revision;int sequence;
        Property first(String name){List<Property> p=properties.get(name);return p==null?null:p.get(0);}
        String value(String name){Property p=first(name);return p==null?"":p.value;}
        List<Property> all(String name){List<Property> p=properties.get(name);return p==null?Collections.emptyList():p;}
        boolean cancelled(){return "CANCELLED".equalsIgnoreCase(value("STATUS"));}
    }
    private static final class Series {
        Component master;
        final Map<Long,Component> overrides=new HashMap<>();
    }
    private static final class Span {
        long milliseconds,days;
        org.dmfs.rfc5545.Duration nominal;
        long end(DateTime occurrence,ZoneId zone) {
            if(nominal!=null)return instant(occurrence.addDuration(nominal),zone);
            if(occurrence.isAllDay())return LocalDate.of(occurrence.getYear(),occurrence.getMonth()+1,occurrence.getDayOfMonth()).plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli();
            return Math.addExact(instant(occurrence,zone),milliseconds);
        }
    }
    static List<CalendarProtocol.Event> expand(String input,long from,long to) throws Exception {
        List<Component> components=components(input);Map<String,Series> series=new LinkedHashMap<>();
        for(Component c:components) {
            Series s=series.get(c.uid);if(s==null){s=new Series();series.put(c.uid,s);}
            Property rid=c.first("RECURRENCE-ID");
            if(rid==null){if(newer(c,s.master))s.master=c;}
            else {
                if(rid.key.toUpperCase(Locale.ROOT).contains("RANGE="))throw failure("Unsupported calendar recurrence range");
                long id=CalendarProtocol.date(rid.value,rid.key);
                if(newer(c,s.overrides.get(id)))s.overrides.put(id,c);
            }
        }
        List<CalendarProtocol.Event> result=new ArrayList<>();long deadline=System.nanoTime()+5000000000L;
        for(Series s:series.values()) {
            Component master=s.master;
            if(master!=null&&!master.cancelled()) {
                Property startProperty=master.first("DTSTART");
                if(startProperty==null)throw failure("Calendar event has no start time");
                ZoneId zone=zone(startProperty);DateTime seed=dateTime(startProperty);Span span=span(master,null);
                long original=instant(seed,zone),lookback=Math.max(DAY,span.end(seed,zone)-original)+2*DAY;
                if(lookback<0||lookback>3660*DAY)throw failure("Calendar event duration is too long");
                Set<Long> excluded=new HashSet<>();for(Property p:master.all("EXDATE"))for(String value:p.value.split(","))excluded.add(CalendarProtocol.date(value,p.key));
                for(Property p:master.all("EXRULE"))for(DateTime instance:occurrences(p.value,seed,from-lookback,to,zone,deadline))excluded.add(instant(instance,zone));
                Map<Long,DateTime> instances=new TreeMap<>();instances.put(original,seed);
                for(Property p:master.all("RRULE"))for(DateTime instance:occurrences(p.value,seed,from-lookback,to,zone,deadline))instances.put(instant(instance,zone),instance);
                Map<Long,Span> periodSpans=new HashMap<>();
                for(Property p:master.all("RDATE"))for(String value:p.value.split(",")) {
                    String[] period=value.split("/",2);Property start=new Property(p.key,period[0]);DateTime instance=dateTime(start);long at=instant(instance,zone(start));
                    instances.put(at,instance);
                    if(period.length==2) {
                        Component temporary=new Component();temporary.properties.put("DTSTART",Collections.singletonList(start));
                        String name=period[1].startsWith("P")?"DURATION":"DTEND";
                        temporary.properties.put(name,Collections.singletonList(new Property(name+zoneParameter(p),period[1])));periodSpans.put(at,span(temporary,null));
                    }
                }
                for(Map.Entry<Long,DateTime> item:instances.entrySet()) {
                    long at=item.getKey();if(excluded.contains(at)||s.overrides.containsKey(at))continue;
                    DateTime instance=item.getValue();Span duration=periodSpans.containsKey(at)?periodSpans.get(at):span;
                    CalendarProtocol.Event e=event(master,null,at,duration.end(instance,zone),instance.isAllDay());add(result,e,from,to);
                }
            }
            // Detached/moved instances are considered even when their original occurrence is outside the requested week.
            if(master==null||!master.cancelled())for(Component c:s.overrides.values()) {
                if(c.cancelled())continue;Property start=c.first("DTSTART");
                if(start==null)throw failure("Calendar event has no start time");
                DateTime instance=dateTime(start);ZoneId zone=zone(start);Span duration=span(c,master);
                add(result,event(c,master,instant(instance,zone),duration.end(instance,zone),instance.isAllDay()),from,to);
            }
            if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted())throw failure("Calendar recurrence limit reached");
        }
        result.sort(Comparator.comparingLong(e->e.start));return result;
    }
    private static List<DateTime> occurrences(String value,DateTime seed,long from,long to,ZoneId zone,long deadline) throws Exception {
        RecurrenceRule rule;
        try{rule=new RecurrenceRule(value,RecurrenceRule.RfcMode.RFC5545_LAX);}catch(Exception e){throw failure("Unsupported calendar recurrence rule");}
        // Sub-daily recurrence is valid but unsuitable for a TV agenda and could produce unbounded work in one week.
        String upper=value.toUpperCase(Locale.ROOT);
        if(upper.contains("FREQ=SECONDLY")||upper.contains("FREQ=MINUTELY"))throw failure("Calendar recurrence limit reached");
        RecurrenceRuleIterator iterator=rule.iterator(seed);iterator.fastForward(from-DAY);
        List<DateTime> result=new ArrayList<>();int count=0;
        while(iterator.hasNext()) {
            if(++count>10000||System.nanoTime()>deadline||Thread.currentThread().isInterrupted())throw failure("Calendar recurrence limit reached");
            DateTime instance=iterator.nextDateTime();long at=instant(instance,zone);
            if(at>=to)break;if(at>=from)result.add(instance);
        }
        return result;
    }
    private static CalendarProtocol.Event event(Component c,Component master,long start,long end,boolean allDay) throws Exception {
        if(end<start)throw failure("Invalid calendar event time");
        CalendarProtocol.Event e=new CalendarProtocol.Event();e.uid=c.uid;e.start=start;e.end=end;e.allDay=allDay;
        e.title=CalendarProtocol.unescape(c.first("SUMMARY")!=null?c.value("SUMMARY"):master==null?"":master.value("SUMMARY"));
        if(e.title.length()>500)e.title=e.title.substring(0,500);return e;
    }
    private static void add(List<CalendarProtocol.Event> result,CalendarProtocol.Event e,long from,long to) throws Exception {
        if(e.start<to&&(e.end>from||e.start>=from&&e.end==e.start))result.add(e);
        if(result.size()>1000)throw failure("Too many calendar events");
    }
    private static Span span(Component c,Component master) throws Exception {
        Property start=c.first("DTSTART");Span span=new Span();DateTime first=dateTime(start);ZoneId zone=zone(start);
        Property end=c.first("DTEND"),duration=c.first("DURATION");
        if(end==null&&duration==null&&master!=null)return span(master,null);
        if(end!=null) {
            DateTime last=dateTime(end);
            if(first.isAllDay()!=last.isAllDay())throw failure("Invalid calendar event time");
            if(first.isAllDay())span.days=java.time.temporal.ChronoUnit.DAYS.between(local(first),local(last));
            else span.milliseconds=CalendarProtocol.date(end.value,end.key)-instant(first,zone);
        }else if(duration!=null) {
            try{span.nominal=org.dmfs.rfc5545.Duration.parse(duration.value);}catch(Exception e){throw failure("Invalid calendar event time");}
        }else if(first.isAllDay())span.days=1;
        if(span.milliseconds<0||span.days<0||span.end(first,zone)<instant(first,zone))throw failure("Invalid calendar event time");
        return span;
    }
    private static List<Component> components(String input) throws Exception {
        if(!input.contains("BEGIN:VCALENDAR")||!input.contains("END:VCALENDAR"))throw failure("Invalid calendar response");
        List<Component> result=new ArrayList<>();Component event=null;int nested=0;
        for(String row:input.replace("\r\n","\n").replaceAll("\n[ \t]","").split("\n")) {
            if(row.equalsIgnoreCase("BEGIN:VEVENT")){if(event!=null)throw failure("Invalid calendar response");event=new Component();nested=0;continue;}
            if(event==null)continue;
            if(row.equalsIgnoreCase("END:VEVENT")) {
                event.uid=event.value("UID");if(event.uid.isEmpty())throw failure("Invalid calendar response");
                try{event.sequence=event.value("SEQUENCE").isEmpty()?0:Integer.parseInt(event.value("SEQUENCE"));}catch(Exception e){throw failure("Invalid calendar response");}
                Property revision=event.first("LAST-MODIFIED");if(revision==null)revision=event.first("DTSTAMP");
                if(revision!=null)event.revision=CalendarProtocol.date(revision.value,revision.key);
                result.add(event);if(result.size()>5000)throw failure("Too many calendar events");event=null;continue;
            }
            if(row.startsWith("BEGIN:")){nested++;continue;}if(row.startsWith("END:")){nested--;continue;}if(nested>0)continue;
            int colon=CalendarProtocol.colon(row);if(colon<0)continue;
            String key=row.substring(0,colon),name=key.split(";",2)[0].toUpperCase(Locale.ROOT),value=row.substring(colon+1);
            List<Property> values=event.properties.get(name);if(values==null){values=new ArrayList<>();event.properties.put(name,values);}values.add(new Property(key,value));
        }
        if(event!=null)throw failure("Invalid calendar response");return result;
    }
    private static boolean newer(Component a,Component b){return b==null||a.sequence>b.sequence||a.sequence==b.sequence&&a.revision>=b.revision;}
    private static ZoneId zone(Property p) throws Exception {
        if(p==null)throw failure("Calendar event has no start time");
        if(p.value.endsWith("Z"))return ZoneOffset.UTC;
        for(String param:p.key.split(";"))if(param.toUpperCase(Locale.ROOT).startsWith("TZID="))try{return CalendarProtocol.zoneId(param.substring(5).replace("\"",""));}catch(Exception e){throw failure("Unsupported calendar time zone");}
        return ZoneId.systemDefault();
    }
    private static String zoneParameter(Property p){for(String param:p.key.split(";"))if(param.toUpperCase(Locale.ROOT).startsWith("TZID="))return ";"+param;return "";}
    private static DateTime dateTime(Property p) throws Exception {
        if(p==null)throw failure("Calendar event has no start time");
        CalendarProtocol.date(p.value,p.key);String value=p.value;
        if(value.length()==13)value+="00";else if(value.length()==14&&value.endsWith("Z"))value=value.substring(0,13)+"00Z";
        return DateTime.parse(TimeZone.getTimeZone(zone(p)),value);
    }
    private static LocalDate local(DateTime time){return LocalDate.of(time.getYear(),time.getMonth()+1,time.getDayOfMonth());}
    private static long instant(DateTime time,ZoneId zone){return time.isAllDay()?local(time).atStartOfDay(zone).toInstant().toEpochMilli():time.getTimestamp();}
    private static CalendarProtocol.Failure failure(String code){return new CalendarProtocol.Failure(code);}
}
