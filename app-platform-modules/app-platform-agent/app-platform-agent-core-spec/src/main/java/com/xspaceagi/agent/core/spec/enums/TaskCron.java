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

import com.xspaceagi.system.spec.utils.I18nUtil;

@Data
public class TaskCron {

    public static List<CronDto> getTaskCronList() {
        return List.of(
                CronDto.builder().typeName(I18nUtil.systemMessage("Backend.TaskCron.Type.Monthly")).items(getEveryMonth()).build(),
                CronDto.builder().typeName(I18nUtil.systemMessage("Backend.TaskCron.Type.Weekly")).items(getEveryWeek()).build(),
                CronDto.builder().typeName(I18nUtil.systemMessage("Backend.TaskCron.Type.Daily")).items(getEveryDay()).build(),
                CronDto.builder().typeName(I18nUtil.systemMessage("Backend.TaskCron.Type.Hourly")).items(getEveryHour()).build(),
                CronDto.builder().typeName(I18nUtil.systemMessage("Backend.TaskCron.Type.Fixed")).items(getFixed()).build());
    }

    public static List<CronDto> getUserTaskCronList() {
        return List.of(
                CronDto.builder().typeName(I18nUtil.systemMessage("Backend.TaskCron.Type.Monthly")).items(getEveryMonth()).build(),
                CronDto.builder().typeName(I18nUtil.systemMessage("Backend.TaskCron.Type.Weekly")).items(getEveryWeek()).build(),
                CronDto.builder().typeName(I18nUtil.systemMessage("Backend.TaskCron.Type.Daily")).items(getEveryDay()).build(),
                CronDto.builder().typeName(I18nUtil.systemMessage("Backend.TaskCron.Type.Hourly")).items(getEveryHour()).build(),
                CronDto.builder().typeName(I18nUtil.systemMessage("Backend.TaskCron.Type.FixedPeriod")).items(getUserFixed()).build());
    }

    public static String getCronDesc(String cron) {
        if (cron == null) {
            return "";
        }
        Optional<CronItemDto> first = getEveryMonth().stream().filter(cronItemDto -> cronItemDto.getCron().equals(cron)).findFirst();
        if (first.isPresent()) {
            return I18nUtil.systemMessage("Backend.TaskCron.Type.Monthly") + first.get().getDesc();
        }
        first = getEveryWeek().stream().filter(cronItemDto -> cronItemDto.getCron().equals(cron)).findFirst();
        if (first.isPresent()) {
            return I18nUtil.systemMessage("Backend.TaskCron.Type.Weekly") + first.get().getDesc();
        }
        first = getEveryDay().stream().filter(cronItemDto -> cronItemDto.getCron().equals(cron)).findFirst();
        if (first.isPresent()) {
            return I18nUtil.systemMessage("Backend.TaskCron.Type.Daily") + first.get().getDesc();
        }
        first = getEveryHour().stream().filter(cronItemDto -> cronItemDto.getCron().equals(cron)).findFirst();
        if (first.isPresent()) {
            return I18nUtil.systemMessage("Backend.TaskCron.Type.Hourly") + first.get().getDesc();
        }
        first = getFixed().stream().filter(cronItemDto -> cronItemDto.getCron().equals(cron)).findFirst();
        if (first.isPresent()) {
            return first.get().getDesc();
        }
        return "";
    }

    public static List<CronItemDto> getEveryMonth() {
        return Arrays.stream(Month.values()).map(month -> {
            CronItemDto cronDto = new CronItemDto();
            cronDto.setCron(month.cron);
            cronDto.setDesc(month.getDesc());
            return cronDto;
        }).collect(Collectors.toList());
    }

    public static List<CronItemDto> getEveryWeek() {
        return Arrays.stream(Week.values()).map(week -> {
            CronItemDto cronDto = new CronItemDto();
            cronDto.setCron(week.cron);
            cronDto.setDesc(week.getDesc());
            return cronDto;
        }).collect(Collectors.toList());
    }

    public static List<CronItemDto> getEveryDay() {
        return Arrays.stream(Day.values()).map(day -> {
            CronItemDto cronDto = new CronItemDto();
            cronDto.setCron(day.cron);
            cronDto.setDesc(day.getDesc());
            return cronDto;
        }).collect(Collectors.toList());
    }

    public static List<CronItemDto> getEveryHour() {
        return Arrays.stream(Hour.values()).map(hour -> {
            CronItemDto cronDto = new CronItemDto();
            cronDto.setCron(hour.cron);
            cronDto.setDesc(hour.getDesc());
            return cronDto;
        }).collect(Collectors.toList());
    }

    public static List<CronItemDto> getFixed() {
        return Arrays.stream(Fixed.values()).map(fixed -> {
            CronItemDto cronDto = new CronItemDto();
            cronDto.setCron(fixed.cron);
            cronDto.setDesc(fixed.getDesc());
            return cronDto;
        }).collect(Collectors.toList());
    }

    public static List<CronItemDto> getUserFixed() {
        return Arrays.stream(FixedUser.values()).map(fixed -> {
            CronItemDto cronDto = new CronItemDto();
            cronDto.setCron(fixed.cron);
            cronDto.setDesc(fixed.getDesc());
            return cronDto;
        }).collect(Collectors.toList());
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CronDto {

        @Schema(description = "Type name, e.g. weekly, monthly, daily")
        private String typeName;

        @Schema(description = "Available options for this type")
        private List<CronItemDto> items;
    }

    @Data
    public static class CronItemDto {

        @Schema(description = "Cron expression, passed to backend")
        private String cron;

        @Schema(description = "Cron description")
        private String desc;
    }

    public enum Month {
        DAY01("0 0 0 1 * ? "),
        DAY02("0 0 0 2 * ? "),
        DAY03("0 0 0 3 * ? "),
        DAY04("0 0 0 4 * ? "),
        DAY05("0 0 0 5 * ? "),
        DAY06("0 0 0 6 * ? "),
        DAY07("0 0 0 7 * ? "),
        DAY08("0 0 0 8 * ? "),
        DAY09("0 0 0 9 * ? "),
        DAY10("0 0 0 10 * ? "),
        DAY11("0 0 0 11 * ? "),
        DAY12("0 0 0 12 * ? "),
        DAY13("0 0 0 13 * ? "),
        DAY14("0 0 0 14 * ? "),
        DAY15("0 0 0 15 * ? "),
        DAY16("0 0 0 16 * ? "),
        DAY17("0 0 0 17 * ? "),
        DAY18("0 0 0 18 * ? "),
        DAY19("0 0 0 19 * ? "),
        DAY20("0 0 0 20 * ? "),
        DAY21("0 0 0 21 * ? "),
        DAY22("0 0 0 22 * ? "),
        DAY23("0 0 0 23 * ? "),
        DAY24("0 0 0 24 * ? "),
        DAY25("0 0 0 25 * ? "),
        DAY26("0 0 0 26 * ? "),
        DAY27("0 0 0 27 * ? "),
        DAY28("0 0 0 28 * ? "),
        DAY29("0 0 0 29 * ? "),
        DAY30("0 0 0 30 * ? "),
        DAY31("0 0 0 31 * ? ");
        private String cron;

        Month(String cron) {
            this.cron = cron;
        }

        public String getDesc() {
            int day = Integer.parseInt(cron.trim().split(" ")[3]);
            return I18nUtil.systemMessage("Backend.TaskCron.Month.Day", String.valueOf(day));
        }
    }


    public enum Week {
        MON("0 0 0 ? * MON", "Backend.TaskCron.Week.Mon"),
        TUE("0 0 0 ? * TUE", "Backend.TaskCron.Week.Tue"),
        WED("0 0 0 ? * WED", "Backend.TaskCron.Week.Wed"),
        THU("0 0 0 ? * THU", "Backend.TaskCron.Week.Thu"),
        FRI("0 0 0 ? * FRI", "Backend.TaskCron.Week.Fri"),
        SAT("0 0 0 ? * SAT", "Backend.TaskCron.Week.Sat"),
        SUN("0 0 0 ? * SUN", "Backend.TaskCron.Week.Sun");
        private String cron;
        private String i18nKey;

        Week(String cron, String i18nKey) {
            this.cron = cron;
            this.i18nKey = i18nKey;
        }

        public String getDesc() {
            return I18nUtil.systemMessage(i18nKey);
        }
    }

    public enum Day {
        EVERYDAY_0("0 0 0 * * ?"),
        EVERYDAY_1("0 0 1 * * ?"),
        EVERYDAY_2("0 0 2 * * ?"),
        EVERYDAY_3("0 0 3 * * ?"),
        EVERYDAY_4("0 0 4 * * ?"),
        EVERYDAY_5("0 0 5 * * ?"),
        EVERYDAY_6("0 0 6 * * ?"),
        EVERYDAY_7("0 0 7 * * ?"),
        EVERYDAY_8("0 0 8 * * ?"),
        EVERYDAY_9("0 0 9 * * ?"),
        EVERYDAY_10("0 0 10 * * ?"),
        EVERYDAY_11("0 0 11 * * ?"),
        EVERYDAY_12("0 0 12 * * ?"),
        EVERYDAY_13("0 0 13 * * ?"),
        EVERYDAY_14("0 0 14 * * ?"),
        EVERYDAY_15("0 0 15 * * ?"),
        EVERYDAY_16("0 0 16 * * ?"),
        EVERYDAY_17("0 0 17 * * ?"),
        EVERYDAY_18("0 0 18 * * ?"),
        EVERYDAY_19("0 0 19 * * ?"),
        EVERYDAY_20("0 0 20 * * ?"),
        EVERYDAY_21("0 0 21 * * ?"),
        EVERYDAY_22("0 0 22 * * ?"),
        EVERYDAY_23("0 0 23 * * ?");
        private String cron;

        Day(String cron) {
            this.cron = cron;
        }

        public String getDesc() {
            int hour = Integer.parseInt(cron.trim().split(" ")[2]);
            return I18nUtil.systemMessage("Backend.TaskCron.Day.Hour", String.valueOf(hour));
        }
    }

    public enum Hour {
        EVERY_HOUR_0("0 0 * * * ?"),
        EVERY_HOUR_1("0 1 * * * ?"),
        EVERY_HOUR_2("0 2 * * * ?"),
        EVERY_HOUR_3("0 3 * * * ?"),
        EVERY_HOUR_4("0 4 * * * ?"),
        EVERY_HOUR_5("0 5 * * * ?"),
        EVERY_HOUR_6("0 6 * * * ?"),
        EVERY_HOUR_7("0 7 * * * ?"),
        EVERY_HOUR_8("0 8 * * * ?"),
        EVERY_HOUR_9("0 9 * * * ?"),
        EVERY_HOUR_10("0 10 * * * ?"),
        EVERY_HOUR_11("0 11 * * * ?"),
        EVERY_HOUR_12("0 12 * * * ?"),
        EVERY_HOUR_13("0 13 * * * ?"),
        EVERY_HOUR_14("0 14 * * * ?"),
        EVERY_HOUR_15("0 15 * * * ?"),
        EVERY_HOUR_16("0 16 * * * ?"),
        EVERY_HOUR_17("0 17 * * * ?"),
        EVERY_HOUR_18("0 18 * * * ?"),
        EVERY_HOUR_19("0 19 * * * ?"),
        EVERY_HOUR_20("0 20 * * * ?"),
        EVERY_HOUR_21("0 21 * * * ?"),
        EVERY_HOUR_22("0 22 * * * ?"),
        EVERY_HOUR_23("0 23 * * * ?"),
        EVERY_HOUR_24("0 24 * * * ?"),
        EVERY_HOUR_25("0 25 * * * ?"),
        EVERY_HOUR_26("0 26 * * * ?"),
        EVERY_HOUR_27("0 27 * * * ?"),
        EVERY_HOUR_28("0 28 * * * ?"),
        EVERY_HOUR_29("0 29 * * * ?"),
        EVERY_HOUR_30("0 30 * * * ?"),
        EVERY_HOUR_31("0 31 * * * ?"),
        EVERY_HOUR_32("0 32 * * * ?"),
        EVERY_HOUR_33("0 33 * * * ?"),
        EVERY_HOUR_34("0 34 * * * ?"),
        EVERY_HOUR_35("0 35 * * * ?"),
        EVERY_HOUR_36("0 36 * * * ?"),
        EVERY_HOUR_37("0 37 * * * ?"),
        EVERY_HOUR_38("0 38 * * * ?"),
        EVERY_HOUR_39("0 39 * * * ?"),
        EVERY_HOUR_40("0 40 * * * ?"),
        EVERY_HOUR_41("0 41 * * * ?"),
        EVERY_HOUR_42("0 42 * * * ?"),
        EVERY_HOUR_43("0 43 * * * ?"),
        EVERY_HOUR_44("0 44 * * * ?"),
        EVERY_HOUR_45("0 45 * * * ?"),
        EVERY_HOUR_46("0 46 * * * ?"),
        EVERY_HOUR_47("0 47 * * * ?"),
        EVERY_HOUR_48("0 48 * * * ?"),
        EVERY_HOUR_49("0 49 * * * ?"),
        EVERY_HOUR_50("0 50 * * * ?"),
        EVERY_HOUR_51("0 51 * * * ?"),
        EVERY_HOUR_52("0 52 * * * ?"),
        EVERY_HOUR_53("0 53 * * * ?"),
        EVERY_HOUR_54("0 54 * * * ?"),
        EVERY_HOUR_55("0 55 * * * ?"),
        EVERY_HOUR_56("0 56 * * * ?"),
        EVERY_HOUR_57("0 57 * * * ?"),
        EVERY_HOUR_58("0 58 * * * ?"),
        EVERY_HOUR_59("0 59 * * * ?");
        private String cron;

        Hour(String cron) {
            this.cron = cron;
        }

        public String getDesc() {
            int minute = Integer.parseInt(cron.trim().split(" ")[1]);
            return I18nUtil.systemMessage("Backend.TaskCron.Hour.Minute", String.valueOf(minute));
        }
    }

    // Fixed cron expressions
    public enum Fixed {
        EVERY_SECOND("0/1 * * * * ?", "Backend.TaskCron.Fixed.EverySecond"),
        EVERY_5_SECOND_FIXED("0/5 * * * * ?", "Backend.TaskCron.Fixed.Every5Seconds"),
        EVERY_10_SECOND_FIXED("0/10 * * * * ?", "Backend.TaskCron.Fixed.Every10Seconds"),
        EVERY_30_SECOND_FIXED("0/30 * * * * ?", "Backend.TaskCron.Fixed.Every30Seconds"),
        EVERY_MINUTE("0 * * * * ?", "Backend.TaskCron.Fixed.EveryMinute"),
        EVERY_5_MINUTE("0 0/5 * * * ?", "Backend.TaskCron.Fixed.Every5Minutes"),
        EVERY_10_MINUTE("0 0/10 * * * ?", "Backend.TaskCron.Fixed.Every10Minutes"),
        EVERY_15_MINUTE("0 0/15 * * * ?", "Backend.TaskCron.Fixed.Every15Minutes"),
        EVERY_20_MINUTE("0 0/20 * * * ?", "Backend.TaskCron.Fixed.Every20Minutes"),
        EVERY_25_MINUTE("0 0/25 * * * ?", "Backend.TaskCron.Fixed.Every25Minutes"),
        EVERY_30_MINUTE("0 0/30 * * * ?", "Backend.TaskCron.Fixed.Every30Minutes"),
        EVERY_35_MINUTE("0 0/35 * * * ?", "Backend.TaskCron.Fixed.Every35Minutes"),
        EVERY_40_MINUTE("0 0/40 * * * ?", "Backend.TaskCron.Fixed.Every40Minutes"),
        EVERY_45_MINUTE("0 0/45 * * * ?", "Backend.TaskCron.Fixed.Every45Minutes"),
        EVERY_50_MINUTE("0 0/50 * * * ?", "Backend.TaskCron.Fixed.Every50Minutes"),
        EVERY_55_MINUTE("0 0/55 * * * ?", "Backend.TaskCron.Fixed.Every55Minutes");

        private String cron;
        private String i18nKey;

        Fixed(String cron, String i18nKey) {
            this.cron = cron;
            this.i18nKey = i18nKey;
        }

        public String getDesc() {
            return I18nUtil.systemMessage(i18nKey);
        }
    }

    // Fixed cron expressions for user tasks
    public enum FixedUser {
        EVERY_MINUTE("0 0 * * * ?", "Backend.TaskCron.Fixed.EveryMinute"),
        EVERY_5_MINUTE("0 0/5 * * * ?", "Backend.TaskCron.Fixed.Every5Minutes"),
        EVERY_10_MINUTE("0 0/10 * * * ?", "Backend.TaskCron.Fixed.Every10Minutes"),
        EVERY_15_MINUTE("0 0/15 * * * ?", "Backend.TaskCron.Fixed.Every15Minutes"),
        EVERY_20_MINUTE("0 0/20 * * * ?", "Backend.TaskCron.Fixed.Every20Minutes"),
        EVERY_25_MINUTE("0 0/25 * * * ?", "Backend.TaskCron.Fixed.Every25Minutes"),
        EVERY_30_MINUTE("0 0/30 * * * ?", "Backend.TaskCron.Fixed.Every30Minutes"),
        EVERY_35_MINUTE("0 0/35 * * * ?", "Backend.TaskCron.Fixed.Every35Minutes"),
        EVERY_40_MINUTE("0 0/40 * * * ?", "Backend.TaskCron.Fixed.Every40Minutes"),
        EVERY_45_MINUTE("0 0/45 * * * ?", "Backend.TaskCron.Fixed.Every45Minutes"),
        EVERY_50_MINUTE("0 0/50 * * * ?", "Backend.TaskCron.Fixed.Every50Minutes"),
        EVERY_55_MINUTE("0 0/55 * * * ?", "Backend.TaskCron.Fixed.Every55Minutes");

        private String cron;
        private String i18nKey;

        FixedUser(String cron, String i18nKey) {
            this.cron = cron;
            this.i18nKey = i18nKey;
        }

        public String getDesc() {
            return I18nUtil.systemMessage(i18nKey);
        }
    }
}
