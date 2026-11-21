package com.ash.attendance;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Saves what the Campus parser found in SharedPreferences, as JSON with the import time. */
final class CampusStore {
    private CampusStore() { }

    static final int MAX_GENERIC = 10;
    static final int MAX_GENERIC_ROWS = 200;

    /** Page JSON from the browser bridge: {path, title, tables:[{caption, headers, rows}], pairs:[[label, value]]}. */
    static Campus.Page toPage(JSONObject o) {
        Campus.Page p = new Campus.Page();
        p.path = o.optString("path", "");
        p.title = o.optString("title", "");
        JSONArray tables = o.optJSONArray("tables");
        for (int i = 0; tables != null && i < tables.length(); i++) {
            JSONObject t = tables.optJSONObject(i);
            if (t == null) continue;
            Campus.Table table = new Campus.Table();
            table.caption = t.optString("caption", "");
            table.headers = strings(t.optJSONArray("headers"));
            JSONArray rows = t.optJSONArray("rows");
            for (int r = 0; rows != null && r < rows.length(); r++) table.rows.add(strings(rows.optJSONArray(r)));
            p.tables.add(table);
        }
        JSONArray pairs = o.optJSONArray("pairs");
        for (int i = 0; pairs != null && i < pairs.length(); i++) {
            JSONArray pr = pairs.optJSONArray(i);
            if (pr != null && pr.length() >= 2) p.pairs.add(new String[]{pr.optString(0), pr.optString(1)});
        }
        return p;
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i, ""));
        return out;
    }

    // ------------------------------------------------------------------------------------------ write

    /** Saves every part the result holds; returns a short summary for the status line. */
    static String save(SharedPreferences prefs, Campus.Page page, Campus.Result r, long now) {
        SharedPreferences.Editor e = prefs.edit();
        List<String> parts = new ArrayList<>();
        if (!r.day.isEmpty()) {
            List<Map<String, String>> merged = Campus.mergeDay(records(prefs.getString("campus_day", null)), r.day);
            e.putString("campus_day", toJson(merged).toString()).putLong("campus_day_time", now);
            parts.add(r.day.size() + " class records");
        }
        if (!r.monthly.isEmpty()) {
            e.putString("campus_monthly", toJson(r.monthly).toString()).putLong("campus_monthly_time", now);
            parts.add("monthly totals");
        }
        if (!r.info.isEmpty() || r.homePass != null) {
            try {
                JSONObject h = new JSONObject();
                JSONObject old = new JSONObject(prefs.getString("campus_hostel", "{}"));
                JSONObject info = new JSONObject();
                Map<String, String> src = r.info.isEmpty() ? mapOf(old.optJSONObject("info")) : r.info;
                for (Map.Entry<String, String> en : src.entrySet()) info.put(en.getKey(), en.getValue());
                h.put("info", info);
                String hp = r.homePass != null ? r.homePass : old.optString("homePass", "");
                if (!hp.isEmpty()) h.put("homePass", hp);
                e.putString("campus_hostel", h.toString()).putLong("campus_hostel_time", now);
                parts.add("hostel details");
            } catch (Exception ignored) { }
        }
        if (!r.hostelAtt.isEmpty()) {
            e.putString("campus_hostel_att", toJson(r.hostelAtt).toString()).putLong("campus_hostel_att_time", now);
            parts.add("hostel attendance");
        }
        if (!r.passes.isEmpty()) {
            e.putString("campus_passes", toJson(r.passes).toString()).putLong("campus_passes_time", now);
            parts.add(r.passes.size() + " passes");
        }
        if (!r.generic.isEmpty()) {
            try {
                JSONArray out = new JSONArray();
                java.util.HashSet<String> keys = new java.util.HashSet<>();
                for (Campus.Table t : r.generic) {
                    String key = page.path + "|" + t.caption + "|" + t.headers;
                    keys.add(key);
                    out.put(tableJson(key, page, t, now));
                }
                JSONArray old = new JSONArray(prefs.getString("campus_generic", "[]"));
                for (int i = 0; i < old.length() && out.length() < MAX_GENERIC; i++)
                    if (!keys.contains(old.getJSONObject(i).optString("key"))) out.put(old.getJSONObject(i));
                while (out.length() > MAX_GENERIC) out.remove(out.length() - 1);
                e.putString("campus_generic", out.toString());
                parts.add(r.generic.size() + (r.generic.size() == 1 ? " table" : " tables"));
            } catch (Exception ignored) { }
        }
        e.apply();
        return parts.isEmpty() ? "Nothing to save from that page" : "Saved " + String.join(", ", parts);
    }

    private static JSONObject tableJson(String key, Campus.Page page, Campus.Table t, long now) throws Exception {
        JSONObject o = new JSONObject();
        o.put("key", key);
        o.put("title", t.caption.isEmpty() ? page.title : t.caption);
        o.put("path", page.path);
        o.put("time", now);
        o.put("headers", new JSONArray(t.headers));
        JSONArray rows = new JSONArray();
        for (int i = 0; i < t.rows.size() && i < MAX_GENERIC_ROWS; i++) rows.put(new JSONArray(t.rows.get(i)));
        o.put("rows", rows);
        return o;
    }

    // ------------------------------------------------------------------------------------------ read

    static JSONArray toJson(List<Map<String, String>> recs) {
        JSONArray a = new JSONArray();
        for (Map<String, String> m : recs) {
            JSONObject o = new JSONObject();
            try { for (Map.Entry<String, String> en : m.entrySet()) o.put(en.getKey(), en.getValue()); }
            catch (Exception ignored) { }
            a.put(o);
        }
        return a;
    }

    static List<Map<String, String>> records(String json) {
        List<Map<String, String>> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) out.add(mapOf(a.getJSONObject(i)));
        } catch (Exception ignored) { }
        return out;
    }

    static Map<String, String> mapOf(JSONObject o) {
        Map<String, String> m = new LinkedHashMap<>();
        if (o == null) return m;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            m.put(k, o.optString(k, ""));
        }
        return m;
    }
}
