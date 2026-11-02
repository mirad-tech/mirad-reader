"""执行生产 SearchFtsDao SQL 的宿主 SQLite 契约检查；不代替 Android Room。

折叠值由 SearchCacheContractTest 对生产 normalizeCase 另行断言。
这里使用明确的期望折叠值，检查真实 SQL 候选、LIKE 转义和原文保留。
"""
import json
import pathlib
import re
import sqlite3
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
DAO_SOURCE = ROOT / "app/src/main/java/com/mirad/reader/data/db/dao/SearchFtsDao.kt"
SCHEMA = ROOT / "app/schemas/com.mirad.reader.data.db.AppDatabase/3.json"


class SearchSqlContractTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))["database"]
        entity = next(row for row in schema["entities"] if row["tableName"] == "chapters_fts")
        self.db.execute(entity["createSql"].replace("${TABLE_NAME}", "chapters_fts"))
        source = DAO_SOURCE.read_text(encoding="utf-8")
        self.query = re.search(r'"""\s*(SELECT rowid,.*?LIMIT :limit)\s*"""', source, re.S).group(1)

    def insert(self, original, folded, book_id=1, index=0):
        self.db.execute(
            "INSERT INTO chapters_fts(bookId,chapterIndex,chapterTitle,content,contentLower) VALUES(?,?,?,?,?)",
            (book_id, index, "合成章节", original, folded),
        )

    def search(self, escaped_query, case_sensitive=False):
        return self.db.execute(self.query, {
            "bookId": 1, "escapedQuery": escaped_query,
            "caseSensitive": int(case_sensitive), "limit": 500,
        }).fetchall()

    def test_folded_unicode_candidates_keep_the_original_text(self):
        for original, folded in [("ς", "σ"), ("ı", "i"), ("ſ", "s"), ("İ", "i"), ("K", "k"), ("𐐀", "𐐨")]:
            with self.subTest(original=original):
                self.db.execute("DELETE FROM chapters_fts")
                self.insert(original, folded)
                rows = self.search(folded)
                self.assertEqual(1, len(rows))
                self.assertEqual(original, rows[0][4], "候选过滤不可改写原文和UTF-16定位来源")

    def test_percent_underscore_and_backslash_are_literal_queries(self):
        cases = [
            ("progress 50%", "progress 500", r"50\%"),
            ("has _blank", "has Xblank", r"\_blank"),
            (r"path a\b", "path ab", r"a\\b"),
        ]
        for match, miss, escaped in cases:
            with self.subTest(escaped=escaped):
                self.db.execute("DELETE FROM chapters_fts")
                self.insert(match, match, index=0)
                self.insert(miss, miss, index=1)
                self.assertEqual([match], [row[4] for row in self.search(escaped)])

    def test_other_books_are_excluded_and_sensitive_positive_is_retained(self):
        self.insert("Σ target", "σ target", book_id=1)
        self.insert("Σ target", "σ target", book_id=2)
        self.assertEqual(1, len(self.search("Σ", case_sensitive=True)))
        self.assertEqual(1, len(self.search("σ")))


if __name__ == "__main__":
    unittest.main()
