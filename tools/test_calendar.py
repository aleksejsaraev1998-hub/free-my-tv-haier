#!/usr/bin/env python3
"""Compile production CalDAV code on the JDK; exercise protocol and date edge cases without an account."""
import os, subprocess, tempfile
from calendar_deps import ensure
from pathlib import Path
root = Path(__file__).resolve().parent
harness = r'''
package io.freemytv;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
public class CalendarProtocolTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
 static String event(String title,String dates,String extra){return "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:"+title+"\r\nSUMMARY:"+title+"\r\n"+dates+"\r\n"+extra+"\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";}
 static String xml(String ics){return "<d:multistatus xmlns:d='DAV:' xmlns:c='urn:ietf:params:xml:ns:caldav'><d:response><d:propstat><d:prop><c:calendar-data><![CDATA["+ics+"]]></c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";}
 interface Attempt {void run()throws Exception;}
 static void reject(Attempt a)throws Exception{try{a.run();}catch(Exception e){return;}throw new AssertionError("Should reject");}
 public static void main(String[] args)throws Exception {
  TimeZone.setDefault(TimeZone.getTimeZone("Asia/Yekaterinburg"));long start=Instant.parse("2026-10-24T19:00:00Z").toEpochMilli(),end=start+7*86400000L;
  String first=event("Встреча\\, план\\nРаботы", "DTSTART:20261025T050000Z\r\nDTEND:20261025T060000Z", "");
  List<CalendarProtocol.Event> list=CalendarProtocol.parseXml(utf(xml(first)),start,end);
  check(list.size()==1&&list.get(0).title.equals("Встреча, план\nРаботы"));
  check(CalendarProtocol.date("20261025T100000","DTSTART;TZID=Asia/Yekaterinburg")==list.get(0).start);
  check(CalendarProtocol.date("20261025T100000","DTSTART")==list.get(0).start);
  check(CalendarProtocol.parseIcs(event("Folded\r\n title", "DTSTART:20261025T050000Z", "DURATION:PT30M"),start,end).get(0).end==list.get(0).start+1800000);
  CalendarProtocol.Event day=CalendarProtocol.parseIcs(event("Day", "DTSTART;VALUE=DATE:20261025\r\nDTEND;VALUE=DATE:20261026", ""),start,end).get(0);
  check(day.allDay&&day.start==start&&day.end==start+86400000L);
  check(CalendarProtocol.parseIcs(event("Cancelled","DTSTART:20261025T050000Z","STATUS:CANCELLED"),start,end).isEmpty());
  check(CalendarProtocol.parseIcs(event("Old","DTSTART:20261020T050000Z",""),start,end).isEmpty());
  String expanded=first.replace("END:VCALENDAR\r\n", "")+event("Встреча\\, план\\nРаботы","DTSTART:20261026T050000Z\r\nDTEND:20261026T060000Z","RECURRENCE-ID:20261026T050000Z").replace("BEGIN:VCALENDAR\r\n", "");
  check(CalendarProtocol.parseXml(utf(xml(expanded)),start,end).size()==2);
  check(CalendarProtocol.parseIcs(event("Daily","DTSTART:20261020T050000Z","RRULE:FREQ=DAILY"),start,end).size()==7);
  check(CalendarProtocol.parseIcs(event("Expired","DTSTART:20261020T050000Z","RRULE:FREQ=DAILY;COUNT=3"),start,end).isEmpty());
  check(CalendarProtocol.parseIcs(event("Until","DTSTART:20261020T050000Z","RRULE:FREQ=DAILY;UNTIL=20261026T050000Z"),start,end).size()==2);
  check(CalendarProtocol.parseIcs(event("Workdays","DTSTART;TZID=Asia/Yekaterinburg:20200101T100000","RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;WKST=MO"),start,end).size()==5);
  list=CalendarProtocol.parseIcs(event("Excluded","DTSTART:20261020T050000Z","RRULE:FREQ=DAILY\r\nEXDATE:20261025T050000Z,20261027T050000Z"),start,end);
  check(list.size()==5&&list.get(0).start==Instant.parse("2026-10-26T05:00:00Z").toEpochMilli());
  check(CalendarProtocol.parseIcs(event("Exrule","DTSTART:20261020T050000Z","RRULE:FREQ=DAILY\r\nEXRULE:FREQ=WEEKLY;BYDAY=SU,SA"),start,end).size()==5);
  check(CalendarProtocol.parseIcs(event("Extra","DTSTART:20261025T050000Z","RDATE:20261026T050000Z,20261026T050000Z"),start,end).size()==2);
  list=CalendarProtocol.parseIcs(event("Period","DTSTART:20261025T050000Z","RDATE;VALUE=PERIOD:20261026T050000Z/PT2H"),start,end);
  check(list.size()==2&&list.get(1).end-list.get(1).start==7200000L);
  String series=event("Series","DTSTART;TZID=Asia/Yekaterinburg:20261020T100000\r\nDTEND;TZID=Asia/Yekaterinburg:20261020T110000","RRULE:FREQ=DAILY").replace("END:VCALENDAR\r\n", "");
  String moved="BEGIN:VEVENT\r\nUID:Series\r\nRECURRENCE-ID;TZID=Asia/Yekaterinburg:20261026T100000\r\nDTSTART;TZID=Asia/Yekaterinburg:20261026T130000\r\nSUMMARY:Moved\r\nEND:VEVENT\r\n";
  String cancelled="BEGIN:VEVENT\r\nUID:Series\r\nRECURRENCE-ID;TZID=Asia/Yekaterinburg:20261027T100000\r\nSTATUS:CANCELLED\r\nEND:VEVENT\r\n";
  list=CalendarProtocol.parseIcs(series+moved+cancelled+"END:VCALENDAR\r\n",start,end);
  check(list.size()==6);CalendarProtocol.Event move=list.stream().filter(e->e.title.equals("Moved")).findFirst().get();
  check(move.start==Instant.parse("2026-10-26T08:00:00Z").toEpochMilli()&&move.end-move.start==3600000L);
  String movedIn=moved.replace("20261026T100000","20261019T100000");
  check(CalendarProtocol.parseIcs(series+movedIn+"END:VCALENDAR\r\n",start,end).size()==8);
  reject(()->CalendarProtocol.parseIcs(series+moved.replace("RECURRENCE-ID;","RECURRENCE-ID;RANGE=THISANDFUTURE;")+"END:VCALENDAR\r\n",start,end));
  list=CalendarProtocol.parseIcs(event("Last weekday","DTSTART:20200131T050000Z","RRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1"),start,end);
  check(list.size()==1&&list.get(0).start==Instant.parse("2026-10-30T05:00:00Z").toEpochMilli());
  long march=Instant.parse("2026-03-28T00:00:00Z").toEpochMilli();
  list=CalendarProtocol.parseIcs(event("DST","DTSTART;TZID=Europe/Berlin:20260328T090000\r\nDURATION:PT1H","RRULE:FREQ=DAILY;COUNT=3"),march,march+3*86400000L);
  check(list.size()==3&&list.get(0).start==Instant.parse("2026-03-28T08:00:00Z").toEpochMilli()&&list.get(1).start==Instant.parse("2026-03-29T07:00:00Z").toEpochMilli());
  list=CalendarProtocol.parseIcs(event("DST day","DTSTART;TZID=Europe/Berlin:20260328T090000\r\nDURATION:P1D","RRULE:FREQ=DAILY;COUNT=2"),march,march+4*86400000L);
  check(list.get(0).end-list.get(0).start==23*3600000L);
  list=CalendarProtocol.parseIcs(event("All day daily","DTSTART;VALUE=DATE:20261020\r\nDTEND;VALUE=DATE:20261022","RRULE:FREQ=DAILY"),start,end);
  check(list.size()==8&&list.stream().allMatch(e->e.allDay&&e.end-e.start==2*86400000L));
  reject(()->CalendarProtocol.parseXml(utf("<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///etc/passwd'>]>"+xml(first)),start,end));
  reject(()->CalendarProtocol.parseXml(utf(xml(first).replace("200 OK","403 Forbidden")),start,end));
  reject(()->CalendarProtocol.parseXml(utf("<html/>"),start,end));
  reject(()->CalendarProtocol.endpoint("http://example.com/calendar/"));reject(()->CalendarProtocol.endpoint("https://user:password@example.com/calendar/"));reject(()->CalendarProtocol.endpoint("https://example.com/personal.ics"));
  check(!CalendarProtocol.query(start,end).contains("<c:expand ")&&CalendarProtocol.query(start,end).contains("start=\"20261024T190000Z\""));
  check(CalendarProtocol.date("20261025T100000","DTSTART;TZID=/inverse.ca/20091015_1/Asia/Yekaterinburg")==Instant.parse("2026-10-25T05:00:00Z").toEpochMilli());

  YearMonth october=YearMonth.of(2026,10);ZoneId localZone=ZoneId.of("Asia/Yekaterinburg");
  check(CalendarMonth.rows(october)==5&&CalendarMonth.rows(YearMonth.of(2021,2))==4&&CalendarMonth.rows(YearMonth.of(2026,8))==6);
  check(CalendarMonth.first(october).equals(LocalDate.of(2026,9,28)));
  long[] window=CalendarMonth.window(october,localZone);check(window[1]-window[0]==42*86400000L);
  check(CalendarMonth.first(YearMonth.of(2024,2)).plusDays(31).equals(LocalDate.of(2024,2,29)));
  check(CalendarMonth.shiftMonth(LocalDate.of(2026,1,31),1).equals(LocalDate.of(2026,2,28)));
  check(CalendarMonth.shiftMonth(LocalDate.of(2026,12,31),1).equals(LocalDate.of(2027,1,31)));
  long[] dstWindow=CalendarMonth.window(YearMonth.of(2026,3),ZoneId.of("Europe/Berlin"));check(dstWindow[1]-dstWindow[0]==42*86400000L-3600000L);
  CalendarProtocol.Event ending=new CalendarProtocol.Event();ending.start=start;ending.end=start+86400000L;
  check(CalendarMonth.overlaps(ending,LocalDate.of(2026,10,25),localZone));check(!CalendarMonth.overlaps(ending,LocalDate.of(2026,10,26),localZone));
  CalendarProtocol.Event point=new CalendarProtocol.Event();point.start=start;point.end=start;
  check(CalendarMonth.overlaps(point,LocalDate.of(2026,10,25),localZone));check(!CalendarMonth.overlaps(point,LocalDate.of(2026,10,24),localZone));
  check(CalendarMonth.day(Arrays.asList(ending,point),LocalDate.of(2026,10,25),localZone).size()==2);
  check(CalendarProtocol.resource("webcal://example.com/events.ics?token=test").toString().equals("https://example.com/events.ics?token=test"));
  reject(()->CalendarProtocol.resource("http://example.com/events.ics"));
  check(!CalendarProtocol.sameOrigin(CalendarProtocol.resource("https://example.com/"),CalendarProtocol.resource("https://example.com:8443/")));
  String found="<d:multistatus xmlns:d='DAV:' xmlns:c='urn:ietf:params:xml:ns:caldav' xmlns:a='http://apple.com/ns/ical/'>"
   +"<d:response><d:href>/dav/user/Calendar/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
   +"<d:response><d:href>/dav/user/Calendar/personal/</d:href><d:propstat><d:prop><d:resourcetype><c:calendar/></d:resourcetype><d:displayname>Мой &amp; рабочий</d:displayname><a:calendar-color>#123456AA</a:calendar-color></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat><d:propstat><d:prop><d:unknown/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>"
   +"<d:response><d:href>https://other.example/Calendar/stolen/</d:href><d:propstat><d:prop><d:resourcetype><c:calendar/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
   +"<d:response><d:href>/dav/user/Calendar/team/</d:href><d:propstat><d:prop><d:resourcetype><c:calendar/></d:resourcetype><d:displayname>Team</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";
  List<CalendarProtocol.Collection> foundCalendars=CalendarProtocol.collections(utf(found),new java.net.URI("https://example.com/dav/user/Calendar/"));
  check(foundCalendars.size()==2&&foundCalendars.get(0).name.equals("Мой & рабочий")&&foundCalendars.get(0).color==0xFF123456&&foundCalendars.get(1).url.endsWith("/team/"));
  check(Arrays.equals(CalendarProtocol.response(new ByteArrayInputStream(utf("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nICS")),200),utf("ICS")));

  String body=xml(first);byte[] bytes=utf(body);String header="HTTP/1.1 207 Multi-Status\r\nContent-Length: "+bytes.length+"\r\n\r\n";
  ByteArrayOutputStream response=new ByteArrayOutputStream();response.write(utf(header));response.write(bytes);
  check(Arrays.equals(CalendarProtocol.response(new ByteArrayInputStream(response.toByteArray())),bytes));
  response.reset();response.write(utf("HTTP/1.1 207 Multi-Status\r\nTransfer-Encoding: chunked\r\n\r\n"+Integer.toHexString(bytes.length)+";extension=1\r\n"));response.write(bytes);response.write(utf("\r\n0\r\n\r\n"));
  check(Arrays.equals(CalendarProtocol.response(new ByteArrayInputStream(response.toByteArray())),bytes));
  reject(()->CalendarProtocol.response(new ByteArrayInputStream(utf("HTTP/1.1 401 Unauthorized\r\n\r\n"))));
  reject(()->CalendarProtocol.response(new ByteArrayInputStream(utf("HTTP/1.1 302 Found\r\nLocation: https://other.example/\r\n\r\n"))));
  reject(()->CalendarProtocol.response(new ByteArrayInputStream(utf("HTTP/1.1 207 Multi-Status\r\nContent-Length: 5\r\n\r\n123"))));
  reject(()->CalendarProtocol.response(new ByteArrayInputStream(utf("HTTP/1.1 207 Multi-Status\r\nContent-Length: 99999999\r\n\r\n"))));
  System.out.println("PASS: CalDAV REPORT, HTTP framing, UTF-8, dates/time zones, all-day, duration, local recurrence, cancellation, bounds, auth, redirect/XML guards, calendar discovery, HTTPS ICS, month navigation and day boundaries");
 }
}
'''
with tempfile.TemporaryDirectory() as temp:
    temp = Path(temp)
    source = temp/'CalendarProtocolTest.java'
    source.write_text(harness)
    jdk = Path(os.environ['JAVA_HOME'])/'bin'
    jars=ensure(root/'.calendar-deps')
    classpath=os.pathsep.join(str(p) for p in jars)
    subprocess.run([str(jdk/'javac'), '-cp', classpath, '-encoding', 'UTF-8', '-d', str(temp), str(root/'bento/CalendarProtocol.java'), str(root/'bento/CalendarRecurrence.java'), str(root/'bento/CalendarMonth.java'), str(source)], check=True)
    subprocess.run([str(jdk/'java'), '-cp', str(temp)+os.pathsep+classpath, 'io.freemytv.CalendarProtocolTest'], check=True)
