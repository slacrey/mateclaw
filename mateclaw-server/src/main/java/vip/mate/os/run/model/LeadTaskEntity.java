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
@TableName("mate_lead_task")
public class LeadTaskEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    private Long workspaceId;

    private String platform;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String keyword;

    @TableField(value = "sort_mode", updateStrategy = FieldStrategy.ALWAYS)
    private String sortMode;

    private String status;

    @TableField(value = "input_json", updateStrategy = FieldStrategy.ALWAYS)
    private String inputJson;

    @TableField(value = "summary_json", updateStrategy = FieldStrategy.ALWAYS)
    private String summaryJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
