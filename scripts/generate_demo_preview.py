#!/usr/bin/env python3
"""Generate the repository's deterministic 30-second UI workflow preview."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs" / "assets" / "demo-preview.gif"
WIDTH, HEIGHT = 720, 1280
FRAME_DURATION_MS = 2_000


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    windows = Path("C:/Windows/Fonts")
    candidates = [
        windows / ("msyhbd.ttc" if bold else "msyh.ttc"),
        windows / ("seguisb.ttf" if bold else "segoeui.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default(size=size)


FONTS = {
    "title": font(34, True),
    "heading": font(25, True),
    "body": font(20),
    "small": font(16),
    "tiny": font(13),
}


def rounded(draw: ImageDraw.ImageDraw, box, fill, outline=None, radius=18, width=2):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def text(draw, xy, value, style="body", fill="#E5E7EB", anchor=None):
    draw.text(xy, value, font=FONTS[style], fill=fill, anchor=anchor)


def base_frame(step: int) -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGB", (WIDTH, HEIGHT), "#070B14")
    draw = ImageDraw.Draw(image)
    rounded(draw, (24, 22, WIDTH - 24, 86), "#111827", "#334155", 18)
    text(draw, (44, 40), "ScreenTranslation · Experimental workflow preview", "small", "#BFDBFE")
    text(draw, (WIDTH - 44, 40), f"{(step + 1) * 2:02d}s / 30s", "small", "#94A3B8", "ra")
    text(
        draw,
        (WIDTH // 2, HEIGHT - 26),
        "Generated from repository UI · not a physical-device recording",
        "tiny",
        "#94A3B8",
        "ms",
    )
    return image, draw


def phone(draw: ImageDraw.ImageDraw):
    rounded(draw, (58, 112, WIDTH - 58, HEIGHT - 64), "#0F172A", "#475569", 36, 4)
    rounded(draw, (274, 126, 446, 144), "#020617", None, 9)


def settings_screen(step: int) -> Image.Image:
    image, draw = base_frame(step)
    phone(draw)
    text(draw, (92, 176), "实时识屏翻译", "title", "#F8FAFC")
    text(draw, (92, 226), "Android 16 · HyperOS target", "small", "#94A3B8")
    text(draw, (92, 286), "翻译设置", "heading", "#60A5FA")
    labels = [("屏幕原文语言", "英语"), ("翻译目标语言", "简体中文")]
    y = 336
    for label, value in labels:
        text(draw, (92, y), label, "small", "#CBD5E1")
        rounded(draw, (92, y + 28, WIDTH - 92, y + 82), "#172033", "#334155", 12)
        text(draw, (112, y + 44), value, "body", "#F8FAFC")
        y += 112
    text(draw, (92, y), "识别模式", "small", "#CBD5E1")
    selected_full = step >= 3
    rounded(draw, (92, y + 28, WIDTH - 92, y + 88), "#172033", "#3B82F6" if selected_full else "#334155", 12, 3)
    text(
        draw,
        (112, y + 47),
        "全屏增量覆盖（Experimental）" if selected_full else "框选区域（默认）",
        "body",
        "#A7F3D0" if selected_full else "#F8FAFC",
    )
    hint = "只重识别变化分块，译文覆盖在原文上方" if selected_full else "授权后拖动选择区域"
    text(draw, (92, y + 100), hint, "small", "#94A3B8")
    rounded(draw, (92, 820, WIDTH - 92, 888), "#2563EB", None, 16)
    text(draw, (WIDTH // 2, 854), "开始识屏翻译", "heading", "#FFFFFF", "mm")
    text(draw, (WIDTH // 2, 946), "1. 选择模式   2. 准备模型   3. 主动授权", "small", "#CBD5E1", "mm")
    return image


def permission_screen(step: int) -> Image.Image:
    image, draw = base_frame(step)
    phone(draw)
    rounded(draw, (96, 338, WIDTH - 96, 770), "#F8FAFC", None, 24)
    text(draw, (124, 382), "开始录制或投射？", "heading", "#111827")
    text(draw, (124, 436), "ScreenTranslation 将读取默认显示器。", "body", "#334155")
    text(draw, (124, 482), "Android 每次新会话都会要求用户确认。", "small", "#64748B")
    rounded(draw, (124, 600, WIDTH - 124, 664), "#2563EB", None, 16)
    text(draw, (WIDTH // 2, 632), "立即开始", "heading", "#FFFFFF", "mm")
    rounded(draw, (124, 682, WIDTH - 124, 738), "#E2E8F0", None, 14)
    text(draw, (WIDTH // 2, 710), "取消", "body", "#334155", "mm")
    return image


def target_screen(step: int) -> Image.Image:
    image, draw = base_frame(step)
    phone(draw)
    text(draw, (92, 178), "Reading view", "title", "#F8FAFC")
    text(draw, (92, 232), "A changing page with English UI and subtitles", "small", "#94A3B8")
    rounded(draw, (88, 286, WIDTH - 88, 486), "#172033", "#334155", 18)
    text(draw, (112, 320), "Account summary", "heading", "#E2E8F0")
    text(draw, (112, 370), "Plan v0.3.1 · Total ￥1,299.00", "body", "#F8FAFC")
    text(draw, (112, 414), "Renewal date: 2026-08-03", "body", "#F8FAFC")
    text(draw, (112, 456), "https://example.com/billing", "small", "#93C5FD")
    rounded(draw, (88, 560, WIDTH - 88, 744), "#111827", "#334155", 18)
    subtitle = (
        "The translation engine only processes changed blocks."
        if step < 13
        else "Only this subtitle changed, so one block is translated again."
    )
    text(draw, (112, 612), subtitle, "body", "#F8FAFC")

    if step == 8:
        for row in range(6):
            for column in range(3):
                x0 = 58 + column * (604 // 3)
                x1 = 58 + (column + 1) * (604 // 3)
                y0 = 112 + row * (1104 // 6)
                y1 = 112 + (row + 1) * (1104 // 6)
                draw.rectangle((x0, y0, x1, y1), outline="#3B82F6", width=2)
        text(draw, (WIDTH // 2, 1080), "Dirty-tile scan · 3 × 6", "heading", "#60A5FA", "mm")

    translations = []
    if step >= 9:
        translations.append(((106, 338, 600, 374), "套餐 v0.3.1 · 合计 ￥1,299.00"))
    if step >= 10:
        translations.append(((106, 382, 516, 418), "续费日期：2026-08-03"))
    if step >= 11:
        translated_subtitle = (
            "翻译引擎只处理发生变化的文字块。"
            if step < 14
            else "只有这条字幕发生变化，因此只重新翻译一个块。"
        )
        translations.append(((106, 566, 610, 606), translated_subtitle))
    for box, value in translations:
        rounded(draw, box, "#0F172AEF", "#34D399", 8, 2)
        text(draw, (box[0] + 8, box[1] + 7), value, "small", "#D1FAE5")

    rounded(draw, (166, 154, WIDTH - 166, 214), "#111827", "#3B82F6", 16)
    status = "全屏增量识别中"
    if step == 12:
        status = "画面稳定 · 自适应采样 1000 ms"
    if step >= 13:
        status = "检测到字幕变化 · 恢复 250 ms"
    text(draw, (WIDTH // 2, 184), status, "small", "#BFDBFE", "mm")
    return image


def build_frames() -> list[Image.Image]:
    frames = []
    for step in range(15):
        if step <= 4:
            frames.append(settings_screen(step))
        elif step == 5:
            frames.append(permission_screen(step))
        else:
            frames.append(target_screen(step))
    return frames


def main() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    frames = build_frames()
    frames[0].save(
        OUTPUT,
        save_all=True,
        append_images=frames[1:],
        duration=FRAME_DURATION_MS,
        loop=0,
        optimize=True,
        disposal=2,
    )
    print(OUTPUT)


if __name__ == "__main__":
    main()
