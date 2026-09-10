package com.jasonlat.ai.types.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class Validator {

    // Regular expression for validating phone numbers
    private static final String PHONE_REGEX = "^\\+?\\d{1,3}?[- ]?\\d{5,15}$";
    private static final Pattern PHONE_PATTERN = Pattern.compile(PHONE_REGEX);

    // Regular expression for validating email addresses
    private static final String EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$";
    private static final Pattern EMAIL_PATTERN = Pattern.compile(EMAIL_REGEX);

    public static boolean isValidPhoneNumber(String phoneNumber) {
        if (phoneNumber == null) {
            return false;
        }
        Matcher matcher = PHONE_PATTERN.matcher(phoneNumber);
        return matcher.matches();
    }

    public static boolean isValidEmail(String email) {
        if (email == null) {
            return false;
        }
        Matcher matcher = EMAIL_PATTERN.matcher(email);
        return matcher.matches();
    }

    private static final String PASSWORD_REGEX = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)[a-zA-Z\\d]{8,}$";
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(PASSWORD_REGEX);

    public static boolean validatePassword(String password) {
        Matcher matcher = PASSWORD_PATTERN.matcher(password);
        return matcher.matches();
    }

    public static void main(String[] args) {






        // Test cases
        System.out.println(isValidPhoneNumber("+1234567890")); // true
        System.out.println(isValidPhoneNumber("1234567890")); // true
        System.out.println(isValidPhoneNumber("123-456-7890")); // false

        System.out.println(isValidEmail("test@example.com")); // true
        System.out.println(isValidEmail("test.email@domain.co.in")); // true
        System.out.println(isValidEmail("test.email@domain")); // false
    }


}