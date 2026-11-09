package org.zjmproject.mapper;

import org.apache.ibatis.annotations.*;
import org.zjmproject.pojo.Rule;

import java.util.List;

@Mapper
public interface RuleMapper {
    @Insert("insert into rule(rule_name,free_time,charging_unit,unit_cost,create_time,update_time)" +
            " values (#{rule_name},#{free_time},#{charging_unit},#{unit_cost},now(),now())")
    void addRule(Rule rule);

    List<Rule> rules(String ruleName);

    @Select("select * from rule where id=#{ruleId}")
    Rule findRuleById(Integer ruleId);

    @Delete("delete from rule where id=#{ruleId}")
    void deleteRule(Integer ruleId);

    @Update("update rule set rule_name=#{rule_name},free_time=#{free_time},charging_unit=#{charging_unit}," +
            "unit_cost=#{unit_cost},update_time=now() where id=#{id}")
    void updateRule(Rule rule);

    @Select("select * from rule where rule_name=#{ruleName}")
    Rule findRuleByRuleName(String ruleName);
}
