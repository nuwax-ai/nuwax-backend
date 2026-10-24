#!/usr/bin/env python3
"""透视页导入前门禁：静态检查 HTML 是否踩了沙箱红线。

用法:
    python3 pivot_lint.py <page.html> <数据表slugId> [数据表pageId]

    第 3 个参数是「数据表 pageId」（可选）。给了它、且环境变量
    PLATFORM_BASE_URL / SANDBOX_ACCESS_KEY 就绪时，会拉一次数据表的真实记录，
    反查 HTML 里是否内嵌了这些记录（硬编码检测）。

退出码:
    0 = 通过（WARN 仍需逐条确认）/ 1 = 有 ERROR（禁止导入）/ 2 = 参数错误

判定分两档:
    ERROR 机械可判的硬红线，命中即不得导入 —— meta 开关缺失或填错、
          **注释/脚本里的假 meta 或假 <head> 抢走真标签**（宿主按原始文本首个匹配解析）、
          自己 fetch/XHR、凭据泄漏、页内 <a href>、非白名单外链 JS、
          googleapis、prompt/confirm/alert、document.cookie、
          内嵌了 ≥2 条真实记录、页面压根没引 SDK、**内联 JS 语法错误**。
    WARN  启发式或环境不足（未做真实性比对、疑似内嵌对象、localStorage、
          window.open、<form> 未确认 preventDefault、**CSS 类名重复定义**、
          **ECharts 未做 resize 补偿**、meta 出现多处…）——不拦导入，但必须逐条确认。

⚠️ 本脚本只兜「机械红线」，**既不代表页面能跑、也不代表渲染正确**。渲染正确性由
   `preview_stub.js --assert`（或一条命令 `pivot_check.sh`）断言 —— 它用系统 Chrome 真渲染并逐项判定；
   导入后仍须做数字对齐（页面上记录数 == 数据表实际行数）+ 活体测试（改一条记录 → 刷新 → 数字跟着变）。
"""
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request

API_PREFIX = "/api/v1/4sandbox/repo"
JS_WHITELIST = "static-resources.nuwax.com"
# 主字段值短于该长度不参与硬编码比对（避免「进行中」这类短词误报）
MIN_TITLE_LEN = 4
# 内嵌对象启发式的最少命中数（低于此数不提示，ECharts 配置等合法 JSON 很常见）
OBJ_HEURISTIC_MIN = 8


def iter_meta(html):
    """产出所有 <meta> 标签的属性字典（属性顺序无关）。"""
    for tag in re.findall(r"<meta\b[^>]*>", html, re.I):
        attrs = {}
        for m in re.finditer(r"([a-zA-Z_:][\w:.-]*)\s*=\s*[\"']([^\"']*)[\"']", tag):
            attrs[m.group(1).lower()] = m.group(2)
        yield attrs


def find_source_slug(html):
    """返回 <meta name="repo-datatable-source" content="..."> 的 content；没有则 None。"""
    for attrs in iter_meta(html):
        if attrs.get("name", "").strip().lower() == "repo-datatable-source":
            return attrs.get("content", "").strip()
    return None


def fetch_record_titles(page_id):
    """读数据表，返回第 0 列（主字段）里长度 >= MIN_TITLE_LEN 的记录值，用于反查硬编码。

    数据表底层就是 sheet（读写带 kind 语义），`GET /pages/{id}/content` 的
    data.snapshot 是完整 workbook：sheets[sheetId].cellData[row][col]。row 0 是表头。
    任何失败都只 WARN 不阻断——门禁宁可少拦，不可误拦。
    """
    base = (os.environ.get("PLATFORM_BASE_URL") or "").rstrip("/")
    ak = os.environ.get("SANDBOX_ACCESS_KEY") or ""
    if not base or not ak or page_id is None:
        return []
    url = f"{base}{API_PREFIX}/pages/{page_id}/content"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {ak}"})
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            payload = json.load(resp)
    except Exception as exc:  # noqa: BLE001 —— 网络/AK 问题只提示，不拦
        print(f"⚠️  WARN  读数据表失败，跳过真实性比对：{exc}")
        return []
    data = payload.get("data") or {}
    snapshot = data.get("snapshot") or {}
    if not snapshot:
        print("⚠️  WARN  数据表读回为空快照，跳过真实性比对（确认 pageId 是数据表页、且 AK 有权限）")
        return []
    titles = []
    for sheet in (snapshot.get("sheets") or {}).values():
        cell_data = (sheet or {}).get("cellData") or {}
        for row, cells in cell_data.items():
            if str(row) == "0":  # 表头行，字段名不算记录
                continue
            cell = (cells or {}).get("0") or {}
            value = cell.get("v")
            if isinstance(value, str) and len(value.strip()) >= MIN_TITLE_LEN:
                titles.append(value.strip())
    return titles


def _strip_at_blocks(css):
    """删掉 @media / @keyframes / @supports 等整块（按花括号配平，支持嵌套）。

    响应式覆盖写同名类是**合法**的，不能算冲突，所以先把这些块摘掉再统计。
    """
    at_names = ("@media", "@keyframes", "@supports", "@-webkit-keyframes", "@-moz-keyframes")
    out, i, n = [], 0, len(css)
    while i < n:
        if not any(css.startswith(name, i) for name in at_names):
            out.append(css[i])
            i += 1
            continue
        j = css.find("{", i)
        if j < 0:
            break  # 畸形 CSS，剩下的丢弃
        depth, k = 0, j
        while k < n:
            if css[k] == "{":
                depth += 1
            elif css[k] == "}":
                depth -= 1
                if depth == 0:
                    break
            k += 1
        i = k + 1
    return "".join(out)


# 只认「整条选择器就是一个类」的形态（.g2 / div.g2）。
# 刻意**不认** .btn:hover（状态覆盖合法）、.btn.active（修饰符）、.a .b（后代）——
# 宁可漏报，也不要让作者被一堆误报训练成「忽略 lint」。
SINGLE_CLASS_SEL_RE = re.compile(r"^(?:[a-zA-Z][\w-]*)?\.([A-Za-z_][\w-]*)$")


def lint_css_class_collision(html):
    """同一类名在同层被定义多次 → 极可能是命名冲突（实测事故：.g2 撞名致整行坍缩）。"""
    css = "\n".join(re.findall(r"<style[^>]*>(.*?)</style>", html, re.S | re.I))
    if not css.strip():
        return []
    css = re.sub(r"/\*.*?\*/", "", css, flags=re.S)
    css = _strip_at_blocks(css)
    counts = {}
    for raw_sel in re.findall(r"([^{}]+)\{", css):
        for group in raw_sel.split(","):
            m = SINGLE_CLASS_SEL_RE.match(group.strip())
            if m:
                counts[m.group(1)] = counts.get(m.group(1), 0) + 1
    return [
        f"类名 .{name} 被重复定义了 {cnt} 次（@media 外）—— 极可能是命名冲突："
        f"两处意图不同却同名，后一处会覆盖前一处的同名属性（实测曾致图表整行坍缩）。"
        f"请改用语义化类名，或把两处合并成一条规则。"
        for name, cnt in sorted(counts.items())
        if cnt > 1
    ]


def lint_echarts_resize(html):
    """用了 echarts.init 却没有任何 resize 补偿 → 画布被压窄变形。

    注意判据是**真的调了 resize**，不是全文出现 "resize" 字样：
    CSS 的 `resize: both`、类名 .resizeable 都会让宽松匹配漏报。
    """
    if "echarts.init" not in html:
        return []
    if re.search(r"\.\s*resize\s*\(|addEventListener\s*\(\s*['\"]resize|onresize\s*=", html):
        return []
    return [
        "用了 echarts.init 但全文没有任何 resize 补偿 —— 入场动画 / 字体加载会改变容器宽度，"
        "画布会被压窄变形。请在渲染后 requestAnimationFrame + setTimeout 各调一次 chart.resize()，"
        "并监听窗口 resize。"
    ]


def lint_inline_js_syntax(html):
    """内联 <script> 过一遍 `node --check`。

    正则是查不出括号/引号错位的，而这类错误会让**整个页面白屏**——
    属于必须拦下的硬错误。没装 node 就降级为 WARN，不阻断。
    """
    errs, warns = [], []
    node = shutil.which("node")
    if node is None:
        return [], ["未找到 node，跳过内联 JS 语法检查 —— 请自行确认脚本无语法错误"]

    blocks = []
    for m in re.finditer(r"<script\b([^>]*)>(.*?)</script\s*>", html, re.S | re.I):
        attrs, body = m.group(1), m.group(2)
        if re.search(r"\bsrc\s*=", attrs, re.I):
            continue
        t = re.search(r"\btype\s*=\s*[\"']([^\"']*)[\"']", attrs, re.I)
        typ = (t.group(1) if t else "").strip().lower()
        if typ and "javascript" not in typ and typ != "module":
            continue  # application/json / text/template 之类不是 JS
        if body.strip():
            blocks.append((typ == "module", body))

    for idx, (is_module, body) in enumerate(blocks, 1):
        fd, tmp = tempfile.mkstemp(suffix=".mjs" if is_module else ".js")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                fh.write(body)
            proc = subprocess.run(
                [node, "--check", tmp], capture_output=True, text=True, timeout=20
            )
            if proc.returncode != 0:
                lines = [ln.strip() for ln in (proc.stderr or "").splitlines() if ln.strip()]
                # 跳过 node 的堆栈头，取第一行真正的错误描述
                detail = next((ln for ln in lines if "SyntaxError" in ln), lines[0] if lines else "语法错误")
                errs.append(f"内联 JS 第 {idx} 段语法错误（node --check）：{detail}")
        except Exception as exc:  # noqa: BLE001 —— 检查器自身异常不阻断导入
            warns.append(f"内联 JS 语法检查失败（已跳过）：{exc}")
        finally:
            try:
                os.unlink(tmp)
            except OSError:
                pass
    return errs, warns


# —— 宿主解析模拟 ——
# 宿主（nuwax-repo-web）对透视页 HTML 的两处解析都是**裸正则跑在原始文本上、取首个匹配**：
#   ① 目标数据表 slugId：/<meta[^>]+name=["']repo-datatable-source["'][^>]*>/i
#   ② CSP/SDK 注入点：html.replace(/<head[^>]*>/i, ...)
# 它既不剥注释、也不建 DOM。而本 lint 第 219 行会**先剥注释**（为了让「注释里写『不要用
# prompt()』」这类说明文字不误报）——两边规则不同，就留下一条缝：注释/脚本里放个示例标签，
# 会抢走真标签，lint 全绿而线上炸。本组检查专补这条缝，必须在剥注释**之前**的原文上跑。
HOST_META_RE = re.compile(r"<meta[^>]+name=[\"']repo-datatable-source[\"'][^>]*>", re.I)
HOST_META_CONTENT_RE = re.compile(r"content=[\"']([^\"']*)[\"']", re.I)
HOST_HEAD_RE = re.compile(r"<head[^>]*>", re.I)
TEXT_BLOCK_RE = re.compile(r"<(script|style|title|textarea)\b[^>]*>(.*?)</\1\s*>", re.S | re.I)


def _text_regions(html):
    """产出 (start, end, 人话名)：注释与 script/style/title/textarea **正文**的区间。

    落在这些区间里的「标签」是纯文本，浏览器不当标签，但宿主的裸正则照样命中。
    """
    regions = [(m.start(), m.end(), "注释") for m in re.finditer(r"<!--.*?-->", html, re.S)]
    regions += [
        (m.start(2), m.end(2), f"{m.group(1).lower()} 正文") for m in TEXT_BLOCK_RE.finditer(html)
    ]
    return regions


def _line_of(html, pos):
    """pos 在原文里的行号（从 1 起）——报错要能直接跳过去。"""
    return html.count("\n", 0, pos) + 1


def lint_host_parse(html):
    """模拟宿主的两处原始文本解析，专抓「注释/脚本里的假标签抢走真标签」。

    返回 (errors, warns, shadowed)；shadowed = {"meta": bool, "head": bool}，
    供上层抑制会误导人的重复报错（真因已由本检查说清）。
    """
    errors, warns = [], []
    shadowed = {"meta": False, "head": False}
    regions = _text_regions(html)

    def enclosing(pos):
        for start, end, kind in regions:
            if start <= pos < end:
                return kind
        return None

    # ① meta：宿主取首个匹配的 content 当目标数据表 slugId
    meta = HOST_META_RE.search(html)
    if meta is not None:
        kind = enclosing(meta.start())
        if kind is not None:
            shadowed["meta"] = True
            content = HOST_META_CONTENT_RE.search(meta.group(0))
            got = content.group(1) if content else ""
            errors.append(
                f"首个 repo-datatable-source meta 落在{kind}里（第 {_line_of(html, meta.start())} 行）——"
                f"宿主取的是**首个文本匹配**且不剥注释，会把 \"{got}\" 当成目标数据表 slugId，"
                f"真 meta 被挤掉，页面 read() 报 forbidden。"
                f"把示例标签从注释里删掉，或写成不含尖括号的纯文字。"
            )
        elif len(HOST_META_RE.findall(html)) > 1:
            warns.append(
                f"源码里有 {len(HOST_META_RE.findall(html))} 处 repo-datatable-source meta ——"
                f"宿主只认**首个**，确认它就是要用的那个（其余请删掉，别留在注释/脚本里）"
            )

    # ② head：宿主把 CSP + SDK bootstrap 注入到首个文本匹配的 <head> 之后
    head = HOST_HEAD_RE.search(html)
    if head is not None:
        kind = enclosing(head.start())
        if kind is not None:
            shadowed["head"] = True
            errors.append(
                f"首个 <head> 落在{kind}里（第 {_line_of(html, head.start())} 行）——"
                f"宿主按裸正则取首个文本匹配注入 SDK，注入的内容落进去是**惰性的**："
                f"window.repoDatatable 不存在，页面永远走兜底面板。"
                f"把 <head> 字样从{kind}里移出去（写成不含尖括号的文字）。"
            )

    return errors, warns, shadowed


def lint(html, slug, page_id):
    errors, warns = [], []
    # 0) 宿主解析模拟 —— 必须在剥注释**之前**（宿主不剥注释，两边规则不同就是那条缝）
    host_errs, host_warns, shadowed = lint_host_parse(html)
    errors.extend(host_errs)
    warns.extend(host_warns)

    # 注释先剥掉再查：注释里写「替代 prompt()」「不要用 <a href>」是说明文字，不算违规
    html = re.sub(r"<!--.*?-->", "", html, flags=re.S)

    # 1) meta 开关：必须存在，且 content 就是目标数据表 slugId
    content = find_source_slug(html)
    if content is None:
        if not shadowed["meta"]:  # 被注释里的假 meta 顶掉时，第 0 步已说得更准，别重复误导
            errors.append(
                '缺少 <meta name="repo-datatable-source" content="..."> —— 透视页开关，必须带'
            )
    elif content != slug:
        errors.append(f'meta content 是 "{content}"，应填数据表 slugId "{slug}"')

    # 2) 凭据泄漏（AK / Bearer / ticket）
    for pattern, label in (
        (r"ak-[0-9a-fA-F]{8,}", "AK"),
        (r"Bearer\s+[A-Za-z0-9._\-]{8,}", "Bearer token"),
        (r"\bticket\b\s*[:=]", "ticket"),
    ):
        for hit in re.finditer(pattern, html):
            errors.append(f"HTML 里出现{label}：{hit.group(0)[:40]}")

    # 3) 自己发请求（沙箱 opaque origin，请求不带登录 cookie，必 401）
    for pattern, label in (
        (r"\bfetch\s*\(", "fetch()"),
        (r"\bXMLHttpRequest\b", "XMLHttpRequest"),
        (r"\bsendBeacon\b", "navigator.sendBeacon"),
    ):
        if re.search(pattern, html):
            errors.append(f"{label} —— 数据只能走 window.repoDatatable，页面自己发请求不带登录 cookie")

    # 4) 页内跳转（点击会把预览框自身导航走）
    if re.search(r"<a\b[^>]*\bhref\s*=", html, re.I | re.S):
        errors.append("<a href=...> —— 会把预览框导航走，改渲染纯文本")

    # 5) 禁用 CDN（国内不可达）
    if re.search(r"googleapis\.com|gstatic\.com|g-fonts\.css|fonts\.google", html, re.I):
        errors.append("引了 googleapis / g-fonts 相关地址 —— 国内不可达，禁用（字体用系统栈）")

    # 6) 外链 JS 白名单（只放行平台 CDN 与 data: 内联）
    for src in re.findall(r"<script[^>]+src\s*=\s*[\"']([^\"']+)[\"']", html, re.I):
        if JS_WHITELIST not in src and not src.startswith("data:"):
            errors.append(f"外部 JS 不在白名单：{src}")

    # 7) 页内阻塞式弹窗（Chrome 记住「阻止此页面再创建对话框」后会静默失效）
    for pattern, label in (
        (r"\bprompt\s*\(", "prompt()"),
        (r"\bconfirm\s*\(", "confirm()"),
        (r"\balert\s*\(", "alert()"),
    ):
        if re.search(pattern, html):
            errors.append(f"{label} —— 规范禁用，用 §5 骨架的 ask() 自绘弹层")

    # 8) document.cookie 访问即抛 SecurityError
    if re.search(r"document\s*\.\s*cookie", html):
        errors.append("访问 document.cookie —— 沙箱内必抛 SecurityError，删掉")

    # 9) 必须真的引了 SDK（否则就是静态页面，等于没接数据）
    if not re.search(r"repoDatatable", html):
        errors.append("页面没有引用 window.repoDatatable —— 数据没动态绑定（铁律 2）")
    elif not re.search(r"\.\s*read\s*\(", html):
        # 允许别名（模板里 var api = window.repoDatatable; api.read()）
        warns.append("没看到 .read() 调用 —— 确认数据是动态读的，不是写死的")

    # 10) 硬编码记录：首选「拿真实数据反查」（几乎零误报，只比对主字段值）
    titles = fetch_record_titles(page_id)
    if titles:
        leaked = sorted({t for t in titles if t in html})
        if len(leaked) >= 2:
            errors.append(
                f"内嵌了数据表真实记录 {len(leaked)} 条，例：{leaked[0][:34]!r} —— 必须动态读"
            )
        elif len(leaked) == 1:
            warns.append(f"HTML 里出现一条真实记录值 {leaked[0][:34]!r} —— 确认不是硬编码")
    else:
        warns.append(
            "未做真实性比对（缺 PLATFORM_BASE_URL/SANDBOX_ACCESS_KEY 或未传数据表 pageId）"
            " —— 必须自己补活体测试"
        )

    # 10b) 兜底启发式（只 WARN）：形如 {"字段名":"值"} 的内嵌对象字面量
    n_obj = len(re.findall(r"\{\s*\"[^\"]{2,20}\"\s*:\s*\"", html))
    if n_obj >= OBJ_HEURISTIC_MIN:
        warns.append(f"发现 {n_obj} 处形如 {{\"字段名\":\"值\"}} 的内嵌对象 —— 确认不是硬编码数据")
    if re.search(
        r"(?:const|let|var)\s+\w*(?:DATA|ROWS|RECORDS|SNAPSHOT)\w*\s*=\s*\[", html, re.I
    ):
        warns.append("疑似内嵌数据数组（DATA/ROWS/RECORDS/SNAPSHOT = [ ... ]）")

    # 11) 沙箱其它边界（静默无效 / CSP 拦，容易白写）
    if re.search(r"window\s*\.\s*open\s*\(", html):
        warns.append("用到 window.open() —— 无 allow-popups，静默失效，返回 null")
    if re.search(r"target\s*=\s*[\"']_blank[\"']", html, re.I):
        warns.append('出现 target="_blank" —— 静默无效，删掉')
    if re.search(r"target\s*=\s*[\"']_top[\"']", html, re.I):
        warns.append('出现 target="_top" —— 顶层导航被拦')
    if re.search(r"\blocalStorage\b|\bsessionStorage\b", html):
        warns.append("用了 localStorage/sessionStorage —— 沙箱内仅当次打开有效、刷新即空；跨会话用 repoHost.setPref/getPref")
    if re.search(r"<base\b[^>]*\bhref\s*=", html, re.I | re.S):
        warns.append("<base href> —— 被 CSP base-uri 'none' 拦，删掉")
    if re.search(r"<(object|embed)\b", html, re.I):
        warns.append("<object>/<embed> —— 被 CSP object-src 'none' 拦，删掉")
    if re.search(r"<form\b", html, re.I):
        warns.append("<form> 真实提交被 CSP form-action 'none' 拦 —— submit 处理里必须 preventDefault() 自处理")

    # 12) 渲染类真实事故（2026-09-13 增补，均由实际踩坑换来）
    warns.extend(lint_css_class_collision(html))
    warns.extend(lint_echarts_resize(html))
    js_errs, js_warns = lint_inline_js_syntax(html)
    errors.extend(js_errs)
    warns.extend(js_warns)

    for warn in warns:
        print("⚠️  WARN ", warn)
    for err in errors:
        print("❌ ERROR", err)
    if errors:
        print(f"\n不通过：{len(errors)} 项违规，禁止导入。")
        return 1
    print(
        "\n✅ 通过静态门禁（WARN 已逐条确认过才算过）。**这只是红线检查，不等于页面能跑**——"
        "接着跑 `bash scripts/pivot_check.sh <页面.html> <slugId> <数据表pageId>` 做真渲染断言（退 0 才算过），"
        "导入后再做数字对齐 + 活体测试（改数据表一条记录 → 刷新 → 数字跟着变）。"
    )
    return 0


def main():
    if len(sys.argv) < 3 or sys.argv[1] in ("-h", "--help"):
        print(__doc__)
        return 2
    path, slug = sys.argv[1], sys.argv[2]
    page_id = sys.argv[3] if len(sys.argv) > 3 else None
    try:
        html = pathlib.Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        print(f"读文件失败：{exc}")
        return 2
    return lint(html, slug, page_id)


if __name__ == "__main__":
    sys.exit(main())
