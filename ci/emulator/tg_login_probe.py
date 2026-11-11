#!/usr/bin/env python3
"""
Diagnoses logins to Telegram's *test* servers: tries each combination of handshake flavour, data
center and API id once with a fresh 99966XYYYY number and prints what the server answered.

Never prints the API id or hash. Test-server accounts are public and wiped periodically.
"""
import asyncio
import os
import random

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


async def attempt(label, api_id, api_hash, dc, dc_marker):
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


async def main():
    combos = []
    secret_id, secret_hash = os.environ.get("TG_APP_ID"), os.environ.get("TG_APP_HASH")
    apis = []
    if secret_id and secret_hash:
        apis.append(("secret api id", int(secret_id), secret_hash))
    apis.append(("public api id 4", 4, "014b35b6184100b085b0d0572f9b5103"))
    for api_label, api_id, api_hash in apis:
        combos.append((f"{api_label}, DC2, legacy p_q_inner_data", api_id, api_hash, 2, None))
        combos.append((f"{api_label}, DC2, p_q_inner_data_dc dc=10002", api_id, api_hash, 2, 10002))
    if apis:
        api_label, api_id, api_hash = apis[0]
        combos.append((f"{api_label}, DC1, p_q_inner_data_dc dc=10001", api_id, api_hash, 1, 10001))
        combos.append((f"{api_label}, DC3, p_q_inner_data_dc dc=10003", api_id, api_hash, 3, 10003))
    for combo in combos:
        label, lines = await attempt(*combo)
        print(f"== {label}")
        for line in lines:
            print("   " + line)


if __name__ == "__main__":
    asyncio.run(main())
