package org.zjmproject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.zjmproject.controller.ChatController;
import org.zjmproject.functions.ReservationSpaceFunction;
import org.zjmproject.functions.UserInfoFunction;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PARKING_INTEGRATION_TESTS",matches="true")
@SpringBootTest(properties={"spring.config.name=application-example","spring.datasource.url=${TEST_DB_URL}",
    "spring.data.redis.port=${TEST_REDIS_PORT:6380}","parking.integrations.ai=true",
    "spring.autoconfigure.exclude=","spring.ai.openai.api-key=integration-test-only",
    "spring.ai.openai.base-url=http://127.0.0.1:1"})
class AiConfigurationTest {
    @Autowired ChatClient client;
    @Autowired ChatController controller;
    @Test void optionalAiConfigurationLoadsWithoutCallingTheProvider() {
        assertNotNull(client);assertNotNull(controller);
        assertEquals(4,ReservationSpaceFunction.ReservationSpaceRequest.class.getDeclaredFields().length);
        assertEquals(1,UserInfoFunction.UserInfoRequest.class.getRecordComponents().length);
    }
}
