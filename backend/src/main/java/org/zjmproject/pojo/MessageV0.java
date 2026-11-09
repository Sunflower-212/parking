package org.zjmproject.pojo;

import lombok.Data;
import org.springframework.ai.chat.messages.Message;

@Data
public class MessageV0 {
    private String role;
    private String content;
    public MessageV0(Message message) {
        switch (message.getMessageType()){
            case USER:
                this.role = "user";
                break;
            case ASSISTANT:
                this.role = "assistant";
                break;
            default:
                this.role = "";
                break;
        }
        this.content = message.getText();
    }
}
