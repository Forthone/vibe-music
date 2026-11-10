package com.vibemusic.config;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vibemusic.controller.CommentController;
import com.vibemusic.mapper.CommentMapper;
import com.vibemusic.model.dto.CommentPageDTO;
import com.vibemusic.model.vo.CommentVO;
import com.vibemusic.result.PageResult;
import com.vibemusic.result.Result;
import com.vibemusic.service.ICommentService;
import com.vibemusic.service.impl.CommentServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CommentPaginationTest {
    @Test void keepsTotalOnEmptyLaterPageAndPassesPaginationToMapper() {
        var mapper=mock(CommentMapper.class);
        var service=new CommentServiceImpl();
        ReflectionTestUtils.setField(service,"commentMapper",mapper);
        when(mapper.getSongComments(any(),eq(7L))).thenAnswer(i -> {
            Page<CommentVO> page=i.getArgument(0);
            assertEquals(3,page.getCurrent()); assertEquals(20,page.getSize());
            page.setTotal(21);page.setRecords(List.of());return page;
        });
        var query=new CommentPageDTO();query.setSongId(7L);query.setPageNum(3);
        var data=service.getSongComments(query).getData();
        assertEquals(21,data.getTotal());assertTrue(data.getItems().isEmpty());
        verify(mapper,times(1)).getSongComments(any(),eq(7L));
    }
    @Test void validatesRequestsAndUsesDefaultPageSize() throws Exception {
        var service=mock(ICommentService.class);
        when(service.getSongComments(any())).thenAnswer(i -> {
            CommentPageDTO query=i.getArgument(0);
            assertEquals(1,query.getPageNum());assertEquals(20,query.getPageSize());
            return Result.success(new PageResult<CommentVO>(0L,List.of()));
        });
        var controller=new CommentController();
        ReflectionTestUtils.setField(controller,"commentService",service);
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/comment/song").param("songId","7")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.items").isEmpty());
        for(String[] pair:new String[][]{{"pageNum","0"},{"pageSize","101"},{"pageSize","0"}}) {
            mvc.perform(get("/comment/song").param("songId","7").param(pair[0],pair[1]))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/comment/song")).andExpect(status().isBadRequest());
        verify(service,times(1)).getSongComments(any());
    }
}