package org.zjmproject.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.zjmproject.utils.Md5Util;

@Component
public class BootstrapAdmin implements ApplicationRunner {
    private final JdbcTemplate db;
    private final String username,password;
    public BootstrapAdmin(JdbcTemplate db,@Value("${parking.bootstrap.username:}") String username,@Value("${parking.bootstrap.password:}") String password) {this.db=db;this.username=username;this.password=password;}
    @Override @Transactional public void run(ApplicationArguments args) {
        if(db.queryForObject("select count(*) from admin where permissions=1",Integer.class)>0) return;
        if(!username.matches("\\S{3,16}") || !password.matches("\\S{3,16}")) throw new IllegalStateException("首次部署请设置BOOTSTRAP_ADMIN_USERNAME及BOOTSTRAP_ADMIN_PASSWORD（3-16位非空白字符，与登录格式一致）");
        db.update("insert into admin(admin_name,password,permissions,create_time,update_time) values(?,?,1,now(),now())",username,Md5Util.encode(password));
    }
}
