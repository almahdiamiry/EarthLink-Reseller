#!/usr/bin/env python3
"""
uTower migration converter

Usage:
  python utower_migration_converter.py --input utower_data.tgz --out-dir utower_migration_out

Produces:
  - utower_export_full.json        Full normalized data for import
  - utower_migration_preview.json  Counts and safe summary
  - utower_migration.sqlite        SQLite import preview database

Notes:
  - Amounts stored by uTower like 40, 50, 760 appear to mean thousand IQD.
    The converter keeps both *_unit and *_iqd fields.
  - The converter intentionally does not export Firebase auth tokens or Earthlink cached JWT tokens.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sqlite3
import tarfile
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Tuple
from xml.etree import ElementTree as ET


def ms_to_iso(ms: Any) -> str | None:
    try:
        if ms is None or ms == "":
            return None
        return datetime.fromtimestamp(int(ms) / 1000, tz=timezone.utc).isoformat()
    except Exception:
        return None


def unit_to_iqd(value: Any) -> int | None:
    try:
        if value is None or value == "":
            return None
        return int(round(float(value) * 1000))
    except Exception:
        return None


def read_json_blob(blob: bytes) -> Any:
    return json.loads(blob.decode("utf-8"))


def extract_tgz(input_path: Path, workdir: Path) -> Path:
    with tarfile.open(input_path, "r:gz") as tf:
        tf.extractall(workdir)
    return workdir


def realtime_db_path(root: Path) -> Path:
    candidates = list((root / "databases").glob("*.firebaseio.com_default"))
    if not candidates:
        raise FileNotFoundError("Could not find Firebase Realtime Database cache (*.firebaseio.com_default)")
    return candidates[0]


def load_server_cache(db_path: Path) -> List[Tuple[str, Any]]:
    con = sqlite3.connect(str(db_path))
    try:
        rows = []
        for path, value in con.execute("select path, value from serverCache"):
            try:
                rows.append((path, read_json_blob(value)))
            except Exception:
                pass
        return rows
    finally:
        con.close()


def path_parts(path: str) -> List[str]:
    return [x for x in path.strip("/").split("/") if x]


def normalize_subscriber(source_key: str, data: Dict[str, Any], source_path: str) -> Dict[str, Any]:
    live = data.get("live") if isinstance(data.get("live"), dict) else {}
    utower = data.get("utower") if isinstance(data.get("utower"), dict) else {}
    debt_unit = utower.get("debts", data.get("debts"))
    price_unit = utower.get("currentPrice", data.get("currentPrice"))
    return {
        "source": "utower_realtime_live_users",
        "source_key": source_key,
        "source_path": source_path,
        "earthlink_user_id": live.get("id"),
        "earthlink_username": live.get("username"),
        "display_name": live.get("name"),
        "package_name": live.get("profileName"),
        "package_id": live.get("profileId"),
        "parent": live.get("parent"),
        "board_name": live.get("boardName"),
        "owner_username": live.get("ownerUsername"),
        "phone1": live.get("phone") or utower.get("phoneNumber"),
        "phone2": utower.get("phoneNumber2"),
        "debt_unit": debt_unit,
        "debt_iqd": unit_to_iqd(debt_unit),
        "current_price_unit": price_unit,
        "current_price_iqd": unit_to_iqd(price_unit),
        "subscription_end_ms": live.get("end"),
        "subscription_end_iso_utc": ms_to_iso(live.get("end")),
        "nano_ip": utower.get("nanoIp"),
        "nano_user": utower.get("nanoUser"),
        "nano_password": utower.get("nanoPassword"),
        "note": utower.get("note"),
        "is_last_sub_cash": utower.get("isLastSubCash"),
        "restricted": live.get("restricted"),
        "is_synced": live.get("isSynced"),
        "legacy_old_user_add_time_id": utower.get("oldUserAddTimeId"),
        "edit_type": utower.get("editType"),
        "raw": data,
    }


def normalize_legacy_user(source_key: str, data: Dict[str, Any], source_path: str) -> Dict[str, Any]:
    price_unit = data.get("currentPrice")
    total_unit = data.get("totalPrice")
    return {
        "source": "utower_realtime_legacy_users",
        "source_key": source_key,
        "source_path": source_path,
        "earthlink_username": data.get("userName"),
        "display_name": data.get("name"),
        "phone1": data.get("phoneNumber"),
        "debt_unit": total_unit,
        "debt_iqd": unit_to_iqd(total_unit),
        "current_price_unit": price_unit,
        "current_price_iqd": unit_to_iqd(price_unit),
        "subscription_start_ms": data.get("start"),
        "subscription_start_iso_utc": ms_to_iso(data.get("start")),
        "subscription_end_ms": data.get("end"),
        "subscription_end_iso_utc": ms_to_iso(data.get("end")),
        "nano_ip": data.get("nanoIp"),
        "nano_user": data.get("nanoUser"),
        "nano_password": data.get("nanoPassword"),
        "note": data.get("note"),
        "is_last_sub_cash": data.get("isLastSubCash"),
        "merged": data.get("merged"),
        "with_hours": data.get("withHours"),
        "raw": data,
    }


def normalize_transaction(source_key: str, data: Dict[str, Any], source_path: str) -> Dict[str, Any]:
    raw_type = data.get("type")
    type_map = {
        "add": "renewal",
        "gave": "payment",
        "debt": "debt_added",
    }
    amount_unit = data.get("amount")
    debt_after_unit = data.get("totalDebitAfter")
    return {
        "source": "utower_realtime_messagesOfHistory",
        "source_key": source_key,
        "source_path": source_path,
        "subscriber_ref": data.get("toWho"),
        "subscriber_name": data.get("toWhoName"),
        "type_raw": raw_type,
        "type_normalized": type_map.get(str(raw_type), str(raw_type) if raw_type is not None else None),
        "amount_unit": amount_unit,
        "amount_iqd": unit_to_iqd(amount_unit),
        "cost_iqd": data.get("cost"),
        "debt_after_unit": debt_after_unit,
        "debt_after_iqd": unit_to_iqd(debt_after_unit),
        "time_ms": data.get("time") or data.get("timeOfAction") or data.get("serverTime") or data.get("timeId"),
        "time_iso_utc": ms_to_iso(data.get("time") or data.get("timeOfAction") or data.get("serverTime") or data.get("timeId")),
        "server_time_ms": data.get("serverTime"),
        "note": data.get("note"),
        "message": data.get("message"),
        "make_calculator": data.get("makeCalculator"),
        "raw": data,
    }


def load_flutter_prefs(root: Path) -> Dict[str, Any]:
    prefs_path = root / "shared_prefs" / "FlutterSharedPreferences.xml"
    result = {
        "uid": None,
        "userName": None,
        "email": None,
        "live_users_cache_count": 0,
        "device_hint_count": 0,
        "device_hints": [],
    }
    if not prefs_path.exists():
        return result
    tree = ET.parse(prefs_path)
    mac_re = re.compile(r"^flutter\.([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    hash_re = re.compile(r"^flutter\.[A-Za-z0-9]{22,40}$")
    for child in tree.getroot():
        key = child.attrib.get("name", "")
        val = child.text if child.text is not None else child.attrib.get("value")
        if key == "flutter.uid":
            result["uid"] = val
        elif key == "flutter.userName":
            result["userName"] = val
        elif key == "flutter.email":
            result["email"] = val
        elif key.startswith("flutter.live_users_cache_v1_"):
            try:
                cache = json.loads(val or "[]")
                if isinstance(cache, list):
                    result["live_users_cache_count"] = len(cache)
            except Exception:
                pass
        elif mac_re.match(key) or hash_re.match(key):
            # These appear to be device label/IP cache hints. Keep but do not treat as authoritative.
            if val:
                result["device_hints"].append({"key": key.removeprefix("flutter."), "value": val})
    result["device_hint_count"] = len(result["device_hints"])
    return result


def build_export(root: Path) -> Dict[str, Any]:
    db = realtime_db_path(root)
    rows = load_server_cache(db)
    subscribers = []
    legacy_users = []
    transactions = []
    config = {}
    raw_top = {}
    source_uid = None

    for path, data in rows:
        parts = path_parts(path)
        if parts:
            source_uid = source_uid or parts[0]
        if len(parts) == 3 and parts[1] == "live_users" and parts[2].startswith("e_") and isinstance(data, dict):
            subscribers.append(normalize_subscriber(parts[2], data, path))
        elif len(parts) == 3 and parts[1] == "users" and isinstance(data, dict):
            legacy_users.append(normalize_legacy_user(parts[2], data, path))
        elif len(parts) == 3 and parts[1] == "messagesOfHistory" and isinstance(data, dict):
            transactions.append(normalize_transaction(parts[2], data, path))
        elif len(parts) == 2 and isinstance(data, (dict, list, int, float, str, bool)):
            raw_top[parts[1]] = data

    # Stable sort
    subscribers.sort(key=lambda x: str(x.get("earthlink_username") or x.get("source_key") or ""))
    legacy_users.sort(key=lambda x: str(x.get("earthlink_username") or x.get("source_key") or ""))
    transactions.sort(key=lambda x: (x.get("time_ms") or 0, str(x.get("source_key"))))

    prefs = load_flutter_prefs(root)

    export = {
        "schema_version": 1,
        "exported_at_utc": datetime.now(timezone.utc).isoformat(),
        "source_app": "uTower",
        "source_package": "com.mobx.utower",
        "source_uid": source_uid,
        "currency": "IQD",
        "amount_note": "uTower fields named amount/debts/currentPrice/totalDebitAfter appear to use thousand-IQD units. This export keeps both *_unit and *_iqd fields.",
        "subscribers": subscribers,
        "legacy_users": legacy_users,
        "transactions": transactions,
        "config": {
            "autoSends": raw_top.get("autoSends"),
            "auto_nano": raw_top.get("auto_nano"),
            "setting": raw_top.get("setting"),
            "live_users_meta": raw_top.get("live_users_meta"),
            "antliz": raw_top.get("antliz"),
            "deep_analysis": raw_top.get("deep_analysis"),
            "flutter_prefs_summary": {
                "uid": prefs.get("uid"),
                "userName": prefs.get("userName"),
                "email": prefs.get("email"),
                "live_users_cache_count": prefs.get("live_users_cache_count"),
                "device_hint_count": prefs.get("device_hint_count"),
            },
        },
        "device_hints": prefs.get("device_hints", []),
    }
    return export


def make_preview(export: Dict[str, Any]) -> Dict[str, Any]:
    subs = export["subscribers"]
    txs = export["transactions"]
    legacy = export["legacy_users"]
    package_counts: Dict[str, int] = {}
    board_counts: Dict[str, int] = {}
    tx_type_counts: Dict[str, int] = {}
    for s in subs:
        package_counts[str(s.get("package_name") or "")] = package_counts.get(str(s.get("package_name") or ""), 0) + 1
        board_counts[str(s.get("board_name") or "")] = board_counts.get(str(s.get("board_name") or ""), 0) + 1
    for t in txs:
        tx_type_counts[str(t.get("type_normalized") or "")] = tx_type_counts.get(str(t.get("type_normalized") or ""), 0) + 1
    return {
        "schema_version": export["schema_version"],
        "source_app": export["source_app"],
        "source_package": export["source_package"],
        "source_uid": export.get("source_uid"),
        "counts": {
            "subscribers_live_users": len(subs),
            "legacy_users": len(legacy),
            "transactions": len(txs),
            "device_hints": len(export.get("device_hints", [])),
        },
        "totals": {
            "debt_unit_sum": sum(float(s.get("debt_unit") or 0) for s in subs),
            "debt_iqd_sum": sum(int(s.get("debt_iqd") or 0) for s in subs),
            "transaction_amount_unit_sum": sum(float(t.get("amount_unit") or 0) for t in txs),
            "transaction_amount_iqd_sum": sum(int(t.get("amount_iqd") or 0) for t in txs),
            "renewal_cost_iqd_sum": sum(int(t.get("cost_iqd") or 0) for t in txs if t.get("type_normalized") == "renewal"),
        },
        "breakdowns": {
            "packages": dict(sorted(package_counts.items(), key=lambda kv: (-kv[1], kv[0]))),
            "boards": dict(sorted(board_counts.items(), key=lambda kv: (-kv[1], kv[0]))),
            "transaction_types": dict(sorted(tx_type_counts.items(), key=lambda kv: (-kv[1], kv[0]))),
        },
        "notes": [
            "Full export intentionally excludes Firebase auth tokens and cached Earthlink JWT tokens.",
            "Use subscribers as primary current records; legacy_users are older / merged records and should be imported as archived/fallback unless unmatched.",
            "Amounts in *_unit are likely thousand-IQD values; use *_iqd for your new app ledger.",
        ],
    }


def create_sqlite(export: Dict[str, Any], db_path: Path) -> None:
    if db_path.exists():
        db_path.unlink()
    con = sqlite3.connect(str(db_path))
    cur = con.cursor()
    cur.executescript(
        """
        PRAGMA journal_mode=WAL;
        CREATE TABLE import_metadata (
          key TEXT PRIMARY KEY,
          value TEXT
        );
        CREATE TABLE subscribers (
          id TEXT PRIMARY KEY,
          source TEXT,
          source_key TEXT,
          earthlink_user_id INTEGER,
          earthlink_username TEXT,
          display_name TEXT,
          package_name TEXT,
          package_id INTEGER,
          parent TEXT,
          board_name TEXT,
          owner_username TEXT,
          phone1 TEXT,
          phone2 TEXT,
          debt_unit REAL,
          debt_iqd INTEGER,
          current_price_unit REAL,
          current_price_iqd INTEGER,
          subscription_end_ms INTEGER,
          subscription_end_iso_utc TEXT,
          nano_ip TEXT,
          nano_user TEXT,
          nano_password TEXT,
          note TEXT,
          is_last_sub_cash INTEGER,
          restricted INTEGER,
          is_synced INTEGER,
          legacy_old_user_add_time_id TEXT,
          edit_type TEXT,
          raw_json TEXT
        );
        CREATE TABLE legacy_users (
          id TEXT PRIMARY KEY,
          source TEXT,
          source_key TEXT,
          earthlink_username TEXT,
          display_name TEXT,
          phone1 TEXT,
          debt_unit REAL,
          debt_iqd INTEGER,
          current_price_unit REAL,
          current_price_iqd INTEGER,
          subscription_start_ms INTEGER,
          subscription_start_iso_utc TEXT,
          subscription_end_ms INTEGER,
          subscription_end_iso_utc TEXT,
          nano_ip TEXT,
          nano_user TEXT,
          nano_password TEXT,
          note TEXT,
          is_last_sub_cash INTEGER,
          merged INTEGER,
          with_hours INTEGER,
          raw_json TEXT
        );
        CREATE TABLE transactions (
          id TEXT PRIMARY KEY,
          source TEXT,
          source_key TEXT,
          subscriber_ref TEXT,
          subscriber_name TEXT,
          type_raw TEXT,
          type_normalized TEXT,
          amount_unit REAL,
          amount_iqd INTEGER,
          cost_iqd INTEGER,
          debt_after_unit REAL,
          debt_after_iqd INTEGER,
          time_ms INTEGER,
          time_iso_utc TEXT,
          server_time_ms INTEGER,
          note TEXT,
          message TEXT,
          make_calculator INTEGER,
          raw_json TEXT
        );
        CREATE INDEX idx_subscribers_username ON subscribers(earthlink_username);
        CREATE INDEX idx_subscribers_debt ON subscribers(debt_iqd);
        CREATE INDEX idx_transactions_subscriber ON transactions(subscriber_ref);
        CREATE INDEX idx_transactions_time ON transactions(time_ms);
        """
    )
    cur.executemany(
        "INSERT INTO import_metadata(key,value) VALUES(?,?)",
        [
            ("source_app", export.get("source_app")),
            ("source_package", export.get("source_package")),
            ("source_uid", export.get("source_uid")),
            ("exported_at_utc", export.get("exported_at_utc")),
            ("amount_note", export.get("amount_note")),
        ],
    )
    for s in export["subscribers"]:
        cur.execute(
            """INSERT INTO subscribers VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                s["source_key"], s.get("source"), s.get("source_key"), s.get("earthlink_user_id"), s.get("earthlink_username"),
                s.get("display_name"), s.get("package_name"), s.get("package_id"), s.get("parent"), s.get("board_name"), s.get("owner_username"),
                s.get("phone1"), s.get("phone2"), s.get("debt_unit"), s.get("debt_iqd"), s.get("current_price_unit"), s.get("current_price_iqd"),
                s.get("subscription_end_ms"), s.get("subscription_end_iso_utc"), s.get("nano_ip"), s.get("nano_user"), s.get("nano_password"),
                s.get("note"), None if s.get("is_last_sub_cash") is None else int(bool(s.get("is_last_sub_cash"))),
                None if s.get("restricted") is None else int(bool(s.get("restricted"))),
                None if s.get("is_synced") is None else int(bool(s.get("is_synced"))),
                s.get("legacy_old_user_add_time_id"), s.get("edit_type"), json.dumps(s.get("raw"), ensure_ascii=False),
            ),
        )
    for u in export["legacy_users"]:
        cur.execute(
            """INSERT INTO legacy_users VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                u["source_key"], u.get("source"), u.get("source_key"), u.get("earthlink_username"), u.get("display_name"), u.get("phone1"),
                u.get("debt_unit"), u.get("debt_iqd"), u.get("current_price_unit"), u.get("current_price_iqd"),
                u.get("subscription_start_ms"), u.get("subscription_start_iso_utc"), u.get("subscription_end_ms"), u.get("subscription_end_iso_utc"),
                u.get("nano_ip"), u.get("nano_user"), u.get("nano_password"), u.get("note"),
                None if u.get("is_last_sub_cash") is None else int(bool(u.get("is_last_sub_cash"))),
                None if u.get("merged") is None else int(bool(u.get("merged"))),
                None if u.get("with_hours") is None else int(bool(u.get("with_hours"))),
                json.dumps(u.get("raw"), ensure_ascii=False),
            ),
        )
    for t in export["transactions"]:
        cur.execute(
            """INSERT INTO transactions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                t["source_key"], t.get("source"), t.get("source_key"), t.get("subscriber_ref"), t.get("subscriber_name"),
                t.get("type_raw"), t.get("type_normalized"), t.get("amount_unit"), t.get("amount_iqd"), t.get("cost_iqd"),
                t.get("debt_after_unit"), t.get("debt_after_iqd"), t.get("time_ms"), t.get("time_iso_utc"), t.get("server_time_ms"),
                t.get("note"), t.get("message"), None if t.get("make_calculator") is None else int(bool(t.get("make_calculator"))),
                json.dumps(t.get("raw"), ensure_ascii=False),
            ),
        )
    con.commit()
    con.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, help="Path to utower_data.tgz")
    parser.add_argument("--out-dir", required=True, help="Output directory")
    args = parser.parse_args()
    input_path = Path(args.input)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as td:
        root = Path(td) / "extract"
        root.mkdir()
        extract_tgz(input_path, root)
        export = build_export(root)
    preview = make_preview(export)
    (out_dir / "utower_export_full.json").write_text(json.dumps(export, ensure_ascii=False, indent=2), encoding="utf-8")
    (out_dir / "utower_migration_preview.json").write_text(json.dumps(preview, ensure_ascii=False, indent=2), encoding="utf-8")
    create_sqlite(export, out_dir / "utower_migration.sqlite")
    print(json.dumps(preview, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
