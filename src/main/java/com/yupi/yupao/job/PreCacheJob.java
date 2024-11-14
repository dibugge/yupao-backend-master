package com.yupi.yupao.job;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yupi.yupao.model.domain.User;
import com.yupi.yupao.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 缓存预热
 */
@Component
@Slf4j
public class PreCacheJob {
    @Resource
    private UserService userService;
    @Resource
    private RedisTemplate<String,Object> redisTemplate;
    @Resource
    private RedissonClient redissonClient;
    //每天的第一个用户
    private List<Long> mainUserList =Arrays.asList(1L);
    @Scheduled(cron="0 0 6 * * ?")
    public void doCacheRecommend(){
        //获取锁
        RLock lock = redissonClient.getLock("shayu:precachejob:docache:lock");
        try{
            //只有一个线程可以获取倒锁
            if (lock.tryLock(0,-1,TimeUnit.MILLISECONDS)){
                System.out.printf("getLock:"+Thread.currentThread().getId());
                for (Long userId : mainUserList) {
                    //查数据
                    QueryWrapper<User> queryWrapper = new QueryWrapper<>();
                    Page<User> userPage = userService.page(new Page<>(1, 20), queryWrapper);
                    String redisKey=String.format("shayu:user:recommend:%s",mainUserList);
                    ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
                    //写缓存
                    try{
                        valueOperations.set(redisKey,userPage,30000, TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        log.error("redis set key error",e);
                    }
                }
            }
        }catch (InterruptedException e){
            log.error("doCacheRecommendUser error",e);
        }finally {
            //只能释放自己的锁
            if (lock.isHeldByCurrentThread()){
                System.out.printf("unlock"+Thread.currentThread().getId());
                lock.unlock();
            }
        }

    }
}
