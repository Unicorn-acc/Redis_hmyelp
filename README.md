# 黑马Redis

黑马点评网：https://www.bilibili.com/video/BV1cr4y1671t/

笔记参考：https://blog.csdn.net/Ydin00?type=blog

包括：

- 短信登录：Redis的共享Session应用
- 商户查询缓存：企业的缓存技巧、缓存雪崩、穿透等问题解决
- 达人探店（博客）：基于List的点赞列表、基于SortedSet的点赞排行榜
- 优惠券秒杀：Redis的计数器、Lua脚本Redis、分布式锁、Redis的三种消息队列
- 好友关注：基于Set集合的关注、取关、共同关注、消息推送等功能
- 附近的商户：Redis的GeoHash应用
- 用户签到：Redis的BitMap数据统计功能
- UV统计：Redis的HyperLogLog的统计功能




Redis各数据结构使用点：

String：查询商铺：`ShopServiceImpl.queryById`

Hash：登录验证通过后保存用户信息：`UserServiceImpl.login`

List：查询商铺类型：`ShopTypeServiceImpl.queryTypeList`

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


## 缓存

缓存就是数据交换的缓冲区Cache，是存储数据的临时地方，**一般读写性能较高**。

**缓存的作用：**

- 降低后端负载
- 提高读写效率，降低响应时间

**缓存的成本：**

- 数据一致性成本
- 代码维护成本
- 运维成本

### 缓存更新策略

缓存更新是redis为了节约内存而设计出来的一个东西，主要是因为内存数据宝贵，当我们向redis插入太多数据，此时就可能会导致缓存中的数据过多，所以redis会对部分数据进行更新，或者把他叫为淘汰更合适。

**内存淘汰：**redis自动进行，当redis内存达到咱们设定的max-memery的时候，会自动触发淘汰机制，淘汰掉一些不重要的数据(可以自己设置策略方式)

**超时剔除：**当我们给redis设置了过期时间ttl之后，redis会将超时的数据进行删除，方便咱们继续使用缓存

**主动更新：**我们可以手动调用方法把缓存删掉，通常用于解决缓存和数据库不一致问题![img](https://img-blog.csdnimg.cn/5d48f0ec62414cfb9e6f1290f759cdc5.png)

**主动更新策略**

1. Cache Aside Pattern

   由缓存的调用者，在更新数据库的同时更新缓存

2. Read/Write Through Pattern

   缓存与数据库整合为一个服务，由服务来维护一致性。
   调用者调用该服务，无需关心缓存一致性问题

3. Write Behind Caching Pattern
   调用者只操作缓存，由其它线程异步的将缓存数据持久化到数据库，保证最终一致。



**数据库和缓存不一致采用什么方案**

综合考虑使用方案一，但是方案一调用者如何处理呢？这里有几个问题

操作缓存和数据库时有三个问题需要考虑：

如果采用第一个方案，那么假设我们每次操作数据库后，都操作缓存，但是中间如果没有人查询，那么这个更新动作实际上只有最后一次生效，中间的更新动作意义并不大，我们可以把缓存删除，等待再次查询时，将缓存中的数据加载出来

- **删除缓存还是更新缓存？**
  - 更新缓存：每次更新数据库都更新缓存，无效写操作较多
  - 删除缓存：更新数据库时让缓存失效，查询时再更新缓存
- **如何保证缓存与数据库的操作的同时成功或失败？**
  - 单体系统，将缓存与数据库操作放在一个事务
  - 分布式系统，利用TCC等分布式事务方案

- **先操作缓存还是先操作数据库？**
  - 先删除缓存，再操作数据库
  - 先操作数据库，再删除缓存

> 应该具体操作缓存还是操作数据库，我们应当是**先操作数据库，再删除缓存**，原因在于，如果你选择第一种方案，在两个线程并发来访问时，假设线程1先来，他先把缓存删了，此时线程2过来，他查询缓存数据并不存在，此时他写入缓存，当他写入缓存后，线程1再执行更新动作时，实际上写入的就是旧的数据，新的数据被旧数据覆盖了。

### 缓存穿透问题及解决方案

**缓存穿透**

缓存穿透 ：缓存穿透是指客户端请求的数据在缓存中和数据库中都不存在，这样缓存永远不会生效，这些请求都会打到数据库。

常见的解决方案有两种：

1. **缓存空对象**

 ◆ 优点：实现简单，维护方便

 ◆ 缺点：①额外的内存消耗、②可能造成短期的不一致

2. **布隆过滤**

◆ 优点：内存占用较少，没有多余key

◆ 缺点：①实现复杂、②存在误判可能

![image-20220516102010002](https://img-blog.csdnimg.cn/img_convert/bb2aa50c42306f057fb691ba79f79131.png)

> **缓存空对象思路分析：**当我们客户端访问不存在的数据时，先请求redis，但是此时redis中没有数据，此时会访问到数据库，但是数据库中也没有数据，这个数据穿透了缓存，直击数据库，我们都知道数据库能够承载的并发不如redis这么高，如果大量的请求同时过来访问这种不存在的数据，这些请求就都会访问到数据库，简单的解决方案就是哪怕这个数据在数据库中也不存在，我们也把这个数据存入到redis中去，这样，下次用户过来访问这个不存在的数据，那么在redis中也能找到这个数据就不会进入到缓存了
>
> **布隆过滤：**布隆过滤器其实采用的是哈希思想来解决这个问题，通过一个庞大的二进制数组，走哈希思想去判断当前这个要查询的这个数据是否存在，如果布隆过滤器判断存在，则放行，这个请求会去访问redis，哪怕此时redis中的数据过期了，但是数据库中一定存在这个数据，在数据库中查询出来这个数据后，再将其放入到redis中，
>
> 假设布隆过滤器判断这个数据不存在，则直接返回
>
> 这种方式优点在于节约内存空间，存在误判，误判原因在于：布隆过滤器走的是哈希思想，只要哈希思想，就可能存在哈希冲突



**编码解决商品查询的缓存穿透问题**

应用，缓存null值：ShopServiceImpl.queryById

![img](https://img-blog.csdnimg.cn/9ba9003ef6b7406397fb997bf3312818.png)

**小总结：**

**缓存穿透产生的原因是什么？**

- 用户请求的数据在缓存中和数据库中都不存在，不断发起这样的请求，给数据库带来巨大压力

**缓存穿透的解决方案有哪些？**

- 缓存null值
- 布隆过滤
- 增强id的复杂度，避免被猜测id规律
- 做好数据的基础格式校验
- 加强用户权限校验
- 做好热点参数的限流--sentinel

### 缓存雪崩问题及解决思路

缓存雪崩是指在**同一时段大量的缓存key同时失效或者Redis服务宕机**，导致大量请求到达数据库，带来巨大压力。

解决方案：

- 给不同的Key的TTL添加随机值
- 利用Redis集群提高服务的可用性
- 给缓存业务添加降级限流策略
- 给业务添加多级缓存

![img](https://img-blog.csdnimg.cn/aea1be74a8df43639d73e478ecae728c.png)



### 缓存击穿问题及解决思路

缓存击穿问题也叫热点Key问题，就是一个被**高并发访问**并且**缓存重建业务较复杂**的key**突然失效了**，无数的请求访问会在瞬间给数据库带来巨大的冲击。

> 逻辑分析：假设线程1在查询缓存之后，本来应该去查询数据库，然后把这个数据重新加载到缓存的，此时只要线程1走完这个逻辑，其他线程就都能从缓存中加载这些数据了，但是假设在线程1没有走完的时候，后续的线程2，线程3，线程4同时过来访问当前这个方法， 那么这些线程都不能从缓存中查询到数据，那么他们就会同一时刻来访问查询缓存，都没查到，接着同一时间去访问数据库，同时的去执行数据库代码，对数据库访问压力过大

![img](https://img-blog.csdnimg.cn/d9294a5cfd6743e9b3a084d3a76a8bd4.png)

常见的解决方案有两种：（案例：查询商铺信息）

- 互斥锁（P44，很细，多看`ShopServiceImpl.queryWithMutex`）
- 逻辑过期（`ShopServiceImpl.queryWithLogicalExpire`）

**解决方案一、使用互斥锁来解决**

因为锁能实现互斥性。假设线程过来，只能一个人一个人的来访问数据库，从而避免对于数据库访问压力过大，但这也会影响查询的性能，因为此时会让查询的性能从并行变成了串行，我们可以采用tryLock方法 + double check来解决这样的问题。

> 假设现在线程1过来访问，他查询缓存没有命中，但是此时他获得到了锁的资源，那么线程1就会一个人去执行逻辑，假设现在线程2过来，线程2在执行过程中，并没有获得到锁，那么线程2就可以进行到休眠，直到线程1把锁释放后，线程2获得到锁，然后再来执行逻辑，此时就能够从缓存中拿到数据了。

<img src="https://img-blog.csdnimg.cn/img_convert/6f1194fedcac27ee6eb505661cb39587.png" width="500">

**解决方案二、逻辑过期方案**

方案分析：我们之所以会出现这个缓存击穿问题，主要原因是在于我们对key设置了过期时间，假设我们不设置过期时间，其实就不会有缓存击穿的问题，但是不设置过期时间，这样数据不就一直占用我们内存了吗，我们可以采用逻辑过期方案。

我们把过期时间设置在 redis的value中，注意：这个过期时间并不会直接作用于redis，而是我们后续通过逻辑去处理。

> 假设线程1去查询缓存，然后从value中判断出来当前的数据已经过期了，此时线程1去获得互斥锁，那么其他线程会进行阻塞，**获得了锁的线程他会开启一个 线程去进行 以前的重构数据的逻辑**，直到新开的线程完成这个逻辑后，才释放锁， 而线程1直接进行返回，假设现在线程3过来访问，由于线程线程2持有着锁，所以线程3无法获得锁，线程3也直接返回数据，只有等到新开的线程2把重建数据构建完后，其他线程才能走返回正确的数据。

这种方案巧妙在于，异步的构建缓存，缺点在于在构建完缓存之前，返回的都是脏数据。

<img src="https://img-blog.csdnimg.cn/ecc9bad176d04450b3eae7e62474e346.png" width="500">

两种解决方案对比：

​	**互斥锁方案：**由于保证了互斥性，所以数据一致，且实现简单，因为仅仅只需要加一把锁而已，也没其他的事情需要操心，所以没有额外的内存消耗，缺点在于有锁就有死锁问题的发生，且只能串行执行性能肯定受到影响

​	**逻辑过期方案：** 线程读取过程中不需要等待，性能好，有一个额外的线程持有锁去进行重构数据，但是在重构数据完成前，其他的线程只能返回之前的数据，且实现起来麻烦

![image-20220516104936500](https://img-blog.csdnimg.cn/img_convert/7627fa67a0e2243d957c8513522c5893.png)

需求：修改根据id查询商铺的信息的业务，使用互斥锁解决缓存击穿问题--`setnx key value`

​	核心思路：相较于原来从缓存中查询不到数据后直接查询数据库而言，现在的方案是 **进行查询之后，如果从缓存没有查询到数据，则进行互斥锁的获取，获取互斥锁后，判断是否获得到了锁，如果没有获得到，则休眠，过一会再进行尝试，直到获取到锁为止，才能进行查询**

​	如果获取到了锁的线程，再去进行查询，查询后将数据写入redis，再释放锁，返回数据，利用互斥锁就能保证只有一个线程去执行操作数据库的逻辑，防止缓存击穿



实际应用：查询店铺信息

- 互斥锁（P44，很细，多看`ShopServiceImpl.queryWithMutex`）

<img src="https://img-blog.csdnimg.cn/img_convert/0d8450aab0b215bdfb60a5f3b91ffe85.png">

- 逻辑过期（`ShopServiceImpl.queryWithLogicalExpire`）

![image-20220516104745962](https://img-blog.csdnimg.cn/img_convert/d53fd2d9a9635389eb3ec2e4fc74b662.png)

### 封装Redis缓存工具类

`P46`：基于StringRedisTemplate封装一个缓存工具类`CacheClient`，满足下列需求：

- 方法1：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
- 方法2：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题


- 方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
- 方法4：根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题