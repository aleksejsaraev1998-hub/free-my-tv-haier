package io.freemytv;

final class CalendarStrings {
    static String translate(String code) {
        switch(code) {
            case "Calendar storage unavailable; enter credentials again": return Ui.tr("Calendar storage unavailable; enter credentials again");
            case "Saving calendar settings": return Ui.tr("Saving calendar settings");
            case "Could not save calendar settings": return Ui.tr("Could not save calendar settings");
            case "Updating calendar": return Ui.tr("Updating calendar");
            case "Calendar TLS connection failed": return Ui.tr("Calendar TLS connection failed");
            case "Calendar unavailable; check network and settings": return Ui.tr("Calendar unavailable; check network and settings");
            case "Calendar needs a clean HTTPS CalDAV URL": return Ui.tr("Calendar needs a clean HTTPS CalDAV URL");
            case "Use the CalDAV collection URL ending in /": return Ui.tr("Use the CalDAV collection URL ending in /");
            case "Check calendar login and password": return Ui.tr("Check calendar login and password");
            case "Calendar response is too large": return Ui.tr("Calendar response is too large");
            case "Invalid calendar response": return Ui.tr("Invalid calendar response");
            case "Calendar redirected; check the URL": return Ui.tr("Calendar redirected; check the URL");
            case "Calendar server rejected request": return Ui.tr("Calendar server rejected request");
            case "Unsupported calendar encoding": return Ui.tr("Unsupported calendar encoding");
            case "Calendar returned incomplete data": return Ui.tr("Calendar returned incomplete data");
            case "Too many calendar events": return Ui.tr("Too many calendar events");
            case "Calendar event has no start time": return Ui.tr("Calendar event has no start time");
            case "Invalid calendar event time": return Ui.tr("Invalid calendar event time");
            case "Unsupported calendar time zone": return Ui.tr("Unsupported calendar time zone");
            case "Unsupported calendar recurrence range": return Ui.tr("Unsupported calendar recurrence range");
            case "Calendar event duration is too long": return Ui.tr("Calendar event duration is too long");
            case "Calendar recurrence limit reached": return Ui.tr("Calendar recurrence limit reached");
            case "Unsupported calendar recurrence rule": return Ui.tr("Unsupported calendar recurrence rule");
            case "Too many calendars": return Ui.tr("Too many calendars");
            case "Finding calendars": return Ui.tr("Finding calendars");
            default: return code;
        }
    }
}
