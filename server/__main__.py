"""Run: python -m server --db data/wenji.sqlite3 --port 8765"""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from .core import DomainError, Repository


def handler_class(repo):
    class Handler(BaseHTTPRequestHandler):
        server_version = "WenjiLocal/0.1"

        def log_message(self, format, *args):
            # Never log imported body, questions, identifiers or request path.
            pass

        def reply(self, status, value):
            body = json.dumps(value, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(body)

        def safe(self, operation):
            try:
                # Reject DNS-rebinding hosts. The emulator host alias is a
                # supported local-development connection, never LAN binding.
                host = self.headers.get("Host", "").split(":")[0].lower()
                if host not in ("127.0.0.1", "localhost", "10.0.2.2"):
                    raise DomainError("host_rejected", "仅接受本机开发地址。", 403)
                self.reply(200, operation())
            except DomainError as error:
                self.reply(error.status, {"error": {"code": error.code, "message": error.message, "details": error.details}})
            except Exception:
                # Keep server paths and imported content out of error responses.
                self.reply(500, {"error": {"code": "internal_error", "message": "本地服务处理失败，请保留请求ID后重试。"}})

        def do_GET(self):
            def get():
                url = urlsplit(self.path)
                if url.path == "/health":
                    return {"status": "ok", "mode": "local-demo", "provider": repo.provider.name, "authentication": "none-loopback-development-only"}
                if url.path == "/state":
                    return repo.state(parse_qs(url.query).get("course_id", ["os-default"])[0])
                if url.path == "/search":
                    query = parse_qs(url.query)
                    return repo.search(query.get("q", [""])[0], query.get("course_id", ["os-default"])[0])
                raise DomainError("not_found", "接口不存在。", 404)
            self.safe(get)

        def do_POST(self):
            def post():
                # Android native client / curl do not send Origin. Browser pages
                # cannot use this unauthenticated local API via a CORS preflight.
                if self.headers.get("Origin"):
                    raise DomainError("origin_rejected", "本地开发接口不接受网页跨源请求。", 403)
                if self.headers.get_content_type() != "application/json":
                    raise DomainError("unsupported_media_type", "Content-Type须为application/json。", 415)
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    if length <= 0 or length > 7_100_000:
                        raise ValueError()
                except ValueError:
                    raise DomainError("invalid_body_size", "请求为空或超过7.1MB。", 413)
                try:
                    payload = json.loads(self.rfile.read(length).decode("utf-8"))
                except (ValueError, UnicodeDecodeError):
                    raise DomainError("invalid_json", "JSON或UTF-8编码不合法。")
                return repo.mutate(urlsplit(self.path).path, payload)
            self.safe(post)
    return Handler


def main():
    parser = argparse.ArgumentParser(description="问迹本地开发服务（无账号认证，仅回环监听）")
    parser.add_argument("--db", default="data/wenji.sqlite3")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    repo = Repository(args.db)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler_class(repo))
    print(f"Wenji local demo: http://127.0.0.1:{args.port} (no LLM, no accounts)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        repo.close()


if __name__ == "__main__":
    main()
