"""Merchant isolation/authentication integration tests with disposable PostgreSQL."""
import base64
import json
import os
from pathlib import Path
import time
from uuid import uuid4

import jwt
import psycopg
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization
from fastapi.testclient import TestClient

from backend.api import create_app
from backend.auth import Settings

DSN = os.getenv("MERCHANTPAY_TEST_DSN")
pytestmark = pytest.mark.skipif(not DSN, reason="MERCHANTPAY_TEST_DSN not set")
ISSUER = "https://issuer.example.test"
AUDIENCE = "merchantpay-sandbox-api"


@pytest.fixture(scope="module", autouse=True)
def schema():
    if not DSN:
        return
    root = Path(__file__).resolve().parents[1] / "migrations"
    with psycopg.connect(DSN) as conn:
        for filename in (
            "0001_payment_intents.sql",
            "0002_payment_commands.sql",
            "0003_simulated_provider_events.sql",
            "0004_merchant_memberships.sql",
        ):
            conn.execute((root / filename).read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def keys():
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    private_pem = private.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    public_pem = private.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    return private_pem, public_pem


@pytest.fixture
def client(keys):
    settings = Settings(DSN or "not-configured", keys[1], ISSUER, AUDIENCE)
    with TestClient(create_app(settings)) as instance:
        yield instance


def claims(subject, **changes):
    now = int(time.time())
    data = {
        "sub": subject,
        "iss": ISSUER,
        "aud": AUDIENCE,
        "iat": now,
        "nbf": now,
        "exp": now + 600,
        "token_use": "access",
    }
    data.update(changes)
    return data


def access_token(keys, subject, **changes):
    return jwt.encode(claims(subject, **changes), keys[0], algorithm="RS256")


def auth(keys, subject, **changes):
    return {"Authorization": "Bearer " + access_token(keys, subject, **changes)}


def grant(merchant, subject, role="cashier", active=True):
    with psycopg.connect(DSN) as conn:
        conn.execute(
            """INSERT INTO merchant_memberships(merchant_id,subject,role,is_active)
               VALUES (%s,%s,%s,%s)
               ON CONFLICT (merchant_id,subject) DO UPDATE
               SET role=excluded.role,is_active=excluded.is_active""",
            (merchant, subject, role, active),
        )


def endpoint(merchant):
    return f"/v1/merchants/{merchant}/payment-intents"


def post(client, keys, merchant, subject, amount=50000, **changes):
    return client.post(
        endpoint(merchant),
        headers={**auth(keys, subject, **changes), "Idempotency-Key": "key-" + uuid4().hex},
        json={"amount_minor": amount, "currency": "KES"},
    )


def test_no_bearer_token_is_401(client):
    response = client.post(endpoint("m1"), json={"amount_minor": 100})
    assert response.status_code == 401
    assert response.headers["WWW-Authenticate"] == "Bearer"


def test_invalid_signature_is_401(client, keys):
    stranger = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    token = jwt.encode(claims("user"), stranger, algorithm="RS256")
    response = client.get(endpoint("m1") + "/" + str(uuid4()),
                          headers={"Authorization": "Bearer " + token})
    assert response.status_code == 401


@pytest.mark.parametrize("overrides", [
    {"iss": "https://wrong.example.test"},
    {"aud": "other-api"},
    {"exp": 1},
    {"nbf": 4102444800},
    {"token_use": "id"},
    {"sub": ""},
])
def test_invalid_claims_rejected(client, keys, overrides):
    response = client.get(endpoint("m1") + "/" + str(uuid4()),
                          headers=auth(keys, "user", **overrides))
    assert response.status_code == 401


def test_missing_required_claim_is_401(client, keys):
    data = claims("user")
    del data["exp"]
    token = jwt.encode(data, keys[0], algorithm="RS256")
    response = client.get(endpoint("m1") + "/" + str(uuid4()),
                          headers={"Authorization": "Bearer " + token})
    assert response.status_code == 401


def test_unsigned_token_is_401(client):
    token = jwt.encode(claims("user"), key="", algorithm="none")
    response = client.get(endpoint("m1") + "/" + str(uuid4()),
                          headers={"Authorization": "Bearer " + token})
    assert response.status_code == 401


def test_tampered_payload_is_401(client, keys):
    token = access_token(keys, "user")
    parts = token.split(".")
    payload = json.loads(base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4)))
    payload["sub"] = "other-user"
    parts[1] = base64.urlsafe_b64encode(json.dumps(payload).encode()).decode().rstrip("=")
    response = client.get(endpoint("m1") + "/" + str(uuid4()),
                          headers={"Authorization": "Bearer " + ".".join(parts)})
    assert response.status_code == 401


def test_no_membership_denies_write(client, keys):
    response = post(client, keys, str(uuid4()), "user-" + str(uuid4()))
    assert response.status_code == 403


def test_viewer_read_only(client, keys):
    merchant, subject = str(uuid4()), "user-" + str(uuid4())
    grant(merchant, subject, role="viewer")
    assert post(client, keys, merchant, subject).status_code == 403


def test_active_cashier_creates_and_reads_intent(client, keys):
    merchant, subject = str(uuid4()), "user-" + str(uuid4())
    grant(merchant, subject)
    response = post(client, keys, merchant, subject)
    assert response.status_code == 201, response.text
    data = response.json()
    assert data["merchant_id"] == merchant
    assert data["status"] == "created"
    assert data["amount_minor"] == 50000
    assert response.headers["Cache-Control"] == "no-store"
    retrieved = client.get(endpoint(merchant) + "/" + data["id"], headers=auth(keys, subject))
    assert retrieved.status_code == 200
    assert retrieved.json() == data


def test_merchant_idor_is_blocked(client, keys):
    merchant_a, merchant_b = str(uuid4()), str(uuid4())
    subject_a, subject_b = "user-" + str(uuid4()), "user-" + str(uuid4())
    grant(merchant_a, subject_a)
    grant(merchant_b, subject_b)
    created = post(client, keys, merchant_a, subject_a)
    assert created.status_code == 201
    intent_id = created.json()["id"]
    assert client.get(endpoint(merchant_a) + "/" + intent_id,
                      headers=auth(keys, subject_b)).status_code == 403
    assert client.get(endpoint(merchant_b) + "/" + intent_id,
                      headers=auth(keys, subject_b)).status_code == 404
    assert post(client, keys, merchant_b, subject_a).status_code == 403


def test_token_merchant_claim_cannot_grant_access(client, keys):
    merchant, subject = str(uuid4()), "user-" + str(uuid4())
    response = post(client, keys, merchant, subject, merchant_id=merchant, role="owner")
    assert response.status_code == 403


def test_revoked_membership_takes_effect_immediately(client, keys):
    merchant, subject = str(uuid4()), "user-" + str(uuid4())
    grant(merchant, subject)
    created = post(client, keys, merchant, subject)
    assert created.status_code == 201
    grant(merchant, subject, active=False)
    assert client.get(endpoint(merchant) + "/" + created.json()["id"],
                      headers=auth(keys, subject)).status_code == 403


def test_replay_same_key_and_conflicting_payload(client, keys):
    merchant, subject = str(uuid4()), "user-" + str(uuid4())
    grant(merchant, subject)
    headers = {**auth(keys, subject), "Idempotency-Key": "key-" + uuid4().hex}
    first = client.post(endpoint(merchant), headers=headers, json={"amount_minor": 50000})
    second = client.post(endpoint(merchant), headers=headers, json={"amount_minor": 50000})
    assert first.status_code == second.status_code == 201
    assert first.json()["id"] == second.json()["id"]
    conflict = client.post(endpoint(merchant), headers=headers, json={"amount_minor": 60000})
    assert conflict.status_code == 409


@pytest.mark.parametrize("amount", [0, -1, 1.5, True, "50000"])
def test_invalid_amount_rejected(client, keys, amount):
    merchant, subject = str(uuid4()), "user-" + str(uuid4())
    grant(merchant, subject)
    assert post(client, keys, merchant, subject, amount=amount).status_code == 422


def test_unknown_fields_and_invalid_key_rejected(client, keys):
    merchant, subject = str(uuid4()), "user-" + str(uuid4())
    grant(merchant, subject)
    assert client.post(endpoint(merchant),
        headers={**auth(keys, subject), "Idempotency-Key": "key-" + uuid4().hex},
        json={"amount_minor": 100, "status": "paid"}).status_code == 422
    assert client.post(endpoint(merchant),
        headers={**auth(keys, subject), "Idempotency-Key": "short"},
        json={"amount_minor": 100}).status_code == 422


def test_no_public_provider_event_endpoint(client, keys):
    merchant, subject = str(uuid4()), "user-" + str(uuid4())
    grant(merchant, subject)
    response = client.post("/v1/provider-events", headers=auth(keys, subject),
                           json={"outcome": "authorized"})
    assert response.status_code == 404
