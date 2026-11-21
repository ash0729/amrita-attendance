package com.ash.attendance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Plain main() test runner for Campus; run through test.sh. */
public final class CampusTest {
    static int checks, failed;

    static void eq(String what, Object want, Object got) {
        checks++;
        if (!String.valueOf(want).equals(String.valueOf(got))) {
            failed++;
            System.out.println("FAIL " + what + ": want <" + want + "> got <" + got + ">");
        }
    }

    static Campus.Table table(String caption, String[] headers, String[]... rows) {
        List<List<String>> r = new ArrayList<>();
        for (String[] row : rows) r.add(Arrays.asList(row));
        return new Campus.Table(caption, new ArrayList<>(Arrays.asList(headers)), r);
    }

    static Campus.Page page(String path, String title, Campus.Table... tables) {
        Campus.Page p = new Campus.Page();
        p.path = path;
        p.title = title;
        p.tables.addAll(Arrays.asList(tables));
        return p;
    }

    public static void main(String[] args) {
        dates();
        longDay();
        wideByDate();
        wideByCourse();
        monthly();
        passes();
        hostelInfo();
        hostelAttendance();
        generic();
        merge();
        System.out.println(checks + " checks, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void dates() {
        eq("slash", "2026-11-12", Campus.parseDate("12/11/2026"));
        eq("dash", "2026-11-12", Campus.parseDate("12-11-2026"));
        eq("iso", "2026-11-12", Campus.parseDate("2026-11-12"));
        eq("words", "2026-11-12", Campus.parseDate("12 Nov 2026"));
        eq("short year", "2026-11-12", Campus.parseDate("12-Nov-26"));
        eq("weekday prefix", "2026-11-12", Campus.parseDate("Thu, 12 November 2026"));
        eq("bad day", null, Campus.parseDate("31/02/2026"));
        eq("time is not a date", null, Campus.parseDate("10:30"));
        eq("hour label", null, Campus.parseDate("P1"));
        eq("month words", "2026-11", Campus.parseMonth("November 2026"));
        eq("month short", "2026-09", Campus.parseMonth("Sep-26"));
        eq("month numeric", "2026-08", Campus.parseMonth("08/2026"));
        eq("status P", "PRESENT", Campus.status("P"));
        eq("status DL", "OD", Campus.status("DL"));
        eq("status ML", "OTHER", Campus.status("ML"));
        eq("status P1 is not a status", null, Campus.status("P1"));
    }

    static void longDay() {
        // Extra column, odd column order, mixed date formats.
        Campus.Table t = table("", new String[]{"Sl", "Course", "Hour", "Status", "Attendance Date", "Faculty"},
                new String[]{"1", "CSE301 DBMS", "1", "Present", "12/11/2026", "X"},
                new String[]{"2", "CSE302 OS", "2", "Absent", "12-11-2026", "Y"},
                new String[]{"3", "MAT301", "3", "OD", "13 Nov 2026", "Z"},
                new String[]{"4", "CSE301 DBMS", "1", "A", "14-Nov-26", "X"},
                new String[]{"5", "CSE301 DBMS", "1", "P", "2026-11-15", "X"});
        Campus.Result r = Campus.classify(page("/client/day-attendance", "Day wise attendance", t));
        eq("long kind", Campus.Kind.DAY, r.kind);
        eq("long count", 5, r.day.size());
        eq("long date0", "2026-11-12", r.day.get(0).get("date"));
        eq("long course0", "CSE301 DBMS", r.day.get(0).get("course"));
        eq("long hour1", "2", r.day.get(1).get("hour"));
        eq("long status1", "ABSENT", r.day.get(1).get("status"));
        eq("long status2", "OD", r.day.get(2).get("status"));
        eq("long date3", "2026-11-14", r.day.get(3).get("date"));
        eq("long date4", "2026-11-15", r.day.get(4).get("date"));

        // No header row at all: first row is promoted.
        Campus.Table nh = table("", new String[]{},
                new String[]{"Date", "Subject", "Period", "Status"},
                new String[]{"01/11/2026", "PHY", "4", "P"},
                new String[]{"01/11/2026", "CHE", "5", "A"});
        Campus.Result r2 = Campus.classify(page("/x", "", nh));
        eq("headerless count", 2, r2.day.size());
        eq("headerless course", "CHE", r2.day.get(1).get("course"));
    }

    static void wideByDate() {
        Campus.Table t = table("", new String[]{"Date", "1", "2", "3", "4"},
                new String[]{"02/11/2026", "P", "P", "A", "P"},
                new String[]{"03/11/2026", "P", "OD", "P", "P"});
        Campus.Result r = Campus.classify(page("/client/attendance-report", "Attendance", t));
        eq("wideA kind", Campus.Kind.DAY, r.kind);
        eq("wideA count", 8, r.day.size());
        eq("wideA hour", "3", r.day.get(2).get("hour"));
        eq("wideA absent", "ABSENT", r.day.get(2).get("status"));
        eq("wideA od", "OD", r.day.get(5).get("status"));
    }

    static void wideByCourse() {
        Campus.Table t = table("", new String[]{"Course", "02/11/2026", "03/11/2026", "04/11/2026"},
                new String[]{"CSE301", "P", "A", "P"},
                new String[]{"CSE302", "A", "P", "P"});
        Campus.Result r = Campus.classify(page("/client/attendance", "Attendance", t));
        eq("wideB kind", Campus.Kind.DAY, r.kind);
        eq("wideB count", 6, r.day.size());
        eq("wideB course", "CSE302", r.day.get(3).get("course"));
        eq("wideB date", "2026-11-02", r.day.get(3).get("date"));
        eq("wideB status", "ABSENT", r.day.get(3).get("status"));
    }

    static void monthly() {
        Campus.Table t = table("", new String[]{"Course", "Month", "Total Classes", "Attended", "Percentage"},
                new String[]{"CSE301", "October 2026", "20", "18", "90"},
                new String[]{"CSE301", "Nov-2026", "10", "7", ""});
        Campus.Result r = Campus.classify(page("/client/monthly-attendance", "Monthly", t));
        eq("monthly kind", Campus.Kind.MONTHLY, r.kind);
        eq("monthly count", 2, r.monthly.size());
        eq("monthly month", "2026-10", r.monthly.get(0).get("month"));
        eq("monthly attended", "18", r.monthly.get(0).get("attended"));
        eq("monthly total", "20", r.monthly.get(0).get("total"));
        eq("monthly computed pct", "70.00", r.monthly.get(1).get("pct"));
    }

    static void passes() {
        Campus.Table t = table("", new String[]{"Pass Type", "Out Date", "In Date", "Reason", "Status", "Applied On"},
                new String[]{"Home Pass", "14/11/2026", "16/11/2026", "Family", "Approved", "10/11/2026"},
                new String[]{"Gate Pass", "20/11/2026", "20/11/2026", "Bank", "Pending", "19/11/2026"});
        Campus.Page p = page("/client/hostel-pass", "Hostel passes", t);
        p.pairs.add(new String[]{"Home passes taken this month:", "2"});
        p.pairs.add(new String[]{"Hostel Block", "Block C"});
        Campus.Result r = Campus.classify(p);
        eq("passes count", 2, r.passes.size());
        eq("passes type", "Home Pass", r.passes.get(0).get("type"));
        eq("passes from", "14/11/2026", r.passes.get(0).get("from"));
        eq("passes to", "16/11/2026", r.passes.get(0).get("to"));
        eq("passes status", "Pending", r.passes.get(1).get("status"));
        eq("passes reason", "Bank", r.passes.get(1).get("reason"));
        eq("home pass count", "2", r.homePass);
        eq("pass page hostel info", "Block C", r.info.get("Hostel Block"));
        eq("passes not generic", 0, r.generic.size());

        // A day-attendance table with a "Type" column must not be taken for passes.
        Campus.Table d = table("", new String[]{"Date", "Type", "Status"},
                new String[]{"01/11/2026", "Theory", "P"}, new String[]{"02/11/2026", "Lab", "A"});
        eq("type column stays day", Campus.Kind.DAY, Campus.classify(page("/x", "", d)).kind);
    }

    static void hostelInfo() {
        Campus.Page p = new Campus.Page();
        p.path = "/client/hostel";
        p.pairs.add(new String[]{"Hostel", "Kaveri"});
        p.pairs.add(new String[]{"Room No", "214"});
        p.pairs.add(new String[]{"Warden", "Dr. Rao"});
        p.pairs.add(new String[]{"Favourite colour", "blue"});
        Campus.Result r = Campus.classify(p);
        eq("hostel kind", Campus.Kind.HOSTEL, r.kind);
        eq("hostel size", 3, r.info.size());
        eq("hostel warden", "Dr. Rao", r.info.get("Warden"));

        Campus.Table two = table("", new String[]{"Field", "Value"},
                new String[]{"Block", "C"}, new String[]{"Floor", "2"}, new String[]{"Mess", "Veg"},
                new String[]{"Home pass count", "3 of 6"});
        Campus.Result r2 = Campus.classify(page("/client/profile", "Profile", two));
        eq("2col kind", Campus.Kind.HOSTEL, r2.kind);
        eq("2col mess", "Veg", r2.info.get("Mess"));
        eq("2col home pass", "3", r2.homePass);
    }

    static void hostelAttendance() {
        Campus.Table t = table("", new String[]{"Date", "Attendance"},
                new String[]{"01/11/2026", "Present"},
                new String[]{"02/11/2026", "Absent"},
                new String[]{"03/11/2026", "On Leave"},
                new String[]{"04/11/2026", "Home Pass"});
        Campus.Result r = Campus.classify(page("/client/hostel-attendance", "Hostel attendance", t));
        eq("hostel att kind", Campus.Kind.HOSTEL, r.kind);
        eq("hostel att day empty", 0, r.day.size());
        eq("hostel att count", 4, r.hostelAtt.size());
        eq("hostel att leave", "LEAVE", r.hostelAtt.get(2).get("status"));
        eq("hostel att pass", "PASS", r.hostelAtt.get(3).get("status"));
        // The same table outside a hostel page is class attendance.
        eq("same table, class page", Campus.Kind.DAY, Campus.classify(page("/client/att", "Attendance", t)).kind);
    }

    static void generic() {
        Campus.Table t = table("Fees", new String[]{"Item", "Amount"},
                new String[]{"Tuition", "100000"}, new String[]{"Exam", "2000"}, new String[]{"Misc", "10"});
        Campus.Result r = Campus.classify(page("/client/fees", "Fees", t));
        eq("generic kind", Campus.Kind.GENERIC, r.kind);
        eq("generic rows kept", 3, r.generic.get(0).rows.size());
        eq("generic caption", "Fees", r.generic.get(0).caption);
        eq("empty page", Campus.Kind.NONE, Campus.classify(page("/", "Home")).kind);
    }

    static void merge() {
        Campus.Table a = table("", new String[]{"Date", "Course", "Hour", "Status"},
                new String[]{"01/10/2026", "CSE", "1", "A"}, new String[]{"02/10/2026", "CSE", "1", "P"});
        Campus.Table b = table("", new String[]{"Date", "Course", "Hour", "Status"},
                new String[]{"02/10/2026", "CSE", "1", "A"}, new String[]{"01/11/2026", "CSE", "1", "P"});
        List<Map<String, String>> m = Campus.mergeDay(Campus.classify(page("/x", "", a)).day, Campus.classify(page("/x", "", b)).day);
        eq("merge size", 3, m.size());
        eq("merge replaced", "ABSENT", m.get(1).get("status"));
    }
}
