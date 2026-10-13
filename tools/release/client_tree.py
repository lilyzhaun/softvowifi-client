"""Shared public-source inventory and digest; no private source or credential inputs."""

import hashlib
import os
from pathlib import Path
import subprocess

ROOT_FILES = frozenset({"README.md", "README.zh-CN.md", "LICENSE", "NOTICE", ".gitignore", "KNOWN_ISSUES.md", "RELEASE.json"})
BOOTSTRAP_PATHS = frozenset({
    ".github/workflows/release.yml", ".github/workflows/ci.yml",
    *(f"tools/release/{name}" for name in (
        "github.py", "version.py", "signing.py", "verify.py", "build-apk.sh",
        "linux-tools.sh", "client_tree.py",
    )),
})
MAX_BYTES = 32 * 1024 * 1024


def managed_path(path):
    parts = path.split("/")
    if any(part in {"", ".", "..", ".git", ".secrets", "build"} for part in parts):
        return False
    return path in ROOT_FILES or path.startswith(("app/manager/", "app/observer/", "export/country-source/")) or path in {
        "tools/export/country_catalog.py", "tools/export/build_country_catalog.py",
    }


def candidate_digest(files):
    digest = hashlib.sha256(b"softvowifi-client-candidate-tree-v1\0")
    for path, (mode, raw) in sorted(files.items()):
        digest.update(mode.encode("ascii") + b"\0" + path.encode("ascii") + b"\0")
        digest.update(len(raw).to_bytes(8, "big"))
        digest.update(raw)
    return digest.hexdigest()


def blob_id(raw):
    return hashlib.sha1(f"blob {len(raw)}\0".encode() + raw).hexdigest()


def git(arguments, data=None):
    return subprocess.run(
        ["git", "--no-optional-locks", "-c", "core.fsmonitor=false", *arguments],
        input=data, capture_output=True, check=True, timeout=30,
        env={**os.environ, "GIT_MASTER": "1"},
    ).stdout


def committed_files():
    """Read the frozen Git tree and compare every managed file to its working bytes."""
    entries = []
    for entry in git(["ls-tree", "-r", "-z", "HEAD"]).split(b"\0"):
        if not entry:
            continue
        fields, raw_path = entry.split(b"\t", 1)
        mode, kind, oid = fields.decode("ascii").split()
        path = raw_path.decode("ascii")
        if kind != "blob" or mode not in {"100644", "100755"}:
            raise ValueError("non-regular client source member")
        if path in BOOTSTRAP_PATHS:
            if mode != "100644":
                raise ValueError("invalid bootstrap mode")
            continue
        if not managed_path(path):
            raise ValueError("unmanaged client source member")
        entries.append((path, mode, oid))
    query = "".join(oid + "\n" for _, _, oid in entries).encode()
    sizes = git(["cat-file", "--batch-check"], query).splitlines()
    if len(sizes) != len(entries) or sum(int(line.split()[2]) for line in sizes) > MAX_BYTES:
        raise ValueError("client source inventory exceeds limit")
    raw = git(["cat-file", "--batch"], query)
    result = {}
    offset = 0
    for path, mode, oid in entries:
        end = raw.index(b"\n", offset)
        header = raw[offset:end].decode().split()
        length = int(header[2])
        content = raw[end + 1:end + 1 + length]
        if header[:2] != [oid, "blob"] or len(content) != length:
            raise ValueError("client Git object differs from inventory")
        offset = end + 2 + length
        target = Path(path)
        working_mode = "100755" if target.stat().st_mode & 0o100 else "100644"
        if target.is_symlink() or working_mode != mode or target.read_bytes() != content:
            raise ValueError("client working source differs from committed tree")
        if path != "RELEASE.json":
            result[path] = (mode, content)
    return result
