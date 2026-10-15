#!/usr/bin/env python3
"""Static independent-package checks; no Android/ADB/runtime safety assertions."""

import argparse
import hashlib
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

PACKAGE = Path(__file__).resolve().parent
ARCHIVE = PACKAGE.parent / "rear-activity-host.zip"
FILES = ("manifest.xml", "README.md", "validate_theme.py")
COMPONENT = "com.android.settings/com.android.settings.Settings"
BOUNDS_EXPRESSION = "'0,0,'+#demoRight+','+#demoBottom"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_xml(data):
    root = ET.fromstring(data)
    require(root.tag == "Screen", "package root must be Screen")
    require(root.get("screenWidth") == "1080" and root.get("version") == "2", "MAML root configuration mismatch")
    require(root.find("./VariableBinders/RearDisplayBinder[@name='display']") is not None, "missing display geometry binder")
    variables = {node.get("name"): node for node in root.findall("Var")}
    for name in ("displayReady", "scale", "safeX", "safeY", "safeW", "safeH", "cardH", "demoRight", "demoBottom"):
        require(name in variables, f"missing layout variable {name}")
    require(variables["demoRight"].get("expression") == "#safeW*0.4", "demo right expression changed")
    require(variables["demoBottom"].get("expression") == "#cardH*0.8", "demo bottom expression changed")
    hosts = root.findall(".//ActivityHost")
    require(len(hosts) == 2, "expected exactly two ActivityHost cards")
    allowed = {"name", "x", "y", "w", "h", "component", "dpi", "boundsExp"}
    for node in hosts:
        require(set(node.attrib) <= allowed, "unknown demo ActivityHost attribute")
        require(node.get("component") == COMPONENT, "replace both components consistently and update the validator")
        require(all(node.get(key) for key in ("name", "x", "y", "w", "h")), "missing carrier geometry")
        require(node.get("w") == "#safeW" and node.get("h") == "#cardH", "unexpected carrier dimensions")
        dpi = int(node.get("dpi", "0"))
        require(dpi == 0 or 120 <= dpi <= 960, "invalid dpi")
        require(re.fullmatch(r"[A-Za-z0-9_$.]+/[A-Za-z0-9_$.]+", node.get("component", "")) is not None, "component must be explicit")
    first, second = hosts
    require(first.get("name") == "defaultHost" and first.get("dpi") == "0", "first card must use default density")
    require("bounds" not in first.attrib and "boundsExp" not in first.attrib, "first card must omit bounds")
    require(second.get("name") == "boundedHost" and second.get("dpi") == "320", "second card must use fixed density")
    require(second.get("boundsExp") == BOUNDS_EXPRESSION, "second card must use the supported string expression")
    require(not any(node.tag.endswith("Command") for node in root.iter()), "demo must not contain automatic launch commands")
    require(not root.findall(".//Button"), "MAML overlays must not cover native launch or navigation controls")
    # Arithmetic smoke checks only, not MAML expression-engine or physical-device tests.
    for width, height, density in ((540, 960, 2.0), (1080, 1920, 3.0), (480, 800, 1.0)):
        logical_per_pixel = 1080 / width
        safe_width = 1080 - 16 * logical_per_pixel
        safe_height = height * logical_per_pixel - 16 * logical_per_pixel
        card_height = safe_height * 0.34
        right_px = safe_width * 0.4 / logical_per_pixel
        bottom_px = card_height * 0.8 / logical_per_pixel
        content_width_px = safe_width / logical_per_pixel - 2 * math.ceil(24 * density)
        require(0 < right_px <= content_width_px, "nominal bounds escape content parent")
        require(0 < bottom_px <= card_height / logical_per_pixel, "nominal bounds escape card height")


def validate_sources():
    for name in FILES:
        require((PACKAGE / name).is_file(), f"missing package file: {name}")
    validate_xml((PACKAGE / "manifest.xml").read_bytes())
    readme = (PACKAGE / "README.md").read_text(encoding="utf-8")
    for term in ("exported", "逻辑单位", "24dp", "主屏", "显式", "真机", "ADB", "字符串"):
        require(term in readme, f"README missing caveat: {term}")


def build_archive():
    # Fixed timestamps and explicit members make rebuilds reproducible and prevent parent-folder nesting.
    with zipfile.ZipFile(ARCHIVE, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in FILES:
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, (PACKAGE / name).read_bytes())


def validate_archive():
    require(ARCHIVE.is_file(), "archive missing; run with --build first")
    with zipfile.ZipFile(ARCHIVE) as archive:
        require(archive.namelist() == list(FILES), "ZIP must contain exactly three root-level files")
        require(archive.testzip() is None, "ZIP CRC check failed")
        for name in FILES:
            require(archive.read(name) == (PACKAGE / name).read_bytes(), f"ZIP has stale contents: {name}")
        validate_xml(archive.read("manifest.xml"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", action="store_true", help="build the independent ZIP before checking it")
    args = parser.parse_args()
    validate_sources()
    if args.build:
        build_archive()
    validate_archive()
    digest = hashlib.sha256(ARCHIVE.read_bytes()).hexdigest()
    print("PASS: XML, two explicit-click ActivityHost cards, README caveats, nominal geometry arithmetic, ZIP root members/content/CRC")
    print(f"Archive: {ARCHIVE.name}; SHA-256: {digest}")
    print("Not verified: ROM ABI, real touch, security, lock-screen authentication, rear fullscreen or main-screen fallback. No ADB used.")


if __name__ == "__main__":
    main()
