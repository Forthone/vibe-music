package com.vibemusic.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vibemusic.model.vo.CommentVO;
import org.apache.ibatis.annotations.Param;

import com.vibemusic.model.entity.Comment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
    IPage<CommentVO> getSongComments(
            Page<CommentVO> page,
            @Param("songId") Long songId);


}
