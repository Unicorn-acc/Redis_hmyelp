## Redis实战篇笔记

https://blog.csdn.net/weixin_65349299/article/details/124855219

**功能实现包括：**

- 短信登录：Redis的共享Session应用
- 商户查询缓存：企业的缓存技巧、缓存雪崩、穿透等问题解决
- 达人探店（博客）：基于List的点赞列表、基于SortedSet的点赞排行榜
- 优惠券秒杀：Redis的计数器、Lua脚本Redis、分布式锁、Redis的三种消息队列
- 好友关注：基于Set集合的关注、取关、共同关注、消息推送等功能
- 附近的商户：Redis的GeoHash应用
- 用户签到：Redis的BitMap数据统计功能
- UV统计：Redis的HyperLogLog的统计功能



**表结构说明：**

- tb_user：用户表
- tb_user_info：用户详情表
- tb_shop：商户信息表
- tb_shop_type：商户类型表
- tb_blog：用户日记表（达人探店表）
- tb_follow：用户关注表
- tb_voucher：优惠券表
- tb_voucher_order：优惠券的订单表



# 1、短信登录

## 1.1、导入黑马点评项目

### 1.1.1 、导入SQL

![img](https://img-blog.csdnimg.cn/5c5e502afc854c10b1947505af9d89b6.png)

### 1.1.2、有关当前模型

手机或者app端发起请求，请求我们的nginx服务器，nginx基于七层模型走的事HTTP协议，可以实现基于Lua直接绕开tomcat访问redis，也可以作为静态资源服务器，轻松扛下上万并发， 负载均衡到下游tomcat服务器，打散流量，我们都知道一台4核8G的tomcat，在优化和处理简单业务的加持下，大不了就处理1000左右的并发， 经过nginx的负载均衡分流后，利用集群支撑起整个项目，同时nginx在部署了前端项目后，更是可以做到动静分离，进一步降低tomcat服务的压力，这些功能都得靠nginx起作用，所以nginx是整个项目中重要的一环。

在tomcat支撑起并发流量后，我们如果让tomcat直接去访问Mysql，根据经验Mysql企业级服务器只要上点并发，一般是16或32 核心cpu，32 或64G内存，像企业级mysql加上固态硬盘能够支撑的并发，大概就是4000起~7000左右，上万并发， 瞬间就会让Mysql服务器的cpu，硬盘全部打满，容易崩溃，所以我们在高并发场景下，会选择使用mysql集群，同时为了进一步降低Mysql的压力，同时增加访问的性能，我们也会加入Redis，同时使用Redis集群使得Redis对外提供更好的服务。![img](https://img-blog.csdnimg.cn/99d60aa93acf4f829c543c2d7067e314.png)

### 1.1.3、导入后端项目

导入黑马后端代码![img](https://img-blog.csdnimg.cn/87aaff4ba52041c1a1fee4ded4bf3da7.png)

设置yaml配置文件：



### 1.1.4、导入打开前端工程

 ![img](https://img-blog.csdnimg.cn/3e86c117c46a4556b6f88f4b2ba1cca7.png)![img](https://img-blog.csdnimg.cn/4e43d9fccb714ccb9a5ed174fe08800b.png)

## 1.2 、基于Session实现登录流程

**发送验证码：**

用户在提交手机号后，会校验手机号是否合法，如果不合法，则要求用户重新输入手机号

如果手机号合法，后台此时生成对应的验证码，同时将验证码进行保存，然后再通过短信的方式将验证码发送给用户

**短信验证码登录、注册：**

用户将验证码和手机号进行输入，后台从session中拿到当前验证码，然后和用户输入的验证码进行校验，如果不一致，则无法通过校验，如果一致，则后台根据手机号查询用户，如果用户不存在，则为用户创建账号信息，保存到数据库，无论是否存在，都会将用户信息保存到session中，方便后续获得当前登录信息

**校验登录状态:**

用户在请求时候，会从cookie中携带者JsessionId到后台，后台通过JsessionId从session中拿到用户信息，如果没有session信息，则进行拦截，如果有session信息，则将用户信息保存到threadLocal中，并且放行![img](https://img-blog.csdnimg.cn/bf8aab12e31b4107a823e6f2aae5b29d.png)

## 1.3 、实现发送短信验证码功能

**页面流程**![img](https://img-blog.csdnimg.cn/b981222085f142f7b24e6b5119a3c015.png)

 **具体代码如下**

 发送验证码 ：

```
     @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
 
        // 4.保存验证码到 session
        session.setAttribute("code",code);
        // 5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 返回ok
        return Result.ok();
    }
```

 登录：

```
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.校验验证码
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.toString().equals(code)){
             //3.不一致，报错
            return Result.fail("验证码错误");
        }
        //一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
 
        //5.判断用户是否存在
        if(user == null){
            //不存在，则创建
            user =  createUserWithPhone(phone);
        }
        //7.保存用户信息到session中
        session.setAttribute("user",user);
 
        return Result.ok();
    }
```

**tomcat的运行原理**![img](https://img-blog.csdnimg.cn/3c4abf2978e04542b8cac8ba360d3f2b.png)

当用户发起请求时，会访问我们像tomcat注册的端口，任何程序想要运行，都需要有一个线程对当前端口号进行监听，tomcat也不例外，当监听线程知道用户想要和tomcat连接连接时，那会由监听线程创建socket连接，socket都是成对出现的，用户通过socket像互相传递数据，当tomcat端的socket接受到数据后，此时监听线程会从tomcat的线程池中取出一个线程执行用户请求，在我们的服务部署到tomcat后，线程会找到用户想要访问的工程，然后用这个线程转发到工程中的controller，service，dao中，并且访问对应的DB，在用户执行完请求后，再统一返回，再找到tomcat端的socket，再将数据写回到用户端的socket，完成请求和响应

通过以上讲解，我们可以得知 每个用户其实对应都是去找tomcat线程池中的一个线程来完成工作的， 使用完成后再进行回收，既然每个请求都是独立的，所以在每个用户去访问我们的工程时，我们可以使用threadlocal来做到线程隔离，每个线程操作自己的一份数据

## 1.5、隐藏用户敏感信息

我们通过浏览器观察到此时用户的全部信息都在，这样极为不靠谱，所以我们应当在返回用户信息之前，将用户的敏感信息进行隐藏，采用的核心思路就是书写一个UserDto对象，这个UserDto对象就没有敏感信息了，我们在返回前，将有用户敏感信息的User对象转化成没有敏感信息的UserDto对象，那么就能够避免这个尴尬的问题了

**在登录方法处修改**

**在登录方法处修改**

```
//7.保存用户信息到session中
session.setAttribute("user", BeanUtils.copyProperties(user,UserDTO.class));
```

**在拦截器处：** 

```
//5.存在，保存用户信息到Threadlocal
UserHolder.saveUser((UserDTO) user);
```

## 共享session登录问题

### 集群的session共享问题

session共享问题：多台Tomcat并不共享session存储空间，当请求切换到不同tomcat服务时导致数据丢失的问题。

session的替代方案应该满足：

• 数据共享

• [内存](https://so.csdn.net/so/search?q=%E5%86%85%E5%AD%98&spm=1001.2101.3001.7020)存储

• key、value结构

![image-20220516093700643](https://img-blog.csdnimg.cn/img_convert/4b60b66a3b6ed864b250395ff94d3835.png)

### 使用Redis实现共享session登录

- 当我们发送验证码时，以手机号为key，存储验证码（String）
- 登录验证通过后，以随机token为key，存储用户数据（Hash）

![image-20220516093914252](https://img-blog.csdnimg.cn/img_convert/ed58671e9be54703336b3ffbca51c2db.png)

发送验证码

```
public Result sedCode(String phone, HttpSession session) {
        //1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        //3. 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4. 保存验证码到session
//        session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5. 发送验证码
        log.debug("发送短信验证码成功，验证码:{}",code);
        //返回ok
        return Result.ok();
    }
1234567891011121314151617
```

登录验证

```
public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //2. 校验验证码
         String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
//        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)){
            //3. 不一致，报错
            return Result.fail("验证码错误");
        }

        //4.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();

        //5. 判断用户是否存在
        if (user == null){
            //6. 不存在，创建新用户
            user = createUserWithPhone(phone);
        }

        String token  = UUID.randomUUID().toString();
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        String tokenkey=LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenkey,userMap);
        			stringRedisTemplate.expire(tokenkey,LOGIN_USER_TTL,TimeUnit.MINUTES);

        return Result.ok(token);
    }
123456789101112131415161718192021222324252627282930313233343536
```

### 登录拦截器

> 每次发送请求时，需要进行身份校验，并且还需要刷新token有效期，这些步骤适合放在登录拦截器中处理

流程分析：

1. 获取token
2. 查询Redis的用户
   - 不存在，拦截
   - 存在，继续
3. 保存到ThreadLocal
4. 刷新token有效期
5. 放行

![image-20220516094619775](https://img-blog.csdnimg.cn/img_convert/29869d8f99a1a0e4b98f5c41222e6102.png)

具体代码：

```
public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
    // 1.获取请求头中的token
    String token = request.getHeader("authorization");
    if (StrUtil.isBlank(token)) {
        return true;
    }
    // 2.基于TOKEN获取redis中的用户
    String key  = LOGIN_USER_KEY + token;
    Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
    // 3.判断用户是否存在
    if (userMap.isEmpty()) {
        return true;
    }
    // 5.将查询到的hash数据转为UserDTO
    UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
    // 6.存在，保存用户信息到 ThreadLocal
    UserHolder.saveUser(userDTO);

    // 7.刷新token有效期
    stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
    // 8.放行
    return true;
}
1234567891011121314151617181920212223
```

> 注意这里之所以要保存信息到ThreadLocal中，是为了后面在service中方便拿出使用，因为存在线程不安全问题，所以使用ThreadLocal保存

## 2、商户查询缓存

> 店铺类型在很多地方都用到，为了提高查询效率，添加查询缓存，但与此同时，因为更新缓存和更新数据库的操作不是原子性的，可能会导致缓存和数据不一致问题

业务场景：

- 低一致性需求：使用内存淘汰机制。例如店铺类型的查询缓存 （设置过期时间）
- 高一致性需求：主动更新，并以超时剔除作为兜底方案。例如店铺详情查询的缓存（使用更新策略）

### 缓存更新策略

#### 主动更新策略

1. Cache Aside Pattern

   由缓存的调用者，在更新数据库的同时更新缓存

2. Read/Write Through Pattern

   缓存与数据库整合为一个服务，由服务来维护一致性。
   调用者调用该服务，无需关心缓存一致性问题

3. Write Behind Caching Pattern
   调用者只操作缓存，由其它线程异步的将缓存数据持久化到数据库，保证最终一致。

> 综合各种考虑，我们这里的更新策略选择第一种，可以更好的与当前业务结合。

#### 更新顺序不一致导致的问题

根据删除缓存和更新数据库的顺序不同会有不同的问题：

![img](https://img-blog.csdnimg.cn/img_convert/aac217619915dff5e8e7b9cc357f51a4.png)

1. 先删除缓存，后更新数据库：

   会导致缓存中存放的是旧值

![image-20220516100907814](https://img-blog.csdnimg.cn/img_convert/ead8aa6510e3def9be0718ada1c82ed9.png)

1. 先更新数据库，后删除缓存

   更新数据库后，未删除缓存时，存在短暂数据不一致情况

![img](https://img-blog.csdnimg.cn/img_convert/da59b946b7f6dd3f3ba5f70f9c451597.png)

> 先更新数据库值，后删除缓存的方案在等待缓存删除完成期间会有短暂的不一致数据存在。但对于商铺详情信息来说，可以接受。

```
@Transactional
public Result update(Shop shop) {
    Long id = shop.getId();
    if(id == null){
        return Result.fail("店铺id不能为空");
    }
    updateById(shop);
    stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
    return Result.ok();
}
12345678910
```

### 缓存穿透问题

缓存穿透是指客户端请求的数据在缓存中和数据库中都不存在，这样缓存永远不会生效，这些请求都会打到数据库。

常见的解决方案有两种：

1. 缓存空对象

 ◆ 优点：实现简单，维护方便

 ◆ 缺点：

 • 额外的内存消耗

 • 可能造成短期的不一致

1. 布隆过滤

   ◆ 优点：内存占用较少，没有多余key

   ◆ 缺点：

    • 实现复杂

    • 存在误判可能

![image-20220516102010002](https://img-blog.csdnimg.cn/img_convert/bb2aa50c42306f057fb691ba79f79131.png)

> 这里我们选择缓存空对象来解决

#### 缓存空对象

流程分析：

![image-20220516102112424](https://img-blog.csdnimg.cn/img_convert/396298b3b085fcfe4a4cebe05721af3d.png)

代码实现：

```
private Shop queryWithPassThrough(Long id){
    String key = CACHE_SHOP_KEY+id;
    String shopJson = stringRedisTemplate.opsForValue().get(key);

    //不为null和“”
    if(StrUtil.isNotBlank(shopJson)){
        Shop shop = JSONUtil.toBean(shopJson, Shop.class);
        return  shop;
    }

    if (shopJson!=null && shopJson.length()==0){
        //命中的是空值“”
        return null;
    }

    Shop shop = getById(id);
    if(shop==null){
        stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
        return  null;
    }

    stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,TimeUnit.MINUTES);
    return shop;
}
123456789101112131415161718192021222324
```

### 缓存雪崩

缓存雪崩是指在同一时段大量的缓存key同时失效或者Redis服务宕机，导致大量请求到达数据库，带来巨大压力。

解决方案：

◆ 给不同的Key的TTL添加随机值

◆ 利用Redis集群提高服务的可用性

◆ 给缓存业务添加降级限流策略

◆ 给业务添加多级缓存

![image-20220516102731884](https://img-blog.csdnimg.cn/img_convert/d4314b00cbf871c33f0d0124d52d0b6c.png)

### 缓存击穿

#### 概念

缓存击穿问题也叫热点Key问题，就是一个被高并发访问并且缓存重建业务较复杂的key突然失效了，无数的请求访问 会在瞬间给数据库带来巨大的冲击。

常见的解决方案有两种：

- 互斥锁
- 逻辑过期

![image-20220516102901205](https://img-blog.csdnimg.cn/img_convert/540e3a08267bfde37a94934b4c77a6a7.png)

#### 互斥锁

如果热点key失效，只运行一个线程去更新缓存，其他线程等更新好后再来获取

![image-20220516102952256](https://img-blog.csdnimg.cn/img_convert/6f1194fedcac27ee6eb505661cb39587.png)

流程分析：

![image-20220516103138696](https://img-blog.csdnimg.cn/img_convert/0d8450aab0b215bdfb60a5f3b91ffe85.png)

代码实现：

```
// 获取锁
private boolean tryLock(String key) {
    Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
    return BooleanUtil.isTrue(flag);
}

private void unlock(String key) {
    stringRedisTemplate.delete(key);
}


public <R, ID> R queryWithMutex(
    String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
    String key = keyPrefix + id;
    // 1.从redis查询商铺缓存
    String shopJson = stringRedisTemplate.opsForValue().get(key);
    // 2.判断是否存在
    if (StrUtil.isNotBlank(shopJson)) {
        // 3.存在，直接返回
        return JSONUtil.toBean(shopJson, type);
    }
    // 判断命中的是否是空值
    if (shopJson != null) {
        // 返回一个错误信息
        return null;
    }

    // 4.实现缓存重建
    // 4.1.获取互斥锁
    String lockKey = LOCK_SHOP_KEY + id;
    R r = null;
    try {
        boolean isLock = tryLock(lockKey);
        // 4.2.判断是否获取成功
        if (!isLock) {
            // 4.3.获取锁失败，休眠并重试
            Thread.sleep(50);
            return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
        }
        // 4.4.获取锁成功，根据id查询数据库
        r = dbFallback.apply(id);
        // 5.不存在，返回错误
        if (r == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 6.存在，写入redis
        this.set(key, r, time, unit);
    } catch (InterruptedException e) {
        throw new RuntimeException(e);
    }finally {
        // 7.释放锁
        unlock(lockKey);
    }
    // 8.返回
    return r;
}

123456789101112131415161718192021222324252627282930313233343536373839404142434445464748495051525354555657585960
```

#### 逻辑锁过期

没有设置过期时间，但会为每个key设置一个逻辑过期时间，当发现超过逻辑过期时间后，会使用单独线程去构建缓存。

![image-20220516104717854](https://img-blog.csdnimg.cn/img_convert/0583550b769794ce94ccc7749af8172d.png)

流程分析：

![image-20220516104745962](https://img-blog.csdnimg.cn/img_convert/d53fd2d9a9635389eb3ec2e4fc74b662.png)

代码实现：

```
 public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
     	
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
          //热点key会进行预热，即会提前传入缓存中，
       	 // 如果缓存中都没有，则表明这个数据也不在数据库，没有必要继续查询数据库了
         // 3.不存在，直接返回
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return r;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock){
            // 6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R newR = dbFallback.apply(id);
                    // 重建缓存
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4.返回过期的商铺信息
        return r;
    }
1234567891011121314151617181920212223242526272829303132333435363738394041424344454647
```

#### 总结

![image-20220516104936500](https://img-blog.csdnimg.cn/img_convert/7627fa67a0e2243d957c8513522c5893.png)

## 3、优惠劵秒杀

### 全局唯一ID

当用户抢购时，就会生成订单并保存到tb_voucher_order这张表中，而订单表如果使用数据库自增ID就存在一些问题 ：

- id的规律性太明显
- 受单表数据量的限制，分布式场景下无法使用

> 我们可以借助redis的incr指令来做自增

全局ID生成器

为了增加ID的安全性，我们可以不直接使用Redis自增的数值，而是拼接一些其它信息：

![image-20220516105549647](https://img-blog.csdnimg.cn/img_convert/6ed82d3db87a0330e6751d9b06ca0224.png)

ID的组成部分：

- 符号位：1bit，永远为0
- 时间戳：31bit，以秒为单位，可以使用69年
- 序列号：32bit，秒内的计数器，支持每秒产生2^32个不同ID（使用redis实现）

```
@Component
public class RedisIdWorker {
    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
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
}
123456789101112131415161718192021222324252627282930313233
```

> 这里的redis的key是`"icr:" + keyPrefix + ":" + date`，这样就可以每天一个key，方便统计订单量

### 实现优惠劵秒杀下单

下单时需要判断两点：

• 秒杀是否开始或结束，如果尚未开始或已经结束则无法下单

• 库存是否充足，不足则无法下单

![image-20220516125521092](https://img-blog.csdnimg.cn/img_convert/b4a7d503c34c6a6472778a70be3d8a5d.png)

```
public Result seckillVoucher(Long voucherId) {
    // 1.查询优惠券
    SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
    // 2.判断秒杀是否开始
    if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
        // 尚未开始
        return Result.fail("秒杀尚未开始！");
    }
    // 3.判断秒杀是否已经结束
    if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
        // 尚未开始
        return Result.fail("秒杀已经结束！");
    }
    // 4.判断库存是否充足
    if (voucher.getStock() < 1) {
        // 库存不足
        return Result.fail("库存不足！");
    }
	//创建订单
    return createVoucherOrder(voucherId);
}
123456789101112131415161718192021
```

### 超卖问题

超卖问题是典型的多线程安全问题，针对这一问题的常见解决方案就是加锁

#### 悲观锁

认为线程安全问题一定会发生，因此 在操作数据之前先获取锁，确保线程 串行执行。例如synchronized、Lock都属于悲观锁。

> 优点：简单粗暴
>
> 缺点：性能一般

#### 乐观锁

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

#### mysql的排他锁

只需要在更新时判断此时库存是否大于0

```
update goods set stock = stock - 1 WHERE id = 1001 and stock > 0
1
```

使用update时会加排他锁，这一行不能被任何其他线程修改和读写

排他锁又称为写锁，简称X锁，顾名思义，排他锁就是不能与其他所并存，如一个事务获取了一个数据行的排他锁，其他事务就不能再获取该行的其他锁，包括共享锁和排他锁，但是获取排他锁的事务是可以对数据就行读取和修改。

### 一人一单

#### 描述

需求：修改秒杀业务，要求同一个优惠券，一个用户只能下一单

流程分析：

![image-20220516211344556](https://img-blog.csdnimg.cn/img_convert/8345ff5d3eecd9e746e67356b6697a63.png)

存在的并发问题：

![image-20220516211421177](https://img-blog.csdnimg.cn/img_convert/2c270bd64c3a8c7236c46fd8ac73546a.png)

> 并发情况下，他们同时查询到订单中没有他们的，于是他们都认为自己是第一次购买，导致一人多单情况

#### 单机情况下

可以使用锁来保证并发安全

![image-20220516211642662](https://img-blog.csdnimg.cn/img_convert/4ab78b3fc9736d1dd2aa175721c7b75a.png)

具体代码实现：

```
@Transactional
public Result createVoucherOrder(Long voucherId) {
    // 5.一人一单
    Long userId = UserHolder.getUser().getId();

    synchronized (userId.toString().intern()) {
        // 5.1.查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过一次！");
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            return Result.fail("库存不足！");
        }

        // 7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2.用户id
        voucherOrder.setUserId(userId);
        // 7.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 7.返回订单id
        return Result.ok(orderId);
    }
}
123456789101112131415161718192021222324252627282930313233343536373839
```

> 这里锁住的是`userId.toString().intern()`，通过intern()可以将字符串放入常量池中，对于一个JVM实例中，常量池中的常量唯一，这样多个线程就可以基于这个唯一常量加锁。

#### 分布式下

![image-20220516212333213](https://img-blog.csdnimg.cn/img_convert/780c9750d45a74ebae358abf42481937.png)

> 分布式锁是由共享存储系统维护的变量，多个客户端可以向共享存储系统发送命令进行加 锁或释放锁操作。Redis 作为一个共享存储系统，可以用来实现分布式锁。

##### 基于Redis实现分布式锁

对于加锁操作，我们需要满足三个条件

1. 加锁包括了读取锁变量、检查锁变量值和设置锁变量值三个操作，但需要以原子操作的 方式完成，所以，我们使用 SET 命令带上 NX 选项来实现加锁；
2. 锁变量需要设置过期时间，以免客户端拿到锁后发生异常，导致锁一直无法释放，所 以，我们在 SET 命令执行时加上 EX/PX 选项，设置其过期时间；
3. 锁变量的值需要能区分来自不同客户端的加锁操作，以免在释放锁时，出现误释放操 作，所以，我们使用 SET 命令设置锁变量值时，每个客户端设置的值是一个唯一值，用 于标识客户端。

对于释放锁操作，需要注意

释放锁包含了读取锁变量值、判断锁变量值和删除锁变量三个操作，我们无法使用单个命令来实现，所以，我们可以采用 Lua 脚本执行释放锁操作，通过 Redis 原子性地执行 Lua 脚本，来保证释放锁操作的原子性。

![image-20220516213013356](https://img-blog.csdnimg.cn/img_convert/4d4f8c1bef72e87b566dded0a55b5aca.png)

加锁操作，需要加入线程标识

```
public boolean tryLock(long timeoutSec) {
    // 获取线程标示
    String threadId = ID_PREFIX + Thread.currentThread().getId();
    // 获取锁
    Boolean success = stringRedisTemplate.opsForValue()
        .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
    return Boolean.TRUE.equals(success);
}

123456789
```

释放锁，调用lua脚本

```
-- 比较线程标示与锁中的标示是否一致
if(redis.call('get', KEYS[1]) ==  ARGV[1]) then
    -- 释放锁 del key
    return redis.call('del', KEYS[1])
end
return 0
123456
```

### 5、分布式锁-Redission

基于setnx实现的分布式锁存在下面的问题

1. 不可重入 : 同一个线程无法多次获取同一把
2. 不可重试 : 获取锁只尝试一次就返回 false，没有重试机制
3. 超时释放 : 锁超时释放虽然可以避免 死锁，但如果是业务执行 耗时较长，也会导致锁释 放，存在安全隐患
4. 主从一致性 : 如果Redis提供了主从集群， 主从同步存在延迟，当主 宕机时，如果从并同步主 中的锁数据，则会出现锁 实现

Redisson是一个在Redis的基础上实现的Java驻内存数据网格（In-Memory Data Grid）。它不仅提供了一系列的分布 式的Java常用对象，还提供了许多分布式服务，其中就包含了各种分布式锁的实现。使用Redission可以解决上面提到的4个问题。

![image-20220516213851729](https://img-blog.csdnimg.cn/img_convert/1ed71674a48578d0ea501dc508dd5898.png)

### 6、Redis优化秒杀

![image-20220518202549208](https://img-blog.csdnimg.cn/img_convert/b10907670e749676ac6aa88029cbec90.png)

我们之前的操作都是基于数据库的，但是操作数据库的性能是比较慢的，我们可以将判断秒杀库存跟校验一人一单的操作放到Redis缓存中，然后再开启另外一个线程去处理数据库相关的步骤。

优化思路：

① 先利用Redis完成库存余量、一人一单判断，完成抢单业务

② 再将下单业务放入阻塞队列，利用独立线程异步下单

![image-20220518202840873](https://img-blog.csdnimg.cn/img_convert/c35a51a4c38d2404eb05ca8242bb4389.png)

步骤分析：

① 新增秒杀优惠券的同时，将优惠券信息保存到Redis中

② 基于Lua脚本，判断秒杀库存、一人一单，决定用户是否抢购成功

③ 如果抢购成功，将优惠券id和用户id封装后存入阻塞队列

④ 开启线程任务，不断从阻塞队列中获取信息，实现异步下单功能

代码实现：

```
-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]

-- 2.数据key
-- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
-- 3.1.判断库存是否充足 get stockKey
if(tonumber(redis.call('get', stockKey)) <= 0) then
    -- 3.2.库存不足，返回1
    return 1
end
-- 3.2.判断用户是否下单 SISMEMBER orderKey userId
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 3.3.存在，说明是重复下单，返回2
    return 2
end
-- 3.4.扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.5.下单（保存用户）sadd orderKey userId
redis.call('sadd', orderKey, userId)
return 0
12345678910111213141516171819202122232425262728
```

```
public Result seckillVoucher(Long voucherId) {
    Long userId = UserHolder.getUser().getId();
    // 1.执行lua脚本
    Long result = stringRedisTemplate.execute(
        SECKILL_SCRIPT,
        Collections.emptyList(),
        voucherId.toString(), userId.toString()
    );
    int r = result.intValue();
    // 2.判断结果是否为0
    if (r != 0) {
        // 2.1.不为0 ，代表没有购买资格
        return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
    }
    // 2.2.为0 ，有购买资格，把下单信息保存到阻塞队列
    VoucherOrder voucherOrder = new VoucherOrder();
    // 2.3.订单id
    long orderId = redisIdWorker.nextId("order");
    voucherOrder.setId(orderId);
    // 2.4.用户id
    voucherOrder.setUserId(userId);
    // 2.5.代金券id
    voucherOrder.setVoucherId(voucherId);
    // 2.6.放入阻塞队列
    orderTasks.add(voucherOrder);

    // 3.返回订单id
    return Result.ok(orderId);
}

//处理阻塞线程中的订单，保存到数据库中
private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    createVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }
123456789101112131415161718192021222324252627282930313233343536373839404142434445464748
```

这种基于java的阻塞队列的异步秒杀存在哪些问题？

- 内存限制问题
- 数据安全问题

### 7、Redis消息队列实现异步秒杀

消息队列（Message Queue），字面意思就是存放消息的队列。最简单的消息队列模型包括3个角色：

- 消息队列：存储和管理消息，也被称为消息代理（Message Broker）
- 生产者：发送消息到消息队列
- 消费者：从消息队列获取消息并处理消息

![image-20220518205827187](https://img-blog.csdnimg.cn/img_convert/1ac248b01c99feb727824433afa08fd8.png)

Redis提供了三种不同的方式来实现消息队列：

◆ list结构：基于List结构模拟消息队列

◆ PubSub：基本的点对点消息模型

◆ Stream：比较完善的消息队列模型

#### 基于List结构模拟消息队列

消息队列（Message Queue），字面意思就是存放消息的队列。而Redis的list数据结构是一个双向链表，很容易模拟 出队列效果。

队列是入口和出口不在一边，因此我们可以利用：LPUSH 结合 RPOP、或者 RPUSH 结合 LPOP来实现。

不过要注意的是，当队列中没有消息时RPOP或LPOP操作会返回null，并不像JVM的阻塞队列那样会阻塞并等待消息 。

因此这里应该使用BRPOP或者BLPOP来实现阻塞效果。

![image-20220518210225552](https://img-blog.csdnimg.cn/img_convert/3382ad703d18013540b2e526f38ecca1.png)

基于List的消息队列有哪些优缺点？

优点：

```
• 利用Redis存储，不受限于JVM内存上限 
1
```

 • 基于Redis的持久化机制，数据安全性有保证

 • 可以满足消息有序性

缺点：

 • 无法避免消息丢失

 • 只支持单消费者

#### 基于PubSub的消息队列

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

#### 基于Stream的消息队列

Stream 是 Redis 5.0 引入的一种新数据类型，可以实现一个功能非常完善的消息队列。 发送消息的命令

![image-20220518210800377](https://img-blog.csdnimg.cn/img_convert/d9ea4855f5b71ac4865a5f21e1ee423c.png)

例如：

![image-20220518210814446](https://img-blog.csdnimg.cn/img_convert/1c19dbf38e3247980245afb81c67ebe3.png)

##### 1. 基于Stream的消息队列-XREAD

 读取消息的方式之一：XREAD

![image-20220518210845461](https://img-blog.csdnimg.cn/img_convert/c9d39e9c20efaa5eebd5994252ca752a.png)

 例如，使用XREAD读取第一个消息：

![image-20220518210920772](https://img-blog.csdnimg.cn/img_convert/f704adcb7a3bc132e1131bf4a66e121c.png)

XREAD阻塞方式，读取最新的消息：

![image-20220518210936922](https://img-blog.csdnimg.cn/img_convert/06811451c64016b20c2e83243c49b890.png)

在业务开发中，我们可以循环的调用XREAD阻塞方式来查询最新消息，从而实现持续监听队列的效果，伪代码如下：

[外链图片转存失败,源站可能有防盗链机制,建议将图片保存下来直接上传(img-trIdyL8V-1654216424225)(C:/Users/wcl/AppData/Roaming/Typora/typora-user-images/image-20220518210952049.png)]

> 当我们指定起始ID为$时，代表读取最新的消息，如果我们处 理一条消息的过程中，又有超过1条以上的消息到达队列，则 下次获取时也只能获取到最新的一条，会出现漏读消息的问题

STREAM类型消息队列的XREAD命令特点：

- 消息可回溯
- 一个消息可以被多个消费者读取
- 可以阻塞读取
- 有消息漏读的风险

##### 2. 基于Stream的消息队列-消费者组

消费者组（Consumer Group）：将多个消费者划分到一个组中，监听同一个队列。具备下列特点：

（1）消息分流

队列中的消息会分流给组内的不同消费者，而不是重复消费，从而加快消息处理的速度

（2）消息标示

消费者组会维护一个标示， 记录最后一个被处理的消息， 哪怕消费者宕机重启，还会从标示之后读取消息。确保每一个消息都会被消费。

（3）消息确认

消费者获取消息后，消息处于 pending状态，并存入一个 pending-list。当处理完成后 需要通过XACK来确认消息，标记 消息为已处理，才会从pendinglist移除

**具体命令**

创建消费者组：

```
XGROUP CREATE key groupName ID [MKSTREAM]
1
```

- key：队列名称
- groupName：消费者组名称
- ID：起始ID标示，$代表队列中最后一个消息，0则代表队列中第一个消息
- MKSTREAM：队列不存在时自动创建队列

其它常见命令：

```
# 删除指定的消费者组
XGROUP DESTORY key groupName
# 给指定的消费者组添加消费者
XGROUP CREATECONSUMER key groupname consumername
# 删除消费者组中的指定消费者
XGROUP DELCONSUMER key groupname consumername
123456
```

从消费者组读取消息：

```
XREADGROUP GROUP group consumer [COUNT count] [BLOCK milliseconds] [NOACK] STREAMS 
key [key ...] ID [ID ...
12
```

- group：消费组名称
- consumer：消费者名称，如果消费者不存在，会自动创建一个消费者
- count：本次查询的最大数量
- BLOCK milliseconds：当没有消息时最长等待时间
- NOACK：无需手动ACK，获取到消息后自动确认
- STREAMS key：指定队列名称
- ID：获取消息的起始ID：
  - “>”：从下一个未消费的消息开始
  - 其它：根据指定id从pending-list中获取已消费但未确认的消息，例如0，是从pending-list中的第一 个消息开始

**消费者监听消息的基本思路**

![image-20220518211817883](https://img-blog.csdnimg.cn/img_convert/078984aae8789a9a1b7079a0f4966695.png)

STREAM类型消息队列的XREADGROUP命令特点：

- 消息可回溯
- 可以多消费者争抢消息，加快消费速度
- 可以阻塞读取
- 没有消息漏读的风险
- 有消息确认机制，保证消息至少被消费一次

#### 总结

![image-20220518211934855](https://img-blog.csdnimg.cn/img_convert/0dff4a14aa61069ad841b0d51d456e43.png)

因此我们这里选择基于Redis的Stream机构作为消息队列，实现异步秒杀下单

思路分析：

① 创建一个Stream类型的消息队列，名为stream.orders

② 修改之前的秒杀下单Lua脚本，在认定有抢购资格后，直接向stream.orders中添加消息，内容包 含voucherId、userId、orderId

③ 项目启动时，开启一个线程任务，尝试获取stream.orders中的消息，完成下单

具体实现：

1. 创建一个Stream类型的消息队列

![image-20220518212512553](https://img-blog.csdnimg.cn/img_convert/9c954ddef30448f765dcd244657b0a5e.png)

1. 修改lua脚本

   ```
   -- 1.参数列表
   -- 1.1.优惠券id
   local voucherId = ARGV[1]
   -- 1.2.用户id
   local userId = ARGV[2]
   -- 1.3.订单id
   local orderId = ARGV[3]

   -- 2.数据key
   -- 2.1.库存key
   local stockKey = 'seckill:stock:' .. voucherId
   -- 2.2.订单key
   local orderKey = 'seckill:order:' .. voucherId

   -- 3.脚本业务
   -- 3.1.判断库存是否充足 get stockKey
   if(tonumber(redis.call('get', stockKey)) <= 0) then
       -- 3.2.库存不足，返回1
       return 1
   end
   -- 3.2.判断用户是否下单 SISMEMBER orderKey userId
   if(redis.call('sismember', orderKey, userId) == 1) then
       -- 3.3.存在，说明是重复下单，返回2
       return 2
   end
   -- 3.4.扣库存 incrby stockKey -1
   redis.call('incrby', stockKey, -1)
   -- 3.5.下单（保存用户）sadd orderKey userId
   redis.call('sadd', orderKey, userId)
   -- 3.6.发送消息到队列中， XADD stream.orders * k1 v1 k2 v2 ...
   redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
   return 0
   1234567891011121314151617181920212223242526272829303132
   ```

2. 执行lua脚本

```
public Result seckillVoucher(Long voucherId) {
    Long userId = UserHolder.getUser().getId();
    long orderId = redisIdWorker.nextId("order");
    // 1.执行lua脚本
    Long result = stringRedisTemplate.execute(
            SECKILL_SCRIPT,
            Collections.emptyList(),
            voucherId.toString(), userId.toString(), String.valueOf(orderId)
    );
    int r = result.intValue();
    // 2.判断结果是否为0
    if (r != 0) {
        // 2.1.不为0 ，代表没有购买资格
        return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
    }
    // 3.返回订单id
    return Result.ok(orderId);
}
123456789101112131415161718
```

1. 从redis的消息队列取出订单，同步到数据库中

```
private class VoucherOrderHandler implements Runnable {

    @Override
    public void run() {
        while (true) {
            try {
                // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                );
                // 2.判断订单信息是否为空
                if (list == null || list.isEmpty()) {
                    // 如果为null，说明没有消息，继续下一次循环
                    continue;
                }
                // 解析数据
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                // 3.创建订单
                createVoucherOrder(voucherOrder);
                // 4.确认消息 XACK
                stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
            } catch (Exception e) {
                log.error("处理订单异常", e);
                handlePendingList();
            }
        }
    }

    private void handlePendingList() {
        while (true) {
            try {
                // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create("stream.orders", ReadOffset.from("0"))
                );
                // 2.判断订单信息是否为空
                if (list == null || list.isEmpty()) {
                    // 如果为null，说明没有异常消息，结束循环
                    break;
                }
                // 解析数据
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                // 3.创建订单
                createVoucherOrder(voucherOrder);
                // 4.确认消息 XACK
                stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
            } catch (Exception e) {
                log.error("处理订单异常", e);
            }
        }
    }
}
123456789101112131415161718192021222324252627282930313233343536373839404142434445464748495051525354555657585960
```

## 8、达人探店

### 点赞

#### 需求

- 同一个用户只能点赞一次，再次点击则取消点赞
- 如果当前用户已经点赞，则点赞按钮高亮显示（前端已实现，判断字段Blog类的isLike属性）

> 因为这里每个用户只能点赞一次，具有唯一性，所以我们可以选择redis中的set集合

#### 实现步骤：

① 给Blog类中添加一个isLike字段，标示是否被当前用户点赞

② 修改点赞功能，利用Redis的set集合判断是否点赞过，未点赞过则点赞数+1，已点赞过则点赞数 -1

③ 修改根据id查询Blog的业务，判断当前登录用户是否点赞过，赋值给isLike字段

④ 修改分页查询Blog业务，判断当前登录用户是否点赞过，赋值给isLike字段

#### 具体代码：

未点赞过则点赞数+1，已点赞过则点赞数 -1

```
@Override
public Result likeBlog(Long id) {
    // 1.获取登录用户
    Long userId = UserHolder.getUser().getId();
    // 2.判断当前登录用户是否已经点赞
    String key = BLOG_LIKED_KEY + id;
    Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
    if (score == null) {
        // 3.如果未点赞，可以点赞
        // 3.1.数据库点赞数 + 1
        boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
        // 3.2.保存用户到Redis的set集合  zadd key value score
        if (isSuccess) {
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        }
    } else {
        // 4.如果已点赞，取消点赞
        // 4.1.数据库点赞数 -1
        boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
        // 4.2.把用户从Redis的set集合移除
        if (isSuccess) {
            stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        }
    }
    return Result.ok();
}
1234567891011121314151617181920212223242526
```

判断当前登录用户是否点赞过，赋值给isLike字段

```
@Override
public Result queryHotBlog(Integer current) {
    // 根据用户查询
    Page<Blog> page = query()
        .orderByDesc("liked")
        .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
    // 获取当前页数据
    List<Blog> records = page.getRecords();
    // 查询用户
    records.forEach(blog -> {
        this.queryBlogUser(blog);
        this.isBlogLiked(blog);
    });
    return Result.ok(records);
}
@Override
public Result queryBlogById(Long id) {
    // 1.查询blog
    Blog blog = getById(id);
    if (blog == null) {
        return Result.fail("笔记不存在！");
    }
    // 2.查询blog有关的用户
    queryBlogUser(blog);
    // 3.查询blog是否被点赞
    isBlogLiked(blog);
    return Result.ok(blog);
}

private void isBlogLiked(Blog blog) {
    // 1.获取登录用户
    UserDTO user = UserHolder.getUser();
    if (user == null) {
        // 用户未登录，无需查询是否点赞
        return;
    }
    Long userId = user.getId();
    // 2.判断当前登录用户是否已经点赞
    String key = "blog:liked:" + blog.getId();
    Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
    blog.setIsLike(score != null);
}
123456789101112131415161718192021222324252627282930313233343536373839404142
```

### 点赞排行榜

在探店笔记的详情页面，应该把给该笔记点赞的人显示出来，比如最早点赞的TOP5，形成点赞排行榜

![image-20220603071611145](https://img-blog.csdnimg.cn/img_convert/c669ed549a8b65fd692e42343a13bd00.png)

需求：按照点赞时间先后排序，返回Top5的用户

![image-20220603071653652](https://img-blog.csdnimg.cn/img_convert/8eaf74db47a83f7b5b3caa419ae98178.png)

这里我们选择sortedset这个数据结构，score值设置为时间，这样就可以按照点赞时间先后排序了

#### 具体代码

```
@Override
public Result queryBlogLikes(Long id) {
    String key = BLOG_LIKED_KEY + id;
    // 1.查询top5的点赞用户 zrange key 0 4
    Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
    if (top5 == null || top5.isEmpty()) {
        return Result.ok(Collections.emptyList());
    }
    // 2.解析出其中的用户id
    List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
    String idStr = StrUtil.join(",", ids);
    // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
    List<UserDTO> userDTOS = userService.query()
        .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
        .stream()
        .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
        .collect(Collectors.toList());
    // 4.返回
    return Result.ok(userDTOS);
}
1234567891011121314151617181920
```

> 一定要注意这里的第三步，拿到排序好的id后，再去数据库查询对应的其他信息时。如果使用in的话，后面返回的数据顺序并不是我们一开始的id的顺序。所以这里我们查询数据库时，使用order by field按照我们指定的顺序进行排序

## 9、好友关注

在探店图文的详情页面中，可以关注发布笔记的作者

![image-20220603073002205](https://img-blog.csdnimg.cn/img_convert/e1a99aa9a5d062a9c745c0508c4c27fa.png)

### 关注和取关

> 因为关注用户的id是唯一的，我们可以将已关注的用户放入set集合中，键为 `"follows:" + userId`

```
@Override
public Result follow(Long followUserId, Boolean isFollow) {
    // 1.获取登录用户
    Long userId = UserHolder.getUser().getId();
    String key = "follows:" + userId;
    // 1.判断到底是关注还是取关
    if (isFollow) {
        // 2.关注，新增数据
        Follow follow = new Follow();
        follow.setUserId(userId);
        follow.setFollowUserId(followUserId);
        boolean isSuccess = save(follow);
        if (isSuccess) {
            // 把关注用户的id，放入redis的set集合 sadd userId followerUserId
            stringRedisTemplate.opsForSet().add(key, followUserId.toString());
        }
    } else {
        // 3.取关，删除 delete from tb_follow where user_id = ? and follow_user_id = ?
        boolean isSuccess = remove(new QueryWrapper<Follow>()
                                   .eq("user_id", userId).eq("follow_user_id", followUserId));
        if (isSuccess) {
            // 把关注用户的id从Redis集合中移除
            stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
        }
    }
    return Result.ok();
}
123456789101112131415161718192021222324252627
```

### 共同关注

点击博主头像，可以进入博主首页后，可以查看当前用户与该博主共同关注的博主

![image-20220603073509285](https://img-blog.csdnimg.cn/img_convert/04b69d96e693c3c220dcb3931981ee37.png)

![image-20220603073529165](https://img-blog.csdnimg.cn/img_convert/74bba8cec12d3e711e155a7d53567665.png)

> 我们可以使用redis的set集合，把他们各自关注的set集合用户取交集，就可以获得他们共同关注的用户了

```
@Override
public Result followCommons(Long id) {
    // 1.获取当前用户
    Long userId = UserHolder.getUser().getId();
    String key = "follows:" + userId;
    // 2.求交集
    String key2 = "follows:" + id;
    Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
    if (intersect == null || intersect.isEmpty()) {
        // 无交集
        return Result.ok(Collections.emptyList());
    }
    // 3.解析id集合
    List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
    // 4.查询用户
    List<UserDTO> users = userService.listByIds(ids)
        .stream()
        .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
        .collect(Collectors.toList());
    return Result.ok(users);
}
123456789101112131415161718192021
```

### 关注推送

关注推送也叫做Feed流，直译为投喂。为用户持续的提供“沉浸式”的体验，通过无限下拉刷新获取新的信息。

![image-20220603074117139](https://img-blog.csdnimg.cn/img_convert/ed86399e57f2abc924a76c5291ab5dbe.png)

#### Feed流的模式

Feed流产品有两种常见模式：

- Timeline：不做内容筛选，简单的按照内容发布时间排序，常用于好友或关注。例如朋友圈

 ➢ 优点：信息全面，不会有缺失。并且实现也相对简单

 ➢ 缺点：信息噪音较多，用户不一定感兴趣，内容获取效率低

- 智能排序：利用智能算法屏蔽掉违规的、用户不感兴趣的内容。推送用户感兴趣信息来吸引用户

   ➢ 优点：投喂用户感兴趣信息，用户粘度很高，容易沉迷

   ➢ 缺点：如果算法不精准，可能起到反作用

本例中的个人页面，是基于关注的好友来做Feed流，因此采用Timeline的模式。该模式的实现方案有三种：

① 拉模式 ② 推模式 ③ 推拉结合

（1）拉模式：也叫做读扩散。

![image-20220603074322205](https://img-blog.csdnimg.cn/img_convert/ee7442d0b6e1be9826e8aaaf4de481fe.png)

- 用户发表的内容只插入到自己的队列里去，粉丝拉取关注流里需要从每个关注人的队列里各取几个，然后进行rank。
- 新粉丝新关注了此用户，因为是拉模式，不需要额外操作，刷新关注流的时候就会拉取。

优点：节省内存空间，收件箱读完以后，就不用了可以清理，所以消息只保存一份在发件人的发件箱中

缺点：每次去读消息时，都要去拉取再做排序，耗时时间长，读取时间较

（2）推模式：也叫做写扩散。

![image-20220603074520755](https://img-blog.csdnimg.cn/img_convert/dd83074b038b8ac8d71146abc4cb0575.png)

- 用户发表的内容插入到所有粉丝的接收队列里，粉丝拉取关注流从自己的接收队列里拉取
- 新粉丝新关注了此用户，将该用户的发表内容插入到新粉丝的队列里

优点：

- 消除了拉模式的IO集中点，每个用户都读自己的数据，高并发下锁竞争少
- 拉取关注流的业务流程简单，速度快
- 拉取不需要进行大量的内存计算，网络传输，性能很高

缺点：

- 极大消耗存储资源，数据会存储很多分，比如大V的粉丝有一百万，他每次发表一次，数据会冗余一百万份，同时，插入到一百万的粉丝的队列中也比较费时。
- 新增关注，取消关，发布，取消发布的业务流程复杂。

**优化点**：先推给在线用户

离线用户上线再拉取

（3）推拉结合模式：也叫做读写混合，兼具推和拉两种模式的优点。

![image-20220603074811175](https://img-blog.csdnimg.cn/img_convert/8d55fabf6608fa691d4b33713c65ec16.png)

活跃用户使用推模式， 普通用户使用拉模式

总结

![image-20220603074829177](https://img-blog.csdnimg.cn/img_convert/f284124a8fd88f82194fa5ed22cdadc3.png)

> 这里我们使用简单易实现的推模式

#### 步骤

① 修改新增探店笔记的业务，在保存blog到数据库的同时，推送到粉丝的收件箱

② 收件箱满足可以根据时间戳排序，必须用Redis的数据结构实现

③ 查询收件箱数据时，可以实现分页查询

#### Feed流的分页问题

Feed流中的数据会不断更新，所以数据的角标也在变化，因此不能采用传统的分页模式。

![image-20220603075007804](https://img-blog.csdnimg.cn/img_convert/0ce955c887c19e2fc21db79f17197b27.png)

Feed流的滚动分页

使用滚动分页，每次存入页的最后一次角标，下次查询时，从最后一次角标的下一个元素开始查

![image-20220603075039013](https://img-blog.csdnimg.cn/img_convert/6ea885079461854d61b7b9d77a444a21.png)

#### 使用什么数据结构

思考应该用Redis中哪种数据结构进行时间戳的排序？是sortedset还是list？

> list查询数据只能按照角标查询，或者首尾查询，不支持滚动分页
>
> ```
>  sortedset按照score值排序，得出排名，如果按照排名查询则跟角标查询无区别，但是sortedset支持按照score值范围进行查询（把时间戳按照从大到小进行排列，每次排序都记住最小的时间戳，然后下次查询时再找比这个时间戳更小的，这样就实现了滚动分页查询）     
> 1
> ```

小结：如果数据会有变化，尽量使用sortedset实现分页查询

命令解析：

```
ZREVRANGE z1 0 2 WITHSCORES
1
```

实现滚动分页查询一定要传入四个参数：

分数的最大值（max），分数的最小值（min），偏移量（offset），数量（count）（其中分数最小值，数量固定不变）

最大值每次都要找上一次查询的最小分数（除了第一次）

偏移量（第一次采取0，以后采取1，小于等于与小于的区别）

注意：如果分数一样会出现以下问题，所以offset应是上一次查询的最小分数的总个数

![image-20220603075555185](https://img-blog.csdnimg.cn/img_convert/105b75f0d9746fe4dc5f86e90f237283.png)

![image-20220603075604818](https://img-blog.csdnimg.cn/img_convert/3cec5ee91eba2ba277e85456e3851016.png)

#### 代码实现

发布博客，推送到用户的 sortedset集合中

```
@Override
public Result saveBlog(Blog blog) {
    // 1.获取登录用户
    UserDTO user = UserHolder.getUser();
    blog.setUserId(user.getId());
    // 2.保存探店笔记
    boolean isSuccess = save(blog);
    if(!isSuccess){
        return Result.fail("新增笔记失败!");
    }
    // 3.查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
    List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
    // 4.推送笔记id给所有粉丝
    for (Follow follow : follows) {
        // 4.1.获取粉丝id
        Long userId = follow.getUserId();
        // 4.2.推送
        String key = FEED_KEY + userId;
        stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
    }
    // 5.返回id
    return Result.ok(blog.getId());
}
1234567891011121314151617181920212223
```

拉取博客

```
@Override
public Result queryBlogOfFollow(Long max, Integer offset) {
    // 1.获取当前用户
    Long userId = UserHolder.getUser().getId();
    // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
    String key = FEED_KEY + userId;
    Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
        .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
    // 3.非空判断
    if (typedTuples == null || typedTuples.isEmpty()) {
        return Result.ok();
    }
    // 4.解析数据：blogId、minTime（时间戳）、offset
    List<Long> ids = new ArrayList<>(typedTuples.size());
    long minTime = 0; // 2
    int os = 1; // 2
    for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
        // 4.1.获取id
        ids.add(Long.valueOf(tuple.getValue()));
        // 4.2.获取分数(时间戳）
        long time = tuple.getScore().longValue();
        if(time == minTime){
            os++;
        }else{
            minTime = time;
            os = 1;
        }
    }

    // 5.根据id查询blog
    String idStr = StrUtil.join(",", ids);
    List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

    for (Blog blog : blogs) {
        // 5.1.查询blog有关的用户
        queryBlogUser(blog);
        // 5.2.查询blog是否被点赞
        isBlogLiked(blog);
    }

    // 6.封装并返回
    ScrollResult r = new ScrollResult();
    r.setList(blogs);
    r.setOffset(os);
    r.setMinTime(minTime);

    return Result.ok(r);
}
123456789101112131415161718192021222324252627282930313233343536373839404142434445464748
```

## 10、附近商户

在首页中点击某个频道，即可看到频道下的商户，现在需要显示附近的商户，并且可以现在距离当前位置的距离。

![image-20220603080400930](https://img-blog.csdnimg.cn/img_convert/8b50e9c82e4e8439d3553d974cf53221.png)

### GEO数据结构

这里我们使用redis提供的GEO功能。

GEO数据结构 GEO就是Geolocation的简写形式，代表地理坐标。Redis在3.2版本中加入了对GEO的支持，允许存储地理坐标信息， 帮助我们根据经纬度来检索数据。

常见的命令有：

- GEOADD：添加一个地理空间信息，包含：经度（longitude）、纬度（latitude）、值（member）
- GEODIST：计算指定的两个点之间的距离并返回
- GEOHASH：将指定member的坐标转为hash字符串形式并返回
- GEOPOS：返回指定member的坐标 GEORADIUS：指定圆心、半径，找到该圆内包含的所有member，并按照与圆心之间的距离排序后返回。6.2以后已废 弃
- GEOSEARCH：在指定范围内搜索member，并按照与指定点之间的距离排序后返回。范围可以是圆形或矩形。6.2.新功 能
- GEOSEARCHSTORE：与GEOSEARCH功能一致，不过可以把结果存储到一个指定的key。 6.2.新功能

SpringDataRedis的2.3.9版本并不支持Redis 6.2提供的GEOSEARCH命令，因此我们需要提示其版本，修改自己的POM 文件，内容如下：

```
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
    <exclusions>
        <exclusion>
            <artifactId>spring-data-redis</artifactId>
            <groupId>org.springframework.data</groupId>
        </exclusion>
        <exclusion>
            <artifactId>lettuce-core</artifactId>
            <groupId>io.lettuce</groupId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>org.springframework.data</groupId>
    <artifactId>spring-data-redis</artifactId>
    <version>2.6.2</version>
</dependency>
<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
    <version>6.1.6.RELEASE</version>
</dependency>
123456789101112131415161718192021222324
```

### 具体实现

按照商户类型做分组，类型相同的商户作为同一组，以typeId为key存入同一个GEO集合中即可

![image-20220603080628413](https://img-blog.csdnimg.cn/img_convert/5423ed4cc56950809c1055d6c4e6b18c.png)

> 这些数据需要提前自己加载进redis中

```
@Test
void loadShopData() {
    // 1.查询店铺信息
    List<Shop> list = shopService.list();
    // 2.把店铺分组，按照typeId分组，typeId一致的放到一个集合
    Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
    // 3.分批完成写入Redis
    for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
        // 3.1.获取类型id
        Long typeId = entry.getKey();
        String key = SHOP_GEO_KEY + typeId;
        // 3.2.获取同类型的店铺的集合
        List<Shop> value = entry.getValue();
        List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
        // 3.3.写入redis GEOADD key 经度 纬度 member
        for (Shop shop : value) {
            // stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
            locations.add(new RedisGeoCommands.GeoLocation<>(
                shop.getId().toString(),
                new Point(shop.getX(), shop.getY())
            ));
        }
        stringRedisTemplate.opsForGeo().add(key, locations);
    }
}
12345678910111213141516171819202122232425
```

搜索附近商户

```
@Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
123456789101112131415161718192021222324252627282930313233343536373839404142434445464748495051525354
```

## 11、用户签到

假如我们用一张表来存储用户签到信息，其结构应该如下：

![image-20220603081010908](https://img-blog.csdnimg.cn/img_convert/898caa27ebd03e49467d6043b232541d.png)

假如有1000万用户，平均每人每年签到次数为10次，则这张表一年的数据量为 1亿条 每签到一次需要使用（8 + 8 + 1 + 1 + 3 + 1）共22 字节的内存，一个月则最多需要600多字节

### BitMap

我们按月来统计用户签到信息，签到记录为1，未签到则记录为0

![image-20220603081054951](https://img-blog.csdnimg.cn/img_convert/76ebe2ffcebb8f648fa2e45c00a3e2f2.png)

把每一个bit位对应当月的每一天，形成了映射关系。用0和1标示业务状态，这种思路就称为位图（BitMap）

Redis中是利用string类型数据结构实现BitMap，因此最大上限是512M，转换为bit则是 2^32个bit位

#### BitMap用法

Redis中是利用string类型数据结构实现BitMap，因此最大上限是512M，转换为bit则是 2^32个bit位。

BitMap的操作命令有：

- SETBIT：向指定位置（offset）存入一个0或1
- GETBIT ：获取指定位置（offset）的bit值
- BITCOUNT ：统计BitMap中值为1的bit位的数量
- BITFIELD ：操作（查询、修改、自增）BitMap中bit数组中的指定位置（offset）的值
- BITFIELD_RO ：获取BitMap中bit数组，并以十进制形式返回
- BITOP ：将多个BitMap的结果做位运算（与 、或、异或）
- BITPOS ：查找bit数组中指定范围内第一个0或1出现的位置

### 签到功能

需求：实现签到接口，将当前用户当天签到信息保存到Redis中

提示：因为BitMap底层是基于String数据结构，因此其操作也都封装在字符串相关操作中了。

![image-20220603081254916](https://img-blog.csdnimg.cn/img_convert/29490513de0655455a898203be6cc36b.png)

> 用【前缀+本月(yyyyMM))+用户id 】作为键，这样就可以表示每个月的签到情况了

```
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
123456789101112131415
```

### 签到统计

**问题1**：什么叫做连续签到天数？

从最后一次签到开始向前统计，直到遇到第一次未签到为止，计算总的签到次数，就是连续签到天数

![image-20220603081506760](https://img-blog.csdnimg.cn/img_convert/11a31ab444ba71f83a0eca798448616b.png)

**问题2**：如何得到本月到今天为止的所有签到数据？

BITFIELD key GET u[dayOfMonth] 0

问题3：如何从后向前遍历每个bit位？ 与 1 做与运算，就能得到最后一个bit位。 随后右移1位，下一个bit位就成为了最后一个bit位。

![image-20220603081538391](https://img-blog.csdnimg.cn/img_convert/3987ebdf9c31c9ea89d0237e71faf63d.png)

#### 代码实现

```
@Override
public Result signCount() {
    // 1.获取当前登录用户
    Long userId = UserHolder.getUser().getId();
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
1234567891011121314151617181920212223242526272829303132333435363738394041
```

## 12、UV统计

### HyperLogLog用法

首先我们搞懂两个概念：

- UV：全称Unique Visitor，也叫独立访客量，是指通过互联网访问、浏览这个网页的自然人。1天内同一个用户多次 访问该网站，只记录1次。
- PV：全称Page View，也叫页面访问量或点击量，用户每访问网站的一个页面，记录1次PV，用户多次打开页面，则 记录多次PV。往往用来衡量网站的流量。

> UV统计在服务端做会比较麻烦，因为要判断该用户是否已经统计过了，需要将统计过的用户信息保存。但是如果每个访 问的用户都保存到Redis中，数据量会非常恐怖

Hyperloglog(HLL)是从Loglog算法派生的概率算法，用于确定非常大的集合的基数，而不需要存储其所有值。

相关算法 原理大家可以参考：https://juejin.cn/post/6844903785744056333#heading-0

Redis中的HLL是基于string结构实现的，单个HLL的内存永远小于16kb，内存占用低的令人发指！作为代价，其测量结 果是概率性的，有小于0.81％的误差。不过对于UV统计来说，这完全可以忽略。

![image-20220603082700177](https://img-blog.csdnimg.cn/img_convert/69206b03843051c88e517cbbe4dcb104.png)

总结

HyperLogLog的作用：

 • 做海量数据的统计工作

HyperLogLog的优点：

 • 内存占用极低

 • 性能非常好

HyperLogLog的缺点：

 • 有一定的误差