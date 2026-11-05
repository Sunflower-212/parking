package org.zjmproject.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.zjmproject.observability.RuntimeEvents;
import org.zjmproject.pojo.FormContent;

import java.math.BigDecimal;
import java.sql.Statement;
import java.time.*;
import java.util.*;

@Service
public class MembershipService {
    public record Plan(String code, String name, BigDecimal price, int months) {}
    public record Order(String id, String planCode, String planName, int months, BigDecimal price,
                        BigDecimal reservedAmount, BigDecimal supplementAmount, String state,
                        Integer rechargeId, String paymentState, LocalDateTime expiresAt,
                        LocalDateTime startsAt, LocalDateTime endsAt, boolean renewed) {}
    public static final String TOPUP = "MEMBERSHIP_TOPUP", DEBIT = "MEMBERSHIP_DEBIT";
    private static final List<Plan> PLANS = List.of(
            new Plan("MONTHLY", "月度", new BigDecimal("188.00"), 1),
            new Plan("QUARTERLY", "季度", new BigDecimal("488.00"), 3),
            new Plan("YEARLY", "年度", new BigDecimal("1888.00"), 12));
    private final JdbcTemplate db;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final AliPayService alipay;
    private final boolean enabled;
    private final Duration timeout;

    public MembershipService(JdbcTemplate db, Clock clock, PlatformTransactionManager manager,
                             ApplicationEventPublisher events, AliPayService alipay,
                             @Value("${parking.integrations.alipay:false}") boolean enabled,
                             @Value("${parking.payment-timeout:30m}") Duration timeout) {
        this.db = db;
        this.clock = clock;
        this.tx = new TransactionTemplate(manager);
        this.events = events;
        this.alipay = alipay;
        this.enabled = enabled;
        this.timeout = timeout;
    }

    public List<Plan> plans() { return PLANS; }
    private LocalDateTime now() { return LocalDateTime.now(clock); }

    static LocalDateTime time(Object value) {
        return value == null ? null : value instanceof java.sql.Timestamp timestamp
                ? timestamp.toLocalDateTime() : (LocalDateTime) value;
    }

    private static BigDecimal amount(Map<String, Object> row, String key) {
        return new BigDecimal(row.get(key).toString());
    }

    private Map<String, Object> lockUser(int userId) {
        var users = db.queryForList("select balance,reserved_balance,vip_start_time,vip_end_time from user where id=? for update", userId);
        if (users.isEmpty()) throw new IllegalArgumentException("用户不存在");
        return users.get(0);
    }

    // The caller holds the user lock before taking an order or recharge lock.
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> lock(String id, int userId) {
        var rows = db.queryForList("select * from membership_order where id=? and user_id=? for update", id, userId);
        if (rows.isEmpty()) throw new IllegalArgumentException("会员订单不存在");
        return rows.get(0);
    }

    private Order read(String id, int userId) {
        var rows = db.queryForList("select o.*,r.id recharge_id,r.payment_state from membership_order o " +
                "left join recharges r on r.membership_order_id=o.id and r.business_type='MEMBERSHIP_TOPUP' " +
                "where o.id=? and o.user_id=?", id, userId);
        if (rows.isEmpty()) throw new IllegalArgumentException("会员订单不存在");
        var row = rows.get(0);
        return new Order(id, (String) row.get("plan_code"), (String) row.get("plan_name"),
                ((Number) row.get("months")).intValue(), amount(row, "price"), amount(row, "reserved_amount"),
                amount(row, "supplement_amount"), (String) row.get("state"),
                row.get("recharge_id") == null ? null : ((Number) row.get("recharge_id")).intValue(),
                (String) row.get("payment_state"), time(row.get("expires_at")), time(row.get("starts_at")),
                time(row.get("ends_at")), Boolean.TRUE.equals(row.get("renewed")) || Integer.valueOf(1).equals(row.get("renewed")));
    }

    public Order current(int userId) {
        var ids = db.queryForList("select id from membership_order where active_user_id=?", String.class, userId);
        return ids.isEmpty() ? null : read(ids.get(0), userId);
    }

    public Order create(int userId, String code, String key) {
        Plan plan = PLANS.stream().filter(item -> item.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请选择有效的会员套餐"));
        if (key == null || !key.matches("[A-Za-z0-9-]{16,64}"))
            throw new IllegalArgumentException("请提供有效的请求标识");
        return tx.execute(transaction -> {
            var user = lockUser(userId);
            var existing = db.queryForList("select id,plan_code from membership_order where user_id=? and request_key=?", userId, key);
            if (!existing.isEmpty()) {
                if (!code.equals(existing.get(0).get("plan_code"))) throw new IllegalArgumentException("请求标识已用于其他套餐，请刷新页面");
                return read((String) existing.get(0).get("id"), userId);
            }
            Order pending = current(userId);
            if (pending != null) {
                throw new IllegalStateException("请先完成或取消待支付会员订单");
            }
            BigDecimal available = amount(user, "balance").subtract(amount(user, "reserved_balance"));
            BigDecimal reserved = available.min(plan.price());
            BigDecimal supplement = plan.price().subtract(reserved);
            if (supplement.signum() > 0 && !enabled) throw new IllegalStateException("余额不足，支付宝尚未启用");
            String id = UUID.randomUUID().toString();
            LocalDateTime created = now().withNano(0);
            LocalDateTime expires = supplement.signum() > 0 ? created.plus(timeout).withNano(0) : null;
            db.update("insert into membership_order(id,user_id,request_key,plan_code,plan_name,months,price,reserved_amount,supplement_amount,expires_at,created_at) values(?,?,?,?,?,?,?,?,?,?,?)",
                    id, userId, key, code, plan.name(), plan.months(), plan.price(), reserved, supplement, expires, created);
            db.update("update user set reserved_balance=reserved_balance+?,update_time=? where id=?", reserved, created, userId);
            if (supplement.signum() == 0) {
                settle(id, userId);
            } else {
                createSupplement(userId, id, plan, supplement, created, expires);
            }
            return read(id, userId);
        });
    }

    private void createSupplement(int userId, String id, Plan plan, BigDecimal supplement,
                                  LocalDateTime created, LocalDateTime expires) {
        var keys = new GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("insert into recharges(user_id,amounts,pay_method,create_time,merchant_order_no,expires_at,payment_state,business_type,description,membership_order_id) values(?,?,0,?,?,?,'PENDING',?,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setInt(1, userId);
            statement.setBigDecimal(2, supplement);
            statement.setObject(3, created);
            statement.setString(4, "PK" + UUID.randomUUID().toString().replace("-", ""));
            statement.setObject(5, expires);
            statement.setString(6, TOPUP);
            statement.setString(7, "会员充值补充（" + plan.name() + "）");
            statement.setString(8, id);
            return statement;
        }, keys);
        int rechargeId = Objects.requireNonNull(keys.getKey()).intValue();
        db.update("insert into payment_event(recharge_id,next_run_at,request_id) values(?,?,?)", rechargeId, created.plusSeconds(30), RuntimeEvents.requestId());
        events.publishEvent(new PaymentService.Scheduled(rechargeId, 1));
    }

    public FormContent<String> pay(int userId, String id) {
        Order order = read(id, userId);
        if (!enabled) throw new IllegalStateException("支付宝尚未启用");
        if (!"PENDING".equals(order.state()) || order.rechargeId() == null ||
                !"PENDING".equals(order.paymentState()) || !now().isBefore(order.expiresAt()))
            throw new IllegalStateException("订单正在核验或已结束，请刷新订单状态");
        return alipay.pay(order.rechargeId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void settle(String id, int userId) {
        var order = db.queryForMap("select * from membership_order where id=? and user_id=? for update", id, userId);
        if (!"PENDING".equals(order.get("state"))) return;
        var user = db.queryForMap("select vip_start_time,vip_end_time from user where id=? for update", userId);
        LocalDateTime at = now().withNano(0), oldEnd = time(user.get("vip_end_time"));
        boolean renewed = oldEnd != null && at.isBefore(oldEnd);
        LocalDateTime start = renewed ? time(user.get("vip_start_time")) : at;
        LocalDateTime end = (renewed ? oldEnd : at).plusMonths(((Number) order.get("months")).intValue());
        BigDecimal reserved = amount(order, "reserved_amount"), price = amount(order, "price");
        int updated = db.update("update user set balance=balance-?,reserved_balance=reserved_balance-?,status=1,vip_start_time=?,vip_end_time=?,update_time=? where id=? and reserved_balance>=? and balance-reserved_balance>=?-?",
                price, reserved, start, end, at, userId, reserved, price, reserved);
        if (updated != 1) throw new IllegalStateException("会员结算余额异常，请核验订单");
        db.update("insert into recharges(user_id,amounts,pay_method,flag,payment_state,pay_no,create_time,pay_time,business_type,description,membership_order_id) values(?,?,null,1,'PAID',null,?,?,?,?,?)",
                userId, price, at, at, DEBIT, "会员充值扣费（" + order.get("plan_name") + "）", id);
        db.update("update membership_order set state='SUCCEEDED',starts_at=?,ends_at=?,renewed=?,completed_at=? where id=?", start, end, renewed, at, id);
        RuntimeEvents.committed("membership.purchase", "SUCCESS", userId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void close(String id, int userId) {
        var order = db.queryForMap("select state,reserved_amount from membership_order where id=? and user_id=? for update", id, userId);
        if (!"PENDING".equals(order.get("state"))) return;
        BigDecimal reserved = amount(order, "reserved_amount");
        if (db.update("update user set reserved_balance=reserved_balance-?,update_time=? where id=? and reserved_balance>=?", reserved, now(), userId, reserved) != 1)
            throw new IllegalStateException("会员预留余额异常，请核验订单");
        db.update("update membership_order set state='CLOSED',completed_at=? where id=?", now(), id);
        RuntimeEvents.committed("membership.purchase", "CLOSED", userId);
    }

    public Order paymentOrder(String id, int userId) { return read(id, userId); }
}
