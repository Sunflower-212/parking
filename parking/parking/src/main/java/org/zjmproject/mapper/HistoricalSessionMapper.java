package org.zjmproject.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.zjmproject.pojo.HistoricalSession;
import java.util.List;

@Mapper
public interface HistoricalSessionMapper {

    void insert(HistoricalSession historicalSession);
    List<HistoricalSession> findByTypeAndUserId(String type, String userId);
    void deleteByChatId(String chatId);

    HistoricalSession findByChatId(String chatId);
}
