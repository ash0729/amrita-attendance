# Asking Amrita IT about portal access

Send this from your college email, and fill in the bracketed details. Keep the repo link out of the first email unless you are comfortable with IT reading the feedback automation; the request works without it.

**Subject:** Permission for a student-built, read-only attendance client

Dear Sir/Madam,

I am [name], a [year] B.Tech [branch] student at the [campus] campus, roll number [roll number].

I built a small Android app for my own use that displays my attendance from the student portal. Since 19 November 2026 the portal answers the app with "Access denied. Custom Android applications are not allowed." I have stopped the app from trying to work around that message, and I am writing to ask what is permitted.

1. Is a student-built, read-only client allowed to access the student portal?
2. If not, is there a supported API, or a process for registering a student client?
3. If neither is possible, could you confirm that the app should not access the portal at all? I will remove that part and keep only a manual import from my browser.

To be clear about what the app does: it only reads my own attendance and feedback status after I sign in with my Microsoft account. It stores nothing on a server, has no analytics or ads, and does not submit anything without me pressing a button. I can share the source with your team and make any change you require.

Thank you,
[name]
[roll number], [branch], [campus]
[phone / college email]

## What to expect

- **"No"** — the import + browser + official-app build is the final product. Nothing else in this plan depends on their answer.
- **"Use the official app"** — same outcome; keep the official-app button and the import.
- **"Here is an API / a client registration"** — then, and only then, add live campus features against that API. Do not implement against the endpoint names found in the official APK before this.
- **No reply** — send one polite follow-up after a week, then leave it. Do not build the evasion.
