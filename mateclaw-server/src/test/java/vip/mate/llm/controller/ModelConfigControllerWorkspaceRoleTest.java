package vip.mate.llm.controller;

import org.junit.jupiter.api.Test;
import vip.mate.workspace.core.annotation.RequireGlobalAdmin;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelConfigControllerWorkspaceRoleTest {

    @Test
    void settingsModelProviderFlowAllowsWorkspaceAdmins() throws Exception {
        assertWorkspaceAdmin("list");
        assertWorkspaceAdmin("catalog");
        assertWorkspaceAdmin("enableProvider", String.class);
        assertWorkspaceAdmin("disableProvider", String.class);
        assertWorkspaceAdmin("setActiveModel", requestClass("ModelSlotRequest"));
        assertWorkspaceAdmin("updateProviderConfig", String.class, requestClass("ProviderConfigRequest"));
        assertWorkspaceAdmin("createCustomProvider", requestClass("CreateCustomProviderRequest"));
        assertWorkspaceAdmin("deleteCustomProvider", String.class);
        assertWorkspaceAdmin("deleteCustomProviderByQuery", String.class);
        assertWorkspaceAdmin("addProviderModel", String.class, requestClass("AddProviderModelRequest"));
        assertWorkspaceAdmin("removeProviderModel", String.class, String.class);
        assertWorkspaceAdmin("discoverModels", String.class);
        assertWorkspaceAdmin("applyDiscoveredModels", String.class, requestClass("ApplyDiscoveredModelsRequest"));
        assertWorkspaceAdmin("testConnection", String.class);
        assertWorkspaceAdmin("testModel", String.class, String.class);
    }

    private void assertWorkspaceAdmin(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = ModelConfigController.class.getMethod(methodName, parameterTypes);

        assertNull(method.getAnnotation(RequireGlobalAdmin.class));
        RequireWorkspaceRole role = method.getAnnotation(RequireWorkspaceRole.class);
        assertEquals("admin", role.value());
    }

    private Class<?> requestClass(String simpleName) {
        return switch (simpleName) {
            case "AddProviderModelRequest" -> vip.mate.llm.model.AddProviderModelRequest.class;
            case "ApplyDiscoveredModelsRequest" -> vip.mate.llm.model.ApplyDiscoveredModelsRequest.class;
            case "CreateCustomProviderRequest" -> vip.mate.llm.model.CreateCustomProviderRequest.class;
            case "ModelSlotRequest" -> vip.mate.llm.model.ModelSlotRequest.class;
            case "ProviderConfigRequest" -> vip.mate.llm.model.ProviderConfigRequest.class;
            default -> throw new IllegalArgumentException("Unknown request class: " + simpleName);
        };
    }
}
