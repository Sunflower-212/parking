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
    @Autowired private java.time.Clock clock;

    @Autowired private org.zjmproject.service.ReadCache cache;
    @Autowired
    private RuleMapper ruleMapper;

    @Override
    public void createRule(Rule rule) {
        ruleMapper.addRule(rule);cache.invalidateAfterCommit("rules");
    }

    @Override
    public List<Rule> selectRule(String ruleName) {
        return ruleName!=null && !ruleName.isBlank()?ruleMapper.rules(ruleName):cache.get("rules","all",java.time.Duration.ofMinutes(10),new com.fasterxml.jackson.core.type.TypeReference<List<Rule>>(){},()->ruleMapper.rules(null));
    }

    @Override
    public Rule findRuleById(Integer ruleId) {
        return ruleMapper.findRuleById(ruleId);
    }

    @Override
    public void deleteRule(Integer ruleId) {
        ruleMapper.deleteRule(ruleId);cache.invalidateAfterCommit("rules");
    }

    @Override
    public void updateRule( Rule rule) {
        rule.setUpdate_time(LocalDateTime.now(clock));
        ruleMapper.updateRule(rule);cache.invalidateAfterCommit("rules");
    }

    @Override
    public Rule findRuleByRuleName(String ruleName) {
        return ruleMapper.findRuleByRuleName(ruleName);
    }
}
