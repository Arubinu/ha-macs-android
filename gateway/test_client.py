#!/usr/bin/env python3
"""Simule une tablette : envoie un fichier WAV (16 kHz mono) à la passerelle et affiche les événements reçus.
L'audio de réponse éventuel est enregistré dans reponse.wav.

Usage : python test_client.py question.wav [ws://localhost:8765/ws]
"""
import asyncio
import base64
import json
import sys
import time
import uuid
import wave

import aiohttp


async def main(path: str, url: str):
    with wave.open(path, "rb") as w:
        rate = w.getframerate()
        pcm = w.readframes(w.getnframes())
    audio, audio_rate, first_audio = bytearray(), 24000, None
    async with aiohttp.ClientSession() as s, s.ws_connect(url) as ws:
        turn = str(uuid.uuid4())
        await ws.send_json({"type": "start", "id": turn, "sample_rate": rate, "format": "pcm_s16le"})
        frame = rate // 50 * 2
        for i in range(0, len(pcm), frame):
            await ws.send_bytes(pcm[i:i + frame])
        t0 = time.perf_counter()
        await ws.send_json({"type": "end"})
        async for msg in ws:
            m = json.loads(msg.data)
            ms = (time.perf_counter() - t0) * 1000
            if m["type"] == "audio":
                audio += base64.b64decode(m["data"])
                audio_rate = m.get("sample_rate", audio_rate)
                if first_audio is None:
                    first_audio = ms
                    print(f"+{ms:6.0f} ms  premier son")
                continue
            print(f"+{ms:6.0f} ms  {m['type']:<12} {m.get('text', '') or json.dumps({k: v for k, v in m.items() if k not in ('type', 'id')}, ensure_ascii=False)}")
            if m["type"] == "done":
                break
    if audio:
        with wave.open("reponse.wav", "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(audio_rate)
            w.writeframes(bytes(audio))
        print(f"Audio : {len(audio) / 2 / audio_rate:.1f} s → reponse.wav")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    asyncio.run(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "ws://localhost:8765/ws"))
