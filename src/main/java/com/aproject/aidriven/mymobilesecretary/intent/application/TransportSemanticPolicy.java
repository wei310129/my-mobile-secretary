package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Classifies transport semantics by grammatical roles, never by person or destination allowlists. */
final class TransportSemanticPolicy {

    private static final Pattern DROP_OFF = Pattern.compile(
            "(?<actor>[^，。；;]{0,16}?)(?:接送|送|載)"
                    + "(?<beneficiary>[^，。；;]{1,18}?)(?:去|到|往|回|上)"
                    + "(?<destination>[^，。；;]{1,30})");
    private static final Pattern PICK_UP = Pattern.compile(
            "(?<actor>[^，。；;]{0,16}?)(?:去)?(?<![直間連承銜迎交對])接(?:回)?"
                    + "(?!著|受|續|收)"
                    + "(?<beneficiary>[^，。；;]{1,18}?)(?:回|去|到|從)"
                    + "(?<destination>[^，。；;]{1,30})");
    private static final Pattern PICK_UP_AT_BOUNDARY = Pattern.compile(
            "(?<actor>[^，。；;]{0,16}?)(?:去)?(?<![直間連承銜迎交對])接(?:回)?"
                    + "(?!著|受|續|收|電話|單|洽)"
                    + "(?<beneficiary>[^，。；;]{1,18}?)"
                    + "(?:下[^，。；;]{1,20}|[^，。；;]{1,20}結束|散場|離開|$)");
    private static final Pattern ACCOMPANY = Pattern.compile(
            "(?<actor>我|本人|自己|[^，。；;]{1,12}?)(?:陪同|陪)"
                    + "(?<beneficiary>[^，。；;]{1,18}?)(?:去|到|往)"
                    + "(?<destination>[^，。；;]{1,30})");
    private static final Pattern SELF_ATTENDANCE = Pattern.compile(
            "(?:^|[^由])(?:我|本人|自己)(?:要|會|將|明天|今天|等等|週.){0,8}"
                    + "(?:去|參加|出席|上)[^，。；;]{1,30}");
    private static final Pattern NON_PASSENGER_DELIVERY = Pattern.compile(
            "(?:送修|送洗|寄送|配送|運送|送貨|送件|送文件|送資料|送包裹|送餐|"
                    + "載貨|載文件|載資料|載包裹)");

    private TransportSemanticPolicy() {
    }

    static boolean isTransportToDependentActivity(String text) {
        return classify(text).isPassengerPointResponsibility();
    }

    static Decision classify(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        for (String clause : compact.split("[，。；;]|(?:然後|以及|並且)")) {
            if (clause.isBlank()) continue;
            if (NON_PASSENGER_DELIVERY.matcher(clause).find()) {
                return new Decision(
                        actor(clause),
                        BeneficiaryRole.NON_PERSON,
                        Responsibility.DELIVERY,
                        Occupancy.POINT_IN_TIME);
            }
            Matcher accompany = ACCOMPANY.matcher(clause);
            if (accompany.find()) {
                return new Decision(
                        actor(accompany.group("actor")),
                        beneficiary(accompany.group("beneficiary")),
                        Responsibility.ACCOMPANY,
                        Occupancy.UNKNOWN);
            }
            Matcher dropOff = DROP_OFF.matcher(clause);
            if (dropOff.find()) {
                return responsibility(dropOff, Responsibility.DROP_OFF);
            }
            Matcher pickUp = PICK_UP.matcher(clause);
            if (pickUp.find()) {
                return responsibility(pickUp, Responsibility.PICK_UP);
            }
            Matcher boundaryPickUp = PICK_UP_AT_BOUNDARY.matcher(clause);
            if (boundaryPickUp.find()) {
                return responsibility(
                        boundaryPickUp, Responsibility.PICK_UP);
            }
            if (SELF_ATTENDANCE.matcher(clause).find()) {
                return new Decision(
                        ActorRole.SELF,
                        BeneficiaryRole.SELF,
                        Responsibility.ATTEND,
                        Occupancy.FULL_INTERVAL);
            }
        }
        return new Decision(
                ActorRole.UNKNOWN,
                BeneficiaryRole.UNKNOWN,
                Responsibility.NONE,
                Occupancy.UNKNOWN);
    }

    private static Decision responsibility(
            Matcher matcher, Responsibility responsibility) {
        BeneficiaryRole beneficiary = beneficiary(matcher.group("beneficiary"));
        return new Decision(
                actor(matcher.group("actor")),
                beneficiary,
                responsibility,
                beneficiary == BeneficiaryRole.SELF
                        ? Occupancy.FULL_INTERVAL
                        : Occupancy.POINT_IN_TIME);
    }

    private static ActorRole actor(String evidence) {
        String value = evidence == null ? "" : evidence.replaceFirst("^由", "");
        if (value.contains("我") || value.contains("本人") || value.contains("自己")) {
            return ActorRole.SELF;
        }
        value = value.replaceAll(
                "(?:今天|明天|後天|明早|今晚|週[一二三四五六日天]|"
                        + "星期[一二三四五六日天]|禮拜[一二三四五六日天]|"
                        + "早上|上午|中午|下午|晚上|凌晨|"
                        + "\\d{1,2}(?:點|時|:[0-5]\\d)|"
                        + "[一二三四五六七八九十兩]{1,3}點)",
                "");
        return value.isBlank() ? ActorRole.UNKNOWN : ActorRole.OTHER;
    }

    private static BeneficiaryRole beneficiary(String evidence) {
        String value = evidence == null ? "" : evidence.strip();
        return value.equals("我") || value.equals("本人") || value.equals("自己")
                ? BeneficiaryRole.SELF
                : BeneficiaryRole.OTHER_PERSON;
    }

    enum ActorRole {
        SELF,
        OTHER,
        UNKNOWN
    }

    enum BeneficiaryRole {
        SELF,
        OTHER_PERSON,
        NON_PERSON,
        UNKNOWN
    }

    enum Responsibility {
        ATTEND,
        DROP_OFF,
        PICK_UP,
        DELIVERY,
        ACCOMPANY,
        NONE
    }

    enum Occupancy {
        FULL_INTERVAL,
        POINT_IN_TIME,
        UNKNOWN
    }

    record Decision(
            ActorRole actor,
            BeneficiaryRole beneficiary,
            Responsibility responsibility,
            Occupancy occupancy) {

        boolean isPointResponsibility() {
            boolean transportForAnother = isPassengerPointResponsibility();
            boolean nonPersonDelivery = beneficiary == BeneficiaryRole.NON_PERSON
                    && responsibility == Responsibility.DELIVERY;
            return (transportForAnother || nonPersonDelivery)
                    && occupancy == Occupancy.POINT_IN_TIME;
        }

        boolean isPassengerPointResponsibility() {
            return beneficiary == BeneficiaryRole.OTHER_PERSON
                    && (responsibility == Responsibility.DROP_OFF
                            || responsibility == Responsibility.PICK_UP)
                    && occupancy == Occupancy.POINT_IN_TIME;
        }
    }
}
