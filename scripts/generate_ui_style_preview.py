#!/usr/bin/env python3
"""Generate a deterministic, code-owned preview of the three Android UI styles."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs" / "assets" / "ui-style-comparison.png"
MATRIX_OUTPUT = ROOT / "docs" / "assets" / "ui-accessibility-matrix.png"
WIDTH, HEIGHT = 2400, 1500


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    candidates = [
        Path(r"C:\Windows\Fonts\msyhbd.ttc" if bold else r"C:\Windows\Fonts\msyh.ttc"),
        Path(r"C:\Windows\Fonts\segoeuib.ttf" if bold else r"C:\Windows\Fonts\segoeui.ttf"),
    ]
    for candidate in candidates:
        if candidate.is_file():
            return ImageFont.truetype(str(candidate), size=size)
    return ImageFont.load_default(size=size)


TITLE = font(48, True)
SUBTITLE = font(25)
PHONE_TITLE = font(36, True)
SECTION = font(22, True)
BODY = font(21)
SMALL = font(17)
BUTTON = font(19, True)


STYLES = [
    {
        "name": "Apple（默认候选）",
        "bg": "#F2F2F7",
        "surface": "#FFFFFF",
        "hero": "#FFFFFF",
        "primary": "#007AFF",
        "primary_container": "#E5F1FF",
        "on": "#1C1C1E",
        "muted": "#636366",
        "outline": "#E5E5EA",
        "radius": 14,
        "control_radius": 12,
        "section": "#636366",
        "segment_bg": "#E5E5EA",
        "segment_selected": "#FFFFFF",
        "segment_selected_text": "#1C1C1E",
        "monet": False,
    },
    {
        "name": "MIUIX",
        "bg": "#F5F5F7",
        "surface": "#FFFFFF",
        "hero": "#EDF5FF",
        "primary": "#3482FF",
        "primary_container": "#E9F2FF",
        "on": "#17171A",
        "muted": "#6F7380",
        "outline": "#EBEDF1",
        "radius": 32,
        "control_radius": 22,
        "monet": False,
    },
    {
        "name": "Material 3 + Monet",
        "bg": "#FBF8FF",
        "surface": "#FFFFFF",
        "hero": "#E7F6EE",
        "primary": "#386A53",
        "primary_container": "#BBF0D4",
        "on": "#191C1A",
        "muted": "#414942",
        "outline": "#DDE5DE",
        "radius": 28,
        "control_radius": 28,
        "monet": True,
    },
]


def rounded(draw: ImageDraw.ImageDraw, box, radius, fill, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def text(draw, xy, value, style, fill, anchor=None):
    draw.text(xy, value, font=style, fill=fill, anchor=anchor)


def line(draw, x1, y, x2, color):
    draw.line((x1, y, x2, y), fill=color, width=2)


def draw_segmented(draw, x, y, width, selected, spec):
    labels = ["Apple", "MIUIX", "Material 3"]
    item = width / 3
    rounded(
        draw,
        (x, y, x + width, y + 62),
        spec["control_radius"],
        spec.get("segment_bg", spec["bg"]),
        spec["outline"],
        2,
    )
    sx = x + selected * item
    rounded(
        draw,
        (sx + 3, y + 3, sx + item - 3, y + 59),
        max(4, spec["control_radius"] - 3),
        spec.get("segment_selected", spec["primary_container"]),
    )
    for index, label in enumerate(labels):
        color = (
            spec.get("segment_selected_text", spec["primary"])
            if index == selected
            else spec["muted"]
        )
        text(draw, (x + item * (index + 0.5), y + 31), label, SMALL, color, "mm")


def draw_button(draw, box, label, spec, ready=False):
    if ready:
        fill, color = spec["outline"], spec["muted"]
    else:
        fill, color = spec["primary"], "#FFFFFF"
    rounded(draw, box, spec["control_radius"], fill)
    text(draw, ((box[0] + box[2]) / 2, (box[1] + box[3]) / 2), label, BUTTON, color, "mm")


def draw_phone(canvas: Image.Image, left: int, top: int, spec: dict, index: int):
    draw = ImageDraw.Draw(canvas)
    phone_w, phone_h = 700, 1260
    rounded(draw, (left, top, left + phone_w, top + phone_h), 56, "#111114")
    rounded(draw, (left + 10, top + 10, left + phone_w - 10, top + phone_h - 10), 48, spec["bg"])
    rounded(draw, (left + 270, top + 22, left + 430, top + 48), 16, "#111114")

    x, y, content_w = left + 40, top + 74, phone_w - 80
    title = "ScreenTranslation" if index == 0 else spec["name"]
    text(draw, (x, y), title, PHONE_TITLE, spec["on"])
    text(draw, (x, y + 52), "实时识屏翻译 · Android 16", SMALL, spec["muted"])

    y += 96
    rounded(draw, (x, y, x + content_w, y + 210), spec["radius"], spec["surface"], spec["outline"], 2)
    text(draw, (x + 22, y + 18), "外观", SECTION, spec.get("section", spec["primary"]))
    text(draw, (x + 22, y + 54), "界面风格", BODY, spec["on"])
    draw_segmented(draw, x + 22, y + 88, content_w - 44, index, spec)
    if spec["monet"]:
        text(draw, (x + 22, y + 172), "莫奈动态取色", SMALL, spec["on"])
        rounded(draw, (x + content_w - 80, y + 165, x + content_w - 24, y + 197), 16, spec["primary"])
        draw.ellipse((x + content_w - 54, y + 169, x + content_w - 28, y + 195), fill="#FFFFFF")

    y += 232
    rounded(draw, (x, y, x + content_w, y + 320), spec["radius"], spec["surface"], spec["outline"], 2)
    text(draw, (x + 22, y + 18), "翻译设置", SECTION, spec.get("section", spec["primary"]))
    text(draw, (x + 22, y + 58), "屏幕原文语言", SMALL, spec["muted"])
    text(draw, (x + content_w - 22, y + 58), "英语  ›", BODY, spec["on"], "ra")
    line(draw, x + 22, y + 94, x + content_w - 22, spec["outline"])
    text(draw, (x + 22, y + 116), "翻译目标语言", SMALL, spec["muted"])
    text(draw, (x + content_w - 22, y + 116), "简体中文  ›", BODY, spec["on"], "ra")
    line(draw, x + 22, y + 154, x + content_w - 22, spec["outline"])
    text(draw, (x + 22, y + 178), "识别模式", SMALL, spec["muted"])
    text(draw, (x + content_w - 22, y + 178), "框选区域  ›", BODY, spec["on"], "ra")
    line(draw, x + 22, y + 216, x + content_w - 22, spec["outline"])
    text(draw, (x + 22, y + 242), "识别帧间隔", SMALL, spec["muted"])
    text(draw, (x + content_w - 22, y + 242), "750 ms", BODY, spec["primary"], "ra")
    draw.line((x + 22, y + 286, x + content_w - 22, y + 286), fill=spec["outline"], width=8)
    draw.line((x + 22, y + 286, x + 290, y + 286), fill=spec["primary"], width=8)
    draw.ellipse((x + 280, y + 274, x + 304, y + 298), fill=spec["primary"])

    y += 342
    rounded(draw, (x, y, x + content_w, y + 270), spec["radius"], spec["surface"], spec["outline"], 2)
    text(draw, (x + 22, y + 18), "翻译模型", SECTION, spec.get("section", spec["primary"]))
    text(draw, (x + 22, y + 58), "当前语言模型已通过完整性校验并加载。", SMALL, spec["muted"])
    draw_button(draw, (x + 22, y + 94, x + content_w - 22, y + 154), "已就绪", spec, ready=True)
    rounded(draw, (x + 22, y + 170, x + content_w - 22, y + 222), 14, spec["primary_container"])
    text(draw, (x + 38, y + 196), "模型已就绪：英语 → 简体中文", SMALL, spec["primary"], "lm")
    text(draw, (x + 22, y + 246), "管理已下载模型  ›", SMALL, spec["primary"])

    y += 292
    rounded(draw, (x, y, x + content_w, y + 136), spec["radius"], spec["hero"])
    text(draw, (x + 22, y + 20), "开始识别", SECTION, spec.get("section", spec["primary"]))
    draw_button(draw, (x + 22, y + 58, x + content_w - 22, y + 116), "开始识屏翻译", spec)


def dark_variant(spec: dict) -> dict:
    return {
        **spec,
        "bg": "#111216",
        "surface": "#1C1D22",
        "hero": "#222832",
        "on": "#F4F5F8",
        "muted": "#B8BBC4",
        "outline": "#3B3E46",
        "primary_container": "#283B54",
    }


def draw_matrix_cell(draw, box, spec, label, night, landscape, scale):
    active = dark_variant(spec) if night else spec
    left, top, right, bottom = box
    rounded(draw, box, 22, active["bg"], active["outline"], 2)
    text(draw, (left + 18, top + 16), spec["name"], SECTION, active["on"])
    text(draw, (left + 18, top + 49), label, SMALL, active["muted"])
    content_top = top + 82
    if landscape:
        first = (left + 18, content_top, left + (right-left) * .53, bottom - 18)
        second = (first[2] + 12, content_top, right - 18, bottom - 18)
    else:
        first = (left + 18, content_top, right - 18, top + (bottom-top) * .58)
        second = (left + 18, first[3] + 12, right - 18, bottom - 18)
    rounded(draw, first, active["radius"], active["surface"], active["outline"], 2)
    body = font(int(17 * scale))
    small = font(int(14 * scale))
    text(draw, (first[0] + 14, first[1] + 12), "英语 → 简体中文", body, active["on"])
    text(draw, (first[0] + 14, first[1] + 48 * scale), "全屏增量覆盖", small, active["muted"])
    button_top = first[3] - 58
    rounded(draw, (first[0] + 14, button_top, first[2] - 14, first[3] - 12), 16, active["primary"])
    text(draw, ((first[0]+first[2])/2, button_top + 23), "开始识屏翻译", small, "#FFFFFF", "mm")
    rounded(draw, second, active["radius"], active["surface"], active["outline"], 2)
    text(draw, (second[0] + 14, second[1] + 12), "阅读 (20)", body, active["on"])
    lines = [
        ("#1  The complete source sentence", "完整译文不会静默丢失"),
        ("#2  Dense label fixture", "密集标签进入阅读面板"),
        ("#3  Pause · Show · Copy", "控件满足 48 dp"),
    ]
    y = second[1] + 48 * scale
    for source, target in lines:
        if y + 42 * scale >= second[3]:
            break
        text(draw, (second[0] + 14, y), source, small, active["muted"])
        text(draw, (second[0] + 14, y + 19 * scale), target, small, active["on"])
        y += 46 * scale


def generate_accessibility_matrix():
    width, height = 2400, 2380
    image = Image.new("RGB", (width, height), "#E8EAF0")
    draw = ImageDraw.Draw(image)
    text(draw, (70, 38), "ScreenTranslation v2.3.0 渲染验收矩阵", TITLE, "#17171A")
    text(
        draw,
        (70, 100),
        "固定 Golden · Light/Night · Portrait/Landscape · font scale 1.0/1.3/2.0 · Material Monet",
        SUBTITLE,
        "#626772",
    )
    configurations = [
        ("Light · Portrait · 1.0×", False, False, 1.0),
        ("Light · Landscape · 1.3×", False, True, 1.3),
        ("Night · Portrait · 2.0×", True, False, 2.0),
        ("Night · Landscape · 2.0×", True, True, 2.0),
    ]
    cell_w, cell_h = 740, 510
    for style_index, spec in enumerate(STYLES):
        for config_index, (label, night, landscape, scale) in enumerate(configurations):
            left = 50 + style_index * 785
            top = 160 + config_index * 545
            draw_matrix_cell(
                draw,
                (left, top, left + cell_w, top + cell_h),
                spec,
                label,
                night,
                landscape,
                scale,
            )
    MATRIX_OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(MATRIX_OUTPUT, format="PNG", optimize=True)
    print(MATRIX_OUTPUT)


def main():
    image = Image.new("RGB", (WIDTH, HEIGHT), "#EEF0F4")
    draw = ImageDraw.Draw(image)
    text(draw, (80, 45), "ScreenTranslation 三套 UI 风格", TITLE, "#17171A")
    text(draw, (80, 108), "静态设计预览 · 非真机截图 · Apple 风格作为默认候选", SUBTITLE, "#626772")
    for index, spec in enumerate(STYLES):
        draw_phone(image, 60 + index * 780, 170, spec, index)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUTPUT, format="PNG", optimize=True)
    print(OUTPUT)
    generate_accessibility_matrix()


if __name__ == "__main__":
    main()
