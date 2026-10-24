#!/usr/bin/env python3
"""Compile production CalDAV code on the JDK; exercise protocol and date edge cases without an account."""
import os, subprocess, tempfile
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
  String expanded=first+event("Встреча\\, план\\nРаботы","DTSTART:20261026T050000Z\r\nDTEND:20261026T060000Z","RECURRENCE-ID:20261026T050000Z");
  check(CalendarProtocol.parseXml(utf(xml(expanded)),start,end).size()==2);
  reject(()->CalendarProtocol.parseIcs(event("Daily","DTSTART:20261025T050000Z","RRULE:FREQ=DAILY"),start,end));
  reject(()->CalendarProtocol.parseXml(utf("<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///etc/passwd'>]>"+xml(first)),start,end));
  reject(()->CalendarProtocol.parseXml(utf(xml(first).replace("200 OK","403 Forbidden")),start,end));
  reject(()->CalendarProtocol.parseXml(utf("<html/>"),start,end));
  reject(()->CalendarProtocol.endpoint("http://example.com/calendar/"));reject(()->CalendarProtocol.endpoint("https://user:password@example.com/calendar/"));reject(()->CalendarProtocol.endpoint("https://example.com/personal.ics"));
  check(CalendarProtocol.query(start,end).contains("<c:expand ")&&CalendarProtocol.query(start,end).contains("start=\"20261024T190000Z\""));
  String body=xml(first);byte[] bytes=utf(body);String header="HTTP/1.1 207 Multi-Status\r\nContent-Length: "+bytes.length+"\r\n\r\n";
  ByteArrayOutputStream response=new ByteArrayOutputStream();response.write(utf(header));response.write(bytes);
  check(Arrays.equals(CalendarProtocol.response(new ByteArrayInputStream(response.toByteArray())),bytes));
  response.reset();response.write(utf("HTTP/1.1 207 Multi-Status\r\nTransfer-Encoding: chunked\r\n\r\n"+Integer.toHexString(bytes.length)+";extension=1\r\n"));response.write(bytes);response.write(utf("\r\n0\r\n\r\n"));
  check(Arrays.equals(CalendarProtocol.response(new ByteArrayInputStream(response.toByteArray())),bytes));
  reject(()->CalendarProtocol.response(new ByteArrayInputStream(utf("HTTP/1.1 401 Unauthorized\r\n\r\n"))));
  reject(()->CalendarProtocol.response(new ByteArrayInputStream(utf("HTTP/1.1 302 Found\r\nLocation: https://other.example/\r\n\r\n"))));
  reject(()->CalendarProtocol.response(new ByteArrayInputStream(utf("HTTP/1.1 207 Multi-Status\r\nContent-Length: 5\r\n\r\n123"))));
  reject(()->CalendarProtocol.response(new ByteArrayInputStream(utf("HTTP/1.1 207 Multi-Status\r\nContent-Length: 99999999\r\n\r\n"))));
  System.out.println("PASS: CalDAV REPORT, HTTP framing, UTF-8, dates/time zones, all-day, duration, expanded recurrence, cancellation, bounds, auth, redirect and XML guards");
 }
}
'''
with tempfile.TemporaryDirectory() as temp:
    temp = Path(temp)
    source = temp/'CalendarProtocolTest.java'
    source.write_text(harness)
    jdk = Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(jdk/'javac'), '-encoding', 'UTF-8', '-d', str(temp), str(root/'bento/CalendarProtocol.java'), str(source)], check=True)
    subprocess.run([str(jdk/'java'), '-cp', str(temp), 'io.freemytv.CalendarProtocolTest'], check=True)
