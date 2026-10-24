package io.freemytv;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.net.ssl.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Read-only CalDAV REPORT, bounded HTTP/1.1 and iCalendar data. No Android dependencies. */
final class CalendarProtocol {
    static final int LIMIT=2*1024*1024;
    static final String DAV="DAV:", CAL="urn:ietf:params:xml:ns:caldav";
    static final DateTimeFormatter UTC=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    static final class Event {
        String title="",uid="",source="",sourceName="";int color=0xFFAA706C;long start,end;boolean allDay;
    }
    static final class Failure extends IOException {
        final String code;
        Failure(String value){super(value);code=value;}
    }
    static URI endpoint(String value) throws Exception {
        URI uri=resource(value);
        if(uri.getQuery()!=null)throw new Failure("Calendar needs a clean HTTPS CalDAV URL");
        String path=uri.getRawPath();
        if(path==null||!path.endsWith("/"))throw new Failure("Use the CalDAV collection URL ending in /");
        return uri;
    }
    static String query(long start,long end) {
        String range="start=\""+UTC.format(Instant.ofEpochMilli(start))+"\" end=\""+UTC.format(Instant.ofEpochMilli(end))+"\"";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><c:calendar-query xmlns:d=\"DAV:\" xmlns:c=\""+CAL+"\"><d:prop><c:calendar-data/></d:prop><c:filter><c:comp-filter name=\"VCALENDAR\"><c:comp-filter name=\"VEVENT\"><c:time-range "+range+"/></c:comp-filter></c:comp-filter></c:filter></c:calendar-query>";
    }
    static URI resource(String address) throws Exception {
        URI uri=new URI(address.trim().replaceFirst("^webcal:","https:"));
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getPort()==0||uri.getPort()>65535)
            throw new Failure("Calendar needs a clean HTTPS CalDAV URL");
        return new URI(uri.toASCIIString());
    }
    static boolean sameOrigin(URI a,URI b){return a.getScheme().equalsIgnoreCase(b.getScheme())&&a.getHost().equalsIgnoreCase(b.getHost())&&(a.getPort()<0?443:a.getPort())==(b.getPort()<0?443:b.getPort());}
    static List<Event> fetch(String address,String username,String password,long start,long end) throws Exception {
        return parseXml(request("REPORT",endpoint(address),username,password,query(start,end),207),start,end);
    }
    static List<Event> fetchIcs(String address,String username,String password,long start,long end) throws Exception {
        byte[] bytes=request("GET",resource(address),username,password,"",200);
        return parseIcs(new String(bytes,StandardCharsets.UTF_8).replace("\uFEFF",""),start,end);
    }
    static final class Collection {String url,name;int color;}
    static List<Collection> discover(String address,String user,String password) throws Exception {
        URI parent=endpoint(address).resolve("../");
        String body="<d:propfind xmlns:d=\"DAV:\" xmlns:c=\""+CAL+"\" xmlns:a=\"http://apple.com/ns/ical/\"><d:prop><d:resourcetype/><d:displayname/><a:calendar-color/></d:prop></d:propfind>";
        return collections(request("PROPFIND",parent,user,password,body,207),parent);
    }
    static List<Collection> collections(byte[] bytes,URI parent) throws Exception {
        Document doc=xml(bytes);List<Collection> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        NodeList responses=doc.getElementsByTagNameNS(DAV,"response");
        for(int i=0;i<responses.getLength();i++) {
            Element response=(Element)responses.item(i);NodeList hrefs=response.getElementsByTagNameNS(DAV,"href");if(hrefs.getLength()==0)continue;
            URI target=parent.resolve(hrefs.item(0).getTextContent().trim());
            if(!sameOrigin(parent,target))continue;try{endpoint(target.toString());}catch(Exception ignored){continue;}
            boolean calendar=false;String name="",color="";NodeList stats=response.getElementsByTagNameNS(DAV,"propstat");
            for(int j=0;j<stats.getLength();j++) {
                Element stat=(Element)stats.item(j);NodeList statuses=stat.getElementsByTagNameNS(DAV,"status");
                if(statuses.getLength()!=1||!statuses.item(0).getTextContent().contains(" 200"))continue;
                calendar|=stat.getElementsByTagNameNS(CAL,"calendar").getLength()>0;
                NodeList names=stat.getElementsByTagNameNS(DAV,"displayname");if(names.getLength()>0)name=names.item(0).getTextContent();
                NodeList colors=stat.getElementsByTagNameNS("http://apple.com/ns/ical/","calendar-color");if(colors.getLength()>0)color=colors.item(0).getTextContent();
            }
            if(calendar&&seen.add(target.toString())) {Collection c=new Collection();c.url=target.toString();c.name=name.isEmpty()?target.getPath():name;c.color=0xFFAA706C;
                if(color.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))c.color=0xFF000000|(int)Long.parseLong(color.substring(1,7),16);
                result.add(c);if(result.size()>100)throw new Failure("Too many calendars");
            }
        }
        return result;
    }
    private static byte[] request(String method,URI uri,String username,String password,String body,int expected) throws Exception {
        if(!username.isEmpty()&&(username.indexOf(':')>=0||username.indexOf('\r')>=0||username.indexOf('\n')>=0))throw new Failure("Check calendar login and password");
        byte[] payload=body.getBytes(StandardCharsets.UTF_8);String host=uri.getHost();int port=uri.getPort()<0?443:uri.getPort();
        // HttpURLConnection does not accept WebDAV verbs on Android. Platform TLS still verifies host and trust chain.
        try(Socket tcp=new Socket()) {
            tcp.connect(new InetSocketAddress(host,port),10000);tcp.setSoTimeout(12000);
            try(SSLSocket socket=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(tcp,host,port,true)) {
                SSLParameters params=socket.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(params);socket.setSoTimeout(12000);socket.startHandshake();
                String auth=username.isEmpty()?"":"Authorization: Basic "+Base64.getEncoder().encodeToString((username+":"+password).getBytes(StandardCharsets.UTF_8))+"\r\n";
                String path=uri.getRawPath();if(path==null||path.isEmpty())path="/";if(uri.getRawQuery()!=null)path+="?"+uri.getRawQuery();
                String request=method+" "+path+" HTTP/1.1\r\nHost: "+uri.getRawAuthority()+"\r\n"+auth+(method.equals("GET")?"":"Depth: 1\r\n")+"Content-Type: application/xml; charset=utf-8\r\nAccept: application/xml, text/calendar\r\nAccept-Encoding: identity\r\nUser-Agent: FreeMyTV-Haier-Calendar/2\r\nConnection: close\r\nContent-Length: "+payload.length+"\r\n\r\n";
                OutputStream out=socket.getOutputStream();out.write(request.getBytes(StandardCharsets.US_ASCII));out.write(payload);out.flush();return response(socket.getInputStream(),expected);
            }
        }
    }
    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();int b;
        while((b=in.read())!=-1) {
            if(b=='\n')return new String(out.toByteArray(),StandardCharsets.US_ASCII).replace("\r","");
            if(out.size()>=8192)throw new Failure("Calendar response is too large");out.write(b);
        }
        throw new EOFException();
    }
    static byte[] response(InputStream input) throws Exception {return response(input,207);}
    static byte[] response(InputStream input,int expected) throws Exception {
        InputStream in=new BufferedInputStream(input);
        String status=line(in);String[] parts=status.split(" ");
        if(parts.length<2||!parts[0].startsWith("HTTP/1."))throw new Failure("Invalid calendar response");
        int code=Integer.parseInt(parts[1]);
        if(code==401||code==403)throw new Failure("Check calendar login and password");
        if(code!=expected)throw new Failure(code>=300&&code<400?"Calendar redirected; check the URL":"Calendar server rejected request");
        Map<String,String> headers=new HashMap<>();int size=0;
        for(String h;!(h=line(in)).isEmpty();) {
            size+=h.length();if(size>32768)throw new Failure("Calendar response is too large");
            int colon=h.indexOf(':');if(colon<1)throw new Failure("Invalid calendar response");
            String name=h.substring(0,colon).toLowerCase(Locale.ROOT);if(headers.put(name,h.substring(colon+1).trim())!=null)throw new Failure("Invalid calendar response");
        }
        String encoding=headers.get("content-encoding");if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new Failure("Unsupported calendar encoding");
        ByteArrayOutputStream body=new ByteArrayOutputStream();String transfer=headers.get("transfer-encoding");
        if(transfer!=null) {
            if(!"chunked".equalsIgnoreCase(transfer))throw new Failure("Invalid calendar response");
            int chunks=0;
            while(true) {
                if(++chunks>100000)throw new Failure("Calendar response is too large");
                String chunk=line(in).split(";",2)[0].trim();long length=Long.parseLong(chunk,16);
                if(length<0||length>LIMIT-body.size())throw new Failure("Calendar response is too large");
                if(length==0)break;
                copy(in,body,(int)length);if(!line(in).isEmpty())throw new Failure("Invalid calendar response");
            }
        } else if(headers.containsKey("content-length")) {
            long length=Long.parseLong(headers.get("content-length"));if(length<0||length>LIMIT)throw new Failure("Calendar response is too large");copy(in,body,(int)length);
        } else {
            byte[] buf=new byte[8192];for(int n;(n=in.read(buf))!=-1;){if(body.size()+n>LIMIT)throw new Failure("Calendar response is too large");body.write(buf,0,n);}
        }
        return body.toByteArray();
    }
    private static void copy(InputStream in,OutputStream out,int length) throws IOException {
        byte[] buffer=new byte[8192];while(length>0){int n=in.read(buffer,0,Math.min(length,buffer.length));if(n<0)throw new EOFException();out.write(buffer,0,n);length-=n;}
    }
    private static Document xml(byte[] bytes) throws Exception {
        if(bytes.length>LIMIT)throw new Failure("Calendar response is too large");
        String xml=new String(bytes,StandardCharsets.UTF_8);
        // DTDs and entities are never needed in CalDAV. Reject before the platform parser sees them.
        String upper=xml.toUpperCase(Locale.ROOT);if(upper.contains("<!DOCTYPE")||upper.contains("<!ENTITY")||xml.indexOf('\0')>=0)throw new Failure("Invalid calendar response");
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder builder=f.newDocumentBuilder();builder.setEntityResolver((p,s)->{throw new org.xml.sax.SAXException("External entities disabled");});
        Document doc=builder.parse(new InputSource(new StringReader(xml)));
        Element root=doc.getDocumentElement();if(!DAV.equals(root.getNamespaceURI())||!"multistatus".equals(root.getLocalName()))throw new Failure("Invalid calendar response");
        return doc;
    }
    static List<Event> parseXml(byte[] bytes,long start,long end) throws Exception {
        Document doc=xml(bytes);
        List<Event> events=new ArrayList<>();Set<String> seen=new HashSet<>();
        NodeList statuses=doc.getElementsByTagNameNS(DAV,"propstat");
        for(int i=0;i<statuses.getLength();i++) {
            Element propstat=(Element)statuses.item(i);NodeList data=propstat.getElementsByTagNameNS(CAL,"calendar-data");
            NodeList status=propstat.getElementsByTagNameNS(DAV,"status");
            if(data.getLength()==0||status.getLength()!=1||!status.item(0).getTextContent().trim().matches("HTTP/\\S+\\s+200(?:\\s.*)?"))throw new Failure("Calendar returned incomplete data");
            for(int j=0;j<data.getLength();j++) for(Event event:parseIcs(data.item(j).getTextContent(),start,end)) {
                if(seen.add(event.uid+"\n"+event.start))events.add(event);
                if(events.size()>1000)throw new Failure("Too many calendar events");
            }
        }
        NodeList responses=doc.getElementsByTagNameNS(DAV,"response");
        for(int i=0;i<responses.getLength();i++)if(((Element)responses.item(i)).getElementsByTagNameNS(DAV,"propstat").getLength()==0)throw new Failure("Calendar returned incomplete data");
        events.sort(Comparator.comparingLong(e->e.start));return events;
    }
    static List<Event> parseIcs(String input,long from,long to) throws Exception {
        return CalendarRecurrence.expand(input,from,to);
    }
    static int colon(String row) {boolean quoted=false;for(int i=0;i<row.length();i++){char c=row.charAt(i);if(c=='"')quoted=!quoted;if(c==':'&&!quoted)return i;}return -1;}
    static long date(String value,String property) throws Exception {
        ZoneId zone=ZoneId.systemDefault();
        for(String param:property.split(";"))if(param.startsWith("TZID=")){String id=param.substring(5).replace("\"","");try{zone=zoneId(id);}catch(Exception e){throw new Failure("Unsupported calendar time zone");}}
        if(value.length()==8)return LocalDate.parse(value,DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(zone).toInstant().toEpochMilli();
        if(value.endsWith("Z")){zone=ZoneOffset.UTC;value=value.substring(0,value.length()-1);}
        return LocalDateTime.parse(value,DateTimeFormatter.ofPattern(value.length()==13?"yyyyMMdd'T'HHmm":"yyyyMMdd'T'HHmmss")).atZone(zone).toInstant().toEpochMilli();
    }
    static ZoneId zoneId(String id) throws Failure {
        try{return ZoneId.of(id);}catch(Exception ignored){}
        // Legacy SOGo/libical namespace prefixes still identify an IANA time zone.
        if(id.startsWith("/inverse.ca/")||id.startsWith("/freeassociation.sourceforge.net/Tzfile/")||id.startsWith("/mozilla.org/")) {
            for(int i=1;i<id.length();i++)if(id.charAt(i-1)=='/') {
                String suffix=id.substring(i);if(!suffix.contains("/"))continue;
                try{return ZoneId.of(suffix);}catch(Exception ignored){}
            }
        }
        throw new Failure("Unsupported calendar time zone");
    }
    static String unescape(String value) {StringBuilder b=new StringBuilder();for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c=='\\'&&i+1<value.length()){c=value.charAt(++i);if(c=='n'||c=='N')c='\n';}b.append(c);}return b.toString();}
}
