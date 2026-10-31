"""Stage and verify APK-owned native/runtime bytes; also supports independent App builds."""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import zipfile

from version import parse_version

ENGINE_MEMBER = "lib/arm64-v8a/libvowifi_engine.so"
RECORD_MEMBER = "assets/engine-source.json"
RUNTIME_FILES = ("control.sh", *("lib/" + name + ".sh" for name in (
    "cellular", "config", "config_create", "config_identity", "config_rebuild", "control_runtime",
    "diagnose", "diagnose_run", "lifecycle", "logs", "logs_keys", "storage",
)))


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def generated(manager):
    return manager / "androidApp/build/generated/engine"


def generated_files(manager):
    result = {}
    root = generated(manager)
    for source, target in (("jniLibs", "lib"), ("assets", "assets")):
        for path in sorted((root / source).rglob("*")):
            if path.is_symlink():
                raise ValueError("payload must use regular files")
            if path.is_file():
                result[target + "/" + path.relative_to(root / source).as_posix()] = path.read_bytes()
    return result


def write_generated(manager, files):
    root = generated(manager)
    # Only this build-owned generated subtree is replaced; Gradle and source caches remain intact.
    if root.exists():
        shutil.rmtree(root)
    for name, raw in files.items():
        relative = name.replace("lib/", "jniLibs/", 1) if name.startswith("lib/") else name
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)


def stage(root, manager, engine, licenses, source_digest):
    if re.fullmatch(r"[0-9a-f]{64}", source_digest) is None:
        raise ValueError("invalid runtime source digest")
    runtime = root / "runtime"
    actual = {path.relative_to(runtime).as_posix() for path in runtime.rglob("*")
              if (path.is_file() or path.is_symlink()) and not path.relative_to(runtime).as_posix().startswith("tests/")
              and path.name != "README.md"}
    if actual != set(RUNTIME_FILES):
        raise ValueError("unreviewed runtime inventory")
    files = {ENGINE_MEMBER: engine.read_bytes()}
    for name in RUNTIME_FILES:
        path = runtime / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("runtime must use regular files")
        files["assets/runtime/" + name] = path.read_bytes()
    version = parse_version((manager / "version.properties").read_text())
    files["assets/runtime/runtime.properties"] = (
        f"id=vowifi_sw\nversion={version.name}\nversionCode={version.code}\n").encode()
    for path in sorted(licenses.rglob("*")):
        if path.is_symlink():
            raise ValueError("licenses must use regular files")
        if path.is_file():
            files["assets/licenses/" + path.relative_to(licenses).as_posix()] = path.read_bytes()
    record = {"schema": 1, "package": "com.vowifi.sw", "source_sha256": source_digest,
              "version": version.name, "version_code": version.code, "engine_sha256": sha(files[ENGINE_MEMBER]),
              "files": {name: sha(raw) for name, raw in sorted(files.items())}}
    files[RECORD_MEMBER] = (json.dumps(record, sort_keys=True, indent=2) + "\n").encode()
    validate(files, source_digest)
    write_generated(manager, files)
    return record


def validate(files, source_digest=None):
    record = json.loads(files[RECORD_MEMBER])
    if (record.get("schema") != 1 or record.get("package") != "com.vowifi.sw"
            or re.fullmatch(r"[0-9a-f]{64}", record.get("source_sha256", "")) is None
            or (source_digest is not None and record["source_sha256"] != source_digest)):
        raise ValueError("APK runtime source differs")
    expected = record.get("files", {})
    if not expected or set(files) != set(expected) | {RECORD_MEMBER}:
        raise ValueError("APK payload inventory differs")
    scripts = {name.removeprefix("assets/runtime/") for name in expected if name.startswith("assets/runtime/")}
    if scripts != set(RUNTIME_FILES) | {"runtime.properties"}:
        raise ValueError("APK runtime inventory differs")
    for name, digest in expected.items():
        path = PurePosixPath(name)
        if (path.is_absolute() or ".." in path.parts or "\\" in name
                or not (name == ENGINE_MEMBER or name.startswith(("assets/runtime/", "assets/licenses/")))):
            raise ValueError("invalid payload path")
        if sha(files[name]) != digest:
            raise ValueError("APK payload bytes differ: " + name)
    engine = files[ENGINE_MEMBER]
    if len(engine) < 64 or engine[:6] != b"\x7fELF\x02\x01" or engine[18:20] != b"\xb7\0":
        raise ValueError("APK engine is not an ARM64 ELF")
    if sha(engine) != record.get("engine_sha256"):
        raise ValueError("APK engine digest differs")
    version = parse_version(files["assets/runtime/runtime.properties"].decode())
    if (version.name, version.code) != (record.get("version"), record.get("version_code")):
        raise ValueError("APK runtime version differs")
    for name in ("APP-GPL-3.0.txt", "APP-NOTICE.txt", "go-toolchain/LICENSE", "go-toolchain/PATENTS"):
        if not files.get("assets/licenses/" + name):
            raise ValueError("missing APK license material")
    dependencies = json.loads(files["assets/licenses/GO-DEPENDENCIES.json"])
    for dependency in dependencies["dependencies"]:
        if not dependency["licenses"]:
            raise ValueError("missing linked-dependency license")
        for license_file in dependency["licenses"]:
            if sha(files["assets/licenses/" + license_file["path"]]) != license_file["sha256"]:
                raise ValueError("APK linked-dependency license differs")
    return record


def read_payload(apk):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or archive.testzip() is not None:
            raise ValueError("APK contains duplicate or corrupt members")
        for info in archive.infolist():
            name = info.filename.rstrip("/")
            path = PurePosixPath(name)
            if (not name or str(path) != name or path.is_absolute() or ".." in path.parts or "\\" in name
                    or info.external_attr >> 16 & 0o170000 not in (0, 0o100000, 0o040000)):
                raise ValueError("non-canonical or non-regular APK member")
        return {name: archive.read(name) for name in names if not name.endswith("/") and
                (name in {ENGINE_MEMBER, RECORD_MEMBER} or name.startswith(("assets/runtime/", "assets/licenses/")))}


def verify(apk, source_digest=None):
    return validate(read_payload(apk), source_digest)


def extract(apk, manager):
    files = read_payload(apk)
    record = validate(files)
    version = parse_version((manager / "version.properties").read_text())
    if (version.name, version.code) != (record["version"], record["version_code"]):
        raise ValueError("independent App and runtime version differ")
    write_generated(manager, files)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prepare = sub.add_parser("stage")
    for name in ("root", "manager", "engine", "licenses"):
        prepare.add_argument(name, type=Path)
    prepare.add_argument("source_digest")
    for name in ("verify", "extract"):
        command = sub.add_parser(name)
        command.add_argument("apk", type=Path)
        if name == "extract":
            command.add_argument("manager", type=Path)
        else:
            command.add_argument("--source-digest")
    args = parser.parse_args()
    if args.command == "stage":
        result = stage(args.root, args.manager, args.engine, args.licenses, args.source_digest)
    elif args.command == "extract":
        result = extract(args.apk, args.manager)
    else:
        result = verify(args.apk, args.source_digest)
    print(json.dumps({key: value for key, value in result.items() if key != "files"}, sort_keys=True))


if __name__ == "__main__":
    main()
