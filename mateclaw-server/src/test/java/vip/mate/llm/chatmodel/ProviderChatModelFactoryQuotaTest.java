package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.llm.service.ProviderTokenQuotaService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProviderChatModelFactoryQuotaTest {

    @Test
    void exhaustedQuotaRejectsBeforeBuildingChatModel() {
        ModelProviderService providerService = mock(ModelProviderService.class);
        ProviderTokenQuotaService quotaService = mock(ProviderTokenQuotaService.class);
        ChatModelBuilder builder = mock(ChatModelBuilder.class);
        when(builder.supportedProtocol()).thenReturn(ModelProtocol.OPENAI_COMPATIBLE);

        ModelProviderEntity provider = new ModelProviderEntity();
        provider.setProviderId("dashscope");
        provider.setChatModel(ModelProtocol.OPENAI_COMPATIBLE.getChatModelClass());
        when(providerService.getProviderConfig("dashscope")).thenReturn(provider);
        doThrow(new MateClawException(
                "err.llm.provider_quota_exhausted",
                429,
                ProviderTokenQuotaService.QUOTA_EXHAUSTED_MESSAGE))
                .when(quotaService).assertNotExhausted(any(), eq("dashscope"));

        ProviderChatModelFactory factory = new ProviderChatModelFactory(
                List.of(builder),
                providerService,
                quotaService);
        ModelConfigEntity model = new ModelConfigEntity();
        model.setProvider("dashscope");

        MateClawException ex = assertThrows(MateClawException.class,
                () -> factory.buildFor(model, null));

        assertEquals(ProviderTokenQuotaService.QUOTA_EXHAUSTED_MESSAGE, ex.getMessage());
        assertEquals(429, ex.getCode());
        verify(builder, never()).build(any(), any(), any());
    }
}
