package com.xspaceagi.agent.core.spec.utils;

import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;

public class FileTypeUtils {

    /**
     * 根据文件名获取对应的 Content-Type
     */
    public static MediaType getContentTypeByFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }

        String lowerFileName = fileName.toLowerCase();

        // 图片类型
        if (lowerFileName.endsWith(".jpg") || lowerFileName.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        } else if (lowerFileName.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        } else if (lowerFileName.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        } else if (lowerFileName.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        } else if (lowerFileName.endsWith(".svg")) {
            return MediaType.parseMediaType("image/svg+xml");
        } else if (lowerFileName.endsWith(".ico")) {
            return MediaType.parseMediaType("image/x-icon");
        } else if (lowerFileName.endsWith(".bmp")) {
            return MediaType.parseMediaType("image/bmp");
        }
        // 文本类型 - 添加 UTF-8 编码以避免乱码
        else if (lowerFileName.endsWith(".html") || lowerFileName.endsWith(".htm")) {
            return new MediaType("text", "html", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".css")) {
            return new MediaType("text", "css", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".js")) {
            return new MediaType("application", "javascript", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".json")) {
            return new MediaType("application", "json", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".xml")) {
            return new MediaType("application", "xml", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".txt") || lowerFileName.endsWith(".text")) {
            return new MediaType("text", "plain", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".md")
                || lowerFileName.endsWith(".markdown")
                || lowerFileName.endsWith(".mdown")
                || lowerFileName.endsWith(".mkd")) {
            return new MediaType("text", "markdown", StandardCharsets.UTF_8);
        }
        // 代码文件
        else if (lowerFileName.endsWith(".py")) {
            return new MediaType("text", "x-python", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".java")) {
            return new MediaType("text", "x-java-source", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".ts")) {
            return new MediaType("text", "typescript", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".tsx")) {
            return new MediaType("text", "tsx", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".jsx")) {
            return new MediaType("text", "jsx", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".go")) {
            return new MediaType("text", "x-go", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".rs")) {
            return new MediaType("text", "rust", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".c") || lowerFileName.endsWith(".h")) {
            return new MediaType("text", "x-c", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".cpp") || lowerFileName.endsWith(".hpp")
                || lowerFileName.endsWith(".cc") || lowerFileName.endsWith(".cxx")) {
            return new MediaType("text", "x-c++", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".cs")) {
            return new MediaType("text", "x-csharp", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".php")) {
            return new MediaType("text", "x-php", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".rb")) {
            return new MediaType("text", "x-ruby", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".swift")) {
            return new MediaType("text", "x-swift", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".kt") || lowerFileName.endsWith(".kts")) {
            return new MediaType("text", "x-kotlin", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".scala") || lowerFileName.endsWith(".sc")) {
            return new MediaType("text", "x-scala", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".clj")) {
            return new MediaType("text", "x-clojure", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".lua")) {
            return new MediaType("text", "x-lua", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".sql")) {
            return new MediaType("application", "sql", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".sh") || lowerFileName.endsWith(".bash")
                || lowerFileName.endsWith(".zsh")) {
            return new MediaType("application", "x-sh", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".fish")) {
            return new MediaType("application", "x-fish", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".ps1")) {
            return new MediaType("text", "x-powershell", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".bat") || lowerFileName.endsWith(".cmd")) {
            return new MediaType("application", "x-bat", StandardCharsets.UTF_8);
        }
        // 配置文件
        else if (lowerFileName.endsWith(".yaml") || lowerFileName.endsWith(".yml")) {
            return new MediaType("application", "x-yaml", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".toml")) {
            return new MediaType("application", "toml", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".csv")) {
            return new MediaType("text", "csv", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".tsv")) {
            return new MediaType("text", "tab-separated-values", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".ini") || lowerFileName.endsWith(".conf")
                || lowerFileName.endsWith(".config") || lowerFileName.endsWith(".properties")
                || lowerFileName.endsWith(".env") || lowerFileName.endsWith(".gitignore")
                || lowerFileName.endsWith(".gitattributes") || lowerFileName.endsWith(".editorconfig")
                || lowerFileName.endsWith(".log")) {
            return new MediaType("text", "plain", StandardCharsets.UTF_8);
        }
        // 样式与文档标记
        else if (lowerFileName.endsWith(".xhtml")) {
            return new MediaType("application", "xhtml+xml", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".scss") || lowerFileName.endsWith(".sass")) {
            return new MediaType("text", "x-scss", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".less")) {
            return new MediaType("text", "x-less", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".styl")) {
            return new MediaType("text", "x-stylus", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".rst")) {
            return new MediaType("text", "x-rst", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".adoc") || lowerFileName.endsWith(".asciidoc")) {
            return new MediaType("text", "asciidoc", StandardCharsets.UTF_8);
        } else if (lowerFileName.endsWith(".diff") || lowerFileName.endsWith(".patch")) {
            return new MediaType("text", "x-diff", StandardCharsets.UTF_8);
        }
        // 其他类型
        else if (lowerFileName.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        } else if (lowerFileName.endsWith(".doc")) {
            return MediaType.parseMediaType("application/msword");
        } else if (lowerFileName.endsWith(".docx")) {
            return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        } else if (lowerFileName.endsWith(".xls")) {
            return MediaType.parseMediaType("application/vnd.ms-excel");
        } else if (lowerFileName.endsWith(".xlsx")) {
            return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } else if (lowerFileName.endsWith(".ppt")) {
            return MediaType.parseMediaType("application/vnd.ms-powerpoint");
        } else if (lowerFileName.endsWith(".pptx")) {
            return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        } else if (lowerFileName.endsWith(".zip")) {
            return MediaType.parseMediaType("application/zip");
        } else if (lowerFileName.endsWith(".woff") || lowerFileName.endsWith(".woff2")) {
            return MediaType.parseMediaType("font/woff");
        } else if (lowerFileName.endsWith(".ttf")) {
            return MediaType.parseMediaType("font/ttf");
        } else if (lowerFileName.endsWith(".eot")) {
            return MediaType.parseMediaType("application/vnd.ms-fontobject");
        }

        // 默认返回二进制流
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /**
     * 判断文件是否为文本文件（白名单方式）
     * 只对明确已知的文本文件类型按文本处理，其他一律按二进制处理，更安全
     */
    public static boolean isTextFile(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        String lowerFileName = fileName.toLowerCase();

        // 纯文本格式
        if (lowerFileName.endsWith(".txt") || lowerFileName.endsWith(".text")) {
            return true;
        }

        // 代码文件
        if (lowerFileName.endsWith(".js") || lowerFileName.endsWith(".jsx")
                || lowerFileName.endsWith(".ts") || lowerFileName.endsWith(".tsx")
                || lowerFileName.endsWith(".java") || lowerFileName.endsWith(".py")
                || lowerFileName.endsWith(".go") || lowerFileName.endsWith(".rs")
                || lowerFileName.endsWith(".cpp") || lowerFileName.endsWith(".c")
                || lowerFileName.endsWith(".h") || lowerFileName.endsWith(".hpp")
                || lowerFileName.endsWith(".cs") || lowerFileName.endsWith(".php")
                || lowerFileName.endsWith(".rb") || lowerFileName.endsWith(".swift")
                || lowerFileName.endsWith(".kt") || lowerFileName.endsWith(".scala")
                || lowerFileName.endsWith(".clj") || lowerFileName.endsWith(".lua")
                || lowerFileName.endsWith(".sh") || lowerFileName.endsWith(".bash")
                || lowerFileName.endsWith(".zsh") || lowerFileName.endsWith(".fish")
                || lowerFileName.endsWith(".ps1") || lowerFileName.endsWith(".bat")
                || lowerFileName.endsWith(".cmd")) {
            return true;
        }

        // 标记语言
        if (lowerFileName.endsWith(".html") || lowerFileName.endsWith(".htm")
                || lowerFileName.endsWith(".xml") || lowerFileName.endsWith(".xhtml")
                || lowerFileName.endsWith(".svg")) {
            return true;
        }

        // 样式文件
        if (lowerFileName.endsWith(".css") || lowerFileName.endsWith(".scss")
                || lowerFileName.endsWith(".sass") || lowerFileName.endsWith(".less")
                || lowerFileName.endsWith(".styl")) {
            return true;
        }

        // 配置文件
        if (lowerFileName.endsWith(".json") || lowerFileName.endsWith(".yaml")
                || lowerFileName.endsWith(".yml") || lowerFileName.endsWith(".toml")
                || lowerFileName.endsWith(".ini") || lowerFileName.endsWith(".conf")
                || lowerFileName.endsWith(".config") || lowerFileName.endsWith(".properties")
                || lowerFileName.endsWith(".env") || lowerFileName.endsWith(".gitignore")
                || lowerFileName.endsWith(".gitattributes") || lowerFileName.endsWith(".editorconfig")) {
            return true;
        }

        // 文档格式
        if (lowerFileName.endsWith(".md") || lowerFileName.endsWith(".markdown")
                || lowerFileName.endsWith(".mdown") || lowerFileName.endsWith(".mkd")
                || lowerFileName.endsWith(".rst") || lowerFileName.endsWith(".adoc")
                || lowerFileName.endsWith(".asciidoc")) {
            return true;
        }

        // 数据格式
        if (lowerFileName.endsWith(".csv") || lowerFileName.endsWith(".tsv")
                || lowerFileName.endsWith(".sql") || lowerFileName.endsWith(".log")) {
            return true;
        }

        // 其他文本格式
        if (lowerFileName.endsWith(".rtf") || lowerFileName.endsWith(".diff")
                || lowerFileName.endsWith(".patch") || lowerFileName.endsWith(".lock")) {
            return true;
        }

        // 默认返回 false，即非文本文件（按二进制处理）
        return false;
    }
}