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




项目运行：

1、nginx

2、redis

3、项目8081、8082

4、Mysql

---

**Redis在秒杀场景下的应用：**

- 缓存
- 分布式锁
- 超卖问题
- Lua脚本
- Redis消息队列

---

Redis各数据结构使用点：

String：

- 查询商铺：`ShopServiceImpl.queryById`

Hash：

- 登录验证通过后保存用户信息：`UserServiceImpl.login`
- 保存作为可重入锁的信息

List：

- 查询商铺类型：`ShopTypeServiceImpl.queryTypeList`

  作为消息队列使用：

Set：

- 点赞功能，重复点为取消点赞：`BlogServiceImpl.likeblog`

SortedSet:

- 点赞排行榜，显示最早点赞的前5(朋友圈点赞)：

  ​	更改`BlogServiceImpl.likeblog`，实现`BlogServiceImpl.queryBlogLikes`



# Redis实战篇技术点

缓存篇：

缓存击穿、缓存雪崩、缓存穿透问题==>展示店铺列表+热点商品逻辑过期`ShopServiceImpl`

锁篇：判断秒杀库存+校验一人一单==>`VoucherOrderServiceImpl`

秒杀优化：同步处理秒杀太耗时，使用**异步**将判断用户资格和订单入库分开

- VoucherOrderServiceImpl.seckkillVoucher



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

### 集群的session共享问题

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


## 2、缓存

**目录：**

1、什么是缓存

2、添加Redis缓存

3、缓存更新策略

4、缓存穿透

5、缓存雪崩

6、缓存击穿

7、缓存工具类封装

---

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

**主动更新的方案**

1. **Cache Aside Pattern**

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

**常见的解决方案有两种：（案例：查询商铺信息）**

1、**互斥锁**（P44，很细，多看`ShopServiceImpl.queryWithMutex`）

- 思路：给缓存重建过程加锁，确保重建过程只有一个线程执行，其他线程等待
- 优点：①实现简单、②没有额外内存消耗、③一致性好
- 缺点：①等待导致性能下降、②有死锁风险

2、**逻辑过期**（`ShopServiceImpl.queryWithLogicalExpire`）

- 思路：<u>热点key缓存永不过期</u>，而是设置一个逻辑过期时间，查询到数据时通过对逻辑过期时间判断，来决定是否需要重建缓存（①重建缓存也通过互斥锁保证单线程执行、②重建缓存利用独立线程异步执行、③其他线程无需等待，直接查询到旧数据返回即可）
- 优点：①线程无需等待，性能较好
- 缺点：①不保证一致性、②有额外内存消耗、③实现复杂



> 问题逻辑分析：假设线程1在查询缓存之后，本来应该去查询数据库，然后把这个数据重新加载到缓存的，此时只要线程1走完这个逻辑，其他线程就都能从缓存中加载这些数据了，但是假设在线程1没有走完的时候，后续的线程2，线程3，线程4同时过来访问当前这个方法， 那么这些线程都不能从缓存中查询到数据，那么他们就会同一时刻来访问查询缓存，都没查到，接着同一时间去访问数据库，同时的去执行数据库代码，对数据库访问压力过大

![img](https://img-blog.csdnimg.cn/d9294a5cfd6743e9b3a084d3a76a8bd4.png)

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

**两种解决方案对比：**

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

## 3、优惠券秒杀(锁！！)

**目录：**

1、全局唯一ID

2、实现优惠券秒杀下单

3、超卖问题

4、一人一单

5、分布式锁

6、Redis优化秒杀

7、Redis消息队列实现异步秒杀



### 全局唯一ID(基于Redis自增)

每个店铺都可以发布优惠券，当用户抢购时，就会生成订单并保存到tb_voucher_order这张表中，而订单表如果使用数据库自增ID就存在一些问题：

- id的规律性太明显
- 受单表数据量的限制

> 场景分析：如果我们的id具有太明显的规则，用户或者说商业对手很容易猜测出来我们的一些敏感信息，比如商城在一天时间内，卖出了多少单，这明显不合适。
>
> 场景分析二：随着我们商城规模越来越大，mysql的单表的容量不宜超过500W，数据量过大之后，我们要进行拆库拆表，但拆分表了之后，他们从逻辑上讲他们是同一张表，所以他们的id是不能一样的， 于是乎我们需要保证id的唯一性。

**全局ID生成器**，是一种在分布式系统下用来生成**全局唯一ID**的工具，一般要满足下列特性：

<img src="https://img-blog.csdnimg.cn/3b89d9289cb944b49f33c05021b18a58.png" width="500">

 为了增加ID的安全性，不要直接使用Redis自增的数值，而是拼接一些其它信息： ![img](https://img-blog.csdnimg.cn/470ad5d5afac49e39e9be57ff346986d.png)

ID的组成部分：符号位：1bit，永远为0

时间戳：31bit，以秒为单位，可以使用69年

序列号：32bit，秒内的计数器，支持每秒产生2^32个不同ID

#### 线程池测试生成唯一ID

`long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);`

```java
@Resource
private RedisIdWorker redisIdWorker;

private ExecutorService es = Executors.newFixedThreadPool(500);

@Test
void TestIdWorker() throws Exception{
    CountDownLatch latch = new CountDownLatch(300);
    Runnable task = () -> {
        for(int i = 0; i < 100; i++){
            long id = redisIdWorker.nextId("order");
            System.out.println("id = " + id);
        }
        latch.countDown();
    };
    long begin = System.currentTimeMillis();
    for(int i = 0; i < 300; i++){
        es.submit(task);
    }
    latch.await();
    long end = System.currentTimeMillis();
    System.out.println("time = " + (end-begin));//time = 3218
}

public long nextId(String keyPrefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1.获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3.拼接并返回
        return timestamp << COUNT_BITS | count;
    }
```

---

**总结：**

**全局唯一ID生成策略：**

- UUID
- Redis自增
- snowflake算法
- 数据库自增

**Redis自增ID策略：** 

- 每天一个key，方便统计订单量
- ID构造是 **时间戳+计数器**一ID生成策略：

### 乐观锁悲观锁

**悲观锁**

认为线程安全问题一定会发生，因此 在操作数据之前先获取锁，确保线程 串行执行。例如synchronized、Lock都属于悲观锁。

> 优点：简单粗暴
>
> 缺点：性能一般



**乐观锁**

认为线程安全问题不一定会发生，因 此不加锁，只是在更新数据时去判断 有没有其它线程对数据做了修改。

◆ 如果没有修改则认为是安全的，自 己才更新数据。

◆ 如果已经被其它线程修改说明发生了安全问题，此时可以重试或异常。

1. 版本号法

   ![image-20220516210403193](https://img-blog.csdnimg.cn/img_convert/d6abecb0a451903cdc8378e57f17573b.png)

   > 需要增加一个版本号字段，如果修改了的话就增加1

   ![image-20220516210114906](https://img-blog.csdnimg.cn/img_convert/6613deaccf8f969525dccd310f0f9c28.png)

2. CAS法

   > 扣减前获取库存量，调用sql扣减时判断此时库存是否等于前面查到的库存，如果没有变化就是没有人修改过

   ![image-20220516210633910](https://img-blog.csdnimg.cn/img_convert/0968c3fdc9f5bc5f468777d857deff69.png)

> 性能好，但适合读多写少的情况，这种秒杀的情况，读跟写差不多的话使用CAS就回导致成功率低



**mysql的排他锁**

只需要在更新时判断此时库存是否大于0

```
update goods set stock = stock - 1 WHERE id = 1001 and stock > 0
```

使用update时会加排他锁，这一行不能被任何其他线程修改和读写

排他锁又称为写锁，简称X锁，顾名思义，排他锁就是不能与其他所并存，如一个事务获取了一个数据行的排他锁，其他事务就不能再获取该行的其他锁，包括共享锁和排他锁，但是获取排他锁的事务是可以对数据就行读取和修改。

**乐观锁和悲观锁总结：**

![img](https://img-blog.csdnimg.cn/c2265fad71774ea0b38accdd27623a10.png)



#### 超卖问题分析（乐观锁解决）

优惠券秒杀属于高并发操作，存在库存超卖问题。--`VoucherOrderServiceImpl.seckkillVoucher`

超卖问题是典型的多线程安全问题，针对这一问题的常见解决方案就是加锁。



解决方案：

只需要在更新时判断此时库存是否大于0

```
update goods set stock = stock - 1 WHERE id = 1001 and stock > 0
```

使用update时会加排他锁，这一行不能被任何其他线程修改和读写



#### 一人一单问题

##### 单机情况下（悲观锁解决）

需求：修改秒杀业务，要求同一个优惠券，一个用户只能下一单

具体操作逻辑如下：比如时间是否充足，如果时间充足，则进一步判断库存是否足够，然后再根据优惠卷id和用户id查询是否已经下过这个订单，如果下过这个订单，则不再下单，否则进行下单

![img](https://img-blog.csdnimg.cn/50b2bd8cc8914edcb4a80df7cebee5b1.png)

**存在问题：**现在的问题还是和之前一样，**并发过来，查询数据库，都不存在订单**，所以我们还是需要加锁，但是**乐观锁比较适合更新数据，而现在是插入数据，所以我们需要使用悲观锁操作**

**	注意：**在这里提到了非常多的问题，我们需要慢慢的来思考，首先我们的初始方案是封装了一个createVoucherOrder方法，同时为了确保他线程安全，添加了一把synchronized 锁

intern() 这个方法是从常量池中拿到数据，如果我们直接使用userId.toString() 他拿到的对象实际上是不同的对象，new出来的对象，我们使用锁必须保证锁必须是同一把，所以我们需要使用intern()方法



但是以上代码还是存在问题，**问题的原因在于当前方法被spring的事务控制，如果你在方法内部加锁，可能会导致当前方法事务还没有提交，但是锁已经释放也会导致问题**，所以我们选择将当前方法整体包裹起来，确保事务不会出现问题：如下：

在seckillVoucher 方法中，添加以下逻辑，这样就能保证事务的特性，同时也控制了锁的粒度

```java
Long userId = UserHolder.getUser().getId();
synchronized (userId.toString().intern()){
    return this.createVoucherOrder(voucherId);
}
```

 但是以上做法依然有问题，因为你调用的方法，其实是this.的方式调用的**，事务想要生效，还得利用代理来生效，所以这个地方，我们需要获得原始的事务对象， 来操作事务 。**

为了实现以上创建代理对象，需要引用aspectj相关依赖，并在主启动类添加相应注解`@EnableAspectJAutoProxy(exposeProxy = true)`。

        <dependency>
            <groupId>org.aspectj</groupId>
            <artifactId>aspectjweaver</artifactId>
        </dependency>
```java
@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.hmdp.mapper")
@SpringBootApplication
public class HmDianPingApplication {
 
    public static void main(String[] args) {
        SpringApplication.run(HmDianPingApplication.class, args);
    }
}
```

## 4、分布式锁

### 集群环境下（Redis实现分布式锁）

#### 如何快速配置项目集群

通过加锁可以解决在单机情况下的一人一单安全问题，但是在集群模式下就不行了。

1、将服务启动两份，端口分别为8081和8082：<img src="https://img-blog.csdnimg.cn/7b5456edd708411bb40a10284a555646.png" width="500">

 

2、然后修改nginx的conf目录下的nginx.conf文件，配置反向代理和负载均衡： ![img](https://img-blog.csdnimg.cn/77c0a65f4da54d3f9b5ba58b4a37c619.png)

3.重启nginx可以在控制台执行nginx.exe -s reload命令，若不生效，则通过任务管理器将所有nginx服务都关闭 

---

**单机情况下**

可以使用锁来保证并发安全

<img src="https://img-blog.csdnimg.cn/img_convert/4ab78b3fc9736d1dd2aa175721c7b75a.png" width="450">

 **分布式下**

![image-20220516212333213](https://img-blog.csdnimg.cn/img_convert/780c9750d45a74ebae358abf42481937.png)

> 分布式锁是由共享存储系统维护的变量，多个客户端可以向共享存储系统发送命令进行加 锁或释放锁操作。Redis 作为一个共享存储系统，可以用来实现分布式锁。

**有关锁失效原因分析**

​	由于现在我们部署了多个tomcat，每个tomcat都有一个属于自己的jvm，那么假设在服务器A的tomcat内部，有两个线程，这两个线程由于使用的是同一份代码，那么他们的锁对象是同一个，是可以实现互斥的，但是如果现在是服务器B的tomcat内部，又有两个线程，但是他们的锁对象写的虽然和服务器A一样，但是**锁对象却不是同一个**，所以线程3和线程4可以实现互斥，但是却无法和线程1和线程2实现互斥，这就是 集群环境下，syn锁失效的原因，在这种情况下，我们就需要使用分布式锁来解决这个问题。



### 分布式锁基本原理和实现方式对比

分布式锁：满足分布式系统或集群模式下多进程可见并且互斥的锁。

**分布式锁的核心思想**就是让大家都使用同一把锁，只要大家使用的是同一把锁，那么我们就能锁住线程，不让线程进行，让程序串行执行，这就是分布式锁的核心思路



**分布式锁他应该满足一些什么样的条件？**

- 可见性：多个线程都能看到相同的结果，注意：这个地方说的可见性并不是并发编程中指的内存可见性，只是说多个进程之间都能感知到变化的意思
- 互斥：互斥是分布式锁的最基本的条件，使得程序串行执行
- 高可用：程序不易崩溃，时时刻刻都保证较高的可用性
- 高性能：由于加锁本身就让性能降低，所有对于分布式锁本身需要他就较高的加锁性能和释放锁性能
- 安全性：安全也是程序中必不可少的一环



**常见的分布式锁有三种**

Mysql：mysql本身就带有锁机制，但是由于mysql性能本身一般，所以采用分布式锁的情况下，其实使用mysql作为分布式锁比较少见

Redis：redis作为分布式锁是非常常见的一种使用方式，现在企业级开发中基本都使用redis或者zookeeper作为分布式锁，利用setnx这个方法，如果插入key成功，则表示获得到了锁，如果有人插入成功，其他人插入失败则表示无法获得到锁，利用这套逻辑来实现分布式锁

Zookeeper：zookeeper也是企业级开发中较好的一个实现分布式锁的方案，由于本套视频并不讲解zookeeper的原理和分布式锁的实现，所以不过多阐述![img](https://img-blog.csdnimg.cn/e4de5eeccb3c4310b54cd3e8f182c2aa.png)

### 基于Redis实现分布式锁

实现基于Redis分布式锁需要实现的两个基本方法

<img src="https://img-blog.csdnimg.cn/22faaeb3f1c34ca68717a35f2dc66384.png" width="450">



#### 实现分布式锁版本一

- 加锁逻辑

利用redis 的setNx 方法，当有多个线程进入时，就利用该方法，第一个线程进入时，redis 中就有这个key 了，返回了1，如果结果是1，则表示他抢到了锁，那么他去执行业务，然后再删除锁，退出锁逻辑，没有抢到锁的线程，等待一定时间后重试即可

<img src="https://img-blog.csdnimg.cn/d568dca7721d4a4f9711f88beb628cf9.png" width="450">

#### Redis分布式锁误删情况说明

逻辑说明：

持有锁的线程在锁的内部出现了阻塞，导致他的锁自动释放，这时其他线程，线程2来尝试获得锁，就拿到了这把锁，然后线程2在持有锁执行过程中，线程1反应过来，继续执行，而线程1执行过程中，走到了删除锁逻辑，此时就会把本应该属于线程2的锁进行删除，这就是误删别人锁的情况说明

解决方案：解决方案就是在每个线程释放锁的时候，去判断一下当前这把锁是否属于自己，如果属于自己，则不进行锁的删除，假设还是上边的情况，线程1卡顿，锁自动释放，线程2进入到锁的内部执行逻辑，此时线程1反应过来，然后删除锁，但是线程1，一看当前这把锁不是属于自己，于是不进行删除锁逻辑，当线程2走到删除锁逻辑时，如果没有卡过自动释放锁的时间点，则判断当前这把锁是属于自己的，于是删除这把锁。![img](https://img-blog.csdnimg.cn/b3fb7cfbb1e5493897984f1f129ca868.png)

#### 解决误删问题版本二

需求：修改之前的分布式锁实现，满足：在获取锁时存入线程标示（可以用UUID表示） 在释放锁时先获取锁中的线程标示，判断是否与当前线程标示一致

- 如果一致则释放锁
- 如果不一致则不释放锁

核心逻辑：在存入锁时，放入自己线程的标识，在删除锁时，判断当前这把锁的标识是不是自己存入的，如果是，则进行删除，如果不是，则不进行删除。

<img src="https://img-blog.csdnimg.cn/480bcc2259da48649a803122365e581e.png" width="450">

#### 分布式锁的原子性问题说明

更为极端的误删逻辑说明：

线程1现在持有锁之后，在执行业务逻辑过程中，他正准备删除锁，而且已经走到了条件判断的过程中，比如他已经拿到了当前这把锁确实是属于他自己的，正准备删除锁，但是此时他的锁到期了，那么此时线程2进来，但是线程1他会接着往后执行，当他卡顿结束后，他直接就会执行删除锁那行代码，相当于条件判断并没有起到作用，这就是删锁时的原子性问题，之所以有这个问题，是因为线程1的拿锁，比锁，删锁，实际上并不是原子性的，我们要防止刚才的情况发生，![img](https://img-blog.csdnimg.cn/09bb73954ba14f0593b9777a6e25f977.png)

#### Lua脚本解决原子性问题版本三

> **为什么Redis中lua脚本可以保证原子性？**
>
> ​	在redis 的官方文档中有描述lua脚本在执行的时候具有排他性，不允许其他命令或者脚本执行，类似于事务。但是存在的另一个问题是，它在执行的过程中如果一个命令报错不会回滚已执行的命令，所以要保证lua脚本的正确性
>
> 查阅总结：lua脚本在执行的时候不允许其他命令出入并发执行，而且他们有两个运行的函数call()和pcall(),两者区别在于，call在执行命令的时候如果报错，会抛出reisd错误终端执行，而pcall不会中断会记录下来错误信息

对于**加锁操作**，我们需要满足三个条件

1. 加锁包括了**读取锁变量、检查锁变量值和设置锁变量值三个操作**，但需要以原子操作的 方式完成，所以，我们使用 SET 命令带上 NX 选项来实现加锁；
2. 锁变量需要**设置过期时间**，以免客户端拿到锁后发生异常，导致锁一直无法释放，所 以，我们在 SET 命令执行时加上 EX/PX 选项，设置其过期时间；
3. 锁变量的值需要能**区分来自不同客户端的加锁操作**，以免在释放锁时，出现误释放操 作，所以，我们使用 SET 命令设置锁变量值时，每个客户端设置的值是一个唯一值，用 于**标识客户端**。

对于**释放锁操作**，需要注意

释放锁包含了**读取锁变量值、判断锁变量值和删除锁变量三个操作**，我们无法使用单个命令来实现，所以，我们可以采用 Lua 脚本执行释放锁操作，通过 Redis 原子性地执行 Lua 脚本，来保证释放锁操作的原子性。

<img src="https://img-blog.csdnimg.cn/img_convert/4d4f8c1bef72e87b566dded0a55b5aca.png" width="400">

加锁操作，需要加入线程标识

```java
public boolean tryLock(long timeoutSec) {
    // 获取线程标示
    String threadId = ID_PREFIX + Thread.currentThread().getId();
    // 获取锁
    Boolean success = stringRedisTemplate.opsForValue()
        .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
    return Boolean.TRUE.equals(success);
}
```

释放锁，调用lua脚本

- 如果脚本中的key和value不想写死，可以作为参数传递。key类型参数会放入KEYS数组，其他参数会放入ARGV数组，在脚本中可以从KEYS和ARGV数组中获取这些参数；

```lua
-- 这里的 KEYS[1] 就是锁的key，这里的ARGV[1] 就是当前线程标示
-- 获取锁中的标示，判断是否与当前线程标示一致
if (redis.call('GET', KEYS[1]) == ARGV[1]) then
  -- 一致，则删除锁
  return redis.call('DEL', KEYS[1])
end
-- 不一致，则直接返回
return 0
```

利用RedisTemplate调用Lua脚本的API如下：

RedisTemplate中，可以利用execute方法去执行lua脚本，参数对应关系就如下图![img](https://img-blog.csdnimg.cn/f3b66541e9f24a90b504be2b8311db40.png)

```java
private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
static {
    UNLOCK_SCRIPT = new DefaultRedisScript<>();
    UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
    UNLOCK_SCRIPT.setResultType(Long.class);
}

@Override
public void unlock() {
    //TODO  调用lua脚本来实现释放锁
    stringRedisTemplate.execute(
            UNLOCK_SCRIPT,
            Collections.singletonList(KEY_PREFIX + name),
            ID_PREFIX + Thread.currentThread().getId());
}
```

#### 总结：分布式锁实现思路

小总结：

基于Redis的分布式锁实现思路：

- 利用set nx ex获取锁，并设置过期时间，保存线程标示
- 释放锁时先判断线程标示是否与自己一致，一致则删除锁
  - 特性：
    - 利用set nx满足互斥性
    - 利用set ex保证故障时锁依然能释放，避免死锁，提高安全性
    - 利用Redis集群保证高可用和高并发特性

笔者总结：我们一路走来，利用添加过期时间，防止死锁问题的发生，但是有了过期时间之后，可能出现误删别人锁的问题，这个问题我们开始是利用删之前 通过拿锁，比锁，删锁这个逻辑来解决的，也就是删之前判断一下当前这把锁是否是属于自己的，但是现在还有原子性问题，也就是我们没法保证拿锁比锁删锁是一个原子性的动作，最后通过lua表达式来解决这个问题

但是目前还剩下一个问题锁不住，什么是锁不住呢，你想一想，如果当过期时间到了之后，我们可以给他续期一下，比如续个30s，就好像是网吧上网， 网费到了之后，然后说，来，网管，再给我来10块的，是不是后边的问题都不会发生了，那么续期问题怎么解决呢，可以依赖于我们接下来要学习redission啦

## 5、分布式锁-redisson

### redisson功能介绍

> https://github.com/redisson/redisson

**基于setnx实现的分布式锁存在下面的问题：**

**重入问题**：重入问题是指 获得锁的线程可以再次进入到相同的锁的代码块中，可重入锁的意义在于防止死锁，比如HashTable这样的代码中，他的方法都是使用synchronized修饰的，假如他在一个方法内，调用另一个方法，那么此时如果是不可重入的，不就死锁了吗？所以可重入锁他的主要意义是防止死锁，我们的synchronized和Lock锁都是可重入的。

**不可重试**：是指目前的分布式只能尝试一次，我们认为合理的情况是：当线程在获得锁失败后，他应该能再次尝试获得锁。

**超时释放：**我们在加锁时增加了过期时间，这样的我们可以防止死锁，但是如果卡顿的时间超长，虽然我们采用了lua表达式防止删锁的时候，误删别人的锁，但是毕竟没有锁住，有安全隐患

**主从一致性：** 如果Redis提供了主从集群，当我们向集群写数据时，主机需要异步的将数据同步给从机，而万一在同步过去之前，主机宕机了，就会出现死锁问题。![img](https://img-blog.csdnimg.cn/4f4c450da13243dfacfcc3815d120d4c.png)

Redisson是一个在Redis的基础上实现的Java驻内存数据网格（In-Memory Data Grid）。它不仅提供了一系列的分布式的Java常用对象，还提供了许多分布式服务，其中就包含了各种分布式锁的实现。

### Redisson快速入门

引入依赖：

```
<dependency>
	<groupId>org.redisson</groupId>
	<artifactId>redisson</artifactId>
	<version>3.13.6</version>
</dependency>
```

配置Redisson客户端：

```java
@Configuration
public class RedissonConfig {
 
    @Bean
    public RedissonClient redissonClient(){
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.19.128:6379")
                .setPassword("123456");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
```

如何使用Redission的分布式锁

```java

@Resource
private RedissionClient redissonClient;
 
@Test
void testRedisson() throws Exception{
    //获取锁(可重入)，指定锁的名称
    RLock lock = redissonClient.getLock("anyLock");
    //尝试获取锁，参数分别是：获取锁的最大等待时间(期间会重试)，锁自动释放时间，时间单位
    boolean isLock = lock.tryLock(1,10,TimeUnit.SECONDS);
    //判断获取锁成功
    if(isLock){
        try{
            System.out.println("执行业务");          
        }finally{
            //释放锁
            lock.unlock();
        }
        
    }
```

### redisson可重入锁原理P66

在Lock锁中，他是借助于底层的一个voaltile的一个state变量来记录重入的状态的，比如当前没有人持有这把锁，那么state=0，假如有人持有这把锁，那么state=1，如果持有这把锁的人再次持有这把锁，那么state就会+1 ，如果是对于synchronized而言，他在c语言代码中会有一个count，原理和state类似，也是重入一次就加一，释放一次就-1 ，直到减少成0 时，表示当前这把锁没有被人持有。

在redission中，我们的也支持支持可重入锁

在分布式锁中，他采用hash结构用来存储锁，其中大key表示表示这把锁是否存在，用小key表示当前这把锁被哪个线程持有

```Lua
"if (redis.call('exists', KEYS[1]) == 0) then " +
    "redis.call('hset', KEYS[1], ARGV[2], 1); " +
    "redis.call('pexpire', KEYS[1], ARGV[1]); " +
    "return nil; " +
"end; " +

"if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then " +
    "redis.call('hincrby', KEYS[1], ARGV[2], 1); " +
    "redis.call('pexpire', KEYS[1], ARGV[1]); " +
    "return nil; " +
"end; " +
"return redis.call('pttl', KEYS[1]);"
```

判断数据是否存在 name：是lock是否存在,如果==0，就表示当前这把锁不存在

redis.call('hset', KEYS[1], ARGV[2], 1);此时他就开始往redis里边去写数据 ，写成一个hash结构



如果当前这把锁存在，则第一个条件不满足，再判断

redis.call('hexists', KEYS[1], ARGV[2]) == 1

此时需要通过大key+小key判断当前这把锁是否是属于自己的，如果是自己的，则进行

redis.call('hincrby', KEYS[1], ARGV[2], 1)

将当前这个锁的value进行+1 ，redis.call('pexpire', KEYS[1], ARGV[1]); 然后再对其设置过期时间，如果以上两个条件都不满足，则表示当前这把锁抢锁失败，最后返回pttl，即为当前这把锁的失效时间

![img](https://img-blog.csdnimg.cn/42a864da719f4b52ad19659b9fd36830.png)

**获取锁的Lua脚本：**

![img](https://img-blog.csdnimg.cn/f3416bd4eb784266bbc05cdc68c84000.png)

**释放锁的Lua脚本：**

![img](https://img-blog.csdnimg.cn/9475663c5b5f470a95081a88490e20f1.png)

### redisson锁重试和WatchDog超时释放机制原理P67

抢锁过程中，获得当前线程，通过tryAcquire进行抢锁，该抢锁逻辑和之前逻辑相同

1、先判断当前这把锁是否存在，如果不存在，插入一把锁，返回null

2、判断当前这把锁是否是属于当前线程，如果是，则返回null

所以如果返回是null，则代表着当前这哥们已经抢锁完毕，或者可重入完毕，但是如果以上两个条件都不满足，则进入到第三个条件，返回的是锁的失效时间，同学们可以自行往下翻一点点，你能发现有个while( true) 再次进行tryAcquire进行抢锁

```
long threadId = Thread.currentThread().getId();
Long ttl = tryAcquire(-1, leaseTime, unit, threadId);
// lock acquired
if (ttl == null) {
    return;
}
```

 接下来会有一个条件分支，因为lock方法有重载方法，一个是带参数，一个是不带参数，如果不带参数传入的值是-1，如果传入参数，则leaseTime是他本身，所以如果传入了参数，此时leaseTime != -1 则会进去抢锁，抢锁的逻辑就是之前说的那三个逻辑	

```
if (leaseTime != -1) {
    return tryLockInnerAsync(waitTime, leaseTime, unit, threadId, RedisCommands.EVAL_LONG);
}
```

如果是没有传入时间，则此时也会进行抢锁， 而且抢锁时间是默认看门狗时间 `commandExecutor.getConnectionManager().getCfg().getLockWatchdogTimeout()`

`ttlRemainingFuture.onComplete((ttlRemaining, e)` 这句话相当于对以上抢锁进行了监听，也就是说当上边抢锁完毕后，此方法会被调用，具体调用的逻辑就是去后台开启一个线程，进行**续约逻辑**，也就是看门狗线程

```java
private <T> RFuture<Long> tryAcquireAsync(long waitTime, long leaseTime, TimeUnit unit, long threadId) {
        if (leaseTime != -1L) {
            return this.tryLockInnerAsync(waitTime, leaseTime, unit, threadId, RedisCommands.EVAL_LONG);
        } else {
            RFuture<Long> ttlRemainingFuture = this.tryLockInnerAsync(waitTime, this.commandExecutor.getConnectionManager().getCfg().getLockWatchdogTimeout(), TimeUnit.MILLISECONDS, threadId, RedisCommands.EVAL_LONG);
            ttlRemainingFuture.onComplete((ttlRemaining, e) -> {
                if (e == null) {
                    if (ttlRemaining == null) {
                        this.scheduleExpirationRenewal(threadId);
                    }

                }
            });
            return ttlRemainingFuture;
        }
    }
```

此逻辑就是续约逻辑，注意看commandExecutor.getConnectionManager().newTimeout() 此方法

Method( **new** TimerTask() {},参数2 ，参数3 )

指的是：通过参数2，参数3 去描述什么时候去做参数1的事情，现在的情况是：**10s之后去做参数一的事情**

**因为锁的失效时间是30s，当10s之后，此时这个timeTask 就触发了，他就去进行续约，把当前这把锁续约成30s，如果操作成功，那么此时就会递归调用自己，再重新设置一个timeTask()，于是再过10s后又再设置一个timerTask，完成不停的续约**

那么大家可以想一想，假设我们的线程出现了宕机他还会续约吗？当然不会，因为没有人再去调用renewExpiration这个方法，所以等到时间之后自然就释放了。

```java
//锁重试中更新有效期机制
private void renewExpiration() {
        RedissonLock.ExpirationEntry ee = (RedissonLock.ExpirationEntry)EXPIRATION_RENEWAL_MAP.get(this.getEntryName());
        if (ee != null) {
            Timeout task = this.commandExecutor.getConnectionManager().newTimeout(
          new TimerTask() {
                public void run(Timeout timeout) throws Exception {
                    RedissonLock.ExpirationEntry ent = (RedissonLock.ExpirationEntry)
                      RedissonLock.EXPIRATION_RENEWAL_MAP.get(
                      								RedissonLock.this.getEntryName());
                    if (ent != null) {
                        Long threadId = ent.getFirstThreadId();
                        if (threadId != null) {
                          //renewExpirationAsync刷新有效期
                            RFuture<Boolean> future = RedissonLock.this.
                              					renewExpirationAsync(threadId);
                            future.onComplete((res, e) -> {
                                if (e != null) {
                                    RedissonLock.log.error("Can't update lock " + RedissonLock.
                                                           this.getName() + " expiration", e);
                                } else {
                                    if (res) {
                                      //如果拿到锁了，那么就调自己（更新有小气）
                                        RedissonLock.this.renewExpiration();
                                    }

                                }
                            });
                        }
                    }
                }
              //过了30s/3进行重试
            }, this.internalLockLeaseTime / 3L, TimeUnit.MILLISECONDS);
            ee.setTimeout(task);
        }
    }
```

#### 分布式锁重试原理图示

![img](https://img-blog.csdnimg.cn/9ee9e998cee84ac0a26b96c0949e7c1b.png)

 Redission分布式锁原理

![img](https://img-blog.csdnimg.cn/33f9d8dd712641c098e54e99661dd623.png)

### redission锁的MutiLock原理（主从一致性）

为了提高redis的可用性，我们会搭建集群或者主从，现在以主从为例

此时我们去写命令，写在主机上， 主机会将数据同步给从机，但是假设在主机还没有来得及把数据写入到从机去的时候，此时主机宕机，哨兵会发现主机宕机，并且选举一个slave变成master，而此时新的master中实际上并没有锁信息，此时锁信息就已经丢掉了。

![img](https://img-blog.csdnimg.cn/25fac4dd5e5242d09bdf0f10c3eb0b8b.png)

 为了解决这个问题，redission提出来了MutiLock锁，使用这把锁咱们就不使用主从了，每个节点的地位都是一样的， **这把锁加锁的逻辑需要写入到每一个主丛节点上，只有所有的服务器都写入成功，此时才是加锁成功**，假设现在某个节点挂了，那么他去获得锁的时候，只要有一个节点拿不到，都不能算是加锁成功，就保证了加锁的可靠性。![img](https://img-blog.csdnimg.cn/b253c158707a4341b5f6ab8ce10717f8.png)

那么MutiLock 加锁原理是什么呢？笔者画了一幅图来说明

当我们去设置了多个锁时，redission会将多个锁添加到一个集合中，然后用while循环去不停去尝试拿锁，但是会有一个总共的加锁时间，这个时间是用需要加锁的个数 * 1500ms ，假设有3个锁，那么时间就是4500ms，假设在这4500ms内，所有的锁都加锁成功， 那么此时才算是加锁成功，如果在4500ms有线程加锁失败，则会再次去进行重试.

![img](https://img-blog.csdnimg.cn/370170c3930f4e59a82d8503388d405a.png)

## 6、秒杀优化

秒杀订单流程回顾：

​	当用户发起请求，此时会请求nginx，nginx会访问到tomcat，而tomcat中的程序，会进行串行操作，分成如下几个步骤

1、查询优惠卷

2、判断秒杀库存是否足够

3、查询订单

4、校验是否是一人一单

5、扣减库存

6、创建订单



**存在问题：**

**在这六步操作中，又有很多操作是要去操作数据库的，而且还是一个线程串行执行， 这样就会导致我们的程序执行的很慢，所以我们需要异步程序执行，那么如何加速呢？**

![](https://img-blog.csdnimg.cn/c0f4c9c83a594332877220edb5c4be66.png)

​	优化方案：我们**将耗时比较短的逻辑判断放入到redis中**，比如是否库存足够，比如是否一人一单，这样的操作，只要这种逻辑可以完成，就意味着我们是一定可以下单完成的，我们只需要进行快速的逻辑判断，根本就不用等下单逻辑走完，我们直接给用户返回成功， 再在后台开一个线程，后台线程慢慢的去执行queue里边的消息，这样程序不就超级快了吗？而且也不用担心线程池消耗殆尽的问题，因为这里我们的程序中并没有手动使用任何线程池，当然这里边有两个难点

第一个难点是我们**怎么在redis中去快速校验一人一单，还有库存判断**

第二个难点是由于我们校验和tomcat下单是两个线程，**那么我们如何知道到底哪个单他最后是否成功，或者是下单完成。**为了完成这件事我们在redis操作完之后，我们会将一些信息返回给前端，同时也会把这些信息丢到异步queue中去，后续操作中，可以通过这个id来查询我们tomcat中的下单逻辑是否完成了。

![img](https://img-blog.csdnimg.cn/18d4a9bfb6124e9ca7ea428cec2381ed.png)

### 异步秒杀思路

现在来看看整体思路：当用户下单之后，判断库存是否充足只需要到redis中去根据key找对应的value是否大于0即可，如果不充足，则直接结束，如果充足，继续在redis中判断用户是否可以下单，如果set集合中没有这条数据，说明他可以下单，如果set集合中没有这条记录，则将userId和优惠卷存入到redis中，并且返回0，整个过程需要保证是原子性的，我们可以使用lua来操作

当以上判断逻辑走完之后，我们可以判断当前redis中返回的结果是否是0 ，如果是0，则表示可以下单，则将之前说的信息存入到到queue中去，然后返回，然后再来个线程异步的下单，前端可以通过返回的订单id来判断是否下单成功。

- **异步消息丢失是消息队列的问题**

![img](https://img-blog.csdnimg.cn/109fcc3661a34d32a8b4d64f5133b79f.png)

### Redis完成秒杀资格判断

**需求：**

- 新增秒杀优惠券的同时，将优惠券信息保存到Redis中`VoucherServiceImpl`

  `VoucherOrderServiceImpl.seckkillVoucher`：

- 基于Lua脚本，判断秒杀库存、一人一单，决定用户是否抢购成功

- 如果抢购成功，将优惠券id和用户id封装后存入阻塞队列

- 开启线程任务，不断从阻塞队列中获取信息，实现异步下单功能



### 阻塞队列实现异步下单

阻塞队列：BlockingQueue  

`VoucherOrderServiceImpl`

修改下单动作，现在我们去下单时，是通过lua表达式去原子执行判断逻辑，如果判断我出来不为0 ，则要么是库存不足，要么是重复下单，返回错误信息，如果是0，则把下单的逻辑保存到队列中去，然后异步执行

```java
@Override
public Result seckkillVoucher(Long voucherId) {
    ....
    // 2.4 创建阻塞队列BlockingQueue,并将订单放入
    ordertasks.add(voucherOrder);

    // 3.获取代理对象,让阻塞队列中能获取
    proxy = (IVoucherOrderService) AopContext.currentProxy();

    // 3.返回订单id
    return Result.ok(orderId);
}

//线程池中的方法,获取阻塞队列中的订单并入库(方法注册在类init后)
	private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                // 1. 获取订单中的订单信息
                try {
                    VoucherOrder voucherOrder = ordertasks.take();
                    handleVoucherOrder(voucherOrder); // 处理订单,订单入库
                } catch (Exception e) {
                    log.error("处理订单异常:" + e);
                    e.printStackTrace();
                }

            }
        }
    }
```







**小总结：**

**秒杀业务的优化思路是什么？**

- 先利用Redis完成库存余量、一人一单判断，完成抢单业务
- 再将下单业务放入阻塞队列，利用独立线程**异步下单**

**基于阻塞队列的异步秒杀存在哪些问题？**

- 内存限制问题
- 数据安全问题

## 7、Redis消息队列

什么是消息队列：字面意思就是存放消息的队列。最简单的消息队列模型包括3个角色：

缺点：

- 消息队列：存储和管理消息，也被称为消息代理（Message Broker）

- 生产者：发送消息到消息队列

- 消费者：从消息队列获取消息并处理消息

  ​

Redis提供了三种不同的方式来实现消息队列：

◆ list结构：基于List结构模拟消息队列

◆ PubSub：基本的点对点消息模型

◆ Stream：比较完善的消息队列模型

![image-20220518205827187](https://img-blog.csdnimg.cn/img_convert/1ac248b01c99feb727824433afa08fd8.png)

### 基于List结构模拟消息队列

- **基于List结构模拟消息队列**

  消息队列（Message Queue），字面意思就是存放消息的队列。而Redis的list数据结构是一个双向链表，很容易模拟出队列效果。

  队列是入口和出口不在一边，因此我们可以利用：LPUSH 结合 RPOP、或者 RPUSH 结合 LPOP来实现。 不过要注意的是，当队列中没有消息时RPOP或LPOP操作会返回null，并不像JVM的阻塞队列那样会阻塞并等待消息。因此**这里应该使用BRPOP或者BLPOP来实现阻塞效果**。![img](https://img-blog.csdnimg.cn/d9f7701b44074ca488da2e870150766d.png)

**基于List的消息队列有哪些优缺点？**

**优点：**

- 利用Redis存储，不受限于JVM内存上限
- 基于Redis的持久化机制，数据安全性有保证
- 可以满足消息有序性

**缺点：**

- 无法避免消息丢失
- 只支持单消费者



### 基于PubSub的消息队列

PubSub（发布订阅）是Redis2.0版本引入的消息传递模型。顾名思义，消费者可以订阅一个或多个channel，生产者 向对应channel发送消息后，所有订阅者都能收到相关消息。

◼ SUBSCRIBE channel [channel] ：订阅一个或多个频道

◼ PUBLISH channel msg ：向一个频道发送消息

◼ PSUBSCRIBE pattern[pattern] ：订阅与pattern格式匹配的所有频道

![image-20220518210545049](https://img-blog.csdnimg.cn/img_convert/075dfd2b3a695d46d9a2c5ca0105167f.png)

基于PubSub的消息队列有哪些优缺点？

优点：

 • 采用发布订阅模型，支持多生产、多消费

缺点：

 • 不支持数据持久化

 • 无法避免消息丢失

 • 消息堆积有上限，超出时数据丢失



### 基于Stream的消息队列

Stream 是 Redis 5.0 引入的一种新数据类型，可以实现一个功能非常完善的消息队列。

#### 基于Stream的消息队列-XREAD

![](https://img-blog.csdnimg.cn/76045e12676849709ed02ad577388c59.png)

---

在业务开发中，我们可以循环的调用XREAD阻塞方式来查询最新消息，从而实现持续监听队列的效果，伪代码如下 ![img](https://img-blog.csdnimg.cn/9dd82ea2ef0a42459545d6632fd38517.png)

注意：当指定起始ID为$时，代表读取最新的消息，如果我们处理一条消息的过程中，又有超过1条以上的消息到达队列，则下次获取时也只能获取到最新的一条，会出现漏读消息的问题



**STREAM类型消息队列的XREAD命令特点：**

- 消息可回溯
- 一个消息可以被多个消费者读取
- 可以阻塞读取
- 有消息漏读的风险

#### 基于Stream的消息队列-消费者组

消费者组（Consumer Group）：将多个消费者划分到一个组中，监听同一个队列。具备下列特点：![img](https://img-blog.csdnimg.cn/fe5b5f4219cb422b86003bd9a0b6b43c.png)

 创建消费者组： ![img](https://img-blog.csdnimg.cn/10925d31a57946dc9dade4967c72ef2c.png)

key：队列名称 

groupName：消费者组名称 

ID：起始ID标示，$代表队列中最后一个消息，0则代表队列中第一个消息 

MKSTREAM：队列不存在时自动创建队列

**删除指定的消费者组**

```
XGROUP DESTORY key groupName
```

**给指定的消费者组添加消费者**

```
XGROUP CREATECONSUMER key groupname consumername
```

**删除消费者组中的指定消费者**

```
XGROUP DELCONSUMER key groupname consumername
```

从消费者组读取消息：

```
XREADGROUP GROUP group consumer [COUNT count] [BLOCK milliseconds] [NOACK] STREAMS key [key ...] ID [ID ...]
```

- group：消费组名称
- consumer：消费者名称，如果消费者不存在，会自动创建一个消费者
- count：本次查询的最大数量
- BLOCK milliseconds：当没有消息时最长等待时间
- NOACK：无需手动ACK，获取到消息后自动确认
- STREAMS key：指定队列名称
- ID：获取消息的起始ID：
- ">"：从下一个未消费的消息开始
- 其它：根据指定id从pending-list中获取已消费但未确认的消息，例如0，是从pending-list中的第一个消息开始

消费者监听消息的基本思路：

<img src="https://img-blog.csdnimg.cn/e36949a4945042d7a9626124c4404e64.png" height = "250px">



 STREAM类消息队列总结

![img](https://img-blog.csdnimg.cn/21629a3946514500b665700b4986eb57.png)

###  实战：基于Redis的Stream结构作为消息队列，实现异步秒杀下单

需求：

- 创建一个Stream类型的消息队列，名为stream.orders
- 修改之前的秒杀下单Lua脚本，在认定有抢购资格后，直接向stream.orders中添加消息，内容包含voucherId、userId、orderId
- 项目启动时，开启一个线程任务，尝试获取stream.orders中的消息，完成下单

**视频P77**

前置操作：

登录redisclient  `auth '123456'`，然后创建消费者组 `XGROUP CREATE stream.orders g1 0 MKSTREAM`

### 总结：秒杀优惠券、一人一单实现思路

在秒杀优惠券功能一开始，使用乐观锁解决超卖问题，

在实现一人一单功能时，先利用redis实现了锁，解决了单机、集群下的线程安全问题，利用redisson分布式锁解决了锁误删问题、原子性操作问题、锁的可重入问题、锁超时释放问题

后面使用进行了秒杀优化，使用基于Redis实现秒杀资格判断、使用阻塞队列实现异步下单、使用Redis消息队列实现异步下单，



## 8、达人探店

业务：

- 发布探店笔记
- 点赞
- 点赞排行榜

发布探店笔记

探店笔记类似点评网站的评价，往往是图文结合。对应的表有两个：

tb_blog：探店笔记表，包含笔记中的标题、文字、图片等

tb_blog_comments：其他用户对探店笔记的评价![img](https://img-blog.csdnimg.cn/cff150038a594d37a9344988715da7d5.png)



### 重复点赞问题-set

初始代码

```java
@GetMapping("/likes/{id}")
public Result queryBlogLikes(@PathVariable("id") Long id) {
    //修改点赞数量
    blogService.update().setSql("liked = liked +1 ").eq("id",id).update();
    return Result.ok();
}
```

问题分析：这种方式会导致一个用户无限点赞，明显是不合理的

造成这个问题的原因是，我们现在的逻辑，发起请求只是给数据库+1，所以才会出现这个问题



完善点赞功能

需求：

- 同一个用户只能点赞一次，再次点击则取消点赞
- 如果当前用户已经点赞，则点赞按钮高亮显示（前端已实现，判断字段Blog类的isLike属性）

实现步骤：

- 给Blog类中添加一个isLike字段，标示是否被当前用户点赞
- 修改点赞功能，利用Redis的set集合判断是否点赞过，未点赞过则点赞数+1，已点赞过则点赞数-1
- 修改根据id查询Blog的业务，判断当前登录用户是否点赞过，赋值给isLike字段
- 修改分页查询Blog业务，判断当前登录用户是否点赞过，赋值给isLike字段



使用数据库来判断的话对数据库压力太大，使用redis来记录

为什么采用set集合：

因为我们的数据是不能重复的，当用户操作过之后，无论他怎么操作，都是

### 点赞排行榜-sortedset

在探店笔记的详情页面，应该把给该笔记点赞的人显示出来，比如最早点赞的TOP5，形成点赞排行榜

![image-20220603071611145](https://img-blog.csdnimg.cn/img_convert/c669ed549a8b65fd692e42343a13bd00.png)

需求：按照点赞时间先后排序，返回Top5的用户

![image-20220603071653652](https://img-blog.csdnimg.cn/img_convert/8eaf74db47a83f7b5b3caa419ae98178.png)

这里我们选择sortedset这个数据结构，score值设置为时间，这样就可以按照点赞时间先后排序了



## 9、好友关注

目录：

- 关注和取关
- 共同关注
- 关注推送



### 关注和取消关注功能![img](https://img-blog.csdnimg.cn/4a78d3b1d1374464a0ad476b0213a4cb.png)

针对用户的操作：可以对用户进行关注和取消关注功能。

实现思路：

需求：基于该表数据结构，实现两个接口：

- 关注和取关接口
- 判断是否关注的接口

关注是User之间的关系，是博主与粉丝的关系，数据库中有一张tb_follow表来标示。

需要把主键修改为自增长，简化开发。



### 共同关注-求交集(待完成)

想要去看共同关注的好友，需要首先进入到这个页面，这个页面会发起两个请求

1、去查询用户的详情

2、去查询用户的笔记

以上两个功能和共同关注没有什么关系，大家可以自行将笔记中的代码拷贝到idea中就可以实现这两个功能了，我们的重点在于共同关注功能。![img](https://img-blog.csdnimg.cn/5b1c870e77164d80be80a41952ee20b8.png)

接下来看看共同关注如何实现：

需求：利用Redis中恰当的数据结构，实现共同关注功能。在博主个人页面展示出当前用户与博主的共同关注呢。

当然是使用我们之前学习过的set集合咯，在set集合中，有交集并集补集的api，我们可以把两人的关注的人分别放入到一个set集合中，然后再通过api去查看这两个set集合中的交集数据。

先来改造当前的关注列表

改造原因是因为我们需要在用户关注了某位用户后，需要将数据放入到set集合中，方便后续进行共同关注，同时当取消关注时，也需要从set集合中进行删除



### 好友关注动态推送-Feed流实现方案(待完成)

当我们关注了用户后，这个用户发了动态，那么我们应该把这些数据推送给用户，这个需求，其实我们又把他叫做Feed流，关注推送也叫做Feed流，直译为投喂。为用户持续的提供“沉浸式”的体验，通过无限下拉刷新获取新的信息。

对于传统的模式的内容解锁：我们是需要用户去通过搜索引擎或者是其他的方式去解锁想要看的内容![img](https://img-blog.csdnimg.cn/e2ba4e231e1c488081c1a482b7c6b0e8.png)

 对于新型的Feed流的的效果：不需要我们用户再去推送信息，而是系统分析用户到底想要什么，然后直接把内容推送给用户，从而使用户能够更加的节约时间，不用主动去寻找。 ![img](https://img-blog.csdnimg.cn/0890104302244d4c92db74a4334aa6e1.png)

Feed流的实现有两种模式：

Feed流产品有两种常见模式：

 Timeline：不做内容筛选，简单的按照内容发布时间排序，常用于好友或关注。例如**朋友圈**

- 优点：信息全面，不会有缺失。并且实现也相对简单
- 缺点：信息噪音较多，用户不一定感兴趣，内容获取效率低

智能排序：利用智能算法屏蔽掉违规的、用户不感兴趣的内容。推送用户感兴趣信息来吸引用户

- 优点：投喂用户感兴趣信息，用户粘度很高，容易沉迷
- 缺点：如果算法不精准，可能起到反作用 本例中的个人页面，是基于关注的好友来做Feed流，因此采用Timeline的模式。该模式的实现方案有三种：



我们本次针对好友的操作，采用的就是Timeline的方式，只需要拿到我们关注用户的信息，然后按照时间排序即可

，因此采用Timeline的模式。该模式的实现方案有三种：

- 拉模式
- 推模式
- 推拉结合

**拉模式**：也叫做读扩散

该模式的核心含义就是：当张三和李四和王五发了消息后，都会保存在自己的邮箱中，假设赵六要读取信息，那么他会从读取他自己的收件箱，此时系统会从他关注的人群中，把他关注人的信息全部都进行拉取，然后在进行排序

优点：比较节约空间，因为赵六在读信息时，并没有重复读取，而且读取完之后可以把他的收件箱进行清楚。

缺点：比较延迟，当用户读取数据时才去关注的人里边去读取数据，假设用户关注了大量的用户，那么此时就会拉取海量的内容，对服务器压力巨大。![img](https://img-blog.csdnimg.cn/2829f80d85b74a3e9fe2e25e0a4da883.png)

**推模式**：也叫做写扩散。

推模式是没有写邮箱的，当张三写了一个内容，此时会主动的把张三写的内容发送到他的粉丝收件箱中去，假设此时李四再来读取，就不用再去临时拉取了

优点：时效快，不用临时拉取

缺点：内存压力大，假设一个大V写信息，很多人关注他， 就会写很多分数据到粉丝那边去![img](https://img-blog.csdnimg.cn/6810ed316ff04f4898a6b86e2f0c38a1.png)

**推拉结合模式**：也叫做读写混合，兼具推和拉两种模式的优点。

推拉模式是一个折中的方案，站在发件人这一段，如果是个普通的人，那么我们采用写扩散的方式，直接把数据写入到他的粉丝中去，因为普通的人他的粉丝关注量比较小，所以这样做没有压力，如果是大V，那么他是直接将数据先写入到一份到发件箱里边去，然后再直接写一份到活跃粉丝收件箱里边去，现在站在收件人这端来看，如果是活跃粉丝，那么大V和普通的人发的都会直接写入到自己收件箱里边来，而如果是普通的粉丝，由于他们上线不是很频繁，所以等他们上线时，再从发件箱里边去拉信息。![img](https://img-blog.csdnimg.cn/e9f4fbd21d5042339bb79450894a06b2.png)

 ![img](https://img-blog.csdnimg.cn/3520c2e77c8c4f509ce5667b3986de03.png)



## 10、附近商户-GEO数据(待完成)



## 11、用户签到-BitMap

### BitMap功能演示与实现

我们针对签到功能完全可以通过mysql来完成，比如说以下这张表![img](https://img-blog.csdnimg.cn/73261a34b446437d97409b001339be02.png)

 

用户一次签到，就是一条记录，假如有1000万用户，平均每人每年签到次数为10次，则这张表一年的数据量为 1亿条

每签到一次需要使用（8 + 8 + 1 + 1 + 3 + 1）共22 字节的内存，一个月则最多需要600多字节

我们如何能够简化一点呢？其实可以考虑小时候一个挺常见的方案，就是小时候，咱们准备一张小小的卡片，你只要签到就打上一个勾，我最后判断你是否签到，其实只需要到小卡片上看一看就知道了

我们可以采用类似这样的方案来实现我们的签到需求。



我们按月来统计用户签到信息，签到记录为1，未签到则记录为0.

**把每一个bit位对应当月的每一天，形成了映射关系。用0和1标示业务状态，这种思路就称为位图（BitMap）。这样我们就用极小的空间，来实现了大量数据的表示**

Redis中是利用string类型数据结构实现BitMap，因此最大上限是512M，转换为bit则是 2^32个bit位。

 ![img](https://img-blog.csdnimg.cn/5c825069bf2f4c0985fad40ca9367c67.png)

BitMap的操作命令有：

- SETBIT：向指定位置（offset）存入一个0或1
- GETBIT ：获取指定位置（offset）的bit值
- BITCOUNT ：统计BitMap中值为1的bit位的数量
- BITFIELD ：操作（查询、修改、自增）BitMap中bit数组中的指定位置（offset）的值
- BITFIELD_RO ：获取BitMap中bit数组，并以十进制形式返回
- BITOP ：将多个BitMap的结果做位运算（与 、或、异或）
- BITPOS ：查找bit数组中指定范围内第一个0或1出现的位置

---

**签到功能实现：**

需求：实现签到接口，将当前用户当天签到信息保存到Redis中

思路：我们可以把年和月作为bitMap的key，然后保存到一个bitMap中，每次签到就到对应的位上把数字从0变成1，只要对应是1，就表明说明这一天已经签到了，反之则没有签到。

我们通过接口文档发现，此接口并没有传递任何的参数，没有参数怎么确实是哪一天签到呢？这个很容易，可以通过后台代码直接获取即可，然后到对应的地址上去修改bitMap。



提示：因为BitMap底层是基于String数据结构，因此其操作也都封装在字符串相关操作中了。

![image-20220603081254916](https://img-blog.csdnimg.cn/img_convert/29490513de0655455a898203be6cc36b.png)

> 用【前缀+本月(yyyyMM))+用户id 】作为键，这样就可以表示每个月的签到情况了

```java
@Override
public Result sign() {
    // 1.获取当前登录用户
    Long userId = UserHolder.getUser().getId();
    // 2.获取日期
    LocalDateTime now = LocalDateTime.now();
    // 3.拼接key
    String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
    String key = USER_SIGN_KEY + userId + keySuffix;
    // 4.获取今天是本月的第几天
    int dayOfMonth = now.getDayOfMonth();
    // 5.写入Redis SETBIT key offset 1
    stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
    return Result.ok();
}
```



### 连续签到天数统计

**问题1：**什么叫做连续签到天数？ 从最后一次签到开始向前统计，直到遇到第一次未签到为止，计算总的签到次数，就是连续签到天数。

![img](https://img-blog.csdnimg.cn/a690c16e9ec44ba48b38617b8358ee80.png)

 

Java逻辑代码：获得当前这个月的最后一次签到数据，定义一个计数器，然后不停的向前统计，直到获得第一个非0的数字即可，每得到一个非0的数字计数器+1，直到遍历完所有的数据，就可以获得当前月的签到总天数了

**问题2：**如何得到本月到今天为止的所有签到数据？

BITFIELD key GET u[dayOfMonth] 0

假设今天是10号，那么我们就可以从当前月的第一天开始，获得到当前这一天的位数，是10号，那么就是10位，去拿这段时间的数据，就能拿到所有的数据了，那么这10天里边签到了多少次呢？统计有多少个1即可。



**问题3：如何从后向前遍历每个bit位？**

注意：bitMap返回的数据是10进制，哪假如说返回一个数字8，那么我哪儿知道到底哪些是0，哪些是1呢？我们只需要让得到的10进制数字和1做与运算就可以了，因为1只有遇见1 才是1，其他数字都是0 ，我们把签到结果和1进行与操作，每与一次，就把签到结果向右移动一位，依次内推，我们就能完成逐个遍历的效果了。

![image-20220603081538391](https://img-blog.csdnimg.cn/img_convert/3987ebdf9c31c9ea89d0237e71faf63d.png)

需求：实现下面接口，统计当前用户截止当前时间在本月的连续签到天数

有用户有时间我们就可以组织出对应的key，此时就能找到这个用户截止这天的所有签到记录，再根据这套算法，就能统计出来他连续签到的次数了

![](https://img-blog.csdnimg.cn/92366124c44c4620ac40506903101376.png)

```java
@Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = 1010L;//UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }
```



## 12、UV统计

### **UV统计-HyperLogLog**

首先搞懂两个概念：

- UV：全称Unique Visitor，也叫独立访客量，是指通过互联网访问、浏览这个网页的自然人。1天内同一个用户多次访问该网站，只记录1次。
- PV：全称Page View，也叫页面访问量或点击量，用户每访问网站的一个页面，记录1次PV，用户多次打开页面，则记录多次PV。往往用来衡量网站的流量。

通常来说UV会比PV大很多，所以衡量同一个网站的访问量，我们需要综合考虑很多因素，所以我们只是单纯的把这两个值作为一个参考值

UV统计在服务端做会比较麻烦，因为要判断该用户是否已经统计过了，需要将统计过的用户信息保存。但是如果每个访问的用户都保存到Redis中，数据量会非常恐怖，那怎么处理呢？

Hyperloglog(HLL)是从Loglog算法派生的概率算法，用于确定非常大的集合的基数，而不需要存储其所有值。相关算法原理大家可以参考：[HyperLogLog 算法的原理讲解以及 Redis 是如何应用它的 - 掘金](https://juejin.cn/post/6844903785744056333#heading-0) Redis中的HLL是基于string结构实现的，单个HLL的内存**永远小于16kb**，**内存占用低**的令人发指！作为代价，其测量结果是概率性的，**有小于0.81％的误差**。不过对于UV统计来说，这完全可以忽略。

![img](https://img-blog.csdnimg.cn/c5aebdb28989481abda62c5bb8a5b819.png)

### UV统计-测试百万数据的统计

测试思路：我们直接利用单元测试，向HyperLogLog中添加100万条数据，看看内存占用和统计效果如何![img](https://img-blog.csdnimg.cn/105765e86ecb4114bdb92f2d2528f235.png)

