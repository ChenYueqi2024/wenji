"""Independent HTTP/persistence smoke checks; never touches the user's database."""
import base64
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / 'reports' / 'http-smoke.json'
checks = []


def check(name, condition):
    if not condition:
        raise AssertionError(name)
    checks.append(name)
    print('PASS', name)


def main():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        port = probe.getsockname()[1]
    url = f'http://127.0.0.1:{port}'
    process = None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(path, data=None, expected=200):
        payload = None if data is None else json.dumps(data, ensure_ascii=False).encode()
        req = urllib.request.Request(url + path, data=payload,
                                     headers={'Content-Type': 'application/json'})
        try:
            response = opener.open(req, timeout=8)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            body = json.loads(response.read())
            if response.status != expected:
                raise AssertionError(f'{path}: expected {expected}, got {response.status}: {body}')
            return body

    def start(db):
        proc = subprocess.Popen([sys.executable, '-X', 'utf8', '-m', 'server', '--db',
                                 str(db), '--port', str(port)], cwd=ROOT,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        last_error = None
        for _ in range(20):
            if proc.poll() is not None:
                raise RuntimeError('Service exited before health check')
            try:
                request('/health')
                return proc
            except (OSError, ValueError) as error:
                last_error = error
                time.sleep(0.1)
        proc.terminate()
        proc.wait(timeout=10)
        raise TimeoutError(f'Service did not become healthy: {last_error}')

    with tempfile.TemporaryDirectory(prefix='wenji-http-') as temp:
        db = Path(temp) / 'smoke.sqlite3'
        try:
            process = start(db)
            check('real HTTP service health', bool(request('/health')))
            pdf = base64.b64encode((ROOT / 'fixtures/resource_graph_v1.pdf').read_bytes()).decode()
            imported = request('/sources', {'request_id': 'http-source', 'title': '资源图入门（合成）', 'pdf_base64': pdf})
            source_id = imported['result']['source_id']
            qbody = {'request_id': 'http-question', 'text': '资源分配图有环，是不是一定死锁？'}
            created = request('/questions', qbody)
            question_id = created['result']['question_id']
            replay = request('/questions', qbody)
            check('request replay returns same question', replay['result']['question_id'] == question_id)
            request('/questions', {**qbody, 'text': 'changed payload'}, expected=409)
            check('request id cannot silently change payload', True)
            explained = request(f'/questions/{question_id}/explain', {'request_id': 'http-explain'})
            state = explained['state']
            check('PDF example creates a review card', len(state['cards']) > 0)
            card_id = state['cards'][-1]['id']
            attempt = request(f'/cards/{card_id}/attempts', {'request_id': 'http-attempt', 'answer': '因为有多个实例'})
            attempt_id = attempt['result']['attempt_id']
            check('attempt is persisted', len(attempt['state']['attempts']) == 1)
            request(f'/cards/{card_id}/confirm', {'request_id': 'http-confirm', 'attempt_id': attempt_id, 'self_rating': 'again'})
            process.terminate()
            process.wait(timeout=10)
            process = start(db)
            persisted = request('/state?course_id=os-default')
            check('restart preserves question and attempt', len(persisted['questions']) == 1 and len(persisted['attempts']) == 1)
            v2 = (ROOT / 'fixtures/resource_graph_v2.txt').read_text(encoding='utf-8')
            revised = request(f'/sources/{source_id}/revisions', {'request_id': 'http-revise', 'base_revision': 1, 'text': v2})
            check('source revision pauses dependent card', revised['state']['cards'][0]['status'] == 'needs_review')
            request(f'/sources/{source_id}/revisions', {'request_id': 'http-stale', 'base_revision': 1, 'text': v2}, expected=409)
            check('stale source revision is rejected', True)
            request(f'/sources/{source_id}/delete', {'request_id': 'http-delete', 'base_revision': 2})
            replay = request(f'/questions/{question_id}/explain', {'request_id': 'http-explain'})
            check('idempotency replay cannot resurrect removed quotation', 'P3可先结束释放A' not in json.dumps(replay, ensure_ascii=False))
            bad_pdf = base64.b64encode(b'not a PDF').decode()
            request('/sources', {'request_id': 'http-bad-pdf', 'title': '坏文件', 'pdf_base64': bad_pdf}, expected=422)
            check('malformed PDF has an explicit parse error', True)
        finally:
            if process and process.poll() is None:
                process.terminate()
                process.wait(timeout=10)


if __name__ == '__main__':
    REPORT.parent.mkdir(exist_ok=True)
    try:
        main()
    except Exception as error:
        REPORT.write_text(json.dumps({'passed': checks, 'failed': str(error), 'status': 'FAIL'}, ensure_ascii=False, indent=2), encoding='utf-8')
        raise
    REPORT.write_text(json.dumps({'passed': checks, 'count': len(checks), 'status': 'PASS', 'scope': 'Local HTTP + SQLite synthetic data; no Android, model, performance or user trial claims'}, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'{len(checks)} HTTP checks passed; report: {REPORT}')
