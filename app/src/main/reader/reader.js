/*
 * 墨读阅读引擎（注入到 WebView 的脚本）。
 *
 * 职责：
 *  - 文本模型：把 DOM 抽成稳定的字符流，供进度定位、划线、朗读、搜索共用
 *  - 分页：CSS 多列 + 视口尺寸计算页数与页起点
 *  - 翻页/滚动、偏移定位
 *  - 标注渲染：划线 / 搜索命中 / TTS 当前句
 *  - 通过 MiradBridge 把状态与选区回报给原生层
 */
(function () {
    'use strict';

    // @include runtime.js

    // @include windowing.js
    // @include layout.js

    // @include highlights.js

    // @include scroll-signals.js

    // @include chapters.js
    // @include selection-anchors.js

    // @include api.js
})();
