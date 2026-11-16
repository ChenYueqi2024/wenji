"""Offline wire-format/security tests; all HTTP traffic stays on loopback."""
from copy import deepcopy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from server.model_gateway import (
    ANTHROPIC_VERSION, MAX_RESPONSE_BYTES, ProviderError, load_provider, retrieve_chunks,
)
from server.provider import ExtractiveDemoProvider


FAKE_KEY = "offline-test-key-never-real"
QUESTION = "When does a resource graph cycle imply deadlock?"
SOURCES = [{"id": "private-source-id", "title": "private-file-title.pdf", "revision": 3,
            "sha256": "trusted-digest", "pages": [{"page": 7, "text":
            "A resource graph cycle implies deadlock when every resource has only one instance."}]}]


def user_payload(request):
    body = request["body"]
    if "generationConfig" in body:
        return json.loads(body["contents"][0]["parts"][0]["text"])
    if "input" in body:
        return json.loads(body["input"])
    return json.loads(body["messages"][-1]["content"])


def proposal(request):
    evidence = user_payload(request)["evidence"]
    return {"status": "ready", "claims": [{"text": "A cycle implies deadlock in the single-instance case.",
            "evidence_ids": [evidence[0]["evidence_id"]]}], "missing_information": []}


def envelope(request, output=None):
    text = json.dumps(proposal(request) if output is None else output)
    if request["path"].endswith("/chat/completions"):
        return {"choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": text}}]}
    if request["path"].endswith("/responses"):
        return {"status": "completed", "output": [{"type": "message", "role": "assistant",
                "status": "completed", "content": [{"type": "output_text", "text": text}]}]}
    if request["path"].endswith("/messages"):
        return {"stop_reason": "end_turn", "content": [{"type": "text", "text": text}]}
    return {"candidates": [{"finishReason": "STOP", "content": {"parts": [{"text": text}]}}]}


class FakeHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        request = {"path": self.path, "headers": {k.lower(): v for k, v in self.headers.items()},
                   "body": json.loads(self.rfile.read(int(self.headers["Content-Length"])))}
        self.server.requests.append(request)
        status, headers, body, delay = self.server.respond(request)
        if delay:
            time.sleep(delay)
        data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            for name, value in headers.items():
                self.send_header(name, str(value))
            if "Content-Length" not in headers and "X-Test-Omit-Length" not in headers:
                self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def log_message(self, *args):
        pass


class ModelGatewayTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), FakeHandler)
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(timeout=2)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.config_path = Path(self.tmp.name) / "provider.json"
        self.httpd.requests = []
        self.httpd.respond = lambda request: (200, {}, envelope(request), 0)
        self.env = patch.dict(os.environ, {"WENJI_OFFLINE_TEST_KEY": FAKE_KEY})
        self.env.start()
        self.addCleanup(self.env.stop)

    def config(self, **overrides):
        result = {"protocol": "openai_chat", "model": "offline-test-model",
                  "base_url": f"http://127.0.0.1:{self.httpd.server_port}/v1",
                  "api_key_env": "WENJI_OFFLINE_TEST_KEY", "label": "Offline test", "timeout_seconds": 2}
        result.update(overrides)
        return result

    def load(self, config=None):
        self.config_path.write_text(json.dumps(self.config() if config is None else config), encoding="utf-8")
        return load_provider(self.config_path)

    def assert_provider_error(self, code, action):
        with self.assertRaises(ProviderError) as caught:
            action()
        error = caught.exception
        self.assertEqual(code, error.code)
        for sensitive in [FAKE_KEY, "SENSITIVE_BODY", "private-file-title", "private-source-id"]:
            self.assertNotIn(sensitive, str(error))
            self.assertNotIn(sensitive, repr(error))
            self.assertNotIn(sensitive, json.dumps(error.__dict__))
        return error

    def test_default_is_demo_and_supplied_config_never_silently_falls_back(self):
        self.assertIsInstance(load_provider(), ExtractiveDemoProvider)
        self.assertFalse(load_provider().public_info()["requires_consent"])
        self.assert_provider_error("provider_config_invalid", lambda: load_provider(self.config_path))
        self.config_path.write_text('{"protocol":"openai_chat","protocol":"openai_chat"}', encoding="utf-8")
        self.assert_provider_error("provider_config_invalid", lambda: load_provider(self.config_path))
        self.config_path.write_bytes(b"x" * 65537)
        self.assert_provider_error("provider_config_invalid", lambda: load_provider(self.config_path))

    def test_four_wire_formats_and_trusted_citation_mapping(self):
        suffixes = {"openai_chat": "/v1/chat/completions", "openai_responses": "/v1/responses",
                    "anthropic_messages": "/v1/messages",
                    "gemini_generate_content": "/v1/models/offline-test-model:generateContent"}
        for protocol, suffix in suffixes.items():
            with self.subTest(protocol=protocol):
                model = self.load(self.config(protocol=protocol,
                    generation={"max_output_tokens": 777, **({} if protocol == "anthropic_messages" else {"temperature": 0.3})},
                    extra_headers={"X-Client-Name": "wenji-test"}))
                result = model.propose(QUESTION, SOURCES)
                request = self.httpd.requests[-1]
                self.assertEqual(suffix, request["path"])
                self.assertEqual("wenji-test", request["headers"]["x-client-name"])
                self.assertNotIn("?", request["path"])
                if protocol == "gemini_generate_content":
                    self.assertEqual(FAKE_KEY, request["headers"]["x-goog-api-key"])
                    self.assertNotIn("authorization", request["headers"])
                    self.assertEqual(777, request["body"]["generationConfig"]["maxOutputTokens"])
                    self.assertEqual(0.3, request["body"]["generationConfig"]["temperature"])
                else:
                    self.assertEqual("Bearer " + FAKE_KEY, request["headers"]["authorization"])
                    token_field = {"openai_chat": "max_tokens", "openai_responses": "max_output_tokens",
                                   "anthropic_messages": "max_tokens"}[protocol]
                    self.assertEqual(777, request["body"][token_field])
                    if protocol != "anthropic_messages":
                        self.assertEqual(0.3, request["body"]["temperature"])
                if protocol == "anthropic_messages":
                    self.assertEqual(ANTHROPIC_VERSION, request["headers"]["anthropic-version"])
                if protocol == "openai_responses":
                    self.assertFalse(request["body"]["store"])
                elif protocol == "openai_chat":
                    self.assertNotIn("store", request["body"])
                self.assertNotIn("tools", request["body"])
                serialized = json.dumps(request["body"])
                for private in [FAKE_KEY, "private-source-id", "private-file-title.pdf", "trusted-digest"]:
                    self.assertNotIn(private, serialized)
                sent = user_payload(request)
                self.assertEqual({"question", "evidence"}, set(sent))
                self.assertEqual({"evidence_id", "text"}, set(sent["evidence"][0]))
                self.assertEqual("ready", result.status)
                self.assertEqual("A cycle implies deadlock in the single-instance case.", result.claims[0].text)
                evidence = result.claims[0].evidence[0]
                self.assertEqual(("private-source-id", 3, 7, "trusted-digest"),
                                 (evidence.source_id, evidence.revision, evidence.page, evidence.sha256))
                self.assertEqual(evidence.quote, SOURCES[0]["pages"][0]["text"][evidence.start:evidence.end])
                self.assertEqual(result.evidence, result.claims[0].evidence)
                self.assertNotIn(FAKE_KEY, json.dumps(model.public_info()))

    def test_retrieval_is_deterministic_bounded_and_excludes_unmatched_corpus(self):
        sources = deepcopy(SOURCES)
        sources[0]["pages"] = [{"page": 7, "text": "resource cycle deadlock " * 1400},
                               {"page": 8, "text": "DO_NOT_SEND unrelated private chapter"}]
        one, two = retrieve_chunks(QUESTION, sources), retrieve_chunks(QUESTION, list(reversed(sources)))
        self.assertEqual(one, two)
        self.assertEqual(8, len(one))
        self.assertLessEqual(sum(len(c.quote) for c in one.values()), 12000)
        self.load().propose(QUESTION, sources)
        sent = user_payload(self.httpd.requests[-1])
        self.assertEqual(8, len(sent["evidence"]))
        self.assertNotIn("DO_NOT_SEND", json.dumps(sent))
        for chunk in one.values():
            self.assertEqual(chunk.quote, sources[0]["pages"][0]["text"][chunk.start:chunk.end])
        chinese = deepcopy(SOURCES)
        chinese[0]["pages"][0]["text"] = "资源分配图的有向环与死锁的关系。"
        self.assertTrue(retrieve_chunks("资源分配图有环是否死锁？", chinese))

    def test_no_relevant_material_never_calls_network(self):
        result = self.load().propose("Photosynthesis chloroplasts", SOURCES)
        self.assertEqual("needs_material", result.status)
        self.assertEqual([], result.claims)
        self.assertTrue(result.missing)
        self.assertEqual([], self.httpd.requests)

    def test_malformed_schema_unknown_citations_and_coordinates_fail_closed(self):
        invalids = [
            {"status": "ready", "claims": [], "missing_information": []},
            {"status": "ready", "claims": [{"text": "Claim", "evidence_ids": ["ev_UNKNOWN"]}], "missing_information": []},
            {"status": "needs_material", "claims": [], "missing_information": []},
            {"status": "unverified", "claims": [], "missing_information": ["Gap"]},
            {"status": "ready", "claims": [{"text": "Claim", "evidence_ids": []}], "missing_information": []},
            {"status": "ready", "claims": [], "missing_information": [], "automatic_grade": 100},
        ]
        for output in invalids:
            with self.subTest(output=output):
                self.httpd.respond = lambda request: (200, {}, envelope(request, output), 0)
                self.assert_provider_error("provider_invalid_output", lambda: self.load().propose(QUESTION, SOURCES))
        for mutation in [lambda p: p["claims"][0].update(page=1, start=0, end=8),
                         lambda p: p["claims"][0].update(text="\u200b"),
                         lambda p: p["claims"][0].update(text="x" * 3001),
                         lambda p: p["claims"][0]["evidence_ids"].append(p["claims"][0]["evidence_ids"][0]),
                         lambda p: p.update(missing_information=["Not ready"])]:
            def respond(request):
                output = proposal(request)
                mutation(output)
                return 200, {}, envelope(request, output), 0
            self.httpd.respond = respond
            self.assert_provider_error("provider_invalid_output", lambda: self.load().propose(QUESTION, SOURCES))

    def test_needs_material_and_conflict_preserve_safe_statuses(self):
        self.httpd.respond = lambda req: (200, {}, envelope(req, {
            "status": "needs_material", "claims": [], "missing_information": ["Need instance counts."]}), 0)
        result = self.load().propose(QUESTION, SOURCES)
        self.assertEqual("needs_material", result.status)
        self.assertEqual([], result.evidence)
        sources = deepcopy(SOURCES)
        sources[0]["pages"].append({"page": 8, "text": "A resource cycle always implies deadlock."})
        def conflict(request):
            ids = [e["evidence_id"] for e in user_payload(request)["evidence"]]
            return 200, {}, envelope(request, {"status": "conflict", "claims": [
                {"text": "These material statements disagree.", "evidence_ids": ids},
                {"text": "The second statement omits conditions.", "evidence_ids": [ids[1]]}],
                "missing_information": ["Check assumptions."]}), 0
        self.httpd.respond = conflict
        result = self.load().propose(QUESTION, sources)
        self.assertTrue(result.conflict)
        self.assertEqual(2, len(result.claims[0].evidence))
        self.assertEqual(2, len(result.evidence))

    def test_refusal_truncation_and_tools_are_rejected_for_each_protocol(self):
        for protocol in ["openai_chat", "openai_responses", "anthropic_messages", "gemini_generate_content"]:
            for kind in ["truncated", "tools"]:
                with self.subTest(protocol=protocol, kind=kind):
                    def respond(request):
                        value = envelope(request)
                        if protocol == "openai_chat":
                            if kind == "truncated":
                                value["choices"][0]["finish_reason"] = "length"
                            else:
                                value["choices"][0]["message"]["tool_calls"] = [{"function": {"name": "evil"}}]
                        elif protocol == "openai_responses":
                            if kind == "truncated":
                                value["status"] = "incomplete"
                            else:
                                value["output"].append({"type": "function_call"})
                        elif protocol == "anthropic_messages":
                            if kind == "truncated":
                                value["stop_reason"] = "max_tokens"
                            else:
                                value["content"].append({"type": "tool_use"})
                        elif kind == "truncated":
                            value["candidates"][0]["finishReason"] = "MAX_TOKENS"
                        else:
                            value["candidates"][0]["content"]["parts"].append({"functionCall": {"name": "evil"}})
                        return 200, {}, value, 0
                    self.httpd.respond = respond
                    self.assert_provider_error("provider_invalid_output", lambda:
                        self.load(self.config(protocol=protocol)).propose(QUESTION, SOURCES))

    def test_errors_are_sanitized_no_redirects_or_automatic_retries(self):
        for status, code in [(429, "provider_rate_limited"), (401, "provider_auth_failed"),
                             (500, "provider_http_error"), (307, "provider_redirect_blocked")]:
            with self.subTest(status=status):
                self.httpd.requests = []
                self.httpd.respond = lambda request: (status, {"Location": "https://must-not-contact.invalid/"},
                                                     {"error": FAKE_KEY + " SENSITIVE_BODY"}, 0)
                self.assert_provider_error(code, lambda: self.load().propose(QUESTION, SOURCES))
                self.assertEqual(1, len(self.httpd.requests))

    def test_timeout_is_bounded_and_not_retried(self):
        self.httpd.respond = lambda req: (200, {}, envelope(req), 0.2)
        started = time.monotonic()
        self.assert_provider_error("provider_timeout", lambda:
            self.load(self.config(timeout_seconds=0.03)).propose(QUESTION, SOURCES))
        self.assertLess(time.monotonic() - started, 1)
        self.assertEqual(1, len(self.httpd.requests))

    def test_response_bounds_json_and_encoding_fail_closed(self):
        for headers, body in [({"Content-Length": MAX_RESPONSE_BYTES + 1}, b"{}"),
                              ({"X-Test-Omit-Length": "1"}, b"x" * (MAX_RESPONSE_BYTES + 1)),
                              ({"Content-Encoding": "gzip"}, b"SENSITIVE_BODY"),
                              ({}, b'{"choices": [], "choices": []}'),
                              ({}, b'{"choices": NaN}'), ({}, b"not-json-" + FAKE_KEY.encode())]:
            with self.subTest(headers=headers):
                self.httpd.respond = lambda req: (200, headers, body, 0)
                self.assert_provider_error("provider_invalid_output", lambda: self.load().propose(QUESTION, SOURCES))

    def test_config_rejects_unsafe_endpoints_headers_and_bad_types(self):
        cases = [
            {"base_url": "http://api.example.test/v1"}, {"base_url": "https://user:pass@example.test/v1"},
            {"base_url": "https://example.test/v1?key=" + FAKE_KEY}, {"base_url": "https://example.test/#x"},
            {"base_url": "https://example.test/v1?"}, {"base_url": "file:///v1"},
            {"base_url": "https://example.test:99999/v1"}, {"base_url": "https://example.test/\r\n"},
            {"protocol": "unknown"}, {"protocol": []}, {"model": ""}, {"model": "model\r\nHeader: bad"},
            {"timeout_seconds": 61}, {"timeout_seconds": True}, {"timeout_seconds": float("nan")},
            {"timeout_seconds": 10 ** 400},
            {"generation": {"max_output_tokens": True}}, {"generation": {"temperature": None}},
            {"generation": {"temperature": 2}}, {"generation": {"tools": []}}, {"api_key": FAKE_KEY},
            {"extra_headers": {"Authorization": FAKE_KEY}}, {"extra_headers": {"x-api-key": FAKE_KEY}},
            {"extra_headers": {"Host": "evil.invalid"}}, {"extra_headers": {"Content-Length": "999"}},
            {"extra_headers": {"X-Test": "abc\r\nAuthorization: test"}},
            {"extra_headers": {"X-Test": "a", "x-test": "b"}},
            {"extra_headers": {"X-Test": {"env": "BAD NAME"}}},
            {"extra_headers": {"X-Tenant-Token": "literal-secret"}},
            {"compatibility": {"json_mode": 1}}, {"compatibility": {"send_store": "yes"}},
            {"compatibility": {"chat_token_parameter": "other"}},
            {"auth_header": "random-header"},
            {"protocol": "anthropic_messages", "generation": {"temperature": 0.3}},
            {"protocol": "gemini_generate_content", "compatibility": {"json_mode": False}},
            {"auth_mode": "none", "base_url": "https://example.test/v1", "api_key_env": None},
        ]
        for overrides in cases:
            with self.subTest(overrides=overrides):
                self.assert_provider_error("provider_config_invalid", lambda: self.load(self.config(**overrides)))

    def test_explicit_loopback_no_auth_and_missing_env(self):
        model = self.load(self.config(auth_mode="none", api_key_env=None))
        model.propose(QUESTION, SOURCES)
        self.assertNotIn("authorization", self.httpd.requests[-1]["headers"])
        self.assertTrue(model.public_info()["requires_consent"])
        with patch.dict(os.environ, {"WENJI_OFFLINE_TEST_KEY": ""}):
            self.assert_provider_error("provider_key_missing", lambda: self.load())

    def test_profiles_stable_secret_sensitive_fingerprint_and_snapshot(self):
        selected = self.config(extra_headers={"X-Tenant-Token": {"env": "WENJI_OFFLINE_HEADER_KEY"}})
        with patch.dict(os.environ, {"WENJI_OFFLINE_HEADER_KEY": "offline-header-value"}):
            first = self.load({"active_profile": "test", "profiles": {"test": selected}})
            same = self.load(selected)
            self.assertEqual(first.public_info()["fingerprint"], same.public_info()["fingerprint"])
            baseline = first.public_info()["fingerprint"]
            with patch.dict(os.environ, {"WENJI_OFFLINE_TEST_KEY": "changed-offline-key"}):
                self.assertNotEqual(baseline, self.load(selected).public_info()["fingerprint"])
                first.propose(QUESTION, SOURCES)
                self.assertEqual("Bearer " + FAKE_KEY, self.httpd.requests[-1]["headers"]["authorization"])
            with patch.dict(os.environ, {"WENJI_OFFLINE_HEADER_KEY": "changed-offline-header"}):
                self.assertNotEqual(baseline, self.load(selected).public_info()["fingerprint"])
            changed = deepcopy(selected)
            changed["model"] = "another-model"
            different = self.load(changed)
            self.assertNotEqual(baseline, different.public_info()["fingerprint"])
            self.assertNotEqual(first.name, different.name)
            self.assertIn("openai_chat:offline-test-model", first.name)
            public = first.public_info()
            public["endpoint"] = "https://untrusted.invalid/"
            self.assertEqual(selected["base_url"], first.public_info()["endpoint"])
            for sensitive in [FAKE_KEY, "offline-header-value", "WENJI_OFFLINE_HEADER_KEY"]:
                self.assertNotIn(sensitive, json.dumps(first.public_info()))
            self.assertRegex(baseline, r"^[0-9a-f]{64}$")
        self.assert_provider_error("provider_config_invalid", lambda:
            self.load({"active_profile": "missing", "profiles": {"test": selected}}))

    def test_openai_compatibility_options_and_explicit_auth_header(self):
        provider = self.load(self.config(compatibility={"chat_token_parameter": "max_completion_tokens",
                "json_mode": False, "send_store": True}, auth_header="api-key"))
        provider.propose(QUESTION, SOURCES)
        request = self.httpd.requests[-1]
        self.assertEqual(2048, request["body"]["max_completion_tokens"])
        self.assertNotIn("max_tokens", request["body"])
        self.assertNotIn("response_format", request["body"])
        self.assertFalse(request["body"]["store"])
        self.assertEqual(FAKE_KEY, request["headers"]["api-key"])
        self.assertNotIn("authorization", request["headers"])
        other = self.load(self.config(compatibility={"chat_token_parameter": "max_tokens"}))
        self.assertNotEqual(provider.public_info()["fingerprint"], other.public_info()["fingerprint"])


if __name__ == "__main__":
    unittest.main()
