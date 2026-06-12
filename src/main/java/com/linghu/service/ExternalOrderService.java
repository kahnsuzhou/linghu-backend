package com.linghu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linghu.entity.*;
import com.linghu.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 外单业务服务
 * 状态说明：0=待处理（刚导入）, 1=库存已锁定, 2=拣货中, 3=已发货, 4=异常, 5=已取消
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalOrderService {

    private final ExternalBatchMapper externalBatchMapper;
    private final ExternalOrderMapper externalOrderMapper;
    private final InventoryMapper inventoryMapper;
    private final ProductMapper productMapper;
    private final WarehouseMapper warehouseMapper;
    private final WalletMapper walletMapper;
    private final WalletTransactionMapper walletTransactionMapper;
    private final UserMapper userMapper;
    private final BrandMapper brandMapper;

    // -------------------------------------------------------------------------
    // 批次号生成
    // -------------------------------------------------------------------------

    private static final AtomicInteger BATCH_SEQ = new AtomicInteger(0);

    private String generateBatchNo() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int seq = BATCH_SEQ.incrementAndGet() % 1_000_000;
        return String.format("WD%s%06d", date, seq);
    }

    // -------------------------------------------------------------------------
    // 品牌端：批量导入外单
    // -------------------------------------------------------------------------

    /**
     * 批量导入外单，含自动分仓逻辑。
     * <p>
     * 分仓规则：对每条外单，根据 sku_code 从 product 表找到对应 productId，
     * 再从 inventory 表找所有满足条件的仓库：
     *   1. 品牌ID匹配
     *   2. quantity - locked_quantity > 0（有可用库存）
     *   3. quantity >= 外单需求数量
     * 优先选可用库存最多的仓库。
     * 找不到则 fail_reason='库存不足'，warehouse_id=null，status=4（异常）。
     *
     * @param brandId    品牌ID
     * @param operatorId 操作人（当前登录用户ID）
     * @param fileName   上传文件名
     * @param orders     外单数据列表，每条 Map 包含字段：
     *                   externalOrderNo, channel, productName, skuCode,
     *                   quantity, receiverName, receiverPhone, receiverAddress
     * @return Map 包含：batchId, batchNo, totalCount, successCount, failedCount, orders
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> importOrders(Long userIdAsBrandId, Long operatorId,
                                            String fileName, List<Map<String, Object>> orders) {
        // 将 userId 转换为 brand 表的真实 brandId
        Brand brand = brandMapper.selectOne(new LambdaQueryWrapper<Brand>()
                .eq(Brand::getUserId, userIdAsBrandId)
                .last("LIMIT 1"));
        Long brandId = brand != null ? brand.getId() : userIdAsBrandId;

        int total = orders.size();
        int successCount = 0;
        int failedCount = 0;

        // 1. 创建批次记录
        ExternalBatch batch = new ExternalBatch();
        batch.setBatchNo(generateBatchNo());
        batch.setBrandId(brandId);
        batch.setFileName(fileName);
        batch.setTotalCount(total);
        batch.setSuccessCount(0);
        batch.setFailedCount(0);
        batch.setStatus(0); // 处理中
        batch.setOperatorId(operatorId);
        externalBatchMapper.insert(batch);
        Long batchId = batch.getId();

        List<Map<String, Object>> resultList = new ArrayList<>();

        // 2. 逐条处理
        for (Map<String, Object> row : orders) {
            ExternalOrder order = new ExternalOrder();
            order.setBatchId(batchId);
            order.setBrandId(brandId);
            order.setExternalOrderNo(getString(row, "externalOrderNo"));
            order.setChannel(getString(row, "channel"));
            order.setProductName(getString(row, "productName"));
            order.setSkuCode(getString(row, "skuCode"));
            order.setQuantity(getInt(row, "quantity", 1));
            order.setReceiverName(getString(row, "receiverName"));
            order.setReceiverPhone(getString(row, "receiverPhone"));
            order.setReceiverAddress(getString(row, "receiverAddress"));

            // 自动分仓
            String skuCode = order.getSkuCode();
            Integer need = order.getQuantity();

            Long assignedWarehouseId = null;
            String failReason = null;

            if (skuCode != null && !skuCode.isEmpty()) {
                // 找 product
                Product product = productMapper.selectOne(
                        new LambdaQueryWrapper<Product>()
                                .eq(Product::getSkuCode, skuCode)
                                .eq(Product::getBrandId, brandId)
                                .eq(Product::getDeleted, 0)
                                .last("LIMIT 1"));

                if (product == null) {
                    failReason = "SKU不存在";
                } else {
                    Long productId = product.getId();
                    // 找有库存的仓库（品牌+产品匹配，可用量 >= 需求）
                    List<Inventory> inventories = inventoryMapper.selectList(
                            new LambdaQueryWrapper<Inventory>()
                                    .eq(Inventory::getProductId, productId)
                                    .eq(Inventory::getBrandId, brandId)
                                    .eq(Inventory::getDeleted, 0)
                                    .apply("quantity - locked_quantity >= {0}", need));

                    if (inventories.isEmpty()) {
                        failReason = "库存不足";
                    } else {
                        // 选可用库存最多的仓库
                        Inventory best = inventories.stream()
                                .max(Comparator.comparingInt(
                                        inv -> inv.getQuantity() - inv.getLockedQuantity()))
                                .orElse(null);
                        if (best != null) {
                            assignedWarehouseId = best.getWarehouseId();
                        } else {
                            failReason = "库存不足";
                        }
                    }
                }
            } else {
                failReason = "SKU编码为空";
            }

            if (assignedWarehouseId != null) {
                order.setWarehouseId(assignedWarehouseId);
                order.setStatus(1); // 库存已锁定（分仓成功，等待仓库拣货）
                successCount++;
            } else {
                order.setWarehouseId(null);
                order.setStatus(4); // 异常
                order.setFailReason(failReason);
                failedCount++;
            }

            externalOrderMapper.insert(order);

            Map<String, Object> item = new HashMap<>();
            item.put("orderId", order.getId());
            item.put("externalOrderNo", order.getExternalOrderNo());
            item.put("skuCode", order.getSkuCode());
            item.put("warehouseId", order.getWarehouseId());
            item.put("status", order.getStatus());
            item.put("failReason", order.getFailReason());
            resultList.add(item);
        }

        // 3. 更新批次统计
        int batchStatus = failedCount == 0 ? 1 : (successCount == 0 ? 1 : 2);
        batch.setSuccessCount(successCount);
        batch.setFailedCount(failedCount);
        batch.setStatus(batchStatus);
        externalBatchMapper.updateById(batch);

        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("batchNo", batch.getBatchNo());
        result.put("totalCount", total);
        result.put("successCount", successCount);
        result.put("failedCount", failedCount);
        result.put("orders", resultList);
        return result;
    }

    // -------------------------------------------------------------------------
    // 品牌端：外单列表（分页）
    // -------------------------------------------------------------------------

    /**
     * 品牌端获取外单列表（分页）
     *
     * @param brandId 品牌ID
     * @param status  状态过滤（null=全部）
     * @param page    页码（从1开始）
     * @param size    每页条数
     */
    public Map<String, Object> getBrandOrders(Long userId, Integer status, int page, int size) {
        // userId → brandId
        Brand brand = brandMapper.selectOne(new LambdaQueryWrapper<Brand>()
                .eq(Brand::getUserId, userId).last("LIMIT 1"));
        Long brandId = brand != null ? brand.getId() : userId;

        int offset = (page - 1) * size;
        List<ExternalOrder> list = externalOrderMapper.selectByBrandIdAndStatus(brandId, status, offset, size);

        // 查总数
        LambdaQueryWrapper<ExternalOrder> cntWrapper = new LambdaQueryWrapper<ExternalOrder>()
                .eq(ExternalOrder::getBrandId, brandId)
                .eq(ExternalOrder::getDeleted, 0);
        if (status != null) {
            cntWrapper.eq(ExternalOrder::getStatus, status);
        }
        Long total = externalOrderMapper.selectCount(cntWrapper);

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("list", list);
        return result;
    }

    // -------------------------------------------------------------------------
    // 品牌端：取消外单
    // -------------------------------------------------------------------------

    /**
     * 品牌端取消外单（仅 status=0 可取消）
     *
     * @param brandId 品牌ID（用于权限校验）
     * @param orderId 外单ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancelOrder(Long userId, Long orderId) {
        // userId → brandId
        Brand brand = brandMapper.selectOne(new LambdaQueryWrapper<Brand>()
                .eq(Brand::getUserId, userId).last("LIMIT 1"));
        Long brandId = brand != null ? brand.getId() : userId;

        ExternalOrder order = externalOrderMapper.selectById(orderId);
        if (order == null || order.getDeleted() == 1) {
            return fail("外单不存在");
        }
        if (!brandId.equals(order.getBrandId())) {
            return fail("无权操作该外单");
        }
        if (order.getStatus() != 0 && order.getStatus() != 1) {
            return fail("当前状态不可取消，仅待处理或库存锁定的外单可取消");
        }

        order.setStatus(5); // 已取消
        order.setUpdateTime(LocalDateTime.now());
        externalOrderMapper.updateById(order);

        return ok("取消成功");
    }

    // -------------------------------------------------------------------------
    // 品牌端：批次列表（分页）
    // -------------------------------------------------------------------------

    /**
     * 品牌端获取批次列表（分页，按创建时间倒序）
     *
     * @param brandId 品牌ID
     * @param page    页码（从1开始）
     * @param size    每页条数
     */
    public Map<String, Object> getBatchList(Long userId, int page, int size) {
        // userId → brandId
        Brand brand = brandMapper.selectOne(new LambdaQueryWrapper<Brand>()
                .eq(Brand::getUserId, userId).last("LIMIT 1"));
        Long brandId = brand != null ? brand.getId() : userId;

        int offset = (page - 1) * size;
        List<ExternalBatch> list = externalBatchMapper.selectByBrandId(brandId, offset, size);

        Long total = externalBatchMapper.selectCount(
                new LambdaQueryWrapper<ExternalBatch>()
                        .eq(ExternalBatch::getBrandId, brandId)
                        .eq(ExternalBatch::getDeleted, 0));

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("list", list);
        return result;
    }

    // -------------------------------------------------------------------------
    // 仓端：外单列表
    // -------------------------------------------------------------------------

    /**
     * 仓端获取外单列表（status=null 查全部待处理和进行中，即0和2）
     *
     * @param warehouseId 仓库ID
     * @param status      状态过滤（null=全部）
     */
    public Map<String, Object> getWarehouseOrders(Long warehouseId, Integer status) {
        List<ExternalOrder> list = externalOrderMapper.selectByWarehouseIdAndStatus(warehouseId, status);

        Map<String, Object> result = new HashMap<>();
        result.put("total", list.size());
        result.put("list", list);
        return result;
    }

    // -------------------------------------------------------------------------
    // 仓端：开始拣货（status 0→2）
    // -------------------------------------------------------------------------

    /**
     * 仓端开始拣货，将外单状态从 0（待处理）变为 2（拣货中）
     *
     * @param warehouseId 仓库ID（权限校验）
     * @param orderId     外单ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> startPicking(Long warehouseId, Long orderId) {
        ExternalOrder order = externalOrderMapper.selectById(orderId);
        if (order == null || order.getDeleted() == 1) {
            return fail("外单不存在");
        }
        if (!warehouseId.equals(order.getWarehouseId())) {
            return fail("该外单不属于当前仓库");
        }
        if (order.getStatus() != 1) {
            return fail("当前状态不可开始拣货，仅库存锁定（status=1）的外单可操作");
        }

        order.setStatus(2); // 拣货中
        order.setUpdateTime(LocalDateTime.now());
        externalOrderMapper.updateById(order);

        return ok("开始拣货成功");
    }

    // -------------------------------------------------------------------------
    // 仓端：确认发货（status 2→3）
    // -------------------------------------------------------------------------

    /**
     * 仓端确认发货，将外单状态从 2（拣货中）变为 3（已发货），并填写物流信息
     *
     * @param warehouseId 仓库ID（权限校验）
     * @param data        包含字段：orderId, logisticsCompany, logisticsNo
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> completePicking(Long warehouseId, Map<String, Object> data) {
        Long orderId = getLong(data, "orderId");
        if (orderId == null) {
            return fail("orderId不能为空");
        }

        ExternalOrder order = externalOrderMapper.selectById(orderId);
        if (order == null || order.getDeleted() == 1) {
            return fail("外单不存在");
        }
        if (!warehouseId.equals(order.getWarehouseId())) {
            return fail("该外单不属于当前仓库");
        }
        if (order.getStatus() != 2) {
            return fail("当前状态不可确认发货，仅拣货中（status=2）的外单可操作");
        }

        String logisticsCompany = getString(data, "logisticsCompany");
        String logisticsNo = getString(data, "logisticsNo");
        if (logisticsCompany == null || logisticsCompany.isEmpty()) {
            return fail("物流公司不能为空");
        }
        if (logisticsNo == null || logisticsNo.isEmpty()) {
            return fail("物流单号不能为空");
        }

        order.setStatus(3); // 已发货
        order.setLogisticsCompany(logisticsCompany);
        order.setLogisticsNo(logisticsNo);
        order.setShipTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        externalOrderMapper.updateById(order);

        // ── 写仓主收益流水 ────────────────────────────────────────────
        try {
            // 查仓库 userId
            Warehouse warehouse = warehouseMapper.selectOne(
                    new LambdaQueryWrapper<Warehouse>()
                            .eq(Warehouse::getId, warehouseId)
                            .eq(Warehouse::getDeleted, 0));
            if (warehouse != null && warehouse.getUserId() != null) {
                BigDecimal serviceFee = warehouse.getServiceFeeRate() != null
                        ? warehouse.getServiceFeeRate() : BigDecimal.ZERO;
                Long warehouseUserId = warehouse.getUserId();

                // 查或建钱包
                Wallet wallet = walletMapper.selectOne(
                        new LambdaQueryWrapper<Wallet>()
                                .eq(Wallet::getUserId, warehouseUserId));
                BigDecimal oldBalance = BigDecimal.ZERO;
                if (wallet == null) {
                    wallet = new Wallet();
                    wallet.setUserId(warehouseUserId);
                    wallet.setBalance(serviceFee);
                    wallet.setFrozen(BigDecimal.ZERO);
                    walletMapper.insert(wallet);
                    oldBalance = BigDecimal.ZERO;
                } else {
                    oldBalance = wallet.getBalance() != null ? wallet.getBalance() : BigDecimal.ZERO;
                    wallet.setBalance(oldBalance.add(serviceFee));
                    walletMapper.updateById(wallet);
                }
                BigDecimal newBalance = oldBalance.add(serviceFee);

                // 写流水
                WalletTransaction tx = new WalletTransaction();
                tx.setUserId(warehouseUserId);
                tx.setType("INCOME");
                tx.setAmount(serviceFee);
                tx.setBalanceAfter(newBalance);
                tx.setRefId(orderId);
                tx.setRemark("外单服务费¥" + serviceFee.toPlainString()
                        + " 外单号:" + (order.getExternalOrderNo() != null ? order.getExternalOrderNo() : orderId));
                walletTransactionMapper.insert(tx);
            }
        } catch (Exception e) {
            log.warn("写外单收益流水失败 orderId={}: {}", orderId, e.getMessage());
        }

        return ok("确认发货成功");
    }

    // -------------------------------------------------------------------------
    // 仓端：上报异常（status→4）
    // -------------------------------------------------------------------------

    /**
     * 仓端上报异常，将外单状态变为 4（异常）
     *
     * @param warehouseId 仓库ID（权限校验）
     * @param data        包含字段：orderId, exceptionReason
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reportException(Long warehouseId, Map<String, Object> data) {
        Long orderId = getLong(data, "orderId");
        if (orderId == null) {
            return fail("orderId不能为空");
        }

        ExternalOrder order = externalOrderMapper.selectById(orderId);
        if (order == null || order.getDeleted() == 1) {
            return fail("外单不存在");
        }
        if (!warehouseId.equals(order.getWarehouseId())) {
            return fail("该外单不属于当前仓库");
        }
        if (order.getStatus() == 5 || order.getStatus() == 3) {
            return fail("已取消或已发货的外单不可上报异常");
        }

        String exceptionReason = getString(data, "exceptionReason");
        if (exceptionReason == null || exceptionReason.isEmpty()) {
            return fail("异常原因不能为空");
        }

        order.setStatus(4); // 异常
        order.setExceptionReason(exceptionReason);
        order.setUpdateTime(LocalDateTime.now());
        externalOrderMapper.updateById(order);

        return ok("异常上报成功");
    }

    // -------------------------------------------------------------------------
    // 私有工具方法
    // -------------------------------------------------------------------------

    private Map<String, Object> ok(String msg) {
        Map<String, Object> map = new HashMap<>();
        map.put("success", true);
        map.put("msg", msg);
        return map;
    }

    private Map<String, Object> fail(String msg) {
        Map<String, Object> map = new HashMap<>();
        map.put("success", false);
        map.put("msg", msg);
        return map;
    }

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString().trim() : null;
    }

    private int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val == null) return defaultVal;
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private Long getLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
