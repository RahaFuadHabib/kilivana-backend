package com.kilivana.common.exception;

public class InvalidAccountDataException extends IllegalArgumentException {
    public InvalidAccountDataException(String message) { super(message); }
    public InvalidAccountDataException(String message, Throwable cause) { super(message, cause); }
}
