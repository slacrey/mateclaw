package vip.mate.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "mateclaw.llm.default-provider-keys")
public class DefaultProviderKeyProperties {

    private String dashscope;

    private String deepseek;
}
