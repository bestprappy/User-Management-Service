package com.navio.usermanagementservice.media;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Where catalog vehicle images may live. Bundled assets ship with the client;
 * uploaded photos are served by this service through the client's public proxy.
 * Both catalog writes and garage snapshots accept exactly these shapes.
 */
public final class VehicleImagePaths {
    public static final Pattern BUNDLED = Pattern.compile("^/images/vehicles/[a-z0-9-]+\\.(png|webp)$");
    public static final Pattern UPLOADED = Pattern.compile(
            "^/api/vehicle-models/images/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private VehicleImagePaths() { }

    public static String uploaded(UUID id) { return "/api/vehicle-models/images/" + id; }

    /** True for a bundled asset or an uploaded photo; HTTPS URLs are checked by the caller. */
    public static boolean isLocal(String value) {
        return BUNDLED.matcher(value).matches() || UPLOADED.matcher(value).matches();
    }
}
