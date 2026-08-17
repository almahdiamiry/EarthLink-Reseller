#!/usr/bin/env python3
"""
Earthlink Reseller App API POC v0.6.48

Purpose:
    First proof-of-concept script for the mobile-app API:
        https://rapi.earthlink.iq/api/reseller/

This does NOT use:
    - admin.earthlink.iq
    - ASP.NET ViewState
    - Telerik Ajax
    - browser cookies

Write/financial actions are available by default but still require previews,
passwords, and explicit confirmations where appropriate.

Environment variables supported:
    EARTHLINK_USER
    EARTHLINK_PASS
    EARTHLINK_DEPOSIT_PASSWORD
    EARTHLINK_API_BASE
    EARTHLINK_TOKEN_FILE

Example:
    python earthlink_app_api_poc_v0_6_48.py
    python earthlink_app_api_poc_v0_6_48.py --login
    python earthlink_app_api_poc_v0_6_48.py --verbose
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import re
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple
from urllib.parse import urljoin

try:
    import requests
except ImportError:
    print("Missing dependency: requests")
    print("Install with: pip install requests rich")
    raise


try:
    from rich import box
    from rich.console import Console
    from rich.panel import Panel
    from rich.table import Table
    from rich.text import Text
except ImportError:
    box = None
    Console = None
    Panel = None
    Table = None
    Text = None


DEFAULT_BASE_URL = "https://rapi.earthlink.iq/api/reseller/"
DEFAULT_TOKEN_FILE = Path(os.environ.get("EARTHLINK_TOKEN_FILE", ".earthlink_app_token.json"))
DEFAULT_SETTINGS_FILE = Path(os.environ.get("EARTHLINK_SETTINGS_FILE", ".earthlink_app_settings.json"))
DEFAULT_CREDENTIAL_FILE = Path(os.environ.get("EARTHLINK_CREDENTIAL_FILE", ".earthlink_app_credentials.json"))
APP_USER_AGENT = "Android 9; Resellers 40001; PythonPOC"
CLI_VERSION = "v0.6.48"


USE_RICH = (
    Console is not None
    and os.environ.get("EARTHLINK_NO_RICH", "").strip().lower() not in {"1", "true", "yes", "on"}
)
console = Console(highlight=False) if USE_RICH else None
DEFAULT_UI_WIDTH = int(os.environ.get("EARTHLINK_UI_WIDTH", "120") or "120")


def load_dotenv_file(path: str = ".env") -> None:
    """Minimal .env loader. Existing environment variables win."""
    p = Path(path)
    if not p.exists():
        return

    for raw_line in p.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw_line.strip()

        if not line or line.startswith("#") or "=" not in line:
            continue

        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")

        if key and key not in os.environ:
            os.environ[key] = value


def yes_no(value: Any) -> str:
    return "Yes" if bool(value) else "No"


def clean_text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def safe_get(data: Dict[str, Any], *keys: str, default: str = "") -> str:
    for key in keys:
        value = data.get(key)
        if value is not None and value != "":
            return str(value)
    return default


def trim(value: Any, width: int) -> str:
    text = clean_text(value)
    if len(text) <= width:
        return text
    return text[: max(0, width - 1)] + "…"


def ui_print(*args: Any, **kwargs: Any) -> None:
    if USE_RICH and console is not None:
        console.print(*args, **kwargs)
    else:
        print(*args)


def ui_width() -> int:
    if not USE_RICH or console is None:
        return DEFAULT_UI_WIDTH
    try:
        terminal_width = int(console.width)
    except Exception:
        terminal_width = DEFAULT_UI_WIDTH
    return max(88, min(DEFAULT_UI_WIDTH, terminal_width))


def ui_rule(title: str = "") -> None:
    if USE_RICH and console is not None:
        console.rule(title)
    else:
        if title:
            print(title)
        print("-" * 72)


def style_for_status(value: Any) -> str:
    status = clean_text(value).lower()
    if status in {"active", "online"}:
        return "green"
    if status in {"expiringsoon", "onlinenonet"}:
        return "yellow"
    if status in {"suspended", "expired", "recentlyexpired", "suspendedbyagent", "offline"}:
        return "red"
    if status in {"grey", "gray"}:
        return "bright_black"
    return "white"


def status_text(value: Any) -> Any:
    text_value = display_value(value) if "display_value" in globals() else clean_text(value)
    if not USE_RICH:
        return text_value
    return f"[{style_for_status(text_value)}]{text_value}[/]"


def money_text(value: Any) -> Any:
    text_value = fmt_iqd(value) if "fmt_iqd" in globals() else clean_text(value)
    if not USE_RICH:
        return text_value
    if str(text_value).strip().startswith("-"):
        return f"[red]{text_value}[/]"
    return f"[green]{text_value}[/]"


def yes_no_text(value: Any) -> Any:
    text_value = yes_no(value) if "yes_no" in globals() else ("Yes" if value else "No")
    if not USE_RICH:
        return text_value
    return f"[green]{text_value}[/]" if bool(value) else f"[red]{text_value}[/]"


def compact_panel(renderable: Any, *, title: str = "", border_style: str = "cyan") -> Any:
    return Panel(
        renderable,
        title=title,
        border_style=border_style,
        box=box.ROUNDED if box else None,
        width=ui_width(),
        expand=False,
    )


def print_panel(title: str, body: str, style: str = "cyan") -> None:
    if USE_RICH and console is not None and Panel is not None:
        console.print(compact_panel(body, title=title, border_style=style))
    else:
        print("\n" + title)
        print("=" * max(40, len(title)))
        print(body)



def print_json(data: Any) -> None:
    print(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=False))


def decode_jwt_payload(token: str) -> Dict[str, Any]:
    """
    Decode JWT payload without verification, only for reading expiry.
    """
    try:
        import base64

        parts = token.split(".")
        if len(parts) < 2:
            return {}

        payload = parts[1]
        payload += "=" * (-len(payload) % 4)
        raw = base64.urlsafe_b64decode(payload.encode("ascii"))
        data = json.loads(raw.decode("utf-8"))
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def jwt_seconds_left(token: str) -> Optional[int]:
    payload = decode_jwt_payload(token)
    exp = payload.get("exp")
    if not exp:
        return None

    try:
        import time

        return int(exp) - int(time.time())
    except Exception:
        return None


def mask_sensitive(value: Any) -> Any:
    if value is None:
        return value

    text_value = str(value)
    if len(text_value) <= 4:
        return "****"
    return text_value[:2] + "****" + text_value[-2:]


def summarize_json_payload_for_debug(payload: Any) -> str:
    """
    Compact debug view for large POST payloads.

    The user update endpoint sends the full user object and can fill the screen.
    By default, only show action-relevant fields. Full payloads can be enabled
    from Settings or EARTHLINK_DEBUG_PAYLOADS=1.
    """
    if not isinstance(payload, dict):
        return trim(payload, 500)

    sensitive_keys = {
        "password",
        "Password",
        "DepositPassword",
        "depositPassword",
        "NewPassword",
        "newPassword",
        "accountPassword",
        "AccountPassword",
    }

    important_keys = [
        "userIndex",
        "UserIndex",
        "userID",
        "UserID",
        "userid",
        "userId",
        "accountStatus",
        "AccountStatus",
        "userActive",
        "UserActive",
        "userActiveManage",
        "UserActiveManage",
        "isBlocked",
        "IsBlocked",
        "accountName",
        "AccountName",
        "accountIndex",
        "AccountIndex",
        "onlineStatus",
        "OnlineStatus",
        "displayName",
        "DisplayName",
        "callerID",
        "CallerID",
        "maxmac",
        "MAXMAC",
        "maxMac",
        "MaxMac",
        "DepositPassword",
        "NewPassword",
        "UserPass",
        "userPass",
        "Status",
        "PaymentDueDate",
        "customerId",
        "CustomerId",
    ]

    compact: Dict[str, Any] = {}
    for key in important_keys:
        if key in payload:
            value = payload[key]
            compact[key] = mask_sensitive(value) if key in sensitive_keys else value

    user_obj = payload.get("userObject") or payload.get("UserObject")
    if isinstance(user_obj, dict):
        compact["userObject"] = {
            k: user_obj.get(k)
            for k in ("userIndex", "userId", "UserId", "userID", "UserID", "displayName", "DisplayName", "status", "Status")
            if user_obj.get(k) not in (None, "")
        }

    online_session = payload.get("onlineSession") or payload.get("OnlineSession")
    if isinstance(online_session, dict):
        compact["onlineSession"] = {
            k: online_session.get(k)
            for k in ("userIndex", "userID", "UserID", "userIP", "UserIP", "callerMAC", "CallerMAC", "onlineTime", "OnlineTime", "onlineStatus", "OnlineStatus")
            if online_session.get(k) not in (None, "")
        }

    if not compact:
        keys = list(payload.keys())
        return f"<dict keys={keys[:20]} total_keys={len(keys)}>"

    return json.dumps(compact, ensure_ascii=False, sort_keys=False)


def print_action_result(title: str, result: Any) -> None:
    if isinstance(result, dict):
        api_ok = result.get("isSuccessful")
        if api_ok is None:
            api_ok = result.get("IsSuccessful")
        if api_ok is None:
            api_ok = True

        value = result.get("value", result.get("Value"))
        message = result.get("responseMessage") or result.get("ResponseMessage")
        error = result.get("error") or result.get("Error")

        if isinstance(value, bool):
            action_ok = value
        elif error:
            action_ok = False
        else:
            action_ok = bool(api_ok)

        error_text = ""
        if isinstance(error, dict):
            err_msg = error.get("message") or error.get("Message") or error.get("detailMessage") or error.get("DetailMessage") or ""
            validation = error.get("validationErrors") or error.get("ValidationErrors") or []
            details = []
            if isinstance(validation, list):
                for item in validation:
                    if isinstance(item, dict):
                        label = item.get("label") or item.get("Label") or ""
                        vmsg = item.get("validationMessage") or item.get("ValidationMessage") or ""
                        if label or vmsg:
                            details.append(f"{label}: {vmsg}".strip(": "))
            error_text = " | ".join([x for x in [err_msg, *details] if x]) or json.dumps(error, ensure_ascii=False)
        elif error:
            error_text = str(error)

        if USE_RICH and console is not None and Table is not None and Panel is not None:
            table = Table.grid(padding=(0, 2))
            table.add_column(style="bold")
            table.add_column()
            table.add_row("API OK", yes_no_text(api_ok))
            table.add_row("Action OK", yes_no_text(action_ok))
            if value not in (None, ""):
                table.add_row("Value", str(value))
            if message:
                table.add_row("Message", str(message))
            if error_text:
                table.add_row("Error", f"[red]{error_text}[/]")
            border = "green" if action_ok else "red"
            console.print(compact_panel(table, title=title, border_style=border))
        else:
            print(f"\n{title}")
            print("=" * 40)
            print(f"API OK    : {yes_no(api_ok)}")
            print(f"Action OK : {yes_no(action_ok)}")
            if value not in (None, ""):
                print(f"Value     : {value}")
            if message:
                print(f"Message   : {message}")
            if error_text:
                print(f"Error     : {error_text}")
        return

    if USE_RICH and console is not None and Panel is not None:
        console.print(compact_panel(str(result), title=title, border_style="cyan"))
    else:
        print(f"\n{title}")
        print("=" * 40)
        print(f"Result    : {result}")


DEFAULT_SETTINGS: Dict[str, Any] = {
    "page_size": int(os.environ.get("EARTHLINK_PAGE_SIZE", "30") or "30"),
    "last_username": os.environ.get("EARTHLINK_USER", "").strip(),
    "debug_payloads": os.environ.get("EARTHLINK_DEBUG_PAYLOADS", "").strip().lower() in {"1", "true", "yes", "on"},
    "remember_login": os.environ.get("EARTHLINK_REMEMBER_LOGIN", "").strip().lower() in {"1", "true", "yes", "on"},
}


def load_cli_settings(path: Path = DEFAULT_SETTINGS_FILE) -> Dict[str, Any]:
    settings = dict(DEFAULT_SETTINGS)

    if path.exists():
        try:
            saved = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(saved, dict):
                settings.update(saved)
        except Exception:
            pass

    page_size = settings.get("page_size", 30)
    try:
        page_size = int(page_size)
    except Exception:
        page_size = 30

    if page_size <= 0:
        page_size = 30

    settings["page_size"] = page_size
    return settings


def save_cli_settings(settings: Dict[str, Any], path: Path = DEFAULT_SETTINGS_FILE) -> None:
    path.write_text(json.dumps(settings, ensure_ascii=False, indent=2), encoding="utf-8")


def load_saved_credentials(path: Path = DEFAULT_CREDENTIAL_FILE) -> Tuple[str, str]:
    """
    Optional local credential storage for CLI remember-me.

    Security note:
        This is plain local JSON storage. Use only on a private trusted machine.
        Prefer .env if you do not want the script to manage a credential file.
    """
    if not path.exists():
        return "", ""

    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return "", ""

    if not isinstance(data, dict):
        return "", ""

    return str(data.get("username", "")).strip(), str(data.get("password", ""))


def save_saved_credentials(username: str, password: str, path: Path = DEFAULT_CREDENTIAL_FILE) -> None:
    path.write_text(
        json.dumps(
            {
                "username": username,
                "password": password,
                "saved_at": time.time(),
                "warning": "Plain local CLI storage. Use only on a private trusted machine.",
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )


def clear_saved_credentials(path: Path = DEFAULT_CREDENTIAL_FILE) -> None:
    try:
        if path.exists():
            path.unlink()
    except OSError:
        pass


CLI_SETTINGS: Dict[str, Any] = load_cli_settings()


def get_page_size() -> int:
    try:
        value = int(CLI_SETTINGS.get("page_size", 30))
    except Exception:
        value = 30
    return value if value > 0 else 30


def settings_menu() -> None:
    while True:
        print("\nSettings")
        print("=" * 72)
        saved_cred_user, _saved_cred_pass = load_saved_credentials()
        print(f"1. Default page size: {get_page_size()}")
        print(f"2. Remembered username: {CLI_SETTINGS.get('last_username') or '(none)'}")
        print(f"3. Full debug payloads: {yes_no(CLI_SETTINGS.get('debug_payloads', False))}")
        print(f"4. Remember login locally: {yes_no(CLI_SETTINGS.get('remember_login', False))}")
        print(f"5. Saved login user: {saved_cred_user or '(none)'}")
        print("B. Back")

        choice = input("Choose: ").strip().lower()

        if choice == "1":
            new_value = input("New default page size [30]: ").strip() or "30"

            if not new_value.isdigit() or int(new_value) <= 0:
                print("Invalid page size.")
                continue

            CLI_SETTINGS["page_size"] = int(new_value)
            save_cli_settings(CLI_SETTINGS)
            print(f"Saved default page size: {new_value}")

        elif choice == "2":
            new_user = input("Remember username (blank = clear): ").strip()
            CLI_SETTINGS["last_username"] = new_user
            save_cli_settings(CLI_SETTINGS)
            print("Saved.")

        elif choice == "3":
            current = bool(CLI_SETTINGS.get("debug_payloads", False))
            CLI_SETTINGS["debug_payloads"] = not current
            save_cli_settings(CLI_SETTINGS)
            print(f"Full debug payloads: {yes_no(CLI_SETTINGS['debug_payloads'])}")

        elif choice == "4":
            current = bool(CLI_SETTINGS.get("remember_login", False))
            CLI_SETTINGS["remember_login"] = not current
            save_cli_settings(CLI_SETTINGS)
            print(f"Remember login locally: {yes_no(CLI_SETTINGS['remember_login'])}")
            if not CLI_SETTINGS["remember_login"]:
                clear_saved_credentials()
                print("Saved local login was cleared.")

        elif choice == "5":
            print("This stores username/password in a local JSON file for automatic CLI login.")
            print("Use only on a private trusted machine.")
            username = input(f"Username [{CLI_SETTINGS.get('last_username') or ''}]: ").strip() or str(CLI_SETTINGS.get("last_username") or "")
            if not username:
                print("Username is required.")
                continue
            password = getpass.getpass("Password to save locally: ")
            if not password:
                print("Password is required.")
                continue
            save_saved_credentials(username, password)
            CLI_SETTINGS["last_username"] = username
            CLI_SETTINGS["remember_login"] = True
            save_cli_settings(CLI_SETTINGS)
            print("Saved local login and enabled remember login.")

        elif choice == "b":
            return

        else:
            print("Invalid choice.")



class ApiError(RuntimeError):
    pass


@dataclass
class UserListItem:
    user_index: str
    user_id: str
    display_name: str = ""
    account_name: str = ""
    account_status: str = ""
    online_status: str = ""
    ip: str = ""
    mac: str = ""
    raw: Dict[str, Any] = field(default_factory=dict)


class EarthlinkAppApiClient:
    def __init__(
        self,
        base_url: str = DEFAULT_BASE_URL,
        token_file: Path = DEFAULT_TOKEN_FILE,
        verbose: bool = False,
        timeout: int = 30,
        debug_payloads: bool = False,
    ) -> None:
        self.base_url = base_url.rstrip("/") + "/"
        self.token_file = token_file
        self.verbose = verbose
        self.timeout = timeout
        self.debug_payloads = bool(debug_payloads)

        self.session = requests.Session()
        self.session.headers.update(
            {
                "User-Agent": APP_USER_AGENT,
                "Accept": "application/json, text/plain, */*",
            }
        )

        self.access_token: Optional[str] = None
        self.token_expires_at: Optional[float] = None

    def log(self, message: str) -> None:
        if self.verbose:
            print(f"[debug] {message}")

    def url(self, path: str) -> str:
        return urljoin(self.base_url, path.lstrip("/"))

    def load_token(self) -> bool:
        if not self.token_file.exists():
            return False

        try:
            data = json.loads(self.token_file.read_text(encoding="utf-8"))
        except Exception:
            return False

        token = data.get("access_token")
        expires_at = data.get("expires_at")

        if not token:
            return False

        # Give 60 seconds buffer.
        if expires_at and time.time() > float(expires_at) - 60:
            return False

        self.access_token = token
        self.token_expires_at = float(expires_at) if expires_at else None
        self.session.headers.update({"Authorization": f"Bearer {token}"})
        self.log(f"Loaded token from {self.token_file}")
        return True

    def save_token(self, token_data: Dict[str, Any]) -> None:
        access_token = token_data.get("access_token")
        if not access_token:
            return

        expires_in = token_data.get("expires_in")
        expires_at = time.time() + float(expires_in) if expires_in else None

        save_data = {
            "access_token": access_token,
            "token_type": token_data.get("token_type", "bearer"),
            "expires_in": expires_in,
            "expires_at": expires_at,
            "saved_at": time.time(),
        }

        self.token_file.write_text(json.dumps(save_data, ensure_ascii=False, indent=2), encoding="utf-8")
        self.access_token = access_token
        self.token_expires_at = expires_at
        self.session.headers.update({"Authorization": f"Bearer {access_token}"})

        self.log(f"Saved token to {self.token_file}")

    def clear_token(self) -> None:
        self.access_token = None
        self.token_expires_at = None
        self.session.headers.pop("Authorization", None)

        if self.token_file.exists():
            try:
                self.token_file.unlink()
            except OSError:
                pass

    def login(self, username: str, password: str) -> Dict[str, Any]:
        url = self.url("token")
        data = {
            "username": username,
            "password": password,
            "loginType": "1",
            "grant_type": "password",
        }

        headers = {
            "Content-Type": "application/x-www-form-urlencoded",
            "Accept": "application/json, text/plain, */*",
            "User-Agent": APP_USER_AGENT,
        }

        self.log(f"POST {url}")
        resp = self.session.post(url, data=data, headers=headers, timeout=self.timeout)
        self.log(f"-> {resp.status_code}")

        if resp.status_code != 200:
            raise ApiError(f"Login failed: HTTP {resp.status_code}\n{resp.text[:500]}")

        try:
            token_data = resp.json()
        except json.JSONDecodeError as exc:
            raise ApiError(f"Login did not return JSON: {resp.text[:500]}") from exc

        if "access_token" not in token_data:
            raise ApiError(f"Login response missing access_token:\n{json.dumps(token_data, ensure_ascii=False, indent=2)}")

        self.save_token(token_data)
        return token_data

    def remember_username(self, username: str) -> None:
        username = (username or "").strip()
        if not username:
            return

        try:
            if "CLI_SETTINGS" in globals():
                CLI_SETTINGS["last_username"] = username
                save_cli_settings(CLI_SETTINGS)
        except Exception:
            pass

    def ensure_login(self, force_login: bool = False, quiet: bool = False) -> None:
        env_user = os.environ.get("EARTHLINK_USER", "").strip()
        env_pass = os.environ.get("EARTHLINK_PASS", "")
        saved_user = ""
        saved_cred_user = ""
        saved_cred_pass = ""

        try:
            saved_user = str(CLI_SETTINGS.get("last_username", "")).strip()
            if bool(CLI_SETTINGS.get("remember_login", False)):
                saved_cred_user, saved_cred_pass = load_saved_credentials()
        except Exception:
            saved_user = ""

        if not force_login and self.load_token():
            # Proactive refresh: avoid waiting for a 401 if token is close to expiry.
            seconds_left = jwt_seconds_left(self.access_token or "")
            if seconds_left is None or seconds_left > 120:
                return

            self.log(f"Token expires in {seconds_left}s; refreshing login token...")
            if env_user and env_pass:
                token_data = self.login(env_user, env_pass)
                self.remember_username(env_user)
                self.log(f"Login refreshed. Token type: {token_data.get('token_type', 'bearer')}")
                return

            if saved_cred_user and saved_cred_pass:
                token_data = self.login(saved_cred_user, saved_cred_pass)
                self.remember_username(saved_cred_user)
                self.log(f"Login refreshed from saved local credentials. Token type: {token_data.get('token_type', 'bearer')}")
                return

            # No password source available; continue with old token until server rejects it.
            return

        # Fully automatic login if .env/environment provides both values.
        if env_user and env_pass:
            if not quiet:
                self.log("Login using EARTHLINK_USER / EARTHLINK_PASS")
            token_data = self.login(env_user, env_pass)
            self.remember_username(env_user)
            if not quiet:
                self.log(f"Login OK. Token type: {token_data.get('token_type', 'bearer')}")
            return

        # Optional local remember-me credentials.
        if saved_cred_user and saved_cred_pass:
            if not quiet:
                self.log("Login using saved local credentials")
            token_data = self.login(saved_cred_user, saved_cred_pass)
            self.remember_username(saved_cred_user)
            if not quiet:
                self.log(f"Login OK. Token type: {token_data.get('token_type', 'bearer')}")
            return

        default_user = env_user or saved_cred_user or saved_user

        print("Login")
        print("-" * 40)

        if default_user:
            username = input(f"Username [{default_user}]: ").strip() or default_user
        else:
            username = input("Username: ").strip()

        if env_pass:
            use_env = input("Use password from EARTHLINK_PASS? [Y/n]: ").strip().lower()
            if use_env in {"", "y", "yes"}:
                password = env_pass
            else:
                password = getpass.getpass("Password: ")
        else:
            password = getpass.getpass("Password: ")

        token_data = self.login(username, password)
        self.remember_username(username)

        if bool(CLI_SETTINGS.get("remember_login", False)):
            save_saved_credentials(username, password)

        print(f"Login OK. Token type: {token_data.get('token_type', 'bearer')}")


    def prompt_relogin(self, quiet: bool = False) -> bool:
        """
        Re-login after 401/session expiry.

        If EARTHLINK_USER and EARTHLINK_PASS exist in .env/environment, this is
        fully automatic and quiet. Otherwise it remembers the last username and
        asks only for password.
        """
        env_user = os.environ.get("EARTHLINK_USER", "").strip()
        env_pass = os.environ.get("EARTHLINK_PASS", "")

        saved_cred_user, saved_cred_pass = ("", "")
        try:
            if bool(CLI_SETTINGS.get("remember_login", False)):
                saved_cred_user, saved_cred_pass = load_saved_credentials()
        except Exception:
            pass

        if (env_user and env_pass) or (saved_cred_user and saved_cred_pass):
            try:
                self.ensure_login(force_login=True, quiet=True)
                return True
            except Exception as exc:
                if not quiet:
                    print(f"Silent re-login failed: {exc}")
                return False

        if not quiet:
            print()
            print("Session/token expired. Please login again.")

        try:
            self.ensure_login(force_login=True, quiet=False)
            return True
        except Exception as exc:
            print(f"Re-login failed: {exc}")
            return False


    def request(
        self,
        method: str,
        path: str,
        *,
        params: Optional[Dict[str, Any]] = None,
        data: Optional[Dict[str, Any]] = None,
        json_body: Optional[Any] = None,
        extra_headers: Optional[Dict[str, str]] = None,
        raw: bool = False,
        retry_on_401: bool = True,
    ) -> Any:
        if not self.access_token:
            self.ensure_login(force_login=False)

        url = self.url(path)

        headers: Dict[str, str] = {}
        if json_body is not None:
            headers["Content-Type"] = "application/json; charset=UTF-8"
        elif data is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded"

        if extra_headers:
            headers.update(extra_headers)

        def do_request() -> requests.Response:
            self.log(f"{method.upper()} {url}")
            if params:
                self.log(f"params={params}")
            if data:
                data_for_log = dict(data)
                for key in list(data_for_log.keys()):
                    key_l = str(key).lower()
                    if "password" in key_l or key_l in {"userpass", "user_pass", "depositpassword"}:
                        data_for_log[key] = "****"
                self.log(f"data={data_for_log}")
            if json_body is not None:
                if self.debug_payloads or bool(CLI_SETTINGS.get("debug_payloads", False)):
                    self.log(f"json={json_body}")
                else:
                    self.log(f"json={summarize_json_payload_for_debug(json_body)}")

            response = self.session.request(
                method.upper(),
                url,
                params=params,
                data=data,
                json=json_body,
                headers=headers,
                timeout=self.timeout,
            )
            self.log(f"-> {response.status_code}")
            return response

        resp = do_request()

        if resp.status_code == 401 and retry_on_401:
            self.clear_token()
            if self.prompt_relogin(quiet=True):
                resp = do_request()

        if resp.status_code == 401:
            self.clear_token()
            raise ApiError("Unauthorized / token expired. Re-login failed.")

        if not (200 <= resp.status_code < 300):
            raise ApiError(f"HTTP {resp.status_code} from {path}\n{resp.text[:1000]}")

        if raw:
            return resp

        try:
            payload = resp.json()
        except json.JSONDecodeError as exc:
            raise ApiError(f"Response is not JSON from {path}:\n{resp.text[:1000]}") from exc

        # Some endpoints may return direct arrays/objects. Keep them.
        if isinstance(payload, dict):
            if payload.get("isSuccessful") is False or payload.get("IsSuccessful") is False:
                # These business endpoints return useful validation messages with HTTP 200.
                # Let the caller show a clean action result instead of a raw API error.
                no_raise_paths = {
                    "user/newuserdeposit",
                    "usercustomer/create",
                    "affiliate/deposit/accountCost",
                }
                if path in no_raise_paths:
                    return payload

                msg = payload.get("responseMessage") or payload.get("ResponseMessage") or payload.get("error") or payload.get("Error")
                raise ApiError(f"API returned unsuccessful response from {path}: {msg or payload}")
        return payload


    @staticmethod
    def unwrap(payload: Any) -> Any:
        if isinstance(payload, dict):
            if "value" in payload:
                return payload["value"]
            if "Value" in payload:
                return payload["Value"]
        return payload

    def get_balance(self) -> Any:
        return self.unwrap(self.request("GET", "affiliate/deposit/balance"))

    def get_test_count(self, affiliate_index: Optional[str] = None) -> Any:
        """
        Return remaining free test users.

        APK documentation:
            GET /testcount
            optional query: affiliateIndex
            called from CreateTestUserViewModel.
        """
        params = {"affiliateIndex": affiliate_index} if affiliate_index else None
        return self.unwrap(self.request("GET", "testcount", params=params))

    def get_accounts(self) -> List[Dict[str, Any]]:
        value = self.unwrap(self.request("GET", "accounts/all"))
        return value if isinstance(value, list) else []

    def get_account_cost(self, account_id: str) -> Any:
        """
        Return account cost payload.

        Note:
            This endpoint can return HTTP 200 + isSuccessful=false when balance is
            insufficient. That response still contains useful text like:
                "Account cost is 90,000 IQD, your current balance is 85250"
            Therefore we parse and return the JSON instead of raising ApiError.
        """
        resp = self.request(
            "POST",
            "affiliate/deposit/accountCost",
            data={"AccountID": account_id},
            raw=True,
        )

        try:
            payload = resp.json()
        except json.JSONDecodeError:
            return {"isSuccessful": False, "error": resp.text}

        return payload

    def list_users(
        self,
        *,
        start_index: int = 0,
        row_count: int = 30,
        account_status_id: Optional[str] = None,
        order_by: str = "",
        order_descending: bool = False,
        extra: Optional[Dict[str, Any]] = None,
    ) -> Tuple[List[UserListItem], int, Any]:
        data: Dict[str, Any] = {
            "StartIndex": str(start_index),
            "RowCount": str(row_count),
            "OrderDescending": "true" if order_descending else "false",
        }

        if order_by:
            data["OrderBy"] = order_by

        if account_status_id is not None and account_status_id != "":
            data["AccountStatusID"] = str(account_status_id)

        if extra:
            for k, v in extra.items():
                if v is not None and v != "":
                    data[k] = str(v)

        payload = self.request("POST", "user/all", data=data)
        value = self.unwrap(payload)

        items_raw: List[Any] = []
        total_count = 0

        if isinstance(value, dict):
            items_raw = (
                value.get("itemsList")
                or value.get("ItemsList")
                or value.get("items")
                or value.get("Items")
                or []
            )
            total_count = int(value.get("totalCount") or value.get("TotalCount") or len(items_raw))
        elif isinstance(value, list):
            items_raw = value
            total_count = len(items_raw)

        users: List[UserListItem] = []
        for item in items_raw:
            if not isinstance(item, dict):
                continue

            users.append(
                UserListItem(
                    user_index=clean_text(item.get("userIndex") or item.get("UserIndex")),
                    user_id=clean_text(item.get("userID") or item.get("UserID") or item.get("userId") or item.get("UserId")),
                    display_name=clean_text(item.get("displayName") or item.get("DisplayName")),
                    account_name=clean_text(item.get("accountName") or item.get("AccountName") or item.get("groupName") or item.get("GroupName")),
                    account_status=clean_text(item.get("accountStatus") or item.get("AccountStatus")),
                    online_status=clean_text(item.get("onlineStatus") or item.get("OnlineStatus")),
                    ip=clean_text(item.get("userIP") or item.get("UserIP") or item.get("routerIp") or item.get("RouterIp")),
                    mac=clean_text(item.get("callerID") or item.get("CallerID") or item.get("maxmac") or item.get("MAXMAC")),
                    raw=item,
                )
            )

        return users, total_count, payload

    def list_active_sessions(self, *, start_index: int = 0, row_count: int = 30) -> Any:
        """
        Users → Sessions → Online.
        Endpoint captured from mobile app:
            POST /usersession/active
        """
        return self.request(
            "POST",
            "usersession/active",
            data={"StartIndex": str(start_index), "RowCount": str(row_count)},
        )

    def list_invoices(
        self,
        *,
        start_index: int = 0,
        row_count: int = 30,
        order_by_desc: bool = True,
        query: str = "",
    ) -> Any:
        """
        Users → Invoices.
        Endpoint captured from mobile app:
            POST /userpayment/usersInvoice

        Query is used for user-specific invoice/payment lookup when supported by API.
        """
        data = {
            "StartIndex": str(start_index),
            "RowCount": str(row_count),
            "OrderByDesc": "true" if order_by_desc else "false",
        }
        if query:
            data["Query"] = query

        return self.request(
            "POST",
            "userpayment/usersInvoice",
            data=data,
        )

    def list_user_errors(self, *, start_index: int = 0, row_count: int = 30) -> Any:
        """
        Users → Errors.
        Endpoint captured from mobile app:
            POST /userlog/all
        """
        return self.request(
            "POST",
            "userlog/all",
            data={"StartIndex": str(start_index), "RowCount": str(row_count)},
        )

    def list_test_users(self, *, start_index: int = 0, row_count: int = 30) -> Any:
        """
        Users → Test Users.
        Endpoint captured from mobile app:
            GET /reports/testsUsed
        """
        return self.request(
            "GET",
            "reports/testsUsed",
            params={"StartIndex": str(start_index), "RowCount": str(row_count)},
        )

    def get_user(self, user_index: str) -> Dict[str, Any]:
        payload = self.request("GET", f"user/{user_index}")
        value = self.unwrap(payload)
        return value if isinstance(value, dict) else {}

    def autocomplete_user(self, key: str) -> Tuple[List[Dict[str, Any]], Any]:
        """
        Search users using the mobile app autocomplete endpoint.

        Expected endpoint from APK/API inventory:
            GET /user/autocomplete?key=<text>

        The response shape can vary, so this returns both normalized items and raw payload.
        """
        payload = self.request("GET", "user/autocomplete", params={"key": key})
        value = self.unwrap(payload)

        if isinstance(value, list):
            return [x for x in value if isinstance(x, dict)], payload

        if isinstance(value, dict):
            items = (
                value.get("itemsList")
                or value.get("ItemsList")
                or value.get("items")
                or value.get("Items")
                or value.get("users")
                or value.get("Users")
                or []
            )
            if isinstance(items, list):
                return [x for x in items if isinstance(x, dict)], payload

        return [], payload

    @staticmethod
    def item_user_id(item: Dict[str, Any]) -> str:
        return clean_text(
            item.get("userID")
            or item.get("UserID")
            or item.get("userId")
            or item.get("UserId")
            or item.get("userid")
            or item.get("id")
        )

    @staticmethod
    def item_user_index(item: Dict[str, Any]) -> str:
        return clean_text(
            item.get("userIndex")
            or item.get("UserIndex")
            or item.get("userindex")
            or item.get("index")
        )

    def resolve_user_index(self, identifier: str) -> Tuple[Optional[str], Optional[str], Any]:
        """
        Accept either a numeric userIndex or a username like abbas@sacx.

        Returns:
            (user_index, user_id, raw_resolution_payload)

        If identifier is already numeric, no API search is needed.
        If identifier contains @, try /user/autocomplete and pick exact userID match.
        """
        identifier = (identifier or "").strip()

        if not identifier:
            return None, None, None

        if identifier.isdigit():
            return identifier, None, None

        items, raw = self.autocomplete_user(identifier)
        needle = identifier.lower()

        # Prefer exact userID match.
        for item in items:
            user_id = self.item_user_id(item)
            user_index = self.item_user_index(item)
            if user_id.lower() == needle and user_index:
                return user_index, user_id, raw

        # Fallback: if only one result, use it.
        if len(items) == 1:
            item = items[0]
            user_id = self.item_user_id(item)
            user_index = self.item_user_index(item)
            if user_index:
                return user_index, user_id, raw

        return None, None, raw

    def get_user_by_identifier(self, identifier: str) -> Tuple[Dict[str, Any], Optional[str]]:
        """
        Get details using either userIndex or username.

        Returns:
            (user_details, resolved_user_index)
        """
        user_index, _user_id, _raw = self.resolve_user_index(identifier)

        if not user_index:
            return {}, None

        return self.get_user(user_index), user_index

    def check_user_available(self, user_id: str) -> Tuple[Optional[bool], Any]:
        """
        Returns (available, raw_payload).

        Confirmed by live test:
            value=true  -> username is available
            value=false -> username already exists / not available
        """
        payload = self.request("POST", "user/checkuseravailable", data={"UserID": user_id})
        value = self.unwrap(payload)
        if isinstance(value, bool):
            return value, payload
        return None, payload

    def create_customer(
        self,
        *,
        customer_full_name: str,
        customer_phone_number: str,
        customer_second_phone_number: str = "",
        email: str = "",
        address: str = "",
    ) -> Any:
        """
        Create customer record.

        Confirmed API:
            POST /usercustomer/create
            customerFullName, customerPhoneNumber, customerSecondPhoneNumber, email, address
        """
        data = {
            "customerFullName": customer_full_name,
            "customerPhoneNumber": customer_phone_number,
            "email": email,
            "address": address,
        }
        if customer_second_phone_number:
            data["customerSecondPhoneNumber"] = customer_second_phone_number

        return self.request(
            "POST",
            "usercustomer/create",
            data=data,
        )

    def create_test_user(
        self,
        *,
        mobile_number: str,
        account_index: str,
        user_id: str,
        display_name: str,
        affiliate_index: str,
        user_pass: str,
    ) -> Any:
        """
        Create a free test user through the mobile app API.

        Confirmed from HAR:
            POST /user/newtestuser
            MobileNumber, AccountIndex, UserID, DisplayName, AffiliateIndex, UserPass
        """
        return self.request(
            "POST",
            "user/newtestuser",
            data={
                "MobileNumber": mobile_number,
                "AccountIndex": str(account_index),
                "UserID": user_id,
                "DisplayName": display_name,
                "AffiliateIndex": str(affiliate_index),
                "UserPass": user_pass,
            },
        )

    def create_paid_user_deposit(
        self,
        *,
        mobile_number: str,
        account_index: str,
        user_id: str,
        display_name: str,
        affiliate_index: str,
        user_pass: str,
        deposit_password: str,
        customer_id: str,
        status: str = "",
        payment_due_date: str = "",
        email: str = "",
        address: str = "",
        id_card_number: str = "",
        id_card_type: str = "",
        gender: str = "",
        type_of_use: str = "",
        location_details: str = "",
    ) -> Any:
        """
        Create a paid user using reseller deposit.

        Confirmed from APK/API docs:
            POST /user/newuserdeposit

        Required/important fields:
            UserID, UserPass, DisplayName, MobileNumber, AffiliateIndex,
            DepositPassword, AccountIndex, customerId

        Optional fields are only sent when provided, to avoid sending
        uncertain default values.
        """
        data: Dict[str, Any] = {
            "UserID": user_id,
            "UserPass": user_pass,
            "DisplayName": display_name,
            "MobileNumber": mobile_number,
            "AffiliateIndex": str(affiliate_index),
            "DepositPassword": deposit_password,
            "AccountIndex": str(account_index),
            "customerId": str(customer_id),
        }

        optional = {
            "Status": status,
            "PaymentDueDate": payment_due_date,
            "Email": email,
            "Address": address,
            "IdCardNumber": id_card_number,
            "IdCardType": id_card_type,
            "Gender": gender,
            "TypeOfUse": type_of_use,
            "LocationDetails": location_details,
        }

        for key, value in optional.items():
            if value not in (None, ""):
                data[key] = value

        return self.request(
            "POST",
            "user/newuserdeposit",
            data=data,
        )


    def refill_deposit(self, user_id: str, deposit_password: str) -> Any:
        body = {
            "DepositPassword": deposit_password,
            "UserID": user_id,
        }
        return self.request("POST", "user/newrefilldeposit", json_body=body)

    def disconnect_user(self, user_index: str, user_id: str) -> Any:
        body = {
            "userindex": int(user_index) if str(user_index).isdigit() else user_index,
            "userid": user_id,
        }
        return self.request("POST", "activesessions/disconnect", json_body=body)

    def get_customers_by_phone(self, phone_number: str) -> Tuple[List[Dict[str, Any]], Any]:
        """
        Lookup customer records by phone number.

        Endpoint from app/API inventory:
            POST /usercustomer/phone
        """
        payload = self.request("POST", "usercustomer/phone", data={"phoneNumber": phone_number})
        value = self.unwrap(payload)

        if isinstance(value, list):
            return [x for x in value if isinstance(x, dict)], payload

        if isinstance(value, dict):
            items = (
                value.get("itemsList")
                or value.get("ItemsList")
                or value.get("items")
                or value.get("Items")
                or value.get("customers")
                or value.get("Customers")
                or []
            )
            if isinstance(items, list):
                return [x for x in items if isinstance(x, dict)], payload

        return [], payload

    def get_prepaid_needed_default(self) -> Any:
        """
        Dashboard prepaid-needed/default forecast.

        Endpoint from app/API inventory:
            GET /home/PrepaidNeeded
        """
        return self.request("GET", "home/PrepaidNeeded")

    def get_prepaid_needed_by_days(self, days: int) -> Any:
        """
        Custom prepaid-needed forecast.

        Endpoint from app/API inventory:
            POST /prepaycard/prepaidneeded
            Days=<number>
        """
        return self.request("POST", "prepaycard/prepaidneeded", data={"Days": str(days)})

    def get_card_prices_for_reseller(self) -> Any:
        """
        Business → Card Prices → Prices for Reseller.

        Endpoint:
            GET /prepaycard/prices/forreseller
        """
        return self.request("GET", "prepaycard/prices/forreseller")

    def get_card_prices_for_user(self) -> Any:
        """
        Business → Card Prices → Prices for Users.

        Endpoint:
            GET /prepaycard/prices/foruser
        """
        return self.request("GET", "prepaycard/prices/foruser")

    def get_prepay_order_filters(self) -> Any:
        return self.request("GET", "prepaycard/filter/orderby")

    def get_prepay_status_filters(self) -> Any:
        return self.request("GET", "prepaycard/filter/status")

    def get_prepaid_orders_history(
        self,
        *,
        start_date: str = "",
        end_date: str = "",
        batch_no: str = "",
        deposit_password: str = "",
    ) -> Any:
        """
        Business → Orders.

        Endpoint:
            GET /prepaycard/order/list

        The mobile app asks for deposit password before showing orders.
        """
        params: Dict[str, Any] = {}
        if start_date:
            params["StartDate"] = start_date
        if end_date:
            params["EndDate"] = end_date
        if batch_no:
            params["BatchNo"] = batch_no
        if deposit_password:
            params["DepositPassword"] = deposit_password
        return self.request("GET", "prepaycard/order/list", params=params)

    def get_account_statement(
        self,
        *,
        start_index: int = 0,
        row_count: int = 10,
        query: str = "",
        operation_type: str = "",
        from_date: str = "",
        to_date: str = "",
        deposit_password: str = "",
        target_affiliate_index: str = "",
        batch_no: str = "",
    ) -> Any:
        """
        Business → Transactions.

        Endpoint:
            GET /affiliate/deposit/accountStatement

        Query params from APK:
            StartIndex, RowCount, Query, OperationType, fromDate, toDate,
            DepositPassword, TargetAffiliateIndex, BatchNo
        """
        params: Dict[str, Any] = {
            "StartIndex": str(start_index),
            "RowCount": str(row_count),
        }
        optional = {
            "Query": query,
            "OperationType": operation_type,
            "fromDate": from_date,
            "toDate": to_date,
            "DepositPassword": deposit_password,
            "TargetAffiliateIndex": target_affiliate_index,
            "BatchNo": batch_no,
        }
        for key, value in optional.items():
            if value not in (None, ""):
                params[key] = value
        return self.request("GET", "affiliate/deposit/accountStatement", params=params)

    def get_affiliates(self) -> Any:
        """
        Business → Transfer Balance target affiliate lookup.

        Endpoint:
            GET /affiliates
        """
        return self.request("GET", "affiliates")

    def get_subaffiliates(self) -> Any:
        """
        Business → Change Card Affiliate / transfer-related helper.

        Endpoint:
            GET /subaffiliates
        """
        return self.request("GET", "subaffiliates")

    def change_card_affiliate(
        self,
        *,
        start_serial: str,
        affiliate_type_id: str,
        target_affiliate: str,
        items_count: str,
    ) -> Any:
        """
        Business → Change Card Affiliate.

        Endpoint path is misspelled in backend as declared in APK:
            POST /prepaycard/changecardffiliate
        """
        return self.request(
            "POST",
            "prepaycard/changecardffiliate",
            data={
                "StartSerial": start_serial,
                "AffiliateTypeID": affiliate_type_id,
                "TargetAffiliate": target_affiliate,
                "ItemsCount": items_count,
            },
        )

    def show_user_password(self, user_index: str, user_id: str) -> Any:
        body = {
            "userindex": int(user_index) if str(user_index).isdigit() else user_index,
            "userid": user_id,
        }
        return self.request("POST", "user/showpassword", json_body=body)

    def show_account_password(self, user_index: str, user_id: str) -> Any:
        body = {
            "userindex": int(user_index) if str(user_index).isdigit() else user_index,
            "userid": user_id,
        }
        return self.request("POST", "user/showaccountpassword", json_body=body)

    def change_user_password(self, user_index: str, user_id: str, new_password: str) -> Any:
        body = {
            "userindex": int(user_index) if str(user_index).isdigit() else user_index,
            "userid": user_id,
            "NewPassword": new_password,
        }
        return self.request("POST", "user/changepassword", json_body=body)

    def change_account_password(self, user_index: str, user_id: str, new_password: str) -> Any:
        body = {
            "userindex": int(user_index) if str(user_index).isdigit() else user_index,
            "userid": user_id,
            "NewPassword": new_password,
        }
        return self.request("POST", "user/changeaccountpassword", json_body=body)

    def extend_user(self, user_index: str) -> Any:
        """
        Extend user using app API.
        Business rules are enforced by the server.
        """
        return self.request("POST", f"user/extend/{user_index}", json_body={})

    def clean_user_update_payload(self, user_index: str, user_object: Dict[str, Any]) -> Dict[str, Any]:
        """
        Remove CLI-only/runtime helper fields before POST /user/{userIndex}.

        Also force top-level userIndex to the real path index. Some GET /user
        responses contain top-level userIndex=0 while userObject.userIndex has
        the real value. Posting to /user/0 breaks updates.
        """
        runtime_only = {
            "_previousNonAgentStatus",
            "currentSession",
            "onlineSince",
            "currentMac",
            "onlineSession",
            "OnlineSession",
            "userIP",
            "UserIP",
            "routerIp",
            "RouterIp",
            "routerIP",
            "RouterIP",
            "currentIP",
            "CurrentIP",
            "currentIp",
            "CurrentIp",
            "usageTime",
            "UsageTime",
            "loginTime",
            "LoginTime",
            "totalUpload",
            "TotalUpload",
            "totalDownload",
            "TotalDownload",
            "totalUploadValue",
            "TotalUploadValue",
            "totalDownloadValue",
            "TotalDownloadValue",
            "userMac",
            "UserMac",
            "userIp",
            "UserIp",
            "isOnline",
            "IsOnline",
            "sessionHasIssue",
            "SessionHasIssue",
            "hasNoInternet",
            "HasNoInternet",
        }

        cleaned: Dict[str, Any] = {}

        for key, value in user_object.items():
            if key.startswith("_") or key in runtime_only:
                continue
            cleaned[key] = value

        # Force correct top-level userIndex.
        if str(user_index).isdigit():
            cleaned["userIndex"] = int(user_index)
        else:
            cleaned["userIndex"] = user_index

        # Keep nested userObject consistent if present.
        nested = cleaned.get("userObject") or cleaned.get("UserObject")
        if isinstance(nested, dict):
            nested = dict(nested)
            if str(user_index).isdigit():
                nested["userIndex"] = int(user_index)
            else:
                nested["userIndex"] = user_index

            if "userObject" in cleaned:
                cleaned["userObject"] = nested
            else:
                cleaned["UserObject"] = nested

        return cleaned

    def update_user(self, user_index: str, user_object: Dict[str, Any]) -> Any:
        """
        Update user by POSTing the full user object back to /user/{userIndex}.

        The mobile app uses POST /user/{realUserIndex}. Never post to /user/0.
        """
        if not user_index or str(user_index) == "0":
            raise ApiError(f"Invalid userIndex for update: {user_index!r}")

        payload = self.clean_user_update_payload(user_index, user_object)
        return self.request("POST", f"user/{user_index}", json_body=payload)


    def clean_user_toggle_payload(self, user_index: str, user_object: Dict[str, Any]) -> Dict[str, Any]:
        """
        App-like payload for the Active toggle.

        HAR analysis:
            The mobile app POST /user/{index} includes:
              - onlineSession when the user is online
              - no null fields such as callerID/maxmac
              - no CLI runtime helper fields
              - no currentMac/userIP duplicate fields outside onlineSession

        The backend returned success but ignored our status change when the
        payload shape differed too much. Keep this shape close to the app.
        """
        runtime_only = {
            "_previousNonAgentStatus",
            "currentSession",
            "onlineSince",
            "currentMac",
            "usageTime",
            "UsageTime",
            "loginTime",
            "LoginTime",
            "totalUpload",
            "TotalUpload",
            "totalDownload",
            "TotalDownload",
            "totalUploadValue",
            "TotalUploadValue",
            "totalDownloadValue",
            "TotalDownloadValue",
            "userMac",
            "UserMac",
            "userIp",
            "UserIp",
            "userIP",
            "UserIP",
            "routerIp",
            "RouterIp",
            "routerIP",
            "RouterIP",
            "currentIP",
            "CurrentIP",
            "currentIp",
            "CurrentIp",
            "isOnline",
            "IsOnline",
            "sessionHasIssue",
            "SessionHasIssue",
            "hasNoInternet",
            "HasNoInternet",
        }

        def clean_value(value: Any) -> Any:
            if isinstance(value, dict):
                return clean_dict(value)
            if isinstance(value, list):
                return [clean_value(item) for item in value if item not in (None, "")]
            return value

        def clean_dict(data: Dict[str, Any]) -> Dict[str, Any]:
            cleaned: Dict[str, Any] = {}
            for key, value in data.items():
                if key.startswith("_") or key in runtime_only:
                    continue
                if value is None or value == "":
                    continue
                cleaned[key] = clean_value(value)
            return cleaned

        cleaned = clean_dict(user_object)

        # Force correct top-level userIndex.
        cleaned["userIndex"] = int(user_index) if str(user_index).isdigit() else user_index

        # Keep nested userObject consistent if present.
        nested = cleaned.get("userObject") or cleaned.get("UserObject")
        if isinstance(nested, dict):
            nested = dict(nested)
            nested["userIndex"] = int(user_index) if str(user_index).isdigit() else user_index
            if "userObject" in cleaned:
                cleaned["userObject"] = nested
            else:
                cleaned["UserObject"] = nested

        # If onlineSession exists, keep it but force index and strip displayName nulls.
        session = cleaned.get("onlineSession") or cleaned.get("OnlineSession")
        if isinstance(session, dict):
            session = dict(session)
            session["userIndex"] = int(user_index) if str(user_index).isdigit() else user_index
            session = clean_dict(session)
            if "onlineSession" in cleaned:
                cleaned["onlineSession"] = session
            else:
                cleaned["OnlineSession"] = session

        return cleaned

    def update_user_toggle(self, user_index: str, user_object: Dict[str, Any]) -> Any:
        """
        POST a mobile-app-shaped payload for activate/deactivate.
        """
        if not user_index or str(user_index) == "0":
            raise ApiError(f"Invalid userIndex for toggle update: {user_index!r}")

        payload = self.clean_user_toggle_payload(user_index, user_object)
        return self.request("POST", f"user/{user_index}", json_body=payload)

    @staticmethod
    def set_existing_or_first(obj: Dict[str, Any], keys: List[str], value: Any) -> None:
        """
        Set all keys that already exist. If none exist, set the first key.
        """
        found = False
        for key in keys:
            if key in obj:
                obj[key] = value
                found = True
        if not found and keys:
            obj[keys[0]] = value

    def update_display_name(self, user_index: str, user_object: Dict[str, Any], new_display_name: str) -> Any:
        updated = dict(user_object)
        self.set_existing_or_first(updated, ["displayName", "DisplayName"], new_display_name)

        # Some payloads also carry ArName as the visible Arabic/name field.
        if "arName" in updated or "ArName" in updated:
            self.set_existing_or_first(updated, ["arName", "ArName"], new_display_name)

        return self.update_user(user_index, updated)

    def update_mac_lock(self, user_index: str, user_object: Dict[str, Any], mac_value: str) -> Any:
        """
        Set or clear MAC lock. Empty string clears/opens MAC lock.
        """
        updated = dict(user_object)

        # Different API objects may expose callerID/MAXMAC with different casing.
        for keys in (
            ["callerID", "CallerID"],
            ["maxmac", "MAXMAC", "maxMac", "MaxMac"],
        ):
            if any(k in updated for k in keys):
                self.set_existing_or_first(updated, keys, mac_value)

        # If neither existed, use the most likely API field.
        if not any(k in updated for k in ["callerID", "CallerID", "maxmac", "MAXMAC", "maxMac", "MaxMac"]):
            updated["callerID"] = mac_value

        return self.update_user(user_index, updated)

    def update_user_active(self, user_index: str, user_object: Dict[str, Any], active: bool) -> Any:
        """
        Toggle the mobile app's "User active" switch using the latest confirmed HAR behavior.

        Confirmed from HAR:
            Deactivate request:
                accountStatus       = current status, e.g. Active / ExpiringSoon
                userActive          = true
                userActiveManage    = false
                isBlocked           = false
              Backend response after GET:
                accountStatus       = SuspendedByAgent
                userActiveManage    = false

            Activate request:
                accountStatus       = SuspendedByAgent
                userActive          = true
                userActiveManage    = true
                isBlocked           = false
              Backend response after GET:
                accountStatus       = Active / ExpiringSoon / normal state
                userActiveManage    = true

        Therefore:
            - Do NOT manually force accountStatus in the POST.
            - Set only the app toggle field userActiveManage.
            - Keep userActive=true and isBlocked=false.
            - Keep v0.6.24 app-shaped payload cleaning and onlineSession handling.
        """
        if not user_index or str(user_index) == "0":
            raise ApiError(f"Invalid userIndex for activate/deactivate: {user_index!r}")

        # Start with fresh backend object, then merge useful current context.
        updated = dict(self.get_user(user_index))

        # If fresh GET has no onlineSession but the current object has one,
        # copy it. If neither has it, try active sessions and build app-like session.
        if not isinstance(updated.get("onlineSession"), dict):
            if isinstance(user_object.get("onlineSession"), dict):
                updated["onlineSession"] = dict(user_object["onlineSession"])
            else:
                user_id = safe_get(updated, "userID", "UserID", "userId", "UserId") or safe_get(user_object, "userID", "UserID", "userId", "UserId")
                try:
                    payload = self.list_active_sessions(start_index=0, row_count=200)
                    rows, _total = payload_items_and_total(payload)
                    wanted = user_id.strip().lower()
                    for row in rows:
                        if row_user_id(row).strip().lower() != wanted:
                            continue
                        updated["onlineSession"] = {
                            "callerMAC": safe_get(row, "callerMAC", "CallerMAC", "callerID", "CallerID", "userMac", "UserMac"),
                            "hasSessionIssue": bool(row.get("hasSessionIssue", row.get("HasSessionIssue", False))),
                            "loginFrom": safe_get(row, "loginFrom", "LoginFrom"),
                            "onlineSince": safe_get(row, "onlineSince", "OnlineSince", "loginTime", "LoginTime"),
                            "onlineStatus": safe_get(row, "onlineStatus", "OnlineStatus"),
                            "onlineTime": safe_get(row, "onlineTime", "OnlineTime", "usageTime", "UsageTime"),
                            "sessionStartDate": safe_get(row, "sessionStartDate", "SessionStartDate"),
                            "userIP": safe_get(row, "userIP", "UserIP", "userIp", "UserIp"),
                            "userID": user_id,
                            "userIndex": int(user_index) if str(user_index).isdigit() else user_index,
                        }
                        break
                except Exception:
                    pass

        # Force correct top-level userIndex.
        updated["userIndex"] = int(user_index) if str(user_index).isdigit() else user_index

        nested = updated.get("userObject") or updated.get("UserObject")
        if isinstance(nested, dict):
            nested = dict(nested)
            nested["userIndex"] = int(user_index) if str(user_index).isdigit() else user_index
            if "userObject" in updated:
                updated["userObject"] = nested
            else:
                updated["UserObject"] = nested

        # Exact app toggle behavior:
        # active=True  -> userActiveManage=True
        # active=False -> userActiveManage=False
        # Do not change accountStatus manually; backend will change it.
        self.set_existing_or_first(updated, ["userActive", "UserActive"], True)
        self.set_existing_or_first(updated, ["userActiveManage", "UserActiveManage"], bool(active))

        # App keeps isBlocked false in both activate and deactivate requests.
        self.set_existing_or_first(updated, ["isBlocked", "IsBlocked"], False)

        # Do not carry CLI-only previous-status helpers.
        updated.pop("_previousNonAgentStatus", None)
        updated.pop("previousNonAgentStatus", None)
        updated.pop("PreviousNonAgentStatus", None)

        return self.update_user_toggle(user_index, updated)


    def update_account_type(
        self,
        user_index: str,
        user_object: Dict[str, Any],
        account_index: str,
        user_id: str = "",
    ) -> Any:
        """
        Change account type using the dedicated mobile-app endpoint.

        APK inventory shows the backend path is misspelled exactly:
            POST /user/chnageaccounttype

        We send multiple common field names because the endpoint was found as a
        HashMap body with unresolved keys in the decompiled APK.
        """
        value: Any = int(account_index) if str(account_index).isdigit() else account_index
        uid = user_id or safe_get(user_object, "userID", "UserID", "userId", "UserId")

        body = {
            "userindex": int(user_index) if str(user_index).isdigit() else user_index,
            "userIndex": int(user_index) if str(user_index).isdigit() else user_index,
            "userid": uid,
            "UserID": uid,
            "accountIndex": value,
            "AccountIndex": value,
            "accountId": value,
            "AccountID": value,
        }

        return self.request("POST", "user/chnageaccounttype", json_body=body)


def print_accounts(accounts: List[Dict[str, Any]]) -> None:
    if USE_RICH and console is not None and Table is not None:
        table = Table(title="Available account types", box=box.SIMPLE_HEAVY if box else None, header_style="bold cyan")
        table.add_column("No.", justify="right", style="bold")
        table.add_column("Index", justify="right")
        table.add_column("Name")
        table.add_column("Can test", justify="center")
        if accounts:
            for i, acc in enumerate(accounts, start=1):
                idx = safe_get(acc, "accountIndex", "AccountIndex", "id", "ID")
                name = safe_get(acc, "accountName", "AccountName", "name", "Name")
                can_test = acc.get("canAddWithTest", acc.get("CanAddWithTest", ""))
                table.add_row(str(i), idx, name, yes_no_text(bool(can_test)) if isinstance(can_test, bool) else str(can_test))
        console.print(table)
        if not accounts:
            console.print("[yellow]No accounts returned.[/]")
        return

    print("\nAvailable account types")
    print("=" * 72)
    if not accounts:
        print("No accounts returned.")
        return

    print(f"{'No.':<4} {'Index':<8} {'Name':<28} {'Can test':<10}")
    print("-" * 72)
    for i, acc in enumerate(accounts, start=1):
        idx = safe_get(acc, "accountIndex", "AccountIndex", "id", "ID")
        name = safe_get(acc, "accountName", "AccountName", "name", "Name")
        can_test = acc.get("canAddWithTest", acc.get("CanAddWithTest", ""))
        print(f"{i:<4} {idx:<8} {trim(name, 28):<28} {str(can_test):<10}")


def print_users(users: List[UserListItem], total_count: int) -> None:
    if USE_RICH and console is not None and Table is not None:
        table = Table(
            title=f"Users — total {total_count}",
            box=box.SIMPLE_HEAVY if box else None,
            header_style="bold cyan",
            show_lines=False,
        )
        table.add_column("No.", justify="right", style="bold")
        table.add_column("Index", justify="right", style="bright_black")
        table.add_column("UserID", style="bold")
        table.add_column("Display")
        table.add_column("Account")
        table.add_column("Status")
        table.add_column("Online")
        table.add_column("IP")

        for i, u in enumerate(users, start=1):
            table.add_row(
                str(i),
                trim(u.user_index, 10),
                trim(u.user_id, 26),
                trim(u.display_name, 22),
                trim(u.account_name, 16),
                status_text(trim(u.account_status, 14)),
                status_text(trim(u.online_status, 12)),
                trim(u.ip, 15),
            )

        console.print(table)
        if not users:
            console.print("[yellow]No users returned.[/]")
        return

    print("\nUsers")
    print("=" * 112)
    print(f"Total count: {total_count}")
    if not users:
        print("No users returned.")
        return

    print(f"{'No.':<4} {'Index':<10} {'UserID':<26} {'Display':<22} {'Account':<16} {'Status':<12} {'Online':<10} {'IP':<15}")
    print("-" * 112)
    for i, u in enumerate(users, start=1):
        print(
            f"{i:<4} "
            f"{trim(u.user_index, 10):<10} "
            f"{trim(u.user_id, 26):<26} "
            f"{trim(u.display_name, 22):<22} "
            f"{trim(u.account_name, 16):<16} "
            f"{trim(u.account_status, 12):<12} "
            f"{trim(u.online_status, 10):<10} "
            f"{trim(u.ip, 15):<15}"
        )


def display_value(value: Any, default: str = "N/A") -> str:
    if value is None:
        return default

    text_value = str(value).strip()
    if text_value == "":
        return default

    return text_value


def secure_value() -> str:
    return "******** (secure)"


def user_customer(user: Dict[str, Any]) -> Dict[str, Any]:
    customer = user.get("customer") or user.get("Customer") or {}
    return customer if isinstance(customer, dict) else {}


def user_phone(user: Dict[str, Any]) -> str:
    customer = user_customer(user)
    return (
        safe_get(user, "mobileNumber", "MobileNumber")
        or safe_get(customer, "customerPhoneNumber", "CustomerPhoneNumber")
    )


def user_second_phone(user: Dict[str, Any]) -> str:
    customer = user_customer(user)
    return (
        safe_get(user, "mobileNumber2", "MobileNumber2")
        or safe_get(customer, "customerSecondPhoneNumber", "CustomerSecondPhoneNumber")
    )


def user_full_name(user: Dict[str, Any]) -> str:
    customer = user_customer(user)
    return (
        safe_get(customer, "customerFullName", "CustomerFullName")
        or safe_get(user, "fullName", "FullName", "arName", "ArName", "enName", "EnName")
    )


def first_user_value(user: Dict[str, Any], keys: List[str]) -> str:
    for key in keys:
        value = user.get(key)
        if value not in (None, ""):
            return str(value)
    return ""


def is_valid_user_index_value(value: Any) -> bool:
    text_value = str(value).strip()
    return text_value not in {"", "0", "None", "none", "null"}


def merge_nonempty_dicts(*dicts: Dict[str, Any]) -> Dict[str, Any]:
    """
    Merge dictionaries while preserving useful runtime fields from list/session rows.

    Important:
        Some GET /user responses return top-level userIndex=0 while the real
        userIndex is in userObject.userIndex or the previous list row. Do not
        let userIndex=0 overwrite a real user index.
    """
    merged: Dict[str, Any] = {}

    for data in dicts:
        if not isinstance(data, dict):
            continue

        # Flatten the nested userObject from list rows; keep outer fields too.
        user_obj = data.get("userObject") or data.get("UserObject")
        if isinstance(user_obj, dict):
            for key, value in user_obj.items():
                if value in (None, ""):
                    continue
                if key in {"userIndex", "UserIndex"} and not is_valid_user_index_value(value):
                    continue
                merged[key] = value

        for key, value in data.items():
            if value in (None, ""):
                continue

            if key in {"userIndex", "UserIndex"} and not is_valid_user_index_value(value):
                # Preserve an already-known real index.
                continue

            merged[key] = value

    return merged


def user_current_ip(user: Dict[str, Any]) -> str:
    online_session = user.get("onlineSession") or user.get("OnlineSession") or {}
    if isinstance(online_session, dict):
        session_ip = safe_get(
            online_session,
            "userIP",
            "UserIP",
            "userIp",
            "UserIp",
            "ipAddress",
            "IPAddress",
        )
        if session_ip:
            return session_ip

    return first_user_value(
        user,
        [
            "currentIP",
            "CurrentIP",
            "currentIp",
            "CurrentIp",
            "userIP",
            "UserIP",
            "userIp",
            "UserIp",
            "routerIp",
            "RouterIp",
            "routerIP",
            "RouterIP",
            "ipAddress",
            "IPAddress",
        ],
    )


def user_current_mac(user: Dict[str, Any]) -> str:
    return first_user_value(
        user,
        [
            "currentMAC",
            "CurrentMAC",
            "currentMac",
            "CurrentMac",
            "sessionMAC",
            "SessionMAC",
            "sessionMac",
            "SessionMac",
            "macAddress",
            "MacAddress",
            "MACAddress",
            "callerID",
            "CallerID",
            "callingStationID",
            "CallingStationID",
            "callingStationId",
            "CallingStationId",
            "maxmac",
            "MAXMAC",
            "maxMac",
            "MaxMac",
        ],
    )


def user_account_mac(user: Dict[str, Any]) -> str:
    """
    Account MAC lock.

    Avoid currentMac/userMac/session MAC here. Those belong to the session tab.
    """
    return first_user_value(
        user,
        [
            "maxmac",
            "MAXMAC",
            "maxMac",
            "MaxMac",
            "lockedMac",
            "LockedMac",
            "macLock",
            "MacLock",
            "callerID",
            "CallerID",
        ],
    )


def user_current_session(user: Dict[str, Any]) -> str:
    online_session = user.get("onlineSession") or user.get("OnlineSession") or {}
    if isinstance(online_session, dict):
        session_time = safe_get(
            online_session,
            "onlineTime",
            "OnlineTime",
            "usageTime",
            "UsageTime",
            "sessionTime",
            "SessionTime",
        )
        if session_time:
            return session_time

    return first_user_value(
        user,
        [
            "currentSession",
            "CurrentSession",
            "usageTime",
            "UsageTime",
            "sessionTime",
            "SessionTime",
            "onlineTime",
            "OnlineTime",
            "onlineSince",
            "OnlineSince",
        ],
    )


def parse_bool_like(value: Any) -> Optional[bool]:
    if isinstance(value, bool):
        return value

    if value is None:
        return None

    normalized = str(value).strip().lower()
    if normalized in {"true", "1", "yes", "active", "enabled", "on"}:
        return True
    if normalized in {"false", "0", "no", "inactive", "disabled", "off"}:
        return False

    return None


def user_active_bool(user: Dict[str, Any]) -> Optional[bool]:
    """
    Return the mobile app's User active switch state.

    Confirmed from latest HAR:
        accountStatus == SuspendedByAgent -> OFF
        userActiveManage == false        -> OFF
        userActiveManage == true         -> ON

    accountStatus is the safest backend result after the toggle. userActiveManage
    is the actual field sent by the app.
    """
    status = safe_get(user, "accountStatus", "AccountStatus").strip().lower()

    if status == "suspendedbyagent":
        return False

    for key in ("userActiveManage", "UserActiveManage"):
        if key in user:
            state = parse_bool_like(user.get(key))
            if state is not None:
                return state

    if status:
        return True

    return None


def user_active_display(user: Dict[str, Any]) -> str:
    state = user_active_bool(user)
    status = safe_get(user, "accountStatus", "AccountStatus").strip()

    if state is True:
        return "Yes (reseller access enabled)"
    if state is False:
        return "No (disabled by reseller / SuspendedByAgent)"

    return "Unknown"


def change_account_status_policy(user: Dict[str, Any]) -> Tuple[str, str]:
    """
    Return (policy, status).

    policy:
        "direct"   -> proceed normally
        "warning"  -> likely allowed in some cases; ask confirmation
        "override" -> usually rejected; allow manual attempt only after warning

    Real-world tests:
        - Suspended/expired user changed successfully.
        - ExpiringSoon can be allowed near final hours before expiry.
        - Active users are usually rejected by the server.
    """
    status = safe_get(user, "accountStatus", "AccountStatus").strip()
    normalized = status.lower()

    direct_statuses = {
        "suspended",
        "expired",
        "recentlyexpired",
        "suspendedbyagent",
    }

    if normalized in direct_statuses:
        return "direct", status or "Unknown"

    if normalized == "expiringsoon":
        return "warning", status or "Unknown"

    return "override", status or "Unknown"



def runtime_context_from_session_row(row: Dict[str, Any]) -> Dict[str, Any]:
    """
    Extract runtime/session values without polluting editable account fields.

    Important:
        `callerID` in list/session rows can be the current connected MAC, not
        the account MAC lock. Never merge it into callerID/maxmac automatically,
        otherwise a normal save/deactivate can accidentally add a MAC lock.
    """
    context = {
        "currentSession": safe_get(row, "usageTime", "UsageTime", "sessionTime", "SessionTime", "onlineTime", "OnlineTime"),
        "onlineSince": safe_get(row, "loginTime", "LoginTime", "onlineSince", "OnlineSince"),
        "userIP": safe_get(row, "userIp", "UserIp", "userIP", "UserIP", "ipAddress", "IPAddress"),
        "currentMac": safe_get(
            row,
            "currentMac",
            "CurrentMac",
            "callerMAC",
            "CallerMAC",
            "userMac",
            "UserMac",
            "callerID",
            "CallerID",
            "callingStationID",
            "CallingStationID",
            "callingStationId",
            "CallingStationId",
            "macAddress",
            "MacAddress",
            "MACAddress",
        ),
    }

    return {k: v for k, v in context.items() if v not in (None, "")}


def hydrate_user_runtime_data(client: EarthlinkAppApiClient, user: Dict[str, Any]) -> Dict[str, Any]:
    """
    /user/{index} sometimes omits runtime fields that the app gets from list/session
    payloads: current IP, current MAC, current session/usage time. Pull one active
    session page and merge the matching row when possible.
    """
    merged = dict(user)
    user_id = safe_get(merged, "userID", "UserID", "userId", "UserId")

    if not user_id:
        return merged

    try:
        payload = client.list_active_sessions(start_index=0, row_count=200)
        rows, _total = payload_items_and_total(payload)

        wanted = user_id.strip().lower()
        for row in rows:
            if row_user_id(row).strip().lower() != wanted:
                continue

            session_context = runtime_context_from_session_row(row)
            merged = merge_nonempty_dicts(merged, session_context)
            break
    except Exception:
        # Runtime hydration is best-effort; user details should still open.
        pass

    return merged


def print_kv_section(title: str, fields: List[Tuple[str, Any]]) -> None:
    if USE_RICH and console is not None and Table is not None and Panel is not None:
        table = Table.grid(padding=(0, 2))
        table.add_column(style="bold cyan", no_wrap=True)
        table.add_column()
        for label, value in fields:
            display = display_value(value)
            if "status" in label.lower():
                display = status_text(display)
            table.add_row(f"{label}", display)
        console.print(compact_panel(table, title=title, border_style="cyan"))
        return

    print("\n" + title)
    print("-" * 72)
    for label, value in fields:
        print(f"{label:<24}: {display_value(value)}")


def print_user_header(user: Dict[str, Any]) -> None:
    user_id = safe_get(user, "userID", "UserID", "userId", "UserId")
    display_name = safe_get(user, "displayName", "DisplayName")
    account_name = safe_get(user, "accountName", "AccountName", "groupName", "GroupName")
    account_status = safe_get(user, "accountStatus", "AccountStatus")
    online_status = safe_get(user, "onlineStatus", "OnlineStatus")
    ip_address = user_current_ip(user)
    current_session = user_current_session(user)
    phone = user_phone(user)

    active_days = safe_get(user, "activeDaysLeft", "ActiveDaysLeft")
    expiry = safe_get(user, "manualExpirationDate", "ManualExpirationDate", "accountExpirationDate", "AccountExpirationDate")

    expiry_text = expiry
    if expiry and active_days:
        expiry_text = f"{expiry} ({active_days}d)"

    title_name = display_value(display_name, "N/A")

    if USE_RICH and console is not None and Table is not None and Panel is not None:
        table = Table.grid(padding=(0, 2))
        table.add_column(style="bold cyan", no_wrap=True, width=18)
        table.add_column(ratio=1)
        table.add_column(style="bold cyan", no_wrap=True, width=14)
        table.add_column(ratio=1)

        table.add_row("Username", display_value(user_id), "Display", display_value(display_name))
        table.add_row("Status", status_text(account_status), "Online", status_text(online_status))
        table.add_row("Package", display_value(account_name), "Expiry", display_value(expiry_text))
        table.add_row("IP", display_value(ip_address), "Session", display_value(current_session))
        table.add_row("Phone", display_value(phone), "", "")

        border = style_for_status(account_status)
        console.print(compact_panel(table, title=f"User ({title_name}) information", border_style=border))
        return

    print(f"\nUser ({title_name}) information")
    print("-" * 72)
    print(f"{'Username':<24}: {display_value(user_id)}")
    print(f"{'Display name':<24}: {display_value(display_name)}")
    print(f"{'Online status':<24}: {display_value(online_status)}")
    print(f"{'Subscription status':<24}: {display_value(account_status)}")
    print(f"{'IP address':<24}: {display_value(ip_address)}")
    print(f"{'Subscription name':<24}: {display_value(account_name)}")
    print(f"{'Subscription expiry':<24}: {display_value(expiry_text)}")
    print(f"{'Current session':<24}: {display_value(current_session)}")
    print(f"{'Phone':<24}: {display_value(phone)}")


def print_user_account_section(user: Dict[str, Any]) -> None:
    customer = user_customer(user)
    print_kv_section(
        "Account information",
        [
            ("Username", safe_get(user, "userID", "UserID", "userId", "UserId")),
            ("User password", secure_value()),
            ("Account password", secure_value()),
            ("MAC address", user_account_mac(user)),
            ("Display name", safe_get(user, "displayName", "DisplayName")),
            ("Notes", safe_get(user, "userNotes", "UserNotes", "notes", "Notes")),
            ("Nano/Router - IP", safe_get(user, "routerIp", "RouterIp", "routerIP", "RouterIP")),
            ("Full name", user_full_name(user)),
            ("Phone", user_phone(user)),
            ("Phone (secondary)", user_second_phone(user)),
            ("Email", safe_get(customer, "email", "Email")),
            ("Address", safe_get(customer, "address", "Address")),
        ],
    )


def print_user_subscription_section(user: Dict[str, Any]) -> None:
    active_days = safe_get(user, "activeDaysLeft", "ActiveDaysLeft")
    expiry = safe_get(user, "manualExpirationDate", "ManualExpirationDate", "accountExpirationDate", "AccountExpirationDate")
    expiry_text = expiry
    if expiry and active_days:
        expiry_text = f"{expiry} ({active_days}d)"

    print_kv_section(
        "Subscription information",
        [
            ("Affiliate name", safe_get(user, "affiliateName", "AffiliateName", "agentName", "AgentName")),
            ("Subscription name", safe_get(user, "accountName", "AccountName", "groupName", "GroupName")),
            ("Subscription status", safe_get(user, "accountStatus", "AccountStatus")),
            ("Subscription expiry", expiry_text),
            ("Last refill date", safe_get(user, "lastRefill", "LastRefill")),
            ("Last payment date", safe_get(user, "lastPayment", "LastPayment")),
            ("User active", user_active_display(user)),
            ("Can refill", yes_no(user.get("canRefill", user.get("CanRefill", False)))),
            ("Can extend", yes_no(user.get("canExtendUser", user.get("CanExtendUser", False)))),
            ("Can change account", yes_no(user.get("canChangeAccount", user.get("CanChangeAccount", False)))),
        ],
    )


def print_user_session_section(user: Dict[str, Any]) -> None:
    print_kv_section(
        "Connection / sessions information",
        [
            ("Current session", user_current_session(user)),
            ("Last login date", safe_get(user, "lastLoginDate", "LastLoginDate") or safe_get(user, "lastLogin", "LastLogin")),
            ("Online status", safe_get(user, "onlineStatus", "OnlineStatus")),
            ("User ID", safe_get(user, "userID", "UserID", "userId", "UserId")),
            ("MAC address", user_current_mac(user)),
            ("IP address", user_current_ip(user)),
            ("Service status", safe_get(user, "serviceStatusColor", "ServiceStatusColor", "serviceStatusColorHex", "ServiceStatusColorHex")),
        ],
    )


def print_user_payments_section(user: Dict[str, Any]) -> None:
    print_kv_section(
        "Payments information",
        [
            ("Last payment", safe_get(user, "lastPayment", "LastPayment")),
            ("Unpaid invoices", safe_get(user, "unPaidInvoices", "UnPaidInvoices", "unpaidInvoices", "UnpaidInvoices")),
            ("Overdue invoices", safe_get(user, "overDuePayments", "OverDuePayments", "overduePayments", "OverduePayments")),
            ("Last invoice notes", safe_get(user, "lastInvoiceNotes", "LastInvoiceNotes")),
            ("Customer ID", safe_get(user, "customerId", "CustomerId")),
        ],
    )


def print_user_detail(user: Dict[str, Any]) -> None:
    if not user:
        print("\nUser Details")
        print("=" * 72)
        print("No user details returned.")
        return

    print_user_header(user)


def choose_account(accounts: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    print_accounts(accounts)
    if not accounts:
        return None

    choice = input("\nChoose account no. (B/Enter = back): ").strip().lower()
    if choice in {"", "b"}:
        return None

    if not choice.isdigit() or not (1 <= int(choice) <= len(accounts)):
        print("Invalid choice.")
        return None

    return accounts[int(choice) - 1]


def unwrap_display_value(payload: Any) -> Any:
    if isinstance(payload, dict):
        if "value" in payload:
            return payload["value"]
        if "Value" in payload:
            return payload["Value"]
        if "responseMessage" in payload and payload.get("responseMessage"):
            return payload.get("responseMessage")
        if "ResponseMessage" in payload and payload.get("ResponseMessage"):
            return payload.get("ResponseMessage")
    return payload


def print_customers(customers: List[Dict[str, Any]], raw: Any) -> None:
    print("\nCustomer phone lookup")
    print("=" * 72)

    if not customers:
        print("No customer records returned.")
        print("\nRaw response:")
        print_json(raw)
        return

    print(f"{'No.':<4} {'Customer ID':<14} {'Name':<30} {'Phone':<16} {'Second phone'}")
    print("-" * 72)

    for i, c in enumerate(customers, start=1):
        cid = safe_get(c, "customerId", "CustomerId", "ID", "id")
        name = safe_get(c, "customerFullName", "CustomerFullName", "name", "Name")
        phone = safe_get(c, "customerPhoneNumber", "CustomerPhoneNumber", "phoneNumber", "PhoneNumber")
        phone2 = safe_get(c, "customerSecondPhoneNumber", "CustomerSecondPhoneNumber")
        print(f"{i:<4} {trim(cid, 14):<14} {trim(name, 30):<30} {trim(phone, 16):<16} {trim(phone2, 16)}")


def parse_money_number(value: Any) -> float:
    if value is None:
        return 0.0

    if isinstance(value, (int, float)):
        return float(value)

    text_value = str(value).strip()
    if not text_value:
        return 0.0

    text_value = (
        text_value.replace("IQD", "")
        .replace(",", "")
        .replace(" ", "")
        .strip()
    )

    try:
        return float(text_value)
    except ValueError:
        return 0.0


def fmt_iqd(value: Any) -> str:
    number = parse_money_number(value)

    if abs(number - round(number)) < 0.000001:
        return f"{int(round(number)):,} IQD"

    return f"{number:,.2f} IQD"


def get_first_number(row: Dict[str, Any], *keys: str) -> float:
    for key in keys:
        if key in row and row.get(key) not in (None, ""):
            return parse_money_number(row.get(key))
    return 0.0


def print_prepaid_needed(
    payload: Any,
    days: Optional[int] = None,
    current_balance: Optional[Any] = None,
) -> None:
    print("\nPrepaid needed forecast")
    print("=" * 96)
    if days is not None:
        print(f"Days: {days}")

    value = unwrap_display_value(payload)

    rows: List[Dict[str, Any]] = []

    if isinstance(value, list):
        rows = [x for x in value if isinstance(x, dict)]
    elif isinstance(value, dict):
        for key in ("itemsList", "ItemsList", "items", "Items", "rows", "Rows", "prepaidNeeded", "PrepaidNeeded"):
            if isinstance(value.get(key), list):
                rows = [x for x in value[key] if isinstance(x, dict)]
                break

    if not rows:
        print_json(payload)
        return

    grand_total = 0.0

    print(f"{'No.':<4} {'Account':<24} {'Expire':<8} {'Available':<10} {'Needed':<8} {'Cost':<16} {'Total'}")
    print("-" * 96)

    for i, row in enumerate(rows, start=1):
        account = safe_get(row, "accountType", "AccountType", "accountName", "AccountName", "name", "Name")

        expire = safe_get(
            row,
            "expireUsersCount",
            "ExpireUsersCount",
            "expireCount",
            "ExpireCount",
            "expiringUsers",
            "ExpiringUsers",
        )

        available = safe_get(row, "availableCards", "AvailableCards", "available", "Available")

        needed = safe_get(row, "neededCount", "NeededCount", "needed", "Needed")

        cost_raw = safe_get(row, "accountCost", "AccountCost", "cost", "Cost")
        total_raw = safe_get(row, "totalCost", "TotalCost", "total", "Total")

        needed_num = get_first_number(row, "neededCount", "NeededCount", "needed", "Needed")
        cost_num = get_first_number(row, "accountCost", "AccountCost", "cost", "Cost")
        total_num = get_first_number(row, "totalCost", "TotalCost", "total", "Total")

        if total_num <= 0 and needed_num > 0 and cost_num > 0:
            total_num = needed_num * cost_num

        grand_total += total_num

        print(
            f"{i:<4} "
            f"{trim(account, 24):<24} "
            f"{trim(expire, 8):<8} "
            f"{trim(available, 10):<10} "
            f"{trim(needed, 8):<8} "
            f"{fmt_iqd(cost_num or cost_raw):<16} "
            f"{fmt_iqd(total_num or total_raw)}"
        )

    print("-" * 96)
    print(f"{'Total needed cost':<68}: {fmt_iqd(grand_total)}")

    if current_balance is not None and str(current_balance).strip() != "":
        balance_num = parse_money_number(current_balance)
        remaining = balance_num - grand_total
        print(f"{'Current balance':<68}: {fmt_iqd(balance_num)}")
        print(f"{'Remaining after forecast':<68}: {fmt_iqd(remaining)}")


def extract_user_identity(user: Dict[str, Any]) -> Tuple[str, str]:
    """
    Extract userIndex/userID safely.

    Some app API user-detail responses have:
        top-level userIndex = 0
        userObject.userIndex = real index

    Treat 0 as missing.
    """
    user_obj = user.get("userObject") or user.get("UserObject") or {}
    if not isinstance(user_obj, dict):
        user_obj = {}

    user_index = ""

    for candidate in (
        safe_get(user, "userIndex", "UserIndex"),
        safe_get(user_obj, "userIndex", "UserIndex"),
    ):
        if is_valid_user_index_value(candidate):
            user_index = str(candidate).strip()
            break

    user_id = (
        safe_get(user, "userID", "UserID", "userId", "UserId")
        or safe_get(user_obj, "userID", "UserID", "userId", "UserId")
    )

    return user_index, user_id


def prepaid_grand_total_from_payload(payload: Any) -> float:
    value = unwrap_display_value(payload)
    rows: List[Dict[str, Any]] = []

    if isinstance(value, list):
        rows = [x for x in value if isinstance(x, dict)]
    elif isinstance(value, dict):
        for key in ("itemsList", "ItemsList", "items", "Items", "rows", "Rows", "prepaidNeeded", "PrepaidNeeded"):
            if isinstance(value.get(key), list):
                rows = [x for x in value[key] if isinstance(x, dict)]
                break

    total = 0.0
    for row in rows:
        needed_num = get_first_number(row, "neededCount", "NeededCount", "needed", "Needed")
        cost_num = get_first_number(row, "accountCost", "AccountCost", "cost", "Cost")
        total_num = get_first_number(row, "totalCost", "TotalCost", "total", "Total")

        if total_num <= 0 and needed_num > 0 and cost_num > 0:
            total_num = needed_num * cost_num

        total += total_num

    return total


def extract_account_cost_value(result: Any) -> Tuple[Optional[float], str]:
    """
    Extract account cost from either successful value object or unsuccessful
    server message, e.g.:
        Account cost is 90,000 IQD, your current balance is 85250
    """
    if isinstance(result, dict):
        value = result.get("value")
        if isinstance(value, dict) and value.get("value") is not None:
            return parse_money_number(value.get("value")), ""

        error = result.get("error")
        message = ""

        if isinstance(error, dict):
            message = str(error.get("message") or error.get("detailMessage") or "")
        elif error:
            message = str(error)
        else:
            message = str(result.get("responseMessage") or "")

        if message:
            m = re.search(r"Account cost is\s*([0-9,]+)", message, flags=re.IGNORECASE)
            if m:
                return parse_money_number(m.group(1)), message

            m = re.search(r"Subscription price[:\s]+([0-9,]+)", message, flags=re.IGNORECASE)
            if m:
                return parse_money_number(m.group(1)), message

        return None, message

    return None, ""


def print_payment_preview(
    *,
    user_id: str,
    account_name: str,
    account_index: str,
    balance: Any,
    account_cost_response: Any,
) -> None:
    """
    Match the mobile app payment/refill screen.
    """
    cost_value, server_message = extract_account_cost_value(account_cost_response)
    balance_value = parse_money_number(balance)
    enough_balance = cost_value is not None and balance_value >= cost_value

    if USE_RICH and console is not None and Table is not None and Panel is not None:
        table = Table.grid(padding=(0, 2))
        table.add_column(style="bold cyan", no_wrap=True)
        table.add_column()
        table.add_row("User", user_id)
        table.add_row("Subscription", f"{account_name} ({account_index})")
        table.add_row("Available balance", money_text(balance_value))
        if cost_value is None:
            table.add_row("Subscription price", "(not returned)")
        else:
            table.add_row("Subscription price", money_text(cost_value))
            table.add_row("Balance after buy", money_text(balance_value - cost_value))
        table.add_row("Status", "[green]Balance is enough[/]" if enough_balance else "[red]Not enough balance[/]")
        if server_message:
            table.add_row("Server message", str(server_message))
        console.print(compact_panel(table, title="Payment preview", border_style="green" if enough_balance else "red"))
        return

    print("\nPayment preview")
    print("=" * 72)
    print(f"User                : {user_id}")
    print(f"Subscription        : {account_name} ({account_index})")
    print(f"Available balance   : {fmt_iqd(balance_value)}")

    if cost_value is None:
        print("Subscription price  : (not returned)")
    else:
        print(f"Subscription price  : {fmt_iqd(cost_value)}")
        print(f"Balance after buy   : {fmt_iqd(balance_value - cost_value)}")

    if server_message:
        print("\nServer message")
        print("-" * 72)
        print(server_message)

    if cost_value is not None and balance_value < cost_value:
        print("\nStatus              : Not enough balance")
    elif cost_value is not None:
        print("\nStatus              : Balance is enough")

    if isinstance(account_cost_response, dict) and account_cost_response.get("isSuccessful") is False:
        return


def safe_status_value(label: str, func: Any, default: str = "?") -> str:
    try:
        value = func()
        if value is None or value == "":
            return default
        if label == "balance":
            return fmt_iqd(value)
        return str(value)
    except Exception:
        return default


def get_total_users_quick(client: EarthlinkAppApiClient) -> str:
    try:
        _users, total_count, _raw = client.list_users(start_index=0, row_count=1)
        return str(total_count)
    except Exception:
        return "?"


def print_app_status_header(client: EarthlinkAppApiClient) -> None:
    """
    Always-visible compact CLI status header.

    Keep it one compact card:
        title line + single status line

    This avoids the old squeezed 5-column issue without making the header too tall.
    """
    balance_raw: Any = None

    try:
        balance_raw = client.get_balance()
        balance = fmt_iqd(balance_raw)
    except Exception:
        balance = "?"

    free_tests = safe_status_value("testcount", client.get_test_count)
    total_users = get_total_users_quick(client)

    projected_balance = "?"
    try:
        prepaid_payload = client.get_prepaid_needed_default()
        prepaid_total = prepaid_grand_total_from_payload(prepaid_payload)
        if balance_raw is not None:
            projected_balance = fmt_iqd(parse_money_number(balance_raw) - prepaid_total)
    except Exception:
        pass

    status_line = (
        f"Balance: {balance}  |  "
        f"Forecast: {projected_balance}  |  "
        f"Free test: {free_tests}  |  "
        f"Users: {total_users}"
    )

    if USE_RICH and console is not None and Panel is not None:
        console.print(
            compact_panel(
                status_line,
                title=f"Earthlink App API POC {CLI_VERSION}",
                border_style="cyan",
            )
        )
        return

    print("\n" + "=" * 96)
    print(f"Earthlink App API POC {CLI_VERSION}")
    print(status_line)
    print("=" * 96)




def print_dashboard_summary(client: EarthlinkAppApiClient) -> None:
    print("\nHome dashboard")
    print("=" * 72)

    try:
        balance = client.get_balance()
        print(f"Balance               : {fmt_iqd(balance)}")
    except Exception as exc:
        balance = None
        print(f"Balance               : error: {exc}")

    try:
        free_test = client.get_test_count()
        print(f"Free test users       : {free_test}")
    except Exception as exc:
        print(f"Free test users       : error: {exc}")

    try:
        users, total_count, _raw = client.list_users(start_index=0, row_count=1)
        print(f"Total users           : {total_count}")
    except Exception as exc:
        print(f"Total users           : error: {exc}")

    try:
        prepaid_payload = client.get_prepaid_needed_default()
        prepaid_total = prepaid_grand_total_from_payload(prepaid_payload)
        print(f"Default prepaid need  : {fmt_iqd(prepaid_total)}")
        if balance is not None:
            print(f"Balance after forecast: {fmt_iqd(parse_money_number(balance) - prepaid_total)}")
    except Exception as exc:
        print(f"Default prepaid need  : error: {exc}")


def print_autocomplete_items(items: List[Dict[str, Any]]) -> None:
    print("\nSearch / autocomplete results")
    print("=" * 92)
    if not items:
        print("No results.")
        return

    print(f"{'No.':<4} {'Index':<10} {'UserID':<26} {'Display':<24} {'Account':<16}")
    print("-" * 92)

    for i, item in enumerate(items, start=1):
        user_index = EarthlinkAppApiClient.item_user_index(item)
        user_id = EarthlinkAppApiClient.item_user_id(item)
        display = safe_get(item, "displayName", "DisplayName", "name", "Name", "text", "Text")
        account = safe_get(item, "accountName", "AccountName", "groupName", "GroupName")
        print(f"{i:<4} {trim(user_index, 10):<10} {trim(user_id, 26):<26} {trim(display, 24):<24} {trim(account, 16):<16}")


def choose_from_autocomplete(client: EarthlinkAppApiClient, query: str) -> Optional[Tuple[str, str]]:
    items, raw = client.autocomplete_user(query)
    print_autocomplete_items(items)

    if not items:
        print("\nRaw response:")
        print_json(raw)
        return None

    choice = input("Choose result no. (B = back): ").strip().lower()
    if choice in {"", "b"}:
        return None

    if not choice.isdigit() or not (1 <= int(choice) <= len(items)):
        print("Invalid choice.")
        return None

    item = items[int(choice) - 1]
    return EarthlinkAppApiClient.item_user_index(item), EarthlinkAppApiClient.item_user_id(item)


def extract_password_text(payload: Any) -> str:
    value = unwrap_display_value(payload)

    if isinstance(value, str):
        return value

    if isinstance(value, dict):
        for key in (
            "password",
            "Password",
            "userPassword",
            "UserPassword",
            "accountPassword",
            "AccountPassword",
            "value",
            "Value",
            "text",
            "Text",
        ):
            if value.get(key) not in (None, ""):
                return str(value.get(key))

        return json.dumps(value, ensure_ascii=False)

    return str(value)


def view_user_invoices_flow(client: EarthlinkAppApiClient, user_id: str) -> None:
    if not user_id:
        print("Missing userID.")
        return

    print(f"\nUser invoices / payments for {user_id}")
    print("-" * 72)

    browse_api_list(
        title=f"Invoices - {user_id}",
        fetch_page=lambda start, count: client.list_invoices(
            start_index=start,
            row_count=count,
            order_by_desc=True,
            query=user_id,
        ),
        print_page=print_invoices,
    )


def password_tools_flow(
    client: EarthlinkAppApiClient,
    *,
    user_index: str,
    user_id: str,
) -> None:
    while True:
        print("\nPassword tools")
        print("=" * 72)
        print(f"User: {user_id}")
        print("1. Show router/user password")
        print("2. Show account password")
        print("3. Show copy-ready username + passwords")
        print("4. Change router/user password")
        print("5. Change account password")
        print("B. Back")

        choice = input("Choose: ").strip().lower()

        try:
            if choice == "1":
                result = client.show_user_password(user_index, user_id)
                print("\nRouter/user password")
                print("-" * 40)
                print(extract_password_text(result))

            elif choice == "2":
                result = client.show_account_password(user_index, user_id)
                print("\nAccount password")
                print("-" * 40)
                print(extract_password_text(result))

            elif choice == "3":
                router_payload = client.show_user_password(user_index, user_id)
                account_payload = client.show_account_password(user_index, user_id)

                print("\nCopy-ready credentials")
                print("-" * 72)
                print(f"Username        : {user_id}")
                print(f"Router password : {extract_password_text(router_payload)}")
                print(f"Account password: {extract_password_text(account_payload)}")

            elif choice == "4":
                new_password = getpass.getpass("New router/user password: ").strip()
                confirm_password = getpass.getpass("Confirm password: ").strip()

                if not new_password:
                    print("Password is required.")
                    continue
                if new_password != confirm_password:
                    print("Passwords do not match.")
                    continue

                confirm = input(f"Change router/user password for {user_id}? [y/N]: ").strip().lower()
                if confirm not in {"y", "yes"}:
                    print("Cancelled.")
                    continue

                result = client.change_user_password(user_index, user_id, new_password)
                print_action_result("Change router/user password result", result)

            elif choice == "5":
                new_password = getpass.getpass("New account password: ").strip()
                confirm_password = getpass.getpass("Confirm password: ").strip()

                if not new_password:
                    print("Password is required.")
                    continue
                if new_password != confirm_password:
                    print("Passwords do not match.")
                    continue

                confirm = input(f"Change account password for {user_id}? [y/N]: ").strip().lower()
                if confirm not in {"y", "yes"}:
                    print("Cancelled.")
                    continue

                result = client.change_account_password(user_index, user_id, new_password)
                print_action_result("Change account password result", result)

            elif choice == "b":
                return

            else:
                print("Invalid choice.")

        except ApiError as exc:
            msg = str(exc)
            print(f"\nAPI error: {msg}")
            if "Unauthorized" in msg or "Not logged in" in msg or "token expired" in msg.lower():
                client.prompt_relogin(quiet=False)
        except requests.RequestException as exc:
            print(f"\nNetwork error: {exc}")


def get_balance_after_write(
    client: EarthlinkAppApiClient,
    *,
    before_balance: Any,
    expected_delta: Optional[float] = None,
    attempts: int = 5,
    delay_seconds: float = 1.5,
) -> Any:
    """
    Balance/deposit endpoint can lag shortly after write actions.

    Example observed:
        create-user returned OK, transaction log showed Add user and dashboard later
        showed the correct lower balance, but the immediate balance check still
        returned the previous balance.

    This helper retries for a few seconds before reporting balance verification.
    """
    last_balance: Any = ""

    for attempt in range(1, max(1, attempts) + 1):
        try:
            last_balance = client.get_balance()
        except Exception:
            last_balance = ""

        if expected_delta is None:
            return last_balance

        try:
            before_value = parse_money_number(before_balance)
            after_value = parse_money_number(last_balance)
            actual_delta = after_value - before_value

            # Server returns currency-like numbers; exact integer comparison is OK,
            # but keep a small tolerance for float conversion.
            if abs(actual_delta - float(expected_delta)) < 0.01:
                if attempt > 1:
                    print(f"Balance sync: updated after {attempt} checks.")
                return last_balance
        except Exception:
            return last_balance

        if attempt == 1:
            print("Balance update is still syncing; waiting briefly...")

        if attempt < attempts:
            time.sleep(delay_seconds)

    print("Balance sync: latest balance still did not match expected change; showing latest returned value.")
    return last_balance


def print_refill_verification(
    *,
    result: Any,
    before_user: Dict[str, Any],
    after_user: Dict[str, Any],
    before_balance: Any,
    after_balance: Any,
) -> None:
    print("\nRefill verification")
    print("=" * 72)

    response_message = ""
    if isinstance(result, dict):
        response_message = str(result.get("responseMessage") or result.get("ResponseMessage") or "")

    if response_message:
        print(f"Server message        : {response_message}")

    print(f"Request accepted      : {yes_no(bool(result.get('value') if isinstance(result, dict) else result))}")

    print("\nBefore")
    print("-" * 72)
    print(f"Status                : {display_value(safe_get(before_user, 'accountStatus', 'AccountStatus'))}")
    print(f"Expiry                : {display_value(safe_get(before_user, 'manualExpirationDate', 'ManualExpirationDate', 'accountExpirationDate', 'AccountExpirationDate'))}")
    print(f"Active days left      : {display_value(safe_get(before_user, 'activeDaysLeft', 'ActiveDaysLeft'))}")
    print(f"Last refill           : {display_value(safe_get(before_user, 'lastRefill', 'LastRefill'))}")
    print(f"Unpaid invoices       : {display_value(safe_get(before_user, 'unPaidInvoices', 'UnPaidInvoices', 'unpaidInvoices', 'UnpaidInvoices'))}")
    print(f"Balance               : {fmt_iqd(before_balance) if before_balance not in (None, '') else 'N/A'}")

    print("\nAfter")
    print("-" * 72)
    print(f"Status                : {display_value(safe_get(after_user, 'accountStatus', 'AccountStatus'))}")
    print(f"Expiry                : {display_value(safe_get(after_user, 'manualExpirationDate', 'ManualExpirationDate', 'accountExpirationDate', 'AccountExpirationDate'))}")
    print(f"Active days left      : {display_value(safe_get(after_user, 'activeDaysLeft', 'ActiveDaysLeft'))}")
    print(f"Last refill           : {display_value(safe_get(after_user, 'lastRefill', 'LastRefill'))}")
    print(f"Unpaid invoices       : {display_value(safe_get(after_user, 'unPaidInvoices', 'UnPaidInvoices', 'unpaidInvoices', 'UnpaidInvoices'))}")
    print(f"Balance               : {fmt_iqd(after_balance) if after_balance not in (None, '') else 'N/A'}")

    try:
        delta = parse_money_number(after_balance) - parse_money_number(before_balance)
        print(f"Balance change        : {fmt_iqd(delta)}")
    except Exception:
        pass

    before_expiry = safe_get(before_user, "manualExpirationDate", "ManualExpirationDate", "accountExpirationDate", "AccountExpirationDate")
    after_expiry = safe_get(after_user, "manualExpirationDate", "ManualExpirationDate", "accountExpirationDate", "AccountExpirationDate")
    after_status = safe_get(after_user, "accountStatus", "AccountStatus")

    if after_expiry and before_expiry and after_expiry != before_expiry:
        print("\nResult                : Refill appears successful")
    elif "accepted" in response_message.lower():
        print("\nResult                : Server accepted refill; expiry verification needs manual review")
    else:
        print("\nResult                : Refill result unclear; check portal/user details")

def render_user_detail_menu() -> None:
    if USE_RICH and console is not None and Table is not None and Panel is not None:
        grid = Table.grid(padding=(0, 4))
        grid.add_column()
        grid.add_column()

        tabs = Table.grid()
        tabs.add_column(style="bold")
        tabs.add_column()
        tabs.add_row("[cyan]Tabs[/]", "")
        tabs.add_row("1", "Account information")
        tabs.add_row("2", "Subscription information")
        tabs.add_row("3", "Sessions / connection")
        tabs.add_row("4", "Payments information")

        actions = Table.grid()
        actions.add_column(style="bold")
        actions.add_column()
        actions.add_row("[cyan]Actions[/]", "")
        actions.add_row("5", "Refill user deposit")
        actions.add_row("6", "Disconnect user")
        actions.add_row("7", "Activate / deactivate")
        actions.add_row("8", "Password tools")
        actions.add_row("9", "User invoices")
        actions.add_row("10", "Change display name")
        actions.add_row("11", "Change / clear MAC")
        actions.add_row("12", "Change account type")
        actions.add_row("13", "Extend user")
        actions.add_row("R", "Refresh")
        actions.add_row("B", "Back")

        grid.add_row(tabs, actions)
        console.print(compact_panel(grid, title="User detail", border_style="cyan"))
        return

    print("\nUser detail")
    print("-" * 72)
    print("Tabs")
    print("1. Account information")
    print("2. Subscription information")
    print("3. Sessions / connection")
    print("4. Payments information")
    print()
    print("Actions")
    print("5. Refill user deposit")
    print("6. Disconnect user")
    print("7. Activate / deactivate user")
    print("8. Password tools")
    print("9. View user invoices / payments")
    print("10. Change display name")
    print("11. Change / clear MAC lock")
    print("12. Change account type          [server-validated]")
    print("13. Extend user")
    print("R. Refresh")
    print("B. Back")


def pause_after_user_tab() -> None:
    input("\nPress Enter to return to user menu...")


def user_detail_actions(
    client: EarthlinkAppApiClient,
    user: Dict[str, Any],
    resolved_index: Optional[str],
    enable_write: bool,
) -> None:
    current_user = hydrate_user_runtime_data(client, dict(user))
    user_index, user_id = extract_user_identity(current_user)

    if (not user_index or user_index == "0") and resolved_index and resolved_index != "0":
        user_index = resolved_index

    while True:
        print_user_detail(current_user)

        render_user_detail_menu()

        choice = input("Choose action: ").strip().lower()

        if choice == "":
            continue

        if choice == "b":
            return

        try:
            if choice == "r":
                if not user_index:
                    print("Cannot refresh: missing userIndex.")
                    continue
                current_user = hydrate_user_runtime_data(client, client.get_user(user_index))
                user_index, user_id = extract_user_identity(current_user)
                if (not user_index or user_index == "0") and resolved_index and resolved_index != "0":
                    user_index = resolved_index

            elif choice == "1":
                print_user_account_section(current_user)
                pause_after_user_tab()

            elif choice == "2":
                print_user_subscription_section(current_user)
                pause_after_user_tab()

            elif choice == "3":
                current_user = hydrate_user_runtime_data(client, current_user)
                print_user_session_section(current_user)
                pause_after_user_tab()

            elif choice == "4":
                print_user_payments_section(current_user)
                pause_after_user_tab()

            elif choice == "5":
                if not user_id:
                    print("Missing userID.")
                    continue

                account_index = safe_get(current_user, "accountIndex", "AccountIndex")
                account_name = safe_get(current_user, "accountName", "AccountName", "groupName", "GroupName")

                if not account_index:
                    print("Cannot read accountIndex from user details.")
                    continue

                try:
                    balance = client.get_balance()
                except Exception:
                    balance = ""

                cost_result = client.get_account_cost(account_index)
                print_payment_preview(
                    user_id=user_id,
                    account_name=account_name,
                    account_index=account_index,
                    balance=balance,
                    account_cost_response=cost_result,
                )

                continue_payment = input("\nContinue to payment/refill? [y/N]: ").strip().lower()
                if continue_payment not in {"y", "yes"}:
                    print("Cancelled.")
                    continue

                cost_value, _msg = extract_account_cost_value(cost_result)
                if cost_value is not None and parse_money_number(balance) < cost_value:
                    proceed_low_balance = input("Balance appears insufficient. Execute refill anyway? [y/N]: ").strip().lower()
                    if proceed_low_balance not in {"y", "yes"}:
                        print("Cancelled.")
                        continue

                deposit_password = os.environ.get("EARTHLINK_DEPOSIT_PASSWORD", "")
                if deposit_password:
                    use_env = input("Use EARTHLINK_DEPOSIT_PASSWORD? [Y/n]: ").strip().lower()
                    if use_env not in {"", "y", "yes"}:
                        deposit_password = getpass.getpass("Deposit/online password: ")
                else:
                    deposit_password = getpass.getpass("Deposit/online password: ")

                if not deposit_password:
                    print("Deposit password is required.")
                    continue

                print("\nWARNING: This is a financial refill action.")
                print(f"UserID: {user_id}")
                print(f"Subscription: {account_name} ({account_index})")
                confirm1 = input("Proceed with refill? [y/N]: ").strip().lower()
                if confirm1 not in {"y", "yes"}:
                    print("Cancelled.")
                    continue

                before_user = dict(current_user)
                before_balance = balance

                result = client.refill_deposit(user_id, deposit_password)

                expected_delta = -cost_value if cost_value is not None else None
                after_balance = get_balance_after_write(
                    client,
                    before_balance=before_balance,
                    expected_delta=expected_delta,
                )

                if user_index:
                    current_user = hydrate_user_runtime_data(client, client.get_user(user_index))
                    user_index, user_id = extract_user_identity(current_user)

                print_refill_verification(
                    result=result,
                    before_user=before_user,
                    after_user=current_user,
                    before_balance=before_balance,
                    after_balance=after_balance,
                )

            elif choice == "6":
                if not user_index or not user_id:
                    print("Missing userIndex/userID.")
                    continue

                print(f"Sending disconnect request for {user_id}.")
                result = client.disconnect_user(user_index, user_id)
                print_action_result("Disconnect result", result)

                if user_index:
                    current_user = hydrate_user_runtime_data(client, client.get_user(user_index))
                    user_index, user_id = extract_user_identity(current_user)

            elif choice == "7":
                if not user_index:
                    print("Missing userIndex.")
                    continue

                currently_active = user_active_bool(current_user)
                if currently_active is None:
                    print("Current user active state is unknown because isBlocked was not returned.")
                    pick = input("Set user active? [y = active / n = blocked / B = back]: ").strip().lower()
                    if pick == "b":
                        print("Cancelled.")
                        continue
                    if pick not in {"y", "yes", "n", "no"}:
                        print("Invalid choice.")
                        continue
                    new_active = pick in {"y", "yes"}
                else:
                    new_active = not currently_active

                action_label = "activate" if new_active else "deactivate"

                print(f"Current user active: {user_active_display(current_user)}")
                if safe_get(current_user, "accountStatus", "AccountStatus").strip().lower() == "suspendedbyagent":
                    print("Note: SuspendedByAgent means this user is already deactivated by reseller.")
                confirm = input(f"{action_label.capitalize()} {user_id or user_index}? [y/N]: ").strip().lower()
                if confirm not in {"y", "yes"}:
                    print("Cancelled.")
                    continue

                result = client.update_user_active(user_index, current_user, new_active)
                print_action_result("Activate / deactivate result", result)

                current_user = hydrate_user_runtime_data(client, client.get_user(user_index))
                user_index, user_id = extract_user_identity(current_user)
                print(f"After refresh - accountStatus       : {safe_get(current_user, 'accountStatus', 'AccountStatus')}")
                print(f"After refresh - User active toggle  : {user_active_display(current_user)}")

            elif choice == "8":
                if not user_index or not user_id:
                    print("Missing userIndex/userID.")
                    continue
                password_tools_flow(client, user_index=user_index, user_id=user_id)

            elif choice == "9":
                view_user_invoices_flow(client, user_id)

            elif choice == "10":
                if not user_index:
                    print("Missing userIndex.")
                    continue

                current_name = safe_get(current_user, "displayName", "DisplayName")
                print(f"Current display name: {current_name}")
                new_name = input("New display name: ").strip()
                if not new_name:
                    print("New display name is required.")
                    continue

                confirm = input(f"Change display name for {user_id} to {new_name!r}? [y/N]: ").strip().lower()
                if confirm not in {"y", "yes"}:
                    print("Cancelled.")
                    continue

                result = client.update_display_name(user_index, current_user, new_name)
                print_action_result("Change display name result", result)

                current_user = hydrate_user_runtime_data(client, client.get_user(user_index))
                user_index, user_id = extract_user_identity(current_user)

            elif choice == "11":
                if not user_index:
                    print("Missing userIndex.")
                    continue

                current_mac = user_account_mac(current_user)
                print(f"Current MAC lock: {current_mac or '(empty / open)'}")
                new_mac = input("New MAC lock, or leave empty to clear/open: ").strip()

                label = new_mac or "CLEAR / OPEN"
                confirm = input(f"Set MAC lock for {user_id} to {label}? [y/N]: ").strip().lower()
                if confirm not in {"y", "yes"}:
                    print("Cancelled.")
                    continue

                result = client.update_mac_lock(user_index, current_user, new_mac)
                print_action_result("Change MAC lock result", result)

                current_user = hydrate_user_runtime_data(client, client.get_user(user_index))
                user_index, user_id = extract_user_identity(current_user)

                actual_mac = user_account_mac(current_user)
                if (actual_mac or "") == (new_mac or ""):
                    print(f"Verified: MAC lock is now {actual_mac or '(empty / open)'}.")
                else:
                    print(
                        "Warning: MAC lock update was accepted, but refresh shows "
                        f"{actual_mac or '(empty / open)'}."
                    )

            elif choice == "12":
                if not user_index:
                    print("Missing userIndex.")
                    continue

                policy, status_label = change_account_status_policy(current_user)

                if policy == "warning":
                    print("Current subscription status: ExpiringSoon.")
                    print("Account-type change may be allowed when the user is very close to expiry.")
                    proceed = input("Try account-type change anyway? [y/N]: ").strip().lower()
                    if proceed not in {"y", "yes"}:
                        print("Cancelled.")
                        continue

                elif policy == "override":
                    print("Account-type change usually works only for expired/suspended users.")
                    print(f"Current subscription status: {status_label}")
                    print("The server may reject this request with value=false.")
                    proceed = input("Try anyway? [y/N]: ").strip().lower()
                    if proceed not in {"y", "yes"}:
                        print("Cancelled.")
                        continue

                can_change_raw = current_user.get("canChangeAccount", current_user.get("CanChangeAccount", None))
                if can_change_raw is False:
                    print("Warning: Server permission says Can change account = No for this user.")
                    proceed = input("Continue anyway? [y/N]: ").strip().lower()
                    if proceed not in {"y", "yes"}:
                        print("Cancelled.")
                        continue

                accounts = client.get_accounts()
                acc = choose_account(accounts)
                if not acc:
                    continue

                account_id = safe_get(acc, "accountIndex", "AccountIndex", "id", "ID")
                account_name = safe_get(acc, "accountName", "AccountName", "name", "Name")
                if not account_id:
                    print("Selected account has no account index.")
                    continue

                confirm = input(f"Change account type for {user_id} to {account_name} ({account_id})? [y/N]: ").strip().lower()
                if confirm not in {"y", "yes"}:
                    print("Cancelled.")
                    continue

                result = client.update_account_type(user_index, current_user, account_id, user_id=user_id)
                print_action_result("Change account type result", result)

                action_value = result.get("value") if isinstance(result, dict) else None
                if action_value is False:
                    print("Server rejected the account-type change.")
                    print("Likely cause: user is not inside the server-allowed change window, or permission is not available.")
                    print("No refresh verification expected.")

                current_user = hydrate_user_runtime_data(client, client.get_user(user_index))
                user_index, user_id = extract_user_identity(current_user)

                actual_account_index = safe_get(current_user, "accountIndex", "AccountIndex")
                actual_account_name = safe_get(current_user, "accountName", "AccountName", "groupName", "GroupName")

                if str(actual_account_index) == str(account_id):
                    print(f"Verified: account type changed to {actual_account_name} ({actual_account_index}).")
                else:
                    print(
                        "Warning: account type was not changed after refresh. "
                        f"Current is {actual_account_name} ({actual_account_index})."
                    )

            elif choice == "13":
                if not user_index:
                    print("Missing userIndex.")
                    continue

                print("This may consume extend credit if the portal accepts it.")
                confirm = input(f"Extend {user_id or user_index}? [y/N]: ").strip().lower()
                if confirm not in {"y", "yes"}:
                    print("Cancelled.")
                    continue

                result = client.extend_user(user_index)
                print_action_result("Extend result", result)

                current_user = hydrate_user_runtime_data(client, client.get_user(user_index))
                user_index, user_id = extract_user_identity(current_user)

            else:
                print("Invalid choice.")

        except ApiError as exc:
            msg = str(exc)
            print(f"\nAPI error: {msg}")
            if "Unauthorized" in msg or "Not logged in" in msg or "token expired" in msg.lower():
                client.prompt_relogin(quiet=False)
        except requests.RequestException as exc:
            print(f"\nNetwork error: {exc}")


def value_rows_from_payload(payload: Any) -> List[Dict[str, Any]]:
    value = unwrap_display_value(payload)

    if isinstance(value, list):
        return [x for x in value if isinstance(x, dict)]

    if isinstance(value, dict):
        for key in ("itemsList", "ItemsList", "items", "Items", "rows", "Rows", "list", "List", "cards", "Cards"):
            if isinstance(value.get(key), list):
                return [x for x in value[key] if isinstance(x, dict)]

        # CardList-style payloads sometimes contain reseller/user lists under descriptive keys.
        for key, val in value.items():
            if isinstance(val, list) and val and isinstance(val[0], dict):
                return [x for x in val if isinstance(x, dict)]

    if isinstance(payload, dict):
        for key, val in payload.items():
            if isinstance(val, list) and val and isinstance(val[0], dict):
                return [x for x in val if isinstance(x, dict)]

    return []


def find_first_value_by_key_hint(row: Dict[str, Any], include_hints: List[str], exclude_hints: Optional[List[str]] = None) -> str:
    exclude_hints = exclude_hints or []

    # First pass: direct scalar values with matching key hints.
    for key, value in row.items():
        key_l = str(key).lower()

        if any(ex in key_l for ex in exclude_hints):
            continue

        if any(hint in key_l for hint in include_hints):
            if isinstance(value, (str, int, float)) and value not in (None, ""):
                return str(value)

    # Second pass: nested dictionaries.
    for key, value in row.items():
        if isinstance(value, dict):
            nested = find_first_value_by_key_hint(value, include_hints, exclude_hints)
            if nested:
                return nested

    return ""


def find_price_value(row: Dict[str, Any]) -> str:
    """
    The card-price endpoints use inconsistent field names across app versions.
    Try common exact keys first, then any key containing price/cost/amount/value.
    Exclude IDs and indexes so we do not print accountIndex as price.
    """
    exact_keys = [
        "price",
        "Price",
        "resellerPrice",
        "ResellerPrice",
        "resellerCardPrice",
        "ResellerCardPrice",
        "dealerPrice",
        "DealerPrice",
        "userPrice",
        "UserPrice",
        "cardPrice",
        "CardPrice",
        "accountCost",
        "AccountCost",
        "cost",
        "Cost",
        "amount",
        "Amount",
        "value",
        "Value",
        "priceValue",
        "PriceValue",
    ]

    for key in exact_keys:
        if row.get(key) not in (None, ""):
            return str(row.get(key))

    return find_first_value_by_key_hint(
        row,
        include_hints=["price", "cost", "amount", "value"],
        exclude_hints=["index", "id", "type", "count", "number", "serial"],
    )


def find_amount_value(row: Dict[str, Any]) -> str:
    exact_keys = [
        "amount",
        "Amount",
        "transactionAmount",
        "TransactionAmount",
        "transactionValue",
        "TransactionValue",
        "value",
        "Value",
        "total",
        "Total",
        "depositValue",
        "DepositValue",
        "operationValue",
        "OperationValue",
        "withdraw",
        "Withdraw",
        "income",
        "Income",
        "balance",
        "Balance",
    ]

    for key in exact_keys:
        if row.get(key) not in (None, ""):
            return str(row.get(key))

    return find_first_value_by_key_hint(
        row,
        include_hints=["amount", "value", "total", "withdraw", "income", "balance"],
        exclude_hints=["id", "index", "type", "count"],
    )


def money_value_from_obj(value: Any) -> str:
    """
    Extract numeric money value from either:
        25000
        "25,000"
        {"value": 25000.0, "currencySign": "IQD"}
    """
    if value is None or value == "":
        return ""

    # bool is a subclass of int; never treat false/true flags as prices.
    if isinstance(value, bool):
        return ""

    if isinstance(value, (int, float)):
        return str(value)

    if isinstance(value, str):
        return value

    if isinstance(value, dict):
        nested_value = value.get("value") or value.get("Value")
        if nested_value is None or nested_value == "":
            return ""
        if isinstance(nested_value, bool):
            return ""
        return str(nested_value)

    return ""


def first_money_from_keys(row: Dict[str, Any], keys: List[str]) -> str:
    for key in keys:
        if key in row:
            money = money_value_from_obj(row.get(key))
            if money != "":
                return money
    return ""


def fmt_price_cell(value: Any) -> str:
    if value in (None, ""):
        return "(not returned)"

    number = parse_money_number(value)

    # Some max/min values in the API use -1 to mean not applicable/unlimited.
    if number < 0:
        return "N/A"

    return fmt_iqd(number)


def print_card_prices(payload: Any, title: str = "Card prices") -> None:
    print(f"\n{title}")
    print("=" * 104)

    rows = value_rows_from_payload(payload)
    if not rows:
        print_json(payload)
        return

    print(f"{'No.':<4} {'Package':<30} {'Reseller price':<18} {'User price':<18} {'Index':<8} {'Custom'}")
    print("-" * 104)

    missing_count = 0

    for i, row in enumerate(rows, start=1):
        name = safe_get(
            row,
            "accountName",
            "AccountName",
            "cardName",
            "CardName",
            "name",
            "Name",
            "title",
            "Title",
            "subscriptionName",
            "SubscriptionName",
        )

        idx = safe_get(row, "accountIndex", "AccountIndex", "accountID", "AccountID", "cardIndex", "CardIndex", "id", "ID")

        reseller_price = first_money_from_keys(
            row,
            ["accountPrice", "AccountPrice", "defaultAccountPrice", "DefaultAccountPrice"],
        )

        user_price = first_money_from_keys(
            row,
            [
                "sellingEndUserAccountPrice",
                "SellingEndUserAccountPrice",
                "endUserAccountPrice",
                "EndUserAccountPrice",
                "defaultEndUserAccountPrice",
                "DefaultEndUserAccountPrice",
            ],
        )

        custom = safe_get(row, "isCustomPrice", "IsCustomPrice")

        if not reseller_price or not user_price:
            missing_count += 1

        print(
            f"{i:<4} "
            f"{trim(name, 30):<30} "
            f"{fmt_price_cell(reseller_price):<18} "
            f"{fmt_price_cell(user_price):<18} "
            f"{trim(idx, 8):<8} "
            f"{trim(custom, 8)}"
        )

    if missing_count:
        print(f"\nWarning: {missing_count} row(s) did not include both reseller and user price.")
        print("Expected app fields include accountPrice.value and sellingEndUserAccountPrice.value.")


def print_orders(payload: Any) -> None:
    print("\nPrepaid card orders")
    print("=" * 104)

    rows = value_rows_from_payload(payload)
    if not rows:
        print_json(payload)
        return

    print(f"{'No.':<4} {'Batch':<12} {'Status':<14} {'Date':<22} {'Amount':<14} {'Notes/User'}")
    print("-" * 104)

    for i, row in enumerate(rows, start=1):
        batch = safe_get(row, "batchNo", "BatchNo", "batchNumber", "BatchNumber", "id", "ID")
        status = safe_get(row, "status", "Status", "orderStatus", "OrderStatus")
        date = safe_get(row, "createdAt", "CreatedAt", "date", "Date", "orderDate", "OrderDate")
        amount = safe_get(row, "amount", "Amount", "total", "Total", "totalPrice", "TotalPrice")
        notes = safe_get(row, "notes", "Notes", "description", "Description", "userID", "UserID")
        print(f"{i:<4} {trim(batch, 12):<12} {trim(status, 14):<14} {trim(date, 22):<22} {trim(fmt_iqd(amount) if amount else '', 14):<14} {trim(notes, 26)}")


def transaction_amount_text(row: Dict[str, Any]) -> str:
    """
    App transaction payload fields:
        deposit / formattedDeposit
        withdrawal / formattedWithdrawal
        currency

    Do NOT use `balance` as transaction amount; it is balance after transaction.
    """
    currency = safe_get(row, "currency", "Currency") or "IQD"

    withdrawal = row.get("withdrawal", row.get("Withdrawal"))
    formatted_withdrawal = safe_get(row, "formattedWithdrawal", "FormattedWithdrawal")

    deposit = row.get("deposit", row.get("Deposit"))
    formatted_deposit = safe_get(row, "formattedDeposit", "FormattedDeposit")

    if withdrawal not in (None, "", 0, 0.0) or formatted_withdrawal:
        value = formatted_withdrawal or fmt_iqd(withdrawal).replace(" IQD", "")
        return f"-{value} {currency}"

    if deposit not in (None, "", 0, 0.0) or formatted_deposit:
        value = formatted_deposit or fmt_iqd(deposit).replace(" IQD", "")
        return f"+{value} {currency}"

    amount = find_amount_value(row)
    if amount:
        return fmt_iqd(amount)

    return "(not returned)"


def transaction_balance_text(row: Dict[str, Any]) -> str:
    currency = safe_get(row, "currency", "Currency") or "IQD"
    formatted_balance = safe_get(row, "formattedBalnace", "FormattedBalnace", "formattedBalance", "FormattedBalance")
    if formatted_balance:
        return f"{formatted_balance} {currency}"

    balance = row.get("balance", row.get("Balance"))
    if balance not in (None, ""):
        return fmt_iqd(balance)

    return ""


def transaction_date_text(row: Dict[str, Any]) -> str:
    raw = safe_get(row, "date", "Date", "transactionDate", "TransactionDate", "createdAt", "CreatedAt")
    if not raw:
        return ""

    # Keep readable but avoid external date libraries.
    text_value = raw.replace("T", " ")
    if "." in text_value:
        text_value = text_value.split(".", 1)[0]
    return text_value


def print_transactions(payload: Any) -> None:
    print("\nTransactions / account statement")
    print("=" * 78)

    value = unwrap_display_value(payload)
    total_count = ""
    if isinstance(value, dict):
        total_count = str(value.get("totalCount") or value.get("TotalCount") or "")

    rows = value_rows_from_payload(payload)
    if not rows:
        print_json(payload)
        return

    if total_count:
        print(f"Total transactions count: {total_count}")

    print("-" * 78)

    for i, row in enumerate(rows, start=1):
        operation = safe_get(
            row,
            "operation",
            "Operation",
            "operationType",
            "OperationType",
            "transactionType",
            "TransactionType",
            "type",
            "Type",
        )

        amount = transaction_amount_text(row)
        date_text = transaction_date_text(row)
        balance_after = transaction_balance_text(row)

        user_id = safe_get(row, "userID", "UserID", "userid", "UserId")
        display = safe_get(row, "displayName", "DisplayName")
        reseller = safe_get(row, "resellerUser", "ResellerUser", "reseller", "Reseller")
        description = safe_get(row, "description", "Description", "transactionDescription", "TransactionDescription")
        notes = safe_get(row, "notes", "Notes")

        txn_id = safe_get(row, "transactionID", "TransactionID", "transactionId", "TransactionId", "id", "ID")
        serials = safe_get(row, "serials", "Serials", "serial", "Serial")

        print(f"{i}. {operation}: {amount}")
        if date_text:
            print(f"   Date          : {date_text}")
        if user_id:
            print(f"   Related user  : {user_id}")
        if display:
            print(f"   Display name  : {display}")
        if balance_after:
            print(f"   Balance after : {balance_after}")
        if reseller:
            print(f"   Reseller      : {reseller}")
        if txn_id:
            print(f"   Transaction ID: {txn_id}")
        if serials:
            print(f"   Serial        : {serials}")
        if description:
            print(f"   Description   : {description}")
        if notes and notes.upper() != "N/A":
            print(f"   Notes         : {notes}")

        print("-" * 78)


def get_deposit_password_from_env_or_prompt(prompt: str = "Deposit password: ") -> str:
    env_password = os.environ.get("EARTHLINK_DEPOSIT_PASSWORD", "")
    if env_password:
        use_env = input("Use EARTHLINK_DEPOSIT_PASSWORD? [Y/n]: ").strip().lower()
        if use_env in {"", "y", "yes"}:
            return env_password
    return getpass.getpass(prompt).strip()


def payload_items_and_total(payload: Any) -> Tuple[List[Dict[str, Any]], int]:
    value = unwrap_display_value(payload)

    if isinstance(value, dict):
        rows = (
            value.get("itemsList")
            or value.get("ItemsList")
            or value.get("items")
            or value.get("Items")
            or []
        )
        total = int(value.get("totalCount") or value.get("TotalCount") or len(rows))
        return [x for x in rows if isinstance(x, dict)], total

    if isinstance(payload, dict):
        rows = (
            payload.get("itemsList")
            or payload.get("ItemsList")
            or payload.get("items")
            or payload.get("Items")
            or []
        )
        total = int(payload.get("totalCount") or payload.get("TotalCount") or len(rows))
        return [x for x in rows if isinstance(x, dict)], total

    if isinstance(value, list):
        return [x for x in value if isinstance(x, dict)], len(value)

    return [], 0


def user_object_from_row(row: Dict[str, Any]) -> Dict[str, Any]:
    user_obj = row.get("userObject") or row.get("UserObject") or {}
    return user_obj if isinstance(user_obj, dict) else {}


def row_user_id(row: Dict[str, Any]) -> str:
    user_obj = user_object_from_row(row)
    return safe_get(row, "userID", "UserID", "userId", "UserId") or safe_get(user_obj, "userId", "UserId", "userID", "UserID")


def row_user_index(row: Dict[str, Any]) -> str:
    user_obj = user_object_from_row(row)
    return safe_get(row, "userIndex", "UserIndex") or safe_get(user_obj, "userIndex", "UserIndex")


def row_display_name(row: Dict[str, Any]) -> str:
    user_obj = user_object_from_row(row)
    return safe_get(row, "displayName", "DisplayName") or safe_get(user_obj, "displayName", "DisplayName")


def paging_prompt(default_row_count: int = 30) -> Tuple[int, int]:
    row_count = input(f"Row count [{default_row_count}]: ").strip() or str(default_row_count)
    start_index = input("Start index [0]: ").strip() or "0"

    if not row_count.isdigit() or int(row_count) <= 0:
        print("Invalid row count. Using default.")
        row_count = str(default_row_count)

    if not start_index.isdigit() or int(start_index) < 0:
        print("Invalid start index. Using 0.")
        start_index = "0"

    return int(start_index), int(row_count)


def print_sessions(payload: Any) -> None:
    rows, total = payload_items_and_total(payload)

    print("\nSessions - online")
    print("=" * 112)
    print(f"Total sessions count: {total}")

    if not rows:
        print("No online sessions returned.")
        return

    print(f"{'No.':<4} {'UserID':<24} {'Display':<22} {'Login / usage':<23} {'Download':<13} {'Upload':<13} {'IP'}")
    print("-" * 112)

    for i, row in enumerate(rows, start=1):
        user_id = row_user_id(row)
        display = row_display_name(row)
        login_time = safe_get(row, "loginTime", "LoginTime")
        usage_time = safe_get(row, "usageTime", "UsageTime")
        login_usage = f"{login_time} / {usage_time}" if usage_time else login_time
        download = safe_get(row, "totalDownload", "TotalDownload")
        upload = safe_get(row, "totalUpload", "TotalUpload")
        ip = safe_get(row, "userIp", "UserIp", "userIP", "UserIP")
        print(
            f"{i:<4} "
            f"{trim(user_id, 24):<24} "
            f"{trim(display, 22):<22} "
            f"{trim(login_usage, 23):<23} "
            f"{trim(download, 13):<13} "
            f"{trim(upload, 13):<13} "
            f"{trim(ip, 15)}"
        )


def print_invoices(payload: Any) -> None:
    rows, total = payload_items_and_total(payload)

    print("\nInvoices")
    print("=" * 116)
    print(f"Total invoices count: {total}")

    if not rows:
        print("No invoices returned.")
        return

    print(f"{'No.':<4} {'UserID':<24} {'Display':<20} {'Amount':<13} {'Status':<10} {'Account':<12} {'Date':<20} {'Ref'}")
    print("-" * 116)

    for i, row in enumerate(rows, start=1):
        user_id = row_user_id(row)
        display = row_display_name(row)
        amount = safe_get(row, "salePrice", "SalePrice", "retailPrice", "RetailPrice")
        status = safe_get(row, "invoiceStatus", "InvoiceStatus")
        account = safe_get(row, "accountName", "AccountName")
        date = safe_get(row, "recordDate", "RecordDate")
        ref = safe_get(row, "referenceRecord", "ReferenceRecord", "invoinceID", "invoiceID", "InvoiceID")
        print(
            f"{i:<4} "
            f"{trim(user_id, 24):<24} "
            f"{trim(display, 20):<20} "
            f"{trim(fmt_iqd(amount) if amount else '', 13):<13} "
            f"{trim(status, 10):<10} "
            f"{trim(account, 12):<12} "
            f"{trim(date, 20):<20} "
            f"{trim(ref, 18)}"
        )


def print_user_errors(payload: Any) -> None:
    rows, total = payload_items_and_total(payload)

    print("\nUser errors")
    print("=" * 88)
    print(f"Total errors count: {total}")

    if not rows:
        print("No user errors returned.")
        return

    for i, row in enumerate(rows, start=1):
        print("-" * 88)
        print(f"{i}. {row_user_id(row) or '(no user)'}")
        for key in ("createdAt", "CreatedAt", "date", "Date", "error", "Error", "message", "Message", "description", "Description"):
            value = row.get(key)
            if value not in (None, ""):
                print(f"   {key}: {value}")


def print_test_users(payload: Any) -> None:
    rows, total = payload_items_and_total(payload)

    print("\nTest users")
    print("=" * 96)
    print(f"Total test users count: {total}")

    if not rows:
        print("No test users returned.")
        return

    print(f"{'No.':<4} {'UserID':<26} {'Display':<22} {'Test count':<12} {'Last used':<22} {'Affiliate'}")
    print("-" * 96)

    for i, row in enumerate(rows, start=1):
        user_id = row_user_id(row) or safe_get(row, "userId", "UserId")
        display = row_display_name(row)
        test_count = safe_get(row, "testCount", "TestCount")
        last_used = safe_get(row, "lastDateUsed", "LastDateUsed")
        affiliate = safe_get(row, "affiliateName", "AffiliateName")
        print(
            f"{i:<4} "
            f"{trim(user_id, 26):<26} "
            f"{trim(display, 22):<22} "
            f"{trim(test_count, 12):<12} "
            f"{trim(last_used, 22):<22} "
            f"{trim(affiliate, 12)}"
        )


def list_all_users_for_filter(
    client: EarthlinkAppApiClient,
    *,
    account_status_id: Optional[str] = None,
    time_period_id: Optional[str] = None,
    max_pages: int = 50,
) -> Tuple[List[UserListItem], int]:
    """
    Fetch all user rows needed for client-side filters.

    Reason:
        The app API currently ignores OnlineStatus=true/false on /user/all,
        so Online/Offline users must be filtered from returned row data.
    """
    page_size = max(100, get_page_size())
    start_index = 0
    all_users: List[UserListItem] = []
    total = 0

    extra: Dict[str, Any] = {}
    if time_period_id:
        extra["TimePeriodID"] = time_period_id

    for _ in range(max_pages):
        users, total, _raw = client.list_users(
            start_index=start_index,
            row_count=page_size,
            account_status_id=account_status_id,
            extra=extra or None,
        )

        all_users.extend(users)

        if not users:
            break

        start_index += page_size

        if total and start_index >= total:
            break

    return all_users, total or len(all_users)


def is_user_online_status(user: UserListItem) -> bool:
    status = (user.online_status or "").strip().lower()

    # Treat exact Online / online prefixes as online.
    # Some portal rows contain odd values such as OnlineNoNAS; still an online session.
    return status.startswith("online")


def browse_static_user_list(
    client: EarthlinkAppApiClient,
    *,
    title: str,
    users: List[UserListItem],
    enable_write: bool = False,
) -> None:
    """
    Paginated browser for already-filtered client-side user lists.
    """
    page_size = get_page_size()
    start_index = 0
    total = len(users)

    while True:
        page = users[start_index : start_index + page_size]

        print("\n" + title)
        print("=" * 112)

        current_page = (start_index // page_size) + 1 if page_size else 1
        total_pages = max(1, (total + page_size - 1) // page_size) if page_size else 1

        print(f"Total: {total} | Page: {current_page}/{total_pages} | Showing: {len(page)} | Page size: {page_size}")
        print_users(page, total)

        print("\nCommands")
        print("-" * 72)
        print("N = next page | P = previous page | R = refresh | S = page size | B = back")
        print("Enter a number to open that user.")

        command = input("Choose: ").strip().lower()

        if command == "b":
            return

        if command == "n":
            if start_index + page_size >= total:
                print("Already at last page.")
            else:
                start_index += page_size
            continue

        if command == "p":
            if start_index <= 0:
                print("Already at first page.")
            else:
                start_index = max(0, start_index - page_size)
            continue

        if command == "r":
            continue

        if command == "s":
            new_size = input(f"Page size [{page_size}]: ").strip()
            if not new_size:
                continue
            if not new_size.isdigit() or int(new_size) <= 0:
                print("Invalid page size.")
                continue
            page_size = int(new_size)
            CLI_SETTINGS["page_size"] = page_size
            save_cli_settings(CLI_SETTINGS)
            start_index = 0
            print(f"Saved default page size: {page_size}")
            continue

        if command.isdigit():
            idx = int(command)
            if 1 <= idx <= len(page):
                selected = page[idx - 1]
                if not selected.user_index:
                    print("Selected row has no userIndex.")
                    continue
                user = client.get_user(selected.user_index)
                # Preserve safe runtime display data only. Do not merge selected.raw
                # directly because list callerID can accidentally become MAC lock.
                user = merge_nonempty_dicts(user, runtime_context_from_session_row(selected.raw))
                user_detail_actions(client, user, selected.user_index, enable_write=enable_write)
            else:
                print("Invalid user number.")
            continue

        print("Invalid command.")


def browse_user_list(
    client: EarthlinkAppApiClient,
    *,
    title: str,
    account_status_id: Optional[str] = None,
    time_period_id: Optional[str] = None,
    extra: Optional[Dict[str, Any]] = None,
    enable_write: bool = False,
) -> None:
    """
    Paginated user browser.

    Commands:
        n       next page
        p       previous page
        r       refresh
        s       change page size for this session and save to settings
        1..N    open user details
        b       back
    """
    start_index = 0
    page_size = get_page_size()

    while True:
        request_extra: Dict[str, Any] = dict(extra or {})
        if time_period_id:
            request_extra["TimePeriodID"] = time_period_id

        users, total, _raw = client.list_users(
            start_index=start_index,
            row_count=page_size,
            account_status_id=account_status_id,
            extra=request_extra or None,
        )

        print("\n" + title)
        print("=" * 112)

        current_page = (start_index // page_size) + 1 if page_size else 1
        total_pages = max(1, (total + page_size - 1) // page_size) if page_size else 1

        print(f"Total: {total} | Page: {current_page}/{total_pages} | Showing: {len(users)} | Page size: {page_size}")
        print_users(users, total)

        print("\nCommands")
        print("-" * 72)
        print("N = next page | P = previous page | R = refresh | S = page size | B = back")
        print("Enter a number to open that user.")

        command = input("Choose: ").strip().lower()

        if command == "b":
            return

        if command == "n":
            if start_index + page_size >= total:
                print("Already at last page.")
            else:
                start_index += page_size
            continue

        if command == "p":
            if start_index <= 0:
                print("Already at first page.")
            else:
                start_index = max(0, start_index - page_size)
            continue

        if command == "r":
            continue

        if command == "s":
            new_size = input(f"Page size [{page_size}]: ").strip()
            if not new_size:
                continue
            if not new_size.isdigit() or int(new_size) <= 0:
                print("Invalid page size.")
                continue
            page_size = int(new_size)
            CLI_SETTINGS["page_size"] = page_size
            save_cli_settings(CLI_SETTINGS)
            start_index = 0
            print(f"Saved default page size: {page_size}")
            continue

        if command.isdigit():
            idx = int(command)
            if 1 <= idx <= len(users):
                selected = users[idx - 1]
                if not selected.user_index:
                    print("Selected row has no userIndex.")
                    continue
                user = client.get_user(selected.user_index)
                # Preserve safe runtime display data only. Do not merge selected.raw
                # directly because list callerID can accidentally become MAC lock.
                user = merge_nonempty_dicts(user, runtime_context_from_session_row(selected.raw))
                user_detail_actions(client, user, selected.user_index, enable_write=enable_write)
            else:
                print("Invalid user number.")
            continue

        print("Invalid command.")


def list_user_bucket_flow(
    client: EarthlinkAppApiClient,
    *,
    title: str,
    account_status_id: str,
    time_period_id: Optional[str] = None,
    default_row_count: int = 30,
) -> Tuple[List[UserListItem], int]:
    print(f"\n{title}")
    print("-" * 72)
    start_index, row_count = paging_prompt(default_row_count)

    extra: Dict[str, Any] = {}
    if time_period_id:
        extra["TimePeriodID"] = time_period_id

    users, total, _raw = client.list_users(
        start_index=start_index,
        row_count=row_count,
        account_status_id=account_status_id,
        extra=extra or None,
    )
    print_users(users, total)
    return users, total


def browse_api_list(
    *,
    title: str,
    fetch_page: Any,
    print_page: Any,
) -> None:
    """
    Generic paginated browser for API list screens.

    Commands:
        n = next page
        p = previous page
        r = refresh
        s = page size
        b = back
    """
    start_index = 0
    page_size = get_page_size()

    while True:
        payload = fetch_page(start_index, page_size)

        print_page(payload)

        rows, total = payload_items_and_total(payload)
        total = total or len(rows)

        current_page = (start_index // page_size) + 1 if page_size else 1
        total_pages = max(1, (total + page_size - 1) // page_size) if page_size else 1

        print("\nNavigation")
        print("-" * 72)
        print(f"{title} | Page {current_page}/{total_pages} | Showing {len(rows)} of {total} | Page size {page_size}")
        print("N = next page | P = previous page | R = refresh | S = page size | B = back")

        command = input("Choose: ").strip().lower()

        if command == "b":
            return

        if command == "n":
            if start_index + page_size >= total:
                print("Already at last page.")
            else:
                start_index += page_size
            continue

        if command == "p":
            if start_index <= 0:
                print("Already at first page.")
            else:
                start_index = max(0, start_index - page_size)
            continue

        if command == "r":
            continue

        if command == "s":
            new_size = input(f"Page size [{page_size}]: ").strip()
            if not new_size:
                continue
            if not new_size.isdigit() or int(new_size) <= 0:
                print("Invalid page size.")
                continue
            page_size = int(new_size)
            CLI_SETTINGS["page_size"] = page_size
            save_cli_settings(CLI_SETTINGS)
            start_index = 0
            print(f"Saved default page size: {page_size}")
            continue

        print("Invalid command.")


def open_user_search_flow(client: EarthlinkAppApiClient, enable_write: bool) -> None:
    identifier = input("Search text / userIndex / username / phone: ").strip()

    if not identifier:
        return

    user, resolved_index = client.get_user_by_identifier(identifier)
    if not user:
        print()
        print(f"Could not resolve exact user: {identifier}")
        print("Showing autocomplete results instead...")
        picked = choose_from_autocomplete(client, identifier)
        if not picked:
            return

        picked_index, picked_user_id = picked
        if not picked_index:
            print("Selected result has no userIndex.")
            return

        user = client.get_user(picked_index)
        resolved_index = picked_index
        print(f"Resolved {picked_user_id or identifier} -> userIndex {picked_index}")

    elif resolved_index and resolved_index != identifier:
        print(f"Resolved {identifier} -> userIndex {resolved_index}")

    user_detail_actions(client, user, resolved_index, enable_write=enable_write)


def other_user_tools_menu(client: EarthlinkAppApiClient, enable_write: bool) -> None:
    """
    Less frequent user lists/tools.
    """
    while True:
        print("\nMore user tools")
        print("=" * 72)
        print("1. All users")
        print("2. Expired users")
        print("3. Sessions - online")
        print("4. Invoices")
        print("5. Errors")
        print("6. Test users")
        print("B. Back")

        choice = input("Choose: ").strip().lower()

        try:
            if choice == "1":
                browse_user_list(
                    client,
                    title="All users",
                    enable_write=enable_write,
                )

            elif choice == "2":
                # Captured from app: AccountStatusID=6&TimePeriodID=7
                browse_user_list(
                    client,
                    title="Expired users",
                    account_status_id="6",
                    time_period_id="7",
                    enable_write=enable_write,
                )

            elif choice == "3":
                browse_api_list(
                    title="Sessions - online",
                    fetch_page=lambda start, count: client.list_active_sessions(start_index=start, row_count=count),
                    print_page=print_sessions,
                )

            elif choice == "4":
                browse_api_list(
                    title="Invoices",
                    fetch_page=lambda start, count: client.list_invoices(start_index=start, row_count=count, order_by_desc=True),
                    print_page=print_invoices,
                )

            elif choice == "5":
                browse_api_list(
                    title="Errors",
                    fetch_page=lambda start, count: client.list_user_errors(start_index=start, row_count=count),
                    print_page=print_user_errors,
                )

            elif choice == "6":
                browse_api_list(
                    title="Test users",
                    fetch_page=lambda start, count: client.list_test_users(start_index=start, row_count=count),
                    print_page=print_test_users,
                )

            elif choice == "b":
                return

            else:
                print("Invalid choice.")

        except ApiError as exc:
            msg = str(exc)
            print(f"\nAPI error: {msg}")
            if "Unauthorized" in msg or "Not logged in" in msg or "token expired" in msg.lower():
                client.prompt_relogin(quiet=False)
        except requests.RequestException as exc:
            print(f"\nNetwork error: {exc}")
        except KeyboardInterrupt:
            print("\nCancelled.")



def users_menu(client: EarthlinkAppApiClient, enable_write: bool) -> None:
    """
    User area organized around the user's real daily work:
        - Online / Offline users
        - Expiring soon / Recently expired
        - Sessions online
        - Search users
    Less frequent reports/lists are moved to More.
    """
    while True:
        print("\nUsers")
        print("=" * 72)
        print("Daily work")
        print("1. Online users")
        print("2. Offline users")
        print("3. Expiring soon")
        print("4. Recently expired")
        print("5. Sessions - online")
        print("6. Search users")
        print()
        print("More")
        print("7. More user tools")
        print("B. Back")

        choice = input("Choose: ").strip().lower()

        try:
            if choice == "1":
                print("\nLoading online users...")
                all_users, _total = list_all_users_for_filter(client)
                online_users = [u for u in all_users if is_user_online_status(u)]
                browse_static_user_list(
                    client,
                    title="Online users",
                    users=online_users,
                    enable_write=enable_write,
                )

            elif choice == "2":
                # Captured app filter for Offline users:
                # POST /user/all AccountStatusID=4
                browse_user_list(
                    client,
                    title="Offline users",
                    account_status_id="4",
                    enable_write=enable_write,
                )

            elif choice == "3":
                # Captured from app: AccountStatusID=5&TimePeriodID=2
                browse_user_list(
                    client,
                    title="Expiring soon users",
                    account_status_id="5",
                    time_period_id="2",
                    enable_write=enable_write,
                )

            elif choice == "4":
                # Captured from app: AccountStatusID=6&TimePeriodID=5
                browse_user_list(
                    client,
                    title="Recently expired users",
                    account_status_id="6",
                    time_period_id="5",
                    enable_write=enable_write,
                )

            elif choice == "5":
                browse_api_list(
                    title="Sessions - online",
                    fetch_page=lambda start, count: client.list_active_sessions(start_index=start, row_count=count),
                    print_page=print_sessions,
                )

            elif choice == "6":
                open_user_search_flow(client, enable_write=enable_write)

            elif choice == "7":
                other_user_tools_menu(client, enable_write=enable_write)

            elif choice == "b":
                return

            else:
                print("Invalid choice.")

        except ApiError as exc:
            msg = str(exc)
            print(f"\nAPI error: {msg}")
            if "Unauthorized" in msg or "Not logged in" in msg or "token expired" in msg.lower():
                client.prompt_relogin(quiet=False)
        except requests.RequestException as exc:
            print(f"\nNetwork error: {exc}")
        except KeyboardInterrupt:
            print("\nCancelled.")



def card_prices_flow(client: EarthlinkAppApiClient) -> None:
    # The reseller endpoint already returns both accountPrice and sellingEndUserAccountPrice.
    result = client.get_card_prices_for_reseller()
    print_card_prices(result, "Card prices")


def prepaid_needed_flow(client: EarthlinkAppApiClient) -> None:
    print("\nPrepaid-needed forecast")
    print("-" * 40)
    print("1. Default app forecast")
    print("2. Custom days")
    sub = input("Choose: ").strip().lower()

    try:
        current_balance = client.get_balance()
    except Exception:
        current_balance = None

    if sub == "1":
        result = client.get_prepaid_needed_default()
        print_prepaid_needed(result, current_balance=current_balance)
    elif sub == "2":
        days_text = input("Days: ").strip()
        if not days_text.isdigit() or int(days_text) <= 0:
            print("Invalid days.")
            return
        days = int(days_text)
        result = client.get_prepaid_needed_by_days(days)
        print_prepaid_needed(result, days=days, current_balance=current_balance)
    else:
        print("Invalid choice.")


def latest_transactions_flow(client: EarthlinkAppApiClient) -> None:
    print("\nLatest transactions / statement")
    print("-" * 40)
    deposit_password = get_deposit_password_from_env_or_prompt("Deposit password: ")
    if not deposit_password:
        print("Deposit password is required.")
        return

    row_count_text = input("How many latest transactions? [10]: ").strip() or "10"
    if not row_count_text.isdigit() or int(row_count_text) <= 0:
        print("Invalid row count.")
        return

    advanced = input("Add filters? [y/N]: ").strip().lower()

    operation_type = ""
    batch_no = ""
    from_date = ""
    to_date = ""

    if advanced in {"y", "yes"}:
        operation_type = input("Operation type (blank = all): ").strip()
        batch_no = input("Batch # (blank = all): ").strip()
        from_date = input("From date YYYY-MM-DD (blank = none): ").strip()
        to_date = input("To date YYYY-MM-DD (blank = none): ").strip()

    result = client.get_account_statement(
        start_index=0,
        row_count=int(row_count_text),
        operation_type=operation_type,
        from_date=from_date,
        to_date=to_date,
        deposit_password=deposit_password,
        batch_no=batch_no,
    )
    print_transactions(result)


def business_menu(client: EarthlinkAppApiClient, enable_write: bool) -> None:
    """
    Kept as a compatibility wrapper. Business items are now exposed directly
    in the main menu because there are only three useful actions.
    """
    while True:
        print("\nBusiness")
        print("=" * 72)
        print("1. Card prices")
        print("2. Prepaid-needed forecast")
        print("3. Latest transactions / statement")
        print("B. Back")

        choice = input("Choose: ").strip().lower()

        try:
            if choice == "1":
                card_prices_flow(client)
            elif choice == "2":
                prepaid_needed_flow(client)
            elif choice == "3":
                latest_transactions_flow(client)
            elif choice == "b":
                return
            else:
                print("Invalid choice.")

        except ApiError as exc:
            msg = str(exc)
            print(f"\nAPI error: {msg}")
            if "Unauthorized" in msg or "Not logged in" in msg or "token expired" in msg.lower():
                client.prompt_relogin(quiet=False)
        except requests.RequestException as exc:
            print(f"\nNetwork error: {exc}")
        except KeyboardInterrupt:
            print("\nCancelled.")



def normalize_affiliates(payload: Any) -> List[Dict[str, Any]]:
    value = unwrap_display_value(payload)
    if isinstance(value, list):
        return [x for x in value if isinstance(x, dict)]
    if isinstance(payload, list):
        return [x for x in payload if isinstance(x, dict)]
    if isinstance(value, dict):
        items = value.get("itemsList") or value.get("ItemsList") or value.get("items") or value.get("Items") or []
        if isinstance(items, list):
            return [x for x in items if isinstance(x, dict)]
    return []


def choose_affiliate(client: EarthlinkAppApiClient) -> Optional[Dict[str, Any]]:
    payload = client.get_affiliates()
    affiliates = normalize_affiliates(payload)

    if not affiliates:
        print("No affiliate records returned.")
        print_json(payload)
        return None

    if len(affiliates) == 1:
        aff = affiliates[0]
        name = safe_get(aff, "affiliateName", "AffiliateName", "name", "Name")
        idx = safe_get(aff, "affiliateIndex", "AffiliateIndex", "id", "ID")
        print(f"Affiliate: {name} ({idx})")
        return aff

    print("\nAffiliates")
    print("=" * 72)
    print(f"{'No.':<4} {'Index':<12} {'Name'}")
    print("-" * 72)
    for i, aff in enumerate(affiliates, start=1):
        idx = safe_get(aff, "affiliateIndex", "AffiliateIndex", "id", "ID")
        name = safe_get(aff, "affiliateName", "AffiliateName", "name", "Name")
        print(f"{i:<4} {idx:<12} {name}")

    choice = input("Choose affiliate no. (B/Enter = back): ").strip().lower()
    if choice in {"", "b"}:
        return None
    if not choice.isdigit() or not (1 <= int(choice) <= len(affiliates)):
        print("Invalid choice.")
        return None
    return affiliates[int(choice) - 1]


def prompt_available_username(client: EarthlinkAppApiClient) -> Optional[str]:
    """
    App-like User ID availability check.

    In the mobile app, availability is checked when the User ID field loses focus
    and a green tick is shown when available. In CLI, we perform the check
    immediately after the User ID is entered.
    """
    while True:
        user_id = input("User ID * e.g. aaa@sacx (B = back): ").strip()
        if user_id.lower() == "b":
            return None
        if not user_id:
            print("User ID is required.")
            continue

        print("Checking User ID availability...")
        available, raw = client.check_user_available(user_id)

        if available is True:
            print(f"✓ User ID available: {user_id}")
            return user_id

        if available is False:
            print(f"✗ User ID already exists: {user_id}")
            print("Action: enter a different User ID.")
            continue

        print("Could not determine User ID availability.")
        print_json(raw)
        retry = input("Try another User ID? [Y/n]: ").strip().lower()
        if retry in {"n", "no"}:
            return None



def choose_customer_from_list(customers: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    if not customers:
        return None

    if len(customers) == 1:
        c = customers[0]
        cid = safe_get(c, "customerId", "CustomerId", "ID", "id")
        name = safe_get(c, "customerFullName", "CustomerFullName", "name", "Name")
        phone = safe_get(c, "customerPhoneNumber", "CustomerPhoneNumber", "phoneNumber", "PhoneNumber")
        print(f"Using existing customer: {name} / {phone} ({cid})")
        return c

    print_customers(customers, {"value": customers})
    choice = input("Choose customer no. (N = create new / B = back): ").strip().lower()

    if choice == "b":
        return None

    if choice == "n":
        return {}

    if not choice.isdigit() or not (1 <= int(choice) <= len(customers)):
        print("Invalid customer choice.")
        return None

    return customers[int(choice) - 1]


def looks_like_valid_arabic_customer_name(name: str) -> bool:
    """
    Backend validation expects Arabic name parts:
        يجب ان يحتوي كل اسم على ما لا يقل عن حرفين الى 20 حرف عربي
    """
    parts = [p.strip() for p in re.split(r"\s+", name.strip()) if p.strip()]
    if len(parts) < 2:
        return False

    arabic_re = re.compile(r"^[\u0600-\u06FF]{2,20}$")
    return all(arabic_re.match(part) for part in parts)


def prompt_valid_customer_full_name(default_name: str) -> str:
    while True:
        name = input(f"Customer full name [{default_name}]: ").strip() or default_name
        if looks_like_valid_arabic_customer_name(name):
            return name

        print("Customer name must be Arabic.")
        print("Each name part must be 2–20 Arabic letters.")
        print("Example: احمد محمد")


def resolve_customer_for_paid_user(
    client: EarthlinkAppApiClient,
    *,
    phone: str,
    default_name: str,
    enable_write: bool,
) -> Tuple[Optional[str], str]:
    """
    Return (customer_id, customer_name).

    If customer exists, use selected customer. If not, create a customer
    after operator confirmation.
    """
    print("Checking existing customer by phone...")
    customers, raw = client.get_customers_by_phone(phone)

    selected: Optional[Dict[str, Any]] = None
    if customers:
        selected = choose_customer_from_list(customers)
        if selected is None:
            return None, ""
        if selected:
            cid = safe_get(selected, "customerId", "CustomerId", "ID", "id")
            name = safe_get(selected, "customerFullName", "CustomerFullName", "name", "Name")
            return cid, name or default_name

    full_name = prompt_valid_customer_full_name(default_name)

    print()
    print("No existing customer was selected/found for this phone.")
    print("A customer record is required before creating the paid user.")
    confirm = input(f"Create customer record for {full_name} / {phone}? [Y/n]: ").strip().lower()
    if confirm in {"n", "no"}:
        print("Customer creation cancelled. Paid-user creation cannot continue without customerId.")
        input("Press Enter to return to main menu...")
        return None, full_name

    result = client.create_customer(
        customer_full_name=full_name,
        customer_phone_number=phone,
        customer_second_phone_number="",
        email="",
        address="",
    )

    print_action_result("Create customer result", result)

    if isinstance(result, dict) and result.get("isSuccessful") is False:
        print("Please retry with a valid Arabic full name.")
        input("Press Enter to return to main menu...")
        return None, full_name

    customer_id = ""
    if isinstance(result, dict):
        value = result.get("value", result.get("Value"))
        if value not in (None, ""):
            customer_id = str(value)

    if not customer_id:
        print("Customer was not created or customerId was not returned.")
        return None, full_name

    print(f"Customer ID: {customer_id}")
    return customer_id, full_name


def verify_created_user_by_id(client: EarthlinkAppApiClient, user_id: str) -> Tuple[Optional[str], Optional[Dict[str, Any]]]:
    try:
        _user, resolved_index = client.get_user_by_identifier(user_id)
        if not resolved_index:
            return None, None
        details = hydrate_user_runtime_data(client, client.get_user(resolved_index))
        return resolved_index, details
    except Exception:
        return None, None


def create_test_user_flow(client: EarthlinkAppApiClient, enable_write: bool) -> None:
    print("\nCreate Test user")
    print("=" * 72)
    print("Note: Test user will expire in 24 hours.")
    print("Endpoint confirmed from HAR: POST /user/newtestuser")

    print("\nLOGIN INFORMATION")
    print("-" * 72)

    affiliate = choose_affiliate(client)
    if not affiliate:
        return

    affiliate_index = safe_get(affiliate, "affiliateIndex", "AffiliateIndex", "id", "ID")
    affiliate_name = safe_get(affiliate, "affiliateName", "AffiliateName", "name", "Name")

    try:
        free_count = client.get_test_count(affiliate_index=affiliate_index)
    except Exception:
        free_count = client.get_test_count()

    print(f"Selected affiliate: {affiliate_name} ({free_count} tests available)")

    user_id = prompt_available_username(client)
    if not user_id:
        return

    user_pass = getpass.getpass("Password *: ").strip()
    confirm_pass = getpass.getpass("Confirm password *: ").strip()
    if not user_pass:
        print("Password is required.")
        return
    if user_pass != confirm_pass:
        print("Passwords do not match.")
        return

    print("\nUSER INFORMATION")
    print("-" * 72)

    display_name = input("Display name *: ").strip()
    if not display_name:
        print("Display name is required.")
        return

    phone = input("Phone number *: ").strip()
    if not phone:
        print("Phone number is required.")
        return

    print("\nSUBSCRIPTION")
    print("-" * 72)

    accounts = client.get_accounts()
    accounts = [a for a in accounts if a.get("canAddWithTest", a.get("CanAddWithTest", True))]
    acc = choose_account(accounts)
    if not acc:
        return

    account_index = safe_get(acc, "accountIndex", "AccountIndex", "id", "ID")
    account_name = safe_get(acc, "accountName", "AccountName", "name", "Name")

    print("\nReview")
    print("-" * 72)
    print(f"Affiliate     : {affiliate_name} ({affiliate_index})")
    print(f"User ID       : {user_id}")
    print(f"Display name  : {display_name}")
    print(f"Phone number  : {phone}")
    print(f"Subscription  : {account_name} ({account_index})")
    print(f"Free test left: {free_count}")
    print("Password      : ****")

    confirm = input("\nCreate this test user? [y/N]: ").strip().lower()
    if confirm not in {"y", "yes"}:
        print("Cancelled.")
        return

    result = client.create_test_user(
        mobile_number=phone,
        account_index=account_index,
        user_id=user_id,
        display_name=display_name,
        affiliate_index=affiliate_index,
        user_pass=user_pass,
    )

    print_action_result("Create Test user result", result)

    new_index = ""
    if isinstance(result, dict):
        value = result.get("value", result.get("Value"))
        if value not in (None, ""):
            new_index = str(value)

    if not new_index:
        resolved_user, resolved_index = client.get_user_by_identifier(user_id)
        new_index = resolved_index or ""

    if not new_index:
        print("Created request returned, but userIndex could not be resolved.")
        return

    try:
        created = hydrate_user_runtime_data(client, client.get_user(new_index))
        print("\nCreated user verification")
        print("-" * 72)
        print_user_header(created)

        open_now = input("\nOpen created user details? [Y/n]: ").strip().lower()
        if open_now in {"", "y", "yes"}:
            user_detail_actions(client, created, new_index, enable_write=enable_write)
    except Exception as exc:
        print(f"Created, but verification failed: {exc}")



def create_using_deposit_preview_flow(client: EarthlinkAppApiClient, enable_write: bool) -> None:
    print("\nCreate using Deposit")
    print("=" * 72)
    print("This creates a paid user and deducts the package cost from reseller deposit.")
    print("Endpoint: POST /user/newuserdeposit")

    print("\nLOGIN INFORMATION")
    print("-" * 72)

    affiliate = choose_affiliate(client)
    if not affiliate:
        return

    affiliate_index = safe_get(affiliate, "affiliateIndex", "AffiliateIndex", "id", "ID")
    affiliate_name = safe_get(affiliate, "affiliateName", "AffiliateName", "name", "Name")
    print(f"Selected affiliate: {affiliate_name} ({affiliate_index})")

    user_id = prompt_available_username(client)
    if not user_id:
        return

    user_pass = getpass.getpass("Password *: ").strip()
    confirm_pass = getpass.getpass("Confirm password *: ").strip()
    if not user_pass:
        print("Password is required.")
        return
    if user_pass != confirm_pass:
        print("Passwords do not match.")
        return

    print("\nUSER INFORMATION")
    print("-" * 72)

    display_name = input("Display name *: ").strip()
    if not display_name:
        print("Display name is required.")
        return

    phone = input("Phone number *: ").strip()
    if not phone:
        print("Phone number is required.")
        return

    customer_id, customer_name = resolve_customer_for_paid_user(
        client,
        phone=phone,
        default_name=display_name,
        enable_write=enable_write,
    )

    if not customer_id:
        return

    print("\nSUBSCRIPTION")
    print("-" * 72)

    accounts = client.get_accounts()
    acc = choose_account(accounts)
    if not acc:
        return

    account_index = safe_get(acc, "accountIndex", "AccountIndex", "id", "ID")
    account_name = safe_get(acc, "accountName", "AccountName", "name", "Name")

    balance = client.get_balance()
    cost_result = client.get_account_cost(account_index)
    cost_value, cost_message = extract_account_cost_value(cost_result)
    balance_value = parse_money_number(balance)

    print_payment_preview(
        user_id=user_id,
        account_name=account_name,
        account_index=account_index,
        balance=balance,
        account_cost_response=cost_result,
    )

    if cost_message:
        print(f"Server message: {cost_message}")

    if cost_value is not None and balance_value < cost_value:
        print("\nStatus: Not enough balance.")
        print("Paid user creation stopped before sending the create request.")
        print("Increase reseller balance or choose a lower-cost subscription.")
        input("Press Enter to return to main menu...")
        return

    print("\nPaid user creation review")
    print("-" * 72)
    print(f"Affiliate        : {affiliate_name} ({affiliate_index})")
    print(f"User ID          : {user_id}")
    print(f"Display name     : {display_name}")
    print(f"Phone number     : {phone}")
    print(f"Customer         : {customer_name} ({customer_id})")
    print(f"Subscription     : {account_name} ({account_index})")
    print(f"Available balance: {fmt_iqd(balance)}")
    if cost_value is not None:
        print(f"Package cost     : {fmt_iqd(cost_value)}")
        print(f"After purchase   : {fmt_iqd(balance_value - cost_value)}")
    print("Password         : ****")

    deposit_password = os.environ.get("EARTHLINK_DEPOSIT_PASSWORD", "")
    if deposit_password:
        use_env = input("Use EARTHLINK_DEPOSIT_PASSWORD? [Y/n]: ").strip().lower()
        if use_env not in {"", "y", "yes"}:
            deposit_password = getpass.getpass("Deposit password *: ")
    else:
        deposit_password = getpass.getpass("Deposit password *: ")

    if not deposit_password:
        print("Deposit password is required.")
        return

    print("\nWARNING: This is a financial create-user action.")
    confirm1 = input("Create paid user using deposit? [y/N]: ").strip().lower()
    if confirm1 not in {"y", "yes"}:
        print("Cancelled.")
        return

    before_balance = balance
    result = client.create_paid_user_deposit(
        mobile_number=phone,
        account_index=account_index,
        user_id=user_id,
        display_name=display_name,
        affiliate_index=affiliate_index,
        user_pass=user_pass,
        deposit_password=deposit_password,
        customer_id=customer_id,
    )

    print_action_result("Create using Deposit result", result)

    if isinstance(result, dict) and result.get("isSuccessful") is False:
        print("Paid user was not created.")
        input("Press Enter to return to main menu...")
        return

    expected_delta = -cost_value if cost_value is not None else None
    after_balance = get_balance_after_write(
        client,
        before_balance=before_balance,
        expected_delta=expected_delta,
    )

    print("\nBalance verification")
    print("-" * 72)
    print(f"Before balance : {fmt_iqd(before_balance)}")
    if after_balance not in (None, ""):
        print(f"After balance  : {fmt_iqd(after_balance)}")
        try:
            print(f"Balance change : {fmt_iqd(parse_money_number(after_balance) - parse_money_number(before_balance))}")
        except Exception:
            pass

    new_index = ""
    if isinstance(result, dict):
        value = result.get("value", result.get("Value"))
        if value not in (None, ""):
            new_index = str(value)

    if not new_index or not new_index.isdigit():
        resolved_index, created = verify_created_user_by_id(client, user_id)
        if resolved_index:
            new_index = resolved_index
        else:
            created = None
    else:
        created = hydrate_user_runtime_data(client, client.get_user(new_index))

    if created:
        print("\nCreated user verification")
        print("-" * 72)
        print_user_header(created)

        open_now = input("\nOpen created user details? [Y/n]: ").strip().lower()
        if open_now in {"", "y", "yes"}:
            user_detail_actions(client, created, new_index, enable_write=enable_write)
    else:
        print("Create request returned, but user verification by username did not find the new user.")


def render_main_menu() -> None:
    if USE_RICH and console is not None and Table is not None and Panel is not None:
        grid = Table.grid(padding=(0, 4))
        grid.add_column()
        grid.add_column()
        grid.add_column()

        quick = Table.grid()
        quick.add_column(style="bold")
        quick.add_column()
        quick.add_row("[cyan]Quick users[/]", "")
        quick.add_row("1", "Online users")
        quick.add_row("2", "Offline users")
        quick.add_row("3", "Expiring soon")
        quick.add_row("4", "Recently expired")
        quick.add_row("5", "Search users")
        quick.add_row("6", "More user tools")

        business = Table.grid()
        business.add_column(style="bold")
        business.add_column()
        business.add_row("[cyan]Business[/]", "")
        business.add_row("7", "Card prices")
        business.add_row("8", "Prepaid-needed forecast")
        business.add_row("9", "Latest transactions / statement")

        create = Table.grid()
        create.add_column(style="bold")
        create.add_column()
        create.add_row("[cyan]Create / Settings[/]", "")
        create.add_row("10", "Create using Deposit")
        create.add_row("11", "Create Test user")
        create.add_row("S", "Settings")
        create.add_row("R", "Force re-login")
        create.add_row("Q", "Exit")

        grid.add_row(quick, business, create)
        console.print(compact_panel(grid, title="Main menu", border_style="cyan"))
        return

    print("Quick users")
    print("1. Online users")
    print("2. Offline users")
    print("3. Expiring soon")
    print("4. Recently expired")
    print("5. Search users")
    print("6. More user tools")
    print()
    print("Business")
    print("7. Card prices")
    print("8. Prepaid-needed forecast")
    print("9. Latest transactions / statement")
    print()
    print("Create new user")
    print("10. Create using Deposit          [deposit payment]")
    print("11. Create Test user")
    print()
    print("Settings")
    print("S. Settings")
    print()
    print("R. Force re-login")
    print("Q. Exit")


def interactive_menu(client: EarthlinkAppApiClient, enable_write: bool) -> None:
    while True:
        print_app_status_header(client)
        render_main_menu()

        choice = input("Choose: ").strip().lower()

        try:
            if choice == "1":
                print("\nLoading online users...")
                all_users, _total = list_all_users_for_filter(client)
                online_users = [u for u in all_users if is_user_online_status(u)]
                browse_static_user_list(
                    client,
                    title="Online users",
                    users=online_users,
                    enable_write=enable_write,
                )

            elif choice == "2":
                # Captured app filter for Offline users:
                # POST /user/all AccountStatusID=4
                browse_user_list(
                    client,
                    title="Offline users",
                    account_status_id="4",
                    enable_write=enable_write,
                )

            elif choice == "3":
                # Captured from app: AccountStatusID=5&TimePeriodID=2
                browse_user_list(
                    client,
                    title="Expiring soon users",
                    account_status_id="5",
                    time_period_id="2",
                    enable_write=enable_write,
                )

            elif choice == "4":
                # Captured from app: AccountStatusID=6&TimePeriodID=5
                browse_user_list(
                    client,
                    title="Recently expired users",
                    account_status_id="6",
                    time_period_id="5",
                    enable_write=enable_write,
                )

            elif choice == "5":
                open_user_search_flow(client, enable_write=enable_write)

            elif choice == "6":
                other_user_tools_menu(client, enable_write=enable_write)

            elif choice == "7":
                card_prices_flow(client)

            elif choice == "8":
                prepaid_needed_flow(client)

            elif choice == "9":
                latest_transactions_flow(client)

            elif choice == "10":
                create_using_deposit_preview_flow(client, enable_write=enable_write)

            elif choice == "11":
                create_test_user_flow(client, enable_write=enable_write)

            elif choice == "s":
                settings_menu()

            elif choice == "r":
                client.clear_token()
                client.ensure_login(force_login=True)

            elif choice == "q":
                return

            else:
                print("Invalid choice.")

        except ApiError as exc:
            msg = str(exc)
            print(f"\nAPI error: {msg}")
            if "Unauthorized" in msg or "Not logged in" in msg or "token expired" in msg.lower():
                client.prompt_relogin(quiet=False)
        except requests.RequestException as exc:
            print(f"\nNetwork error: {exc}")
        except KeyboardInterrupt:
            print("\nCancelled.")



def parse_args(argv: Optional[Iterable[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=f"Earthlink Reseller App API POC {CLI_VERSION}",
        epilog=(
            "Examples:\n"
            "  py earthlink_app_api_poc_v0_6_48.py --verbose\n"
            "  py earthlink_app_api_poc_v0_6_48.py --login --verbose\n"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--base-url", default=os.environ.get("EARTHLINK_API_BASE", DEFAULT_BASE_URL))
    parser.add_argument("--token-file", default=str(DEFAULT_TOKEN_FILE))
    parser.add_argument("--login", action="store_true", help="Force new login and overwrite saved token")
    parser.add_argument("--no-token-cache", action="store_true", help="Do not reuse saved token; login every run")
    parser.add_argument("--verbose", action="store_true", help="Show HTTP request/debug logs")
    parser.add_argument("--debug-payloads", action="store_true", help="With --verbose, print full JSON payloads instead of compact summaries")
    parser.add_argument("--enable-write", action="store_true", help=argparse.SUPPRESS)  # Deprecated; write actions are enabled by default.
    parser.add_argument("--env", default=".env", help="Path to .env file")
    parser.add_argument("--version", action="store_true", help="Print version and exit")
    return parser.parse_args(argv)


def main(argv: Optional[Iterable[str]] = None) -> int:
    args = parse_args(argv)

    if args.version:
        print(f"Earthlink App API POC {CLI_VERSION}")
        return 0

    load_dotenv_file(args.env)

    token_file = Path(args.token_file)
    client = EarthlinkAppApiClient(
        base_url=args.base_url,
        token_file=token_file,
        verbose=args.verbose,
        debug_payloads=args.debug_payloads,
    )

    try:
        force_login = args.login or args.no_token_cache
        if args.no_token_cache:
            client.clear_token()

        client.ensure_login(force_login=force_login)
        interactive_menu(client, enable_write=True)
        return 0

    except ApiError as exc:
        print(f"API error: {exc}")
        return 1
    except KeyboardInterrupt:
        print("\nExiting.")
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
