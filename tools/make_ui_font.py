"""将已有内置宋体离线转换为 Compose 字体，保留完整 GB2312 汉字覆盖。"""

import argparse
import hashlib
import json
from pathlib import Path

from fontTools.ttLib import TTFont


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=root / "app/src/main/res/font/mirad_ui_serif.otf")
    args = parser.parse_args()
    source = root / "app/src/main/assets/fonts/noto-serif-sc-regular.woff2"
    font = TTFont(source, recalcTimestamp=False)
    hanzi = set()
    for first in range(0xB0, 0xF8):
        for second in range(0xA1, 0xFF):
            try:
                hanzi.add(ord(bytes((first, second)).decode("gb2312")))
            except UnicodeDecodeError:
                # GB2312 字区内有未分配码位，不属于待覆盖字符。
                continue
    cmap = font.getBestCmap()
    missing = sorted(hanzi - cmap.keys())
    if len(hanzi) != 6763 or missing:
        raise ValueError(f"GB2312 coverage failed: {len(hanzi)} characters, missing {missing[:12]}")
    if "CFF " not in font or "fvar" in font:
        raise ValueError("Expected the bundled static CFF serif font")
    font.flavor = None
    args.output.parent.mkdir(parents=True, exist_ok=True)
    font.save(args.output)
    converted = TTFont(args.output)
    if converted.getBestCmap() != cmap:
        raise ValueError("Conversion changed the character map")
    print(json.dumps({
        "source": str(source), "output": str(args.output),
        "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "output_sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
        "bytes": args.output.stat().st_size, "unicode_mappings": len(cmap),
        "gb2312_hanzi": len(hanzi), "units_per_em": font["head"].unitsPerEm,
        "ascent": font["hhea"].ascent, "descent": font["hhea"].descent,
        "family": font["name"].getDebugName(1), "version": font["name"].getDebugName(5),
    }, indent=2))


if __name__ == "__main__":
    main()
