#!/usr/bin/env python3
"""Migrate moe.rukamori.archivetune → app.hush.music and rebrand ArchiveTune symbols."""

from __future__ import annotations

import os
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

SKIP_DIR_NAMES = {
    ".git",
    ".gradle",
    "build",
    "build 2",
    "node_modules",
    ".idea",
    "schemas",  # handled separately
}

TEXT_EXTENSIONS = {
    ".kt",
    ".kts",
    ".java",
    ".xml",
    ".pro",
    ".md",
    ".properties",
    ".json",
    ".txt",
    ".gradle",
}

DIR_MOVES = [
    ("moe/rukamori/archivetune", "app/hush/music"),
    ("moe/koiverse/rukamori/betterlyrics", "app/hush/music/betterlyrics"),
    ("moe/koiverse/rukamori/shazamkit", "app/hush/music/shazamkit"),
]

# Order matters — longer / more specific tokens first.
REPLACEMENTS = [
    ("__LEGACY_ARCHIVETUNE_PACKAGE__", "moe.rukamori.archivetune"),  # restore protected
    ("moe/rukamori/archivetune", "app/hush/music"),
    ("moe/koiverse/rukamori/betterlyrics", "app/hush/music/betterlyrics"),
    ("moe/koiverse/rukamori/shazamkit", "app/hush/music/shazamkit"),
    ("moe.rukamori.archivetune", "app.hush.music"),
    ("ArchiveTuneCastOptionsProvider", "HushCastOptionsProvider"),
    ("ArchiveTuneCombinedPressable", "HushCombinedPressable"),
    ("archiveTuneCombinedPressable", "hushCombinedPressable"),
    ("ArchiveTunePressable", "HushPressable"),
    ("archiveTunePressable", "hushPressable"),
    ("rememberArchiveTuneLyricsFontFamily", "rememberHushLyricsFontFamily"),
    ("onArchiveTuneCanvasEnabledChange", "onHushCanvasEnabledChange"),
    ("archiveTuneCanvasEnabled", "hushCanvasEnabled"),
    ("ArchiveTuneCanvasKey", "HushCanvasKey"),
    ("ArchiveTuneCanvas", "HushCanvas"),
    ("ArchiveTuneMotion", "HushMotion"),
    ("ArchiveTuneDesign", "HushDesign"),
    ("ArchiveTuneTheme", "HushTheme"),
    ("player_stream_client_archivetune_extractor_desc", "player_stream_client_hush_extractor_desc"),
    ("player_stream_client_archivetune_extractor", "player_stream_client_hush_extractor"),
    ("archivetune_canvas_desc", "hush_canvas_desc"),
    ("archivetune_canvas", "hush_canvas"),
    ("archivetune_strings", "hush_strings"),
    ('android:scheme="archivetune"', 'android:scheme="hush"'),
    ("archivetune://", "hush://"),
    ("moe.rukamori.archivetune.action.", "app.hush.music.action."),
    ('rootProject.name = "ArchiveTune"', 'rootProject.name = "Hush"'),
    ("moe.rukamori.archivetune.db.InternalDatabase", "app.hush.music.db.InternalDatabase"),
]

FILE_RENAMES = [
    ("ArchiveTuneDesign.kt", "HushDesign.kt"),
    ("ArchiveTuneCastOptionsProvider.kt", "HushCastOptionsProvider.kt"),
    ("ArchiveTuneCanvas.kt", "HushCanvas.kt"),
    ("archivetune_strings.xml", "hush_strings.xml"),
]

PROTECT_BEFORE = [
    ('packageName = "moe.rukamori.archivetune"', 'packageName = "__LEGACY_ARCHIVETUNE_PACKAGE__"'),
]


def should_skip(path: Path) -> bool:
    return any(part in SKIP_DIR_NAMES for part in path.parts)


def move_kotlin_trees() -> None:
    for kotlin_root in ROOT.rglob("kotlin"):
        if should_skip(kotlin_root):
            continue
        if "src" not in kotlin_root.parts:
            continue
        for old_rel, new_rel in DIR_MOVES:
            old_path = kotlin_root / old_rel
            if not old_path.exists():
                continue
            new_path = kotlin_root / new_rel
            new_path.parent.mkdir(parents=True, exist_ok=True)
            if new_path.exists():
                for child in old_path.iterdir():
                    dest = new_path / child.name
                    if dest.exists():
                        if child.is_dir():
                            shutil.copytree(child, dest, dirs_exist_ok=True)
                            shutil.rmtree(child)
                        else:
                            shutil.move(str(child), str(dest.with_name(
                                f"{child.stem}_migrated{child.suffix}"
                            )))
                    else:
                        shutil.move(str(child), str(dest))
                if old_path.exists() and not any(old_path.iterdir()):
                    old_path.rmdir()
            else:
                shutil.move(str(old_path), str(new_path))

    # Remove empty moe/ trees under src
    for src_kotlin in ROOT.rglob("src/*/kotlin/moe"):
        if should_skip(src_kotlin):
            continue
        for path in sorted(src_kotlin.rglob("*"), reverse=True):
            if path.is_dir() and not any(path.iterdir()):
                path.rmdir()


def iter_text_files() -> list[Path]:
    files: list[Path] = []
    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        if should_skip(path):
            continue
        if path.suffix in TEXT_EXTENSIONS or path.name in {"proguard-rules.pro", "gradle.properties"}:
            files.append(path)
    return files


def apply_replacements() -> None:
    for path in iter_text_files():
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        original = text
        for old, new in PROTECT_BEFORE:
            text = text.replace(old, new)
        for old, new in REPLACEMENTS:
            text = text.replace(old, new)
        if text != original:
            path.write_text(text, encoding="utf-8")


def rename_files() -> None:
    for old_name, new_name in FILE_RENAMES:
        for path in ROOT.rglob(old_name):
            if should_skip(path):
                continue
            target = path.with_name(new_name)
            if target.exists():
                continue
            path.rename(target)


def move_schema_dir() -> None:
    old = ROOT / "app/schemas/moe.rukamori.archivetune.db.InternalDatabase"
    new = ROOT / "app/schemas/app.hush.music.db.InternalDatabase"
    if old.exists():
        new.parent.mkdir(parents=True, exist_ok=True)
        if new.exists():
            shutil.rmtree(new)
        shutil.move(str(old), str(new))


def main() -> None:
    move_kotlin_trees()
    move_schema_dir()
    apply_replacements()
    rename_files()
    print("Rename complete.")


if __name__ == "__main__":
    main()
