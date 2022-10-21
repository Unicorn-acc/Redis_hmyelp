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

- 思路：热点key缓存永不过期，而是设置一个逻辑过期时间，查询到数据时通过对逻辑过期时间判断，来决定是否需要重建缓存（①重建缓存也通过互斥锁保证单线程执行、②重建缓存利用独立线程异步执行、③其他线程无需等待，直接查询到旧数据返回即可）
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

◆ 如果已经被其它线程修改说明发生 了安全问题，此时可以重试或异常。

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

##### 集群环境下（Redis实现分布式锁）

##### 如何快速配置项目集群

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



### 分布式锁

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

#### 基于Redis实现分布式锁

实现基于Redis分布式锁需要实现的两个基本方法

<img src="https://img-blog.csdnimg.cn/22faaeb3f1c34ca68717a35f2dc66384.png" width="450">



对于加锁操作，我们需要满足三个条件

1. 加锁包括了读取锁变量、检查锁变量值和设置锁变量值三个操作，但需要以原子操作的 方式完成，所以，我们使用 SET 命令带上 NX 选项来实现加锁；
2. 锁变量需要设置过期时间，以免客户端拿到锁后发生异常，导致锁一直无法释放，所 以，我们在 SET 命令执行时加上 EX/PX 选项，设置其过期时间；
3. 锁变量的值需要能区分来自不同客户端的加锁操作，以免在释放锁时，出现误释放操 作，所以，我们使用 SET 命令设置锁变量值时，每个客户端设置的值是一个唯一值，用 于标识客户端。

对于释放锁操作，需要注意

释放锁包含了读取锁变量值、判断锁变量值和删除锁变量三个操作，我们无法使用单个命令来实现，所以，我们可以采用 Lua 脚本执行释放锁操作，通过 Redis 原子性地执行 Lua 脚本，来保证释放锁操作的原子性。