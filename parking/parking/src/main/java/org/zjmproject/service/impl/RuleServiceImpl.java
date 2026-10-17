package org.zjmproject.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zjmproject.mapper.RuleMapper;
import org.zjmproject.pojo.Rule;
import org.zjmproject.service.RuleService;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class RuleServiceImpl implements RuleService {

    @Autowired
    private RuleMapper ruleMapper;

    @Override
    public void createRule(Rule rule) {
        ruleMapper.addRule(rule);
    }

    @Override
    public List<Rule> selectRule(String ruleName) {
        return ruleMapper.rules(ruleName);
    }

    @Override
    public Rule findRuleById(Integer ruleId) {
        return ruleMapper.findRuleById(ruleId);
    }

    @Override
    public void deleteRule(Integer ruleId) {
        ruleMapper.deleteRule(ruleId);
    }

    @Override
    public void updateRule( Rule rule) {
        rule.setUpdate_time(LocalDateTime.now());
        ruleMapper.updateRule(rule);
    }

    @Override
    public Rule findRuleByRuleName(String ruleName) {
        return ruleMapper.findRuleByRuleName(ruleName);
    }
}
