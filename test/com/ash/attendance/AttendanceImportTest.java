package com.ash.attendance;

/** Host-side checks for AttendanceImport. Run with ./test.sh. */
public final class AttendanceImportTest {
    private static int failures;

    public static void main(String[] args) {
        tabbedWithHeader();
        multilineCourse();
        positional();
        htmlTable();
        computedPercentage();
        compactCsv();
        junk();
        termDetected();
        if (failures > 0) {
            System.out.println(failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("All attendance import checks passed");
    }

    private static void tabbedWithHeader() {
        String text = "Sl No\tCourse Code\tCourse Name\tFaculty\tTotal\tPresent\tDL\tAbsent\tPercentage\tMedical\n"
                + "1\tCS101\tData Structures\tDr A\t40\t38\t0\t2\t95.00\t\n"
                + "2\tCS102\tOperating Systems\tDr B\t40\t30\t2\t8\t80.00\t";
        AttendanceImport.Result r = AttendanceImport.parseText(text);
        eq("tabbed rows", 2, r.rows.size());
        String[] a = r.rows.get(0);
        eq("tabbed course", "CS101\nData Structures", a[2]);
        eq("tabbed faculty", "Dr A", a[5]);
        eq("tabbed total", "40", a[6]);
        eq("tabbed present", "38", a[7]);
        eq("tabbed dl", "0", a[8]);
        eq("tabbed absent", "2", a[9]);
        eq("tabbed pct", "95.00", a[10]);
        eq("tabbed second", "CS102\nOperating Systems", r.rows.get(1)[2]);
    }

    private static void multilineCourse() {
        String text = "Sl No\tCourse Code\tCourse Name\tFaculty\tTotal\tPresent\tDL\tAbsent\tPercentage\n"
                + "1\tCS103\tMachine\nLearning\tDr C\t40\t36\t0\t4\t90.00";
        AttendanceImport.Result r = AttendanceImport.parseText(text);
        eq("multiline rows", 1, r.rows.size());
        eq("multiline course", "CS103\nMachine\nLearning", r.rows.get(0)[2]);
        eq("multiline total", "40", r.rows.get(0)[6]);
    }

    private static void positional() {
        String text = "1\tCS201\tAlgorithms\tDr D\t40\t34\t0\t6\t85.00\t";
        AttendanceImport.Result r = AttendanceImport.parseText(text);
        eq("positional rows", 1, r.rows.size());
        eq("positional course", "Algorithms", r.rows.get(0)[2]);
        eq("positional faculty", "Dr D", r.rows.get(0)[5]);
        eq("positional pct", "85.00", r.rows.get(0)[10]);
    }

    private static void htmlTable() {
        String html = "<table><tr><th>Course Code</th><th>Course Name</th><th>Total</th>"
                + "<th>Present</th><th>DL</th><th>Absent</th><th>Percentage</th></tr>"
                + "<tr><td>CS301</td><td>Networks</td><td>40</td><td>32</td><td>0</td>"
                + "<td>8</td><td>80%</td></tr></table>";
        AttendanceImport.Result r = AttendanceImport.parseHtml(html);
        eq("html rows", 1, r.rows.size());
        eq("html course", "CS301\nNetworks", r.rows.get(0)[2]);
        eq("html pct", "80.00", r.rows.get(0)[10]);
    }

    private static void computedPercentage() {
        String text = "1\tCS401\tCompilers\tDr E\t40\t30\t0\t10\t\t";
        AttendanceImport.Result r = AttendanceImport.parseText(text);
        eq("computed rows", 1, r.rows.size());
        eq("computed pct", "75.00", r.rows.get(0)[10]);
        eq("computed attended", "30", r.rows.get(0)[7]);
    }

    private static void compactCsv() {
        AttendanceImport.Result r = AttendanceImport.parseText("CS101,DataStructures,40,38,0,2,95.00");
        eq("csv rows", 1, r.rows.size());
        eq("csv course", "CS101\nDataStructures", r.rows.get(0)[2]);
        eq("csv total", "40", r.rows.get(0)[6]);
        eq("csv pct", "95.00", r.rows.get(0)[10]);
    }

    private static void junk() {
        AttendanceImport.Result r = AttendanceImport.parseText("hello world\nnot a table at all");
        eq("junk rows", 0, r.rows.size());
        AttendanceImport.Result empty = AttendanceImport.parseText("");
        eq("empty rows", 0, empty.rows.size());
    }

    private static void termDetected() {
        AttendanceImport.Result r = AttendanceImport.parseText(
                "2025-26 Odd Sem\nCS101,DataStructures,40,38,0,2,95.00");
        eq("term", "2025-26 Odd Sem", r.term);
    }

    private static void eq(String what, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) return;
        failures++;
        System.out.println("FAIL " + what + ": expected <" + expected + "> got <" + actual + ">");
    }
}
