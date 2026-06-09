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
@TableName("mate_browser_tab")
public class BrowserTabEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long browserSessionId;

    @TableField(value = "run_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long runId;

    private String tabRef;

    private String role;

    @TableField(value = "group_id", updateStrategy = FieldStrategy.ALWAYS)
    private String groupId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String url;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String title;

    private String status;

    @TableField(value = "opened_by_step_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long openedByStepId;

    @TableField(value = "last_seen_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastSeenAt;

    @TableField(value = "metadata_json", updateStrategy = FieldStrategy.ALWAYS)
    private String metadataJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
