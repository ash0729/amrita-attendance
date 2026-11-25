package com.ash.attendance;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

/**
 * Shows attendance from https://students.amrita.edu/client/class-attendance.
 * The app reads attendance in its in-app Firefox browser and renders the saved table natively.
 * No password is ever seen or stored by the app.
 */
public class MainActivity extends Activity {
    static final String URL = "https://students.amrita.edu/client/class-attendance";
    static final String HOST = "students.amrita.edu";
    static final String[] FEEDBACK_URLS = {
            "https://students.amrita.edu/client/course-feedback-list",
            "https://students.amrita.edu/client/class-feedback"
    };
    static final int TARGET = 75; // minimum attendance percentage
    static final int PICK_ATTENDANCE = 41;
    static final int PICK_CAMPUS = 42;

    // Palette, filled in by applyTheme() from the phone's light/dark setting.
    static int BG, TXT, DIM, TER, ACCENT, GOOD, WARN, BAD, ON_GOOD, ON_SEL;
    static boolean dark = true;

    LinearLayout list;
    TextView titleView, status;
    SharedPreferences prefs;
    boolean feedbackMode;
    boolean campusMode;
    CampusTab campus;
    Glass.Backdrop backdrop;
    Glass.Segmented nav;
    int kind; // 0 = course feedback, 1 = class TLP feedback

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("att", MODE_PRIVATE);
        applyTheme();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (!dark) { // dark icons on the light bars
            int f = getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (android.os.Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(f);
        }

        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        // The stage is everything the floating nav blurs; the nav itself sits above it, outside the stage.
        FrameLayout stage = new FrameLayout(this);
        stage.setClipChildren(false);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        backdrop = new Glass.Backdrop(this);
        stage.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setFitsSystemWindows(true);
        content.setClipChildren(false);
        stage.addView(content, new FrameLayout.LayoutParams(-1, -1));

        // Header: title + live status on the left, refresh on the right.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(13), dp(20), dp(8));
        bar.setClipChildren(false);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titleView = text("Attendance", 30, TXT, true);
        titleView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        status = text("", 13, DIM, false);
        status.setPadding(0, dp(2), 0, 0);
        titles.addView(titleView);
        titles.addView(status);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        final TextView refresh = text("\u21BB", 21, TXT, false);
        refresh.setGravity(Gravity.CENTER);
        refresh.setBackground(Glass.surface(this, 99));
        refresh.setContentDescription("Fetch from the in-app Firefox browser");
        Glass.pressable(refresh);
        refresh.setOnClickListener(v -> {
            refresh.animate().rotationBy(360f).setDuration(520).start();
            if (campusMode) campus.render(true);
            else if (feedbackMode) openFeedback(kind);
            else refresh();
        });
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(bar);

        FrameLayout body = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(20), dp(5), dp(20), dp(110));
        list.setClipChildren(false);
        scroll.addView(list);
        body.addView(scroll);

        content.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        // Floating glass tab bar: blurs the stage behind it on API 31+, plain glass elsewhere.
        nav = new Glass.Segmented(this, 99, false, 14, 5);
        nav.setSource(stage, null);
        String[] names = {"Attendance", "Feedback", "Campus"};
        for (int i = 0; i < 3; i++) {
            TextView t = text(names[i], 13, DIM, false);
            t.setTypeface(Glass.medium());
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(14), 0, dp(14));
            Glass.pressable(t);
            final int which = i;
            t.setOnClickListener(v -> setTab(which));
            nav.addTab(t);
        }
        final FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        nlp.setMargins(dp(20), 0, dp(20), dp(13));
        root.addView(nav, nlp);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            nlp.bottomMargin = dp(13) + ins.getSystemWindowInsetBottom();
            nav.setLayoutParams(nlp);
            return ins;
        });
        updateNav();
        setContentView(root);

        if (!showSaved()) showAttendanceHome();
    }

    /** Black glass on a dark phone, white glass on a light one. */
    void applyTheme() {
        dark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        Glass.dark = dark;
        if (dark) {
            BG = 0xFF000000; TXT = 0xFFF5F5F7; DIM = 0x99F5F5F7; TER = 0x61F5F5F7; ACCENT = 0xFFFFA048;
            GOOD = 0xFF30D158; WARN = 0xFFFFD60A; BAD = 0xFFFF453A; ON_GOOD = Color.WHITE;
        } else {
            BG = 0xFFF5F6F8; TXT = 0xFF0B0B0F; DIM = 0x990B0B0F; TER = 0x610B0B0F; ACCENT = 0xFF0A6CFF;
            GOOD = 0xFF1A9D4A; WARN = 0xFFC77700; BAD = 0xFFD92D20; ON_GOOD = Color.WHITE;
        }
        ON_SEL = dark ? ACCENT : Color.WHITE; // dark: orange label on grey glass
        Glass.BASE = BG;
    }

    void updateNav() {
        nav.select(campusMode ? 2 : feedbackMode ? 1 : 0, true);
    }

    /** 0 = Attendance, 1 = Feedback, 2 = Campus. */
    void setTab(int tab) {
        if (tab == 2) {
            if (!campusMode) enterCampus();
            return;
        }
        campusMode = false;
        feedbackMode = tab == 1;
        titleView.setText(feedbackMode ? "Feedback" : "Attendance");
        updateNav();
        if (feedbackMode) showFeedbackHome();
        else if (!showSaved()) showAttendanceHome();
    }

    void enterCampus() {
        feedbackMode = false;
        campusMode = true;
        titleView.setText("Campus");
        updateNav();
        if (campus == null) campus = new CampusTab(this);
        campus.render(true);
    }

    void refresh() {
        if (campusMode) campus.render(true);
        else if (feedbackMode) openFeedback(kind);
        else openAttendanceBrowser(URL);
    }

    void showAttendanceHome() {
        list.removeAllViews();
        status.setText("No attendance saved");
        LinearLayout c = card();
        c.addView(text("Get attendance", 16, TXT, true));
        c.addView(text("The in-app Firefox engine uses a Chrome User-Agent for the portal. After signing in, tap Use attendance to bring the table here.",
                13, DIM, false));
        TextView fetch = action("Fetch attendance with Firefox", true);
        fetch.setOnClickListener(v -> openAttendanceBrowser(URL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(13);
        c.addView(fetch, lp);
        list.addView(c);
    }

    void showFeedbackHome() {
        list.removeAllViews();
        status.setText("Feedback opens in the in-app Firefox browser");
        LinearLayout c = card();
        c.addView(text("Open a feedback list", 16, TXT, true));
        c.addView(text("The feedback helper appears in the browser. Set a rating there, then enter the OTP sent to your email.",
                13, DIM, false));
        TextView course = action("Course feedback", true);
        course.setOnClickListener(v -> openFeedback(0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(13);
        c.addView(course, lp);
        TextView tlp = action("Class TLP feedback", false);
        tlp.setOnClickListener(v -> openFeedback(1));
        lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        c.addView(tlp, lp);
        list.addView(c);
    }

    void openFeedback(int which) {
        kind = which;
        startActivity(BrowserActivity.intent(this, FEEDBACK_URLS[which]));
    }

    static String currentTermName() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int y = c.get(java.util.Calendar.YEAR), m = c.get(java.util.Calendar.MONTH) + 1;
        int start = m >= 7 ? y : y - 1;                       // Jul-Dec: odd semester, Jan-Jun: even
        return start + "-" + String.format("%02d", (start + 1) % 100) + (m >= 7 ? " Odd Sem" : " Even Sem");
    }

    boolean showSaved() { return showCached(); }

    boolean showCached() {
        try {
            String json = prefs.getString("data", null);
            if (json == null) return false;
            JSONObject j = new JSONObject(json);
            if (!j.has("terms")) return false;
            render(j, prefs.getLong("time", 0), true);
            return true;
        } catch (Exception e) { return false; }
    }

    void pickAttTerm(final JSONArray names, final JSONArray ids) {
        String[] items = new String[names.length()];
        for (int i = 0; i < items.length; i++) items[i] = names.optString(i);
        new android.app.AlertDialog.Builder(this).setTitle("Semester").setItems(items, (d, i) -> {
            String id = ids == null ? "" : ids.optString(i);
            openAttendanceBrowser(id.isEmpty() ? URL : URL + "?academic_term_id=" + id);
        }).show();
    }

    static JSONArray parse(JSONArray rows) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONArray r = rows.getJSONArray(i);
            int n = r.length();
            try {
                int total = Integer.parseInt(r.getString(n - 6).trim());
                int present = Integer.parseInt(r.getString(n - 5).trim());
                int dl = Integer.parseInt(r.getString(n - 4).trim());
                int absent = Integer.parseInt(r.getString(n - 3).trim());
                double pct = Double.parseDouble(r.getString(n - 2).trim());
                String[] course = r.getString(2).split("\\s*\\n\\s*", 2);
                String[] fac = r.getString(n - 7).split("\\s*\\n\\s*");
                JSONObject o = new JSONObject();
                o.put("code", course[0]);
                o.put("name", course.length > 1 ? course[1] : course[0]);
                o.put("faculty", android.text.TextUtils.join(", ", fac));
                o.put("total", total);
                o.put("attended", present + dl);
                o.put("absent", absent);
                o.put("pct", pct);
                out.put(o);
            } catch (Exception skip) { /* not a data row */ }
        }
        return out;
    }

    void render(JSONObject data, long time, boolean cached) {
        if (campusMode) return;
        try {
            JSONArray courses = parse(data.getJSONArray("rows"));
            list.removeAllViews();
            int T = 0, A = 0;
            for (int i = 0; i < courses.length(); i++) {
                T += courses.getJSONObject(i).getInt("total");
                A += courses.getJSONObject(i).getInt("attended");
            }
            String term = data.optString("term", "");
            String when = cached ? "saved " : "updated ";
            status.setText((term.isEmpty() ? "" : term + " · ") + when
                    + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(time)));

            final JSONArray tn = data.optJSONArray("terms"), ti = data.optJSONArray("ids");
            if (tn != null && tn.length() > 1) {
                LinearLayout t = card();
                t.addView(text("Semester  \u00B7  " + term + "  \u25BE", 14, TXT, true));
                Glass.pressable(t);
                t.setOnClickListener(v -> pickAttTerm(tn, ti));
                list.addView(t);
            }
            if (courses.length() == 0) {
                status.setText((term.isEmpty() ? "" : term + " \u00B7 ") + "no attendance recorded yet");
                LinearLayout none = card();
                none.addView(text("Nothing has been recorded for this semester yet.", 14, DIM, false));
                none.addView(text("Pick another semester above.", 13, DIM, false));
                TextView fetch = action("Fetch attendance with Firefox", true);
                fetch.setOnClickListener(v -> openAttendanceBrowser(URL));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(13);
                none.addView(fetch, lp);
                list.addView(none);
                Glass.enter(list);
                return;
            }

            double overall = T == 0 ? 0 : 100.0 * A / T;
            LinearLayout head = card();
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            Glass.Ring ring = new Glass.Ring(this);
            head.addView(ring, new LinearLayout.LayoutParams(dp(89), dp(89)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(21), 0, 0, 0);
            col.addView(text("Overall attendance", 13, DIM, false));
            col.addView(text(A + " of " + T + " classes", 20, TXT, true));
            col.addView(pill(advice(A, T), overall >= 80 ? DIM : colorFor(overall)));
            head.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            list.addView(head);
            ring.set(overall, colorFor(overall));

            for (int i = 0; i < courses.length(); i++) {
                JSONObject c = courses.getJSONObject(i);
                int t = c.getInt("total"), a = c.getInt("attended");
                double p = c.getDouble("pct");
                int col2 = colorFor(p);
                LinearLayout card = card();
                card.addView(text(c.getString("name"), 16, TXT, true));
                String faculty = c.getString("faculty");
                card.addView(text(faculty.isEmpty() ? c.getString("code")
                        : c.getString("code") + " \u00B7 " + faculty, 12, DIM, false));
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(13), 0, dp(8));
                TextView pct = text("", 22, col2, false);
                pct.setTypeface(Glass.light());
                Glass.countUp(pct, p, p >= 80 ? TXT : col2);
                row.addView(pct);
                TextView counts = text(a + "/" + t + " · absent " + c.getInt("absent"), 13, DIM, false);
                counts.setGravity(Gravity.END);
                row.addView(counts, new LinearLayout.LayoutParams(0, -2, 1));
                card.addView(row);
                Glass.Bar bar = new Glass.Bar(this, TARGET);
                card.addView(bar, new LinearLayout.LayoutParams(-1, dp(8)));
                bar.set(p, col2);
                card.addView(pill(advice(a, t), p >= 80 ? DIM : col2));
                list.addView(card);
            }
            TextView fetch = action("Update attendance with Firefox", false);
            fetch.setOnClickListener(v -> openAttendanceBrowser(URL));
            LinearLayout.LayoutParams fetchParams = new LinearLayout.LayoutParams(-1, -2);
            fetchParams.topMargin = dp(8);
            list.addView(fetch, fetchParams);
            Glass.enter(list);
            if (courses.length() == 0) status.setText("No attendance rows found");
        } catch (Exception e) {
            status.setText("Error: " + e.getMessage());
        }
    }

    /** Classes that can still be missed, or must be attended, to sit at TARGET%. */
    static String advice(int a, int t) {
        // a/(t+n) >= T/100  ->  n <= (100a - T t) / T
        int slack = 100 * a - TARGET * t;
        if (slack >= 0) {
            int n = slack / TARGET;
            return n == 0 ? "On the edge: don't miss the next class" : "Can miss " + n + " more class" + (n == 1 ? "" : "es");
        }
        // (a+x)/(t+x) >= T/100 -> x >= (T t - 100 a) / (100 - T)
        int x = (int) Math.ceil((double) -slack / (100 - TARGET));
        return "Attend next " + x + " class" + (x == 1 ? "" : "es") + " to reach " + TARGET + "%";
    }

    static int colorFor(double p) { return p >= 80 ? (dark ? GOOD : ACCENT) : p >= TARGET ? WARN : BAD; }

    TextView action(String label, boolean primary) {
        TextView t = text(label, 14, primary ? ON_SEL : TXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(13), 0, dp(13));
        t.setBackground(primary ? Glass.selected(this, 99, ACCENT) : Glass.surface(this, 99));
        Glass.pressable(t);
        return t;
    }

    void openOfficial() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.myamrita.studentportal");
        if (i != null) {
            try { startActivity(i); return; } catch (Exception ignored) { }
        }
        openAttendanceBrowser(URL);
    }

    void openAttendanceBrowser(String url) {
        startActivityForResult(BrowserActivity.intent(this, url), PICK_ATTENDANCE);
    }

    void openCampusBrowser(String url) {
        startActivityForResult(BrowserActivity.intent(this, url), PICK_CAMPUS);
    }

    /** A portal page the user chose in the app browser: classify it and keep what the Campus tab can show. */
    void importPage(String json) {
        String note;
        try {
            Campus.Page page = CampusStore.toPage(new JSONObject(json));
            Campus.Result r = Campus.classify(page);
            note = CampusStore.save(prefs, page, r, System.currentTimeMillis());
        } catch (Exception e) {
            note = "Couldn't read that page";
        }
        if (campusMode) campus.render(false);
        else setTab(2);
        status.setText(note);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        String page = data.getStringExtra(BrowserActivity.EXTRA_PAGE_JSON);
        if (page != null) { importPage(page); return; }
        if (data.getStringExtra(BrowserActivity.EXTRA_ATTENDANCE_JSON) == null) return;
        try {
            JSONObject attendance = new JSONObject(data.getStringExtra(BrowserActivity.EXTRA_ATTENDANCE_JSON));
            if (parse(attendance.getJSONArray("rows")).length() == 0) {
                status.setText("No attendance table found in the browser page");
                return;
            }
            String term = attendance.optString("term", "");
            if (term.isEmpty()) attendance.put("term", term = currentTermName());
            if (!attendance.has("terms")) attendance.put("terms", new JSONArray().put(term));
            if (!attendance.has("ids")) attendance.put("ids", new JSONArray().put(""));
            long now = System.currentTimeMillis();
            boolean saved = term.equals(currentTermName()) || !prefs.contains("data");
            if (campusMode) {
                campusMode = false;
                titleView.setText("Attendance");
                updateNav();
            }
            feedbackMode = false;
            if (saved) prefs.edit().putString("data", attendance.toString()).putLong("time", now).apply();
            render(attendance, now, true);
            status.setText("Attendance " + (saved ? "saved" : "loaded") + " from Firefox \u00B7 " + term);
        } catch (Exception e) {
            status.setText("Couldn't read attendance from the browser page");
        }
    }

    LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(16), dp(20), dp(16));
        l.setBackground(Glass.card(this, 21));
        l.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, dp(6));
        l.setLayoutParams(lp);
        return l;
    }

    /** Small tinted label: status and advice text. */
    TextView pill(String s, int color) {
        TextView t = text(s, 12, color, true);
        t.setPadding(dp(13), dp(5), dp(13), dp(5));
        t.setBackground(Glass.pill(this, color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, dp(8), 0, 0);
        t.setLayoutParams(lp);
        return t;
    }

    TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setLineSpacing(0f, 1.12f);
        t.setTextColor(color);
        t.setTypeface(bold ? Glass.medium() : Typeface.DEFAULT);
        return t;
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onResume() {
        super.onResume();
        backdrop.start();
    }

    @Override
    protected void onPause() {
        backdrop.stop();
        super.onPause();
    }
}
