package com.ash.attendance;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Draws the browser's own UI for web pages: dropdowns (&lt;select&gt;), alert/confirm/prompt, date and time pickers,
 * file uploads, sign-in boxes and "leave page?" questions. GeckoView renders none of these by itself; without a
 * handler a tapped dropdown simply does nothing.
 */
final class BrowserPrompts implements PromptDelegate {
    static final int FILE_REQUEST = 4101;

    private final Activity activity;
    private FilePrompt pendingFile;
    private Reply pendingFileReply;

    BrowserPrompts(Activity activity) { this.activity = activity; }

    /** Completes a prompt exactly once, whichever of click, cancel or page-dismiss comes first. */
    private static final class Reply {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        final BasePrompt prompt;
        private boolean done;

        Reply(BasePrompt prompt) { this.prompt = prompt; }

        void send(PromptResponse r) {
            if (done) return;
            done = true;
            result.complete(r);
        }

        void dismiss() { if (!done && !prompt.isComplete()) send(prompt.dismiss()); }
    }

    /** Closes our dialog if the page withdraws the prompt (navigation, script). */
    private static void bind(BasePrompt prompt, Dialog d) {
        prompt.setDelegate(new PromptInstanceDelegate() {
            @Override public void onPromptDismiss(BasePrompt p) { if (d.isShowing()) d.dismiss(); }
        });
    }

    private Dialog show(Dialog d, Reply reply) {
        d.setOnDismissListener(x -> reply.dismiss());
        bind(reply.prompt, d);
        d.show();
        if (d instanceof AlertDialog) style((AlertDialog) d);
        return d;
    }

    /** Same glass sheet and colours as the app's own dialogs. */
    private void style(AlertDialog d) {
        if (d.getWindow() != null)
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(Glass.sheet(activity),
                    Glass.dp(activity, 13)));
        TextView m = d.findViewById(android.R.id.message);
        if (m != null) { m.setTextColor(MainActivity.TXT); m.setTextSize(16); }
        for (int w : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button b = d.getButton(w);
            if (b != null) b.setTextColor(w == AlertDialog.BUTTON_POSITIVE ? MainActivity.ACCENT : MainActivity.DIM);
        }
    }

    private AlertDialog.Builder builder(String title, String message) {
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        if (title != null && !title.trim().isEmpty()) b.setTitle(title);
        if (message != null && !message.isEmpty()) b.setMessage(message);
        return b;
    }

    // --- alert / confirm / prompt ---------------------------------------------------------------------

    @Override public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession s, AlertPrompt p) {
        Reply r = new Reply(p);
        show(builder(p.title, p.message).setPositiveButton(android.R.string.ok, null).create(), r);
        return r.result;
    }

    @Override public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession s, ButtonPrompt p) {
        Reply r = new Reply(p);
        show(builder(p.title, p.message)
                .setPositiveButton(android.R.string.ok, (d, w) -> r.send(p.confirm(ButtonPrompt.Type.POSITIVE)))
                .setNegativeButton(android.R.string.cancel, (d, w) -> r.send(p.confirm(ButtonPrompt.Type.NEGATIVE)))
                .create(), r);
        return r.result;
    }

    @Override public GeckoResult<PromptResponse> onTextPrompt(GeckoSession s, TextPrompt p) {
        Reply r = new Reply(p);
        EditText input = field(p.defaultValue, InputType.TYPE_CLASS_TEXT);
        show(builder(p.title, p.message).setView(pad(input))
                .setPositiveButton(android.R.string.ok, (d, w) -> r.send(p.confirm(input.getText().toString())))
                .setNegativeButton(android.R.string.cancel, null).create(), r);
        return r.result;
    }

    @Override public GeckoResult<PromptResponse> onAuthPrompt(GeckoSession s, AuthPrompt p) {
        Reply r = new Reply(p);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText user = field(p.authOptions.username, InputType.TYPE_CLASS_TEXT);
        user.setHint("Username");
        EditText pass = field(p.authOptions.password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setHint("Password");
        boolean passwordOnly = (p.authOptions.flags & AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0;
        if (!passwordOnly) box.addView(user);
        box.addView(pass);
        show(builder(p.title, p.message).setView(pad(box))
                .setPositiveButton("Sign in", (d, w) -> r.send(passwordOnly ? p.confirm(pass.getText().toString())
                        : p.confirm(user.getText().toString(), pass.getText().toString())))
                .setNegativeButton(android.R.string.cancel, null).create(), r);
        return r.result;
    }

    // --- dropdowns ------------------------------------------------------------------------------------

    @Override public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession s, ChoicePrompt p) {
        Reply r = new Reply(p);
        List<ChoicePrompt.Choice> list = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        flatten(p.choices, "", list, labels);
        CharSequence[] names = labels.toArray(new CharSequence[0]);
        AlertDialog.Builder b = builder(p.title, null);
        if (p.type == ChoicePrompt.Type.MULTIPLE) {
            boolean[] checked = new boolean[list.size()];
            for (int i = 0; i < checked.length; i++) checked[i] = list.get(i).selected;
            b.setMultiChoiceItems(names, checked, (d, i, on) -> checked[i] = on)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        List<ChoicePrompt.Choice> picked = new ArrayList<>();
                        for (int i = 0; i < checked.length; i++) if (checked[i]) picked.add(list.get(i));
                        r.send(p.confirm(picked.toArray(new ChoicePrompt.Choice[0])));
                    })
                    .setNegativeButton(android.R.string.cancel, null);
        } else {
            int sel = -1;
            for (int i = 0; i < list.size(); i++) if (list.get(i).selected) sel = i;
            b.setSingleChoiceItems(names, sel, (d, i) -> {
                if (list.get(i).disabled) return;
                r.send(p.confirm(list.get(i)));
                d.dismiss();
            });
        }
        show(b.create(), r);
        return r.result;
    }

    /** Opens option groups into one list; group members are indented under the group name. */
    private static void flatten(ChoicePrompt.Choice[] in, String indent, List<ChoicePrompt.Choice> out,
                                List<String> labels) {
        if (in == null) return;
        for (ChoicePrompt.Choice c : in) {
            if (c.separator) continue;
            if (c.items != null) { flatten(c.items, indent + "    ", out, labels); continue; }
            out.add(c);
            labels.add(indent + (c.label == null ? "" : c.label.trim()) + (c.disabled ? "  (unavailable)" : ""));
        }
    }

    // --- dates and times ------------------------------------------------------------------------------

    @Override public GeckoResult<PromptResponse> onDateTimePrompt(GeckoSession s, DateTimePrompt p) {
        Reply r = new Reply(p);
        Calendar c = parse(p.defaultValue, p.type);
        if (p.type == DateTimePrompt.Type.TIME) {
            pickTime(r, c, time -> r.send(p.confirm(time)));
            return r.result;
        }
        DatePickerDialog d = new DatePickerDialog(activity, (v, y, m, day) -> {
            Calendar picked = Calendar.getInstance();
            picked.set(y, m, day);
            switch (p.type) {
                case DateTimePrompt.Type.MONTH:
                    r.send(p.confirm(String.format(Locale.ROOT, "%04d-%02d", y, m + 1))); break;
                case DateTimePrompt.Type.WEEK:
                    picked.setMinimalDaysInFirstWeek(4);
                    picked.setFirstDayOfWeek(Calendar.MONDAY);
                    r.send(p.confirm(String.format(Locale.ROOT, "%04d-W%02d", picked.getWeekYear(),
                            picked.get(Calendar.WEEK_OF_YEAR)))); break;
                case DateTimePrompt.Type.DATETIME_LOCAL:
                    pickTime(r, c, time -> r.send(p.confirm(
                            String.format(Locale.ROOT, "%04d-%02d-%02dT%s", y, m + 1, day, time)))); break;
                default:
                    r.send(p.confirm(String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, day)));
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        Calendar min = parse(p.minValue, p.type), max = parse(p.maxValue, p.type);
        if (p.minValue != null && !p.minValue.isEmpty()) d.getDatePicker().setMinDate(min.getTimeInMillis());
        if (p.maxValue != null && !p.maxValue.isEmpty()) d.getDatePicker().setMaxDate(max.getTimeInMillis());
        d.setButton(DatePickerDialog.BUTTON_NEUTRAL, "Clear", (x, w) -> r.send(p.confirm("")));
        if (p.type == DateTimePrompt.Type.DATETIME_LOCAL) {
            // The time picker follows; don't let this dialog's dismissal cancel the prompt.
            d.setOnDismissListener(null);
            bind(p, d);
            d.setOnCancelListener(x -> r.dismiss());
            d.show();
        } else {
            show(d, r);
        }
        return r.result;
    }

    private interface Text { void put(String s); }

    private void pickTime(Reply r, Calendar c, Text out) {
        TimePickerDialog t = new TimePickerDialog(activity,
                (v, h, min) -> out.put(String.format(Locale.ROOT, "%02d:%02d", h, min)),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(activity));
        show(t, r);
    }

    /** Reads an HTML date/time value (2026-11-21, 2026-11, 2026-W47, 14:05, 2026-11-21T14:05); today if blank. */
    private static Calendar parse(String v, int type) {
        Calendar c = Calendar.getInstance();
        if (v == null || v.isEmpty()) return c;
        try {
            if (type == DateTimePrompt.Type.TIME) {
                String[] t = v.split(":");
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(t[0]));
                c.set(Calendar.MINUTE, Integer.parseInt(t[1]));
                return c;
            }
            if (v.contains("-W")) {
                String[] w = v.split("-W");
                c.setMinimalDaysInFirstWeek(4);
                c.setFirstDayOfWeek(Calendar.MONDAY);
                c.setWeekDate(Integer.parseInt(w[0]), Integer.parseInt(w[1]), Calendar.MONDAY);
                return c;
            }
            String[] dt = v.split("T");
            String[] d = dt[0].split("-");
            c.set(Integer.parseInt(d[0]), Integer.parseInt(d[1]) - 1, d.length > 2 ? Integer.parseInt(d[2]) : 1);
            if (dt.length > 1) {
                String[] t = dt[1].split(":");
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(t[0]));
                c.set(Calendar.MINUTE, Integer.parseInt(t[1]));
            }
        } catch (RuntimeException ignored) { }
        return c;
    }

    // --- page questions -------------------------------------------------------------------------------

    @Override public GeckoResult<PromptResponse> onBeforeUnloadPrompt(GeckoSession s, BeforeUnloadPrompt p) {
        Reply r = new Reply(p);
        show(builder("Leave this page?", "Changes you made may not be saved.")
                .setPositiveButton("Leave", (d, w) -> r.send(p.confirm(AllowOrDeny.ALLOW)))
                .setNegativeButton("Stay", (d, w) -> r.send(p.confirm(AllowOrDeny.DENY))).create(), r);
        return r.result;
    }

    @Override public GeckoResult<PromptResponse> onRepostConfirmPrompt(GeckoSession s, RepostConfirmPrompt p) {
        Reply r = new Reply(p);
        show(builder("Send the form again?", "This page was made from a form you sent. Reloading sends it again.")
                .setPositiveButton("Resend", (d, w) -> r.send(p.confirm(AllowOrDeny.ALLOW)))
                .setNegativeButton(android.R.string.cancel, (d, w) -> r.send(p.confirm(AllowOrDeny.DENY)))
                .create(), r);
        return r.result;
    }

    /** Pop-ups opened by a tap are allowed; BrowserActivity loads them in the same tab. */
    @Override public GeckoResult<PromptResponse> onPopupPrompt(GeckoSession s, PopupPrompt p) {
        return GeckoResult.fromValue(p.confirm(AllowOrDeny.ALLOW));
    }

    // --- file uploads ---------------------------------------------------------------------------------

    @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession s, FilePrompt p) {
        if (pendingFileReply != null) pendingFileReply.dismiss();
        Reply r = new Reply(p);
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE);
        String[] mimes = p.mimeTypes == null ? new String[0] : p.mimeTypes;
        List<String> types = new ArrayList<>();
        for (String m : mimes) if (m != null && m.contains("/")) types.add(m);
        i.setType(types.size() == 1 ? types.get(0) : "*/*");
        if (types.size() > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
        if (p.type == FilePrompt.Type.MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            activity.startActivityForResult(Intent.createChooser(i, "Choose a file"), FILE_REQUEST);
            pendingFile = p;
            pendingFileReply = r;
        } catch (Exception e) {
            r.dismiss();
        }
        return r.result;
    }

    /** Called from BrowserActivity.onActivityResult. */
    void onFileResult(int resultCode, Intent data) {
        Reply r = pendingFileReply;
        FilePrompt p = pendingFile;
        pendingFile = null;
        pendingFileReply = null;
        if (r == null || p == null) return;
        if (resultCode != Activity.RESULT_OK || data == null) { r.dismiss(); return; }
        try {
            if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                Uri[] uris = new Uri[data.getClipData().getItemCount()];
                for (int i = 0; i < uris.length; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
                r.send(p.confirm(activity, uris));
            } else if (data.getData() != null) {
                r.send(p.confirm(activity, data.getData()));
            } else {
                r.dismiss();
            }
        } catch (Exception e) {
            r.dismiss();
        }
    }

    // --- helpers --------------------------------------------------------------------------------------

    private EditText field(String value, int type) {
        EditText e = new EditText(activity);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextColor(MainActivity.TXT);
        e.setHintTextColor(MainActivity.DIM);
        if (value != null) e.setText(value);
        e.setSelectAllOnFocus(true);
        return e;
    }

    private LinearLayout pad(android.view.View v) {
        LinearLayout box = new LinearLayout(activity);
        int p = Glass.dp(activity, 21);
        box.setPadding(p, Glass.dp(activity, 8), p, 0);
        box.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }
}
