package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.time.LocalDate;
import java.util.List;

/**
 * 可驗證的農曆轉國曆介面。
 *
 * <p>JDK 本身沒有臺灣常用農曆的曆法實作；提供者必須使用有版本與來源可追溯的曆法資料，
 * 不得用固定天數位移、語言模型猜測或節日日期硬編碼。未安裝提供者時，上層只會澄清，
 * 不會建立或修改行程。</p>
 */
public interface LunarCalendarConversionProvider {

    Conversion convert(LunarDate lunarDate);

    enum LeapMonth {
        REGULAR,
        LEAP,
        UNSPECIFIED
    }

    enum Status {
        RESOLVED,
        AMBIGUOUS_LEAP_MONTH,
        INVALID_DATE,
        OUT_OF_RANGE,
        TEMPORARILY_UNAVAILABLE
    }

    record LunarDate(int year, int month, int day, LeapMonth leapMonth) {
        public LunarDate {
            if (year < 1) throw new IllegalArgumentException("lunar year must be positive");
            if (month < 1 || month > 12) throw new IllegalArgumentException("lunar month must be 1..12");
            if (day < 1 || day > 30) throw new IllegalArgumentException("lunar day must be 1..30");
            if (leapMonth == null) throw new IllegalArgumentException("leapMonth is required");
        }
    }

    record Candidate(LocalDate gregorianDate, LeapMonth leapMonth) {
        public Candidate {
            if (gregorianDate == null) throw new IllegalArgumentException("gregorianDate is required");
            if (leapMonth == null || leapMonth == LeapMonth.UNSPECIFIED) {
                throw new IllegalArgumentException("candidate must identify regular or leap month");
            }
        }
    }

    /**
     * @param sourceReference 可供使用者查核的資料集名稱、版本或網址；成功結果不可空白
     */
    record Conversion(Status status, List<Candidate> candidates, String sourceReference) {
        public Conversion {
            if (status == null) throw new IllegalArgumentException("status is required");
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            sourceReference = sourceReference == null ? "" : sourceReference.strip();
            if (status == Status.RESOLVED && (candidates.size() != 1 || sourceReference.isBlank())) {
                throw new IllegalArgumentException("resolved conversion needs one candidate and a source");
            }
            if (status == Status.AMBIGUOUS_LEAP_MONTH
                    && (candidates.size() < 2 || sourceReference.isBlank())) {
                throw new IllegalArgumentException("ambiguous conversion needs candidates and a source");
            }
        }

        public static Conversion resolved(LocalDate date, LeapMonth leapMonth, String sourceReference) {
            return new Conversion(Status.RESOLVED,
                    List.of(new Candidate(date, leapMonth)), sourceReference);
        }

        public static Conversion ambiguous(List<Candidate> candidates, String sourceReference) {
            return new Conversion(Status.AMBIGUOUS_LEAP_MONTH, candidates, sourceReference);
        }

        public static Conversion failed(Status status, String sourceReference) {
            if (status == Status.RESOLVED || status == Status.AMBIGUOUS_LEAP_MONTH) {
                throw new IllegalArgumentException("use resolved or ambiguous factory");
            }
            return new Conversion(status, List.of(), sourceReference);
        }
    }
}
