package org.zjmproject.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.zjmproject.functions.*;
import org.zjmproject.service.*;
import reactor.core.publisher.Flux;
import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/ai")
@ConditionalOnProperty(name="parking.integrations.ai",havingValue="true")
public class ChatController {
    private final ChatClient client;
    private final JdbcTemplate db;
    private final UserService users;
    private final ParkingService parks;
    private final SpacesService spaces;
    private final ParkingFlowService flow;
    @GetMapping(value="/chat",produces="text/plain;charset=UTF-8")
    public Flux<String> chat(@RequestParam String message,@RequestParam String chatId) {
        final int actorId=AccessGuard.userId();
        if(chatId.isBlank() || chatId.length()>100 || message.isBlank() || message.length()>8000) throw new IllegalArgumentException("会话ID或消息长度无效");
        db.update("insert into historicalSession(chatId,type,userId,create_time,update_time) values(?,'chat',?,now(),now()) on duplicate key update chatId=chatId",chatId,Integer.toString(actorId));
        if(db.queryForObject("select count(*) from historicalSession where chatId=? and userId=?",Integer.class,chatId,Integer.toString(actorId))!=1) throw new IllegalArgumentException("无权访问此会话");
        return client.prompt().user(message)
            .advisors(a->a.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY,actorId+":"+chatId))
            .function("parkingFunctionV2","查询停车场",ParkingFunction.ParkingRequest.class,ignored->parks.parkingList())
            .function("userInfoFunction","查询当前登录用户资料",UserInfoFunction.UserInfoRequest.class,ignored->{
                var u=users.findUserByUserId(actorId);
                return Map.of("username",u.getUser_name(),"plateNumber",u.getPlate_num()==null?"":u.getPlate_num(),"balance",u.getBalance());
            })
            .function("spaceFunction","查询停车场车位",SpaceFunction.SpaceRequest.class,spaces::findSpacesByParking)
            .function("reservationSpaceFunction","为当前登录用户预约指定空闲车位",ReservationSpaceFunction.ReservationSpaceRequest.class,request->{
                var space=spaces.findSpacesByInfo(request.getParkingName(),request.getSpaces_row(),request.getSpaces_col(),request.getSpaces_storey());
                if(space==null) throw new IllegalArgumentException("车位不存在");
                return flow.reserve(actorId,space.getId());
            }).stream().content().timeout(java.time.Duration.ofSeconds(60));
    }
}
