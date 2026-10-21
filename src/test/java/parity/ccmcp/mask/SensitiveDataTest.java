package parity.ccmcp.mask;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SensitiveDataTest {

    @Test
    void typedCardsKeepOnlyTheirLastFourDigits() {
        assertThat(SensitiveData.mask("card 4111 1111 1111 1111, thanks")).isEqualTo("card [card ending 1111], thanks");
        assertThat(SensitiveData.mask("4111111111111111")).isEqualTo("[card ending 1111]");
        assertThat(SensitiveData.mask("amex 3782-822463-10005")).isEqualTo("amex [card ending 0005]");
    }

    @Test
    void spokenCardsAreFoundToo() {
        String masked = SensitiveData.mask("it's four one one one, one one one one, one one one one, one one one one ok");

        assertThat(masked).contains("[card ending 1111]").doesNotContain("four one one one");
    }

    @Test
    void numbersThatArentCardsStay() {
        assertThat(SensitiveData.mask("order 48213, call +40 721 123 456")).isEqualTo("order 48213, call +40 721 123 456");
        // A Visa prefix and length, but the Luhn check fails.
        assertThat(SensitiveData.mask("4111 1111 1111 1112")).isEqualTo("4111 1111 1111 1112");
    }

    @Test
    void ibansKeepOnlyTheirLastFourCharacters() {
        assertThat(SensitiveData.mask("pay to DE89 3704 0044 0532 0130 00 please")).isEqualTo("pay to [IBAN ending 3000] please");
        assertThat(SensitiveData.mask("GB82WEST12345698765432")).isEqualTo("[IBAN ending 5432]");
        // The check digits don't match.
        assertThat(SensitiveData.mask("DE00 3704 0044 0532 0130 00")).doesNotContain("[IBAN");
    }

    @Test
    void everyStringInJsonIsMasked() {
        JsonMapper json = JsonMapper.builder().build();
        JsonNode node = json.readTree("{\"a\":\"4111111111111111\",\"b\":[{\"c\":\"GB82WEST12345698765432\"}],\"n\":4111111111111111}");

        JsonNode masked = SensitiveData.mask(node);

        assertThat(masked.path("a").asString()).isEqualTo("[card ending 1111]");
        assertThat(masked.path("b").get(0).path("c").asString()).isEqualTo("[IBAN ending 5432]");
        assertThat(node.path("a").asString()).isEqualTo("4111111111111111");
    }
}
