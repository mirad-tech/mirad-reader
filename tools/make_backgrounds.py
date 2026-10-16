"""生成墨读的内置阅读背景图（低对比、适合长时间阅读）。

用法：python tools/make_backgrounds.py [输出目录]
输出：600x1000 的 JPEG，单张控制在 100KB 以内。
"""

import os
import sys

import numpy as np
from PIL import Image, ImageFilter

WIDTH = 600
HEIGHT = 1000
QUALITY = 78

rng = np.random.default_rng(20260116)


def _base(rgb):
    plane = np.zeros((HEIGHT, WIDTH, 3), dtype=np.float32)
    plane[:, :, 0] = rgb[0]
    plane[:, :, 1] = rgb[1]
    plane[:, :, 2] = rgb[2]
    return plane


def _blurred_noise(sigma, size=(HEIGHT, WIDTH)):
    """低频噪声：小尺寸随机后再放大，产生自然的明暗起伏。"""
    small_h = max(4, size[0] // sigma)
    small_w = max(4, size[1] // sigma)
    noise = rng.normal(0.0, 1.0, (small_h, small_w)).astype(np.float32)
    image = Image.fromarray(noise, mode="F").resize((size[1], size[0]), Image.BICUBIC)
    return np.asarray(image, dtype=np.float32)


def _vignette(strength=0.06):
    yy, xx = np.mgrid[0:HEIGHT, 0:WIDTH]
    cx, cy = WIDTH / 2.0, HEIGHT / 2.0
    radius = np.sqrt(((xx - cx) / cx) ** 2 + ((yy - cy) / cy) ** 2)
    return 1.0 - strength * np.clip(radius - 0.35, 0.0, None) ** 1.4


def _vertical_falloff(strength=0.03):
    ramp = np.linspace(1.0 + strength, 1.0 - strength, HEIGHT, dtype=np.float32)
    return ramp[:, None]


def _save(plane, name, out_dir):
    plane = np.clip(plane, 0, 255).astype(np.uint8)
    image = Image.fromarray(plane, mode="RGB")
    path = os.path.join(out_dir, name)
    image.save(path, "JPEG", quality=QUALITY, optimize=True, progressive=True)
    return os.path.getsize(path)


def paper():
    rgb = (246, 242, 231)
    plane = _base(rgb)
    grain = _blurred_noise(2) * 1.8 + rng.normal(0, 1.1, (HEIGHT, WIDTH)).astype(np.float32)
    plane += grain[:, :, None]
    plane *= _vignette(0.04)[:, :, None] * _vertical_falloff(0.02)[:, :, None]
    return plane


def parchment():
    # 只保留极轻的深浅不均，避免出现"霉斑"
    rgb = (236, 225, 200)
    plane = _base(rgb)
    plane += (_blurred_noise(70) * 2.4)[:, :, None]
    plane += (_blurred_noise(14) * 1.2)[:, :, None]
    plane += rng.normal(0, 1.0, (HEIGHT, WIDTH))[:, :, None]
    plane *= _vignette(0.07)[:, :, None]
    return plane


def linen():
    rgb = (241, 236, 225)
    plane = _base(rgb)
    yy, xx = np.mgrid[0:HEIGHT, 0:WIDTH]
    weave = np.sin(xx * np.pi / 2.0) * 1.3 + np.sin(yy * np.pi / 2.0) * 1.3
    plane += weave[:, :, None]
    plane += (_blurred_noise(8) * 1.4)[:, :, None]
    plane += rng.normal(0, 0.9, (HEIGHT, WIDTH))[:, :, None]
    plane *= _vignette(0.045)[:, :, None]
    return plane


def green():
    rgb = (223, 236, 223)
    plane = _base(rgb)
    plane += (_blurred_noise(40) * 2.2 + _blurred_noise(9) * 1.2)[:, :, None]
    plane += rng.normal(0, 0.9, (HEIGHT, WIDTH))[:, :, None]
    plane *= _vignette(0.055)[:, :, None]
    plane[:, :, 1] += 1.2
    plane[:, :, 0] -= 0.8
    plane[:, :, 2] -= 0.8
    return plane


def graph():
    rgb = (249, 247, 242)
    plane = _base(rgb)
    yy, xx = np.mgrid[0:HEIGHT, 0:WIDTH]
    step = 24.0
    grid = ((xx % step < 1.0) | (yy % step < 1.0)).astype(np.float32) * 3.4
    major = ((xx % (step * 4) < 1.4) | (yy % (step * 4) < 1.4)).astype(np.float32) * 3.0
    plane -= (grid + major)[:, :, None]
    plane += (_blurred_noise(3) * 1.2)[:, :, None]
    plane *= _vignette(0.035)[:, :, None]
    return plane


def night():
    rgb = (26, 28, 30)
    plane = _base(rgb)
    plane += (_blurred_noise(30) * 2.2 + _blurred_noise(120) * 1.8)[:, :, None]
    # 顶部一点点柔光，避免纯黑压抑
    glow = np.clip(np.linspace(6.0, 0.0, HEIGHT, dtype=np.float32), 0, None)
    plane += glow[:, None, None]
    plane += rng.normal(0, 0.6, (HEIGHT, WIDTH))[:, :, None]
    return plane


def dawn():
    top = np.array([252, 247, 238], dtype=np.float32)
    bottom = np.array([238, 227, 210], dtype=np.float32)
    ramp = np.linspace(0.0, 1.0, HEIGHT, dtype=np.float32)[:, None, None]
    plane = top[None, None, :] * (1 - ramp) + bottom[None, None, :] * ramp
    plane = np.repeat(plane, WIDTH, axis=1)
    plane += (_blurred_noise(60) * 1.6)[:, :, None]
    return plane


BACKGROUNDS = {
    "paper.jpg": paper,
    "parchment.jpg": parchment,
    "linen.jpg": linen,
    "green.jpg": green,
    "graph.jpg": graph,
    "night.jpg": night,
    "dawn.jpg": dawn,
}


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join("app", "src", "main", "assets", "backgrounds")
    os.makedirs(out, exist_ok=True)
    total = 0
    for name, builder in BACKGROUNDS.items():
        size = _save(builder(), name, out)
        total += size
        print("%-16s %6.1f KB" % (name, size / 1024.0))
    print("total %.1f KB" % (total / 1024.0))


if __name__ == "__main__":
    main()
