package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;

/**
 * @author MiracloW
 * @date 2022-10-22 01:03
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    Result seckkillVoucher(Long voucherId);

    Result createVoucherOrder(Long voucherId);
}
