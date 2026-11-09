package org.zjmproject.service;

import org.zjmproject.pojo.Rule;

import java.util.List;

public interface RuleService {
    void createRule(Rule rule);

    List<Rule> selectRule(String ruleName);

    Rule findRuleById(Integer ruleId);

    void deleteRule(Integer ruleId);

    void updateRule(Rule rule);

    Rule findRuleByRuleName(String ruleName);
}
