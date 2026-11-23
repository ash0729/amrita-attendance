package com.ash.attendance;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.json.JSONObject;

/** A small in-app Firefox browser for portal pages. */
public final class BrowserActivity extends Activity {
    static final String EXTRA_URL = "browser_url";
    static final String EXTRA_ATTENDANCE_JSON = "browser_attendance_json";
    static final String EXTRA_PAGE_JSON = "browser_page_json";
    static final String FEEDBACK_EXTENSION = "resource://android/assets/geckoview-feedback/";
    static final String FEEDBACK_EXTENSION_ID = "feedback-helper@amrita-attendance";
    /** students.amrita.edu answers any Firefox/Gecko User-Agent with "Please use Chrome or Safari browser". */
    static final String CHROME_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36";

    private static GeckoRuntime runtime;
    private GeckoView browser;
    private final BrowserPrompts prompts = new BrowserPrompts(this);
    private GeckoSession session;
    private WebExtension extension;
    private EditText address;
    private Button back;
    private Button useAttendance;
    private Button usePage;
    private ProgressBar progress;
    private JSONObject attendanceData;
    private JSONObject pageData;
    private boolean canGoBack;
    private boolean canGoForward;
    private WebExtension.Port tidyPort;
    private int hiddenCount;
    private boolean picking;

    public static Intent intent(Context context, String url) {
        return new Intent(context, BrowserActivity.class).putExtra(EXTRA_URL, url);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (runtime == null) runtime = GeckoRuntime.create(this);
        buildUi();
        String initialUrl = getIntent().getStringExtra(EXTRA_URL);
        if (initialUrl == null) initialUrl = MainActivity.URL;
        final String firstUrl = normalize(initialUrl);
        runtime.getWebExtensionController().ensureBuiltIn(FEEDBACK_EXTENSION, FEEDBACK_EXTENSION_ID)
                .accept(builtIn -> {
                    extension = builtIn;
                    openPage(firstUrl);
                }, error -> {
                    Toast.makeText(this, "Feedback helper could not be loaded", Toast.LENGTH_LONG).show();
                    openPage(firstUrl);
                });
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.BG);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(4), dp(4), dp(4), dp(4));
        toolbar.setBackground(Glass.card(this, 99));
        toolbar.setClipChildren(false);

        Button close = button("×", "Close browser");
        close.setOnClickListener(v -> finish());
        toolbar.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));

        back = button("‹", "Back");
        back.setOnClickListener(v -> goBack());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(14);
        address.setTextColor(MainActivity.TXT);
        address.setHintTextColor(MainActivity.DIM);
        address.setHint("Website address");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setSelectAllOnFocus(true);
        address.setPadding(dp(16), 0, dp(16), 0);
        address.setBackground(Glass.surface(this, 99));
        address.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                loadAddress();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        addressParams.setMargins(dp(4), 0, dp(4), 0);
        toolbar.addView(address, addressParams);

        Button menu = button("\u22EE", "Menu: portal sections, hide parts of the page, forward");
        menu.setOnClickListener(v -> showMenu());
        toolbar.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));

        Button reload = button("↻", "Reload");
        reload.setOnClickListener(v -> { if (session != null) session.reload(); });
        toolbar.addView(reload, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, -2);
        barParams.setMargins(dp(12), dp(8), dp(12), dp(8));
        root.setClipChildren(false);
        root.addView(toolbar, barParams);

        useAttendance = accentButton("Use attendance from browser", "Use attendance from this page");
        useAttendance.setOnClickListener(v -> {
            if (attendanceData == null) return;
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_ATTENDANCE_JSON, attendanceData.toString()));
            finish();
        });
        LinearLayout.LayoutParams pickParams = new LinearLayout.LayoutParams(-1, dp(44));
        pickParams.setMargins(dp(20), dp(4), dp(20), dp(8));
        root.addView(useAttendance, pickParams);

        usePage = accentButton("Use this page", "Keep a copy of this page's tables in the Campus tab");
        usePage.setOnClickListener(v -> {
            if (pageData == null) return;
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_PAGE_JSON, pageData.toString()));
            finish();
        });
        root.addView(usePage, pickParams);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(MainActivity.ACCENT));
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(MainActivity.ACCENT));
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));

        browser = new GeckoView(this);
        root.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        updateNavigationButtons();
    }

    private Button accentButton(String label, String description) {
        Button b = button(label, description);
        b.setTextSize(14);
        b.setTextColor(MainActivity.ON_SEL);
        b.setBackground(Glass.selected(this, 99, MainActivity.ACCENT));
        b.setTypeface(Glass.medium());
        b.setVisibility(View.GONE);
        return b;
    }

    private Button button(String label, String description) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(20);
        button.setTextColor(MainActivity.TXT);
        button.setContentDescription(description);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setBackground(Glass.surface(this, 99));
        Glass.pressable(button);
        return button;
    }

    private void openPage(String url) {
        session = new GeckoSession(new GeckoSessionSettings.Builder()
                .userAgentOverride(CHROME_UA)
                .build());
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onExternalResponse(GeckoSession current, org.mozilla.geckoview.WebResponse response) {
                BrowserDownloads.save(BrowserActivity.this, response);
            }

            @Override public void onCloseRequest(GeckoSession current) { goBack(); }

            @Override public void onCrash(GeckoSession current) { restart(current); }

            @Override public void onKill(GeckoSession current) { restart(current); }
        });
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession current, String uri) {
                progress.setProgress(0);
                progress.setVisibility(View.VISIBLE);
                pageData = null;
                usePage.setVisibility(View.GONE);
                attendanceData = null;
                useAttendance.setVisibility(View.GONE);
                setAddress(uri);
            }

            @Override public void onProgressChange(GeckoSession current, int value) {
                progress.setProgress(value);
            }

            @Override public void onPageStop(GeckoSession current, boolean success) {
                progress.setVisibility(View.GONE);
            }
        });
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onLocationChange(GeckoSession current, String uri,
                    java.util.List<GeckoSession.PermissionDelegate.ContentPermission> permissions,
                    Boolean hasUserGesture) {
                setAddress(uri);
            }

            @Override public void onCanGoBack(GeckoSession current, boolean value) {
                canGoBack = value;
                updateNavigationButtons();
            }

            @Override public void onCanGoForward(GeckoSession current, boolean value) {
                canGoForward = value;
                updateNavigationButtons();
            }

            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession current,
                    GeckoSession.NavigationDelegate.LoadRequest request) {
                Uri uri = Uri.parse(request.uri);
                if ("http".equalsIgnoreCase(uri.getScheme()) && isAmritaHost(uri.getHost())) {
                    current.loadUri(uri.buildUpon().scheme("https").build().toString());
                    return GeckoResult.deny();
                }
                String scheme = uri.getScheme();
                if (scheme != null && !"http".equalsIgnoreCase(scheme)
                        && !"https".equalsIgnoreCase(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (Exception ignored) { }
                    return GeckoResult.deny();
                }
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    current.loadUri(request.uri);
                    return GeckoResult.deny();
                }
                return null;
            }
        });
        session.setPromptDelegate(prompts);
        session.open(runtime);
        if (extension != null) {
            session.getWebExtensionController().setMessageDelegate(extension, new WebExtension.MessageDelegate() {
                @Override public GeckoResult<Object> onMessage(String nativeApp, Object message,
                        WebExtension.MessageSender sender) {
                    if (!"browser".equals(nativeApp) || sender.session != session || !sender.isTopLevel()
                            || !(message instanceof JSONObject)) return null;
                    JSONObject envelope = (JSONObject) message;
                    String type = envelope.optString("type");
                    if ("page".equals(type) && isPortalPage(sender.url)) {
                        onPageData(envelope, isAttendancePage(sender.url));
                        return null;
                    }
                    if (!"attendance".equals(type) || !isAttendancePage(sender.url)) return null;
                    try {
                        String json = envelope.optString("dataJson", null);
                        JSONObject data = json == null ? null : new JSONObject(json);
                        if (data == null || data.toString().length() > 200000
                                || MainActivity.parse(data.getJSONArray("rows")).length() == 0) return null;
                        attendanceData = data;
                        String term = data.optString("term", "");
                        useAttendance.setText(term.isEmpty() ? "Use attendance from browser"
                                : "Use attendance \u00B7 " + term);
                        useAttendance.setVisibility(View.VISIBLE);
                    } catch (Exception ignored) { }
                    return null;
                }

                /** The tidy script on each portal page connects a port; keep the top-level one for menu commands. */
                @Override public void onConnect(WebExtension.Port port) {
                    if (port.sender.session != session || !port.sender.isTopLevel()) { port.disconnect(); return; }
                    tidyPort = port;
                    port.setDelegate(new WebExtension.PortDelegate() {
                        @Override public void onPortMessage(Object m, WebExtension.Port p) {
                            if (m instanceof JSONObject && "tidy".equals(((JSONObject) m).optString("type"))) {
                                hiddenCount = ((JSONObject) m).optInt("count", hiddenCount);
                                picking = ((JSONObject) m).optBoolean("picking", false);
                            }
                        }

                        @Override public void onDisconnect(WebExtension.Port p) { if (tidyPort == p) tidyPort = null; }
                    });
                    tidy("phone", getPreferences(MODE_PRIVATE).getBoolean("phone_layout", true));
                }
            }, "browser");
        }
        browser.setSession(session);
        session.setActive(true);
        session.setFocused(true);
        address.setText(url);
        session.loadUri(url);
    }

    /** A table/pair dump of a portal page: offer it when the Campus parser finds something in it. */
    private void onPageData(JSONObject envelope, boolean attendancePage) {
        try {
            String json = envelope.optString("dataJson", null);
            if (json == null || json.length() > 200000) return;
            JSONObject data = new JSONObject(json);
            Campus.Kind kind = Campus.classify(CampusStore.toPage(data)).kind;
            // The attendance page has its own button; its table would only show up here as a plain page.
            // Plain tables are kept from the menu instead, so the button only shows for pages the Campus tab reads.
            if (kind == Campus.Kind.GENERIC) { pageData = data; usePage.setVisibility(View.GONE); return; }
            if (kind == Campus.Kind.NONE || (attendancePage && attendanceData != null)) {
                pageData = null;
                usePage.setVisibility(View.GONE);
                return;
            }
            pageData = data;
            usePage.setText("Use this page \u00B7 " + Campus.label(kind));
            usePage.setVisibility(View.VISIBLE);
        } catch (Exception ignored) { }
    }

    /** The page's content process died: start a fresh tab on the same address instead of a blank view. */
    private void restart(GeckoSession dead) {
        if (dead != session) return;
        String url = normalize(address.getText().toString());
        openPage(url == null ? "https://" + MainActivity.HOST + "/client/index" : url);
        try { dead.close(); } catch (Exception ignored) { }
    }

    private void tidy(String cmd, Boolean on) {
        if (tidyPort == null) return;
        try {
            JSONObject m = new JSONObject().put("cmd", cmd);
            if (on != null) m.put("on", on);
            tidyPort.postMessage(m);
        } catch (Exception ignored) { }
    }

    /** Portal sections, in the order of the portal's home page. Paths are relative to https://students.amrita.edu. */
    static final String[][] SECTIONS = {
            {"Home", "/client/index"},
            {"Class attendance", "/client/class-attendance"},
            {"Absent report", "/client/absent-report"},
            {"Timetable", "/client/timetable"},
            {"Marks", "/client/mark"},
            {"Course feedback", "/client/course-feedback-list"},
            {"TLP feedback", "/client/class-feedback"},
            {"Leave list", "/client/leave-list"},
            {"Gate pass \u00B7 Hostel", "/hostel/index"},
            {"Profile", "/client/profile"},
            {"Payments", "/client/payment"},
            {"Services", "/client/request-list"},
            {"Complaints", "/client/complaints"},
            {"Downloads", "/client/downloads"},
            {"Documents", "/client/document"},
            {"Incidents", "/client/incidents"},
            {"Awards", "/client/award-list"},
            {"Supplementary", "/client/supply-request"},
            {"Refund", "/client/refund-list"},
            {"Exit survey", "/client/exit-survey"},
    };

    private void showMenu() {
        boolean portal = tidyPort != null;
        boolean phone = getPreferences(MODE_PRIVATE).getBoolean("phone_layout", true);
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();
        labels.add("Portal sections\u2026"); actions.add(this::showSections);
        if (canGoForward) { labels.add("Forward"); actions.add(() -> session.goForward()); }
        if (portal) {
            labels.add("Hide parts of this page"); actions.add(() -> tidy("pick", null));
            if (hiddenCount > 0) {
                labels.add("Show everything I hid (" + hiddenCount + ")");
                actions.add(() -> tidy("reset", null));
            }
            labels.add(phone ? "Phone layout: on" : "Phone layout: off");
            actions.add(() -> {
                getPreferences(MODE_PRIVATE).edit().putBoolean("phone_layout", !phone).apply();
                tidy("phone", !phone);
            });
        }
        if (pageData != null && !usePage.isShown()) {
            labels.add("Keep this page's tables in Campus");
            actions.add(() -> {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_PAGE_JSON, pageData.toString()));
                finish();
            });
        }
        glassList(null, labels.toArray(new String[0]), i -> actions.get(i).run());
    }

    private void showSections() {
        String[] names = new String[SECTIONS.length];
        for (int i = 0; i < names.length; i++) names[i] = SECTIONS[i][0];
        glassList("Portal sections", names, i -> {
            if (session != null) session.loadUri("https://" + MainActivity.HOST + SECTIONS[i][1]);
        });
    }

    private interface Pick { void at(int i); }

    private void glassList(String title, String[] items, Pick pick) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setItems(items, (d, i) -> pick.at(i));
        if (title != null) b.setTitle(title);
        android.app.AlertDialog d = b.create();
        d.show();
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(Glass.sheet(this), dp(13)));
            d.getWindow().setGravity(Gravity.TOP | Gravity.END);
        }
    }

    private void loadAddress() {
        String url = normalize(address.getText().toString());
        if (url == null) {
            Toast.makeText(this, "Enter an http or https address", Toast.LENGTH_SHORT).show();
            return;
        }
        address.clearFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(address.getWindowToken(), 0);
        session.loadUri(url);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String input = value.trim();
        if (input.isEmpty() || input.contains(" ")) return null;
        if (!input.matches("(?i)^https?://.*")) input = "https://" + input;
        Uri uri = Uri.parse(input);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
        if ("http".equalsIgnoreCase(scheme) && isAmritaHost(uri.getHost())) {
            uri = uri.buildUpon().scheme("https").build();
        }
        return uri.toString();
    }

    private static boolean isAmritaHost(String host) {
        return host != null && (host.equalsIgnoreCase("amrita.edu")
                || host.toLowerCase(java.util.Locale.ROOT).endsWith(".amrita.edu"));
    }

    private static boolean isPortalPage(String url) {
        Uri uri = Uri.parse(url);
        return "https".equalsIgnoreCase(uri.getScheme()) && MainActivity.HOST.equalsIgnoreCase(uri.getHost());
    }

    private static boolean isAttendancePage(String url) {
        Uri uri = Uri.parse(url);
        return "https".equalsIgnoreCase(uri.getScheme())
                && MainActivity.HOST.equalsIgnoreCase(uri.getHost())
                && "/client/class-attendance".equals(uri.getPath());
    }

    private void setAddress(String url) {
        if (url != null && !address.hasFocus()) address.setText(url);
    }

    private void goBack() {
        if (canGoBack && session != null) session.goBack();
        else finish();
    }

    private void updateNavigationButtons() {
        if (back != null) { back.setEnabled(canGoBack); back.setAlpha(canGoBack ? 1f : 0.35f); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BrowserPrompts.FILE_REQUEST) prompts.onFileResult(resultCode, data);
    }

    @Override public void onResume() {
        super.onResume();
        if (session != null) {
            session.setActive(true);
            session.setFocused(true);
        }
    }

    @Override public void onPause() {
        if (session != null) {
            session.setFocused(false);
            session.setActive(false);
        }
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (picking) { tidy("stop", null); picking = false; return; }
        goBack();
    }

    @Override public void onDestroy() {
        if (browser != null) browser.releaseSession();
        if (session != null) session.close();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
