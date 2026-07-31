package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Applies an explicit address only to the tenant-scoped place carried by a LINE quote. */
@Service
public class QuotedPlaceCorrectionConversationService {

    private static final Pattern QUOTED_PLACE =
            Pattern.compile("【LINE 引用參考】[^\\n]*\\bPLACE:(?<id>\\d+):\\d+");
    private static final Pattern STREET_NUMBER = Pattern.compile(
            "(?:路|街|大道|巷)\\s*\\d+(?:之\\d+)?號");
    private static final Pattern ADDRESS_PREFIX = Pattern.compile(
            "^(?:正確(?:的)?地址(?:是|為)?|地址(?:是|為)?|應該是|改成)\\s*");

    private final PlaceService places;

    public QuotedPlaceCorrectionConversationService(PlaceService places) {
        this.places = places;
    }

    public Optional<IntentResult> answer(
            String userText, String interpretationText, Runnable beforeMutation) {
        String address = explicitAddress(userText);
        if (address == null || interpretationText == null) {
            return Optional.empty();
        }
        Matcher reference = QUOTED_PLACE.matcher(interpretationText);
        if (!reference.find()) {
            return Optional.empty();
        }
        long placeId = Long.parseLong(reference.group("id"));
        places.getPlace(placeId);
        beforeMutation.run();
        Place updated = places.updateAddress(placeId, address);
        return Optional.of(IntentResult.message(
                IntentResult.Action.PLACE_UPDATED,
                "已依你明確提供的地址更新「%s」。\n- 地址：%s"
                        .formatted(updated.getName(), updated.getAddress())));
    }

    private static String explicitAddress(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String candidate = ADDRESS_PREFIX.matcher(text.strip()).replaceFirst("").strip();
        return STREET_NUMBER.matcher(candidate).find() ? candidate : null;
    }
}
