package com.xspaceagi.im.web.util;

import com.xspaceagi.agent.core.adapter.application.IComputerFileApplicationService;
import com.xspaceagi.im.web.service.ImFileShareService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IM 智能机器人输出内容统一处理工具
 * <p>
 * 用于处理飞书、钉钉、企业微信等平台的智能体输出内容，确保：
 * 1. 移除内部标签和分隔符
 * 2. 还原工具输出中的转义字符
 * 3. 替换文件标签为 URL
 * 4. 规范化 Markdown 格式
 * <p>
 * 统一处理逻辑，避免各平台重复实现
 */
@Slf4j
public class ImOutputProcessor {

    /**
     * 内部消息分隔符（用于在 AI 消息流中分隔不同片段）
     */
    private static final String INTERNAL_DIV_PATTERN = "<div class=\"ai-message-divider\"></div>";

    /**
     * 内部标签正则（匹配任意 HTML 标签）
     */
    private static final String INTERNAL_TAG_PATTERN = "<[^>]+>";

    /**
     * 文件标签正则（匹配 <file>filename</file>）
     */
    private static final Pattern FILE_TAG_PATTERN = Pattern.compile("<file>([^<]+)</file>");

    /**
     * 通用文件路径正则（匹配多种操作系统的文件路径格式）
     * <p>
     * 支持的格式：
     * - Linux/MacOS: /home/user/file.txt, /Users/username/project/file.py
     * - Windows: C:\\Users\\file.txt, D:/project/file.md
     * - 云端沙箱: /home/user/12345/file.txt
     * <p>
     * 限制条件：
     * - 必须包含文件扩展名（.txt, .md, .py等）
     * - 避免匹配HTML标签和目录路径
     */
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
            // 匹配以盘符开头的Windows路径（如 C:\path\file.txt）
            // 前置负向约束，避免误匹配 file:///D:/... 里的 e:/... 片段
            "(?<![A-Za-z0-9_])([A-Za-z]:[\\\\/][^\\s\"'`)\\]]+\\.[a-zA-Z0-9]+)|" +
            // 匹配以 / 开头的Unix/Linux/MacOS路径（支持中文等 Unicode 文件名）
            "(/[^\\s\"'`)\\]]+\\.[a-zA-Z0-9]+)" +
            // 确保路径以文件扩展名结束
            "(?<!\\.markdown-custom-process)(?<!\\.div)"
    );

    /**
     * 文件名前缀识别（如：文件名:xxx.md）
     */
    private static final Pattern FILE_NAME_PREFIX_PATTERN = Pattern.compile(
            "(?i)(文件名\\s*[:：]\\s*|file name\\s*[:：]\\s*)([^\\s<>'\"`\\[\\]]+\\.[a-zA-Z0-9]+)"
    );

    /**
     * 反引号包裹的文件名/相对路径
     */
    private static final Pattern BACKTICK_FILE_PATTERN = Pattern.compile("`([^`\\n]+\\.[a-zA-Z0-9]+)`");
    private static final Pattern PURE_MARKDOWN_LINK_LINE_PATTERN = Pattern.compile("^\\s*\\[([^\\]]+)\\]\\([^)]+\\)\\s*$");
    private static final Pattern MARKDOWN_LINK_CAPTURE_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    // 兼容 ```...``` / ```\n...\n``` / ```markdown\n...\n``` 等多种围栏格式
    private static final Pattern CODE_FENCE_BLOCK_PATTERN = Pattern.compile("```+[^\\n`]*\\n?([\\s\\S]*?)\\n?```+");
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[[^\\]]+\\]\\([^)]+\\)");

    /**
     * 工具调用标签正则（匹配 <div><markdown-custom-process type="ToolCall" name="..."></markdown-custom-process></div>）
     */
    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile("<div>\\s*<markdown-custom-process[^>]*type\\s*=\\s*\"ToolCall\"[^>]*name\\s*=\\s*\"([^\"]+)\"[^>]*/?>\\s*</markdown-custom-process>\\s*</div>", Pattern.CASE_INSENSITIVE);

    private static String stripWrapping(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) ||
            (v.startsWith("'") && v.endsWith("'")) ||
            (v.startsWith("`") && v.endsWith("`"))) {
            if (v.length() >= 2) {
                v = v.substring(1, v.length() - 1).trim();
            }
        }
        return v;
    }

    private static String normalizeRelativePathForCompare(String path) {
        if (path == null) {
            return null;
        }
        // 保持相对路径语义：去掉开头的 /，并统一分隔符
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /**
     * 判断匹配片段是否处于 Markdown 链接 label 中：
     * 例如在 [xxx](url) 的 xxx 区域内，避免重复替换导致 [[...](...)](...)
     */
    private static boolean isInsideMarkdownLinkLabel(String text, int start, int end) {
        if (StringUtils.isBlank(text) || start < 0 || end <= start || end > text.length()) {
            return false;
        }
        int leftBracket = text.lastIndexOf('[', start);
        if (leftBracket < 0 || leftBracket >= start) {
            return false;
        }
        int rightBracket = text.indexOf(']', start);
        if (rightBracket < 0 || rightBracket < end) {
            return false;
        }
        // rightBracket 后必须紧跟 '(' 才是 markdown 链接
        if (rightBracket + 1 >= text.length() || text.charAt(rightBracket + 1) != '(') {
            return false;
        }
        int rightParen = text.indexOf(')', rightBracket + 2);
        return rightParen > rightBracket + 1;
    }

    /**
     * 判断匹配位置是否位于“工具调用”行，避免把工具调用说明里的路径替换成链接。
     */
    private static boolean isInToolCallLine(String text, int matchStart) {
        if (StringUtils.isBlank(text) || matchStart < 0 || matchStart > text.length()) {
            return false;
        }
        int lineStart = text.lastIndexOf('\n', Math.max(0, matchStart - 1));
        lineStart = lineStart < 0 ? 0 : lineStart + 1;
        int lineEnd = text.indexOf('\n', matchStart);
        lineEnd = lineEnd < 0 ? text.length() : lineEnd;
        String line = text.substring(lineStart, lineEnd).trim();
        return line.startsWith("> 🔧 工具调用：");
    }

    private static class FileEntry {
        private final String name; // 归一化后的相对路径（无开头/，分隔符为/）
        private final String basename; // name 的最后一段
        private final String fileProxyUrl;

        private FileEntry(String name, String basename, String fileProxyUrl) {
            this.name = name;
            this.basename = basename;
            this.fileProxyUrl = fileProxyUrl;
        }
    }

    private static List<FileEntry> fetchFileEntries(Long userId, Long conversationId,
                                                    IComputerFileApplicationService computerFileApplicationService) {
        List<FileEntry> entries = new ArrayList<>();
        if (conversationId == null || userId == null || computerFileApplicationService == null) {
            return entries;
        }

        try {
            String proxyPath = String.format("/api/computer/static/%s", conversationId);
            Map<String, Object> result = computerFileApplicationService.getFileList(userId, conversationId, proxyPath, null);
            if (result == null) {
                return entries;
            }

            Object filesObj = result.get("files");
            // 兼容另一种返回结构: { data: { files: [...] } }
            if (!(filesObj instanceof List)) {
                Object dataObj = result.get("data");
                if (dataObj instanceof Map) {
                    filesObj = ((Map<?, ?>) dataObj).get("files");
                }
            }
            if (!(filesObj instanceof List)) {
                return entries;
            }

            List<?> files = (List<?>) filesObj;
            for (Object f : files) {
                if (!(f instanceof Map)) {
                    continue;
                }
                Map<?, ?> fm = (Map<?, ?>) f;
                Object nameObj = fm.get("name");
                Object proxyObj = fm.get("fileProxyUrl");
                if (nameObj == null || proxyObj == null) {
                    continue;
                }

                String name = String.valueOf(nameObj);
                String proxyUrl = String.valueOf(proxyObj);
                if (StringUtils.isBlank(name) || StringUtils.isBlank(proxyUrl)) {
                    continue;
                }

                String normalizedName = normalizeRelativePathForCompare(name);
                String basename = extractFilename(normalizedName);
                if (StringUtils.isBlank(normalizedName) || StringUtils.isBlank(basename)) {
                    continue;
                }

                entries.add(new FileEntry(normalizedName, basename, proxyUrl));
            }
        } catch (Exception e) {
            log.warn("[fetchFileEntries] 获取文件列表失败: userId={}, conversationId={}, error={}",
                    userId, conversationId, e.getMessage());
        }
        return entries;
    }

    private static String toMarkdownLinkIfMatched(String originalToken, List<FileEntry> entries,
                                                  Map<String, String> proxyByExactName,
                                                  Map<String, String> proxyByBasename,
                                                  String domain,
                                                  ImFileShareService fileShareService,
                                                  Long tenantId,
                                                  Long userId,
                                                  Long conversationId) {
        if (StringUtils.isBlank(originalToken)) {
            return originalToken;
        }

        String tokenForLabel = stripWrapping(originalToken);
        if (StringUtils.isBlank(tokenForLabel)) {
            return originalToken;
        }

        String tokenNorm = normalizeRelativePathForCompare(tokenForLabel);
        if (StringUtils.isBlank(tokenNorm)) {
            return originalToken;
        }

        String tokenBasename = extractFilename(tokenNorm);
        if (StringUtils.isBlank(tokenBasename)) {
            return originalToken;
        }

        String proxyUrl = proxyByExactName.get(tokenNorm);
        if (proxyUrl == null) {
            proxyUrl = proxyByBasename.get(tokenBasename);
        }

        if (proxyUrl == null && entries != null) {
            // 兜底：支持 token 是“完整路径”但我们只有相对路径 name 的情况
            for (FileEntry entry : entries) {
                if (entry == null || StringUtils.isBlank(entry.name)) {
                    continue;
                }
                if (tokenNorm.equals(entry.name) || tokenNorm.endsWith("/" + entry.name)) {
                    proxyUrl = entry.fileProxyUrl;
                    break;
                }
            }
        }

        if (proxyUrl == null) {
            return originalToken;
        }

        String finalUrl = proxyUrl;
        if (StringUtils.isNotBlank(domain)) {
            // 优先使用分享链接（带 sk），若无法生成则回退为完整代理链接
            if (fileShareService != null && tenantId != null && userId != null && conversationId != null) {
                String shareUrl = getShareUrl(domain, fileShareService, tenantId, userId, conversationId, proxyUrl);
                if (StringUtils.isNotBlank(shareUrl)) {
                    finalUrl = shareUrl;
                } else if (proxyUrl.startsWith("/")) {
                    finalUrl = domain + proxyUrl;
                }
            } else if (proxyUrl.startsWith("/")) {
                finalUrl = domain + proxyUrl;
            }
        }
        return "[" + tokenForLabel + "](" + finalUrl + ")";
    }

    private static String resolveMatchedUrl(String token, List<FileEntry> entries,
                                            Map<String, String> proxyByExactName,
                                            Map<String, String> proxyByBasename,
                                            String domain,
                                            ImFileShareService fileShareService,
                                            Long tenantId,
                                            Long userId,
                                            Long conversationId) {
        if (StringUtils.isBlank(token)) {
            return null;
        }
        String tokenForLookup = stripWrapping(token);
        if (StringUtils.isBlank(tokenForLookup)) {
            return null;
        }
        String tokenNorm = normalizeRelativePathForCompare(tokenForLookup);
        if (StringUtils.isBlank(tokenNorm)) {
            return null;
        }
        String tokenBasename = extractFilename(tokenNorm);
        if (StringUtils.isBlank(tokenBasename)) {
            return null;
        }

        String proxyUrl = proxyByExactName.get(tokenNorm);
        if (proxyUrl == null) {
            proxyUrl = proxyByBasename.get(tokenBasename);
        }
        if (proxyUrl == null && entries != null) {
            for (FileEntry entry : entries) {
                if (entry == null || StringUtils.isBlank(entry.name)) {
                    continue;
                }
                if (tokenNorm.equals(entry.name) || tokenNorm.endsWith("/" + entry.name)) {
                    proxyUrl = entry.fileProxyUrl;
                    break;
                }
            }
        }
        if (proxyUrl == null) {
            return null;
        }

        String finalUrl = proxyUrl;
        if (StringUtils.isNotBlank(domain)) {
            if (fileShareService != null && tenantId != null && userId != null && conversationId != null) {
                String shareUrl = getShareUrl(domain, fileShareService, tenantId, userId, conversationId, proxyUrl);
                if (StringUtils.isNotBlank(shareUrl)) {
                    finalUrl = shareUrl;
                } else if (proxyUrl.startsWith("/")) {
                    finalUrl = domain + proxyUrl;
                }
            } else if (proxyUrl.startsWith("/")) {
                finalUrl = domain + proxyUrl;
            }
        }
        return finalUrl;
    }

    /**
     * 从文件路径中提取文件名
     *
     * @param filePath 文件路径（可能包含多层目录）
     * @return 提取的文件名
     */
    private static String extractFilename(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return filePath;
        }
        // 处理 Unix 路径分隔符 /
        int lastUnixSlash = filePath.lastIndexOf('/');
        // 处理 Windows 路径分隔符 \
        int lastWindowsSlash = filePath.lastIndexOf('\\');
        // 取最后一个分隔符之后的内容
        int lastSeparator = Math.max(lastUnixSlash, lastWindowsSlash);
        if (lastSeparator >= 0) {
            return filePath.substring(lastSeparator + 1);
        }
        return filePath;
    }

    /**
     * 替换工具调用标签
     * 将 <div><markdown-custom-process type="ToolCall" name="..."></markdown-custom-process></div>
     * 替换为 *工具调用：...*
     */
    private static String replaceToolCallTags(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = TOOL_CALL_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String toolName = matcher.group(1);
            // 使用引用块，让工具调用更明显
            // 引用块后面必须加换行符，否则企业微信会把后续内容也渲染成引用块
            String replacement = "> 🔧 工具调用：" + toolName + "\n";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 移除内部标签和分隔符
     * <p>
     * 移除 AI 消息流中使用的内部标签，如 <div class="ai-message-divider"></div> 等
     *
     * @param text 原始文本
     * @return 移除内部标签后的文本
     */
    private static String removeInternalTags(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // 替换内部分隔符为换行
        text = text.replaceAll(INTERNAL_DIV_PATTERN, "\n");
        // 移除所有内部标签
        text = text.replaceAll(INTERNAL_TAG_PATTERN, "");
        return text;
    }


    /**
     * 将连续空行压缩为最多 1 个空行。
     * 例如：三连换行或多连换行 -> 保留为双换行（中间 1 行空行）。
     */
    private static String collapseConsecutiveBlankLines(String text) {
        if (StringUtils.isBlank(text)) {
            return text;
        }
        // (?m) 多行模式；允许空行中存在空格/Tab
        // 将 “至少 3 个连续换行（中间可带空白）” 替换为 “2 个连续换行（中间仅 1 个空行）”
        return text.replaceAll("(\\r?\\n)[ \\t]*(?:\\r?\\n[ \\t]*){2,}", "$1$1");
    }

    /**
     * 去掉开头/结尾的“纯空行”（允许空格/Tab）。
     * 注意：只移除空行，不影响中间非空内容及其换行结构。
     */
    private static String stripLeadingTrailingBlankLines(String text) {
        if (text == null) {
            return null;
        }
        if (text.isEmpty()) {
            return text;
        }

        String[] lines = text.split("\\r?\\n", -1);

        int start = 0;
        while (start < lines.length && lines[start].trim().isEmpty()) {
            start++;
        }

        int end = lines.length - 1;
        while (end >= start && lines[end].trim().isEmpty()) {
            end--;
        }

        if (start == 0 && end == lines.length - 1) {
            return text;
        }
        if (end < start) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (i > start) {
                sb.append("\n");
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /**
     * 移除Markdown链接周围的内联代码标记（反引号）
     * <p>
     * 智能体可能输出：文件：`[filename.md](url)`，这会导致链接无法渲染
     * 该方法会移除链接周围的反引号，使其正常显示
     *
     * @param text 包含Markdown链接的文本
     * @return 移除链接周围反引号后的文本
     */
    private static String removeInlineCodeAroundLinks(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // 移除内联代码标记包裹的Markdown链接
        // 匹配：`[text](url)` 或 `[text](url)` 这种格式
        // 保持链接本身不变，只移除外层的反引号

        // 处理单反引号包裹的情况：`[link](url)` -> [link](url)
        text = text.replaceAll("`+\\[([^\\]]+)\\]\\(([^)]+)\\)`+", "[$1]($2)");

        // 处理更复杂的情况：链接前有反引号但没有闭合的情况
        // `[link](url) -> [link](url)
        text = text.replaceAll("`+\\[", "[");

        return text;
    }

    /**
     * 去重“纯 Markdown 链接行”。
     * 典型场景：同一文件路径同时出现在 <description> 和 <file> 中，替换后出现两行重复链接。
     */
    private static String deduplicatePureLinkLines(String text) {
        if (StringUtils.isBlank(text)) {
            return text;
        }
        String[] lines = text.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder();
        String previousPureLinkLabel = null;
        boolean changed = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line == null ? "" : line.trim();
            Matcher m = PURE_MARKDOWN_LINK_LINE_PATTERN.matcher(trimmed);
            boolean isPureLink = m.matches();
            if (isPureLink) {
                String label = StringUtils.defaultString(m.group(1)).trim();
                // 仅去重“连续相同链接文案”的纯链接行（description + file 场景）
                if (StringUtils.equals(previousPureLinkLabel, label)) {
                    changed = true;
                    continue;
                }
                previousPureLinkLabel = label;
            } else {
                previousPureLinkLabel = null;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(line);
        }
        return changed ? sb.toString() : text;
    }

    /**
     * 移除包裹Markdown链接的代码块标记
     * <p>
     * 智能体可能输出：```[link](url)```，导致链接无法渲染
     * 该方法会移除链接周围的代码块标记，使其正常显示
     *
     * @param text 包含Markdown链接的文本
     * @return 移除代码块标记后的文本
     */
    private static String removeCodeBlocksAroundLinks(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        Matcher matcher = CODE_FENCE_BLOCK_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String blockContent = matcher.group(1);
            // 只要代码块内容中存在 markdown 链接，就去掉外层代码围栏
            if (MARKDOWN_LINK_PATTERN.matcher(blockContent).find()) {
                String normalized = blockContent;
                // 单行围栏场景可能把两端空白一起包进来，这里裁掉仅保留内容
                if (normalized != null) {
                    normalized = normalized.trim();
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(normalized));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 还原工具输出中的转义字符
     * <p>
     * 工具输出中的换行符、引号等被转义为 \\n、\\"，需要还原为原始字符
     * 否则 Markdown 无法正确解析换行和引号
     *
     * @param text 包含转义字符的文本
     * @return 还原转义字符后的文本
     */
    private static String unescapeToolOutput(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replace("\\n", "\n")  // 还原换行符
                .replace("\\\"", "\"") // 还原引号
                .replace("\\t", "\t")  // 还原制表符
                .replace("\\\\", "\\"); // 还原反斜杠
    }

    /**
     * 规范化 Markdown 内容，确保解析器能正确识别语法
     * <p>
     * 飞书、钉钉、企业微信等平台的 Markdown 解析器要求：
     * - ** 加粗标记与文本之间不能有换行，但可以有空格
     * - 当 ** 与前后文本粘连时，自动添加空格避免语法错误
     * <p>
     * 例如：
     * - "text**bold**" → "text **bold**"（前面加空格）
     * - "**bold**more" → "**bold** more"（后面加空格）
     *
     * @param text 原始 Markdown 文本
     * @return 规范化后的 Markdown 文本
     */
    private static String normalizeMarkdownContent(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // ** 前：若与前置文本粘连，则加空格（text** -> text **）
        text = text.replaceAll("([^\\n\\s])\\*\\*", "$1 **");

        // **...** 后：若与后置文本粘连，则加空格（**bold**more -> **bold** more）
        text = text.replaceAll("(\\*\\*[^*]*\\*\\*)([^\\n\\s*])", "$1 $2");

        // 兼容：冒号后紧跟链接时可能无法渲染，补一个空格
        // 例如 file:[a](b) -> file: [a](b)
        text = text.replaceAll("([:：])\\[", "$1 [");

        return text;
    }

    private static String getShareUrl(String domain, ImFileShareService fileShareService, Long tenantId, Long userId, Long conversationId, String proxyUrl) {
        if (fileShareService == null || tenantId == null || userId == null || conversationId == null || StringUtils.isBlank(proxyUrl)) {
            return null;
        }
        try {
            String shareKey = fileShareService.createFileShare(userId, conversationId, proxyUrl, tenantId);
            if (StringUtils.isBlank(shareKey)) {
                return null;
            }
            String separator = proxyUrl.contains("?") ? "&" : "?";
            return domain + "/static/file-preview.html" + separator + "sk=" + shareKey;
        } catch (Exception e) {
            log.warn("[getShareUrl] 生成分享链接失败, conversationId={}, proxyUrl={}, error={}", conversationId, proxyUrl, e.getMessage());
            return null;
        }
    }


//    private static String getShareUrl(String domain, ImFileShareService fileShareService, Long tenantId, Long userId, Long conversationId, String relativePath) {
//        String shareKey = getShareKey(fileShareService, tenantId, userId, conversationId, relativePath);
//        if (shareKey != null) {
//            return domain + "/static/file-preview.html?sk=" + shareKey;
//        } else {
//            log.warn("[getShareUrl] 文件分享创建失败，使用直接访问链接: relativePath={}", relativePath);
//            return getDirectUrl(domain, conversationId, relativePath);
//        }
//    }

    /**
     * 将文本中的文件信息替换为 Markdown 链接 [文件](fileProxyUrl)，用于在钉钉/飞书/企业微信中可点击。
     * <p>
     * 处理来源：
     * - `<file>filename</file>` 标签
     * - 文本中的绝对路径/相对路径（如 `/home/...`、`/xxx/...`、`/D/...`、`D:\\...` 等，且包含扩展名）
     * - `文件名:xxx.md`（或 `file name:xxx.md`）
     * <p>
     * 匹配策略：
     * - 先调用 `file-list` 获取空间下所有文件的 `name` 与 `fileProxyUrl`
     * - 将识别到的“路径/文件名”与文件列表的 `name`（以及其 basename 后缀）做匹配
     * - 匹配不到时保持原样（不会生成 Markdown 链接）
     * <p>
     * 如果存在任意文件候选且 agentId 不为空，追加会话链接：[点此链接查看会话]({fileUrlDomain}/home/chat/{conversationId}/{agentId})
     */
    private static String replaceFileTagsWithUrl(String text, Long conversationId, Long agentId, String fileUrlDomain, Long userId, Long tenantId,
                                                 ImFileShareService fileShareService, IComputerFileApplicationService computerFileApplicationService) {
        if (text == null || text.isEmpty() || conversationId == null || StringUtils.isBlank(fileUrlDomain)) {
            return text;
        }
        String domain = fileUrlDomain.endsWith("/") ? fileUrlDomain.substring(0, fileUrlDomain.length() - 1) : fileUrlDomain;

        // 只有匹配到“文件候选”时才拉取文件列表，避免无谓的 HTTP 调用
        boolean hasFileCandidate = FILE_TAG_PATTERN.matcher(text).find()
                || FILE_PATH_PATTERN.matcher(text).find()
                || FILE_NAME_PREFIX_PATTERN.matcher(text).find()
                || BACKTICK_FILE_PATTERN.matcher(text).find()
                || MARKDOWN_LINK_CAPTURE_PATTERN.matcher(text).find();
        if (!hasFileCandidate) {
            return text;
        }

        // 预先拉取空间下所有文件名 -> 代理地址，用于后续匹配替换
        // 注意：tenantId/userId/fileShareService 在新逻辑中不再参与“文件链接生成”，保留参数用于兼容老方法签名
        log.debug("[replaceFileTagsWithUrl] file-list match mode. tenantId={}, userId={}", tenantId, userId);
        List<FileEntry> fileEntries = fetchFileEntries(userId, conversationId, computerFileApplicationService);

        Map<String, String> proxyByExactName = new HashMap<>();
        Map<String, String> proxyByBasename = new HashMap<>();
        for (FileEntry entry : fileEntries) {
            if (entry == null || StringUtils.isBlank(entry.name) || StringUtils.isBlank(entry.fileProxyUrl)) {
                continue;
            }
            // 同名取第一个，避免覆盖导致不确定行为
            proxyByExactName.putIfAbsent(entry.name, entry.fileProxyUrl);
            proxyByBasename.putIfAbsent(entry.basename, entry.fileProxyUrl);
        }

        String processed = text;

        // 1) 处理 <file> 标签：<file>xxx</file> -> [xxx](fileProxyUrl)（仅匹配到时）
        Matcher matcher = FILE_TAG_PATTERN.matcher(processed);
        StringBuilder sbWithTag = new StringBuilder();
        int fileTagCount = 0;
        while (matcher.find()) {
            fileTagCount++;
            String rawToken = matcher.group(1);
            String replacement = toMarkdownLinkIfMatched(rawToken, fileEntries, proxyByExactName, proxyByBasename,
                    domain, fileShareService, tenantId, userId, conversationId);
            matcher.appendReplacement(sbWithTag, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sbWithTag);
        processed = sbWithTag.toString();

        // 2) 处理文本中的文件路径：/home/... 或 D:\\... 等
        Matcher filePathMatcher = FILE_PATH_PATTERN.matcher(processed);
        StringBuilder sbWithPath = new StringBuilder();
        int filePathCount = 0;
        while (filePathMatcher.find()) {
            filePathCount++;
            String rawToken = filePathMatcher.group();
            if (isInsideMarkdownLinkLabel(processed, filePathMatcher.start(), filePathMatcher.end())) {
                // 已在 markdown 链接 label 内，跳过，防止重复套娃
                filePathMatcher.appendReplacement(sbWithPath, Matcher.quoteReplacement(rawToken));
                continue;
            }
            if (isInToolCallLine(processed, filePathMatcher.start())) {
                // 工具调用说明行不做文件链接替换
                filePathMatcher.appendReplacement(sbWithPath, Matcher.quoteReplacement(rawToken));
                continue;
            }
            String replacement = toMarkdownLinkIfMatched(rawToken, fileEntries, proxyByExactName, proxyByBasename,
                    domain, fileShareService, tenantId, userId, conversationId);
            filePathMatcher.appendReplacement(sbWithPath, Matcher.quoteReplacement(replacement));
        }
        filePathMatcher.appendTail(sbWithPath);
        processed = sbWithPath.toString();

        // 3) 处理“文件名:xxx”
        Matcher fileNameMatcher = FILE_NAME_PREFIX_PATTERN.matcher(processed);
        StringBuilder sbWithName = new StringBuilder();
        int fileNameCount = 0;
        while (fileNameMatcher.find()) {
            fileNameCount++;
            String prefix = fileNameMatcher.group(1);
            String filename = fileNameMatcher.group(2);
            String replacementFilename = toMarkdownLinkIfMatched(filename, fileEntries, proxyByExactName, proxyByBasename,
                    domain, fileShareService, tenantId, userId, conversationId);
            fileNameMatcher.appendReplacement(sbWithName, Matcher.quoteReplacement(prefix + replacementFilename));
        }
        fileNameMatcher.appendTail(sbWithName);
        processed = sbWithName.toString();

        // 4) 处理反引号包裹的文件名/相对路径：`xxx.md`
        Matcher backtickMatcher = BACKTICK_FILE_PATTERN.matcher(processed);
        StringBuilder sbWithBacktick = new StringBuilder();
        int backtickCount = 0;
        while (backtickMatcher.find()) {
            backtickCount++;
            String token = backtickMatcher.group(1);
            String replacement = toMarkdownLinkIfMatched(token, fileEntries, proxyByExactName, proxyByBasename,
                    domain, fileShareService, tenantId, userId, conversationId);
            backtickMatcher.appendReplacement(sbWithBacktick, Matcher.quoteReplacement(replacement));
        }
        backtickMatcher.appendTail(sbWithBacktick);
        processed = sbWithBacktick.toString();

        // 5) 处理“已是 markdown 链接，但 href 是相对文件名/相对路径”的场景：
        //    [arbor_day.html](arbor_day.html) -> [arbor_day.html](https://.../static/file-preview.html?sk=...)
        Matcher markdownLinkMatcher = MARKDOWN_LINK_CAPTURE_PATTERN.matcher(processed);
        StringBuilder sbWithMarkdownLink = new StringBuilder();
        int markdownLinkCount = 0;
        while (markdownLinkMatcher.find()) {
            String label = markdownLinkMatcher.group(1);
            String href = markdownLinkMatcher.group(2);
            String hrefTrim = href == null ? "" : href.trim();

            // 仅处理相对 href，外部链接/锚点链接保持原样
            if (hrefTrim.startsWith("http://") || hrefTrim.startsWith("https://")
                    || hrefTrim.startsWith("mailto:") || hrefTrim.startsWith("#")) {
                markdownLinkMatcher.appendReplacement(sbWithMarkdownLink, Matcher.quoteReplacement(markdownLinkMatcher.group(0)));
                continue;
            }

            String resolvedUrl = resolveMatchedUrl(hrefTrim, fileEntries, proxyByExactName, proxyByBasename,
                    domain, fileShareService, tenantId, userId, conversationId);
            if (StringUtils.isBlank(resolvedUrl)) {
                markdownLinkMatcher.appendReplacement(sbWithMarkdownLink, Matcher.quoteReplacement(markdownLinkMatcher.group(0)));
                continue;
            }
            markdownLinkCount++;
            String replacement = "[" + label + "](" + resolvedUrl + ")";
            markdownLinkMatcher.appendReplacement(sbWithMarkdownLink, Matcher.quoteReplacement(replacement));
        }
        markdownLinkMatcher.appendTail(sbWithMarkdownLink);
        processed = sbWithMarkdownLink.toString();

        boolean hasAnyFile = (fileTagCount + filePathCount + fileNameCount + backtickCount + markdownLinkCount) > 0;
        if (hasAnyFile && agentId != null) {
            String sessionUrl = domain + "/home/chat/" + conversationId + "/" + agentId;
            if (!(processed.endsWith("\n") || processed.endsWith("\r\n"))) {
                processed = processed + "\n";
            }
            processed = processed + "[点此链接查看会话](" + sessionUrl + ")";
        }

        return processed;
    }

    /**
     * 处理输出内容，应用标准化处理流程
     */
    public static String processOutput(String text, Long conversationId, Long agentId, String fileUrlDomain, Long userId, Long tenantId,
                                       ImFileShareService fileShareService, IComputerFileApplicationService computerFileApplicationService) {
        log.info("---------------------- ImOutputProcessor start ----------------------");
        log.info("处理前: {}", text);
        if (text == null) {
            log.debug("处理后: null");
            log.debug("==================== ImOutputProcessor end ====================");
            return "";
        }

        // 1. 替换工具调用标签
        text = replaceToolCallTags(text);

        // 2. 替换文件标签为 URL（必须在移除内部标签之前）
        if (conversationId != null && StringUtils.isNotBlank(fileUrlDomain)) {
            text = replaceFileTagsWithUrl(text, conversationId, agentId, fileUrlDomain.trim(), userId, tenantId, fileShareService, computerFileApplicationService);
        } else {
            // 如果没有 URL 参数，至少移除 <file> 标签，保留文件名
            text = FILE_TAG_PATTERN.matcher(text).replaceAll("$1");
        }

        // 3. 移除内部标签和分隔符（如 <description>、<div> 等）
        text = removeInternalTags(text);

        // 4. 去重纯链接行（同一路径在多个内部标签中重复时保留一条）
        text = deduplicatePureLinkLines(text);

        // 5. 移除可能包裹文件名的内联代码标记（反引号）
        // 智能体可能输出：文件：`filename.md`，这会导致链接无法在代码块中渲染
        text = removeInlineCodeAroundLinks(text);

        // 6 移除包裹Markdown链接的代码块标记
        // 智能体可能输出：```[link](url)```，导致链接无法渲染
        text = removeCodeBlocksAroundLinks(text);

        // 7. 还原工具输出中的转义字符
        text = unescapeToolOutput(text);

        // 8. 再次移除包裹链接的代码块（处理转义字符还原后才出现的 ``` 场景）
        text = removeCodeBlocksAroundLinks(text);

        // 9. 规范化 Markdown 内容
        text = normalizeMarkdownContent(text);
        // 10. 合并连续多行空行：最多保留 1 个空行
        text = collapseConsecutiveBlankLines(text);
        // 11. 去掉开头/结尾的纯空行
        text = stripLeadingTrailingBlankLines(text);

        log.info("处理后: {}", text);
        log.info("==================== ImOutputProcessor end ====================");
        return text;
    }

}
