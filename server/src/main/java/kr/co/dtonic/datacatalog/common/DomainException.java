package kr.co.dtonic.datacatalog.common;

import org.springframework.http.HttpStatus;

/** A safe, user-facing business failure. Never include SQL or request secrets. */
public class DomainException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public DomainException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
