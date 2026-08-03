#!/usr/bin/env python3
"""Mechanical security checks for the TSANet Connect SDK (Java/Maven).

The deterministic floor of the connector security review. Everything
requiring judgement (credential lifecycle, data handling, authorization
boundaries, audit trail) lives in the review taxonomy and is performed by a
reviewer, not here.

This SDK has no release pipeline yet, so the supply-chain checks that apply
to a published artifact report SKIP rather than FAIL. They become live when
a release workflow exists.

Exit codes (cron/CI friendly):
  0  all checks passed
  1  one or more FAIL results
  2  no FAILs, one or more WARN results
  3  the audit could not run

Usage:
  security-audit.py [--json] [--repo-root PATH]
"""
import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

RESULTS = []
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}

SOURCE_EXT = (".java", ".xml", ".properties", ".yaml", ".yml", ".json")
SKIP_DIRS = {".git", "target", "node_modules", ".idea"}


def record(status, check, detail, category):
    RESULTS.append({"status": status, "check": check,
                    "detail": detail, "category": category})


def walk_sources(root):
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for fn in files:
            if fn.endswith(SOURCE_EXT):
                path = os.path.join(base, fn)
                rel = os.path.relpath(path, root)
                if rel == os.path.join("scripts", "security-audit.py"):
                    continue
                try:
                    with open(path, encoding="utf-8", errors="replace") as f:
                        yield rel, f.read()
                except OSError:
                    continue


# ── Supply chain (mostly pending a release pipeline) ────────────────────

def check_release_pipeline(root):
    cat = "supply-chain"
    wf = os.path.join(root, ".github", "workflows")
    if not os.path.isdir(wf) or not os.listdir(wf):
        record("SKIP", "release pipeline hardening",
               "no CI workflows in this repo yet; the release-integrity checks "
               "(least-privilege token, SHA-pinned actions, approval gate, "
               "provenance attestation) apply once a release pipeline exists",
               cat)
        return
    unpinned = []
    for name in sorted(os.listdir(wf)):
        if not name.endswith((".yml", ".yaml")):
            continue
        with open(os.path.join(wf, name), encoding="utf-8") as f:
            body = f.read()
        for m in re.finditer(r"uses:\s*([\w.-]+/[\w.-]+)@(\S+)", body):
            if not re.fullmatch(r"[0-9a-f]{40}", m.group(2)):
                unpinned.append(f"{name}: {m.group(1)}@{m.group(2)}")
    if unpinned:
        record("WARN", "third-party actions SHA-pinned",
               "moving refs: " + "; ".join(unpinned), cat)
    else:
        record("PASS", "third-party actions SHA-pinned",
               "all actions pinned to a commit SHA", cat)


def check_dependency_pinning(root):
    """A dependency without a resolvable version is a supply-chain hole."""
    cat = "supply-chain"
    poms = []
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        if "pom.xml" in files:
            poms.append(os.path.relpath(os.path.join(base, "pom.xml"), root))
    if not poms:
        record("FAIL", "maven project readable", "no pom.xml found", cat)
        return
    snapshots = []
    for rel in poms:
        try:
            tree = ET.parse(os.path.join(root, rel))
        except ET.ParseError as e:
            record("FAIL", f"pom parses ({rel})", str(e), cat)
            continue
        for dep in tree.getroot().iter("{http://maven.apache.org/POM/4.0.0}dependency"):
            ver = dep.find("m:version", POM_NS)
            art = dep.find("m:artifactId", POM_NS)
            if ver is not None and art is not None and ver.text and "SNAPSHOT" in ver.text:
                snapshots.append(f"{rel}: {art.text}@{ver.text}")
    if snapshots:
        record("WARN", "no SNAPSHOT dependencies",
               "SNAPSHOT versions are mutable: " + "; ".join(snapshots), cat)
    else:
        record("PASS", "no SNAPSHOT dependencies",
               f"{len(poms)} pom(s), all dependency versions fixed", cat)


# ── Credentials ─────────────────────────────────────────────────────────

def names_itself(match):
    """True when a credential-shaped literal is just the key's own name.

    `MODE_PASSWORD = "password"` and `KEY_API_KEY = "api_key"` are mode and
    property names, not secrets: the value carries no entropy beyond the
    identifier that introduces it. Comparing the two with separators and case
    stripped keeps those quiet without weakening the check, because any real
    secret differs from its own key name.

    Only applies to the keyword pattern, which is the one with two groups.
    """
    if match.re.groups < 2:
        return False
    normalize = lambda s: re.sub(r"[^a-z0-9]", "", s.lower())
    return normalize(match.group(1)) == normalize(match.group(2))


def check_no_embedded_secrets(root):
    cat = "credentials"
    patterns = [
        # The value must be single-line: without excluding newlines a prompt
        # such as System.out.print("Password: ") matches across the following
        # lines and reports a literal that does not exist.
        (re.compile(r"(?i)(password|client[_-]?secret|api[_-]?key|apikey)\s*[=:]\s*[\"']([^\"'{$<\n][^\"'\n]{7,})[\"']"),
         "credential-shaped literal"),
        (re.compile(r"xox[baprs]-[A-Za-z0-9-]{10,}"), "Slack token"),
        (re.compile(r"ghp_[A-Za-z0-9]{20,}"), "GitHub PAT"),
        (re.compile(r"-----BEGIN (RSA |EC )?PRIVATE KEY-----"), "private key"),
    ]
    placeholders = re.compile(r"(?i)your|example|changeme|placeholder|xxx|\.\.\.|\$\{|<.+>|test|dummy|sample")
    hits = []
    for rel, body in walk_sources(root):
        for pat, label in patterns:
            for m in pat.finditer(body):
                if placeholders.search(m.group(0)):
                    continue
                if names_itself(m):
                    continue
                hits.append(f"{rel}: {label}")
    if hits:
        record("FAIL", "no embedded credentials in source",
               "; ".join(sorted(set(hits))), cat)
    else:
        record("PASS", "no embedded credentials in source",
               "no credential-shaped literals outside placeholders", cat)


def check_credentials_not_logged(root):
    cat = "credentials"
    # Only a credential-bearing *identifier* reaching a log call is a leak.
    # The same word inside a string literal is a label or prompt ("Password: "),
    # so string literals are blanked before matching.
    string_lit = re.compile(r'"(?:[^"\\\n]|\\.)*"')
    log_call = re.compile(
        r"(?i)(log(ger)?\.(trace|debug|info|warn|error)|System\.out\.print\w*)\s*\("
        r"[^;\n]{0,200}\b(password|secret|token|credential|apikey|api_key)\b")
    hits = []
    for rel, body in walk_sources(root):
        if not rel.endswith(".java"):
            continue
        masked = string_lit.sub(lambda m: '"' + " " * max(0, len(m.group(0)) - 2) + '"', body)
        for m in log_call.finditer(masked):
            line = masked[:m.start()].count("\n") + 1
            hits.append(f"{rel}:{line}")
    if hits:
        record("WARN", "credentials not written to logs",
               "log statements referencing credential-shaped names: "
               + ", ".join(sorted(set(hits))[:8]), cat)
    else:
        record("PASS", "credentials not written to logs",
               "no log statements referencing credential-shaped names", cat)


# ── Transport ───────────────────────────────────────────────────────────

def check_tls(root):
    cat = "transport"
    plain = re.compile(r"http://(?!localhost|127\.0\.0\.1|schemas?\.|www\.w3\.org|maven\.apache\.org|xmlns)")
    disabled = re.compile(
        r"(?i)(TrustAllCerts|ALLOW_ALL_HOSTNAME|setHostnameVerifier\s*\(\s*\(.*\)\s*->\s*true"
        r"|X509TrustManager|NoopHostnameVerifier|\.verify\s*\(.*return true)")
    plain_hits, disabled_hits = [], []
    for rel, body in walk_sources(root):
        for m in plain.finditer(body):
            line = body[:m.start()].count("\n") + 1
            plain_hits.append(f"{rel}:{line}")
        for m in disabled.finditer(body):
            line = body[:m.start()].count("\n") + 1
            disabled_hits.append(f"{rel}:{line}")
    if disabled_hits:
        record("FAIL", "TLS verification not disabled",
               "certificate/hostname verification appears bypassed at: "
               + ", ".join(sorted(set(disabled_hits))[:8]), cat)
    else:
        record("PASS", "TLS verification not disabled",
               "no trust-all or hostname-verifier bypass patterns", cat)
    if plain_hits:
        record("WARN", "no plaintext HTTP endpoints",
               "http:// URLs (non-localhost): " + ", ".join(sorted(set(plain_hits))[:8]), cat)
    else:
        record("PASS", "no plaintext HTTP endpoints",
               "no non-local http:// URLs in source", cat)


# ── Platform deadlines ──────────────────────────────────────────────────

def check_deprecated_endpoints(root):
    cat = "platform-deadlines"
    deprecated = {
        r"/v1/webhooks": ("v1 webhook registration/list", "2027-01-01", "/v2/webhooks"),
        r"/v1/collaboration-requests[\"'\s]*\+?\s*$": (
            "GET /v1/collaboration-requests (list)", "2027-01-01",
            "GET /v2/collaboration-requests"),
    }
    found = []
    for rel, body in walk_sources(root):
        for pat, (label, date, repl) in deprecated.items():
            if re.search(pat, body, re.M):
                found.append(f"{rel}: {label} (sunset {date}, use {repl})")
    if found:
        record("WARN", "no calls to sunsetting endpoints",
               "; ".join(sorted(set(found))), cat)
    else:
        record("PASS", "no calls to sunsetting endpoints",
               "no known-deprecated endpoint usage found", cat)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--repo-root", default=os.path.dirname(
        os.path.dirname(os.path.abspath(__file__))))
    args = ap.parse_args()
    root = args.repo_root

    if not os.path.isfile(os.path.join(root, "pom.xml")):
        print(f"error: {root} does not look like the SDK repo (no pom.xml)",
              file=sys.stderr)
        sys.exit(3)

    try:
        check_release_pipeline(root)
        check_dependency_pinning(root)
        check_no_embedded_secrets(root)
        check_credentials_not_logged(root)
        check_tls(root)
        check_deprecated_endpoints(root)
    except Exception as e:  # noqa: BLE001 - a crashed audit must not read as a pass
        print(f"error: audit aborted: {e}", file=sys.stderr)
        sys.exit(3)

    fails = [r for r in RESULTS if r["status"] == "FAIL"]
    warns = [r for r in RESULTS if r["status"] == "WARN"]

    if args.json:
        print(json.dumps({"results": RESULTS,
                          "summary": {"pass": sum(1 for r in RESULTS if r["status"] == "PASS"),
                                      "fail": len(fails), "warn": len(warns),
                                      "skip": sum(1 for r in RESULTS if r["status"] == "SKIP")}},
                         indent=2))
    else:
        for r in RESULTS:
            print(f"{r['status']:5s} [{r['category']}] {r['check']}")
            if r["status"] != "PASS":
                print(f"        {r['detail']}")
        print(f"\n{len(fails)} fail, {len(warns)} warn, "
              f"{sum(1 for r in RESULTS if r['status'] == 'PASS')} pass, "
              f"{sum(1 for r in RESULTS if r['status'] == 'SKIP')} skip")
        print("\nMechanical checks are the floor, not the review. The design "
              "review is performed by a reviewer against the connector "
              "security taxonomy.")

    sys.exit(1 if fails else (2 if warns else 0))


if __name__ == "__main__":
    main()
