package com.ash.attendance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an attendance table copied from the portal page (rich text or plain text) into the same
 * row shape MainActivity.parse() already understands. Pure Java so the rules can be tested on
 * the host without a device.
 */
final class AttendanceImport {
    private AttendanceImport() { }

    static final class Result {
        final String term;
        final List<String[]> rows;

        Result(String term, List<String[]> rows) {
            this.term = term == null ? "" : term;
            this.rows = rows;
        }
    }

    private static final Pattern TERM = Pattern.compile("(\\d{4}-\\d{2})\\s*(odd|even)\\s*sem",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_CELL = Pattern.compile("<t[dh][^>]*>(.*?)</t[dh]>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MANY_SPACES = Pattern.compile("\\s{2,}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    static Result parseHtml(String html) {
        if (html == null || html.trim().isEmpty()) return empty();
        String clean = html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ");
        List<List<String>> rows = new ArrayList<>();
        Matcher rm = HTML_ROW.matcher(clean);
        while (rm.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cm = HTML_CELL.matcher(rm.group(1));
            while (cm.find()) cells.add(cell(cm.group(1)));
            if (!cells.isEmpty()) rows.add(cells);
        }
        return new Result(term(html), mapRows(rows, true));
    }

    static Result parseText(String text) {
        if (text == null || text.trim().isEmpty()) return empty();
        String foundTerm = term(text);
        if (text.contains("<tr") && (text.contains("<td") || text.contains("<th"))) {
            Result html = parseHtml(text);
            if (!html.rows.isEmpty())
                return new Result(foundTerm.isEmpty() ? html.term : foundTerm, html.rows);
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean tabs = false;
        for (String line : lines) if (line.indexOf('\t') >= 0) { tabs = true; break; }
        List<List<String>> rows = new ArrayList<>();
        boolean exact = true;
        if (tabs) {
            for (String logical : joinTabbed(lines)) rows.add(split(logical));
        } else {
            for (String line : lines) {
                String t = line.replace('\u00A0', ' ').trim();
                if (t.isEmpty()) continue;
                if (t.indexOf(',') >= 0 && !MANY_SPACES.matcher(t).find()) {
                    rows.add(trimAll(Arrays.asList(t.split(",", -1))));
                } else if (MANY_SPACES.matcher(t).find()) {
                    exact = false;
                    rows.add(trimAll(Arrays.asList(MANY_SPACES.split(t, -1))));
                } else {
                    rows.add(trimAll(Arrays.asList(t)));
                }
            }
        }
        return new Result(foundTerm, mapRows(rows, exact));
    }

    private static Result empty() {
        return new Result("", new ArrayList<String[]>());
    }

    /** A cell's text, with line breaks preserved so course code + name stay in one cell. */
    private static String cell(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(div|p|li|tr|h[1-6])>", "\n")
                .replace("&nbsp;", " ").replace("&#160;", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&#x2F;", "/");
        s = TAG.matcher(s).replaceAll(" ");
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\\n")) {
            String t = line.replace('\u00A0', ' ').replaceAll("[ \\t\\x0B\\f]+", " ").trim();
            if (t.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(t);
        }
        return out.toString();
    }

    /** A pasted cell can contain a newline, so stitch lines back together until a row has its tabs. */
    private static List<String> joinTabbed(String[] lines) {
        int expected = 0;
        for (String line : lines) expected = Math.max(expected, countTabs(line));
        if (expected == 0) return Arrays.asList(lines);
        List<String> out = new ArrayList<>();
        StringBuilder buf = null;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            if (buf == null) buf = new StringBuilder(line);
            else buf.append('\n').append(line);
            if (countTabs(buf.toString()) >= expected) {
                out.add(buf.toString());
                buf = null;
            }
        }
        if (buf != null) out.add(buf.toString());
        return out;
    }

    private static int countTabs(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\t') n++;
        return n;
    }

    private static List<String> split(String logical) {
        String[] parts = logical.split("\t", -1);
        return trimAll(Arrays.asList(parts));
    }

    private static List<String> trimAll(List<String> cells) {
        List<String> out = new ArrayList<>(cells.size());
        for (String c : cells) out.add(c == null ? "" : c.replace('\u00A0', ' ').trim());
        return out;
    }

    private static List<String[]> mapRows(List<List<String>> all, boolean exact) {
        List<String[]> out = new ArrayList<>();
        Cols cols = null;
        for (List<String> cells : all) {
            if (cells.isEmpty()) continue;
            if (isHeader(cells)) {
                if (exact) cols = Cols.read(cells);
                continue;
            }
            String[] row = null;
            if (exact && cols != null && cols.usable()) row = fromCols(cells, cols);
            if (row == null) row = compact(cells);
            if (row == null) row = positional(cells);
            if (row != null) out.add(row);
        }
        return out;
    }

    private static boolean isHeader(List<String> cells) {
        int hits = 0;
        for (String c : cells) {
            String n = norm(c);
            if (n.contains("total")) hits++;
            if (n.contains("present")) hits++;
            if (n.contains("absent")) hits++;
            if (n.contains("percent") || n.contains("%")) hits++;
            if (n.contains("course")) hits++;
        }
        return hits >= 3;
    }

    private static String[] fromCols(List<String> cells, Cols cols) {
        String code = at(cells, cols.code);
        String name = at(cells, cols.name);
        String generic = at(cells, cols.course);
        if (name.isEmpty()) name = generic;
        if (code.isEmpty()) code = generic;
        return build(code, name, at(cells, cols.faculty),
                num(at(cells, cols.total)), num(at(cells, cols.present)), num(at(cells, cols.dl)),
                num(at(cells, cols.absent)), num(at(cells, cols.pct)), at(cells, cols.medical));
    }

    /** code, name, total, present, dl, absent, pct (or code, name, total, present, absent, pct). */
    private static String[] compact(List<String> cells) {
        int n = cells.size();
        if (n == 7 && numeric(cells, 2, 7))
            return build(cells.get(0), cells.get(1), "", num(cells.get(2)), num(cells.get(3)),
                    num(cells.get(4)), num(cells.get(5)), num(cells.get(6)), "");
        if (n == 6 && numeric(cells, 2, 6))
            return build(cells.get(0), cells.get(1), "", num(cells.get(2)), num(cells.get(3)),
                    0.0, num(cells.get(4)), num(cells.get(5)), "");
        return null;
    }

    /** The portal's own layout: the last six cells are Total, Present, DL, Absent, Percentage, Medical. */
    private static String[] positional(List<String> cells) {
        int n = cells.size();
        if (n < 6) return null;
        Double total = num(cells.get(n - 6)), present = num(cells.get(n - 5)), dl = num(cells.get(n - 4));
        Double absent = num(cells.get(n - 3)), pct = num(cells.get(n - 2));
        String medical = cells.get(n - 1);
        String course = n > 2 ? cells.get(2) : cells.get(0);
        String faculty = n >= 7 ? cells.get(n - 7) : "";
        if (!hasLetter(course)) course = firstText(cells);
        if (course == null || course.isEmpty()) return null;
        return build(course, "", faculty, total, present, dl, absent, pct, medical);
    }

    private static String[] build(String code, String name, String faculty, Double total, Double present,
                                  Double dl, Double absent, Double pct, String medical) {
        if (total == null || total <= 0) return null;
        if (present == null || present < 0) return null;
        double t = total, p = present;
        double d = dl == null ? 0 : dl;
        double a = absent == null ? Math.max(0, t - p) : absent;
        if (d < 0 || a < 0) return null;
        double percent = pct == null ? (t == 0 ? 0 : 100.0 * (p + d) / t) : pct;
        if (percent < 0 || percent > 100.0) return null;
        if (p > t + 0.5) return null;
        String c = code == null ? "" : code.trim();
        String nm = name == null ? "" : name.trim();
        if (c.isEmpty()) c = nm;
        if (nm.isEmpty() || nm.equals(c)) nm = "";
        String course = nm.isEmpty() ? c : c + "\n" + nm;
        if (course.isEmpty()) return null;
        String[] row = new String[12];
        Arrays.fill(row, "");
        row[0] = "1";
        row[2] = course;
        row[5] = faculty == null ? "" : faculty.trim();
        row[6] = whole(t);
        row[7] = whole(p);
        row[8] = whole(d);
        row[9] = whole(a);
        row[10] = String.format(Locale.US, "%.2f", percent);
        row[11] = medical == null ? "" : medical.trim();
        return row;
    }

    private static String at(List<String> cells, int i) {
        return i >= 0 && i < cells.size() ? cells.get(i) : "";
    }

    private static Double num(String s) {
        if (s == null) return null;
        String t = s.replace('\u00A0', ' ').replace("%", "").replace(",", "").trim();
        if (t.isEmpty()) return null;
        try {
            return Double.parseDouble(t);
        } catch (Exception ignored) {
            Matcher m = NUMBER.matcher(t);
            if (!m.find()) return null;
            try { return Double.parseDouble(m.group()); } catch (Exception e) { return null; }
        }
    }

    private static boolean numeric(List<String> cells, int from, int to) {
        for (int i = from; i < to; i++) if (num(cells.get(i)) == null) return false;
        return true;
    }

    private static String whole(double v) {
        return String.valueOf((long) Math.round(v));
    }

    private static boolean hasLetter(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) return true;
        return false;
    }

    private static String firstText(List<String> cells) {
        for (String c : cells) if (hasLetter(c) && num(c) == null) return c;
        return "";
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US).replaceAll("[^a-z0-9%]+", " ").trim();
    }

    private static String term(String text) {
        Matcher m = TERM.matcher(text == null ? "" : text);
        if (!m.find()) return "";
        String kind = "odd".equalsIgnoreCase(m.group(2)) ? "Odd" : "Even";
        return m.group(1) + " " + kind + " Sem";
    }

    private static final class Cols {
        int code = -1, name = -1, course = -1, faculty = -1, total = -1, present = -1,
                dl = -1, absent = -1, pct = -1, medical = -1;

        static Cols read(List<String> cells) {
            Cols c = new Cols();
            for (int i = 0; i < cells.size(); i++) {
                String n = norm(cells.get(i));
                if (n.isEmpty()) continue;
                boolean courseDone = false;
                if (n.contains("course") && n.contains("code")) { if (c.code < 0) c.code = i; courseDone = true; }
                else if (n.contains("course") && n.contains("name")) { if (c.name < 0) c.name = i; courseDone = true; }
                else if (n.contains("course")) { if (c.course < 0) c.course = i; courseDone = true; }
                if (courseDone) continue;
                if (n.contains("total")) { if (c.total < 0) c.total = i; }
                else if (n.contains("present")) { if (c.present < 0) c.present = i; }
                else if (n.contains("duty") || n.equals("dl")) { if (c.dl < 0) c.dl = i; }
                else if (n.contains("absent")) { if (c.absent < 0) c.absent = i; }
                else if (n.contains("%") || n.contains("percent") || n.contains("attendance")) { if (c.pct < 0) c.pct = i; }
                else if (n.contains("medical")) { if (c.medical < 0) c.medical = i; }
                else if (n.contains("faculty") || n.contains("staff") || n.contains("handled")) { if (c.faculty < 0) c.faculty = i; }
            }
            return c;
        }

        boolean usable() {
            return total >= 0 && present >= 0 && (absent >= 0 || pct >= 0);
        }
    }
}
