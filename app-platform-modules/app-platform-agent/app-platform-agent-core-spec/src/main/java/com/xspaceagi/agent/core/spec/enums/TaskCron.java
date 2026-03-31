package com.xspaceagi.agent.core.spec.enums;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Data
public class TaskCron {

    public static List<CronDto> getTaskCronList() {
        return List.of(
                CronDto.builder().typeName("每月").items(getEveryMonth()).build(),
                CronDto.builder().typeName("每周").items(getEveryWeek()).build(),
                CronDto.builder().typeName("每天").items(getEveryDay()).build(),
                CronDto.builder().typeName("每小时").items(getEveryHour()).build(),
                CronDto.builder().typeName("固定").items(getFixed()).build());
    }

    public static List<CronDto> getUserTaskCronList() {
        return List.of(
                CronDto.builder().typeName("每月").items(getEveryMonth()).build(),
                CronDto.builder().typeName("每周").items(getEveryWeek()).build(),
                CronDto.builder().typeName("每天").items(getEveryDay()).build(),
                CronDto.builder().typeName("每小时").items(getEveryHour()).build(),
                CronDto.builder().typeName("固定周期").items(getUserFixed()).build());
    }

    public static String getCronDesc(String cron) {
        if (cron == null) {
            return "";
        }
        Optional<CronItemDto> first = getEveryMonth().stream().filter(cronItemDto -> cronItemDto.getCron().equals(cron)).findFirst();
        if (first.isPresent()) {
            CronItemDto cronItemDto = first.get();
            return "每月" + cronItemDto.getDesc();
        }
        first = getEveryWeek().stream().filter(cronItemDto -> cronItemDto.getCron().equals(cron)).findFirst();
        if (first.isPresent()) {
            CronItemDto cronItemDto = first.get();
            return "每周" + cronItemDto.getDesc();
        }
        first = getEveryDay().stream().filter(cronItemDto -> cronItemDto.getCron().equals(cron)).findFirst();
        if (first.isPresent()) {
            CronItemDto cronItemDto = first.get();
            return "每天" + cronItemDto.getDesc();
        }
        first = getEveryHour().stream().filter(cronItemDto -> cronItemDto.getCron().equals(cron)).findFirst();
        if (first.isPresent()) {
            CronItemDto cronItemDto = first.get();
            return "每小时" + cronItemDto.getDesc();
        }
        first = getFixed().stream().filter(cronItemDto -> cronItemDto.getCron().equals(cron)).findFirst();
        if (first.isPresent()) {
            CronItemDto cronItemDto = first.get();
            return cronItemDto.getDesc();
        }
        return "";
    }

    public static List<CronItemDto> getEveryMonth() {
        return Arrays.stream(Month.values()).map(month -> {
            CronItemDto cronDto = new CronItemDto();
            cronDto.setCron(month.cron);
            cronDto.setDesc(month.desc);
            return cronDto;
        }).collect(Collectors.toList());
    }

    public static List<CronItemDto> getEveryWeek() {
        return Arrays.stream(Week.values()).map(week -> {
            CronItemDto cronDto = new CronItemDto();
            cronDto.setCron(week.cron);
            cronDto.setDesc(week.desc);
            return cronDto;
        }).collect(Collectors.toList());
    }

    public static List<CronItemDto> getEveryDay() {
        return Arrays.stream(Day.values()).map(day -> {
            CronItemDto cronDto = new CronItemDto();
            cronDto.setCron(day.cron);
            cronDto.setDesc(day.desc);
            return cronDto;
        }).collect(Collectors.toList());
    }

    public static List<CronItemDto> getEveryHour() {
        return Arrays.stream(Hour.values()).map(hour -> {
            CronItemDto cronDto = new CronItemDto();
            cronDto.setCron(hour.cron);
            cronDto.setDesc(hour.desc);
            return cronDto;
        }).collect(Collectors.toList());
    }

    public static List<CronItemDto> getFixed() {
        return Arrays.stream(Fixed.values()).map(fixed -> {
            CronItemDto cronDto = new CronItemDto();
            cronDto.setCron(fixed.cron);
            cronDto.setDesc(fixed.desc);
            return cronDto;
        }).collect(Collectors.toList());
    }

    public static List<CronItemDto> getUserFixed() {
        return Arrays.stream(FixedUser.values()).map(fixed -> {
            CronItemDto cronDto = new CronItemDto();
            cronDto.setCron(fixed.cron);
            cronDto.setDesc(fixed.desc);
            return cronDto;
        }).collect(Collectors.toList());
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CronDto {

        @Schema(description = "类型，比如 每周 每月 每天")
        private String typeName;

        @Schema(description = "具体类型下可选项")
        private List<CronItemDto> items;
    }

    @Data
    public static class CronItemDto {

        @Schema(description = "cron表达式，传给后台")
        private String cron;

        @Schema(description = "cron描述")
        private String desc;
    }

    public enum Month {
        //每月1号执行一次
        DAY01("0 0 0 1 * ? ", "1号"),
        //每月2号执行一次
        DAY02("0 0 0 2 * ? ", "2号"),
        //每月3号执行一次
        DAY03("0 0 0 3 * ? ", "3号"),
        //每月4号执行一次
        DAY04("0 0 0 4 * ? ", "4号"),
        //每月5号执行一次
        DAY05("0 0 0 5 * ? ", "5号"),
        //每月6号执行一次
        DAY06("0 0 0 6 * ? ", "6号"),
        //每月7号执行一次
        DAY07("0 0 0 7 * ? ", "7号"),
        //每月8号执行一次
        DAY08("0 0 0 8 * ? ", "8号"),
        //直接生成到31号
        DAY09("0 0 0 9 * ? ", "9号"),
        //每月10号执行一次
        DAY10("0 0 0 10 * ? ", "10号"),
        //每月11号执行一次
        DAY11("0 0 0 11 * ? ", "11号"),
        //每月12号执行一次
        DAY12("0 0 0 12 * ? ", "12号"),
        DAY13("0 0 0 13 * ? ", "13号"),
        DAY14("0 0 0 14 * ? ", "14号"),
        DAY15("0 0 0 15 * ? ", "15号"),
        DAY16("0 0 0 16 * ? ", "16号"),
        DAY17("0 0 0 17 * ? ", "17号"),
        DAY18("0 0 0 18 * ? ", "18号"),
        DAY19("0 0 0 19 * ? ", "19号"),
        DAY20("0 0 0 20 * ? ", "20号"),
        DAY21("0 0 0 21 * ? ", "21号"),
        DAY22("0 0 0 22 * ? ", "22号"),
        DAY23("0 0 0 23 * ? ", "23号"),
        DAY24("0 0 0 24 * ? ", "24号"),
        DAY25("0 0 0 25 * ? ", "25号"),
        DAY26("0 0 0 26 * ? ", "26号"),
        DAY27("0 0 0 27 * ? ", "27号"),
        DAY28("0 0 0 28 * ? ", "28号"),
        DAY29("0 0 0 29 * ? ", "29号"),
        DAY30("0 0 0 30 * ? ", "30号"),
        DAY31("0 0 0 31 * ? ", "31号");
        private String cron;

        private String desc;

        Month(String cron, String desc) {
            this.cron = cron;
            this.desc = desc;
        }

    }


    public enum Week {
        //每周一执行一次
        MON("0 0 0 ? * MON", "星期一"),
        //每周二执行一次
        TUE("0 0 0 ? * TUE", "星期二"),
        //每周三执行一次
        WED("0 0 0 ? * WED", "星期三"),
        //每周四执行一次
        THU("0 0 0 ? * THU", "星期四"),
        //每周五执行一次
        FRI("0 0 0 ? * FRI", "星期五"),
        //每周六执行一次
        SAT("0 0 0 ? * SAT", "星期六"),
        //每周日执行一次
        SUN("0 0 0 ? * SUN", "星期日");
        private String cron;

        private String desc;

        Week(String cron, String desc) {
            this.cron = cron;
            this.desc = desc;
        }

    }

    public enum Day {
        //每点0点执行一次
        EVERYDAY_0("0 0 0 * * ?", "0点"),
        //每点1点执行一次
        EVERYDAY_1("0 0 1 * * ?", "1点"),
        EVERYDAY_2("0 0 2 * * ?", "2点"),
        EVERYDAY_3("0 0 3 * * ?", "3点"),
        EVERYDAY_4("0 0 4 * * ?", "4点"),
        EVERYDAY_5("0 0 5 * * ?", "5点"),
        EVERYDAY_6("0 0 6 * * ?", "6点"),
        EVERYDAY_7("0 0 7 * * ?", "7点"),
        EVERYDAY_8("0 0 8 * * ?", "8点"),
        EVERYDAY_9("0 0 9 * * ?", "9点"),
        EVERYDAY_10("0 0 10 * * ?", "10点"),
        EVERYDAY_11("0 0 11 * * ?", "11点"),
        EVERYDAY_12("0 0 12 * * ?", "12点"),
        EVERYDAY_13("0 0 13 * * ?", "13点"),
        EVERYDAY_14("0 0 14 * * ?", "14点"),
        EVERYDAY_15("0 0 15 * * ?", "15点"),
        EVERYDAY_16("0 0 16 * * ?", "16点"),
        EVERYDAY_17("0 0 17 * * ?", "17点"),
        EVERYDAY_18("0 0 18 * * ?", "18点"),
        EVERYDAY_19("0 0 19 * * ?", "19点"),
        EVERYDAY_20("0 0 20 * * ?", "20点"),
        EVERYDAY_21("0 0 21 * * ?", "21点"),
        EVERYDAY_22("0 0 22 * * ?", "22点"),
        EVERYDAY_23("0 0 23 * * ?", "23点");
        private String cron;

        private String desc;

        Day(String cron, String desc) {
            this.cron = cron;
            this.desc = desc;
        }

    }

    public enum Hour {
        //每小时第一分钟执行一次
        EVERY_HOUR_0("0 0 * * * ?", "00分"),
        EVERY_HOUR_1("0 1 * * * ?", "01分"),
        EVERY_HOUR_2("0 2 * * * ?", "02分"),
        EVERY_HOUR_3("0 3 * * * ?", "03分"),
        EVERY_HOUR_4("0 4 * * * ?", "04分"),
        EVERY_HOUR_5("0 5 * * * ?", "05分"),
        EVERY_HOUR_6("0 6 * * * ?", "06分"),
        EVERY_HOUR_7("0 7 * * * ?", "07分"),
        EVERY_HOUR_8("0 8 * * * ?", "08分"),
        EVERY_HOUR_9("0 9 * * * ?", "09分"),
        EVERY_HOUR_10("0 10 * * * ?", "10分"),
        EVERY_HOUR_11("0 11 * * * ?", "11分"),
        EVERY_HOUR_12("0 12 * * * ?", "12分"),
        EVERY_HOUR_13("0 13 * * * ?", "13分"),
        EVERY_HOUR_14("0 14 * * * ?", "14分"),
        EVERY_HOUR_15("0 15 * * * ?", "15分"),
        EVERY_HOUR_16("0 16 * * * ?", "16分"),
        EVERY_HOUR_17("0 17 * * * ?", "17分"),
        EVERY_HOUR_18("0 18 * * * ?", "18分"),
        EVERY_HOUR_19("0 19 * * * ?", "19分"),
        EVERY_HOUR_20("0 20 * * * ?", "20分"),
        EVERY_HOUR_21("0 21 * * * ?", "21分"),
        EVERY_HOUR_22("0 22 * * * ?", "22分"),
        EVERY_HOUR_23("0 23 * * * ?", "23分"),
        EVERY_HOUR_24("0 24 * * * ?", "24分"),
        EVERY_HOUR_25("0 25 * * * ?", "25分"),
        EVERY_HOUR_26("0 26 * * * ?", "26分"),
        EVERY_HOUR_27("0 27 * * * ?", "27分"),
        EVERY_HOUR_28("0 28 * * * ?", "28分"),
        EVERY_HOUR_29("0 29 * * * ?", "29分"),
        EVERY_HOUR_30("0 30 * * * ?", "30分"),
        EVERY_HOUR_31("0 31 * * * ?", "31分"),
        EVERY_HOUR_32("0 32 * * * ?", "32分"),
        EVERY_HOUR_33("0 33 * * * ?", "33分"),
        EVERY_HOUR_34("0 34 * * * ?", "34分"),
        EVERY_HOUR_35("0 35 * * * ?", "35分"),
        EVERY_HOUR_36("0 36 * * * ?", "36分"),
        EVERY_HOUR_37("0 37 * * * ?", "37分"),
        EVERY_HOUR_38("0 38 * * * ?", "38分"),
        EVERY_HOUR_39("0 39 * * * ?", "39分"),
        EVERY_HOUR_40("0 40 * * * ?", "40分"),
        EVERY_HOUR_41("0 41 * * * ?", "41分"),
        EVERY_HOUR_42("0 42 * * * ?", "42分"),
        EVERY_HOUR_43("0 43 * * * ?", "43分"),
        EVERY_HOUR_44("0 44 * * * ?", "44分"),
        EVERY_HOUR_45("0 45 * * * ?", "45分"),
        EVERY_HOUR_46("0 46 * * * ?", "46分"),
        EVERY_HOUR_47("0 47 * * * ?", "47分"),
        EVERY_HOUR_48("0 48 * * * ?", "48分"),
        EVERY_HOUR_49("0 49 * * * ?", "49分"),
        EVERY_HOUR_50("0 50 * * * ?", "50分"),
        EVERY_HOUR_51("0 51 * * * ?", "51分"),
        EVERY_HOUR_52("0 52 * * * ?", "52分"),
        EVERY_HOUR_53("0 53 * * * ?", "53分"),
        EVERY_HOUR_54("0 54 * * * ?", "54分"),
        EVERY_HOUR_55("0 55 * * * ?", "55分"),
        EVERY_HOUR_56("0 56 * * * ?", "56分"),
        EVERY_HOUR_57("0 57 * * * ?", "57分"),
        EVERY_HOUR_58("0 58 * * * ?", "58分"),
        EVERY_HOUR_59("0 59 * * * ?", "59分");
        private String cron;

        private String desc;

        Hour(String cron, String desc) {
            this.cron = cron;
            this.desc = desc;
        }

    }

    //固定的cron表达式
    public enum Fixed {
        //每秒执行一次
        EVERY_SECOND("0/1 * * * * ?", "每秒"),
        //每5秒执行一次
        EVERY_5_SECOND_FIXED("0/5 * * * * ?", "每5秒"),
        //每10秒执行一次
        EVERY_10_SECOND_FIXED("0/10 * * * * ?", "每10秒"),
        //每30秒执行一次
        EVERY_30_SECOND_FIXED("0/30 * * * * ?", "每30秒"),
        //每1分钟执行一次
        EVERY_MINUTE("0 * * * * ?", "每1分钟"),
        //每五分钟执行一次
        EVERY_5_MINUTE("0 0/5 * * * ?", "每5分钟"),
        //每10分钟执行一次
        EVERY_10_MINUTE("0 0/10 * * * ?", "每10分钟"),
        //每15分钟执行一次
        EVERY_15_MINUTE("0 0/15 * * * ?", "每15分钟"),
        //每20分钟执行一次
        EVERY_20_MINUTE("0 0/20 * * * ?", "每20分钟"),
        //每25分钟执行一次
        EVERY_25_MINUTE("0 0/25 * * * ?", "每25分钟"),
        //每30分钟执行一次
        EVERY_30_MINUTE("0 0/30 * * * ?", "每30分钟"),
        //每35分钟执行一次
        EVERY_35_MINUTE("0 0/35 * * * ?", "每35分钟"),
        //每40分钟执行一次
        EVERY_40_MINUTE("0 0/40 * * * ?", "每40分钟"),
        //每45分钟执行一次
        EVERY_45_MINUTE("0 0/45 * * * ?", "每45分钟"),
        EVERY_50_MINUTE("0 0/50 * * * ?", "每50分钟"),
        EVERY_55_MINUTE("0 0/55 * * * ?", "每55分钟");

        private String cron;

        private String desc;

        Fixed(String cron, String desc) {
            this.cron = cron;
            this.desc = desc;
        }

    }

    //固定的cron表达式
    public enum FixedUser {
        //每1分钟执行一次
        EVERY_MINUTE("0 0 * * * ?", "每1分钟"),
        //每五分钟执行一次
        EVERY_5_MINUTE("0 0/5 * * * ?", "每5分钟"),
        //每10分钟执行一次
        EVERY_10_MINUTE("0 0/10 * * * ?", "每10分钟"),
        //每15分钟执行一次
        EVERY_15_MINUTE("0 0/15 * * * ?", "每15分钟"),
        //每20分钟执行一次
        EVERY_20_MINUTE("0 0/20 * * * ?", "每20分钟"),
        //每25分钟执行一次
        EVERY_25_MINUTE("0 0/25 * * * ?", "每25分钟"),
        //每30分钟执行一次
        EVERY_30_MINUTE("0 0/30 * * * ?", "每30分钟"),
        //每35分钟执行一次
        EVERY_35_MINUTE("0 0/35 * * * ?", "每35分钟"),
        //每40分钟执行一次
        EVERY_40_MINUTE("0 0/40 * * * ?", "每40分钟"),
        //每45分钟执行一次
        EVERY_45_MINUTE("0 0/45 * * * ?", "每45分钟"),
        EVERY_50_MINUTE("0 0/50 * * * ?", "每50分钟"),
        EVERY_55_MINUTE("0 0/55 * * * ?", "每55分钟");

        private String cron;

        private String desc;

        FixedUser(String cron, String desc) {
            this.cron = cron;
            this.desc = desc;
        }

    }
}
