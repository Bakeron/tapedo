"""Stamps the demo's script, style and picture links with a short hash of each file (`tapedo.js?v=1a2b3c4d5e`).

The host keeps .js, .css and pictures in the browser for hours, and Chrome's reload takes them from there - after
a demo update visitors kept the old code (author 21.11.2026). The pages themselves are always re-checked, so a new
hash in their links fetches the new file. Run after changing the demo (tools/demo_strings.py runs it too).
"""

import hashlib
import re
import sys
from pathlib import Path

DEMO = Path(__file__).resolve().parent.parent / "demo"

# Page -> pattern of the links to stamp (the file path is group 2, relative to the page).
PAGES = {
    DEMO / "index.html": r'((?:src|href)=")((?:strings|notes|tapedo)\.(?:js|css))(?:\?v=[0-9a-f]+)?(")',
    DEMO / "instrukcja" / "index.html": r'(src=")(img/[\w.-]+\.webp)(?:\?v=[0-9a-f]+)?(")',
}


def stamp(page: Path, pattern: str) -> int:
    text = page.read_text(encoding="utf-8")
    count = 0

    def replace(m: re.Match) -> str:
        nonlocal count
        count += 1
        digest = hashlib.sha256((page.parent / m.group(2)).read_bytes()).hexdigest()[:10]
        return f"{m.group(1)}{m.group(2)}?v={digest}{m.group(3)}"

    page.write_text(re.sub(pattern, replace, text), encoding="utf-8", newline="")
    return count


def main() -> int:
    for page, pattern in PAGES.items():
        print(f"{page.relative_to(DEMO)}: {stamp(page, pattern)} links stamped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
