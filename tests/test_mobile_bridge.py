"""Offline phone-engine regressions: same domain flow, no local HTTP service."""
import base64
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
import uuid

from server.mobile_bridge import MobileEngine
from server.model_gateway import ProviderError, load_provider_config
from server.provider import SINGLE, MULTI, EXAMPLE


ROOT = Path(__file__).resolve().parents[1]
QUESTION = "资源分配图有环，是否一定死锁？"
SAMPLE = SINGLE + "\f" + MULTI + "\n" + EXAMPLE
FAKE_KEY = "test-only-phone-key-never-real"
PROFILE = {"protocol": "openai_chat", "base_url": "https://model.invalid/v1",
           "model": "offline-model-test", "api_key_env": "MOBILE_TEST_KEY", "label": "Phone test"}
SECRETS = json.dumps({"MOBILE_TEST_KEY": FAKE_KEY})


def model_response(url, headers, payload, timeout):
    prompt = json.loads(payload["messages"][-1]["content"])
    evidence = prompt["evidence"][0]
    output = {"status": "ready", "claims": [{"text": "必须结合资源实例数判断死锁。",
               "evidence_ids": [evidence["evidence_id"]]}], "missing_information": []}
    return {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(output)}}]}


class MobileBridgeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "phone.sqlite3"
        self.engine = MobileEngine(self.path)
        # Any accidentally introduced socket or loopback server fails this suite.
        self.socket_guard = patch("socket.socket", side_effect=AssertionError("No network in bridge tests"))
        self.socket_guard.start()

    def tearDown(self):
        self.socket_guard.stop()
        self.engine.close()
        self.tmp.cleanup()

    def request(self, path, payload=None, status=200):
        raw = self.engine.dispatch(path, None if payload is None else json.dumps(payload))
        value = json.loads(raw)
        self.assertEqual(status, value["status"], raw)
        return value["body"]

    def call(self, path, status=200, **payload):
        payload.setdefault("request_id", uuid.uuid4().hex)
        return self.request(path, payload, status)

    def ready(self):
        sid = self.call("/sources", title="手机合成资料", text=SAMPLE)["result"]["source_id"]
        qid = self.call("/questions", text=QUESTION)["result"]["question_id"]
        return sid, qid

    def configure(self, config=PROFILE, secrets=SECRETS, status=200):
        raw = self.engine.configure(None if config is None else json.dumps(config), secrets)
        reply = json.loads(raw)
        self.assertEqual(status, reply["status"], raw)
        self.assertNotIn(FAKE_KEY, raw)
        return reply["body"]

    def consent(self, **extra):
        payload = {"request_id": uuid.uuid4().hex, "allow_remote": True, "question_revision": 1,
                   "provider_fingerprint": self.request("/state")["provider_info"]["fingerprint"]}
        payload.update(extra)
        return payload

    def test_offline_full_learning_flow_survives_restart_and_deletion(self):
        health = self.request("/health")
        self.assertEqual("android-in-process", health["runtime"])
        self.assertEqual("android-app-private", health["authentication"])
        sid, qid = self.ready()
        self.assertTrue(self.request("/search?q=%E6%AD%BB%E9%94%81")["candidates"])
        generated = self.call(f"/questions/{qid}/explain")
        cid = generated["result"]["card_id"]
        for claim in generated["state"]["questions"][0]["answer"]["claims"]:
            evidence = claim["evidence"][0]
            page = generated["state"]["sources"][0]["pages"][evidence["page"] - 1]["text"]
            self.assertEqual(evidence["quote"], page[evidence["start"]:evidence["end"]])
        aid = self.call(f"/cards/{cid}/attempts", answer="保留我的手机作答")["result"]["attempt_id"]
        confirmed = self.call(f"/cards/{cid}/confirm", attempt_id=aid, self_rating="understood")
        self.assertTrue(confirmed["state"]["attempts"][0]["confirmed"])
        self.engine.close()
        self.engine = MobileEngine(self.path)
        self.assertEqual(aid, self.request("/state")["attempts"][0]["id"])
        revised = self.call(f"/sources/{sid}/revisions", base_revision=1, text=SAMPLE + "修订")
        self.assertEqual("needs_review", revised["state"]["cards"][0]["status"])
        deleted = self.call(f"/sources/{sid}/delete", base_revision=2)
        self.assertEqual([], deleted["state"]["sources"][0]["pages"])
        self.assertNotIn(SINGLE, json.dumps(deleted, ensure_ascii=False))
        self.assertEqual("保留我的手机作答", deleted["state"]["attempts"][0]["answer"])

    def test_copyable_pdf_import_is_local_and_keeps_page_anchors(self):
        encoded = base64.b64encode((ROOT / "fixtures/resource_graph_v1.pdf").read_bytes()).decode()
        imported = self.call("/sources", title="手机PDF", pdf_base64=encoded)
        self.assertEqual(2, len(imported["state"]["sources"][0]["pages"]))
        qid = self.call("/questions", text=QUESTION)["result"]["question_id"]
        answer = self.call(f"/questions/{qid}/explain")["state"]["questions"][0]["answer"]
        self.assertEqual("ready", answer["status"])
        self.assertEqual({1, 2}, {c["evidence"][0]["page"] for c in answer["claims"]})

    def test_model_calls_require_current_consent_and_replay_without_spending(self):
        self.configure()
        sid, qid = self.ready()
        with patch("server.model_gateway._post_json", side_effect=model_response) as send:
            rejected = self.call(f"/questions/{qid}/explain", status=409)
            self.assertEqual("model_consent_required", rejected["error"]["code"])
            send.assert_not_called()
            payload = self.consent()
            first = self.request(f"/questions/{qid}/explain", payload)
            replay = self.request(f"/questions/{qid}/explain", payload)
            self.assertTrue(replay["result"]["replayed"])
            self.assertEqual(first["result"]["answer_id"], replay["result"]["answer_id"])
            self.assertEqual(1, send.call_count)
            self.assertEqual("Bearer " + FAKE_KEY, send.call_args.args[1]["Authorization"])
        self.assertNotIn(FAKE_KEY, json.dumps(self.request("/state")))
        self.assertNotIn(FAKE_KEY.encode(), self.path.read_bytes())
        revised = self.call(f"/sources/{sid}/revisions", base_revision=1, text=SAMPLE + "修订")
        self.assertEqual("needs_review", revised["state"]["questions"][0]["answer"]["status"])

    def test_invalid_config_preserves_valid_provider_and_never_reads_process_keys(self):
        self.configure()
        provider = self.engine.repo.provider
        for raw in ("null", "{}", "{", '{"protocol":"openai_chat","protocol":"openai_chat"}',
                    json.dumps(dict(PROFILE, base_url="http://remote.invalid"))):
            with self.subTest(raw=raw):
                reply = json.loads(self.engine.configure(raw, SECRETS))
                self.assertEqual(400, reply["status"])
                self.assertIs(provider, self.engine.repo.provider)
                self.assertNotIn(FAKE_KEY, json.dumps(reply))
        with patch.dict(os.environ, {"MOBILE_TEST_KEY": FAKE_KEY}):
            reply = self.configure(secrets="{}", status=400)
            self.assertEqual("provider_key_missing", reply["error"]["code"])
            self.assertEqual(FAKE_KEY, os.environ["MOBILE_TEST_KEY"])
        self.assertIs(provider, self.engine.repo.provider)

    def test_invalid_saved_config_fails_before_opening_database(self):
        unopened = Path(self.tmp.name) / "unopened.sqlite3"
        for raw in ("null", "{}", "{", json.dumps(PROFILE)):
            with self.subTest(raw=raw), self.assertRaises(ProviderError):
                MobileEngine(unopened, raw, "{}")
            self.assertFalse(unopened.exists())

    def test_profile_switch_updates_current_state_and_invalidates_old_consent(self):
        original = self.configure()["provider_info"]
        _, qid = self.ready()
        repo = self.engine.repo
        second = self.configure(dict(PROFILE, model="second-model"))
        self.assertIs(repo, self.engine.repo)
        self.assertEqual(qid, second["state"]["questions"][0]["id"])
        self.assertNotEqual(original["fingerprint"], second["provider_info"]["fingerprint"])
        rejected = self.request(f"/questions/{qid}/explain",
                                self.consent(provider_fingerprint=original["fingerprint"]), status=409)
        self.assertEqual("model_consent_required", rejected["error"]["code"])
        self.assertEqual("demo", self.configure(None)["provider_info"]["mode"])

    def test_provider_swap_during_model_request_discards_returned_output(self):
        self.configure()
        _, qid = self.ready()
        entered, release = threading.Event(), threading.Event()
        result = {}

        def block(*args):
            entered.set()
            if not release.wait(5):
                raise RuntimeError("test timed out")
            return model_response(*args)

        payload = self.consent()
        with patch("server.model_gateway._post_json", side_effect=block):
            worker = threading.Thread(target=lambda: result.update(json.loads(
                self.engine.dispatch(f"/questions/{qid}/explain", json.dumps(payload)))))
            worker.start()
            try:
                self.assertTrue(entered.wait(3))
                self.configure(dict(PROFILE, model="replacement"))
                self.assertEqual(qid, self.request("/state")["questions"][0]["id"])
            finally:
                release.set()
                worker.join(3)
        self.assertFalse(worker.is_alive())
        self.assertEqual(409, result["status"])
        self.assertEqual("generation_context_changed", result["body"]["error"]["code"])
        self.assertIsNone(self.request("/state")["questions"][0]["answer"])

    def test_interrupted_request_stays_reserved_after_process_restart(self):
        self.configure()
        _, qid = self.ready()
        payload = self.consent()
        with patch("server.model_gateway._post_json", side_effect=SystemExit("simulated process death")):
            with self.assertRaises(SystemExit):
                self.engine.dispatch(f"/questions/{qid}/explain", json.dumps(payload))
        self.engine.close()
        self.engine = MobileEngine(self.path, json.dumps(PROFILE), SECRETS)
        with patch("server.model_gateway._post_json") as send:
            replay = self.request(f"/questions/{qid}/explain", payload, status=409)
            self.assertEqual("generation_in_progress", replay["error"]["code"])
            send.assert_not_called()

    def test_json_validation_and_generic_errors_keep_private_content_out(self):
        for raw, status in (("", 413), ("[]", 400), ("{", 400), ('{"value":NaN}', 400),
                            ('{"request_id":"a","request_id":"b"}', 400)):
            self.assertEqual(status, json.loads(self.engine.dispatch("/questions", raw))["status"])
        with patch.object(self.engine.repo, "state", side_effect=RuntimeError(FAKE_KEY)):
            reply = self.engine.dispatch("/state")
            self.assertEqual(500, json.loads(reply)["status"])
            self.assertNotIn(FAKE_KEY, reply)
        self.assertEqual("not_found", self.request("/missing", status=404)["error"]["code"])


class InMemoryProviderConfigTests(unittest.TestCase):
    def test_injected_key_and_header_snapshots_do_not_touch_environment(self):
        secrets = {"MOBILE_TEST_KEY": FAKE_KEY, "MOBILE_EXTRA": "test-only-header"}
        config = dict(PROFILE, extra_headers={"X-Custom-Token": {"env": "MOBILE_EXTRA"}})
        before = dict(os.environ)
        provider = load_provider_config(config, environ=secrets)
        secrets["MOBILE_TEST_KEY"] = "changed-after-load"
        config["model"] = "changed-after-load"
        _, headers, _ = provider._request("fixture")
        self.assertEqual("Bearer " + FAKE_KEY, headers["Authorization"])
        self.assertEqual("test-only-header", headers["x-custom-token"])
        self.assertEqual(PROFILE["model"], provider.public_info()["model"])
        self.assertEqual(before, dict(os.environ))
        self.assertNotIn(FAKE_KEY, json.dumps(provider.public_info()))

    def test_profile_wrapper_is_supported_without_file_access(self):
        wrapped = {"active_profile": "mobile", "profiles": {"mobile": PROFILE}}
        provider = load_provider_config(wrapped, environ={"MOBILE_TEST_KEY": FAKE_KEY})
        self.assertEqual(PROFILE["model"], provider.public_info()["model"])
        with self.assertRaises(ProviderError):
            load_provider_config(PROFILE, environ={})


if __name__ == "__main__":
    unittest.main(verbosity=2)
