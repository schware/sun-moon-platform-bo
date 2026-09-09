package com.sunmoon.bo.auth;

import com.sunmoon.bo.domain.operator.OperatorScreenPermission;
import com.sunmoon.bo.domain.operator.Screen;

import java.util.Map;

/** Permissions are loaded once at login and cached here, rather than hitting {@code OperatorRepository} on every request. */
public record Session(
        String sessionId,
        long operatorId,
        String username,
        String displayName,
        boolean superAdmin,
        Map<Screen, OperatorScreenPermission> permissionsByScreen
) {
}
