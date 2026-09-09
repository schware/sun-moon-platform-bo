package com.sunmoon.bo.web.device;

import jakarta.validation.constraints.NotBlank;

/** Shared shape for create (POST) and save (PUT). {@code location} is optional. */
public record DeviceRequest(
        @NotBlank String deviceId,
        @NotBlank String name,
        @NotBlank String deviceType,
        String location,
        boolean active
) {
}
