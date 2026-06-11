package vip.mate.lead.douyin.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.lead.douyin.model.CommentMatchRule;
import vip.mate.os.run.model.LeadTemplateEntity;
import vip.mate.os.run.repository.LeadTemplateMapper;

import java.util.List;

@Service
public class DouyinLeadTemplateService {

    private final LeadTemplateMapper templateMapper;
    private final ObjectMapper objectMapper;

    public DouyinLeadTemplateService(LeadTemplateMapper templateMapper, ObjectMapper objectMapper) {
        this.templateMapper = templateMapper;
        this.objectMapper = objectMapper;
    }

    public List<DouyinLeadTemplateDTO> list(Long workspaceId) {
        return templateMapper.selectList(new LambdaQueryWrapper<LeadTemplateEntity>()
                        .eq(LeadTemplateEntity::getWorkspaceId, workspaceId)
                        .eq(LeadTemplateEntity::getPlatform, "douyin")
                        .eq(LeadTemplateEntity::getDeleted, 0)
                        .orderByDesc(LeadTemplateEntity::getUpdateTime)
                        .orderByDesc(LeadTemplateEntity::getCreateTime))
                .stream()
                .map(row -> DouyinLeadTemplateDTO.from(row, objectMapper))
                .toList();
    }

    @Transactional
    public DouyinLeadTemplateDTO create(Long workspaceId, Long createdBy, DouyinLeadTemplateRequest request) {
        LeadTemplateEntity row = new LeadTemplateEntity();
        row.setWorkspaceId(workspaceId);
        row.setPlatform("douyin");
        row.setCreatedBy(createdBy);
        row.setDeleted(0);
        apply(row, request);
        templateMapper.insert(row);
        return DouyinLeadTemplateDTO.from(row, objectMapper);
    }

    @Transactional
    public DouyinLeadTemplateDTO update(Long workspaceId, Long id, DouyinLeadTemplateRequest request) {
        LeadTemplateEntity row = requireTemplate(workspaceId, id);
        apply(row, request);
        templateMapper.updateById(row);
        return DouyinLeadTemplateDTO.from(row, objectMapper);
    }

    @Transactional
    public void delete(Long workspaceId, Long id) {
        LeadTemplateEntity row = requireTemplate(workspaceId, id);
        row.setDeleted(1);
        templateMapper.updateById(row);
    }

    private LeadTemplateEntity requireTemplate(Long workspaceId, Long id) {
        LeadTemplateEntity row = templateMapper.selectOne(new LambdaQueryWrapper<LeadTemplateEntity>()
                .eq(LeadTemplateEntity::getId, id)
                .eq(LeadTemplateEntity::getWorkspaceId, workspaceId)
                .eq(LeadTemplateEntity::getPlatform, "douyin")
                .eq(LeadTemplateEntity::getDeleted, 0)
                .last("LIMIT 1"));
        if (row == null) {
            throw new MateClawException("err.lead.template.not_found", "Lead template not found");
        }
        return row;
    }

    private void apply(LeadTemplateEntity row, DouyinLeadTemplateRequest request) {
        if (request == null) {
            throw new MateClawException("err.lead.template.request_required", "Template payload is required");
        }
        row.setName(request.normalizedName());
        row.setKeyword(request.normalizedKeyword());
        row.setSortMode(request.normalizedSort());
        row.setVideoLimit(request.normalizedVideoLimit());
        row.setCommentMatchRule("");
        row.setMatchRulesJson(json(request.normalizedMatchRules()));
        row.setDmDraft(request.normalizedDmDraft());
        row.setEngage(request.normalizedEngage());
        row.setSendDm(request.normalizedSendDm());
    }

    private String json(List<CommentMatchRule> rules) {
        try {
            return objectMapper.writeValueAsString(rules == null ? List.of() : rules);
        } catch (Exception e) {
            throw new MateClawException("err.lead.template.match_rules_invalid", "Match rules are invalid");
        }
    }
}
