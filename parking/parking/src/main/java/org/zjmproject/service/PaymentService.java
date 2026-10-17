package org.zjmproject.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zjmproject.pojo.FormContent;
import org.zjmproject.pojo.Recharges;
import java.math.BigDecimal;
import java.util.Map;

@Service
public class PaymentService {
    private final JdbcTemplate db;
    private final RechargesService recharges;
    private final AliPayService alipay;
    private final boolean enabled;
    private final String appId;
    public PaymentService(JdbcTemplate db,RechargesService recharges,AliPayService alipay,@Value("${parking.integrations.alipay:false}") boolean enabled,@Value("${alipay.app-id:}") String appId) {
        this.db=db;this.recharges=recharges;this.alipay=alipay;this.enabled=enabled;this.appId=appId;
    }
    @Transactional(rollbackFor=Exception.class)
    public FormContent<String> create(int userId,BigDecimal amount,int method) throws Exception {
        if(!enabled) throw new IllegalStateException("支付宝尚未启用，请配置支付宝服务");
        if(method!=0) throw new IllegalArgumentException("当前仅支持支付宝，微信支付尚未接入");
        if(amount==null || amount.signum()<=0 || amount.scale()>2 || amount.compareTo(new BigDecimal("1000000"))>0) throw new IllegalArgumentException("充值金额需为0.01至1000000，最多两位小数");
        Recharges charge=new Recharges();charge.setUser_id(userId);charge.setAmounts(amount);charge.setPay_method(method);
        recharges.add(charge);return alipay.pay(charge.getId());
    }
    @Transactional public void cancel(int userId,int id) {
        var rows=db.queryForList("select * from recharges where id=? and user_id=? for update",id,userId);
        if(rows.isEmpty()) throw new IllegalArgumentException("充值记录不存在");
        if(((Number)rows.get(0).get("flag")).intValue()==1) throw new IllegalStateException("已支付的充值不能取消");
        db.update("update recharges set flag=2 where id=? and flag=0",id);
    }
    /** Only call with signature-verified provider parameters. */
    @Transactional public void confirm(Map<String,String> params) {
        if(!enabled) throw new IllegalStateException("支付服务尚未启用");
        if(appId.isBlank() || !appId.equals(params.get("app_id")) || !java.util.Set.of("TRADE_SUCCESS","TRADE_FINISHED").contains(params.getOrDefault("trade_status",""))) throw new IllegalArgumentException("支付应用或状态不匹配");
        int id=Integer.parseInt(params.get("out_trade_no"));
        Map<String,Object> charge=db.queryForMap("select * from recharges where id=? for update",id);
        BigDecimal expected=new BigDecimal(charge.get("amounts").toString()), paid=new BigDecimal(params.get("total_amount"));
        if(expected.compareTo(paid)!=0 || paid.signum()<=0 || ((Number)charge.get("pay_method")).intValue()!=0) throw new IllegalArgumentException("支付金额或支付方式不匹配");
        if(((Number)charge.get("flag")).intValue()==1) return;
        String trade=params.get("trade_no");
        if(trade==null || trade.isBlank()) throw new IllegalArgumentException("缺少支付流水号");
        if(db.update("update user set balance=balance+?,update_time=now() where id=?",expected,charge.get("user_id"))!=1) throw new IllegalStateException("充值用户不存在");
        db.update("update recharges set flag=1,pay_no=?,pay_time=? where id=?",trade,params.get("gmt_payment"),id);
    }
}
