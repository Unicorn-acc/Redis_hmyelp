package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private ShopServiceImpl shopService;

    @Autowired
    private CacheClient cacheClient;

    @Test
    void testSaveShop(){
        shopService.saveShop2Redis(1L,10L);
    }

    @Test
    void testSaveShopWithUtil(){
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY+1L,shop,10L, TimeUnit.MINUTES);
    }
}
