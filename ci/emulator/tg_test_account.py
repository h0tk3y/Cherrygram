#!/usr/bin/env python3
"""
Prepares a throwaway account on Telegram's *test* servers for the emulator UI test.

  setup  --out DIR   sign up a random test number (99966 2 XXXX, login code 22222), create three
                     broadcast channels and one group, post interleaved messages (text, a photo and
                     an album), and put all of them into a chat folder "Feeds". Writes
                     DIR/phone.txt and DIR/session.txt.
  post   --out DIR   post one more message to a channel (live-update check).

Test-server accounts are public and wiped periodically; nothing here touches production Telegram.
Requires TG_APP_ID / TG_APP_HASH in the environment (from https://my.telegram.org).
"""
import argparse
import asyncio
import os
import random
import struct
import sys
import zlib

from telethon import TelegramClient, functions, types
from telethon.errors import (PhoneNumberBannedError, PhoneNumberInvalidError,
                             SessionPasswordNeededError, FloodWaitError)
from telethon.sessions import StringSession

TEST_DC_ID = 2
TEST_DC_IP = "149.154.167.40"
TEST_DC_PORT = 443
FOLDER_ID = 42
CHANNELS = ["Feed Alpha", "Feed Beta", "Feed Gamma"]
GROUP = "Feed Group (not a channel)"


def png(width, height, color_a, color_b):
    """A small gradient PNG without third-party imaging libraries."""
    rows = []
    for y in range(height):
        t = y / max(1, height - 1)
        r, g, b = (int(color_a[i] + (color_b[i] - color_a[i]) * t) for i in range(3))
        rows.append(b"\x00" + bytes([r, g, b]) * width)
    raw = b"".join(rows)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def make_client(session=""):
    api_id = int(os.environ["TG_APP_ID"])
    api_hash = os.environ["TG_APP_HASH"]
    client = TelegramClient(StringSession(session), api_id, api_hash)
    client.session.set_dc(TEST_DC_ID, TEST_DC_IP, TEST_DC_PORT)
    return client


async def sign_in(out):
    for attempt in range(15):
        phone = "99966%d%04d" % (TEST_DC_ID, random.randint(0, 9999))
        client = make_client()
        await client.connect()
        try:
            await client.start(phone=phone, code_callback=lambda: str(TEST_DC_ID) * 5,
                               first_name="Feed", last_name="Tester")
            me = await client.get_me()
            print(f"signed in as {me.id} with {phone}")
            with open(os.path.join(out, "phone.txt"), "w") as f:
                f.write(phone)
            with open(os.path.join(out, "session.txt"), "w") as f:
                f.write(client.session.save())
            return client
        except (SessionPasswordNeededError, PhoneNumberBannedError, PhoneNumberInvalidError) as e:
            print(f"{phone}: {type(e).__name__}, trying another number")
            await client.disconnect()
        except FloodWaitError as e:
            print(f"flood wait {e.seconds}s")
            await client.disconnect()
            await asyncio.sleep(min(e.seconds, 60))
    sys.exit("could not sign in to a test account")


async def create_chat(client, title, broadcast):
    result = await client(functions.channels.CreateChannelRequest(
        title=title, about="Cherrygram folder feed CI", broadcast=broadcast, megagroup=not broadcast))
    return result.chats[0]


async def setup(out):
    os.makedirs(out, exist_ok=True)
    client = await sign_in(out)
    async with client:
        channels = [await create_chat(client, title, True) for title in CHANNELS]
        group = await create_chat(client, GROUP, False)

        images = []
        for i, (a, b) in enumerate([((255, 94, 98), (255, 195, 113)), ((54, 209, 220), (91, 134, 229)), ((168, 224, 99), (86, 171, 47))]):
            path = os.path.join(out, f"img{i}.png")
            with open(path, "wb") as f:
                f.write(png(320, 200, a, b))
            images.append(path)

        rng = random.Random(7)
        order = [0, 0, 1, 2, 1, 0, 2, 2, 1, 0, 1, 2, 0, 1, 2, 0, 1, 2]
        counters = [0, 0, 0]
        for step, idx in enumerate(order):
            counters[idx] += 1
            text = f"{CHANNELS[idx]} post #{counters[idx]} (step {step + 1}). " + rng.choice([
                "Short update.",
                "A slightly longer post to check that text wraps correctly inside the merged feed bubble.",
                "Release notes: fixed bugs, improved performance, added more bugs to fix later.",
            ])
            if step == 6:
                await client.send_file(channels[idx], images[0], caption=text)
            elif step == 11:
                await client.send_file(channels[idx], images[1:], caption=text)
            else:
                await client.send_message(channels[idx], text)
            if step == 8:
                await client.send_message(group, "Group message that must NOT appear in the feed")
            await asyncio.sleep(1.2)

        peers = [await client.get_input_entity(c) for c in channels] + [await client.get_input_entity(group)]
        title = "Feeds"
        try:
            dialog_filter = types.DialogFilter(id=FOLDER_ID, title=types.TextWithEntities(text=title, entities=[]),
                                               pinned_peers=[], include_peers=peers, exclude_peers=[])
        except TypeError:
            dialog_filter = types.DialogFilter(id=FOLDER_ID, title=title, pinned_peers=[], include_peers=peers, exclude_peers=[])
        await client(functions.messages.UpdateDialogFilterRequest(id=FOLDER_ID, filter=dialog_filter))
        print("folder created")


async def post(out):
    with open(os.path.join(out, "session.txt")) as f:
        client = make_client(f.read().strip())
    async with client:
        async for dialog in client.iter_dialogs():
            if dialog.name == CHANNELS[1]:
                await client.send_message(dialog.entity, "Live update: this post was sent while the feed was open")
                print("posted live update")
                return
    sys.exit("channel not found")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["setup", "post"])
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    asyncio.run(setup(args.out) if args.command == "setup" else post(args.out))


if __name__ == "__main__":
    main()
