package com.kilivana.users;

import com.kilivana.users.service.AccountIdentity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AccountIdentityTest {
    @ParameterizedTest
    @ValueSource(strings = {"0712345678", "712345678", "254712345678", "+254712345678", "0712 345 678", "+254 (712) 345-678"})
    void equivalentKenyanPhonesHaveOneIdentity(String input) {
        assertThat(AccountIdentity.phone(input)).isEqualTo("+254712345678");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "+12025550123", "0712345678x1", "call 0712345678", "++254712345678"})
    void invalidForeignAndExtendedNumbersAreRejected(String input) {
        assertThatIllegalArgumentException().isThrownBy(() -> AccountIdentity.phone(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {" Green_User ", "GREEN_USER", "green_user"})
    void usernamesUseTrimmedRootLocaleLowercase(String input) {
        assertThat(AccountIdentity.username(input)).isEqualTo("green_user");
    }

    @ParameterizedTest
    @ValueSource(strings = {"amara", "amara_osei", "farmer123"})
    void acceptsAgreedUsernameExamples(String input) {
        assertThat(AccountIdentity.username(input)).isEqualTo(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "123farmer", "farmer-name", "farmer name", "abcdefghijklmnopqrstuvwxyz12345", "_farmer", "ab", "", "éclair"})
    void rejectsInvalidUsernames(String input) {
        assertThatIllegalArgumentException().isThrownBy(() -> AccountIdentity.username(input));
    }

    @Test
    void canonicalLengthBoundariesApplyAfterTrimmingAndLowercasing() {
        assertThat(AccountIdentity.username("  AMA  ")).isEqualTo("ama");
        assertThat(AccountIdentity.username("  " + "A".repeat(30) + "  ")).isEqualTo("a".repeat(30));
        assertThatIllegalArgumentException().isThrownBy(() -> AccountIdentity.username("a".repeat(31)));
    }

    @Test
    void lowercaseUsesRootLocaleEvenWhenDefaultLocaleIsTurkish() {
        var original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertThat(AccountIdentity.username("INSPECTOR")).isEqualTo("inspector");
        } finally {
            java.util.Locale.setDefault(original);
        }
    }
}
