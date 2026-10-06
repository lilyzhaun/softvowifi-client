"""Bounded GitHub calls with immutable release assets and no credential output."""

import hashlib
import json
import os
import subprocess
from pathlib import Path


class GitHub:
    def __init__(self, repo, token=None):
        self.repo = repo
        self.environment = {**os.environ, "GIT_MASTER": "1"}
        if token:
            self.environment["GH_TOKEN"] = token

    def command(self, arguments, data=None, optional=False):
        result = subprocess.run(
            ["gh", *arguments], input=data, capture_output=True, timeout=180,
            env=self.environment,
        )
        if result.returncode:
            if optional:
                try:
                    if str(json.loads(result.stdout).get("status")) == "404":
                        return None
                except (ValueError, AttributeError):
                    pass
            raise RuntimeError("GitHub operation failed; credentials and response suppressed")
        return result.stdout

    def api(self, route, payload=None, method=None, optional=False):
        arguments = ["api", f"repos/{self.repo}/{route}"]
        if method:
            arguments += ["--method", method]
        if payload is not None:
            arguments += ["--input", "-"]
        raw = self.command(arguments, json.dumps(payload).encode() if payload is not None else None,
                           optional=optional)
        return json.loads(raw) if raw else None

    def check_repo(self, expected_id):
        raw = self.command(["api", f"repos/{self.repo}"])
        repo = json.loads(raw)
        if repo["id"] != expected_id or not repo["private"] or repo["default_branch"] != "master":
            raise ValueError("repository identity or visibility changed")

    def tag(self, tag, commit):
        ref = self.api(f"git/ref/tags/{tag}", optional=True)
        if ref is None:
            self.api("git/refs", {"ref": f"refs/tags/{tag}", "sha": commit})
        elif ref["object"]["type"] != "commit" or ref["object"]["sha"] != commit:
            raise ValueError("existing tag does not match release commit")

    def release(self, tag, commit, prerelease, body):
        release = self.api(f"releases/tags/{tag}", optional=True)
        if release is None:
            release = self.api("releases", {
                "tag_name": tag, "target_commitish": commit, "name": f"SoftVoWiFi {tag}",
                "body": body, "draft": True, "prerelease": prerelease,
            })
        return release

    def upload(self, release, path):
        path = Path(path)
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        present = next((asset for asset in release["assets"] if asset["name"] == path.name), None)
        if present is not None:
            recorded = present.get("digest")
            if recorded is None:
                raw = self.command(["api", f"repos/{self.repo}/releases/assets/{present['id']}",
                                    "-H", "Accept: application/octet-stream"])
                recorded = "sha256:" + hashlib.sha256(raw).hexdigest()
            if present["size"] != path.stat().st_size or recorded != "sha256:" + digest:
                raise ValueError("existing release asset differs; refusing overwrite")
            return
        if not release["draft"]:
            raise ValueError("published release is immutable")
        self.command(["release", "upload", release["tag_name"], str(path), "--repo", self.repo])

    def finish(self, release):
        if release["draft"]:
            self.api(f"releases/{release['id']}", {"draft": False}, method="PATCH")


def finish_client(dist):
    directory = Path(dist)
    metadata = json.loads((directory / "RELEASE.json").read_text())
    github = GitHub("lilyzhaun/softvowifi-client")
    github.check_repo(1373788867)
    release = github.api(f"releases/tags/{metadata['tag']}")
    if release is None:
        raise ValueError("source release handoff is missing")
    expected = {"RELEASE.json", "SHA256SUMS", metadata["module_name"], f"SoftVoWiFi-{metadata['tag']}.apk"}
    if {path.name for path in directory.iterdir() if path.is_file()} != expected:
        raise ValueError("unexpected release artifact inventory")
    module = directory / metadata["module_name"]
    if module.stat().st_size != metadata["module_size"] or hashlib.sha256(module.read_bytes()).hexdigest() != metadata["module_sha256"]:
        raise ValueError("release module checksum changed")
    checksums = "".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n"
                        for path in sorted(directory.iterdir()) if path.name != "SHA256SUMS")
    if (directory / "SHA256SUMS").read_text() != checksums:
        raise ValueError("release asset checksums differ")
    for path in sorted(directory.iterdir()):
        if path.is_file():
            github.upload(release, path)
    github.finish(release)


if __name__ == "__main__":
    import sys
    finish_client(sys.argv[1])
