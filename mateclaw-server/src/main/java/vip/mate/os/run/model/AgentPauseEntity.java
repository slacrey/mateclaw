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
@TableName("mate_agent_pause")
public class AgentPauseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    @TableField(value = "step_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long stepId;

    private String pauseKind;

    private String pauseToken;

    private String status;

    @TableField(value = "external_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String externalRef;

    @TableField(value = "resume_deadline", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime resumeDeadline;

    @TableField(value = "resume_payload_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String resumePayloadRef;

    private LocalDateTime pausedAt;

    @TableField(value = "resumed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime resumedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
