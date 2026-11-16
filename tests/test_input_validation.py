"""Regressions from MuMu input injecting Ctrl+A/Ctrl+V as literal characters."""
import unittest
import uuid

from server.core import DomainError, Repository, parse_pages
from server.provider import SINGLE, MULTI, EXAMPLE


class InputValidationTests(unittest.TestCase):
    def setUp(self):
        self.repo = Repository(":memory:")

    def tearDown(self):
        self.repo.close()

    def call(self, path, **payload):
        payload.setdefault("request_id", uuid.uuid4().hex)
        return self.repo.mutate(path, payload)

    def assert_invalid(self, operation):
        with self.assertRaises(DomainError) as context:
            operation()
        self.assertEqual("invalid_input", context.exception.code)
        self.assertEqual(400, context.exception.status)

    def ready_card(self):
        self.call("/sources", title="合成控制字符回归", text=SINGLE + "\f" + MULTI + "\n" + EXAMPLE)
        qid = self.call("/questions", text="资源分配图有环，是否一定死锁？")["result"]["question_id"]
        return self.call(f"/questions/{qid}/explain")["result"]["card_id"]

    def test_shortcut_controls_cannot_create_question_or_feedback(self):
        cid = self.ready_card()
        prior_questions = len(self.repo.state()["questions"])
        for value in ("\x16\x01\x16", "正常文字\x16", "\x00答案", "回答\x7f", "答案\x85", "答案\ud800"):
            with self.subTest(value=repr(value)):
                self.assert_invalid(lambda: self.call("/questions", text=value))
                request_id = uuid.uuid4().hex
                self.assert_invalid(lambda: self.call(f"/cards/{cid}/attempts", answer=value, request_id=request_id))
                self.assertIsNone(self.repo.one("SELECT * FROM requests WHERE request_id=?", (request_id,)))
        state = self.repo.state()
        self.assertEqual(prior_questions, len(state["questions"]))
        self.assertEqual([], state["attempts"])
        self.assertEqual("awaiting_attempt", state["cards"][0]["status"])

    def test_invisible_only_input_is_not_an_independent_answer(self):
        cid = self.ready_card()
        for value in (" \t\r\n", "\u200b\u200d\u2060\ufeff", "\u0301\ufe0f", "\u3164\u2800"):
            with self.subTest(value=repr(value)):
                self.assert_invalid(lambda: self.call("/questions", text=value))
                self.assert_invalid(lambda: self.call(f"/cards/{cid}/attempts", answer=value))
        self.assertEqual([], self.repo.state()["attempts"])

    def test_multiline_text_and_unicode_sequences_round_trip_without_rewriting(self):
        cid = self.ready_card()
        answer = "P3释放A。\n\tP2释放B，P1继续。\r\n例：👩\u200d💻；e\u0301。"
        result = self.call(f"/cards/{cid}/attempts", answer=answer)
        self.assertEqual(answer, result["state"]["attempts"][0]["answer"])
        self.assertIsNone(result["state"]["attempts"][0]["feedback"]["automatic_grade"])

    def test_form_feed_is_a_document_separator_not_a_question_or_answer_control(self):
        pages, _ = parse_pages({"text": "第一页\n\t内容\f第二页"})
        self.assertEqual([{"page": 1, "text": "第一页\n\t内容"}, {"page": 2, "text": "第二页"}], pages)
        cid = self.ready_card()
        self.assert_invalid(lambda: self.call("/questions", text="问题\f内容"))
        self.assert_invalid(lambda: self.call(f"/cards/{cid}/attempts", answer="答案\f内容"))
        self.assert_invalid(lambda: self.call("/sources", title="坏输入", text="资料\x16正文"))

    def test_invisible_only_document_page_does_not_become_evidence(self):
        with self.assertRaises(DomainError) as context:
            parse_pages({"text": "正常第一页\f\u200b\u200d"})
        self.assertEqual("page_without_text", context.exception.code)
        self.assertEqual(422, context.exception.status)


if __name__ == "__main__":
    unittest.main()
