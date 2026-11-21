package com.ash.attendance;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** The Campus tab: imported day attendance, hostel details, passes and other saved pages, drawn as glass cards. */
final class CampusTab {
    static final String PORTAL = "https://" + MainActivity.HOST + "/";
    private static final int SHOWN = 8;

    private final MainActivity a;
    private final SharedPreferences p;
    private String month;                       // picked class-attendance month, yyyy-MM
    private final Set<String> open = new HashSet<>();   // expanded generic tables
    private final Set<String> all = new HashSet<>();    // sections showing every row

    CampusTab(MainActivity a) {
        this.a = a;
        this.p = a.prefs;
    }

    void render(boolean animate) {
        LinearLayout list = a.list;
        list.removeAllViews();
        top(list);
        classAttendance(list);
        hostel(list);
        generic(list);
        filePass(list);
        a.status.setText("Read-only copies of pages you opened");
        if (animate) Glass.enter(list);
    }

    // ------------------------------------------------------------------------------------------ cards

    private void top(LinearLayout list) {
        LinearLayout c = a.card();
        c.addView(a.text("Campus", 16, MainActivity.TXT, true));
        TextView t = a.text("Open a portal page in the app browser, then tap “Use this page” to keep a copy "
                + "here. Nothing is sent anywhere.", 13, MainActivity.DIM, false);
        t.setPadding(0, a.dp(5), 0, 0);
        c.addView(t);
        c.addView(button("Open portal in app browser", true, v -> a.openCampusBrowser(PORTAL)), gap(13));
        if (officialInstalled()) c.addView(button("Open myAmrita", false, v -> a.openOfficial()), gap(8));
        list.addView(c);
    }

    private void filePass(LinearLayout list) {
        list.addView(header("Passes and leave", null));
        LinearLayout c = a.card();
        c.addView(a.text("File a pass or leave", 16, MainActivity.TXT, true));
        TextView t = a.text("Passes are filed on the official form so the warden can trace them.", 13,
                MainActivity.DIM, false);
        t.setPadding(0, a.dp(5), 0, 0);
        c.addView(t);
        boolean app = officialInstalled();
        c.addView(button(app ? "Open myAmrita" : "Open portal in app browser", true,
                v -> { if (app) a.openOfficial(); else a.openCampusBrowser(PORTAL); }), gap(13));
        list.addView(c);
    }

    // ------------------------------------------------------------------------------------------ class attendance

    private void classAttendance(LinearLayout list) {
        List<Map<String, String>> day = CampusStore.records(p.getString("campus_day", null));
        List<Map<String, String>> monthly = CampusStore.records(p.getString("campus_monthly", null));
        list.addView(header("Class attendance by day", day.isEmpty() ? null : p.getLong("campus_day_time", 0)));
        if (day.isEmpty()) {
            list.addView(hint("No day-wise attendance yet. Open your day-wise or monthly attendance page in the app "
                    + "browser and tap “Use this page”. Import one month at a time and they add up here."));
        } else {
            TreeSet<String> months = new TreeSet<>(Collections.reverseOrder());
            for (Map<String, String> m : day) months.add(m.get("date").substring(0, 7));
            String now = new SimpleDateFormat("yyyy-MM", Locale.ROOT).format(new Date());
            if (month == null || !months.contains(month)) month = months.contains(now) ? now : months.first();
            list.addView(monthChips(months));

            List<Map<String, String>> rows = new ArrayList<>();
            for (Map<String, String> m : day) if (m.get("date").startsWith(month)) rows.add(m);
            monthTotals(list, rows);
            TreeMap<String, List<Map<String, String>>> byDay = new TreeMap<>(Collections.reverseOrder());
            for (Map<String, String> m : rows) {
                List<Map<String, String>> l = byDay.get(m.get("date"));
                if (l == null) byDay.put(m.get("date"), l = new ArrayList<>());
                l.add(m);
            }
            for (Map.Entry<String, List<Map<String, String>>> e : byDay.entrySet()) list.addView(dayCard(e.getKey(), e.getValue()));
            missed(list, rows);
        }
        if (!monthly.isEmpty()) {
            list.addView(header("Monthly totals", p.getLong("campus_monthly_time", 0)));
            TreeMap<String, List<Map<String, String>>> byMonth = new TreeMap<>(Collections.reverseOrder());
            for (Map<String, String> m : monthly) {
                List<Map<String, String>> l = byMonth.get(m.get("month"));
                if (l == null) byMonth.put(m.get("month"), l = new ArrayList<>());
                l.add(m);
            }
            for (Map.Entry<String, List<Map<String, String>>> e : byMonth.entrySet()) list.addView(monthlyCard(e.getKey(), e.getValue()));
        }
    }

    private View monthChips(Set<String> months) {
        HorizontalScrollView sv = new HorizontalScrollView(a);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        LinearLayout row = new LinearLayout(a);
        row.setClipChildren(false);
        for (String m : months) {
            boolean sel = m.equals(month);
            TextView c = a.text(monthName(m), 14, sel ? MainActivity.ON_SEL : MainActivity.TXT, true);
            c.setGravity(Gravity.CENTER);
            c.setPadding(a.dp(18), a.dp(11), a.dp(18), a.dp(11));
            c.setBackground(sel ? Glass.selected(a, 99, MainActivity.ACCENT) : Glass.surface(a, 99));
            Glass.pressable(c);
            c.setOnClickListener(v -> { month = m; render(false); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, a.dp(2), a.dp(8), a.dp(8));
            row.addView(c, lp);
        }
        sv.addView(row);
        return sv;
    }

    private void monthTotals(LinearLayout list, List<Map<String, String>> rows) {
        int pr = 0, ab = 0, od = 0;
        for (Map<String, String> m : rows) {
            String s = m.get("status");
            if ("PRESENT".equals(s)) pr++; else if ("ABSENT".equals(s)) ab++; else if ("OD".equals(s)) od++;
        }
        int counted = pr + ab + od;
        double pct = counted == 0 ? 0 : 100.0 * (pr + od) / counted;
        LinearLayout c = a.card();
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        TextView big = a.text("", 30, MainActivity.TXT, true);
        big.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        // The portal's Absent Report lists only missed classes; a percentage from it would read 0%.
        if (pr + od == 0 && ab > 0) { big.setText(String.valueOf(ab)); big.setTextColor(MainActivity.BAD); }
        else Glass.countUp(big, pct, MainActivity.colorFor(pct));
        c.addView(big, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout side = new LinearLayout(a);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(a.dp(18), 0, 0, 0);
        side.addView(a.text(monthName(month), 15, MainActivity.TXT, true));
        side.addView(a.text(pr + od == 0 && ab > 0 ? (ab == 1 ? "class missed" : "classes missed")
                : pr + " present · " + ab + " absent · " + od + " OD", 13, MainActivity.DIM, false));
        c.addView(side, new LinearLayout.LayoutParams(0, -2, 1));
        list.addView(c);
    }

    private View dayCard(String date, List<Map<String, String>> recs) {
        LinearLayout c = a.card();
        c.addView(a.text(dayName(date), 15, MainActivity.TXT, true));
        List<Map<String, String>> sorted = new ArrayList<>(recs);
        Collections.sort(sorted, new Comparator<Map<String, String>>() {
            @Override public int compare(Map<String, String> x, Map<String, String> y) {
                return Integer.compare(order(x.get("hour")), order(y.get("hour")));
            }
        });
        for (Map<String, String> m : sorted) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            String course = m.get("course"), hour = m.get("hour");
            String label = course.isEmpty() ? (hour.isEmpty() ? "Class" : "Hour " + hour)
                    : hour.isEmpty() ? course : course + " · hour " + hour;
            TextView t = a.text(label, 14, MainActivity.TXT, false);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(smallPill(statusWord(m.get("status")), statusColor(m.get("status"))));
            c.addView(row, gap(8));
        }
        return c;
    }

    private void missed(LinearLayout list, List<Map<String, String>> rows) {
        TreeMap<String, List<String>> byCourse = new TreeMap<>();
        for (Map<String, String> m : rows) {
            if (!"ABSENT".equals(m.get("status"))) continue;
            String course = m.get("course").isEmpty() ? "Class" : m.get("course");
            List<String> l = byCourse.get(course);
            if (l == null) byCourse.put(course, l = new ArrayList<>());
            String d = shortDate(m.get("date"));
            if (!m.get("hour").isEmpty()) d += " (h" + m.get("hour") + ")";
            l.add(d);
        }
        LinearLayout c = a.card();
        c.addView(a.text("Missed", 16, MainActivity.TXT, true));
        if (byCourse.isEmpty()) {
            TextView t = a.text("No missed classes in " + monthName(month) + ".", 13, MainActivity.DIM, false);
            t.setPadding(0, a.dp(5), 0, 0);
            c.addView(t);
        }
        for (Map.Entry<String, List<String>> e : byCourse.entrySet()) {
            TextView name = a.text(e.getKey() + " · " + e.getValue().size(), 14, MainActivity.TXT, true);
            name.setPadding(0, a.dp(10), 0, 0);
            c.addView(name);
            c.addView(a.text(String.join(", ", e.getValue()), 13, MainActivity.BAD, false));
        }
        list.addView(c);
    }

    private View monthlyCard(String key, List<Map<String, String>> recs) {
        LinearLayout c = a.card();
        c.addView(a.text(monthName(key), 15, MainActivity.TXT, true));
        for (Map<String, String> m : recs) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            String course = m.get("course").isEmpty() ? "All courses" : m.get("course");
            String tot = m.get("total").isEmpty() ? "" : " of " + m.get("total");
            String att = m.get("attended").isEmpty() ? "" : m.get("attended") + tot;
            row.addView(a.text(att.isEmpty() ? course : course + " · " + att, 14, MainActivity.TXT, false),
                    new LinearLayout.LayoutParams(0, -2, 1));
            if (!m.get("pct").isEmpty()) {
                double pct = Double.parseDouble(m.get("pct"));
                row.addView(smallPill(String.format(Locale.US, "%.1f%%", pct), MainActivity.colorFor(pct)));
            }
            c.addView(row, gap(8));
        }
        return c;
    }

    // ------------------------------------------------------------------------------------------ hostel

    private void hostel(LinearLayout list) {
        JSONObject h;
        try { h = new JSONObject(p.getString("campus_hostel", "{}")); } catch (Exception e) { h = new JSONObject(); }
        Map<String, String> info = CampusStore.mapOf(h.optJSONObject("info"));
        String homePass = h.optString("homePass", "");
        List<Map<String, String>> att = CampusStore.records(p.getString("campus_hostel_att", null));
        List<Map<String, String>> passes = CampusStore.records(p.getString("campus_passes", null));

        list.addView(header("Hostel and passes", null));
        if (info.isEmpty() && homePass.isEmpty() && att.isEmpty() && passes.isEmpty()) {
            list.addView(hint("No hostel data yet. Open your hostel details or pass page in the app browser and tap "
                    + "“Use this page”."));
            return;
        }
        if (!info.isEmpty()) {
            LinearLayout c = a.card();
            c.addView(a.text("Hostel", 16, MainActivity.TXT, true));
            for (Map.Entry<String, String> e : info.entrySet()) {
                TextView l = a.text(e.getKey(), 12, MainActivity.DIM, false);
                l.setPadding(0, a.dp(9), 0, 0);
                c.addView(l);
                c.addView(a.text(e.getValue(), 15, MainActivity.TXT, false));
            }
            c.addView(imported(p.getLong("campus_hostel_time", 0)));
            list.addView(c);
        }
        if (!homePass.isEmpty()) {
            LinearLayout c = a.card();
            c.setOrientation(LinearLayout.HORIZONTAL);
            c.setGravity(Gravity.CENTER_VERTICAL);
            TextView big = a.text(homePass, 44, MainActivity.TXT, true);
            big.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            c.addView(big);
            LinearLayout side = new LinearLayout(a);
            side.setOrientation(LinearLayout.VERTICAL);
            side.setPadding(a.dp(18), 0, 0, 0);
            side.addView(a.text("home passes this month", 15, MainActivity.TXT, true));
            side.addView(imported(p.getLong("campus_hostel_time", 0)));
            c.addView(side, new LinearLayout.LayoutParams(0, -2, 1));
            list.addView(c);
        }
        if (!att.isEmpty()) hostelAttendance(list, att);
        if (!passes.isEmpty()) {
            list.addView(header("Gate and home passes", p.getLong("campus_passes_time", 0)));
            boolean full = all.contains("passes");
            for (int i = 0; i < passes.size() && (full || i < SHOWN); i++) list.addView(passCard(passes.get(i)));
            if (passes.size() > SHOWN) list.addView(toggle("passes", passes.size()));
        }
    }

    private void hostelAttendance(LinearLayout list, List<Map<String, String>> att) {
        String now = new SimpleDateFormat("yyyy-MM", Locale.ROOT).format(new Date());
        TreeSet<String> months = new TreeSet<>(Collections.reverseOrder());
        for (Map<String, String> m : att) months.add(m.get("date").substring(0, 7));
        String mo = months.contains(now) ? now : months.first();
        int pr = 0, ab = 0, lv = 0, ps = 0;
        TreeMap<String, String> odd = new TreeMap<>();
        for (Map<String, String> m : att) {
            if (!m.get("date").startsWith(mo)) continue;
            String s = m.get("status");
            if ("PRESENT".equals(s)) pr++; else if ("ABSENT".equals(s)) ab++; else if ("LEAVE".equals(s)) lv++; else if ("PASS".equals(s)) ps++;
            if (!"PRESENT".equals(s)) odd.put(m.get("date"), s);
        }
        LinearLayout c = a.card();
        c.addView(a.text("Hostel attendance · " + monthName(mo), 16, MainActivity.TXT, true));
        TextView t = a.text(pr + " present · " + ab + " absent · " + lv + " leave · " + ps + " pass",
                13, MainActivity.DIM, false);
        t.setPadding(0, a.dp(5), 0, 0);
        c.addView(t);
        for (Map.Entry<String, String> e : odd.entrySet()) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(a.text(shortDate(e.getKey()), 14, MainActivity.TXT, false), new LinearLayout.LayoutParams(0, -2, 1));
            String s = e.getValue();
            row.addView(smallPill(s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT),
                    "ABSENT".equals(s) ? MainActivity.BAD : MainActivity.WARN));
            c.addView(row, gap(8));
        }
        c.addView(imported(p.getLong("campus_hostel_att_time", 0)));
        list.addView(c);
    }

    private View passCard(Map<String, String> m) {
        LinearLayout c = a.card();
        String type = m.get("type").isEmpty() ? "Pass" : m.get("type");
        c.addView(a.text(type, 16, MainActivity.TXT, true));
        String from = m.get("from"), to = m.get("to");
        String when = from.isEmpty() ? to : to.isEmpty() ? from : from + "  →  " + to;
        if (!when.isEmpty()) c.addView(a.text(when, 13, MainActivity.DIM, false));
        if (!m.get("reason").isEmpty()) c.addView(a.text(m.get("reason"), 13, MainActivity.DIM, false));
        String st = m.get("status");
        if (!st.isEmpty()) {
            TextView pill = smallPill(st, passColor(st));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pill.getLayoutParams();
            lp.topMargin = a.dp(8);
            c.addView(pill);
        }
        return c;
    }

    private int passColor(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        if (l.matches(".*(approv|accept|grant|allow|sanction).*")) return MainActivity.GOOD;
        if (l.matches(".*(pending|wait|applied|submit|process|await).*")) return MainActivity.WARN;
        if (l.matches(".*(reject|cancel|declin|denied|disapprov|revok).*")) return MainActivity.BAD;
        return MainActivity.DIM;
    }

    // ------------------------------------------------------------------------------------------ generic pages

    private void generic(LinearLayout list) {
        JSONArray tables;
        try { tables = new JSONArray(p.getString("campus_generic", "[]")); } catch (Exception e) { tables = new JSONArray(); }
        list.addView(header("Other saved pages", null));
        if (tables.length() == 0) {
            list.addView(hint("Any other table you open in the app browser can be kept here too: tap “Use this page”."));
            return;
        }
        for (int i = 0; i < tables.length(); i++) {
            final JSONObject t = tables.optJSONObject(i);
            if (t == null) continue;
            final String key = t.optString("key");
            boolean isOpen = open.contains(key);
            JSONArray rows = t.optJSONArray("rows");
            int n = rows == null ? 0 : rows.length();
            LinearLayout c = a.card();
            LinearLayout head = new LinearLayout(a);
            head.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout names = new LinearLayout(a);
            names.setOrientation(LinearLayout.VERTICAL);
            String title = t.optString("title", "").isEmpty() ? t.optString("path", "Page") : t.optString("title");
            names.addView(a.text(title, 15, MainActivity.TXT, true));
            names.addView(a.text(n + (n == 1 ? " row" : " rows") + " · imported " + ago(t.optLong("time")), 12, MainActivity.DIM, false));
            head.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(a.text(isOpen ? "▾" : "▸", 18, MainActivity.DIM, false));
            c.addView(head);
            if (isOpen && rows != null) {
                JSONArray headers = t.optJSONArray("headers");
                boolean full = all.contains(key);
                for (int r = 0; r < n && (full || r < 10); r++) c.addView(genericRow(headers, rows.optJSONArray(r)));
                if (n > 10) {
                    TextView more = a.text(full ? "Show fewer" : "Show all " + n + " rows", 13, MainActivity.ACCENT, true);
                    more.setPadding(0, a.dp(12), 0, 0);
                    more.setOnClickListener(v -> { if (!all.remove(key)) all.add(key); render(false); });
                    c.addView(more);
                }
            }
            Glass.pressable(c);
            c.setOnClickListener(v -> { if (!open.remove(key)) open.add(key); render(false); });
            list.addView(c);
        }
    }

    private View genericRow(JSONArray headers, JSONArray row) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, a.dp(10), 0, 0);
        if (row == null) return r;
        boolean named = headers != null && headers.length() == row.length();
        String first = row.optString(0);
        r.addView(a.text(first.isEmpty() ? "—" : first, 14, MainActivity.TXT, true));
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < row.length(); i++) {
            String v = row.optString(i);
            if (v.isEmpty()) continue;
            if (sb.length() > 0) sb.append(named ? "\n" : " · ");
            sb.append(named && !headers.optString(i).isEmpty() ? headers.optString(i) + ": " : "").append(v);
        }
        if (sb.length() > 0) r.addView(a.text(sb.toString(), 13, MainActivity.DIM, false));
        return r;
    }

    // ------------------------------------------------------------------------------------------ pieces

    private TextView header(String title, Long time) {
        TextView t = a.text(title.toUpperCase(Locale.getDefault()) + (time != null && time > 0 ? "  ·  IMPORTED " + ago(time).toUpperCase(Locale.getDefault()) : ""),
                12, MainActivity.DIM, true);
        t.setLetterSpacing(0.06f);
        t.setPadding(a.dp(4), a.dp(20), 0, a.dp(2));
        return t;
    }

    private TextView hint(String s) {
        TextView t = a.text(s, 13, MainActivity.DIM, false);
        t.setPadding(a.dp(20), a.dp(16), a.dp(20), a.dp(16));
        t.setBackground(Glass.card(a, 21));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, a.dp(6), 0, a.dp(6));
        t.setLayoutParams(lp);
        return t;
    }

    private TextView imported(long time) {
        TextView t = a.text(time > 0 ? "Imported " + ago(time) : "", 12, MainActivity.TER, false);
        t.setPadding(0, a.dp(10), 0, 0);
        return t;
    }

    private TextView toggle(String key, int n) {
        boolean full = all.contains(key);
        TextView t = a.text(full ? "Show fewer" : "Show all " + n, 13, MainActivity.ACCENT, true);
        t.setPadding(a.dp(4), a.dp(8), 0, a.dp(4));
        t.setOnClickListener(v -> { if (!all.remove(key)) all.add(key); render(false); });
        return t;
    }

    private TextView button(String label, boolean primary, View.OnClickListener l) {
        TextView t = a.action(label, primary);
        t.setOnClickListener(l);
        return t;
    }

    private TextView smallPill(String s, int color) {
        TextView t = a.text(s, 12, color, true);
        t.setPadding(a.dp(12), a.dp(4), a.dp(12), a.dp(4));
        t.setBackground(Glass.pill(a, color));
        t.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        return t;
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = a.dp(top);
        return lp;
    }

    private boolean officialInstalled() {
        return a.getPackageManager().getLaunchIntentForPackage("com.myamrita.studentportal") != null;
    }

    private static int order(String hour) {
        try { return Integer.parseInt(hour.replaceAll("\\D", "")); } catch (Exception e) { return 99; }
    }

    private static int statusColor(String s) {
        return "PRESENT".equals(s) ? MainActivity.GOOD : "ABSENT".equals(s) ? MainActivity.BAD
                : "OD".equals(s) ? MainActivity.WARN : MainActivity.DIM;
    }

    private static String statusWord(String s) {
        return "PRESENT".equals(s) ? "Present" : "ABSENT".equals(s) ? "Absent" : "OD".equals(s) ? "OD" : "Other";
    }

    private static String monthName(String ym) {
        try {
            return new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(new SimpleDateFormat("yyyy-MM", Locale.ROOT).parse(ym));
        } catch (Exception e) { return ym; }
    }

    private static String dayName(String iso) {
        try {
            return new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso));
        } catch (Exception e) { return iso; }
    }

    private static String shortDate(String iso) {
        try {
            return new SimpleDateFormat("d MMM", Locale.getDefault()).format(new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso));
        } catch (Exception e) { return iso; }
    }

    static String ago(long time) {
        long s = (System.currentTimeMillis() - time) / 1000;
        if (time <= 0) return "";
        if (s < 60) return "just now";
        if (s < 3600) return s / 60 + " min ago";
        if (s < 86400) return s / 3600 + " h ago";
        long d = s / 86400;
        return d + (d == 1 ? " day ago" : " days ago");
    }
}
