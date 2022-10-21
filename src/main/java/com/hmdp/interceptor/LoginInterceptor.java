package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //1. 获取session
//        HttpSession session = request.getSession();
//        //2.获取session中的用户
//        Object user = session.getAttribute("user");
        //TODO 1.获取请求头中的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            //4. 不存在，拦截
            response.setStatus(401);
            return false;
        }
        String key = RedisConstants.LOGIN_USER_KEY + token;
        //TODO 2.基于Token获取Redis中的用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);//entries返回整个Map

    //3. 判断用户是否存在
        if (userMap.isEmpty()){
            //4. 不存在，拦截
            response.setStatus(401);
            return false;
        }
        //TODO 5. 将查询到的Hash数据转为DTO对象
        UserDTO user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        //6. 存在 保存用户信息到ThreadLocal
        UserHolder.saveUser((UserDTO) user);
        //TODO 刷新Token有效期
        stringRedisTemplate.expire(key,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //6. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户
        UserHolder.removeUser();
    }
}
