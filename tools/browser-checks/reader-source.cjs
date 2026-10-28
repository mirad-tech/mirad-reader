const fs = require('node:fs');
const path = require('node:path');

// 与 Gradle 使用同一入口清单和拼接顺序；浏览器测试不依赖 Android SDK。
module.exports = function readerSource() {
  const directory = path.resolve(__dirname, '../../app/src/main/reader');
  const read = name => fs.readFileSync(path.join(directory, name), 'utf8').replace(/\r\n/g, '\n');
  return read('reader.js').replace(/^    \/\/ @include (\S+)\n/gm, (_, name) => read(name));
};
