#!/usr/bin/env python3
"""Build compact, per-letter dictionary assets from an Open English WordNet JSON release."""

from __future__ import annotations

import argparse
import gzip
import json
import re
import shutil
import zipfile
from collections import defaultdict
from pathlib import Path


PARTS_OF_SPEECH = {
    "n": "noun",
    "v": "verb",
    "a": "adjective",
    "s": "adjective",
    "r": "adverb",
}


def clean(value) -> str:
    if isinstance(value, dict):
        value = value.get("text", "")
    return re.sub(r"\s+", " ", str(value)).strip()


def asset_group(word: str) -> str:
    first = word[:1].lower()
    return first if "a" <= first <= "z" else "other"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("release_zip", type=Path)
    parser.add_argument("output_directory", type=Path)
    args = parser.parse_args()

    synsets: dict[str, dict] = {}
    entry_documents: list[tuple[str, dict]] = []
    with zipfile.ZipFile(args.release_zip) as archive:
        for name in archive.namelist():
            if not name.endswith(".json") or name == "frames.json":
                continue
            with archive.open(name) as source:
                document = json.load(source)
            if name.startswith("entries-"):
                entry_documents.append((name, document))
            else:
                synsets.update(document)

    records: dict[str, list[tuple[str, dict]]] = defaultdict(list)
    seen: set[tuple[str, str, str]] = set()
    for _, entries in sorted(entry_documents):
        for displayed_word, forms in entries.items():
            word = clean(displayed_word.replace("_", " ")).lower()
            if not word or "\t" in word or "\n" in word:
                continue
            for part_code, form in forms.items():
                part = PARTS_OF_SPEECH.get(part_code)
                if part is None:
                    continue
                pronunciation = ""
                values = form.get("pronunciation", [])
                if values:
                    pronunciation = clean(values[0].get("value", ""))
                for sense in form.get("sense", []):
                    synset = synsets.get(sense.get("synset"), {})
                    definitions = synset.get("definition", [])
                    if not definitions:
                        continue
                    definition = clean(definitions[0])
                    identity = (word, part, definition)
                    if not definition or identity in seen:
                        continue
                    seen.add(identity)
                    examples = [clean(value) for value in synset.get("example", []) if clean(value)][:2]
                    synonyms = []
                    for member in synset.get("members", []):
                        synonym = clean(member.replace("_", " "))
                        if synonym.lower() != word and synonym not in synonyms:
                            synonyms.append(synonym)
                        if len(synonyms) == 6:
                            break
                    payload = {"p": part, "d": definition}
                    if examples:
                        payload["e"] = examples
                    if synonyms:
                        payload["s"] = synonyms
                    if pronunciation:
                        payload["r"] = pronunciation
                    records[asset_group(word)].append((word, payload))

    destination = args.output_directory
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    for group, values in records.items():
        values.sort(key=lambda item: item[0])
        with gzip.open(destination / f"{group}.jsonl.gz", "wt", encoding="utf-8", compresslevel=9) as output:
            for word, payload in values:
                output.write(word)
                output.write("\t")
                output.write(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
                output.write("\n")

    metadata = {
        "title": "Open English WordNet 2025",
        "source": "https://en-word.net/",
        "license": "CC BY 4.0",
        "licenseUrl": "https://creativecommons.org/licenses/by/4.0/",
        "entries": len({identity[0] for identity in seen}),
        "senses": len(seen),
    }
    (destination / "metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (destination / "NOTICE.txt").write_text(
        "Open English WordNet 2025\n"
        "Copyright Open English WordNet contributors.\n"
        "Source: https://en-word.net/\n"
        "Licensed under Creative Commons Attribution 4.0 International:\n"
        "https://creativecommons.org/licenses/by/4.0/\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
