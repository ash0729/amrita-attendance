package com.ash.attendance;

import android.app.Activity;
import android.app.AlertDialog;
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

    private static GeckoRuntime runtime;
    private GeckoView browser;
    private GeckoSession session;
    private WebExtension extension;
    private EditText address;
    private Button back;
    private Button forward;
    private Button useAttendance;
    private Button usePage;
    private ProgressBar progress;
    private JSONObject attendanceData;
    private JSONObject pageData;
    private boolean canGoBack;
    private boolean canGoForward;

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
        toolbar.setPadding(dp(8), dp(6), dp(8), dp(6));
        toolbar.setBackground(Glass.card(this, 99));
        toolbar.setClipChildren(false);

        Button close = button("×", "Close browser");
        close.setOnClickListener(v -> finish());
        toolbar.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));

        back = button("‹", "Back");
        back.setOnClickListener(v -> goBack());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(42)));

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
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        addressParams.setMargins(dp(6), 0, dp(6), 0);
        toolbar.addView(address, addressParams);

        forward = button("›", "Forward");
        forward.setOnClickListener(v -> { if (canGoForward) session.goForward(); });
        toolbar.addView(forward, new LinearLayout.LayoutParams(dp(42), dp(42)));

        Button reload = button("↻", "Reload");
        reload.setOnClickListener(v -> { if (session != null) session.reload(); });
        toolbar.addView(reload, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, -2);
        barParams.setMargins(dp(20), dp(8), dp(20), dp(8));
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
        session = new GeckoSession();
        session.setContentDelegate(new GeckoSession.ContentDelegate() {});
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession current, String uri) {
                progress.setProgress(0);
                progress.setVisibility(View.VISIBLE);
                pageData = null;
                usePage.setVisibility(View.GONE);
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
        session.setPromptDelegate(new GeckoSession.PromptDelegate() {
            @Override public GeckoResult<GeckoSession.PromptDelegate.PromptResponse> onButtonPrompt(
                    GeckoSession current, GeckoSession.PromptDelegate.ButtonPrompt prompt) {
                GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result = new GeckoResult<>();
                new AlertDialog.Builder(BrowserActivity.this)
                        .setTitle(prompt.title == null ? "Website" : prompt.title)
                        .setMessage(prompt.message == null ? "" : prompt.message)
                        .setPositiveButton(android.R.string.ok,
                                (dialog, which) -> result.complete(prompt.confirm(
                                        GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE)))
                        .setNegativeButton(android.R.string.cancel,
                                (dialog, which) -> result.complete(prompt.confirm(
                                        GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE)))
                        .setOnCancelListener(dialog -> result.complete(prompt.confirm(
                                GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE)))
                        .show();
                return result;
            }
        });
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
        if (back != null) back.setEnabled(canGoBack);
        if (forward != null) forward.setEnabled(canGoForward);
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
