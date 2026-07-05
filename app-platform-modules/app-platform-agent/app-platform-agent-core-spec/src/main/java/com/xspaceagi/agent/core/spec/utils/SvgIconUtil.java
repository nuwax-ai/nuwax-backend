package com.xspaceagi.agent.core.spec.utils;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 与 {@code IconGenerator} 默认 logo 输出尺寸保持一致（200x200 PNG）。
 */
public final class SvgIconUtil {

    public static final int ICON_SIZE = 200;

    public static final double VIEW_BOX_SIZE = 100D;

    public static final String DEFAULT_VIEW_BOX = "0 0 100 100";

    private static final double CONTENT_PADDING = 4D;

    /** 内容已占画布该比例时，不再额外缩放，避免对已合理的 AI 输出二次处理。 */
    private static final double MIN_FILL_RATIO_FOR_SKIP = 0.68D;

    /** 缩放倍率接近 1 时不包裹 transform，避免无意义的结构改动。 */
    private static final double MIN_SCALE_TO_APPLY = 1.12D;

    private static final Pattern TAG_PATTERN = Pattern.compile("<(circle|rect|ellipse|line|polyline|polygon|path|text)\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    private static final Pattern VIEW_BOX_PATTERN = Pattern.compile("viewBox\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final Pattern BACKGROUND_RECT_PATTERN = Pattern.compile("<rect\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    private static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?");

    private SvgIconUtil() {
    }

    public static String normalize(String svgIcon) {
        if (StringUtils.isBlank(svgIcon)) {
            return svgIcon;
        }
        String svg = stripMarkdownFence(svgIcon.trim());
        int start = svg.indexOf("<svg");
        if (start < 0) {
            return svg;
        }
        int openEnd = svg.indexOf('>', start);
        int closeStart = svg.toLowerCase().lastIndexOf("</svg>");
        if (openEnd < 0 || closeStart < 0 || closeStart <= openEnd) {
            return svg;
        }

        String openTag = svg.substring(start, openEnd + 1);
        String content = svg.substring(openEnd + 1, closeStart).trim();
        ViewBox viewBox = parseViewBox(openTag);
        ContentParts parts = splitBackground(content, viewBox);
        Bounds bounds = mapBoundsToTargetViewBox(estimateBounds(parts.foreground(), viewBox), viewBox);

        StringBuilder normalized = new StringBuilder();
        normalized.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(ICON_SIZE).append("\" height=\"")
                .append(ICON_SIZE).append("\" viewBox=\"").append(DEFAULT_VIEW_BOX).append("\">");
        if (StringUtils.isNotBlank(parts.background())) {
            normalized.append(normalizeBackgroundRect(parts.background()));
        }
        if (StringUtils.isNotBlank(parts.foreground())) {
            normalized.append(wrapForeground(parts.foreground(), viewBox, bounds));
        }
        normalized.append("</svg>");
        return normalized.toString();
    }

    private static String wrapForeground(String foreground, ViewBox viewBox, Bounds bounds) {
        String wrapped = foreground;
        if (!isStandardViewBox(viewBox)) {
            wrapped = "<g transform=\"" + buildViewBoxTransform(viewBox) + "\">" + wrapped + "</g>";
        }
        if (shouldApplyFitTransform(bounds)) {
            wrapped = "<g transform=\"" + buildFitTransform(bounds) + "\">" + wrapped + "</g>";
        }
        return wrapped;
    }

    private static boolean shouldApplyFitTransform(Bounds bounds) {
        double fillRatio = Math.max(bounds.width(), bounds.height()) / VIEW_BOX_SIZE;
        if (fillRatio >= MIN_FILL_RATIO_FOR_SKIP) {
            return false;
        }
        double available = VIEW_BOX_SIZE - CONTENT_PADDING * 2;
        double scale = Math.min(available / bounds.width(), available / bounds.height());
        return scale >= MIN_SCALE_TO_APPLY;
    }

    private static boolean isStandardViewBox(ViewBox viewBox) {
        return Math.abs(viewBox.minX()) < 0.001D
                && Math.abs(viewBox.minY()) < 0.001D
                && Math.abs(viewBox.width() - VIEW_BOX_SIZE) < 0.001D
                && Math.abs(viewBox.height() - VIEW_BOX_SIZE) < 0.001D;
    }

    private static String buildViewBoxTransform(ViewBox viewBox) {
        double scale = VIEW_BOX_SIZE / Math.max(viewBox.width(), viewBox.height());
        return "translate(" + format(-viewBox.minX() * scale) + " " + format(-viewBox.minY() * scale) + ") scale(" + format(scale) + ")";
    }

    private static Bounds mapBoundsToTargetViewBox(Bounds bounds, ViewBox viewBox) {
        if (isStandardViewBox(viewBox)) {
            return bounds;
        }
        double scale = VIEW_BOX_SIZE / Math.max(viewBox.width(), viewBox.height());
        Bounds mapped = new Bounds();
        mapped.includePoint((bounds.minX() - viewBox.minX()) * scale, (bounds.minY() - viewBox.minY()) * scale);
        mapped.includePoint((bounds.maxX() - viewBox.minX()) * scale, (bounds.maxY() - viewBox.minY()) * scale);
        return mapped;
    }

    private static String normalizeBackgroundRect(String rectTag) {
        StringBuilder rect = new StringBuilder("<rect x=\"0\" y=\"0\" width=\"100\" height=\"100\"");
        copyAttr(rect, rectTag, "fill");
        copyAttr(rect, rectTag, "rx");
        copyAttr(rect, rectTag, "ry");
        copyAttr(rect, rectTag, "stroke");
        copyAttr(rect, rectTag, "stroke-width");
        rect.append("/>");
        return rect.toString();
    }

    private static void copyAttr(StringBuilder target, String tag, String name) {
        String value = getAttrRaw(tag, name);
        if (StringUtils.isNotBlank(value)) {
            target.append(' ').append(name).append("=\"").append(value).append('"');
        }
    }

    private static ContentParts splitBackground(String content, ViewBox viewBox) {
        Matcher matcher = BACKGROUND_RECT_PATTERN.matcher(content);
        if (!matcher.find()) {
            return new ContentParts(null, content);
        }
        String rectTag = matcher.group();
        double x = getAttr(rectTag, "x", -1);
        double y = getAttr(rectTag, "y", -1);
        double width = getAttr(rectTag, "width", 0);
        double height = getAttr(rectTag, "height", 0);
        boolean fullCanvasBackground = x <= 1 && y <= 1
                && width >= viewBox.width() * 0.9
                && height >= viewBox.height() * 0.9;
        if (!fullCanvasBackground) {
            return new ContentParts(null, content);
        }
        String foreground = (content.substring(0, matcher.start()) + content.substring(matcher.end())).trim();
        return new ContentParts(rectTag, foreground);
    }

    private static String stripMarkdownFence(String svg) {
        if (!svg.startsWith("```")) {
            return svg;
        }
        return svg.replaceFirst("```(?:svg)?\\s*", "").replaceFirst("```\\s*$", "").trim();
    }

    private static ViewBox parseViewBox(String openTag) {
        Matcher matcher = VIEW_BOX_PATTERN.matcher(openTag);
        if (matcher.find()) {
            String[] parts = matcher.group(1).trim().split("[\\s,]+");
            if (parts.length == 4) {
                return new ViewBox(
                        Double.parseDouble(parts[0]),
                        Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3]));
            }
        }
        double width = getAttr(openTag, "width", VIEW_BOX_SIZE);
        double height = getAttr(openTag, "height", VIEW_BOX_SIZE);
        return new ViewBox(0, 0, width, height);
    }

    private static Bounds estimateBounds(String content, ViewBox viewBox) {
        if (StringUtils.isBlank(content)) {
            return Bounds.fromViewBox(viewBox);
        }
        Bounds bounds = new Bounds();
        Matcher matcher = TAG_PATTERN.matcher(content);
        while (matcher.find()) {
            String tag = matcher.group();
            if (isDecorativeElement(tag)) {
                continue;
            }
            String type = matcher.group(1).toLowerCase();
            switch (type) {
                case "circle" -> {
                    double cx = getAttr(tag, "cx", viewBox.centerX());
                    double cy = getAttr(tag, "cy", viewBox.centerY());
                    double r = getAttr(tag, "r", 0);
                    bounds.include(cx - r, cy - r, cx + r, cy + r);
                }
                case "ellipse" -> {
                    double cx = getAttr(tag, "cx", viewBox.centerX());
                    double cy = getAttr(tag, "cy", viewBox.centerY());
                    double rx = getAttr(tag, "rx", 0);
                    double ry = getAttr(tag, "ry", 0);
                    bounds.include(cx - rx, cy - ry, cx + rx, cy + ry);
                }
                case "rect" -> {
                    double x = getAttr(tag, "x", 0);
                    double y = getAttr(tag, "y", 0);
                    double width = getAttr(tag, "width", 0);
                    double height = getAttr(tag, "height", 0);
                    bounds.include(x, y, x + width, y + height);
                }
                case "line" -> {
                    bounds.includePoint(getAttr(tag, "x1", 0), getAttr(tag, "y1", 0));
                    bounds.includePoint(getAttr(tag, "x2", 0), getAttr(tag, "y2", 0));
                }
                case "polyline", "polygon" -> includePoints(bounds, getAttrRaw(tag, "points"));
                case "path" -> includePathNumbers(bounds, getAttrRaw(tag, "d"));
                case "text" -> bounds.includePoint(getAttr(tag, "x", viewBox.centerX()), getAttr(tag, "y", viewBox.centerY()));
                default -> {
                }
            }
        }
        if (!bounds.isValid()) {
            return Bounds.fromViewBox(viewBox);
        }
        return bounds;
    }

    private static boolean isDecorativeElement(String tag) {
        String opacityRaw = getAttrRaw(tag, "opacity");
        if (StringUtils.isBlank(opacityRaw)) {
            opacityRaw = getAttrRaw(tag, "fill-opacity");
        }
        if (StringUtils.isBlank(opacityRaw)) {
            return false;
        }
        try {
            return Double.parseDouble(opacityRaw) < 0.5D;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void includePoints(Bounds bounds, String points) {
        if (StringUtils.isBlank(points)) {
            return;
        }
        String[] parts = points.trim().split("[\\s,]+");
        for (int i = 0; i + 1 < parts.length; i += 2) {
            bounds.includePoint(Double.parseDouble(parts[i]), Double.parseDouble(parts[i + 1]));
        }
    }

    private static void includePathNumbers(Bounds bounds, String pathData) {
        if (StringUtils.isBlank(pathData)) {
            return;
        }
        List<Double> numbers = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(pathData);
        while (matcher.find()) {
            numbers.add(Double.parseDouble(matcher.group()));
        }
        for (int i = 0; i + 1 < numbers.size(); i += 2) {
            bounds.includePoint(numbers.get(i), numbers.get(i + 1));
        }
    }

    private static String buildFitTransform(Bounds bounds) {
        double available = VIEW_BOX_SIZE - CONTENT_PADDING * 2;
        double scale = Math.min(available / bounds.width(), available / bounds.height());
        return "translate(" + format(VIEW_BOX_SIZE / 2) + " " + format(VIEW_BOX_SIZE / 2) + ") "
                + "scale(" + format(scale) + ") "
                + "translate(" + format(-bounds.centerX()) + " " + format(-bounds.centerY()) + ")";
    }

    private static double getAttr(String tag, String name, double defaultValue) {
        String raw = getAttrRaw(tag, name);
        if (StringUtils.isBlank(raw)) {
            return defaultValue;
        }
        return Double.parseDouble(raw);
    }

    private static String getAttrRaw(String tag, String name) {
        Matcher matcher = Pattern.compile("\\b" + name + "\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(tag);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = Pattern.compile("\\b" + name + "\\s*=\\s*'([^']+)'", Pattern.CASE_INSENSITIVE).matcher(tag);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static String format(double value) {
        if (Math.rint(value) == value) {
            return String.valueOf((long) value);
        }
        return String.format("%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private record ContentParts(String background, String foreground) {
    }

    private record ViewBox(double minX, double minY, double width, double height) {
        double centerX() {
            return minX + width / 2;
        }

        double centerY() {
            return minY + height / 2;
        }
    }

    private static final class Bounds {
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;

        void include(double x1, double y1, double x2, double y2) {
            includePoint(x1, y1);
            includePoint(x2, y2);
        }

        void includePoint(double x, double y) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        boolean isValid() {
            return minX != Double.POSITIVE_INFINITY && width() > 0 && height() > 0;
        }

        double minX() {
            return minX;
        }

        double minY() {
            return minY;
        }

        double maxX() {
            return maxX;
        }

        double maxY() {
            return maxY;
        }

        double width() {
            return maxX - minX;
        }

        double height() {
            return maxY - minY;
        }

        double centerX() {
            return (minX + maxX) / 2;
        }

        double centerY() {
            return (minY + maxY) / 2;
        }

        static Bounds fromViewBox(ViewBox viewBox) {
            Bounds bounds = new Bounds();
            bounds.include(viewBox.minX(), viewBox.minY(), viewBox.minX() + viewBox.width(), viewBox.minY() + viewBox.height());
            return bounds;
        }
    }
}
