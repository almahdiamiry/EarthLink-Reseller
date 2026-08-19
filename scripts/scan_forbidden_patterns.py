#!/usr/bin/env python3
"""
scripts/scan_forbidden_patterns.py

Generic, registry-driven structural regression scanner for Earthlink Reseller App.
Driven exclusively by contract/forbidden_patterns.yaml without hardcoded checks.

Features:
- Registry self-validation: checks schema, regex syntax, glob syntax, unique IDs, and valid invariant references (INV-01..INV-16).
- Supports check types:
    * 'regex': matches forbidden regular expressions within specified file patterns.
    * 'semantic_combo': flags forbidden patterns only when all required semantic symbols are co-present.
    * 'file_glob': detects presence of disallowed files matching glob patterns.
    * 'cross_file_match': checks cross-file symbol leaks or boundary violations.
- Line-level precision: reports exact line numbers and code snippets for each violation.
- JSON and human-readable output formats.
- Exit code 0 if 0 violations found, non-zero if violations or errors occur.
"""

import argparse
import glob
import json
import os
import re
import sys
import os
sys.path.insert(0, os.path.dirname(__file__))
import yaml

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DEFAULT_REGISTRY_PATH = os.path.join(REPO_ROOT, "contract", "forbidden_patterns.yaml")
VALID_INVARIANTS = {f"INV-{i:02d}" for i in range(1, 17)}
SUPPORTED_CHECK_TYPES = {"regex", "semantic_combo", "file_glob", "cross_file_match", "behavioral_fixture"}


def validate_registry(registry_data: dict) -> list[str]:
    """
    Validates the structure and content of the forbidden patterns registry.
    Returns a list of error messages (empty if valid).
    """
    errors = []

    if not isinstance(registry_data, dict):
        return ["Registry root must be a YAML mapping/dict."]

    patterns = registry_data.get("patterns")
    if not isinstance(patterns, list):
        return ["'patterns' key must be a list of pattern definitions."]

    if len(patterns) == 0:
        return ["'patterns' list cannot be empty."]

    seen_ids = set()

    for idx, pat in enumerate(patterns):
        prefix = f"Pattern #{idx + 1}"
        if not isinstance(pat, dict):
            errors.append(f"{prefix}: definition must be a dictionary.")
            continue

        p_id = pat.get("id")
        if not p_id or not isinstance(p_id, str) or not p_id.strip():
            errors.append(f"{prefix}: 'id' is required and must be a non-empty string.")
            p_id = f"unknown_{idx}"
        else:
            p_id = p_id.strip()
            if p_id in seen_ids:
                errors.append(f"[{p_id}]: Duplicate pattern ID detected.")
            seen_ids.add(p_id)

        inv = pat.get("invariant")
        if not inv or inv not in VALID_INVARIANTS:
            errors.append(f"[{p_id}]: 'invariant' must be one of {sorted(list(VALID_INVARIANTS))}, got '{inv}'.")

        desc = pat.get("description")
        if not desc or not isinstance(desc, str) or not desc.strip():
            errors.append(f"[{p_id}]: 'description' is required and must be a non-empty string.")

        check_type = pat.get("check_type")
        if not check_type or check_type not in SUPPORTED_CHECK_TYPES:
            errors.append(f"[{p_id}]: 'check_type' must be one of {sorted(list(SUPPORTED_CHECK_TYPES))}, got '{check_type}'.")
            continue

        # Check-specific validation
        allowed_in_functions = pat.get("allowed_in_functions")
        if allowed_in_functions is not None:
            if not isinstance(allowed_in_functions, list) or len(allowed_in_functions) == 0:
                errors.append(f"[{p_id}]: 'allowed_in_functions' must be a non-empty list of function names.")
            else:
                for f_idx, fn in enumerate(allowed_in_functions):
                    if not isinstance(fn, str) or not fn.strip():
                        errors.append(f"[{p_id}]: 'allowed_in_functions' item at index {f_idx} is empty or not a string.")

        if check_type == "regex":
            file_glob = pat.get("file_glob")
            files = pat.get("files")
            if not file_glob and not files:
                errors.append(f"[{p_id}]: 'regex' check requires 'file_glob' or 'files'.")

            forbidden_regexes = pat.get("forbidden_regexes")
            if not forbidden_regexes or not isinstance(forbidden_regexes, list) or len(forbidden_regexes) == 0:
                errors.append(f"[{p_id}]: 'regex' check requires a non-empty 'forbidden_regexes' list.")
            else:
                for r_idx, rx in enumerate(forbidden_regexes):
                    if not isinstance(rx, str) or not rx.strip():
                        errors.append(f"[{p_id}]: regex at index {r_idx} is empty or not a string.")
                    else:
                        try:
                            re.compile(rx)
                        except re.error as e:
                            errors.append(f"[{p_id}]: Invalid regular expression '{rx}': {e}")

        elif check_type == "semantic_combo":
            file_glob = pat.get("file_glob")
            files = pat.get("files")
            if not file_glob and not files:
                errors.append(f"[{p_id}]: 'semantic_combo' check requires 'file_glob' or 'files'.")

            symbols = pat.get("required_symbols")
            if not symbols or not isinstance(symbols, list) or len(symbols) == 0:
                errors.append(f"[{p_id}]: 'semantic_combo' requires a non-empty 'required_symbols' list.")
            else:
                for s_idx, sym in enumerate(symbols):
                    if not isinstance(sym, str) or not sym.strip():
                        errors.append(f"[{p_id}]: required symbol at index {s_idx} is empty.")

            forbidden_regexes = pat.get("forbidden_regexes", [])
            if forbidden_regexes and isinstance(forbidden_regexes, list):
                for r_idx, rx in enumerate(forbidden_regexes):
                    if not isinstance(rx, str) or not rx.strip():
                        errors.append(f"[{p_id}]: combo regex at index {r_idx} is empty or not a string.")
                    else:
                        try:
                            re.compile(rx)
                        except re.error as e:
                            errors.append(f"[{p_id}]: Invalid combo regular expression '{rx}': {e}")

        elif check_type == "file_glob":
            file_glob = pat.get("file_glob")
            files = pat.get("files")
            disallowed = pat.get("disallowed_globs")
            if not file_glob and not files and not disallowed:
                errors.append(f"[{p_id}]: 'file_glob' check requires 'file_glob', 'files', or 'disallowed_globs'.")

        elif check_type == "cross_file_match":
            source_files = pat.get("source_files") or pat.get("source_glob")
            target_files = pat.get("target_files") or pat.get("target_glob")
            if not source_files or not target_files:
                errors.append(f"[{p_id}]: 'cross_file_match' requires both source and target file definitions.")

        elif check_type == "behavioral_fixture":
            fixture_file = pat.get("fixture_file") or pat.get("file_glob") or pat.get("files")
            fixture_method = pat.get("fixture_method") or pat.get("test_name")
            if not fixture_file:
                errors.append(f"[{p_id}]: 'behavioral_fixture' check requires 'fixture_file' or 'file_glob'.")
            if not fixture_method:
                errors.append(f"[{p_id}]: 'behavioral_fixture' check requires 'fixture_method' or 'test_name'.")

    return errors


def resolve_file_targets(root_dir: str, pat: dict) -> list[str]:
    """Resolves all existing file paths matching the pattern's file specs."""
    targets = set()

    # Single glob
    fg = pat.get("file_glob")
    if fg:
        full_pattern = os.path.join(root_dir, fg)
        for p in glob.glob(full_pattern, recursive=True):
            if os.path.isfile(p):
                targets.add(os.path.abspath(p))

    # List of explicit files or globs
    flist = pat.get("files", [])
    if isinstance(flist, list):
        for item in flist:
            full_item = os.path.join(root_dir, item)
            for p in glob.glob(full_item, recursive=True):
                if os.path.isfile(p):
                    targets.add(os.path.abspath(p))

    return sorted(list(targets))


def extract_kotlin_function_spans(lines: list[str]) -> list[dict]:
    """
    Extracts Kotlin function scopes (name, start_line, end_line).
    Line numbers are 1-indexed.
    """
    raw_text = "".join(lines)

    # 1. Clean the text (replace comments, string literals with spaces, keeping newlines)
    cleaned_chars = []
    i = 0
    n = len(raw_text)
    in_block_comment = False
    in_line_comment = False
    in_triple_quote = False
    in_str = False
    in_char = False

    while i < n:
        c = raw_text[i]
        c2 = raw_text[i:i+2]
        c3 = raw_text[i:i+3]

        if in_line_comment:
            if c == '\n':
                in_line_comment = False
                cleaned_chars.append('\n')
            else:
                cleaned_chars.append(' ')
            i += 1
            continue

        if in_block_comment:
            if c2 == '*/':
                in_block_comment = False
                cleaned_chars.append('  ')
                i += 2
            else:
                if c == '\n':
                    cleaned_chars.append('\n')
                else:
                    cleaned_chars.append(' ')
                i += 1
            continue

        if in_triple_quote:
            if c3 == '"""':
                in_triple_quote = False
                cleaned_chars.append('   ')
                i += 3
            else:
                if c == '\n':
                    cleaned_chars.append('\n')
                else:
                    cleaned_chars.append(' ')
                i += 1
            continue

        if in_str:
            if c == '\\':
                cleaned_chars.append('  ')
                i += 2
                continue
            if c == '"':
                in_str = False
                cleaned_chars.append(' ')
                i += 1
            else:
                if c == '\n':
                    cleaned_chars.append('\n')
                else:
                    cleaned_chars.append(' ')
                i += 1
            continue

        if in_char:
            if c == '\\':
                cleaned_chars.append('  ')
                i += 2
                continue
            if c == "'":
                in_char = False
                cleaned_chars.append(' ')
                i += 1
            else:
                cleaned_chars.append(' ')
                i += 1
            continue

        # Normal code
        if c2 == '//':
            in_line_comment = True
            cleaned_chars.append('  ')
            i += 2
            continue
        if c2 == '/*':
            in_block_comment = True
            cleaned_chars.append('  ')
            i += 2
            continue
        if c3 == '"""':
            in_triple_quote = True
            cleaned_chars.append('   ')
            i += 3
            continue
        if c == '"':
            in_str = True
            cleaned_chars.append(' ')
            i += 1
            continue
        if c == "'":
            in_char = True
            cleaned_chars.append(' ')
            i += 1
            continue

        cleaned_chars.append(c)
        i += 1

    cleaned_text = "".join(cleaned_chars)

    # 2. Find function declarations and track their body braces
    fun_pattern = re.compile(r'\bfun\s+(?:<[^>]+>\s+)?(?:[A-Za-z0-9_]+\.)?([A-Za-z0-9_]+)\s*[\(<]')

    line_starts = [0]
    for idx, ch in enumerate(cleaned_text):
        if ch == '\n':
            line_starts.append(idx + 1)

    import bisect
    def get_line_num(char_idx: int) -> int:
        return bisect.bisect_right(line_starts, char_idx)

    spans = []
    for match in fun_pattern.finditer(cleaned_text):
        func_name = match.group(1)
        start_char = match.start()
        start_line = get_line_num(start_char)

        body_start = None
        brace_count = 0
        paren_count = 1 if cleaned_text[match.end() - 1] == '(' else 0
        search_idx = match.end()
        found_brace = False

        while search_idx < len(cleaned_text):
            ch = cleaned_text[search_idx]
            if ch == '(':
                paren_count += 1
            elif ch == ')':
                if paren_count > 0:
                    paren_count -= 1
            elif ch == '{':
                body_start = search_idx
                brace_count = 1
                found_brace = True
                search_idx += 1
                break
            elif paren_count == 0:
                if ch in (';', '='):
                    break
                elif cleaned_text[search_idx:search_idx+3] in ('fun', 'val', 'var') and search_idx > 0 and cleaned_text[search_idx-1:search_idx].isspace():
                    break
            search_idx += 1

        if found_brace and body_start is not None:
            while search_idx < len(cleaned_text) and brace_count > 0:
                ch = cleaned_text[search_idx]
                if ch == '{':
                    brace_count += 1
                elif ch == '}':
                    brace_count -= 1
                search_idx += 1

            end_line = get_line_num(search_idx - 1)
            spans.append({
                "name": func_name,
                "start_line": start_line,
                "end_line": end_line
            })
        else:
            spans.append({
                "name": func_name,
                "start_line": start_line,
                "end_line": start_line
            })

    return spans


def is_line_in_allowed_functions(line_idx: int, allowed_funcs: list[str], function_spans: list[dict]) -> bool:
    if not allowed_funcs or not function_spans:
        return False
    for span in function_spans:
        if span["name"] in allowed_funcs and span["start_line"] <= line_idx <= span["end_line"]:
            return True
    return False


def scan_patterns(root_dir: str = REPO_ROOT, registry_path: str = DEFAULT_REGISTRY_PATH) -> dict:
    """
    Executes the forbidden pattern scan across the specified repository root.
    Returns a dictionary containing full scan results and violation details.
    """
    if not os.path.exists(registry_path):
        return {
            "status": "FAIL",
            "details": f"Forbidden patterns registry file not found: {registry_path}",
            "validation_errors": [f"Missing registry file: {registry_path}"],
            "violations_count": 0,
            "violations": [],
            "pattern_results": {}
        }

    try:
        with open(registry_path, "r", encoding="utf-8") as f:
            registry_data = yaml.safe_load(f)
    except Exception as e:
        return {
            "status": "FAIL",
            "details": f"Failed to parse registry YAML: {e}",
            "validation_errors": [f"YAML parse error: {e}"],
            "violations_count": 0,
            "violations": [],
            "pattern_results": {}
        }

    val_errors = validate_registry(registry_data)
    if val_errors:
        return {
            "status": "FAIL",
            "details": f"Registry self-validation failed with {len(val_errors)} error(s).",
            "validation_errors": val_errors,
            "violations_count": 0,
            "violations": [],
            "pattern_results": {}
        }

    patterns = registry_data.get("patterns", [])
    all_violations = []
    pattern_results = {}

    for pat in patterns:
        p_id = pat["id"]
        p_inv = pat["invariant"]
        check_type = pat["check_type"]
        desc = pat.get("description", "")
        pat_violations = []

        if check_type == "regex":
            matched_files = resolve_file_targets(root_dir, pat)
            regexes = [re.compile(rx) for rx in pat.get("forbidden_regexes", [])]
            allowed_funcs = pat.get("allowed_in_functions")

            for filepath in matched_files:
                rel_path = os.path.relpath(filepath, root_dir).replace("\\", "/")
                try:
                    with open(filepath, "r", encoding="utf-8", errors="replace") as f:
                        lines = f.readlines()
                    func_spans = extract_kotlin_function_spans(lines) if (allowed_funcs and rel_path.endswith(".kt")) else []
                    
                    scrubbed_lines = list(lines)
                    if allowed_funcs and rel_path.endswith(".kt"):
                        for line_idx in range(1, len(lines) + 1):
                            if is_line_in_allowed_functions(line_idx, allowed_funcs, func_spans):
                                orig_line = lines[line_idx - 1]
                                newline_char = '\n' if orig_line.endswith('\n') else ''
                                scrubbed_lines[line_idx - 1] = ' ' * (len(orig_line) - len(newline_char)) + newline_char
                    
                    scrubbed_content = "".join(scrubbed_lines)
                    
                    for rx in regexes:
                        for match in rx.finditer(scrubbed_content):
                            match_start = match.start()
                            match_line_idx = scrubbed_content[:match_start].count('\n') + 1
                            line_content = lines[match_line_idx - 1].strip()
                            violation_entry = {
                                "pattern_id": p_id,
                                "invariant": p_inv,
                                "check_type": check_type,
                                "file": rel_path,
                                "line_number": match_line_idx,
                                "matched_pattern": rx.pattern,
                                "line_content": line_content,
                                "message": f"[{p_id}] Prohibited pattern '{rx.pattern}' found in {rel_path}:{match_line_idx}"
                            }
                            pat_violations.append(violation_entry)
                            all_violations.append(violation_entry)
                except Exception as e:
                    violation_entry = {
                        "pattern_id": p_id,
                        "invariant": p_inv,
                        "check_type": check_type,
                        "file": rel_path,
                        "line_number": 0,
                        "matched_pattern": "FILE_READ_ERROR",
                        "line_content": "",
                        "message": f"[{p_id}] Error reading {rel_path}: {e}"
                    }
                    pat_violations.append(violation_entry)
                    all_violations.append(violation_entry)

        elif check_type == "semantic_combo":
            matched_files = resolve_file_targets(root_dir, pat)
            required_symbols = pat.get("required_symbols", [])
            combo_regexes = [re.compile(rx) for rx in pat.get("forbidden_regexes", [])]
            allowed_funcs = pat.get("allowed_in_functions")

            for filepath in matched_files:
                rel_path = os.path.relpath(filepath, root_dir).replace("\\", "/")
                try:
                    with open(filepath, "r", encoding="utf-8", errors="replace") as f:
                        lines = f.readlines()
                    full_content = "".join(lines)

                    # Check if all required symbols exist in file
                    symbols_present = all(sym in full_content for sym in required_symbols)
                    if symbols_present:
                        func_spans = extract_kotlin_function_spans(lines) if (allowed_funcs and rel_path.endswith(".kt")) else []
                        scrubbed_lines = list(lines)
                        if allowed_funcs and rel_path.endswith(".kt"):
                            for line_idx in range(1, len(lines) + 1):
                                if is_line_in_allowed_functions(line_idx, allowed_funcs, func_spans):
                                    orig_line = lines[line_idx - 1]
                                    newline_char = '\n' if orig_line.endswith('\n') else ''
                                    scrubbed_lines[line_idx - 1] = ' ' * (len(orig_line) - len(newline_char)) + newline_char
                        
                        scrubbed_content = "".join(scrubbed_lines)

                        for rx in combo_regexes:
                            for match in rx.finditer(scrubbed_content):
                                match_start = match.start()
                                match_line_idx = scrubbed_content[:match_start].count('\n') + 1
                                line_content = lines[match_line_idx - 1].strip()
                                violation_entry = {
                                    "pattern_id": p_id,
                                    "invariant": p_inv,
                                    "check_type": check_type,
                                    "file": rel_path,
                                    "line_number": match_line_idx,
                                    "matched_pattern": rx.pattern,
                                    "line_content": line_content,
                                    "message": f"[{p_id}] Prohibited semantic combo ('{rx.pattern}' with symbols {required_symbols}) in {rel_path}:{match_line_idx}"
                                }
                                pat_violations.append(violation_entry)
                                all_violations.append(violation_entry)
                except Exception as e:
                    violation_entry = {
                        "pattern_id": p_id,
                        "invariant": p_inv,
                        "check_type": check_type,
                        "file": rel_path,
                        "line_number": 0,
                        "matched_pattern": "FILE_READ_ERROR",
                        "line_content": "",
                        "message": f"[{p_id}] Error reading {rel_path}: {e}"
                    }
                    pat_violations.append(violation_entry)
                    all_violations.append(violation_entry)

        elif check_type == "file_glob":
            disallowed_patterns = []
            if pat.get("file_glob"):
                disallowed_patterns.append(pat["file_glob"])
            disallowed_patterns.extend(pat.get("disallowed_globs", []))
            disallowed_patterns.extend(pat.get("files", []))

            for dp in disallowed_patterns:
                full_pattern = os.path.join(root_dir, dp)
                for matched_f in glob.glob(full_pattern, recursive=True):
                    if os.path.exists(matched_f):
                        rel_path = os.path.relpath(matched_f, root_dir).replace("\\", "/")
                        violation_entry = {
                            "pattern_id": p_id,
                            "invariant": p_inv,
                            "check_type": check_type,
                            "file": rel_path,
                            "line_number": 1,
                            "matched_pattern": dp,
                            "line_content": "",
                            "message": f"[{p_id}] Disallowed file present on disk: {rel_path}"
                        }
                        pat_violations.append(violation_entry)
                        all_violations.append(violation_entry)

        elif check_type == "cross_file_match":
            # Cross-file pattern evaluation
            source_glob = pat.get("source_glob")
            target_glob = pat.get("target_glob")
            forbidden_tokens = pat.get("forbidden_tokens", [])
            if source_glob and target_glob:
                s_files = glob.glob(os.path.join(root_dir, source_glob), recursive=True)
                t_files = glob.glob(os.path.join(root_dir, target_glob), recursive=True)
                for tf in t_files:
                    rel_path = os.path.relpath(tf, root_dir).replace("\\", "/")
                    try:
                        with open(tf, "r", encoding="utf-8", errors="replace") as f:
                            content = f.read()
                        for tok in forbidden_tokens:
                            if tok in content:
                                violation_entry = {
                                    "pattern_id": p_id,
                                    "invariant": p_inv,
                                    "check_type": check_type,
                                    "file": rel_path,
                                    "line_number": 1,
                                    "matched_pattern": tok,
                                    "line_content": "",
                                    "message": f"[{p_id}] Prohibited cross-file token '{tok}' referenced in {rel_path}"
                                }
                                pat_violations.append(violation_entry)
                                all_violations.append(violation_entry)
                    except Exception as e:
                        pass

        elif check_type == "behavioral_fixture":
            matched_files = resolve_file_targets(root_dir, pat)
            if not matched_files:
                ff = pat.get("fixture_file")
                if ff:
                    full_p = os.path.join(root_dir, ff)
                    if os.path.isfile(full_p):
                        matched_files = [full_p]

            fixture_methods = pat.get("fixture_method") or pat.get("test_name")
            if isinstance(fixture_methods, str):
                fixture_methods = [fixture_methods]

            if not matched_files:
                violation_entry = {
                    "pattern_id": p_id,
                    "invariant": p_inv,
                    "check_type": check_type,
                    "file": pat.get("fixture_file") or pat.get("file_glob", ""),
                    "line_number": 0,
                    "matched_pattern": "MISSING_FIXTURE_FILE",
                    "line_content": "",
                    "message": f"[{p_id}] Mandatory behavioral fixture file not found on disk."
                }
                pat_violations.append(violation_entry)
                all_violations.append(violation_entry)
            else:
                for filepath in matched_files:
                    rel_path = os.path.relpath(filepath, root_dir).replace("\\", "/")
                    try:
                        with open(filepath, "r", encoding="utf-8", errors="replace") as f:
                            content = f.read()
                        if fixture_methods:
                            for fm in fixture_methods:
                                if fm not in content:
                                    violation_entry = {
                                        "pattern_id": p_id,
                                        "invariant": p_inv,
                                        "check_type": check_type,
                                        "file": rel_path,
                                        "line_number": 0,
                                        "matched_pattern": fm,
                                        "line_content": "",
                                        "message": f"[{p_id}] Mandatory behavioral fixture method '{fm}' not found in {rel_path}."
                                    }
                                    pat_violations.append(violation_entry)
                                    all_violations.append(violation_entry)
                    except Exception as e:
                        violation_entry = {
                            "pattern_id": p_id,
                            "invariant": p_inv,
                            "check_type": check_type,
                            "file": rel_path,
                            "line_number": 0,
                            "matched_pattern": "FILE_READ_ERROR",
                            "line_content": "",
                            "message": f"[{p_id}] Error reading fixture file {rel_path}: {e}"
                        }
                        pat_violations.append(violation_entry)
                        all_violations.append(violation_entry)

        pattern_results[p_id] = {
            "status": "PASS" if len(pat_violations) == 0 else "FAIL",
            "invariant": p_inv,
            "description": desc,
            "violations_count": len(pat_violations),
            "violations": [v["message"] for v in pat_violations],
            "violation_details": pat_violations
        }

    status = "PASS" if len(all_violations) == 0 else "FAIL"
    return {
        "status": status,
        "details": f"Scanned {len(patterns)} registered patterns across repository. Found {len(all_violations)} violation(s).",
        "total_patterns_scanned": len(patterns),
        "violations_count": len(all_violations),
        "violations": [v["message"] for v in all_violations],
        "violation_details": all_violations,
        "pattern_results": pattern_results
    }


def main():
    parser = argparse.ArgumentParser(description="Scan repository for forbidden architectural patterns.")
    parser.add_argument("--registry", default=DEFAULT_REGISTRY_PATH, help="Path to forbidden_patterns.yaml")
    parser.add_argument("--root", default=REPO_ROOT, help="Repository root directory")
    parser.add_argument("--output", help="Optional JSON output file path")
    parser.add_argument("--validate-only", action="store_true", help="Validate registry syntax only")
    parser.add_argument("--json", action="store_true", help="Output results as JSON to stdout")
    args = parser.parse_args()

    registry_file = os.path.abspath(args.registry)
    root_dir = os.path.abspath(args.root)

    if args.validate_only:
        if not os.path.exists(registry_file):
            print(f"[FAIL] Registry file not found: {registry_file}")
            sys.exit(2)
        with open(registry_file, "r", encoding="utf-8") as f:
            try:
                data = yaml.safe_load(f)
            except Exception as e:
                print(f"[FAIL] YAML syntax error: {e}")
                sys.exit(2)
        errors = validate_registry(data)
        if errors:
            print(f"[FAIL] Registry validation failed with {len(errors)} error(s):")
            for err in errors:
                print(f"  * {err}")
            sys.exit(2)
        else:
            print(f"[PASS] Registry at {registry_file} is valid.")
            sys.exit(0)

    result = scan_patterns(root_dir=root_dir, registry_path=registry_file)

    if args.output:
        out_dir = os.path.dirname(os.path.abspath(args.output))
        if out_dir and not os.path.exists(out_dir):
            os.makedirs(out_dir, exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(result, f, indent=2)

    if args.json:
        print(json.dumps(result, indent=2))
        sys.exit(0 if result["status"] == "PASS" else 2)

    print("=================================================================")
    print("=== Earthlink Reseller App -- Forbidden Pattern Scanner =======")
    print("=================================================================")
    print(f"Registry Path : {os.path.relpath(registry_file, root_dir)}")
    print(f"Root Directory: {root_dir}")
    print("-----------------------------------------------------------------")

    if result.get("validation_errors"):
        print("[FAIL] Registry validation errors:")
        for err in result["validation_errors"]:
            print(f"   * {err}")
        print("=================================================================")
        sys.exit(2)

    pat_results = result.get("pattern_results", {})
    for p_id, p_info in pat_results.items():
        status_tag = f"[{p_info['status']}]"
        inv_tag = f"({p_info['invariant']})"
        desc = p_info.get("description", "")
        print(f"  {status_tag:<8} {p_id:<32} {inv_tag:<10} - {desc}")
        for v in p_info.get("violations", []):
            print(f"           --> {v}")

    print("-----------------------------------------------------------------")
    print(f"Summary: {result['details']}")
    print("=================================================================")

    if result["status"] == "PASS":
        print("=== FORBIDDEN PATTERN SCAN: PASSED (0 Violations) ===")
        print("=================================================================")
        sys.exit(0)
    else:
        print(f"=== FORBIDDEN PATTERN SCAN: FAILED ({result['violations_count']} Violation(s)) ===")
        print("=================================================================")
        sys.exit(2)


if __name__ == "__main__":
    main()
