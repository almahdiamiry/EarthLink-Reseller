#!/bin/bash
# ==============================================================================
# SAMM Production VPS Setup: HTTPS IP + Agent Auth + SSH KeepAlive
# Target: Ubuntu 24.04 (62.129.141.36 / isp.alamiry.com)
# ==============================================================================

set -e

echo "======================================================"
echo " [1/7] Fixing SSH Regular Disconnection (KeepAlive)   "
echo "======================================================"
sudo mkdir -p /etc/ssh/sshd_config.d
sudo tee /etc/ssh/sshd_config.d/99-keepalive.conf > /dev/null << 'EOF'
ClientAliveInterval 30
ClientAliveCountMax 5
TCPKeepAlive yes
EOF
sudo systemctl restart ssh || sudo systemctl restart sshd
echo "SSH keepalive configured. Disconnections during idle are now prevented."

echo ""
echo "======================================================"
echo " [2/7] Database Pre-Migration Backup                  "
echo "======================================================"
BACKUP_FILE="/home/almahdi/backup_pre_agent_auth_$(date +%Y%m%d_%H%M%S).dump"
sudo -u postgres pg_dump -Fc samm > "$BACKUP_FILE"
echo "Safety backup created at: $BACKUP_FILE"

echo ""
echo "======================================================"
echo " [3/7] Applying Database Migrations (0218 & 0219)     "
echo "======================================================"
sudo -u postgres psql -d samm << 'EOF'
-- Migration 0218: Agent API Tokens
ALTER TABLE samm.api_token
    ADD COLUMN IF NOT EXISTS admin_id INTEGER REFERENCES samm.admin_user(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_api_token_admin_id ON samm.api_token(admin_id);

CREATE OR REPLACE FUNCTION samm.trg_fn_revoke_agent_tokens_on_pw_change()
RETURNS trigger AS $$
BEGIN
    IF OLD.password_hash IS DISTINCT FROM NEW.password_hash THEN
        UPDATE samm.api_token SET enabled = false WHERE admin_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_admin_user_pw_change ON samm.admin_user;
CREATE TRIGGER trg_admin_user_pw_change
    AFTER UPDATE OF password_hash ON samm.admin_user
    FOR EACH ROW
    EXECUTE FUNCTION samm.trg_fn_revoke_agent_tokens_on_pw_change();

INSERT INTO samm.schema_migration (version, name, applied_at)
VALUES (218, '0218_agent_api_tokens', now())
ON CONFLICT (name) DO NOTHING;

-- Migration 0219: Auth Throttle Ledger
CREATE TABLE IF NOT EXISTS samm.auth_throttle_event (
    id BIGSERIAL PRIMARY KEY,
    key_type VARCHAR(16) NOT NULL,
    key_hash VARCHAR(64) NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_auth_throttle_lookup
    ON samm.auth_throttle_event (key_type, key_hash, attempted_at DESC);

INSERT INTO samm.schema_migration (version, name, applied_at)
VALUES (219, '0219_auth_throttle_ledger', now())
ON CONFLICT (name) DO NOTHING;
EOF
echo "Database migrations 0218 and 0219 applied successfully."

echo ""
echo "======================================================"
echo " [4/7] Generating SSL Certificate for IP & Domain     "
echo "======================================================"
sudo mkdir -p /etc/ssl/certs /etc/ssl/private

cat << 'EOF' > /tmp/openssl_san.cnf
[req]
default_bits = 2048
prompt = no
default_md = sha256
distinguished_name = dn
x509_extensions = v3_req

[dn]
C = IQ
ST = Basra
L = Basra
O = Alamiry ISP
OU = NOC
CN = 62.129.141.36

[v3_req]
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @alt_names

[alt_names]
IP.1 = 62.129.141.36
IP.2 = 127.0.0.1
DNS.1 = isp.alamiry.com
DNS.2 = localhost
EOF

sudo openssl req -x509 -nodes -days 3650 -newkey rsa:2048 \
  -config /tmp/openssl_san.cnf \
  -keyout /etc/ssl/private/samm-ip.key \
  -out /etc/ssl/certs/samm-ip.crt

sudo chmod 600 /etc/ssl/private/samm-ip.key
sudo chmod 644 /etc/ssl/certs/samm-ip.crt
rm -f /tmp/openssl_san.cnf
echo "SSL Certificate created for IP: 62.129.141.36 and DNS: isp.alamiry.com"

echo ""
echo "======================================================"
echo " [5/7] Deploying SAMM Agent Extension Files           "
echo "======================================================"

cat << 'EOF' | sudo tee /opt/samm/app/api_agent_extension.py > /dev/null
"""
app/api_agent_extension.py
SAMM 5.2.0 Extension: Reseller Agent Authentication, Shared Throttle & Multi-Agent Isolation
"""
import functools
import hashlib
import secrets
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import text
from sqlalchemy.orm import Session

import app.admin.auth as admin_auth
import app.api.auth as api_auth
import app.services.audit_service as audit_svc
from app.database import get_db

agent_router = APIRouter(tags=["Agent Auth"])

THROTTLE_WINDOW_SECONDS = 300  # 5 minutes rolling window
THROTTLE_MAX_FAILURES = 5      # 5 failures threshold

def _get_trusted_client_ip(request: Request) -> str:
    peer_ip = request.client.host if request.client else "127.0.0.1"
    if peer_ip in ("127.0.0.1", "::1"):
        real_ip = request.headers.get("x-real-ip")
        if real_ip:
            return real_ip.strip()
    return peer_ip

def _hash_username(username: str) -> str:
    return hashlib.sha256(username.strip().lower().encode("utf-8")).hexdigest()

def _check_auth_throttle(db: Session, client_ip: str, username: str) -> Optional[int]:
    user_hash = _hash_username(username)
    keys = [("ip", client_ip), ("user", user_hash)]
    
    for k_type, k_val in keys:
        lock_key = f"{k_type}:{k_val}"
        db.execute(text("SELECT pg_advisory_xact_lock(hashtext(:lock_key))"), {"lock_key": lock_key})
        
        row = db.execute(text("""
            SELECT count(*), min(attempted_at)
            FROM samm.auth_throttle_event
            WHERE key_type = :k_type
              AND key_hash = :k_val
              AND attempted_at > now() - INTERVAL '300 seconds'
        """), {
            "k_type": k_type,
            "k_val": k_val
        }).fetchone()
        
        fail_count = row[0] if row else 0
        if fail_count >= THROTTLE_MAX_FAILURES:
            oldest = row[1]
            if oldest:
                now_dt = db.execute(text("SELECT now()")).scalar()
                elapsed = (now_dt - oldest).total_seconds()
                retry_after = max(1, int(THROTTLE_WINDOW_SECONDS - elapsed))
            else:
                retry_after = THROTTLE_WINDOW_SECONDS
            return retry_after
            
    return None

def _record_auth_failure(db: Session, client_ip: str, username: str):
    user_hash = _hash_username(username)
    keys = [("ip", client_ip), ("user", user_hash)]
    
    for k_type, k_val in keys:
        lock_key = f"{k_type}:{k_val}"
        db.execute(text("SELECT pg_advisory_xact_lock(hashtext(:lock_key))"), {"lock_key": lock_key})
        db.execute(text("""
            INSERT INTO samm.auth_throttle_event (key_type, key_hash, attempted_at)
            VALUES (:k_type, :k_val, now())
        """), {
            "k_type": k_type,
            "k_val": k_val
        })
        
    db.execute(text("""
        DELETE FROM samm.auth_throttle_event
        WHERE attempted_at < now() - INTERVAL '600 seconds'
    """))
    db.commit()

def _record_auth_success(db: Session, username: str):
    user_hash = _hash_username(username)
    lock_key = f"user:{user_hash}"
    db.execute(text("SELECT pg_advisory_xact_lock(hashtext(:lock_key))"), {"lock_key": lock_key})
    db.execute(text("""
        DELETE FROM samm.auth_throttle_event
        WHERE key_type = 'user' AND key_hash = :user_hash
    """), {"user_hash": user_hash})
    db.commit()

class AgentLoginRequest(BaseModel):
    username: str = Field(..., min_length=1, max_length=100)
    password: str = Field(..., min_length=1, max_length=200)

class AgentInfo(BaseModel):
    id: int
    username: str
    role: str
    reseller_id: Optional[int] = None

class AgentLoginResponse(BaseModel):
    token: str
    token_type: str = "bearer"
    expires_at: Optional[str] = None
    agent: AgentInfo

@agent_router.post("/auth/agent-login", response_model=AgentLoginResponse)
def agent_login(
    req: AgentLoginRequest,
    request: Request,
    db: Session = Depends(get_db)
):
    client_ip = _get_trusted_client_ip(request)
    
    # 1. Check shared database-backed rate limit
    retry_after = _check_auth_throttle(db, client_ip, req.username)
    if retry_after is not None:
        raise HTTPException(
            status_code=429,
            detail="Too many failed login attempts. Please try again later.",
            headers={"Retry-After": str(retry_after)}
        )

    # 2. Authenticate admin user
    admin = admin_auth.authenticate_admin(db, req.username, req.password)
    if not admin:
        _record_auth_failure(db, client_ip, req.username)
        try:
            audit_svc.log_admin_action(
                db,
                admin=None,
                action="auth.login_failed",
                entity_type="admin",
                target_name=req.username,
                request=request,
                commit=True
            )
        except Exception:
            pass
        raise HTTPException(status_code=401, detail="Invalid username or password.")

    if admin.get("role") != "agent":
        _record_auth_failure(db, client_ip, req.username)
        raise HTTPException(status_code=401, detail="Invalid username or password.")

    admin_row = db.execute(
        text("SELECT reseller_id, is_active FROM samm.admin_user WHERE id = :id"),
        {"id": admin["id"]}
    ).fetchone()
    if not admin_row or not admin_row.is_active:
        _record_auth_failure(db, client_ip, req.username)
        raise HTTPException(status_code=401, detail="Invalid username or password.")

    # 3. Successful authentication: clear user-level failures
    _record_auth_success(db, req.username)

    raw_token = "samm_" + secrets.token_hex(20)
    token_hash = hashlib.sha256(raw_token.encode("utf-8")).hexdigest()
    token_prefix = raw_token[:12]
    scopes = ["customers:read", "customers:write", "plans:read"]
    rate_limit = 120

    db.execute(text("""
        INSERT INTO samm.api_token (
            name, token_hash, token_prefix, scopes, rate_limit_per_min, enabled, created_by, admin_id, created_at
        ) VALUES (
            :name, :token_hash, :token_prefix, :scopes, :rate_limit, true, :created_by, :admin_id, now()
        )
    """), {
        "name": f"agent:{admin['username']}",
        "token_hash": token_hash,
        "token_prefix": token_prefix,
        "scopes": scopes,
        "rate_limit": rate_limit,
        "created_by": admin["username"],
        "admin_id": admin["id"]
    })
    db.commit()

    try:
        audit_svc.log_admin_action(
            db,
            admin={"id": admin["id"], "username": admin["username"], "role": admin["role"]},
            action="auth.login",
            entity_type="admin",
            entity_id=admin["id"],
            target_name=admin["username"],
            request=request,
            commit=True
        )
    except Exception:
        pass

    return AgentLoginResponse(
        token=raw_token,
        agent=AgentInfo(
            id=admin["id"],
            username=admin["username"],
            role=admin["role"],
            reseller_id=admin_row.reseller_id
        )
    )

@agent_router.post("/auth/agent-logout")
def agent_logout(
    token: api_auth.ApiToken = Depends(api_auth.authenticate),
    db: Session = Depends(get_db)
):
    db.execute(
        text("UPDATE samm.api_token SET enabled = false WHERE id = :id"),
        {"id": token.id}
    )
    db.commit()
    return {"status": "ok", "message": "Token revoked successfully."}

_orig_authenticate = api_auth.authenticate

def custom_authenticate(request: Request, db: Session = Depends(get_db)) -> api_auth.ApiToken:
    t = _orig_authenticate(request, db)
    row = db.execute(
        text("SELECT admin_id FROM samm.api_token WHERE id = :id"),
        {"id": t.id}
    ).fetchone()
    token_admin_id = row[0] if row else None
    t.admin_id = token_admin_id

    if token_admin_id is not None:
        admin = db.execute(
            text("SELECT id, username, role, is_active FROM samm.admin_user WHERE id = :aid"),
            {"aid": token_admin_id}
        ).fetchone()
        if not admin or not admin.is_active:
            raise HTTPException(status_code=401, detail="Agent account disabled or revoked.")
        t.agent_username = admin.username
        t.agent_role = admin.role
        request.state.admin_id = admin.id
        request.state.agent = admin
    else:
        t.agent_username = None
        t.agent_role = None

    return t

def setup_agent_extension(app, api_v1):
    found = any(getattr(r, 'path', '') == '/auth/agent-login' for r in api_v1.routes)
    if not found:
        api_v1.include_router(agent_router)

    api_v1.dependency_overrides[api_auth.authenticate] = custom_authenticate
    app.dependency_overrides[api_auth.authenticate] = custom_authenticate

    for r in api_v1.routes:
        path = getattr(r, 'path', '')
        methods = getattr(r, 'methods', set())
        if not hasattr(r, 'dependant') or not r.dependant.call:
            continue
        orig_call = r.dependant.call

        if path == '/customers' and 'GET' in methods:
            @functools.wraps(orig_call)
            def wrap_list(orig_fn=orig_call, *args, **kwargs):
                tok = kwargs.get('token')
                if tok and getattr(tok, 'admin_id', None) is not None:
                    kwargs['assigned_admin_id'] = tok.admin_id
                return orig_fn(*args, **kwargs)
            r.dependant.call = wrap_list

        elif path == '/customers' and 'POST' in methods:
            @functools.wraps(orig_call)
            def wrap_create(orig_fn=orig_call, *args, **kwargs):
                tok = kwargs.get('token')
                db_sess = kwargs.get('db')
                if tok and getattr(tok, 'admin_id', None) is not None:
                    req_obj = kwargs.get('req')
                    if req_obj:
                        req_obj.assigned_admin_id = tok.admin_id
                        if hasattr(req_obj, 'created_by_admin_id'):
                            req_obj.created_by_admin_id = tok.admin_id
                res = orig_fn(*args, **kwargs)
                if tok and getattr(tok, 'admin_id', None) is not None and db_sess and res:
                    created_id = getattr(res, 'id', None)
                    if created_id:
                        db_sess.execute(
                            text("UPDATE samm.user SET created_by_admin_id = :aid WHERE id = :id AND created_by_admin_id IS NULL"),
                            {"aid": tok.admin_id, "id": created_id}
                        )
                        db_sess.commit()
                    try:
                        audit_svc.log_admin_action(
                            db_sess,
                            admin={"id": tok.admin_id, "username": tok.agent_username, "role": tok.agent_role},
                            action="customer.create",
                            entity_type="user",
                            entity_id=str(created_id),
                            target_name=getattr(res, 'username', None),
                            commit=True
                        )
                    except Exception:
                        pass
                return res
            r.dependant.call = wrap_create

        elif '/customers/{customer_id}' in path:
            @functools.wraps(orig_call)
            def wrap_cust_action(orig_fn=orig_call, p=path, meth=methods, *args, **kwargs):
                tok = kwargs.get('token')
                cid = kwargs.get('customer_id')
                db_sess = kwargs.get('db')
                if tok and getattr(tok, 'admin_id', None) is not None and cid and db_sess:
                    owner = db_sess.execute(
                        text("SELECT assigned_admin_id, username FROM samm.user WHERE id = :cid"),
                        {"cid": cid}
                    ).fetchone()
                    if not owner or owner.assigned_admin_id != tok.admin_id:
                        raise HTTPException(status_code=404, detail="Customer not found.")
                    req_obj = kwargs.get('req')
                    if req_obj and hasattr(req_obj, 'assigned_admin_id') and req_obj.assigned_admin_id is not None:
                        req_obj.assigned_admin_id = tok.admin_id
                res = orig_fn(*args, **kwargs)
                if tok and getattr(tok, 'admin_id', None) is not None and any(m in meth for m in ['POST', 'PATCH', 'DELETE']) and db_sess:
                    try:
                        audit_svc.log_admin_action(
                            db_sess,
                            admin={"id": tok.admin_id, "username": tok.agent_username, "role": tok.agent_role},
                            action=f"customer.{p.split('/')[-1]}",
                            entity_type="user",
                            entity_id=str(cid),
                            commit=True
                        )
                    except Exception:
                        pass
                return res
            r.dependant.call = wrap_cust_action
EOF

sudo chown samm:samm /opt/samm/app/api_agent_extension.py
sudo chmod 644 /opt/samm/app/api_agent_extension.py

cat << 'EOF' | sudo tee /opt/samm/app/server.py > /dev/null
# /opt/samm/app/server.py
import sys
sys.path.insert(0, '/opt/samm')

from app.main import app
from app.api.v1_app import api_v1
from app.api_agent_extension import setup_agent_extension

# Initialize extension
setup_agent_extension(app, api_v1)
EOF

sudo chown samm:samm /opt/samm/app/server.py
sudo chmod 644 /opt/samm/app/server.py
echo "Extension files installed in /opt/samm/app/."

echo ""
echo "======================================================"
echo " [6/7] Configuring Nginx (HTTP + HTTPS for IP)        "
echo "======================================================"

# Add HTTPS server block to nginx
sudo tee /etc/nginx/sites-available/samm-ssl > /dev/null << 'EOF'
server {
    listen 443 ssl default_server;
    listen [::]:443 ssl default_server;
    server_name _;

    ssl_certificate     /etc/ssl/certs/samm-ip.crt;
    ssl_certificate_key /etc/ssl/private/samm-ip.key;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    client_max_body_size 20M;

    location / {
        proxy_pass         http://127.0.0.1:8000;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $remote_addr;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_read_timeout 120s;
        proxy_buffering    off;
    }

    location = /expired {
        return 302 /;
    }
}
EOF

sudo ln -sf /etc/nginx/sites-available/samm-ssl /etc/nginx/sites-enabled/samm-ssl
sudo nginx -t

echo ""
echo "======================================================"
echo " [7/7] Configuring Systemd & Restarting Services      "
echo "======================================================"

sudo mkdir -p /etc/systemd/system/samm-api.service.d
sudo tee /etc/systemd/system/samm-api.service.d/50-workers.conf > /dev/null << 'EOF'
[Service]
ExecStart=
ExecStart=/opt/samm/venv/bin/uvicorn app.server:app --host 127.0.0.1 --port 8000 --workers 2
EOF

sudo systemctl daemon-reload
sudo systemctl restart samm-api
sudo systemctl reload nginx

echo ""
echo "======================================================"
echo " VERIFICATION CHECKS                                  "
echo "======================================================"
sleep 2

echo "Checking port 443 listener:"
sudo ss -tulpn | grep -E ':80|:443'

echo ""
echo "Testing POST /api/v1/auth/agent-login endpoint over HTTPS (self-signed):"
curl -k -s -w "\nHTTP Status: %{http_code}\n" -X POST https://127.0.0.1/api/v1/auth/agent-login \
  -H "Content-Type: application/json" \
  -d '{"username":"invalid_test","password":"invalid_test"}'

echo ""
echo "======================================================"
echo " Setup complete! HTTPS on 62.129.141.36 is active.    "
echo "======================================================"
