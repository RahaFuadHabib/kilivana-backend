package com.kilivana.support;

import java.util.concurrent.atomic.AtomicInteger;

public final class TestAccounts {
    private static final AtomicInteger PHONES = new AtomicInteger(100000);
    private TestAccounts() { }
    public static String nextPhone() { return "+254700" + PHONES.getAndIncrement(); }
}
