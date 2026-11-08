package org.zjmproject.config;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.zjmproject.ai.*;
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="parking.integrations.ai",havingValue="true")
public class AIConfig {
    @Bean public DeepSeekThinkingChatModel thinkingModel(ObjectMapper json,AiSessions sessions,
        @Value("${spring.ai.openai.base-url:https://api.deepseek.com}") String base,
        @Value("${spring.ai.openai.api-key:}") String key,
        @Value("${spring.ai.openai.chat.options.model:deepseek-flash}") String model) {
        return new DeepSeekThinkingChatModel(json,base,key,model,sessions);
    }
    @Bean public ChatClient chatClient(DeepSeekThinkingChatModel model){return ChatClient.builder(model).build();}
}
