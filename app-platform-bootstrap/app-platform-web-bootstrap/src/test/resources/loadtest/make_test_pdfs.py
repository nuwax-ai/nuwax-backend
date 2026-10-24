#!/usr/bin/env python3
"""生成知识库解析压测用的测试 PDF: 文本书(大量文本页)与扫描书(每页一张大图)两种形态。

用法: python3 make_test_pdfs.py <输出目录>
依赖: pip install pypdf reportlab pillow
"""
import io
import os
import random
import sys

from PIL import Image, ImageDraw
from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, TextStringObject
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas


def make_text_pdf(path, target_mb, label):
    """文本书: 每页大量文本内容流,模拟文字版电子书(驱动 Tika 文本抽取路径)。"""
    w = PdfWriter()
    para = ('知识库解析压测文档 ' + label + '。' * 40 +
            '本段用于模拟真实书籍的正文文本内容,包含经济学、地质学、水文学等专业术语。') * 20
    total = 0
    page_no = 0
    limit = target_mb * 1024 * 1024
    while total < limit:
        page_no += 1
        content = f'第{page_no}章 {label}\n' + (para + f'\n[page {page_no}]\n') * 12
        w.add_page(w.add_blank_page(595, 842))
        text = content.encode('utf-8').decode('latin-1', 'ignore')
        stream = f'BT /F1 10 Tf 40 800 Td 12 TL ({text[:3000]}) Tj ET'.encode('latin-1', 'ignore')
        obj = DecodedStreamObject()
        obj.set_data(stream)
        w.pages[-1][TextStringObject('/Contents')] = w._add_object(obj)
        total += len(stream)
    with open(path, 'wb') as f:
        w.write(f)
    print(path, round(os.path.getsize(path) / 1048576, 1), 'MB', page_no, 'pages')


def make_scan_pdf(path, pages, jpeg_kb):
    """扫描书: 每页一张大 JPEG 图,模拟扫描版图书(驱动 Tika 图片提取路径,事故主要形态)。"""
    c = canvas.Canvas(path)
    rng = random.Random(42)
    for _ in range(pages):
        img = Image.new('RGB', (1654, 2339), (248, 246, 240))
        d = ImageDraw.Draw(img)
        for line in range(55):
            y = 180 + line * 38
            d.line([(140, y), (1500, y + rng.randint(-2, 2))], fill=(60, 60, 65), width=3)
        d.rectangle([140, 60, 900, 130], fill=(30, 30, 35))
        buf = io.BytesIO()
        img.save(buf, 'JPEG', quality=85)
        c.drawImage(ImageReader(io.BytesIO(buf.getvalue())), 0, 0, 595, 842)
        c.showPage()
    c.save()
    print(path, round(os.path.getsize(path) / 1048576, 1), 'MB', pages, 'pages')


if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else '.'
    os.makedirs(out, exist_ok=True)
    make_text_pdf(os.path.join(out, 'small1.pdf'), 3, '文本文档')
    make_scan_pdf(os.path.join(out, 'scan20.pdf'), 60, 350)
