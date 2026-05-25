package com.xspaceagi.log.spec;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * 反序列化 {@link LocalDateTime}：支持 UTC/带 Z 的字符串、ISO 日期时间、仅日期 {@code yyyy-MM-dd}、以及 Unix 时间戳。
 */
@Slf4j
public class FlexibleDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

    private static final DateTimeFormatter FORMATTER_WITH_NANOS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter FORMATTER_WITH_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter FORMATTER_WITHOUT_FRACTION =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"));

    private static final DateTimeFormatter SPACE_SEPARATED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            return fromEpoch(p.getLongValue());
        }
        if (token == JsonToken.VALUE_STRING) {
            String dateString = p.getText().trim();
            if (StringUtils.isBlank(dateString)) {
                return null;
            }
            return parseString(dateString, p);
        }
        throw new JsonParseException(p, "无法解析 LocalDateTime：需要字符串或数字，实际为 " + token);
    }

    private static LocalDateTime fromEpoch(long raw) {
        Instant instant = raw > 10_000_000_000L ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
        return LocalDateTime.ofInstant(instant, BEIJING_ZONE);
    }

    private LocalDateTime parseString(String dateString, JsonParser p) throws IOException {
        try {
            if (dateString.matches(".*\\.\\d{6}Z$")) {
                Instant instant = Instant.from(FORMATTER_WITH_NANOS.parse(dateString));
                return LocalDateTime.ofInstant(instant, BEIJING_ZONE);
            }
            if (dateString.matches(".*\\.\\d{3}Z$")) {
                Instant instant = Instant.from(FORMATTER_WITH_MILLIS.parse(dateString));
                return LocalDateTime.ofInstant(instant, BEIJING_ZONE);
            }
            if (dateString.endsWith("Z") && dateString.contains("T")) {
                Instant instant = Instant.from(FORMATTER_WITHOUT_FRACTION.parse(dateString));
                return LocalDateTime.ofInstant(instant, BEIJING_ZONE);
            }
        } catch (DateTimeParseException ignored) {
            // 走下方通用解析
        }
        try {
            return LocalDateTime.ofInstant(OffsetDateTime.parse(dateString).toInstant(), BEIJING_ZONE);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(dateString, SPACE_SEPARATED);
        } catch (DateTimeParseException ignored) {
        }
        try {
            // 纯日期 yyyy-MM-dd → 当天 00:00:00（东八区墙钟）
            return LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(BEIJING_ZONE).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        log.error("无法解析日期时间: {}", dateString);
        throw new JsonParseException(p, "无法解析日期时间: " + dateString);
    }
}
