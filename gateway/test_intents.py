#!/usr/bin/env python3
"""Vérifie la reconnaissance locale des commandes sur la vraie maison, SANS rien exécuter.

Usage : HA_URL=… HA_TOKEN=… python test_intents.py "allume les spots du salon" "quelle est la température du salon"
"""
import asyncio
import sys

import aiohttp

import voice_gateway as g


async def main(phrases):
    async with aiohttp.ClientSession() as session:
        ha = g.HomeAssistant(session)
        task = asyncio.create_task(ha.run())
        await asyncio.wait_for(ha._ready.wait(), 20)
        for p in phrases:
            res = g.fast_intent(ha, p)
            print(f"« {p} »\n   → {res if res else 'modèle (non reconnu localement)'}")
            if not res:
                words = [w for w in g.norm(p).split() if w not in g.STOPWORDS and len(w) > 2]
                for score, e in ha.ranked(" ".join(words))[:4]:
                    print(f"      {score} {e.entity_id} | {e.name} | {e.area}")
        task.cancel()


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    asyncio.run(main(sys.argv[1:]))
