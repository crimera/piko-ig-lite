"""APKMirror listing, variant and download access for the Instagram version watch.

Selectors follow monsivamon/x-lite-apk's apkmirror.py. Every fetch or parse problem raises
ApkMirrorError: a failed lookup must never look like "no new version". A page that parses but
offers no ARM64 bundle is not an error; it returns no variant.
"""

from __future__ import annotations

import hashlib
import re
import zipfile
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import unquote, urljoin, urlparse

BASE_URL = "https://www.apkmirror.com/"
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/126.0.0.0 Safari/537.36"
)
TIMEOUT_SECONDS = 60
CHUNK_BYTES = 1 << 20
# A real APKM bundle is tens of MB. Anything smaller is an error page or a truncated transfer.
MIN_BUNDLE_BYTES = 10 * 1024 * 1024
# APKMirror file names look like com.instagram.android_452.0.0.48.64-385810228_2dpi_<hash>_apkmirror.com.apkm
FILENAME_RE = re.compile(r"^[\w.]+?_(\d+(?:\.\d+)+)-(\d+)_")
DISPOSITION_RE = re.compile(r'filename="?([^";]+)"?')

_scraper = None


class ApkMirrorError(Exception):
    """A fetch, parse or download step failed. Always fatal for the run."""


@dataclass(frozen=True)
class Version:
    version: str
    # Whole row text, so badges such as "Beta" stay visible to the stability check.
    text: str
    link: str


@dataclass(frozen=True)
class Variant:
    is_bundle: bool
    architecture: str
    min_android_text: str
    dpi_text: str
    link: str


def _session():
    global _scraper
    if _scraper is None:
        # Imported lazily so the stdlib-only commands (add-target, render-*) run without it.
        import cloudscraper

        _scraper = cloudscraper.create_scraper()
        _scraper.headers.update({"User-Agent": USER_AGENT})
    return _scraper


def _soup(html: str):
    from bs4 import BeautifulSoup

    return BeautifulSoup(html, "html.parser")


def _check(response, url: str, html: bool) -> None:
    # Cloudflare answers a challenge with HTTP 403 plus cf-mitigated, so the challenge is named
    # before the status code is reported.
    if response.headers.get("cf-mitigated") == "challenge":
        raise ApkMirrorError(f"Cloudflare challenged {url} (HTTP {response.status_code}); no page content")
    if html and "Just a moment" in response.text[:4096]:
        raise ApkMirrorError(f"Cloudflare challenge page served for {url}; no page content")
    if response.status_code != 200:
        raise ApkMirrorError(f"HTTP {response.status_code} for {url}")


def _get(url: str):
    try:
        response = _session().get(url, timeout=TIMEOUT_SECONDS)
    except Exception as error:  # requests and cloudscraper raise several unrelated types
        raise ApkMirrorError(f"request failed: {url}: {error}") from error
    _check(response, url, html=True)
    return response


def get_versions(listing_url: str) -> list[Version]:
    widget = _soup(_get(listing_url).text).find("div", attrs={"class": "listWidget"})
    if widget is None:
        raise ApkMirrorError("version listing has no div.listWidget; page layout changed")

    rows = widget.find_all("div", recursive=False)[1:]  # the first child is the header row
    versions: list[Version] = []
    for index, row in enumerate(rows):
        value = row.find("span", attrs={"class": "infoSlide-value"})
        anchor = row.find("a")
        if value is None and anchor is None and not row.get_text(strip=True):
            if index == 0:
                raise ApkMirrorError("the first listing row has no version; layout changed")
            continue  # proven decorative: no version, no link, and no text
        if value is None or anchor is None or not anchor.get("href"):
            # A row with only one of the two is a real release whose layout changed. Skipping it
            # would hide the newest release, so it is an error.
            raise ApkMirrorError(f"listing row {index} has a version or link missing; layout changed")
        versions.append(
            Version(
                version=value.get_text(strip=True),
                text=row.get_text(" ", strip=True),
                link=urljoin(BASE_URL, anchor["href"]),
            )
        )
    if not versions:
        raise ApkMirrorError(f"version listing parsed 0 of {len(rows)} rows; layout changed")
    return versions


def get_variants(version_url: str) -> list[Variant]:
    table = _soup(_get(version_url).text).find("div", attrs={"class": "table"})
    if table is None:
        raise ApkMirrorError(f"variants table missing: {version_url}")

    rows = table.find_all("div", recursive=False)[1:]  # the first child is the header row
    variants: list[Variant] = []
    for index, row in enumerate(rows):
        cells = row.find_all("div", attrs={"class": "table-cell"}, recursive=False)
        link = row.find("a", attrs={"class": "accent_color"})
        badge = row.find("span", attrs={"class": "apkm-badge"})
        if not cells and link is None and badge is None and not row.get_text(strip=True):
            if index == 0:
                raise ApkMirrorError(f"the first variant row is empty; layout changed: {version_url}")
            continue  # proven decorative: no cells, no link, no badge, and no text
        if len(cells) < 3 or link is None or not link.get("href"):
            # Anything else that does not parse may be a real variant, so it is an error.
            # Skipping it could hide the ARM64 bundle and report a false "no candidate".
            raise ApkMirrorError(
                f"variant row {index} is malformed (cells={len(cells)}, link={link is not None}); "
                f"layout changed: {version_url}"
            )
        variants.append(
            Variant(
                is_bundle=badge is not None and badge.get_text(strip=True) == "BUNDLE",
                architecture=cells[1].get_text(strip=True),
                min_android_text=cells[2].get_text(strip=True),
                dpi_text=cells[3].get_text(strip=True) if len(cells) > 3 else "",
                link=urljoin(BASE_URL, link["href"]),
            )
        )
    if not rows or not variants:
        raise ApkMirrorError(f"variants table parsed {len(variants)} of {len(rows)} rows: {version_url}")
    return variants


def download_bundle(variant_url: str, out: Path, expected_version: str) -> dict:
    """Follow variant page -> download page -> direct link and write a checked bundle to `out`.

    The bytes go to `<out>.part` first. `out` appears only after the zip checks pass, and an
    existing `.part` is an error rather than something to resume. The file name must give
    `expected_version` and a versionCode before any bytes are written.
    """
    button = _soup(_get(variant_url).text).find("a", attrs={"class": "downloadButton"})
    if button is None or not button.get("href"):
        raise ApkMirrorError(f"download button missing: {variant_url}")
    download_page_url = urljoin(BASE_URL, button["href"])

    # The first rel=nofollow link is the direct file link, as in the reference. The checks below
    # reject the file if APKMirror changes that layout.
    direct = _soup(_get(download_page_url).text).find("a", attrs={"rel": "nofollow"})
    if direct is None or not direct.get("href"):
        raise ApkMirrorError(f"direct download link missing: {download_page_url}")
    direct_url = urljoin(BASE_URL, direct["href"])

    try:
        response = _session().get(
            direct_url,
            headers={"Referer": download_page_url},
            stream=True,
            timeout=TIMEOUT_SECONDS,
        )
    except Exception as error:
        raise ApkMirrorError(f"download request failed: {error}") from error
    _check(response, direct_url, html=False)

    filename = _advertised_filename(response.headers.get("Content-Disposition"), direct_url)
    filename_match = FILENAME_RE.match(filename)
    if filename_match is None or filename_match.group(1) != expected_version:
        raise ApkMirrorError(
            f"file name {filename!r} does not give version {expected_version} and a versionCode"
        )

    part = out.with_name(out.name + ".part")
    if part.exists():
        raise ApkMirrorError(f"{part} already exists; remove it before rerunning")
    out.parent.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256()
    size = 0
    with part.open("xb") as file:
        for chunk in response.iter_content(CHUNK_BYTES):
            if chunk:
                file.write(chunk)
                digest.update(chunk)
                size += len(chunk)
    check_bundle(part)
    part.rename(out)
    return {
        "bytes": size,
        "sha256": digest.hexdigest(),
        "filename": filename,
        "filename_version": filename_match.group(1),
        "filename_version_code": int(filename_match.group(2)),
    }


def _advertised_filename(disposition: str | None, url: str) -> str:
    if disposition:
        match = DISPOSITION_RE.search(disposition)
        if match:
            return match.group(1)
    return unquote(urlparse(url).path.rsplit("/", 1)[-1])


def check_bundle(path: Path) -> None:
    """Raise unless the file is a zip with a base.apk. ARM64 is checked on the merged APK."""
    size = path.stat().st_size
    if size < MIN_BUNDLE_BYTES:
        raise ApkMirrorError(f"{path.name} is {size} bytes, too small to be an APKM bundle")
    if not zipfile.is_zipfile(path):
        raise ApkMirrorError(f"{path.name} is not a zip archive")
    with zipfile.ZipFile(path) as bundle:
        if "base.apk" not in bundle.namelist():
            raise ApkMirrorError(f"{path.name} has no base.apk")
