"""Strict version detection for the single app/manager/version.properties release entry point."""

import argparse
import json
import os
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

VERSION_RE = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*))?")


@dataclass(frozen=True)
class Version:
    name: str
    code: int

    @property
    def tag(self):
        return f"v{self.name}"

    @property
    def prerelease(self):
        return "-" in self.name

    @property
    def order(self):
        major, minor, patch, pre = VERSION_RE.fullmatch(self.name).groups()
        parts = tuple((0, int(part)) if part.isdigit() else (1, part)
                      for part in (pre or "").split("."))
        return int(major), int(minor), int(patch), (0, parts) if pre else (1, ())


def parse_version(text):
    values = {}
    for line in text.splitlines():
        key, separator, value = line.partition("=")
        if separator and key in {"version", "versionName", "versionCode"}:
            key = "version" if key == "versionName" else key
            if key in values:
                raise ValueError("duplicate release version")
            values[key] = value
    name, code = values.get("version", ""), values.get("versionCode", "")
    if (VERSION_RE.fullmatch(name) is None or re.fullmatch(r"[1-9][0-9]{0,9}", code) is None
            or int(code) > 2_147_483_647):
        raise ValueError("invalid release version")
    return Version(name, int(code))


def should_release(previous, current, manual=False):
    if previous == current:
        return manual
    if current.code <= previous.code or current.order <= previous.order:
        raise ValueError("version and versionCode must both increase")
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", default="app/manager/version.properties")
    parser.add_argument("--before")
    parser.add_argument("--manual", action="store_true")
    args = parser.parse_args()
    current = parse_version(Path(args.file).read_text())
    previous = current
    if args.before and args.before != "0" * 40:
        if re.fullmatch(r"[0-9a-f]{40}", args.before) is None:
            raise ValueError("invalid previous commit")
        previous_path = args.file
        present = subprocess.run(["git", "cat-file", "-e", f"{args.before}:{previous_path}"],
                                 capture_output=True, timeout=30, env={**os.environ, "GIT_MASTER": "1"})
        # Only pre-migration commits use the historical version location.
        if present.returncode and previous_path == "app/manager/version.properties":
            previous_path = "module/module.prop"
        raw = subprocess.run(
            ["git", "show", f"{args.before}:{previous_path}"], check=True,
            capture_output=True, text=True, timeout=30, env={**os.environ, "GIT_MASTER": "1"},
        ).stdout
        previous = parse_version(raw)
    values = {"release": str(should_release(previous, current, args.manual)).lower(),
              "version": current.name, "version_code": str(current.code), "tag": current.tag}
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            for key, value in values.items():
                output.write(f"{key}={value}\n")
    print(json.dumps(values))


if __name__ == "__main__":
    main()
