"""仅使用合成响应与临时目录验证下载缓存，不联网、不覆盖 assets。

使用已安装字体工具原有依赖 fontTools 的 Python 执行 unittest discover。
"""
import importlib.util
import io
import pathlib
import tempfile
import unittest
import zipfile
from unittest import mock


SPEC = importlib.util.spec_from_file_location("mirad_make_fonts", pathlib.Path(__file__).resolve().parents[1] / "make_fonts.py")
MAKE_FONTS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MAKE_FONTS)


def archive_bytes():
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("NotoSerifSC-Regular.otf", b"synthetic font payload" * 100)
    return output.getvalue()


def font_bytes():
    from fontTools.fontBuilder import FontBuilder
    from fontTools.pens.ttGlyphPen import TTGlyphPen

    builder = FontBuilder(1000, isTTF=True)
    builder.setupGlyphOrder([".notdef", "A"])
    builder.setupCharacterMap({65: "A"})
    glyphs = {}
    for name in (".notdef", "A"):
        pen = TTGlyphPen(None)
        pen.moveTo((0, 0))
        pen.lineTo((250, 700))
        pen.lineTo((500, 0))
        pen.closePath()
        glyphs[name] = pen.glyph()
    builder.setupGlyf(glyphs)
    builder.setupHorizontalMetrics({".notdef": (500, 0), "A": (500, 0)})
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupNameTable({"familyName": "Mirad Synthetic Fixture", "styleName": "Regular"})
    builder.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    builder.setupPost()
    builder.setupMaxp()
    output = io.BytesIO()
    builder.save(output)
    return output.getvalue()


class InterruptedResponse(io.BytesIO):
    def __init__(self, value):
        super().__init__(value)
        self.delivered = False

    def read(self, size=-1):
        if self.delivered:
            raise OSError("controlled download interruption")
        self.delivered = True
        return super().read(17)


class FontDownloadContractTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="mirad-font-download-")
        self.addCleanup(self.directory.cleanup)
        self.target = pathlib.Path(self.directory.name) / "NotoSerifSC.zip"
        self.complete = archive_bytes()
        self.url = "https://fixture.invalid/font.zip"

    def test_interruption_does_not_publish_a_partial_final_cache(self):
        with mock.patch.object(MAKE_FONTS.urllib.request, "urlopen", return_value=InterruptedResponse(self.complete)):
            with self.assertRaises(OSError):
                MAKE_FONTS.download(self.url, str(self.target))
        self.assertFalse(self.target.exists())
        self.assertEqual([], list(self.target.parent.iterdir()), "只能清理本次临时下载，且不得留下可复用半文件")

    def test_retry_after_interruption_requests_and_publishes_the_complete_file(self):
        with mock.patch.object(MAKE_FONTS.urllib.request, "urlopen", side_effect=[InterruptedResponse(self.complete), io.BytesIO(self.complete)]) as request:
            with self.assertRaises(OSError):
                MAKE_FONTS.download(self.url, str(self.target))
            self.assertEqual(str(self.target), MAKE_FONTS.download(self.url, str(self.target)))
        self.assertEqual(2, request.call_count)
        self.assertEqual(self.complete, self.target.read_bytes())
        self.assertEqual([self.target], list(self.target.parent.iterdir()))

    def test_legacy_nonempty_partial_cache_is_downloaded_again(self):
        self.target.write_bytes(self.complete[:17])
        with mock.patch.object(MAKE_FONTS.urllib.request, "urlopen", return_value=io.BytesIO(self.complete)) as request:
            MAKE_FONTS.download(self.url, str(self.target))
        request.assert_called_once()
        self.assertEqual(self.complete, self.target.read_bytes())

    def test_complete_cache_is_reused_without_request_or_overwrite(self):
        self.target.write_bytes(self.complete)
        with mock.patch.object(MAKE_FONTS.urllib.request, "urlopen", side_effect=OSError("must not request")) as request:
            self.assertEqual(str(self.target), MAKE_FONTS.download(self.url, str(self.target)))
        request.assert_not_called()
        self.assertEqual(self.complete, self.target.read_bytes())

    def test_invalid_completed_response_is_not_published(self):
        with mock.patch.object(MAKE_FONTS.urllib.request, "urlopen", return_value=io.BytesIO(b"HTTP error body, not a zip")):
            with self.assertRaises(ValueError):
                MAKE_FONTS.download(self.url, str(self.target))
        self.assertFalse(self.target.exists())
        self.assertEqual([], list(self.target.parent.iterdir()))


class TrueTypeDownloadContractTest(FontDownloadContractTest):
    def setUp(self):
        super().setUp()
        self.target = pathlib.Path(self.directory.name) / "SyntheticFixture.ttf"
        self.complete = font_bytes()
        self.url = "https://fixture.invalid/font.ttf"


if __name__ == "__main__":
    unittest.main()
