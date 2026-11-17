"""In-process Android entry point; no server, sockets, config files or globals.

Chaquopy creates one MobileEngine for the app-private SQLite database. Arguments
and replies cross the Kotlin/Python boundary as JSON strings. Only an explicit
None config selects fixed-case demo; corrupt saved config must fail closed.
"""
import json
from urllib.parse import parse_qs, urlsplit

from .core import DomainError, Repository
from .model_gateway import ProviderError, _invalid_config, _strict_json, load_provider_config


def _reply(status, body):
    return json.dumps({"status": status, "body": body}, ensure_ascii=False, allow_nan=False)


def _error(error):
    value = {"code": error.code, "message": error.message}
    if isinstance(error, DomainError):
        value["details"] = error.details
    return _reply(error.status, {"error": value})


def _provider(config_json, secrets_json):
    try:
        if not isinstance(secrets_json, str) or len(secrets_json.encode("utf-8")) > 65536:
            raise _invalid_config()
        secrets = _strict_json(secrets_json)
        if (not isinstance(secrets, dict)
                or any(not isinstance(k, str) or not isinstance(v, str) for k, v in secrets.items())):
            raise _invalid_config()
        if config_json is None:
            return load_provider_config(None, environ=secrets)
        if not isinstance(config_json, str) or len(config_json.encode("utf-8")) > 65536:
            raise _invalid_config()
        config = _strict_json(config_json)
        if not isinstance(config, dict):
            raise _invalid_config()
        return load_provider_config(config, environ=secrets)
    except ProviderError:
        raise
    except (ValueError, TypeError, RecursionError):
        raise _invalid_config() from None


class MobileEngine:
    def __init__(self, db_path, config_json=None, secrets_json="{}"):
        # Validate before opening SQLite: failed restoration cannot leave behind
        # a usable demo engine which could be mistaken for the configured model.
        provider = _provider(config_json, secrets_json)
        self.repo = Repository(db_path, provider=provider)
        self._closed = False

    def _ensure_open(self):
        if self._closed:
            raise DomainError("engine_closed", "手机本地引擎已关闭，请重新打开应用。", 503)

    def _state(self, course="os-default"):
        state = self.repo.state(course)
        state["runtime"] = "android-in-process"
        state["limitations"][0] = "资料与学习记录保存在本机应用内，无跨设备同步。"
        return state

    def configure(self, config_json=None, secrets_json="{}"):
        """Validate then atomically swap provider; failed edits keep valid config.

        Existing answers retain their recorded provenance. Consent fingerprints
        change with model settings or keys; core rejects obsolete consent and
        discards any reply from a provider replaced during an in-flight call.
        """
        try:
            provider = _provider(config_json, secrets_json)
            with self.repo.lock:
                self._ensure_open()
                self.repo.provider = provider
                return _reply(200, {"provider_info": self.repo.provider_info(), "state": self._state()})
        except (DomainError, ProviderError) as error:
            return _error(error)
        except Exception:
            return _reply(500, {"error": {"code": "internal_error", "message": "模型设置未完成，请重试。"}})

    def dispatch(self, path, body_json=None):
        """None body is GET; a JSON string body is POST. Return status/body JSON.

        Mirrors the optional desktop API without importing its HTTP handler or
        opening a listening socket. Model transport still requires core consent.
        """
        try:
            self._ensure_open()
            if (not isinstance(path, str) or len(path) > 8192 or not path.startswith("/")
                    or path.startswith("//") or "#" in path or any(ord(c) < 32 for c in path)):
                raise DomainError("invalid_path", "本地操作地址无效。")
            url = urlsplit(path)
            if body_json is None:
                query = parse_qs(url.query)
                course = query.get("course_id", ["os-default"])[0]
                if url.path == "/health":
                    with self.repo.lock:
                        info = self.repo.provider_info()
                        value = {"status": "ok", "runtime": "android-in-process",
                                 "mode": "local-llm" if info["mode"] == "llm" else "local-demo",
                                 "provider": self.repo.provider.name, "provider_info": info,
                                 "authentication": "android-app-private"}
                elif url.path == "/state":
                    value = self._state(course)
                elif url.path == "/search":
                    value = self.repo.search(query.get("q", [""])[0], course)
                else:
                    raise DomainError("not_found", "本地操作不存在。", 404)
            else:
                if not isinstance(body_json, str):
                    raise DomainError("invalid_json", "请求必须为JSON文本。")
                try:
                    size = len(body_json.encode("utf-8"))
                except UnicodeEncodeError:
                    raise DomainError("invalid_json", "JSON或UTF-8编码不合法。") from None
                if size <= 0 or size > 7_100_000:
                    raise DomainError("invalid_body_size", "请求为空或超过7.1MB。", 413)
                try:
                    payload = _strict_json(body_json)
                except (ValueError, TypeError, RecursionError):
                    raise DomainError("invalid_json", "JSON或UTF-8编码不合法。") from None
                value = self.repo.mutate(url.path, payload)
                # Keep the mutation and its returned snapshot consistent. Only
                # presentation metadata differs from the shared domain response.
                value["state"]["runtime"] = "android-in-process"
                value["state"]["limitations"][0] = "资料与学习记录保存在本机应用内，无跨设备同步。"
            return _reply(200, value)
        except (DomainError, ProviderError) as error:
            return _error(error)
        except Exception:
            # Exceptions must not expose keys, imported text or app-private paths.
            return _reply(500, {"error": {"code": "internal_error", "message": "手机本地处理失败，请保留请求ID后重试。"}})

    def close(self):
        """Close after callers have drained; never interrupt a live model request."""
        with self.repo.lock:
            if self._closed:
                return
            if self.repo.generating:
                raise DomainError("generation_busy", "已有解释正在生成，请稍后关闭。", 409)
            self.repo.close()
            self._closed = True
