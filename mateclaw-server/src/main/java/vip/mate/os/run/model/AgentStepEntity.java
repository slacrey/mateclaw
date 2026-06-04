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
@TableName("mate_agent_step")
public class AgentStepEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    @TableField(value = "parent_step_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long parentStepId;

    private String stepKey;

    @TableField(value = "parent_step_key", updateStrategy = FieldStrategy.ALWAYS)
    private String parentStepKey;

    private String stepType;

    private String status;

    private Integer attempt;

    @TableField(value = "idempotency_key", updateStrategy = FieldStrategy.ALWAYS)
    private String idempotencyKey;

    @TableField(value = "policy_tags", updateStrategy = FieldStrategy.ALWAYS)
    private String policyTags;

    @TableField(value = "input_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String inputRef;

    @TableField(value = "output_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String outputRef;

    @TableField(value = "checkpoint_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String checkpointRef;

    @TableField(value = "failure_code", updateStrategy = FieldStrategy.ALWAYS)
    private String failureCode;

    @TableField(value = "failure_message", updateStrategy = FieldStrategy.ALWAYS)
    private String failureMessage;

    @TableField(value = "duration_ms", updateStrategy = FieldStrategy.ALWAYS)
    private Long durationMs;

    @TableField(value = "token_input", updateStrategy = FieldStrategy.ALWAYS)
    private Integer tokenInput;

    @TableField(value = "token_output", updateStrategy = FieldStrategy.ALWAYS)
    private Integer tokenOutput;

    @TableField(value = "started_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime startedAt;

    @TableField(value = "completed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime completedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
