package vip.mate.llm.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class ModelWorkspaceResolver {

    public static final long DEFAULT_WORKSPACE_ID = 1L;

    private ModelWorkspaceResolver() {
    }

    public static long currentWorkspaceId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            String header = request.getHeader("X-Workspace-Id");
            if (header != null && !header.isBlank()) {
                try {
                    return Long.parseLong(header.trim());
                } catch (NumberFormatException ignored) {
                    return DEFAULT_WORKSPACE_ID;
                }
            }
        }
        return DEFAULT_WORKSPACE_ID;
    }
}
