package vip.mate.llm.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DefaultProviderKeyProperties.class)
public class LlmAutoConfiguration {
}
