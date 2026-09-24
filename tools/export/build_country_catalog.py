"""Deterministic public-country catalog CLI; no network, Git, or UI rewiring."""

# ruff: noqa: EM101

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Final

from country_catalog import (
    CatalogError,
    Command,
    check_catalog,
    generate_catalog,
    scoped_path,
)

HELP: Final = (
    "usage: build_country_catalog.py generate "
    "--repo-root DIR --source FILE --output FILE\n"
    "       build_country_catalog.py check --repo-root DIR --source FILE --output FILE\n"
    "pinned public MCC to ISO2 only; no OEM or operator profiles\n"
)
FLAGS: Final = ("--repo-root", "--source", "--output")


def parse_request(arguments: list[str]) -> tuple[Command, Path, Path, Path]:
    """Parse generate/check flags without executing paths yet."""
    if not arguments:
        raise CatalogError("usage")
    command: Command
    match arguments[0]:
        case "generate" | "check" as command:
            pass
        case _:
            raise CatalogError("usage")
    values: dict[str, str] = {}
    rest = arguments[1:]
    index = 0
    while index < len(rest):
        key = rest[index]
        if key not in FLAGS or index + 1 >= len(rest) or key in values:
            raise CatalogError("usage")
        values[key] = rest[index + 1]
        index += 2
    if len(values) != len(FLAGS):
        raise CatalogError("usage")
    root = Path(values["--repo-root"]).resolve()
    source = scoped_path(root, values["--source"])
    output = scoped_path(root, values["--output"])
    return command, source, output, root


def main(arguments: list[str]) -> int:
    """Emit fixed JSON only; never echo paths or source text."""
    if arguments == ["--help"]:
        _ = sys.stdout.write(HELP)
        return 0
    try:
        command, source, output, _root = parse_request(arguments)
        match command:
            case "generate":
                counts = generate_catalog(source, output)
            case "check":
                counts = check_catalog(source, output)
    except CatalogError as error:
        if error.code == "usage":
            _ = sys.stdout.write('{"ok":false,"error":"usage"}\n')
            return 2
        _ = sys.stdout.write(json.dumps({"ok": False, "error": error.code}) + "\n")
        return 1
    except OSError:
        _ = sys.stdout.write('{"ok":false,"error":"local_io_failure"}\n')
        return 2
    entries, iso_codes = counts
    _ = sys.stdout.write(
        json.dumps({"ok": True, "entries": entries, "iso": iso_codes}) + "\n"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
