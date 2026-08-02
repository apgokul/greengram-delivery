package com.localcommerce.delivery.utils;

public class UserContext {

    private static final ThreadLocal<String> currentCustomerPhone = new ThreadLocal<>();

    public static void setSenderId(String senderId) {
        currentCustomerPhone.set(senderId);
    }

    public static String getPhoneNumber() {
        return currentCustomerPhone.get();
    }

    public static void clear() {
        currentCustomerPhone.remove();
    }
}