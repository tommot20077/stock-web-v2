package dowob.xyz.stockwebv2.user.api;

import dowob.xyz.stockwebv2.common.model.Role;
import dowob.xyz.stockwebv2.common.model.UserStatus;

import java.util.UUID;

public record MeResponse(Long id, UUID uuid, String email, String username, Role role, UserStatus status) {
}
