#!/usr/bin/env python3
"""Validate flat source-to-English dictionaries without third-party dependencies.

Usage: python3 scripts/check-localization.py [dictionary.json ...]
The default dictionary is localization/backend-en.json, resolved from this script.
Additional platform UI dictionaries may be passed to the same validation command.
"""

from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys


PLACEHOLDER = re.compile(r"\{(\d+)\}")
CYRILLIC = re.compile(r"[\u0400-\u052f\u2de0-\u2dff\ua640-\ua69f]")


def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate source key: {key!r}")
        result[key] = value
    return result


def validate(path: Path) -> tuple[int, list[str]]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    except (OSError, UnicodeError, ValueError) as error:
        return 0, [str(error)]
    if not isinstance(data, dict) or not data:
        return 0, ["The dictionary must be a nonempty JSON object."]
    errors: list[str] = []
    for source, translation in data.items():
        if not isinstance(translation, str):
            errors.append(f"{source!r}: translation must be a string")
            continue
        if not source.strip() or not translation.strip():
            errors.append(f"{source!r}: source and translation must not be empty")
        if CYRILLIC.search(translation):
            errors.append(f"{source!r}: English translation still contains Cyrillic text")
        expected = Counter(PLACEHOLDER.findall(source))
        actual = Counter(PLACEHOLDER.findall(translation))
        if expected != actual:
            errors.append(
                f"{source!r}: placeholder mismatch; "
                f"source={dict(expected)}, translation={dict(actual)}"
            )
        if expected and set(map(int, expected)) != set(range(len(expected))):
            errors.append(f"{source!r}: placeholder indices must start at 0 without gaps")
    return len(data), errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dictionaries", nargs="*", type=Path)
    args = parser.parse_args()
    default = Path(__file__).resolve().parents[1] / "localization" / "backend-en.json"
    failed = False
    for path in args.dictionaries or [default]:
        count, errors = validate(path)
        if errors:
            failed = True
            for error in errors:
                print(f"{path}: {error}", file=sys.stderr)
        else:
            print(f"Validated {count} English translations: {path}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
