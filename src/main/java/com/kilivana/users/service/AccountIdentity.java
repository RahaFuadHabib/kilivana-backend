package com.kilivana.users.service;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.kilivana.common.exception.InvalidAccountDataException;
import java.util.Locale;

/** Canonical account identifiers shared by registration, profile updates and login. */
public final class AccountIdentity {
    private static final PhoneNumberUtil PHONES = PhoneNumberUtil.getInstance();

    private AccountIdentity() { }

    public static String username(String value) {
        String canonical = required(value, "username", 30).toLowerCase(Locale.ROOT);
        if (!canonical.matches("[a-z][a-z0-9_]{2,29}")) {
            throw new InvalidAccountDataException("username must match [a-z][a-z0-9_]{2,29}: 3-30 characters, starting with an ASCII letter");
        }
        return canonical;
    }

    public static String region(String value) {
        return required(value, "region", 200);
    }

    public static String required(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.trim().length() > maximum) {
            throw new InvalidAccountDataException(field + " must be nonblank and at most " + maximum + " characters");
        }
        return value.trim();
    }

    public static String phone(String value) {
        String input = required(value, "phoneNumber", 40);
        if (!input.matches("\\+?[0-9 ()-]+")) {
            throw new InvalidAccountDataException("phoneNumber must be a valid Kenyan number without extensions");
        }
        try {
            var parsed = PHONES.parse(input, "KE");
            if (parsed.hasExtension() || !PHONES.isValidNumberForRegion(parsed, "KE")) {
                throw new InvalidAccountDataException("phoneNumber must be a valid Kenyan number");
            }
            return PHONES.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException exception) {
            throw new InvalidAccountDataException("phoneNumber must be a valid Kenyan number", exception);
        }
    }
}
