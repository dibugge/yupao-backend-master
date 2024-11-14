package com.yupi.yupao.service;

import com.yupi.yupao.model.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.annotation.Resource;

/**
 * redis测试
 */
@SpringBootTest
public class RedisTest {
    @Resource
    private RedisTemplate redisTemplate;
    @Test
    void test(){
        //增
        ValueOperations valueOperations = redisTemplate.opsForValue();
        valueOperations.set("shayuString","fish");
        valueOperations.set("shayuInt",1);
        valueOperations.set("shayuBouble",2.0);
        User user = new User();
        user.setId(1L);
        user.setUsername("shayu");
        valueOperations.set("shayuUser",user);

    }
}
