# 黑马Redis

黑马点评网：https://www.bilibili.com/video/BV1cr4y1671t/

包括：

- 短信登录：Redis的共享Session应用
- 商户查询缓存：企业的缓存技巧、缓存雪崩、穿透等问题解决
- 达人探店（博客）：基于List的点赞列表、基于SortedSet的点赞排行榜
- 优惠券秒杀：Redis的计数器、Lua脚本Redis、分布式锁、Redis的三种消息队列
- 好友关注：基于Set集合的关注、取关、共同关注、消息推送等功能
- 附近的商户：Redis的GeoHash应用
- 用户签到：Redis的BitMap数据统计功能
- UV统计：Redis的HyperLogLog的统计功能




# 技术点

## ThreadLocal（线程隔离）

**业务需求：校验用户登录状态**

​	用户在请求时候，会从cookie中携带者JsessionId到后台，后台通过JsessionId从session中拿到用户信息，如果没有session信息，则进行拦截，如果有session信息，则将用户信息保存到threadLocal中，并且放行

**1、为什么要使用拦截器来进行用户校验？**分布式部署下，在多个服务器上用拦截器实现用户校验来的优雅（AOP）

2、拦截器获取到了用户信息后**如何在后面的业务信息里获取用户信息**？ ==> ThreadLocal

- **session以后要做分布式session，最好不要存这些容易变化的东西，增加系统负担**



**关于threadlocal**

在threadLocal中，无论是他的put方法和他的get方法， 都是先从获得当前用户的线程，然后从线程中取出线程的成员变量map，只要线程不一样，map就不一样，所以可以通过这种方式来做到线程隔离

- 在拦截器的最后一个方法afterCompletion中，**清空thread local的信息，第一可以做到退出登录，第二可以防止内存泄露**

![img](https://img-blog.csdnimg.cn/48e31438138e444092625f206d3d679f.png)

**实现：**

1、自定义LoginInterceptor拦截器类实现HandlerInterceptor接口重写preHandle、afterCompletion方法

```java
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1. 获取session
        HttpSession session = request.getSession();
        //2.获取session中的用户
        Object user = session.getAttribute("user");
        //3. 判断用户是否存在
        if (user == null){
            //4. 不存在，拦截
            response.setStatus(401);
            return false;
        }

        //5. 存在 保存用户信息到ThreadLocal
        UserHolder.saveUser((UserDTO) user);
        //6. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户
        UserHolder.removeUser();
    }
}
```

2、在MvcConfig 类中配置让拦截器生效

```java
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login"
                );
    }
}
```

3、ThreadLocal在UserHolder中的实现：

```java
//5. 存在 保存用户信息到ThreadLocal
UserHolder.saveUser((UserDTO) user);
//移除用户
UserHolder.removeUser();
// 获取登录用户
UserDTO user = UserHolder.getUser();

public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}

```



###tomcat的运行原理![img](https://img-blog.csdnimg.cn/3c4abf2978e04542b8cac8ba360d3f2b.png)

当用户发起请求时，会访问我们像tomcat注册的端口，任何程序想要运行，都需要有一个线程对当前端口号进行监听，tomcat也不例外，当监听线程知道用户想要和tomcat连接连接时，那会由监听线程创建socket连接，socket都是成对出现的，用户通过socket像互相传递数据，当tomcat端的socket接受到数据后，此时监听线程会从tomcat的线程池中取出一个线程执行用户请求，在我们的服务部署到tomcat后，线程会找到用户想要访问的工程，然后用这个线程转发到工程中的controller，service，dao中，并且访问对应的DB，在用户执行完请求后，再统一返回，再找到tomcat端的socket，再将数据写回到用户端的socket，完成请求和响应

通过以上讲解，我们可以得知 每个用户其实对应都是去找tomcat线程池中的一个线程来完成工作的， 使用完成后再进行回收，既然每个请求都是独立的，所以在每个用户去访问我们的工程时，我们可以使用threadlocal来做到线程隔离，每个线程操作自己的一份数据

## 集群的session共享问题

session共享问题：多台Tomcat并不共享session存储空间，当请求切换到不同tomcat服务时导致数据丢失的问题。

session的替代方案应该满足：①数据共享 ②内存存储 ③key、value结构

![image-20220516093700643](https://img-blog.csdnimg.cn/img_convert/4b60b66a3b6ed864b250395ff94d3835.png)

**使用Redis实现共享session登录**

- 当我们发送验证码时，以手机号为key，存储验证码（String）
- 登录验证通过后，以随机token为key，存储用户数据，返回token到前端（Hash）

![image-20220516093914252](https://img-blog.csdnimg.cn/img_convert/ed58671e9be54703336b3ffbca51c2db.png)

**Redis代替Session需要考虑的问题：**

- 选择合适的数据结构
- 选择合适的Key
- 选择合适的存储粒度

