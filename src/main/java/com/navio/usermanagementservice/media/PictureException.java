package com.navio.usermanagementservice.media;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class PictureException extends RuntimeException {
    private final HttpStatus status;
    public PictureException(HttpStatus status, String message) { super(message); this.status = status; }
    public static PictureException invalid(String message) { return new PictureException(HttpStatus.BAD_REQUEST, message); }
    public static PictureException notFound() { return new PictureException(HttpStatus.NOT_FOUND, "Profile picture not found"); }
}
