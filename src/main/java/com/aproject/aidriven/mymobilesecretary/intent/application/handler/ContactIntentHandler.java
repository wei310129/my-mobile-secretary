package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.contact.application.ExternalContactService;
import com.aproject.aidriven.mymobilesecretary.contact.domain.ExternalContact;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Actor-private, read-only conversational lookup for imported professional contacts. */
@Component
public class ContactIntentHandler implements IntentHandler {

    private static final Set<IntentCommand.Type> TYPES = Set.of(IntentCommand.Type.ASK_CONTACT);
    private final ExternalContactService contactService;

    public ContactIntentHandler(ExternalContactService contactService) {
        this.contactService = contactService;
    }

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return TYPES;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        List<ExternalContact> contacts = contactService.search(command.title());
        if (contacts.isEmpty()) {
            String suffix = command.title() == null || command.title().isBlank()
                    ? "" : "「" + command.title().strip() + "」";
            return IntentResult.message(IntentResult.Action.CONTACT_INFO,
                    "找不到" + suffix + "的名片聯絡人紀錄。你可以上傳名片圖片建立私有聯絡資料。");
        }
        List<String> lines = new ArrayList<>();
        for (ExternalContact contact : contacts) {
            List<String> details = new ArrayList<>();
            if (contact.getOrganizationName() != null) details.add(contact.getOrganizationName());
            if (contact.getProfession() != null) details.add(contact.getProfession());
            if (contact.getPhoneNumber() != null) details.add("電話 " + contact.getPhoneNumber());
            if (contact.getEmail() != null) details.add("Email " + contact.getEmail());
            if (contact.getAddress() != null) details.add("地址 " + contact.getAddress());
            lines.add("- " + contact.getDisplayName()
                    + (details.isEmpty() ? "" : "｜" + String.join("｜", details)));
        }
        return IntentResult.message(IntentResult.Action.CONTACT_INFO,
                "找到 %d 筆本人私有名片聯絡人：\n%s"
                        .formatted(contacts.size(), String.join("\n", lines)));
    }
}
