#!/usr/bin/env python3
"""Probes for scripts/security-audit.py's embedded-credentials check (#63).

Each probe builds a throwaway tree (a pom.xml so the script accepts the root,
plus one fixture file), runs the check, and asserts the verdict. The point is
the pair of directions the issue pins: the shapes that let a committed secret
ship must FAIL, and the shapes that are references or placeholders must stay
quiet, because a detector that fires on a clean tree gets ignored.

Run: python3 -m unittest scripts/test_security_audit.py
"""
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "security-audit.py")


def load_audit():
    spec = importlib.util.spec_from_file_location("security_audit", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class EmbeddedCredentialsProbe(unittest.TestCase):

    def setUp(self):
        self.audit = load_audit()
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        with open(os.path.join(self.root, "pom.xml"), "w") as f:
            f.write("<project/>")

    def tearDown(self):
        self.tmp.cleanup()

    def verdict(self, filename, content):
        """The credentials-check status for a tree holding exactly this fixture."""
        with open(os.path.join(self.root, filename), "w") as f:
            f.write(content)
        self.audit.RESULTS.clear()
        self.audit.check_no_embedded_secrets(self.root)
        [result] = [r for r in self.audit.RESULTS if r["check"] == "no embedded credentials in source"]
        os.remove(os.path.join(self.root, filename))
        return result["status"], result["detail"]

    def assertFails(self, filename, content):
        status, detail = self.verdict(filename, content)
        self.assertEqual("FAIL", status, f"{filename!r} {content!r} must FAIL; detail: {detail}")

    def assertPasses(self, filename, content):
        status, detail = self.verdict(filename, content)
        self.assertEqual("PASS", status, f"{filename!r} {content!r} must PASS; detail: {detail}")

    # ---- the misses that let #61 ship (issue acceptance, first three items) ----

    def test_unquoted_short_password_in_yaml_fails(self):
        self.assertFails("application.yml", "crash:\n  auth-password: crash\n")

    def test_unquoted_high_entropy_client_secret_in_yaml_fails(self):
        self.assertFails("application.yml", "tsanet:\n  client-secret: Xq7bTn2LpR9dWv4A\n")

    def test_the_61_pair_in_properties_fails(self):
        self.assertFails("tsanet-demo-crash-ssh.properties",
                         "crash.enabled=true\ncrash.auth-password=crash\n")

    # ---- shapes the old matcher missed for other reasons ----

    def test_quoted_short_secret_fails_now_that_the_length_floor_is_gone(self):
        self.assertFails("application.yml", "auth-password: \"crash\"\n")

    def test_shell_export_fails(self):
        self.assertFails("simple-test.sh", "#!/bin/sh\nexport TSANET_PASSWORD=hunter2\n")

    def test_dotted_properties_key_fails(self):
        self.assertFails("app.properties", "spring.datasource.password=s3cr3t-value\n")

    def test_unquoted_value_before_a_comment_fails(self):
        self.assertFails("application.yml", "api-key: k9v2m4x8q1  # rotate me\n")

    def test_a_placeholder_word_in_the_comment_does_not_silence_the_value(self):
        self.assertFails("application.yml", "api-key: k9v2m4x8q1  # test env only\n")

    def test_a_placeholder_word_in_the_key_prefix_does_not_silence_the_value(self):
        self.assertFails("app.properties", "test.password=Xq7bTn2LpR9dWv4A\n")

    def test_a_placeholder_word_inside_a_secret_does_not_silence_it(self):
        # The guard is what separates a secret from a placeholder now that the
        # length floor is gone, so it must match placeholder WORDS, not substrings.
        self.assertFails("application.yml", "client-secret: Xq7test2LpR9dWv4A\n")
        self.assertFails("application.yml", "client-secret: myXXXsecretvalue1\n")
        self.assertFails("application.yml", "password: sampleXq7bTn2LpR9\n")

    def test_placeholder_words_at_word_edges_still_stay_quiet(self):
        self.assertPasses("application.yml", "password: your-secret-here\n")
        self.assertPasses("application.yml", "client-secret: \"<client secret>\"\n")
        self.assertPasses("application.yml", "api-key: changeme\n")

    def test_passwd_and_pwd_spellings_fail(self):
        self.assertFails("db.properties", "db.passwd=Xq7bTn2LpR9dWv4A\n")
        self.assertFails("db.properties", "db.pwd=Xq7bTn2LpR9dWv4A\n")

    def test_yaml_list_item_fails(self):
        self.assertFails("accounts.yml", "accounts:\n  - username: a\n  - password: hunter2\n")

    def test_shell_local_fails(self):
        self.assertFails("run.sh", "f() {\n  local password=hunter2\n}\n")

    # ---- every shape the old matcher already caught still fails ----

    def test_quoted_long_literal_in_java_still_fails(self):
        self.assertFails("Config.java", 'String password = "hunter2hunter2";\n')

    def test_slack_token_still_fails(self):
        # Adjacent literals: the committed test source never carries the contiguous
        # token shape, so push protection has nothing to match; the fixture written
        # at run time does, so the probe exercises the real pattern.
        self.assertFails("notes.sh", "TOKEN=xox" "b-1234567890-abcdefghijklmnop\n")

    def test_github_pat_still_fails(self):
        self.assertFails("Settings.java", 'String pat = "ghp_abcdefghijklmnopqrstuvwxyz0123";\n')

    def test_private_key_still_fails(self):
        self.assertFails("key.properties", "-----BEGIN RSA PRIVATE KEY-----\nMIIE\n")

    # ---- what must stay quiet, or the check gets ignored ----

    def test_env_var_reference_is_a_variable_name_not_a_secret(self):
        self.assertPasses("publish.yml", "with:\n  server-password: MAVEN_TOKEN\n")

    def test_spring_placeholder_stays_quiet(self):
        self.assertPasses("application.yml", "password: ${TSANET_DEMO_AUTH_PASSWORD:}\n")

    def test_shell_reference_stays_quiet(self):
        self.assertPasses("run.sh", "export PASSWORD=$TSANET_PASSWORD\n")

    def test_yaml_tag_and_template_stay_quiet(self):
        self.assertPasses("vault.yml", "password: !vault |\n  $ANSIBLE\n")
        self.assertPasses("chart.yaml", "password: {{ .Values.password }}\n")

    def test_key_naming_itself_stays_quiet(self):
        self.assertPasses("CredentialsStore.java", 'static final String PASSWORD = "password";\n')

    def test_test_placeholder_stays_quiet(self):
        self.assertPasses("application.yml", "password: \"test-password\"\n")

    def test_all_caps_without_underscore_is_still_a_secret(self):
        self.assertFails("application.yml", "auth-password: HUNTER2\n")

    def test_unquoted_form_does_not_apply_to_java(self):
        # `password = value` without quotes is not a literal in Java; the
        # unquoted form is a config-format rule and must not fire on code.
        self.assertPasses("Login.java", "String password = credentials.password;\n")


class RecordSecretsProbe(unittest.TestCase):

    def verdict(self, content, source="main"):
        audit = load_audit()
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "module", "src", source, "java")
            os.makedirs(path)
            with open(os.path.join(path, "Records.java"), "w") as f:
                f.write(content)
            audit.check_record_secrets_redacted(root)
        [result] = audit.RESULTS
        return result

    def test_secret_component_names_fail(self):
        for name in ("password", "clientSecret", "bearerToken", "credentials",
                     "apiKey", "api_key", "caseToken", "requestToken", "noteToken", "token"):
            with self.subTest(name=name):
                result = self.verdict(f"record Login(String {name}) {{}}")
                self.assertEqual("FAIL", result["status"])
                self.assertIn("Records.java:1 (Login)", result["detail"])

    def test_multiline_nested_record_fails(self):
        result = self.verdict("""
class Config {
    record Login(
        String username,
        String password
    ) {}
}
""")
        self.assertEqual("FAIL", result["status"])
        self.assertIn("Records.java:3 (Login)", result["detail"])

    def test_override_passes(self):
        result = self.verdict("""
record Login(String password) {
    @Override
    public String toString() { return "redacted"; }
}
""")
        self.assertEqual("PASS", result["status"])

    def test_nested_override_does_not_cover_outer_record(self):
        result = self.verdict("""
record Outer(String password) {
    record Inner(String token) {
        public String toString() { return "redacted"; }
    }
}
""")
        self.assertEqual("FAIL", result["status"])
        self.assertIn("(Outer)", result["detail"])
        self.assertNotIn("(Inner)", result["detail"])

    def test_literals_and_comments_cannot_supply_override(self):
        result = self.verdict('''
record Login(String password) {
    // public String toString() {}
    static String example = """
        public String toString() { return "}"; }
        """;
}
''')
        self.assertEqual("FAIL", result["status"])

    def test_non_secret_record_and_test_sources_pass(self):
        self.assertEqual("PASS", self.verdict("record User(String name) {}")["status"])
        self.assertEqual("PASS", self.verdict(
            "record Status(boolean credentialsLocked, Boolean secretConfigured) {}")["status"])
        self.assertEqual("PASS", self.verdict(
            "record Login(String password) {}", source="test")["status"])


class ExitCodeContract(unittest.TestCase):
    """The workflow keys on the exit code, so one probe runs the script for real."""

    def test_a_committed_config_secret_exits_one(self):
        with tempfile.TemporaryDirectory() as root:
            with open(os.path.join(root, "pom.xml"), "w") as f:
                f.write("<project/>")
            with open(os.path.join(root, "application.yml"), "w") as f:
                f.write("tsanet:\n  client-secret: Xq7bTn2LpR9dWv4A\n")
            run = subprocess.run([sys.executable, SCRIPT, "--repo-root", root],
                                 capture_output=True, text=True)
            self.assertEqual(1, run.returncode, run.stdout + run.stderr)
            self.assertIn("FAIL  [credentials] no embedded credentials in source", run.stdout)


if __name__ == "__main__":
    unittest.main()
