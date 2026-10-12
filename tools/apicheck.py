#!/usr/bin/env python3
"""Reject source that would compile against android-34 but crash on old Android.

We build against the newest android.jar while shipping to minSdkVersion 4, so
javac happily accepts calls that do not exist on a 2010 device. They compile
clean and then throw NoSuchMethodError at runtime, on hardware where attaching
a debugger is impractical. Neither aapt2 nor d8 performs any API-level check,
so this script is the only thing standing between us and that class of bug.

Comments and string literals are stripped before matching, so a mention of a
forbidden symbol in prose or in an error message does not trip the check --
only real code does. Line numbers still refer to the original file.
"""
import os
import re
import sys

# --- Java: (regex, why) -----------------------------------------------------
JAVA_DENY = [
    (r"android\.util\.Base64",        "API 8  - use com.oneai.util.Base64"),
    (r"\.apply\(\)",                  "API 9  - SharedPreferences.Editor.apply(); use commit()"),
    (r"\.setBackground\(",            "API 16 - use setBackgroundDrawable()"),
    (r"\bonBackPressed\s*\(",         "API 5  - handle KEYCODE_BACK in onKeyDown()"),
    (r"\bonKeyLongPress\s*\(",        "API 5  - use KeyEvent.getRepeatCount()"),
    (r"\banimateLayoutChanges",       "API 11 - use LayoutAnimationController"),
    (r"\bGridLayout\b",               "API 14 - use LinearLayout/TableLayout"),
    (r"\btry\s*\(",                   "try-with-resources: AutoCloseable is API 19"),
    (r"\bObjects\s*\.",               "java.util.Objects - not backported by d8"),
    (r"java\.util\.stream",           "java.util.stream - not backported by d8"),
    (r"\.stream\s*\(\s*\)",           "Stream - not backported by d8"),
    (r"\bOptional\b",                 "java.util.Optional - not backported by d8"),
    (r"\bStandardCharsets\b",         'API 19 - use getBytes("UTF-8")'),
    (r"\bjava\.time\b",               "API 26 - use Calendar/SimpleDateFormat"),
    (r"\bString\s*\.\s*join\s*\(",    "API 26 - build the string manually"),
    (r"\bAsyncTask\b",                "serialisation changed at API 11 - use Executor+Handler"),
    (r"\bgetStringSet\b",             "API 11 - store a joined String instead"),
    (r"\bMODE_MULTI_PROCESS\b",       "API 11"),
    (r"\bTheme\.(Holo|Material|DeviceDefault)", "modern theme parent - must live in a -vN folder"),
]

# --- XML: attributes whose mere presence is a bug ---------------------------
XML_DENY = [
    (r"android:animateLayoutChanges", "API 11"),
    (r"state_activated",              "API 11 - use state_selected"),
    (r"<GridLayout",                  "API 14"),
    (r"android:textAllCaps",          "API 14"),
    (r"android:fontFamily",           "API 16"),
    (r"android:drawableStart",        "API 17"),
    (r"android:drawableEnd",          "API 17"),
    (r"android:paddingStart",         "API 17"),
    (r"android:paddingEnd",           "API 17"),
    (r"android:elevation",            "API 21"),
    (r"android:backgroundTint",       "API 21"),
    (r"<ripple",                      "API 21 - use a <selector>"),
    (r"<vector",                      "API 21 - use a <shape>"),
    (r"android:selectableItemBackground", "API 11"),
]

# A theme parent is only safe inside a version-qualified folder, because style
# parents resolve at runtime: an unqualified Theme.Holo crashes API 4-10 with
# Resources$NotFoundException.
VERSIONED_THEME = re.compile(r"@android:style/Theme\.(Holo|Material|DeviceDefault)")
VERSIONED_DIR = re.compile(r"-(v\d+)(/|$)")


def strip_java(text):
    """Blank out comments and string/char literals, preserving line structure."""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif c in "\"'":
            quote, j = c, i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == quote:
                    j += 1
                    break
                # an unterminated literal means a newline ended it
                if text[j] == "\n":
                    break
                j += 1
            out.append(quote + " " * max(0, j - i - 2) + (quote if j <= n and j > i + 1 and text[j - 1] == quote else ""))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def strip_xml(text):
    return re.sub(r"<!--.*?-->",
                  lambda m: "".join(ch if ch == "\n" else " " for ch in m.group(0)),
                  text, flags=re.S)


def scan(root):
    findings = []
    for dirpath, _dirs, files in os.walk(root):
        rel_dir = os.path.relpath(dirpath, root)
        for name in files:
            path = os.path.join(dirpath, name)
            try:
                with open(path, "r", encoding="utf-8", errors="replace") as fh:
                    raw = fh.read()
            except OSError:
                continue

            if name.endswith(".java"):
                stripped = strip_java(raw)
                rules = JAVA_DENY
            elif name.endswith(".xml"):
                stripped = strip_xml(raw)
                rules = XML_DENY
                if not VERSIONED_DIR.search(rel_dir.replace(os.sep, "/") + "/"):
                    for lineno, line in enumerate(stripped.splitlines(), 1):
                        if VERSIONED_THEME.search(line):
                            findings.append((path, lineno,
                                             "modern theme parent outside a -vN folder",
                                             line.strip()))
            else:
                continue

            for lineno, line in enumerate(stripped.splitlines(), 1):
                for pattern, why in rules:
                    if re.search(pattern, line):
                        findings.append((path, lineno, why, line.strip()))
    return findings


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "app"
    min_sdk = sys.argv[2] if len(sys.argv) > 2 else "4"
    findings = scan(root)
    if not findings:
        print("      clean: no members newer than API %s" % min_sdk)
        return 0
    for path, lineno, why, text in findings:
        print("      %s:%d" % (path, lineno))
        print("        %s" % why)
        print("        %s" % text)
    print("      %d problem(s) that would break on Android %s" % (len(findings), min_sdk))
    return 1


if __name__ == "__main__":
    sys.exit(main())
