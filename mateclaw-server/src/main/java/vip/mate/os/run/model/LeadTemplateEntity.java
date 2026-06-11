package vip.mate.os.run.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mate_lead_template")
public class LeadTemplateEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;

    private String platform;

    private String name;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String keyword;

    @TableField(value = "sort_mode", updateStrategy = FieldStrategy.ALWAYS)
    private String sortMode;

    @TableField(value = "video_limit")
    private Integer videoLimit;

    @TableField(value = "comment_match_rule", updateStrategy = FieldStrategy.ALWAYS)
    private String commentMatchRule;

    @TableField(value = "match_rules_json", updateStrategy = FieldStrategy.ALWAYS)
    private String matchRulesJson;

    @TableField(value = "dm_draft", updateStrategy = FieldStrategy.ALWAYS)
    private String dmDraft;

    private Boolean engage;

    private Boolean sendDm;

    @TableField(value = "created_by", updateStrategy = FieldStrategy.ALWAYS)
    private Long createdBy;

    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
