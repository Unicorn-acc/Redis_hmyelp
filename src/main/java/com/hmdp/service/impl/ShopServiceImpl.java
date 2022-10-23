package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import io.netty.handler.codec.json.JsonObjectDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //使用工具类进行缓存
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
//        TODO 缓存空指针解决缓存穿透问题
//        Shop shop = queryWithPassThrough(id);
//
//        TODO 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        if(shop == null){
//            return Result.fail("店铺不存在");
//        }
//
//        TODO 逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
//
//        TODO 使用工具类继续缓存处理
//        TODO 1.利用缓存空值的方式解决缓存穿透问题
        Shop shop = cacheClient.queryWithPassThrough(
                CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
//        TODO 2.逻辑过期解决缓存击穿
//        TODO 热点商品缓存永不过期(需要手动初始key)，才可以继续使用逻辑过期方式解决缓存击穿
//        Shop shop = cacheClient.queryWithLogicalExpire(
//                CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return Result.ok(shop);

    }

    //缓存重建的线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿问题
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存(使用String序列化来演示)
        String shopjson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isBlank(shopjson)){
            //TODO 3.若缓存未命中，则直接返回
            return null;
        }

        //TODO 4.命中，需要判断过期时间
        //      先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopjson, RedisData.class);
        //JSONObject data = (JSONObject)redisData.getData();//因为RedisData的data是Object类型，转化时会变成JSONObject对象
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //  5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){//过期时间在当前时间之后
            //  5.1未过期，直接返回店铺信息
            return shop;
        }
        //  5.2过期，需要进行缓存重建
        //  6.缓存重建
        // 6.1获取互斥锁
        String lockkey = LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockkey);
        //  6.2判断是否获取锁成功
        if(!isLock){
            //  6.3成功，开启独立线程，实现缓存重建
            // TODO 使用线程池进行进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    log.info("获取锁成功，进行缓存重建");
                    //将 数据写入Redis缓存中
                    this.saveShop2Redis(id,20L);

                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    log.info("释放锁");
                    unLock(lockkey);
                }
            });

        }
        //  6.4返回过期的商铺信息
        return shop;
    }

    ////互斥锁解决缓存击穿问题实现
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;

        // 1.从redis查询商铺缓存(使用String序列化来演示)
        String shopjson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopjson)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(shopjson, Shop.class);
        }
        //TODO 判断命中的是否是空值
        if (shopjson != null) {
            return null;
        }

        //TODO 4.实现缓存重建
        //      4.1 获取互斥锁
        String lockkey = "lock:shop:" + id;
        Shop shop = null;
        try{
            boolean isLock = tryLock(lockkey);
            //      4.2 判断是否获取成功
            if (!isLock) {
                //      4.3失败则休眠重试
                Thread.sleep(50);
                return queryWithMutex(id);
                //TODO 注意：获取锁成功后应该再次检测Redis缓存是否存在，做DoubleCheck，如果存在则无需重建缓存
            }
            log.info("获得锁成功，开始查询数据库");
            //      4.4.成功，根据id查询数据库
            shop = this.getById(id);

            //模拟访问服务器的延时
            Thread.sleep(200);

            // 5.不存在，返回错误
            if (shop == null) {
                //TODO 解决缓存穿透问题方法1：将空值写入Redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);

                return null;
            }

            // 6.存在，写入Redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        }catch (Exception e){
            throw new RuntimeException(e);
        }finally {
            //TODO 7.释放互斥锁
            log.info("查询结束，释放锁");
            unLock(lockkey);
        }
        // 8.返回
        return shop;
    }

    //解决缓存穿透问题方式1：缓存空对象
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存(使用String序列化来演示)
        String shopjson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isNotBlank(shopjson)){
            // 3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopjson, Shop.class);
            return shop;
        }
        //TODO 解决缓存穿透方法1的查询
        if(shopjson != null){
            return null;
        }

        // 4.不存在，根据id查询数据库
        Shop shop = this.getById(id);

        // 5.不存在，返回错误
        if(shop == null){
            //TODO 解决缓存穿透问题方法1：将空值写入Redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);

            return null;
        }

        // 6.存在，写入Redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL,TimeUnit.MINUTES);

        // 7.返回
        return shop;
    }

    //互斥锁解决缓存击穿问题-加锁
    private boolean tryLock(String key){
        //setIfAbsent：如果缺失、不存在这个key，则可以set
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//如果包装类是null，就会报空指针异常
    }

    //互斥锁解决缓存击穿问题-解锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }


    //逻辑过期解决缓存击穿问题-向Redis写入店铺数据和逻辑过期时间（自定义类实现）
    public void saveShop2Redis(Long id, Long expireSeconds){
        //1.查询店铺数据
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);

        // 2.删除缓存（分布式需要mq传递消息，因为redis可能不是这个服务器上管理的）
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);

        return null;
    }
}
