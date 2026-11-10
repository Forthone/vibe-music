package com.vibemusic.config;

import com.vibemusic.mapper.SongMapper;
import com.vibemusic.mapper.UserFavoriteMapper;
import com.vibemusic.model.entity.UserFavorite;
import com.vibemusic.model.vo.SongVO;
import com.vibemusic.service.impl.SongServiceImpl;
import com.vibemusic.service.impl.UserFavoriteServiceImpl;
import com.vibemusic.util.JwtUtil;
import com.vibemusic.util.ThreadLocalUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="recommendation.redis", matches="true")
class RecommendationRedisTest {
    @Test void redisRoundTripAndFavoriteInvalidation() {
        long userId=System.currentTimeMillis();
        String key="recommended_songs:v2:"+userId;
        var connection=new LettuceConnectionFactory("127.0.0.1",26379);
        connection.afterPropertiesSet();
        var redis=new RedisConfig().redisTemplate(connection);
        redis.afterPropertiesSet();
        try {
            var songs=mock(SongMapper.class);
            var favorites=mock(UserFavoriteMapper.class);
            var candidates=new ArrayList<SongVO>();
            for(long id=1;id<=25;id++) { var song=new SongVO();song.setSongId(id);candidates.add(song); }
            when(favorites.getFavoriteSongIdsByUserId(userId)).thenReturn(List.of(90L));
            when(songs.getFavoriteSongStyles(List.of(90L))).thenReturn(List.of(3L));
            when(songs.getRecommendedSongsByStyles(List.of(3L),List.of(90L),80)).thenReturn(candidates);
            var service=new SongServiceImpl();
            ReflectionTestUtils.setField(service,"songMapper",songs);
            ReflectionTestUtils.setField(service,"userFavoriteMapper",favorites);
            ReflectionTestUtils.setField(service,"redisTemplate",redis);
            var request=new MockHttpServletRequest();
            request.addHeader("Authorization","Bearer "+JwtUtil.generateToken(Map.of("userId",userId,"role","ROLE_USER")));
            assertEquals(20,service.getRecommendedSongs(request).getData().size());
            assertTrue(redis.getExpire(key)>0 && redis.getExpire(key)<=1800);
            clearInvocations(songs,favorites);
            assertEquals(20,service.getRecommendedSongs(request).getData().size());
            verifyNoInteractions(songs,favorites);
            var favoriteService=new UserFavoriteServiceImpl();
            ReflectionTestUtils.setField(favoriteService,"userFavoriteMapper",favorites);
            ReflectionTestUtils.setField(favoriteService,"redisTemplate",redis);
            when(favorites.selectCount(any())).thenReturn(0L);
            when(favorites.insert(any(UserFavorite.class))).thenReturn(1);
            ThreadLocalUtil.set(Map.of("userId",userId,"role","ROLE_USER"));
            assertEquals(0,favoriteService.collectSong(90L).getCode());
            assertNull(redis.opsForValue().get(key));
        } finally {
            redis.delete(key);
            ThreadLocalUtil.remove();
            connection.destroy();
        }
    }
}