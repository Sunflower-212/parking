package org.zjmproject;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.zjmproject.utils.Md5Util;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PARKING_INTEGRATION_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.config.name=application-example","spring.datasource.url=${TEST_DB_URL}",
    "spring.data.redis.port=${TEST_REDIS_PORT}","parking.background.enabled=false"})
class LoginResponseIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired TestRestTemplate http;
    @Autowired StringRedisTemplate redis;
    String username,correlation;
    @BeforeEach void prepare(){
        username="login"+UUID.randomUUID().toString().replace("-","").substring(0,10);correlation=UUID.randomUUID().toString();
        db.update("insert into user(user_name,password,create_time,update_time) values(?,?,now(),now())",username,Md5Util.encode("LoginTest123"));
        db.update("insert into admin(admin_name,password,permissions,create_time,update_time) values(?,?,0,now(),now())",username,Md5Util.encode("LoginTest123"));
    }
    @AfterEach void cleanup(){
        redis.delete(List.of("user="+username,"admin="+username));
        db.update("delete from admin_audit where request_id=?",correlation);
        db.update("delete from user where user_name=?",username);db.update("delete from admin where admin_name=?",username);
    }
    Map<?,?> login(String kind,String name,String password){
        var form=new LinkedMultiValueMap<String,String>();form.add("username",name);form.add("password",password);
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);headers.set("X-Request-ID",correlation);
        var response=http.postForEntity("/"+kind+"/login",new HttpEntity<>(form,headers),Map.class);
        assertEquals(HttpStatus.OK,response.getStatusCode());return Objects.requireNonNull(response.getBody());
    }
    void verify(String kind){
        var unknown=login(kind,"missingAccount","WrongTest123");var mismatch=login(kind,username,"WrongTest123");
        for(var failure:List.of(unknown,mismatch)){
            assertEquals(1,failure.get("code"));assertEquals("账号或者密码错误",failure.get("message"));assertNull(failure.get("data"));
        }
        assertNull(redis.opsForValue().get(kind+"="+username));
        var success=login(kind,username,"LoginTest123");assertEquals(0,success.get("code"));
        assertInstanceOf(String.class,success.get("data"));assertEquals(success.get("data"),redis.opsForValue().get(kind+"="+username));
    }
    @Test void userCredentialsHaveOnePublicFailureReason(){verify("user");}
    @Test void administratorCredentialsHaveOnePublicFailureReason(){verify("admin");}
}
