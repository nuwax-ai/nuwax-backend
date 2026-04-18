package com.xspaceagi.compose.spec.utils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.util.StringUtils;

import com.xspaceagi.compose.sdk.vo.doris.DorisTableDefinitionVo;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;

@Slf4j
public class DDLSqlParseUtil {

    /**
     * 解析建表语句，提取表的基本信息 (使用 JSqlParser 结合字符串处理)
     *
     * @param createTableDdl SHOW CREATE TABLE 返回的 DDL 字符串
     * @param definition     待填充的 DorisTableDefinitionVo 对象
     */
    public static void parseCreateTableDdl(String createTableDdl, DorisTableDefinitionVo definition) {
        if (!StringUtils.hasText(createTableDdl)) {
            log.warn("createTableDdl is empty, cannot parse.");
            return;
        }

        try {
            Statement statement = CCJSqlParserUtil.parse(createTableDdl);

            if (statement instanceof CreateTable createTable) {

                // 1. 使用 JSqlParser 提取表名 (主要是验证) 和标准选项
                // definition.setTable(createTable.getTable().getName()); // 通常由调用者设置

                List<String> options = createTable.getTableOptionsStrings();
                if (options != null) {
                    boolean commentFound = false;
                    boolean engineFound = false;
                    for (int i = 0; i < options.size(); i++) {
                        String option = options.get(i).toUpperCase();
                        // 提取 COMMENT (标准方式)
                        if (!commentFound && option.equals("COMMENT") && i + 1 < options.size()) {
                            // JSqlParser 通常会将带引号的值作为下一个元素
                            String comment = options.get(i + 1);
                            // 去除可能的引号
                            if (comment.startsWith("'") && comment.endsWith("'")) {
                                comment = comment.substring(1, comment.length() - 1);
                            }
                            definition.setComment(comment);
                            commentFound = true;
                            i++; // 跳过值
                        }
                        // 提取 ENGINE (标准方式) - Doris 通常是 ENGINE=OLAP，可能不在此处
                        else if (!engineFound && option.equals("ENGINE") && i + 2 < options.size()
                                && options.get(i + 1).equals("=")) {
                            definition.setEngine(options.get(i + 2));
                            engineFound = true;
                            i += 2; // 跳过 '=' 和值
                        }
                        // JSqlParser v5 might parse ENGINE=OLAP directly in some cases
                        else if (!engineFound && option.startsWith("ENGINE=")) {
                            definition.setEngine(option.substring(7).trim());
                            engineFound = true;
                        }
                    }
                    if (!commentFound) {
                        log.debug("JSqlParser: no COMMENT in TableOptions, falling back to string parse.");
                    }
                    if (!engineFound) {
                        log.debug("JSqlParser: no ENGINE in TableOptions, falling back to string parse.");
                    }
                } else {
                    log.debug("JSqlParser: TableOptions not found.");
                }

                // JSqlParser 提取列定义 (主要用于未来扩展，当前 V_o 不存储详细列信息)
                /* ... (注释掉的代码) ... */

                // JSqlParser 提取索引 (可能不适用于 Doris 的 DUPLICATE/AGGREGATE KEY)
                /* ... (注释掉的代码) ... */

            } else {
                log.warn("Statement is not CREATE TABLE: {}", statement.getClass().getName());
                // 如果不是 CreateTable，后续的字符串解析可能也无效
                // 但仍然尝试字符串解析，以防是其他 CREATE 语句变体
            }

        } catch (JSQLParserException e) {
            log.warn("JSqlParser DDL parse failed: {}, full string fallback.", e.getMessage());
            // 不再抛出异常，继续尝试字符串解析
        } catch (Exception e) {
            log.warn("JSqlParser unexpected error: {}, full string fallback.", e.getMessage(), e);
            // 不再抛出异常，继续尝试字符串解析
        }

        // 2. 使用字符串处理补充或覆盖 JSqlParser 未能解析的 Doris 特定信息
        // (即使 JSqlParser 成功，也执行这部分以确保 Doris 特有信息被提取)

        // 提取表注释 (覆盖或补充)
        try {
            // 使用更健壮的查找方式，匹配 COMMENT '...' 后跟换行或 PROPERTIES
            int commentIndex = createTableDdl.indexOf("COMMENT '");
            int propertiesIndexForComment = createTableDdl.indexOf("\nPROPERTIES");
            int newLineAfterComment = createTableDdl.indexOf("'\n", commentIndex + 9); // 查找 COMMENT '...' \n
            int endQuoteIndex = -1;

            if (commentIndex > 0) {
                 if (newLineAfterComment > commentIndex + 8) { // 优先匹配 ' 后直接换行的
                     endQuoteIndex = newLineAfterComment;
                 } else if (propertiesIndexForComment > commentIndex + 8) { // 其次匹配 ' 后跟 PROPERTIES 的
                     int quoteBeforeProp = createTableDdl.lastIndexOf("'", propertiesIndexForComment -1);
                     if (quoteBeforeProp > commentIndex + 8) {
                         endQuoteIndex = quoteBeforeProp;
                     }
                  } else { // 最后尝试普通匹配 '...') 或 '...'
                       int endParenIndex = createTableDdl.indexOf("')", commentIndex + 9);
                       int endAloneIndex = createTableDdl.indexOf("'", commentIndex + 9);
                       if (endParenIndex > 0 && (endAloneIndex == -1 || endParenIndex < endAloneIndex)) {
                            endQuoteIndex = endParenIndex;
                       } else {
                            endQuoteIndex = endAloneIndex;
                       }
                  }

                if (endQuoteIndex > commentIndex + 8) {
                    definition.setComment(createTableDdl.substring(commentIndex + 9, endQuoteIndex));
                } else if (definition.getComment() == null){ // 只有当 JSqlParser 也没解析到时才告警
                    log.warn("String parse: cannot parse table comment. DDL: {}", createTableDdl);
                }
             } else if (definition.getComment() == null) {
                 log.warn("String parse: COMMENT not found. DDL: {}", createTableDdl);
             }
        } catch (Exception e) {
            log.warn("String parse table comment error: {}", e.getMessage());
        }

        // 提取表引擎 (覆盖或补充)
        try {
             // Doris 通常是 ENGINE=OLAP 后跟换行
             String enginePattern = "ENGINE=OLAP";
             int engineIndex = createTableDdl.indexOf(enginePattern);
             if (engineIndex > 0) {
                 // 确认后面是换行符或空格
                 int engineEndIndex = engineIndex + enginePattern.length();
                 if (engineEndIndex >= createTableDdl.length() ||
                     createTableDdl.charAt(engineEndIndex) == '\n' ||
                     createTableDdl.charAt(engineEndIndex) == ' ' ) {
                     definition.setEngine("OLAP");
                 } else if (definition.getEngine() == null) {
                      log.warn("String parse: ENGINE=OLAP but unexpected tail. DDL: {}", createTableDdl);
                 }
             } else if (definition.getEngine() == null){
                log.debug("String parse: no ENGINE=OLAP, trying MySQL engine. DDL: {}", createTableDdl);
                // 尝试解析 ENGINE = MySQL 引擎
                 int mysqlEngineIndex = createTableDdl.indexOf("ENGINE=");
                 if (mysqlEngineIndex > 0) {
                    int engineEndIndex = createTableDdl.indexOf("\n", mysqlEngineIndex);
                    if (engineEndIndex == -1) engineEndIndex = createTableDdl.indexOf(" ", mysqlEngineIndex);
                    if (engineEndIndex == -1) engineEndIndex = createTableDdl.length();
                    if(engineEndIndex > mysqlEngineIndex + 7) {
                        definition.setEngine(createTableDdl.substring(mysqlEngineIndex + 7, engineEndIndex).trim());
                        log.info("Parsed MySQL engine: {}", definition.getEngine());
                    }
                 }
             }
        } catch (Exception e) {
             log.warn("String parse table engine error: {}", e.getMessage());
        }


        // 提取分桶数
        try {
            int bucketsIndex = createTableDdl.indexOf("BUCKETS ");
            if (bucketsIndex > 0) {
                int bucketsEndIndex = createTableDdl.indexOf("\n", bucketsIndex);
                 if (bucketsEndIndex == -1) {
                     bucketsEndIndex = createTableDdl.indexOf("PROPERTIES", bucketsIndex);
                      if (bucketsEndIndex == -1) {
                          bucketsEndIndex = createTableDdl.length();
                      }
                 }

                if (bucketsEndIndex > bucketsIndex + 8) {
                    String bucketsStr = createTableDdl.substring(bucketsIndex + 8, bucketsEndIndex).trim();
                    try {
                        definition.setBuckets(Integer.parseInt(bucketsStr));
                    } catch (NumberFormatException nfe) {
                         log.warn("String parse: invalid bucket count '{}'. DDL: {}", bucketsStr, createTableDdl);
                    }
                } else {
                     log.warn("String parse: bucket count end not found. DDL: {}", createTableDdl);
                }
            }
        } catch (Exception e) {
             log.warn("String parse bucket count error: {}", e.getMessage());
        }

        // 提取表属性 (包括副本数)
         try {
            Map<String, String> properties = new HashMap<>();
            int propertiesIndex = createTableDdl.indexOf("PROPERTIES (");
            if (propertiesIndex > 0) {
                int propertiesEndIndex = createTableDdl.lastIndexOf(")"); // 找到最后一个 ')' 作为结束
                if (propertiesEndIndex > propertiesIndex + 11) {
                     String propertiesStr = createTableDdl.substring(propertiesIndex + 12, propertiesEndIndex).trim();
                     for (String property : propertiesStr.split(",")) {
                        String trimmedProperty = property.trim();
                        if (StringUtils.hasText(trimmedProperty)) {
                            // 处理 key = value 对，去除引号
                            int equalsIndex = trimmedProperty.indexOf("=");
                            if (equalsIndex > 0) {
                                String key = trimmedProperty.substring(0, equalsIndex).trim().replace("\"", "");
                                String value = trimmedProperty.substring(equalsIndex + 1).trim().replace("\"", "");
                                if (StringUtils.hasText(key)) {
                                    properties.put(key, value);
                                    // 特别提取副本数
                                    if ("replication_num".equalsIgnoreCase(key)) {
                                        try {
                                            definition.setReplicationNum(Integer.parseInt(value));
                                        } catch (NumberFormatException nfe) {
                                            log.warn("String parse: invalid replication_num '{}'.", value);
                                        }
                                    }
                                }
                             } else {
                                 log.warn("String parse: invalid PROPERTIES attr format: {}", trimmedProperty);
                             }
                        }
                    }
                } else {
                     log.warn("String parse: PROPERTIES block invalid. DDL: {}", createTableDdl);
                }
            }
            if (!properties.isEmpty()) {
                definition.setProperties(properties);
            }
         } catch (Exception e) {
             log.warn("String parse PROPERTIES error: {}", e.getMessage());
         }


        // 提取分布键
        try {
            String distributedPattern = "DISTRIBUTED BY HASH(";
            int distributedIndex = createTableDdl.indexOf(distributedPattern);
            if (distributedIndex > 0) {
                int distributedEndIndex = createTableDdl.indexOf(")", distributedIndex + distributedPattern.length());
                if (distributedEndIndex > distributedIndex + distributedPattern.length()) {
                    String keysStr = createTableDdl
                            .substring(distributedIndex + distributedPattern.length(), distributedEndIndex)
                            .replace("`", "");
                    String[] keys = keysStr.split(",");
                    List<String> keyList = Arrays.stream(keys).map(String::trim).filter(StringUtils::hasText)
                            .collect(Collectors.toList());
                    if (!keyList.isEmpty()) {
                       definition.setDistributedKeys(keyList);
                    } else {
                         log.warn("String parse: empty distribution keys. Keys: '{}', DDL: {}", keysStr, createTableDdl);
                    }
                } else {
                    log.warn("String parse: distribution keys end not found. DDL: {}", createTableDdl);
                }
            }
        } catch (Exception e) {
             log.warn("String parse distribution keys error: {}", e.getMessage());
        }

        // 提取重复键/聚合键/唯一键 (Doris 支持多种 Key 类型)
        try {
            String keyPattern = null;
            int keyIndex = -1;
            int keyNameLength = 0;

            if ((keyIndex = createTableDdl.indexOf("DUPLICATE KEY(")) > 0) {
                keyPattern = "DUPLICATE KEY(";
                keyNameLength = keyPattern.length();
            } else if ((keyIndex = createTableDdl.indexOf("AGGREGATE KEY(")) > 0) {
                keyPattern = "AGGREGATE KEY(";
                 keyNameLength = keyPattern.length();
            } else if ((keyIndex = createTableDdl.indexOf("UNIQUE KEY(")) > 0) {
                 keyPattern = "UNIQUE KEY(";
                  keyNameLength = keyPattern.length();
            }

            if (keyPattern != null && keyIndex > 0) {
                int keyEndIndex = createTableDdl.indexOf(")", keyIndex + keyNameLength);
                 if (keyEndIndex > keyIndex + keyNameLength) {
                    String keysStr = createTableDdl.substring(keyIndex + keyNameLength, keyEndIndex).replace("`", "");
                    String[] keys = keysStr.split(",");
                    List<String> keyList = Arrays.stream(keys).map(String::trim).filter(StringUtils::hasText)
                            .collect(Collectors.toList());
                    if (!keyList.isEmpty()) {
                        // 统一存到 DuplicateKeys 里，或者需要扩展 V_o 以区分 Key 类型
                        definition.setDuplicateKeys(keyList);
                    } else {
                         log.warn("String parse: {} is empty. Keys: '{}', DDL: {}", keyPattern, keysStr, createTableDdl);
                    }
                } else {
                     log.warn("String parse: cannot parse {}: end paren not found. DDL: {}", keyPattern, createTableDdl);
                }
            }
        } catch (Exception e) {
             log.warn("String parse table key error: {}", e.getMessage());
        }

    }
}
