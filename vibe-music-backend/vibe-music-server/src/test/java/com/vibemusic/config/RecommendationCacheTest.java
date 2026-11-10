package com.vibemusic.config;

import com.vibemusic.mapper.SongMapper;
import com.vibemusic.mapper.UserFavoriteMapper;
import com.vibemusic.model.entity.UserFavorite;
import com.vibemusic.model.vo.SongVO;
import com.vibemusic.service.impl.SongServiceImpl;
import com.vibemusic.service.impl.UserFavoriteServiceImpl;
import com.vibemusic.util.JwtUtil;
import com.vibemusic.util.ThreadLocalUtil;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendationCacheTest {
    SongMapper songs; UserFavoriteMapper favorites;
    RedisTemplate redis; ValueOperations values;
    SongServiceImpl service; UserFavoriteServiceImpl favoriteService;
    @BeforeEach void setup() {
        songs=mock(SongMapper.class); favorites=mock(UserFavoriteMapper.class);
        redis=mock(RedisTemplate.class); values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service=new SongServiceImpl(); favoriteService=new UserFavoriteServiceImpl();
        ReflectionTestUtils.setField(service,"songMapper",songs);
        ReflectionTestUtils.setField(service,"userFavoriteMapper",favorites);
        ReflectionTestUtils.setField(service,"redisTemplate",redis);
        ReflectionTestUtils.setField(favoriteService,"userFavoriteMapper",favorites);
        ReflectionTestUtils.setField(favoriteService,"redisTemplate",redis);
    }
    @AfterEach void cleanup() { ThreadLocalUtil.remove(); }
    MockHttpServletRequest request(long id) {
        var request=new MockHttpServletRequest();
        request.addHeader("Authorization","Bearer "+JwtUtil.generateToken(Map.of("userId",id,"role","ROLE_USER")));
        return request;
    }
    List<SongVO> candidates(int size) {
        return IntStream.range(0,size).mapToObj(i -> {var song=new SongVO();song.setSongId((long)i);return song;}).toList();
    }
    @Test void hitSkipsDatabaseAndDoesNotMutateCachedList() {
        var cached=candidates(25);
        when(values.get("recommended_songs:v2:1")).thenReturn(cached);
        var result=service.getRecommendedSongs(request(1));
        assertEquals(20,result.getData().size());
        assertEquals(20,result.getData().stream().map(SongVO::getSongId).distinct().count());
        assertEquals(0L,cached.get(0).getSongId());
        verifyNoInteractions(songs,favorites);
    }
    @Test void missBuildsCacheWithTtl() {
        when(favorites.getFavoriteSongIdsByUserId(2L)).thenReturn(List.of(90L));
        when(songs.getFavoriteSongStyles(List.of(90L))).thenReturn(List.of(3L,3L));
        var cached=candidates(25);
        when(songs.getRecommendedSongsByStyles(List.of(3L),List.of(90L),80)).thenReturn(cached);
        assertEquals(20,service.getRecommendedSongs(request(2)).getData().size());
        verify(values).set("recommended_songs:v2:2",cached,30,TimeUnit.MINUTES);
        verify(values).get("recommended_songs:v2:2");
    }
    @Test void noFavoritesFallsBackWithoutCaching() {
        when(favorites.getFavoriteSongIdsByUserId(1L)).thenReturn(List.of());
        when(songs.getRandomSongsWithArtist()).thenReturn(candidates(20));
        assertEquals(20,service.getRecommendedSongs(request(1)).getData().size());
        verify(values,never()).set(any(),any(),anyLong(),any());
        verify(songs,never()).getFavoriteSongStyles(any());
    }
    @Test void shortHitFillsWithoutDuplicatesOrPreferenceQueries() {
        when(values.get("recommended_songs:v2:1")).thenReturn(candidates(2));
        var extras=new ArrayList<>(candidates(20)); extras.add(extras.get(3));
        when(songs.getRandomSongsWithArtist()).thenReturn(extras);
        var result=service.getRecommendedSongs(request(1)).getData();
        assertEquals(20,result.size());
        assertEquals(20,result.stream().map(SongVO::getSongId).distinct().count());
        verifyNoInteractions(favorites);
        verify(songs,never()).getFavoriteSongStyles(any());
    }
    @Test void successfulFavoriteChangesInvalidateOnlyCurrentUserRecommendations() {
        ThreadLocalUtil.set(Map.of("userId",1L,"role","ROLE_USER"));
        when(favorites.selectCount(any())).thenReturn(0L);
        when(favorites.insert(any(UserFavorite.class))).thenReturn(1);
        when(favorites.delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        assertEquals(0,favoriteService.collectSong(10L).getCode());
        assertEquals(0,favoriteService.cancelCollectSong(10L).getCode());
        verify(redis,times(2)).delete("recommended_songs:v2:1");
        verify(redis,never()).delete("recommended_songs:v2:2");
    }
    @Test void unsuccessfulFavoriteChangesDoNotInvalidateRecommendations() {
        ThreadLocalUtil.set(Map.of("userId",1L,"role","ROLE_USER"));
        when(favorites.selectCount(any())).thenReturn(1L);
        assertEquals(1,favoriteService.collectSong(10L).getCode());
        assertEquals(1,favoriteService.cancelCollectSong(10L).getCode());
        verifyNoInteractions(redis);
    }
}
