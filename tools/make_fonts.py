"""下载开源中文字体并做字形子集化，产出入内置 assets/fonts 的字体文件。

字体与许可：
  霞鹜文楷 LXGW WenKai      —— OFL 1.1，https://github.com/lxgw/LxgwWenKai
  Noto Serif SC（思源宋体） —— OFL 1.1，https://github.com/notofonts/noto-cjk

只保留 GB2312 常用字（一级 + 二级共 6763 字）+ ASCII + 中文标点 + 常用符号，
既能覆盖绝大多数小说，又能把单款字体从 20MB+ 压到几 MB。

用法：python tools/make_fonts.py [--keep-temp]
"""

import os
import shutil
import sys
import tempfile
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
ASSETS = os.path.join(PROJECT, "app", "src", "main", "assets", "fonts")
TEMP = os.path.join(PROJECT, "build", "font-work")

LXGW_URL = "https://github.com/lxgw/LxgwWenKai/releases/download/v1.522/LXGWWenKai-Regular.ttf"
NOTO_ZIP_URL = "https://github.com/notofonts/noto-cjk/releases/download/Serif2.003/14_NotoSerifSC.zip"


def validate_download(path, target):
    """校验完整归档/字体，旧实现遗留的非空半文件不能被当作缓存。"""
    is_archive = os.fspath(target).lower().endswith(".zip")
    if not is_archive:
        from fontTools.ttLib import TTFont
    try:
        if is_archive:
            with zipfile.ZipFile(path) as archive:
                if archive.testzip() is not None:
                    raise ValueError("压缩包校验失败")
        else:
            with TTFont(path, lazy=False, checkChecksums=2) as font:
                font.ensureDecompiled()
    except Exception as error:
        raise ValueError("下载文件不完整或格式无效：%s" % os.path.basename(target)) from error


def download(url, target):
    if os.path.isfile(target):
        try:
            validate_download(target, target)
        except ValueError:
            pass
        else:
            print("  已存在，跳过下载：%s" % os.path.basename(target))
            return target
    directory = os.path.dirname(os.path.abspath(target))
    os.makedirs(directory, exist_ok=True)
    print("  下载 %s ..." % url.split("/")[-1])
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=directory, prefix=os.path.basename(target) + ".", suffix=".part", delete=False) as out:
            temporary = out.name
            with urllib.request.urlopen(url, timeout=180) as resp:
                shutil.copyfileobj(resp, out)
        validate_download(temporary, target)
        os.replace(temporary, target)
    finally:
        # 唯一临时路径由本次调用创建；失败不覆盖最终缓存，也不清理其他下载的文件。
        if temporary is not None and os.path.exists(temporary):
            os.remove(temporary)
    print("  完成 %.1f MB" % (os.path.getsize(target) / 1048576.0))
    return target


def gb2312_chars():
    """GB2312 全字符集（含一级/二级汉字与符号区）。"""
    chars = set()
    for high in range(0xA1, 0xF8):
        for low in range(0xA1, 0xFF):
            try:
                chars.add(bytes([high, low]).decode("gb2312"))
            except UnicodeDecodeError:
                continue
    return chars


def extra_chars():
    """ASCII、中文标点、常用符号与排版字符（小说里会出现的那些）。"""
    chars = set(chr(c) for c in range(0x20, 0x7F))
    chars.update("　、。〃々〈〉《》「」『』【】〔〕〖〗〝〞〜…—～‖·‐‑‒–―‘’‛“”„‟†‡•‥…‰′″※℃№")
    chars.update("！＂＃＄％＆＇（）＊＋，－．／０１２３４５６７８９：；＜＝＞？＠［＼］＾＿｀｛｜｝～")
    chars.update("０１２３４５６７８９ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ")
    chars.update("←↑→↓↔↕⇒⇔★☆○●◎◇◆□■△▲▽▼※§¶©®™°±×÷≈≠≤≥∞∴∵")
    chars.update("①②③④⑤⑥⑦⑧⑨⑩⑴⑵⑶⑷⑸⑹⑺⑻⑼⑽")
    chars.update("āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜüńňǹ")
    chars.update("€£¥$¢")
    return chars


def write_charset(path):
    """写成纯 ASCII 的 U+XXXX 列表——避免 fontTools 用系统默认编码读取时报错。"""
    chars = sorted(gb2312_chars() | extra_chars() | {chr(0xFFFD), "\u200b", "\ufeff"})
    codepoints = ",".join("U+%04X" % ord(c) for c in chars)
    with open(path, "w", encoding="ascii", newline="\n") as handle:
        handle.write(codepoints)
    print("  字符集：%d 个码位" % len(chars))
    return len(chars)


def subset(source, charset_file, target, flavor=None):
    from fontTools import subset as ft_subset

    args = [
        source,
        "--unicodes-file=%s" % charset_file,
        "--output-file=%s" % target,
        "--layout-features=*",
        "--glyph-names",
        "--symbol-cmap",
        "--legacy-cmap",
        "--notdef-glyph",
        "--notdef-outline",
        "--recommended-glyphs",
        "--name-IDs=*",
        "--name-legacy",
        "--name-languages=*",
        "--drop-tables+=DSIG",
    ]
    if flavor == "woff2":
        args.append("--flavor=woff2")
    ft_subset.main(args)
    print("  → %s  %.2f MB" % (os.path.basename(target), os.path.getsize(target) / 1048576.0))
    return target


def find_in_zip(zip_path, keywords):
    with zipfile.ZipFile(zip_path) as archive:
        for name in archive.namelist():
            lowered = name.lower()
            if all(k.lower() in lowered for k in keywords) and lowered.endswith((".otf", ".ttf")):
                return name
    return None


def main():
    keep_temp = "--keep-temp" in sys.argv
    os.makedirs(ASSETS, exist_ok=True)
    os.makedirs(TEMP, exist_ok=True)
    charset_file = os.path.join(TEMP, "charset.txt")
    print("[1/4] 生成字符集")
    write_charset(charset_file)

    print("[2/4] 霞鹜文楷（楷体）")
    kai_ttf = download(LXGW_URL, os.path.join(TEMP, "LXGWWenKai-Regular.ttf"))
    subset(kai_ttf, charset_file, os.path.join(ASSETS, "lxgw-wenkai-regular.woff2"), "woff2")

    print("[3/4] Noto Serif SC（宋体）")
    noto_zip = download(NOTO_ZIP_URL, os.path.join(TEMP, "NotoSerifSC.zip"))
    entry = find_in_zip(noto_zip, ["NotoSerifSC", "Regular"])
    if entry is None:
        entry = find_in_zip(noto_zip, ["Regular"])
    if entry is None:
        print("  压缩包内没找到 Regular 字重，包内容示例：")
        with zipfile.ZipFile(noto_zip) as archive:
            for name in archive.namelist()[:20]:
                print("   ", name)
        sys.exit(1)
    print("  取出 %s" % entry)
    extracted = os.path.join(TEMP, os.path.basename(entry))
    if not os.path.exists(extracted):
        with zipfile.ZipFile(noto_zip) as archive:
            with archive.open(entry) as src, open(extracted, "wb") as dst:
                shutil.copyfileobj(src, dst)
    subset(extracted, charset_file, os.path.join(ASSETS, "noto-serif-sc-regular.woff2"), "woff2")

    print("[4/4] 产物")
    for name in sorted(os.listdir(ASSETS)):
        path = os.path.join(ASSETS, name)
        print("  %-28s %8.2f MB" % (name, os.path.getsize(path) / 1048576.0))

    if not keep_temp:
        shutil.rmtree(TEMP, ignore_errors=True)
        print("  已清理临时文件（加 --keep-temp 可保留）")


if __name__ == "__main__":
    main()
