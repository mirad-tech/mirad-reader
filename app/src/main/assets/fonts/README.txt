把阅读字体放在这个目录即可内置进 APK（文件名去掉扩展名就是设置里显示的名字）。

支持格式：.ttf / .otf / .ttc / .otc / .woff / .woff2
命名建议：用中文可读名称，例如  霞鹜文楷-Regular.ttf、思源宋体-Regular.otf

注意：中文字体文件通常 10–20MB/个，内置会明显增大 APK 体积。
如果不希望增大安装包，可以不在本目录放字体，改用阅读设置里的「导入字体文件」，
用户自己选择 .ttf/.otf 即可（文件会复制到应用私有目录）。

2026-10-30 新增内置字体（两种外观模式共用同一字体列表）：

1. mirad-reading-sans.woff2，显示名「Noto 黑体」
   来源：Google Fonts 官方 Noto Sans SC，提交 a85815a42757630ce188fdad368c2dfc444d4773
   https://github.com/google/fonts/tree/a85815a42757630ce188fdad368c2dfc444d4773/ofl/notosanssc
   原文件：NotoSansSC[wght].ttf；字重固定 400。
   SIL OFL 1.1，完整许可及版权声明：licenses/noto-sans-sc-OFL.txt。
   使用 FontTools 4.68.0 固定字重并子集化；保留 GB2312 全部可解码字符，另含
   U+0020–00FF、U+2000–206F、U+3000–303F、U+FF00–FFEF 中原字体支持的字符。
   实际含 7812 个 Unicode 映射，其中 CJK 基本区汉字 6763 个。
   为避免保留名称冲突，派生字体内部族名改为 Mirad Reading Sans；版权和许可记录保留。
   缺失字形由既有 CSS 中文系统字体栈回退，不替换原默认字体。

2. zhuque-fangsong-regular.woff2，显示名「朱雀仿宋」
   来源：璇玑造字官方 v0.212（预览测试版）
   https://github.com/TrionesType/zhuque/releases/tag/v0.212
   https://github.com/TrionesType/zhuque/releases/download/v0.212/ZhuqueFangsong-v0.212.zip
   下载 ZIP SHA256：bb8b661a7643d2296a72d9d10530a00949419c4e527fb61783f73c2ba1a8c062
   SIL OFL 1.1，完整许可及版权声明：licenses/zhuque-fangsong-OFL.txt。
   西文与符号源自 Alegreya，附其上游许可：licenses/alegreya-OFL.txt。
   仅将官方 TTF 用 FontTools 4.68.0 转为 WOFF2 压缩；不做字形、度量、名字或子集修改。
   保留全部 12882 个 Unicode 映射，其中 CJK 基本区汉字 11009 个。
   原始项目仍标为测试版；不将该字体设为默认。

转换方式：FontTools varLib.instancer.instantiateVariableFont(font, {"wght": 400})
固定 Noto 字重，subset.Subsetter 按上述字符集合裁切，保留全部 name/语言及 layout 特性；
随后 TTFont.flavor = "woff2"，save。朱雀仿宋只执行最后的 WOFF2 保存。
字体缺字继续走系统回退；原有文楷、宋体及 DEFAULT_CUSTOM_FONT 均保持不变。
