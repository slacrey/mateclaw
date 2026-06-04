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
@TableName("mate_lead_engagement")
public class LeadEngagementEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long taskId;

    private Long runId;

    @TableField(value = "profile_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long profileId;

    @TableField(value = "comment_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long commentId;

    private String engagementType;

    private String status;

    @TableField(value = "draft_text", updateStrategy = FieldStrategy.ALWAYS)
    private String draftText;

    @TableField(value = "failure_code", updateStrategy = FieldStrategy.ALWAYS)
    private String failureCode;

    @TableField(value = "failure_message", updateStrategy = FieldStrategy.ALWAYS)
    private String failureMessage;

    @TableField(value = "evidence_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String evidenceRef;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
