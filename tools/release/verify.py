"""Verify the public version/ZIP handoff and the independently built Android package."""

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

from version import parse_version
from client_tree import candidate_digest, committed_files


def metadata():
    result = json.loads(Path("RELEASE.json").read_text())
    fields = {"schema", "version", "version_code", "tag", "module_name", "module_sha256",
              "module_size", "client_tree_sha256"}
    if set(result) != fields or type(result["schema"]) is not int or result["schema"] != 1:
        raise ValueError("invalid public release handoff")
    version = parse_version(Path("app/manager/version.properties").read_text())
    if (result["version"] != version.name or result["version_code"] != version.code
            or result["tag"] != version.tag or result["module_name"] != f"SoftVoWiFi-ksu-{version.tag}.zip"
            or re.fullmatch(r"[0-9a-f]{64}", result["module_sha256"]) is None
            or re.fullmatch(r"[0-9a-f]{64}", result["client_tree_sha256"]) is None
            or type(result["module_size"]) is not int or result["module_size"] <= 0):
        raise ValueError("client/module version mismatch or invalid checksum")
    if candidate_digest(committed_files()) != result["client_tree_sha256"]:
        raise ValueError("actual client source digest differs from release metadata")
    return result


def verify_apk(path, data):
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
    analyzer = sdk / "cmdline-tools/latest/bin/apkanalyzer"
    for command, expected in (("application-id", "com.vowifi.sw"),
                              ("version-name", data["version"]),
                              ("version-code", str(data["version_code"]))):
        actual = subprocess.run([str(analyzer), "manifest", command, str(path)],
                                check=True, capture_output=True, text=True, timeout=60).stdout.strip()
        if actual != expected:
            raise ValueError("unexpected release APK identity/version")


def main():
    command, value = sys.argv[1:]
    data = metadata()
    if command == "metadata":
        if value != data["tag"]:
            raise ValueError("requested tag and release version differ")
        if os.environ.get("GITHUB_OUTPUT"):
            with open(os.environ["GITHUB_OUTPUT"], "a") as output:
                output.write(f"module_name={data['module_name']}\napk_name=SoftVoWiFi-{data['tag']}.apk\n")
    elif command == "module":
        path = Path(value)
        if (path.name != data["module_name"] or path.stat().st_size != data["module_size"]
                or hashlib.sha256(path.read_bytes()).hexdigest() != data["module_sha256"]):
            raise ValueError("module ZIP checksum mismatch")
    elif command == "apk":
        verify_apk(Path(value), data)
    elif command == "checksums":
        directory = Path(value)
        shutil.copyfile("RELEASE.json", directory / "RELEASE.json")
        files = sorted(path for path in directory.iterdir() if path.is_file())
        (directory / "SHA256SUMS").write_text("".join(
            f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in files
        ))
    elif command == "release-artifact":
        if (Path(value) / "RELEASE.json").read_bytes() != Path("RELEASE.json").read_bytes():
            raise ValueError("signed artifact metadata differs from committed source")
    else:
        raise ValueError("unknown release check")
    print("Client release handoff verified")


if __name__ == "__main__":
    main()
