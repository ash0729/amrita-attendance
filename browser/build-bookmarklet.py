#!/usr/bin/env python3
"""Builds the bookmarklet from amrita-feedback.user.js. Run: python3 -I browser/build-bookmarklet.py

Conservative on purpose: it drops the ==UserScript== header and whole-line // comments, nothing else (no
renaming, no whitespace squeezing), keeps newlines, and percent-encodes the result into a javascript: URL.
The `window.__afhFrame=1;` prefix is what switches the same code into frame mode.
"""
import os
import re
import sys
from urllib.parse import quote

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.dirname(here)
src = open(os.path.join(here, "amrita-feedback.user.js"), encoding="utf-8").read()

src = re.sub(r"// ==UserScript==.*?// ==/UserScript==\n", "", src, flags=re.S)
lines = [ln.rstrip() for ln in src.split("\n") if ln.strip() and not ln.lstrip().startswith("//")]
body = "\n".join(lines)
if re.search(r"^\s*/\*", body, re.M):
    sys.exit("block comment found; strip it by hand or extend this script")

code = "window.__afhFrame=1;\n" + body + "\n"
# Characters left as-is; everything else (including % # space " < > \ and newlines) is percent-encoded.
url = "javascript:" + quote(code, safe="!'()*-._~=;,:{}[]+/|&?$@^")

for path in (os.path.join(here, "bookmarklet.txt"), os.path.join(root, "assets", "feedback-bookmarklet.txt")):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(url)
print("wrote %d bytes (%d lines of code)" % (len(url.encode()), len(lines)))
