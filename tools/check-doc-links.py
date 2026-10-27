"""检查纳入 Git 的 Markdown 相对文件链接；不请求外链或校验页内锚点。"""
import pathlib
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit

ROOT = pathlib.Path(__file__).resolve().parents[1]
LINK = re.compile(r'!?\[[^\]\n]*\]\((?:<([^>\n]+)>|([^\s)\n]+))(?:\s+["\'][^\n]*?["\'])?\)')
names = subprocess.check_output(
    ['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=ROOT
).decode('utf8').split('\0')
files = {name.replace('\\', '/') for name in names if name and (ROOT / name).is_file()}
checked = 0
errors = []
for name in sorted(files):
    if not name.endswith('.md'):
        continue
    fenced = False
    for number, line in enumerate((ROOT / name).read_text(encoding='utf-8-sig').splitlines(), 1):
        if line.lstrip().startswith('```'):
            fenced = not fenced
        if fenced:
            continue
        for match in LINK.finditer(line):
            destination = match.group(1) or match.group(2)
            url = urlsplit(destination)
            if url.scheme in {'http', 'https', 'mailto', 'tel', 'data'} or url.netloc or not url.path:
                continue
            checked += 1
            target = ((ROOT / name).parent / unquote(url.path)).resolve()
            if url.scheme or not target.is_relative_to(ROOT):
                errors.append(f'{name}:{number}: 仓库外路径 {destination}')
                continue
            relative = target.relative_to(ROOT).as_posix()
            included = relative in files or (
                target.is_dir() and any(item.startswith(relative.rstrip('/') + '/') for item in files)
            )
            if not included:
                errors.append(f'{name}:{number}: 文件不存在或未纳入 Git: {destination}')

for error in errors:
    print(error)
print(f'Checked {checked} relative links; {len(errors)} broken.')
sys.exit(1 if errors else 0)
