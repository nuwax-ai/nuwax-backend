package com.xspaceagi.system.spec.utils;

import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Date转换工具
 *
 * @author
 *
 */
public class DateUtil {

    /** 锁对象 */
    private static final Object lockObj = new Object();

    /**
     * 存放不同的日期模板格式的sdf的Map
     */
    private static Map<String, ThreadLocal<SimpleDateFormat>> sdfMap = new HashMap<String, ThreadLocal<SimpleDateFormat>>();

    /**
     * 返回一个ThreadLocal的sdf,每个线程只会new一次sdf
     *
     * @param pattern
     * @return
     */
    private static SimpleDateFormat getSdf(final String pattern) {
        ThreadLocal<SimpleDateFormat> tl = sdfMap.get(pattern);

        // 此处的双重判断和同步是为了防止sdfMap这个单例被多次put重复的sdf
        if (tl == null) {
            synchronized (lockObj) {
                tl = sdfMap.get(pattern);
                if (tl == null) {
                    // 只有Map中还没有这个pattern的sdf才会生成新的sdf并放入map
                    // 这里是关键,使用ThreadLocal<SimpleDateFormat>替代原来直接new
                    // SimpleDateFormat
                    tl = new ThreadLocal<SimpleDateFormat>() {

                        @Override
                        protected SimpleDateFormat initialValue() {
                            return new SimpleDateFormat(pattern);
                        }
                    };
                    sdfMap.put(pattern, tl);
                }
            }
        }

        return tl.get();
    }

    /**
     * 是用ThreadLocal
     * <SimpleDateFormat>来获取SimpleDateFormat,这样每个线程只会有一个SimpleDateFormat
     *
     * @param date
     * @param pattern
     * @return
     */
    public static String format(Date date, String pattern) {
        return getSdf(pattern).format(date);
    }

    public static Date parse(String dateStr, String pattern) throws ParseException {
        return getSdf(pattern).parse(dateStr);
    }

    public static int getTimeDelta(Date start, Date end) {
        long timeDelta = (end.getTime() - start.getTime()) / 1000;// 单位是秒
        int secondsDelta = timeDelta > 0 ? (int) timeDelta : (int) Math.abs(timeDelta);
        return secondsDelta;
    }

    // 获得本月最后一天24点时间
    public static Date getTimesMonthnight() {
        Calendar cal = Calendar.getInstance();
        cal.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONDAY), cal.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH));
        cal.set(Calendar.HOUR_OF_DAY, 24);
        return cal.getTime();
    }

    public static int getCurrentTime() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    public static String getYearMonth() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMM");
        return dateFormat.format(new Date());
    }

    /**
     * 字符串转时间，精确到秒
     *
     * @return long timestamp to second
     */
    public static int stringToTimeSecond(String dateString) {
        int result = 0;
        Timestamp ts = null;
        try {
            ts = Timestamp.valueOf(dateString);
            result = Integer.parseInt(String.valueOf(ts.getTime()).substring(0, 10));
        } catch (Exception e) {

        }
        return result;
    }

    /**
     * 字符串转时间，精确到秒
     *
     * @return long timestamp to second
     */
    public static int stringToTimeSecondNoSplit(String dateString) {
        int result = 0;
        Timestamp ts = null;
        try {
            SimpleDateFormat sdf1 = new SimpleDateFormat("yyyyMMddhhmmss");
            Date date = sdf1.parse(dateString);
            SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            ts = Timestamp.valueOf(sdf2.format(date));
            result = Integer.parseInt(String.valueOf(ts.getTime()).substring(0, 10));
        } catch (ParseException e) {

        }
        return result;
    }

    /**
     * 获取当前日期字符串，yyyy-MM-dd HH:mm:ss
     *
     * @return
     */
    public static String getNowTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

}