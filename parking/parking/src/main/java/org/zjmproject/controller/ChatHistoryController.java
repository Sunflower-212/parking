package org.zjmproject.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zjmproject.pojo.MessageV0;
import org.zjmproject.repository.ChatHistoryRepository;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/history")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "parking.integrations.ai", havingValue = "true")
public class ChatHistoryController {
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate db;
    @Autowired
    private ChatHistoryRepository chatHistoryRepository;

    @Autowired
    private ChatMemory chatMemory;

    @GetMapping("/{type}")
    public List<String> getChatIds(@PathVariable("type") String type) {
        return chatHistoryRepository.getChatIds(type);
    }

    @GetMapping("/{type}/{chatId}")
    public List<MessageV0> getChatHistory(@PathVariable("type") String type, @PathVariable("chatId") String chatId) {
        int actorId = org.zjmproject.service.AccessGuard.userId();
        if (db.queryForObject("select count(*) from historicalSession where chatId=? and userId=? and type=?", Integer.class, chatId, Integer.toString(actorId), type) != 1)
            throw new IllegalArgumentException("无权访问此会话");
        List<Message> messages = chatMemory.get(actorId + ":" + chatId, 1000);
        if (messages == null) {
            return List.of();
        }
        return messages.stream().map(MessageV0::new).toList();
    }
}
