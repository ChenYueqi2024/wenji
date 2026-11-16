"""SQLite domain layer with atomic version invalidation and idempotent writes."""
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import sqlite3
import threading
import unicodedata
import uuid
from datetime import datetime, timedelta, timezone

from .provider import ExtractiveDemoProvider, feedback, quiz


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def uid():
    return uuid.uuid4().hex


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


class DomainError(Exception):
    def __init__(self, code, message, status=400, details=None):
        super().__init__(message)
        self.code, self.message, self.status, self.details = code, message, status, details


def has_visible_content(value):
    # Formatting/combining characters may accompany real text (e.g. emoji ZWJ),
    # but cannot constitute an answer by themselves. Known blank glyphs below
    # are letters/symbols in Unicode and therefore need an explicit exclusion.
    blank_glyphs = "\u115f\u1160\u3164\uffa0\u2800"
    return any(not char.isspace() and char not in blank_glyphs
               and unicodedata.category(char)[0] in "LNPS" for char in value)


def require_text(value, name, limit=20000, *, allow_form_feed=False):
    if not isinstance(value, str) or len(value) > limit or not has_visible_content(value):
        raise DomainError("invalid_input", f"{name}须为非空文本，且不超过{limit}个字符。")
    # Reject, never silently erase, accidental shortcut keystrokes such as
    # Ctrl+A/Ctrl+V. Ordinary multiline text and indentation remain supported.
    # Form feed is reserved for the documented plain-text page separator.
    allowed_controls = "\t\n\r" + ("\f" if allow_form_feed else "")
    if any((unicodedata.category(char) == "Cc" and char not in allowed_controls)
           or unicodedata.category(char) == "Cs" for char in value):
        raise DomainError("invalid_input", f"{name}含不可用的控制字符，请重新输入可见文本。")
    return value.strip()


def normalize(text):
    return unicodedata.normalize("NFC", text.replace("\r\n", "\n").replace("\r", "\n"))


def parse_pages(payload):
    if ("text" in payload) == ("pdf_base64" in payload):
        raise DomainError("invalid_input", "请且仅请提供text或pdf_base64其中一种资料。")
    if "text" in payload:
        raw = require_text(payload["text"], "资料正文", 500000, allow_form_feed=True)
        pages = normalize(raw).split("\f")
    else:
        try:
            encoded = require_text(payload["pdf_base64"], "PDF", 7000000)
            data = base64.b64decode(encoded, validate=True)
            if len(data) > 5_000_000 or not data.startswith(b"%PDF-"):
                raise ValueError("invalid PDF header or size")
            from pypdf import PdfReader
            reader = PdfReader(io.BytesIO(data), strict=True)
            if reader.is_encrypted or len(reader.pages) > 100:
                raise ValueError("encrypted PDF or page count > 100")
            pages = [normalize(page.extract_text() or "") for page in reader.pages]
        except ImportError:
            raise DomainError("pdf_dependency_missing", "缺少pypdf，请使用附带运行库或安装server/requirements.txt。", 503)
        except Exception:
            raise DomainError("pdf_parse_failed", "PDF解析失败：仅支持5MB以内、100页以内、未加密的可复制文本PDF；可改为粘贴文本。", 422)
    if not pages or len(pages) > 100 or sum(map(len, pages)) > 500000:
        raise DomainError("document_too_large", "资料限100页及50万个字符。", 422)
    # Reject an image-only page instead of silently treating it as empty evidence.
    if any(not has_visible_content(page) for page in pages):
        raise DomainError("page_without_text", "存在无可提取文字的页面；请先核对页面，扫描件暂不支持。", 422)
    result = [{"page": i + 1, "text": text} for i, text in enumerate(pages)]
    return result, hashlib.sha256(encode(result).encode("utf-8")).hexdigest()


SCHEMA = """
PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS sources(id TEXT PRIMARY KEY, course_id TEXT NOT NULL, title TEXT NOT NULL, revision INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS revisions(source_id TEXT NOT NULL REFERENCES sources(id), revision INTEGER NOT NULL, pages TEXT NOT NULL, sha256 TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(source_id,revision));
CREATE TABLE IF NOT EXISTS questions(id TEXT PRIMARY KEY, course_id TEXT NOT NULL, text TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 1, state TEXT NOT NULL, current_answer_id TEXT);
CREATE TABLE IF NOT EXISTS answers(id TEXT PRIMARY KEY, question_id TEXT NOT NULL REFERENCES questions(id), status TEXT NOT NULL, missing TEXT NOT NULL, provider TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS evidence(id TEXT PRIMARY KEY, source_id TEXT NOT NULL, revision INTEGER NOT NULL, page INTEGER NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, quote TEXT NOT NULL, sha256 TEXT NOT NULL, status TEXT NOT NULL, FOREIGN KEY(source_id,revision) REFERENCES revisions(source_id,revision));
CREATE TABLE IF NOT EXISTS claims(id TEXT PRIMARY KEY, answer_id TEXT NOT NULL REFERENCES answers(id), text TEXT NOT NULL, status TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS claim_evidence(claim_id TEXT NOT NULL REFERENCES claims(id), evidence_id TEXT NOT NULL REFERENCES evidence(id), PRIMARY KEY(claim_id,evidence_id));
CREATE INDEX IF NOT EXISTS evidence_source ON evidence(source_id);
CREATE INDEX IF NOT EXISTS dependency_evidence ON claim_evidence(evidence_id);
CREATE TABLE IF NOT EXISTS cards(id TEXT PRIMARY KEY, question_id TEXT NOT NULL REFERENCES questions(id), answer_id TEXT NOT NULL REFERENCES answers(id), prompt TEXT NOT NULL, rubric TEXT NOT NULL, status TEXT NOT NULL, due_at TEXT);
CREATE TABLE IF NOT EXISTS attempts(id TEXT PRIMARY KEY, card_id TEXT NOT NULL REFERENCES cards(id), answer TEXT NOT NULL, feedback TEXT NOT NULL, created_at TEXT NOT NULL, confirmed INTEGER NOT NULL DEFAULT 0, self_rating TEXT);
CREATE TABLE IF NOT EXISTS requests(course_id TEXT NOT NULL, request_id TEXT NOT NULL, fingerprint TEXT NOT NULL, result TEXT NOT NULL, PRIMARY KEY(course_id,request_id));
CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY, event TEXT NOT NULL, entity_id TEXT NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS conflicts(id TEXT PRIMARY KEY, question_id TEXT NOT NULL REFERENCES questions(id), base_revision INTEGER NOT NULL, incoming_text TEXT NOT NULL, created_at TEXT NOT NULL);
"""


class Repository:
    def __init__(self, path, provider=None):
        if str(path) != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(str(path), check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        self.db.execute("PRAGMA secure_delete=ON")
        self.lock = threading.RLock()
        self.provider = provider or ExtractiveDemoProvider()

    def close(self):
        self.db.close()

    def rows(self, sql, args=()):
        return [dict(row) for row in self.db.execute(sql, args).fetchall()]

    def one(self, sql, args=()):
        row = self.db.execute(sql, args).fetchone()
        return dict(row) if row else None

    def entity(self, table, identity, course):
        if table in ("sources", "questions"):
            row = self.one(f"SELECT * FROM {table} WHERE id=? AND course_id=?", (identity, course))
        elif table == "cards":
            row = self.one("SELECT c.* FROM cards c JOIN questions q ON q.id=c.question_id WHERE c.id=? AND q.course_id=?", (identity, course))
        else:
            raise ValueError("unsupported entity")
        if not row:
            raise DomainError("not_found", "对象不存在于所选课程。", 404)
        return row

    def sources(self, course):
        sources = self.rows("SELECT s.*,r.pages,r.sha256 FROM sources s JOIN revisions r ON r.source_id=s.id AND r.revision=s.revision WHERE course_id=? ORDER BY s.rowid", (course,))
        for source in sources:
            source["deleted"] = bool(source["deleted"])
            source["pages"] = json.loads(source["pages"])
        return sources

    def answer(self, answer_id):
        answer = self.one("SELECT * FROM answers WHERE id=?", (answer_id,))
        if not answer:
            return None
        answer["missing_information"] = json.loads(answer.pop("missing"))
        answer["claims"] = self.rows("SELECT * FROM claims WHERE answer_id=? ORDER BY rowid", (answer_id,))
        for claim in answer["claims"]:
            claim["evidence"] = self.rows("SELECT e.*,s.title AS source_title FROM evidence e JOIN claim_evidence ce ON ce.evidence_id=e.id JOIN sources s ON s.id=e.source_id WHERE ce.claim_id=? ORDER BY e.rowid", (claim["id"],))
        answer["conflicts"] = [{"text": c["text"], "evidence_ids": [e["id"] for e in c["evidence"]]} for c in answer["claims"] if c["status"] == "conflict"]
        return answer

    def search(self, query, course="os-default", limit=6):
        """Small-course lexical baseline. Scores rank candidates, not truth."""
        query = require_text(query, "检索词", 2000)
        def tokens(text):
            result = set(re.findall(r"[a-z0-9]+", text.lower()))
            for word in re.findall(r"[\u4e00-\u9fff]+", text):
                if len(word) == 1:
                    result.add(word)
                result.update(word[i:i + 2] for i in range(len(word) - 1))
            return result
        wanted = tokens(query)
        candidates = []
        with self.lock:
            for source in self.sources(course):
                if source["deleted"]:
                    continue
                for page in source["pages"]:
                    for start in range(0, len(page["text"]), 300):
                        end = min(start + 400, len(page["text"]))
                        quote = page["text"][start:end]
                        overlap = wanted & tokens(quote)
                        if overlap:
                            candidates.append({"source_id": source["id"], "source_title": source["title"],
                                               "revision": source["revision"], "page": page["page"],
                                               "start": start, "end": end, "quote": quote, "sha256": source["sha256"],
                                               "matched_terms": sorted(overlap), "rank_score": len(overlap)})
        candidates.sort(key=lambda c: (-c["rank_score"], c["source_id"], c["page"], c["start"]))
        return {"query": query, "course_id": course, "method": "chinese-bigram-english-token-v1",
                "notice": "仅词项检索候选，排序得分不是答案正确率；请核对原文是否支持疑问。",
                "candidates": candidates[:limit]}

    def state(self, course="os-default"):
        with self.lock:
            questions = self.rows("SELECT * FROM questions WHERE course_id=? ORDER BY rowid", (course,))
            for question in questions:
                question["answer"] = self.answer(question["current_answer_id"])
                question["conflict_drafts"] = self.rows("SELECT * FROM conflicts WHERE question_id=? ORDER BY rowid", (question["id"],))
            cards = self.rows("SELECT c.id,c.question_id,c.answer_id,c.prompt,c.status,c.due_at FROM cards c JOIN questions q ON q.id=c.question_id WHERE q.course_id=? ORDER BY c.rowid", (course,))
            attempts = self.rows("SELECT a.*,c.status AS card_status FROM attempts a JOIN cards c ON c.id=a.card_id JOIN questions q ON q.id=c.question_id WHERE q.course_id=? ORDER BY a.rowid", (course,))
            for attempt in attempts:
                attempt["feedback"] = json.loads(attempt["feedback"])
                attempt["confirmed"] = bool(attempt["confirmed"])
                attempt["source_warning"] = "依据已变更或撤回，请重新核对；历史作答仅供查看。" if attempt.pop("card_status") == "needs_review" else None
            return {"mode": "local-demo", "provider": self.provider.name, "course_id": course,
                    "limitations": ["仅单机开发演示，无账号认证或跨设备同步。", "固定案例摘录与冲突规则，未接入大模型，未验证通用语义支持。", "删除不覆盖客户端旧缓存或操作系统备份；客户端同步后应替换缓存。"],
                    "sources": self.sources(course), "questions": questions, "cards": cards, "attempts": attempts}

    def mutate(self, path, payload):
        if not isinstance(payload, dict):
            raise DomainError("invalid_input", "请求正文必须为JSON对象。")
        course = require_text(payload.get("course_id", "os-default"), "course_id", 100)
        request_id = require_text(payload.get("request_id"), "request_id", 128)
        try:
            fingerprint = hashlib.sha256(encode({"path": path, "payload": payload}).encode()).hexdigest()
        except UnicodeEncodeError:
            raise DomainError("invalid_input", "请求含不完整的Unicode字符，请重新输入。")
        with self.lock:
            prior = self.one("SELECT * FROM requests WHERE course_id=? AND request_id=?", (course, request_id))
            if prior:
                if prior["fingerprint"] != fingerprint:
                    raise DomainError("idempotency_conflict", "相同request_id已用于不同请求，请保留原请求或使用新的ID。", 409)
                cached = json.loads(prior["result"])
                if cached.get("error_code") == "question_revision_conflict":
                    question = self.entity("questions", cached["question_id"], course)
                    draft = self.one("SELECT * FROM conflicts WHERE id=?", (cached["conflict_id"],))
                    raise DomainError("question_revision_conflict", "疑问版本冲突；此前提交草稿仍保留。", 409,
                                      {"current_revision": question["revision"], "current_text": question["text"],
                                       "incoming_text": draft["incoming_text"], "conflict_id": draft["id"]})
                return {"result": dict(cached, replayed=True), "state": self.state(course)}
            try:
                self.db.execute("BEGIN IMMEDIATE")
                result = self.dispatch(path, payload, course)
                self.db.execute("INSERT INTO requests VALUES(?,?,?,?)", (course, request_id, fingerprint, encode(result)))
                self.db.commit()
            except DomainError as exc:
                # Optimistic question conflicts preserve the submitted draft atomically.
                if exc.code == "question_revision_conflict":
                    self.db.execute("INSERT INTO requests VALUES(?,?,?,?)", (course, request_id, fingerprint,
                                    encode({"error_code": exc.code, "question_id": path.split("/")[2], "conflict_id": exc.details["conflict_id"]})))
                    self.db.commit()
                else:
                    self.db.rollback()
                raise
            except Exception:
                self.db.rollback()
                raise
            return {"result": result, "state": self.state(course)}

    def check_revision(self, entity, payload):
        if type(payload.get("base_revision")) is not int or payload["base_revision"] != entity["revision"]:
            raise DomainError("revision_conflict", "资料版本已变化，请刷新后比较，禁止静默覆盖。", 409,
                              {"current_revision": entity["revision"], "supplied_revision": payload.get("base_revision")})

    def invalidate(self, source_id, deleted=False):
        evidence_ids = [r["id"] for r in self.rows("SELECT id FROM evidence WHERE source_id=?", (source_id,))]
        for evidence_id in evidence_ids:
            self.db.execute("UPDATE evidence SET status='needs_review' WHERE id=?", (evidence_id,))
            claims = self.rows("SELECT c.* FROM claims c JOIN claim_evidence ce ON ce.claim_id=c.id WHERE ce.evidence_id=?", (evidence_id,))
            for claim in claims:
                aid = claim["answer_id"]
                self.db.execute("UPDATE claims SET status='needs_review' WHERE id=?", (claim["id"],))
                self.db.execute("UPDATE answers SET status='needs_review' WHERE id=?", (aid,))
                self.db.execute("UPDATE cards SET status='needs_review',due_at=NULL WHERE answer_id=?", (aid,))
                self.db.execute("UPDATE questions SET state='needs_review' WHERE current_answer_id=?", (aid,))
                if deleted:
                    self.db.execute("UPDATE claims SET text='[依据已撤回]' WHERE id=?", (claim["id"],))
                    self.db.execute("UPDATE cards SET prompt='[依据已撤回，请重新生成自测]',rubric='[]' WHERE answer_id=?", (aid,))
                    self.db.execute("UPDATE attempts SET feedback=? WHERE card_id IN (SELECT id FROM cards WHERE answer_id=?)", (encode({"message": "依据已撤回，原反馈已清理；保留用户历史作答。", "reference_points": [], "automatic_grade": None}), aid))
        if deleted:
            self.db.execute("UPDATE revisions SET pages='[]',sha256='',deleted=1 WHERE source_id=?", (source_id,))
            self.db.execute("UPDATE evidence SET quote='',sha256='',start=0,end=0 WHERE source_id=?", (source_id,))

    def dispatch(self, path, payload, course):
        parts = [p for p in path.split("/") if p]
        if parts == ["sources"]:
            title = require_text(payload.get("title"), "资料标题", 200)
            pages, digest = parse_pages(payload)
            sid = uid()
            self.db.execute("INSERT INTO sources VALUES(?,?,?,?,0)", (sid, course, title, 1))
            self.db.execute("INSERT INTO revisions VALUES(?,?,?,?,0)", (sid, 1, encode(pages), digest))
            self.audit("source_imported", sid)
            return {"source_id": sid, "revision": 1}
        if len(parts) == 3 and parts[0] == "sources" and parts[2] in ("revisions", "delete"):
            source = self.entity("sources", parts[1], course)
            self.check_revision(source, payload)
            if source["deleted"]:
                raise DomainError("source_deleted", "资料已撤回，不能复用旧ID。", 410)
            if parts[2] == "delete":
                self.invalidate(source["id"], deleted=True)
                self.db.execute("UPDATE sources SET deleted=1,title='[已撤回资料]' WHERE id=?", (source["id"],))
                self.audit("source_deleted", source["id"])
                return {"source_id": source["id"], "deleted": True}
            pages, digest = parse_pages(payload)
            existing = self.one("SELECT sha256 FROM revisions WHERE source_id=? AND revision=?", (source["id"], source["revision"]))
            if digest == existing["sha256"]:
                return {"source_id": source["id"], "revision": source["revision"], "unchanged": True}
            revision = source["revision"] + 1
            self.db.execute("INSERT INTO revisions VALUES(?,?,?,?,0)", (source["id"], revision, encode(pages), digest))
            self.db.execute("UPDATE sources SET revision=? WHERE id=?", (revision, source["id"]))
            self.invalidate(source["id"])
            self.audit("source_revised", source["id"])
            return {"source_id": source["id"], "revision": revision}
        if parts == ["questions"]:
            question_id = uid()
            text = require_text(payload.get("text"), "疑问", 2000)
            self.db.execute("INSERT INTO questions VALUES(?,?,?,1,'recorded',NULL)", (question_id, course, text))
            return {"question_id": question_id}
        if len(parts) == 2 and parts[0] == "questions":
            question = self.entity("questions", parts[1], course)
            text = require_text(payload.get("text"), "疑问", 2000)
            if type(payload.get("base_revision")) is not int:
                raise DomainError("invalid_input", "base_revision必须是整数。")
            if payload["base_revision"] != question["revision"]:
                conflict_id = uid()
                self.db.execute("INSERT INTO conflicts VALUES(?,?,?,?,?)", (conflict_id, question["id"], payload["base_revision"], text, timestamp()))
                raise DomainError("question_revision_conflict", "疑问已被修改；原内容与当前提交草稿均保留，请刷新比较后再修改。", 409,
                                  {"current_revision": question["revision"], "current_text": question["text"], "incoming_text": text, "conflict_id": conflict_id})
            self.db.execute("UPDATE questions SET text=?,revision=revision+1,state='recorded',current_answer_id=NULL WHERE id=?", (text, question["id"]))
            self.db.execute("UPDATE cards SET status='needs_review',due_at=NULL WHERE question_id=?", (question["id"],))
            return {"question_id": question["id"], "revision": question["revision"] + 1}
        if len(parts) == 3 and parts[0] == "questions" and parts[2] == "explain":
            question = self.entity("questions", parts[1], course)
            proposal = self.provider.propose(question["text"], [s for s in self.sources(course) if not s["deleted"]])
            answer_id = uid()
            if proposal.status not in ("ready", "needs_material", "conflict"):
                raise DomainError("invalid_provider_output", "解释服务返回非法状态。", 422)
            if (proposal.conflict != (proposal.status == "conflict")
                    or (proposal.status == "needs_material" and proposal.evidence)
                    or not isinstance(proposal.missing, list)
                    or any(not isinstance(item, str) for item in proposal.missing)
                    or (proposal.status == "conflict" and len(proposal.evidence) < 2)):
                raise DomainError("invalid_provider_output", "解释状态、缺项或冲突引用相互矛盾，已拒绝写入。", 422)
            if proposal.status in ("ready", "conflict") and not proposal.evidence:
                raise DomainError("invalid_provider_output", "有结论时必须提供可校验引用。", 422)
            self.db.execute("INSERT INTO answers VALUES(?,?,?,?,?)", (answer_id, question["id"], proposal.status, encode(proposal.missing), self.provider.name))
            for candidate in proposal.evidence:
                self.validate_evidence(candidate, course)
                eid, cid = uid(), uid()
                status = "conflict" if proposal.conflict else "unconfirmed"
                self.db.execute("INSERT INTO evidence VALUES(?,?,?,?,?,?,?,?,?)", (eid, candidate.source_id, candidate.revision, candidate.page, candidate.start, candidate.end, candidate.quote, candidate.sha256, "current"))
                self.db.execute("INSERT INTO claims VALUES(?,?,?,?)", (cid, answer_id, candidate.quote, status))
                self.db.execute("INSERT INTO claim_evidence VALUES(?,?)", (cid, eid))
            # Superseding an explanation never leaves an older card silently active.
            self.db.execute("UPDATE cards SET status='needs_review',due_at=NULL WHERE question_id=?", (question["id"],))
            state = "needs_confirmation" if proposal.status == "ready" else "needs_material"
            self.db.execute("UPDATE questions SET current_answer_id=?,state=? WHERE id=?", (answer_id, state, question["id"]))
            result = {"answer_id": answer_id, "question_id": question["id"]}
            if proposal.status == "ready":
                prompt, rubric = quiz(proposal.has_example)
                card_id = uid()
                self.db.execute("INSERT INTO cards VALUES(?,?,?,?,?,'awaiting_attempt',NULL)", (card_id, question["id"], answer_id, prompt, encode(rubric)))
                result["card_id"] = card_id
            return result
        if len(parts) == 3 and parts[0] == "cards" and parts[2] in ("attempts", "confirm"):
            card = self.entity("cards", parts[1], course)
            if card["status"] == "needs_review":
                raise DomainError("source_needs_review", "来源或疑问已变更，旧卡已暂停。请重新找依据并生成自测。", 409)
            if parts[2] == "attempts":
                answer = require_text(payload.get("answer"), "独立作答", 10000)
                aid = uid()
                response = feedback(answer, json.loads(card["rubric"]))
                self.db.execute("INSERT INTO attempts VALUES(?,?,?,?,?,0,NULL)", (aid, card["id"], answer, encode(response), timestamp()))
                self.db.execute("UPDATE cards SET status='awaiting_confirmation',due_at=NULL WHERE id=?", (card["id"],))
                return {"attempt_id": aid, "card_id": card["id"]}
            attempt = self.one("SELECT * FROM attempts WHERE id=? AND card_id=?", (payload.get("attempt_id"), card["id"]))
            if not attempt:
                raise DomainError("attempt_required", "先提交独立作答，再对照依据确认。", 409)
            latest = self.one("SELECT id FROM attempts WHERE card_id=? ORDER BY rowid DESC LIMIT 1", (card["id"],))
            if attempt["id"] != latest["id"]:
                raise DomainError("stale_attempt", "请确认最近一次独立作答。", 409)
            rating = payload.get("self_rating")
            if rating not in ("again", "understood"):
                raise DomainError("invalid_input", "self_rating须为again或understood。")
            days = 1 if rating == "again" else 3
            due = (datetime.now(timezone.utc) + timedelta(days=days)).isoformat()
            self.db.execute("UPDATE attempts SET confirmed=1,self_rating=? WHERE id=?", (rating, attempt["id"]))
            self.db.execute("UPDATE cards SET status='reviewing',due_at=? WHERE id=?", (due, card["id"]))
            self.db.execute("UPDATE questions SET state='reviewing' WHERE id=?", (card["question_id"],))
            return {"card_id": card["id"], "attempt_id": attempt["id"], "due_at": due}
        raise DomainError("not_found", "接口不存在。", 404)

    def validate_evidence(self, candidate, course):
        source = self.entity("sources", candidate.source_id, course)
        if source["deleted"] or source["revision"] != candidate.revision:
            raise DomainError("invalid_evidence", "引用来自已撤回或非当前版本资料。", 422)
        revision = self.one("SELECT * FROM revisions WHERE source_id=? AND revision=?", (candidate.source_id, candidate.revision))
        pages = json.loads(revision["pages"])
        page = next((p for p in pages if p["page"] == candidate.page), None)
        if (not page or type(candidate.start) is not int or type(candidate.end) is not int
                or candidate.start < 0 or candidate.end <= candidate.start or candidate.end > len(page["text"])
                or page["text"][candidate.start:candidate.end] != candidate.quote
                or revision["sha256"] != candidate.sha256):
            raise DomainError("invalid_evidence", "引用页码、偏移、文本或版本摘要不匹配，已拒绝写入。", 422)

    def audit(self, event, identity):
        self.db.execute("INSERT INTO audit(event,entity_id,created_at) VALUES(?,?,?)", (event, identity, timestamp()))
