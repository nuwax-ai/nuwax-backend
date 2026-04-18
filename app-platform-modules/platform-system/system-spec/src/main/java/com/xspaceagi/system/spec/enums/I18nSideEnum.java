package com.xspaceagi.system.spec.enums;

import lombok.Getter;

/**
 * 时段类型枚举
 */
@Getter
public enum I18nSideEnum {

    PC("PC", "pc"),
    Mobile("Mobile", "h5"),
    Claw("Claw", "nuwa claw"),
    Backend("Backend", "backend notice");

    private final String side;
    private final String desc;

    I18nSideEnum(String side, String desc) {
        this.side = side;
        this.desc = desc;
    }

    public static I18nSideEnum fromSide(String side) {
        if (side == null) {
            return null;
        }
        for (I18nSideEnum type : I18nSideEnum.values()) {
            if (type.getSide().equals(side)) {
                return type;
            }
        }
        return null;
    }

    public static boolean isValid(String side) {
        return fromSide(side) != null;
    }

    public static boolean isInValid(String code) {
        return !isValid(code);
    }
}