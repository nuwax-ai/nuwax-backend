package com.xspaceagi.repo.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.ImageTranscoder;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 流程图导出成图（需求3）：解析前端 .flow-diagram 的 data-flow（LogicFlow graphData），
 * 后端自绘 SVG 后用 Batik 栅格化为 PNG，供 docx/pdf 导出内嵌。
 * <p>
 * 默认值对齐 LogicFlow 2.2.5 主题与前端 FlowBlock：
 * 节点填充 #FFFFFF、描边 #474747、线宽 2；连线 #474747；文字 #1D2129 / 12px。
 * 节点支持 rect（含 radius 圆角）/circle/ellipse/diamond/polygon（properties.points 相对中心）/text；
 * 连线优先用 pointsList 折线，无路径点数据时退化为两节点中心连线并裁剪到包围盒边缘。
 */
@Slf4j
@Service
public class RepoFlowImageService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** LogicFlow 默认尺寸（2.2.5：rect 100x80、circle r50、ellipse rx30 ry45、diamond rx30 ry50） */
    private static final double RECT_W = 100;
    private static final double RECT_H = 80;
    private static final double CIRCLE_R = 50;
    private static final double ELLIPSE_RX = 30;
    private static final double ELLIPSE_RY = 45;
    private static final double DIAMOND_RX = 30;
    private static final double DIAMOND_RY = 50;

    private static final String DEFAULT_FILL = "#FFFFFF";
    private static final String DEFAULT_STROKE = "#474747";
    private static final double DEFAULT_STROKE_WIDTH = 2;
    private static final String DEFAULT_TEXT_COLOR = "#1D2129";
    private static final double DEFAULT_FONT_SIZE = 12;
    private static final double LINE_HEIGHT_RATIO = 1.2;

    /** 箭头参数对齐 LogicFlow theme arrow：offset 10、verticalLength 5 */
    private static final double ARROW_LEN = 10;
    private static final double ARROW_HALF_W = 5;

    private static final double PADDING = 30;
    private static final double SCALE = 2;
    private static final double MAX_PNG_WIDTH = 2400;

    private static final String FONT_STACK =
            "'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'WenQuanYi Micro Hei', sans-serif";

    /**
     * 把 data-flow JSON 渲染为 PNG 字节。空图/解析失败返回 null（调用方退化为占位文本）。
     */
    public byte[] renderFlowToPng(String flowJson) {
        if (flowJson == null || flowJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(flowJson);
            JsonNode nodesEl = root.get("nodes");
            if (nodesEl == null || !nodesEl.isArray() || nodesEl.isEmpty()) {
                return null;
            }
            List<NodeInfo> nodes = new ArrayList<>();
            Map<String, NodeInfo> byId = new HashMap<>();
            for (JsonNode n : nodesEl) {
                NodeInfo info = parseNode(n);
                if (info != null) {
                    nodes.add(info);
                    byId.put(info.id, info);
                }
            }
            if (nodes.isEmpty()) {
                return null;
            }
            List<EdgeInfo> edges = new ArrayList<>();
            JsonNode edgesEl = root.get("edges");
            if (edgesEl != null && edgesEl.isArray()) {
                for (JsonNode e : edgesEl) {
                    EdgeInfo info = parseEdge(e, byId);
                    if (info != null) {
                        edges.add(info);
                    }
                }
            }
            String svg = buildSvg(nodes, edges);
            if (svg == null) {
                return null;
            }
            return rasterize(svg);
        } catch (Exception e) {
            log.warn("render flow diagram failed: {}", e.getMessage());
            return null;
        }
    }

    // ============================ 思维导图（mind-elixir） ============================

    /** 思维导图行内常量：后端自绘水平树状图（根向左，子节点向右展开，贝塞尔曲线连接） */
    private static final double MM_ROW_HEIGHT = 64;
    private static final double MM_COL_SPACING = 200;
    private static final double MM_NODE_PAD_X = 14;
    private static final double MM_NODE_PAD_Y = 10;
    private static final double MM_MIN_NODE_WIDTH = 60;
    private static final double MM_MAX_NODE_WIDTH = 320;
    private static final double MM_FONT_SIZE = 14;
    private static final double MM_LINE_WIDTH = 2;
    /** 各深度节点配色：第 0 项为根，其余为浅色填充 + 对应描边（超过复用最后一项） */
    private static final String[][] MM_PALETTE = {
            {"#4E83E6", "#4E83E6"}, // 根：深蓝，白字
            {"#E9F1FD", "#7FA6E8"},
            {"#EAF8EF", "#6DBE8B"},
            {"#FCF0E8", "#D98B5F"},
            {"#F0ECFA", "#9B87D4"},
    };

    /**
     * 把 data-mindmap JSON（mind-elixir 的 {@code { nodeData 树 }}）渲染为 PNG 字节。
     * 空图/解析失败返回 null（调用方退化为占位文本）。
     */
    public byte[] renderMindmapToPng(String mindmapJson) {
        if (mindmapJson == null || mindmapJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(mindmapJson);
            JsonNode nodeData = root.get("nodeData");
            if (nodeData == null || !nodeData.isObject()) {
                return null;
            }
            MindTree tree = new MindTree(nodeData);
            if (tree.nodes.isEmpty()) {
                return null;
            }
            String svg = tree.buildSvg();
            if (svg == null) {
                return null;
            }
            return rasterize(svg);
        } catch (Exception e) {
            log.warn("render mindmap failed: {}", e.getMessage());
            return null;
        }
    }

    /** 思维导图节点（扁平收集 + 树结构），layout 与 buildSvg 共用 */
    private static final class MindNodeInfo {
        String text;
        double depth;
        double cx;
        double cy;
        double hw;
        double hh;
        String fill;
        String stroke;
        String textColor;
        String[] textLines;
        final List<MindNodeInfo> children = new ArrayList<>();
    }

    /** 思维导图整树：解析 nodeData → 布局（子树叶子行数定高、先序分配 y、x 按深度）→ 生成 SVG */
    private static final class MindTree {
        final List<MindNodeInfo> nodes = new ArrayList<>();
        private final MindNodeInfo root;
        private final double[] cursor = {0};

        MindTree(JsonNode nodeData) {
            this.root = parse(nodeData, "思维导图", 0);
        }

        private MindNodeInfo parse(JsonNode n, String fallback, double depth) {
            if (n == null || !n.isObject()) {
                return null;
            }
            String text = n.path("topic").asText(null);
            if (text == null || text.isBlank()) {
                text = fallback;
            }
            if (text == null || text.isBlank()) {
                text = "—";
            }
            MindNodeInfo info = new MindNodeInfo();
            info.text = text.trim();
            info.depth = depth;
            String[] palette = MM_PALETTE[(int) Math.min(depth, MM_PALETTE.length - 1)];
            info.fill = palette[0];
            info.stroke = palette[1];
            info.textColor = depth == 0 ? "#FFFFFF" : "#1D2129";
            JsonNode childrenEl = n.get("children");
            if (childrenEl != null && childrenEl.isArray()) {
                for (JsonNode c : childrenEl) {
                    MindNodeInfo child = parse(c, null, depth + 1);
                    if (child != null) {
                        info.children.add(child);
                    }
                }
            }
            nodes.add(info);
            return info;
        }

        String buildSvg() {
            if (root == null) {
                return null;
            }
            // 布局：每棵子树高度 = 叶子数 * rowHeight，节点 y = 子树中心；x = padding + depth * colSpacing
            layoutSubtree(root);
            computeBox(root);
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (MindNodeInfo n : nodes) {
                minX = Math.min(minX, n.cx - n.hw);
                maxX = Math.max(maxX, n.cx + n.hw);
                minY = Math.min(minY, n.cy - n.hh);
                maxY = Math.max(maxY, n.cy + n.hh);
            }
            if (minX >= maxX || minY >= maxY) {
                return null;
            }
            minX -= PADDING;
            minY -= PADDING;
            maxX += PADDING;
            maxY += PADDING;
            double w = maxX - minX;
            double h = maxY - minY;

            StringBuilder sb = new StringBuilder();
            sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(fmt(w))
                    .append("\" height=\"").append(fmt(h))
                    .append("\" viewBox=\"").append(fmt(minX)).append(' ').append(fmt(minY))
                    .append(' ').append(fmt(w)).append(' ').append(fmt(h)).append("\">");
            // 连接线先画（位于节点下方）
            for (MindNodeInfo n : nodes) {
                for (MindNodeInfo child : n.children) {
                    drawConnector(sb, n, child);
                }
            }
            // 节点形状 + 文字
            for (MindNodeInfo n : nodes) {
                drawMindNode(sb, n);
            }
            sb.append("</svg>");
            return sb.toString();
        }

        private double layoutSubtree(MindNodeInfo node) {
            if (node.children.isEmpty()) {
                node.cy = cursor[0];
                cursor[0] += MM_ROW_HEIGHT;
            } else {
                double firstY = Double.MAX_VALUE;
                double lastY = -Double.MAX_VALUE;
                for (MindNodeInfo c : node.children) {
                    double y = layoutSubtree(c);
                    firstY = Math.min(firstY, y);
                    lastY = Math.max(lastY, y);
                }
                node.cy = (firstY + lastY) / 2;
            }
            node.cx = PADDING + node.depth * MM_COL_SPACING;
            return node.cy;
        }

        private void computeBox(MindNodeInfo node) {
            double maxContentW = MM_MAX_NODE_WIDTH - 2 * MM_NODE_PAD_X;
            int maxChars = (int) Math.max(1, maxContentW / (MM_FONT_SIZE * 0.8));
            node.textLines = wrapMindText(node.text, maxChars);
            double maxLineChars = 0;
            for (String line : node.textLines) {
                maxLineChars = Math.max(maxLineChars, line.length());
            }
            double contentW = maxLineChars * MM_FONT_SIZE * 0.8;
            double contentH = node.textLines.length * MM_FONT_SIZE * 1.3;
            node.hw = Math.min(MM_MAX_NODE_WIDTH, Math.max(MM_MIN_NODE_WIDTH, contentW) + 2 * MM_NODE_PAD_X) / 2;
            node.hh = contentH / 2 + MM_NODE_PAD_Y;
            for (MindNodeInfo c : node.children) {
                computeBox(c);
            }
        }

        private void drawConnector(StringBuilder sb, MindNodeInfo parent, MindNodeInfo child) {
            double px = parent.cx + parent.hw;
            double py = parent.cy;
            double cx = child.cx - child.hw;
            double cy = child.cy;
            double mx = (px + cx) / 2;
            sb.append("<path d=\"M ").append(fmt(px)).append(' ').append(fmt(py))
                    .append(" C ").append(fmt(mx)).append(' ').append(fmt(py))
                    .append(' ').append(fmt(mx)).append(' ').append(fmt(cy))
                    .append(' ').append(fmt(cx)).append(' ').append(fmt(cy))
                    .append("\" fill=\"none\" stroke=\"").append(xmlEscape(parent.stroke)).append('"')
                    .append(" stroke-width=\"").append(fmt(MM_LINE_WIDTH)).append("\"/>");
        }

        private void drawMindNode(StringBuilder sb, MindNodeInfo n) {
            sb.append("<rect x=\"").append(fmt(n.cx - n.hw)).append("\" y=\"").append(fmt(n.cy - n.hh))
                    .append("\" width=\"").append(fmt(n.hw * 2)).append("\" height=\"").append(fmt(n.hh * 2))
                    .append("\" rx=\"6\" fill=\"").append(xmlEscape(n.fill))
                    .append("\" stroke=\"").append(xmlEscape(n.stroke))
                    .append("\" stroke-width=\"").append(fmt(MM_LINE_WIDTH)).append("\"/>");
            if (n.textLines == null || n.textLines.length == 0) {
                return;
            }
            double lh = MM_FONT_SIZE * 1.3;
            double firstBaseline = n.cy - (n.textLines.length - 1) * lh / 2 + MM_FONT_SIZE * 0.35;
            for (int i = 0; i < n.textLines.length; i++) {
                String line = n.textLines[i];
                if (line.isEmpty()) {
                    continue;
                }
                sb.append("<text x=\"").append(fmt(n.cx)).append("\" y=\"").append(fmt(firstBaseline + i * lh))
                        .append("\" text-anchor=\"middle\" font-family=\"").append(FONT_STACK)
                        .append("\" font-size=\"").append(fmt(MM_FONT_SIZE))
                        .append("\" fill=\"").append(xmlEscape(n.textColor)).append("\">")
                        .append(xmlEscape(line)).append("</text>");
            }
        }
    }

    /** 思维导图文字折行：按 maxChars 折，最多 3 行，超长截断 */
    private static String[] wrapMindText(String text, int maxChars) {
        String[] paragraphs = text == null ? new String[0] : text.split("\n");
        List<String> out = new ArrayList<>();
        int totalLines = 0;
        for (String para : paragraphs) {
            if (totalLines >= 3) {
                break;
            }
            String p = para.trim();
            if (p.isEmpty()) {
                continue;
            }
            int idx = 0;
            while (idx < p.length() && totalLines < 3) {
                int len = Math.min(maxChars, p.length() - idx);
                out.add(p.substring(idx, idx + len));
                idx += len;
                totalLines++;
            }
        }
        if (out.isEmpty()) {
            return new String[]{"—"};
        }
        return out.toArray(new String[0]);
    }

    // ============================ 数据解析 ============================

    private static final class NodeInfo {
        String id;
        String type;
        double cx;
        double cy;
        /** 形状包围盒（半宽/半高），text 节点为文字估算盒 */
        double hw;
        double hh;
        /** rect 圆角 */
        double radius;
        /** ellipse/diamond 半径 */
        double rx;
        double ry;
        /** polygon 顶点（相对中心） */
        List<double[]> points;
        String fill = DEFAULT_FILL;
        String stroke = DEFAULT_STROKE;
        double strokeWidth = DEFAULT_STROKE_WIDTH;
        String textValue;
        /** NaN = 未显式指定，回落到节点中心 */
        double textX = Double.NaN;
        double textY = Double.NaN;
        String textColor = DEFAULT_TEXT_COLOR;
        double fontSize = DEFAULT_FONT_SIZE;
    }

    private static final class EdgeInfo {
        List<double[]> points;
        String stroke = DEFAULT_STROKE;
        double strokeWidth = DEFAULT_STROKE_WIDTH;
        String textValue;
        double textX;
        double textY;
    }

    private NodeInfo parseNode(JsonNode n) {
        if (n == null || !n.hasNonNull("id")) {
            return null;
        }
        NodeInfo info = new NodeInfo();
        info.id = n.get("id").asText();
        info.type = n.path("type").asText("rect");
        info.cx = n.path("x").asDouble(0);
        info.cy = n.path("y").asDouble(0);
        JsonNode props = n.path("properties");
        JsonNode style = props.path("style");

        double width = numOrNull(props.get("width"));
        double height = numOrNull(props.get("height"));
        switch (info.type) {
            case "circle" -> {
                double r = Math.max(1, firstPositive(props.get("r"), CIRCLE_R));
                info.hw = r;
                info.hh = r;
            }
            case "ellipse" -> {
                info.rx = firstPositive(props.get("rx"), ELLIPSE_RX);
                info.ry = firstPositive(props.get("ry"), ELLIPSE_RY);
                info.hw = info.rx;
                info.hh = info.ry;
            }
            case "diamond" -> {
                info.rx = firstPositive(props.get("rx"), DIAMOND_RX);
                info.ry = firstPositive(props.get("ry"), DIAMOND_RY);
                info.hw = info.rx;
                info.hh = info.ry;
            }
            case "polygon" -> {
                info.points = parsePoints(props.get("points"));
                if (info.points.isEmpty()) {
                    // 兜底为默认菱形化多边形
                    info.hw = RECT_W / 2;
                    info.hh = RECT_H / 2;
                } else {
                    double minX = 0, maxX = 0, minY = 0, maxY = 0;
                    for (double[] p : info.points) {
                        minX = Math.min(minX, p[0]);
                        maxX = Math.max(maxX, p[0]);
                        minY = Math.min(minY, p[1]);
                        maxY = Math.max(maxY, p[1]);
                    }
                    info.hw = Math.max(1, Math.max(maxX, -minX));
                    info.hh = Math.max(1, Math.max(maxY, -minY));
                }
            }
            default -> {
                // rect / text 及其他未知类型按矩形处理
                info.hw = Math.max(1, width > 0 ? width / 2 : RECT_W / 2);
                info.hh = Math.max(1, height > 0 ? height / 2 : RECT_H / 2);
                info.radius = Math.max(0, firstNonNegative(
                        style.get("radius"), props.get("radius"), 0));
            }
        }

        info.fill = textOrDefault(style.get("fill"), DEFAULT_FILL);
        info.stroke = textOrDefault(style.get("stroke"), DEFAULT_STROKE);
        double sw = numOrNull(style.get("strokeWidth"));
        info.strokeWidth = sw > 0 ? sw : DEFAULT_STROKE_WIDTH;

        // 文字：string 或 {value, x, y}
        JsonNode text = n.get("text");
        if (text != null) {
            if (text.isTextual()) {
                info.textValue = text.asText();
            } else if (text.isObject()) {
                info.textValue = text.path("value").asText("");
                double tx = numOrNull(text.get("x"));
                double ty = numOrNull(text.get("y"));
                info.textX = Double.isNaN(tx) ? info.cx : tx;
                info.textY = Double.isNaN(ty) ? info.cy : ty;
            }
        }
        if (Double.isNaN(info.textX) && info.textValue != null) {
            info.textX = info.cx;
            info.textY = info.cy;
        }
        if (info.textValue != null) {
            info.textValue = info.textValue.trim();
            if (info.textValue.isEmpty()) {
                info.textValue = null;
            }
        }
        JsonNode textStyle = props.path("textStyle");
        info.textColor = textOrDefault(textStyle.get("fill"), DEFAULT_TEXT_COLOR);
        double fs = numOrNull(textStyle.get("fontSize"));
        info.fontSize = fs > 0 ? fs : DEFAULT_FONT_SIZE;

        // text 类型节点：形状不画，包围盒按文字估算（用于 viewBox）
        if ("text".equals(info.type) && info.textValue != null) {
            String[] lines = info.textValue.split("\n");
            double maxLen = 0;
            for (String line : lines) {
                maxLen = Math.max(maxLen, line.length());
            }
            info.hw = Math.max(10, maxLen * info.fontSize * 0.6) / 2;
            info.hh = Math.max(10, lines.length * info.fontSize * LINE_HEIGHT_RATIO) / 2;
            info.fill = "none";
            info.stroke = "none";
            info.strokeWidth = 0;
        }
        if (Double.isNaN(info.textX)) {
            info.textX = info.cx;
        }
        if (Double.isNaN(info.textY)) {
            info.textY = info.cy;
        }
        return info;
    }

    private EdgeInfo parseEdge(JsonNode e, Map<String, NodeInfo> byId) {
        if (e == null) {
            return null;
        }
        EdgeInfo info = new EdgeInfo();
        JsonNode style = e.path("properties").path("style");
        info.stroke = textOrDefault(style.get("stroke"), DEFAULT_STROKE);
        double sw = numOrNull(style.get("strokeWidth"));
        info.strokeWidth = sw > 0 ? sw : DEFAULT_STROKE_WIDTH;

        info.points = parsePoints(e.get("pointsList"));
        if (info.points.size() < 2) {
            // 无路径点：起点/终点 → 两节点中心连线并裁剪到包围盒
            info.points = new ArrayList<>();
            double[] sp = readPoint(e.get("startPoint"));
            double[] ep = readPoint(e.get("endPoint"));
            NodeInfo source = byId.get(e.path("sourceNodeId").asText(""));
            NodeInfo target = byId.get(e.path("targetNodeId").asText(""));
            double sx = sp != null ? sp[0] : (source != null ? source.cx : Double.NaN);
            double sy = sp != null ? sp[1] : (source != null ? source.cy : Double.NaN);
            double tx = ep != null ? ep[0] : (target != null ? target.cx : Double.NaN);
            double ty = ep != null ? ep[1] : (target != null ? target.cy : Double.NaN);
            if (Double.isNaN(sx) || Double.isNaN(sy) || Double.isNaN(tx) || Double.isNaN(ty)
                    || (sx == tx && sy == ty)) {
                return null;
            }
            if (source != null && sp == null) {
                double[] hit = boxIntersect(source, tx, ty);
                if (hit != null) {
                    sx = hit[0];
                    sy = hit[1];
                }
            }
            if (target != null && ep == null) {
                double[] hit = boxIntersect(target, sx, sy);
                if (hit != null) {
                    tx = hit[0];
                    ty = hit[1];
                }
            }
            info.points.add(new double[]{sx, sy});
            info.points.add(new double[]{tx, ty});
        }

        JsonNode text = e.get("text");
        if (text != null && text.isObject()) {
            String value = text.path("value").asText("").trim();
            if (!value.isEmpty()) {
                info.textValue = value;
                double tx = numOrNull(text.get("x"));
                double ty = numOrNull(text.get("y"));
                if (!Double.isNaN(tx) && !Double.isNaN(ty)) {
                    info.textX = tx;
                    info.textY = ty;
                } else {
                    double[] mid = midpoint(info.points);
                    info.textX = mid[0];
                    info.textY = mid[1];
                }
            }
        }
        return info;
    }

    /** 从节点中心朝 (toX,toY) 方向与包围盒的交点（近似所有形状） */
    private double[] boxIntersect(NodeInfo node, double toX, double toY) {
        double dx = toX - node.cx;
        double dy = toY - node.cy;
        if (dx == 0 && dy == 0) {
            return null;
        }
        double hw = node.hw + node.strokeWidth / 2;
        double hh = node.hh + node.strokeWidth / 2;
        double t = Double.MAX_VALUE;
        if (dx != 0) {
            t = Math.min(t, hw / Math.abs(dx));
        }
        if (dy != 0) {
            t = Math.min(t, hh / Math.abs(dy));
        }
        if (t == Double.MAX_VALUE) {
            return null;
        }
        return new double[]{node.cx + dx * t, node.cy + dy * t};
    }

    private static double[] midpoint(List<double[]> pts) {
        double[] mid = pts.get(pts.size() / 2);
        if (pts.size() % 2 == 0) {
            double[] prev = pts.get(pts.size() / 2 - 1);
            return new double[]{(prev[0] + mid[0]) / 2, (prev[1] + mid[1]) / 2};
        }
        return mid;
    }

    private List<double[]> parsePoints(JsonNode arr) {
        List<double[]> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode p : arr) {
            if (p != null && p.isArray() && p.size() >= 2) {
                out.add(new double[]{p.get(0).asDouble(0), p.get(1).asDouble(0)});
            } else if (p != null && p.isObject()) {
                double x = numOrNull(p.get("x"));
                double y = numOrNull(p.get("y"));
                if (!Double.isNaN(x) && !Double.isNaN(y)) {
                    out.add(new double[]{x, y});
                }
            }
        }
        return out;
    }

    private double[] readPoint(JsonNode p) {
        if (p == null || !p.isObject()) {
            return null;
        }
        double x = numOrNull(p.get("x"));
        double y = numOrNull(p.get("y"));
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return null;
        }
        return new double[]{x, y};
    }

    private static double numOrNull(JsonNode n) {
        return n != null && n.isNumber() ? n.asDouble() : Double.NaN;
    }

    private static double firstPositive(JsonNode n, double fallback) {
        double v = numOrNull(n);
        return !Double.isNaN(v) && v > 0 ? v : fallback;
    }

    private static double firstNonNegative(JsonNode first, JsonNode second, double fallback) {
        double v = numOrNull(first);
        if (!Double.isNaN(v) && v >= 0) {
            return v;
        }
        v = numOrNull(second);
        return !Double.isNaN(v) && v >= 0 ? v : fallback;
    }

    private static String textOrDefault(JsonNode n, String fallback) {
        if (n != null && n.isTextual() && !n.asText().isBlank()) {
            return n.asText().trim();
        }
        return fallback;
    }

    // ============================ SVG 构建 ============================

    private String buildSvg(List<NodeInfo> nodes, List<EdgeInfo> edges) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (NodeInfo n : nodes) {
            minX = Math.min(minX, n.cx - n.hw);
            maxX = Math.max(maxX, n.cx + n.hw);
            minY = Math.min(minY, n.cy - n.hh);
            maxY = Math.max(maxY, n.cy + n.hh);
        }
        for (EdgeInfo e : edges) {
            for (double[] p : e.points) {
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
            }
        }
        if (minX >= maxX || minY >= maxY) {
            return null;
        }
        minX -= PADDING;
        minY -= PADDING;
        maxX += PADDING;
        maxY += PADDING;
        double w = maxX - minX;
        double h = maxY - minY;

        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(fmt(w))
                .append("\" height=\"").append(fmt(h))
                .append("\" viewBox=\"").append(fmt(minX)).append(' ').append(fmt(minY))
                .append(' ').append(fmt(w)).append(' ').append(fmt(h)).append("\">");

        // 连线先画（位于节点下方）
        for (EdgeInfo e : edges) {
            drawEdge(sb, e);
        }
        // 节点形状
        for (NodeInfo n : nodes) {
            drawNodeShape(sb, n);
        }
        // 文字最后画（浮在形状与连线之上）
        for (NodeInfo n : nodes) {
            if (n.textValue != null) {
                drawText(sb, n.textValue, n.textX, n.textY, n.fontSize, n.textColor, null);
            }
        }
        for (EdgeInfo e : edges) {
            if (e.textValue != null) {
                drawText(sb, e.textValue, e.textX, e.textY, DEFAULT_FONT_SIZE, DEFAULT_STROKE, "#FFFFFF");
            }
        }
        sb.append("</svg>");
        return sb.toString();
    }

    private void drawEdge(StringBuilder sb, EdgeInfo e) {
        if (e.points.size() < 2) {
            return;
        }
        sb.append("<polyline points=\"");
        for (double[] p : e.points) {
            sb.append(fmt(p[0])).append(',').append(fmt(p[1])).append(' ');
        }
        sb.append("\" fill=\"none\" stroke=\"").append(xmlEscape(e.stroke))
                .append("\" stroke-width=\"").append(fmt(e.strokeWidth))
                .append("\" stroke-linejoin=\"round\"/>");
        // 箭头：沿最后一段方向的三角形（不用 marker，Batik 兼容更稳）
        double[] end = e.points.get(e.points.size() - 1);
        double[] prev = e.points.get(e.points.size() - 2);
        double dx = end[0] - prev[0];
        double dy = end[1] - prev[1];
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6) {
            return;
        }
        double ux = dx / len;
        double uy = dy / len;
        double bx = end[0] - ux * ARROW_LEN;
        double by = end[1] - uy * ARROW_LEN;
        double vx = -uy;
        double vy = ux;
        sb.append("<polygon points=\"")
                .append(fmt(end[0])).append(',').append(fmt(end[1])).append(' ')
                .append(fmt(bx + vx * ARROW_HALF_W)).append(',').append(fmt(by + vy * ARROW_HALF_W)).append(' ')
                .append(fmt(bx - vx * ARROW_HALF_W)).append(',').append(fmt(by - vy * ARROW_HALF_W))
                .append("\" fill=\"").append(xmlEscape(e.stroke)).append("\"/>");
    }

    private void drawNodeShape(StringBuilder sb, NodeInfo n) {
        if ("none".equals(n.stroke) && "none".equals(n.fill)) {
            return;
        }
        String styleAttrs = " fill=\"" + xmlEscape(n.fill) + "\" stroke=\"" + xmlEscape(n.stroke)
                + "\" stroke-width=\"" + fmt(n.strokeWidth) + "\"";
        switch (n.type) {
            case "circle" -> sb.append("<circle cx=\"").append(fmt(n.cx)).append("\" cy=\"").append(fmt(n.cy))
                    .append("\" r=\"").append(fmt(n.hw)).append("\"").append(styleAttrs).append("/>");
            case "ellipse" -> sb.append("<ellipse cx=\"").append(fmt(n.cx)).append("\" cy=\"").append(fmt(n.cy))
                    .append("\" rx=\"").append(fmt(n.rx)).append("\" ry=\"").append(fmt(n.ry))
                    .append("\"").append(styleAttrs).append("/>");
            case "diamond" -> sb.append("<polygon points=\"")
                    .append(fmt(n.cx)).append(',').append(fmt(n.cy - n.ry)).append(' ')
                    .append(fmt(n.cx + n.rx)).append(',').append(fmt(n.cy)).append(' ')
                    .append(fmt(n.cx)).append(',').append(fmt(n.cy + n.ry)).append(' ')
                    .append(fmt(n.cx - n.rx)).append(',').append(fmt(n.cy))
                    .append("\" stroke-linejoin=\"round\"").append(styleAttrs).append("/>");
            case "polygon" -> {
                if (n.points != null && !n.points.isEmpty()) {
                    sb.append("<polygon points=\"");
                    for (double[] p : n.points) {
                        sb.append(fmt(n.cx + p[0])).append(',').append(fmt(n.cy + p[1])).append(' ');
                    }
                    sb.append("\" stroke-linejoin=\"round\"").append(styleAttrs).append("/>");
                }
            }
            default -> sb.append("<rect x=\"").append(fmt(n.cx - n.hw)).append("\" y=\"").append(fmt(n.cy - n.hh))
                    .append("\" width=\"").append(fmt(n.hw * 2)).append("\" height=\"").append(fmt(n.hh * 2))
                    .append(n.radius > 0 ? "\" rx=\"" + fmt(n.radius) : "")
                    .append("\"").append(styleAttrs).append("/>");
        }
    }

    /** 多行文字：逐行 text-anchor=middle，垂直按行高居中（手工算基线，不依赖 dominant-baseline） */
    private void drawText(StringBuilder sb, String value, double x, double y, double fontSize,
                          String color, String haloColor) {
        String[] lines = value.split("\n");
        double lh = fontSize * LINE_HEIGHT_RATIO;
        double firstBaseline = y - (lines.length - 1) * lh / 2 + fontSize * 0.35;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            double lineY = firstBaseline + i * lh;
            if (haloColor != null) {
                sb.append("<text x=\"").append(fmt(x)).append("\" y=\"").append(fmt(lineY))
                        .append("\" text-anchor=\"middle\" font-family=\"").append(FONT_STACK)
                        .append("\" font-size=\"").append(fmt(fontSize))
                        .append("\" fill=\"none\" stroke=\"").append(haloColor)
                        .append("\" stroke-width=\"4\">").append(xmlEscape(line)).append("</text>");
            }
            sb.append("<text x=\"").append(fmt(x)).append("\" y=\"").append(fmt(lineY))
                    .append("\" text-anchor=\"middle\" font-family=\"").append(FONT_STACK)
                    .append("\" font-size=\"").append(fmt(fontSize))
                    .append("\" fill=\"").append(xmlEscape(color)).append("\">")
                    .append(xmlEscape(line)).append("</text>");
        }
    }

    private static String fmt(double v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String xmlEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    // ============================ 栅格化 ============================

    /**
     * 公式 SVG → PNG 字节（需求3 Batch3 增补：数学公式导出）。
     *
     * 复用流程图自绘 SVG 的同一条栅格化路径（Batik PNGTranscoder），额外做一件流程图不需要的事
     * —— **字体替换**：MathJax 把数学字形全部描成纯 {@code <path>}（不依赖任何字体，这正是选它
     * 而非 KaTeX 的原因），但 `\text{中文}` 这类非数学字符会退化成
     * {@code <text font-family="serif">}；`serif` 在无桌面字体的服务器上解析不到中文字形，
     * 导出里会变成方框或空白。统一换成项目已验证的中文字体栈（与流程图同一套）。
     *
     * 失败返回 null，调用方（{@code RepoDocExportService.preprocess}）保留节点原文
     * ——元素文本就是 TeX 源码，导出里降级成可读文本而不是空白。
     */
    public byte[] rasterizeMath(String svg) {
        if (svg == null || svg.isBlank()) {
            return null;
        }
        String localized = svg.replace("font-family=\"serif\"", "font-family=\"" + FONT_STACK + "\"");
        try {
            return rasterize(localized);
        } catch (Exception e) {
            log.warn("rasterize math formula failed: {}", e.getMessage());
            return null;
        }
    }

    private byte[] rasterize(String svg) throws Exception {
        PNGTranscoder transcoder = new PNGTranscoder();
        transcoder.addTranscodingHint(ImageTranscoder.KEY_BACKGROUND_COLOR, Color.WHITE);
        double svgWidth = parseWidth(svg);
        if (svgWidth > 0) {
            float target = (float) Math.min(svgWidth * SCALE, MAX_PNG_WIDTH);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, target);
        }
        TranscoderInput input = new TranscoderInput(new StringReader(svg));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transcoder.transcode(input, new TranscoderOutput(out));
        byte[] bytes = out.toByteArray();
        return bytes.length == 0 ? null : bytes;
    }

    /**
     * 取 SVG 根标签的宽度数值。自绘 SVG 是裸数字（{@code width="820"}），公式 SVG 带 px 后缀
     * （{@code width="69.59px"}，前端已把 ex/em 换算成 px 以规避 Batik 对 ex 的不确定性）——
     * 后缀会让 {@code Double.parseDouble} 直接抛异常并导致宽度丢失（PNG 退化成 1 倍、发虚），
     * 故统一截到第一个非数值字符为止。
     */
    private double parseWidth(String svg) {
        try {
            int idx = svg.indexOf("width=\"");
            if (idx < 0) {
                return -1;
            }
            int start = idx + "width=\"".length();
            int end = svg.indexOf('"', start);
            String raw = svg.substring(start, end).trim();
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                boolean leadingSign = (c == '-' || c == '+') && digits.length() == 0;
                if (Character.isDigit(c) || c == '.' || leadingSign) {
                    digits.append(c);
                } else {
                    break;
                }
            }
            return digits.length() == 0 ? -1 : Double.parseDouble(digits.toString());
        } catch (Exception e) {
            return -1;
        }
    }
}
