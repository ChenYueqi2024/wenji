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
CREATE TABLE IF NOT EXISTS answer_provenance(answer_id TEXT PRIMARY KEY REFERENCES answers(id), remote_called INTEGER NOT NULL CHECK(remote_called IN (0,1)));
CREATE TABLE IF NOT EXISTS general_references(id TEXT PRIMARY KEY, question_id TEXT NOT NULL REFERENCES questions(id), question_revision INTEGER NOT NULL, text TEXT NOT NULL, provider TEXT NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS answer_context(answer_id TEXT NOT NULL REFERENCES answers(id), source_id TEXT NOT NULL REFERENCES sources(id), revision INTEGER NOT NULL, PRIMARY KEY(answer_id,source_id));
CREATE TABLE IF NOT EXISTS evidence(id TEXT PRIMARY KEY, source_id TEXT NOT NULL, revision INTEGER NOT NULL, page INTEGER NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, quote TEXT NOT NULL, sha256 TEXT NOT NULL, status TEXT NOT NULL, FOREIGN KEY(source_id,revision) REFERENCES revisions(source_id,revision));
CREATE TABLE IF NOT EXISTS claims(id TEXT PRIMARY KEY, answer_id TEXT NOT NULL REFERENCES answers(id), text TEXT NOT NULL, status TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS claim_evidence(claim_id TEXT NOT NULL REFERENCES claims(id), evidence_id TEXT NOT NULL REFERENCES evidence(id), PRIMARY KEY(claim_id,evidence_id));
CREATE INDEX IF NOT EXISTS evidence_source ON evidence(source_id);
CREATE INDEX IF NOT EXISTS dependency_evidence ON claim_evidence(evidence_id);
CREATE TABLE IF NOT EXISTS cards(id TEXT PRIMARY KEY, question_id TEXT NOT NULL REFERENCES questions(id), answer_id TEXT NOT NULL REFERENCES answers(id), prompt TEXT NOT NULL, rubric TEXT NOT NULL, status TEXT NOT NULL, due_at TEXT);
CREATE TABLE IF NOT EXISTS general_reference_cards(reference_id TEXT PRIMARY KEY REFERENCES general_references(id), card_id TEXT UNIQUE NOT NULL REFERENCES cards(id), superseded_by_answer_id TEXT REFERENCES answers(id));
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
        self.generating = set()
        # Existing 0.4.2 references were saved before uncited review cards were
        # requested. Backfill from local text only, atomically and idempotently.
        try:
            with self.db:
                self.db.execute("BEGIN IMMEDIATE")
                for reference in self.rows("SELECT r.* FROM general_references r LEFT JOIN general_reference_cards g ON g.reference_id=r.id WHERE g.reference_id IS NULL ORDER BY r.rowid"):
                    question = self.one("SELECT * FROM questions WHERE id=?", (reference["question_id"],))
                    self.ensure_reference_card(reference, question)
        except Exception:
            self.db.close()
            raise

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

    def card_basis(self, card_id, status=None):
        link = self.one("SELECT * FROM general_reference_cards WHERE card_id=?", (card_id,))
        if not link:
            return {"basis": "course_evidence", "reference_id": None, "superseded_by_answer_id": None,
                    "basis_notice": "依据课程资料生成；仍需核对引用与适用条件。"}
        notice = "通用参考复习 · 尚无课程依据，模型内容可能有误；自评不代表已掌握课程知识。"
        if link["superseded_by_answer_id"]:
            notice += " 已生成资料版，此卡保留为历史，不再安排复习。"
        elif status == "needs_review":
            notice += " 问题或参考已更新，此卡已暂停。"
        return {"basis": "general_reference", "reference_id": link["reference_id"],
                "superseded_by_answer_id": link["superseded_by_answer_id"], "basis_notice": notice}

    def answer(self, answer_id):
        answer = self.one("SELECT * FROM answers WHERE id=?", (answer_id,))
        if not answer:
            return None
        answer["missing_information"] = json.loads(answer.pop("missing"))
        provenance = self.one("SELECT remote_called FROM answer_provenance WHERE answer_id=?", (answer_id,))
        answer["remote_called"] = (bool(provenance["remote_called"]) if provenance else
                                   False if answer["provider"] == "deterministic-extractive-v1" else None)
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
                reference = self.one("SELECT * FROM general_references WHERE question_id=? ORDER BY rowid DESC LIMIT 1", (question["id"],))
                if reference:
                    link = self.one("SELECT * FROM general_reference_cards WHERE reference_id=?", (reference["id"],))
                    reference.update(kind="general_knowledge",
                                     status=("needs_review" if reference["question_revision"] != question["revision"] else
                                             "superseded" if link and link["superseded_by_answer_id"] else "unverified"),
                                     card_id=link["card_id"] if link else None,
                                     superseded_by_answer_id=link["superseded_by_answer_id"] if link else None,
                                     notice="通用知识参考 · 无课程原文依据，可能有误，请核对")
                question["reference"] = reference
                question["conflict_drafts"] = self.rows("SELECT * FROM conflicts WHERE question_id=? ORDER BY rowid", (question["id"],))
            cards = self.rows("SELECT c.id,c.question_id,c.answer_id,c.prompt,c.status,c.due_at FROM cards c JOIN questions q ON q.id=c.question_id WHERE q.course_id=? ORDER BY c.rowid", (course,))
            for card in cards:
                card.update(self.card_basis(card["id"], card["status"]))
            attempts = self.rows("SELECT a.*,c.status AS card_status FROM attempts a JOIN cards c ON c.id=a.card_id JOIN questions q ON q.id=c.question_id WHERE q.course_id=? ORDER BY a.rowid", (course,))
            for attempt in attempts:
                attempt["feedback"] = json.loads(attempt["feedback"])
                attempt["confirmed"] = bool(attempt["confirmed"])
                status = attempt.pop("card_status")
                basis = self.card_basis(attempt["card_id"], status)
                attempt.update(basis)
                attempt["source_warning"] = (basis["basis_notice"] if basis["basis"] == "general_reference" else
                                             "依据已变更或撤回，请重新核对；历史作答仅供查看。" if status == "needs_review" else None)
            info = self.provider_info()
            return {"mode": "local-llm" if info["mode"] == "llm" else "local-demo", "provider": self.provider.name, "provider_info": info, "course_id": course,
                    "limitations": ["仅单机开发预览，无账号认证或跨设备同步。", "模型解释及引用语义仍需人工核对；结构校验不证明内容正确。" if info["mode"] == "llm" else "固定案例摘录与冲突规则，未调用大模型。", "删除不覆盖已发送给供应商的内容、客户端旧缓存或操作系统备份。"],
                    "sources": self.sources(course), "questions": questions, "cards": cards, "attempts": attempts}

    def provider_info(self):
        if hasattr(self.provider, "public_info"):
            return self.provider.public_info()
        return {"mode": "demo", "label": "固定案例规则演示", "protocol": "demo", "model": "", "endpoint": "",
                "fingerprint": self.provider.name, "requires_consent": False}

    def replay(self, course, request_id, fingerprint):
        prior = self.one("SELECT * FROM requests WHERE course_id=? AND request_id=?", (course, request_id))
        if not prior:
            return None
        if prior["fingerprint"] != fingerprint:
            raise DomainError("idempotency_conflict", "相同request_id已用于不同请求，请保留原请求或使用新的ID。", 409)
        cached = json.loads(prior["result"])
        if cached.get("error_code") == "question_revision_conflict":
            question = self.entity("questions", cached["question_id"], course)
            draft = self.one("SELECT * FROM conflicts WHERE id=?", (cached["conflict_id"],))
            raise DomainError("question_revision_conflict", "疑问版本冲突；此前提交草稿仍保留。", 409,
                              {"current_revision": question["revision"], "current_text": question["text"],
                               "incoming_text": draft["incoming_text"], "conflict_id": draft["id"]})
        if cached.get("error_code"):
            raise DomainError(cached["error_code"], cached["message"], cached.get("http_status", 409))
        return {"result": dict(cached, replayed=True), "state": self.state(course)}

    def mutate(self, path, payload):
        if not isinstance(payload, dict):
            raise DomainError("invalid_input", "请求正文必须为JSON对象。")
        course = require_text(payload.get("course_id", "os-default"), "course_id", 100)
        request_id = require_text(payload.get("request_id"), "request_id", 128)
        try:
            fingerprint = hashlib.sha256(encode({"path": path, "payload": payload}).encode()).hexdigest()
        except UnicodeEncodeError:
            raise DomainError("invalid_input", "请求含不完整的Unicode字符，请重新输入。")
        parts = [p for p in path.split("/") if p]
        if len(parts) == 3 and parts[0] == "questions" and parts[2] in ("explain", "reference"):
            return self.explain(parts[1], payload, course, request_id, fingerprint, general=parts[2] == "reference")
        with self.lock:
            prior = self.replay(course, request_id, fingerprint)
            if prior:
                return prior
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

    def explain(self, question_id, payload, course, request_id, fingerprint, *, general=False):
        from .model_gateway import ProviderError
        key = (course, question_id)
        with self.lock:
            prior = self.replay(course, request_id, fingerprint)
            if prior:
                return prior
            question = self.entity("questions", question_id, course)
            provider = self.provider
            info = self.provider_info()
            if general:
                if info.get("mode") != "llm" or not callable(getattr(provider, "general_reference", None)):
                    raise DomainError("general_reference_unavailable", "通用知识参考需要先配置模型 API；规则演示不支持此功能。", 400)
                if payload.get("purpose") != "general_reference":
                    raise DomainError("general_reference_consent_required", "请单独确认生成无课程原文依据的通用知识参考。", 409)
            if info.get("requires_consent") and (payload.get("allow_remote") is not True
                    or payload.get("provider_fingerprint") != info["fingerprint"]):
                raise DomainError("model_consent_required", "模型配置已变更或尚未确认发送。请同步后重新确认供应商、模型与资料发送范围。", 409)
            if info["mode"] == "llm" and (type(payload.get("question_revision")) is not int
                    or payload["question_revision"] != question["revision"]):
                raise DomainError("generation_question_changed", "疑问已变化或缺少已确认的版本，请同步后重新确认本次问题。", 409)
            if key in self.generating or len(self.generating) >= 2:
                raise DomainError("generation_busy", "已有解释正在生成，请稍后同步查看结果。", 409)
            # General references never receive imported material, even when it
            # exists; course-backed generation retains its original snapshot.
            sources = [] if general else [s for s in self.sources(course) if not s["deleted"]]
            snapshot = [(s["id"], s["revision"], s["sha256"]) for s in sources]
            previous_reference = self.one("SELECT id FROM general_references WHERE question_id=? ORDER BY rowid DESC LIMIT 1", (question_id,)) if general else None
            # Persist a reservation BEFORE sending anything: an ambiguous timeout,
            # process crash or duplicate request must not automatically spend twice.
            reserved = {"error_code": "generation_in_progress", "message": "此解释请求已发出或被中断，请先同步检查；如仍无结果，停止本地重试后重新确认生成。", "http_status": 409}
            with self.db:
                self.db.execute("INSERT INTO requests VALUES(?,?,?,?)", (course, request_id, fingerprint, encode(reserved)))
            self.generating.add(key)
        try:
            # Network I/O holds neither the SQLite transaction nor the repository lock.
            try:
                proposal = provider.general_reference(question["text"]) if general else provider.propose(question["text"], sources)
            except ProviderError as exc:
                raise DomainError(exc.code, exc.message, exc.status)
            except Exception:
                raise DomainError("provider_failed", "解释服务未完成；未保存生成内容。请核对模型配置后停止旧重试，再重新发起。", 502)
            with self.lock:
                try:
                    self.db.execute("BEGIN IMMEDIATE")
                    current = self.entity("questions", question_id, course)
                    current_sources = [] if general else [(s["id"], s["revision"], s["sha256"]) for s in self.sources(course) if not s["deleted"]]
                    current_reference = self.one("SELECT id FROM general_references WHERE question_id=? ORDER BY rowid DESC LIMIT 1", (question_id,)) if general else None
                    if (current["revision"] != question["revision"] or current["current_answer_id"] != question["current_answer_id"]
                            or current_reference != previous_reference
                            or current_sources != snapshot or self.provider is not provider
                            or self.provider_info()["fingerprint"] != info["fingerprint"]):
                        raise DomainError("generation_context_changed", "生成期间疑问、资料或模型配置已变更；返回内容已丢弃，请同步后重新生成。", 409)
                    result = (self.store_reference(question, proposal, provider.name) if general else
                              self.store_proposal(question, proposal, provider.name, course, info["mode"] == "llm", sources))
                    self.db.execute("UPDATE requests SET result=? WHERE course_id=? AND request_id=?", (encode(result), course, request_id))
                    self.db.commit()
                except Exception:
                    self.db.rollback()
                    raise
                return {"result": result, "state": self.state(course)}
        except Exception as exc:
            error = exc if isinstance(exc, DomainError) else DomainError("invalid_provider_output", "模型输出不符合结构或引用要求，已拒绝保存。", 422)
            # Store sanitized terminal errors; retries return them without model calls.
            with self.lock, self.db:
                self.db.execute("UPDATE requests SET result=? WHERE course_id=? AND request_id=?",
                                (encode({"error_code": error.code, "message": error.message, "http_status": error.status}), course, request_id))
            raise error
        finally:
            with self.lock:
                self.generating.discard(key)

    def store_reference(self, question, text, provider_name):
        # Reference review is separately labelled and never promoted into a
        # cited claim or the question's course-backed current_answer_id.
        text = require_text(text, "通用知识参考", 12000)
        identity = uid()
        self.db.execute("INSERT INTO general_references VALUES(?,?,?,?,?,?)",
                        (identity, question["id"], question["revision"], text, provider_name, timestamp()))
        self.audit("general_reference_generated", identity)
        self.db.execute("UPDATE cards SET status='needs_review',due_at=NULL WHERE question_id=? AND id IN (SELECT card_id FROM general_reference_cards)", (question["id"],))
        reference = self.one("SELECT * FROM general_references WHERE id=?", (identity,))
        card_id = self.ensure_reference_card(reference, question)
        return {"reference_id": identity, "card_id": card_id, "question_id": question["id"], "kind": "general_knowledge"}

    def ensure_reference_card(self, reference, question):
        existing = self.one("SELECT card_id FROM general_reference_cards WHERE reference_id=?", (reference["id"],))
        if existing:
            return existing["card_id"]
        text = require_text(reference["text"], "通用知识参考", 12000)
        latest = self.one("SELECT id FROM general_references WHERE question_id=? ORDER BY rowid DESC LIMIT 1", (question["id"],))
        current = self.one("SELECT id,status FROM answers WHERE id=?", (question["current_answer_id"],))
        superseded = current["id"] if current and current["status"] == "ready" else None
        stale = (reference["question_revision"] != question["revision"] or latest["id"] != reference["id"] or superseded)
        answer_id, card_id = uid(), uid()
        self.db.execute("INSERT INTO answers VALUES(?,?,?,'[]',?)", (answer_id, question["id"], "general_reference", reference["provider"]))
        # These private internal answer rows have no claims, evidence or source
        # context. Their sole purpose is compatibility with the cards FK.
        prompt = ("请先独立回答：" + question["text"] if reference["question_revision"] == question["revision"] else
                  "旧问题的通用参考复习（问题已修改，仅供历史查看）")
        self.db.execute("INSERT INTO cards VALUES(?,?,?,?,?,?,NULL)",
                        (card_id, question["id"], answer_id, prompt, encode([text]), "needs_review" if stale else "awaiting_attempt"))
        self.db.execute("INSERT INTO general_reference_cards VALUES(?,?,?)", (reference["id"], card_id, superseded))
        return card_id

    def store_proposal(self, question, proposal, provider_name, course, is_llm=False, sources=()):
        if proposal.status not in ("ready", "needs_material", "conflict"):
            raise DomainError("invalid_provider_output", "解释服务返回非法状态。", 422)
        if (proposal.conflict != (proposal.status == "conflict")
                or (proposal.status == "needs_material" and proposal.evidence)
                or not isinstance(proposal.missing, list) or len(proposal.missing) > 10
                or any(not isinstance(item, str) for item in proposal.missing)
                or (proposal.status == "conflict" and len(proposal.evidence) < 2)):
            raise DomainError("invalid_provider_output", "解释状态、缺项或冲突引用相互矛盾，已拒绝写入。", 422)
        for item in proposal.missing:
            require_text(item, "模型缺项说明", 2000)
        proposed_claims = getattr(proposal, "claims", None)
        pairs = [(c.text, c.evidence) for c in proposed_claims] if proposed_claims is not None else [(c.quote, [c]) for c in proposal.evidence]
        if (len(pairs) > 12 or (proposal.status == "needs_material" and pairs)
                or (proposal.status in ("ready", "conflict") and (not pairs or not proposal.evidence))):
            raise DomainError("invalid_provider_output", "有结论时必须提供可校验引用。", 422)
        if proposal.status == "conflict":
            distinct = {(e.source_id, e.revision, e.page, e.start, e.end) for _, refs in pairs for e in refs}
            if len(pairs) < 2 or len(distinct) < 2:
                raise DomainError("invalid_provider_output", "冲突须并列至少两条说法及不同引用，已拒绝保存。", 422)
        answer_id = uid()
        self.db.execute("INSERT INTO answers VALUES(?,?,?,?,?)", (answer_id, question["id"], proposal.status, encode(proposal.missing), provider_name))
        remote_called = getattr(proposal, "remote_called", None) if is_llm else False
        if type(remote_called) is bool:
            self.db.execute("INSERT INTO answer_provenance VALUES(?,?)", (answer_id, int(remote_called)))
        if is_llm:
            self.db.executemany("INSERT INTO answer_context VALUES(?,?,?)", [(answer_id, s["id"], s["revision"]) for s in sources])
        for text, evidence in pairs:
            require_text(text, "模型结论", 3000)
            if not isinstance(evidence, list) or not evidence or len(evidence) > 8:
                raise DomainError("invalid_provider_output", "每条结论必须关联有效引用。", 422)
            cid = uid()
            self.db.execute("INSERT INTO claims VALUES(?,?,?,?)", (cid, answer_id, text, "conflict" if proposal.conflict else "unconfirmed"))
            for candidate in evidence:
                if candidate not in proposal.evidence:
                    raise DomainError("invalid_provider_output", "结论引用不属于本次候选依据。", 422)
                self.validate_evidence(candidate, course)
                eid = uid()
                self.db.execute("INSERT INTO evidence VALUES(?,?,?,?,?,?,?,?,?)", (eid, candidate.source_id, candidate.revision, candidate.page, candidate.start, candidate.end, candidate.quote, candidate.sha256, "current"))
                self.db.execute("INSERT INTO claim_evidence VALUES(?,?)", (cid, eid))
        # A failed/insufficient course answer must not erase an uncited review
        # flow. Only a ready course answer supersedes general references.
        self.db.execute("UPDATE cards SET status='needs_review',due_at=NULL WHERE question_id=? AND id NOT IN (SELECT card_id FROM general_reference_cards)", (question["id"],))
        state = "needs_confirmation" if proposal.status == "ready" else "needs_material"
        self.db.execute("UPDATE questions SET current_answer_id=?,state=? WHERE id=?", (answer_id, state, question["id"]))
        result = {"answer_id": answer_id, "question_id": question["id"]}
        if proposal.status == "ready":
            self.db.execute("UPDATE cards SET status='needs_review',due_at=NULL WHERE question_id=? AND id IN (SELECT card_id FROM general_reference_cards)", (question["id"],))
            self.db.execute("UPDATE general_reference_cards SET superseded_by_answer_id=? WHERE card_id IN (SELECT id FROM cards WHERE question_id=?) AND superseded_by_answer_id IS NULL", (answer_id, question["id"]))
            prompt, rubric = ("请先独立回答：" + question["text"], [text for text, _ in pairs]) if is_llm else quiz(proposal.has_example)
            card_id = uid()
            self.db.execute("INSERT INTO cards VALUES(?,?,?,?,?,'awaiting_attempt',NULL)", (card_id, question["id"], answer_id, prompt, encode(rubric)))
            result["card_id"] = card_id
        return result

    def check_revision(self, entity, payload):
        if type(payload.get("base_revision")) is not int or payload["base_revision"] != entity["revision"]:
            raise DomainError("revision_conflict", "资料版本已变化，请刷新后比较，禁止静默覆盖。", 409,
                              {"current_revision": entity["revision"], "supplied_revision": payload.get("base_revision")})

    def invalidate(self, source_id, deleted=False):
        # A model may base missing/conflict statements or paraphrases on context
        # it did not cite. Conservatively invalidate all of that answer's context.
        for row in self.rows("SELECT answer_id FROM answer_context WHERE source_id=?", (source_id,)):
            aid = row["answer_id"]
            self.db.execute("UPDATE answers SET status='needs_review' WHERE id=?", (aid,))
            self.db.execute("UPDATE claims SET status='needs_review' WHERE answer_id=?", (aid,))
            self.db.execute("UPDATE cards SET status='needs_review',due_at=NULL WHERE answer_id=?", (aid,))
            self.db.execute("UPDATE questions SET state='needs_review' WHERE current_answer_id=?", (aid,))
            if deleted:
                self.db.execute("UPDATE answers SET missing='[]' WHERE id=?", (aid,))
                self.db.execute("UPDATE claims SET text='[依据已撤回]' WHERE answer_id=?", (aid,))
                self.db.execute("UPDATE cards SET prompt='[依据已撤回，请重新生成自测]',rubric='[]' WHERE answer_id=?", (aid,))
                self.db.execute("UPDATE attempts SET feedback=? WHERE card_id IN (SELECT id FROM cards WHERE answer_id=?)",
                                (encode({"message": "依据已撤回，原反馈已清理；保留用户历史作答。", "reference_points": [], "automatic_grade": None}), aid))
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
        if len(parts) == 3 and parts[0] == "cards" and parts[2] in ("attempts", "confirm"):
            card = self.entity("cards", parts[1], course)
            basis = self.card_basis(card["id"], card["status"])
            if card["status"] == "needs_review":
                raise DomainError("source_needs_review", "问题、参考或依据已更新，旧卡已暂停。请使用当前复习卡；历史作答仍保留。", 409)
            if parts[2] == "attempts":
                answer = require_text(payload.get("answer"), "独立作答", 10000)
                aid = uid()
                response = feedback(answer, json.loads(card["rubric"]))
                response["basis"] = basis["basis"]
                if basis["basis"] == "general_reference":
                    response.update(mode="general-reference-comparison",
                                    message="已保存独立作答。以下为模型通用参考全文，尚无课程依据，可能有误；请自行对照，未自动评分或认定课程知识已掌握。")
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
            if basis["basis"] != "general_reference":
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
