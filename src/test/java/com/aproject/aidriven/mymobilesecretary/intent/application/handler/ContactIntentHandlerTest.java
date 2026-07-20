package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.contact.application.ExternalContactService;
import com.aproject.aidriven.mymobilesecretary.contact.domain.ExternalContact;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContactIntentHandlerTest {

    @Test
    void returnsMatchingPrivateContactDetails() {
        ExternalContactService service = mock(ExternalContactService.class);
        ExternalContact contact = mock(ExternalContact.class);
        when(contact.getDisplayName()).thenReturn("王師傅");
        when(contact.getOrganizationName()).thenReturn("安心水電");
        when(contact.getProfession()).thenReturn("水電師傅");
        when(contact.getPhoneNumber()).thenReturn("0912-345-678");
        when(service.search("水電")).thenReturn(List.of(contact));
        ContactIntentHandler handler = new ContactIntentHandler(service);

        var result = handler.handle("水電師傅電話是多少", command("水電"));

        assertThat(result.action().name()).isEqualTo("CONTACT_INFO");
        assertThat(result.message()).contains("王師傅", "安心水電", "0912-345-678");
    }

    private static IntentCommand command(String title) {
        return new IntentCommand(IntentCommand.Type.ASK_CONTACT, title, null, null, null,
                null, null, null, null, null, null, null, null);
    }
}
