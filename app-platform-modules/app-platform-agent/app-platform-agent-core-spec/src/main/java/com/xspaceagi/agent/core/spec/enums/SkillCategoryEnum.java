package com.xspaceagi.agent.core.spec.enums;

public enum SkillCategoryEnum {

    creation("creation", "内容创作"),
    data("data", "数据分析"),
    dev("dev", "开发部署"),
    edu("edu", "教育学习"),
    efficiency("efficiency", "效率工具"),
    finance("finance", "投资理财"),
    news("news", "信息资讯"),
    op("op", "商业运营"),
    service("service", "生活服务"),
    work("work", "协同办公");

    private String name;
    private String desc;

    SkillCategoryEnum(String name, String desc) {
        this.name = name;
        this.desc = desc;
    }

    public String getName() {
        return name;
    }

    public String getDesc() {
        return desc;
    }

    //获取所有的name
    public static String[] getNames() {
        SkillCategoryEnum[] values = SkillCategoryEnum.values();
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].getName();
        }
        return names;
    }

}
