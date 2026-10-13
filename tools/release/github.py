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
        known = {"lilyzhaun/softvowifi": 1360754180, "lilyzhaun/softvowifi-client": 1373788867}
        if (known.get(self.repo) != expected_id or repo["id"] != expected_id
                or repo["full_name"] != self.repo or repo["default_branch"] != "master"
                or type(repo["private"]) is not bool
                or (self.repo == "lilyzhaun/softvowifi" and not repo["private"])):
            raise ValueError("repository identity or visibility changed")

    def tag(self, tag, commit):
        ref = self.api(f"git/ref/tags/{tag}", optional=True)
        if ref is None:
            self.api("git/refs", {"ref": f"refs/tags/{tag}", "sha": commit})
        elif ref["object"]["type"] != "commit" or ref["object"]["sha"] != commit:
            raise ValueError("existing tag does not match release commit")

    def release(self, tag, commit, prerelease, body):
        release = self.find_release(tag)
        if release is None:
            release = self.api("releases", {
                "tag_name": tag, "target_commitish": commit, "name": f"SoftVoWiFi {tag}",
                "body": body, "draft": True, "prerelease": prerelease,
            })
        return release

    def find_release(self, tag):
        release = self.api(f"releases/tags/{tag}", optional=True)
        if release is not None:
            return release
        # The by-tag endpoint only returns published releases. Write identities
        # can find the newest draft through the releases list instead.
        return next((item for item in self.api("releases?per_page=100")
                     if item["tag_name"] == tag), None)

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


def finish_client(dist, requested_tag, expected_commit):
    directory = Path(dist)
    metadata = json.loads((directory / "RELEASE.json").read_text())
    if metadata["tag"] != requested_tag or len(expected_commit) != 40:
        raise ValueError("release artifact does not match requested version")
    github = GitHub("lilyzhaun/softvowifi-client")
    github.check_repo(1373788867)
    ref = github.api(f"git/ref/tags/{requested_tag}")
    if ref["object"]["type"] != "commit" or ref["object"]["sha"] != expected_commit:
        raise ValueError("release tag changed after trusted handoff")
    release = github.find_release(metadata["tag"])
    if release is None:
        raise ValueError("source release handoff is missing")
    expected = {"RELEASE.json", "SHA256SUMS", metadata["module_name"], f"SoftVoWiFi-{metadata['tag']}.apk"}
    present = {path.name for path in directory.iterdir() if path.is_file()}
    optional = {f"SoftVoWiFi-{metadata['tag']}.apk.idsig"}
    if not expected <= present or present - expected - optional:
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


def verify_recovery(run_id, artifact_id, tag):
    if not run_id.isdigit() or not artifact_id.isdigit():
        raise ValueError("recovery IDs must be numeric")
    github = GitHub("lilyzhaun/softvowifi-client")
    github.check_repo(1373788867)
    run = github.api(f"actions/runs/{run_id}")
    artifact = github.api(f"actions/artifacts/{artifact_id}")
    jobs = github.api(f"actions/runs/{run_id}/jobs?per_page=100")["jobs"]
    if (run["event"] != "workflow_dispatch" or run["head_branch"] != "master"
            or run["path"] != ".github/workflows/release.yml"
            or run["display_title"] != f"发布客户端 {tag}"
            or artifact["name"] != "client-release" or artifact["expired"]
            or artifact["workflow_run"]["id"] != int(run_id)
             or not any(job["name"] == "build" and job["conclusion"] == "success" for job in jobs)
             or (any(job["name"] == "sign" for job in jobs)
                 and not any(job["name"] == "sign" and job["conclusion"] == "success" for job in jobs))):
        raise ValueError("artifact is not from a successful trusted release build")
    print("Trusted signed release artifact verified; no rebuild requested")


if __name__ == "__main__":
    import sys
    if sys.argv[1] == "verify-recovery":
        verify_recovery(*sys.argv[2:])
    else:
        finish_client(*sys.argv[1:])
