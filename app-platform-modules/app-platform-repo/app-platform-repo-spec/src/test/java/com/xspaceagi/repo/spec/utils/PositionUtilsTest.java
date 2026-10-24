package com.xspaceagi.repo.spec.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PositionUtils 正确性验证：核心不变量为
 * before &lt; between(before, after) &lt; after（字典序），且任意序列操作不产生重复/乱序。
 */
class PositionUtilsTest {

    @Test
    void defaultKey() {
        assertEquals("a0", PositionUtils.between(null, null));
    }

    @Test
    void appendAndPrepend() {
        String first = PositionUtils.between(null, null);
        String second = PositionUtils.between(first, null);
        assertTrue(first.compareTo(second) < 0, first + " < " + second);

        String zeroth = PositionUtils.between(null, first);
        assertTrue(zeroth.compareTo(first) < 0, zeroth + " < " + first);
    }

    @Test
    void betweenAdjacentIntegers() {
        String mid = PositionUtils.between("a0", "a1");
        assertTrue("a0".compareTo(mid) < 0 && mid.compareTo("a1") < 0, "a0 < " + mid + " < a1");
    }

    @Test
    void crossesNegativeBoundary() {
        // 在 -1(Zz) 与 0(a0) 之间仍有位置
        String mid = PositionUtils.between("Zz", "a0");
        assertTrue("Zz".compareTo(mid) < 0 && mid.compareTo("a0") < 0, "Zz < " + mid + " < a0");
    }

    @Test
    void manyHeadInserts() {
        // 连续头插 200 次，始终有序且无重复
        List<String> keys = new ArrayList<>();
        keys.add(PositionUtils.between(null, null));
        Set<String> seen = new HashSet<>(keys);
        for (int i = 0; i < 200; i++) {
            String next = PositionUtils.between(null, keys.get(0));
            assertTrue(next.compareTo(keys.get(0)) < 0, "head insert order broken at " + i + ": " + next);
            assertTrue(seen.add(next), "duplicate key at " + i + ": " + next);
            keys.add(0, next);
        }
    }

    @Test
    void manyTailInserts() {
        List<String> keys = new ArrayList<>();
        keys.add(PositionUtils.between(null, null));
        Set<String> seen = new HashSet<>(keys);
        for (int i = 0; i < 200; i++) {
            String next = PositionUtils.between(keys.get(keys.size() - 1), null);
            assertTrue(keys.get(keys.size() - 1).compareTo(next) < 0, "tail insert order broken at " + i);
            assertTrue(seen.add(next), "duplicate key at " + i);
            keys.add(next);
        }
    }

    @Test
    void sameGapRepeatedInserts() {
        // 反复在同一缝隙（相邻键之间）插入，逼出小数位增长
        String lo = PositionUtils.between(null, null);      // a0
        String hi = PositionUtils.between(lo, null);        // a1
        Set<String> seen = new HashSet<>();
        seen.add(lo);
        seen.add(hi);
        for (int i = 0; i < 200; i++) {
            String mid = PositionUtils.between(lo, hi);
            assertTrue(lo.compareTo(mid) < 0 && mid.compareTo(hi) < 0,
                    "gap broken at " + i + ": " + lo + " < " + mid + " < " + hi);
            assertTrue(seen.add(mid), "duplicate at " + i + ": " + mid);
            hi = mid; // 每次都挤进左半缝，制造最坏情况
        }
    }

    @Test
    void randomOperationsKeepSorted() {
        Random random = new Random(42);
        List<String> keys = new ArrayList<>();
        keys.add(PositionUtils.between(null, null));
        Set<String> seen = new HashSet<>(keys);
        for (int round = 0; round < 5000; round++) {
            // 随机选一个插入位置：0=最前，size=最后
            int pos = random.nextInt(keys.size() + 1);
            String before = pos == 0 ? null : keys.get(pos - 1);
            String after = pos == keys.size() ? null : keys.get(pos);
            String key = PositionUtils.between(before, after);
            if (before != null) {
                assertTrue(before.compareTo(key) < 0,
                        "order broken: " + before + " >= " + key + " (round " + round + ")");
            }
            if (after != null) {
                assertTrue(key.compareTo(after) < 0,
                        "order broken: " + key + " >= " + after + " (round " + round + ")");
            }
            assertTrue(seen.add(key), "duplicate key at round " + round + ": " + key);
            keys.add(pos, key);
        }
        // 最终整体有序
        List<String> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);
        assertEquals(sorted, keys, "list not in lexicographic order");
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> PositionUtils.between("a1", "a0"));
        assertThrows(IllegalArgumentException.class, () -> PositionUtils.between("a0", "a0"));
        // 尾零键非法
        assertThrows(IllegalArgumentException.class, () -> PositionUtils.between("a00", null));
        // 非法字符
        assertThrows(IllegalArgumentException.class, () -> PositionUtils.between("a中", null));
        // head 非法
        assertThrows(IllegalArgumentException.class, () -> PositionUtils.between("0abc", null));
    }

    @Test
    void integerCarryAndBorrow() {
        // a0 前驱是 Zz（跨零），Zz 后继回到 a0
        assertEquals("Zz", PositionUtils.between(null, "a0"));
        String afterZz = PositionUtils.between("Zz", null);
        assertEquals("a0", afterZz);
        // az 的后继是 b00（整数进位）
        String afterAz = PositionUtils.between("az", null);
        assertEquals("b00", afterAz);
    }
}
