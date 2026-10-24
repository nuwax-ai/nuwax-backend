#!/usr/bin/env bash
# 透视页导入前的「一条命令」自检：静态门禁 → 本地预览 → 真渲染断言。
#
# 为什么要有这个脚本：这三步单独跑都不难，但**容易只跑一步就导入**。只跑 lint 会漏掉
# 「结构对不上、真数据下报错、图表拿不到字段」这类只有真数据才暴露的问题；而只有预览、
# 没有断言时，「渲染对不对」只能靠人眼盯截图判 —— 主观、会漏、且无法回归。串起来跑，
# 让「跳过」变成需要主动做的事。
#
# 用法:
#   scripts/pivot_check.sh <页面.html> <数据表slugId> [数据表pageId]
#   scripts/pivot_check.sh <页面.html> <数据表slugId> --from-file <快照.json>
#
# 可选:
#   --no-assert   只生成预览文件、不跑真渲染断言（不推荐：等于把「对不对」重新交回肉眼）
#
# 环境变量（联网预览需要）: PLATFORM_BASE_URL、SANDBOX_ACCESS_KEY
#
# 退出码:
#   0  两步都过（可以导入）
#   1  被拦下（静态门禁有 ERROR，或预览/断言失败）
#   2  参数错
#   4  静态门禁通过，但**渲染没被验证**（没给数据源，或用了 --no-assert）—— 未完整验证，别当成通过
#   5  环境不具备（本机没有 Chrome），断言没跑成 —— 不假装通过，装好 Chrome 再跑
set -uo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"

usage() {
  sed -n '2,23p' "$0" | sed 's/^# \{0,1\}//'
}

if [ "$#" -eq 0 ] || [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 2
fi

PAGE="$1"; shift || true
SLUG="${1:-}"; shift || true
if [ -z "$PAGE" ] || [ -z "$SLUG" ]; then
  echo "✘ 至少要给：<页面.html> <数据表slugId>" >&2
  echo >&2
  usage >&2
  exit 2
fi

DT_PAGE=""
FROM_FILE=""
DO_ASSERT=1
while [ "$#" -gt 0 ]; do
  case "$1" in
    --from-file)
      FROM_FILE="${2:-}"
      if [ -z "$FROM_FILE" ]; then echo "✘ --from-file 需要一个文件路径" >&2; exit 2; fi
      shift 2
      ;;
    --no-assert)
      DO_ASSERT=0
      shift
      ;;
    *)
      if [ -z "$DT_PAGE" ]; then DT_PAGE="$1"; else
        echo "✘ 多余的参数：$1" >&2; exit 2
      fi
      shift
      ;;
  esac
done

if [ ! -f "$PAGE" ]; then
  echo "✘ 页面文件不存在：$PAGE" >&2
  exit 2
fi
if [ -n "$FROM_FILE" ] && [ ! -f "$FROM_FILE" ]; then
  echo "✘ 快照文件不存在：$FROM_FILE" >&2
  exit 2
fi

if [ -t 1 ]; then
  B=$'\033[1m'; R=$'\033[31m'; G=$'\033[32m'; Y=$'\033[33m'; N=$'\033[0m'
else
  B=""; R=""; G=""; Y=""; N=""
fi

TOTAL=2

echo "${B}════ 1/${TOTAL}  静态门禁（pivot_lint.py）════${N}"
if [ -n "$DT_PAGE" ]; then
  python3 "$SELF_DIR/pivot_lint.py" "$PAGE" "$SLUG" "$DT_PAGE"
else
  python3 "$SELF_DIR/pivot_lint.py" "$PAGE" "$SLUG"
fi
LINT_CODE=$?
case "$LINT_CODE" in
  0) echo "${G}✔ 静态门禁：通过${N}" ;;
  1) echo "${R}✘ 静态门禁：有 ERROR —— 先改完再谈预览，别导入${N}"; exit 1 ;;
  *) echo "${R}✘ 静态门禁：脚本自身出错（退出码 $LINT_CODE）${N}"; exit 1 ;;
esac

echo
if [ "$DO_ASSERT" -eq 1 ]; then
  echo "${B}════ 2/${TOTAL}  本地预览 + 真渲染断言（preview_stub.js --assert）════${N}"
else
  echo "${B}════ 2/${TOTAL}  本地预览（preview_stub.js，--no-assert 已跳过断言）════${N}"
fi

# 两种数据来源：联网拉真快照（给 pageId）或离线回放（--from-file）
if [ -n "$FROM_FILE" ]; then
  ARGS=(--from-file "$FROM_FILE" "$PAGE")
elif [ -n "$DT_PAGE" ]; then
  ARGS=("$DT_PAGE" "$PAGE")
else
  ARGS=()
fi

if [ "${#ARGS[@]}" -eq 0 ]; then
  echo "${Y}────────────────────────────────────────────────────────────${N}"
  echo "${Y}⚠️  本地预览：已跳过 —— 没给数据表 pageId${N}"
  echo "${Y}${N}"
  echo "${Y}   静态门禁过了**不等于页面能跑**。以下这类问题只有真数据才暴露：${N}"
  echo "${Y}     · 数据桥拿不到数据 / 字段名对不上（渲染成空态或 NaN）${N}"
  echo "${Y}     · 结构对不上（字段缺席、类型不是预期的那种）${N}"
  echo "${Y}     · 图表取不到字段、分组轴空、总数对不上${N}"
  echo "${Y}${N}"
  echo "${Y}   补法二选一：${N}"
  echo "${Y}     · 加第三个参数：scripts/pivot_check.sh <页面.html> <slugId> <数据表pageId>${N}"
  echo "${Y}     · 或离线回放：  scripts/pivot_check.sh <页面.html> <slugId> --from-file <快照.json>${N}"
  echo "${Y}${N}"
  echo "${Y}   别把这次「通过」当成可导入。${N}"
  echo "${Y}────────────────────────────────────────────────────────────${N}"
  exit 4
fi

if [ "$DO_ASSERT" -eq 1 ]; then
  node "$SELF_DIR/preview_stub.js" "${ARGS[@]}" --assert
  PREVIEW_CODE=$?
else
  node "$SELF_DIR/preview_stub.js" "${ARGS[@]}"
  PREVIEW_CODE=$?
fi

case "$PREVIEW_CODE" in
  0)
    if [ "$DO_ASSERT" -eq 1 ]; then
      echo "${G}✔ 本地预览：通过（真渲染断言全过 —— 数据上了屏、无报错、版面没坏）${N}"
    else
      echo "${Y}✔ 本地预览：文件已生成，但${B}断言被 --no-assert 跳过${N}${Y} —— 渲染对不对没有被自动验证${N}"
    fi
    ;;
  4)
    # preview_stub 退 4 = 没跑断言（只生成了文件）。正常只会由 --no-assert 走到；
    # 但即便断言开着也退 4，那也绝不能算过 —— 一律按「未完整验证」收尾。
    echo "${Y}✔ 本地预览：文件已生成，但${B}断言没跑${N}${Y} —— 渲染对不对没有被自动验证${N}"
    ;;
  5)
    echo "${R}✘ 环境不具备：真渲染断言跑不了（退出码 5）—— 具体原因见上一行${N}"
    echo "${R}   （本机没装 Chrome，或 PIVOT_CHROME 指的路径不存在）${N}"
    echo "${R}   装 Chrome，或 PIVOT_CHROME=<可执行文件路径> 指一个；${N}"
    echo "${R}   不想装就退一步只生成预览文件：加 --no-assert（但那是退 4，仍未验证渲染）${N}"
    echo "${R}   —— 不假装通过。${N}"
    exit 5
    ;;
  *)
    echo "${R}✘ 本地预览/断言：失败（退出码 $PREVIEW_CODE）—— 按上面的提示改，别导入${N}"
    exit 1
    ;;
esac

if [ "$DO_ASSERT" -eq 0 ]; then
  # --no-assert 是「显式放弃自动判真伪」，那就不能报成「通过」—— 否则又回到「肉眼看一眼就当验过了」。
  echo
  echo "${Y}${B}════ 静态门禁过了，但断言被跳过（未完整验证，退出码 4）════${N}"
  echo "${Y}   这次只证明了「没有静态红线」，没有证明「页面用真数据能跑对」。${N}"
  echo "${Y}   要么去掉 --no-assert 重跑，要么自己打开预览文件逐项核对：${N}"
  echo "${Y}     · 数据桥到位、数字与数据表一致、分组桶数与去重取值数吻合、图表不是空的${N}"
  exit 4
fi

echo
echo "${G}${B}════ 两步都过 ════${N}"
echo "  可选：看一眼预览文件的视觉（断言管不了「好不好看」，只管「对不对」）"
echo "  下一步：导入 → 线上复核（URL 里的 slugId 未变、数据条数/分组数与数据表实际值一致）"
echo "  交付前建议再跑一次多视口 + 坏数据演练："
echo "    node scripts/e2e_pivot.mjs --page $PAGE --from-file <快照.json> --corrupt --fail-writes"
