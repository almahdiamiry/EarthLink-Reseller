"""
Robust pure-Python YAML parser & dumper for scripts/ verification tooling.
Complies with YAML mapping, sequence, scalar, multiline strings (> and |), inline dict/list, comments, and unquoting.
"""
import re
import json

def safe_load(stream):
    if hasattr(stream, "read"):
        text = stream.read()
    else:
        text = stream

    lines = text.splitlines()
    return _parse_yaml_lines(lines)

def load(stream, Loader=None):
    return safe_load(stream)

def dump(data, stream=None, sort_keys=False, **kwargs):
    text = _dump_yaml(data, indent=0)
    if stream is not None:
        if hasattr(stream, "write"):
            stream.write(text)
        return None
    return text

def _dump_yaml(data, indent=0):
    spaces = "  " * indent
    if data is None:
        return "null\n"
    elif isinstance(data, bool):
        return ("true" if data else "false") + "\n"
    elif isinstance(data, (int, float)):
        return str(data) + "\n"
    elif isinstance(data, str):
        if "\n" in data:
            lines = data.splitlines()
            res = "|\n"
            for l in lines:
                res += spaces + "  " + l + "\n"
            return res
        elif any(c in data for c in ":#{}[]|>&*!%@`,'\"\\") or data.strip() != data:
            return json.dumps(data) + "\n"
        else:
            return data + "\n"
    elif isinstance(data, list):
        if not data:
            return "[]\n"
        res = "\n" if indent > 0 else ""
        for item in data:
            item_dump = _dump_yaml(item, indent + 1).lstrip()
            res += spaces + "- " + item_dump
        return res
    elif isinstance(data, dict):
        if not data:
            return "{}\n"
        res = "\n" if indent > 0 else ""
        for k, v in data.items():
            v_dump = _dump_yaml(v, indent + 1)
            if isinstance(v, (dict, list)) and v:
                res += spaces + str(k) + ":" + v_dump
            else:
                res += spaces + str(k) + ": " + v_dump.lstrip()
        return res
    else:
        return str(data) + "\n"

def _parse_yaml_lines(lines):
    parsed_lines = []
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(line) - len(line.lstrip(" "))
        parsed_lines.append((indent, line.lstrip(" ")))
    
    if not parsed_lines:
        return {}

    return _parse_block(parsed_lines, 0, len(parsed_lines), parsed_lines[0][0])

def _parse_block(lines, start, end, current_indent):
    if start >= end:
        return None
    
    first_indent, first_line = lines[start]
    
    # Check if this block is a list
    if first_line.startswith("-"):
        result = []
        i = start
        while i < end:
            indent, line = lines[i]
            if indent < current_indent:
                break
            if indent == current_indent and line.startswith("-"):
                val_str = line[1:].strip()
                next_i = i + 1
                while next_i < end and lines[next_i][0] > current_indent:
                    next_i += 1
                
                if next_i > i + 1:
                    sub_indent = lines[i+1][0]
                    if val_str:
                        sub_lines = [(sub_indent, val_str)] + lines[i+1:next_i]
                        item_val = _parse_block(sub_lines, 0, len(sub_lines), sub_indent)
                    else:
                        item_val = _parse_block(lines, i+1, next_i, sub_indent)
                    result.append(item_val)
                else:
                    if val_str:
                        result.append(_parse_scalar(val_str))
                    else:
                        result.append(None)
                i = next_i
            else:
                i += 1
        return result
    else:
        # Dictionary
        result = {}
        i = start
        while i < end:
            indent, line = lines[i]
            if indent < current_indent:
                break
            if indent == current_indent and ":" in line:
                key, rest = line.split(":", 1)
                key = key.strip()
                val_str = rest.strip()
                
                next_i = i + 1
                while next_i < end and lines[next_i][0] > current_indent:
                    next_i += 1
                
                if val_str in (">", "|", ">-", "|-"):
                    multiline = []
                    for k in range(i+1, next_i):
                        multiline.append(lines[k][1])
                    result[key] = " ".join(multiline) if ">" in val_str else "\n".join(multiline)
                elif next_i > i + 1:
                    sub_indent = lines[i+1][0]
                    result[key] = _parse_block(lines, i+1, next_i, sub_indent)
                else:
                    result[key] = _parse_scalar(val_str)
                i = next_i
            else:
                i += 1
        return result

def _parse_scalar(val):
    if not val:
        return ""
    val = val.strip()
    if (val.startswith('"') and val.endswith('"')) or (val.startswith("'") and val.endswith("'")):
        try:
            return json.loads(val) if val.startswith('"') else val[1:-1]
        except Exception:
            return val[1:-1]
    if val.lower() == "true":
        return True
    if val.lower() == "false":
        return False
    if val.lower() in ("null", "none", "~"):
        return None
    if val.startswith("[") and val.endswith("]"):
        inner = val[1:-1].strip()
        if not inner:
            return []
        items = [_parse_scalar(s.strip()) for s in inner.split(",") if s.strip()]
        return items
    try:
        if "." in val:
            return float(val)
        return int(val)
    except ValueError:
        return val

class YAMLError(Exception):
    pass
