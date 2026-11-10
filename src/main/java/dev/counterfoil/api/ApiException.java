package dev.counterfoil.api;

import dev.counterfoil.ledger.LedgerException;

public class ApiException extends LedgerException {
    public ApiException(String code, int status, String message) {
        super(code, status, message);
    }

    public static ApiException bad(String message) {
        return new ApiException("BAD_REQUEST", 400, message);
    }
}
