"""Explicit uncited references: offline transport fixtures, never live models."""
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
import unittest
from unittest.mock import patch
import uuid

from server.core import Repository, SCHEMA
from server.mobile_bridge import MobileEngine
from server.model_gateway import GENERAL_REFERENCE_PROMPT, ProviderError, load_provider_config
from server.provider import SINGLE, MULTI
from tests.test_model_gateway import envelope, user_payload

QUESTION = "什么是强化学习？"
REFERENCE = "强化学习通过智能体与环境交互，根据奖励反馈学习决策策略。此处仅作通用知识参考。"
PROFILE = {"protocol": "openai_chat", "base_url": "https://model.invalid/v1",
           "model": "offline-test-model", "api_key_env": "REFERENCE_TEST_KEY"}
SECRETS = {"REFERENCE_TEST_KEY": "fake-reference-test-secret"}


def response(url, headers, payload, timeout):
    return envelope({"path": url, "body": payload}, {"text": REFERENCE})


class GeneralReferenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "mobile.sqlite3"
        self.engine = MobileEngine(self.path)
        self.guard = patch("socket.socket", side_effect=AssertionError("No network in reference tests"))
        self.guard.start()

    def tearDown(self):
        self.guard.stop()
        self.engine.close()
        self.tmp.cleanup()

    def request(self, path, payload=None, status=200):
        raw = self.engine.dispatch(path, None if payload is None else json.dumps(payload))
        reply = json.loads(raw)
        self.assertEqual(status, reply["status"], raw)
        return reply["body"]

    def call(self, path, status=200, **payload):
        payload.setdefault("request_id", uuid.uuid4().hex)
        return self.request(path, payload, status)

    def question(self, text=QUESTION, **payload):
        return self.call("/questions", text=text, **payload)["result"]["question_id"]

    def configure(self, profile=PROFILE):
        value = json.loads(self.engine.configure(json.dumps(profile), json.dumps(SECRETS)))
        self.assertEqual(200, value["status"])

    def consent(self, **overrides):
        value = {"request_id": uuid.uuid4().hex, "purpose": "general_reference", "allow_remote": True,
                 "question_revision": 1,
                 "provider_fingerprint": self.request("/state")["provider_info"]["fingerprint"]}
        value.update(overrides)
        return value

    def test_screenshot_question_is_unsupported_by_demo_even_with_matching_material(self):
        qid = self.question()
        self.call("/sources", title="合成强化学习资料", text=REFERENCE)
        with patch("server.model_gateway._post_json") as send:
            state = self.call(f"/questions/{qid}/explain")["state"]
            self.assertEqual("needs_material", state["questions"][0]["answer"]["status"])
            self.assertIs(state["questions"][0]["answer"]["remote_called"], False)
            self.assertEqual("demo", state["provider_info"]["mode"])
            self.assertIsNone(state["questions"][0]["reference"])
            error = self.request(f"/questions/{qid}/reference", self.consent(), 400)
            self.assertEqual("general_reference_unavailable", error["error"]["code"])
            send.assert_not_called()

    def test_material_mode_never_falls_back_to_general_reference(self):
        self.configure()
        qid = self.question()
        with patch("server.model_gateway._post_json", side_effect=response) as send:
            state = self.request(f"/questions/{qid}/explain", self.consent())["state"]
            self.assertEqual("needs_material", state["questions"][0]["answer"]["status"])
            self.assertIs(state["questions"][0]["answer"]["remote_called"], False)
            self.assertIsNone(state["questions"][0]["reference"])
            send.assert_not_called()
            reference_state = self.request(f"/questions/{qid}/reference", self.consent())["state"]
            self.assertEqual(REFERENCE, reference_state["questions"][0]["reference"]["text"])
            self.assertEqual(1, send.call_count)

    def test_new_topic_with_matching_material_uses_citations_and_records_actual_call(self):
        self.configure()
        qid = self.question()
        self.call("/sources", title="合成强化学习定义", text=REFERENCE)
        def cited(url, headers, payload, timeout):
            evidence = user_payload({"body": payload})["evidence"]
            return envelope({"path": url, "body": payload}, {
                "status": "ready", "claims": [{"text": "强化学习根据奖励反馈学习决策策略。",
                                                "evidence_ids": [evidence[0]["evidence_id"]]}],
                "missing_information": []})
        with patch("server.model_gateway._post_json", side_effect=cited) as send:
            state = self.request(f"/questions/{qid}/explain", self.consent())["state"]
        answer = state["questions"][0]["answer"]
        self.assertEqual("ready", answer["status"])
        self.assertIs(answer["remote_called"], True)
        self.assertEqual(REFERENCE, answer["claims"][0]["evidence"][0]["quote"])
        self.assertEqual(1, len(state["cards"]))
        self.assertEqual(1, send.call_count)
        # Older persisted model answers have no trace proving a network call.
        with self.engine.repo.db:
            self.engine.repo.db.execute("DELETE FROM answer_provenance WHERE answer_id=?", (answer["id"],))
        self.assertIsNone(self.request("/state")["questions"][0]["answer"]["remote_called"])

    def test_reference_requires_separate_exact_current_consent(self):
        self.configure()
        qid = self.question()
        cases = [("purpose", None, "general_reference_consent_required"),
                 ("purpose", "evidence", "general_reference_consent_required"),
                 ("allow_remote", None, "model_consent_required"),
                 ("allow_remote", "true", "model_consent_required"),
                 ("provider_fingerprint", "old-fingerprint", "model_consent_required"),
                 ("question_revision", None, "generation_question_changed"),
                 ("question_revision", True, "generation_question_changed"),
                 ("question_revision", 0, "generation_question_changed")]
        with patch("server.model_gateway._post_json") as send:
            for key, value, code in cases:
                with self.subTest(key=key, value=value):
                    payload = self.consent(**{key: value})
                    if value is None:
                        payload.pop(key)
                    result = self.request(f"/questions/{qid}/reference", payload, 409)
                    self.assertEqual(code, result["error"]["code"])
            send.assert_not_called()
        self.assertEqual([], self.engine.repo.rows("SELECT * FROM requests WHERE result LIKE '%reference_id%'"))

    def test_only_question_sent_uncited_card_created_without_evidence_or_mastery(self):
        self.configure()
        qid = self.question()
        self.call("/sources", title="私人标题", text="PRIVATE_DOCUMENT_DO_NOT_SEND 强化学习课程材料")
        with patch("server.model_gateway._post_json", side_effect=response) as send:
            state = self.request(f"/questions/{qid}/reference", self.consent())["state"]
        payload = send.call_args.args[2]
        self.assertEqual({"question": QUESTION}, user_payload({"body": payload}))
        self.assertEqual(GENERAL_REFERENCE_PROMPT, payload["messages"][0]["content"])
        self.assertNotIn("PRIVATE_DOCUMENT_DO_NOT_SEND", json.dumps(payload))
        question = state["questions"][0]
        self.assertEqual("recorded", question["state"])
        self.assertIsNone(question["answer"])
        self.assertEqual("general_knowledge", question["reference"]["kind"])
        self.assertEqual("unverified", question["reference"]["status"])
        self.assertIn("无课程原文依据", question["reference"]["notice"])
        self.assertEqual(1, len(state["cards"]))
        card = state["cards"][0]
        self.assertEqual("general_reference", card["basis"])
        self.assertEqual("awaiting_attempt", card["status"])
        self.assertEqual(question["reference"]["id"], card["reference_id"])
        self.assertEqual(card["id"], question["reference"]["card_id"])
        self.assertIn("尚无课程依据", card["basis_notice"])
        self.assertEqual("general_reference", self.engine.repo.rows("SELECT * FROM answers")[0]["status"])
        for table in ("claims", "evidence", "answer_context", "attempts"):
            self.assertEqual([], self.engine.repo.rows(f"SELECT * FROM {table}"))

    def test_reference_survives_restart_and_same_request_never_spends_twice(self):
        self.configure()
        qid = self.question()
        payload = self.consent()
        with patch("server.model_gateway._post_json", side_effect=response) as send:
            first = self.request(f"/questions/{qid}/reference", payload)
            self.engine.close()
            self.engine = MobileEngine(self.path, json.dumps(PROFILE), json.dumps(SECRETS))
            replay = self.request(f"/questions/{qid}/reference", payload)
            self.assertEqual(first["result"]["reference_id"], replay["result"]["reference_id"])
            self.assertTrue(replay["result"]["replayed"])
            self.assertEqual(first["state"]["questions"][0]["reference"], replay["state"]["questions"][0]["reference"])
            self.assertEqual(1, send.call_count)
            changed = dict(payload, purpose="evidence")
            conflict = self.request(f"/questions/{qid}/reference", changed, 409)
            self.assertEqual("idempotency_conflict", conflict["error"]["code"])

    def test_reference_preserves_existing_cited_answer_card_and_later_source_invalidation(self):
        qid = self.question("资源分配图有环，是否一定死锁？")
        sid = self.call("/sources", title="合成资料", text=SINGLE + MULTI)["result"]["source_id"]
        original = self.call(f"/questions/{qid}/explain")["state"]
        self.configure()
        with patch("server.model_gateway._post_json", side_effect=response):
            state = self.request(f"/questions/{qid}/reference", self.consent())["state"]
        self.assertEqual(original["questions"][0]["answer"], state["questions"][0]["answer"])
        self.assertEqual(original["cards"], [c for c in state["cards"] if c["basis"] == "course_evidence"])
        general = next(c for c in state["cards"] if c["basis"] == "general_reference")
        self.assertEqual("needs_review", general["status"])
        self.assertEqual(original["questions"][0]["answer"]["id"], general["superseded_by_answer_id"])
        self.assertEqual(original["questions"][0]["state"], state["questions"][0]["state"])
        deleted = self.call(f"/sources/{sid}/delete", base_revision=1)["state"]
        self.assertEqual("needs_review", deleted["questions"][0]["answer"]["status"])
        self.assertEqual("needs_review", deleted["cards"][0]["status"])
        self.assertEqual("superseded", deleted["questions"][0]["reference"]["status"])
        self.assertTrue(all(c["status"] == "needs_review" for c in deleted["cards"]))

    def test_question_edit_marks_old_reference_for_review(self):
        self.configure()
        qid = self.question()
        with patch("server.model_gateway._post_json", side_effect=response):
            self.request(f"/questions/{qid}/reference", self.consent())
        state = self.call(f"/questions/{qid}", text="什么是监督学习？", base_revision=1)["state"]
        self.assertEqual("needs_review", state["questions"][0]["reference"]["status"])
        self.assertEqual(1, state["questions"][0]["reference"]["question_revision"])
        self.assertEqual(2, state["questions"][0]["revision"])

    def test_changed_question_or_provider_discards_inflight_reference(self):
        self.configure()
        qid = self.question()
        for change in ("question", "provider"):
            with self.subTest(change=change):
                revision = self.request("/state")["questions"][0]["revision"]
                def changed(url, headers, payload, timeout):
                    if change == "question":
                        self.call(f"/questions/{qid}", text="新问题", base_revision=revision)
                    else:
                        self.configure(dict(PROFILE, model="changed-model"))
                    return response(url, headers, payload, timeout)
                with patch("server.model_gateway._post_json", side_effect=changed):
                    result = self.request(f"/questions/{qid}/reference", self.consent(question_revision=revision), 409)
                    self.assertEqual("generation_context_changed", result["error"]["code"])
        self.assertEqual([], self.engine.repo.rows("SELECT * FROM general_references"))

    def test_source_change_is_not_a_dependency_of_question_only_reference(self):
        self.configure()
        qid = self.question()
        def changed(url, headers, payload, timeout):
            self.call("/sources", title="新增未发送资料", text="新增未发送的材料")
            return response(url, headers, payload, timeout)
        with patch("server.model_gateway._post_json", side_effect=changed):
            state = self.request(f"/questions/{qid}/reference", self.consent())["state"]
        self.assertEqual(REFERENCE, state["questions"][0]["reference"]["text"])

    def test_failure_is_durable_and_does_not_replace_prior_reference(self):
        self.configure()
        qid = self.question()
        with patch("server.model_gateway._post_json", side_effect=response):
            old = self.request(f"/questions/{qid}/reference", self.consent())["state"]["questions"][0]["reference"]
        payload = self.consent()
        failure = ProviderError("provider_timeout", "模型请求超时；本次请求未自动重试。", 504)
        with patch("server.model_gateway._post_json", side_effect=failure) as send:
            self.request(f"/questions/{qid}/reference", payload, 504)
            self.request(f"/questions/{qid}/reference", payload, 504)
            self.assertEqual(1, send.call_count)
        self.assertEqual(old, self.request("/state")["questions"][0]["reference"])

    def test_concurrent_duplicate_and_other_generation_never_send_again(self):
        self.configure()
        qid = self.question()
        started, release = threading.Event(), threading.Event()
        result = []
        payload = self.consent()
        def blocked(*args):
            started.set()
            if not release.wait(5):
                raise AssertionError("test worker release timed out")
            return response(*args)
        with patch("server.model_gateway._post_json", side_effect=blocked) as send:
            worker = threading.Thread(target=lambda: result.append(self.engine.dispatch(f"/questions/{qid}/reference", json.dumps(payload))))
            worker.start()
            try:
                self.assertTrue(started.wait(3))
                replay = self.request(f"/questions/{qid}/reference", payload, 409)
                self.assertEqual("generation_in_progress", replay["error"]["code"])
                busy = self.request(f"/questions/{qid}/explain", self.consent(), 409)
                self.assertEqual("generation_busy", busy["error"]["code"])
            finally:
                release.set()
                worker.join(5)
            self.assertFalse(worker.is_alive())
            self.assertEqual(200, json.loads(result[0])["status"])
            self.assertEqual(1, send.call_count)

    def test_course_partition_applies_to_reference(self):
        self.configure()
        qid = self.question(course_id="other-course")
        with patch("server.model_gateway._post_json") as send:
            result = self.request(f"/questions/{qid}/reference", self.consent(), 404)
            self.assertEqual("not_found", result["error"]["code"])
            send.assert_not_called()
        self.assertEqual([], self.request("/state")["questions"])

    def test_four_protocols_accept_only_separate_bounded_text_schema(self):
        for protocol in ("openai_chat", "openai_responses", "anthropic_messages", "gemini_generate_content"):
            with self.subTest(protocol=protocol):
                provider = load_provider_config(dict(PROFILE, protocol=protocol), environ=SECRETS)
                with patch("server.model_gateway._post_json", side_effect=response) as send:
                    self.assertEqual(REFERENCE, provider.general_reference(QUESTION))
                    self.assertEqual({"question": QUESTION}, user_payload({"body": send.call_args.args[2]}))
                for bad in ({"text": REFERENCE, "evidence_ids": ["fake"]}, {"text": ""},
                            {"text": "x" * 12001}, {"text": "\x01bad"}, {"text": [REFERENCE]},
                            {"status": "ready", "claims": [], "missing_information": []}):
                    def invalid(url, headers, payload, timeout):
                        return envelope({"path": url, "body": payload}, bad)
                    with patch("server.model_gateway._post_json", side_effect=invalid), self.assertRaises(ProviderError) as raised:
                        provider.general_reference(QUESTION)
                    self.assertEqual("provider_invalid_output", raised.exception.code)

    def test_existing_database_gains_table_without_losing_learning_data(self):
        self.engine.close()
        old_path = Path(self.tmp.name) / "old.sqlite3"
        old_schema = "\n".join(line for line in SCHEMA.splitlines()
                               if not any(f"CREATE TABLE IF NOT EXISTS {name}" in line
                                          for name in ("general_references", "answer_provenance", "general_reference_cards")))
        with closing(sqlite3.connect(old_path)) as db, db:
            db.executescript(old_schema)
            db.execute("INSERT INTO questions VALUES('old-question','os-default','保留旧疑问',1,'recorded',NULL)")
        self.engine = MobileEngine(old_path)
        question = self.request("/state")["questions"][0]
        self.assertEqual("保留旧疑问", question["text"])
        self.assertIsNone(question["reference"])
        self.assertEqual([], self.engine.repo.rows("SELECT * FROM general_references"))

    def test_uncited_review_attempt_self_rating_and_restart_never_change_question_mastery(self):
        self.configure()
        qid = self.question()
        with patch("server.model_gateway._post_json", side_effect=response) as send:
            generated = self.request(f"/questions/{qid}/reference", self.consent())
            cid = generated["result"]["card_id"]
            attempted = self.call(f"/cards/{cid}/attempts", answer="根据奖励反馈来学习决策。")
            aid = attempted["result"]["attempt_id"]
            record = attempted["state"]["attempts"][0]
            self.assertEqual("general_reference", record["basis"])
            self.assertEqual("general-reference-comparison", record["feedback"]["mode"])
            self.assertEqual([REFERENCE], record["feedback"]["reference_points"])
            self.assertIsNone(record["feedback"]["automatic_grade"])
            self.assertIn("尚无课程依据", record["source_warning"])
            state = self.call(f"/cards/{cid}/confirm", attempt_id=aid, self_rating="understood")["state"]
            self.assertEqual("reviewing", state["cards"][0]["status"])
            self.assertTrue(state["cards"][0]["due_at"])
            self.assertEqual("recorded", state["questions"][0]["state"])
            self.assertIsNone(state["questions"][0]["answer"])
            self.engine.close()
            self.engine = MobileEngine(self.path, json.dumps(PROFILE), json.dumps(SECRETS))
            restored = self.request("/state")
            self.assertEqual(state["attempts"], restored["attempts"])
            self.assertEqual(state["cards"], restored["cards"])
            self.assertEqual(1, send.call_count)

    def test_material_import_then_explicit_cited_upgrade_preserves_old_review_history(self):
        self.configure()
        qid = self.question()
        with patch("server.model_gateway._post_json", side_effect=response) as send:
            general = self.request(f"/questions/{qid}/reference", self.consent())
            cid = general["result"]["card_id"]
            attempted = self.call(f"/cards/{cid}/attempts", answer="保留我的独立作答")
            aid = attempted["result"]["attempt_id"]
            self.call(f"/cards/{cid}/confirm", attempt_id=aid, self_rating="again")
            before = self.request("/state")
            imported = self.call("/sources", title="课后合成资料", text=REFERENCE)
            sid = imported["result"]["source_id"]
            self.assertEqual(1, send.call_count)
            self.assertEqual(before["cards"], imported["state"]["cards"])
        def cited(url, headers, payload, timeout):
            data = user_payload({"body": payload})
            self.assertIn("evidence", data)
            return envelope({"path": url, "body": payload}, {"status": "ready", "claims": [
                {"text": "强化学习利用奖励反馈学习。", "evidence_ids": [data["evidence"][0]["evidence_id"]]}],
                "missing_information": []})
        with patch("server.model_gateway._post_json", side_effect=cited) as send:
            upgraded = self.request(f"/questions/{qid}/explain", self.consent())["state"]
            self.assertEqual(1, send.call_count)
        question = upgraded["questions"][0]
        self.assertEqual("ready", question["answer"]["status"])
        self.assertEqual("superseded", question["reference"]["status"])
        self.assertEqual(REFERENCE, question["reference"]["text"])
        self.assertEqual(2, len(upgraded["cards"]))
        old = next(c for c in upgraded["cards"] if c["id"] == cid)
        self.assertEqual("needs_review", old["status"])
        self.assertEqual(question["answer"]["id"], old["superseded_by_answer_id"])
        self.assertIsNone(old["due_at"])
        new = next(c for c in upgraded["cards"] if c["id"] != cid)
        self.assertEqual("course_evidence", new["basis"])
        self.assertEqual("awaiting_attempt", new["status"])
        self.assertEqual(before["attempts"][0]["answer"], upgraded["attempts"][0]["answer"])
        self.assertEqual(before["attempts"][0]["feedback"], upgraded["attempts"][0]["feedback"])
        self.assertEqual(before["attempts"][0]["self_rating"], upgraded["attempts"][0]["self_rating"])
        self.call(f"/cards/{cid}/confirm", status=409, attempt_id=aid, self_rating="understood")
        deleted = self.call(f"/sources/{sid}/delete", base_revision=1)["state"]
        self.assertTrue(all(c["status"] == "needs_review" for c in deleted["cards"]))
        self.assertEqual(REFERENCE, deleted["questions"][0]["reference"]["text"])
        self.assertEqual("superseded", deleted["questions"][0]["reference"]["status"])

    def test_missing_or_conflicting_material_does_not_supersede_general_review(self):
        self.configure()
        qid = self.question()
        with patch("server.model_gateway._post_json", side_effect=response):
            original = self.request(f"/questions/{qid}/reference", self.consent())["state"]
        with patch("server.model_gateway._post_json") as send:
            missing = self.request(f"/questions/{qid}/explain", self.consent())["state"]
            send.assert_not_called()
        self.assertEqual(original["cards"], missing["cards"])
        self.call("/sources", title="合成观点一", text=REFERENCE)
        self.call("/sources", title="合成观点二", text="强化学习采用不同说法，供冲突分支测试。")
        def conflict(url, headers, payload, timeout):
            data = user_payload({"body": payload})
            ids = [item["evidence_id"] for item in data["evidence"]]
            return envelope({"path": url, "body": payload}, {"status": "conflict", "claims": [
                {"text": "模拟观点一", "evidence_ids": [ids[0]]},
                {"text": "模拟观点二", "evidence_ids": [ids[1]]}], "missing_information": ["请核对两份合成材料。"]})
        with patch("server.model_gateway._post_json", side_effect=conflict):
            conflicted = self.request(f"/questions/{qid}/explain", self.consent())["state"]
        self.assertEqual("conflict", conflicted["questions"][0]["answer"]["status"])
        self.assertEqual("unverified", conflicted["questions"][0]["reference"]["status"])
        self.assertEqual(original["cards"], conflicted["cards"])

    def test_regeneration_retains_human_answers_and_pauses_only_old_general_cards(self):
        self.configure()
        qid = self.question()
        with patch("server.model_gateway._post_json", side_effect=response):
            first = self.request(f"/questions/{qid}/reference", self.consent())
            cid = first["result"]["card_id"]
            self.call(f"/cards/{cid}/attempts", answer="先前人工回答")
            second = self.request(f"/questions/{qid}/reference", self.consent())
        state = second["state"]
        self.assertEqual(2, len(state["cards"]))
        self.assertNotEqual(cid, second["result"]["card_id"])
        self.assertEqual("needs_review", state["cards"][0]["status"])
        self.assertEqual("awaiting_attempt", state["cards"][1]["status"])
        self.assertEqual("先前人工回答", state["attempts"][0]["answer"])
        self.assertEqual(2, len(self.engine.repo.rows("SELECT * FROM general_references")))

    def test_old_reference_backfill_is_local_idempotent_and_keeps_only_latest_active(self):
        qid = self.question()
        with self.engine.repo.db:
            for index in (1, 2):
                self.engine.repo.db.execute("INSERT INTO general_references VALUES(?,?,?,?,?,?)",
                                            (f"old-{index}", qid, 1, f"旧通用参考{index}", "legacy-provider", "2026-11-17"))
        self.engine.close()
        with patch("server.model_gateway._post_json") as send:
            self.engine = MobileEngine(self.path)
            first = self.request("/state")
            self.engine.close()
            self.engine = MobileEngine(self.path)
            second = self.request("/state")
            send.assert_not_called()
        self.assertEqual(first["cards"], second["cards"])
        self.assertEqual(2, len(second["cards"]))
        self.assertEqual(["needs_review", "awaiting_attempt"], [c["status"] for c in second["cards"]])
        self.assertEqual(second["cards"][1]["id"], second["questions"][0]["reference"]["card_id"])
        self.assertIsNone(second["questions"][0]["answer"])
        self.assertEqual(0, len(self.engine.repo.rows("SELECT * FROM claims")))

    def test_backfill_failure_rolls_back_all_added_answers_cards_and_links(self):
        qid = self.question()
        with self.engine.repo.db:
            self.engine.repo.db.execute("INSERT INTO general_references VALUES(?,?,?,?,?,?)", ("valid-old", qid, 1, "合法旧参考", "legacy", "2026-11-17"))
            self.engine.repo.db.execute("INSERT INTO general_references VALUES(?,?,?,?,?,?)", ("invalid-old", qid, 1, "", "legacy", "2026-11-17"))
        self.engine.close()
        with self.assertRaises(Exception):
            MobileEngine(self.path)
        with closing(sqlite3.connect(self.path)) as db:
            for table in ("answers", "cards", "general_reference_cards"):
                self.assertEqual(0, db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])
            self.assertEqual(2, db.execute("SELECT COUNT(*) FROM general_references").fetchone()[0])
        # Repair the intentionally corrupt fixture, then reopen for cleanup.
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute("UPDATE general_references SET text='修复的测试夹具' WHERE id='invalid-old'")
        self.engine = MobileEngine(self.path)


if __name__ == "__main__":
    unittest.main()
