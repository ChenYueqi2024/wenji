"""Offline regressions for consent, durable model requests, and source races."""
import json
from pathlib import Path
import tempfile
import threading
import unittest
import uuid

from server.core import DomainError, Repository
from server.provider import Candidate, Claim, Proposal


FINGERPRINT = "offline-fake-model-v1"
SOURCE_TEXT = "This is the source text used by the offline model tests."


def ready_proposal(question, sources):
    source = sources[0]
    page = source["pages"][0]
    candidate = Candidate(source["id"], source["revision"], page["page"],
                          0, len(page["text"]), page["text"], source["sha256"])
    return Proposal("ready", [candidate], [],
                    claims=[Claim("A model paraphrase grounded in the cited source.", [candidate])])


class FakeModelProvider:
    name = "offline-fake-model"

    def __init__(self, *, builder=ready_proposal, block=False, failure=None):
        self.builder = builder
        self.block = block
        self.failure = failure
        self.fingerprint = FINGERPRINT
        self.calls = 0
        self.entered = threading.Event()
        self.release = threading.Event()

    def public_info(self):
        return {"mode": "llm", "label": "Offline fake model", "protocol": "fake",
                "model": "unit-test", "endpoint": "https://offline.invalid",
                "fingerprint": self.fingerprint, "requires_consent": True}

    def propose(self, question, sources):
        self.calls += 1
        self.entered.set()
        if self.block and not self.release.wait(8):
            raise RuntimeError("test did not release the offline provider")
        if self.failure is not None:
            raise self.failure
        return self.builder(question, sources)


class ModelDomainTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "models.sqlite3"
        self.provider = FakeModelProvider()
        self.repo = Repository(self.path, self.provider)
        self.workers = []

    def tearDown(self):
        for provider, worker, _ in self.workers:
            provider.release.set()
            worker.join(3)
        self.repo.close()
        self.tmp.cleanup()

    def call(self, path, **payload):
        payload.setdefault("request_id", uuid.uuid4().hex)
        return self.repo.mutate(path, payload)

    def source(self, text=SOURCE_TEXT):
        return self.call("/sources", title="Offline fixture", text=text)["result"]["source_id"]

    def question(self):
        return self.call("/questions", text="What does the supplied source say?")["result"]["question_id"]

    def consent(self, **extra):
        payload = dict(request_id=uuid.uuid4().hex, allow_remote=True,
                       provider_fingerprint=FINGERPRINT, question_revision=1)
        payload.update(extra)
        return payload

    def assert_error(self, code, operation):
        with self.assertRaises(DomainError) as caught:
            operation()
        self.assertEqual(code, caught.exception.code)
        return caught.exception

    def start_generation(self, question_id, payload=None):
        self.provider.block = True
        payload = payload or self.consent()
        outcome = {}

        def generate():
            try:
                outcome["response"] = self.repo.mutate(f"/questions/{question_id}/explain", payload)
            except BaseException as exc:
                outcome["error"] = exc

        worker = threading.Thread(target=generate, daemon=True)
        self.workers.append((self.provider, worker, outcome))
        worker.start()
        self.assertTrue(self.provider.entered.wait(3), "provider was not entered")
        return payload, worker, outcome

    def finish_generation(self, worker, outcome):
        self.provider.release.set()
        worker.join(3)
        self.assertFalse(worker.is_alive(), "generation did not finish after release")
        return outcome

    def while_generating(self, operation):
        """Bound the assertion so a mistakenly held repository lock cannot hang."""
        complete = threading.Event()
        outcome = {}

        def execute():
            try:
                outcome["result"] = operation()
            except BaseException as exc:
                outcome["error"] = exc
            finally:
                complete.set()

        worker = threading.Thread(target=execute, daemon=True)
        worker.start()
        finished = complete.wait(2)
        if not finished:
            self.provider.release.set()
        worker.join(3)
        self.assertTrue(finished, "database operation blocked behind provider I/O")
        if "error" in outcome:
            raise outcome["error"]
        return outcome["result"]

    def reopen(self):
        self.repo.close()
        self.repo = Repository(self.path, self.provider)

    def test_remote_consent_is_exact_and_bound_to_current_provider(self):
        self.source()
        qid = self.question()
        variants = [{}, {"allow_remote": False}, {"allow_remote": "true"},
                    {"allow_remote": 1}, {"allow_remote": True},
                    {"allow_remote": True, "provider_fingerprint": "stale-model"}]
        for consent in variants:
            with self.subTest(consent=consent):
                payload = dict(request_id=uuid.uuid4().hex, **consent)
                self.assert_error("model_consent_required",
                                  lambda: self.repo.mutate(f"/questions/{qid}/explain", payload))
                self.assertIsNone(self.repo.one("SELECT * FROM requests WHERE request_id=?",
                                               (payload["request_id"],)))
        self.assertEqual(0, self.provider.calls)
        self.assertEqual([], self.repo.rows("SELECT * FROM answers"))

    def test_remote_consent_rejects_missing_or_changed_question_revision_before_sending(self):
        self.source()
        qid = self.question()
        self.call(f"/questions/{qid}", base_revision=1, text="Edited after the consent dialog opened.")
        for revision in (None, 1, True, "2"):
            with self.subTest(revision=revision):
                payload = self.consent(question_revision=revision)
                self.assert_error("generation_question_changed",
                                  lambda: self.repo.mutate(f"/questions/{qid}/explain", payload))
                self.assertIsNone(self.repo.one("SELECT * FROM requests WHERE request_id=?",
                                               (payload["request_id"],)))
        self.assertEqual(0, self.provider.calls)
        result = self.repo.mutate(f"/questions/{qid}/explain", self.consent(question_revision=2))
        self.assertEqual("ready", result["state"]["questions"][0]["answer"]["status"])
        self.assertEqual(1, self.provider.calls)

    def test_success_stores_paraphrase_and_replays_once_after_restart(self):
        self.source()
        qid = self.question()
        payload = self.consent()
        path = f"/questions/{qid}/explain"
        first = self.repo.mutate(path, payload)
        claim = first["state"]["questions"][0]["answer"]["claims"][0]
        self.assertNotEqual(claim["text"], claim["evidence"][0]["quote"])
        self.assertEqual("unconfirmed", claim["status"])
        replay = self.repo.mutate(path, payload)
        self.assertEqual(first["result"]["answer_id"], replay["result"]["answer_id"])
        self.assertTrue(replay["result"]["replayed"])
        self.reopen()
        replay = self.repo.mutate(path, payload)
        self.assertEqual(first["result"]["card_id"], replay["result"]["card_id"])
        self.assertEqual(1, self.provider.calls)
        self.assertEqual(1, len(self.repo.rows("SELECT * FROM answers")))
        self.assert_error("idempotency_conflict",
                          lambda: self.repo.mutate(path, dict(payload, provider_fingerprint="changed")))
        self.assertEqual(1, self.provider.calls)

    def test_provider_failure_is_sanitized_and_durable_without_retry(self):
        self.source()
        qid = self.question()
        secret = "test-only-upstream-body-and-api-key"
        self.provider.failure = RuntimeError(secret)
        payload = self.consent()
        path = f"/questions/{qid}/explain"
        failure = self.assert_error("provider_failed", lambda: self.repo.mutate(path, payload))
        self.assertNotIn(secret, failure.message)
        self.reopen()
        self.assert_error("provider_failed", lambda: self.repo.mutate(path, payload))
        self.assertEqual(1, self.provider.calls)
        self.assertEqual([], self.repo.rows("SELECT * FROM answers"))
        self.assertNotIn(secret, json.dumps(self.repo.rows("SELECT * FROM requests")))

    def test_interrupted_request_reservation_survives_restart(self):
        class SimulatedProcessExit(BaseException):
            pass

        self.source()
        qid = self.question()
        self.provider.failure = SimulatedProcessExit()
        payload = self.consent()
        path = f"/questions/{qid}/explain"
        with self.assertRaises(SimulatedProcessExit):
            self.repo.mutate(path, payload)
        self.reopen()
        self.provider.failure = None
        self.assert_error("generation_in_progress", lambda: self.repo.mutate(path, payload))
        self.assertEqual(1, self.provider.calls)
        self.assertEqual([], self.repo.rows("SELECT * FROM answers"))

    def test_inflight_duplicate_cannot_call_provider_twice_and_reads_remain_available(self):
        self.source()
        qid = self.question()
        payload, worker, outcome = self.start_generation(qid)
        path = f"/questions/{qid}/explain"
        self.while_generating(lambda: self.assert_error("generation_in_progress",
                                                       lambda: self.repo.mutate(path, payload)))
        self.while_generating(lambda: self.assert_error("generation_busy",
                                                       lambda: self.repo.mutate(path, self.consent())))
        state = self.while_generating(self.repo.state)
        self.assertIsNone(state["questions"][0]["answer"])
        self.assertEqual(1, self.provider.calls)
        self.finish_generation(worker, outcome)
        self.assertNotIn("error", outcome)
        self.assertEqual(1, len(self.repo.state()["cards"]))

    def test_reservation_is_visible_to_another_repository_before_provider_finishes(self):
        sid = self.source()
        qid = self.question()
        payload, worker, outcome = self.start_generation(qid)
        other = Repository(self.path, self.provider)
        try:
            self.assert_error("generation_in_progress",
                              lambda: other.mutate(f"/questions/{qid}/explain", payload))
            self.while_generating(lambda: other.mutate(f"/sources/{sid}/revisions",
                                   {"request_id": uuid.uuid4().hex, "base_revision": 1,
                                    "text": "Revision saved by a separate database connection."}))
        finally:
            other.close()
        self.finish_generation(worker, outcome)
        self.assertIsInstance(outcome.get("error"), DomainError)
        self.assertEqual("generation_context_changed", outcome["error"].code)
        self.assertEqual(1, self.provider.calls)
        self.assertEqual([], self.repo.rows("SELECT * FROM answers"))

    def assert_context_change_rejected(self, change):
        sid = self.source()
        qid = self.question()
        payload, worker, outcome = self.start_generation(qid)
        self.while_generating(lambda: change(sid, qid))
        self.finish_generation(worker, outcome)
        self.assertIsInstance(outcome.get("error"), DomainError)
        self.assertEqual("generation_context_changed", outcome["error"].code)
        self.assertEqual([], self.repo.rows("SELECT * FROM answers"))
        self.assertEqual([], self.repo.rows("SELECT * FROM evidence"))
        self.assertEqual([], self.repo.state()["cards"])
        self.assert_error("generation_context_changed",
                          lambda: self.repo.mutate(f"/questions/{qid}/explain", payload))
        self.assertEqual(1, self.provider.calls)

    def test_source_addition_during_generation_discards_the_result(self):
        self.assert_context_change_rejected(lambda sid, qid: self.source("New relevant evidence."))

    def test_source_revision_during_generation_discards_the_result(self):
        self.assert_context_change_rejected(lambda sid, qid: self.call(
            f"/sources/{sid}/revisions", base_revision=1, text="Revised source text."))

    def test_source_deletion_during_generation_discards_the_result(self):
        self.assert_context_change_rejected(lambda sid, qid: self.call(
            f"/sources/{sid}/delete", base_revision=1))

    def test_question_edit_during_generation_discards_the_result(self):
        self.assert_context_change_rejected(lambda sid, qid: self.call(
            f"/questions/{qid}", base_revision=1, text="A different question."))

    def test_provider_replacement_during_generation_discards_the_result(self):
        self.assert_context_change_rejected(lambda sid, qid: setattr(self.repo, "provider", FakeModelProvider()))

    def test_provider_fingerprint_change_during_generation_discards_the_result(self):
        self.assert_context_change_rejected(lambda sid, qid: setattr(self.provider, "fingerprint", "changed-model"))

    def test_uncited_context_deletion_scrubs_model_claims_missing_and_feedback(self):
        self.source()
        marker = "PRIVATE_UNCITED_CONTEXT_MARKER"
        uncited = self.source(marker)
        qid = self.question()

        def derived_proposal(question, sources):
            proposal = ready_proposal(question, sources)
            proposal.claims[0].text = "Paraphrase influenced by " + marker
            proposal.missing = ["Further context needed about " + marker]
            return proposal

        self.provider.builder = derived_proposal
        payload = self.consent()
        result = self.repo.mutate(f"/questions/{qid}/explain", payload)
        cid = result["result"]["card_id"]
        self.call(f"/cards/{cid}/attempts", answer="Keep my independently written answer.")
        # A newer explanation must not leave source-derived content in hidden
        # historical answers, cards, or feedback after the context is withdrawn.
        self.provider.builder = ready_proposal
        self.repo.mutate(f"/questions/{qid}/explain", self.consent())
        self.assertIn(marker, json.dumps(self.repo.state()))
        result = self.call(f"/sources/{uncited}/delete", base_revision=1)
        serialized = json.dumps(result)
        self.assertNotIn(marker, serialized)
        self.assertIn("Keep my independently written answer.", serialized)
        answer = result["state"]["questions"][0]["answer"]
        self.assertEqual("needs_review", answer["status"])
        self.assertEqual([], answer["missing_information"])
        self.assertEqual("needs_review", result["state"]["cards"][0]["status"])
        self.assertEqual([], result["state"]["attempts"][0]["feedback"]["reference_points"])
        self.assertEqual("[]", self.repo.one("SELECT rubric FROM cards WHERE id=?", (cid,))["rubric"])
        for table in ("answers", "claims", "cards", "attempts"):
            self.assertNotIn(marker, json.dumps(self.repo.rows(f"SELECT * FROM {table}")))
        replay = self.repo.mutate(f"/questions/{qid}/explain", payload)
        self.assertNotIn(marker, json.dumps(replay))
        self.assertEqual(2, self.provider.calls)

    def test_uncited_context_revision_invalidates_model_answer_and_card(self):
        self.source()
        uncited = self.source("Uncited supporting context.")
        qid = self.question()
        result = self.repo.mutate(f"/questions/{qid}/explain", self.consent())
        cid = result["result"]["card_id"]
        result = self.call(f"/sources/{uncited}/revisions", base_revision=1,
                           text="Uncited context changed its meaning.")
        self.assertEqual("needs_review", result["state"]["questions"][0]["answer"]["status"])
        self.assertEqual("needs_review", result["state"]["cards"][0]["status"])
        self.assert_error("source_needs_review", lambda: self.call(
            f"/cards/{cid}/attempts", answer="Cannot use an outdated model rubric."))

    def test_context_deletion_scrubs_needs_material_without_any_citations(self):
        marker = "PRIVATE_MISSING_INFORMATION_MARKER"
        sid = self.source(marker)
        qid = self.question()
        self.provider.builder = lambda question, sources: Proposal(
            "needs_material", [], ["Need another source describing " + marker], claims=[])
        result = self.repo.mutate(f"/questions/{qid}/explain", self.consent())
        self.assertIn(marker, json.dumps(result))
        self.assertEqual([], result["state"]["cards"])
        result = self.call(f"/sources/{sid}/delete", base_revision=1)
        self.assertNotIn(marker, json.dumps(result))
        answer = result["state"]["questions"][0]["answer"]
        self.assertEqual("needs_review", answer["status"])
        self.assertEqual([], answer["missing_information"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
