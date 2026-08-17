#!/usr/bin/env python3
"""
Verified Command Runner
Canonical fail-closed command wrapper for long-running verification tasks.
Ensures bounded synchronous execution, strict timeout handling, process-tree termination,
heartbeat emission, cross-platform support (Linux/Windows), and structured machine-readable result metadata.
"""

import sys
import os
import time
import subprocess
import signal
import json
import argparse
from typing import Dict, Any, List, Optional

def kill_process_tree(process: subprocess.Popen) -> None:
    """Safely and aggressively terminates a process and all its child processes."""
    try:
        if os.name == 'nt':
            # Windows: use taskkill to forcibly kill process and child tree
            subprocess.run(
                ['taskkill', '/F', '/T', '/PID', str(process.pid)],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False
            )
        else:
            # Unix/Linux: kill entire process group if process group leader, else terminate process
            try:
                pgid = os.getpgid(process.pid)
                os.killpg(pgid, signal.SIGKILL)
            except Exception:
                process.kill()
    except Exception as e:
        sys.stderr.write(f"[WARN] Exception during process tree termination: {e}\n")
    finally:
        try:
            process.poll()
        except Exception:
            pass

def run_verified_command(
    command: List[str],
    timeout_seconds: int = 300,
    heartbeat_interval: int = 15,
    cwd: Optional[str] = None,
    env: Optional[Dict[str, str]] = None,
    output_metadata_path: Optional[str] = None,
    fail_on_no_source: bool = True
) -> Dict[str, Any]:
    start_time = time.time()
    start_iso = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(start_time))
    
    stdout_lines: List[str] = []
    stderr_lines: List[str] = []
    
    status = "RUNNING"
    exit_code = -1
    
    # Configure preexec_fn for process group on Unix
    preexec_fn = os.setsid if os.name != 'nt' else None
    
    try:
        process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
            cwd=cwd,
            env=env or os.environ.copy(),
            preexec_fn=preexec_fn
        )
    except Exception as e:
        end_time = time.time()
        result = {
            "status": "BLOCKED",
            "exit_code": 127,
            "command": command,
            "start_time": start_iso,
            "end_time": time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(end_time)),
            "duration_seconds": round(end_time - start_time, 2),
            "stdout": "",
            "stderr": f"Failed to spawn command: {str(e)}",
            "timed_out": False,
            "no_source_detected": False
        }
        if output_metadata_path:
            with open(output_metadata_path, 'w', encoding='utf-8') as f:
                json.dump(result, f, indent=2)
        return result

    timed_out = False
    
    try:
        while True:
            now = time.time()
            remaining = timeout_seconds - (now - start_time)
            if remaining <= 0:
                timed_out = True
                status = "TIMEOUT"
                sys.stderr.write(f"\n[VERIFIED_RUNNER] TIMEOUT EXCEEDED ({timeout_seconds}s). Terminating process tree (PID: {process.pid})...\n")
                kill_process_tree(process)
                try:
                    out, err = process.communicate(timeout=1)
                    if out:
                        stdout_lines.append(out)
                    if err:
                        stderr_lines.append(err)
                except Exception:
                    pass
                break

            slice_timeout = min(float(heartbeat_interval), max(0.5, remaining))
            try:
                out, err = process.communicate(timeout=slice_timeout)
                if out:
                    stdout_lines.append(out)
                if err:
                    stderr_lines.append(err)
                exit_code = process.returncode
                break
            except subprocess.TimeoutExpired as te:
                if te.stdout:
                    stdout_lines.append(te.stdout if isinstance(te.stdout, str) else te.stdout.decode('utf-8', errors='replace'))
                if te.stderr:
                    stderr_lines.append(te.stderr if isinstance(te.stderr, str) else te.stderr.decode('utf-8', errors='replace'))
                
                now = time.time()
                elapsed = int(now - start_time)
                if now - start_time >= timeout_seconds:
                    timed_out = True
                    status = "TIMEOUT"
                    sys.stderr.write(f"\n[VERIFIED_RUNNER] TIMEOUT EXCEEDED ({timeout_seconds}s). Terminating process tree (PID: {process.pid})...\n")
                    kill_process_tree(process)
                    break
                else:
                    sys.stdout.write(f"[VERIFIED_RUNNER HEARTBEAT] PID {process.pid} running... elapsed: {elapsed}s / timeout: {timeout_seconds}s\n")
                    sys.stdout.flush()

    except Exception as e:
        sys.stderr.write(f"[VERIFIED_RUNNER ERROR] Execution exception: {e}\n")
        kill_process_tree(process)
        status = "BLOCKED"
        stderr_lines.append(f"Runner exception: {e}")

    end_time = time.time()
    full_stdout = "".join(stdout_lines)
    full_stderr = "".join(stderr_lines)
    
    no_source_detected = False
    # Check specifically for Gradle NO-SOURCE task execution markers in build output (ignoring standard JavaWithJavac in pure Kotlin projects)
    import re
    no_source_matches = re.findall(r'>\s*Task\s+:([^\s]+)\s+NO-SOURCE', full_stdout) + re.findall(r'>\s*Task\s+:([^\s]+)\s+NO-SOURCE', full_stderr)
    for task_name in no_source_matches:
        if not task_name.endswith("JavaWithJavac"):
            no_source_detected = True
            break

    if timed_out:
        status = "TIMEOUT"
        exit_code = 124  # Standard timeout exit code
    elif status == "BLOCKED":
        exit_code = 125
    elif fail_on_no_source and no_source_detected and exit_code == 0:
        # Explicit fail-closed policy for NO-SOURCE test suites
        status = "FAIL"
        exit_code = 2
        full_stderr += "\n[VERIFIED_RUNNER] FAIL: 'NO-SOURCE' detected in test execution output under fail-closed policy."
    elif exit_code == 0:
        status = "PASS"
    else:
        status = "FAIL"

    result = {
        "status": status,
        "exit_code": exit_code,
        "command": command,
        "start_time": start_iso,
        "end_time": time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(end_time)),
        "duration_seconds": round(end_time - start_time, 2),
        "stdout": full_stdout,
        "stderr": full_stderr,
        "timed_out": timed_out,
        "no_source_detected": no_source_detected
    }

    if output_metadata_path:
        os.makedirs(os.path.dirname(os.path.abspath(output_metadata_path)), exist_ok=True)
        with open(output_metadata_path, 'w', encoding='utf-8') as f:
            json.dump(result, f, indent=2)

    return result

def main():
    parser = argparse.ArgumentParser(description="Verified Command Runner")
    parser.add_argument("--timeout", type=int, default=300, help="Command timeout in seconds")
    parser.add_argument("--heartbeat", type=int, default=15, help="Heartbeat interval in seconds")
    parser.add_argument("--cwd", type=str, default=None, help="Working directory")
    parser.add_argument("--metadata-out", type=str, default=None, help="Path to write result metadata JSON")
    parser.add_argument("--allow-no-source", action="store_true", help="Do not fail on NO-SOURCE")
    parser.add_argument("cmd", nargs=argparse.REMAINDER, help="Command to run")

    args = parser.parse_args()

    if not args.cmd:
        sys.stderr.write("Usage: run_verified_command.py [options] -- <command...>\n")
        sys.exit(1)

    cmd = args.cmd
    if cmd[0] == "--":
        cmd = cmd[1:]

    res = run_verified_command(
        command=cmd,
        timeout_seconds=args.timeout,
        heartbeat_interval=args.heartbeat,
        cwd=args.cwd,
        output_metadata_path=args.metadata_out,
        fail_on_no_source=not args.allow_no_source
    )

    # Print output
    if res["stdout"]:
        sys.stdout.write(res["stdout"])
        sys.stdout.flush()
    if res["stderr"]:
        sys.stderr.write(res["stderr"])
        sys.stderr.flush()

    sys.exit(res["exit_code"])

if __name__ == "__main__":
    main()
