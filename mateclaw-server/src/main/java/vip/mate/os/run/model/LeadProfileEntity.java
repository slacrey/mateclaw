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
@TableName("mate_lead_profile")
public class LeadProfileEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long taskId;

    private Long runId;

    private String platform;

    private String profileUrl;

    @TableField(value = "display_name", updateStrategy = FieldStrategy.ALWAYS)
    private String displayName;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String handle;

    @TableField(value = "avatar_url", updateStrategy = FieldStrategy.ALWAYS)
    private String avatarUrl;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String bio;

    @TableField(value = "raw_json", updateStrategy = FieldStrategy.ALWAYS)
    private String rawJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
