package com.vibemusic.config;

import com.vibemusic.mapper.*;
import com.vibemusic.model.dto.SongDTO;
import com.vibemusic.model.entity.UserFavorite;
import com.vibemusic.model.vo.SongDetailVO;
import com.vibemusic.service.IUserFavoriteService;
import com.vibemusic.service.ISongService;
import com.vibemusic.service.impl.UserFavoriteServiceImpl;
import com.vibemusic.service.impl.SongServiceImpl;
import com.vibemusic.util.JwtUtil;
import com.vibemusic.util.ThreadLocalUtil;
import org.junit.jupiter.api.*;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpServletRequest;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserScopedCacheTest {
    private AnnotationConfigApplicationContext context;
    @BeforeEach void open() { context = new AnnotationConfigApplicationContext(Config.class); }
    @AfterEach void close() { ThreadLocalUtil.remove(); context.close(); }

    @Test void favoriteCacheSeparatesUsersAndRetainsSameUserHits() {
        var service = context.getBean(IUserFavoriteService.class);
        var mapper = context.getBean(UserFavoriteMapper.class);
        var songs = context.getBean(SongMapper.class);
        when(songs.getUserFavoriteSongs(any(), anyLong(), any(), any(), any()))
                .thenAnswer(i -> {
                    Page<com.vibemusic.model.vo.SongVO> page = i.getArgument(0);
                    page.setTotal(37L);
                    page.setRecords(List.of());
                    return page;
                });
        var query = new SongDTO(); query.setPageNum(1); query.setPageSize(20);
        ThreadLocalUtil.set(Map.of("userId", 1L, "role", "ROLE_USER"));
        assertEquals(37L, service.getUserFavoriteSongs(query).getData().getTotal());
        service.getUserFavoriteSongs(query);
        ThreadLocalUtil.set(Map.of("userId", 2L, "role", "ROLE_USER"));
        service.getUserFavoriteSongs(query);
        verify(songs, times(1)).getUserFavoriteSongs(any(), eq(1L), any(), any(), any());
        verify(songs, times(1)).getUserFavoriteSongs(any(), eq(2L), any(), any(), any());
        verify(mapper, never()).getUserFavoriteSongIds(anyLong());
        ThreadLocalUtil.remove();
        assertThrows(IllegalStateException.class, () -> service.getUserFavoriteSongs(query));
    }

    @Test void detailSeparatesUsersAndGuestsAndDoesNotReadLegacyCache() {
        var service = context.getBean(ISongService.class);
        var songs = context.getBean(SongMapper.class);
        var favorites = context.getBean(UserFavoriteMapper.class);
        when(songs.getSongDetailById(10L)).thenAnswer(i -> new SongDetailVO());
        when(favorites.selectOne(any())).thenReturn(new UserFavorite()).thenReturn(null);
        context.getBean(CacheManager.class).getCache("songCache").put(10L, "legacy result");
        var a = request(1L); var b = request(2L); var guest = new MockHttpServletRequest();
        var first = service.getSongDetail(10L, a);
        assertSame(first, service.getSongDetail(10L, a));
        var second = service.getSongDetail(10L, b);
        var anonymous = service.getSongDetail(10L, guest);
        assertNotSame(first, second);
        assertNotSame(second, anonymous);
        assertNotEquals(first.getData().getLikeStatus(), second.getData().getLikeStatus());
        assertSame(anonymous, service.getSongDetail(10L, guest));
        verify(songs, times(3)).getSongDetailById(10L);
        var invalid = new MockHttpServletRequest(); invalid.addHeader("Authorization", "Bearer invalid");
        assertThrows(RuntimeException.class, () -> service.getSongDetail(10L, invalid));
    }

    private static MockHttpServletRequest request(long id) {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + JwtUtil.generateToken(Map.of("userId", id, "role", "ROLE_USER")));
        return request;
    }

    @Configuration @EnableCaching
    static class Config {
        @Bean CacheManager cacheManager() { return new ConcurrentMapCacheManager(); }
        @Bean UserScopedCacheKeyGenerator userScopedCacheKeyGenerator() { return new UserScopedCacheKeyGenerator(); }
        @Bean UserFavoriteMapper favoriteMapper() { return mock(UserFavoriteMapper.class); }
        @Bean SongMapper songMapper() { return mock(SongMapper.class); }
        @Bean PlaylistMapper playlistMapper() { return mock(PlaylistMapper.class); }
        @Bean com.vibemusic.service.MinioService minioService() { return mock(com.vibemusic.service.MinioService.class); }
        @Bean StyleMapper styleMapper() { return mock(StyleMapper.class); }
        @Bean GenreMapper genreMapper() { return mock(GenreMapper.class); }
        @Bean PlaylistBindingMapper playlistBindingMapper() { return mock(PlaylistBindingMapper.class); }
        @Bean CommentMapper commentMapper() { return mock(CommentMapper.class); }
        @Bean org.springframework.data.redis.core.RedisTemplate redisTemplate() { return mock(org.springframework.data.redis.core.RedisTemplate.class); }
        @Bean IUserFavoriteService favoriteService() { return new UserFavoriteServiceImpl(); }
        @Bean ISongService songService() { return new SongServiceImpl(); }
    }
}
