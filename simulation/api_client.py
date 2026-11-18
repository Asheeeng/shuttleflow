"""ShuttleFlow真实HTTP业务接口客户端；只保存当前测试会话Cookie。"""
from __future__ import annotations

import http.cookiejar
import json
import time
import uuid
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any


@dataclass
class ApiResponse:
    http_status: int
    code: int
    message: str
    data: Any
    elapsed_ms: float


class ShuttleApiError(RuntimeError):
    def __init__(self, path: str, response: ApiResponse):
        super().__init__(f"{path}: HTTP {response.http_status}, code={response.code}, {response.message}")
        self.path = path
        self.response = response


class LoopbackOnly(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(req.full_url, code, "隔离业务接口不接受重定向", headers, fp)


class ShuttleApi:
    def __init__(self, base_url: str, timeout: float = 12.0, *, secure_loopback: bool = False):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        if secure_loopback:
            if self.base_url != "http://127.0.0.1:18080":
                raise ValueError("Secure Cookie 只允许由隔离服务通过固定回环地址使用")
            policy = http.cookiejar.DefaultCookiePolicy(secure_protocols=("http", "https"))
            self.cookies = http.cookiejar.CookieJar(policy)
            self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                LoopbackOnly(), urllib.request.HTTPCookieProcessor(self.cookies))
        else:
            self.cookies = http.cookiejar.CookieJar()
            self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf_token: str | None = None
        self.operation_token: str | None = None

    def request(self, method: str, path: str, body: dict | None = None,
                *, csrf: bool = True, idempotency_key: str | None = None,
                retries: int = 0) -> ApiResponse:
        url = self.base_url + (path if path.startswith("/") else "/" + path)
        headers = {"Accept": "application/json"}
        if self.operation_token:
            headers["X-Operation-Token"] = self.operation_token
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if csrf and method.upper() in {"POST", "PUT", "PATCH", "DELETE"} and self.csrf_token:
            headers["X-Shuttle-CSRF"] = self.csrf_token
        if idempotency_key:
            if not all(c.isalnum() or c in "_-" for c in idempotency_key) or not 16 <= len(idempotency_key) <= 63:
                raise ValueError("Idempotency-Key格式不符合司机事件接口要求。")
            headers["Idempotency-Key"] = idempotency_key
        req = urllib.request.Request(url, data=data, headers=headers, method=method.upper())
        started = time.monotonic()
        http_status = 0
        raw = b""
        for attempt in range(retries + 1):
            try:
                with self.opener.open(req, timeout=self.timeout) as resp:
                    http_status = resp.status
                    raw = resp.read()
                break
            except urllib.error.HTTPError as error:
                http_status = error.code
                raw = error.read()
                if idempotency_key and http_status in (502, 503, 504) and attempt < retries:
                    time.sleep(0.15 * (attempt + 1))
                    continue
                break
            except (urllib.error.URLError, TimeoutError) as error:
                if idempotency_key and attempt < retries:
                    time.sleep(0.15 * (attempt + 1))
                    continue
                raise RuntimeError(f"本机业务接口不可达 {url}: {type(error).__name__}") from error
        elapsed = (time.monotonic() - started) * 1000
        try:
            payload = json.loads(raw.decode("utf-8")) if raw else {}
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise RuntimeError(f"接口返回非JSON：{path} HTTP {http_status}") from error
        return ApiResponse(
            http_status=http_status,
            code=int(payload.get("code", http_status)),
            message=str(payload.get("msg", "")),
            data=payload.get("data"),
            elapsed_ms=elapsed,
        )

    def get(self, path: str, *, expected_code: int = 200) -> ApiResponse:
        response = self.request("GET", path)
        return self._expect(path, response, {expected_code})

    def post(self, path: str, body: dict, *, expected_code: int = 200,
             csrf: bool = True, idempotency_key: str | None = None,
             retries: int = 0) -> ApiResponse:
        response = self.request("POST", path, body, csrf=csrf,
                                idempotency_key=idempotency_key, retries=retries)
        return self._expect(path, response, {expected_code})

    def post_driver_event(self, path: str, body: dict, *, idempotency_key: str | None = None) -> ApiResponse:
        """司机事件在响应超时后可用同一事件号安全重试，避免重复上下客。"""
        return self.post(path, body, idempotency_key=idempotency_key or uuid.uuid4().hex, retries=1)

    def _expect(self, path: str, response: ApiResponse, expected: set[int]) -> ApiResponse:
        if response.http_status not in range(200, 300) or response.code not in expected:
            raise ShuttleApiError(path, response)
        return response

    def login_admin(self, username: str, password: str) -> None:
        response = self.post("/api/admin/login", {"username": username, "password": password}, csrf=False)
        self.csrf_token = (response.data or {}).get("csrfToken")
        if not self.csrf_token:
            raise ShuttleApiError("/api/admin/login", response)

    def login_driver(self, phone: str, password: str) -> None:
        response = self.post("/api/driver/login", {"phone": phone, "password": password}, csrf=False)
        self.csrf_token = (response.data or {}).get("csrfToken")
        if not self.csrf_token:
            raise ShuttleApiError("/api/driver/login", response)

    def start_guest(self) -> None:
        response = self.get("/api/auth/session?guest=true")
        self.csrf_token = (response.data or {}).get("csrfToken")
        if not self.csrf_token:
            raise ShuttleApiError("/api/auth/session?guest=true", response)

    def logout(self) -> None:
        if self.csrf_token:
            self.post("/api/auth/logout", {}, csrf=True)
        self.csrf_token = None

    @staticmethod
    def query(path: str, **params: Any) -> str:
        return path + "?" + urllib.parse.urlencode(params)
