package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * @author MiracloW
 * @date 2022-10-22 01:04
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckkillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }

        // 3.判断秒杀是否已经结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }

        // 4.判断库存是否充足
        if(voucher.getStock() < 1){
            return Result.fail("库存不足");
        }
        //TODO 将整个方法加锁
        Long userId = UserHolder.getUser().getId();
//        synchronized (userId.toString().intern()){
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();//利用代理获取原始的事务对象
//            return proxy.createVoucherOrder(voucherId);
//        }
        //TODO 定义锁工具类后，先创建锁对象获取锁，再执行相应逻辑代码
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId,stringRedisTemplate);
        boolean islock = lock.tryLock(120);//设置超时时间
        //判断是否获取锁成功
        if(!islock){
            //获取锁失败
            return Result.fail("不允许重复下单");
        }
        try{
            //利用代理获取原始的事务对象
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();//利用代理获取原始的事务对象
            return proxy.createVoucherOrder(voucherId);
        }catch (Exception e){
            throw new RuntimeException(e);
        }finally {
            //释放锁
            lock.unlock();
        }

    }

    @Transactional
    public Result createVoucherOrder(Long voucherId){
        //TODO 5. 一人一单
        Long userId = UserHolder.getUser().getId();;

//        synchronized (userId.toString().intern()){//intern() 返回字符串的规范表示
                int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
                if(count > 0){
                    //用户已经购买过了
                    return Result.fail("用户已经购买一次");
                }


            // 5.扣减库存
            //TODO 乐观锁防止高并发下的超卖问题（stock>0就可以）
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")    //更新语句 set stock = stock-1
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0) // where id = ? nad stock > 0
                    .update();//匹配条件

            if(!success){
                return Result.fail("库存不足！");
            }

            // 6.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            long id = redisIdWorker.nextId("order");
            voucherOrder.setId(id);//订单唯一ID
            voucherOrder.setUserId(UserHolder.getUser().getId());//用户id
            voucherOrder.setVoucherId(voucherId);//代金券ID

            this.save(voucherOrder);

            // 7.返回订单ID
            return Result.ok(id);
    }
}
