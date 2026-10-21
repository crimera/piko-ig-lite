"""Instagram version watch: find a newer stable ARM64 release and prove the current dev bundle
patches every Instagram patch in it.

Every subcommand fails closed. Unexpected CLI output, count mismatches, and fetch or parse
errors exit 1 with a message, and the workflow decides what each failure means. Expected
patch names always come from the MPP built in the same run, never from a fixed list. Operator
notes are in README.md beside this file.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

import apkmirror

PACKAGE = "com.instagram.android"
LISTING_URL = "https://www.apkmirror.com/apk/instagram/instagram-instagram/"
CONSTANTS = Path("patches/src/main/kotlin/app/crimera/patches/instagram/utils/Constants.kt")
BRANCH_PREFIX = "instagram-version-watch"
GH_LIMIT = 1000
GH_TIMEOUT_SECONDS = 120
LIST_PATCHES_TIMEOUT_SECONDS = 300
INSPECT_TIMEOUT_SECONDS = 300
MAX_VERSIONS_TO_WALK = 5
# Instagram versions are five numeric parts, for example 452.0.0.48.64. Anything else never
# reaches a branch name, PR title, issue title or body.
VERSION_RE = re.compile(r"[0-9]+(\.[0-9]+){4}")
UNSTABLE_TEXT = re.compile(r"alpha|beta|\brc\b|preview", re.IGNORECASE)
TARGET_RE = re.compile(
    r'AppTarget\(\s*version\s*=\s*"([^"]+)",\s*versionCodes\s*=\s*mapOf\(\s*ARM64_V8A to (\d+),?\s*\)'
)
# morphe-desktop CLI output. `list-patches` prints an "INFO: Index: N" line per patch, then
# "Name:", "Enabled:" and, with -p -v, "Compatible packages:" blocks.
PATCH_INDEX_LINE = re.compile(r"(?:INFO: )?Index: \d+")  # only the first index carries the INFO prefix
PATCH_NAME_LINE = re.compile(r"Name: (.+)")
PATCH_ENABLED_LINE = re.compile(r"Enabled: (true|false)")
PACKAGE_LINE = re.compile(r"\tPackage name: (.+)")
VERSION_CODE_LINE = re.compile(r"\t\t(\S+): ARM64_V8A=(\d+)")
# apkeditor `info` prints these attributes on the manifest.
INFO_PACKAGE = re.compile(r'package="([^"]+)"')
INFO_VERSION_CODE = re.compile(r'VersionCode="(\d+)"')
INFO_VERSION_NAME = re.compile(r'VersionName="([^"]+)"')


class WatchError(Exception):
    """A check failed in a way that stops the run. The message is the diagnostic."""


def version_key(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def checked_version(version: str) -> str:
    if VERSION_RE.fullmatch(version) is None:
        raise WatchError(f"version {version!r} is not five numeric parts; refusing to use it")
    return version


def min_android_key(text: str) -> tuple[int, int]:
    # "Android 9.0+" -> (9, 0). Unknown sorts last, as in the reference.
    match = re.search(r"Android\s+(\d+)(?:\.(\d+))?", text, re.IGNORECASE)
    if match is None:
        return (999, 0)
    return (int(match.group(1)), int(match.group(2) or 0))


def is_stable(row: apkmirror.Version) -> bool:
    return VERSION_RE.fullmatch(row.version) is not None and UNSTABLE_TEXT.search(row.text) is None


def arm64_priority(architecture: str) -> int | None:
    arch = architecture.lower()
    # A universal bundle can list arm64 too, and may carry a different versionCode, so it never
    # counts as an explicit ARM64 variant.
    if "universal" in arch or "arm64-v8a" not in arch:
        return None
    return 1 if "armeabi-v7a" in arch else 2


def select_bundle(variants: list[apkmirror.Variant]) -> apkmirror.Variant | None:
    # Fully ordered, so the same page always yields the same variant. Ties are broken by Android
    # version, then DPI text, then link.
    candidates = [v for v in variants if v.is_bundle and arm64_priority(v.architecture) is not None]
    if not candidates:
        return None
    return min(
        candidates,
        key=lambda v: (arm64_priority(v.architecture), min_android_key(v.min_android_text), v.dpi_text, v.link),
    )


def parse_targets(text: str, label: str) -> list[tuple[str, int]]:
    if f'packageName = "{PACKAGE}"' not in text:
        raise WatchError(f"{label} does not declare packageName {PACKAGE}")
    targets = [(match.group(1), int(match.group(2))) for match in TARGET_RE.finditer(text)]
    if not targets:
        raise WatchError(f"{label}: no AppTarget entries with an ARM64_V8A versionCode parsed")
    if text.count("AppTarget(") != len(targets):
        raise WatchError(f"{label}: an AppTarget does not match the expected ARM64 shape")
    return targets


def declared_targets(constants: Path) -> list[tuple[str, int]]:
    return parse_targets(constants.read_text(encoding="utf-8"), str(constants))


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def write_github_output(path: Path | None, values: dict[str, str]) -> None:
    if path is None:
        return
    with path.open("a", encoding="utf-8") as file:
        for key, value in values.items():
            if "\n" in value or "\r" in value:
                raise WatchError(f"output {key} must be one line")
            file.write(f"{key}={value}\n")


def run_command(command: list[str], timeout: int) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(command, capture_output=True, text=True, check=False, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        raise WatchError(f"{command[0]} did not finish within {timeout} seconds") from error


def gh_json(*args: str):
    result = run_command(["gh", *args], GH_TIMEOUT_SECONDS)
    if result.returncode != 0:
        raise WatchError(f"gh {' '.join(args[:2])} failed: {result.stderr.strip()}")
    return json.loads(result.stdout)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


# ---- discover -------------------------------------------------------------------------------


def cmd_discover(args) -> None:
    declared = declared_targets(args.constants)
    declared_versions = {version for version, _ in declared}
    newest_declared = max(declared_versions, key=version_key)

    rows = apkmirror.get_versions(LISTING_URL)
    stable = sorted(
        (row for row in rows if is_stable(row)),
        key=lambda row: version_key(row.version),
        reverse=True,
    )
    if not stable:
        raise WatchError(f"no stable five-part versions among {len(rows)} listed rows")

    # Walk newest first. The first release with an ARM64 bundle decides the outcome. A page that
    # parses but has no ARM64 bundle moves the walk on; a page that fails to parse raises above.
    walked: list[str] = []
    found = None
    for row in stable[:MAX_VERSIONS_TO_WALK]:
        walked.append(row.version)
        variant = select_bundle(apkmirror.get_variants(row.link))
        if variant is not None:
            found = (row, variant)
            break

    if found is None:
        status = "no-arm64-bundle"
        record_row = stable[0]
        variant_record = None
    else:
        record_row, variant = found
        version = checked_version(record_row.version)
        if version in declared_versions:
            status = "supported"
        elif version_key(version) <= version_key(newest_declared):
            status = "not-newer"
        else:
            status = "candidate"
        variant_record = {
            "page_url": variant.link,
            "architecture": variant.architecture,
            "min_android": variant.min_android_text,
            "dpi": variant.dpi_text,
        }

    record = {
        "status": status,
        "version": record_row.version,
        "newest_declared": newest_declared,
        "version_url": record_row.link,
        "variant": variant_record,
        "walked": walked,
        "stable_rows": len(stable),
    }
    write_json(args.out, record)
    write_github_output(
        args.github_output,
        {
            "status": status,
            "version": record_row.version,
            "version_url": record_row.link,
            "variant_page": variant_record["page_url"] if variant_record else "",
        },
    )
    print(f"{status}: newest stable ARM64 bundle {record_row.version}; newest declared {newest_declared}")


# ---- check-existing -------------------------------------------------------------------------


def decide_existing(record: dict, repo: str) -> tuple[bool, str]:
    """Open-only dedupe: an open PR on the version branch or an open issue for the version skips
    the run, whatever the dev commit is. Closed PRs and closed issues do not, so closing a failure
    issue allows a retry."""
    if record["status"] != "candidate":
        return False, f"{record['version']} is {record['status']}, not a new candidate"

    version = checked_version(record["version"])
    branch = f"{BRANCH_PREFIX}/{version}"
    prs = gh_json("pr", "list", "--repo", repo, "--state", "open", "--limit", str(GH_LIMIT),
                  "--json", "number,headRefName,url")
    issues = gh_json("issue", "list", "--repo", repo, "--state", "open", "--limit", str(GH_LIMIT),
                     "--json", "number,title,url")
    if len(prs) >= GH_LIMIT or len(issues) >= GH_LIMIT:
        raise WatchError(f"reached the {GH_LIMIT} item limit; cannot check open PRs and issues safely")

    for pr in prs:
        if pr["headRefName"] == branch:
            return False, f"open PR {pr['url']} is on {branch}"
    title_prefix = f"Instagram {version} version watch:"
    for issue in issues:
        if issue["title"].startswith(title_prefix):
            return False, f"open issue {issue['url']} already reports {version}"
    return True, f"no open PR or issue for {version}"


def cmd_check_existing(args) -> None:
    record = read_json(args.discover)
    proceed, reason = decide_existing(record, args.repo)
    record["proceed"] = proceed
    record["reason"] = reason
    write_json(args.discover, record)
    write_github_output(args.github_output, {"proceed": "true" if proceed else "false", "reason": reason})
    print(f"proceed={'true' if proceed else 'false'}: {reason}")


# ---- enumerate ------------------------------------------------------------------------------


def list_patches(cli: Path, mpp: Path) -> str:
    # The morphe-desktop CLI lists packages and ARM64 version codes for each patch with these flags.
    command = ["java", "-jar", str(cli), "list-patches", "--patches", str(mpp),
               "--with-descriptions=false", "--with-packages", "--with-versions"]
    result = run_command(command, LIST_PATCHES_TIMEOUT_SECONDS)
    if result.returncode != 0:
        raise WatchError(f"list-patches exited {result.returncode}: {result.stderr.strip()}")
    return result.stdout


def parse_list_patches(text: str) -> list[dict]:
    """Parse the block format. Unknown lines fail the parse rather than being skipped."""
    patches: list[dict] = []
    for line in text.splitlines():
        if PATCH_INDEX_LINE.fullmatch(line):
            patches.append({"name": None, "default": None, "packages": [], "version_codes": {}})
            continue
        if not line.strip() or line.startswith("INFO: "):
            continue  # blank separators and tool log lines such as the data root
        if not patches:
            raise WatchError(f"list-patches line before the first patch: {line!r}")
        if match := PATCH_NAME_LINE.fullmatch(line):
            patches[-1]["name"] = match.group(1)
        elif match := PATCH_ENABLED_LINE.fullmatch(line):
            patches[-1]["default"] = match.group(1) == "true"
        elif match := PACKAGE_LINE.fullmatch(line):
            patches[-1]["packages"].append(match.group(1))
        elif match := VERSION_CODE_LINE.fullmatch(line):
            patches[-1]["version_codes"][match.group(1)] = int(match.group(2))
        elif line in ("Compatible packages:", "\tCompatible versions:", "\tVersion codes:") or line.startswith("\t\t"):
            continue  # section headers and version lists, whose values the patterns above already take
        else:
            raise WatchError(f"unexpected list-patches line: {line!r}")
    for patch in patches:
        if not patch["name"] or patch["default"] is None:
            raise WatchError(f"list-patches block without a name or Enabled line: {patch}")
    return patches


def in_instagram_scope(packages: list[str]) -> bool:
    # A patch with no compatibility lines applies to every app, and so does an explicit universal one.
    return not packages or PACKAGE in packages or "(universal)" in packages


def cmd_enumerate(args) -> None:
    patches = parse_list_patches(list_patches(args.cli, args.mpp))
    if not patches:
        raise WatchError(f"{args.mpp.name} lists no named patches")

    names = [patch["name"] for patch in patches]
    duplicates = sorted({name for name in names if names.count(name) > 1})
    if duplicates:
        raise WatchError(f"duplicate patch names in {args.mpp.name}: {duplicates}")

    selected = sorted((p for p in patches if in_instagram_scope(p["packages"])), key=lambda p: p["name"])
    if not selected:
        raise WatchError(f"{args.mpp.name} has no patches declared for {PACKAGE} or universal")

    selected_names = {patch["name"] for patch in selected}
    record = {
        "mpp": args.mpp.name,
        "total_loaded": len(patches),
        "names": [patch["name"] for patch in selected],
        "excluded": sorted(name for name in names if name not in selected_names),
        "declared": {patch["name"]: patch["packages"] for patch in selected},
    }
    write_json(args.out, record)
    print(f"{len(selected)} of {len(patches)} patches in {args.mpp.name} are Instagram or universal:")
    for name in record["names"]:
        print(f"  {name}")


# ---- download -----------------------------------------------------------------------------------


def cmd_download(args) -> None:
    result = apkmirror.download_bundle(args.variant_page, args.out, checked_version(args.version))
    result["variant_page"] = args.variant_page
    result["file"] = args.out.name
    write_json(args.result, result)
    print(f"downloaded {args.out.name}: {result['bytes']} bytes, sha256 {result['sha256']}")


def check_output_apk(path: Path) -> int:
    """Return the number of arm64-v8a native library entries. Raise unless the APK is usable."""
    if not zipfile.is_zipfile(path):
        raise WatchError(f"patched APK {path.name} is not a zip archive")
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
    for required in ("AndroidManifest.xml", "classes.dex"):
        if required not in names:
            raise WatchError(f"patched APK has no {required}")
    arm64 = [name for name in names if name.startswith("lib/arm64-v8a/")]
    if not arm64:
        raise WatchError("patched APK has no lib/arm64-v8a entries; the ARM64 contract does not hold")
    return len(arm64)


def find_attribute(text: str, pattern: re.Pattern, label: str) -> str:
    matches = pattern.findall(text)
    if len(matches) != 1:
        raise WatchError(f"APKEditor info: expected one {label}, found {len(matches)}")
    return matches[0]


# ---- validate -------------------------------------------------------------------------------


def check_cli_report(report: dict, names: list[str], output_apk: Path, version: str) -> None:
    if report.get("success") is not True or report.get("failedPatches"):
        raise WatchError(f"result report: success={report.get('success')}, failed={report.get('failedPatches')}")
    if sorted(report.get("appliedPatches", [])) != sorted(names):
        raise WatchError(f"result report applied {report.get('appliedPatches')}; expected {sorted(names)}")
    if report.get("packageName") != PACKAGE or report.get("packageVersion") != version:
        raise WatchError(f"result report is {report.get('packageName')} {report.get('packageVersion')}; "
                         f"expected {PACKAGE} {version}")
    if Path(report.get("outputPath", "")).resolve() != output_apk.resolve():
        raise WatchError(f"result report outputPath {report.get('outputPath')} is not {output_apk}")


def cmd_validate(args) -> None:
    names = read_json(args.names)["names"]
    check_cli_report(read_json(args.report), names, args.apk, args.version)
    arm64_entries = check_output_apk(args.apk)

    # morphe-desktop's result report has no versionCode, so the patched APK's own manifest is read
    # with the pinned APKEditor. That is the check that the output carries the code AppTarget declares.
    command = ["java", "-jar", str(args.apkeditor), "info", "-i", str(args.apk)]
    result = run_command(command, INSPECT_TIMEOUT_SECONDS)
    if result.returncode != 0:
        raise WatchError(f"APKEditor info exited {result.returncode}: {result.stderr.strip()}")
    package = find_attribute(result.stdout, INFO_PACKAGE, "package")
    version_name = find_attribute(result.stdout, INFO_VERSION_NAME, "VersionName")
    version_code = int(find_attribute(result.stdout, INFO_VERSION_CODE, "VersionCode"))
    if package != PACKAGE or version_name != args.version:
        raise WatchError(f"patched APK is {package} {version_name}; expected {PACKAGE} {args.version}")
    if version_code != args.expected_code:
        raise WatchError(f"patched APK versionCode {version_code} differs from the "
                         f"APKMirror file name code {args.expected_code}")

    record = {
        "package": package,
        "version_name": version_name,
        "version_code": version_code,
        "applied": sorted(names),
        "output_bytes": args.apk.stat().st_size,
        "output_sha256": sha256_file(args.apk),
        "arm64_native_entries": arm64_entries,
        "mpp": args.mpp.name,
        "mpp_sha256": sha256_file(args.mpp),
    }
    write_json(args.result, record)
    print(f"validated: {package} {version_name} ({version_code}), {len(names)} patches applied")


# ---- add-target -----------------------------------------------------------------------------


def add_target(constants: Path, version: str, version_code: int) -> None:
    checked_version(version)
    before = declared_targets(constants)
    if any(declared == version for declared, _ in before):
        raise WatchError(f"{version} is already declared")
    if version_key(version) <= max(version_key(declared) for declared, _ in before):
        raise WatchError(f"{version} is not newer than the declared targets")

    lines = constants.read_text(encoding="utf-8").splitlines(keepends=True)
    first = next(i for i, line in enumerate(lines) if line.lstrip().startswith("AppTarget("))
    indent = lines[first][: len(lines[first]) - len(lines[first].lstrip())]
    lines[first:first] = [
        f"{indent}AppTarget(\n",
        f'{indent}    version = "{version}",\n',
        f"{indent}    versionCodes =\n",
        f"{indent}        mapOf(\n",
        f"{indent}            ARM64_V8A to {version_code},\n",
        f"{indent}        ),\n",
        f"{indent}),\n",
    ]
    new_text = "".join(lines)
    after = parse_targets(new_text, str(constants))
    if after != [(version, version_code)] + before:
        raise WatchError("targets after the edit differ from the expected list; file not written")
    constants.write_text(new_text, encoding="utf-8")


def cmd_add_target(args) -> None:
    add_target(args.constants, args.version, args.version_code)
    print(f"added AppTarget {args.version} (ARM64_V8A {args.version_code}) to {args.constants}")


# ---- rendering ------------------------------------------------------------------------------


def load_records(directory: Path) -> dict:
    # The four files the build job writes. A missing one means the run did not finish.
    records = {}
    for name in ("discover", "download", "names", "patch-result"):
        path = directory / f"{name}.json"
        if not path.is_file():
            raise WatchError(f"{path} is missing; refusing to render the PR without it")
        records[name] = read_json(path)
    return records


def render_pr(records: dict, source_sha: str, run_url: str, cli_tag: str, cli_sha256: str,
              apkeditor_version: str) -> tuple[str, str]:
    discover = records["discover"]
    variant = discover["variant"]
    download = records["download"]
    result = records["patch-result"]
    version = discover["version"]
    count = len(result["applied"])
    title = f"feat(instagram): add {version} compatibility"
    patch_list = "\n".join(f"- {name}" for name in result["applied"])

    body = f"""### Description

Adds Instagram {version} to `COMPATIBILITY_INSTAGRAM` with ARM64_V8A versionCode `{result['version_code']}`.

Automated by the Instagram version watch ({run_url}): built the `dev` bundle at `{source_sha}`, passed resolver lint, and applied all {count} Instagram patches exclusively to the APKMirror bundle. Patch application is not runtime verification.

### Details

- APKMirror: {discover['version_url']} — variant {variant['page_url']} (`{variant['architecture']}`, {variant['min_android']}, {variant['dpi']})
- APKM `{download['filename']}`, sha256 `{download['sha256']}`
- Patched APK sha256 `{result['output_sha256']}`, versionCode `{result['version_code']}`
- Bundle `{result['mpp']}`, sha256 `{result['mpp_sha256']}`
- Morphe CLI `{cli_tag}` (sha256 `{cli_sha256}`), APKEditor `{apkeditor_version}`

<details><summary>Patches ({count})</summary>

{patch_list}
</details>

### Maintainer checks

- [ ] Patched an older supported version and compared resolved members
- [ ] Runtime-tested the patched APK on a device
"""
    return title, body


def cmd_render_pr(args) -> None:
    title, body = render_pr(load_records(args.records_dir), args.source_sha, args.run_url,
                            args.cli_tag, args.cli_sha256, args.apkeditor_version)
    args.title_out.write_text(title + "\n", encoding="utf-8")
    args.body_out.write_text(body, encoding="utf-8")


# ---- entry point ----------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Instagram version watch helper.")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("discover", help="find the newest stable ARM64 release")
    p.add_argument("--constants", type=Path, default=CONSTANTS)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--github-output", type=Path)
    p.set_defaults(handler=cmd_discover)

    p = sub.add_parser("check-existing", help="skip versions with an open PR or open issue")
    p.add_argument("--discover", type=Path, required=True)
    p.add_argument("--repo", required=True)
    p.add_argument("--github-output", type=Path)
    p.set_defaults(handler=cmd_check_existing)

    p = sub.add_parser("enumerate", help="list Instagram patch names from a built MPP")
    p.add_argument("--cli", type=Path, required=True)
    p.add_argument("--mpp", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.set_defaults(handler=cmd_enumerate)

    p = sub.add_parser("download", help="download the APKM bundle and check its file name and zip structure")
    p.add_argument("--variant-page", required=True)
    p.add_argument("--version", required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--result", type=Path, required=True)
    p.set_defaults(handler=cmd_download)

    p = sub.add_parser("validate", help="check the CLI report and the patched APK, then write patch-result.json")
    p.add_argument("--names", type=Path, required=True)
    p.add_argument("--report", type=Path, required=True)
    p.add_argument("--apkeditor", type=Path, required=True)
    p.add_argument("--apk", type=Path, required=True)
    p.add_argument("--mpp", type=Path, required=True)
    p.add_argument("--version", required=True)
    p.add_argument("--expected-code", type=int, required=True)
    p.add_argument("--result", type=Path, required=True)
    p.set_defaults(handler=cmd_validate)

    p = sub.add_parser("add-target", help="insert the new AppTarget at the top of Constants.kt")
    p.add_argument("--constants", type=Path, default=CONSTANTS)
    p.add_argument("--version", required=True)
    p.add_argument("--version-code", type=int, required=True)
    p.set_defaults(handler=cmd_add_target)

    p = sub.add_parser("render-pr", help="write the PR title and body from the records directory")
    p.add_argument("--records-dir", type=Path, required=True)
    p.add_argument("--run-url", required=True)
    p.add_argument("--source-sha", required=True)
    p.add_argument("--cli-tag", required=True)
    p.add_argument("--cli-sha256", required=True)
    p.add_argument("--apkeditor-version", required=True)
    p.add_argument("--title-out", type=Path, required=True)
    p.add_argument("--body-out", type=Path, required=True)
    p.set_defaults(handler=cmd_render_pr)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        args.handler(args)
    except (WatchError, apkmirror.ApkMirrorError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
