package dev.jvmsentinel.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonStringsTest {
    @Test
    void quotesSpecialCharacters() {
        assertEquals("\"hello\\n\\\"world\\\"\"", JsonStrings.quote("hello\n\"world\""));
    }

    @Test
    void extractsEscapedJsonText() {
        String reply = "{\"model\":\"small\",\"response\":\"thread\\nblocked\\\"yes\\\"\",\"done\":true}";
        assertEquals("thread\nblocked\"yes\"", JsonStrings.extractStringField(reply, "response"));
    }

    @Test
    void missingResponseFieldThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> JsonStrings.extractStringField("{}", "response"));
    }
    @Test
    void rejectsDuplicateKeysTrailingContentAndInvalidNumberGrammar() {
        for (String invalid : java.util.List.of(
                "{\"a\":null,\"a\":true}", "{}{}", "{\"n\":01}", "{\"n\":1.}", "{\"n\":+1}")) {
            assertThrows(IllegalArgumentException.class, () -> Json.object(invalid));
        }
    }
}
