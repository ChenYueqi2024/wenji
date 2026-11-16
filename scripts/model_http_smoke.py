"""Four local fake-provider HTTP end-to-end scenarios; NEVER calls a real model."""
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sys
import tempfile
import threading
import uuid
from urllib.error import HTTPError
from urllib.request import build_opener, ProxyHandler, Request

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server.__main__ import handler_class
from server.core import Repository
from server.model_gateway import load_provider, PROTOCOLS


class FakeModel(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        self.server.calls += 1
        if self.path.endswith('/responses'):
            prompt = body['input']
        elif ':generateContent' in self.path:
            prompt = body['contents'][0]['parts'][0]['text']
        else:
            prompt = body['messages'][-1]['content']
        evidence = json.loads(prompt)['evidence']
        answer = json.dumps({'status': 'ready', 'claims': [{'text': '合成协议测试：队列先进先出。',
                            'evidence_ids': [evidence[0]['evidence_id']]}], 'missing_information': []}, ensure_ascii=False)
        if self.path.endswith('/chat/completions'):
            result = {'choices': [{'finish_reason': 'stop', 'message': {'content': answer}}]}
        elif self.path.endswith('/responses'):
            result = {'status': 'completed', 'output': [{'type': 'message', 'role': 'assistant', 'status': 'completed',
                      'content': [{'type': 'output_text', 'text': answer}]}]}
        elif self.path.endswith('/messages'):
            result = {'stop_reason': 'end_turn', 'content': [{'type': 'text', 'text': answer}]}
        else:
            result = {'candidates': [{'finishReason': 'STOP', 'content': {'parts': [{'text': answer}]}}]}
        data = json.dumps(result, ensure_ascii=False).encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)


@contextmanager
def serving(handler):
    server = ThreadingHTTPServer(('127.0.0.1', 0), handler)
    server.calls = 0
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server, f'http://127.0.0.1:{server.server_port}'
    finally:
        server.shutdown()
        server.server_close()
        thread.join(5)


def request(url, body=None, expected=200):
    encoded = json.dumps(body).encode('utf-8') if body is not None else None
    req = Request(url, encoded, {'Content-Type': 'application/json'})
    try:
        response = build_opener(ProxyHandler({})).open(req, timeout=10)
    except HTTPError as exc:
        response = exc
    with response:
        assert response.code == expected, (response.code, expected)
        return json.loads(response.read())


def main():
    results = []
    with tempfile.TemporaryDirectory(prefix='wenji-protocol-test-') as folder, serving(FakeModel) as (fake, fake_url):
        for protocol in sorted(PROTOCOLS):
            config = Path(folder) / 'model.local.json'
            config.write_text(json.dumps({'protocol': protocol, 'base_url': fake_url + '/v1', 'model': 'synthetic-only',
                                         'auth_mode': 'none', 'label': '合成HTTP协议测试，非真实模型'}), encoding='utf-8')
            repo = Repository(Path(folder) / (protocol + '.sqlite3'), provider=load_provider(config))
            try:
                with serving(handler_class(repo)) as (_, url):
                    def post(path, **body):
                        body.setdefault('request_id', uuid.uuid4().hex)
                        return request(url + path, body)
                    info = request(url + '/health')['provider_info']
                    assert info['mode'] == 'llm' and info['protocol'] == protocol
                    sid = post('/sources', title='原创合成说明', text='队列按照先进先出的顺序处理元素。')['result']['source_id']
                    qid = post('/questions', text='队列的处理顺序是什么？')['result']['question_id']
                    before = fake.calls
                    rejected = request(url + f'/questions/{qid}/explain', {'request_id': uuid.uuid4().hex}, expected=409)
                    assert rejected['error']['code'] == 'model_consent_required' and fake.calls == before
                    payload = {'request_id': uuid.uuid4().hex, 'allow_remote': True,
                               'provider_fingerprint': info['fingerprint'], 'question_revision': 1}
                    result = request(url + f'/questions/{qid}/explain', payload)
                    answer = result['state']['questions'][0]['answer']
                    assert answer['status'] == 'ready' and answer['claims'][0]['text'].startswith('合成协议测试')
                    citation = answer['claims'][0]['evidence'][0]
                    assert citation['source_id'] == sid and citation['revision'] == 1 and citation['page'] == 1
                    assert 'rubric' not in result['state']['cards'][0]
                    repeated = request(url + f'/questions/{qid}/explain', payload)
                    assert repeated['result']['replayed'] and fake.calls == before + 1
                    revised = post(f'/sources/{sid}/revisions', base_revision=1, text='队列采用先进先出，栈采用后进先出。')
                    assert revised['state']['questions'][0]['answer']['status'] == 'needs_review'
                    assert revised['state']['cards'][0]['status'] == 'needs_review'
                    results.append({'protocol': protocol, 'result': 'PASS', 'model_requests': fake.calls - before})
            finally:
                repo.close()
    print(json.dumps({'scope': 'local fake model HTTP only; no paid or real LLM calls', 'scenarios': results}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
