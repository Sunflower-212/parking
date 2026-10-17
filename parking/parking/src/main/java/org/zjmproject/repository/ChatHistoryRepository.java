package org.zjmproject.repository;

import org.zjmproject.pojo.HistoricalSession;

import java.util.List;

public interface ChatHistoryRepository {
    void save(String type, String chatId);
    void delete(String chatId);
    List<String> getChatIds(String type);
    // 新增方法：根据类型和用户ID获取历史会话

    List<HistoricalSession> getHistoricalSessionsByTypeAndUserId(String type);

    // 新增方法：保存历史会话
    void saveHistoricalSession(HistoricalSession historicalSession);
}
