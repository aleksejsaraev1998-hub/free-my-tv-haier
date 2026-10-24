package io.freemytv;

import java.time.*;
import java.util.*;

/** Monday-first six-row grid, shared by the Canvas, loading windows and tests. */
final class CalendarMonth {
    static int rows(YearMonth month){return (month.atDay(1).getDayOfWeek().getValue()-1+month.lengthOfMonth()+6)/7;}
    static LocalDate first(YearMonth month){LocalDate day=month.atDay(1);return day.minusDays(day.getDayOfWeek().getValue()-1);}
    static long[] window(YearMonth month,ZoneId zone){LocalDate first=first(month);return new long[]{first.atStartOfDay(zone).toInstant().toEpochMilli(),first.plusDays(42).atStartOfDay(zone).toInstant().toEpochMilli()};}
    static boolean overlaps(CalendarProtocol.Event e,LocalDate day,ZoneId zone){long from=day.atStartOfDay(zone).toInstant().toEpochMilli(),to=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();return e.start<to&&(e.end>from||e.start>=from&&e.end==e.start);}
    static List<CalendarProtocol.Event> day(List<CalendarProtocol.Event> events,LocalDate day,ZoneId zone){long from=day.atStartOfDay(zone).toInstant().toEpochMilli(),to=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();List<CalendarProtocol.Event> list=new ArrayList<>();for(CalendarProtocol.Event e:events)if(e.start<to&&(e.end>from||e.start>=from&&e.end==e.start))list.add(e);list.sort(Comparator.<CalendarProtocol.Event>comparingInt(e->e.allDay?0:1).thenComparingLong(e->e.start));return list;}
    static LocalDate shiftMonth(LocalDate day,int step){return day.plusMonths(step);}
}
