package org.zjmproject;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.zjmproject.config.BootstrapAdmin;
import org.zjmproject.utils.Md5Util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BootstrapAdminTest {
    @Test void shortNumericPasswordCreatesAnAdministratorWithAHash() {
        var db=mock(JdbcTemplate.class);
        when(db.queryForObject(anyString(),eq(Integer.class))).thenReturn(0);
        new BootstrapAdmin(db,"admin","123456").run(new DefaultApplicationArguments());
        verify(db).update(anyString(),eq("admin"),argThat((String hash)->
                !"123456".equals(hash) && Md5Util.checkPassword("123456",hash)));
    }

    @Test void invalidLoginFormatsFailBeforeCreatingAnUnusableAccount() {
        for(String password:new String[]{"", "12", "has spaces", "12345678901234567"}) {
            var db=mock(JdbcTemplate.class);
            when(db.queryForObject(anyString(),eq(Integer.class))).thenReturn(0);
            assertThrows(IllegalStateException.class,()->
                    new BootstrapAdmin(db,"admin",password).run(new DefaultApplicationArguments()));
            verify(db,never()).update(anyString(),any(Object[].class));
        }
    }

    @Test void existingAdministratorIsNotReset() {
        var db=mock(JdbcTemplate.class);
        when(db.queryForObject(anyString(),eq(Integer.class))).thenReturn(1);
        new BootstrapAdmin(db,"admin","").run(new DefaultApplicationArguments());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}
