package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.repository.ModelConfigMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModelConfigServiceWorkspaceIsolationTest {

    private ModelConfigMapper modelConfigMapper;
    private ModelConfigService service;

    @BeforeAll
    static void initMyBatisPlusCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                ModelConfigEntity.class);
    }

    @BeforeEach
    void setUp() {
        modelConfigMapper = mock(ModelConfigMapper.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        service = new ModelConfigService(modelConfigMapper, eventPublisher, mock(ModelCapabilityService.class));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void listEnabledModelsFiltersByCurrentWorkspace() {
        withWorkspace(20L);
        when(modelConfigMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of());

        service.listEnabledModels();

        LambdaQueryWrapper<ModelConfigEntity> wrapper = capturedModelQuery();
        assertTrue(wrapper.getSqlSegment().contains("workspace_id"),
                "enabled model query must be scoped by workspace_id, actual=" + wrapper.getSqlSegment()
                        + ", params=" + wrapper.getParamNameValuePairs());
    }

    @Test
    void addModelToProviderStampsCurrentWorkspace() {
        withWorkspace(20L);
        when(modelConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.addModelToProvider("openai", "gpt-4o", "GPT-4o", false);

        org.mockito.ArgumentCaptor<ModelConfigEntity> captor =
                org.mockito.ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(modelConfigMapper).insert(captor.capture());
        assertTrue(Long.valueOf(20L).equals(captor.getValue().getWorkspaceId()),
                "inserted model row must inherit the current workspace");
    }

    private LambdaQueryWrapper<ModelConfigEntity> capturedModelQuery() {
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<LambdaQueryWrapper<ModelConfigEntity>> captor =
                org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(modelConfigMapper).selectList(captor.capture());
        return captor.getValue();
    }

    private static void withWorkspace(Long workspaceId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", workspaceId.toString());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
