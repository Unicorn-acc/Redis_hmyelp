package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author MiracloW
 * @date 2022-10-22 01:04
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {    //静态方法用静态代码块进行初始化（但是不推荐）
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua")); //设置脚本位置（ClassPathResource：在resource文件夹下找）
        SECKILL_SCRIPT.setResultType(Long.class);    // 返回值
    }

    private BlockingQueue<VoucherOrder> ordertasks = new ArrayBlockingQueue<>(1024*1024);//指定初始化内存值

    @PostConstruct //这个注解表示:这个方法在这个类VoucherOrderServiceImpl加载完毕后执行
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true){
                try {
//                    TODO 调用阻塞队列的方法
//                    // 1. 获取订单中的订单信息
//                    VoucherOrder voucherOrder = ordertasks.take();
//                    handleVoucherOrder(voucherOrder); // 处理订单


//                    TODO 使用Stream结构的方法
                    //1.获取消息队列中的订单信息  XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 2,1如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    //3.获取成功,可以下单
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //4.ACK确认
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常:" + e);
                    //处理异常消息
                    handlePendingList();
                }

            }
        }
    }

    //TODO stream处理消息方式下的处理异常信息的方法
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
                log.error("处理pendding-list订单异常", e);
                try{
                    Thread.sleep(20);
                }catch(Exception ex){
                    ex.printStackTrace();
                }
            }
        }
    }


//    //TODO 阻塞队列中处理订单的方法
//    private void handleVoucherOrder(VoucherOrder voucherOrder){
//        //1. 获取用户
//        Long userId = voucherOrder.getUserId();
//
//        //2. 获取锁对象
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean islock = lock.tryLock();//设置超时时间30s
//        //判断是否获取锁成功
//        if(!islock){
//            //获取锁失败
//            log.error("不允许重复下单");
//            return ;
//        }
//        try{
//            //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
//            proxy.createVoucherOrder(voucherOrder);
//        }catch (Exception e){
//            throw new RuntimeException(e);
//        }finally {
//            //释放锁
//            lock.unlock();
//        }
//    }

    private IVoucherOrderService proxy;

    //TODO P77优化:使用Stream结构作为消息队列异步处理判断用户资格+订单入库
    @Override
    public Result seckkillVoucher(Long voucherId) {
        //TODO  优化：使用Lua脚本判断库存和秒杀资格
        //获取用户
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
        if(r != 0){
            // 2.1 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }


        // 3.获取代理对象,让阻塞队列中能获取
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 3.返回订单id
        return Result.ok(orderId);
    }

//    //TODO P70优化:使用阻塞队列异步处理判断用户资格+订单入库
//    @Override
//    public Result seckkillVoucher(Long voucherId) {
//        //TODO  优化：使用Lua脚本判断库存和秒杀资格
//        //获取用户
//        Long userId = UserHolder.getUser().getId();
//        long orderId = redisIdWorker.nextId("order");
//        // 1.执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString(), String.valueOf(orderId)
//        );
//        int r = result.intValue();
//
//        // 2.判断结果是否为0
//        if(r != 0){
//            // 2.1 不为0，代表没有购买资格
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        // 2.2 为0，有购买资格，把下单信息保存到消息队列中
//
//        // TODO 保存阻塞队列
//        // 2.3创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long id = redisIdWorker.nextId("order");
//        voucherOrder.setId(id);//订单唯一ID
//        voucherOrder.setUserId(UserHolder.getUser().getId());//用户id
//        voucherOrder.setVoucherId(voucherId);//代金券ID
//
//
//        // 2.4 创建阻塞队列BlockingQueue,并将订单放入
//        ordertasks.add(voucherOrder);
//
//        // 3.获取代理对象,让阻塞队列中能获取
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//        // 3.返回订单id
//        return Result.ok(orderId);
//    }


//    TODO 使用java代码判断库存和一人一单
//    @Override
//    public Result seckkillVoucher(Long voucherId) {
//        // 1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        // 2.判断秒杀是否开始
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始");
//        }
//
//        // 3.判断秒杀是否已经结束
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束");
//        }
//
//        // 4.判断库存是否充足
//        if(voucher.getStock() < 1){
//            return Result.fail("库存不足");
//        }
//        //TODO 将整个方法加锁
//        Long userId = UserHolder.getUser().getId();
////        synchronized (userId.toString().intern()){
////            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();//利用代理获取原始的事务对象
////            return proxy.createVoucherOrder(voucherId);
////        }
//        //TODO 定义锁工具类后，先创建锁对象获取锁，再执行相应逻辑代码
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId,stringRedisTemplate);
//
//        //TODO 使用Redissonapi进行锁的管理
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean islock = lock.tryLock();//设置超时时间30s
//        //判断是否获取锁成功
//        if(!islock){
//            //获取锁失败
//            return Result.fail("不允许重复下单");
//        }
//        try{
//            //利用代理获取原始的事务对象
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();//利用代理获取原始的事务对象
//            return proxy.createVoucherOrder(voucherId);
//        }catch (Exception e){
//            throw new RuntimeException(e);
//        }finally {
//            //释放锁
//            lock.unlock();
//        }
//    }

//    @Transactional
//    public Result createVoucherOrder(Long voucherId){
//        //TODO P54 5. 一人一单
//        Long userId = UserHolder.getUser().getId();;
//
////        synchronized (userId.toString().intern()){//intern() 返回字符串的规范表示
//        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        if(count > 0){
//            //用户已经购买过了
//            return Result.fail("用户已经购买一次");
//        }
//
//
//        // 5.扣减库存
//        //TODO 乐观锁防止高并发下的超卖问题（stock>0就可以）
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock - 1")    //更新语句 set stock = stock-1
//                .eq("voucher_id", voucherId)
//                .gt("stock", 0) //TODO where id = ? nad stock > 0
//                .update();//匹配条件
//
//        if(!success){
//            return Result.fail("库存不足！");
//        }
//
//        // 6.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long id = redisIdWorker.nextId("order");
//        voucherOrder.setId(id);//订单唯一ID
//        voucherOrder.setUserId(UserHolder.getUser().getId());//用户id
//        voucherOrder.setVoucherId(voucherId);//代金券ID
//
//        this.save(voucherOrder);
//
//        // 7.返回订单ID
//        return Result.ok(id);
//    }

    //TODO P71阻塞队列中处理订单入库操作
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //TODO 5. 一人一单
        Long userId = voucherOrder.getUserId();

//        synchronized (userId.toString().intern()){//intern() 返回字符串的规范表示
                int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
                if(count > 0){
                    //用户已经购买过了
//                    return Result.fail("用户已经购买一次");
                }


            // 5.扣减库存
            //TODO 乐观锁防止高并发下的超卖问题（stock>0就可以）
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")    //更新语句 set stock = stock-1
                    .eq("voucher_id", voucherOrder.getVoucherId())
                    .gt("stock", 0) //TODO where id = ? nad stock > 0
                    .update();//匹配条件

            if(!success){
//                return Result.fail("库存不足！");
            }

            // 6.创建订单
            //TODO 方法参数id改order,直接调用mapper的save
            save(voucherOrder);

    }
}
