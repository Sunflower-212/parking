package org.zjmproject.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.zjmproject.mapper.HistoricalSessionMapper;
import org.zjmproject.pojo.HistoricalSession;
import org.zjmproject.pojo.User;
import org.zjmproject.service.UserService;
import org.zjmproject.utils.ThreadLocalUtil;

import java.util.List;
import java.util.Map;

@Component
public class InMemoryChatHistoryRepository implements ChatHistoryRepository{

    @Autowired
    private HistoricalSessionMapper historicalSessionMapper;

    @Autowired
    private UserService userService;

    /**
     * 保存会话记录
     * @param type 会话类型
     * @param chatId 会话ID
     */
    @Override
    public void save(String type, String chatId) {
        Map<String, Object> map = ThreadLocalUtil.get();
        String username = (String) map.get("username");
        User user = userService.findByUserName(username);
        Integer userId = user.getId();
        if(historicalSessionMapper.findByChatId(chatId)!=null){
            return;
        }
        // 保存到数据库
        HistoricalSession historicalSession = new HistoricalSession();
        historicalSession.setChatId(chatId);
        historicalSession.setType(type);
        historicalSession.setUserId(userId.toString());
        historicalSessionMapper.insert(historicalSession);
    }

    @Override
    public void delete(String chatId) {
        // 从数据库删除
        historicalSessionMapper.deleteByChatId(chatId);
    }

    @Override
    public List<String> getChatIds(String type) {
        Map<String, Object> map = ThreadLocalUtil.get();
        String username = (String) map.get("username");
        User user = userService.findByUserName(username);
        Integer userId = user.getId();
        // 从数据库获取
        List<HistoricalSession> sessions = historicalSessionMapper.findByTypeAndUserId(type, userId.toString()); // 使用实际用户ID
        List<String> chatIds = new java.util.ArrayList<>();
        for (HistoricalSession session : sessions) {
            chatIds.add(session.getChatId());
        }
        return chatIds;
    }

    @Override
    public List<HistoricalSession> getHistoricalSessionsByTypeAndUserId(String type) {
        Map<String, Object> map = ThreadLocalUtil.get();
        String username = (String) map.get("username");
        User user = userService.findByUserName(username);
        Integer userId = user.getId();
        return historicalSessionMapper.findByTypeAndUserId(type, userId.toString());
    }

    @Override
    public void saveHistoricalSession(HistoricalSession historicalSession) {
        historicalSessionMapper.insert(historicalSession);
    }
}
