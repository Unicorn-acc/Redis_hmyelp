package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;

/**
 * @author MiracloW
 * @date 2022-10-24 10:13
 */
public interface IFollowService extends IService<Follow> {

    public Result follow(Long followUserId,Boolean isFollow);

    public Result isFollow(Long followUserId);
}
