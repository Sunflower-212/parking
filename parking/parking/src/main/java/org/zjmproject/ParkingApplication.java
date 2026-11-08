package org.zjmproject;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


// The application supplies a thinking-aware ChatModel; do not instantiate a second,
// unused OpenAI model (which also rejects an empty key before /ai/status can respond).
@SpringBootApplication(exclude=org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration.class)
public class ParkingApplication
{
    public static void main( String[] args )
    {

        SpringApplication.run(ParkingApplication.class,args);
    }
}
