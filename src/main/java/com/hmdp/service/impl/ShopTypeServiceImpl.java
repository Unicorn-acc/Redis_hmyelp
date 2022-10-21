package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 1.查redis缓存是否有shoptype(使用list实现)
        List<String> shopTypeList = new ArrayList<>();

        shopTypeList = stringRedisTemplate.opsForList().range(RedisConstants.CACHE_SHOPTYPE_KEY,0,-1);

        // 2.判断是否存在
        if (!shopTypeList.isEmpty()) {
            // 3.存在，直接返回
            List<ShopType> typeList = new ArrayList<>();
            for(String s : shopTypeList){
                ShopType shopType = JSONUtil.toBean(s,ShopType.class);
                typeList.add(shopType);
            }
            return Result.ok(typeList);
        }
        // 4.不存在，则去数据库中进行查询
        List<ShopType> typeList = this
                .query().orderByAsc("sort").list();
        // 5.判断是否存在，不存在直接返回错误
        if(typeList.isEmpty()){
            return Result.fail("不存在分类");
        }
        //添加redis缓存
        for(ShopType shopType : typeList){
            String s = JSONUtil.toJsonStr(shopType);

            shopTypeList.add(s);
        }
        stringRedisTemplate.opsForList().rightPushAll(RedisConstants.CACHE_SHOPTYPE_KEY,shopTypeList);

        return Result.ok(typeList);
    }
}
