"""生成墨读的回归测试样书：GBK 中文 TXT、UTF-8 无章节 TXT、含图片的 EPUB3。

用法：python tools/make_samples.py [输出目录]
"""

import io
import os
import struct
import sys
import zipfile
import zlib

CHAPTERS = [
    ("第一章 雪夜出城", [
        "雪下了整整一夜，官道上的车辙被填得平平整整，连马蹄印都寻不见半点痕迹。",
        "沈砚把行囊往肩上一甩，回头望了一眼城门上那盏昏黄的灯笼，没再停留。",
        "他怀里揣着半块冷透的胡饼，那是母亲昨夜塞给他的，说是路上压压饥。",
    ]),
    ("第二章 破庙逢人", [
        "走到半夜，风雪里现出一座破败的山神庙，屋顶塌了半边，神像的脑袋也不知去了哪里。",
        "庙里已经有人了。火堆烧得正旺，一个穿青衫的书生正拿着树枝拨火，抬头看了他一眼。",
        "「借个火。」沈砚说。书生笑了笑，往旁边挪了挪，让出半块干燥的地方。",
    ]),
    ("第三章 一纸旧约", [
        "书生自称姓陆，说要去南边寻一个人，寻了七年，还没寻到。",
        "沈砚没问姓名，只把胡饼掰成两半，递了一半过去。陆书生接过去，咬了一口，忽然咳嗽起来。",
        "火光把他的影子投在断墙上，长得几乎要触到屋顶。",
    ]),
    ("第四章 剑鸣于匣", [
        "天亮时分，沈砚从怀里摸出一柄没有鞘的短剑，剑身乌沉，看不出锋利与否。",
        "陆书生盯着那剑看了很久，终于开口：「这剑，我见过。」",
        "沈砚的手停在半空。风雪不知何时停了，庙外的世界静得可怕。",
    ]),
    ("第五章 各奔东西", [
        "两人在岔路口分了手。陆书生往南，沈砚往北。",
        "走出二十步，沈砚回头，陆书生也在回头，两人同时拱手，然后各自转身。",
        "这一别，又是三年。",
    ]),
    ("第六章 归来不是少年", [
        "三年后，沈砚回到城里，城门口的灯笼换了新的，卖馄饨的老翁却已经不在了。",
        "他站在自家门前，抬手敲了三下，没有人应。",
        "邻居家的妇人探出头来，说：「他们家啊，两年前就搬走了。」",
    ]),
]

INTRO = "本书为墨读阅读器回归测试样书，正文共计六章，用于验证 GBK 编码识别、章节切分与排版渲染。"


def build_novel():
    parts = ["《风雪归人》", "作者：测试作者", "", INTRO, ""]
    for index, (title, paragraphs) in enumerate(CHAPTERS):
        parts.append("")
        parts.append(title)
        for paragraph in paragraphs:
            parts.append(paragraph)
    return "\n".join(parts) + "\n"


def write_gbk_txt(path):
    text = build_novel()
    with open(path, "wb") as handle:
        handle.write(text.encode("gbk"))


def write_utf8_no_chapter_txt(path):
    lines = []
    for line in range(1, 260):
        lines.append("这是第 %d 行没有章节标记的正文，用来验证按字数兜底分节。" % line)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")


def make_png(width=320, height=200):
    """生成一张纯色渐变 PNG（不依赖第三方库）。"""
    rows = []
    for y in range(height):
        row = bytearray([0])
        for x in range(width):
            row += bytes([
                int(255 * x / width),
                int(200 * y / height),
                160,
            ])
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(tag, data):
        payload = tag + data
        return struct.pack(">I", len(data)) + payload + struct.pack(">I", zlib.crc32(payload) & 0xFFFFFFFF)

    header = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return header + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

OPF = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="zh-CN">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:mirad-test-0001</dc:identifier>
    <dc:title>星辰之下</dc:title>
    <dc:creator>测试作者</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:publisher>墨读测试出版社</dc:publisher>
    <dc:description>用于验证 EPUB 解析、目录树、原书排版与插图渲染的测试样书。</dc:description>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="css" href="style/book.css" media-type="text/css"/>
    <item id="cover" href="images/cover.png" media-type="image/png" properties="cover-image"/>
    <item id="ch1" href="text/ch01.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/ch02.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch3" href="text/ch03.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
    <itemref idref="ch3"/>
  </spine>
</package>
"""

NAV = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN">
<head><title>目录</title><link rel="stylesheet" type="text/css" href="style/book.css"/></head>
<body>
<nav epub:type="toc" id="toc">
  <h1>目录</h1>
  <ol>
    <li><a href="text/ch01.xhtml">第一章 观星台</a>
      <ol><li><a href="text/ch01.xhtml#sec1">其一 夜观天象</a></li></ol>
    </li>
    <li><a href="text/ch02.xhtml">第二章 碑文</a></li>
    <li><a href="text/ch03.xhtml">第三章 星陨</a></li>
  </ol>
</nav>
</body>
</html>
"""

BOOK_CSS = """body { font-family: serif; line-height: 1.6; }
h2 { text-align: center; }
p { text-indent: 2em; margin: 0.6em 0; }
figure { text-align: center; margin: 1em 0; }
figcaption { font-size: 0.85em; color: #666; }
.center { text-align: center; }
blockquote { border-left: 3px solid #ccc; padding-left: 1em; color: #444; }
"""

CH1 = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
<head><title>第一章 观星台</title><link rel="stylesheet" type="text/css" href="../style/book.css"/></head>
<body>
<h2 id="sec1">第一章 观星台</h2>
<p>观星台建在半山腰，青石垒成的台阶一共九百九十九级，据说是前朝一位痴迷星象的国师督造的。</p>
<p>每到无云的夜里，台顶的铜壶滴漏便会被守台人重新添水，滴水声穿过石缝，听上去像有人在低声数着数。</p>
<blockquote>「星辰有常，人心无常。」守台人常把这句话挂在嘴边。</blockquote>
<figure>
  <img src="../images/cover.png" alt="星河图"/>
  <figcaption>图一：观星台上所见的星河</figcaption>
</figure>
<p>那年秋天，一个背着旧书箱的少年沿着台阶走了整整一夜，天亮时坐在台顶，看着东方的天光，一句话也没有说。</p>
<p>守台人递给他一碗热水。<strong>少年接过碗，手指是抖的。</strong></p>
</body>
</html>
"""

CH2 = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
<head><title>第二章 碑文</title><link rel="stylesheet" type="text/css" href="../style/book.css"/></head>
<body>
<h2>第二章 碑文</h2>
<p>台后的石碑上刻着密密麻麻的小字，笔画细瘦，像是用针尖刻上去的。</p>
<p>少年一个字一个字地读，读到第七行时，忽然停了下来，回头问守台人：「这上面写的人，还活着吗？」</p>
<p>守台人没有回答，只是把铜壶里的水续满。</p>
<p>碑的背面还有一行小字：STARFIELD MARKER，据说是建台之人留下的名字。</p>
<p class="center">—— 星辰之下，皆是故人 ——</p>
</body>
</html>
"""

CH3 = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
<head><title>第三章 星陨</title><link rel="stylesheet" type="text/css" href="../style/book.css"/></head>
<body>
<h2>第三章 星陨</h2>
<p>入冬后的第一个夜里，天上有星坠落，拖着长长的尾巴划过山脊。</p>
<p>少年站在台顶，看着那道白光消失在群山中，忽然明白碑文上最后一句的意思。</p>
<p>他转身下山，没有再回头。</p>
</body>
</html>
"""


def write_epub(path):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as epub:
        # 规范要求 mimetype 第一个且不压缩
        epub.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", zipfile.ZIP_STORED)
        epub.writestr("META-INF/container.xml", CONTAINER)
        epub.writestr("OEBPS/content.opf", OPF)
        epub.writestr("OEBPS/nav.xhtml", NAV)
        epub.writestr("OEBPS/style/book.css", BOOK_CSS)
        epub.writestr("OEBPS/text/ch01.xhtml", CH1)
        epub.writestr("OEBPS/text/ch02.xhtml", CH2)
        epub.writestr("OEBPS/text/ch03.xhtml", CH3)
        epub.writestr("OEBPS/images/cover.png", make_png())


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join("verification", "samples")
    os.makedirs(out, exist_ok=True)
    write_gbk_txt(os.path.join(out, "风雪归人_GBK.txt"))
    write_utf8_no_chapter_txt(os.path.join(out, "无章节样本_UTF8.txt"))
    write_epub(os.path.join(out, "星辰之下.epub"))
    for name in sorted(os.listdir(out)):
        print(name, os.path.getsize(os.path.join(out, name)), "bytes")


if __name__ == "__main__":
    main()
