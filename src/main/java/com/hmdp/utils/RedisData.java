package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * expireTime：逻辑过期时间
 * data：在redis中想要存储的数据key
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
