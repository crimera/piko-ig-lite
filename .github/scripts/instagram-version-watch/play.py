"""Google Play access for the Instagram version watch.

The anonymous token comes from the Aurora Store dispenser through gplaydl 2.1.7's library, using one
pinned device profile. Every failure raises PlayError: a failed lookup must never look like "no new
version". Nothing here writes or prints the token. gplaydl and httpx are imported inside the functions
that need them, so the stdlib-only commands (add-target, render-*) run without them.
"""

from __future__ import annotations

import hashlib
import re
import shutil
import sys
import time
import zipfile
from pathlib import Path

PACKAGE = "com.instagram.android"
PROFILE_KEY = "Pv"
PROFILE_NAME = "Google Pixel 9a"
PROFILE_ABI = "arm64-v8a"
DISPENSER_ATTEMPTS = 3
DISPENSER_BACKOFF_SECONDS = 15
TIMEOUT_SECONDS = 60
CHUNK_BYTES = 1 << 20
# A real Instagram base APK is tens of MB. Anything smaller is an error page or a truncated transfer.
MIN_BASE_BYTES = 10 * 1024 * 1024
# Split names come from Google. Only plain file-name characters may reach a path on disk.
SPLIT_NAME_RE = re.compile(r"[A-Za-z0-9_.-]+")


class PlayError(Exception):
    """A Play lookup, login or download step failed. Always fatal for the run."""


def profile() -> dict:
    from gplaydl.profiles import load_all_profiles

    entry = load_all_profiles().get(PROFILE_KEY)
    if entry is None:
        raise PlayError(f"gplaydl has no device profile {PROFILE_KEY}")
    data = entry["profile"]
    if data.get("UserReadableName") != PROFILE_NAME or PROFILE_ABI not in data.get("Platforms", ""):
        raise PlayError(f"device profile {PROFILE_KEY} is not {PROFILE_NAME} with {PROFILE_ABI}")
    return data


def login() -> dict:
    import httpx
    from gplaydl.auth import DEFAULT_DISPENSER_URL

    device = profile()
    headers = {"User-Agent": "com.aurora.store-4.6.1-70", "Content-Type": "application/json"}
    for attempt in range(1, DISPENSER_ATTEMPTS + 1):
        try:
            response = httpx.post(DEFAULT_DISPENSER_URL, json=device, headers=headers, timeout=30)
            reason = f"HTTP {response.status_code}"
            if response.status_code == 200:
                data = response.json()
                if isinstance(data, dict) and data.get("authToken"):
                    return data
                reason = "no authToken"
        except (httpx.HTTPError, ValueError) as error:
            # The error type only: the reply body is never shown.
            reason = f"request failed ({type(error).__name__})"
        if attempt == DISPENSER_ATTEMPTS:
            raise PlayError(f"dispenser login failed after {attempt} attempts: {reason}")
        print(f"dispenser login: {reason}; retrying in {DISPENSER_BACKOFF_SECONDS * attempt}s", file=sys.stderr)
        time.sleep(DISPENSER_BACKOFF_SECONDS * attempt)
    raise AssertionError("unreachable: the last attempt returns or raises")


def details(auth: dict) -> tuple[str, int]:
    import httpx
    from gplaydl.api import PlayAPIError, get_details

    try:
        found = get_details(PACKAGE, auth)
    except (PlayAPIError, httpx.HTTPError) as error:
        raise PlayError(f"details for {PACKAGE} failed: {error}") from error
    if found.package != PACKAGE:
        raise PlayError(f"Play returned package {found.package!r} for {PACKAGE}")
    if not found.version_string or found.version_code <= 0:
        raise PlayError(f"Play returned no usable version for {PACKAGE}")
    return found.version_string, found.version_code


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(CHUNK_BYTES), b""):
            digest.update(chunk)
    return digest.hexdigest()


def zip_names(path: Path) -> list[str]:
    if not zipfile.is_zipfile(path):
        raise PlayError(f"{path.name} is not a zip archive")
    try:
        with zipfile.ZipFile(path) as archive:
            return archive.namelist()
    except zipfile.BadZipFile as error:
        raise PlayError(f"{path.name} is a damaged zip archive: {error}") from error


def check_base(path: Path) -> None:
    size = path.stat().st_size
    if size < MIN_BASE_BYTES:
        raise PlayError(f"base.apk is {size} bytes, too small to be an Instagram APK")
    names = zip_names(path)
    for required in ("AndroidManifest.xml", "classes.dex"):
        if required not in names:
            raise PlayError(f"base.apk has no {required}")
    if not any(name.startswith("lib/arm64-v8a/") for name in names):
        raise PlayError("base.apk has no lib/arm64-v8a entries; the ARM64 contract does not hold")


def fetch(url: str, path: Path, headers: dict[str, str]) -> tuple[int, str]:
    import httpx

    digest = hashlib.sha256()
    size = 0
    try:
        with httpx.stream("GET", url, headers=headers, timeout=TIMEOUT_SECONDS, follow_redirects=True) as response:
            if response.status_code != 200:
                raise PlayError(f"HTTP {response.status_code} downloading {path.name}")
            with path.open("xb") as file:
                for chunk in response.iter_bytes(CHUNK_BYTES):
                    file.write(chunk)
                    digest.update(chunk)
                    size += len(chunk)
    except httpx.HTTPError as error:
        # Delivery URLs carry signed query strings, so the message names only the error type.
        raise PlayError(f"download of {path.name} failed ({type(error).__name__})") from error
    return size, digest.hexdigest()


def download(auth: dict, version: str, version_code: int, out: Path) -> dict:
    import httpx
    from gplaydl.api import PlayAPIError, get_delivery, purchase

    served = details(auth)
    if served != (version, version_code):
        raise PlayError(
            f"Play now serves {served[0]} ({served[1]}), not {version} ({version_code}); "
            "the next run will pick it up"
        )

    try:
        purchase(PACKAGE, version_code, auth)  # free-app acquire; its HTTP status is ignored, as in gplaydl
        delivery = get_delivery(PACKAGE, version_code, auth)
    except (PlayAPIError, httpx.HTTPError) as error:
        raise PlayError(f"delivery for {PACKAGE} {version} failed: {error}") from error

    split_names = [split.name for split in delivery.splits]
    for name in split_names:
        if SPLIT_NAME_RE.fullmatch(name) is None:
            raise PlayError(f"split name {name!r} is not a plain file name; refusing to use it")
    if len(set(split_names)) != len(split_names):
        raise PlayError(f"Play returned duplicate split names: {split_names}")

    parts = out.parent / (out.stem + ".parts")
    if parts.exists():
        raise PlayError(f"{parts} already exists; remove it before rerunning")
    parts.mkdir(parents=True)

    # Like gplaydl, the delivery cookies go with the base APK only. Their values are never logged.
    base_headers = {}
    if delivery.cookies:
        base_headers["Cookie"] = "; ".join(f"{c['name']}={c['value']}" for c in delivery.cookies)
    files = [("base.apk", delivery.download_url, delivery.download_size, base_headers)]
    files += [(f"split_{split.name}.apk", split.url, split.size, {}) for split in delivery.splits]

    records = []
    for name, url, expected, headers in files:
        size, sha256 = fetch(url, parts / name, headers)
        if expected > 0 and size != expected:
            raise PlayError(f"{name} is {size} bytes; Play says {expected}")
        records.append({"name": name, "bytes": size, "sha256": sha256})

    check_base(parts / "base.apk")
    for name, _, _, _ in files[1:]:
        if "AndroidManifest.xml" not in zip_names(parts / name):
            raise PlayError(f"{name} has no AndroidManifest.xml")

    # The bundle is written as a stored zip, and the file appears only after it is complete.
    part = out.with_name(out.name + ".part")
    with zipfile.ZipFile(part, "x", compression=zipfile.ZIP_STORED) as bundle:
        for record in records:
            bundle.write(parts / record["name"], arcname=record["name"])
    bundle_bytes = part.stat().st_size
    bundle_sha256 = sha256_file(part)
    part.rename(out)
    shutil.rmtree(parts)

    return {
        "source": "Google Play",
        "profile": PROFILE_NAME,
        "version": version,
        "version_code": version_code,
        "file": out.name,
        "bytes": bundle_bytes,
        "sha256": bundle_sha256,
        "parts": records,
        # Instagram's asset entry is a byte-identical duplicate of the base APK, so it is not fetched.
        "additional_files_ignored": len(delivery.additional_files),
    }
