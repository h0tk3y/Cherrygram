#!/usr/bin/env python3
"""
Diagnoses logins to Telegram's *test* servers: tries each combination of handshake flavour, data
center and API id once with a fresh 99966XYYYY number and prints what the server answered.

Never prints the API id or hash. Test-server accounts are public and wiped periodically.
"""
import asyncio
import json
import os
import random
import tempfile
import time

import tdjson

from telethon import TelegramClient, errors, functions, types
from telethon.network import authenticator
from telethon.sessions import StringSession
from telethon.tl.types import PQInnerData, PQInnerDataDc

# Test DC addresses as in tgnet ConnectionsManager::initDatacenters (testBackend branch).
TEST_DCS = {1: "149.154.175.40", 2: "149.154.167.40", 3: "149.154.175.117"}


def use_handshake(dc_marker):
    """dc_marker None: telethon's legacy p_q_inner_data (no dc). Otherwise p_q_inner_data_dc with
    that dc, which official clients set to 10000 + dc on the test backend (tgnet Handshake.cpp)."""
    if dc_marker is None:
        authenticator.PQInnerData = PQInnerData
    else:
        authenticator.PQInnerData = lambda **kw: PQInnerDataDc(dc=dc_marker, **kw)


async def attempt(label, api_id, api_hash, dc, dc_marker, delay=0):
    use_handshake(dc_marker)
    client = TelegramClient(StringSession(), api_id, api_hash)
    client.session.set_dc(dc, TEST_DCS[dc], 443)
    out = []
    try:
        await asyncio.wait_for(client.connect(), 30)
        cfg = await client(functions.help.GetConfigRequest())
        out.append(f"this_dc={cfg.this_dc} test_mode={cfg.test_mode}")
        phone = "99966%d%04d" % (dc, random.randint(0, 9999))
        sent = await client(functions.auth.SendCodeRequest(phone, api_id, api_hash, types.CodeSettings()))
        if not isinstance(sent, types.auth.SentCode):
            out.append(f"{phone}: sendCode -> {type(sent).__name__}")
            return label, out
        length = getattr(sent.type, "length", None) or 5
        out.append(f"{phone}: sendCode -> {type(sent.type).__name__} length={length} next={type(sent.next_type).__name__ if sent.next_type else None}")
        if delay:
            await asyncio.sleep(delay)
        for n in dict.fromkeys([length, 5, 6]):
            code = str(dc) * n
            try:
                res = await client(functions.auth.SignInRequest(phone, sent.phone_code_hash, code))
            except errors.RPCError as e:
                out.append(f"  signIn {code} -> {type(e).__name__} {e.message}")
                continue
            out.append(f"  signIn {code} -> {type(res).__name__}")
            if isinstance(res, types.auth.AuthorizationSignUpRequired):
                try:
                    res = await client(functions.auth.SignUpRequest(phone, sent.phone_code_hash, "Feed", "Tester"))
                    out.append(f"  signUp -> {type(res).__name__}")
                except errors.RPCError as e:
                    out.append(f"  signUp -> {type(e).__name__} {e.message}")
                    break
            if isinstance(res, types.auth.Authorization):
                me = await client.get_me()
                out.append(f"  LOGGED IN as user {me.id}")
            break
    except Exception as e:  # noqa: BLE001 - diagnostics
        out.append(f"error {type(e).__name__}: {e}")
    finally:
        await client.disconnect()
    return label, out


def tdlib_attempt(label, api_id, api_hash, dc, timeout=60):
    out = []
    tdjson.td_execute(json.dumps({"@type": "setLogVerbosityLevel", "new_verbosity_level": 1}).encode())
    client = tdjson.td_create_client_id()
    send = lambda q: tdjson.td_send(client, json.dumps(q).encode())
    send({"@type": "getOption", "name": "version"})
    phone = "99966%d%04d" % (dc, random.randint(0, 9999))
    db = tempfile.mkdtemp()
    deadline = time.time() + timeout
    code_tries = []
    while time.time() < deadline:
        raw = tdjson.td_receive(1.0)
        if not raw:
            continue
        ev = json.loads(raw)
        if ev.get("@client_id") not in (None, client):
            continue
        t = ev["@type"]
        if t == "error":
            out.append(f"error {ev.get('code')} {ev.get('message')}")
            if code_tries and len(code_tries) < 2 and "CODE" in ev.get("message", ""):
                code = str(dc) * 6
                code_tries.append(code)
                send({"@type": "checkAuthenticationCode", "code": code})
                out.append(f"checkAuthenticationCode {code}")
                continue
            if code_tries:
                break
        elif t == "optionValue" and not out:
            out.append(f"tdlib {ev.get('value', {}).get('value')}")
        elif t == "updateAuthorizationState":
            st = ev["authorization_state"]
            s = st["@type"]
            if s == "authorizationStateWaitTdlibParameters":
                send({"@type": "setTdlibParameters", "use_test_dc": True, "database_directory": db,
                      "use_message_database": False, "use_secret_chats": False, "api_id": api_id,
                      "api_hash": api_hash, "system_language_code": "en", "device_model": "CI",
                      "application_version": "1.0"})
            elif s == "authorizationStateWaitPhoneNumber":
                send({"@type": "setAuthenticationPhoneNumber", "phone_number": phone})
                out.append(f"{phone}: setAuthenticationPhoneNumber")
            elif s == "authorizationStateWaitCode":
                info = st["code_info"]
                ctype = info["type"]
                length = ctype.get("length") or 5
                out.append(f"  code type {ctype['@type']} length={length}")
                code = str(dc) * length
                code_tries.append(code)
                send({"@type": "checkAuthenticationCode", "code": code})
                out.append(f"  checkAuthenticationCode {code}")
            elif s == "authorizationStateWaitRegistration":
                out.append("  registration required -> registerUser")
                send({"@type": "registerUser", "first_name": "Feed", "last_name": "Tester"})
            elif s == "authorizationStateReady":
                out.append("  LOGGED IN")
                break
            else:
                out.append(f"  state {s}")
                if s in ("authorizationStateClosed",):
                    break
    else:
        out.append("timeout")
    send({"@type": "close"})
    return label, out


async def main():
    combos = []
    secret_id, secret_hash = os.environ.get("TG_APP_ID"), os.environ.get("TG_APP_HASH")
    apis = []
    if secret_id and secret_hash:
        apis.append(("secret api id", int(secret_id), secret_hash))
    apis.append(("public api id 4", 4, "014b35b6184100b085b0d0572f9b5103"))
    api_label, api_id, api_hash = apis[0]
    combos.append((f"{api_label}, DC2, p_q_inner_data_dc dc=10002, sign in after 15 s", api_id, api_hash, 2, 10002, 15))
    for api_label, api_id, api_hash in apis:
        label, lines = tdlib_attempt(f"TDLib, {api_label}, test DC2", api_id, api_hash, 2)
        print(f"== {label}")
        for line in lines:
            print("   " + line)
    for combo in combos:
        label, lines = await attempt(*combo)
        print(f"== {label}")
        for line in lines:
            print("   " + line)


if __name__ == "__main__":
    asyncio.run(main())
