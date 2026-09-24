package jev.dashboard.datasource;

import jev.dashboard.ApiException;
import org.springframework.http.HttpStatus;

public class UploadParseException extends ApiException {
    public UploadParseException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
