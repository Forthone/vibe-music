package com.vibemusic.model.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class CommentPageDTO {
    @NotNull @Positive private Long songId;
    @NotNull @Min(1) private Integer pageNum = 1;
    @NotNull @Min(1) @Max(100) private Integer pageSize = 20;
}
