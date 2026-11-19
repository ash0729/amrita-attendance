#!/usr/bin/env python3
"""Stateful mock of the Amrita student portal's feedback pages. Stdlib only.
Usage: python3 -I test/mock-portal.py [port]     (default 8765)
Extra endpoints for tests: GET /__state (JSON), POST /__reset.
Never talks to the network; binds to 127.0.0.1 only.
"""
import html
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

OTP = "123456"
LOCK = threading.Lock()

# kind "course": /client/course-feedback?id=N ; kind "tlp": /client/feedback?id=N
# otp: "start" (OTP step before the questions), "end" (OTP box next to Finish) or None
# stuck: Save & Next never advances (loop-guard test)
COURSES = {
    "1": dict(kind="course", code="CS101", name="Data Structures", faculty="Dr. A Rao", n=5, opts=5, otp="start"),
    "2": dict(kind="course", code="CS102", name="Operating Systems", faculty="Dr. B Nair", n=3, opts=5, otp="end"),
    "3": dict(kind="course", code="CS103", name="Networks", faculty="Dr. C Iyer", n=3, opts=5, otp=None, stuck=True),
    "T1": dict(kind="tlp", code="CS101", name="Data Structures", faculty="Dr. A Rao", n=3, opts=4, otp=None),
}
STATE = {}


def reset():
    STATE.clear()
    for k in COURSES:
        STATE[k] = dict(sent=False, otp_ok=False, idx=0, answers=[], completed=False)


reset()

STYLE = """<style>
body{font-family:Georgia,serif;font-size:20px;letter-spacing:1px;text-transform:uppercase;margin:0;padding:16px 16px 340px}
table{border-collapse:collapse;width:100%} td,th{border:1px solid #999;padding:8px}
button{font:inherit;letter-spacing:3px;padding:6px 10px}
.step-item{display:inline-block;width:2em;border:1px solid #666;text-align:center}
.step-item.active{background:#09f;color:#fff}
label{display:block;padding:6px 0} .err{color:#b00}
</style>"""


def page(title, body):
    return ("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
            "<title>%s</title>%s</head><body>%s</body></html>" % (html.escape(title), STYLE, body))


def url_for(cid):
    c = COURSES[cid]
    return ("/client/course-feedback?id=%s" if c["kind"] == "course" else "/client/feedback?id=%s") % cid


def list_url(cid):
    return "/client/course-feedback-list" if COURSES[cid]["kind"] == "course" else "/client/class-feedback"


def course_list():
    rows = []
    n = 0
    for cid, c in COURSES.items():
        if c["kind"] != "course":
            continue
        n += 1
        st = STATE[cid]
        if st["completed"]:
            status, act = "Completed", "Completed"
        else:
            status = "Pending"
            act = '<a href="%s" target="_blank">Submit Feedback</a>' % url_for(cid)
        rows.append("<tr><td>%d</td><td>%s<br>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
                    % (n, c["code"], html.escape(c["name"]), html.escape(c["faculty"]), status, act))
    return page("Course Feedback", (
        "<h2>Course Feedback</h2><select><option>Odd Sem 2026</option><option selected>Even Sem 2026</option></select>"
        "<table><tr><th>Sl No</th><th>Course</th><th>Faculty</th><th>Status</th><th>Action</th></tr>%s</table>"
        "<p><a href='/client/class-feedback'>Class feedback</a></p>" % "".join(rows)))


def tlp_list():
    c = COURSES["T1"]
    st = STATE["T1"]
    act = "Completed" if st["completed"] else '<a href="%s" target="_blank">Submit Feedback</a>' % url_for("T1")
    return page("Class Feedback", (
        "<h2>Class Feedback</h2><select><option selected>Even Sem 2026</option></select>"
        "<table><tr><th>Sl No</th><th>Class</th><th>Course</th></tr>"
        "<tr><td>1</td><td>Class A</td><td>%s %s</td></tr>"
        "<tr><td>%s</td><td>%s</td><td>%s</td></tr></table>"
        % (c["code"], html.escape(c["name"]), html.escape(c["faculty"]),
           "Completed" if st["completed"] else "Pending", act)))


def otp_step(cid, err=""):
    st = STATE[cid]
    if not st["sent"]:
        form = "<p>Verify your identity to start.</p><button type=submit name=send_otp>Send OTP</button>"
    else:
        form = ("<p>An OTP was sent to your email.</p><input name=otp placeholder='Enter OTP' autocomplete=off>"
                "<button type=submit name=verify_otp>Verify</button>")
    return page("OTP", "<h2>Feedback</h2><p class=err>%s</p><form method=post action='%s'>%s</form>"
                % (html.escape(err), url_for(cid), form))


def question_page(cid):
    c, st = COURSES[cid], STATE[cid]
    i = st["idx"]
    qid = (int(cid[-1]) * 100 + i + 1) if cid[-1].isdigit() else 900 + i + 1
    if cid == "T1":
        qid = 700 + i + 1
    steps = "".join('<span class="step-item%s">%d</span>' % (" active" if k == i else "", k + 1) for k in range(c["n"]))
    radios = "".join("<label><input type=radio name=option_id value=%d> Option %d</label>" % (qid * 10 + k, k)
                     for k in range(1, c["opts"] + 1))
    return page("Question", "<div>%s</div><h3>Question %d of %d</h3><form method=post action='%s'>"
                "<input type=hidden name=question_id value=%d>%s"
                "<button type=submit name=submit_ans>Save &amp; Next</button></form>"
                % (steps, i + 1, c["n"], url_for(cid), qid, radios))


def finish_page(cid, err=""):
    c = COURSES[cid]
    otp = "<input name=otp placeholder='Enter OTP' autocomplete=off>" if c["otp"] == "end" else ""
    return page("Finish", "<h3>All questions answered</h3><p class=err>%s</p><form method=post action='%s'>%s"
                "<button type=submit name=finish_feedback onclick=\"return confirm('Are you sure?')\">Finish</button></form>"
                % (html.escape(err), url_for(cid), otp))


def answers_state():
    out = {}
    for k, st in STATE.items():
        out[k] = dict(completed=st["completed"], otp_ok=st["otp_ok"],
                      answers=[dict(question_id=q, option=o) for q, o in st["answers"]])
    return out


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body, ctype="text/html; charset=utf-8", loc=None):
        data = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        if loc:
            self.send_header("Location", loc)
        self.end_headers()
        self.wfile.write(data)

    def redirect(self, loc):
        self.send(303, "", loc=loc)

    def cid(self, q):
        cid = (q.get("id") or [""])[0]
        want = "course" if urlparse(self.path).path.endswith("/course-feedback") else "tlp"
        return cid if cid in COURSES and COURSES[cid]["kind"] == want else None

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        with LOCK:
            if u.path == "/":
                return self.redirect("/client/course-feedback-list")
            if u.path == "/__state":
                return self.send(200, json.dumps(answers_state()), "application/json")
            if u.path == "/client/course-feedback-list":
                return self.send(200, course_list())
            if u.path == "/client/class-feedback":
                return self.send(200, tlp_list())
            if u.path == "/client/feedback-submitted":
                back = list_url((q.get("id") or ["1"])[0])
                return self.send(200, page("Submitted", "<h2>Feedback submitted</h2><p><a href='%s'>Back to the list</a></p>" % back))
            if u.path in ("/client/course-feedback", "/client/feedback"):
                cid = self.cid(q)
                if not cid:
                    return self.send(404, page("Not found", "No such course"))
                return self.send(200, self.render(cid))
        self.send(404, page("Not found", "Not found"))

    def render(self, cid, err=""):
        c, st = COURSES[cid], STATE[cid]
        if st["completed"]:
            return page("Done", "<h2>Feedback already submitted</h2><a href='%s'>Back to the list</a>" % list_url(cid))
        if c["otp"] == "start" and not st["otp_ok"]:
            return otp_step(cid, err)
        if st["idx"] < c["n"]:
            return question_page(cid)
        return finish_page(cid, err)

    def do_POST(self):
        u = urlparse(self.path)
        n = int(self.headers.get("Content-Length") or 0)
        form = parse_qs(self.rfile.read(n).decode(), keep_blank_values=True)
        with LOCK:
            if u.path == "/__reset":
                reset()
                return self.send(200, "ok", "text/plain")
            if u.path not in ("/client/course-feedback", "/client/feedback"):
                return self.send(404, "no")
            cid = self.cid(parse_qs(u.query))
            if not cid:
                return self.send(404, "no")
            c, st = COURSES[cid], STATE[cid]
            f = lambda k: (form.get(k) or [""])[0]
            if "send_otp" in form:
                st["sent"] = True
            elif "verify_otp" in form:
                if f("otp").strip() != OTP:
                    return self.send(200, self.render(cid, "Wrong OTP"))
                st["otp_ok"] = True
            elif "submit_ans" in form and not st["completed"]:
                if c["otp"] == "start" and not st["otp_ok"]:
                    return self.send(403, "OTP required")
                st["answers"].append((int(f("question_id") or 0), int(f("option_id") or 0) % 10))
                if not c.get("stuck"):
                    st["idx"] += 1
            elif "finish_feedback" in form:
                if st["idx"] < c["n"]:
                    return self.send(400, "not finished")
                if c["otp"] == "end" and f("otp").strip() != OTP:
                    return self.send(200, self.render(cid, "Wrong OTP"))
                st["completed"] = True
                return self.redirect("/client/feedback-submitted?id=%s" % cid)
            return self.redirect(url_for(cid))


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    srv = ThreadingHTTPServer(("127.0.0.1", port), H)
    print("mock portal on http://localhost:%d" % port, flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
