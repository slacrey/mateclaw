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
@TableName("mate_lead_comment")
public class LeadCommentEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long taskId;

    private Long runId;

    @TableField(value = "video_key", updateStrategy = FieldStrategy.ALWAYS)
    private String videoKey;

    @TableField(value = "comment_key", updateStrategy = FieldStrategy.ALWAYS)
    private String commentKey;

    @TableField(value = "parent_comment_key", updateStrategy = FieldStrategy.ALWAYS)
    private String parentCommentKey;

    @TableField(value = "author_name", updateStrategy = FieldStrategy.ALWAYS)
    private String authorName;

    @TableField(value = "author_profile_url", updateStrategy = FieldStrategy.ALWAYS)
    private String authorProfileUrl;

    @TableField(value = "author_avatar_url", updateStrategy = FieldStrategy.ALWAYS)
    private String authorAvatarUrl;

    @TableField(value = "comment_text", updateStrategy = FieldStrategy.ALWAYS)
    private String commentText;

    @TableField(value = "like_count", updateStrategy = FieldStrategy.ALWAYS)
    private Integer likeCount;

    @TableField(value = "reply_count", updateStrategy = FieldStrategy.ALWAYS)
    private Integer replyCount;

    private Boolean matched;

    @TableField(value = "match_score", updateStrategy = FieldStrategy.ALWAYS)
    private Double matchScore;

    @TableField(value = "match_reason", updateStrategy = FieldStrategy.ALWAYS)
    private String matchReason;

    @TableField(value = "metadata_json", updateStrategy = FieldStrategy.ALWAYS)
    private String metadataJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
