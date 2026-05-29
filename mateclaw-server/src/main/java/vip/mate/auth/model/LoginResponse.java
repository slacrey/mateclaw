package vip.mate.auth.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录响应
 *
 * @author MateClaw Team
 */
@Data
@AllArgsConstructor
public class LoginResponse {
    private Long id;
    private String token;
    private String username;
    private String nickname;
    private String role;
    private LocalDateTime expiresAt;
    private boolean expired;
    private Long currentWorkspaceId;
}
