package com.ash.attendance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies tables and label/value pairs copied from a portal page the user opened.
 * Pure Java on purpose (no Android imports) so test.sh can run it on a plain JVM.
 * The real portal pages have not been seen, so everything is detected by header keywords and
 * cell shapes, not by fixed column positions.
 */
final class Campus {
    private Campus() { }

    enum Kind { DAY, HOSTEL, PASSES, MONTHLY, GENERIC, NONE }

    static final class Table {
        String caption = "";
        List<String> headers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();

        Table() { }

        Table(String caption, List<String> headers, List<List<String>> rows) {
            this.caption = caption == null ? "" : caption;
            this.headers = headers;
            this.rows = rows;
        }
    }

    static final class Page {
        String path = "", title = "";
        List<Table> tables = new ArrayList<>();
        List<String[]> pairs = new ArrayList<>();
    }

    static final class Result {
        Kind kind = Kind.NONE;
        /** date (yyyy-MM-dd), course, hour, status (PRESENT/ABSENT/OD/OTHER) */
        final List<Map<String, String>> day = new ArrayList<>();
        /** month (yyyy-MM or raw), course, attended, total, pct */
        final List<Map<String, String>> monthly = new ArrayList<>();
        /** type, from, to, status, reason */
        final List<Map<String, String>> passes = new ArrayList<>();
        /** date (yyyy-MM-dd), status (PRESENT/ABSENT/LEAVE/PASS/OTHER), slot */
        final List<Map<String, String>> hostelAtt = new ArrayList<>();
        final Map<String, String> info = new LinkedHashMap<>();
        String homePass; // a number, or null
        final List<Table> generic = new ArrayList<>();

        boolean hasHostel() { return !info.isEmpty() || homePass != null || !hostelAtt.isEmpty(); }
    }

    static String label(Kind k) {
        switch (k) {
            case DAY: return "Day attendance";
            case HOSTEL: return "Hostel";
            case PASSES: return "Passes";
            case MONTHLY: return "Monthly attendance";
            default: return "Page";
        }
    }

    // ------------------------------------------------------------------------------------------ classify

    static Result classify(Page page) {
        Result r = new Result();
        String ctx = (page.path + " " + page.title).toLowerCase(Locale.ROOT);
        boolean hostelCtx = ctx.contains("hostel");
        boolean passCtx = ctx.contains("pass");

        for (String[] p : page.pairs) addPair(r, p[0], p[1]);

        for (Table raw : page.tables) {
            Table t = normalize(raw);
            if (t.rows.isEmpty()) continue;
            if (twoColumnInfo(r, t)) continue;
            if (passesTable(r, t, passCtx)) continue;
            if (monthlyTable(r, t)) continue;
            if (wideDayTable(r, t, hostelCtx)) continue;
            if (longDayTable(r, t, hostelCtx)) continue;
            r.generic.add(t);
        }

        if (!r.day.isEmpty()) r.kind = Kind.DAY;
        else if (r.hasHostel()) r.kind = Kind.HOSTEL;
        else if (!r.passes.isEmpty()) r.kind = Kind.PASSES;
        else if (!r.monthly.isEmpty()) r.kind = Kind.MONTHLY;
        else if (!r.generic.isEmpty()) r.kind = Kind.GENERIC;
        return r;
    }

    /** If the table has no header row, promote the first row when it holds no status-like cell. */
    static Table normalize(Table t) {
        List<String> h = new ArrayList<>();
        for (String s : t.headers) h.add(s == null ? "" : s.trim());
        boolean blank = true;
        for (String s : h) if (!s.isEmpty()) blank = false;
        List<List<String>> rows = new ArrayList<>();
        for (List<String> row : t.rows) {
            List<String> c = new ArrayList<>();
            for (String s : row) c.add(s == null ? "" : s.trim());
            rows.add(c);
        }
        if (blank && rows.size() >= 2) {
            boolean data = false;
            for (String s : rows.get(0)) if (status(s) != null) data = true;
            if (!data) { h = rows.get(0); rows = new ArrayList<>(rows.subList(1, rows.size())); }
        }
        return new Table(t.caption, h, rows);
    }

    // ------------------------------------------------------------------------------------------ pairs / info

    private static final Pattern INFO_KEY = Pattern.compile(
            "hostel|block|room|\\bbed\\b|\\bcot\\b|warden|floor|mess|\\bwing\\b|building|roommate|caretaker|in-?charge");
    private static final Pattern HOME_PASS = Pattern.compile("home\\s*-?\\s*pass", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private static void addPair(Result r, String label, String value) {
        if (label == null || value == null) return;
        String l = label.trim().replaceAll("[:\\s]+$", ""), v = value.trim();
        if (l.isEmpty() || v.isEmpty()) return;
        String lower = l.toLowerCase(Locale.ROOT);
        if (HOME_PASS.matcher(l).find() || HOME_PASS.matcher(v).find()) {
            Matcher m = NUMBER.matcher(v);
            boolean found = m.find();
            if (!found) { m = NUMBER.matcher(l); found = m.find(); }
            if (r.homePass == null && found) r.homePass = m.group();
            return;
        }
        if (INFO_KEY.matcher(lower).find() && r.info.size() < 30 && !r.info.containsKey(l)) r.info.put(l, v);
    }

    private static boolean twoColumnInfo(Result r, Table t) {
        for (List<String> row : t.rows) if (row.size() != 2) return false;
        boolean hit = false;
        for (List<String> row : t.rows) {
            String l = row.get(0).toLowerCase(Locale.ROOT);
            if (INFO_KEY.matcher(l).find() || HOME_PASS.matcher(l).find()) hit = true;
        }
        if (!hit) return false;
        for (List<String> row : t.rows) addPair(r, row.get(0), row.get(1));
        return true;
    }

    // ------------------------------------------------------------------------------------------ passes

    private static boolean passesTable(Result r, Table t, boolean passCtx) {
        List<String> h = t.headers;
        int type = -1, status = -1, from = -1, to = -1, date = -1, reason = -1;
        for (int i = 0; i < h.size(); i++) {
            String s = h.get(i).toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            if (status < 0 && s.matches(".*(status|state|approval).*")) status = i;
            else if (reason < 0 && s.matches(".*(reason|purpose|remark|destination).*")) reason = i;
            else if (from < 0 && s.matches(".*(from|\\bout\\b|leaving|depart|start).*")) from = i;
            else if (to < 0 && s.matches(".*(\\bto\\b|\\bin\\b|return|arriv|\\bend\\b|till|until).*")) to = i;
            else if (type < 0 && s.matches(".*(type|pass|category|leave).*") && !s.matches(".*(\\bno\\b|number|\\bid\\b|sl).*")) type = i;
            else if (date < 0 && s.contains("date")) date = i;
        }
        if (type < 0 || status < 0 || (from < 0 && to < 0 && date < 0)) return false;
        boolean passLike = passCtx;
        for (String s : h) if (s.toLowerCase(Locale.ROOT).contains("pass")) passLike = true;
        for (List<String> row : t.rows)
            if (cell(row, type).toLowerCase(Locale.ROOT).matches(".*(pass|leave|outing|gate|home).*")) passLike = true;
        if (!passLike) return false;
        if (from < 0) from = date;
        int added = 0;
        for (List<String> row : t.rows) {
            if (cell(row, type).isEmpty() && cell(row, from).isEmpty() && cell(row, status).isEmpty()) continue;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("type", cell(row, type));
            m.put("from", cell(row, from));
            m.put("to", cell(row, to));
            m.put("status", cell(row, status));
            m.put("reason", cell(row, reason));
            r.passes.add(m);
            added++;
        }
        return added > 0;
    }

    // ------------------------------------------------------------------------------------------ monthly

    private static boolean monthlyTable(Result r, Table t) {
        List<String> h = t.headers;
        int month = -1, course = -1, att = -1, total = -1, pct = -1;
        for (int i = 0; i < h.size(); i++) {
            String s = h.get(i).toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            if (month < 0 && s.contains("month")) month = i;
            else if (pct < 0 && s.matches(".*(%|percent|pct).*")) pct = i;
            else if (att < 0 && s.matches(".*(attend|present|attd).*")) att = i;
            else if (total < 0 && s.matches(".*(total|conducted|held|classes).*")) total = i;
            else if (course < 0 && s.matches(".*(course|subject|paper|code).*")) course = i;
        }
        if (month < 0 || (att < 0 && total < 0 && pct < 0)) return false;
        int added = 0;
        for (List<String> row : t.rows) {
            String m = cell(row, month);
            if (m.isEmpty()) continue;
            String mk = parseMonth(m);
            String a = num(cell(row, att)), tot = num(cell(row, total)), p = num(cell(row, pct));
            if (a.isEmpty() && tot.isEmpty() && p.isEmpty()) continue;
            if (p.isEmpty() && !a.isEmpty() && !tot.isEmpty() && Double.parseDouble(tot) > 0)
                p = String.format(Locale.ROOT, "%.2f", Double.parseDouble(a) * 100 / Double.parseDouble(tot));
            Map<String, String> rec = new LinkedHashMap<>();
            rec.put("month", mk != null ? mk : m);
            rec.put("course", cell(row, course));
            rec.put("attended", a);
            rec.put("total", tot);
            rec.put("pct", p);
            r.monthly.add(rec);
            added++;
        }
        return added > 0;
    }

    // ------------------------------------------------------------------------------------------ day attendance

    private static boolean longDayTable(Result r, Table t, boolean hostelCtx) {
        int date = dateColumn(t, -1);
        if (date < 0) return false;
        int status = statusColumn(t, date);
        if (status < 0) return false;
        int course = -1, course2 = -1, hour = -1;
        for (int i = 0; i < t.headers.size(); i++) {
            if (i == date || i == status) continue;
            String s = t.headers.get(i).toLowerCase(Locale.ROOT);
            if (s.matches(".*(course|subject|paper|code).*")) { if (course < 0) course = i; else if (course2 < 0) course2 = i; }
            else if (hour < 0 && s.matches(".*(hour|period|slot|session|\\btime\\b).*")) hour = i;
        }
        boolean hostel = hostelCtx && course < 0 && hour < 0;
        String lastDate = "";
        int added = 0;
        for (List<String> row : t.rows) {
            String d = parseDate(cell(row, date));
            if (d == null) d = cell(row, date).isEmpty() ? lastDate : null; // rowspan: blank means "same day"
            String st = status(cell(row, status));
            if (d == null || d.isEmpty() || st == null) continue;
            lastDate = d;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("date", d);
            if (hostel) {
                m.put("status", hostelStatus(cell(row, status)));
                m.put("slot", "");
                r.hostelAtt.add(m);
            } else {
                String c = cell(row, course);
                if (course2 >= 0 && !cell(row, course2).isEmpty() && !c.equals(cell(row, course2)))
                    c = c.isEmpty() ? cell(row, course2) : c + " \u00B7 " + cell(row, course2);
                m.put("course", c);
                m.put("hour", cell(row, hour));
                m.put("status", st);
                r.day.add(m);
            }
            added++;
        }
        return added > 0;
    }

    /** Rows = dates, columns = hours; or rows = courses, columns = dates. */
    private static boolean wideDayTable(Result r, Table t, boolean hostelCtx) {
        int cols = t.headers.size();
        for (List<String> row : t.rows) cols = Math.max(cols, row.size());
        if (cols < 3) return false;

        // Layout A: a date column and two or more columns full of status letters.
        int date = dateColumn(t, -1);
        if (date >= 0) {
            List<Integer> marks = new ArrayList<>();
            for (int c = 0; c < cols; c++) if (c != date && tokenShare(t, c) >= 0.6) marks.add(c);
            if (marks.size() >= 2) {
                int added = 0;
                for (List<String> row : t.rows) {
                    String d = parseDate(cell(row, date));
                    if (d == null) continue;
                    for (int c : marks) {
                        String st = status(cell(row, c));
                        if (st == null) continue;
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("date", d);
                        String head = c < t.headers.size() ? t.headers.get(c) : "";
                        if (hostelCtx) {
                            m.put("status", hostelStatus(cell(row, c)));
                            m.put("slot", head);
                            r.hostelAtt.add(m);
                        } else {
                            m.put("course", "");
                            m.put("hour", head);
                            m.put("status", st);
                            r.day.add(m);
                        }
                        added++;
                    }
                }
                if (added > 0) return true;
            }
        }

        // Layout B: dates across the header row, course names down the first column.
        int dateHeads = 0;
        for (int c = 1; c < t.headers.size(); c++) if (parseDate(t.headers.get(c)) != null) dateHeads++;
        if (dateHeads >= 2 && dateHeads >= 0.6 * (t.headers.size() - 1)) {
            int added = 0;
            for (List<String> row : t.rows) {
                String course = cell(row, 0);
                for (int c = 1; c < t.headers.size(); c++) {
                    String d = parseDate(t.headers.get(c));
                    String st = status(cell(row, c));
                    if (d == null || st == null) continue;
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("date", d);
                    m.put("course", course);
                    m.put("hour", "");
                    m.put("status", st);
                    r.day.add(m);
                    added++;
                }
            }
            return added > 0;
        }
        return false;
    }

    /** Column holding dates: header says "date" with at least one parsable cell, or 60% of cells parse. */
    private static int dateColumn(Table t, int skip) {
        int cols = 0;
        for (List<String> row : t.rows) cols = Math.max(cols, row.size());
        int best = -1;
        for (int c = 0; c < cols; c++) {
            if (c == skip) continue;
            int n = 0, ok = 0;
            for (List<String> row : t.rows) {
                String s = cell(row, c);
                if (s.isEmpty()) continue;
                n++;
                if (parseDate(s) != null) ok++;
            }
            boolean named = c < t.headers.size() && t.headers.get(c).toLowerCase(Locale.ROOT).contains("date");
            if (n > 0 && ((named && ok > 0) || ok >= 0.6 * n)) {
                if (named) return c;
                if (best < 0) best = c;
            }
        }
        return best;
    }

    private static int statusColumn(Table t, int skip) {
        int cols = t.headers.size();
        for (List<String> row : t.rows) cols = Math.max(cols, row.size());
        int best = -1;
        for (int c = 0; c < cols; c++) {
            if (c == skip || tokenShare(t, c) < 0.6) continue;
            boolean named = c < t.headers.size()
                    && t.headers.get(c).toLowerCase(Locale.ROOT).matches(".*(status|attendance|present|absent|p/a).*");
            if (named) return c;
            if (best < 0) best = c;
        }
        return best;
    }

    private static double tokenShare(Table t, int c) {
        int n = 0, ok = 0;
        for (List<String> row : t.rows) {
            String s = cell(row, c);
            if (s.isEmpty()) continue;
            n++;
            if (status(s) != null) ok++;
        }
        return n == 0 ? 0 : (double) ok / n;
    }

    // ------------------------------------------------------------------------------------------ cells

    private static String cell(List<String> row, int i) {
        return i < 0 || i >= row.size() || row.get(i) == null ? "" : row.get(i).trim();
    }

    /** First number in the text, or "". */
    private static String num(String s) {
        Matcher m = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(s);
        return m.find() ? m.group() : "";
    }

    /** PRESENT / ABSENT / OD / OTHER for a status cell, or null when the cell is not a status word. */
    static String status(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 20 || !s.matches("[A-Za-z .()/]+")) return null;
        String k = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        switch (k) {
            case "p": case "pr": case "pres": case "present": return "PRESENT";
            case "a": case "ab": case "abs": case "absent": return "ABSENT";
            case "od": case "dl": case "onduty": case "dutyleave": return "OD";
            case "ml": case "l": case "lv": case "leave": case "onleave": case "medical": case "medicalleave":
            case "h": case "holiday": case "hol": case "pass": case "onpass": case "homepass": case "gatepass":
                return "OTHER";
            default: return null;
        }
    }

    static String hostelStatus(String raw) {
        String k = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (k.contains("pass")) return "PASS";
        if (k.contains("leave") || k.equals("l") || k.equals("lv")) return "LEAVE";
        String s = status(raw);
        return s == null || s.equals("OD") ? "OTHER" : s;
    }

    // ------------------------------------------------------------------------------------------ dates

    private static final Pattern D_ISO = Pattern.compile("(?<!\\d)(\\d{4})[/.\\-](\\d{1,2})[/.\\-](\\d{1,2})(?!\\d)");
    private static final Pattern D_NUM = Pattern.compile("(?<!\\d)(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{4}|\\d{2})(?!\\d)");
    private static final Pattern D_MON = Pattern.compile(
            "(?<!\\d)(\\d{1,2})(?:st|nd|rd|th)?[ \\-/.,]+([A-Za-z]{3,9})[ \\-/.,]+(\\d{4}|\\d{2})(?!\\d)");
    private static final String MONTHS = "janfebmaraprmayjunjulaugsepoctnovdec";

    /** yyyy-MM-dd for dates written 12/11/2026, 12-11-2026, 2026-11-12, 12 Nov 2026, 12-Nov-26 (day first), else null. */
    static String parseDate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 40) return null;
        Matcher m = D_ISO.matcher(s);
        if (m.find()) return iso(m.group(1), m.group(2), m.group(3));
        m = D_NUM.matcher(s);
        if (m.find()) return iso(m.group(3), m.group(2), m.group(1));
        m = D_MON.matcher(s);
        while (m.find()) {
            int mo = monthNo(m.group(2));
            if (mo > 0) return iso(m.group(3), String.valueOf(mo), m.group(1));
        }
        return null;
    }

    private static int monthNo(String name) {
        if (name.length() < 3) return 0;
        int i = MONTHS.indexOf(name.substring(0, 3).toLowerCase(Locale.ROOT));
        return i >= 0 && i % 3 == 0 ? i / 3 + 1 : 0;
    }

    private static String iso(String y, String mo, String d) {
        int year = Integer.parseInt(y), month = Integer.parseInt(mo), day = Integer.parseInt(d);
        if (y.length() == 2) year += 2000;
        if (year < 2000 || year > 2100 || month < 1 || month > 12 || day < 1) return null;
        int[] len = {31, (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        if (day > len[month - 1]) return null;
        return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);
    }

    /** yyyy-MM for "November 2026", "Nov-26", "11/2026", "2026-11" or a full date; null if unknown. */
    static String parseMonth(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        String d = parseDate(s);
        if (d != null) return d.substring(0, 7);
        Matcher m = Pattern.compile("(?<!\\d)(\\d{4})[/.\\-](\\d{1,2})(?!\\d)").matcher(s);
        if (m.find()) return ym(m.group(1), m.group(2));
        m = Pattern.compile("(?<!\\d)(\\d{1,2})[/.\\-](\\d{4})(?!\\d)").matcher(s);
        if (m.find()) return ym(m.group(2), m.group(1));
        m = Pattern.compile("([A-Za-z]{3,9})[ \\-/.,']*(\\d{4}|\\d{2})(?!\\d)").matcher(s);
        if (m.find() && monthNo(m.group(1)) > 0) return ym(m.group(2), String.valueOf(monthNo(m.group(1))));
        m = Pattern.compile("(\\d{4})[ \\-/.,]*([A-Za-z]{3,9})").matcher(s);
        if (m.find() && monthNo(m.group(2)) > 0) return ym(m.group(1), String.valueOf(monthNo(m.group(2))));
        return null;
    }

    private static String ym(String y, String mo) {
        int year = Integer.parseInt(y), month = Integer.parseInt(mo);
        if (y.length() == 2) year += 2000;
        if (year < 2000 || year > 2100 || month < 1 || month > 12) return null;
        return String.format(Locale.ROOT, "%04d-%02d", year, month);
    }

    // ------------------------------------------------------------------------------------------ merging

    /** Adds incoming day records; one with the same date + course + hour replaces the older one. */
    static List<Map<String, String>> mergeDay(List<Map<String, String>> old, List<Map<String, String>> incoming) {
        Map<String, Map<String, String>> byKey = new LinkedHashMap<>();
        for (Map<String, String> m : old) byKey.put(dayKey(m), m);
        for (Map<String, String> m : incoming) byKey.put(dayKey(m), m);
        return new ArrayList<>(byKey.values());
    }

    private static String dayKey(Map<String, String> m) {
        return (m.get("date") + "|" + nz(m.get("course")) + "|" + nz(m.get("hour"))).toLowerCase(Locale.ROOT);
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }
}
