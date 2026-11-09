package org.zjmproject.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.zjmproject.service.EnterService;
import org.zjmproject.service.RechargesService;
import org.zjmproject.service.SpacesService;
import org.zjmproject.service.UserService;

@Component
@EnableScheduling
public class QuartzConfig {
    @Autowired
    private UserService userService;

    @Autowired
    private RechargesService rechargesService;

    @Autowired
    private SpacesService spacesService;

    @Autowired
    private EnterService enterService;

    @Scheduled(cron = "0 0/5 * * * ?")
    public void checkAndUpdateUserStatus(){
        userService.checkAndUpdateUserStatus();
    }

}
