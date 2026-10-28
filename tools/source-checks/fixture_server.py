"""本地书源验收夹具：仅监听回环地址，控制文件决定章节失败与延迟。"""
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

COUNTS = [12, 11, 10, 9, 8, 7, 0]
TITLE = "长标题验收书：星河尽头的旅人和一段不会结束的远行"
AUTHOR = "夹具作者甲"


def sources(base):
    result = []
    for i, count in enumerate(COUNTS):
        prefix = f"{base}/s{i}"
        item = {
            "bookSourceUrl": prefix, "bookSourceName": f"验收源{i + 1}（{count or '目录失败'}）",
            "searchUrl": prefix + "/search?q={{key}}",
            "ruleSearch": {"bookList": ".book", "name": ".title@text", "author": ".author@text", "bookUrl": ".title@href"},
            "ruleBookInfo": {"name": "h1@text", "author": ".author@text", "tocUrl": ".toc@href"},
            "ruleToc": {"chapterList": "-li.chapter", "chapterName": "a@text", "chapterUrl": "a@href", "isVolume": "a@data-volume", "nextTocUrl": "a.next@href"},
            "ruleContent": {"content": "#body@html", "nextContentUrl": "a.next@href"},
        }
        if i == 4:
            item["ruleBookInfo"] = {"init": "@js:JSON.stringify({name:'" + TITLE + "',author:'" + AUTHOR + "',toc:'" + prefix + "/toc'})", "name": "$.name", "author": "$.author", "tocUrl": "$.toc"}
        if i == 5:
            item["ruleSearch"] = {"bookList": "$.books[*]", "name": "$.name", "author": "$.author", "bookUrl": "$.url"}
            item["ruleBookInfo"] = {"name": "$.name", "author": "$.author", "tocUrl": "$.toc"}
            item["ruleToc"] = {"chapterList": "-$.chapters[*]", "chapterName": "$.name", "chapterUrl": "$.url", "isVolume": "$.volume", "nextTocUrl": "$.next"}
            item["ruleContent"] = {"content": "$.content", "nextContentUrl": "$.next"}
        result.append(item)
    return result


def loop_source(base):
    """可单独导入的超时夹具，不影响正常七源。"""
    return {
        "bookSourceUrl": base + "/loop", "bookSourceName": "验收源：规则死循环",
        "searchUrl": base + "/loop/search?q={{key}}",
        "ruleSearch": {"bookList": "@js:while(true) {}", "name": "$.name", "bookUrl": "$.url"},
        "ruleToc": {"chapterList": "a", "chapterName": "text", "chapterUrl": "href"},
        "ruleContent": {"content": "text"},
    }


class Handler(BaseHTTPRequestHandler):
    def send(self, value, status=200, content_type="text/html"):
        if not isinstance(value, str):
            value = json.dumps(value, ensure_ascii=False)
            content_type = "application/json"
        data = value.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type + "; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        parsed = urlparse(self.path)
        parts = parsed.path.strip("/").split("/")
        query = parse_qs(parsed.query)
        base = f"http://127.0.0.1:{self.server.server_port}"
        if parsed.path == "/sources":
            return self.send(sources(base))
        if parsed.path == "/loop-source":
            return self.send(loop_source(base))
        if parsed.path == "/loop/search":
            return self.send("fixture")
        if len(parts) < 2 or not parts[0].startswith("s") or not parts[0][1:].isdigit():
            return self.send("missing", 404)
        i = int(parts[0][1:])
        if i >= len(COUNTS):
            return self.send("missing", 404)
        prefix = f"/s{i}"
        action = parts[1]
        if action == "search":
            control = json.loads(self.server.control.read_text(encoding="utf-8")) if self.server.control.exists() else {}
            time.sleep(float(control.get("search_delay_seconds", 0)))
            key = query.get("q", [""])[0].strip()
            matches = key.casefold() == "fixture" or key == TITLE
            books = [{"name": TITLE, "author": AUTHOR, "url": prefix + "/book"}] if matches else []
            if i == 0 and matches:
                books += [{"name": TITLE, "author": "夹具作者乙", "url": prefix + "/other"}, {"name": TITLE, "author": "", "url": prefix + "/unknown"}]
            if i == 5:
                return self.send({"books": books})
            return self.send("".join(f'<div class="book"><a class="title" href="{b["url"]}">{b["name"]}</a><span class="author">{b["author"]}</span></div>' for b in books))
        if action in ("book", "other", "unknown"):
            author = AUTHOR if action == "book" else ("夹具作者乙" if action == "other" else "")
            if i == 5:
                return self.send({"name": TITLE, "author": author, "toc": prefix + "/toc"})
            return self.send(f'<h1>{TITLE}</h1><span class="author">{author}</span><a class="toc" href="{prefix}/toc">目录</a>')
        if action == "toc":
            if i == 6:
                return self.send("catalog unavailable", 503)
            n = COUNTS[i]
            page = int(query.get("page", ["1"])[0])
            values = list(range(n, 0, -1))
            values = values[: n // 2] if page == 1 else values[n // 2 :]
            if page == 2:
                values.insert(0, n)
            rows = [{"name": f"第{x}章", "url": f"{prefix}/chapter/{x}", "volume": "0"} for x in values]
            if page == 1:
                rows.insert(0, {"name": "第一卷", "url": "", "volume": "1"})
            next_url = "?page=2" if page == 1 else ""
            if i == 5:
                return self.send({"chapters": rows, "next": next_url})
            html = "".join(f'<li class="chapter"><a href="{r["url"]}" data-volume="{r["volume"]}">{r["name"]}</a></li>' for r in rows)
            return self.send(html + (f'<a class="next" href="{next_url}">下一页</a>' if next_url else ""))
        if action == "chapter" and len(parts) == 3:
            control = json.loads(self.server.control.read_text(encoding="utf-8")) if self.server.control.exists() else {}
            time.sleep(float(control.get("delay_seconds", 0)))
            number = int(parts[2])
            if number == 3 and control.get("fail_chapter_3", True):
                return self.send("chapter unavailable", 503)
            page = int(query.get("page", ["1"])[0])
            text = f"验收正文，源{i + 1}，第{number}章，第{page}页。" * 20
            next_url = "?page=2" if number == 1 and page == 1 else ""
            if i == 5:
                return self.send({"content": text, "next": next_url})
            return self.send(f'<div id="body"><p>{text}</p></div>' + (f'<a class="next" href="{next_url}">下一页</a>' if next_url else ""))
        return self.send("missing", 404)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18972)
    parser.add_argument("--control", type=Path, required=True)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.control = args.control
    server.serve_forever()
