package com.xspaceagi.userapp.application.util;

import java.util.regex.Pattern;

import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;

/**
 * 工作空间目录校验
 */
public final class WorkspacePathValidator {

    /** 与数据库一致 */
    private static final int MAX_LENGTH = 512;

    /** Windows 盘符绝对路径：C:/a/b、裸盘符 C:/（与 POSIX 裸根 / 同等对待，是否可用由白名单约束） */
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:/.*");

    private WorkspacePathValidator() {
    }

    /**
     * 归一化并校验目录；空白返回 null，非法抛业务异常。
     */
    public static String normalize(String workspacePath) {
        if (workspacePath == null || workspacePath.isBlank()) {
            return null;
        }
        String dir = workspacePath.trim();
        if (dir.length() > MAX_LENGTH) {
            throw invalid("length exceeds " + MAX_LENGTH);
        }
        if (dir.indexOf('\0') >= 0 || dir.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw invalid("contains illegal characters");
        }
        // 分隔符统一为 /，并折叠连续分隔符（保留 UNC 的前导 //）
        boolean unc = dir.startsWith("//") || dir.startsWith("\\\\");
        dir = dir.replace('\\', '/').replaceAll("//+", "/");
        if (unc) {
            dir = "/" + dir;
        }
        // Windows 盘符大小写不敏感，统一为大写（c:/x 与 C:/x 视为同一路径，白名单比较才一致）
        if (dir.length() >= 2 && dir.charAt(1) == ':' && Character.isLetter(dir.charAt(0))) {
            dir = Character.toUpperCase(dir.charAt(0)) + dir.substring(1);
        }
        if (!dir.startsWith("/") && !WINDOWS_DRIVE.matcher(dir).matches()) {
            throw invalid("must be an absolute path (POSIX /a/b, Windows C:/a/b or UNC //server/share/a/b)");
        }
        for (String segment : dir.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw invalid("dot segments are not allowed");
            }
        }
        // 抹掉结尾分隔符：与不带斜杠语义上是同一目录（占用比较按精确串匹配，不抹会绕过）
        while (dir.endsWith("/") && dir.length() > 1) {
            dir = dir.substring(0, dir.length() - 1);
        }
        return dir;
    }

    private static BizException invalid(String reason) {
        return new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "Invalid workspacePath: " + reason);
    }
}
