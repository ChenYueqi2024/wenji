"""Replaceable explanation boundary. This provider is deliberately NOT an LLM.

It recognizes one published synthetic example, returns literal source excerpts,
and refuses general questions. Retrieval relevance is never called confidence.
"""
from dataclasses import dataclass
import re

PROVIDER_NAME = "deterministic-extractive-v1"
SINGLE = "如果每类资源均只有一个实例，有向环意味着死锁。"
MULTI = "如果资源类型允许多个实例，图中有环并不足以判定死锁。"
EXAMPLE = "资源A有两个实例，B有一个。P1持有A的一个实例并等待B；P2持有B并等待A；P3持有A的另一个实例但不等待其他资源。P3可先结束释放A，P2获得A后完成并释放B，随后P1可继续。"
CONTRADICTION = "资源分配图有环就一定死锁，无论资源有多少个实例。"


@dataclass
class Candidate:
    source_id: str
    revision: int
    page: int
    start: int
    end: int
    quote: str
    sha256: str


@dataclass
class Claim:
    text: str
    evidence: list


@dataclass
class Proposal:
    status: str
    evidence: list
    missing: list
    conflict: bool = False
    has_example: bool = False
    claims: list | None = None


class ExtractiveDemoProvider:
    name = PROVIDER_NAME

    def public_info(self):
        return {"mode": "demo", "protocol": None, "model": None, "endpoint": None,
                "label": "确定性合成样例", "fingerprint": PROVIDER_NAME,
                "requires_consent": False}

    def propose(self, question, sources):
        normalized = re.sub(r"[\s，,？?。！!]", "", question)
        supported = {
            "资源分配图有环是否一定死锁", "资源分配图有环是不是一定死锁",
            "资源分配图有环是否一定会死锁", "资源分配图有环是否必然死锁",
        }
        if normalized not in supported:
            return Proposal("needs_material", [], [
                "当前仅实现‘资源分配图有环是否一定死锁’的合成案例规则。此问题尚无受验证的解释规则，请补充可核对资料并人工核对；未调用通用模型。"
            ])
        found = {}
        # Stable source/page order, literal offsets into normalized imported text.
        for source in sources:
            for page in source["pages"]:
                for key, fragment in [("single", SINGLE), ("multi", MULTI),
                                      ("example", EXAMPLE), ("contradiction", CONTRADICTION)]:
                    # PDF text often wraps inside a sentence. The regex only
                    # tolerates whitespace; offsets still point into stored text.
                    match = re.search(r"\s*".join(map(re.escape, fragment)), page["text"])
                    if match and key not in found:
                        found[key] = Candidate(source["id"], source["revision"], page["page"],
                                               match.start(), match.end(), match.group(), source["sha256"])
        # Deliberately scoped conflict detector; never advertise arbitrary contradiction detection.
        if "contradiction" in found and "multi" in found:
            return Proposal("conflict", [found["multi"], found["contradiction"]],
                            ["合成样例规则检测到不同说法；请核对适用条件和原文，暂不生成复习卡。"], True)
        missing = []
        if "single" not in found:
            missing.append("缺少单实例资源条件的原文依据。")
        if "multi" not in found:
            missing.append("缺少多实例资源条件的原文依据。")
        if missing:
            return Proposal("needs_material", [], missing)
        return Proposal("ready", [found[k] for k in ["single", "multi", "example"] if k in found],
                        [], has_example="example" in found)


def quiz(has_example):
    if has_example:
        return ("合成反例中为什么有环仍可能继续执行？请说明 P3、P2、P1 的推进顺序。",
                ["P3不等待其他资源，可先结束", "P3释放A供P2使用", "P2完成后释放B供P1使用"])
    return ("资源分配图有环时，单实例与多实例资源分别能得出什么结论？",
            ["每类资源只有一个实例时，有环意味着死锁", "允许多个实例时，不能只凭有环判定死锁"])


def feedback(answer, rubric):
    # No scores: a substring match is not a measurement of understanding.
    return {"mode": "reference-comparison", "message": "已保存独立作答。请逐条对照参考关键点，自行确认；系统未自动评分或判为已掌握。",
            "reference_points": rubric, "automatic_grade": None}
