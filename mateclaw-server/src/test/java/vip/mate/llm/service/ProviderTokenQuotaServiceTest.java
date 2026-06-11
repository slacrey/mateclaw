package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ProviderTokenQuotaEntity;
import vip.mate.llm.repository.ProviderTokenQuotaMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProviderTokenQuotaServiceTest {

    private ProviderTokenQuotaMapper mapper;
    private ProviderTokenQuotaService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ProviderTokenQuotaMapper.class);
        service = new ProviderTokenQuotaService(mapper);
    }

    @Test
    void ensureDefaultQuotasCreatesDashscopeAndDeepseekRows() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.ensureDefaultQuotas(20L);

        var captor = org.mockito.ArgumentCaptor.forClass(ProviderTokenQuotaEntity.class);
        verify(mapper, times(2)).insert(captor.capture());

        ProviderTokenQuotaEntity dashscope = captor.getAllValues().get(0);
        assertEquals(20L, dashscope.getWorkspaceId());
        assertEquals("dashscope", dashscope.getProviderId());
        assertEquals(2_000_000L, dashscope.getLimitTokens());
        assertEquals(0L, dashscope.getUsedTokens());

        ProviderTokenQuotaEntity deepseek = captor.getAllValues().get(1);
        assertEquals("deepseek", deepseek.getProviderId());
        assertEquals(3_000_000L, deepseek.getLimitTokens());
    }

    @Test
    void assertNotExhaustedRejectsManagedProviderAtLimit() {
        ProviderTokenQuotaEntity quota = quota("dashscope", 2_000_000L, 2_000_000L);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.assertNotExhausted(20L, "dashscope"));

        assertEquals("额度已用完，请在 设置-模型管理 中充值或切换模型", ex.getMessage());
        assertEquals(429, ex.getCode());
    }

    @Test
    void recordUsageAddsPromptAndCompletionTokens() {
        ProviderTokenQuotaEntity quota = quota("deepseek", 3_000_000L, 12L);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota);

        service.recordUsage(20L, "deepseek", 30, 40);

        verify(mapper).incrementUsedTokens(20L, "deepseek", 70L);
        verify(mapper, never()).updateById(any(ProviderTokenQuotaEntity.class));
    }

    @Test
    void unmanagedProviderDoesNothing() {
        service.assertNotExhausted(20L, "openai");
        service.recordUsage(20L, "openai", 10, 20);

        verifyNoInteractions(mapper);
    }

    private static ProviderTokenQuotaEntity quota(String providerId, long limit, long used) {
        ProviderTokenQuotaEntity quota = new ProviderTokenQuotaEntity();
        quota.setWorkspaceId(20L);
        quota.setProviderId(providerId);
        quota.setLimitTokens(limit);
        quota.setUsedTokens(used);
        return quota;
    }
}
