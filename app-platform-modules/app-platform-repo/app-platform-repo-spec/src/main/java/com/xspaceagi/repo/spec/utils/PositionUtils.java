package com.xspaceagi.repo.spec.utils;

import java.util.Arrays;

/**
 * fractional index 排序键工具（Figma 同款方案）：
 * 生成字典序严格介于 before 与 after 之间的字符串排序键，
 * 用于页面同级排序（repo_page.position），避免移动节点时批量改position。
 * <p>
 * 键结构：[整数部分][小数部分]
 * <ul>
 *   <li>整数部分首字符为 head：'a'..'z' 表示正方向（长度 = head-'a'+2），
 *       'A'..'Z' 表示负方向（长度 = 'Z'-head+2），head 后为 BASE-62 数字；</li>
 *   <li>小数部分为 BASE-62 数字串，不允许以 '0'（零数字）结尾（保证字典序=数值序）；</li>
 *   <li>字母表按 ASCII 排序：0-9 &lt; A-Z &lt; a-z，因此负键 &lt; 正键，整体字典序即数值序。</li>
 * </ul>
 * 默认键 "a0"（=0），向两端无限扩展，永不需要重平衡。
 */
public final class PositionUtils {

    /**
     * 字母表（62 个 URL 安全字符，ASCII 升序）
     */
    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private static final int BASE = ALPHABET.length;

    /**
     * 零数字（小数部分尾字符禁止为它）
     */
    private static final char ZERO = ALPHABET[0];

    private static final char MAX_DIGIT = ALPHABET[BASE - 1];

    private static final int[] CHAR_TO_INDEX = new int[128];

    static {
        Arrays.fill(CHAR_TO_INDEX, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            CHAR_TO_INDEX[ALPHABET[i]] = i;
        }
    }

    private PositionUtils() {
    }

    /**
     * 生成严格介于 before 与 after 之间的排序键。
     *
     * @param before 前一个键，null 表示插入到最前
     * @param after  后一个键，null 表示追加到最后
     * @return 排序键，满足 before &lt; result &lt; after（字典序）
     * @throws IllegalArgumentException before &gt;= after 或键非法
     */
    public static String between(String before, String after) {
        if (before != null) {
            validateKey(before);
        }
        if (after != null) {
            validateKey(after);
        }
        if (before != null && after != null && before.compareTo(after) >= 0) {
            throw new IllegalArgumentException("position order invalid: " + before + " >= " + after);
        }
        if (before == null && after == null) {
            return "a0";
        }
        if (before == null) {
            String integerPartAfter = getIntegerPart(after);
            String fractionalAfter = after.substring(integerPartAfter.length());
            if (fractionalAfter.isEmpty()) {
                return decrementInteger(integerPartAfter);
            }
            return integerPartAfter + midpoint("", fractionalAfter);
        }
        if (after == null) {
            String integerPartBefore = getIntegerPart(before);
            String fractionalBefore = before.substring(integerPartBefore.length());
            if (fractionalBefore.isEmpty()) {
                return incrementInteger(integerPartBefore);
            }
            return integerPartBefore + midpoint(fractionalBefore, null);
        }
        String integerPartBefore = getIntegerPart(before);
        String integerPartAfter = getIntegerPart(after);
        String fractionalBefore = before.substring(integerPartBefore.length());
        String fractionalAfter = after.substring(integerPartAfter.length());
        if (integerPartBefore.equals(integerPartAfter)) {
            return integerPartBefore + midpoint(fractionalBefore, fractionalAfter);
        }
        String incremented = incrementInteger(integerPartBefore);
        if (incremented.compareTo(integerPartAfter) < 0) {
            return incremented;
        }
        // 相邻整数键之间没有整数空位：挂在 before 的整数部分下扩小数位
        return integerPartBefore + midpoint(fractionalBefore, null);
    }

    /**
     * 小数部分中点：a &lt; 结果 &lt; b（b 为 null 表示上界无穷大）。
     * 入参不允许以零数字结尾。
     */
    private static String midpoint(String a, String b) {
        if (b != null && a.compareTo(b) >= 0) {
            throw new IllegalArgumentException("midpoint invalid: " + a + " >= " + b);
        }
        if (endsWithZero(a) || (b != null && endsWithZero(b))) {
            throw new IllegalArgumentException("midpoint invalid: trailing zero");
        }
        if (b != null) {
            // 剥掉公共前缀（a 耗尽的位按零数字对齐）
            int n = 0;
            while (n < b.length() && charAtOrZero(a, n) == b.charAt(n)) {
                n++;
            }
            if (n > 0) {
                return b.substring(0, n) + midpoint(n < a.length() ? a.substring(n) : "", b.substring(n));
            }
        }
        int digitA = a.isEmpty() ? 0 : indexOf(a.charAt(0));
        int digitB = b == null ? BASE : indexOf(b.charAt(0));
        if (digitB - digitA > 1) {
            int mid = (int) Math.round((digitA + digitB) / 2.0);
            return String.valueOf(ALPHABET[mid]);
        }
        // 首位相邻：
        if (b != null && b.length() > 1) {
            // 取 b 的首字符（b 还有后续位，故首字符前缀 < b；且 a 首位 < b 首位，故 > a）
            return b.substring(0, 1);
        }
        // 挂在 a 的首位下继续找中点（a 耗尽时首位按零数字）
        char headDigit = a.isEmpty() ? ZERO : a.charAt(0);
        return headDigit + midpoint(a.isEmpty() ? "" : a.substring(1), null);
    }

    /**
     * 整数部分 +1（字典序后继）
     */
    private static String incrementInteger(String x) {
        char head = x.charAt(0);
        boolean negative = head >= 'A' && head <= 'Z';
        int[] digits = digitsOf(x);
        for (int i = digits.length - 1; i >= 0; i--) {
            if (digits[i] == BASE - 1) {
                digits[i] = 0;
                continue;
            }
            digits[i]++;
            return head + digitsToString(digits);
        }
        // 全进位溢出
        if (negative) {
            if (head == 'Z') {
                // -1 + 1 = 0：跨越到最小正整数
                return "a0";
            }
            char newHead = (char) (head + 1);
            return String.valueOf(newHead) + repeat(ZERO, integerLength(newHead) - 1);
        }
        if (head == 'z') {
            throw new IllegalStateException("position overflow: " + x);
        }
        char newHead = (char) (head + 1);
        return String.valueOf(newHead) + repeat(ZERO, integerLength(newHead) - 1);
    }

    /**
     * 整数部分 -1（字典序前驱）
     */
    private static String decrementInteger(String x) {
        char head = x.charAt(0);
        boolean negative = head >= 'A' && head <= 'Z';
        int[] digits = digitsOf(x);
        for (int i = digits.length - 1; i >= 0; i--) {
            if (digits[i] == 0) {
                digits[i] = BASE - 1;
                continue;
            }
            digits[i]--;
            return head + digitsToString(digits);
        }
        // 全借位下溢
        if (negative) {
            if (head == 'A') {
                throw new IllegalStateException("position underflow: " + x);
            }
            char newHead = (char) (head - 1);
            return String.valueOf(newHead) + repeat(MAX_DIGIT, integerLength(newHead) - 1);
        }
        if (head == 'a') {
            // 0 - 1 = -1：跨越到最大负整数
            return "Zz";
        }
        char newHead = (char) (head - 1);
        return String.valueOf(newHead) + repeat(MAX_DIGIT, integerLength(newHead) - 1);
    }

    /**
     * head 决定的整数部分长度
     */
    private static int integerLength(char head) {
        if (head >= 'a' && head <= 'z') {
            return head - 'a' + 2;
        }
        if (head >= 'A' && head <= 'Z') {
            return 'Z' - head + 2;
        }
        throw new IllegalArgumentException("invalid position head: " + head);
    }

    private static String getIntegerPart(String key) {
        int length = integerLength(key.charAt(0));
        if (key.length() < length) {
            throw new IllegalArgumentException("invalid position key: " + key);
        }
        return key.substring(0, length);
    }

    private static void validateKey(String key) {
        if (key.isEmpty()) {
            throw new IllegalArgumentException("position key is empty");
        }
        String integerPart = getIntegerPart(key);
        for (int i = 1; i < integerPart.length(); i++) {
            indexOf(integerPart.charAt(i));
        }
        String fractional = key.substring(integerPart.length());
        if (endsWithZero(fractional)) {
            throw new IllegalArgumentException("position key has trailing zero: " + key);
        }
        for (int i = 0; i < fractional.length(); i++) {
            indexOf(fractional.charAt(i));
        }
    }

    private static boolean endsWithZero(String s) {
        return !s.isEmpty() && s.charAt(s.length() - 1) == ZERO;
    }

    private static char charAtOrZero(String s, int i) {
        return i < s.length() ? s.charAt(i) : ZERO;
    }

    private static int indexOf(char c) {
        if (c >= CHAR_TO_INDEX.length || CHAR_TO_INDEX[c] < 0) {
            throw new IllegalArgumentException("invalid position char: " + c);
        }
        return CHAR_TO_INDEX[c];
    }

    private static int[] digitsOf(String integerPart) {
        int[] digits = new int[integerPart.length() - 1];
        for (int i = 1; i < integerPart.length(); i++) {
            digits[i - 1] = indexOf(integerPart.charAt(i));
        }
        return digits;
    }

    private static String digitsToString(int[] digits) {
        StringBuilder sb = new StringBuilder(digits.length);
        for (int digit : digits) {
            sb.append(ALPHABET[digit]);
        }
        return sb.toString();
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
