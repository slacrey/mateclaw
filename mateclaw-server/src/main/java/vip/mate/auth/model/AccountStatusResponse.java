package vip.mate.auth.model;

import java.time.LocalDateTime;

public record AccountStatusResponse(
        Long id,
        String username,
        String nickname,
        String role,
        LocalDateTime expiresAt,
        boolean expired) {

    public static AccountStatusResponse from(UserEntity user, boolean expired) {
        return new AccountStatusResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getRole(),
                user.getExpiresAt(),
                expired);
    }
}
