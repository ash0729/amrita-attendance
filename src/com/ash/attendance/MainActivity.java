package com.ash.attendance;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.text.DateFormat;
import java.util.Date;

/**
 * Shows attendance from https://students.amrita.edu/client/class-attendance.
 * The portal uses Microsoft SSO, so the user signs in inside a WebView; the app then
 * reads the attendance table out of that signed-in page and renders it natively.
 * No password is ever seen or stored by the app.
 */
public class MainActivity extends Activity {
    static final String URL = "https://students.amrita.edu/client/class-attendance";
    static final String HOST = "students.amrita.edu";
    static final int TARGET = 75; // minimum attendance percentage

    // Palette, filled in by applyTheme() from the phone's light/dark setting.
    static int BG, TXT, DIM, ACCENT, GOOD, WARN, BAD, ON_GOOD, ON_SEL;
    static boolean dark = true;

    // True when the portal answers with its "Custom Android applications are not allowed" page.
    static final String BLOCKED_JS = "/access denied|custom android applications/i.test((document.body&&document.body.innerText)||'')";

    // Reads every table row with >= 9 cells plus the selected term name.
    static final String EXTRACT_JS = "(function(){var rows=[];"
            + "document.querySelectorAll('tr').forEach(function(tr){var c=tr.children;"
            + "if(c.length<9)return;rows.push([].map.call(c,function(x){return (x.innerText||'').trim();}));});"
            + "var s=document.querySelector('select'),terms=[],ids=[],sel=-1;"
            + "if(s){terms=[].map.call(s.options,function(o){return o.text.trim();});"
            + "ids=[].map.call(s.options,function(o){return o.value;});sel=s.selectedIndex;}"
            + "return JSON.stringify({term:terms[sel]||'',terms:terms,ids:ids,sel:sel,rows:rows,blocked:" + BLOCKED_JS + "});})()";

    WebView web;
    ScrollView scroll;
    LinearLayout list;
    TextView status;
    ProgressBar spinner;
    SharedPreferences prefs;
    final Handler ui = new Handler(Looper.getMainLooper());
    boolean polling;
    boolean feedbackMode;
    Glass.Backdrop backdrop;
    final TextView[] navTabs = new TextView[2];
    LinearLayout ratingBar;
    int autoRating;      // 0 = autofill off, else 1..5
    String lastQid;      // loop guard: question id we last tried to save
    int pollTries;
    boolean termSwitched;   // already navigated to the wanted semester during this refresh
    JSONObject lastRead;    // last successful read of the page, used if the wanted semester never shows
    String chosenTerm, chosenId; // semester picked this session only; every launch starts on the current one

    static final boolean DEBUG_WEB = false;
    static final String[] LISTS = {"https://" + HOST + "/client/course-feedback-list",
                                   "https://" + HOST + "/client/class-feedback"};
    static final String[] LABELS = {"Strongly Agree", "Agree", "Neutral", "Disagree", "Strongly Disagree"};
    // Handles both lists. Course list: 5+ cells per row. TLP list: a header row (Sl No, Class, Course) followed by
    // one 3-cell row per faculty (name, status, action). Every actionable row is tagged data-ci for a later tap.
    static final String LIST_JS = "(function(){var rows=[],k=0,course='';"
            + "var txt=function(e){return (e.innerText||'').trim();};"
            + "document.querySelectorAll('tr').forEach(function(tr){var c=tr.children,n=c.length,row=null,act;"
            + "if(n>=5){var st=txt(c[3]);act=tr.querySelector('a[href],button');"
            + "if(!act||!/pending|complete|submit|done/i.test(st))return;"
            + "row={course:txt(c[1]),faculty:'',status:st,act:txt(act)};}"
            + "else if(n===3){var t0=txt(c[0]);"
            + "if(c[0].rowSpan>1||/^\\d+$/.test(t0)){course=txt(c[2]);return;}"
            + "act=tr.querySelector('a[href]');row={course:course,faculty:t0,status:txt(c[1]),act:act?txt(act):txt(c[2])};}"
            + "if(!row)return;row.pend=/pending/i.test(row.status)||/submit/i.test(row.act);"
            + "row.closed=/closed/i.test(row.act+' '+row.status);row.k=k;tr.setAttribute('data-ci',k);k++;rows.push(row);});"
            + "var s=document.querySelector('select'),terms=[],sel=0;"
            + "if(s){terms=[].map.call(s.options,function(o){return o.text.trim();});sel=s.selectedIndex;}"
            + "return JSON.stringify({term:terms[sel]||'',terms:terms,rows:rows,blocked:" + BLOCKED_JS + "});})()";

    TextView titleView, ratingLabel;
    android.widget.Switch autoSwitch;
    final TextView[] chips = new TextView[5];
    final TextView[] tabs = new TextView[2];
    int kind;               // 0 = course feedback, 1 = class TLP feedback
    boolean autoMode;       // saved: do the whole form on its own
    int rating = 2;         // saved: option applied to every question (1..5)
    boolean onQuestion;     // current page is a course-feedback question/OTP page
    boolean autoStarted;    // this course has had at least one question answered
    boolean autoConfirm;    // auto-accept the portal's "Are you sure?" dialog
    boolean autoFinishing;  // Finish was clicked; return to the list after the next page load
    boolean finishCancelled;
    int otpTries;
    final Runnable stepRunner = () -> autoStep();
    boolean listPolling;
    int listTries;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("att", MODE_PRIVATE);
        applyTheme();
        autoMode = prefs.getBoolean("auto", false);
        rating = Math.max(1, Math.min(5, prefs.getInt("rating", 2)));
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (!dark) { // dark icons on the light bars
            int f = getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (android.os.Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(f);
        }

        FrameLayout root = new FrameLayout(this);
        backdrop = new Glass.Backdrop(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setFitsSystemWindows(true);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        // Header: title + live status on the left, refresh on the right.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(21), dp(13), dp(13), dp(8));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titleView = text("Attendance", 23, TXT, true);
        status = text("", 13, DIM, false);
        status.setPadding(0, dp(2), 0, 0);
        titles.addView(titleView);
        titles.addView(status);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        spinner = new ProgressBar(this);
        spinner.getIndeterminateDrawable().setTint(ACCENT);
        spinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(21), dp(21));
        sp.setMargins(0, 0, dp(13), 0);
        bar.addView(spinner, sp);
        final TextView refresh = text("\u21BB", 21, TXT, false);
        refresh.setGravity(Gravity.CENTER);
        refresh.setBackground(Glass.surface(this, 24));
        refresh.setContentDescription("Refresh");
        Glass.pressable(refresh);
        refresh.setOnClickListener(v -> {
            refresh.animate().rotationBy(360f).setDuration(520).start();
            if (feedbackMode) { listPolling = false; spinner.setVisibility(View.VISIBLE); web.loadUrl(LISTS[kind]); }
            else refresh();
        });
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(bar);

        ratingBar = new Glass.Panel(this);
        buildRatingPanel();
        ratingBar.setVisibility(View.GONE);
        content.addView(ratingBar);

        FrameLayout body = new FrameLayout(this);
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(34));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(13), dp(5), dp(13), dp(21));
        scroll.addView(list);
        body.addView(scroll);

        web = new WebView(this);
        WebView.setWebContentsDebuggingEnabled(DEBUG_WEB);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            // The portal redirects to plain http:// after sign-in; cleartext is blocked, so upgrade it.
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest r) {
                android.net.Uri u = r.getUrl();
                if ("http".equals(u.getScheme()) && u.getHost() != null && u.getHost().endsWith("amrita.edu")) {
                    v.loadUrl(u.buildUpon().scheme("https").build().toString());
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                onPage(url);
            }
        });
        // WebView silently cancels JS confirm()/alert(); the portal's "Finish" button needs confirm().
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView v, String url, String message, android.webkit.JsResult r) {
                if (autoConfirm) { r.confirm(); return true; } // auto mode: the user already opted in
                new android.app.AlertDialog.Builder(MainActivity.this).setMessage(message)
                        .setPositiveButton("OK", (d, w) -> r.confirm())
                        .setNegativeButton("Cancel", (d, w) -> { finishCancelled = true; r.cancel(); })
                        .setOnCancelListener(d -> { finishCancelled = true; r.cancel(); }).show();
                return true;
            }

            @Override
            public boolean onJsAlert(WebView v, String url, String message, android.webkit.JsResult r) {
                new android.app.AlertDialog.Builder(MainActivity.this).setMessage(message)
                        .setPositiveButton("OK", (d, w) -> r.confirm())
                        .setOnCancelListener(d -> r.confirm()).show();
                return true;
            }
        });
        web.setVisibility(View.INVISIBLE);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(21)); }
        });
        web.setClipToOutline(true);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(-1, -1);
        wlp.setMargins(dp(13), dp(5), dp(13), dp(5));
        body.addView(web, wlp);
        content.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        // Floating glass tab bar at the bottom: within thumb reach, always shows where you are.
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(dp(5), dp(5), dp(5), dp(5));
        nav.setBackground(Glass.surface(this, 29));
        String[] names = {"Attendance", "Feedback"};
        for (int i = 0; i < 2; i++) {
            TextView t = text(names[i], 14, DIM, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(13), 0, dp(13));
            Glass.pressable(t);
            final int which = i;
            t.setOnClickListener(v -> setMode(which == 1));
            navTabs[i] = t;
            nav.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.setMargins(dp(21), dp(5), dp(21), dp(13));
        content.addView(nav, nlp);
        updateNav();
        setContentView(root);

        boolean cached = showCached();
        if (!cached) status.setText("Loading…");
        refresh();
    }

    /** Black glass on a dark phone, white glass on a light one. */
    void applyTheme() {
        dark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        Glass.dark = dark;
        if (dark) {
            BG = 0xFF000000; TXT = 0xFFF5F5F7; DIM = 0xFF8E8E93; ACCENT = 0xFFFFA048;
            GOOD = 0xFF30D158; WARN = 0xFFFFD60A; BAD = 0xFFFF453A; ON_GOOD = Color.WHITE;
        } else {
            BG = 0xFFF5F6F8; TXT = 0xFF0B0B0F; DIM = 0xFF6B6F80; ACCENT = 0xFF0A6CFF;
            GOOD = 0xFF1A9D4A; WARN = 0xFFC77700; BAD = 0xFFD92D20; ON_GOOD = Color.WHITE;
        }
        ON_SEL = dark ? ACCENT : Color.WHITE; // dark: orange label on grey glass
        Glass.BASE = BG;
    }

    void buildRatingPanel() {
        ratingBar.setOrientation(LinearLayout.VERTICAL);
        ratingBar.setPadding(dp(13), dp(13), dp(13), dp(13));
        ratingBar.setBackground(Glass.surface(this, 21));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(13), 0, dp(13), dp(8));
        ratingBar.setLayoutParams(lp);

        // Segmented control: course feedback / class TLP.
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        seg.setBackground(Glass.surface(this, 16));
        String[] tabNames = {"Course feedback", "Class TLP"};
        for (int t = 0; t < 2; t++) {
            TextView tb = text(tabNames[t], 13, DIM, true);
            tb.setGravity(Gravity.CENTER);
            tb.setPadding(0, dp(8), 0, dp(8));
            final int which = t;
            tb.setOnClickListener(v -> switchKind(which));
            tabs[t] = tb;
            seg.addView(tb, new LinearLayout.LayoutParams(0, -2, 1));
        }
        ratingBar.addView(seg);

        autoSwitch = new android.widget.Switch(this);
        autoSwitch.setText("Auto-complete & submit");
        autoSwitch.setTextColor(TXT);
        autoSwitch.setTextSize(14);
        autoSwitch.setTypeface(Glass.medium());
        autoSwitch.setPadding(dp(8), dp(13), dp(8), dp(8));
        autoSwitch.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Color.WHITE, dark ? 0xFFB4B4BF : 0xFF8E8E99}));
        autoSwitch.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Glass.withAlpha(ACCENT, 0xE0), dark ? 0x33FFFFFF : 0x4D0B0B0F}));
        autoSwitch.setChecked(autoMode);
        autoSwitch.setOnCheckedChangeListener((v, on) -> {
            autoMode = on;
            prefs.edit().putBoolean("auto", on).apply();
            updateRatingUi();
            if (on && onQuestion && autoRating == 0 && !autoStarted) startAuto(rating);
        });
        ratingBar.addView(autoSwitch);

        // Five equal 48dp targets for the answer.
        LinearLayout row = new LinearLayout(this);
        for (int n = 1; n <= 5; n++) {
            TextView c = text(String.valueOf(n), 16, TXT, true);
            c.setGravity(Gravity.CENTER);
            Glass.pressable(c);
            final int val = n;
            c.setOnClickListener(v -> {
                rating = val;
                prefs.edit().putInt("rating", val).apply();
                updateRatingUi();
                if (onQuestion) startAuto(val);
            });
            chips[n - 1] = c;
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(48), 1);
            cp.setMargins(dp(3), 0, dp(3), 0);
            row.addView(c, cp);
        }
        ratingBar.addView(row);
        ratingLabel = text("", 12, DIM, false);
        ratingLabel.setPadding(dp(5), dp(8), dp(5), 0);
        ratingBar.addView(ratingLabel);
        updateRatingUi();
    }

    void updateRatingUi() {
        for (int i = 0; i < 5; i++) {
            boolean sel = i + 1 == rating;
            chips[i].setBackground(sel ? Glass.selected(this, 16, ACCENT) : Glass.surface(this, 16));
            chips[i].setTextColor(sel ? ON_SEL : TXT);
        }
        for (int i = 0; i < 2; i++) {
            boolean sel = i == kind;
            tabs[i].setBackground(sel ? Glass.selected(this, 13, ACCENT) : null);
            tabs[i].setTextColor(sel ? ON_SEL : DIM);
        }
        ratingLabel.setText((kind == 0 ? "Every question: " + rating + " · " + LABELS[rating - 1]
                                       : "Every question: choice number " + rating + " (1 = first option)")
                + (autoMode ? "\nAuto is on: after the OTP it answers, saves, finishes and submits."
                            : "\nTap a number on a question page to fill it in."));
    }

    void updateNav() {
        for (int i = 0; i < 2; i++) {
            boolean sel = (i == 1) == feedbackMode;
            navTabs[i].setBackground(sel ? Glass.selected(this, 24, ACCENT) : null);
            navTabs[i].setTextColor(sel ? ON_SEL : DIM);
        }
    }

    void setMode(boolean feedback) {
        if (feedback != feedbackMode) toggleFeedback();
    }

    void startAuto(int r) {
        autoRating = r;
        lastQid = null;
        otpTries = 0;
        autoStep();
    }

    /** Answers the current question with autoRating and clicks Save & Next; the page reload re-enters here. */
    void autoStep() {
        ui.removeCallbacks(stepRunner);
        if (autoRating == 0) return;
        autoConfirm = autoMode;
        finishCancelled = false;
        String js = "(function(r){var otp=[].find.call(document.querySelectorAll('input,textarea'),function(x){"
                + "return x.type!=='hidden'&&x.type!=='radio'&&x.type!=='checkbox'&&x.offsetParent!==null"
                + "&&/otp/i.test((x.name||'')+' '+(x.id||'')+' '+(x.placeholder||''));});"
                + "if(otp&&!(otp.value||'').trim())return 'otp';"
                + "var f=document.querySelector('button[name=finish_feedback]')||[].find.call("
                + "document.querySelectorAll('form button,form input[type=submit]'),function(x){return x.type!=='button'"
                + "&&/^\\s*(finish|submit)\\s*$/i.test(x.innerText||x.value||'');});"
                + "if(f){f.click();return 'finish';}"
                + "var rs=document.querySelectorAll('input[name=option_id]'),q=document.querySelector('input[name=question_id]'),"
                + "b=document.querySelector('button[name=submit_ans]')||[].find.call(document.querySelectorAll('form button'),"
                + "function(x){return x.type!=='button'&&/save|next/i.test(x.innerText||'');});"
                + "if(!rs.length||!q||!b||rs.length<r)return 'done';"
                + "var a=document.querySelector('.step-item.active'),all=document.querySelectorAll('.step-item'),"
                + "i=a?[].indexOf.call(all,a)+1:0,n=all.length;rs[r-1].click();b.click();"
                + "return 'step|'+q.value+'|'+i+'|'+n;})(" + autoRating + ")";
        web.evaluateJavascript(js, v -> {
            String[] p = (v == null ? "" : v.replace("\"", "")).split("\\|");
            if (p[0].equals("step")) {
                if (p[1].equals(lastQid)) { autoRating = 0; status.setText("Save didn't advance; stopped at question " + p[2]); return; }
                lastQid = p[1];
                autoStarted = true;
                otpTries = 0;
                status.setText("Saved " + p[2] + " of " + p[3] + " (answer " + autoRating + ")…");
            } else if (p[0].equals("otp")) {
                // An OTP box is on screen and still empty: wait for the user to type it, then carry on.
                if (++otpTries > 160) { autoRating = 0; status.setText("Gave up waiting for the OTP"); return; }
                status.setText("Enter the OTP; it submits right after");
                ui.postDelayed(stepRunner, 1500);
            } else if (p[0].equals("finish")) {
                autoRating = 0;
                autoStarted = true;
                autoFinishing = !finishCancelled;
                status.setText(finishCancelled ? "Finish cancelled" : autoMode ? "Submitting…" : "All answered");
            } else {
                autoRating = 0;
                status.setText(autoStarted ? "Nothing left to fill on this page" : "No questions yet. Verify the OTP first");
            }
        });
    }

    void switchKind(int which) {
        kind = which;
        autoRating = 0;
        autoStarted = autoConfirm = autoFinishing = false;
        updateRatingUi();
        list.removeAllViews();
        status.setText("Loading…");
        web.setVisibility(View.INVISIBLE);
        spinner.setVisibility(View.VISIBLE);
        listPolling = false;
        web.loadUrl(LISTS[which]);
    }

    void pickTerm(final JSONArray terms) {
        try {
            String[] names = new String[terms.length()];
            for (int i = 0; i < names.length; i++) names[i] = terms.getString(i);
            new android.app.AlertDialog.Builder(this).setTitle("Academic term").setItems(names, (d, i) -> {
                spinner.setVisibility(View.VISIBLE);
                web.evaluateJavascript("(function(i){var s=document.querySelector('select');s.selectedIndex=i;"
                        + "s.dispatchEvent(new Event('change',{bubbles:true}));if(window.jQuery)jQuery(s).trigger('change');})(" + i + ")", null);
                listTries = 0;
                ui.postDelayed(() -> { if (!listPolling) { listPolling = true; pollList(); } }, 1500);
            }).show();
        } catch (Exception ignored) { }
    }

    void toggleFeedback() {
        feedbackMode = !feedbackMode;
        autoRating = 0;
        autoStarted = autoConfirm = autoFinishing = false;
        ratingBar.setVisibility(View.GONE);
        updateNav();
        if (feedbackMode) {
            titleView.setText("Course feedback");
            polling = false;
            status.setText("Loading courses…");
            list.removeAllViews();
            web.setVisibility(View.INVISIBLE);
            spinner.setVisibility(View.VISIBLE);
            web.loadUrl(LISTS[kind]);
        } else {
            titleView.setText("Attendance");
            if (!showCached()) status.setText("Loading…");
            refresh();
        }
    }

    void pollList() {
        String url = web.getUrl();
        if (!feedbackMode || url == null || !(url.contains("course-feedback-list") || url.contains("/client/class-feedback"))) { listPolling = false; return; }
        web.evaluateJavascript(LIST_JS, value -> {
            try {
                JSONObject o = new JSONObject((String) new JSONTokener(value).nextValue());
                if (o.optBoolean("blocked")) { listPolling = false; showBlocked(LISTS[kind]); return; }
                if (o.getJSONArray("rows").length() > 0) {
                    listPolling = false;
                    spinner.setVisibility(View.GONE);
                    web.setVisibility(View.INVISIBLE);
                    renderList(o);
                    return;
                }
            } catch (Exception ignored) { }
            if (++listTries < 12) ui.postDelayed(this::pollList, 1000);
            else {
                listPolling = false;
                spinner.setVisibility(View.GONE);
                web.setVisibility(View.VISIBLE);
                status.setText("Couldn't read the list; showing the portal page");
            }
        });
    }

    void renderList(JSONObject o) throws Exception {
        JSONArray rows = o.getJSONArray("rows");
        final JSONArray terms = o.optJSONArray("terms");
        list.removeAllViews();
        int pending = 0;
        for (int i = 0; i < rows.length(); i++) if (rows.getJSONObject(i).optBoolean("pend")) pending++;
        String term = o.optString("term", "");
        status.setText((term.isEmpty() ? "" : term + " · ") + pending + " pending of " + rows.length());
        if (terms != null && terms.length() > 1) {
            LinearLayout t = card();
            t.addView(text("Term  ·  " + term + "  \u25BE", 14, TXT, true));
            Glass.pressable(t);
            t.setOnClickListener(v -> pickTerm(terms));
            list.addView(t);
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i);
            String[] course = r.getString("course").split("\\s*\\n\\s*", 2);
            String name = course.length > 1 ? course[1] : course[0];
            String fac = r.optString("faculty", "");
            boolean pend = r.optBoolean("pend"), closed = r.optBoolean("closed");
            LinearLayout card = card();
            card.addView(text(fac.isEmpty() ? name : fac, 16, TXT, true));
            card.addView(text(fac.isEmpty() ? course[0] : name + " · " + course[0], 12, DIM, false));
            card.addView(pill(pend ? "Pending · tap to fill" : closed ? "Closed" : "Completed \u2713",
                    pend ? WARN : closed ? BAD : DIM));
            if (pend) {
                final int k = r.getInt("k");
                Glass.pressable(card);
                card.setOnClickListener(v -> openCourse(k));
            } else {
                card.setAlpha(0.62f);
            }
            list.addView(card);
        }
        Glass.enter(list);
    }

    /** The row's action is a target=_blank link, which a WebView ignores, so load its address in place. */
    void openCourse(int k) {
        spinner.setVisibility(View.VISIBLE);
        status.setText("Opening…");
        web.evaluateJavascript("(function(k){var tr=document.querySelector('tr[data-ci=\"'+k+'\"]');"
                + "var a=tr&&tr.querySelector('a[href]');if(a)return a.href;"
                + "var b=tr&&tr.querySelector('button');if(b){b.click();return 'clicked';}return '';})(" + k + ")", v -> {
            String href = v == null ? "" : v.replace("\"", "");
            if (href.startsWith("http")) {
                web.loadUrl(href.replaceFirst("^http://", "https://"));
            } else if (!href.equals("clicked")) {
                spinner.setVisibility(View.GONE);
                web.setVisibility(View.VISIBLE);
                status.setText("Couldn't open it; showing the portal page");
            }
        });
    }

    void refresh() {
        spinner.setVisibility(View.VISIBLE);
        pollTries = 0;
        termSwitched = false;
        lastRead = null;
        String id = chosenId != null ? chosenId
                : currentTermName().equals(prefs.getString("defTermName", null)) ? prefs.getString("defTermId", null) : null;
        web.loadUrl(id == null ? URL : URL + "?academic_term_id=" + id);
    }

    /** Called when any page finishes loading in the WebView. */
    void onPage(String url) {
        if (url == null) return;
        if (feedbackMode) {
            spinner.setVisibility(View.GONE);
            boolean isList = url.contains("course-feedback-list") || url.contains("/client/class-feedback");
            boolean question = url.contains("/client/course-feedback?") || url.contains("/client/feedback?");
            int k2 = (url.contains("/client/class-feedback") || url.contains("/client/feedback?")) ? 1
                    : url.contains("course-feedback") ? 0 : kind;
            if (k2 != kind) { kind = k2; updateRatingUi(); }
            onQuestion = question;
            ratingBar.setVisibility(isList || question ? View.VISIBLE : View.GONE);
            if (isList) {
                autoRating = 0;
                autoStarted = autoConfirm = autoFinishing = false;
                if (!listPolling) { listPolling = true; listTries = 0; pollList(); }
                return;
            }
            web.setVisibility(View.VISIBLE);
            if (autoFinishing) { // Finish went through: go back to the list
                autoFinishing = false;
                autoConfirm = false;
                status.setText("Submitted ✓ Back to the list…");
                ui.postDelayed(() -> { if (feedbackMode) web.loadUrl(LISTS[kind]); }, 1500);
                return;
            }
            if (!question) { autoRating = 0; autoConfirm = false; return; }
            if (autoMode && !autoStarted && autoRating == 0) { autoRating = rating; lastQid = null; }
            if (autoRating > 0) { ui.removeCallbacks(stepRunner); ui.postDelayed(stepRunner, 500); }
            else status.setText(autoMode ? "Auto is on: verify the OTP and the rest runs by itself"
                                         : "Tap a number to answer every question");
            return;
        }
        boolean onPortal = url.contains(HOST) && url.contains("/client/");
        if (onPortal) {
            if (!polling) { polling = true; poll(); }
        } else if (url.startsWith("https://")) {
            // Sign-in (Microsoft) or any other page: let the user interact with it.
            spinner.setVisibility(View.GONE);
            web.setVisibility(View.VISIBLE);
            status.setText("Sign in with your Amrita account");
        }
    }

    /** The portal lists terms newest first, including future ones, and opens on an old one. */
    String currentTermName() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int y = c.get(java.util.Calendar.YEAR), m = c.get(java.util.Calendar.MONTH) + 1;
        int start = m >= 7 ? y : y - 1;                       // Jul-Dec: odd semester, Jan-Jun: even
        return start + "-" + String.format("%02d", (start + 1) % 100) + (m >= 7 ? " Odd Sem" : " Even Sem");
    }

    /** First year of the current academic year, to hide semesters that haven't started. */
    int currentStartYear() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.MONTH) + 1 >= 7 ? c.get(java.util.Calendar.YEAR) : c.get(java.util.Calendar.YEAR) - 1;
    }

    /** Index of the semester to show: the one picked this session, else the current one, else -1 (leave it). */
    int wantedTerm(JSONArray names) {
        if (names == null) return -1;
        String now = currentTermName();
        if (chosenTerm != null) for (int i = 0; i < names.length(); i++) if (names.optString(i).equals(chosenTerm)) return i;
        for (int i = 0; i < names.length(); i++) if (names.optString(i).equals(now)) return i;
        return -1;
    }

    void pickAttTerm(final JSONArray names, final JSONArray ids) {
        final java.util.ArrayList<Integer> keep = new java.util.ArrayList<>();
        for (int i = 0; i < names.length(); i++) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{4})").matcher(names.optString(i));
            if (!m.find() || Integer.parseInt(m.group(1)) <= currentStartYear()) keep.add(i);   // hide future terms
        }
        String[] items = new String[keep.size()];
        for (int i = 0; i < items.length; i++) items[i] = names.optString(keep.get(i));
        new android.app.AlertDialog.Builder(this).setTitle("Semester").setItems(items, (d, which) -> {
            int i = keep.get(which);
            chosenTerm = names.optString(i);
            chosenId = ids.optString(i);
            spinner.setVisibility(View.VISIBLE);
            status.setText("Loading " + names.optString(i) + "…");
            polling = true;
            pollTries = 0;
            termSwitched = true;
            lastRead = null;
            web.loadUrl(URL + "?academic_term_id=" + ids.optString(i));
            ui.postDelayed(this::poll, 1200);
        }).show();
    }

    /** The table may be filled in after load, so retry for a few seconds. */
    void poll() {
        String url = web.getUrl();
        if (url == null || !url.contains(HOST)) { polling = false; return; }
        web.evaluateJavascript(EXTRACT_JS, value -> {
            try {
                String json = (String) new JSONTokener(value).nextValue();
                JSONObject o = new JSONObject(json);
                if (o.optBoolean("blocked")) { polling = false; showBlocked(URL); return; }
                JSONArray names = o.optJSONArray("terms"), ids = o.optJSONArray("ids");
                int want = wantedTerm(names), sel = o.optInt("sel", -1);
                if (want >= 0 && names.optString(want).equals(currentTermName()))   // remember its id to skip a hop next time
                    prefs.edit().putString("defTermName", names.optString(want)).putString("defTermId", ids.optString(want)).apply();
                if (want >= 0 && sel != want) {
                    // The page opened on another semester: go to the one we want, then read it.
                    if (!termSwitched) {
                        termSwitched = true;
                        web.loadUrl(URL + "?academic_term_id=" + ids.optString(want));
                    }
                } else {
                    lastRead = o;
                    if (parse(o.getJSONArray("rows")).length() > 0) {
                        if (o.optString("term").equals(currentTermName())) // the saved copy is always the current semester
                            prefs.edit().putString("data", json).putLong("time", System.currentTimeMillis()).apply();
                        web.setVisibility(View.INVISIBLE);
                        spinner.setVisibility(View.GONE);
                        polling = false;
                        render(o, System.currentTimeMillis(), false);
                        return;
                    }
                }
            } catch (Exception ignored) { }
            if (++pollTries < 14) ui.postDelayed(this::poll, 1000);
            else {
                polling = false;
                spinner.setVisibility(View.GONE);
                if (lastRead != null && lastRead.optJSONArray("terms") != null) { // page works, this semester just has no rows
                    web.setVisibility(View.INVISIBLE);
                    render(lastRead, System.currentTimeMillis(), false);
                } else {
                    web.setVisibility(View.VISIBLE); // couldn't find the table: show the page itself
                    status.setText("Couldn't read the table; showing the portal page");
                }
            }
        });
    }

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

    /** Keep rows whose last six cells are Total, Present, DL, Absent, Percentage, Medical. */
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
        try {
            JSONArray courses = parse(data.getJSONArray("rows"));
            list.removeAllViews();
            int T = 0, A = 0;
            for (int i = 0; i < courses.length(); i++) {
                T += courses.getJSONObject(i).getInt("total");
                A += courses.getJSONObject(i).getInt("attended");
            }
            String term = data.optString("term", "");
            status.setText((term.isEmpty() ? "" : term + " · ") + (cached ? "saved " : "updated ")
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
            col.addView(text(A + " of " + T + " classes", 16, TXT, true));
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
                card.addView(text(c.getString("code") + " · " + c.getString("faculty"), 12, DIM, false));
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(13), 0, dp(8));
                TextView pct = text("", 23, col2, false);
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

    /** The portal refused the app. Say so, keep the saved numbers, and offer the page in the phone's browser. */
    void showBlocked(final String url) {
        spinner.setVisibility(View.GONE);
        web.setVisibility(View.INVISIBLE);
        ratingBar.setVisibility(View.GONE);
        boolean cached = !feedbackMode && showCached();
        if (!cached) list.removeAllViews();
        LinearLayout c = card();
        c.addView(text("The portal is blocking this app", 16, TXT, true));
        TextView why = text("students.amrita.edu now answers \u201CAccess denied. Custom Android applications are not "
                + "allowed.\u201D " + (cached ? "Below are the last numbers the app saved." : "The page still opens in your browser."),
                13, DIM, false);
        why.setPadding(0, dp(5), 0, 0);
        c.addView(why);
        TextView open = text("Open in browser", 14, ON_SEL, true);
        open.setGravity(Gravity.CENTER);
        open.setPadding(0, dp(13), 0, dp(13));
        open.setBackground(Glass.selected(this, 21, ACCENT));
        Glass.pressable(open);
        open.setOnClickListener(v -> startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url))));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(13);
        c.addView(open, lp);
        list.addView(c, 0);
        status.setText(cached ? "Blocked by the portal \u00B7 showing saved numbers" : "Blocked by the portal");
    }

    LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(21), dp(16), dp(21), dp(16));
        l.setBackground(Glass.surface(this, 21));
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
        t.setTextColor(color);
        t.setTypeface(bold ? Glass.medium() : Typeface.DEFAULT);
        return t;
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    public void onBackPressed() {
        if (web.getVisibility() == View.VISIBLE && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        backdrop.start();
    }

    @Override
    protected void onPause() {
        backdrop.stop();
        CookieManager.getInstance().flush();
        super.onPause();
    }
}
